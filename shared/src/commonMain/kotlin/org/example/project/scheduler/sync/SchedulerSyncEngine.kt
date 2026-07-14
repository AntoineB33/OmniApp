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
import kotlinx.serialization.json.Json
import org.example.project.scheduler.persistence.PersistedSnapshot
import org.example.project.scheduler.persistence.SyncMeta
import org.example.project.scheduler.persistence.SyncMetaStore
import org.example.project.scheduler.platform.Diagnostics

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
) : PauseCueGateway {
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

    /**
     * The unified sync moments: emits after EVERY [reconcile] — startup, login completion, the manual sync
     * button, and the debounced-change flush all funnel through [reconcile], so collecting this runs the
     * side channels (push own active sessions / pull derived pauses / sleep gaps) at exactly the same
     * moments the snapshot syncs. Emits even on a signed-out or failed reconcile: the side channels have
     * their own signed-out fallbacks (e.g. local pause derivation) and swallow transport errors themselves.
     * `replay = 1` so a collector that subscribes after the startup reconcile finished (engine start races
     * the async auto-login) still observes that moment instead of silently missing it.
     */
    private val _syncMoments = MutableSharedFlow<Unit>(replay = 1)
    val syncMoments: SharedFlow<Unit> = _syncMoments.asSharedFlow()

    /**
     * Every Supabase HTTP call the transport made (see [SupabaseUsageEvent]) — forwarded straight from the
     * [RemoteSnapshotClient], which is where every request funnels through. The ViewModel collects this into the
     * local-only History-window "Supabase usage" column; it is a per-device diagnostic and syncs nothing.
     */
    val supabaseUsage: SharedFlow<SupabaseUsageEvent> get() = client.usageEvents

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
            Diagnostics.log("restored persisted session (${m.email ?: "unknown email"})")
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
                recordLogoutBaseline(s)
                _state.value = SyncState.Idle
                Diagnostics.log("signed in as $email")
            } catch (e: SupabaseException) {
                _state.value = SyncState.Error(e.message ?: "auth failed")
                throw e
            }
        }

    /**
     * Signs out: drops the cached session locally. Does not delete the remote snapshot. This is only the
     * local drop — the user-initiated sign-out's farewell pull/push happens in the caller
     * ([org.example.project.scheduler.ui.TaskSchedulerViewModel.signOut] reconciles first), so the remote
     * force-logout path in [runReconcile] can keep using this to sign out while pushing nothing.
     */
    fun signOut() {
        Diagnostics.log("signed out")
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
        try {
            mutex.withLock {
                val current = session ?: run { _state.value = SyncState.SignedOut; return }
                _state.value = SyncState.Syncing
                try {
                    runReconcile(current)
                    // runReconcile may have signed us out (remote force-logout); don't clobber SignedOut with Idle.
                    if (session != null) _state.value = SyncState.Idle
                } catch (e: SupabaseException) {
                    _state.value = SyncState.Error(e.message ?: "sync failed")
                } catch (e: Exception) {
                    // Offline / transport errors: stay calm, keep local state, retry on the next trigger.
                    _state.value = SyncState.Error(e.message ?: "offline")
                }
            }
        } finally {
            // Unified sync moment (see [syncMoments]): fires on every outcome — including the signed-out
            // early return above — so the side channels always run (or locally fall back) at this moment.
            _syncMoments.tryEmit(Unit)
        }
    }

    private suspend fun runReconcile(session: SupabaseSession) {
        // Remote force-logout (account-empty script, scripts/account1-empty-and-open.bat): if the account's
        // server-side `account_logout` marker advanced past the baseline this device recorded at login, drop the
        // session locally and push NOTHING — otherwise a still-running device would re-seed the snapshot the
        // empty just deleted. `null` baseline = unknown (pre-feature / failed login fetch), which still logs out
        // if a marker exists; `0` = seen with no marker at login. See CLAUDE.md "Account scripts".
        if (isRemotelyLoggedOut(session)) {
            Diagnostics.log("remote force-logout detected — signing out, pushing nothing")
            signOut()
            return
        }
        var m = meta()
        val remote = withAuth(session) { client.fetch(it) }

        when {
            // First device for this account: seed the remote from local.
            remote == null -> {
                val ok = withAuth(session) { client.insert(it, payload()) }
                if (ok) {
                    Diagnostics.log("reconcile: seeded remote snapshot (revision 1)")
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
                    Diagnostics.log("reconcile: pushed local changes (revision ${m.lastKnownRevision + 1})")
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
        Diagnostics.log("reconcile: pulled remote snapshot (revision ${remote.revision})")
        val snapshot = json.decodeFromString<PersistedSnapshot>(remote.payload)
        checkNotNull(applyRemote) { "SchedulerSyncEngine.bind() not called" }(snapshot)
        setMeta(meta().copy(lastKnownRevision = remote.revision, dirty = false))
        _remoteApplied.tryEmit(Unit)
    }

    private fun payload(): String =
        json.encodeToString(checkNotNull(localSnapshot) { "SchedulerSyncEngine.bind() not called" }())

    private fun persistSession(s: SupabaseSession, email: String) =
        setMeta(meta().copy(accessToken = s.accessToken, refreshToken = s.refreshToken, userId = s.userId, email = email))

    /**
     * Records the account's current `account_logout.logout_at` (epoch millis) as this login's baseline so a
     * LATER server-side force-logout signs this device out but this fresh login itself does not. On a successful
     * fetch the marker (or `0` when none exists) is stored; on a transport failure the baseline is left `null`
     * (unknown) — the fetch shares the moment of a just-succeeded sign-in, so this is rare.
     */
    private suspend fun recordLogoutBaseline(s: SupabaseSession) {
        val fetched = runCatching { withAuth(s) { client.fetchLogoutAt(it) } }
        if (fetched.isSuccess) setMeta(meta().copy(acknowledgedLogoutAtMillis = fetched.getOrNull() ?: 0L))
    }

    /**
     * True when the account's server-side logout marker has advanced past this session's login baseline.
     * Fails **open**: a missing `account_logout` table (migration not yet applied) or any transport error
     * counts as "not logged out" so it can never wedge normal syncing — the logout is simply honored on a
     * later reconcile once the fetch succeeds. `null` (no row) likewise means no logout.
     */
    private suspend fun isRemotelyLoggedOut(session: SupabaseSession): Boolean {
        val logoutAt = runCatching { withAuth(session) { client.fetchLogoutAt(it) } }.getOrNull() ?: return false
        val baseline = meta().acknowledgedLogoutAtMillis
        return baseline == null || logoutAt > baseline
    }

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
            block(refreshSession(session))
        }

    // Serializes token refreshes independently of [mutex]: reconcile runs under [mutex], but presence/gap/cue
    // calls run off it, so two of them can hit an expired access token at the same tick.
    private val refreshMutex = Mutex()

    /**
     * Exchanges an expired [stale] session for a fresh one. Supabase rotates refresh tokens on every use and
     * rejects the previous one with `400 refresh token not found`, so two concurrent 401s must not each spend
     * the same token. This serializes refreshes and, if another caller — or another process sharing this
     * account's local DB — already rotated the token (the persisted refresh token no longer matches [stale]),
     * adopts the persisted session instead of refreshing again with the already-consumed token.
     */
    private suspend fun refreshSession(stale: SupabaseSession): SupabaseSession =
        refreshMutex.withLock {
            val persisted = metaStore.loadSyncMeta()
            if (persisted?.refreshToken != null && persisted.accessToken != null && persisted.userId != null &&
                persisted.refreshToken != stale.refreshToken
            ) {
                return@withLock SupabaseSession(persisted.accessToken, persisted.refreshToken, persisted.userId)
                    .also { session = it }
            }
            val refreshed = client.refresh(stale.refreshToken)
            session = refreshed
            setMeta(meta().copy(accessToken = refreshed.accessToken, refreshToken = refreshed.refreshToken))
            refreshed
        }

    // ---- PRD §15 pause-end cue delivery (PauseCueGateway) ----
    //
    // These run outside [mutex] (the reconcile lock): each is an independent, best-effort per-row side channel,
    // never blocking — or blocked by — a whole-document snapshot reconcile. This is the ONLY remaining server
    // side-channel: the phone registers its push token and claims the account's last phone so the external
    // Realtime listener can reach it. (The old presence / sleep-gap / active-session / derived-pause and
    // next-cue-instant channels are retired; activity is Supabase Realtime Presence, watched by the listener.)

    override val signedIn: Boolean get() = session != null

    override val deviceId: String get() = meta().deviceId

    override val realtimeUrl: String get() = client.config.realtimeUrl
    override val realtimeApiKey: String get() = client.config.anonKey

    override fun realtimeAuth(): Pair<String, String>? = session?.let { it.userId to it.accessToken }

    override suspend fun refreshRealtimeAuth() {
        val current = session ?: return
        // Uses the same serialized refresh as [withAuth] (adopts a token another caller already rotated), so
        // the single-use refresh token is never double-spent against a concurrent reconcile.
        runCatching { refreshSession(current) }
    }

    override suspend fun claimLastPhone() {
        val current = session ?: return
        runCatching { withAuth(current) { client.claimLastPhone(it, meta().deviceId) } }
    }

    override suspend fun registerPushToken(kind: String, platform: String, token: String) {
        val current = session ?: return
        runCatching { withAuth(current) { client.upsertPushToken(it, meta().deviceId, kind, platform, token) } }
    }

    override suspend fun publishAccountState(sleeping: Boolean, wakeAtMillis: Long?) {
        val current = session ?: return
        runCatching { withAuth(current) { client.upsertAccountState(it, sleeping, wakeAtMillis) } }
    }
}
