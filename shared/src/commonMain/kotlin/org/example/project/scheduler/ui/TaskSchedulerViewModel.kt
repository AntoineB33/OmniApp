package org.example.project.scheduler.ui

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.persistence.PersistedSnapshot
import org.example.project.scheduler.persistence.SchedulerStateCodec
import org.example.project.scheduler.persistence.SchedulerStore
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState
import org.example.project.scheduler.sync.PauseCueGateway
import org.example.project.scheduler.sync.PresenceGateway
import org.example.project.scheduler.sync.SchedulerSyncEngine
import org.example.project.scheduler.sync.ServerSyncThrottle
import org.example.project.scheduler.sync.ActiveSessionGateway
import org.example.project.scheduler.sync.SleepGapGateway
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
) : ViewModel() {
    // PRD §5 Initialization: load from local persistence when present, otherwise start
    // from the empty DB (root → main).
    private val _state = MutableStateFlow(loadInitialState(store, initial))
    val state: StateFlow<SchedulerState> = _state.asStateFlow()

    /** PRD §5 sync status for an indicator; null when sync is disabled. */
    val syncState: StateFlow<SyncState>? = syncEngine?.state

    /** PRD §15 cross-device presence channel for the scheduler engine; null when sync is disabled. */
    val presence: PresenceGateway? get() = syncEngine

    /** PRD §15 device-sleep gaps channel (push/pull exact pauses) for the engine; null when sync is disabled. */
    val sleepGaps: SleepGapGateway? get() = syncEngine

    /** PRD §15 device-active sessions channel (push activity / pull server-derived pauses); null when sync is off. */
    val activeSessions: ActiveSessionGateway? get() = syncEngine

    /** PRD §15 / ARCHITECTURE.md §8 pause-end cue delivery channel for the engine; null when sync is disabled. */
    val pauseCue: PauseCueGateway? get() = syncEngine

    // PRD §5 Persistence: every change updates the in-memory state immediately; the SQLite DB is then
    // updated on a debounce so a burst of edits (e.g. typing a cell) collapses into one write instead
    // of one per keystroke.
    private val saveScope = CoroutineScope(SupervisorJob() + saveDispatcher)
    private var saveJob: Job? = null

    // ARCHITECTURE.md §8: the local SQLite write coalesces on the small [SAVE_DEBOUNCE_MILLIS] debounce, but
    // the network push that mirrors it to Supabase is rate-limited to at most once per minute (idle → sent
    // immediately). Only created when sync is enabled.
    private val serverSync: ServerSyncThrottle? =
        syncEngine?.let { engine ->
            ServerSyncThrottle(saveScope, SERVER_SYNC_INTERVAL_MILLIS) { engine.reconcile() }
        }

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

    init {
        syncEngine?.let { engine ->
            // Break the engine<->ViewModel cycle: the engine reads/writes our state through these.
            engine.bind(
                localSnapshot = { SchedulerStateCodec.encodeSnapshot(_state.value) },
                applyRemote = { snapshot -> applyRemoteSnapshot(snapshot) },
            )
            engine.restoreSession()
            // Non-interactive launch (per-account `/scripts`): when no session was restored and startup
            // credentials were supplied, sign in to that account. signIn() reconciles, so we skip the plain
            // reconcile below in that case.
            val creds = if (!engine.isSignedIn) startupLogin() else null
            if (creds != null) {
                signIn(usernameToEmail(creds.username), creds.password)
            } else {
                // PRD §5 Initialization: pull any newer remote state on launch (no-op when signed out/offline).
                saveScope.launch { engine.reconcile() }
            }
        }
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
     * Every other intent — tree edits, pinned/user panels, reminders, sleep/settings, history, and the MANUAL
     * record edits `RemoveRecordPeriod` / `PinRecordAsPanel` — is authoritative and syncs (subject to the
     * fingerprint gate in [pushIfAuthoritativeChanged]).
     */
    private fun SchedulerIntent.syncsToServer(): Boolean =
        when (this) {
            is SchedulerIntent.AdvanceSchedule,
            is SchedulerIntent.RefreshSchedule,
            is SchedulerIntent.ReportDeviceSleep -> false
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
                // before the push still reconciles next launch). The push itself is throttled to once per minute
                // (ARCHITECTURE.md §8); markDirty keeps it durable until it goes out.
                if (syncPending) {
                    syncPending = false
                    pushIfAuthoritativeChanged(requestPush = true)
                }
            }
    }

    /**
     * Marks the sync engine dirty and (optionally) requests a push, but only when the authoritative
     * projection of the current state ([SchedulerStateCodec.syncFingerprint]) differs from what was last
     * flagged. A no-op when sync is disabled or when nothing authoritative changed — which is the case for an
     * engine-tick reschedule that only re-derived the auto/side/sleep panels. [requestPush] is false from
     * [flush] (at close there is no time to push; the persisted dirty flag drives the next launch's reconcile).
     */
    private fun pushIfAuthoritativeChanged(requestPush: Boolean) {
        val engine = syncEngine ?: return
        val fingerprint = SchedulerStateCodec.syncFingerprint(_state.value)
        if (fingerprint == lastSyncedFingerprint) return
        lastSyncedFingerprint = fingerprint
        engine.markDirty()
        if (requestPush) serverSync?.request()
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
        // A change within the debounce window never reached the push above; flag it (only if it was an
        // authoritative, user-authored change) so the next launch's reconcile sends it. No-op when the debounced
        // save already accounted for it (savePending false), when only schedule-advance ticks ran (syncPending
        // false), or when nothing authoritative changed.
        if (savePending && syncPending) {
            pushIfAuthoritativeChanged(requestPush = false)
        }
        savePending = false
        syncPending = false
    }

    // ---- PRD §5 cross-device sync controls (no-ops when sync is disabled) ----

    /** Signs in and immediately reconciles. Errors surface via [syncState]; never throws to the caller. */
    fun signIn(email: String, password: String) =
        saveScope.launch { runCatching { syncEngine?.signIn(email, password); syncEngine?.reconcile() } }

    /** Registers a new account, then reconciles. Errors surface via [syncState]. */
    fun signUp(email: String, password: String) =
        saveScope.launch { runCatching { syncEngine?.signUp(email, password); syncEngine?.reconcile() } }

    /** Forgets the local session (does not delete remote data). */
    fun signOut() = syncEngine?.signOut()

    /** Manual sync trigger (e.g. a "Sync now" button / window-focus). */
    fun syncNow() = saveScope.launch { syncEngine?.reconcile() }

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

        /**
         * ARCHITECTURE.md §8: minimum spacing between **server** pushes. A change ≥ this long after the
         * previous push goes out immediately; a burst within the window coalesces into one deferred push.
         */
        private const val SERVER_SYNC_INTERVAL_MILLIS = 60_000L

        /** PRD §5: reload persisted state; an interrupted Edit Mode session is canceled. */
        fun loadInitialState(store: SchedulerStore?, initial: SchedulerState): SchedulerState =
            prepareLoadedState(store?.load()?.let(SchedulerStateCodec::decodeSnapshot) ?: initial)

        /**
         * Normalizes a just-loaded state (from local disk or a pulled remote snapshot) for the running app:
         * revert debug-tainted changes (§6), seed the hardcoded side tasks and default sleep window (§15),
         * and cancel any interrupted Edit Mode session.
         */
        fun prepareLoadedState(loaded: SchedulerState): SchedulerState {
            // PRD §6: revert any changes committed under the diverged debug clock before they reach the
            // running app, so a fast-forwarded session never pollutes the real saved data on restart.
            val clean = SchedulerReducer.rollbackDebugTainted(loaded)
            // PRD §15: side tasks are a hardcoded set (not persisted user data); seed them onto whatever
            // was loaded so they are always present in the running app, never in the bare test states.
            // The sleep schedule is seeded with the default only when none was persisted, so production
            // always has a sleep window (07:30 / 8h30) while bare test states keep `sleep = null`.
            val seeded =
                clean.copy(
                    sideTasks = SchedulerDomain.DEFAULT_SIDE_TASKS,
                    sleep = clean.sleep ?: SchedulerDomain.DEFAULT_SLEEP,
                )
            return if (seeded.editSession != null) {
                SchedulerReducer.reduce(seeded, SchedulerIntent.CancelEdit)
            } else {
                seeded
            }
        }
    }
}
