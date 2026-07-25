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
import org.example.project.scheduler.domain.AlarmDomain
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.AlarmEntry
import org.example.project.scheduler.model.ScreenBreak
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
import org.example.project.scheduler.sync.DeviceHeartbeatPublisher
import org.example.project.scheduler.sync.NextBreakState
import org.example.project.scheduler.sync.PauseCueGateway
import org.example.project.scheduler.sync.PresenceState
import org.example.project.scheduler.sync.RealtimePresence
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

// The phone's foreground-lease length. (The debug fast-break override now retimes only the 5-min break's
// interval/duration — not this lease — so the lease keeps its production one-minute granularity. Live cross-
// device presence is the WebSocket, which drops within moments of a lock/suspend regardless of this lease.)
private fun phoneSessionLeaseMillis(): Long = PHONE_SESSION_LEASE_MILLIS

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

// PRD §18 Alarms: how far the recomputed real instant of the armed ring may drift before the OS alarm is
// rewritten. Only the debug sim clock drifts at all (it re-derives the real instant every display tick); on the
// production clock the value is exactly stable, so this never applies.
private const val ALARM_REARM_TOLERANCE_MILLIS: Long = 1_000

// PRD §12/§15: the window the server derivation (and its local fallback) considers — the last 168 hours, the
// same horizon §9/§12 use for "the calendar the user can change". Older pauses age out of the bands.
private const val PAUSE_DERIVE_HORIZON_MILLIS: Long = 168L * 60 * 60 * 1_000

/**
 * Bundle of the single process-shared [TaskSchedulerViewModel] + its already-started [SchedulerEngine],
 * handed to `App()` so the Android foreground service and the Activity render/drive one source of truth
 * (one state, one notification stream). Null on platforms where `App()` creates them itself.
 */
class AppSchedulerHost(val vm: TaskSchedulerViewModel, val engine: SchedulerEngine)

/**
 * PRD §18 Alarms: the one ring this device currently has armed with the OS — which alarm ([alarmId]), the
 * **real** wall-clock instant ([atMillis]) it goes off at, and everything needed to actually ring it. The OS
 * knows nothing of the debug sim clock, so the engine converts before handing it over (see
 * `SchedulerEngine.realInstantFor`).
 *
 * The ring parameters travel *with* the armed alarm (rather than being looked up when it fires) so the ring is
 * exactly what was armed: it survives a cold process that has to rebuild its state, and — for a **one-off** —
 * it cannot be silenced by the peer that rang a moment earlier syncing its own "this one-off has rung" disarm
 * back to us first. PRD §18 says the alarm rings on *every* phone of the account.
 *
 * Only the soonest ring is ever armed; the platform receiver calls `SchedulerEngine.onAlarmFire`, which rings
 * it and arms the one after it.
 */
data class ArmedAlarm(
    val alarmId: String,
    val atMillis: Long,
    val label: String = "",
    val soundSeconds: Int = AlarmEntry.DEFAULT_ALARM_SOUND_SECONDS,
    val vibrate: Boolean = true,
)

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
    // The beat itself only writes the local store + the ~10 s device_heartbeat row (via [pauseCue]); the
    // active-session rows travel to peers ONLY inside the Sync-button reconcile. There is no live cross-device
    // activity channel — peers learn of activity at the next Sync.
    private val activeSessionStore: ActiveSessionStore? = null,
    // Push-token / last-phone / device-heartbeat channel (the sync engine) for the pg_cron pause-cue delivery:
    // the device writes its activity heartbeat, and a phone registers its FCM/APNs token + claims the account's
    // last phone so the Edge push can reach it. Null disables it.
    private val pauseCue: PauseCueGateway? = null,
    // PRD §15: the platform OS-scheduled local cue seam — schedule the "pause is over" alarm at the given
    // instant, or cancel the pending one when null. Default no-op (desktop/tests); the phone wires AlarmManager
    // / UNUserNotificationCenter here (see docs/PAUSE_CUE_DELIVERY.md). Driven by the cron's Edge push
    // ([onPauseCuePush]); the alarm's fire handler runs [onPauseCueFire] before speaking.
    private val scheduleLocalPauseCue: (Long?) -> Unit = {},
    // PRD §15: true on a platform that delivers the pause-end cue via [scheduleLocalPauseCue] + [onPauseCueFire]
    // (the OS-scheduled alarm, which fires even if the app was killed). When true the older in-app
    // pose-finish cue is skipped so the phone never speaks the cue twice. Default false keeps the in-app path
    // for desktop / not-yet-wired platforms.
    private val localPauseCueDelivery: Boolean = false,
    // PRD §18 Alarms: arm the OS-level alarm clock for the next ring — a **real** wall-clock instant, since the
    // OS knows nothing of the debug sim clock (see [realInstantFor]) — or cancel the armed one when null. The
    // phone wires AlarmManager here (its receiver calls [onAlarmFire]); the default no-op is what keeps every
    // non-phone device silent (PRD §18: an alarm rings on the account's PHONES).
    private val scheduleDeviceAlarm: (ArmedAlarm?) -> Unit = {},
    // PRD §18 Alarms: ring NOW — play the alarm sound for the armed length and vibrate if asked. Called from
    // [onAlarmFire]; injectable so tests can assert what rang.
    private val ringAlarm: (ArmedAlarm) -> Unit = {},
) {
    private val _nowMillis = MutableStateFlow(clock.nowMillis())

    /** The advancing "now" (epoch millis); the UI collects this for display. */
    val nowMillis: StateFlow<Long> = _nowMillis.asStateFlow()

    // PRD §17 past sleep: the instant this engine session began. A scheduled sleep window is materialized as a
    // persisted past "Sleep" panel only for the portion that elapsed WHILE THIS SESSION RAN (bounded below by
    // this anchor) — a freshly opened/emptied account must not retroactively assume sleep across the whole
    // derive window. The past is Inactivity + No-screen until the running app observes a scheduled window pass
    // with no device active; already-materialized panels persist across restarts.
    private val sessionStartMillis: Long = clock.nowMillis()

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

    // PRD §15: the user manually declared they are AWAY from this device via the left-menu "I'm away" button.
    // While set, the device reads screen-inactive regardless of the platform sensor, so its active session is
    // finalized and its `t_a` presence tick stopped + reported as a screen-off — telling the server this device
    // is no longer being worked on. Purely local runtime state: never persisted, never synced,
    // so a restart returns the device to active. Toggled by [setUserAway]; the menu reads it via [userAway].
    private val _userAway = MutableStateFlow(false)

    /** PRD §15: whether the user declared they are away from this device (drives the left-menu button label). */
    val userAway: StateFlow<Boolean> = _userAway.asStateFlow()

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

    // PRD §15: the presence publisher — this device writes its presence row every `t_a` (10 s by default, set
    // server-side) while signed in + screen-on, carrying its next break window, and on screen-off stops ticking
    // and reports that to the pause-cue Edge Function. Constructed in [start] from the [pauseCue] gateway; null
    // when sync is disabled or on a platform without an injected gateway.
    private var realtimePresence: RealtimePresence? = null

    private var started = false

    fun start() {
        if (started) return
        started = true
        Diagnostics.log("engine started (device=$activeSessionDeviceId)")
        pauseCue?.let { gateway ->
            realtimePresence = DeviceHeartbeatPublisher(scope = scope, gateway = gateway).also { it.start() }
        }
        // PRD §15: every reducer refill folds this device's live ongoing/held pause into screen-break
        // PLACEMENT (SchedulerDomain.screenBreaksForPlacement), so screen-break panels slide with a pause the
        // derives haven't banked yet — the now-line can no longer cross a stale look-away slot and fire a
        // spurious cue mid-pause, and a rest pose re-places itself the moment the pause has lasted its
        // duration (fluid under an accelerated leap; no post-leap snap when the derive lands). Placement-
        // only: the stored lastRestMillis still advances only via the derives' forward-only seeding
        // ([applySeededScreenBreaks]).
        SchedulerReducer.liveRestGap = {
            SchedulerDomain.liveRestGap(_inactiveSince.value, _activeSince.value, clock.nowMillis())
        }
        launchAdvanceTick()
        launchScreenBreakSeeding()
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
        // PRD §18 Alarms: keep this phone's OS alarm armed for the next ring in the synced alarm list.
        launchAlarmArming()
    }

    // ----- PRD §18 Alarms -------------------------------------------------------------------------

    // The ring this device currently has armed with the OS, so a re-computation that lands on the same
    // (alarm, instant) doesn't re-arm on every tick. Null when nothing is armed.
    private var armedAlarm: ArmedAlarm? = null

    /**
     * PRD §18 Alarms: keep the OS-level alarm armed for the soonest ring in the synced alarm list. Re-runs on
     * every now-tick and whenever the list changes (an edit here, or a peer's edit arriving over sync), and
     * only touches the OS when the target actually moves. Phones only — a desktop never rings.
     */
    private fun launchAlarmArming() = scope.launch {
        if (deviceKind != DeviceKind.Phone) return@launch
        combine(_nowMillis, vm.state.map { it.alarms }.distinctUntilChanged()) { now, alarms -> now to alarms }
            .collectLatest { (now, alarms) -> armNextAlarm(now, alarms) }
    }

    // Computes the next ring after [now] and hands it to the OS (or cancels when there is none).
    private fun armNextAlarm(now: Long, alarms: List<AlarmEntry>) {
        val next = AlarmDomain.nextOccurrence(alarms, now, tz)
        val armed = next?.let {
            ArmedAlarm(
                alarmId = it.entry.id,
                atMillis = realInstantFor(it.instant),
                label = it.entry.label,
                soundSeconds = it.entry.soundSeconds,
                vibrate = it.entry.vibrate,
            )
        }
        if (!needsRearming(armed)) return
        armedAlarm = armed
        if (armed == null) {
            Diagnostics.log("alarm: none armed (no enabled alarm)")
        } else {
            Diagnostics.log(
                "alarm ${armed.alarmId} armed for ${Diagnostics.formatInstant(armed.atMillis)} (real clock)",
            )
        }
        scheduleDeviceAlarm(armed)
    }

    /**
     * Whether [next] differs from what is already armed enough to re-arm the OS. The instant is compared with a
     * tolerance because under the debug sim clock [realInstantFor] recomputes a slightly different real instant
     * on every display tick (20×/s) — without the tolerance the OS alarm would be rewritten continuously. On the
     * production clock the value is exactly stable, so this is a no-op there.
     */
    private fun needsRearming(next: ArmedAlarm?): Boolean {
        val current = armedAlarm
        if (next == null || current == null) return next != current
        if (next.copy(atMillis = 0) != current.copy(atMillis = 0)) return true
        val drift = next.atMillis - current.atMillis
        return drift > ALARM_REARM_TOLERANCE_MILLIS || drift < -ALARM_REARM_TOLERANCE_MILLIS
    }

    /**
     * PRD §18 Alarms: the OS alarm fired — ring exactly what was [armed] (its sound for its configured length,
     * vibrating if asked), post the notification, disarm a **one-off** now that it has rung, and arm the next
     * ring. Called from the platform receiver, which may have woken the process from scratch.
     *
     * The ring itself is unconditional: the arming decision was already made from the alarm list, so a list
     * that has since changed (typically a peer that rang the same one-off a moment earlier and synced its
     * disarm) must not silence this phone — PRD §18 rings every phone of the account.
     */
    fun onAlarmFire(armed: ArmedAlarm) {
        Diagnostics.log(
            "alarm ${armed.alarmId} RINGING (${armed.soundSeconds}s sound, vibrate=${armed.vibrate})",
        )
        ringAlarm(armed)
        notifyUser("Alarm", armed.label.ifBlank { "Alarm" })
        // A one-off has now rung: disarm it so it doesn't come round again tomorrow (the row stays in the
        // window, ready to be re-armed). Unknown id = deleted meanwhile; nothing to disarm.
        val entry = vm.state.value.alarms.firstOrNull { it.id == armed.alarmId }
        if (entry != null && !entry.repeatDaily) {
            vm.dispatch(SchedulerIntent.SetAlarmEnabled(entry.id, false))
        }
        // Re-arm from the state as it is AFTER that dispatch, so a one-off doesn't re-arm itself.
        armedAlarm = null
        armNextAlarm(clock.nowMillis(), vm.state.value.alarms)
    }

    /**
     * Converts an instant on the engine's (possibly simulated) clock into the **real** wall-clock instant the
     * OS scheduler understands: under acceleration a sim instant `d` is reached in `(d − simNow)/speed` real
     * millis. Identity on the production clock. A paused sim clock (speed 0) never reaches it, so the alarm is
     * pushed out of reach rather than firing immediately.
     */
    private fun realInstantFor(simInstant: Long): Long {
        val sim = clock as? SimAppClock ?: return simInstant
        if (sim.speed <= 0.0) return Long.MAX_VALUE
        val remaining = ((simInstant - sim.nowMillis()).toDouble() / sim.speed).toLong()
        return SystemAppClock.nowMillis() + remaining
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
        // now-line), or when the live pause overlay would move a screen break's placement — an ongoing gap
        // keeps re-presuming/re-satisfying, so the projected grid moves ahead of the racing now-line
        // instead of being crossed at (or frozen behind) a stale slot. RefreshSchedule is a non-syncing
        // intent, so this refill makes zero server writes (sync-gating rule).
        val liveRest = SchedulerDomain.liveRestGap(_inactiveSince.value, _activeSince.value, now)
        val screenBreakDue =
            current.automaticSchedule &&
                (
                    current.screenBreaks.any { it.restBreak && SchedulerDomain.isScreenBreakOverdue(it, now) } ||
                        SchedulerDomain.screenBreaksForPlacement(current.screenBreaks, liveRest) != current.screenBreaks
                    )
        if (screenBreakDue) {
            vm.dispatch(SchedulerIntent.RefreshSchedule(now))
        } else {
            vm.dispatch(SchedulerIntent.AdvanceSchedule(now))
        }
        // PRD §17: the Sleep toggle auto-wakes when its scheduled wake instant lapses mid-session — finalize
        // the sleep session as a past "Sleep" panel (reduceSetSleepMode) and stop suppressing the pause cue.
        current.sleepingUntilMillis?.let { until -> if (now >= until) vm.setSleepMode(null) }
        maybeMaterializePastSleep(now)
    }

    // PRD §9/§17 past sleep: as `now` advances, record any scheduled sleep window that has fully elapsed and
    // turned out to be a no-screen/inactive period as a persisted past "Sleep" panel. Bounded to the portion
    // that elapsed while THIS session ran ([sessionStartMillis]) so a freshly-opened/emptied account never
    // retroactively assumes sleep across the whole derive window; the empty case ([inactivityGaps] empty = no
    // evidence yet) records nothing, matching the conservative sleep-band carve. The reducer dedups against
    // already-materialized panels and drops sub-minute slivers, so an over-eager call is a cheap no-op.
    private fun maybeMaterializePastSleep(now: Long) {
        val st = vm.state.value
        val sleep = st.sleep ?: return
        val gaps = _inactivityGaps.value
        if (gaps.isEmpty()) return
        val scheduled =
            SchedulerDomain.sleepRegions(sleep, now - PAUSE_DERIVE_HORIZON_MILLIS, now, tz)
                .filter { it.endEpochMillis <= now }
        if (scheduled.isEmpty()) return
        // The scheduled sleep that was inactive AND observed this session (start ≥ session start).
        val observed = listOf(TaskTimeRange(sessionStartMillis, now))
        val candidates =
            SchedulerDomain.intersectRegions(SchedulerDomain.intersectRegions(scheduled, gaps), observed)
                .filter { it.endEpochMillis - it.startEpochMillis >= SchedulerDomain.MIN_MANUAL_ENTRY_MILLIS }
        if (candidates.isNotEmpty()) vm.dispatch(SchedulerIntent.MaterializePastSleep(candidates))
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

    /**
     * PRD §15: the left-menu "I'm away" button. Declares this device idle (`true`) or back in use (`false`),
     * overriding the platform screen sensor. Flipping it advances the active session immediately — finalizing the
     * open session on `true` (locally, like a real walk-away) so its heartbeat is closed, and reopening one on
     * `false` so the heartbeat resumes — rather than waiting for the next beat. A
     * same-value call is a no-op. This device's own Inactivity band then derives the resulting pause on the next
     * refresh, exactly like an observed walk-away.
     */
    fun setUserAway(away: Boolean) {
        if (_userAway.value == away) return
        _userAway.value = away
        scope.launch {
            advanceActiveSession(clock.nowMillis(), effectiveScreenActive(), suspended = false)
        }
    }

    // The screen-activity sample every engine site reads: the real platform sensor, overridden to inactive
    // while the debug leap forces it ([setDebugForcedInactive]) or the user declared they are away ([setUserAway]).
    private fun effectiveScreenActive(): Boolean = !debugForcedInactive && !_userAway.value && screenActive()

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

    // PRD §15: at launch, seed each screen break's last-rest time from the last qualifying pause — this device's
    // OS sleep log (Windows) AND every device's synced sleep gaps already in the local store. The gap seeding is
    // what a phone (no readable OS sleep log) relies on to inherit the account's rests instead of showing a rest
    // pose pinned to the now-line the desktop doesn't have. Screen-break config is recomputed, never persisted.
    private fun launchScreenBreakSeeding() = scope.launch {
        val before = vm.state.value.screenBreaks
        val restedTasks = withContext(Dispatchers.Default) {
            val fromOsLog = before.map { side ->
                if (side.durationMillis <= 0) {
                    side
                } else {
                    val lastRest = lastWakeAfterLongSleepMillis(side.durationMillis)
                    if (lastRest != null) side.copy(lastRestMillis = lastRest) else side
                }
            }
            SchedulerDomain.seedScreenBreaksFromGaps(fromOsLog, loadStoredGaps())
        }
        applySeededScreenBreaks(restedTasks)
    }

    // PRD §15: after a pull brings in another device's exact sleep gaps, re-seed the rest poses from every gap now
    // in the local store — advancing `lastRestMillis` only (no work records / panel carving; see
    // [SchedulerDomain.seedScreenBreaksFromGaps]). This is the account-wide-pause signal reaching a device that never
    // slept, so its derived 5/15-min poses line up with the peer that recorded the sleep.
    private fun reseedScreenBreaksFromGaps() {
        val before = vm.state.value.screenBreaks
        applySeededScreenBreaks(SchedulerDomain.seedScreenBreaksFromGaps(before, loadStoredGaps()))
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
                if (deviceKind == DeviceKind.Phone) phoneSessionLeaseMillis() else ACTIVE_SESSION_BEAT_MILLIS_PROD
            tickDelay(if (timeAccelerated()) ACTIVE_SESSION_BEAT_MILLIS_SIM else prodBeat)
        }
    }

    // The instant a session claims activity up to when extended at `now`: the phone claims a one-minute
    // LEASE (`now + 1 min` — foreground is its only activity signal, renewed each beat, expiring on its own
    // if the app is backgrounded/killed); other devices claim only what the beat observed (`now`).
    private fun sessionClaimEnd(now: Long): Long =
        if (deviceKind == DeviceKind.Phone) now + phoneSessionLeaseMillis() else now

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

    // PRD §15: publish what the server needs about this device, on TWO cadences (migration 20260726000000).
    //
    //  * Presence — identity only, every `t_a` while the screen is on and signed in, cleared otherwise. Driven
    //    from every active-session beat so a walk-away is reflected within a beat; [DeviceHeartbeatPublisher]
    //    then re-upserts the row on its own ~10 s cadence.
    //  * The next rest pose — written only when it CHANGES. It used to ride the beat, which left the server up
    //    to one active-session beat (30 s in production) behind a schedule edit; if every device went dark
    //    inside that window the cue was judged on the pre-edit break, and the clean screen-off path hit that by
    //    construction (it calls e1 without a final beat). Publishing on change closes it.
    //
    // The break is published ONLY while active, and deliberately never cleared: the last value published while
    // active is exactly the state the user walked away in, which is what the server's overdue gate is judged on.
    private fun updatePresence() {
        val gateway = pauseCue ?: return
        val presence = realtimePresence ?: return
        val active = effectiveScreenActive() && gateway.signedIn
        presence.setPresence(if (active) PresenceState(gateway.deviceId) else null)
        if (!active) return

        // Compute the next rest-pose window LIVE from the screen-break config, NOT from the (possibly stale)
        // stored `state.panels`. Reading stored panels dropped the short (fast-break) now-line break as soon as
        // its window elapsed and substituted a far-future sleep-anchored occurrence, so the cue aimed hours out.
        // See RestPosePresenceWindowTest.
        val now = clock.nowMillis()
        val window = nextRestPoseWindowMillis(vm.state.value.screenBreaks, now)
        presence.setNextBreak(
            NextBreakState(
                deviceId = gateway.deviceId,
                kind = deviceKind.name.lowercase(),
                // WHICH break type is waiting, so the server resolves its configured length + vocal message.
                breakKind = window?.key?.takeIf { it.isNotBlank() },
                dueMillis = window?.let { publishableDueMillis(it.dueMillis, now) },
                lengthMillis = window?.lengthMillis,
            ),
        )
    }

    /**
     * The pose's due instant as the server should read it: a value that is STABLE while the pose is unchanged
     * (otherwise the event-driven write above would fire at every sample) and that lives on the same REAL
     * timeline as the server-stamped `beat_at` it will be compared against.
     *
     *  * Already due → [ALREADY_DUE_MILLIS]. The server only ever asks `due <= beat_at`, so any past value is
     *    equivalent; a constant one is the only choice that does not re-trigger a write as the now-line moves
     *    (the drawn start, `maxOf(due, now)`, rides the now-line and cannot be published event-driven at all).
     *  * Still upcoming → the REAL instant the clock reaches it at, so a device running an accelerated debug/sim
     *    clock does not report a sim-ahead instant the server would read against real wall-clock beats.
     */
    private fun publishableDueMillis(dueMillis: Long, now: Long): Long =
        if (dueMillis <= now) ALREADY_DUE_MILLIS else realInstantFor(dueMillis)

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
        val before = vm.state.value.screenBreaks
        applySeededScreenBreaks(SchedulerDomain.seedScreenBreaksFromGaps(before, pauses))
        // PRD §17: a fresh derive (startup, sync-pull) may reveal a scheduled sleep window was inactive — record
        // the observed part as a past "Sleep" panel now, not only on the next schedule advance.
        maybeMaterializePastSleep(until)
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
    // is **monotonic** — it folds [rested] into the LIVE screen breaks and never moves a pose's `lastRestMillis`
    // backward. This is essential because the two seeders run concurrently and off-thread against a snapshot
    // captured when they started: the slow OS-log query ([launchScreenBreakSeeding], an up-to-8s PowerShell call
    // built from the startup state where `lastRestMillis == 0`) can land AFTER [refreshDerivedPauses] has
    // already advanced the poses to `now` (the leading account-wide pause on a freshly-opened account ends at
    // the now-line). A wholesale overwrite would then drag `lastRestMillis` back to the morning wake and
    // re-pin the 5-/15-min pose to the now-line — the reported empty-account anomaly. Merging forward makes
    // the apply order irrelevant.
    private fun applySeededScreenBreaks(rested: List<ScreenBreak>) {
        val live = vm.state.value.screenBreaks
        val merged = SchedulerDomain.advanceRestsForward(live, rested)
        if (merged != live) {
            vm.dispatch(SchedulerIntent.SetScreenBreaks(merged))
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
    // from the mathematical [SchedulerDomain.screenBreakOccurrencesBetween] reconstruction (NOT `state.panels`,
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
                        screenBreaks = st.screenBreaks,
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
                    val restTitles = st.screenBreaks.filter { it.restBreak }.map { it.title }.toSet()
                    sidePoseNotifiedDue = sidePoseNotifiedDue.filterKeys { it in restTitles }

                    // Self-delay to the next boundary across every cue kind, so a cue fires at its instant and
                    // not up to a tick late (the outer collectLatest also re-keys each tick). The forward
                    // look-away / wind-down come from the projection, which keeps FUTURE occurrences; the next
                    // pose due is arithmetic.
                    val nextStart = st.panels
                        .filter { p -> p.screenBreak && st.screenBreaks.any { !it.restBreak && it.title == p.title } }
                        .map { it.startEpochMillis }
                        .filter { it > simNow && it !in announcedStarts }.minOrNull()
                    val nextEnd = pendingEnds.filter { it > simNow }.minOrNull()
                    val nextWind = windDownInstants.filter { it > simNow && it !in announcedWindDowns }.minOrNull()
                    val nextPose = if (st.automaticSchedule) {
                        st.screenBreaks
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
        val lookAway = st.screenBreaks.firstOrNull { !it.restBreak } ?: return
        stopSpeaking()
        pendingEnds = emptySet()
        manualLookAwayJob?.cancel()
        vm.dispatch(
            SchedulerIntent.SetScreenBreaks(
                st.screenBreaks.map { if (!it.restBreak) it.copy(lastRestMillis = now) else it },
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
            reseedScreenBreaksFromGaps()
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
    fun onAppForegrounded() {
        claimLastPhone()
        // PRD §15: a foregrounded phone must resume its device_heartbeat at once (it may have been
        // closed while backgrounded/locked) — sample the activity beat now instead of waiting for it.
        onPlatformActivityChanged()
    }

    /**
     * PRD §15: platform hint that this device's activity signal just flipped (phone lock/unlock, app brought
     * to the foreground). Samples the activity beat immediately — opening/finalizing the session and
     * resuming/closing the device_heartbeat (the liveness signal) within moments of the change instead of
     * waiting for the next minute beat. Same-value samples are no-ops, so spurious calls are harmless.
     */
    fun onPlatformActivityChanged() {
        scope.launch { advanceActiveSession(clock.nowMillis(), effectiveScreenActive(), suspended = false) }
    }

    private fun claimLastPhone() {
        val gateway = pauseCue ?: return
        if (deviceKind != DeviceKind.Phone) return
        scope.launch { withContext(Dispatchers.Default) { runCatching { gateway.claimLastPhone() } } }
    }

    /**
     * PRD §15 cue delivery: the native push receiver (an FCM/APNs data message from the `pause-cue` Edge
     * Function) calls this — schedule the local OS cue at [dueAtMillis] on `"schedule"`, or cancel the pending
     * one on `"cancel"`. The scheduled cue's fire handler ([onPauseCueFire]) re-checks the local screen before
     * speaking.
     *
     * [voiceCue] is the break's configured vocal message (`break_config.voice_cue`, changeable over HTTP),
     * resolved to one of the app's bundled cues — the phone plays a pre-rendered clip rather than synthesizing
     * arbitrary text, so an unknown id falls back to [VoiceCue.PauseOver]. Remembered until the alarm fires.
     */
    fun onPauseCuePush(action: String, dueAtMillis: Long?, voiceCue: String? = null) {
        Diagnostics.log(
            "pause-cue push handled: action=$action due_at=" +
                (dueAtMillis?.let { Diagnostics.formatInstant(it) } ?: "null") +
                " voice_cue=${voiceCue ?: "-"}",
        )
        when (action) {
            "schedule" ->
                if (dueAtMillis != null) {
                    pendingPauseCue = resolveVoiceCue(voiceCue)
                    scheduleLocalPauseCue(dueAtMillis)
                }
            "cancel" -> scheduleLocalPauseCue(null)
        }
    }

    // The cue the pending OS alarm should speak, as named by the push that scheduled it (PRD §15).
    private var pendingPauseCue: VoiceCue = VoiceCue.PauseOver

    /**
     * PRD §15: the platform local cue fired at the pause-end instant (an AlarmManager alarm / a delivered
     * `UNNotification`) calls this — the server cron already decided the whole account was inactive when it
     * scheduled the cue, so the phone only re-checks its OWN screen (the user may have picked the phone up)
     * and speaks if it is still off. Inert unless this is a signed-in phone with its screen off.
     */
    suspend fun onPauseCueFire() {
        if (poseFinishEligible(
                isPhone = deviceKind == DeviceKind.Phone,
                signedIn = pauseCue?.signedIn == true,
                screenActive = effectiveScreenActive(),
            )
        ) {
            speakCue(pendingPauseCue)
        } else {
            Diagnostics.log(
                "OS pause-cue alarm fired but suppressed (phone=${deviceKind == DeviceKind.Phone}, " +
                    "signedIn=${pauseCue?.signedIn == true}, screenActive=${effectiveScreenActive()})",
            )
        }
    }

    companion object {
        /**
         * PRD §15 (server-side break computation): the `(due, length)` window of the governing 5/15-min rest
         * pose as of [now], computed LIVE from the [screenBreaks] config — the value this device writes into its
         * `device_break` row so the server can decide whether the account went idle with a break owed, and time
         * the cue as `idleInstant + length`. Null when no rest pose is configured.
         *
         * **`dueMillis` is the pose's mathematical due instant `lastRest + interval`, NOT its drawn start.** The
         * drawn start is [SchedulerDomain.screenBreakNextStart] = `maxOf(due, now)`, which for an overdue pose
         * rides the now-line and therefore changes at every sample — publishing that is what forced the window
         * onto the `t_a` beat in the first place (migration 20260726000000). The due instant moves only when the
         * pose is served or reconfigured, so it can be written event-driven; it is also the same instant the
         * client's own rest-pose cue keys on (`SchedulerDomain.reachedRestPoseDueByTitle`), so client and server
         * now agree on one boundary. "Overdue" is `due <= now` either way.
         *
         * **Selection rule (PRD §15): when several poses are simultaneously overdue, the LONGEST one governs** —
         * resting the 15-min pose also discharges the 5-min one due at the same instant, so the user is told to
         * rest 15 min, not 5. Only when NONE is overdue does the soonest-DUE upcoming pose win (there is nothing
         * yet to discharge). Deliberately NOT derived from `state.panels`: that frozen snapshot's short
         * (fast-break) now-line break expires within seconds and is then replaced there by a far-future
         * sleep-anchored occurrence, which the server would mistime the cue to (hours out). See
         * RestPosePresenceWindowTest.
         */
        internal fun nextRestPoseWindowMillis(
            screenBreaks: List<ScreenBreak>,
            now: Long,
        ): RestPoseWindow? {
            val poses = screenBreaks.asSequence()
                .filter { it.restBreak && it.intervalMillis > 0 && it.durationMillis > 0 && it.title.isNotBlank() }
                .map { RestPoseWindow(it.lastRestMillis + it.intervalMillis, it.durationMillis, it.key) }
                .toList()
            if (poses.isEmpty()) return null
            // Among the poses already due, the longest length governs. Fall back to the soonest-due upcoming
            // pose only when nothing is overdue.
            val overdue = poses.filter { it.dueMillis <= now }
            return overdue.maxByOrNull { it.lengthMillis } ?: poses.minByOrNull { it.dueMillis }
        }

        /**
         * The governing rest pose as published to the server: when it comes DUE, how long it lasts, and WHICH
         * break type it is ([ScreenBreak.key] — `"5min_break"`/`"15min_break"`, blank for an ad-hoc break). The
         * key is what lets the server resolve the break's configured length + vocal message (`break_config`).
         */
        internal data class RestPoseWindow(
            val dueMillis: Long,
            val lengthMillis: Long,
            val key: String,
        )

        /**
         * The `break_due_ms` a device publishes for a pose that is ALREADY due (PRD §15, migration
         * 20260726000000). The server's only question is `due <= beat_at`, so the epoch answers it for every
         * beat — and, unlike the true instant, it does not change as the now-line advances, which is what keeps
         * the break row's write cadence event-driven.
         */
        internal const val ALREADY_DUE_MILLIS: Long = 0L

        /**
         * PRD §15: the bundled cue a `break_config.voice_cue` id names. The cues are pre-rendered clips
         * ([VoiceCue]), so a message the app does not ship falls back to the generic "your pause is over".
         */
        internal fun resolveVoiceCue(id: String?): VoiceCue = when (id?.trim()?.lowercase()) {
            "look_away" -> VoiceCue.LookAway
            "resume_work" -> VoiceCue.ResumeWork
            else -> VoiceCue.PauseOver
        }

        /**
         * PRD §15: the gate for the phone's "pause finished" voice cue — true only when this is the phone, a
         * signed-in session is available, and this device's screen is off. (Whether any OTHER device is active
         * was already decided by the server cron before it pushed this phone.)
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
