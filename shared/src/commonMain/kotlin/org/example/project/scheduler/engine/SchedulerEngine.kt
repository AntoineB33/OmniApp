package org.example.project.scheduler.engine

import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.example.project.DebugFlags
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.SideTask
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.model.TaskPanel
import org.example.project.scheduler.model.TaskTimeRange
import org.example.project.scheduler.persistence.ActiveSessionRecord
import org.example.project.scheduler.persistence.ActiveSessionStore
import org.example.project.scheduler.persistence.DeviceSleepGapStore
import org.example.project.scheduler.persistence.SleepGapRecord
import org.example.project.scheduler.persistence.SleepScanCheckpointStore
import org.example.project.scheduler.platform.DeviceKind
import org.example.project.scheduler.platform.DeviceSleepGap
import org.example.project.scheduler.platform.Diagnostics
import org.example.project.scheduler.platform.currentDeviceKind
import org.example.project.scheduler.platform.isScreenActive
import org.example.project.scheduler.platform.sendSystemNotification
import org.example.project.scheduler.platform.lastWakeAfterLongSleepMillis
import org.example.project.scheduler.platform.recentSleepGaps as platformRecentSleepGaps
import org.example.project.scheduler.platform.VoiceCue
import org.example.project.scheduler.platform.playVoiceCue as platformPlayVoiceCue
import org.example.project.scheduler.platform.stopSpeaking
import org.example.project.scheduler.sync.PauseCueGateway
import org.example.project.scheduler.sync.PresenceState
import org.example.project.scheduler.sync.RealtimePresence
import org.example.project.scheduler.sync.RealtimePresenceClient
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState
import org.example.project.scheduler.ui.TaskSchedulerViewModel
import org.example.project.time.AppClock
import org.example.project.time.SimAppClock
import org.example.project.time.SystemAppClock

// PRD §15: the furthest back (in sim time) the boundary sweeps scan, purely a scan/bookkeeping bound — it
// exceeds the fastest sim speed's reach between sweeps (300× advances 300 s over the 1 s tick) so a live
// fast-forward never clips a crossing out of the scan. Whether a scanned crossing actually FIRES is decided
// separately, by its real-time age ([BoundarySweep] vs [LOOK_AWAY_START_FRESH_MILLIS]) — never by this cap.
private const val LOOK_AWAY_SWEEP_CAP_MILLIS: Long = 10L * 60 * 1_000

// How fresh a boundary crossing must be — a budget in **real** time, measured DIRECTLY ([BoundarySweep]),
// never approximated by sim distance — for its user-audible output (notification / voice cue) to fire.
// A crossing older than this means the process could not run when it happened (device asleep, app
// suspended), where a late cue is noise; a crossing the running engine merely *observed* late (accelerated
// clock, a time-link re-anchor jump, a busy main thread) measures fresh by real age and always fires — the
// trigger is a function of the timeline, not of how the sweep wake-ups align with the calendar.
private const val LOOK_AWAY_START_FRESH_MILLIS: Long = 2_000

// Real-time cap on each sleep while the manual "Look away now" rest counts down (see [restartLookAway]).
private const val LOOK_AWAY_RESUME_POLL_MILLIS: Long = 200

// PRD §12/§15 device-sleep detection: the *real*-time gap between two advance ticks that means the process was
// suspended (the device slept). It is the production tick cadence × 3 — a fixed REAL duration that does NOT
// scale with the (possibly accelerated) sim tick rate, so device inactivity is detected by the same ~90 s real
// gap at every speed.
private const val DEVICE_SLEEP_THRESHOLD_MILLIS: Long = 90L * 1_000

// PRD §15 device-sleep gaps: how far before a detected sleep's coarse start the OS sleep/wake log is scanned
// for the EXACT interval(s) to record. A margin past the tick-gap boundary so a sleep that began a little
// before the last tick (up to one tick cadence) is still captured.
private const val GAP_QUERY_MARGIN_MILLIS: Long = 5L * 60 * 1_000

// PRD §15 device-sleep gaps: how far back the launch backfill scans this device's OS sleep/wake log to publish
// past sleeps as synced gaps. Only the most-recent qualifying rest actually reseeds a pose (the 5/15-min poses
// recur ≤2h apart), so a few days amply covers "the sleep that seeded my poses" while staying idempotent.
private const val SLEEP_GAP_BACKFILL_HORIZON_MILLIS: Long = 3L * 24 * 60 * 60 * 1_000

// PRD §15: stand-in device id for gaps recorded while sync is disabled / signed out, so a local-only install
// still tags its rows with something stable (it never reaches the remote table).
private const val LOCAL_DEVICE_ID: String = "local"

// PRD §15: how often the active-session beat samples `isScreenActive()` and extends the current session in the
// LOCAL store. Mirrors the advance-tick cadence (1 s under sim / 30 s in production) so the session timeline
// tracks the same `now` the schedule does. The beat NEVER talks to the server — opens, extends and finalizes
// are all local-only; the rows ride the next manual Sync-button reconcile.
private const val ACTIVE_SESSION_BEAT_MILLIS_SIM: Long = 1_000
private const val ACTIVE_SESSION_BEAT_MILLIS_PROD: Long = 30L * 1_000

// The PHONE's activity model: the app being in the FOREGROUND is the only activity signal, expressed as a
// one-minute LEASE — each beat (every [PHONE_SESSION_LEASE_MILLIS] in production) claims activity from `now`
// to `now + 1 min`, so consecutive beats merge into one continuous session and a backgrounded/killed app
// reads as inactive within a minute without relying on a finalize ever running. The lease deliberately
// over-reports by up to one minute after the last renewal — that IS the spec's granularity.
private const val PHONE_SESSION_LEASE_MILLIS: Long = 60L * 1_000

// The now-line display cadence while time is ACCELERATED. Fine enough that the now-line glides instead of
// jumping once per schedule tick (the reported x300 "jumps" anomaly) — on the phone as well as the desktop.
// The phone's clock is accelerated via the desktop time-link even though DebugFlags.TIME_SIMULATION is off
// there, so every accelerated cadence keys off the clock's actual speed ([timeAccelerated]), not the flag.
private const val ADVANCE_DISPLAY_MILLIS_ACCEL: Long = 50
// The now-line display cadence at REAL time (1×): the production advance/poll cadence.
private const val ADVANCE_TICK_MILLIS_PROD: Long = 30L * 1_000
// The finer cadence used for the schedule-advance dispatch and the horizon/active-session polls while
// accelerated (the display now-line moves faster still, every [ADVANCE_DISPLAY_MILLIS_ACCEL]).
private const val ADVANCE_TICK_MILLIS_ACCEL: Long = 1_000
// Coarsest sim-time between schedule advances (banking auto records / re-deriving panels). The display
// now-line moves every display tick; the heavier advance only needs ~1 s-of-sim granularity — banking
// records at sub-second sim resolution is pointless and just churns the reducer.
private const val SCHEDULE_ADVANCE_STEP_MILLIS: Long = 1_000

// PRD §12/§15: the window the server derivation (and its local fallback) considers — the last 168 hours, the
// same horizon §9/§12 use for "the calendar the user can change". Older pauses age out of the bands.
private const val PAUSE_DERIVE_HORIZON_MILLIS: Long = 168L * 60 * 60 * 1_000

/**
 * Bundle of the single process-shared [TaskSchedulerViewModel] + its already-started [SchedulerEngine],
 * handed to `App()` so the Android foreground service and the Activity render/drive one source of truth
 * (one state, one notification stream). Null on platforms where `App()` creates them itself.
 */
class AppSchedulerHost(val vm: TaskSchedulerViewModel, val engine: SchedulerEngine)

/** PRD §13: a compact `HH:MM` label for a schedule-unit step deadline in the task-switch notification. */
private fun formatClockTime(dateTime: LocalDateTime): String {
    val hh = dateTime.hour.toString().padStart(2, '0')
    val mm = dateTime.minute.toString().padStart(2, '0')
    return "$hh:$mm"
}

/**
 * The headless scheduling engine: the time/notification/voice loops that used to live in `App.kt`'s
 * Compose `LaunchedEffect`s, lifted into a plain coroutine-driven object so they can run **without a UI**
 * (in the Android foreground service) and, when the UI is present, drive it from the same single source of
 * truth. Each former `LaunchedEffect(key)` becomes a `collectLatest` over a `distinctUntilChanged` flow of
 * that key — identical "cancel the prior run when the key changes" semantics — or a one-shot `launch` for
 * the keyless effects.
 *
 * It owns the advancing [nowMillis] (which the UI observes for the now-line/calendar), drives the §9
 * reschedule events, and posts the §11/§13/§15 notifications and §15 voice cues. The bookkeeping that was
 * `remember`ed in the composable is now plain fields, so it survives a `collectLatest` restart exactly as
 * the `remember`ed state survived a re-key.
 *
 * [clock] must be the same clock the UI shell reads (the sim clock under time simulation, else
 * [org.example.project.time.SystemAppClock]); [scope] outlives the UI (the service scope on Android, the
 * app-lifetime composition scope on desktop). Call [start] exactly once.
 */
class SchedulerEngine(
    private val vm: TaskSchedulerViewModel,
    private val clock: AppClock,
    private val scope: CoroutineScope,
    private val tz: TimeZone = TimeZone.currentSystemDefault(),
    // PRD §15: what kind of device this is — only the phone speaks the "pause finished" cue. Injectable for tests.
    private val deviceKind: DeviceKind = currentDeviceKind(),
    // PRD §15: whether this device's screen is active right now. Injectable for tests.
    private val screenActive: () -> Boolean = ::isScreenActive,
    // PRD §15: the voice sink (defaults to the platform player of the bundled shared cue audio); injectable so
    // cues are assertable in tests.
    private val playCue: (VoiceCue) -> Unit = ::platformPlayVoiceCue,
    // PRD §15 device-sleep gaps: LOCAL-ONLY store for the exact pause intervals read from the OS sleep log;
    // null disables gap recording. These feed this device's own rest-pose seeding / Inactivity bands only —
    // they are NEVER synced any more (the cross-device sleep-gap channel is retired; see CLAUDE.md).
    private val sleepGapStore: DeviceSleepGapStore? = null,
    // PRD §15: the OS sleep/wake-log query (defaults to the platform reader); injectable for tests.
    private val sleepGapQuery: (Long) -> List<DeviceSleepGap> = ::platformRecentSleepGaps,
    // PRD §15 device-sleep gaps: LOCAL-ONLY watermark of how far the OS sleep/wake log has been scanned, so the
    // launch backfill resumes instead of re-reading the full 3-day horizon each launch; null re-scans it fully.
    private val sleepScanCheckpoint: SleepScanCheckpointStore? = null,
    // PRD §15: store for the active-session intervals — this device's own rows (the beat writes them) plus
    // the peers' rows the manual Sync button pulls in. The input to the "Inactivity" bands / "Sleep"-band
    // carve / live-rest placement and the calendar's per-panel device sets. Null disables activity tracking.
    // The beat itself never talks to the server; rows travel ONLY inside the Sync-button reconcile. LIVE
    // cross-device presence stays Supabase Realtime, watched by the external listener.
    private val activeSessionStore: ActiveSessionStore? = null,
    // Push-token / last-phone channel (the sync engine) for the Realtime-presence + listener cue delivery:
    // only a phone registers its FCM/APNs token and claims the account's last phone so the listener can reach
    // it. Null disables it. This is the ONLY remaining server side-channel and is event-driven, never a timer.
    private val pauseCue: PauseCueGateway? = null,
    // PRD §15: the platform OS-scheduled local cue seam — schedule the "pause is over" alarm at the given
    // instant, or cancel the pending one when null. Default no-op (desktop/tests); the phone wires AlarmManager
    // / UNUserNotificationCenter here (see docs/PAUSE_CUE_DELIVERY.md). Driven by the listener's push
    // ([onPauseCuePush]); the alarm's fire handler runs [onPauseCueFire] before speaking.
    private val scheduleLocalPauseCue: (Long?) -> Unit = {},
    // PRD §15: true on a platform that delivers the pause-end cue via [scheduleLocalPauseCue] + [onPauseCueFire]
    // (the OS-scheduled alarm, which fires even if the app was killed). When true the older in-app
    // pose-finish cue is skipped so the phone never speaks the cue twice. Default false keeps the in-app path
    // for desktop / not-yet-wired platforms.
    private val localPauseCueDelivery: Boolean = false,
) {
    private val _nowMillis = MutableStateFlow(clock.nowMillis())

    /** The advancing "now" (epoch millis); the UI collects this for display. */
    val nowMillis: StateFlow<Long> = _nowMillis.asStateFlow()

    // PRD §15 server-derived pauses: the account-wide pauses (windows when NO device was active), surfaced for
    // the calendar to draw as greyed "Inactivity" bands. Display-only and derived — by the server from every
    // device's active sessions (or locally from this device's own sessions when signed out) — so it is never
    // persisted anew. Refreshed on start, on the event triggers, and on a periodic re-derive.
    private val _inactivityGaps = MutableStateFlow<List<TaskTimeRange>>(emptyList())

    /** The account-wide pauses as time ranges; the UI collects these for the calendar's "Inactivity" bands. */
    val inactivityGaps: StateFlow<List<TaskTimeRange>> = _inactivityGaps.asStateFlow()

    // PRD §15/§17: the start of this device's currently-OPEN active session (null while inactive). The UI pairs
    // it with the advancing now-line to carve the "Sleep" band right up to the present as the user keeps working
    // through a scheduled sleep window — a continuous, LOCAL-ONLY, non-syncing retraction. It only reveals a
    // structural change (a session opened/finalized), never a per-tick edge, so it never triggers a push; peers
    // learn of the same activity later, when an unrelated sync moment carries this device's active-session rows.
    private val _activeSince = MutableStateFlow<Long?>(null)

    /** Start of this device's open active session (null while inactive); the UI carves the "Sleep" band to now. */
    val activeSince: StateFlow<Long?> = _activeSince.asStateFlow()

    // PRD §15: the end of this device's LAST FINALIZED session — the locally-observed start of a pause the
    // derived gaps ([_inactivityGaps]) may not cover yet, because a derive only runs at the sync moments.
    // The UI pairs it with the now-line (or, once the user is back, the reopened session's start) to render
    // the "Inactivity" band growing live behind an advancing now-line — the mirror of [activeSince]'s live
    // sleep retraction: display-only, non-syncing, moved only by a structural session event (a finalize),
    // never per tick. Deliberately NOT cleared when a session reopens (the band would flicker out until the
    // next derive re-covers the pause); instead the first derive that completes with a session open again
    // retires it (see [refreshDerivedPausesNow]) — so a stale local presumption can never outlive the
    // server's account-wide answer, which may legitimately exclude the window (a peer was active).
    private val _inactiveSince = MutableStateFlow<Long?>(null)

    /** End of this device's last finalized session — the live "Inactivity" tail's start (null = none pending). */
    val inactiveSince: StateFlow<Long?> = _inactiveSince.asStateFlow()

    // Every stored active session — this device's own AND the peers' rows the Sync-button reconcile pulled
    // in. The calendar reads these to label past panels with which devices were open (hover bubble) and to
    // draw the dashed separators where the device set changed. Display-only and local: loaded from the store
    // at startup / after each derive-refresh, and patched in-memory as the beat extends the open session.
    private val _activeSessions = MutableStateFlow<List<ActiveSessionRecord>>(emptyList())

    /** All stored active sessions (own + pulled peers); the UI derives the per-panel device sets from these. */
    val activeSessions: StateFlow<List<ActiveSessionRecord>> = _activeSessions.asStateFlow()

    // PRD §15 server-derived pauses: this device's currently-open active session (null while inactive), tracked
    // in the LOCAL store only. Mutated only under [activeSessionMutex] because the beat loop, the debug
    // forced-inactivity flip ([setDebugForcedInactive]), and [freshenOpenSession] all advance it.
    private var currentSession: ActiveSessionRecord? = null
    private val activeSessionMutex = Mutex()

    // Debug "simulate pause + leap": while true, every screen-activity sample reads INACTIVE, exactly as if
    // the user had walked away — the active-session beat finalizes the open session (so a pause derives),
    // presence stops announcing, and the pose-finish gate sees the screen off. Set around the accelerated
    // leap by the desktop debug control, and on a linked phone by the time-link frames' inactive flag.
    private var debugForcedInactive = false

    // PRD §7: a §9 calculation event that comes due while "Auto schedule" is off is deferred and coalesced
    // into a single reschedule fired when the switch is turned back on.
    private var pendingReschedule = false

    // PRD §11/§15 notification de-dupe (see the long-form rationale in git history of App.kt).
    private var lastNotifiedTaskId: TaskId? = null

    // PRD §15 (20s look-away) / wind-down bookkeeping; survives a collectLatest restart like the old remember.
    private var announcedStarts = setOf<Long>()
    private var pendingEnds = setOf<Long>()
    private var announcedWindDowns = setOf<Long>()
    // PRD §15 (5/15-min rest-pose notification): the DUE instant (`lastRest + interval`) already notified per
    // rest-pose title, so a break fires once when the now-line reaches it and stays silent as its panel drags
    // along the now-line — the next cue comes only after `due` steps forward (the break is served). See
    // [launchCueSweep].
    private var sidePoseNotifiedDue = mapOf<String, Long>()
    private var manualLookAwayJob: Job? = null

    // Real-time lateness bookkeeping, one per sweep loop (see [BoundarySweep]): the fire/stale decision for
    // every crossed boundary is exact real-clock arithmetic, never a sim-distance heuristic, so the triggers
    // do not depend on how the sweeps' wake-ups align with the calendar. Fields (not locals) so a
    // collectLatest re-key never resets the previous-sweep anchor.
    // One sweep for the whole ordered cue pass (look-away, wind-down; the rest-pose is a level reach with no
    // staleness gate).
    private val cueSweep = BoundarySweep()

    // The sim clock's reconfiguration generation (speed change / leap / time-link re-anchor); any constant
    // for a non-sim clock, whose run is always continuous.
    private fun clockGeneration(): Long = (clock as? SimAppClock)?.reconfigured?.value ?: 0L

    // PRD §15: the Realtime-Presence publisher — this device announces itself active (with its next break-end)
    // over a WebSocket while signed in + screen-on, so the external listener knows the account's live activity.
    // Constructed in [start] from the [pauseCue] gateway (which carries the Realtime URL/key + session auth);
    // null when sync is disabled or on a platform without an injected gateway.
    private var realtimePresence: RealtimePresence? = null

    private var started = false

    fun start() {
        if (started) return
        started = true
        Diagnostics.log("engine started (device=$activeSessionDeviceId)")
        pauseCue?.let { gateway ->
            realtimePresence = RealtimePresenceClient(
                scope = scope,
                realtimeUrl = gateway.realtimeUrl,
                apiKey = gateway.realtimeApiKey,
                deviceId = gateway.deviceId,
                auth = { gateway.realtimeAuth() },
                refreshAuth = { gateway.refreshRealtimeAuth() },
            ).also { it.start() }
        }
        // PRD §15: every reducer refill folds this device's live ongoing/held pause into side-task
        // PLACEMENT (SchedulerDomain.sideTasksForPlacement), so side-task panels slide with a pause the
        // derives haven't banked yet — the now-line can no longer cross a stale look-away slot and fire a
        // spurious cue mid-pause, and a rest pose re-places itself the moment the pause has lasted its
        // duration (fluid under an accelerated leap; no post-leap snap when the derive lands). Placement-
        // only: the stored lastRestMillis still advances only via the derives' forward-only seeding
        // ([applySeededSideTasks]).
        SchedulerReducer.liveRestGap = {
            SchedulerDomain.liveRestGap(_inactiveSince.value, _activeSince.value, clock.nowMillis())
        }
        launchAdvanceTick()
        launchSideTaskSeeding()
        launchTreeChangeReschedule()
        launchHorizonReschedule()
        launchPendingRescheduleOnSwitch()
        // PRD §15 / CLAUDE.md "each fires exactly once, in order": ONE now-line sweep drives the
        // task-switch, look-away, rest-pose and wind-down cues, ordered by their true boundary instants —
        // so a single leap that crosses several boundaries fires them chronologically (the
        // look-away whose boundary precedes a rest-pose due is announced first), instead of racing four
        // independent collectors.
        launchCueSweep()
        // PRD §15: on startup, scan this device's OS sleep log into the LOCAL gaps store, which seeds this
        // device's own rest poses / Inactivity bands. Local-only — nothing is pushed or pulled (the
        // cross-device sleep-gap channel is retired).
        backfillSleepGaps()
        // PRD §15: track this device's active sessions locally; the rows ride the manual Sync button (push
        // own / pull peers), feeding the "Inactivity" bands, the "Sleep"-band carve, the live-rest placement
        // and the calendar's per-panel device sets. [purgeLegacyAdoptedRows] heals old DBs that still hold
        // the retired adopted remote-activity rows.
        purgeLegacyAdoptedRows()
        launchActiveSessionTracking()
        refreshDerivedPauses()
        // The manual Sync button is the only path that can pull PEER session rows into the local store (the
        // reconcile's per-row active-session merge); after every reconcile, reload the stored sessions and
        // re-derive the Inactivity bands so the pulled activity shows at once. Fires on every reconcile
        // outcome and the refresh is local-only, so a failed/push-only reconcile just re-derives in place.
        pauseCue?.syncMoments?.let { moments -> scope.launch { moments.collect { refreshDerivedPausesNow() } } }
        // PRD §15 / listener cue delivery: a phone's startup claims the account's last phone so the external
        // listener knows which phone to push. Phones only; a no-op when sync is disabled / signed out.
        claimLastPhoneOnStartup()
        resolveSleepModeOnStartup()
    }

    // Sleep/Work toggle: if the user pressed "Sleep" and the scheduled wake instant has already passed by the
    // time the app (re)starts, reset the button to "Sleep" (working) and tell the server. While the wake
    // instant is still in the future the sleeping state persists across the restart, so the user needn't
    // re-press "Sleep" after briefly reopening the app during the night (see the CLAUDE.md sleep-button note).
    private fun resolveSleepModeOnStartup() {
        val until = vm.state.value.sleepingUntilMillis ?: return
        if (clock.nowMillis() >= until) vm.setSleepMode(null)
    }

    // True when the now-line advances faster than real time — the desktop time-sim, OR a phone whose clock is
    // driven above 1× by the desktop time-link (where DebugFlags.TIME_SIMULATION is off). It drives the fine
    // tick cadences so the now-line/schedule keep up smoothly instead of jumping once per production tick. The
    // desktop debug flag forces it true even at 1× so the sim panel's manual leaps still refresh promptly. Read
    // per loop-iteration because the user changes the speed (e.g. clicks x300) at runtime.
    private fun timeAccelerated(): Boolean =
        DebugFlags.TIME_SIMULATION || ((clock as? SimAppClock)?.speed ?: 1.0) > 1.0

    // Delay [millis], but wake early when the clock is reconfigured (a speed change or leap — e.g. the desktop
    // pushing x300 to the phone over the time-link). A poll loop that chose the coarse production cadence at 1×
    // would otherwise finish a full ~30 s sleep before noticing acceleration turned on and switching to the
    // fine display cadence — the reported "the phone's now-line lags the desktop's by ~30 s" anomaly. Reading
    // the generation before the wait then waiting for it to differ never misses a bump raced in between
    // (a StateFlow replays its current value to a fresh collector). No-op fallback on a non-sim clock.
    private suspend fun tickDelay(millis: Long) {
        val sim = clock as? SimAppClock ?: return delay(millis)
        val gen = sim.reconfigured.value
        withTimeoutOrNull(millis) { sim.reconfigured.first { it != gen } }
    }

    // PRD §9: the single "time has advanced to `now`" step — see the original `advanceTo` in App.kt. Moves the
    // display now-line AND advances the schedule (the leap/device-sleep path wants both at once).
    private fun advanceTo(now: Long) {
        _nowMillis.value = now
        dispatchScheduleAdvance(now)
    }

    // PRD §9: bank elapsed auto records / re-derive panels up to `now`. Split out from [advanceTo] so the fluid
    // display tick can move the now-line every frame while dispatching this heavier step only every
    // [SCHEDULE_ADVANCE_STEP_MILLIS]. The reducer no-ops (returns the same state) until a panel actually elapses,
    // so an over-eager call is cheap; the step guard just keeps the reducer from churning under acceleration.
    private fun dispatchScheduleAdvance(now: Long) {
        val current = vm.state.value
        // A refill (not just an advance) is needed when a restBreak pose is overdue (it re-pins to the
        // now-line), or when the live pause overlay would move a side task's placement — an ongoing gap
        // keeps re-presuming/re-satisfying, so the projected grid moves ahead of the racing now-line
        // instead of being crossed at (or frozen behind) a stale slot. RefreshSchedule is a non-syncing
        // intent, so this refill makes zero server writes (sync-gating rule).
        val liveRest = SchedulerDomain.liveRestGap(_inactiveSince.value, _activeSince.value, now)
        val sideTaskDue =
            current.automaticSchedule &&
                (
                    current.sideTasks.any { it.restBreak && SchedulerDomain.isSideTaskOverdue(it, now) } ||
                        SchedulerDomain.sideTasksForPlacement(current.sideTasks, liveRest) != current.sideTasks
                    )
        if (sideTaskDue) {
            vm.dispatch(SchedulerIntent.RefreshSchedule(now))
        } else {
            vm.dispatch(SchedulerIntent.AdvanceSchedule(now))
        }
    }

    /**
     * Debug "simulate pause + leap": force this device to read as screen-inactive (`true`) or return to the
     * real platform sensor (`false`). Flipping it advances the active session immediately — finalizing the
     * open session on `true` (locally, like a real walk-away) and reopening one on `false` — so the debug
     * instant leap never depends on the beat cadence to see the pause boundaries. The finalized bounds feed
     * this device's own Inactivity bands via the debug control's explicit [refreshDerivedPauses] after the
     * leap. Called by the desktop debug control, and per time-link frame on a linked phone (a same-value call
     * is a no-op, so the 250 ms frames don't churn).
     */
    fun setDebugForcedInactive(inactive: Boolean) {
        if (debugForcedInactive == inactive) return
        debugForcedInactive = inactive
        scope.launch {
            advanceActiveSession(clock.nowMillis(), effectiveScreenActive(), suspended = false)
        }
    }

    // The screen-activity sample every engine site reads: the real platform sensor, overridden to inactive
    // while the debug leap forces it (see [setDebugForcedInactive]).
    private fun effectiveScreenActive(): Boolean = !debugForcedInactive && screenActive()

    // Diagnostics-instrumented seams for every user-audible output: each posted notification and played voice
    // cue lands in the cross-device timeline with the sim instant it fired at, so "this device stayed silent
    // through that break" is answerable from scripts/collect-diagnostics.bat instead of a live repro.
    private fun notifyUser(title: String, message: String) {
        val now = clock.nowMillis()
        Diagnostics.log(
            "notification [$title] ${message.replace('\n', ' ')} " +
                "(sim now=${Diagnostics.formatInstant(now)})",
        )
        // Append to the History Manager's local-only Notifications column (capped, non-syncing).
        vm.dispatch(SchedulerIntent.RecordNotification(title, message, now))
        sendSystemNotification(title, message)
    }

    private fun speakCue(cue: VoiceCue) {
        Diagnostics.log("voice cue ${cue.name} (sim now=${Diagnostics.formatInstant(clock.nowMillis())})")
        playCue(cue)
    }

    /**
     * PRD §12: a gap in time `[sleepStart, sleepEnd]` — the process was suspended (real device sleep) or a
     * debug leap jumped the clock over it.
     */
    fun reportTimeGap(sleepStart: Long, sleepEnd: Long) {
        vm.dispatch(SchedulerIntent.ReportDeviceSleep(sleepStart, sleepEnd))
        advanceTo(sleepEnd)
    }

    // PRD §9: the advance tick + PRD §12 device-sleep detection (real-time gap → inject a hole).
    //
    // Two cadences, so an accelerated now-line GLIDES instead of jumping once per tick (the x300 "jumps"
    // anomaly, worst on the phone where the production 30 s tick made each jump ~2.5 h of sim time):
    //  • the display now-line ([_nowMillis], which the UI collects) moves every [ADVANCE_DISPLAY_MILLIS_ACCEL];
    //  • the heavier schedule advance (banking auto records / re-deriving panels) fires only once the clock has
    //    moved [SCHEDULE_ADVANCE_STEP_MILLIS] of sim time — no point banking records at sub-second sim res.
    // At real time (1×) both collapse to the single production cadence, so production behaviour is unchanged.
    private fun launchAdvanceTick() = scope.launch {
        var lastRealTick = SystemAppClock.nowMillis()
        var lastClockTick = clock.nowMillis()
        // Seed one step behind so the first non-sleep iteration dispatches immediately (as the old tick did).
        var lastScheduleAdvance = lastClockTick - SCHEDULE_ADVANCE_STEP_MILLIS
        while (true) {
            val realNow = SystemAppClock.nowMillis()
            val now = clock.nowMillis()
            if (realNow - lastRealTick > DEVICE_SLEEP_THRESHOLD_MILLIS) {
                // The schedule resumes immediately from the coarse tick boundaries (unchanged); separately,
                // the OS sleep/wake log is queried off-thread for the EXACT pause interval(s) to record and
                // sync (PRD §15) — so a ping that was due mid-pause but never sent (the machine was down)
                // becomes an exact gap other devices can pull.
                reportTimeGap(lastClockTick, now)
                recordExactSleepGaps(lastClockTick, now)
                lastScheduleAdvance = now
            } else {
                // Display: always glide the now-line to the current clock instant.
                _nowMillis.value = now
                // Schedule: bank records / re-derive panels only on the coarser sim-time step.
                if (now - lastScheduleAdvance >= SCHEDULE_ADVANCE_STEP_MILLIS) {
                    dispatchScheduleAdvance(now)
                    lastScheduleAdvance = now
                }
            }
            lastRealTick = realNow
            lastClockTick = now
            tickDelay(if (timeAccelerated()) ADVANCE_DISPLAY_MILLIS_ACCEL else ADVANCE_TICK_MILLIS_PROD)
        }
    }

    // PRD §15: at launch, seed each side task's last-rest time from the last qualifying pause — this device's
    // OS sleep log (Windows) AND every device's synced sleep gaps already in the local store. The gap seeding is
    // what a phone (no readable OS sleep log) relies on to inherit the account's rests instead of showing a rest
    // pose pinned to the now-line the desktop doesn't have. Side-task config is recomputed, never persisted.
    private fun launchSideTaskSeeding() = scope.launch {
        val before = vm.state.value.sideTasks
        val restedTasks = withContext(Dispatchers.Default) {
            val fromOsLog = before.map { side ->
                if (side.durationMillis <= 0) {
                    side
                } else {
                    val lastRest = lastWakeAfterLongSleepMillis(side.durationMillis)
                    if (lastRest != null) side.copy(lastRestMillis = lastRest) else side
                }
            }
            SchedulerDomain.seedSideTasksFromGaps(fromOsLog, loadStoredGaps())
        }
        applySeededSideTasks(restedTasks)
    }

    // PRD §15: after a pull brings in another device's exact sleep gaps, re-seed the rest poses from every gap now
    // in the local store — advancing `lastRestMillis` only (no work records / panel carving; see
    // [SchedulerDomain.seedSideTasksFromGaps]). This is the account-wide-pause signal reaching a device that never
    // slept, so its derived 5/15-min poses line up with the peer that recorded the sleep.
    private fun reseedSideTasksFromGaps() {
        val before = vm.state.value.sideTasks
        applySeededSideTasks(SchedulerDomain.seedSideTasksFromGaps(before, loadStoredGaps()))
    }

    private fun loadStoredGaps(): List<TaskTimeRange> =
        sleepGapStore?.loadSleepGaps()?.map { TaskTimeRange(it.startMillis, it.endMillis) } ?: emptyList()

    // ---- PRD §15 LOCAL active-session tracking + local derived-pause refresh (never synced) ----

    private val activeSessionDeviceId: String get() = pauseCue?.deviceId ?: LOCAL_DEVICE_ID

    // PRD §15: track this device's activity in the LOCAL store. Each beat samples `screenActive()` and either
    // opens, extends, or finalizes the current session, persisting it locally. Opens and extends NEVER talk to
    // the server (they ride the next moment); a FINALIZE — the beat's device check finding the device went
    // inactive — is authoritative and not reconstructible by a peer, so it fires sync moment #5 right then
    // ([advanceActiveSession] → [requestSyncMoment]) rather than only riding the next of the other four moments
    // (ARCHITECTURE.md §8). `suspended` is judged from REAL elapsed time between beats — NOT the (possibly
    // accelerated) clock — so a real process suspension ends the session at its pre-sleep end and the post-wake
    // session opens after the gap, exactly like the §12 device-sleep detection; a fast sim tick just extends the
    // session across the leaped clock time.
    private fun launchActiveSessionTracking() = scope.launch {
        var lastRealBeat = SystemAppClock.nowMillis()
        while (true) {
            val realNow = SystemAppClock.nowMillis()
            val suspended = realNow - lastRealBeat > DEVICE_SLEEP_THRESHOLD_MILLIS
            advanceActiveSession(clock.nowMillis(), effectiveScreenActive(), suspended)
            lastRealBeat = realNow
            // Beat faster while accelerated (re-checked each pass — the speed changes at runtime) so the session
            // timeline tracks the racing `now` finely; keys off the clock's actual speed so the phone beats fast
            // too under the desktop time-link, where DebugFlags.TIME_SIMULATION is off. The phone renews its
            // one-minute foreground lease once per lease length ("adds [now, now+1 min] every minute").
            val prodBeat =
                if (deviceKind == DeviceKind.Phone) PHONE_SESSION_LEASE_MILLIS else ACTIVE_SESSION_BEAT_MILLIS_PROD
            tickDelay(if (timeAccelerated()) ACTIVE_SESSION_BEAT_MILLIS_SIM else prodBeat)
        }
    }

    // The instant a session claims activity up to when extended at `now`: the phone claims a one-minute
    // LEASE (`now + 1 min` — foreground is its only activity signal, renewed each beat, expiring on its own
    // if the app is backgrounded/killed); other devices claim only what the beat observed (`now`).
    private fun sessionClaimEnd(now: Long): Long =
        if (deviceKind == DeviceKind.Phone) now + PHONE_SESSION_LEASE_MILLIS else now

    // Patch [activeSessions] in-memory with an updated row (keyed by device+start), keeping it sorted like
    // the store's load. Cheaper than re-reading the whole table on every beat.
    private fun publishSessionRow(row: ActiveSessionRecord) {
        _activeSessions.value =
            (_activeSessions.value.filterNot { it.deviceId == row.deviceId && it.startMillis == row.startMillis } + row)
                .sortedBy { it.startMillis }
    }

    // PRD §15: advance the current active session to `now`, LOCAL store only. A suspension or an inactive
    // screen finalizes it; an active screen opens a new session (if none / after a finalize) or extends the
    // open one. Serialized under [activeSessionMutex] because the beat and the debug forced-inactivity flip
    // both call it. The store write is local-only in every branch — activity is never synced.
    private suspend fun advanceActiveSession(
        now: Long,
        active: Boolean,
        suspended: Boolean,
    ) {
        activeSessionMutex.withLock {
            val open = currentSession
            if ((suspended || !active) && open != null) {
                finalizeSessionLocked(open)
                currentSession = null
                // The walk-away instant: the UI's live "Inactivity" tail grows from here (see [inactiveSince]).
                _inactiveSince.value = open.endMillis
            }
            if (active) {
                val cur = currentSession
                val realNow = SystemAppClock.nowMillis()
                val claimEnd = sessionClaimEnd(now)
                val updated =
                    if (cur == null) {
                        ActiveSessionRecord(activeSessionDeviceId, now, claimEnd, realNow, sessionKind)
                    } else {
                        cur.copy(endMillis = maxOf(cur.endMillis, claimEnd), updatedAtMillis = realNow)
                    }
                currentSession = updated
                persistSessionLocked(updated)
            }
            _activeSince.value = currentSession?.startMillis
        }
        updatePresence()
    }

    // PRD §15: publish this device's Realtime presence — active (with its next ≥5-min break end) while the
    // screen is on and signed in, cleared otherwise. Driven from every active-session beat, so a walk-away
    // (screen off) or a break passing is reflected within a beat; the StateFlow behind [setPresence] dedupes,
    // so an unchanged state sends nothing.
    private fun updatePresence() {
        val gateway = pauseCue ?: return
        val presence = realtimePresence ?: return
        val active = effectiveScreenActive() && gateway.signedIn
        presence.setPresence(
            if (active) {
                PresenceState(
                    deviceId = gateway.deviceId,
                    kind = deviceKind.name.lowercase(),
                    nextBreakEndMillis = nextRestPoseEndMillis(
                        vm.state.value.panels,
                        vm.state.value.sideTasks,
                        clock.nowMillis(),
                    ),
                )
            } else {
                null
            },
        )
    }

    // Visible for tests: drive one heartbeat sample exactly as [launchActiveSessionTracking]'s beat does
    // (open/extend/finalize + sync moment #5 on a finalize), without standing up the full [start] loop set.
    internal suspend fun heartbeatSampleForTest(active: Boolean, suspended: Boolean) =
        advanceActiveSession(clock.nowMillis(), active, suspended)

    // Finalize a session: persist its final bounds locally. Caller holds [activeSessionMutex].
    private fun finalizeSessionLocked(session: ActiveSessionRecord) {
        persistSessionLocked(session)
    }

    private fun persistSessionLocked(session: ActiveSessionRecord) {
        activeSessionStore?.saveActiveSessions(listOf(session))
        publishSessionRow(session)
    }

    // The device-kind label written on every session row this device records ('desktop'/'phone'/'other') —
    // what the calendar's "which devices were open" bubble shows for it on every device after a sync.
    private val sessionKind: String get() = deviceKind.name.lowercase()

    // PRD §15: freshen the open session up to `now`, opening one if the screen is active and none is open, and
    // persist it locally so a subsequent load/push reports activity right up to the present. No-op when the
    // screen is inactive (the beat finalizes it; masking a just-started pause here would hide a real pause).
    private suspend fun freshenOpenSession() {
        activeSessionMutex.withLock {
            if (!effectiveScreenActive()) return@withLock
            val now = clock.nowMillis()
            val realNow = SystemAppClock.nowMillis()
            val cur = currentSession
            val claimEnd = sessionClaimEnd(now)
            val updated =
                cur?.copy(endMillis = maxOf(cur.endMillis, claimEnd), updatedAtMillis = realNow)
                    ?: ActiveSessionRecord(activeSessionDeviceId, now, claimEnd, realNow, sessionKind)
            currentSession = updated
            persistSessionLocked(updated)
            _activeSince.value = updated.startMillis
        }
    }

    /**
     * PRD §15: refresh the calendar's "Inactivity" bands from the stored active sessions — this device's own
     * rows plus the peers' rows the last Sync-button reconcile pulled, so a pause is account-wide ("no device
     * was active") with Sync-bounded staleness. A purely LOCAL derivation (no server RPC; the derived pauses
     * are never stored). The freshly derived pauses also seed the §15 rest poses (advancing `lastRestMillis`
     * only). Public for the debug "simulate pause + leap" control so the bands / rest poses reflect the
     * just-simulated pause at once.
     */
    fun refreshDerivedPauses() {
        scope.launch { refreshDerivedPausesNow() }
    }

    /**
     * Debug "simulate pause + leap": the same LOCAL derive as [refreshDerivedPauses], but awaitable so the
     * debug control's post-leap step runs only after the bands were recomputed. Runs on the engine's own scope
     * (the body touches the main-thread ViewModel); the caller merely waits for it.
     */
    suspend fun refreshDerivedPausesAndWait() {
        scope.launch { refreshDerivedPausesNow() }.join()
    }

    private suspend fun refreshDerivedPausesNow() {
        // Freshen the open session up to `now` so its trailing gap is genuinely empty while active.
        freshenOpenSession()
        val until = clock.nowMillis()
        val since = until - PAUSE_DERIVE_HORIZON_MILLIS
        val pauses = withContext(Dispatchers.Default) { localDerivedPauses(since, until) }
        Diagnostics.log(
            "pauses refreshed [local] window=${Diagnostics.formatInstant(since)} → " +
                "${Diagnostics.formatInstant(until)}; inactivity: ${Diagnostics.formatRanges(pauses)}",
        )
        _inactivityGaps.value = pauses
        // The freshly-derived pauses now cover any pause this device observed locally: retire the live
        // "Inactivity" tail once a session is open again. While still inactive the tail stays, so the band
        // keeps growing between derives.
        activeSessionMutex.withLock { if (currentSession != null) _inactiveSince.value = null }
        val before = vm.state.value.sideTasks
        applySeededSideTasks(SchedulerDomain.seedSideTasksFromGaps(before, pauses))
    }

    // Startup heal for the retired adoption scheme: delete the [REMOTE_ACTIVITY_DEVICE_ID] rows an older
    // build wrote. They record PRESUMED (not observed) activity — the trailing window the old trailing-drop
    // `derive_pauses` refused to call a pause — and were subtracted from every later pull, durably hiding any
    // genuine pause inside that window (the "Inactivity band stops an hour before the now-line" incident).
    // Under the closed-flag scheme the server answers correctly on every pull, so nothing replaces them.
    private fun purgeLegacyAdoptedRows() {
        scope.launch {
            withContext(Dispatchers.Default) {
                runCatching { activeSessionStore?.deleteActiveSessionsForDevice(REMOTE_ACTIVITY_DEVICE_ID) }
            }
        }
    }

    // Derive the pauses from EVERY stored session — this device's own rows plus the peers' rows the last
    // Sync-button reconcile pulled in, so the bands are account-wide again with Sync-bounded staleness (a
    // pause = no device active). Also refreshes [activeSessions] from the store, since this runs at exactly
    // the moments the stored set can change structurally (startup, post-reconcile, leap end).
    private fun localDerivedPauses(since: Long, until: Long): List<TaskTimeRange> {
        val records = activeSessionStore?.loadActiveSessions() ?: return emptyList()
        _activeSessions.value = records
        return SchedulerDomain.derivePauses(records.map { TaskTimeRange(it.startMillis, it.endMillis) }, since, until)
    }

    // PRD §15: install freshly-seeded rest times. Rest evidence only ever reveals a MORE-RECENT rest, so this
    // is **monotonic** — it folds [rested] into the LIVE side tasks and never moves a pose's `lastRestMillis`
    // backward. This is essential because the two seeders run concurrently and off-thread against a snapshot
    // captured when they started: the slow OS-log query ([launchSideTaskSeeding], an up-to-8s PowerShell call
    // built from the startup state where `lastRestMillis == 0`) can land AFTER [refreshDerivedPauses] has
    // already advanced the poses to `now` (the leading account-wide pause on a freshly-opened account ends at
    // the now-line). A wholesale overwrite would then drag `lastRestMillis` back to the morning wake and
    // re-pin the 5-/15-min pose to the now-line — the reported empty-account anomaly. Merging forward makes
    // the apply order irrelevant.
    private fun applySeededSideTasks(rested: List<SideTask>) {
        val live = vm.state.value.sideTasks
        val merged = SchedulerDomain.advanceRestsForward(live, rested)
        if (merged != live) {
            vm.dispatch(SchedulerIntent.SetSideTasks(merged))
            vm.dispatch(SchedulerIntent.RefreshSchedule(clock.nowMillis()))
        }
    }

    // PRD §9 calculation event #2 (tree change): recompute on a 1-second debounce after the task tree changes.
    private fun launchTreeChangeReschedule() = scope.launch {
        vm.state.map { it.tasks to it.cells }.distinctUntilChanged().collectLatest {
            delay(1_000)
            if (vm.state.value.automaticSchedule) vm.dispatch(SchedulerIntent.RefreshSchedule(clock.nowMillis()))
            else pendingReschedule = true
        }
    }

    // PRD §9 calculation event #1 (calendar change / rolling horizon): refill ~168h ahead as `now` reaches
    // `firstFreeMoment − 168h`.
    private fun launchHorizonReschedule() = scope.launch {
        vm.state.map { it.panels }.distinctUntilChanged().collectLatest { panels ->
            val target =
                SchedulerDomain.firstFreeMoment(panels, clock.nowMillis()) -
                    SchedulerDomain.SCHEDULE_HORIZON_MILLIS
            // Poll faster while accelerated (re-checked each pass — the user changes speed at runtime) so the
            // refill isn't reached late when `now` races ahead; the phone keys off the clock's actual speed too.
            while (clock.nowMillis() < target) {
                tickDelay(if (timeAccelerated()) ADVANCE_TICK_MILLIS_ACCEL else ADVANCE_TICK_MILLIS_PROD)
            }
            if (vm.state.value.automaticSchedule) vm.dispatch(SchedulerIntent.RefreshSchedule(clock.nowMillis()))
            else pendingReschedule = true
        }
    }

    // PRD §7: fire the single deferred reschedule when the switch is turned on.
    private fun launchPendingRescheduleOnSwitch() = scope.launch {
        vm.state.map { it.automaticSchedule }.distinctUntilChanged().collectLatest { on ->
            if (on && pendingReschedule) {
                pendingReschedule = false
                vm.dispatch(SchedulerIntent.RefreshSchedule(clock.nowMillis()))
            }
        }
    }

    // PRD §11/§13 Notifications: whenever "the task to do now" changes to a DIFFERENT task, post a system
    // notification naming it (with each schedule-unit step's deadline when present).
    // PRD §15 / CLAUDE.md "each fires exactly once, in order": the ONE ordered cue sweep. Every now-line
    // advance recomputes the cue boundaries the clock crossed since the previous sweep and fires them in the
    // chronological order of their boundary instants — collapsing the task-switch, look-away, rest-pose and
    // wind-down cues that used to run as four independent now-line collectors. That independence was the
    // reported bug: a single 300× leap could cross a 20s look-away start (earlier) and a 5-min rest-pose due
    // (later) in one tick, and the racing collectors announced the pose first, then the look-away, though the
    // look-away's boundary came first. Here they share one [cueSweep] window and one sorted firing list.
    //
    // Leap-safety per cue is preserved and unified in [SchedulerDomain.cueCrossings]: look-away starts come
    // from the mathematical [SchedulerDomain.sideTaskOccurrencesBetween] reconstruction (NOT `state.panels`,
    // whose forward projection drops an occurrence the instant `now` passes it — the earlier look-away that
    // vanished in the report), and the rest-pose is the level `now >= due` reach that a jump can't skip.
    // Real-age staleness ([BoundarySweep.realLatenessMillis]), the screen-active gate, resume-cue arming and
    // the once-only de-dupe stay here (this owns the clock and the fired-boundary memory).
    private fun launchCueSweep() = scope.launch {
        combine(_nowMillis, vm.state.map { it.panels }.distinctUntilChanged()) { _, panels -> panels }
            .collectLatest {
                while (true) {
                    val st = vm.state.value
                    val simNow = clock.nowMillis()
                    val speed = (clock as? SimAppClock)?.speed ?: 1.0
                    val voice = st.lookAwayVoiceEnabled
                    // Fire/stale is decided by each crossing's REAL age ([BoundarySweep]); the scan floor (not
                    // a fixed cap off sim-now) tiles consecutive sweeps so no crossing is clipped by a jump.
                    cueSweep.beginSweep(simNow, speed, clockGeneration())
                    val scanFloor = cueSweep.scanFloorMillis(LOOK_AWAY_SWEEP_CAP_MILLIS)
                    announcedStarts = announcedStarts.filterTo(mutableSetOf()) { it >= scanFloor }
                    announcedWindDowns = announcedWindDowns.filterTo(mutableSetOf()) { it >= scanFloor }

                    val windDownInstants = st.panels
                        .filter { it.sleep }
                        .map { it.startEpochMillis - SchedulerDomain.NO_TASK_BEFORE_BED_MILLIS }
                    val crossings = SchedulerDomain.cueCrossings(
                        sideTasks = st.sideTasks,
                        windDownInstants = windDownInstants,
                        automaticSchedule = st.automaticSchedule,
                        alreadyNotifiedPoseDues = sidePoseNotifiedDue,
                        fromMillis = scanFloor,
                        toMillis = simNow,
                    )

                    // Each fire as (instant, tie, action); executed in boundary order below. `tie` only
                    // orders cues that share an instant (task context, then look-away start, then its resume,
                    // then rest-pose due, then wind-down).
                    data class Firing(val instant: Long, val tie: Int, val run: () -> Unit)
                    val firings = mutableListOf<Firing>()

                    // Task-switch (PRD §11/§13) — level: the task the now-line currently sits in, announced
                    // once when it changes ([lastNotifiedTaskId]); ordered by that panel's start.
                    val currentPanel = SchedulerDomain.currentPanel(st, simNow)
                    val currentTaskId = currentPanel?.taskId
                    if (currentTaskId != null && currentTaskId != lastNotifiedTaskId) {
                        val message = SchedulerDomain.taskSwitchNotificationMessage(
                            state = st,
                            taskId = currentTaskId,
                            startMillis = currentPanel.startEpochMillis,
                        ) { deadline -> formatClockTime(Instant.fromEpochMilliseconds(deadline).toLocalDateTime(tz)) }
                        if (message != null) {
                            firings += Firing(currentPanel.startEpochMillis, 0) {
                                lastNotifiedTaskId = currentTaskId
                                notifyUser("Task to do now", message)
                            }
                        }
                    }

                    for (crossing in crossings) {
                        when (crossing.kind) {
                            SchedulerDomain.CueKind.LookAwayStart -> {
                                val start = crossing.instant
                                if (start in announcedStarts) continue
                                val end = crossing.endInstant
                                val title = crossing.title
                                // Decide the start's fate now (reads are synchronous and stable across this
                                // sweep): a crossing older than the real-age budget was slept through, and a
                                // screen-inactive user is already resting (no cue, no resume armed).
                                val lateness = cueSweep.realLatenessMillis(start)
                                val stale = lateness > LOOK_AWAY_START_FRESH_MILLIS
                                val screenActive = effectiveScreenActive()
                                val startFires = !stale && screenActive
                                firings += Firing(start, 1) {
                                    announcedStarts = announcedStarts + start
                                    when {
                                        stale -> Diagnostics.log(
                                            "look-away start ${Diagnostics.formatInstant(start)} swallowed: " +
                                                "crossed ~$lateness ms (real) ago — process was suspended or engine " +
                                                "just started (budget $LOOK_AWAY_START_FRESH_MILLIS ms, speed ${speed}x)",
                                        )
                                        !screenActive -> Diagnostics.log(
                                            "look-away start ${Diagnostics.formatInstant(start)} suppressed: " +
                                                "screen inactive at crossing (user already resting; sim now=" +
                                                "${Diagnostics.formatInstant(simNow)})",
                                        )
                                        else -> {
                                            notifyUser("Screen break", title)
                                            if (voice) speakCue(VoiceCue.LookAway)
                                            // Resume fires at `end`: same tick if the whole break was leaped
                                            // (queued below, sorted after this start); else armed for later.
                                            if (end > simNow) pendingEnds = pendingEnds + end
                                        }
                                    }
                                }
                                if (startFires && end <= simNow) {
                                    firings += Firing(end, 2) {
                                        if (voice &&
                                            cueSweep.realLatenessMillis(end) <= LOOK_AWAY_START_FRESH_MILLIS &&
                                            effectiveScreenActive()
                                        ) {
                                            speakCue(VoiceCue.ResumeWork)
                                        }
                                    }
                                }
                            }
                            SchedulerDomain.CueKind.RestPoseDue -> {
                                // Level reach — NO staleness gate (a break announces however late the clock
                                // finally crossed its due). De-duped on the stable due, so a sliding overdue
                                // pose fires once and stays silent until a rest advances the due.
                                val due = crossing.instant
                                val title = crossing.title
                                firings += Firing(due, 3) {
                                    sidePoseNotifiedDue = sidePoseNotifiedDue + (title to due)
                                    notifyUser("Screen break", title)
                                }
                            }
                            SchedulerDomain.CueKind.WindDown -> {
                                val wd = crossing.instant
                                if (wd in announcedWindDowns) continue
                                firings += Firing(wd, 4) {
                                    announcedWindDowns = announcedWindDowns + wd
                                    if (cueSweep.realLatenessMillis(wd) <= LOOK_AWAY_START_FRESH_MILLIS) {
                                        notifyUser("Stop work", "Wind down — bedtime in 1 hour")
                                    }
                                }
                            }
                        }
                    }

                    // Resume cues armed on a PREVIOUS sweep whose end the now-line has now reached.
                    pendingEnds.filter { it <= simNow }.forEach { end ->
                        firings += Firing(end, 2) {
                            pendingEnds = pendingEnds - end
                            val lateness = cueSweep.realLatenessMillis(end)
                            when {
                                lateness > LOOK_AWAY_START_FRESH_MILLIS -> Diagnostics.log(
                                    "look-away end ${Diagnostics.formatInstant(end)} resume cue swallowed: " +
                                        "crossed ~$lateness ms (real) ago (budget $LOOK_AWAY_START_FRESH_MILLIS ms)",
                                )
                                !effectiveScreenActive() -> Diagnostics.log(
                                    "look-away end ${Diagnostics.formatInstant(end)} resume cue suppressed: " +
                                        "screen inactive at crossing",
                                )
                                voice -> speakCue(VoiceCue.ResumeWork)
                            }
                        }
                    }

                    // Fire everything in true boundary order (CLAUDE.md "in order").
                    firings.sortedWith(compareBy({ it.instant }, { it.tie })).forEach { it.run() }

                    // Keep the pose de-dupe map bounded: retain only still-existing rest-pose titles.
                    val restTitles = st.sideTasks.filter { it.restBreak }.map { it.title }.toSet()
                    sidePoseNotifiedDue = sidePoseNotifiedDue.filterKeys { it in restTitles }

                    // Self-delay to the next boundary across every cue kind, so a cue fires at its instant and
                    // not up to a tick late (the outer collectLatest also re-keys each tick). The forward
                    // look-away / wind-down come from the projection, which keeps FUTURE occurrences; the next
                    // pose due is arithmetic.
                    val nextStart = st.panels
                        .filter { p -> p.sideTask && st.sideTasks.any { !it.restBreak && it.title == p.title } }
                        .map { it.startEpochMillis }
                        .filter { it > simNow && it !in announcedStarts }.minOrNull()
                    val nextEnd = pendingEnds.filter { it > simNow }.minOrNull()
                    val nextWind = windDownInstants.filter { it > simNow && it !in announcedWindDowns }.minOrNull()
                    val nextPose = if (st.automaticSchedule) {
                        st.sideTasks
                            .filter { it.restBreak && it.intervalMillis > 0 && it.durationMillis > 0 && it.title.isNotBlank() }
                            .map { it.lastRestMillis + it.intervalMillis }
                            .filter { it > simNow }.minOrNull()
                    } else {
                        null
                    }
                    val next = listOfNotNull(nextStart, nextEnd, nextWind, nextPose).minOrNull() ?: break
                    if (speed <= 0.0) break
                    delay(((next - simNow).toDouble() / speed).toLong().coerceAtLeast(1L))
                }
            }
    }

    /**
     * PRD §15 (20s look-away) manual redo: re-run the 20s pause now, superseding any look-away cue still
     * sounding or pending. Mirrors the old `restartLookAway` in App.kt (the cue scope is now [scope]).
     */
    fun restartLookAway() {
        val now = clock.nowMillis()
        val st = vm.state.value
        val lookAway = st.sideTasks.firstOrNull { !it.restBreak } ?: return
        stopSpeaking()
        pendingEnds = emptySet()
        manualLookAwayJob?.cancel()
        vm.dispatch(
            SchedulerIntent.SetSideTasks(
                st.sideTasks.map { if (!it.restBreak) it.copy(lastRestMillis = now) else it },
            ),
        )
        if (st.automaticSchedule) vm.dispatch(SchedulerIntent.RefreshSchedule(now))
        val voice = st.lookAwayVoiceEnabled
        manualLookAwayJob = scope.launch {
            notifyUser("Screen break", lookAway.title)
            if (voice) speakCue(VoiceCue.LookAway)
            val resumeAt = clock.nowMillis() + lookAway.durationMillis
            while (clock.nowMillis() < resumeAt) {
                val speed = (clock as? SimAppClock)?.speed ?: 1.0
                val remainingReal =
                    if (speed > 0.0) ((resumeAt - clock.nowMillis()).toDouble() / speed).toLong() else Long.MAX_VALUE
                delay(remainingReal.coerceIn(1L, LOOK_AWAY_RESUME_POLL_MILLIS))
            }
            if (voice) speakCue(VoiceCue.ResumeWork)
        }
    }

    // PRD §15 device-sleep gaps: after a sleep is detected, query the OS sleep/wake log off-thread for the
    // EXACT interval(s) of the pause that was just missed and record them into the LOCAL gaps store. No
    // remote push here — the recorded gaps ride the next sync moment ([pushOwnRecentSleepGaps]). Best-effort:
    // an unsupported platform / failed query returns nothing and the coarse tick-gap hole already kept the
    // schedule correct. Idempotent — the store/remote upsert keys on (deviceId, sleepStart), so re-recording
    // the same interval (or backfilling earlier ones) is harmless.
    private fun recordExactSleepGaps(approxStart: Long, approxEnd: Long) {
        val store = sleepGapStore ?: return
        scope.launch {
            val gaps = withContext(Dispatchers.Default) {
                runCatching { sleepGapQuery(approxStart - GAP_QUERY_MARGIN_MILLIS) }.getOrDefault(emptyList())
            }.filter { it.endMillis > it.startMillis && it.endMillis <= approxEnd + GAP_QUERY_MARGIN_MILLIS }
            if (gaps.isEmpty()) return@launch
            val recordedAt = SystemAppClock.nowMillis()
            val records = gaps.map { SleepGapRecord(activeSessionDeviceId, it.startMillis, it.endMillis, recordedAt) }
            store.saveSleepGaps(records)
        }
    }

    // PRD §15 device-sleep gaps: at launch, scan this device's recent OS-recorded sleeps into the LOCAL gaps
    // store so a peer that never witnessed them can inherit the account's rests — the rows reach the server on
    // the next sync moment ([pushOwnRecentSleepGaps]; the login reconcile's moment follows this scan within
    // seconds). [recordExactSleepGaps] only covers sleeps this device sees LIVE while running; a freshly-opened
    // desktop seeds its own poses from the OS log (e.g. last night's sleep) but, without this, never records
    // that sleep — so a phone (no readable OS log) would keep showing a rest pose pinned to the now-line the
    // desktop doesn't have. Idempotent (the store keys on (deviceId, sleepStart)); no-op on a platform whose
    // OS log is empty (e.g. Android/iOS).
    //
    // The scan is INCREMENTAL: it starts from the persisted [sleepScanCheckpoint] ("scanned through" instant)
    // rather than re-reading the full 3-day horizon on every launch, and records the current time as the new
    // checkpoint afterward (see [SchedulerDomain.sleepScanFloor]). The floor still clamps the first run / a long
    // gap offline to 3 days.
    private fun backfillSleepGaps() {
        val store = sleepGapStore ?: return
        scope.launch {
            val now = clock.nowMillis()
            // Resume from the last "scanned through" checkpoint so an already-examined stretch of the OS log
            // isn't re-read every launch; clamp to the 3-day floor on the first run (no checkpoint) or after a
            // long time offline (a stale checkpoint) — only the last few days can still reseed a pose.
            val checkpoint =
                withContext(Dispatchers.Default) { runCatching { sleepScanCheckpoint?.loadSleepScanCheckpoint() }.getOrNull() }
            val since = SchedulerDomain.sleepScanFloor(now, checkpoint, SLEEP_GAP_BACKFILL_HORIZON_MILLIS)
            val gaps = withContext(Dispatchers.Default) {
                runCatching { sleepGapQuery(since) }.getOrDefault(emptyList())
            }.filter { it.endMillis > it.startMillis }
            // Advance the checkpoint to now even when nothing was found, so the next launch skips this stretch.
            // Recording new gaps still merges into the store idempotently (upsert keyed on (deviceId, start)), so
            // a re-scan that the checkpoint elides costs nothing — it just avoids the up-to-8s OS-log query.
            withContext(Dispatchers.Default) { runCatching { sleepScanCheckpoint?.saveSleepScanCheckpoint(now) } }
            if (gaps.isEmpty()) return@launch
            val recordedAt = SystemAppClock.nowMillis()
            val records = gaps.map { SleepGapRecord(activeSessionDeviceId, it.startMillis, it.endMillis, recordedAt) }
            store.saveSleepGaps(records)
            reseedSideTasksFromGaps()
        }
    }

    // PRD §15 / listener cue delivery: a phone's startup claims the account's last phone so the external
    // listener knows which phone to push (its change also cancels the previous phone's pending cue). Phones
    // only; a no-op when sync is disabled/signed out.
    private fun claimLastPhoneOnStartup() = claimLastPhone()

    /**
     * PRD §15 / ARCHITECTURE.md §8: scenario #3 — a phone that BECOMES the active app (re)claims the account's
     * last phone. Call this on app-foreground as well as startup: on Android the foreground service keeps the
     * process (and [start]) alive across resumes, so a phone re-opened after another phone was used would never
     * re-claim from [start] alone. Claiming is idempotent — the `account_last_phone` trigger only pushes `cancel`
     * to the previous phone when the device id actually changes, so re-claiming the same phone is a no-op.
     */
    fun onAppForegrounded() = claimLastPhone()

    private fun claimLastPhone() {
        val gateway = pauseCue ?: return
        if (deviceKind != DeviceKind.Phone) return
        scope.launch { withContext(Dispatchers.Default) { runCatching { gateway.claimLastPhone() } } }
    }

    /**
     * PRD §15 / listener cue delivery: the native push receiver (an FCM/APNs data message from the external
     * listener) calls this — schedule the local OS cue at [dueAtMillis] on `"schedule"`, or cancel the pending
     * one on `"cancel"`. The scheduled cue's fire handler ([onPauseCueFire]) re-checks the local screen before
     * speaking.
     */
    fun onPauseCuePush(action: String, dueAtMillis: Long?) {
        when (action) {
            "schedule" -> if (dueAtMillis != null) scheduleLocalPauseCue(dueAtMillis)
            "cancel" -> scheduleLocalPauseCue(null)
        }
    }

    /**
     * PRD §15: the platform local cue fired at the pause-end instant (an AlarmManager alarm / a delivered
     * `UNNotification`) calls this — the external listener already decided the whole account was inactive when
     * it scheduled the cue, so the phone only re-checks its OWN screen (the user may have picked the phone up)
     * and speaks if it is still off. Inert unless this is a signed-in phone with its screen off.
     */
    suspend fun onPauseCueFire() {
        if (poseFinishEligible(
                isPhone = deviceKind == DeviceKind.Phone,
                signedIn = pauseCue?.signedIn == true,
                screenActive = effectiveScreenActive(),
            )
        ) {
            speakCue(VoiceCue.PauseOver)
        } else {
            Diagnostics.log(
                "OS pause-cue alarm fired but suppressed (phone=${deviceKind == DeviceKind.Phone}, " +
                    "signedIn=${pauseCue?.signedIn == true}, screenActive=${effectiveScreenActive()})",
            )
        }
    }

    companion object {
        /**
         * PRD §15: the end instant of the next 5/15-min rest pose strictly after [now], or null if none is
         * scheduled — the `next_break_end_ms` this device publishes in its Realtime presence so the external
         * listener can fire the pause-end cue at that instant. A pose is a `sideTask` panel whose title matches
         * a `restBreak` side task.
         */
        internal fun nextRestPoseEndMillis(panels: List<TaskPanel>, sideTasks: List<SideTask>, now: Long): Long? =
            panels.asSequence()
                .filter { p -> p.sideTask && sideTasks.any { it.restBreak && it.title == p.title } }
                .map { it.endEpochMillis }
                .filter { it > now }
                .minOrNull()

        /**
         * PRD §15: the gate for the phone's "pause finished" voice cue — true only when this is the phone, a
         * signed-in session is available, and this device's screen is off. (Whether any OTHER device is active
         * was already decided by the external listener before it pushed this phone.)
         */
        internal fun poseFinishEligible(
            isPhone: Boolean,
            signedIn: Boolean,
            screenActive: Boolean,
        ): Boolean = isPhone && signedIn && !screenActive

        /**
         * PRD §15 (legacy): the reserved `deviceId` of the LOCAL-ONLY rows the RETIRED startup adoption wrote
         * into the active-session store — account activity presumed from the server rather than observed by
         * any device. These rows are deleted at startup ([purgeLegacyAdoptedRows]); the constant remains so old
         * DBs heal. Real device ids are UUIDs (or `LOCAL_DEVICE_ID` while signed out), so it can never collide.
         */
        const val REMOTE_ACTIVITY_DEVICE_ID: String = "remote-activity"
    }
}
