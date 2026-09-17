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
import org.example.project.scheduler.model.TaskTimeRange
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

    /**
     * The user switched this device to work completely offline (`sync-and-accounts.md` § *Working offline*): nothing
     * is sent or received, edits are kept and pushed once the device goes online again.
     */
    data object Offline : SyncState
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
 * **Conflict policy = three-way MERGE, falling back to last-write-wins.** [reconcile] pushes local changes
 * when the remote still sits at the revision this device last saw. When the remote has advanced *and* this
 * device has unpushed edits, both sides changed concurrently and [SnapshotMerge] combines them against the
 * common ancestor recorded in [SyncMeta.baseSnapshot] — each side's additions, deletions and field edits are
 * attributed and kept, so neither user's work disappears. The merged document is applied locally and pushed
 * on top of the remote revision, which is what makes every device converge on it.
 *
 * The old whole-document last-write-wins (remote wins, local edits dropped) survives only as the fallback for
 * the cases a merge cannot be attempted: no ancestor is on record (a DB upgraded from schema v9, or an account
 * never yet synced on this device) or one of the three snapshots fails to decode. A [remoteApplied] event
 * fires on both paths so the UI can tell the user the state reloaded.
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
    // The wall clock, read to know how long ago this device last pulled (see [FULL_PULL_AFTER_MILLIS]).
    private val clock: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
    // Where the user's "work offline" choice is kept; null (tests, the web store) starts online unless [startOffline].
    private val networkModeStore: org.example.project.scheduler.persistence.NetworkModeStore? = null,
    // This launch starts offline whatever was chosen last ([startOfflineRequested]).
    startOffline: Boolean = false,
) : PauseCueGateway {
    private val mutex = Mutex()
    private var session: SupabaseSession? = null

    private val _offline = MutableStateFlow(startOffline || networkModeStore?.loadOfflineChoice() == true)

    /**
     * `docs/invariants/sync-and-accounts.md` § *Working offline*: true while this device works completely offline.
     * Every request is then refused by [RemoteSnapshotClient.offline], and the engine does not even ask: no
     * reconcile, no guest account, no sign-in, no presence or break rows, no Realtime auth.
     */
    val offline: StateFlow<Boolean> = _offline.asStateFlow()

    init {
        client.offline = _offline.value
    }

    /**
     * Switches this device offline or back online, and remembers the choice for the next launches. Going online does
     * not reconcile by itself: the owner does (see `TaskSchedulerViewModel.setOffline`).
     */
    fun setOffline(value: Boolean) {
        networkModeStore?.saveOfflineChoice(value)
        client.offline = value
        _offline.value = value
        _state.value = if (value) SyncState.Offline else if (session != null) SyncState.Idle else SyncState.SignedOut
        Diagnostics.log(if (value) "working offline: nothing is sent or received" else "back online")
    }

    // The session to use for a request, or null while signed out OR offline.
    private fun onlineSession(): SupabaseSession? = session?.takeIf { !_offline.value }

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

    private val _state = MutableStateFlow<SyncState>(if (_offline.value) SyncState.Offline else SyncState.SignedOut)
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
            _state.value = if (_offline.value) SyncState.Offline else SyncState.Idle
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
        if (_offline.value) return false
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
        if (_offline.value) throw WorkingOfflineException()
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
        if (_offline.value) throw WorkingOfflineException()
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
        _state.value = if (_offline.value) SyncState.Offline else SyncState.SignedOut
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
     * Pull-or-merge-or-push reconcile against the remote. Safe to call repeatedly — the
     * triggers are startup, an account change, the 500 ms debounce after an authoritative edit, a Realtime
     * poke or (re)subscribe catch-up, and the manual Sync button. No-op (and never throws) when signed out;
     * on network/server failure it records [SyncState.Error] and leaves local state untouched.
     */
    open suspend fun reconcile() {
        try {
            if (_offline.value) {
                _state.value = SyncState.Offline
                return
            }
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
        rowReconcile(session)

        syncActiveSessions(session)
    }

    // How far back the per-row active-session merge reaches — the same 168 h horizon the Inactivity bands /
    // "the calendar the user can change" use. Anchored on the newest LOCAL row (not the wall clock) so a
    // debug sim clock that leaped far ahead still syncs the window around what this device actually wrote.
    private companion object {
        const val ACTIVE_SESSION_SYNC_HORIZON_MILLIS: Long = 168L * 60 * 60 * 1_000

        /** Rows per write request. */
        const val WRITE_BATCH: Int = 200

        /**
         * A device that last pulled longer ago than this reads the account's live rows in full: the server deletes a
         * tombstone a week after it was written (`purge_scheduler_tombstones`), so an incremental pull could miss a
         * deletion. A day short of that week, for clock skew and a purge running late.
         */
        const val FULL_PULL_AFTER_MILLIS: Long = 6L * 24 * 60 * 60 * 1_000
    }

    // The own rows the last successful push wrote, for the account it wrote them to: pushing the same rows again is a
    // request for nothing (`docs/invariants/server-quota.md`).
    private var pushedSessions: Pair<String, List<org.example.project.scheduler.persistence.ActiveSessionRecord>>? = null

    private suspend fun syncActiveSessions(session: SupabaseSession) {
        val store = activeSessionStore ?: return
        runCatching {
            val all = store.loadActiveSessions()
            val since = (all.maxOfOrNull { it.endMillis } ?: 0L) - ACTIVE_SESSION_SYNC_HORIZON_MILLIS
            val ownId = meta().deviceId
            // Only rows recorded under this install's real device id are ours to push: rows written while
            // signed out ("local") or by the retired remote-activity adoption never leave the device.
            val own = all.filter { it.deviceId == ownId && it.endMillis >= since }
            if (pushedSessions != (session.userId to own)) {
                withAuth(session) { client.upsertActiveSessions(it, own) }
                pushedSessions = session.userId to own
            }
            val peers = withAuth(session) { client.fetchActiveSessions(it, since) }
                .filter { it.deviceId != ownId }
            store.saveActiveSessions(peers)
            Diagnostics.log("reconcile: active sessions pushed=${own.size}, pulled=${peers.size} (since=$since)")
        }.onFailure { Diagnostics.log("reconcile: active-session merge failed (${it.message}); rows ride the next sync") }
    }

    // ---- Sync by rows (docs/invariants/sync-and-accounts.md § Sync by rows, migration 20260917000000) ----------

    /**
     * What this device knows of the account's rows, kept in `account_sync.base_payload`: the payload the server's
     * entity rows spell out as of [entityCursor] (the common ancestor of a three-way merge), the highest history
     * revision pulled, and the newest unit change pushed. A base written by the whole-document sync decodes to the
     * default — no ancestor — so the first reconcile after the upgrade uploads the account's rows.
     */
    @kotlinx.serialization.Serializable
    private data class RowBase(
        val rows: String = "{}",
        val entityCursor: Long = 0,
        val historyCursor: Long = 0,
        val historyPushedAt: Long = 0,
        /** When this device last pulled the entity rows (wall clock). */
        val pulledAtMillis: Long = 0,
        /**
         * The rows of a push that has not been acknowledged yet, as `[kind, id]`: its answer may have been lost after
         * the server applied it, so the next reconcile writes each of them again as this device then holds it.
         */
        val pending: List<List<String>> = emptyList(),
    )

    private fun rowBase(m: SyncMeta): RowBase =
        m.baseSnapshot?.let { text -> runCatching { json.decodeFromString<RowBase>(text) }.getOrNull() } ?: RowBase()

    private fun encodeBase(base: RowBase): String = json.encodeToString(RowBase.serializer(), base)

    private fun localPayload(): String =
        checkNotNull(localSnapshot) { "SchedulerSyncEngine.bind() not called" }().statePayload

    /** The owner's units, per category, and the sink for the units another device has. Wired by the ViewModel. */
    private var ownHistory: (() -> Map<String, List<org.example.project.scheduler.state.HistoryUnit>>)? = null
    private var mergePeerHistory: ((Map<String, List<org.example.project.scheduler.state.HistoryUnit>>, Map<String, Set<Pair<String, Long>>>) -> Unit)? = null

    /**
     * `docs/invariants/persistence.md` § *One history, per-device undo*: wire the history half of the sync — [own]
     * reads every unit per category name, [mergePeer] takes the units another device has (and the redo branches it
     * dropped) into the local history.
     */
    fun bindHistory(
        own: () -> Map<String, List<org.example.project.scheduler.state.HistoryUnit>>,
        mergePeer: (Map<String, List<org.example.project.scheduler.state.HistoryUnit>>, Map<String, Set<Pair<String, Long>>>) -> Unit,
    ) {
        ownHistory = own
        mergePeerHistory = mergePeer
    }

    /**
     * One reconcile by rows:
     *
     * 1. **Pull** the entity rows another device wrote since the cursor. When there are any, the account's state is
     *    the base with them applied; a device with no edits of its own takes it as is, one with edits MERGES
     *    (`SnapshotMerge` — the same three-way rules the document sync had).
     * 2. **Push** every entity row that differs from what the server now holds, and a tombstone for every one the
     *    device no longer has. An edit therefore writes the rows it touched, and nothing else.
     * 3. **History**: push this device's units changed since the last push (and mark a discarded redo branch
     *    dropped), pull the units other devices changed.
     *
     * Every write is an idempotent upsert keyed by the entity (or the unit), and a pull never returns this device's
     * own rows, so a push whose answer was lost is simply written again — the lost-acknowledgement repair the
     * document sync needed has nothing left to repair.
     */
    private suspend fun rowReconcile(session: SupabaseSession) {
        val m = meta()
        val me = m.deviceId
        var base = rowBase(m)

        val now = clock()
        // A cursor older than the tombstones the server still keeps cannot be trusted to have seen every deletion.
        val full = base.entityCursor > 0 && now - base.pulledAtMillis > FULL_PULL_AFTER_MILLIS
        val pulled = ArrayList<EntityRow>()
        var cursor = if (full) 0L else base.entityCursor
        while (true) {
            val page = withAuth(session) { client.fetchEntities(it, if (full) null else me, cursor, liveOnly = full) }
            pulled += page
            if (page.isEmpty()) break
            cursor = page.maxOf { it.revision }
            if (page.size < RemoteSnapshotClient.PAGE) break
        }

        val baseRows = EntityRows.split(base.rows)
        var local = EntityRows.split(localPayload())
        var serverRows = baseRows
        if (full) cursor = maxOf(cursor, base.entityCursor)
        if (pulled.isNotEmpty() || full) {
            // A full read IS the server's rows; an incremental one is the base with what changed applied.
            val remoteRows = if (full) LinkedHashMap() else LinkedHashMap(baseRows)
            for (row in pulled) {
                val key = EntityRows.Key(row.kind, row.entityId)
                if (row.deleted || row.payload == null) remoteRows.remove(key) else remoteRows[key] = row.payload
            }
            val remotePayload = EntityRows.join(remoteRows)
            val merged =
                if (local == baseRows || baseRows.isEmpty() && !m.dirty) remotePayload
                else SnapshotMerge.merge(snapshotOf(base.rows), snapshotOf(EntityRows.join(local)), snapshotOf(remotePayload))?.statePayload
                    ?: remotePayload
            if (EntityRows.split(merged) != local) {
                applyRemote?.invoke(snapshotOf(merged))
                _remoteApplied.tryEmit(Unit)
                local = EntityRows.split(localPayload())
            }
            serverRows = remoteRows
            base = base.copy(rows = remotePayload, entityCursor = cursor)
            Diagnostics.log("reconcile: pulled ${pulled.size} entity row(s) up to revision $cursor${if (full) " (full read)" else ""}")
        }

        val changed = LinkedHashMap<EntityRows.Key, String?>()
        for ((key, value) in local) if (serverRows[key] != value) changed[key] = value
        for (key in serverRows.keys) if (key !in local) changed[key] = null
        // A push whose answer never came may still have landed: whatever it wrote is written again, as it is now.
        for (pending in base.pending) {
            val key = EntityRows.Key(pending[0], pending[1])
            if (key !in changed) changed[key] = local[key]
        }
        if (changed.isNotEmpty()) {
            // Recorded BEFORE the request: if its answer is lost, the next reconcile knows what it may have written.
            val pending = changed.keys.map { listOf(it.kind, it.id) }
            setMeta(meta().copy(baseSnapshot = encodeBase(base.copy(pending = pending))))
            val writes = changed.map { (key, value) -> EntityWrite(key.kind, key.id, value) }
            for (batch in writes.chunked(WRITE_BATCH)) withAuth(session) { client.upsertEntities(it, me, batch) }
            Diagnostics.log("reconcile: pushed ${writes.size} entity row(s)")
        }
        base = base.copy(rows = EntityRows.join(local), pulledAtMillis = now, pending = emptyList())

        base = syncHistory(session, me, base)
        setMeta(meta().copy(lastKnownRevision = base.entityCursor, dirty = false, baseSnapshot = encodeBase(base)))
    }

    private fun snapshotOf(payload: String) = PersistedSnapshot(payload, emptyList(), emptyList())

    private suspend fun syncHistory(session: SupabaseSession, me: String, start: RowBase): RowBase {
        val own = ownHistory ?: return start
        var base = start
        val units = own()
        val changed =
            units.flatMap { (category, list) ->
                list.filter { it.ownedBy(me) && it.changedAtMillis > base.historyPushedAt }.map { category to it }
            }
        if (changed.isNotEmpty()) {
            val rows =
                changed.map { (category, unit) ->
                    HistoryUnitRow(
                        deviceId = me,
                        category = category,
                        deviceSeq = unit.deviceSeq,
                        timeMillis = unit.timeMillis,
                        chronoId = unit.chronoId,
                        tainted = unit.debugTainted,
                        undone = unit.undone,
                        window = unit.window?.name,
                        delta = org.example.project.scheduler.persistence.SchedulerStateCodec.encodeUnit(unit),
                    )
                }
            for (batch in rows.chunked(WRITE_BATCH)) withAuth(session) { client.upsertHistoryUnits(it, batch) }
            // A redo branch a new edit discarded: its rows are this device's undone units older than its newest
            // applied one, which the local history no longer holds.
            // Only a NEW unit can have discarded one, so only a category this push committed into is asked.
            val committedInto = changed.filter { it.second.timeMillis > start.historyPushedAt }.map { it.first }.toSet()
            for ((category, list) in units) {
                if (category !in committedInto) continue
                val newestApplied = list.filter { it.ownedBy(me) && !it.undone }.maxOfOrNull { it.timeMillis } ?: continue
                withAuth(session) { client.dropHistoryBranch(it, me, category, newestApplied) }
            }
            base = base.copy(historyPushedAt = changed.maxOf { it.second.changedAtMillis })
            Diagnostics.log("reconcile: pushed ${rows.size} history unit(s)")
        }

        val merge = mergePeerHistory ?: return base
        var cursor = base.historyCursor
        val peers = HashMap<String, MutableList<org.example.project.scheduler.state.HistoryUnit>>()
        val dropped = HashMap<String, MutableSet<Pair<String, Long>>>()
        while (true) {
            val page = withAuth(session) { client.fetchHistoryUnits(it, me, cursor) }
            for (row in page) {
                if (row.dropped) {
                    dropped.getOrPut(row.category) { HashSet() } += row.deviceId to row.deviceSeq
                    continue
                }
                org.example.project.scheduler.persistence.SchedulerStateCodec.decodeUnit(
                    row.timeMillis, row.chronoId, row.tainted, row.window, row.deviceId, row.deviceSeq, row.undone, row.delta,
                )?.let { peers.getOrPut(row.category) { ArrayList() } += it }
            }
            if (page.isEmpty()) break
            cursor = page.maxOf { it.revision }
            if (page.size < RemoteSnapshotClient.PAGE) break
        }
        if (peers.isNotEmpty() || dropped.isNotEmpty()) {
            merge(peers, dropped)
            Diagnostics.log("reconcile: pulled ${peers.values.sumOf { it.size }} history unit(s), ${dropped.values.sumOf { it.size }} dropped")
        }
        return base.copy(historyCursor = cursor)
    }

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

    override val signedIn: Boolean get() = onlineSession() != null

    override val deviceId: String get() = meta().deviceId

    override val realtimeUrl: String get() = client.config.realtimeUrl
    override val realtimeApiKey: String get() = client.config.anonKey

    override fun realtimeAuth(): Pair<String, String>? = onlineSession()?.let { it.userId to it.accessToken }

    override suspend fun refreshRealtimeAuth() {
        val current = onlineSession() ?: return
        // Uses the same serialized refresh as [withAuth] (adopts a token another caller already rotated), so
        // the single-use refresh token is never double-spent against a concurrent reconcile.
        runCatching { refreshSession(current) }
    }

    override suspend fun claimLastPhone() {
        val current = onlineSession() ?: return
        runCatching { withAuth(current) { client.claimLastPhone(it, meta().deviceId) } }
    }

    override suspend fun registerPushToken(kind: String, platform: String, token: String) {
        val current = onlineSession() ?: run {
            Diagnostics.log("push token NOT registered ($platform): signed out")
            return
        }
        runCatching { withAuth(current) { client.upsertPushToken(it, meta().deviceId, kind, platform, token) } }
            .onSuccess { Diagnostics.log("push token registered with backend ($platform, ${token.take(12)}…)") }
            .onFailure { Diagnostics.log("push token registration FAILED ($platform): ${it.message}") }
    }

    override suspend fun publishAccountState(sleeping: Boolean, wakeAtMillis: Long?) {
        val current = onlineSession() ?: return
        runCatching { withAuth(current) { client.upsertAccountState(it, sleeping, wakeAtMillis) } }
    }

    override suspend fun publishPresence(state: PresenceState): Int? {
        val current = onlineSession() ?: return null
        return runCatching {
            withAuth(current) { client.publishPresence(session = it, deviceId = meta().deviceId) }
        }.getOrNull()
    }

    // Unlike the beat, this one RETHROWS: it is written only on a change, so nothing re-sends it on its own and
    // the publisher's retry loop is what keeps the server's copy from going stale. A signed-out device drops it
    // (there is no account to write for; the next change after sign-in republishes).
    override suspend fun publishNextBreak(state: NextBreakState) {
        val current = onlineSession() ?: return
        withAuth(current) {
            // The rule set first: it is what the mode-3 evaluation reads, and the two dues below are its own
            // projection. Both are sent under one caller so a retry re-sends the pair together.
            client.publishBreakRules(session = it, rules = state.rules)
            client.publishNextBreak(
                session = it,
                fiveMinDueMs = state.fiveMinDueMillis,
                fifteenMinDueMs = state.fifteenMinDueMillis,
            )
        }
    }

    override suspend fun notifyScreenOff() {
        val current = onlineSession() ?: return
        runCatching { withAuth(current) { client.notifyScreenOff(it, meta().deviceId) } }
            .onSuccess { Diagnostics.log("screen off reported to pause-cue function") }
            .onFailure { Diagnostics.log("screen-off report FAILED: ${it.message}") }
    }

    // Best-effort like the beat, not like the break write: the flag is re-asserted by the next edge and by
    // every sync moment, and a lost call costs at most one cue. A signed-out device answers "nobody is away",
    // which is what a device with no account to ask about should say.
    override suspend fun syncDeviceAway(away: Boolean?): Boolean {
        val current = onlineSession() ?: return false
        return runCatching { withAuth(current) { client.syncDeviceAway(it, meta().deviceId, away) } }
            .onFailure { Diagnostics.log("away flag sync FAILED: ${it.message}") }
            .getOrDefault(false)
    }

    override suspend fun fetchAwaySpans(fromMillis: Long, toMillis: Long): List<TaskTimeRange> {
        val current = onlineSession() ?: return emptyList()
        return runCatching { withAuth(current) { client.fetchAwaySpans(it, fromMillis, toMillis) } }
            .onFailure { Diagnostics.log("mode-3 span fetch FAILED: ${it.message}") }
            .getOrDefault(emptyList())
    }
}
