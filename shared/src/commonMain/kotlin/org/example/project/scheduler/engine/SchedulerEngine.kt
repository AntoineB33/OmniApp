package org.example.project.scheduler.engine

import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
import org.example.project.scheduler.platform.DeviceKind
import org.example.project.scheduler.platform.DeviceSleepGap
import org.example.project.scheduler.platform.currentDeviceKind
import org.example.project.scheduler.platform.isScreenActive
import org.example.project.scheduler.platform.sendSystemNotification
import org.example.project.scheduler.platform.lastWakeAfterLongSleepMillis
import org.example.project.scheduler.platform.recentSleepGaps as platformRecentSleepGaps
import org.example.project.scheduler.platform.speak as platformSpeak
import org.example.project.scheduler.platform.stopSpeaking
import org.example.project.scheduler.sync.ActiveSessionGateway
import org.example.project.scheduler.sync.PauseCueGateway
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

// PRD §15 cross-device presence: instead of a constant heartbeat, a device with an active screen announces
// itself only WHILE the now-line sits inside a 5/15-min rest pose — the one window the phone's "pause
// finished" cue needs to know whether another device is in use. First ping is one interval in (so a user who
// closes the machine to take the pause never announces), then one every interval it stays in the pose.
private const val POSE_BEACON_INTERVAL_MILLIS: Long = 60L * 1_000

// PRD §15: how long before a pose's end the phone reads presence to decide the cue — a 1-min lead that
// doubles as a buffer to retry the read on a flaky connection before the pose actually ends.
private const val POSE_FINISH_CHECK_LEAD_MILLIS: Long = 60L * 1_000

// PRD §15: a presence row older than this no longer counts as an active screen. ~2.5× the beacon interval, so
// a live machine that dropped a single beacon still reads as present at the pre-end check.
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

// PRD §15 server-derived pauses: how often the active-session heartbeat samples `isScreenActive()` and extends
// the current session. Mirrors the advance-tick cadence (1 s under sim / 30 s in production) so the session
// timeline tracks the same `now` the schedule does.
private const val ACTIVE_SESSION_BEAT_MILLIS_SIM: Long = 1_000
private const val ACTIVE_SESSION_BEAT_MILLIS_PROD: Long = 30L * 1_000

// PRD §15: coalesce the current session's growing `end` into at most one remote push per this REAL interval
// (a finalize or a brand-new session pushes immediately regardless). Keeps writes to ~one per minute per
// device while active — the debounce the request calls for.
private const val ACTIVE_SESSION_PUSH_INTERVAL_MILLIS: Long = 60L * 1_000

// PRD §15: re-derive the account-wide pauses from the server on this REAL cadence (plus on the event triggers:
// startup, session finalize, debug pause, manual fetch, ~1 min before a pose), so a peer's newly reported
// activity and this device's own growing session surface without hammering the RPC.
private const val PAUSE_REFRESH_INTERVAL_MILLIS: Long = 5L * 60 * 1_000

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
    // PRD §15 cross-device presence: the heartbeat/peer-query channel (the sync engine); null disables it.
    private val presence: PresenceGateway? = null,
    // PRD §15: what kind of device this is — only the phone speaks the "pause finished" cue. Injectable for tests.
    private val deviceKind: DeviceKind = currentDeviceKind(),
    // PRD §15: whether this device's screen is active right now. Injectable for tests.
    private val screenActive: () -> Boolean = ::isScreenActive,
    // PRD §15: the voice sink (defaults to the platform TTS); injectable so cues are assertable in tests.
    private val speak: (String) -> Unit = ::platformSpeak,
    // PRD §15 device-sleep gaps: local store for the exact pause intervals; null disables gap recording/pull.
    private val sleepGapStore: DeviceSleepGapStore? = null,
    // PRD §15 device-sleep gaps: the push/pull channel (the sync engine); null disables remote gap sync.
    private val sleepGaps: SleepGapGateway? = null,
    // PRD §15: the OS sleep/wake-log query (defaults to the platform reader); injectable for tests.
    private val sleepGapQuery: (Long) -> List<DeviceSleepGap> = ::platformRecentSleepGaps,
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

    // PRD §15 server-derived pauses: this device's currently-open active session (null while inactive), and the
    // real-time bookkeeping to rate-limit the remote push. Mutated only under [activeSessionMutex] because the
    // heartbeat loop and the debug carve ([recordInactivityGap]) both advance it.
    private var currentSession: ActiveSessionRecord? = null
    private var lastSessionPushRealMillis: Long = 0
    private val activeSessionMutex = Mutex()

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
        launchPosePresenceBeacon()
        // The in-app cue is the delivery path only where the OS-scheduled alarm isn't wired; otherwise the
        // alarm ([onPauseCueFire]) speaks, so running both would double-speak.
        if (!localPauseCueDelivery) launchPoseFinishVoiceCue()
        launchPauseCueSchedule()
        // PRD §15: on startup, publish this device's own recent OS-recorded sleeps as synced gaps so peers can
        // inherit the rests, then pull every device's exact pause gaps into the local DB (still seeds the rest
        // poses; no longer the source of the calendar's "Inactivity" bands — see [refreshDerivedPauses]).
        backfillSleepGaps()
        pullSleepGaps()
        // PRD §15 server-derived pauses: track this device's active sessions (heartbeat) and surface the
        // account-wide pauses the server derives from every device's activity as the "Inactivity" bands.
        launchActiveSessionTracking()
        launchPauseRefresh()
        refreshDerivedPauses()
        // PRD §15 / ARCHITECTURE.md §8: requirement #6 — a phone's startup becomes the account's last phone,
        // which pushes `cancel` to the previous phone. Piggybacks the ViewModel's startup reconcile.
        claimLastPhoneOnStartup()
    }

    // PRD §9: the single "time has advanced to `now`" step — see the original `advanceTo` in App.kt.
    private fun advanceTo(now: Long) {
        _nowMillis.value = now
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
     * PRD §12: a gap in time `[sleepStart, sleepEnd]` — the process was suspended (real device sleep) or a
     * debug leap jumped the clock over it. Public so the debug "simulate pause" control can feed a gap through
     * the same path a real device sleep uses.
     */
    fun reportTimeGap(sleepStart: Long, sleepEnd: Long) {
        vm.dispatch(SchedulerIntent.ReportDeviceSleep(sleepStart, sleepEnd))
        advanceTo(sleepEnd)
    }

    // PRD §9: the advance tick + PRD §12 device-sleep detection (real-time gap → inject a hole).
    private fun launchAdvanceTick() = scope.launch {
        val interval: Long = if (DebugFlags.TIME_SIMULATION) 1_000 else 30_000
        var lastRealTick = SystemAppClock.nowMillis()
        var lastClockTick = clock.nowMillis()
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
            } else {
                advanceTo(now)
            }
            lastRealTick = realNow
            lastClockTick = now
            delay(interval)
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

    // PRD §15: heartbeat this device's activity. Each beat samples `screenActive()` and either opens, extends,
    // or finalizes the current session, persisting it locally and (debounced) pushing it. `suspended` is judged
    // from REAL elapsed time between beats — NOT the (possibly accelerated) clock — so a real process suspension
    // ends the session at its pre-sleep end and the post-wake session opens after the gap, exactly like the
    // §12 device-sleep detection; a fast sim tick just extends the session across the leaped clock time.
    private fun launchActiveSessionTracking() = scope.launch {
        val interval = if (DebugFlags.TIME_SIMULATION) ACTIVE_SESSION_BEAT_MILLIS_SIM else ACTIVE_SESSION_BEAT_MILLIS_PROD
        var lastRealBeat = SystemAppClock.nowMillis()
        while (true) {
            val realNow = SystemAppClock.nowMillis()
            val suspended = realNow - lastRealBeat > DEVICE_SLEEP_THRESHOLD_MILLIS
            advanceActiveSession(clock.nowMillis(), screenActive(), suspended)
            lastRealBeat = realNow
            delay(interval)
        }
    }

    // PRD §15: advance the current active session to `now`. A suspension or an inactive screen finalizes it; an
    // active screen opens a new session (if none / after a finalize) or extends the open one. Serialized under
    // [activeSessionMutex] because the heartbeat and the debug carve both call it.
    private suspend fun advanceActiveSession(now: Long, active: Boolean, suspended: Boolean) {
        activeSessionMutex.withLock {
            val open = currentSession
            if ((suspended || !active) && open != null) {
                finalizeSessionLocked(open)
                currentSession = null
            }
            if (!active) return
            val cur = currentSession
            val realNow = SystemAppClock.nowMillis()
            if (cur == null) {
                val fresh = ActiveSessionRecord(activeSessionDeviceId, now, now, realNow)
                currentSession = fresh
                persistSessionLocked(fresh)
                pushSessions(listOf(fresh))
                lastSessionPushRealMillis = realNow
                // Becoming active again closes the preceding inactive window into an interior gap — the moment a
                // pause becomes derivable — so re-derive the bands now rather than waiting for the periodic tick.
                refreshDerivedPauses()
            } else {
                val extended = cur.copy(endMillis = maxOf(cur.endMillis, now), updatedAtMillis = realNow)
                currentSession = extended
                persistSessionLocked(extended)
                if (realNow - lastSessionPushRealMillis >= ACTIVE_SESSION_PUSH_INTERVAL_MILLIS) {
                    pushSessions(listOf(extended))
                    lastSessionPushRealMillis = realNow
                }
            }
        }
    }

    // Finalize a session: persist its final bounds and push it immediately (so its end is durable even if the
    // app closes right after). Caller holds [activeSessionMutex].
    private fun finalizeSessionLocked(session: ActiveSessionRecord) {
        persistSessionLocked(session)
        pushSessions(listOf(session))
        lastSessionPushRealMillis = SystemAppClock.nowMillis()
    }

    private fun persistSessionLocked(session: ActiveSessionRecord) {
        activeSessionStore?.saveActiveSessions(listOf(session))
    }

    private fun pushSessions(records: List<ActiveSessionRecord>) {
        val gateway = activeSessions ?: return
        scope.launch { withContext(Dispatchers.Default) { runCatching { gateway.pushActiveSessions(records) } } }
    }

    /**
     * PRD §15 / §16: carve a pause `[startMillis, endMillis]` into THIS device's active timeline — finalize the
     * open session at [startMillis] and open a fresh one at [endMillis] — so the derivation yields the pause the
     * same way a real device sleep would (a real sleep produces this hole naturally; a debug "simulate pause"
     * leaps the clock, which the heartbeat can't see as a suspension, so this reproduces it). Then re-derive the
     * bands. Public for the debug "simulate pause" control.
     */
    fun recordInactivityGap(startMillis: Long, endMillis: Long) {
        if (endMillis <= startMillis) return
        scope.launch {
            activeSessionMutex.withLock {
                val realNow = SystemAppClock.nowMillis()
                val open = currentSession
                if (open != null && open.startMillis < startMillis) {
                    finalizeSessionLocked(open.copy(endMillis = startMillis, updatedAtMillis = realNow))
                }
                val reopened = ActiveSessionRecord(activeSessionDeviceId, endMillis, endMillis, realNow)
                currentSession = reopened
                persistSessionLocked(reopened)
                pushSessions(listOf(reopened))
                lastSessionPushRealMillis = realNow
            }
            refreshDerivedPauses()
        }
    }

    // PRD §15: re-derive the account-wide pauses on a periodic REAL cadence so a peer's newly reported activity
    // and this device's own growing current session surface into the bands without an event trigger.
    private fun launchPauseRefresh() = scope.launch {
        while (true) {
            delay(PAUSE_REFRESH_INTERVAL_MILLIS)
            refreshDerivedPauses()
        }
    }

    /**
     * PRD §15: refresh the calendar's "Inactivity" bands from the account-wide pauses. When signed in the
     * SERVER is authoritative (the `derive_pauses` RPC over every device's active sessions). If the RPC is
     * unavailable — a transport blip, or the migration not yet deployed — OR the account is signed out, it falls
     * back to deriving from THIS device's own stored sessions, so the past still fills instead of showing
     * nothing (for a single device the local answer equals the server's anyway). The freshly derived pauses
     * also seed the §15 rest poses (advancing `lastRestMillis` only) — the account-wide-pause signal reaching a
     * device that never slept.
     */
    private fun refreshDerivedPauses() {
        scope.launch {
            val until = clock.nowMillis()
            val since = until - PAUSE_DERIVE_HORIZON_MILLIS
            val gateway = activeSessions
            val fromServer =
                if (gateway?.signedIn == true) {
                    withContext(Dispatchers.Default) { runCatching { gateway.fetchDerivedPauses(since, until) }.getOrNull() }
                } else {
                    null
                }
            val pauses = fromServer ?: withContext(Dispatchers.Default) { localDerivedPauses(since, until) }
            _inactivityGaps.value = pauses
            val before = vm.state.value.sideTasks
            applySeededSideTasks(SchedulerDomain.seedSideTasksFromGaps(before, pauses))
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
            val pollInterval: Long = if (DebugFlags.TIME_SIMULATION) 1_000 else 30_000
            while (clock.nowMillis() < target) {
                delay(pollInterval)
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
                                if (voice) speak("Look 20 feet away")
                            }
                        }

                    pendingEnds.filter { it <= simNow }.sorted().forEach { end ->
                        pendingEnds = pendingEnds - end
                        if (end >= simNow - LOOK_AWAY_SWEEP_CAP_MILLIS && voice) speak("Resume your work")
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
            if (voice) speak("Look 20 feet away")
            val resumeAt = clock.nowMillis() + lookAway.durationMillis
            while (clock.nowMillis() < resumeAt) {
                val speed = (clock as? SimAppClock)?.speed ?: 1.0
                val remainingReal =
                    if (speed > 0.0) ((resumeAt - clock.nowMillis()).toDouble() / speed).toLong() else Long.MAX_VALUE
                delay(remainingReal.coerceIn(1L, LOOK_AWAY_RESUME_POLL_MILLIS))
            }
            if (voice) speak("Resume your work")
        }
    }

    // PRD §15 cross-device presence: announce this device's active screen ONLY while the now-line is inside a
    // 5/15-min rest pose — the only window the phone's "pause finished" cue needs to know whether another
    // device is in use (so an idle/working session writes nothing outside poses, staying well within the free
    // tier). Keyed on the boolean "now is in a rest pose" so re-placing the pose at the now-line (the user
    // working through it) does NOT reset the loop. First ping is one interval in, so a user who closes the
    // machine to take the pause never announces; thereafter one per interval while still in the pose with the
    // screen on. A closed/asleep machine's coroutine isn't running, so it simply stops announcing.
    private fun launchPosePresenceBeacon() = scope.launch {
        val gateway = presence ?: return@launch
        combine(_nowMillis, vm.state) { now, st -> inRestPose(st, now) }
            .distinctUntilChanged()
            .collectLatest { inPose ->
                if (!inPose) return@collectLatest
                while (true) {
                    delay(POSE_BEACON_INTERVAL_MILLIS)
                    if (!inRestPose(vm.state.value, clock.nowMillis())) break
                    if (gateway.signedIn && screenActive()) {
                        withContext(Dispatchers.Default) {
                            runCatching { gateway.publishPresence(deviceKind, screenActive = true) }
                        }
                    }
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
            if (screenActive()) return@launch
            // PRD §15: trigger #3 — ~1 min before the pose ends, also pull the latest exact gaps and re-derive
            // the account-wide pauses so the local DB / bands reflect any pause another device just recorded.
            pullSleepGaps()
            refreshDerivedPauses()
            val peersActive = readPeersActiveWithRetry(gateway, endMillis)
            if (!poseFinishEligible(isPhone = deviceKind == DeviceKind.Phone, signedIn = gateway.signedIn,
                    screenActive = screenActive(), peersActive = peersActive)) {
                return@launch
            }
            sleepUntilSim(endMillis)
            // Re-check the local screen: the user may have picked up the phone during the final minute.
            if (screenActive()) return@launch
            speak("Your pause is over. You can resume your work.")
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
    // EXACT interval(s) of the pause that was just missed and record them into the synced gaps table (local
    // store + remote push). Best-effort: an unsupported platform / failed query returns nothing and the
    // coarse tick-gap hole already kept the schedule correct. Idempotent — the store/remote upsert keys on
    // (deviceId, sleepStart), so re-recording the same interval (or backfilling earlier ones) is harmless.
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
            withContext(Dispatchers.Default) { runCatching { sleepGaps?.pushSleepGaps(records) } }
        }
    }

    // PRD §15 device-sleep gaps: at launch, publish this device's recent OS-recorded sleeps into the synced gaps
    // table so a peer that never witnessed them inherits the account's rests. [recordExactSleepGaps] only covers
    // sleeps this device sees LIVE while running; a freshly-opened desktop seeds its own poses from the OS log
    // (e.g. last night's sleep) but, without this, never pushes that sleep — so a phone (no readable OS log)
    // would keep showing a rest pose pinned to the now-line the desktop doesn't have. Best-effort and idempotent
    // (upsert keys on (deviceId, sleepStart)); no-op on a platform whose OS log is empty (e.g. Android/iOS).
    private fun backfillSleepGaps() {
        if (sleepGapStore == null && sleepGaps == null) return
        scope.launch {
            val since = clock.nowMillis() - SLEEP_GAP_BACKFILL_HORIZON_MILLIS
            val gaps = withContext(Dispatchers.Default) {
                runCatching { sleepGapQuery(since) }.getOrDefault(emptyList())
            }.filter { it.endMillis > it.startMillis }
            if (gaps.isEmpty()) return@launch
            val deviceId = sleepGaps?.deviceId ?: LOCAL_DEVICE_ID
            val recordedAt = SystemAppClock.nowMillis()
            val records = gaps.map { SleepGapRecord(deviceId, it.startMillis, it.endMillis, recordedAt) }
            sleepGapStore?.saveSleepGaps(records)
            withContext(Dispatchers.Default) { runCatching { sleepGaps?.pushSleepGaps(records) } }
            reseedSideTasksFromGaps()
        }
    }

    /**
     * PRD §15: trigger #2 (manual button) — pull every device's exact pause gaps AND re-derive the account-wide
     * pauses (the "Fetch from server" control). Public so a "fetch now" control can drive it; the gap pull also
     * runs at startup and ~1 min before a pose ends. No-op parts when sync/gap storage is disabled or signed
     * out; a transport failure is swallowed so the next trigger retries.
     */
    fun fetchRemoteGapsNow() {
        pullSleepGaps()
        refreshDerivedPauses()
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

    // PRD §15 / ARCHITECTURE.md §8: publish the account's next pause-end cue instant whenever it changes — on
    // ANY device, because the desktop's write is exactly what the server pushes the phone from ~1 min before
    // (requirement #2). On a phone, ALSO (re)schedule the local OS cue at that instant and cancel the previous,
    // so a phone-originated change is honored with no server round-trip (requirement #5). A no-pose horizon
    // clears the schedule and cancels the local cue. The publish is a tiny per-row upsert, deliberately NOT on
    // the 60-s whole-snapshot throttle.
    private fun launchPauseCueSchedule() = scope.launch {
        val gateway = pauseCue ?: return@launch
        combine(_nowMillis, vm.state.map { it.panels }.distinctUntilChanged()) { now, panels ->
            nextRestPoseEndMillis(panels, vm.state.value.sideTasks, now)
        }
            .distinctUntilChanged()
            .collectLatest { nextEnd ->
                if (deviceKind == DeviceKind.Phone) scheduleLocalPauseCue(nextEnd)
                withContext(Dispatchers.Default) {
                    runCatching {
                        if (nextEnd != null) gateway.publishPauseCueSchedule(nextEnd) else gateway.clearPauseCueSchedule()
                    }
                }
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
                screenActive = screenActive(),
                peersActive = peersActive,
            )
        ) {
            speak("Your pause is over. You can resume your work.")
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
    }
}
