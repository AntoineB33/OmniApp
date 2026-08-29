package org.example.project.scheduler.ui

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.persistence.PersistedSnapshot
import org.example.project.scheduler.persistence.SchedulerStateCodec
import org.example.project.scheduler.persistence.SchedulerStore
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState
import org.example.project.scheduler.state.TaskTreeEntry
import org.example.project.scheduler.sync.AccountInfo
import org.example.project.scheduler.sync.PauseCueGateway
import org.example.project.scheduler.sync.RealtimeSnapshotSubscriber
import org.example.project.scheduler.sync.SchedulerSyncEngine
import org.example.project.scheduler.sync.SnapshotChangeSubscription
import org.example.project.scheduler.sync.StartupLogin
import org.example.project.scheduler.sync.SyncState
import org.example.project.scheduler.sync.startupLoginCredentials
import org.example.project.scheduler.sync.usernameToEmail

class TaskSchedulerViewModel(
    initial: SchedulerState = SchedulerState.empty(),
    private val store: SchedulerStore? = null,
    // Off the main thread: the debounced SQLite write is blocking IO. Injectable so tests can drive it
    // with a virtual-time test dispatcher.
    saveDispatcher: CoroutineDispatcher = Dispatchers.Default,
    // PRD §5 cross-device sync. Null = sync disabled (the default, so tests and offline-only builds are
    // unaffected). When present, local saves push (debounced) and the app pulls remote changes.
    private val syncEngine: SchedulerSyncEngine? = null,
    // Credentials for non-interactive launch (the per-account `/scripts`). Injectable so tests can drive
    // the auto-login path without the platform property/env/Intent plumbing.
    private val startupLogin: () -> StartupLogin? = { startupLoginCredentials() },
    // PRD §5 bidirectional sync — the remote→local auto-pull subscription. Injectable so tests can supply a
    // no-op/recording fake (a real one would open a WebSocket). Null → build the real [RealtimeSnapshotSubscriber]
    // when sync is enabled.
    snapshotSubscription: SnapshotChangeSubscription? = null,
) : ViewModel() {
    // PRD §5 Initialization: load from local persistence when present, otherwise start
    // from the empty DB (root → main).
    private val _state = MutableStateFlow(loadInitialState(store, initial))
    val state: StateFlow<SchedulerState> = _state.asStateFlow()

    /** PRD §5 sync status for an indicator; null when sync is disabled. */
    val syncState: StateFlow<SyncState>? = syncEngine?.state

    /**
     * PRD §5: the account this device is connected to — always one, a **guest** account (no credentials)
     * until the user signs in or gives it an email/password. Null when sync is disabled, or in the window
     * before the first guest account could be created. Drives the account chip/dialog.
     */
    val account: StateFlow<AccountInfo?>? = syncEngine?.account

    /**
     * Push-token / last-phone / device-heartbeat channel for the pg_cron pause-cue delivery: the device writes
     * its activity heartbeat (~10 s while active), and a phone registers its FCM/APNs token + claims the
     * account's last phone so the Edge push can reach it. Null when sync is disabled. (The old
     * presence/sleep-gap/derived-pause channels and the Realtime-presence listener are gone — the server's
     * `tick_pause_cues()` cron reads the `device_heartbeat` table; see CLAUDE.md.)
     */
    val pauseCue: PauseCueGateway? get() = syncEngine

    // PRD §5 Persistence: every change updates the in-memory state immediately; the SQLite DB is then
    // updated on a debounce so a burst of edits (e.g. typing a cell) collapses into one write instead
    // of one per keystroke.
    private val saveScope = CoroutineScope(SupervisorJob() + saveDispatcher)
    private var saveJob: Job? = null

    // True while there are state changes not yet written by the debounced save (so [flush] knows whether a
    // close-within-the-debounce-window left unpushed work to flag for the next launch's reconcile).
    private var savePending = false

    // True when at least one change in the current debounce window was authoritative (user-authored) and so
    // may push. A window of only schedule-advance ticks (which re-derive panels + bank auto records another
    // device recomputes) leaves this false, so the debounced flush pushes nothing. Sticky across the window so
    // a user edit followed within the debounce by an engine tick still pushes (the tick can't cancel its push).
    private var syncPending = false

    // CLAUDE.md reconstructibility rule: the authoritative projection ([SchedulerStateCodec.syncFingerprint])
    // last flagged for a server push. A state change whose fingerprint equals this carries no new
    // authoritative data — an engine-tick reschedule that only re-derived the auto/side/sleep panels — so it
    // must NOT mark state dirty or push (the "known deviation": an idle session was syncing once per tick).
    // Seeded from the loaded state so a launch that matches the server pushes nothing; refreshed on every
    // decided push and after a pulled remote snapshot (local then equals remote). Only tracked when sync is on.
    private var lastSyncedFingerprint: PersistedSnapshot? =
        if (syncEngine != null) SchedulerStateCodec.syncFingerprint(_state.value) else null

    // PRD §5 bidirectional sync — local→remote auto-push. An authoritative, fingerprint-changing edit emits
    // here (from [markDirtyIfAuthoritativeChanged]); a 500 ms debounce coalesces a burst of edits into a single
    // [SchedulerSyncEngine.reconcile] push. Only meaningful when sync is on; the manual Sync button ([syncNow])
    // remains as a fallback. `extraBufferCapacity = 1` so a `tryEmit` from the (conflated) edit path never drops.
    private val pushRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    // PRD §5 bidirectional sync — remote→local auto-pull. While signed in this holds a Realtime `postgres_changes`
    // subscription on this account's `scheduler_snapshot` row; a server-side change (a peer pushed) pokes
    // [syncNow], which pulls via the LWW reconcile (the `revision` guard drops this device's own echo). Null when
    // sync is disabled (tests / offline builds), so no WebSocket is opened. Kept for [onCleared] cleanup only via
    // [saveScope] cancellation — the subscriber's coroutines live on that scope.
    private val snapshotSubscriber: SnapshotChangeSubscription? =
        snapshotSubscription ?: syncEngine?.let { engine ->
            RealtimeSnapshotSubscriber(
                scope = saveScope,
                realtimeUrl = engine.realtimeUrl,
                apiKey = engine.realtimeApiKey,
                auth = { engine.realtimeAuth() },
                refreshAuth = { engine.refreshRealtimeAuth() },
                onRemoteChange = { syncNow() },
            )
        }

    init {
        syncEngine?.let { engine ->
            // History-window "Supabase usage" column (local-only diagnostic): record every Supabase HTTP call
            // the transport makes. Subscribed FIRST — before restoreSession/reconcile below drive the launch's
            // login/reconcile traffic — and the source flow replays, so those first calls are still captured.
            // Stamped on the same clock the History units use ([SchedulerReducer.clock]); non-syncing (never
            // pushes — see [syncsToServer]), so this never feeds back into more network usage.
            saveScope.launch {
                engine.supabaseUsage.collect { event ->
                    dispatch(
                        SchedulerIntent.RecordSupabaseUsage(
                            resource = event.resource,
                            operation = event.operation,
                            requestBytes = event.requestBytes,
                            responseBytes = event.responseBytes,
                            status = event.status,
                            timeMillis = SchedulerReducer.clock.nowMillis(),
                        ),
                    )
                }
            }
            // Break the engine<->ViewModel cycle: the engine reads/writes our state through these.
            // The PUSHED payload is the authoritative projection ([SchedulerStateCodec.syncFingerprint]),
            // not the full local snapshot: the regenerated auto/side/sleep panels and the per-device view
            // state are recomputed/kept locally by every device, so shipping them would only spend
            // bandwidth on data the puller discards or re-derives (CLAUDE.md reconstructibility rule).
            engine.bind(
                localSnapshot = { SchedulerStateCodec.syncFingerprint(_state.value) },
                applyRemote = { snapshot -> applyRemoteSnapshot(snapshot) },
            )
            // PRD §5 bidirectional sync — local→remote auto-push: coalesce authoritative edits and reconcile
            // (push) after a 500 ms quiet period. Only authoritative, fingerprint-changing edits reach here
            // (emitted by [markDirtyIfAuthoritativeChanged]); derived/tick changes are gated out upstream, so an
            // idle running session never auto-pushes.
            startAutoPush()
            // PRD §5 bidirectional sync — remote→local auto-pull: keep the Realtime subscription running; it
            // connects once an account is set below (on restore/login) and pokes [syncNow] on a peer's push.
            snapshotSubscriber?.start()
            // The local scheduler data is stored per account; flush pending edits into the account being left
            // before the engine switches away from it.
            engine.beforeAccountSwitch = { flush() }
            // Swap the local per-account partition whenever the active account changes (guest created,
            // sign-in, sign-out, remote force-logout). A guest CLAIMED with an email keeps the same user id,
            // so it is not a switch and the data simply stays.
            watchAccountChanges(engine)
            engine.restoreSession()
            // Non-interactive launch (per-account `/scripts`): when no session was restored and startup
            // credentials were supplied, sign in to that account — that is how the scripts open the app
            // already on account 1/2/3 instead of on a guest account. Otherwise PRD §5 applies: the app is
            // always connected to an account, so a device without one creates its GUEST account here.
            // Both of those land in [watchAccountChanges], which reconciles once against the new account.
            if (engine.isSignedIn) {
                refreshRealtimeSubscription()
                // STARTUP RECONCILE — the app checks the server for remote changes every launch.
                //
                // A RESTORED session is NOT an account change: [SchedulerSyncEngine._account] is seeded from the
                // persisted `sync_meta` at engine construction, so by the time [watchAccountChanges] subscribes the
                // value is already this account and its first emission is filtered out. Without this call nothing
                // else reconciles at startup, and the device opens on whatever `revision` it last saw — for as long
                // as the user makes no edit. That is not merely stale: the FIRST authoritative edit is what then
                // triggers the auto-push reconcile, which fetches, finds the remote ahead, and takes the LWW `pull`
                // branch — silently DESTROYING that very edit. Observed 2026-07-28 on account 3: a restart left the
                // device one revision behind for 17 h; the user deleted two task cells and the pull put them back.
                // Reconciling here collapses that window: the launch adopts (or pulls) whatever the server holds
                // before the user can touch anything, and equally FLUSHES any edit a previous session left unpushed.
                // Offline is a non-event — [SchedulerSyncEngine.reconcile] catches transport failures, logs them and
                // keeps the local state for the next trigger.
                saveScope.launch { engine.reconcile() }
            } else {
                val creds = startupLogin()
                if (creds != null) {
                    signIn(usernameToEmail(creds.username), creds.password)
                } else {
                    saveScope.launch { engine.ensureAccount() }
                }
            }
        }
    }

    /**
     * PRD §5: the locally persisted scheduler data is partitioned per account, so a change of the ACTIVE
     * account means the in-memory state must be replaced by that account's own data (empty for a brand-new
     * guest account). Nothing is deleted — the account being left keeps its partition and gets it back
     * verbatim if it signs in again — and no data crosses over, so signing in to another account can never
     * push this account's tree over that one's remote snapshot.
     */
    private fun watchAccountChanges(engine: SchedulerSyncEngine) {
        var loadedAccountId = engine.account.value?.userId
        saveScope.launch {
            engine.account.collect { info ->
                if (info?.userId == loadedAccountId) return@collect
                loadedAccountId = info?.userId
                // The engine flushed the previous account's pending edits (beforeAccountSwitch) before
                // repointing the store, so loading now reads the account we just switched to.
                val loaded = prepareLoadedState(store?.load()?.let(SchedulerStateCodec::decodeSnapshot) ?: SchedulerState.empty())
                _state.value = loaded
                lastSyncedFingerprint = SchedulerStateCodec.syncFingerprint(loaded)
                refreshRealtimeSubscription()
                // Pull whatever that account holds server-side (a fresh guest simply seeds it).
                engine.reconcile()
            }
        }
    }

    /**
     * PRD §5 bidirectional sync — the local→remote auto-push loop. Collects [pushRequests] (one per authoritative
     * edit), debounces [AUTO_PUSH_DEBOUNCE_MILLIS] so a burst of edits (e.g. dragging a panel) becomes one push,
     * then reconciles. Reconcile pushes only the pending change (it no-ops unless the engine is dirty and the
     * remote is still where we left it — see [SchedulerSyncEngine.reconcile]), and a pulled remote snapshot never
     * re-emits here (see the echo-prevention note on [applyRemoteSnapshot]), so this can't loop.
     */
    @OptIn(FlowPreview::class)
    private fun startAutoPush() {
        val engine = syncEngine ?: return
        // UNDISPATCHED so the collector subscribes to [pushRequests] synchronously before this returns: an edit
        // that emits moments after startup (or in a test) then can't be dropped by the replay-0 SharedFlow for
        // lack of a subscriber. Emissions still resume on [saveScope]'s dispatcher.
        saveScope.launch(start = CoroutineStart.UNDISPATCHED) {
            pushRequests.debounce(AUTO_PUSH_DEBOUNCE_MILLIS).collect { engine.reconcile() }
        }
    }

    /** Points the Realtime snapshot subscription at the current account (or disconnects it when signed out). */
    private fun refreshRealtimeSubscription() {
        snapshotSubscriber?.setAccount(syncEngine?.realtimeAuth()?.first)
    }

    fun dispatch(intent: SchedulerIntent) {
        val current = _state.value
        val next = SchedulerReducer.reduce(current, intent)
        // No-op intents (e.g. a RefreshSchedule tick still within the deadline) return the same
        // instance; skip the state push and persist so the timer doesn't churn storage.
        if (next === current) return
        _state.value = next
        scheduleSave(syncable = intent.syncsToServer())
    }

    /**
     * CLAUDE.md reconstructibility rule: the schedule-advance intents only re-derive state that another device
     * recomputes by advancing its own now-line over the synced task tree — the auto/side/sleep panels AND the
     * completed-work **records** [org.example.project.scheduler.state.SchedulerReducer] banks as auto panels
     * elapse (`AdvanceSchedule`/`RefreshSchedule`) or as a device sleep cuts them (`ReportDeviceSleep`). None of
     * that is a user-authored change another device can't deduce, so it must NEVER trigger a server push on its
     * own — an always-running device would otherwise `scheduler_snapshot`-write all day just from time passing.
     * (Any such record still rides along with the next authoritative push; the snapshot is whole-document.)
     *
     * `MaterializePastSleep` is the same shape: a time-driven materialization of past scheduled-sleep windows
     * (fired from [org.example.project.scheduler.engine.SchedulerEngine]'s tick, not a user action). It is
     * derived (a pure function of `now` + the sleep config + inactivity), banked locally only, and its panels
     * are stripped from the sync wire — but materializing one calls `allocatePanelId()`, which advances the
     * persisted `nextPanelCounter` (part of `syncFingerprint`). Left syncable, that counter bump alone flips
     * the fingerprint and fires a **phantom push** of otherwise-identical content, which (whole-doc LWW) makes
     * peers pull-and-drop their own unpushed edits. So it must NOT push on its own. (It has no inactivity
     * sibling any more: what a no-screen period vacates is idle time the calendar DERIVES a grey band for,
     * never a panel the app writes — see `SchedulerReducer.stripRecords`.)
     *
     * Every other intent — tree edits, pinned/user panels, reminders, sleep/settings, history, and the MANUAL
     * record edits `RemoveRecordPeriod` / `PinRecordAsPanel` — is authoritative and syncs (subject to the
     * fingerprint gate in [pushIfAuthoritativeChanged]).
     */
    private fun SchedulerIntent.syncsToServer(): Boolean =
        when (this) {
            is SchedulerIntent.AdvanceSchedule,
            is SchedulerIntent.RefreshSchedule,
            // Materializes more of the SAME plan as the horizon rolls (see SchedulerIntent.ExtendSchedule) —
            // derived panels only, exactly like the two above.
            is SchedulerIntent.ExtendSchedule,
            is SchedulerIntent.ReportDeviceSleep,
            // Derived time-driven materialization — see the KDoc above: its `allocatePanelId()` counter bump
            // would otherwise phantom-push identical content and clobber peers' edits via whole-doc LWW.
            is SchedulerIntent.MaterializePastSleep,
            // The notification log is a per-device diagnostic (see SchedulerState.notificationLog); recording
            // one persists locally but is never an authoritative change, so it must not request a server push.
            is SchedulerIntent.RecordNotification,
            // Likewise the Supabase-usage log (see SchedulerState.supabaseUsageLog) — a per-device diagnostic
            // that must never push (and a push would ironically log even more usage, i.e. never terminate).
            is SchedulerIntent.RecordSupabaseUsage -> false
            else -> true
        }

    /**
     * Schedules a debounced persist of the current state, replacing any pending one.
     *
     * The local SQLite write always runs (it is the source of truth and cheap on the debounce). The *server*
     * push, however, is gated on an authoritative change (reconstructibility rule — see CLAUDE.md): a state
     * change that only re-derived the auto/side/sleep panels (an engine-tick reschedule — a pure function of
     * `now` + the tree + sleep/side config) leaves [SchedulerStateCodec.syncFingerprint] unchanged and so
     * neither marks the state dirty nor pushes. Only authoritative changes (tree edits, MANUAL record edits,
     * pinned/user panels, reminders, sleep settings, history) move the fingerprint. This — together with
     * [syncsToServer] gating out the schedule-advance ticks — is what stops a running session from syncing on
     * every scheduler tick (the "known deviation") and from `scheduler_snapshot`-writing as auto records bank.
     *
     * [syncable] is false for a schedule-advance tick (see [syncsToServer]): the local SQLite write still runs
     * (records are kept locally, recomputed on load) but the server push is skipped. The flag is sticky across
     * the debounce window so a user edit is never robbed of its push by a tick that lands within the window.
     */
    private fun scheduleSave(syncable: Boolean = true) {
        val store = store ?: return
        savePending = true
        if (syncable) syncPending = true
        saveJob?.cancel()
        saveJob =
            saveScope.launch {
                delay(SAVE_DEBOUNCE_MILLIS)
                store.save(SchedulerStateCodec.encodeSnapshot(_state.value))
                savePending = false
                // PRD §5 sync: flag + request a push only when an authoritative (user-authored) change occurred
                // in this window AND the authoritative projection actually changed (immediately, so a close
                // before the push still reconciles next launch). The push itself waits out the 10-s user-change
                // debounce (ARCHITECTURE.md §8); markDirty keeps it durable until it goes out.
                if (syncPending) {
                    syncPending = false
                    markDirtyIfAuthoritativeChanged()
                }
            }
    }

    /**
     * Marks the sync engine dirty when the authoritative projection of the current state
     * ([SchedulerStateCodec.syncFingerprint]) differs from what was last flagged, so the next reconcile
     * pushes it — **and** enqueues that reconcile itself on the 500 ms auto-push debounce (see
     * [startAutoPush]); the durable dirty flag is what survives if the app dies before it fires, and the
     * button remains the force-now fallback. A no-op when sync is disabled or when nothing authoritative changed (an
     * engine-tick reschedule that only re-derived the auto/side/sleep panels leaves the fingerprint unchanged).
     */
    private fun markDirtyIfAuthoritativeChanged() {
        val engine = syncEngine ?: return
        val fingerprint = SchedulerStateCodec.syncFingerprint(_state.value)
        if (fingerprint == lastSyncedFingerprint) return
        lastSyncedFingerprint = fingerprint
        engine.markDirty()
        // PRD §5 bidirectional sync: request a debounced auto-push. Only reached for an authoritative change
        // whose projection actually moved, so derived/tick reschedules never enqueue a push (see [startAutoPush]).
        pushRequests.tryEmit(Unit)
    }

    /**
     * Cancels any pending debounce and writes the current state synchronously. Call before the app
     * closes so a change made within the debounce window is not lost.
     */
    fun flush() {
        val store = store ?: return
        saveJob?.cancel()
        saveJob = null
        store.save(SchedulerStateCodec.encodeSnapshot(_state.value))
        // A change within the debounce window never reached the dirty-flag above; flag it (only if it was an
        // authoritative, user-authored change) so the next reconcile sends it. No-op when the
        // debounced save already accounted for it (savePending false), when only schedule-advance ticks ran
        // (syncPending false), or when nothing authoritative changed.
        if (savePending && syncPending) {
            markDirtyIfAuthoritativeChanged()
        }
        savePending = false
        syncPending = false
    }

    // ---- PRD §5 cross-device sync controls (no-ops when sync is disabled) ----
    //
    // Sync is BIDIRECTIONAL: every launch checks the server once (the startup reconcile in `init`), the Realtime
    // subscription then streams peers' pushes (auto-pull) and authoritative local edits auto-push on a 500 ms
    // debounce. These controls change WHICH ACCOUNT the device is connected to; [watchAccountChanges] is what then
    // loads that account's local partition, re-points the subscription and reconciles once against it. The manual
    // Sync button ([syncNow]) is a force-now fallback.

    /**
     * Signs in to an existing account, switching this device to it (its data is then loaded/pulled by
     * [watchAccountChanges]). The account being left — including a guest one — keeps its local partition and
     * its remote data; nothing is deleted. Errors surface via [syncState].
     */
    fun signIn(email: String, password: String) =
        saveScope.launch { runCatching { syncEngine?.signIn(email, password) } }

    /**
     * "Create an account" (PRD §5). On the guest account this device is normally on, this gives THAT account
     * the email + password: same account, same data, now reachable from other devices. Errors via [syncState].
     */
    fun createAccount(email: String, password: String) =
        saveScope.launch { runCatching { syncEngine?.createAccount(email, password) } }

    /**
     * Signs out and lands on a fresh **guest** account (PRD §5: the app is always connected to one). Any
     * change still inside the save debounce is flushed into the account being left first, and that account's
     * local partition and remote data are both left untouched — signing back in restores it verbatim.
     */
    fun signOut() {
        val engine = syncEngine ?: return
        saveScope.launch {
            runCatching { flush() }
            engine.signOutToGuest()
        }
    }

    /** The manual sync trigger (fallback / force-now). Auto-push + Realtime auto-pull also reconcile on their own. */
    fun syncNow() = saveScope.launch { syncEngine?.reconcile() }

    /**
     * Sleep/Work toggle: set the account's sleeping-until instant (null = working) locally AND mirror the mode
     * to the `account_state` table immediately, so the server cron (`tick_pause_cues()`) suppresses the
     * pause-end cue while the user is deliberately away. Pass the next scheduled wake instant when pressing **Sleep**, or null when
     * pressing **Work** (or when the wake instant lapses on launch). The local dispatch persists on the save
     * debounce and marks the snapshot dirty for the next reconcile; the account_state write is its own targeted,
     * event-driven call (not the snapshot reconcile).
     */
    fun setSleepMode(sleepingUntilMillis: Long?) {
        dispatch(SchedulerIntent.SetSleepMode(sleepingUntilMillis))
        saveScope.launch {
            runCatching { pauseCue?.publishAccountState(sleepingUntilMillis != null, sleepingUntilMillis) }
        }
    }

    /** Decodes a pulled remote snapshot, prepares it like a fresh load, swaps it in, and mirrors it locally. */
    private fun applyRemoteSnapshot(snapshot: PersistedSnapshot) {
        val decoded = SchedulerStateCodec.decodeSnapshot(snapshot) ?: return
        // CLAUDE.md reconstructibility rule: the per-device view state (focused window, tree selection,
        // calendar display switches, WindowNav/Selection history) is local-only and must never be adopted
        // from another device — carry the current local values across the pull. (syncFingerprint likewise
        // neutralizes them, so pushing them never happens in the first place.)
        val prepared = prepareLoadedState(decoded.withLocalViewStateFrom(_state.value))
        _state.value = prepared
        store?.save(SchedulerStateCodec.encodeSnapshot(prepared))
        // Local state now equals the remote we just pulled — reset the sync baseline so the next tick-only
        // reschedule is (correctly) seen as no authoritative change and does not immediately push back.
        if (syncEngine != null) lastSyncedFingerprint = SchedulerStateCodec.syncFingerprint(prepared)
    }

    override fun onCleared() {
        flush()
        saveScope.cancel()
        super.onCleared()
    }

    companion object {
        /** PRD §5: how long edits must be quiet before the debounced write to SQLite fires. */
        private const val SAVE_DEBOUNCE_MILLIS = 400L

        /** PRD §5 bidirectional sync: how long authoritative edits must be quiet before the auto-push reconcile. */
        private const val AUTO_PUSH_DEBOUNCE_MILLIS = 500L

        /** PRD §5: reload persisted state; an interrupted Edit Mode session is canceled. */
        fun loadInitialState(store: SchedulerStore?, initial: SchedulerState): SchedulerState =
            prepareLoadedState(store?.load()?.let(SchedulerStateCodec::decodeSnapshot) ?: initial)

        /**
         * Normalizes a just-loaded state (from local disk or a pulled remote snapshot) for the running app:
         * revert debug-tainted changes (§6), seed the hardcoded screen breaks and default sleep window (§15),
         * cancel any interrupted Edit Mode session, and name the tree when the account never has (§5).
         */
        fun prepareLoadedState(loaded: SchedulerState): SchedulerState {
            // PRD §6: revert any changes committed under the diverged debug clock before they reach the
            // running app, so a fast-forwarded session never pollutes the real saved data on restart.
            val clean = SchedulerReducer.rollbackDebugTainted(loaded)
            // PRD §15: screen breaks are a hardcoded set (not persisted user data); seed them onto whatever
            // was loaded so they are always present in the running app, never in the bare test states.
            // The sleep schedule is seeded with the default only when none was persisted, so production
            // always has a sleep window (07:30 / 8h30) while bare test states keep `sleep = null`.
            val seeded =
                clean.copy(
                    screenBreaks = SchedulerDomain.effectiveDefaultScreenBreaks(),
                    sleep = clean.sleep ?: SchedulerDomain.DEFAULT_SLEEP,
                )
            val settled =
                if (seeded.editSession != null) {
                    SchedulerReducer.reduce(seeded, SchedulerIntent.CancelEdit)
                } else {
                    seeded
                }
            return withDefaultTaskTree(settled)
        }

        /**
         * PRD §5 task-tree selector: an account that has never named its tree gets one, titled
         * `tree-YYYY-MM-DD` for today ([SchedulerDomain.defaultTaskTreeTitle]) — so the field above the tree
         * is never empty at first startup, and the row the identity menu always leads with *is* that tree
         * rather than an offer to create it.
         *
         * Applied structurally rather than through [SchedulerIntent.CreateTaskTree]: a default is not
         * something the user did, so it records no History Unit and Ctrl+Z cannot land on it — exactly like
         * the sleep-window and screen-break seeding above. It captures the tree as loaded, so the entry is
         * current from the start, and it does not mark the state dirty (every caller re-baselines
         * `lastSyncedFingerprint` from the prepared state), so it reaches the account with the user's next
         * real edit instead of provoking a push of its own.
         */
        private fun withDefaultTaskTree(state: SchedulerState): SchedulerState {
            if (state.taskTrees.isNotEmpty() || state.activeTaskTreeId != null) return state
            val (id, allocated) = state.allocateTaskTreeId()
            val entry =
                TaskTreeEntry(
                    id = id,
                    title = SchedulerDomain.defaultTaskTreeTitle(SchedulerReducer.clock.nowMillis()),
                    tree = state.captureTreeWithRecords(),
                    expanded = state.expanded,
                )
            return allocated.copy(taskTrees = listOf(entry), activeTaskTreeId = id)
        }
    }
}
