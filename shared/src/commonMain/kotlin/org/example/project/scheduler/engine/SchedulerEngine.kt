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
import org.example.project.scheduler.platform.DeviceKind
import org.example.project.scheduler.platform.currentDeviceKind
import org.example.project.scheduler.platform.isScreenActive
import org.example.project.scheduler.platform.sendSystemNotification
import org.example.project.scheduler.platform.lastWakeAfterLongSleepMillis
import org.example.project.scheduler.platform.speak as platformSpeak
import org.example.project.scheduler.platform.stopSpeaking
import org.example.project.scheduler.sync.PresenceGateway
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

// PRD §15 cross-device presence: how often this device republishes its "active screen" heartbeat (real time).
private const val PRESENCE_HEARTBEAT_MILLIS: Long = 30L * 1_000

// PRD §15: a peer heartbeat older than this (real time) no longer counts as an active screen — it covers a
// device that slept/crashed without posting screen_active=false. Matches [DEVICE_SLEEP_THRESHOLD_MILLIS].
private const val PRESENCE_STALE_MILLIS: Long = 90L * 1_000

// PRD §15: real-time cap on each sleep while waiting for a 5/15-min pose to end before the "pause finished"
// voice cue, so a mid-wait sim-speed change is picked up promptly (cf. [LOOK_AWAY_RESUME_POLL_MILLIS]).
private const val POSE_FINISH_POLL_MILLIS: Long = 1_000

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
        launchPresenceHeartbeat()
        launchPoseFinishVoiceCue()
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
                reportTimeGap(lastClockTick, now)
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

    // PRD §15 cross-device presence: republish this device's "active screen" heartbeat on a fixed real-time
    // cadence so peers always have a fresh row to read when a pose starts. No-op when sync is disabled.
    private fun launchPresenceHeartbeat() = scope.launch {
        val gateway = presence ?: return@launch
        while (true) {
            if (gateway.signedIn) {
                withContext(Dispatchers.Default) { runCatching { gateway.publishPresence(deviceKind, screenActive()) } }
            }
            delay(PRESENCE_HEARTBEAT_MILLIS)
        }
    }

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

    // PRD §15: gate evaluated at a pose's start; if it holds, wait until [endMillis] and announce the pause is
    // over. Launched on [scope] (not the collectLatest job) so a panel/now re-key never cancels a committed
    // cue. The eligibility query is at the start (per spec); the wait then runs to the original end.
    private fun schedulePoseFinishCue(gateway: PresenceGateway, endMillis: Long) {
        scope.launch {
            if (!gateway.signedIn || screenActive()) return@launch
            val peersActive = withContext(Dispatchers.Default) { gateway.activePeersExist(PRESENCE_STALE_MILLIS) }
            if (!poseFinishEligible(isPhone = deviceKind == DeviceKind.Phone, signedIn = gateway.signedIn,
                    screenActive = screenActive(), peersActive = peersActive)) {
                return@launch
            }
            while (clock.nowMillis() < endMillis) {
                val speed = (clock as? SimAppClock)?.speed ?: 1.0
                if (speed <= 0.0) {
                    delay(POSE_FINISH_POLL_MILLIS)
                    continue
                }
                val remainingReal = ((endMillis - clock.nowMillis()).toDouble() / speed).toLong()
                delay(remainingReal.coerceIn(1L, POSE_FINISH_POLL_MILLIS))
            }
            speak("Your pause is over. You can resume your work.")
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
