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
import kotlinx.coroutines.flow.SharedFlow
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
import org.example.project.scheduler.platform.currentDeviceKind
import org.example.project.scheduler.platform.isScreenActive
import org.example.project.scheduler.platform.sendSystemNotification
import org.example.project.scheduler.platform.lastWakeAfterLongSleepMillis
import org.example.project.scheduler.platform.recentSleepGaps as platformRecentSleepGaps
import org.example.project.scheduler.platform.VoiceCue
import org.example.project.scheduler.platform.playVoiceCue as platformPlayVoiceCue
import org.example.project.scheduler.platform.stopSpeaking
import org.example.project.scheduler.sync.ActiveSessionGateway
import org.example.project.scheduler.sync.PauseCueGateway
import org.example.project.scheduler.sync.PauseCuePushScheduler
import org.example.project.scheduler.sync.PresenceGateway
import org.example.project.scheduler.sync.SleepGapGateway
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerState
import org.example.project.scheduler.ui.TaskSchedulerViewModel
import org.example.project.time.AppClock
import org.example.project.time.SimAppClock
import org.example.project.time.SystemAppClock

// PRD §15: the furthest back the look-away cue scan looks when the now-line advances in one step. It exceeds
// a normal accelerated tick's reach (the fastest sim speed, 300×, advances 300 s over the 1 s tick) so smooth
// fast-forward never clips a crossing, while a larger leap (manual time-leap, or waking from a long real
// device sleep) is treated as a jump that announces at most the last few minutes — not a backlog of cues.
private const val LOOK_AWAY_SWEEP_CAP_MILLIS: Long = 10L * 60 * 1_000

// How fresh a look-away occurrence's *start* must be — as a budget in **real** time — for its cues to fire.
// See the long-form rationale this was lifted from in App.kt's history: a fixed sim-time window would shrink
// to a few real ms under heavy acceleration and every just-reached start would be judged stale.
private const val LOOK_AWAY_START_FRESH_MILLIS: Long = 2_000

// Real-time cap on each sleep while the manual "Look away now" rest counts down (see [restartLookAway]).
private const val LOOK_AWAY_RESUME_POLL_MILLIS: Long = 200

// PRD §12/§15 device-sleep detection: the *real*-time gap between two advance ticks that means the process was
// suspended (the device slept). It is the production tick cadence × 3 — a fixed REAL duration that does NOT
// scale with the (possibly accelerated) sim tick rate, so device inactivity is detected by the same ~90 s real
// gap at every speed.
private const val DEVICE_SLEEP_THRESHOLD_MILLIS: Long = 90L * 1_000

// PRD §15 cross-device presence: there is NO periodic beacon. A device with an active screen inside a 5/15-min
// rest pose announces itself once, coalesced into the SAME last-responsible-moment burst as the deferred
// pause-cue push ([launchPauseCueSchedule]'s push lambda) — i.e. at min(d1,d2) − margin, right before the pose
// end the phone reads presence at. The phone gets *when* to speak from the Edge push; presence only supplies
// the screen-off suppression gate at that read, so a single fresh write there is enough — a 60 s poll (the
// former beacon) would just chatter one `device_presence` upsert per minute on an otherwise-idle session.

// PRD §15: how long before a pose's end the phone reads presence to decide the cue — a 1-min lead that
// doubles as a buffer to retry the read on a flaky connection before the pose actually ends.
private const val POSE_FINISH_CHECK_LEAD_MILLIS: Long = 60L * 1_000

// PRD §15: a presence row older than this no longer counts as an active screen. Wide enough that the single
// presence write coalesced with the deferred pause-cue push (near the pose end) still reads fresh at the
// phone's pre-end / fire-time check.
private const val POSE_PRESENCE_FRESH_MILLIS: Long = 150L * 1_000

// PRD §15: how many times the pre-end presence read is retried (and the real-time gap between tries) before
// giving up. A give-up is treated as "no peer active" (fail-open: a real pause still gets its end cue).
private const val POSE_FINISH_READ_ATTEMPTS: Int = 3
private const val POSE_FINISH_RETRY_MILLIS: Long = 2L * 1_000

// PRD §15: real-time cap on each sleep while waiting for a 5/15-min pose to end before the "pause finished"
// voice cue, so a mid-wait sim-speed change is picked up promptly (cf. [LOOK_AWAY_RESUME_POLL_MILLIS]).
private const val POSE_FINISH_POLL_MILLIS: Long = 1_000

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

// PRD §15 server-derived pauses: how often the active-session beat samples `isScreenActive()` and extends the
// current session in the LOCAL store. Mirrors the advance-tick cadence (1 s under sim / 30 s in production) so
// the session timeline tracks the same `now` the schedule does. The beat NEVER talks to the server — opens,
// extends, and finalizes are all local-only; the rows ride the next of the four sync moments (login, the sync
// button, the 10-s user-change debounce, the deferred pause-cue burst) — see [pushOwnActiveSessions].
private const val ACTIVE_SESSION_BEAT_MILLIS_SIM: Long = 1_000
private const val ACTIVE_SESSION_BEAT_MILLIS_PROD: Long = 30L * 1_000

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
// PRD §15 / ARCHITECTURE.md §8 (requirement #4): the deferred pause-cue push margins. 2 s when the cue moves
// LATER (the server still holds the earlier instant, so the last phone must be told to CANCEL its stale alarm —
// ~1 s for the upsert's trigger → edge push to reach the phone, plus ~1 s of slack); 1 s otherwise (nothing to
// cancel — the cue is just moving earlier, or it is the first publish). See [PauseCuePushScheduler].
private const val PAUSE_CUE_CANCEL_MARGIN_MILLIS: Long = 2_000
private const val PAUSE_CUE_PUBLISH_MARGIN_MILLIS: Long = 1_000

class SchedulerEngine(
    private val vm: TaskSchedulerViewModel,
    private val clock: AppClock,
    private val scope: CoroutineScope,
    private val tz: TimeZone = TimeZone.currentSystemDefault(),
    // PRD §15 cross-device presence: the heartbeat/peer-query channel (the sync engine); null disables it.
    private val presence: PresenceGateway? = null,
    // PRD §15: what kind of device this is — only the phone speaks the "pause finished" cue. Injectable for tests.
    private val deviceKind: DeviceKind = currentDeviceKind(),
    // PRD §15: whether this device's screen is active right now. Injectable for tests.
    private val screenActive: () -> Boolean = ::isScreenActive,
    // PRD §15: the voice sink (defaults to the platform player of the bundled shared cue audio); injectable so
    // cues are assertable in tests.
    private val playCue: (VoiceCue) -> Unit = ::platformPlayVoiceCue,
    // PRD §15 device-sleep gaps: local store for the exact pause intervals; null disables gap recording/pull.
    private val sleepGapStore: DeviceSleepGapStore? = null,
    // PRD §15 device-sleep gaps: the push/pull channel (the sync engine); null disables remote gap sync.
    private val sleepGaps: SleepGapGateway? = null,
    // PRD §15: the OS sleep/wake-log query (defaults to the platform reader); injectable for tests.
    private val sleepGapQuery: (Long) -> List<DeviceSleepGap> = ::platformRecentSleepGaps,
    // PRD §15 device-sleep gaps: LOCAL-ONLY watermark of how far the OS sleep/wake log has been scanned, so the
    // launch backfill resumes instead of re-reading the full 3-day horizon each launch; null re-scans it fully.
    private val sleepScanCheckpoint: SleepScanCheckpointStore? = null,
    // PRD §15 server-derived pauses: local store for this device's active-session intervals; null disables
    // active-session tracking (the heartbeat still runs but persists nothing / renders no bands).
    private val activeSessionStore: ActiveSessionStore? = null,
    // PRD §15 server-derived pauses: the report-activity / pull-derived-pauses channel (the sync engine); null
    // falls back to deriving pauses from THIS device's own sessions locally (single-device / signed-out).
    private val activeSessions: ActiveSessionGateway? = null,
    // PRD §15 / ARCHITECTURE.md §8 pause-end cue delivery: the server channel (write next-cue instant / claim
    // last phone / register token). Null disables it; the desktop still publishes the schedule so the phone's
    // push fires, but only a phone claims last-phone (see [launchPauseCueSchedule]).
    private val pauseCue: PauseCueGateway? = null,
    // The unified sync moments (every snapshot reconcile: startup, login, manual sync, debounced change) —
    // the engine runs its side-channel push/pull on each emission (see [launchSyncMomentSideChannels]), so
    // the activity/gap channels sync at exactly the moments the snapshot does. Notably this is what makes a
    // fresh-credential launch push its open session AFTER the async auto-login lands (engine start alone
    // raced it, so the push was silently dropped and peers derived a too-long Inactivity band). Null when
    // sync is disabled.
    private val syncMoments: SharedFlow<Unit>? = null,
    // PRD §15: the platform OS-scheduled local cue seam — schedule the "pause is over" alarm at the given
    // instant, or cancel the pending one when null. Default no-op (desktop/tests); the phone wires AlarmManager
    // / UNUserNotificationCenter here (see docs/PAUSE_CUE_DELIVERY.md steps 2/3). The alarm's fire handler runs
    // [poseFinishEligible] before speaking, so the presence/screen-off gate is still honored at delivery.
    private val scheduleLocalPauseCue: (Long?) -> Unit = {},
    // PRD §15: true on a platform that delivers the pause-end cue via [scheduleLocalPauseCue] + [onPauseCueFire]
    // (the OS-scheduled alarm, which fires even if the app was killed). When true the older in-app
    // [launchPoseFinishVoiceCue] is skipped so the phone never speaks the cue twice. Default false keeps the
    // in-app path for desktop / not-yet-wired platforms.
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
    private var lastNotifiedSideTitle: String? = null

    // PRD §15 (20s look-away) / wind-down bookkeeping; survives a collectLatest restart like the old remember.
    private var announcedStarts = setOf<Long>()
    private var pendingEnds = setOf<Long>()
    private var announcedWindDowns = setOf<Long>()
    private var manualLookAwayJob: Job? = null

    // PRD §15 (5/15-min pose "pause finished" cue): pose start instants already evaluated, so each pose is
    // only gated/scheduled once. Survives a collectLatest restart like [announcedStarts]/[pendingEnds].
    private var poseFinishHandled = setOf<Long>()

    // Whether the once-per-process last-phone claim on the first unified sync moment has fired
    // (see [launchSyncMomentSideChannels]).
    private var lastPhoneClaimedAtSyncMoment = false

    private var started = false

    fun start() {
        if (started) return
        started = true
        launchAdvanceTick()
        launchSideTaskSeeding()
        launchTreeChangeReschedule()
        launchHorizonReschedule()
        launchPendingRescheduleOnSwitch()
        launchTaskSwitchNotification()
        launchSidePoseNotification()
        launchLookAwayCues()
        launchWindDownNotification()
        // The in-app cue is the delivery path only where the OS-scheduled alarm isn't wired; otherwise the
        // alarm ([onPauseCueFire]) speaks, so running both would double-speak.
        if (!localPauseCueDelivery) launchPoseFinishVoiceCue()
        launchPauseCueSchedule()
        // PRD §15: on startup, scan this device's OS sleep log into the LOCAL gaps store (the recorded gaps
        // ride the next sync moment; nothing is pushed or pulled here — the login reconcile's sync moment,
        // which fires moments later, does the remote leg via [launchSyncMomentSideChannels]).
        backfillSleepGaps()
        // PRD §15 server-derived pauses: track this device's active sessions LOCALLY. The server knows which
        // peer sessions are still OPEN (the `closed` flag) and presumes only those active up to the now-line,
        // so there is no pull-first ordering and no "adopt the unknown window as remote activity" step any
        // more — [purgeLegacyAdoptedRows] deletes what an older build adopted. There is NO periodic remote
        // refresh and NO startup one-shot fetch: the side channels push/pull only on the four sync moments —
        // every snapshot reconcile (login incl. this launch's, the sync button, the 10-s user-change
        // debounce; see [launchSyncMomentSideChannels]) plus the deferred pause-cue burst
        // ([launchPauseCueSchedule]). The local-only refresh below just gives a prompt first render (and the
        // sync-disabled install its only derivation) from this device's own stored sessions while the
        // startup reconcile is still in flight.
        purgeLegacyAdoptedRows()
        launchActiveSessionTracking()
        launchSyncMomentSideChannels()
        refreshDerivedPauses(remote = false)
        // PRD §15 / ARCHITECTURE.md §8: requirement #6 — a phone's startup becomes the account's last phone,
        // which pushes `cancel` to the previous phone. Kept for the sync-disabled/no-moments path; the
        // fresh-credential launch (auto-login still in flight here, so this no-ops) is healed by the claim on
        // the first sync moment in [launchSyncMomentSideChannels].
        claimLastPhoneOnStartup()
    }

    /**
     * The unified sync moments: on every snapshot reconcile (startup, login completion, the manual sync
     * button, the debounced-change flush) re-run the side-channel push/pull — this device's active sessions
     * and sleep gaps up, the account-wide derived pauses and peer gaps down. One schedule for every channel:
     * a moment that syncs the snapshot syncs everything, so a channel can no longer miss a trigger the other
     * ran on (the desktop-active-since-21:28-but-Android-shows-21:31 anomaly: engine start raced the async
     * auto-login, the startup session push no-oped signed-out, and no later moment retried it).
     *
     * The re-push of the own recent sleep gaps is also THE upload path for what [backfillSleepGaps] and
     * [recordExactSleepGaps] record — those scans write the local store only, so the gaps reach the server
     * exclusively here (bandwidth rule: no side-channel write outside the sync moments). The upserts are
     * idempotent (keyed on device + start), so re-pushing is cheap.
     */
    private fun launchSyncMomentSideChannels() {
        val moments = syncMoments ?: return
        scope.launch {
            moments.collect {
                // Phones: (re)claim last-phone once per process on the first moment — by then the auto-login
                // has resolved, unlike [claimLastPhoneOnStartup]. Not on EVERY moment: a background service's
                // debounced pushes must not steal the claim from the phone the user actually holds
                // ([onAppForegrounded] is the deliberate re-claim path).
                if (!lastPhoneClaimedAtSyncMoment) {
                    lastPhoneClaimedAtSyncMoment = true
                    claimLastPhone()
                }
                pushOwnRecentSleepGaps()
                pullSleepGaps()
                refreshDerivedPauses()
            }
        }
    }

    // PRD §15: re-upsert this device's own recent exact sleep gaps (see [launchSyncMomentSideChannels]).
    private suspend fun pushOwnRecentSleepGaps() {
        val gateway = sleepGaps ?: return
        val store = sleepGapStore ?: return
        val since = clock.nowMillis() - PAUSE_DERIVE_HORIZON_MILLIS
        val own = store.loadSleepGaps().filter { it.deviceId == gateway.deviceId && it.endMillis >= since }
        if (own.isEmpty()) return
        withContext(Dispatchers.Default) { runCatching { gateway.pushSleepGaps(own) } }
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
        val sideTaskDue =
            current.automaticSchedule &&
                current.sideTasks.any { it.restBreak && SchedulerDomain.isSideTaskOverdue(it, now) }
        if (sideTaskDue) {
            vm.dispatch(SchedulerIntent.RefreshSchedule(now))
        } else {
            vm.dispatch(SchedulerIntent.AdvanceSchedule(now))
        }
    }

    /**
     * Debug "simulate pause + leap": force this device to read as screen-inactive (`true`) or return to the
     * real platform sensor (`false`). Flipping it advances the active session immediately — finalizing the
     * open session on `true` (locally, like a real walk-away) and reopening one on `false` — so a
     * 1-real-second accelerated leap never depends on the beat cadence to see the pause boundaries. The
     * finalized bounds reach the server via the debug control's explicit [refreshDerivedPauses] after the
     * leap (a debug leap counts as an explicit sync moment). Called by the desktop debug control, and per
     * time-link frame on a linked phone (a same-value call is a no-op, so the 250 ms frames don't churn).
     */
    fun setDebugForcedInactive(inactive: Boolean) {
        if (debugForcedInactive == inactive) return
        debugForcedInactive = inactive
        scope.launch { advanceActiveSession(clock.nowMillis(), effectiveScreenActive(), suspended = false) }
    }

    // The screen-activity sample every engine site reads: the real platform sensor, overridden to inactive
    // while the debug leap forces it (see [setDebugForcedInactive]).
    private fun effectiveScreenActive(): Boolean = !debugForcedInactive && screenActive()

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

    // ---- PRD §15 server-derived pauses: active-session tracking + derived-pause refresh ----

    private val activeSessionDeviceId: String get() = activeSessions?.deviceId ?: LOCAL_DEVICE_ID

    // PRD §15: track this device's activity in the LOCAL store. Each beat samples `screenActive()` and either
    // opens, extends, or finalizes the current session, persisting it locally — it NEVER talks to the server:
    // the remote push is [pushOwnActiveSessions], fired only at the four sync moments. A finalize (the user
    // walked away) therefore reaches the server at the next moment — in practice the deferred pause-cue burst
    // that lands 1–2 s before the next pose end, so a peer over-presumes this device active for at most one
    // pose cadence (bounded server-side by the open-session freshness grace). That bounded staleness is the
    // deliberate trade for "the four moments are the only communications" (ARCHITECTURE.md §8). `suspended`
    // is judged from REAL elapsed time between beats — NOT the (possibly accelerated) clock — so a real process
    // suspension ends the session at its pre-sleep end and the post-wake session opens after the gap, exactly
    // like the §12 device-sleep detection; a fast sim tick just extends the session across the leaped clock time.
    private fun launchActiveSessionTracking() = scope.launch {
        var lastRealBeat = SystemAppClock.nowMillis()
        while (true) {
            val realNow = SystemAppClock.nowMillis()
            val suspended = realNow - lastRealBeat > DEVICE_SLEEP_THRESHOLD_MILLIS
            advanceActiveSession(clock.nowMillis(), effectiveScreenActive(), suspended)
            lastRealBeat = realNow
            // Beat faster while accelerated (re-checked each pass — the speed changes at runtime) so the session
            // timeline tracks the racing `now` finely; keys off the clock's actual speed so the phone beats fast
            // too under the desktop time-link, where DebugFlags.TIME_SIMULATION is off.
            tickDelay(if (timeAccelerated()) ACTIVE_SESSION_BEAT_MILLIS_SIM else ACTIVE_SESSION_BEAT_MILLIS_PROD)
        }
    }

    // PRD §15: advance the current active session to `now`, LOCAL store only. A suspension or an inactive
    // screen finalizes it; an active screen opens a new session (if none / after a finalize) or extends the
    // open one. Serialized under [activeSessionMutex] because the beat and the debug forced-inactivity flip
    // both call it. Local-only in every branch — a finalize rides the next sync moment, never its own push.
    private suspend fun advanceActiveSession(now: Long, active: Boolean, suspended: Boolean) {
        activeSessionMutex.withLock {
            val open = currentSession
            if ((suspended || !active) && open != null) {
                finalizeSessionLocked(open)
                currentSession = null
            }
            if (active) {
                val cur = currentSession
                val realNow = SystemAppClock.nowMillis()
                val updated =
                    if (cur == null) {
                        ActiveSessionRecord(activeSessionDeviceId, now, now, realNow)
                    } else {
                        cur.copy(endMillis = maxOf(cur.endMillis, now), updatedAtMillis = realNow)
                    }
                currentSession = updated
                persistSessionLocked(updated)
            }
            _activeSince.value = currentSession?.startMillis
        }
    }

    // Finalize a session: persist its final bounds locally. Caller holds [activeSessionMutex].
    private fun finalizeSessionLocked(session: ActiveSessionRecord) {
        persistSessionLocked(session)
    }

    private fun persistSessionLocked(session: ActiveSessionRecord) {
        activeSessionStore?.saveActiveSessions(listOf(session))
    }

    // PRD §15: freshen the open session up to `now`, opening one if the screen is active and none is open, and
    // persist it locally so a subsequent load/push reports activity right up to the present. No-op when the
    // screen is inactive (the beat finalizes it; masking a just-started pause here would hide a real pause).
    private suspend fun freshenOpenSession() {
        activeSessionMutex.withLock {
            if (!effectiveScreenActive()) return@withLock
            val now = clock.nowMillis()
            val realNow = SystemAppClock.nowMillis()
            val cur = currentSession
            val updated =
                cur?.copy(endMillis = maxOf(cur.endMillis, now), updatedAtMillis = realNow)
                    ?: ActiveSessionRecord(activeSessionDeviceId, now, now, realNow)
            currentSession = updated
            persistSessionLocked(updated)
            _activeSince.value = updated.startMillis
        }
    }

    // PRD §15: push this device's active-session history to the server — freshened up to `now` and covering the
    // whole derive horizon, NOT just the currently-open session. This is the ONLY active-session remote write,
    // fired exclusively at the four sync moments ([refreshDerivedPauses]'s remote callers: every unified sync
    // moment — login/manual sync/10-s debounced change, see [launchSyncMomentSideChannels] — and coalesced
    // with the deferred pause-cue burst). Never on a beat, an open, or a finalize — those are local-only and
    // ride the next moment.
    //
    // Pushing the FULL local history (not just the current session) is what keeps `derive_pauses` honest: the
    // server deduces the account-wide pauses as the complement of the UNION of every device's active intervals,
    // so any finalized session we never uploaded — an idle-screen finalize, or an earlier fragment — becomes a
    // phantom pause on OTHER devices (and on our own next server re-derive). This device's local store is the
    // complete truth of when it was active; publishing it lets a peer's pull only ever SHRINK a pause over time
    // we were demonstrably active, never fabricate one. Best-effort; swallows transport errors.
    private suspend fun pushOwnActiveSessions() {
        val gateway = activeSessions ?: return
        freshenOpenSession()
        val horizonStart = clock.nowMillis() - PAUSE_DERIVE_HORIZON_MILLIS
        val stored = activeSessionStore?.loadActiveSessions()
        val sessions =
            when {
                // A store-backed install: the durable history is the source of truth (includes the freshly
                // persisted open session). Only rows still within the derive horizon are relevant, and the
                // legacy adopted remote-activity rows are LOCAL-ONLY (see [pushableActiveSessions]).
                stored != null -> pushableActiveSessions(stored, horizonStart)
                // A store-less install (e.g. web's in-memory store): fall back to the single open session.
                else -> activeSessionMutex.withLock { currentSession }?.let { listOf(it) } ?: emptyList()
            }
        if (sessions.isEmpty()) return
        // The open session (if any) uploads with closed = false — the one row the server may presume still
        // extends toward `now`; every finalized session uploads closed, so the time after it can derive as a
        // pause on every peer.
        val openStart = activeSessionMutex.withLock { currentSession?.startMillis }
        withContext(Dispatchers.Default) { runCatching { gateway.pushActiveSessions(sessions, openStart) } }
    }

    /**
     * PRD §15: refresh the calendar's "Inactivity" bands from the account-wide pauses. When signed in the
     * SERVER is authoritative (the `derive_pauses` RPC over every device's active sessions). If the RPC is
     * unavailable — a transport blip, or the migration not yet deployed — OR the account is signed out, it falls
     * back to deriving from THIS device's own stored sessions, so the past still fills instead of showing
     * nothing (for a single device the local answer equals the server's anyway). The freshly derived pauses
     * also seed the §15 rest poses (advancing `lastRestMillis` only) — the account-wide-pause signal reaching a
     * device that never slept.
     *
     * Always PUSH first, then pull: publishing before deriving means the server never hands us (or a peer) a
     * pause over a window this device was active but hadn't yet uploaded — the "if the server's Inactivity
     * block is bigger than the app's, reduce it" rule — and a pause this device just bounded by becoming
     * active again is returned (and seeds the rest poses) immediately. The push marks this device's open
     * session `closed = false`; the server presumes only fresh OPEN sessions active up to the now-line and
     * returns everything else — including the trailing window after every finalized session — as pauses
     * ("inactivity unless a device reported activity"). That is what lets startup be push-first with no
     * special ordering: our own just-opened point never bounds a live peer's window, because the peer's open
     * row covers it server-side. The old scheme instead presumed the whole trailing window active and DURABLY
     * ADOPTED it into the local store at startup, which fabricated activity over genuine pauses whose
     * finalize hadn't been uploaded yet (see [purgeLegacyAdoptedRows]).
     *
     * As a same-coroutine belt to those suspenders, the pulled pauses then have this device's own local
     * sessions SUBTRACTED before display/seeding: a device must never render a pause over time it knows the
     * account was active, even if its push lost a race or a peer's clock is skewed (a pause is, by
     * definition, a window when NO device was active, so known activity legitimately cancels it).
     *
     * Public for the debug "simulate pause + leap" control: a debug leap counts as an explicit sync moment,
     * so the bands / rest poses reflect the just-simulated pause without waiting for the next reconcile.
     *
     * `remote = false` (the startup one-shot) makes it a purely LOCAL derivation — no push, no fetch — so a
     * launch renders the bands promptly from this device's own stored sessions without adding a server
     * round-trip outside the four sync moments (the login reconcile's moment follows and does the remote leg).
     */
    fun refreshDerivedPauses() = refreshDerivedPauses(remote = true)

    private fun refreshDerivedPauses(remote: Boolean) {
        scope.launch {
            val gateway = if (remote) activeSessions else null
            if (remote) pushOwnActiveSessions()
            // Signed out, pushOwnActiveSessions returns without freshening; freshen here so the local
            // fallback's open session reaches `now` and its trailing gap is genuinely empty while active.
            freshenOpenSession()
            val until = clock.nowMillis()
            val since = until - PAUSE_DERIVE_HORIZON_MILLIS
            val fromServer =
                if (gateway?.signedIn == true) {
                    withContext(Dispatchers.Default) { runCatching { gateway.fetchDerivedPauses(since, until) }.getOrNull() }
                } else {
                    null
                }
            val serverPauses = fromServer ?: withContext(Dispatchers.Default) { localDerivedPauses(since, until) }
            val ownActive =
                withContext(Dispatchers.Default) {
                    activeSessionStore?.loadActiveSessions()
                        ?.filter { it.deviceId != REMOTE_ACTIVITY_DEVICE_ID }
                        ?.map { TaskTimeRange(it.startMillis, it.endMillis) }
                        ?: emptyList()
                }
            val pauses = SchedulerDomain.subtractRegions(serverPauses, ownActive)
            _inactivityGaps.value = pauses
            val before = vm.state.value.sideTasks
            applySeededSideTasks(SchedulerDomain.seedSideTasksFromGaps(before, pauses))
        }
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

    private fun localDerivedPauses(since: Long, until: Long): List<TaskTimeRange> {
        val sessions = activeSessionStore?.loadActiveSessions()?.map { TaskTimeRange(it.startMillis, it.endMillis) }
            ?: return emptyList()
        return SchedulerDomain.derivePauses(sessions, since, until)
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
    private fun launchTaskSwitchNotification() = scope.launch {
        combine(_nowMillis, vm.state) { now, st -> SchedulerDomain.currentPanel(st, now)?.taskId }
            .distinctUntilChanged()
            .collectLatest { taskId ->
                if (taskId == null || taskId == lastNotifiedTaskId) return@collectLatest
                val st = vm.state.value
                val currentPanel = SchedulerDomain.currentPanel(st, _nowMillis.value) ?: return@collectLatest
                if (currentPanel.taskId != taskId) return@collectLatest
                val message =
                    SchedulerDomain.taskSwitchNotificationMessage(
                        state = st,
                        taskId = taskId,
                        startMillis = currentPanel.startEpochMillis,
                    ) { deadline -> formatClockTime(Instant.fromEpochMilliseconds(deadline).toLocalDateTime(tz)) }
                        ?: return@collectLatest
                lastNotifiedTaskId = taskId
                sendSystemNotification("Task to do now", message)
            }
    }

    // PRD §15 Notifications: a *rest pose* that becomes the current activity is notified by its title.
    private fun launchSidePoseNotification() = scope.launch {
        combine(_nowMillis, vm.state) { now, st -> currentPoseTitle(st, now) }
            .distinctUntilChanged()
            .collectLatest { title ->
                if (title == null || title == lastNotifiedSideTitle) return@collectLatest
                lastNotifiedSideTitle = title
                sendSystemNotification("Side task", title)
            }
    }

    private fun currentPoseTitle(st: SchedulerState, now: Long): String? =
        SchedulerDomain.currentPanel(st, now)
            ?.takeIf { panel ->
                panel.sideTask && st.sideTasks.any { it.restBreak && it.title == panel.title }
            }
            ?.title

    // PRD §15 (20s look-away): schedule each cue at the real instant the (possibly accelerated) clock reaches
    // its boundary. Re-keys on (now, panels) to pick up newly projected occurrences and clock-speed changes;
    // [announcedStarts]/[pendingEnds] survive the re-key so nothing fires twice.
    private fun launchLookAwayCues() = scope.launch {
        combine(_nowMillis, vm.state.map { it.panels }.distinctUntilChanged()) { _, panels -> panels }
            .collectLatest {
                while (true) {
                    val st = vm.state.value
                    val simNow = clock.nowMillis()
                    val speed = (clock as? SimAppClock)?.speed ?: 1.0
                    val voice = st.lookAwayVoiceEnabled
                    announcedStarts = announcedStarts.filterTo(mutableSetOf()) { it >= simNow - LOOK_AWAY_SWEEP_CAP_MILLIS }

                    val occurrences = st.panels.filter { panel ->
                        panel.sideTask && st.sideTasks.any { !it.restBreak && it.title == panel.title }
                    }

                    occurrences
                        .filter {
                            it.startEpochMillis in (simNow - LOOK_AWAY_SWEEP_CAP_MILLIS)..simNow &&
                                it.startEpochMillis !in announcedStarts
                        }
                        .sortedBy { it.startEpochMillis }
                        .forEach {
                            announcedStarts = announcedStarts + it.startEpochMillis
                            val durationMillis = it.endEpochMillis - it.startEpochMillis
                            val freshWindow =
                                (LOOK_AWAY_START_FRESH_MILLIS * speed).toLong().coerceAtMost(durationMillis - 1)
                            if (it.startEpochMillis >= simNow - freshWindow) {
                                pendingEnds = pendingEnds + it.endEpochMillis
                                sendSystemNotification("Side task", it.title)
                                if (voice) playCue(VoiceCue.LookAway)
                            }
                        }

                    pendingEnds.filter { it <= simNow }.sorted().forEach { end ->
                        pendingEnds = pendingEnds - end
                        if (end >= simNow - LOOK_AWAY_SWEEP_CAP_MILLIS && voice) playCue(VoiceCue.ResumeWork)
                    }

                    val nextStart = occurrences.map { it.startEpochMillis }
                        .filter { it > simNow && it !in announcedStarts }.minOrNull()
                    val nextEnd = pendingEnds.filter { it > simNow }.minOrNull()
                    val next = listOfNotNull(nextStart, nextEnd).minOrNull() ?: break
                    if (speed <= 0.0) break
                    delay(((next - simNow).toDouble() / speed).toLong().coerceAtLeast(1L))
                }
            }
    }

    // Wind-down: notify to stop work when the now-line reaches each sleep window's bedtime − 1h.
    private fun launchWindDownNotification() = scope.launch {
        combine(_nowMillis, vm.state.map { it.panels }.distinctUntilChanged()) { _, panels -> panels }
            .collectLatest {
                while (true) {
                    val st = vm.state.value
                    val simNow = clock.nowMillis()
                    val speed = (clock as? SimAppClock)?.speed ?: 1.0
                    announcedWindDowns = announcedWindDowns.filterTo(mutableSetOf()) { it >= simNow - LOOK_AWAY_SWEEP_CAP_MILLIS }
                    val windDowns = st.panels
                        .filter { it.sleep }
                        .map { it.startEpochMillis - SchedulerDomain.NO_TASK_BEFORE_BED_MILLIS }
                    windDowns.filter { it <= simNow && it !in announcedWindDowns }.sorted().forEach {
                        announcedWindDowns = announcedWindDowns + it
                        if (it >= simNow - LOOK_AWAY_SWEEP_CAP_MILLIS) {
                            sendSystemNotification("Stop work", "Wind down — bedtime in 1 hour")
                        }
                    }
                    val next = windDowns.filter { it > simNow && it !in announcedWindDowns }.minOrNull() ?: break
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
            sendSystemNotification("Side task", lookAway.title)
            if (voice) playCue(VoiceCue.LookAway)
            val resumeAt = clock.nowMillis() + lookAway.durationMillis
            while (clock.nowMillis() < resumeAt) {
                val speed = (clock as? SimAppClock)?.speed ?: 1.0
                val remainingReal =
                    if (speed > 0.0) ((resumeAt - clock.nowMillis()).toDouble() / speed).toLong() else Long.MAX_VALUE
                delay(remainingReal.coerceIn(1L, LOOK_AWAY_RESUME_POLL_MILLIS))
            }
            if (voice) playCue(VoiceCue.ResumeWork)
        }
    }

    // PRD §15 cross-device presence: publish this device's "active screen" once, at the deferred
    // last-responsible-moment burst (see [launchPauseCueSchedule]), NOT on a periodic poll. Only announces
    // while the now-line is inside a 5/15-min rest pose and the screen is on — the one window the phone's
    // "pause finished" cue needs to know whether another device is in use. A closed/asleep machine simply
    // never reaches the burst, so it stops announcing.
    private suspend fun publishPosePresenceIfActive() {
        val gateway = presence ?: return
        if (gateway.signedIn && effectiveScreenActive() && inRestPose(vm.state.value, clock.nowMillis())) {
            withContext(Dispatchers.Default) {
                runCatching { gateway.publishPresence(deviceKind, screenActive = true) }
            }
        }
    }

    /** Whether the panel covering [now] is a 5/15-min rest pose (a `restBreak` side task). */
    private fun inRestPose(st: SchedulerState, now: Long): Boolean = currentPoseTitle(st, now) != null

    /**
     * PRD §15: when the now-line reaches the **start** of a 5- or 15-minute pose and **no device on the
     * account has an active screen** (this phone's screen is off and no peer reports one), the **phone**
     * speaks at the pose's **end** to say the pause is over. Eligibility is decided at the start; only the
     * phone ever speaks this cue, so it is inert on desktop. Mirrors [launchLookAwayCues]' occurrence scan,
     * filtered to the `restBreak` poses; [poseFinishHandled] survives the re-key so each pose fires once.
     */
    private fun launchPoseFinishVoiceCue() = scope.launch {
        val gateway = presence ?: return@launch
        if (deviceKind != DeviceKind.Phone) return@launch
        combine(_nowMillis, vm.state.map { it.panels }.distinctUntilChanged()) { _, panels -> panels }
            .collectLatest {
                while (true) {
                    val st = vm.state.value
                    val simNow = clock.nowMillis()
                    val speed = (clock as? SimAppClock)?.speed ?: 1.0
                    poseFinishHandled = poseFinishHandled.filterTo(mutableSetOf()) { it >= simNow - LOOK_AWAY_SWEEP_CAP_MILLIS }

                    val poses = st.panels.filter { panel ->
                        panel.sideTask && st.sideTasks.any { it.restBreak && it.title == panel.title }
                    }

                    poses
                        .filter {
                            it.startEpochMillis in (simNow - LOOK_AWAY_SWEEP_CAP_MILLIS)..simNow &&
                                it.startEpochMillis !in poseFinishHandled
                        }
                        .sortedBy { it.startEpochMillis }
                        .forEach { pose ->
                            poseFinishHandled = poseFinishHandled + pose.startEpochMillis
                            val durationMillis = pose.endEpochMillis - pose.startEpochMillis
                            val freshWindow =
                                (LOOK_AWAY_START_FRESH_MILLIS * speed).toLong()
                                    .coerceAtMost((durationMillis - 1).coerceAtLeast(1))
                            if (pose.startEpochMillis >= simNow - freshWindow) {
                                schedulePoseFinishCue(gateway, pose.endEpochMillis)
                            }
                        }

                    val next = poses.map { it.startEpochMillis }
                        .filter { it > simNow && it !in poseFinishHandled }.minOrNull() ?: break
                    if (speed <= 0.0) break
                    delay(((next - simNow).toDouble() / speed).toLong().coerceAtLeast(1L))
                }
            }
    }

    // PRD §15: committed at a pose's start but DECIDED ~1 min before its end — late enough that a machine the
    // user kept working on has beaconed within the freshness window (so the phone stays silent), early enough
    // to leave a buffer to retry the read on a flaky connection. Launched on [scope] (not the collectLatest
    // job) so a panel/now re-key never cancels a committed cue. A read that never succeeds is treated as "no
    // peer" (fail-open) so a real pause is never left without its end cue.
    private fun schedulePoseFinishCue(gateway: PresenceGateway, endMillis: Long) {
        scope.launch {
            if (!gateway.signedIn) return@launch
            sleepUntilSim(endMillis - POSE_FINISH_CHECK_LEAD_MILLIS)
            if (effectiveScreenActive()) return@launch
            // The presence read below is the cue's eligibility gate (the in-app analogue of [onPauseCueFire]'s
            // read at the OS alarm's fire time) — it is part of the cue decision, not an extra sync: the old
            // opportunistic gap-pull/pause-refresh that rode this moment is gone, since the side channels now
            // sync only at the four sync moments.
            val peersActive = readPeersActiveWithRetry(gateway, endMillis)
            if (!poseFinishEligible(isPhone = deviceKind == DeviceKind.Phone, signedIn = gateway.signedIn,
                    screenActive = effectiveScreenActive(), peersActive = peersActive)) {
                return@launch
            }
            sleepUntilSim(endMillis)
            // Re-check the local screen: the user may have picked up the phone during the final minute.
            if (effectiveScreenActive()) return@launch
            playCue(VoiceCue.PauseOver)
        }
    }

    /** Suspends (sim-speed aware, so a mid-wait speed change is honored) until the clock reaches [targetMillis]. */
    private suspend fun sleepUntilSim(targetMillis: Long) {
        while (clock.nowMillis() < targetMillis) {
            val speed = (clock as? SimAppClock)?.speed ?: 1.0
            if (speed <= 0.0) {
                delay(POSE_FINISH_POLL_MILLIS)
                continue
            }
            val remainingReal = ((targetMillis - clock.nowMillis()).toDouble() / speed).toLong()
            delay(remainingReal.coerceIn(1L, POSE_FINISH_POLL_MILLIS))
        }
    }

    /**
     * Reads whether any peer device currently has an active screen, retrying on a transport failure (a `null`
     * answer) up to [POSE_FINISH_READ_ATTEMPTS] times within the pre-end buffer. A definitive `true`/`false`
     * returns immediately; persistent failure returns `false` (fail-open — the phone speaks the end cue).
     */
    private suspend fun readPeersActiveWithRetry(gateway: PresenceGateway, endMillis: Long): Boolean {
        repeat(POSE_FINISH_READ_ATTEMPTS) { attempt ->
            val answer = withContext(Dispatchers.Default) { gateway.activePeersExistOrNull(POSE_PRESENCE_FRESH_MILLIS) }
            if (answer != null) return answer
            if (attempt < POSE_FINISH_READ_ATTEMPTS - 1 && clock.nowMillis() < endMillis) delay(POSE_FINISH_RETRY_MILLIS)
        }
        return false
    }

    // PRD §15 device-sleep gaps: after a sleep is detected, query the OS sleep/wake log off-thread for the
    // EXACT interval(s) of the pause that was just missed and record them into the LOCAL gaps store. No
    // remote push here — the recorded gaps ride the next sync moment ([pushOwnRecentSleepGaps]). Best-effort:
    // an unsupported platform / failed query returns nothing and the coarse tick-gap hole already kept the
    // schedule correct. Idempotent — the store/remote upsert keys on (deviceId, sleepStart), so re-recording
    // the same interval (or backfilling earlier ones) is harmless.
    private fun recordExactSleepGaps(approxStart: Long, approxEnd: Long) {
        if (sleepGapStore == null && sleepGaps == null) return
        scope.launch {
            val gaps = withContext(Dispatchers.Default) {
                runCatching { sleepGapQuery(approxStart - GAP_QUERY_MARGIN_MILLIS) }.getOrDefault(emptyList())
            }.filter { it.endMillis > it.startMillis && it.endMillis <= approxEnd + GAP_QUERY_MARGIN_MILLIS }
            if (gaps.isEmpty()) return@launch
            val deviceId = sleepGaps?.deviceId ?: LOCAL_DEVICE_ID
            val recordedAt = SystemAppClock.nowMillis()
            val records = gaps.map { SleepGapRecord(deviceId, it.startMillis, it.endMillis, recordedAt) }
            sleepGapStore?.saveSleepGaps(records)
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
        if (sleepGapStore == null && sleepGaps == null) return
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
            val deviceId = sleepGaps?.deviceId ?: LOCAL_DEVICE_ID
            val recordedAt = SystemAppClock.nowMillis()
            val records = gaps.map { SleepGapRecord(deviceId, it.startMillis, it.endMillis, recordedAt) }
            sleepGapStore?.saveSleepGaps(records)
            reseedSideTasksFromGaps()
        }
    }

    private fun pullSleepGaps() {
        val gateway = sleepGaps ?: return
        val store = sleepGapStore ?: return
        scope.launch {
            val remote =
                withContext(Dispatchers.Default) { runCatching { gateway.fetchSleepGaps() }.getOrNull() }
                    ?: return@launch
            val known = store.loadSleepGaps().mapTo(mutableSetOf()) { it.deviceId to it.startMillis }
            val fresh = remote.filter { (it.deviceId to it.startMillis) !in known }
            if (fresh.isNotEmpty()) {
                store.saveSleepGaps(fresh)
                // The freshly pulled gaps are authoritative account-wide pauses; fold them into the rest poses so
                // this device's derived schedule matches the peer that recorded the sleep (PRD §15).
                reseedSideTasksFromGaps()
            }
        }
    }

    // PRD §15 / ARCHITECTURE.md §8: split the two halves of the next pause-end cue instant.
    //  • Phone-local OS cue (requirement #5): (re)scheduled IMMEDIATELY when this device's prediction (d2)
    //    changes, so a phone-originated change is honored with no server round-trip.
    //  • Server upsert (requirements #2/#4): DEFERRED to the last responsible moment — min(d1, d2) minus a
    //    small margin — by [PauseCuePushScheduler], so an idle session never chatters. d1 is the instant the
    //    server currently holds (seeded from the row at startup, then tracked); no push at all in steady state
    //    (d1 == d2). The server's `tick_pause_cues` cron remains the ~1-min-before backstop.
    // d2 is effectively always non-null (every app state has an upcoming pause); if it is ever null (no pose in
    // the horizon) there is nothing to push and no server round-trip is made.
    private fun launchPauseCueSchedule() = scope.launch {
        val gateway = pauseCue ?: return@launch
        val pusher =
            PauseCuePushScheduler(
                scope = scope,
                nowMillis = clock::nowMillis,
                cancelMarginMillis = PAUSE_CUE_CANCEL_MARGIN_MILLIS,
                publishMarginMillis = PAUSE_CUE_PUBLISH_MARGIN_MILLIS,
                push = { dueAt ->
                    withContext(Dispatchers.Default) { runCatching { gateway.publishPauseCueSchedule(dueAt) } }
                    // The fourth sync moment (min(d1,d2) − margin): coalesce this device's active-session push,
                    // the derived-pause pull, and the cross-device "active screen" presence write into the same
                    // last-responsible-moment burst as the cue publish, so an idle session's only remote traffic
                    // is here (the other three moments — login, sync button, 10-s debounced change — all require
                    // a user action) — never a periodic heartbeat or a 60 s presence poll. Publishing presence
                    // here, ~2 s (or 1 s) before the pose end, is exactly when the phone reads it to gate its
                    // cue. This burst is also what carries a session finalize (the user walked away) to the
                    // server, via [refreshDerivedPauses]'s push-then-pull.
                    publishPosePresenceIfActive()
                    refreshDerivedPauses()
                },
            )
        // Seed d1 from what the server already holds so the first deferred push compares against reality
        // instead of pushing unconditionally. Best-effort; an absent/failed read leaves d1 null (1-s margin).
        pusher.seed(withContext(Dispatchers.Default) { runCatching { gateway.fetchPauseCueSchedule() }.getOrNull() })
        combine(_nowMillis, vm.state.map { it.panels }.distinctUntilChanged()) { now, panels ->
            nextRestPoseEndMillis(panels, vm.state.value.sideTasks, now)
        }
            .distinctUntilChanged()
            .collectLatest { d2 ->
                if (deviceKind == DeviceKind.Phone) scheduleLocalPauseCue(d2)
                if (d2 != null) pusher.onPrediction(d2)
            }
    }

    // PRD §15 / ARCHITECTURE.md §8: requirement #6 — a phone's startup claims the account's last phone, whose
    // change pushes `cancel` to the previous phone. Phones only; a no-op when sync is disabled/signed out.
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
     * PRD §15 / ARCHITECTURE.md §8: the native push receiver (an FCM/APNs data message) calls this — schedule
     * the local OS cue at [dueAtMillis] on `"schedule"` (requirement #2: a change on another device reaches
     * this phone), or cancel the pending one on `"cancel"` (the cancel half of requirement #6). The scheduled
     * cue's fire handler still runs [poseFinishEligible] before speaking.
     */
    fun onPauseCuePush(action: String, dueAtMillis: Long?) {
        when (action) {
            "schedule" -> if (dueAtMillis != null) scheduleLocalPauseCue(dueAtMillis)
            "cancel" -> scheduleLocalPauseCue(null)
        }
    }

    /**
     * PRD §15: the platform local cue fired at the pause-end instant (an AlarmManager alarm / a delivered
     * `UNNotification`) calls this — run the presence/screen-off eligibility gate ([poseFinishEligible]) and,
     * if it passes, speak. Reads presence once, failing **open** (speak) on a transport error, so a real pause
     * is never left silent. Inert unless this is a signed-in phone with its screen off and no active peer.
     */
    suspend fun onPauseCueFire() {
        val gateway = presence
        val peersActive: Boolean =
            gateway?.let { g ->
                withContext(Dispatchers.Default) { g.activePeersExistOrNull(POSE_PRESENCE_FRESH_MILLIS) }
            } ?: false
        if (poseFinishEligible(
                isPhone = deviceKind == DeviceKind.Phone,
                signedIn = gateway?.signedIn == true,
                screenActive = effectiveScreenActive(),
                peersActive = peersActive,
            )
        ) {
            playCue(VoiceCue.PauseOver)
        }
    }

    companion object {
        /**
         * PRD §15: the end instant of the next 5/15-min rest pose strictly after [now], or null if none is
         * scheduled. A pose is a `sideTask` panel whose title matches a `restBreak` side task (mirrors the
         * occurrence scan in [launchPoseFinishVoiceCue]).
         */
        internal fun nextRestPoseEndMillis(panels: List<TaskPanel>, sideTasks: List<SideTask>, now: Long): Long? =
            panels.asSequence()
                .filter { p -> p.sideTask && sideTasks.any { it.restBreak && it.title == p.title } }
                .map { it.endEpochMillis }
                .filter { it > now }
                .minOrNull()

        /**
         * PRD §15: the gate for the phone's "pause finished" voice cue — true only when this is the phone,
         * a session is available, this device's screen is off, and no other device reports an active screen.
         */
        internal fun poseFinishEligible(
            isPhone: Boolean,
            signedIn: Boolean,
            screenActive: Boolean,
            peersActive: Boolean,
        ): Boolean = isPhone && signedIn && !screenActive && !peersActive

        /**
         * PRD §15 (legacy): the reserved `deviceId` of the LOCAL-ONLY rows the RETIRED startup adoption wrote
         * into the active-session store — account activity presumed from the server's old trailing-drop
         * answer rather than observed by any device. The closed-flag `derive_pauses` made adoption
         * unnecessary (and it fabricated activity over genuine pauses), so these rows are now deleted at
         * startup ([purgeLegacyAdoptedRows]), excluded from the own-activity subtraction, and — as always —
         * never pushed ([pushableActiveSessions]). The constant remains so old DBs heal. Real device ids are
         * UUIDs (or `LOCAL_DEVICE_ID` while signed out), so it can never collide.
         */
        const val REMOTE_ACTIVITY_DEVICE_ID: String = "remote-activity"

        /**
         * PRD §15: the subset of the locally stored [sessions] that [pushOwnActiveSessions] may upload — rows
         * still inside the derive horizon, EXCLUDING any legacy [REMOTE_ACTIVITY_DEVICE_ID] rows not yet
         * purged. Adopted rows were hearsay (derived from the server's own answer): uploading them would
         * assert activity to the server as if observed, poisoning every peer's derived pauses.
         */
        internal fun pushableActiveSessions(
            sessions: List<ActiveSessionRecord>,
            horizonStartMillis: Long,
        ): List<ActiveSessionRecord> =
            sessions.filter { it.deviceId != REMOTE_ACTIVITY_DEVICE_ID && it.endMillis >= horizonStartMillis }
    }
}
