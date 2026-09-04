package org.example.project.scheduler.engine

import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.getAndUpdate
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
import org.example.project.scheduler.domain.DynamicPeriods
import org.example.project.scheduler.domain.AlarmDomain
import org.example.project.scheduler.domain.TimerDomain
import org.example.project.scheduler.domain.PlanBlock
import org.example.project.scheduler.domain.PlanTask
import org.example.project.scheduler.domain.PeriodKinds
import org.example.project.scheduler.domain.RestrictivePeriod
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.AlarmEntry
import org.example.project.scheduler.model.TimerEntry
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
import org.example.project.scheduler.platform.GlobalShortcut
import org.example.project.scheduler.platform.GlobalShortcutBindings
import org.example.project.scheduler.platform.deviceLockedIntervals
import org.example.project.scheduler.platform.currentDeviceKind
import org.example.project.scheduler.platform.isScreenActive
import org.example.project.scheduler.platform.cancelSystemNotifications
import org.example.project.scheduler.platform.sendSystemNotification
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

// PRD §9: the debounce on the ONE thing that re-plans the schedule — a change to the scheduling rules
// ([SchedulerDomain.schedulingSignature]), made here or pulled from another device. One second: long enough
// that typing a task title, dragging a panel or a burst of pulled changes costs a single fill, short enough
// that the calendar visibly answers the edit. Sim time, like every other engine delay: an accelerated clock
// is meant to reach the next fill sooner, not to change how many fills a burst produces.
private const val RESCHEDULE_DEBOUNCE_MILLIS: Long = 1_000

// PRD §9: the STALENESS bound on the plan — the longest the schedule may go without being re-planned. The
// rule-change watcher above answers "the inputs changed"; this answers "the plan has simply been standing
// for too long", so a session where nobody edits anything still re-plans hourly instead of serving a plan
// laid down against a now-line an arbitrary distance behind. It is not a tick: the timer is reset by EVERY
// re-plan ([markRescheduled]), so an account being edited never reaches it, and a quiet one costs exactly
// one fill per hour. Sim time like every other engine duration — an accelerated clock reaches the next
// re-plan sooner rather than re-planning the same hour more often.
internal const val SCHEDULE_STALENESS_MILLIS: Long = 60L * 60 * 1_000

// How often the task-tree timeline's blend cursor is SAMPLED (see [launchTaskTreeBlendReschedule]). Not a
// re-plan cadence: a fill happens only when the sample crosses a quantized step, so this only bounds how
// late a step is noticed. One minute is far finer than the shortest transition anyone would draw on a
// calendar of dates, and it is sim time like every other engine delay, so an accelerated clock reaches the
// next step sooner rather than sampling the same instant more often.
private const val TASK_TREE_BLEND_POLL_MILLIS: Long = 60_000

// Real-time cap on each sleep while the manual "Look away now" rest counts down (see [restartLookAway]).
private const val LOOK_AWAY_RESUME_POLL_MILLIS: Long = 200

// PRD §15: what the end of a look-away break announces (see [SchedulerEngine.announceResumeWork]). Posted as a
// notification, not merely spoken, so the History window's Notifications column shows a break's end as well as
// its start; the `resume_work` voice cue speaks the same thing.
private const val RESUME_WORK_TITLE: String = "Screen break over"
private const val RESUME_WORK_MESSAGE: String = "Resume your work"

// PRD §7/§15: the title every system-wide chord's receipt is posted under (see
// [SchedulerEngine.announceShortcutReceived]). One shared title, so the receipts group together in the
// History window's Notifications column and in the OS's own notification list — the chord itself is the
// message, which is what tells the user WHICH press landed.
private const val SHORTCUT_RECEIVED_TITLE: String = "Shortcut received"

// PRD §11: what turning notifications back ON announces (see [SchedulerEngine.setNotificationsEnabled]). The
// only notification the mute cannot hide, because it is posted from the far side of the flip — which is what
// makes the un-mute chord's own (still-muted, hence swallowed) receipt visible after all.
private const val NOTIFICATIONS_ON_TITLE: String = "Notifications on"
private const val NOTIFICATIONS_ON_MESSAGE: String = "OmniApp will notify you again"

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
// are all local-only; the rows ride the next reconcile, whichever trigger fires it.
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
// The real-time engine cadence for schedule banking and housekeeping. The Compose UI samples the visual
// now-line from the frame clock, so this is deliberately unrelated to display refresh.
private const val ADVANCE_TICK_MILLIS_PROD: Long = 30L * 1_000
// The finer cadence used for the schedule-advance dispatch and the horizon/active-session polls while
// accelerated (the display now-line moves faster still, every [ADVANCE_DISPLAY_MILLIS_ACCEL]).
private const val ADVANCE_TICK_MILLIS_ACCEL: Long = 1_000
// Coarsest sim-time between schedule advances (banking auto records / re-deriving panels). The display
// now-line moves every display tick; the heavier advance only needs ~1 s-of-sim granularity — banking
// records at sub-second sim resolution is pointless and just churns the reducer.
private const val SCHEDULE_ADVANCE_STEP_MILLIS: Long = 1_000

/**
 * `side-dev/README.md` § *Progressive Calculation*, the escape clause: **the most steps one journey of the
 * now-line may be walked in.**
 *
 * The line's step is one minimum execution time ([SchedulerDomain.sweepStepMillis]), and that is what the walk
 * asks for. But the journey's LENGTH is not the app's to choose — a machine asleep for a week, a debug leap of
 * a month — and an account whose finest minimum is one minute would ask for tens of thousands of commits, on
 * the dispatcher every sweep and every advance is queued behind (ADR 0009). So the stride is widened, once,
 * to bring the whole journey inside this many steps: "if exact schedules cannot be found in time, approved
 * approximation strategies must be used". It only ever bites on a journey no ordinary tick takes — 3 h of
 * ground at a 15-minute minimum is 12 steps — and it is logged when it does.
 */
private const val MAX_SWEEP_STEPS: Int = 2_000

/**
 * PRD §9/§12: how often the engine re-reads the OS lock/standby history that feeds
 * [SchedulerReducer.noScreenEvidence]. Reading it costs a process launch (a PowerShell query on Windows), so it
 * is emphatically NOT on the advance cadence — 10 min, the same bucket the calendar's own layer scan uses.
 */
private const val NO_SCREEN_EVIDENCE_REFRESH_MILLIS: Long = 10L * 60 * 1000

/**
 * PRD §9/§12: how far back the engine asks about. The evidence only has to cover the span still waiting to be
 * banked — the panels that elapsed since the last tick — so a day of slack is generous even across a long
 * suspend, and it keeps the query bounded (ADR 0009: never O(total history)). The RETROACTIVE strip at startup
 * asks over the display window instead, once.
 */
private const val NO_SCREEN_EVIDENCE_LOOKBACK_MILLIS: Long = 24L * 60 * 60 * 1000

// PRD §18 Alarms: how late (in REAL time, measured by [BoundarySweep] like every other boundary) a crossed
// alarm instant may be and still ring on a device that has no OS alarm clock (see [launchAlarmSweep]). That
// sweep self-delays to each ring's instant, so a running app crosses it within milliseconds; a larger real
// age means the process was suspended (machine asleep, lid shut) straight through the ring, where sounding
// it on resume is only noise. More generous than [LOOK_AWAY_START_FRESH_MILLIS] because an alarm is still
// worth hearing a few seconds late, whereas a stale look-away cue is not.
private const val ALARM_FRESH_MILLIS: Long = 60_000

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
    /**
     * PRD §18 Timers: whether [alarmId] names a `TimerEntry` rather than an `AlarmEntry`. A timer is due at
     * one absolute instant instead of a wall-clock time of day, and that is the *whole* difference — so it is
     * armed, swept and rung through this same type, and this flag exists only so `onAlarmFire` knows which
     * list to put the row back in and what to call the ring. It travels with the armed ring (into the phone's
     * OS intent included) rather than being inferred from the id, so the routing is stated, not guessed.
     */
    val timer: Boolean = false,
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
    // PRD §11: the notification sink and the "withdraw what is already showing" seam (default to the platform
    // notifier); injectable for the same reason [playCue] is — what the app POSTED is otherwise unassertable,
    // and the mute below is precisely a rule about that call and not about the log beside it.
    private val postNotification: (String, String) -> Unit = ::sendSystemNotification,
    private val clearNotifications: () -> Unit = ::cancelSystemNotifications,
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
    // active-session rows travel to peers ONLY inside a reconcile (any trigger: startup, an account change,
    // the debounced auto-push, a Realtime poke, the button) — never on a timer of their own. There is no live
    // cross-device activity channel, so peers learn of activity at their next reconcile.
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
    // PRD §9/§12: the stretches the DEVICES observed nobody at a screen for — both calendar layers' OS
    // lock/standby evidence intersected. Feeds SchedulerReducer.noScreenEvidence, so §9's "assume nothing
    // happened" rule stops banking an on-screen task's record over a machine the OS says was asleep. Cached
    // here because the read is a process launch; refreshed by [launchNoScreenEvidenceScan] on a coarse bucket.
    // Derived, device-level and local: never persisted, never synced, never part of any fingerprint.
    private val _noScreenEvidence = MutableStateFlow<List<TaskTimeRange>>(emptyList())

    // The two halves [_noScreenEvidence] is the union of, kept apart because they are refreshed by different
    // events: what the last OS scan READ ([launchNoScreenEvidenceScan], a process launch on a coarse bucket)
    // and what the now-line SWEPT in mode 2 ([sweepNowLineTo], an edge — a wake from device sleep). Only the
    // union is ever published, by [publishNoScreenEvidence]; nothing else writes the flow.
    private var scannedNoScreen: List<TaskTimeRange> = emptyList()
    private var sweptNoScreen: List<TaskTimeRange> = emptyList()

    // `side-dev/README.md` § *$now line$ 2 modes*: the mode the JOURNEY is being walked in, held for as long as
    // the walk lasts and null at every other instant. A wake from device sleep is swept in mode 2 — the README
    // says so in as many words — and mode 2 is a statement about where the line is, not about where it has
    // arrived, so every position the walk commits has to be asked in it. Read by [tpModeNow], which is the one
    // reading of the mode the fills and the display share; never persisted, never synced.
    private var sweepMode: Int? = null

    /**
     * The stretches the devices observed nobody at a screen for — the ONE reading of it in the app.
     *
     * `side-dev/README.md` § *3 Dynamic Restrictive Period*: the recurrence bars read their rest stretches out
     * of the timeline, so this evidence is part of the timeline they are asked about
     * ([SchedulerDomain.observedNoScreenPeriods]). It reaches the fill through
     * [SchedulerReducer.noScreenEvidence], the cue sweep and the published due through the call sites below,
     * and the calendar through this flow — one cached answer, so none of them can place a break on a different
     * timeline from the others.
     */
    val noScreenEvidence: StateFlow<List<TaskTimeRange>> = _noScreenEvidence.asStateFlow()

    private val _inactiveSince = MutableStateFlow<Long?>(null)

    /** End of this device's last finalized session — the live "Inactivity" tail's start (null = none pending). */
    val inactiveSince: StateFlow<Long?> = _inactiveSince.asStateFlow()

    // Every stored active session — this device's own AND the peers' rows the last reconcile pulled
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

    // PRD §15: whether the gateway was signed in at the last sync moment. Only the signed-out→signed-in EDGE
    // matters — it is what starts the `t_a` presence tick at login rather than at the next 30-s activity beat
    // (see the sync-moment collector in [start]). Starts false so a startup auto-login counts as the edge.
    private var lastKnownSignedIn = false

    // PRD §15: the user manually declared they are AWAY from this device via the left-menu "I'm away" button.
    // While set, the device reads screen-inactive to the PRESENCE reading ([effectiveScreenActive]) regardless
    // of the platform sensor, so its active session is finalized and its `t_a` presence tick stopped +
    // reported as a screen-off — telling the server this device is no longer being worked on. It says nothing
    // about the app's OUTPUT: the notifications and voice cues keep firing, because the screen is still
    // unlocked and the user can still act on them ([deviceUnlocked] is what gates those, and it does not read
    // this flag). Purely local runtime state: never persisted, never synced, so a restart returns the device
    // to active. Toggled by [setUserAway], cleared by an unlock ([noteScreenSignal]); the menu reads it via
    // [userAway].
    private val _userAway = MutableStateFlow(false)

    /** PRD §15: whether the user declared they are away from this device (drives the left-menu button label). */
    val userAway: StateFlow<Boolean> = _userAway.asStateFlow()

    // The RAW platform lock signal ([screenActive], unmasked by the away flag or the debug leap) as of the last
    // sample — the only thing the away flag's automatic clearing is read from (see [noteScreenSignal]). A
    // MutableStateFlow rather than a plain field because the two samplers sit on different threads: the
    // active-session beat is on the engine scope, while [onPlatformActivityChanged] is called straight from the
    // OS notification thread (the Win32 session listener, Android's broadcast receiver). Null until the first
    // sample, so an engine that starts on a locked device has no phantom edge to answer.
    private val lastScreenSignal = MutableStateFlow<Boolean?>(null)

    // PRD §7: a §9 calculation event that comes due while "Auto schedule" is off is deferred and coalesced
    // into a single reschedule fired when the switch is turned back on.
    private var pendingReschedule = false

    // PRD §9: the clock instant of the last RE-PLAN this engine asked for — what [launchStaleReschedule]
    // measures the [SCHEDULE_STALENESS_MILLIS] bound against, so any re-plan (a rule change, a blend step,
    // the deferred one the §7 switch releases, the manual look-away re-anchor) postpones the next stale
    // refill by a full hour. Stamped even when the §7 switch is OFF and the re-plan is only *deferred*:
    // the deferred one is coalesced and fires on the switch, so re-arming the timer there is what keeps
    // this loop from spinning on a request it cannot dispatch. Null until the first re-plan; deliberately
    // NOT stamped by an ExtendSchedule, which materializes the tail without re-planning the head.
    // `internal` so the rule's test can read the timer directly: a re-plan of an unchanged account is a
    // deliberate no-op (the same inputs at a later `now` yield the same continuation, so `panels` is
    // untouched and nothing is saved), which leaves this stamp as the only observable of the trigger.
    internal var lastRescheduleMillis: Long? = null
        private set

    // PRD §9: the end of the day span the calendar window is DISPLAYING, published by the UI
    // ([setCalendarHorizon]); null when no calendar is open. It is the calendar half of $t_{goal}$ — see
    // [scheduleHorizonEndMillis] and [SchedulerDomain.scheduleGoalEndMillis]. Local runtime
    // view state: never persisted, never synced, never part of the schedule's inputs (it only bounds HOW FAR
    // the same deterministic plan is computed, so two devices on different weeks still agree where they
    // overlap).
    private val _calendarHorizonEndMillis = MutableStateFlow<Long?>(null)

    /**
     * PRD §9: tell the engine which days the calendar is showing — the EXCLUSIVE end of the displayed day
     * span, which is also the start of the first day that does NOT appear — so the §9 fill materializes the
     * plan out to the $t_{goal}$ it implies ([SchedulerDomain.scheduleGoalEndMillis]). Pass null when no
     * calendar is open: the goal then falls back to the current week's own, which is what the headless
     * notification/cue paths read. Growing it triggers one refill (see [launchCalendarHorizonReschedule]);
     * shrinking it triggers none — the goal is a MAX, so scrolling back never shortens the schedule below
     * what the current week asks for.
     */
    fun setCalendarHorizon(endMillis: Long?) {
        _calendarHorizonEndMillis.value = endMillis
    }

    /**
     * The §9 fill horizon in force at [now]: **$t_{goal}$**
     * ([SchedulerDomain.scheduleGoalEndMillis] — the end of the first day that does not appear in the
     * calendar, or of the first day of the week after the current week, whichever is further), capped for a
     * calendar-driven far week.
     */
    private fun scheduleHorizonEndMillis(now: Long): Long =
        SchedulerDomain.scheduleHorizonEndMillis(now, _calendarHorizonEndMillis.value, tz)

    // PRD §11/§15 notification de-dupe (see the long-form rationale in git history of App.kt).
    private var lastNotifiedTaskId: TaskId? = null

    // PRD §15 (20s look-away) / wind-down bookkeeping; survives a collectLatest restart like the old remember.
    private var announcedStarts = setOf<Long>()
    private var pendingEnds = setOf<Long>()
    private var announcedWindDowns = setOf<Long>()
    // PRD §15 (5/15-min rest-pose notification): the placed START already announced per rest-pose title, so a
    // break fires once when the now-line reaches it and never again. The key is stable because the recurrence
    // bars pin the start (ADR 0003) — it was an anchored due precisely because an owed pose used to drag along
    // the now-line and had no stable drawn start to key on. See [launchCueSweep].
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
        // `side-dev/README.md`: the recurrence bars read the rest stretches out of the TIMELINE, so this
        // device's live ongoing pause reaches every placement as the period it is
        // ([SchedulerDomain.liveRestPeriod] off [SchedulerReducer.liveRestGap]) rather than as an overlay on
        // a stored anchor. There is no stored anchor: nothing seeds, serves or advances a `lastRest` any
        // more (ADR 0003).
        // PRD §9/§12: what the DEVICES observed about whether anyone was at a screen, feeding §9's "assume
        // nothing happened" rule. Cached, because reading it is a process launch; refreshed on a coarse bucket
        // by [launchNoScreenEvidenceScan] below and read here synchronously by every banking reducer path.
        SchedulerReducer.noScreenEvidence = { _noScreenEvidence.value }
        SchedulerReducer.liveRestGap = {
            SchedulerDomain.liveRestGap(_inactiveSince.value, _activeSince.value, clock.nowMillis())
        }
        // `side-dev/README.md` § *$t_p$ 2 modes*: mode 1 while a device of the account is unlocked, mode 2
        // otherwise. Read at fill time from the same account-wide pause the calendar draws ([tpModeNow]).
        SchedulerReducer.tpMode = { tpModeNow() }
        // PRD §9 / `docs/scheduler_requirements.md` § *Progressive Calculation*: every refill materializes the
        // plan out to $t_{goal}$ — the end of the first day that does not appear in the calendar, or of the
        // first day of the week after the current week, whichever is further.
        SchedulerReducer.scheduleHorizonEndMillis = { now -> scheduleHorizonEndMillis(now) }
        launchNoScreenEvidenceScan()
        launchRetroactiveNoScreenStrip()
        launchAdvanceTick()
        launchRuleChangeReschedule()
        launchStaleReschedule()
        launchTaskTreeBlendReschedule()
        launchHorizonReschedule()
        launchCalendarHorizonReschedule()
        launchPendingRescheduleOnSwitch()
        launchTpModeReschedule()
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
        pauseCue?.syncMoments?.let { moments ->
            scope.launch {
                moments.collect {
                    refreshDerivedPausesNow()
                    // PRD §15: the presence tick must start as soon as the app is LOGGED IN, not at the next
                    // 30-s activity beat. A reconcile is what carries a sign-in, so sample the beat here on the
                    // signed-out→signed-in edge; [updatePresence] then starts the `t_a` loop (and publishes the
                    // break dues) right away. Edge-triggered, so an ordinary reconcile costs no extra work.
                    val signedIn = pauseCue?.signedIn == true
                    if (signedIn && !lastKnownSignedIn) {
                        advanceActiveSession(clock.nowMillis(), effectiveScreenActive(), suspended = false)
                    }
                    lastKnownSignedIn = signedIn
                }
            }
        }
        // PRD §15 / listener cue delivery: a phone's startup claims the account's last phone so the external
        // listener knows which phone to push. Phones only; a no-op when sync is disabled / signed out.
        claimLastPhoneOnStartup()
        resolveSleepModeOnStartup()
        // PRD §18 Alarms/Timers: keep this phone's OS alarm armed for the next ring in the synced lists.
        launchAlarmArming()
        // PRD §18 Alarms/Timers: on a device with no OS alarm clock (the desktop), ring from the now-line.
        launchAlarmSweep()
    }

    // ----- PRD §18 Alarms and timers -------------------------------------------------------------------------

    // The ring this device currently has armed with the OS, so a re-computation that lands on the same
    // (alarm, instant) doesn't re-arm on every tick. Null when nothing is armed.
    private var armedAlarm: ArmedAlarm? = null

    // [launchAlarmSweep]'s own real-age bookkeeping (a field, so the sweep's collectLatest re-key on every
    // now-tick never resets the previous-sweep anchor) and the rings it has already sounded, as
    // (alarm id, boundary instant) — keyed on BOTH because two alarms may share one instant, and one
    // de-duped on the instant alone would silence the second.
    private val alarmSweep = BoundarySweep()
    private var rungAlarms = setOf<Pair<String, Long>>()

    /**
     * PRD §18 Alarms/Timers: keep the OS-level alarm armed for the soonest ring in the synced alarm **and
     * timer** lists. Re-runs on every now-tick and whenever either list changes (an edit here, a timer
     * started, or a peer's edit arriving over sync), and only touches the OS when the target actually moves.
     *
     * The two lists are armed **together** because the OS slot is one: a second arming loop would overwrite
     * whatever the first had put there, and the device would ring for whichever of the two happened to be
     * recomputed last.
     *
     * **Phones only** — not because other devices stay silent (the desktop rings too, see [launchAlarmSweep]),
     * but because only a phone has an OS alarm clock to hand the ring to. A phone must ring with the app
     * killed and the device dozing, which nothing in this process can promise; a desktop app that is not
     * running cannot ring at all, so there is nothing to arm and the now-line IS the trigger.
     */
    private fun launchAlarmArming() = scope.launch {
        if (deviceKind != DeviceKind.Phone) return@launch
        combine(
            _nowMillis,
            vm.state.map { it.alarms }.distinctUntilChanged(),
            vm.state.map { it.timers }.distinctUntilChanged(),
        ) { now, alarms, timers -> Triple(now, alarms, timers) }
            .collectLatest { (now, alarms, timers) -> armNextAlarm(now, alarms, timers) }
    }

    // Computes the next ring after [now] — the soonest of the alarms' and the timers' — and hands it to the
    // OS (or cancels when there is none). Ties go to the alarm, arbitrarily but stably: the two lists' ids
    // are disjoint, so the choice only has to be the same on every device.
    private fun armNextAlarm(now: Long, alarms: List<AlarmEntry>, timers: List<TimerEntry>) {
        val nextAlarm = AlarmDomain.nextOccurrence(alarms, now, tz)?.let {
            ArmedAlarm(
                alarmId = it.entry.id,
                atMillis = it.instant,
                label = it.entry.label,
                soundSeconds = it.entry.soundSeconds,
                vibrate = it.entry.vibrate,
            )
        }
        val nextTimer = TimerDomain.nextOccurrence(timers, now)?.let {
            ArmedAlarm(
                alarmId = it.entry.id,
                atMillis = it.instant,
                label = it.entry.label,
                soundSeconds = it.entry.soundSeconds,
                vibrate = it.entry.vibrate,
                timer = true,
            )
        }
        // The winner is picked on the engine's own (possibly simulated) timeline, and only then converted to
        // the real instant the OS understands — the two candidates are comparable there, and [realInstantFor]
        // is a per-call recomputation that has no business deciding which ring is sooner.
        val armed = listOfNotNull(nextAlarm, nextTimer)
            .minByOrNull { it.atMillis }
            ?.let { it.copy(atMillis = realInstantFor(it.atMillis)) }
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
     * PRD §18 Alarms/Timers on a device with **no OS alarm clock** (the desktop): ring from the now-line. Every
     * sweep fires the instants the clock crossed since the previous one, in boundary order, then self-delays
     * to the next ring — without that delay a ring would sound up to one production tick
     * ([ADVANCE_TICK_MILLIS_PROD], 30 s) late and then be swallowed as stale by its own freshness budget.
     *
     * Per CLAUDE.md the trigger is a pure function of the boundary instants the clock crossed: the ring
     * instants come from [AlarmDomain.crossingsBetween] (a fixed wall-clock instant per local day, so a leap
     * over several days still fires each one, in order), consecutive sweeps tile the timeline via
     * [BoundarySweep.scanFloorMillis] so a clock jump cannot clip a crossing out of the scan, and whether a
     * scanned crossing actually rings is decided ONLY by its REAL age ([ALARM_FRESH_MILLIS]) — an alarm the
     * machine slept through stays silent, one the running app merely observed late still rings.
     *
     * The **timers** are swept in the same pass, their crossings merged into the alarms' in boundary order
     * (ids are disjoint, so the de-dupe key cannot collide) — one sweep for both, exactly as one arming loop
     * serves both on the phone. A timer the machine slept through is swallowed by the same freshness budget,
     * and for the same reason: it is late in real time, and a countdown that ran out while the process was
     * down is not worth sounding at the wrong moment.
     *
     * No lock gate, unlike the §15 break cues ([deviceUnlocked]): an alarm exists precisely to be heard by a
     * user who is not at the screen — a locked machine is the case it is FOR, which is why the phone arms it
     * with the OS and rings it with the app killed (ADR 0010). It is the one deliberate exception to "a
     * locked device says nothing", and it is an exception about alarms, never about a break cue.
     */
    private fun launchAlarmSweep() = scope.launch {
        if (deviceKind == DeviceKind.Phone) return@launch
        combine(
            _nowMillis,
            vm.state.map { it.alarms }.distinctUntilChanged(),
            vm.state.map { it.timers }.distinctUntilChanged(),
        ) { _, alarms, timers -> alarms to timers }
            .collectLatest { (alarms, timers) ->
                while (true) {
                    val simNow = clock.nowMillis()
                    val speed = (clock as? SimAppClock)?.speed ?: 1.0
                    alarmSweep.beginSweep(simNow, speed, clockGeneration())
                    val scanFloor = alarmSweep.scanFloorMillis(LOOK_AWAY_SWEEP_CAP_MILLIS)
                    rungAlarms = rungAlarms.filterTo(mutableSetOf()) { it.second >= scanFloor }

                    for (crossing in ringCrossingsBetween(alarms, timers, scanFloor, simNow)) {
                        val key = crossing.alarmId to crossing.atMillis
                        if (key in rungAlarms) continue
                        rungAlarms = rungAlarms + key
                        val lateness = alarmSweep.realLatenessMillis(crossing.atMillis)
                        if (lateness > ALARM_FRESH_MILLIS) {
                            Diagnostics.log(
                                "alarm ${crossing.alarmId} at ${Diagnostics.formatInstant(crossing.atMillis)} " +
                                    "swallowed: crossed ~$lateness ms (real) ago — process was suspended or " +
                                    "engine just started (budget $ALARM_FRESH_MILLIS ms, speed ${speed}x)",
                            )
                            continue
                        }
                        // Same entry point the phone's OS receiver uses, so both rings behave identically
                        // (ring what was decided, disarm a one-off / reset a timer, log it).
                        onAlarmFire(crossing)
                    }

                    // Sleep to the next ring so it sounds AT its instant. Re-read both lists: a one-off just
                    // rung has disarmed itself, and a timer just rung has reset itself, in the dispatch above.
                    val state = vm.state.value
                    val next = listOfNotNull(
                        AlarmDomain.nextOccurrence(state.alarms, simNow, tz)?.instant,
                        TimerDomain.nextOccurrence(state.timers, simNow)?.instant,
                    ).minOrNull() ?: break
                    if (speed <= 0.0) break
                    delay(((next - simNow).toDouble() / speed).toLong().coerceAtLeast(1L))
                }
            }
    }

    /**
     * PRD §18: every ring — alarm or timer — the clock crossed in `(fromMillis, toMillis]`, as the
     * [ArmedAlarm]s to sound, in boundary order. One merged stream so the two kinds fire in the order they
     * are actually due rather than one whole list at a time.
     */
    private fun ringCrossingsBetween(
        alarms: List<AlarmEntry>,
        timers: List<TimerEntry>,
        fromMillis: Long,
        toMillis: Long,
    ): List<ArmedAlarm> {
        val fromAlarms = AlarmDomain.crossingsBetween(alarms, fromMillis, toMillis, tz).map {
            ArmedAlarm(
                alarmId = it.entry.id,
                atMillis = it.instant,
                label = it.entry.label,
                soundSeconds = it.entry.soundSeconds,
                vibrate = it.entry.vibrate,
            )
        }
        val fromTimers = TimerDomain.crossingsBetween(timers, fromMillis, toMillis).map {
            ArmedAlarm(
                alarmId = it.entry.id,
                atMillis = it.instant,
                label = it.entry.label,
                soundSeconds = it.entry.soundSeconds,
                vibrate = it.entry.vibrate,
                timer = true,
            )
        }
        return (fromAlarms + fromTimers).sortedWith(compareBy({ it.atMillis }, { it.alarmId }))
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
     * PRD §18 Alarms/Timers: the OS alarm fired — ring exactly what was [armed] (its sound for its configured
     * length, vibrating if asked), post the notification, put the row that rang back where it belongs, and arm
     * the next ring. Called from the platform receiver, which may have woken the process from scratch.
     *
     * The ring itself is unconditional: the arming decision was already made from the lists, so a list that
     * has since changed (typically a peer that rang the same one-off a moment earlier and synced its disarm)
     * must not silence this phone — PRD §18 rings every phone of the account.
     *
     * What "putting the row back" means is the one thing the two kinds do differently, and it follows from
     * what each of them is: a **one-off alarm** disarms itself (it has an on/off switch, and it has now
     * happened), while a **timer** resets to its full duration (it has no switch — a countdown that has run
     * out is simply back at the start, ready to be started again).
     */
    fun onAlarmFire(armed: ArmedAlarm) {
        val kind = if (armed.timer) "timer" else "alarm"
        Diagnostics.log(
            "$kind ${armed.alarmId} RINGING (${armed.soundSeconds}s sound, vibrate=${armed.vibrate})",
        )
        ringAlarm(armed)
        val title = if (armed.timer) "Timer" else "Alarm"
        notifyUser(title, armed.label.ifBlank { title })
        if (armed.timer) {
            // A timer is a one-off by nature: it has run out, so it goes back to its full duration. Unknown
            // id = deleted meanwhile; nothing to reset.
            vm.dispatch(SchedulerIntent.ResetTimer(armed.alarmId))
        } else {
            // A one-off alarm has now rung: disarm it so it doesn't come round again tomorrow (the row stays
            // in the window, ready to be re-armed). Unknown id = deleted meanwhile; nothing to disarm.
            val entry = vm.state.value.alarms.firstOrNull { it.id == armed.alarmId }
            if (entry != null && !entry.repeats) {
                vm.dispatch(SchedulerIntent.SetAlarmEnabled(entry.id, false))
            }
        }
        // Re-arm from the state as it is AFTER that dispatch, so a one-off (or a just-reset timer) doesn't
        // re-arm itself. Only a phone arms anything: [launchAlarmSweep] (which is what called us on every
        // other device) finds the next ring itself, and running the arming path there would log an OS arming
        // that never happened.
        armedAlarm = null
        if (deviceKind == DeviceKind.Phone) {
            val state = vm.state.value
            armNextAlarm(clock.nowMillis(), state.alarms, state.timers)
        }
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
        // Time passing only ADVANCES the plan (banking the records of panels that elapsed) — it never
        // re-plans. The scheduler itself runs on a rule change ([launchRuleChangeReschedule]) or, at most
        // once an hour, on the staleness bound ([launchStaleReschedule]); the rolling horizon only extends
        // the plan's tail ([SchedulerIntent.ExtendSchedule]).
        //
        // This used to fire a full RefreshSchedule on every tick where a rest pose was overdue or a live
        // pause moved the screen-break grid, i.e. continuously while the user was away — churning the whole
        // work plan out of a purely time-driven event. The calendar does not need it: `App.kt` projects the
        // screen-break markers for DISPLAY itself, from the same live-pause overlay, so what is drawn keeps
        // moving with the pause; and the cue sweep keys on the poses' fixed due instants, not on panels.
        vm.dispatch(SchedulerIntent.AdvanceSchedule(now))
        // PRD §17: the Sleep toggle auto-wakes when its scheduled wake instant lapses mid-session — finalize
        // the sleep session as a past "Sleep" panel (reduceSetSleepMode) and stop suppressing the pause cue.
        current.sleepingUntilMillis?.let { until -> if (now >= until) vm.setSleepMode(null) }
        maybeMaterializePastSleep(now)
        // PRD §15: a look-away the app CONDUCTED is written into the past where it happened, by
        // [SchedulerIntent.RecordConductedBreak] at the moment it finishes ([restartLookAway]) — the bars
        // then read it out of the timeline as the rest stretch it is. The tick has nothing to serve.
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
     *
     * **It declares an empty SCREEN, not a silent app.** The one thing it says is "count this device as
     * locked when asking whether anybody is at a screen" — the no-screen periods the calendar draws, the
     * evidence the §9 record bank reads, the `t_p` mode, the account-wide idleness the pause cue is judged
     * on. It deliberately does NOT silence this device: the notifications and the voice cues go on firing,
     * because the machine is still unlocked and the user is still able to see and act on them. That is the
     * case the button exists for — a user who has to leave the screen unlocked for a program to keep running,
     * and who still wants the "task to do now" notification when the plan moves on. A real LOCK is the
     * opposite: it silences this device outright ([deviceUnlocked]), and the break-over message then comes
     * from the server's push instead ([onPauseCueFire], `docs/PAUSE_CUE_DELIVERY.md`).
     */
    fun setUserAway(away: Boolean) {
        if (_userAway.value == away) return
        _userAway.value = away
        scope.launch {
            advanceActiveSession(clock.nowMillis(), effectiveScreenActive(), suspended = false)
        }
    }

    /**
     * PRD §11: the left-menu **Notifications** switch and the `Ctrl+Shift+Alt+N` chord — the same lever from
     * two places, exactly as "I'm away" is.
     *
     * Switching off does both halves of "cancel every notification": [notifyUser] stops handing anything to
     * the OS, and [clearNotifications] withdraws what it has already shown (Android's shade / iOS's
     * Notification Centre keep a notification until it is dismissed; a desktop tray balloon cannot be recalled,
     * and that actual is a documented no-op). The History window's Notifications column is untouched either
     * way, and so are the voice cues — they have their own switch.
     *
     * Switching back **on** posts one notification saying so, and that is deliberate rather than chatty. The
     * chord is struck with another window in front, and its ordinary receipt ([announceShortcutReceived]) is
     * raised *before* this runs — so on the un-mute press that receipt is still muted and swallowed, which
     * would leave the one press whose whole subject is notifications as the only one the user cannot see
     * landing. This is that press's receipt, posted from the far side of the flip where the app may speak
     * again; it doubles as proof the OS channel still works. A same-value call is a no-op, so nothing is
     * announced and nothing is cleared.
     */
    fun setNotificationsEnabled(enabled: Boolean) {
        if (vm.state.value.notificationsEnabled == enabled) return
        vm.dispatch(SchedulerIntent.SetNotificationsEnabled(enabled))
        if (enabled) notifyUser(NOTIFICATIONS_ON_TITLE, NOTIFICATIONS_ON_MESSAGE)
        else runCatching { clearNotifications() }
    }

    /**
     * PRD §15: sample the RAW platform lock signal and clear "I'm away" on a **lock→unlock edge**.
     *
     * Unlocking this device is the user coming back to it — the one unambiguous "I'm back" the app can read
     * without being told — so the button must not be left declaring an absence that has visibly ended, holding
     * the active session finalized and the heartbeat closed for as long as it does. Only that edge clears: an
     * unlock with no lock before it is not a return (nothing said the user left), and a lock while away must
     * obviously leave the flag alone.
     *
     * **This needs no polling.** The edge IS the platform's own event — Windows `WM_WTSSESSION_CHANGE`
     * (`WTS_SESSION_LOCK`/`_UNLOCK`), Android's `ACTION_SCREEN_OFF`/`ACTION_USER_PRESENT` — which already
     * reaches the engine through [onPlatformActivityChanged], the same poke that re-samples presence at a
     * lock/unlock. The active-session beat calls this too, but only as a re-read of a signal it is sampling
     * anyway (`ARCHITECTURE.md` §8): it is a safety net for a missed notification, not the mechanism. A host
     * with no such signal (a non-Windows JVM, a failed native install, iOS) never flips it and the flag simply
     * stays as the user set it — which is the same degradation `isScreenActive` already has there.
     *
     * The read-modify-write is atomic on both flows because the OS notification thread and the beat can call
     * this concurrently; the clear is idempotent, so the worst a race can do is answer one edge twice.
     */
    private fun noteScreenSignal() {
        val signal = screenActive()
        val wasLocked = lastScreenSignal.getAndUpdate { signal } == false
        if (signal && wasLocked && _userAway.compareAndSet(expect = true, update = false)) {
            Diagnostics.log("\"I'm away\" cleared: this device was unlocked")
        }
    }

    // PRD §15: "is anybody WORKING AT this device" — the presence reading. The real platform sensor,
    // overridden to inactive while the debug leap forces it ([setDebugForcedInactive]) or the user declared
    // they are away ([setUserAway]). It answers the active session, the `t_a` presence heartbeat, the
    // no-screen evidence the calendar's layers and the §9 record bank read, and through them the `t_p` mode.
    private fun effectiveScreenActive(): Boolean = !debugForcedInactive && !_userAway.value && screenActive()

    // PRD §15: "may this device SAY anything" — the output reading, and the one thing "I'm away" deliberately
    // does NOT mask. A LOCKED device says nothing: its user cannot see a notification or be spoken to, which
    // is why the break-over cue for a locked device is the server's push and not the app's own sweep
    // (`docs/PAUSE_CUE_DELIVERY.md`). "I'm away" says something else entirely — *nobody is at this screen*,
    // which is a statement about the no-screen periods and about nothing else. The user who presses it is
    // routinely still at the machine with it unlocked (a program is running, they are reading off a second
    // screen), so silencing the app there would take away the very notifications — "task to do now" above all
    // — that they pressed it while still able to act on. The debug leap DOES mask, because it simulates a
    // machine that went to sleep, which is a lock.
    private fun deviceUnlocked(): Boolean = !debugForcedInactive && screenActive()

    // Diagnostics-instrumented seams for every user-audible output: each posted notification and played voice
    // cue lands in the cross-device timeline with the sim instant it fired at, so "this device stayed silent
    // through that break" is answerable from scripts/collect-diagnostics.bat instead of a live repro.
    private fun notifyUser(title: String, message: String) {
        val now = clock.nowMillis()
        // PRD §11: the account's Notifications switch, read HERE and nowhere else — this is the one funnel
        // every notification the app posts goes through (a break's start and end, "task to do now", the
        // wind-down, an alarm, a chord's own receipt), so gating it is what makes "cancel every notification"
        // mean every one of them rather than the handful somebody remembered to guard.
        val muted = !vm.state.value.notificationsEnabled
        Diagnostics.log(
            "notification [$title] ${message.replace('\n', ' ')} " +
                "(sim now=${Diagnostics.formatInstant(now)})" +
                (if (muted) " [suppressed: notifications off]" else ""),
        )
        // Append to the History Manager's local-only Notifications column (capped, non-syncing). Written
        // whether or not the OS is told: the switch silences the interruption, never the record, so the
        // column still answers "what did the app decide to say while I had it muted".
        vm.dispatch(SchedulerIntent.RecordNotification(title, message, now))
        if (!muted) postNotification(title, message)
    }

    private fun speakCue(cue: VoiceCue) {
        Diagnostics.log("voice cue ${cue.name} (sim now=${Diagnostics.formatInstant(clock.nowMillis())})")
        playCue(cue)
    }

    /**
     * PRD §15: announce the END of a look-away break — "resume your work".
     *
     * Posted as a NOTIFICATION as well as spoken. The History window's Notifications column lists what the app
     * posted, and this cue used to be voice-only ([speakCue] writes the Diagnostics timeline, not the log), so
     * the column showed every break starting and none of them ever finishing. The spoken half stays gated on
     * the look-away voice switch ([SchedulerState.lookAwayVoiceEnabled], captured by the caller); the
     * notification does not, exactly as the break's own start doesn't.
     */
    internal fun announceResumeWork(voice: Boolean) {
        notifyUser(RESUME_WORK_TITLE, RESUME_WORK_MESSAGE)
        if (voice) speakCue(VoiceCue.ResumeWork)
    }

    /**
     * PRD §12: a gap in time `[sleepStart, sleepEnd]` — the process was suspended (real device sleep) or a
     * debug leap jumped the clock over it.
     */
    fun reportTimeGap(sleepStart: Long, sleepEnd: Long) {
        vm.dispatch(SchedulerIntent.ReportDeviceSleep(sleepStart, sleepEnd))
        // `side-dev/README.md` § *Progressive Calculation*, direct consequence: *"If the device bearing the
        // running process is put to sleep, then when the program wakes up, the now line does a fast move
        // forward (in epsilon time) in mode 2 to the current date."*
        //
        // Mode 2 is *"the now line must be covered by the period 'no on-screen task'"*, and the line was in it
        // at every instant of the journey — so the whole swept stretch is covered by one, which is noted BEFORE
        // the walk so the walk itself sees it. That is not evidence about a device and does not wait on any: it
        // is what the mode MEANS, which is why the bars stop counting from the last recorded break the moment
        // the app wakes instead of ten minutes later when the OS lock scan lands (the drift the funnel exists
        // to remove).
        noteSweptNoScreen(sleepStart, sleepEnd)
        // scripts/collect-diagnostics.bat: a wake is the event that explains most post-wake anomalies (a break
        // owed at the line, a hole in the records), and nothing logged it at all before. Once per wake.
        Diagnostics.log(
            "device sleep ${(sleepEnd - sleepStart) / 60_000}min " +
                "(${Diagnostics.formatInstant(sleepStart)} → ${Diagnostics.formatInstant(sleepEnd)}): " +
                "now-line swept in mode 2, the stretch covered as no on-screen task",
        )
        sweepNowLineTo(sleepStart, sleepEnd, DynamicPeriods.MODE_AWAY)
    }

    /**
     * `side-dev/README.md` § *$now line$*: **move the line to [toMillis], CONTINUOUSLY** — the one way it ever
     * moves, and the reason nothing in this engine teleports it.
     *
     * *"Every t such that t <= now line are the previous values of the now line variable. This means that the
     * now line moves continuously forward in time."* So a caller asking for a distant position is asking for a
     * JOURNEY, not a landing, and the line is walked there one [SchedulerDomain.sweepStepMillis] at a time,
     * freezing what it passes as it passes it. An ordinary tick is far inside the first step and costs exactly
     * one commit, so this is the same single dispatch it always was where no ground is being covered.
     *
     * [mode] is the mode to hold for the WHOLE journey ([sweepMode]) — mode 2 for a wake from device sleep —
     * or `null` to read the live one at each position, which is what a debug time leap wants (the devices'
     * state is real there; only the clock moved).
     *
     * The journey does **not** re-plan, and that is the README's own answer for it: *"If the current date is
     * beyond the definitive schedule, then it is similar to a case where no CPU were available during this
     * period and the current set of rules, parameterized by now line and now line mode, is used to define the
     * schedule as the now line does its fast move, while no better set of rules was found."* Each step is an
     * ordinary [SchedulerIntent.AdvanceSchedule] — the plan in force writes the past it passes — and the
     * re-plan that follows belongs to the landing, through [requestReschedule] like every other one.
     */
    private fun sweepNowLineTo(fromMillis: Long, toMillis: Long, mode: Int? = null) {
        if (toMillis <= fromMillis) return
        val previous = sweepMode
        sweepMode = mode
        var steps = 0
        var widened = false
        try {
            var cursor = fromMillis
            while (cursor < toMillis) {
                // The rules at the line, so a journey crossing a task-tree keyframe steps by the minimum in
                // force there rather than the one it set out with (ADR 0008).
                val step = SchedulerDomain.sweepStepMillis(vm.state.value, cursor) ?: (toMillis - cursor)
                val budget = (MAX_SWEEP_STEPS - steps).coerceAtLeast(1)
                val even = (toMillis - cursor + budget - 1) / budget
                if (even > step) widened = true
                cursor = minOf(toMillis, cursor + maxOf(step, even, 1L))
                advanceTo(cursor)
                steps++
            }
        } finally {
            sweepMode = previous
        }
        if (widened) {
            Diagnostics.log(
                "now-line swept ${(toMillis - fromMillis) / 60_000}min in $steps step(s) " +
                    "(stride widened past the minimum execution time to stay inside $MAX_SWEEP_STEPS)",
            )
        }
    }

    /**
     * `side-dev/README.md` § *$now line$ 2 modes*, **mode 2's own rule made a fact about the timeline**: the
     * line was covered by "no on-screen task" at every instant of `[fromMillis, toMillis]`, so that stretch is
     * covered by one.
     *
     * It joins the OS scan's answer in [_noScreenEvidence] — the funnel every placement already reads
     * ([SchedulerDomain.observedNoScreenPeriods]) — rather than becoming a panel: it is derived, local and
     * recomputed, exactly like the scan beside it, and CLAUDE.md's rule is that a period is never
     * manufactured from an observation. Pruned to the same [NO_SCREEN_EVIDENCE_LOOKBACK_MILLIS] window the
     * scan answers over, so the list cannot grow without bound across a long-running session.
     */
    private fun noteSweptNoScreen(fromMillis: Long, toMillis: Long) {
        if (toMillis <= fromMillis) return
        sweptNoScreen = sweptNoScreen + TaskTimeRange(fromMillis, toMillis)
        publishNoScreenEvidence()
    }

    /** The union of the two halves, pruned to the window the scan answers over — the only writer of the flow. */
    private fun publishNoScreenEvidence() {
        val floor = clock.nowMillis() - NO_SCREEN_EVIDENCE_LOOKBACK_MILLIS
        sweptNoScreen = sweptNoScreen.filter { it.endEpochMillis > floor }
        val merged =
            SchedulerDomain.mergeOccupied(
                scannedNoScreen + sweptNoScreen.map { TaskTimeRange(maxOf(it.startEpochMillis, floor), it.endEpochMillis) },
            )
        if (merged != _noScreenEvidence.value) _noScreenEvidence.value = merged
    }

    // PRD §9: the advance tick + PRD §12 device-sleep detection (real-time gap → inject a hole).
    //
    // Two cadences, so an accelerated now-line GLIDES instead of jumping once per tick (the x300 "jumps"
    // anomaly, worst on the phone where the production 30 s tick made each jump ~2.5 h of sim time):
    //  • the engine clock state ([_nowMillis]) advances on the engine cadence;
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
                // The line is SWEPT from the coarse tick boundary to the wake, in mode 2 ([reportTimeGap]);
                // separately, the OS sleep/wake log is queried off-thread for the EXACT pause interval(s) to
                // record and sync (PRD §15) — so a ping that was due mid-pause but never sent (the machine was
                // down) becomes an exact gap other devices can pull. The journey's own cover is the tick
                // boundaries, which strictly CONTAIN the suspension, so it can only over-claim the ~30 s before
                // the machine went down — the same span `ReportDeviceSleep` already treats as the hole.
                reportTimeGap(lastClockTick, now)
                recordExactSleepGaps(lastClockTick, now)
                lastScheduleAdvance = now
            } else {
                // Schedule: bank records / re-derive panels only on the coarser sim-time step — and reach `now`
                // by SWEEPING, so a clock that has covered ground (a debug leap) is walked rather than jumped.
                // At 1x that is one commit, exactly as it was: a tick is far inside one minimum execution time.
                // It runs BEFORE the glide below, so the display line is never wound back to a swept position.
                if (now - lastScheduleAdvance >= SCHEDULE_ADVANCE_STEP_MILLIS) {
                    sweepNowLineTo(lastScheduleAdvance, now)
                    lastScheduleAdvance = now
                }
                // Display: always glide the now-line to the current clock instant.
                _nowMillis.value = now
            }
            lastRealTick = realNow
            lastClockTick = now
            tickDelay(if (timeAccelerated()) ADVANCE_DISPLAY_MILLIS_ACCEL else ADVANCE_TICK_MILLIS_PROD)
        }
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
            // Re-read the raw lock signal first: an unlock clears "I'm away" (see [noteScreenSignal]), and the
            // sample below must already be answering the flag that unlock left behind.
            noteScreenSignal()
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

    // PRD §15: publish what the server needs, on TWO cadences (migrations 20260726000000 + 20260728000000).
    //
    //  * Presence — `{ account, device, time of upsert }` plus the account's re-armed claim row, every `t_a`
    //    from the moment this device is signed in and unlocked, cleared otherwise. Driven from every
    //    active-session beat so a walk-away is reflected within a beat; [DeviceHeartbeatPublisher] then
    //    re-upserts the row on its own ~10 s cadence.
    //  * The two screen breaks' due instants — written only when they CHANGE. They used to ride the beat, which
    //    left the server up to one active-session beat (30 s in production) behind a schedule edit; if every
    //    device went dark inside that window the cue was judged on the pre-edit break, and the clean screen-off
    //    path hit that by construction (it calls e1 without a final beat). Publishing on change closes it.
    //
    // The breaks are published ONLY while active, and deliberately never cleared: the last pair published while
    // active is exactly the state the user walked away in, which is what the server's overdue gate is judged on.
    private fun updatePresence() {
        val gateway = pauseCue ?: return
        val presence = realtimePresence ?: return
        val active = effectiveScreenActive() && gateway.signedIn
        presence.setPresence(if (active) PresenceState(gateway.deviceId) else null)
        if (!active) return

        // Ask the RECURRENCE BARS where each pose next falls, over the same environment the fill and the cue
        // sweep are handed — never the stored `state.panels`, whose forward projection is a frozen snapshot
        // (the short fast-break now-line break vanished from it the moment its window elapsed and was replaced
        // by a far-future sleep-anchored occurrence, so the cue aimed hours out; see
        // RestPosePresenceWindowTest).
        val now = clock.nowMillis()
        val st = vm.state.value
        val dues = restPoseDueMillisByKey(
            screenBreaks = st.screenBreaks,
            nowMillis = now,
            basePeriods = dynamicPeriodBaseNow(st),
            tasks = SchedulerDomain.planTasksOf(st, now),
        )
        presence.setNextBreak(
            NextBreakState(
                fiveMinDueMillis = dues[SchedulerDomain.FIVE_MIN_BREAK_KEY]?.let { publishableDueMillis(it, now) },
                fifteenMinDueMillis =
                    dues[SchedulerDomain.FIFTEEN_MIN_BREAK_KEY]?.let { publishableDueMillis(it, now) },
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
     * rows plus the peers' rows the last reconcile pulled, so a pause is account-wide ("no device
     * was active") with reconcile-bounded staleness. A purely LOCAL derivation (no server RPC; the derived pauses
     * are never stored). The freshly derived pauses reach the §15 recurrence bars as the rest stretches they
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
        // `side-dev/README.md`: a pause is a REST STRETCH the recurrence bars read straight off the
        // timeline, so a derive has nothing to fold into a break's configuration — the periods it reveals
        // bar what follows them by the ordinary rule, wherever they are asked about.
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
    // reconcile pulled in, so the bands are account-wide again with reconcile-bounded staleness (a
    // pause = no device active). Also refreshes [activeSessions] from the store, since this runs at exactly
    // the moments the stored set can change structurally (startup, post-reconcile, leap end).
    private fun localDerivedPauses(since: Long, until: Long): List<TaskTimeRange> {
        val records = activeSessionStore?.loadActiveSessions() ?: return emptyList()
        _activeSessions.value = records
        return SchedulerDomain.derivePauses(records.map { TaskTimeRange(it.startMillis, it.endMillis) }, since, until)
    }

    /**
     * PRD §9 calculation event #2 — **a CHANGE re-plans the schedule.** (The only other thing that does is
     * the hourly staleness bound, [launchStaleReschedule]; time passing still re-plans nothing.)
     *
     * The scheduler runs when someone CHANGED a rule it depends on: this user editing the
     * task tree / priorities / minimum times, pinning or moving a calendar block, editing the sleep or
     * screen-break configuration, flipping the §7 switch — or a **remote** user doing any of that, which
     * reaches this device as a pulled snapshot written straight into `vm.state` (`applyRemoteSnapshot`) and
     * so lands in this same flow. [SchedulerDomain.schedulingSignature] is exactly that input set; time
     * passing is deliberately outside it, and so are the panels the fill itself regenerates (else every fill
     * would trigger the next one).
     *
     * Debounced by [RESCHEDULE_DEBOUNCE_MILLIS] via `collectLatest`, so a burst — typing a task title
     * character by character, dragging a panel, a sync pull landing on top of a local edit — costs one fill,
     * not one per keystroke.
     */
    private fun launchRuleChangeReschedule() = scope.launch {
        vm.state.map { SchedulerDomain.schedulingSignature(it) }.distinctUntilChanged().collectLatest {
            delay(RESCHEDULE_DEBOUNCE_MILLIS)
            requestReschedule()
        }
    }

    /**
     * The single way this engine asks for a **re-plan**: dispatch it, or (§7 switch off) defer it — and in
     * either case re-arm the staleness timer [launchStaleReschedule] watches. Every re-plan path goes
     * through here so "the last scheduling" means one thing, whichever event triggered it.
     */
    private fun requestReschedule(now: Long = clock.nowMillis()) {
        lastRescheduleMillis = now
        if (vm.state.value.automaticSchedule) vm.dispatch(SchedulerIntent.RefreshSchedule(now))
        else pendingReschedule = true
    }

    /**
     * `side-dev/README.md` § *3 Dynamic Restrictive Period*: **the environment the three are placed over, as
     * this engine sees it right now** — the same three parts the reducer's fills are given
     * ([SchedulerDomain.dynamicPeriodBase]), so the instant the app ANNOUNCES a break at and the instant the
     * fill places one at are answers to one question.
     *
     * Asked without the live pause and the observed evidence — which is what these call sites did until
     * 2026-08-29 — the bars walk a timeline in which the user has been at the screen without interruption
     * since the placement origin, and a pose falls due inside the hour a real pause bars it in.
     */
    private fun dynamicPeriodBaseNow(state: SchedulerState) =
        SchedulerDomain.dynamicPeriodBase(
            panels = state.panels,
            liveRest =
                SchedulerDomain.liveRestGap(_inactiveSince.value, _activeSince.value, clock.nowMillis()),
            noScreenEvidence = _noScreenEvidence.value,
        )

    /**
     * `side-dev/README.md` § *$t_p$ 2 modes*: **the mode the now-line is in, right now** — the one reading,
     * shared by the reducer's fills ([SchedulerReducer.tpMode]) and by the display (`App.kt` asks
     * [SchedulerDomain.anyDeviceUnlockedAt] over the same three flows).
     */
    fun tpModeNow(nowMillis: Long = clock.nowMillis()): Int =
        // A JOURNEY is walked in the mode the journey is in, not in the mode the arrival is in ([sweepMode]).
        // Without this the wake from a device sleep would sweep the whole night in mode 1 — the machine is
        // unlocked again by the time anything asks — which is the one mode the README says it is not.
        sweepMode
            ?: SchedulerDomain.tpMode(
                SchedulerDomain.anyDeviceUnlockedAt(
                    _inactivityGaps.value, _inactiveSince.value, _activeSince.value, nowMillis,
                ),
            )

    /**
     * A **mode flip re-plans**, because the mode is part of the environment the three dynamic periods are
     * placed against — where they sit relative to the line is a function of it.
     *
     * This is not "time passing re-plans" (CLAUDE.md's rule): the flip is an EDGE the platform announces — a
     * lock, an unlock, the "I'm away" button — reaching this engine through the same session advance that
     * moves [_inactiveSince] / [_activeSince]. Nothing polls, and a mode that does not change costs nothing.
     * It goes through [requestReschedule] like every other re-plan rather than dispatching its own
     * `RefreshSchedule`, so the staleness bound is re-armed with it. It cannot live in
     * [SchedulerDomain.schedulingSignature] instead: the mode is not in `SchedulerState` — it is a fact about
     * the devices, not about the account's data — which is also why it is not synced.
     */
    private fun launchTpModeReschedule() = scope.launch {
        combine(_inactivityGaps, _inactiveSince, _activeSince) { gaps, inactive, active ->
            SchedulerDomain.tpMode(
                SchedulerDomain.anyDeviceUnlockedAt(gaps, inactive, active, clock.nowMillis()),
            )
        }
            .distinctUntilChanged()
            // The first emission is the mode the app started in, which the start-up fill already used.
            .drop(1)
            .collect {
                Diagnostics.log("t_p mode is now $it (${if (it == DynamicPeriods.MODE_AT_SCREEN) "a device is unlocked" else "no device unlocked"})")
                requestReschedule()
            }
    }

    /**
     * PRD §9 calculation event #3 — the plan's **staleness bound**: re-plan when the last one was asked for
     * [SCHEDULE_STALENESS_MILLIS] ago or more.
     *
     * This is not a re-plan tick, and it is not a second reading of "time passing re-plans": the timer is
     * reset by every re-plan ([requestReschedule]), so a session where the user is actually editing never
     * reaches it, and an untouched one costs exactly one fill per hour. What it buys is that a plan is never
     * served indefinitely against a now-line that has since moved arbitrarily far — the inputs the signature
     * cannot see (the banked records the advance has been writing all along, this device's live rest gap, a
     * horizon that rolled) get folded in at a bounded cadence instead of waiting for the user's next edit.
     *
     * Polled rather than slept-to-the-instant, like [launchHorizonReschedule], so a clock leap or a speed
     * change is noticed within one poll instead of after a full hour of real time.
     */
    /**
     * PRD §9/§12: read the stretches the devices say nobody was at a screen for over `[since, until]`.
     *
     * Only THIS device can be asked — there is no channel carrying a peer's lock history — so the other kind
     * passes `null`, which [SchedulerDomain.observedNoScreenRegions] reads as assumed-LOCKED throughout, the
     * same default the calendar layers use. On a phone-less account that makes the intersection exactly this
     * computer's own locked spans.
     */
    private suspend fun readNoScreenEvidence(since: Long, until: Long): List<TaskTimeRange> {
        val own = SchedulerDomain.layerForDeviceKind(currentDeviceKind())
        // A query that FAILED is not evidence. `null` means "assumed locked throughout" to
        // [SchedulerDomain.observedNoScreenRegions] — the right default for the CALENDAR, where hatching a
        // stretch nobody can vouch for is honest — but the exact opposite of what the record bank needs: one
        // PowerShell hiccup or a 20 s timeout would blanket the whole window as no-screen, suppress every
        // record and grey out the day. So the OWN scan must SUCCEED to say anything at all; when it does not,
        // the engine reports no evidence and banking behaves exactly as it did before this channel existed.
        //
        // The PEER's null keeps its assumed-locked meaning: that is the spec (a device nobody can ask was not
        // in use), and it is what makes a phone-less account turn on the computer's own history. The asymmetry
        // is deliberate — silence about a device we CANNOT reach is a rule, silence from the one we CAN reach
        // is a failure.
        //
        // ADR 0009 / ADR 0002: the read spawns a PowerShell process and waits up to 20 s for it, so it must
        // never run on the caller's dispatcher — blocking the engine scope stalls the advance tick and every
        // cue/boundary sweep behind it. `App.kt` hands its own layer scan off the same way.
        val locked =
            withContext(Dispatchers.Default) {
                runCatching { deviceLockedIntervals(since, until) }
                    .getOrNull()
                    ?.map { TaskTimeRange(it.startMillis, it.endMillis) }
            } ?: return emptyList()
        val computer = if (own == SchedulerDomain.ActivityLayer.NoComputerUnlocked) locked else null
        val phone = if (own == SchedulerDomain.ActivityLayer.NoPhoneUnlocked) locked else null
        return SchedulerDomain.observedNoScreenRegions(computer, phone, since, until)
    }

    /**
     * PRD §9/§12 retroactive: apply the no-screen rule ONCE, at start-up, to work banked before the rule could
     * see the OS lock history at all.
     *
     * Until [SchedulerReducer.noScreenEvidence] existed the rule keyed on hand-drawn "No screen" panels alone,
     * and the only thing that creates one is the §8 contextual-menu action — so on an account where the user
     * had never drawn one it never fired, and the app banked records straight through a machine its own OS
     * reported asleep (account 3: 43 h across 206 records). Going forward the scan prevents that; this repairs
     * what is already stored.
     *
     * Runs over the DISPLAYED past ([SchedulerDomain.SCHEDULE_HORIZON_MILLIS] back, the same floor the calendar
     * uses) and is naturally idempotent — once the covered records are gone there is nothing left to subtract,
     * so a later launch reduces to a no-op and returns the same state instance.
     */
    private fun launchRetroactiveNoScreenStrip() = scope.launch {
        val now = clock.nowMillis()
        val since = now - SchedulerDomain.SCHEDULE_HORIZON_MILLIS
        val observed = readNoScreenEvidence(since, now)
        if (observed.isEmpty()) return@launch
        val before = vm.state.value
        vm.dispatch(SchedulerIntent.StripNoScreenRecords(observed))
        val after = vm.state.value
        if (after === before) return@launch
        // scripts/collect-diagnostics.bat: this REMOVES recorded work, so it must be visible in the timeline
        // rather than silently changing every priority percentage.
        fun recorded(state: SchedulerState): Long =
            state.tasks.values.sumOf { task -> task.record.sumOf { it.endEpochMillis - it.startEpochMillis } }
        Diagnostics.log(
            "retroactive no-screen strip: removed ${(recorded(before) - recorded(after)) / 60_000}min of " +
                "on-screen records over ${observed.size} observed no-screen span(s)",
        )
    }

    /**
     * PRD §9/§12: keep [_noScreenEvidence] fresh — the OS lock/standby history of every device kind that can be
     * asked, intersected into the stretches nobody was at a screen for.
     *
     * Only THIS device can be asked (there is no channel carrying a peer's lock history), so the other kind
     * passes `null`, which [SchedulerDomain.observedNoScreenRegions] reads as assumed-LOCKED throughout — the
     * same default the calendar layers use. On a phone-less account that makes the intersection exactly this
     * computer's own locked spans, which is the case that surfaced the bug.
     *
     * ADR 0009: the read costs a process launch, so it runs off the tick entirely — once per
     * [NO_SCREEN_EVIDENCE_REFRESH_MILLIS], over a bounded [NO_SCREEN_EVIDENCE_LOOKBACK_MILLIS] window, on the
     * engine's own scope. A scan that fails leaves the previous answer standing rather than reverting to
     * "nothing observed", so a transient query failure cannot silently re-enable banking over a sleeping
     * machine.
     */
    private fun launchNoScreenEvidenceScan() = scope.launch {
        while (true) {
            val now = clock.nowMillis()
            val since = now - NO_SCREEN_EVIDENCE_LOOKBACK_MILLIS
            val observed = readNoScreenEvidence(since, now)
            if (observed != scannedNoScreen) {
                scannedNoScreen = observed
                // Published as the union with what the line SWEPT in mode 2 — a scan that cannot see a
                // stretch (an unsupported host, a query that came back empty) must not un-say the mode.
                publishNoScreenEvidence()
                // scripts/collect-diagnostics.bat: this is what the record bank actually applied, so an
                // anomaly in a banked past panel is an anomaly in THIS answer. Logged only on change.
                Diagnostics.log(
                    "no-screen evidence: ${observed.size} span(s), " +
                        "${observed.sumOf { it.endEpochMillis - it.startEpochMillis } / 60_000}min " +
                        "over the last ${NO_SCREEN_EVIDENCE_LOOKBACK_MILLIS / 3_600_000}h",
                )
            }
            tickDelay(NO_SCREEN_EVIDENCE_REFRESH_MILLIS)
        }
    }

    private fun launchStaleReschedule() = scope.launch {
        fun pollInterval(): Long = if (timeAccelerated()) ADVANCE_TICK_MILLIS_ACCEL else ADVANCE_TICK_MILLIS_PROD
        // Nothing has re-planned yet at start-up; the rule-change watcher's first emission is about to, so
        // start the hour from here rather than firing an immediate duplicate fill.
        lastRescheduleMillis = clock.nowMillis()
        while (true) {
            val last = lastRescheduleMillis ?: clock.nowMillis()
            if (clock.nowMillis() - last >= SCHEDULE_STALENESS_MILLIS) requestReschedule()
            else tickDelay(pollInterval())
        }
    }

    /**
     * The task-tree timeline's re-plan: refill when the blend between two dated task trees has moved a
     * whole step ([SchedulerDomain.taskTreeBlendStep]).
     *
     * With [launchStaleReschedule] this is one of the two deliberate exceptions to "time passing must never
     * re-plan" — and the only one where the plan's CONTENT is a function of time. It exists because the
     * user's timeline makes the plan genuinely a function of time: between two dated trees the absolute
     * priorities transform continuously, so a plan computed once would simply be wrong from the next
     * instant on. What the rule is really protecting against — a *continuous* input churning the whole plan
     * on every tick — is handled by quantizing rather than by refusing to fire: the blend is cut into
     * [SchedulerDomain.TASK_TREE_BLEND_STEPS] steps, so a transition costs that many fills in total however
     * long it lasts (a two-month one re-plans roughly every 14 hours), and the poll below only *samples*
     * that step — it dispatches nothing while the cursor stays inside one.
     *
     * With no dated tree the step is a constant, so an account that never opens the timeline window never
     * reaches the dispatch at all. The first sample only primes `last`, so starting up mid-transition does
     * not itself force a fill; the rule-change watcher has just run one anyway.
     */
    private fun launchTaskTreeBlendReschedule() = scope.launch {
        var last: Int? = null
        while (true) {
            val step = SchedulerDomain.taskTreeBlendStep(vm.state.value, clock.nowMillis())
            if (last != null && step != last) requestReschedule()
            last = step
            tickDelay(TASK_TREE_BLEND_POLL_MILLIS)
        }
    }

    // PRD §9 calculation event #1 (calendar change / the goal stepping forward): refill as `now` reaches
    // [SchedulerDomain.horizonRefillDueMillis] — the point where the materialized schedule has fallen a
    // whole [SchedulerDomain.HORIZON_REFILL_MARGIN_MILLIS] short of the horizon IN FORCE ($t_goal$,
    // [scheduleHorizonEndMillis] — not a fixed 168h). The margin (and the rate floor below) exist
    // because this loop feeds itself: the refill rewrites the very `panels` it watches. The goal being an
    // ABSOLUTE staircase, this now fires about once a week — when the week rolls over — rather than daily.
    private fun launchHorizonReschedule() = scope.launch {
        // Last instant a refill was dispatched, kept OUTSIDE `collectLatest` so it survives the restart the
        // refill itself causes — it is what floors the refill rate (below).
        var lastRefillMillis: Long? = null
        // Poll faster while accelerated (re-checked each pass — the user changes speed at runtime) so the
        // refill isn't reached late when `now` races ahead; the phone keys off the clock's actual speed too.
        fun pollInterval(): Long = if (timeAccelerated()) ADVANCE_TICK_MILLIS_ACCEL else ADVANCE_TICK_MILLIS_PROD
        vm.state.map { it.panels }.distinctUntilChanged().collectLatest { panels ->
            // Re-evaluated every pass rather than pinned once: the horizon is an ABSOLUTE instant
            // ($t_goal$), so its remaining span shrinks as `now` advances and the due instant
            // moves — and a target computed once would fire early, refill to no effect, and (since `panels`
            // would not change) park this collector for good.
            fun refillDue(): Boolean {
                val now = clock.nowMillis()
                return SchedulerDomain.horizonRefillDueMillis(panels, now, scheduleHorizonEndMillis(now)) <= now
            }
            while (!refillDue()) {
                tickDelay(pollInterval())
            }
            // Floor the refill RATE as well as its due instant. The due instant alone is not enough: when a
            // refill cannot close the gap it was triggered by (a no-screen span no off-screen task can fill,
            // an exhausted task list), the recomputed target stays in the past, the refill rewrites `panels`,
            // this collector restarts on that very change — and the cycle repeats with no delay at all. That
            // ran the fill hundreds of times a second on the UI thread, and since a `Window` is only shown
            // once it presents a frame, the app came up as a tray icon with no window at all (2026-07-28).
            while (lastRefillMillis?.let { clock.nowMillis() - it < pollInterval() } == true) {
                tickDelay(pollInterval())
            }
            lastRefillMillis = clock.nowMillis()
            // An EXTENSION, not a re-plan: the horizon rolling forward is not a rule change, so the plan
            // already on screen is kept and only its tail is materialized.
            if (vm.state.value.automaticSchedule) vm.dispatch(SchedulerIntent.ExtendSchedule(clock.nowMillis()))
            else pendingReschedule = true
        }
    }

    // PRD §9 "schedule the whole span displayed": the user navigating the calendar to a further-out week
    // GROWS $t_goal$, so the plan must extend to cover it — and promptly, not at the next 30-s poll of
    // [launchHorizonReschedule]. Navigating back dispatches nothing: the goal is a MAX, so a nearer week
    // does not shorten it at all, and even a genuinely smaller horizon is already covered (the extra days
    // are simply left in `panels` until the next fill that genuinely reaches past them).
    //
    // This cannot self-retrigger the way the rolling-horizon loop can: a refill never writes the calendar
    // horizon, so the flow only emits on a user navigation.
    private fun launchCalendarHorizonReschedule() = scope.launch {
        // StateFlow already conflates and de-duplicates, so re-publishing the same week emits nothing.
        _calendarHorizonEndMillis.collect { end ->
            val now = clock.nowMillis()
            val horizon = SchedulerDomain.scheduleHorizonEndMillis(now, end, tz)
            if (SchedulerDomain.horizonRefillDueMillis(vm.state.value.panels, now, horizon) > now) return@collect
            // Navigating the calendar shows more days; it does not change any scheduling rule, so this too
            // extends the plan's tail rather than re-planning it.
            if (vm.state.value.automaticSchedule) vm.dispatch(SchedulerIntent.ExtendSchedule(now))
            else pendingReschedule = true
        }
    }

    // PRD §7: fire the single deferred reschedule when the switch is turned on.
    private fun launchPendingRescheduleOnSwitch() = scope.launch {
        vm.state.map { it.automaticSchedule }.distinctUntilChanged().collectLatest { on ->
            if (on && pendingReschedule) {
                pendingReschedule = false
                val now = clock.nowMillis()
                lastRescheduleMillis = now
                vm.dispatch(SchedulerIntent.RefreshSchedule(now))
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
    // from the mathematical [SchedulerDomain.screenBreakCueOccurrencesBetween] reconstruction (NOT `state.panels`,
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

                    // PRD §17: the wind-down cue fires where the "before bed" PERIOD starts — the period the
                    // fill laid, not a second reading of the sleep schedule. One instant, so the notification
                    // and the band on the calendar can never say two different things.
                    val windDownInstants = st.panels
                        .filter { it.restrictiveKind == PeriodKinds.BEFORE_BED }
                        .map { it.startEpochMillis }
                    // `side-dev/README.md`: the cue's boundaries are the STARTS of the placed dynamic
                    // periods, so the sweep has to be handed the same environment the fill was — the standing
                    // restrictive periods (the user's own and the §17 sleep windows, both already materialized
                    // in `st.panels`) and the tasks. Asked without them the bars would answer a different
                    // timeline, and the app would announce a break at an instant the calendar does not draw
                    // one at, which is the very drift this change exists to remove.
                    val crossings = SchedulerDomain.cueCrossings(
                        screenBreaks = st.screenBreaks,
                        windDownInstants = windDownInstants,
                        automaticSchedule = st.automaticSchedule,
                        alreadyNotifiedPoseDues = sidePoseNotifiedDue,
                        fromMillis = scanFloor,
                        toMillis = simNow,
                        basePeriods = dynamicPeriodBaseNow(st),
                        tasks = SchedulerDomain.planTasksOf(st, simNow),
                        // ...and the mode, because half that reading is the AT-LINE run: the 20 s look-away
                        // is never dragged, so its cue keys on where it really falls, and where a POSE the
                        // line is dragging falls is what decides that.
                        mode = tpModeNow(simNow),
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
                                // A LOCKED device says nothing (PRD §15): nobody can read it. It is
                                // deliberately not de-duped here — [lastNotifiedTaskId] is left untouched, so
                                // the task the user comes back to is announced the moment they unlock,
                                // instead of being lost to a level that had already moved on. "I'm away" is
                                // NOT this: it leaves the screen unlocked and this cue is the main thing it
                                // must not take away ([deviceUnlocked]).
                                if (deviceUnlocked()) {
                                    lastNotifiedTaskId = currentTaskId
                                    notifyUser("Task to do now", message)
                                } else {
                                    Diagnostics.log(
                                        "task switch notification suppressed: device locked (sim now=" +
                                            "${Diagnostics.formatInstant(simNow)})",
                                    )
                                }
                            }
                        }
                    }

                    for (crossing in crossings) {
                        when (crossing.kind) {
                            SchedulerDomain.CueKind.LookAwayStart -> {
                                val start = crossing.instant
                                if (start in announcedStarts) continue
                                // A manual "Look away now" is running in this occurrence's place. Its anchor
                                // only moves when it finishes ([restartLookAway]), so this due is still a
                                // crossable boundary — swallow it rather than announce a second break over the
                                // one the user is already taking.
                                if (manualLookAwayJob?.isActive == true) {
                                    announcedStarts = announcedStarts + start
                                    Diagnostics.log(
                                        "look-away start ${Diagnostics.formatInstant(start)} superseded: the " +
                                            "manual 'Look away now' break is running in its place",
                                    )
                                    continue
                                }
                                val end = crossing.endInstant
                                val title = crossing.title
                                // Decide the start's fate now (reads are synchronous and stable across this
                                // sweep): a crossing older than the real-age budget was slept through, and a
                                // LOCKED device says nothing at all — its user cannot hear it and is already
                                // away from the screen the break is about. The gate is [deviceUnlocked], not
                                // the presence reading: "I'm away" leaves the screen unlocked, and a user who
                                // declared it is still there to be told to look away.
                                val lateness = cueSweep.realLatenessMillis(start)
                                val stale = lateness > LOOK_AWAY_START_FRESH_MILLIS
                                val unlocked = deviceUnlocked()
                                val startFires = !stale && unlocked
                                firings += Firing(start, 1) {
                                    announcedStarts = announcedStarts + start
                                    when {
                                        stale -> Diagnostics.log(
                                            "look-away start ${Diagnostics.formatInstant(start)} swallowed: " +
                                                "crossed ~$lateness ms (real) ago — process was suspended or engine " +
                                                "just started (budget $LOOK_AWAY_START_FRESH_MILLIS ms, speed ${speed}x)",
                                        )
                                        !unlocked -> Diagnostics.log(
                                            "look-away start ${Diagnostics.formatInstant(start)} suppressed: " +
                                                "device locked at crossing (user already away from the screen; " +
                                                "sim now=${Diagnostics.formatInstant(simNow)})",
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
                                        if (cueSweep.realLatenessMillis(end) <= LOOK_AWAY_START_FRESH_MILLIS &&
                                            deviceUnlocked()
                                        ) {
                                            announceResumeWork(voice)
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
                                    // A LOCKED device says nothing (PRD §15), and — unlike the look-away
                                    // above — the due is NOT marked announced: a pose stays due until a rest
                                    // discharges it, so the break is announced on the next sweep after the
                                    // user unlocks, which is the first moment there is anybody to tell. "I'm
                                    // away" does not suppress it: that screen is unlocked and its user asked
                                    // for exactly these.
                                    if (!deviceUnlocked()) {
                                        Diagnostics.log(
                                            "rest-pose due ${Diagnostics.formatInstant(due)} ($title) " +
                                                "suppressed: device locked (still due when it unlocks)",
                                        )
                                    } else {
                                        sidePoseNotifiedDue = sidePoseNotifiedDue + (title to due)
                                        notifyUser("Screen break", title)
                                    }
                                }
                            }
                            SchedulerDomain.CueKind.WindDown -> {
                                val wd = crossing.instant
                                if (wd in announcedWindDowns) continue
                                firings += Firing(wd, 4) {
                                    announcedWindDowns = announcedWindDowns + wd
                                    // Crossed once, so it is marked either way — a locked device says
                                    // nothing (PRD §15) and "stop work" an hour late is not worth saying.
                                    // "I'm away" is not a lock: it still hears this.
                                    if (cueSweep.realLatenessMillis(wd) <= LOOK_AWAY_START_FRESH_MILLIS &&
                                        deviceUnlocked()
                                    ) {
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
                                !deviceUnlocked() -> Diagnostics.log(
                                    "look-away end ${Diagnostics.formatInstant(end)} resume cue suppressed: " +
                                        "device locked at crossing",
                                )
                                else -> announceResumeWork(voice)
                            }
                        }
                    }

                    // Fire everything in true boundary order (CLAUDE.md "in order").
                    firings.sortedWith(compareBy({ it.instant }, { it.tie })).forEach { it.run() }

                    // Keep the pose de-dupe map bounded: retain only still-existing rest-pose titles.
                    val restTitles = st.screenBreaks.filter { it.restBreak }.map { it.title }.toSet()
                    sidePoseNotifiedDue = sidePoseNotifiedDue.filterKeys { it in restTitles }

                    // Self-delay to the next boundary across every cue kind, so a cue fires at its instant and
                    // not up to a tick late (the outer collectLatest also re-keys each tick). EVERY screen
                    // break's next boundary is the START its own cue keys on — the SAME derivation the sweep
                    // above announces from ([SchedulerDomain.screenBreakCueOccurrencesBetween]: a pose's
                    // undragged due, a look-away's at-line placement), or the sweep would sleep past the very
                    // instant it is waiting for. The pose starts are gated on the §7 switch; the look-away's
                    // cue is not (it has never been).
                    val nextEnd = pendingEnds.filter { it > simNow }.minOrNull()
                    val nextWind = windDownInstants.filter { it > simNow && it !in announcedWindDowns }.minOrNull()
                    // `side-dev/README.md`: the next boundary is the START of the next dynamic period, read
                    // exactly as the sweep reads it. Read off an anchor instead, this went looking for
                    // `lastRest + interval`, which the bars no longer put anything at: the sweep found no next
                    // boundary at all and stopped.
                    val eligible = st.screenBreaks.filter { st.automaticSchedule || !it.restBreak }
                    val nextBreak =
                        SchedulerDomain.screenBreakCueOccurrencesBetween(
                            screenBreaks = eligible,
                            fromMillis = simNow,
                            toMillis = simNow + SchedulerDomain.NEXT_BREAK_SEARCH_MILLIS,
                            nowMillis = simNow,
                            basePeriods = dynamicPeriodBaseNow(st),
                            tasks = SchedulerDomain.planTasksOf(st, simNow),
                            mode = tpModeNow(simNow),
                        )
                            .map { it.startEpochMillis }
                            .filter { it > simNow && it !in announcedStarts }
                            .minOrNull()
                    val next = listOfNotNull(nextBreak, nextEnd, nextWind).minOrNull() ?: break
                    if (speed <= 0.0) break
                    delay(((next - simNow).toDouble() / speed).toLong().coerceAtLeast(1L))
                }
            }
    }

    /**
     * PRD §15 (20s look-away) manual redo: re-run the 20s pause now, superseding any look-away cue still
     * sounding or pending. Mirrors the old `restartLookAway` in App.kt (the cue scope is now [scope]).
     *
     * **The period is written when the break ENDS, not here** ([SchedulerIntent.RecordConductedBreak]). The
     * look-away is the one break the app conducts, so a press that writes at once would put a break into the
     * past before it happened. Recording only on completion is what makes both §15 rules true: a run that did
     * not finish (this press superseding the previous one, the app stopping) leaves no trace at all, and the
     * one that did finish stays drawn exactly where it happened.
     *
     * While it runs, the automatic occurrence it stands in for must not announce itself as well — the placed
     * period is still a crossable boundary — so [launchCueSweep] swallows look-away starts for as long as
     * [manualLookAwayJob] is active.
     */
    fun restartLookAway() {
        val st = vm.state.value
        val lookAway = st.screenBreaks.firstOrNull { !it.restBreak } ?: return
        stopSpeaking()
        pendingEnds = emptySet()
        manualLookAwayJob?.cancel()
        val voice = st.lookAwayVoiceEnabled
        manualLookAwayJob = scope.launch {
            notifyUser("Screen break", lookAway.title)
            if (voice) speakCue(VoiceCue.LookAway)
            // The break is the full duration counted from when the user was TOLD, so `resumeAt` is read after
            // the cue, and it is the end this occurrence is recorded at if it gets there.
            val resumeAt = clock.nowMillis() + lookAway.durationMillis
            while (clock.nowMillis() < resumeAt) {
                val speed = (clock as? SimAppClock)?.speed ?: 1.0
                val remainingReal =
                    if (speed > 0.0) ((resumeAt - clock.nowMillis()).toDouble() / speed).toLong() else Long.MAX_VALUE
                delay(remainingReal.coerceIn(1L, LOOK_AWAY_RESUME_POLL_MILLIS))
            }
            // It happened, wholly — so it is recorded as what it was: 20 seconds of "no task allowed" ending
            // here, marked as a dynamic period the app CONDUCTED (`TaskPanel.conductedBreak`). That mark is
            // what makes the README's first bar fire — no 20 s period in the twenty minutes after it — since
            // that bar keys on a dynamic *period* and the other two on a rest *stretch* far longer than this.
            // Nothing else is needed and nothing else is written, and the calendar draws it because it is a
            // real panel. A run that was interrupted
            // never reaches here, so it leaves no trace at all — the same asymmetry as before, now by
            // construction rather than by an anchor that only moves on completion.
            vm.dispatch(
                SchedulerIntent.RecordConductedBreak(
                    lookAway.title,
                    resumeAt - lookAway.durationMillis,
                    resumeAt,
                ),
            )
            requestReschedule(clock.nowMillis())
            announceResumeWork(voice)
        }
    }

    /**
     * PRD §7 **"Switch task"** — the lateral-menu button and the system-wide `Ctrl+Shift+Alt+Z` chord: refuse
     * the task the now-line is on, so the plan starts a different one from now.
     *
     * The whole of it is the intent: the reducer records the refusal and re-plans in one step (the press IS
     * the calculation event), and the §11 task-switch sweep announces whatever the new plan starts — which is
     * the feedback the chord needs, since it is struck with some other window in front.
     */
    fun forceTaskSwitch() {
        vm.dispatch(SchedulerIntent.ForceTaskSwitch(clock.nowMillis()))
    }

    /**
     * PRD §7/§15: **the receipt** for a system-wide chord — a notification saying which
     * `Ctrl+Shift+Alt+<letter>` the app just received, posted the moment the press arrives and before the
     * action it asks for runs.
     *
     * Every one of these chords is struck while OmniApp is *not* the focused window, so the app gives the
     * user nothing they can see; and each of them can be silently lost in a different way — another
     * application swallowing the press underneath our hook, Windows dropping a hook that overran
     * `LowLevelHooksTimeout`, a claim that came back `Unavailable`. "Nothing happened" would otherwise be
     * indistinguishable from "the app received it and decided there was nothing to do":
     * [restartLookAway] returns silently when no look-away break exists, [setUserAway] is a no-op on a
     * same-value call, and [forceTaskSwitch]'s own announcement only comes if the re-plan actually starts a
     * different task.
     *
     * So it is deliberately a receipt for the PRESS and not for the effect: it is posted whatever the
     * handler then does, and it names the chord as well as the action so a user who struck two chords in
     * quick succession can tell which one landed.
     *
     * It belongs to the hot-key seam, NOT to the actions: the lateral-menu buttons drive exactly the same
     * engine entry points, and a click needs no receipt — the window is already in front of the user.
     */
    fun announceShortcutReceived(shortcut: GlobalShortcut) {
        // The chord the ACCOUNT is bound to, not the one the enum ships with (PRD §7: the
        // keyboard-shortcuts window can rebind these three). A receipt naming a chord the user does not
        // have would be worse than none — it is the one line they check when a press seems to go nowhere.
        val chord = GlobalShortcutBindings.chordOf(vm.state.value.shortcutBindings, shortcut)
        notifyUser(SHORTCUT_RECEIVED_TITLE, "$chord — ${shortcut.action}")
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
        // Synchronously, before the sample below is scheduled: an unlock is also the user's "I'm back"
        // ([noteScreenSignal]), and the session/heartbeat this poke advances must reflect the cleared flag.
        noteScreenSignal()
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
     *
     * The re-check is the LOCK ([deviceUnlocked]), not the presence reading: this is the cue for a device
     * whose user cannot see a notification, so a phone the user is holding must stay silent even while "I'm
     * away" says nobody is working at it (PRD §15 — that button is about the no-screen periods, not about
     * whether the app may speak).
     */
    suspend fun onPauseCueFire() {
        if (poseFinishEligible(
                isPhone = deviceKind == DeviceKind.Phone,
                signedIn = pauseCue?.signedIn == true,
                screenActive = deviceUnlocked(),
            )
        ) {
            speakCue(pendingPauseCue)
        } else {
            Diagnostics.log(
                "OS pause-cue alarm fired but suppressed (phone=${deviceKind == DeviceKind.Phone}, " +
                    "signedIn=${pauseCue?.signedIn == true}, screenActive=${deviceUnlocked()})",
            )
        }
    }

    companion object {
        /**
         * PRD §15 (server-side break computation): **where each of the two poses' next period is PLACED** —
         * the whole content of the account's `device_break` row, from which the server decides whether the
         * account went idle with a break owed and times the cue as `idleInstant + length`. Keyed by
         * [ScreenBreak.key] (`"5min_break"` / `"15min_break"`); a key is absent when that pose is not
         * configured, or when the recurrence bars place no occurrence of it inside
         * [SchedulerDomain.NEXT_BREAK_SEARCH_MILLIS] (a night, an open-ended inactivity period — a pose the
         * environment has suspended indefinitely has no next instant to name).
         *
         * **The value is the pose's next placed START** ([SchedulerDomain.nextScreenBreakStartMillis]), and
         * that is the whole of the 1.6.0 change here. It used to be the anchored due `lastRest + interval`,
         * *because* the drawn start rode the now-line: an owed break slid right with it and changed at every
         * sample, so it could not be written event-driven and the published instant had to be a separate
         * derivation. Nothing slides any more (ADR 0003) — a break's start is a fixed instant the bars derive,
         * moving only when the rules or the environment do — so the server and the client key on ONE instant,
         * the same one the calendar draws and the local cue sweep fires on.
         *
         * It must therefore be asked with the **same environment the fill was** ([basePeriods], [tasks]): the
         * bars walked over a different timeline answer a different grid, and the server would then time the
         * cue to a break the user never sees.
         *
         * **Both dues are published; the SELECTION is the server's** (migration 20260728000000). Among the
         * breaks already due at the last beat the LONGEST governs, because resting the 15-min pose also
         * discharges a 5-min one due at the same instant.
         */
        internal fun restPoseDueMillisByKey(
            screenBreaks: List<ScreenBreak>,
            nowMillis: Long,
            basePeriods: List<RestrictivePeriod> = emptyList(),
            blocks: List<PlanBlock> = emptyList(),
            tasks: List<PlanTask> = emptyList(),
        ): Map<String, Long> {
            val poses = screenBreaks.filter {
                it.restBreak && it.intervalMillis > 0 && it.durationMillis > 0 &&
                    it.title.isNotBlank() && it.key.isNotBlank()
            }
            if (poses.isEmpty()) return emptyMap()
            val out = HashMap<String, Long>()
            for (pose in poses) {
                val start = SchedulerDomain.nextScreenBreakStartMillis(
                    screenBreaks = screenBreaks,
                    title = pose.title,
                    nowMillis = nowMillis,
                    basePeriods = basePeriods,
                    blocks = blocks,
                    tasks = tasks,
                ) ?: continue
                // Several configs sharing one key would be one break as far as the server is concerned; the
                // soonest is the one the user reaches first.
                out[pose.key] = minOf(out[pose.key] ?: Long.MAX_VALUE, start)
            }
            return out
        }

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
