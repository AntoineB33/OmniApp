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
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.example.project.DebugFlags
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.TaskId
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

// PRD §15: stand-in device id for gaps recorded while sync is disabled / signed out, so a local-only install
// still tags its rows with something stable (it never reaches the remote table).
private const val LOCAL_DEVICE_ID: String = "local"

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
) {
    private val _nowMillis = MutableStateFlow(clock.nowMillis())

    /** The advancing "now" (epoch millis); the UI collects this for display. */
    val nowMillis: StateFlow<Long> = _nowMillis.asStateFlow()

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
        launchPoseFinishVoiceCue()
        // PRD §15: trigger #1 — on startup, pull every device's exact pause gaps into the local DB.
        pullSleepGaps()
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

    // PRD §15: at launch, seed each side task's last-rest time from the last qualifying device sleep.
    private fun launchSideTaskSeeding() = scope.launch {
        val before = vm.state.value.sideTasks
        val restedTasks = withContext(Dispatchers.Default) {
            before.map { side ->
                if (side.durationMillis <= 0) {
                    side
                } else {
                    val lastRest = lastWakeAfterLongSleepMillis(side.durationMillis)
                    if (lastRest != null) side.copy(lastRestMillis = lastRest) else side
                }
            }
        }
        if (restedTasks != before) {
            vm.dispatch(SchedulerIntent.SetSideTasks(restedTasks))
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
            // PRD §15: trigger #3 — ~1 min before the pose ends, also pull the latest exact gaps so the
            // local DB reflects any pause another device just recorded.
            pullSleepGaps()
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

    /**
     * PRD §15: trigger #2 (manual button) — pull every device's exact pause gaps from the remote table into
     * the local DB. Public so a "fetch now" control can drive it; also called at startup and ~1 min before a
     * pose ends. No-op when sync/gap storage is disabled or signed out; a transport failure is swallowed
     * (a `null` fetch) so the next trigger retries.
     */
    fun fetchRemoteGapsNow() = pullSleepGaps()

    private fun pullSleepGaps() {
        val gateway = sleepGaps ?: return
        val store = sleepGapStore ?: return
        scope.launch {
            val remote =
                withContext(Dispatchers.Default) { runCatching { gateway.fetchSleepGaps() }.getOrNull() }
                    ?: return@launch
            val known = store.loadSleepGaps().mapTo(mutableSetOf()) { it.deviceId to it.startMillis }
            val fresh = remote.filter { (it.deviceId to it.startMillis) !in known }
            if (fresh.isNotEmpty()) store.saveSleepGaps(fresh)
        }
    }

    companion object {
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
