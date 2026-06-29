package org.example.project.scheduler.sync

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Instant
import kotlinx.serialization.json.Json
import org.example.project.scheduler.persistence.PersistedSnapshot
import org.example.project.scheduler.persistence.SleepGapRecord
import org.example.project.scheduler.persistence.SyncMeta
import org.example.project.scheduler.persistence.SyncMetaStore
import org.example.project.scheduler.platform.DeviceKind
import org.example.project.time.SystemAppClock

/** Coarse status for a sync status indicator. */
sealed interface SyncState {
    /** No signed-in user; sync is dormant. */
    data object SignedOut : SyncState

    /** Signed in, nothing in flight. */
    data object Idle : SyncState

    /** A reconcile (pull/push) is running. */
    data object Syncing : SyncState

    /** The last attempt failed (offline, auth, server). Local state is unaffected; sync retries later. */
    data class Error(val message: String) : SyncState
}

/**
 * Cross-device sync for the scheduler (PRD §5, offline-first). Local SQLite remains the source of truth;
 * this engine mirrors the whole [PersistedSnapshot] to/from the Supabase `scheduler_snapshot` row through
 * [RemoteSnapshotClient], versioned by an optimistic-concurrency `revision`.
 *
 * **Phase 1 conflict policy = whole-document last-write-wins.** [reconcile] pushes local changes only when
 * the remote still sits at the revision this device last saw; if the remote has advanced, the remote wins
 * and is applied locally (any local change made since the last successful push is dropped, and a
 * [remoteApplied] event fires so the UI can tell the user it reloaded). Field-level merge of concurrent
 * offline edits is Phase 2 (per-entity HLC registers); [SyncMeta.deviceId] is the future tie-breaker.
 *
 * The engine is transport-only: [localSnapshot] supplies the current state to push and [applyRemote]
 * installs a pulled one — both wired by [org.example.project.scheduler.ui.TaskSchedulerViewModel].
 */
@OptIn(ExperimentalUuidApi::class)
class SchedulerSyncEngine(
    private val client: RemoteSnapshotClient,
    private val metaStore: SyncMetaStore,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : PresenceGateway, SleepGapGateway {
    private val mutex = Mutex()
    private var session: SupabaseSession? = null

    // Late-bound by the owner (the ViewModel) to break the engine<->ViewModel construction cycle:
    // [localSnapshot] reads the current state to push, [applyRemote] installs a pulled one.
    private var localSnapshot: (() -> PersistedSnapshot)? = null
    private var applyRemote: ((PersistedSnapshot) -> Unit)? = null

    /** Wires the local-state provider and the pulled-snapshot sink. Call once before [reconcile]. */
    fun bind(localSnapshot: () -> PersistedSnapshot, applyRemote: (PersistedSnapshot) -> Unit) {
        this.localSnapshot = localSnapshot
        this.applyRemote = applyRemote
    }

    private val _state = MutableStateFlow<SyncState>(SyncState.SignedOut)
    val state: StateFlow<SyncState> = _state.asStateFlow()

    /** Emits when a remote snapshot was pulled and applied over the local state (LWW / first load). */
    private val _remoteApplied = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val remoteApplied: SharedFlow<Unit> = _remoteApplied.asSharedFlow()

    /** Ensures a [SyncMeta] row exists (allocating a stable device id once), returning it. */
    private fun meta(): SyncMeta =
        metaStore.loadSyncMeta()
            ?: SyncMeta(deviceId = Uuid.random().toString()).also(metaStore::saveSyncMeta)

    val isSignedIn: Boolean get() = meta().userId != null

    /** Restores a cached session (if any) from a previous run; call once at startup before [reconcile]. */
    fun restoreSession() {
        val m = meta()
        if (m.accessToken != null && m.refreshToken != null && m.userId != null) {
            session = SupabaseSession(m.accessToken, m.refreshToken, m.userId)
            _state.value = SyncState.Idle
        }
    }

    suspend fun signUp(email: String, password: String) = authenticate(email) { client.signUp(email, password) }

    suspend fun signIn(email: String, password: String) = authenticate(email) { client.signIn(email, password) }

    private suspend fun authenticate(email: String, call: suspend () -> SupabaseSession) =
        mutex.withLock {
            _state.value = SyncState.Syncing
            try {
                val s = call()
                session = s
                persistSession(s, email)
                _state.value = SyncState.Idle
            } catch (e: SupabaseException) {
                _state.value = SyncState.Error(e.message ?: "auth failed")
                throw e
            }
        }

    /** Signs out: drops the cached session locally. Does not delete the remote snapshot. */
    fun signOut() {
        session = null
        val m = meta()
        metaStore.saveSyncMeta(m.copy(accessToken = null, refreshToken = null, userId = null))
        _state.value = SyncState.SignedOut
    }

    /** Marks local state as having unpushed changes; the next [reconcile] will push it. */
    fun markDirty() {
        val m = meta()
        if (!m.dirty) metaStore.saveSyncMeta(m.copy(dirty = true))
    }

    /**
     * Pull-or-push reconcile against the remote (Phase 1 whole-document LWW). Safe to call repeatedly —
     * on startup, on window focus, and after a debounced save. No-op (and never throws) when signed out;
     * on network/server failure it records [SyncState.Error] and leaves local state untouched.
     */
    suspend fun reconcile() {
        mutex.withLock {
            val current = session ?: run { _state.value = SyncState.SignedOut; return }
            _state.value = SyncState.Syncing
            try {
                runReconcile(current)
                _state.value = SyncState.Idle
            } catch (e: SupabaseException) {
                _state.value = SyncState.Error(e.message ?: "sync failed")
            } catch (e: Exception) {
                // Offline / transport errors: stay calm, keep local state, retry on the next trigger.
                _state.value = SyncState.Error(e.message ?: "offline")
            }
        }
    }

    private suspend fun runReconcile(session: SupabaseSession) {
        var m = meta()
        val remote = withAuth(session) { client.fetch(it) }

        when {
            // First device for this account: seed the remote from local.
            remote == null -> {
                val ok = withAuth(session) { client.insert(it, payload()) }
                if (ok) {
                    setMeta(m.copy(lastKnownRevision = 1, dirty = false))
                } else {
                    // Lost the race; another device inserted first — re-fetch and apply it.
                    withAuth(session) { client.fetch(it) }?.let { pull(it) }
                }
            }
            // Remote advanced past what we last saw: remote wins (LWW). Drop any local unpushed change.
            remote.revision > m.lastKnownRevision -> pull(remote)
            // We have unpushed local changes and the remote is still where we left it: push them.
            m.dirty -> {
                val ok = withAuth(session) { client.update(it, payload(), m.lastKnownRevision) }
                if (ok) {
                    setMeta(meta().copy(lastKnownRevision = m.lastKnownRevision + 1, dirty = false))
                } else {
                    // The remote moved between fetch and patch; pull the newer revision.
                    withAuth(session) { client.fetch(it) }?.let { pull(it) }
                }
            }
            // In sync, nothing pending.
            else -> Unit
        }
    }

    private fun pull(remote: RemoteSnapshot) {
        val snapshot = json.decodeFromString<PersistedSnapshot>(remote.payload)
        checkNotNull(applyRemote) { "SchedulerSyncEngine.bind() not called" }(snapshot)
        setMeta(meta().copy(lastKnownRevision = remote.revision, dirty = false))
        _remoteApplied.tryEmit(Unit)
    }

    private fun payload(): String =
        json.encodeToString(checkNotNull(localSnapshot) { "SchedulerSyncEngine.bind() not called" }())

    private fun persistSession(s: SupabaseSession, email: String) =
        setMeta(meta().copy(accessToken = s.accessToken, refreshToken = s.refreshToken, userId = s.userId, email = email))

    private fun setMeta(m: SyncMeta) = metaStore.saveSyncMeta(m)

    /**
     * Runs [block] with the current bearer token, transparently refreshing once on a 401 (the access token
     * expired). A failed refresh propagates so [reconcile] surfaces it as an error and keeps local state.
     */
    private suspend fun <T> withAuth(session: SupabaseSession, block: suspend (SupabaseSession) -> T): T =
        try {
            block(session)
        } catch (e: SupabaseException) {
            if (e.status != 401) throw e
            val refreshed = client.refresh(session.refreshToken)
            this.session = refreshed
            setMeta(meta().copy(accessToken = refreshed.accessToken, refreshToken = refreshed.refreshToken))
            block(refreshed)
        }

    // ---- PRD §15 cross-device presence (PresenceGateway) ----
    //
    // These run outside [mutex] (the reconcile lock): presence is an independent, best-effort side channel,
    // so a heartbeat/peer-query never blocks — or is blocked by — a snapshot reconcile.

    override val signedIn: Boolean get() = session != null

    override suspend fun publishPresence(kind: DeviceKind, screenActive: Boolean) {
        val current = session ?: return
        val deviceId = meta().deviceId
        runCatching { withAuth(current) { client.upsertPresence(it, deviceId, kind.name.lowercase(), screenActive) } }
    }

    /** Fail-open convenience: a transport failure (`null`) counts as "no active peer". */
    suspend fun activePeersExist(staleMillis: Long): Boolean = activePeersExistOrNull(staleMillis) ?: false

    override suspend fun activePeersExistOrNull(staleMillis: Long): Boolean? {
        val current = session ?: return false
        val selfId = meta().deviceId
        // A transport failure returns null (unknown) so the caller can retry, rather than masquerading as "no peer".
        val rows = runCatching { withAuth(current) { client.fetchPresence(it) } }.getOrNull() ?: return null
        val now = SystemAppClock.nowMillis()
        return rows.any { row ->
            row.deviceId != selfId &&
                row.screenActive &&
                run {
                    // A future timestamp (server clock ahead of ours) is the freshest possible, so allow it.
                    val updated = runCatching { Instant.parse(row.updatedAt).toEpochMilliseconds() }.getOrNull()
                    updated != null && now - updated < staleMillis
                }
        }
    }

    // ---- PRD §15 device-sleep gaps (SleepGapGateway) ----
    //
    // Like presence, these run outside [mutex]: gaps are an independent per-row side channel, never blocking
    // — or blocked by — a whole-document snapshot reconcile.

    override val deviceId: String get() = meta().deviceId

    override suspend fun pushSleepGaps(records: List<SleepGapRecord>) {
        val current = session ?: return
        if (records.isEmpty()) return
        val rows = records.map { SleepGapRow(it.deviceId, it.startMillis, it.endMillis, it.recordedAtMillis) }
        runCatching { withAuth(current) { client.upsertSleepGaps(it, rows) } }
    }

    override suspend fun fetchSleepGaps(): List<SleepGapRecord>? {
        val current = session ?: return emptyList()
        val rows = runCatching { withAuth(current) { client.fetchSleepGaps(it) } }.getOrNull() ?: return null
        return rows.map { SleepGapRecord(it.deviceId, it.sleepStart, it.sleepEnd, it.recordedAt) }
    }
}
