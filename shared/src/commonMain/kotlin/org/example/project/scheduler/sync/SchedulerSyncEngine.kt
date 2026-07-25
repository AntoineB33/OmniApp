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
import org.example.project.scheduler.persistence.ActiveSessionStore
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
open class SchedulerSyncEngine(
    private val client: RemoteSnapshotClient,
    private val metaStore: SyncMetaStore,
    private val json: Json = Json { ignoreUnknownKeys = true },
    // PRD §15: the local active-session table. When present, every reconcile ALSO merges it per-row with the
    // remote `device_active_session` table (push this device's rows, pull the peers') so the calendar can
    // show which devices were open during past panels. Null (tests / store-less platforms) skips it.
    private val activeSessionStore: ActiveSessionStore? = null,
) : PauseCueGateway {
    private val mutex = Mutex()
    private var session: SupabaseSession? = null

    // Late-bound by the owner (the ViewModel) to break the engine<->ViewModel construction cycle:
    // [localSnapshot] reads the current state to push, [applyRemote] installs a pulled one.
    private var localSnapshot: (() -> PersistedSnapshot)? = null
    private var applyRemote: ((PersistedSnapshot) -> Unit)? = null

    /** Wires the local-state provider and the pulled-snapshot sink. Call once before [reconcile]. `open` so a
     * test double can capture the [applyRemote] sink to simulate a pull without a live transport. */
    open fun bind(localSnapshot: () -> PersistedSnapshot, applyRemote: (PersistedSnapshot) -> Unit) {
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
    override val syncMoments: SharedFlow<Unit> = _syncMoments.asSharedFlow()

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
    open suspend fun reconcile() {
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
            // `dirty` is set, but our authoritative projection is already byte-identical to the remote — a
            // PHANTOM push. A transient DERIVED change (a time-passing reschedule/materialization that briefly
            // perturbs the sync fingerprint before reverting to the same content) can leave `dirty` set with
            // nothing real to send. Pushing it would advance the `revision` for no content change, which makes
            // every peer take the `pull` branch above and silently DROP its own genuine unpushed edit
            // (whole-doc LWW). This actually happened: an idle peer pushed a content-identical snapshot that
            // clobbered a concurrent sleep-schedule edit on another device. Clear the flag and send nothing.
            m.dirty && localMatchesRemote(remote) -> {
                Diagnostics.log("reconcile: dirty but local == remote (revision ${m.lastKnownRevision}); skipping phantom push")
                setMeta(m.copy(dirty = false))
            }
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

        // Active sessions ride the SAME (button-only) reconcile, merged per-row rather than LWW: this
        // device's rows are pushed and every peer's are pulled into the local store, so the calendar can
        // label past panels with the devices that were open. Best-effort — a failure here never fails the
        // snapshot reconcile (the rows simply ride the next press). Skipped entirely on the remote
        // force-logout return above, which must push nothing.
        syncActiveSessions(session)
    }

    // How far back the per-row active-session merge reaches — the same 168 h horizon the Inactivity bands /
    // "the calendar the user can change" use. Anchored on the newest LOCAL row (not the wall clock) so a
    // debug sim clock that leaped far ahead still syncs the window around what this device actually wrote.
    private companion object {
        const val ACTIVE_SESSION_SYNC_HORIZON_MILLIS: Long = 168L * 60 * 60 * 1_000
    }

    private suspend fun syncActiveSessions(session: SupabaseSession) {
        val store = activeSessionStore ?: return
        runCatching {
            val all = store.loadActiveSessions()
            val since = (all.maxOfOrNull { it.endMillis } ?: 0L) - ACTIVE_SESSION_SYNC_HORIZON_MILLIS
            val ownId = meta().deviceId
            // Only rows recorded under this install's real device id are ours to push: rows written while
            // signed out ("local") or by the retired remote-activity adoption never leave the device.
            val own = all.filter { it.deviceId == ownId && it.endMillis >= since }
            withAuth(session) { client.upsertActiveSessions(it, own) }
            val peers = withAuth(session) { client.fetchActiveSessions(it, since) }
                .filter { it.deviceId != ownId }
            store.saveActiveSessions(peers)
            Diagnostics.log("reconcile: active sessions pushed=${own.size}, pulled=${peers.size} (since=$since)")
        }.onFailure { Diagnostics.log("reconcile: active-session merge failed (${it.message}); rows ride the next sync") }
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

    /**
     * True when this device's authoritative projection already equals what the server holds, so a `dirty`
     * flag has nothing real to push (see the phantom-push guard in [runReconcile]). Compares the decoded
     * [PersistedSnapshot] VALUES — not the raw JSON — so a difference in field order or omitted defaults from
     * another client's encoder cannot read as a change. Fails safe to `false` (any decode error ⇒ treat as
     * different ⇒ push normally), so the guard can never suppress a genuine change.
     */
    private fun localMatchesRemote(remote: RemoteSnapshot): Boolean =
        runCatching {
            checkNotNull(localSnapshot) { "SchedulerSyncEngine.bind() not called" }() ==
                json.decodeFromString<PersistedSnapshot>(remote.payload)
        }.getOrDefault(false)

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
    // never blocking — or blocked by — a whole-document snapshot reconcile. The device writes its activity
    // heartbeat (~10 s while active) and a phone registers its push token + claims the account's last phone so
    // the Edge push can reach it. (The old presence / sleep-gap / derived-pause / next-cue-instant channels and
    // the Realtime-presence listener are retired; the `tick_pause_cues()` cron reads the device_heartbeat table.)

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
        val current = session ?: run {
            Diagnostics.log("push token NOT registered ($platform): signed out")
            return
        }
        runCatching { withAuth(current) { client.upsertPushToken(it, meta().deviceId, kind, platform, token) } }
            .onSuccess { Diagnostics.log("push token registered with backend ($platform, ${token.take(12)}…)") }
            .onFailure { Diagnostics.log("push token registration FAILED ($platform): ${it.message}") }
    }

    override suspend fun publishAccountState(sleeping: Boolean, wakeAtMillis: Long?) {
        val current = session ?: return
        runCatching { withAuth(current) { client.upsertAccountState(it, sleeping, wakeAtMillis) } }
    }

    override suspend fun publishPresence(state: PresenceState): Int? {
        val current = session ?: return null
        return runCatching {
            withAuth(current) { client.publishPresence(session = it, deviceId = meta().deviceId) }
        }.getOrNull()
    }

    // Unlike the beat, this one RETHROWS: it is written only on a change, so nothing re-sends it on its own and
    // the publisher's retry loop is what keeps the server's copy from going stale. A signed-out device drops it
    // (there is no account to write for; the next change after sign-in republishes).
    override suspend fun publishNextBreak(state: NextBreakState) {
        val current = session ?: return
        withAuth(current) {
            client.publishNextBreak(
                session = it,
                deviceId = meta().deviceId,
                kind = state.kind,
                breakKind = state.breakKind,
                breakDueMs = state.dueMillis,
                breakLenMs = state.lengthMillis,
            )
        }
    }

    override suspend fun notifyScreenOff() {
        val current = session ?: return
        runCatching { withAuth(current) { client.notifyScreenOff(it, meta().deviceId) } }
            .onSuccess { Diagnostics.log("screen off reported to pause-cue function") }
            .onFailure { Diagnostics.log("screen-off report FAILED: ${it.message}") }
    }
}
