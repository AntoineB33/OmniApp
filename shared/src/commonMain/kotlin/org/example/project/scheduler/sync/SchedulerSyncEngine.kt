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
    /**
     * No account on this device yet, so sync is dormant. The app is always meant to be connected to an
     * account (a GUEST one when the user never signed in — see [SchedulerSyncEngine.ensureAccount]), so this
     * only shows in the window before the first account could be created: a first launch that is offline, or
     * a project without anonymous sign-ins enabled. The app runs fully offline meanwhile and every sync
     * moment retries creating the guest account.
     */
    data object SignedOut : SyncState

    /** Signed in, nothing in flight. */
    data object Idle : SyncState

    /** A reconcile (pull/push) is running. */
    data object Syncing : SyncState

    /** The last attempt failed (offline, auth, server). Local state is unaffected; sync retries later. */
    data class Error(val message: String) : SyncState
}

/**
 * The account this device is currently connected to (PRD §5). The app is **always** connected to one:
 * either a normal account ([email] set) or a **guest** account — a real Supabase account created
 * automatically on first launch (or after a sign-out) that simply has no credentials, which is why no other
 * device can sign in to it. Everything else works identically: its own synced snapshot, its own presence /
 * break / push rows, its own local partition.
 *
 * "Creating an account" from a guest does not make a second account — it gives THIS one an email and a
 * password ([SchedulerSyncEngine.createAccount]), so the data and the user id are unchanged.
 */
data class AccountInfo(val userId: String, val email: String?) {
    val isGuest: Boolean get() = email == null
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
 * The one revision LWW does **not** apply to is a revision THIS device wrote: a push whose response was lost
 * in transit advances the remote without this device recording the new baseline, and pulling it back would
 * revert the user's own newer edits for no reason. [RemoteSnapshot.writerDeviceId] identifies that case
 * ([isOwnUnacknowledgedPush]) so the revision is adopted rather than applied. It is not a weakening of the
 * conflict policy — a genuine peer write at the same revision still wins.
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

    /**
     * The account this device is connected to — a guest one unless the user signed in / gave it credentials
     * (see [AccountInfo]). Null only before the first account could be created. Republished after every
     * account lifecycle step; the ViewModel watches it to swap the local per-account data partition.
     */
    private val _account = MutableStateFlow(loadAccountInfo())
    val account: StateFlow<AccountInfo?> = _account.asStateFlow()

    /**
     * Invoked immediately BEFORE the active account changes, so the owner can flush pending local edits into
     * the account being left (the local scheduler partition is keyed on the active account — see
     * [org.example.project.scheduler.persistence.SqlDelightSchedulerStore]). Set by the ViewModel.
     */
    var beforeAccountSwitch: (() -> Unit)? = null

    private fun loadAccountInfo(): AccountInfo? =
        metaStore.loadSyncMeta()?.let { m -> m.userId?.let { AccountInfo(it, m.email) } }

    private fun publishAccount() {
        _account.value = loadAccountInfo()
    }

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
            publishAccount()
            Diagnostics.log("restored persisted session (${m.email ?: "guest account"})")
        }
    }

    /** True when the active account is a **guest** one (no credentials) — see [AccountInfo]. */
    val isGuest: Boolean get() = _account.value?.isGuest == true

    /**
     * Makes sure this device is connected to an account (PRD §5: it always is), creating a **guest** account
     * when it is not. Returns true when an account is active afterwards.
     *
     * Called at startup and at the head of every [reconcile], so a first launch that was offline — or a
     * project where anonymous sign-ins were still disabled — simply keeps retrying while the app runs fully
     * offline against its unclaimed local partition. The guest adopts that partition
     * ([org.example.project.scheduler.persistence.SyncMetaStore.adoptUnclaimedAccountData]), so nothing the
     * user did before the account existed is lost.
     *
     * Skipped entirely once any account is active: a guest is created only when there is nothing else.
     */
    suspend fun ensureAccount(): Boolean {
        if (session != null) return true
        return try {
            authenticate(email = null) { client.signUpGuest() }
            true
        } catch (e: Exception) {
            Diagnostics.log("guest-account creation failed (${e.message}); staying local, will retry")
            false
        }
    }

    /**
     * Signs in to an existing account, switching this device to it. The account being left keeps its own
     * local partition and its remote data untouched — nothing is deleted, and signing back in restores it.
     */
    suspend fun signIn(email: String, password: String) =
        authenticate(email) { client.signIn(email, password) }

    /**
     * "Create an account" (PRD §5). On a **guest** account this gives THAT account the [email] and
     * [password] — same user id, same data, same devices, now reachable from another device — rather than
     * creating a second account. (When the active account already has credentials, or when there is no
     * account at all because the guest could not be created, this registers a brand-new account and switches
     * to it.)
     */
    suspend fun createAccount(email: String, password: String) {
        val current = session
        if (current == null || !isGuest) {
            authenticate(email) { client.signUp(email, password) }
            return
        }
        mutex.withLock {
            _state.value = SyncState.Syncing
            try {
                withAuth(current) { client.updateCredentials(it, email, password) }
                // Same account, so no account switch: only the credentials (and the displayed identity) change.
                setMeta(meta().copy(email = email))
                publishAccount()
                _state.value = SyncState.Idle
                Diagnostics.log("guest account claimed as $email (same account, data kept)")
            } catch (e: SupabaseException) {
                _state.value = SyncState.Error(e.message ?: "could not create the account")
                throw e
            }
        }
    }

    /**
     * Authenticates and makes the result this device's active account.
     *
     * [email] is null for a guest account (it has none). Every path here can land on a DIFFERENT account, so
     * the owner is given a chance to flush pending edits into the account being left first, and the account
     * being entered keeps whatever per-account sync bookkeeping this device already had for it (the store
     * refuses to copy the previous account's — see `SqlDelightSchedulerStore.saveSyncMeta`).
     */
    private suspend fun authenticate(
        email: String?,
        call: suspend () -> SupabaseSession,
    ) = mutex.withLock {
        _state.value = SyncState.Syncing
        try {
            val s = call()
            beforeAccountSwitch?.invoke()
            session = s
            persistSession(s, email)
            if (email == null) metaStore.adoptUnclaimedAccountData(s.userId)
            // Published before the (network) logout-baseline fetch, so the owner swaps to this account's
            // local partition as soon as the account exists rather than one round-trip later.
            publishAccount()
            recordLogoutBaseline(s)
            _state.value = SyncState.Idle
            Diagnostics.log(if (email == null) "guest account created (${s.userId})" else "signed in as $email")
        } catch (e: SupabaseException) {
            _state.value = SyncState.Error(e.message ?: "auth failed")
            throw e
        }
    }

    /**
     * Drops the cached session locally — the app is left with no account until [ensureAccount] gives it a
     * fresh guest one (which [signOutToGuest] and [reconcile] do). Does not delete anything: the account's
     * remote snapshot and its local partition both stay put, and signing back in picks them up unchanged.
     *
     * This is only the local drop, so the remote force-logout path in [runReconcile] can use it to sign out
     * while pushing nothing.
     */
    fun signOut() {
        Diagnostics.log("signed out")
        beforeAccountSwitch?.invoke()
        session = null
        val m = meta()
        metaStore.saveSyncMeta(m.copy(accessToken = null, refreshToken = null, userId = null, email = null))
        publishAccount()
        _state.value = SyncState.SignedOut
    }

    /** The user-initiated sign-out: drop the account, then land on a fresh guest account (PRD §5). */
    suspend fun signOutToGuest() {
        signOut()
        ensureAccount()
    }

    /** Marks local state as having unpushed changes; the next [reconcile] will push it. */
    fun markDirty() {
        val m = meta()
        if (!m.dirty) metaStore.saveSyncMeta(m.copy(dirty = true))
    }

    /**
     * Pull-or-push reconcile against the remote (Phase 1 whole-document LWW). Safe to call repeatedly — the
     * triggers are startup, an account change, the 500 ms debounce after an authoritative edit, a Realtime
     * poke or (re)subscribe catch-up, and the manual Sync button. No-op (and never throws) when signed out;
     * on network/server failure it records [SyncState.Error] and leaves local state untouched.
     */
    open suspend fun reconcile() {
        try {
            // PRD §5: the app is always connected to an account. A device that has none yet (first launch
            // while offline, or one whose guest creation failed) gets its guest account here, so every sync
            // moment doubles as the retry — and reconciling then proceeds normally against it.
            ensureAccount()
            var forcedOut = false
            mutex.withLock {
                val current = session ?: run { _state.value = SyncState.SignedOut; return@withLock }
                _state.value = SyncState.Syncing
                try {
                    runReconcile(current)
                    // runReconcile may have signed us out (remote force-logout); don't clobber SignedOut with Idle.
                    if (session != null) _state.value = SyncState.Idle else forcedOut = true
                } catch (e: SupabaseException) {
                    Diagnostics.log("reconcile FAILED (server ${e.status}): ${e.message}")
                    _state.value = SyncState.Error(e.message ?: "sync failed")
                } catch (e: Exception) {
                    // Offline / transport errors: stay calm, keep local state, retry on the next trigger.
                    // ALWAYS logged: a push that threw here may still have landed server-side (the write is
                    // applied before the response is read), which is exactly the lost-acknowledgement case
                    // [runReconcile] repairs on the next pass — and without this line that whole episode was
                    // invisible in the diagnostics timeline.
                    Diagnostics.log("reconcile FAILED (transport): ${e.message}")
                    _state.value = SyncState.Error(e.message ?: "offline")
                }
            }
            // A remote force-logout dropped the account: land on a fresh guest one rather than on nothing
            // (outside the lock — [ensureAccount] takes it). The forced-out account's local partition and
            // whatever survives server-side are left exactly as they are.
            if (forcedOut) ensureAccount()
        } finally {
            // Unified sync moment (see [syncMoments]): fires on every outcome — including the accountless
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

        // Lost-ACKNOWLEDGEMENT repair — must run BEFORE the LWW branches below, because it decides what the
        // remote revision MEANS. A push is not atomic from this side: [RemoteSnapshotClient.update] PATCHes
        // the row and then reads the response. The server can apply the write (revision N -> N+1) and the
        // response never come back — a dropped connection, the machine suspending mid-request — in which case
        // `update` throws, `runReconcile` unwinds, and this device never records the new baseline: it stays at
        // N with `dirty` still set. The next reconcile then sees `remote.revision > lastKnownRevision`, reads
        // it as a PEER's newer write, and takes the `pull` branch — applying THIS DEVICE'S OWN older push over
        // everything the user edited since, silently. Observed on a single-device account: two task cells were
        // deleted and reappeared seconds later.
        //
        // `writer_device_id` (migration 20260730000000) is what tells the two apart. The guard is deliberately
        // narrow — exactly one revision ahead, still dirty, and stamped with OUR device id:
        //  * exactly one ahead, because a lost ack can only ever be one: the retry PATCHes against the stale
        //    `revision = eq.N` guard, matches no row, and falls into the ordinary pull;
        //  * still dirty, because that is the only case where pulling would DESTROY something;
        //  * our device id, because a peer's write at N+1 is a genuine conflict and LWW must still apply.
        // Null writer (a row last written by an older client, or a project without the migration) reads as
        // "unknown" and falls through to the existing behaviour.
        if (remote != null && isOwnUnacknowledgedPush(remote, m)) {
            Diagnostics.log(
                "reconcile: remote revision ${remote.revision} is THIS device's own unacknowledged push — " +
                    "adopting the revision instead of pulling it back over local changes",
            )
            m = m.copy(lastKnownRevision = remote.revision)
            setMeta(m)
        }

        when {
            // First device for this account: seed the remote from local.
            remote == null -> {
                val ok = withAuth(session) { client.insert(it, payload(), m.deviceId) }
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
                val ok = withAuth(session) { client.update(it, payload(), m.lastKnownRevision, m.deviceId) }
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

        // Active sessions ride the SAME reconcile as the snapshot — EVERY one of them (startup, an account
        // change, the debounced auto-push, a Realtime poke or (re)subscribe, the button) — merged per-row
        // rather than LWW: this device's rows are pushed and every peer's are pulled into the local store,
        // so the calendar can label past panels with the devices that were open. Nothing else carries them
        // (no timer, no presence beat), so peer activity is reconcile-bounded. Best-effort — a failure here
        // never fails the snapshot reconcile (the rows simply ride the next one). Skipped entirely on the
        // remote force-logout return above, which must push nothing.
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

    /**
     * True when [remote] is this device's OWN push whose acknowledgement was lost in transit, rather than a
     * peer's newer write — see the long note at the call site in [runReconcile] for why each clause is
     * needed. Pulling such a revision would silently revert every local edit made since that push.
     */
    private fun isOwnUnacknowledgedPush(remote: RemoteSnapshot, m: SyncMeta): Boolean =
        m.dirty &&
            remote.revision == m.lastKnownRevision + 1 &&
            remote.writerDeviceId != null &&
            remote.writerDeviceId == m.deviceId

    private fun pull(remote: RemoteSnapshot) {
        // Log the LWW casualty explicitly: `dirty` here means unpushed local edits are being dropped by the
        // pull (PRD Phase 1 policy). Without this line a silent revert leaves no trace at all in the
        // diagnostics timeline — which is what made the "my deleted cells came back" report so hard to place.
        val dropping = if (meta().dirty) " — DROPPING this device's unpushed local changes (whole-doc LWW)" else ""
        Diagnostics.log("reconcile: pulled remote snapshot (revision ${remote.revision})$dropping")
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

    private fun persistSession(s: SupabaseSession, email: String?) =
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
                fiveMinDueMs = state.fiveMinDueMillis,
                fifteenMinDueMs = state.fifteenMinDueMillis,
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
