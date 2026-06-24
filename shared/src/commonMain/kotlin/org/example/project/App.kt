package org.example.project

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlin.math.abs
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.CellListId
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.model.TaskPanel
import org.example.project.scheduler.model.TaskTimeRange
import org.example.project.scheduler.persistence.SchedulerStore
import org.example.project.scheduler.persistence.createDefaultSchedulerStore
import org.example.project.scheduler.platform.lastWakeAfterLongSleepMillis
import org.example.project.scheduler.platform.sendSystemNotification
import org.example.project.scheduler.platform.speak
import org.example.project.scheduler.state.AppWindow
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.ui.PriorityWeightWindow
import org.example.project.scheduler.ui.TaskSchedulerScreen
import org.example.project.scheduler.ui.TaskTextWindow
import org.example.project.scheduler.ui.TaskSchedulerViewModel
import org.example.project.time.AppClock
import org.example.project.time.SimAppClock
import org.example.project.time.SystemAppClock
import org.example.project.ui.CalendarFloatingWindow
import org.example.project.ui.CalendarRecord
import org.example.project.ui.ChoresManagerWindow
import org.example.project.ui.HistoryManagerWindow
import org.example.project.ui.raiseOnPress
import org.example.project.ui.LateralMenu
import org.example.project.ui.ManualEntryEditWindow
import org.example.project.ui.PlacedRecord
import org.example.project.ui.ReminderEditWindow
import org.example.project.ui.TimeSimPanel
import org.example.project.ui.startOfWeek

enum class OmniPage(val label: String) {
    TaskScheduler("Task Scheduler"),
}

// PRD §15: the furthest back the look-away cue scan looks when the now-line advances in one step. It exceeds
// a normal accelerated tick's reach (the fastest sim speed, 300×, advances 300 s over the 1 s tick) so smooth
// fast-forward never clips a crossing, while a larger leap (manual time-leap, or waking from a long real
// device sleep) is treated as a jump that announces at most the last few minutes — not a backlog of cues.
private const val LOOK_AWAY_SWEEP_CAP_MILLIS: Long = 10L * 60 * 1_000

/** The z-stackable floating windows; the currently focused one is drawn on top (see [App]'s windowStack). */
private enum class FloatingWindow { Calendar, Reminders, History, TimeSim }

@Composable
@Preview
fun App(store: SchedulerStore? = createDefaultSchedulerStore()) {
    MaterialTheme {
        var page by remember { mutableStateOf(OmniPage.TaskScheduler) }

        // The scheduler view-model is hoisted here so the floating calendar can read the Task Tree's
        // records (PRD §8) while the Task Scheduler screen drives the same state.
        val vm: TaskSchedulerViewModel = viewModel { TaskSchedulerViewModel(store = store) }
        val schedulerState by vm.state.collectAsState()

        // PRD §5 Persistence: flush any pending debounced write when the app/composition is torn down,
        // so a change made within the debounce window survives a normal close.
        DisposableEffect(vm) {
            onDispose { vm.flush() }
        }

        // Time source: a virtual clock when the debug time-sim flag is on (so deadlines, the calendar
        // now-line and day rollovers can be exercised in seconds), else the real wall clock.
        val simClock = remember { SimAppClock() }
        val clock: AppClock = if (DebugFlags.TIME_SIMULATION) simClock else SystemAppClock
        // PRD §6: History Units are timestamped from the same clock the rest of the app reads, so under
        // time simulation their times match the (accelerated) calendar.
        SideEffect {
            SchedulerReducer.clock = clock
            // Flag changes made while the debug clock is diverged from real time (accelerated, paused or
            // leaped) so the next app start reverts them. Production (no time-sim) is never tainted.
            SchedulerReducer.debugTainting = {
                DebugFlags.TIME_SIMULATION &&
                    (simClock.speed != 1.0 ||
                        abs(simClock.nowMillis() - SystemAppClock.nowMillis()) > 1_000L)
            }
        }
        val tz = remember { TimeZone.currentSystemDefault() }

        var nowMillis by remember { mutableStateOf(clock.nowMillis()) }

        // PRD §9: the single "time has advanced to `now`" step — record any completed panel (so the next
        // panel becomes the current "task to do now") and, when a side task is due, refill the +168h
        // schedule; otherwise just advance. Shared by the real per-tick loop and the debug "simulate
        // pause" control so both drive the identical scheduling logic.
        fun advanceTo(now: Long) {
            nowMillis = now
            // PRD §15: an overdue *rest pose* must sit at the now-line and split the surrounding task
            // around it. That placement only happens in a full refill, so while a pose is overdue we refill
            // each tick (it re-splits cleanly and is a no-op when nothing moved) — this is what keeps the
            // pose tracking `now` under accelerated time. The look-away is deliberately excluded: it sits on
            // a fixed grid (its `lastRest` never updates), so refilling on it would re-place it at `now`
            // every tick and make the voice cue repeat. Otherwise we only advance (the cheaper tick): the
            // window refill is left to the §9 calculation events.
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

        // PRD §12: a gap in time of `[sleepStart, sleepEnd]` — the process was suspended (real device
        // sleep) or a debug leap jumped the clock over it. Report the sleep (cut the in-progress panel,
        // leave the hole, rest the covered side tasks), then advance to the wake instant exactly as a
        // normal tick does. The one handler both the real detector and the "simulate pause" button call,
        // so a simulated pause is just an alternate *source* of a gap, handled by identical code.
        fun onTimeGap(sleepStart: Long, sleepEnd: Long) {
            vm.dispatch(SchedulerIntent.ReportDeviceSleep(sleepStart, sleepEnd))
            advanceTo(sleepEnd)
        }

        // PRD §9: the frequent tick. Ticks every second under time simulation so accelerated time shows
        // promptly; otherwise the production 30s cadence.
        LaunchedEffect(clock) {
            val interval: Long = if (DebugFlags.TIME_SIMULATION) 1_000 else 30_000
            // PRD §12 Device sleep is detected from *real* elapsed time, not the active clock's: a tick
            // gap far larger than the cadence in wall-clock seconds means the process was suspended.
            // Using the sim clock here would misread fast-forwarded time (e.g. 300×) as constant sleep
            // and keep cutting the current panel's past portion. A debug leap advances only the sim clock
            // (no real gap), so it cannot trip this detector — the panel injects the gap via [onTimeGap].
            var lastRealTick = SystemAppClock.nowMillis()
            var lastClockTick = clock.nowMillis()
            while (true) {
                val realNow = SystemAppClock.nowMillis()
                val now = clock.nowMillis()
                if (realNow - lastRealTick > interval * 3) {
                    // Report the gap in the active clock's domain (sim time when accelerated), so the
                    // hole lands where the panel actually is on the calendar.
                    onTimeGap(lastClockTick, now)
                } else {
                    advanceTo(now)
                }
                lastRealTick = realNow
                lastClockTick = now
                delay(interval)
            }
        }

        // PRD §15: at launch, read the OS sleep history off the UI thread to seed each side task's last-rest
        // time from the last device sleep that was at least as long as that pause — so a pause the user
        // already rested away (slept through) is shown next at its due time rather than overdue at the
        // now-line. The in-session updates are handled by ReportDeviceSleep in the tick loop above.
        LaunchedEffect(Unit) {
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

        // PRD §7: while "Auto schedule" is off, the §9 calculation events that come due are deferred —
        // they coalesce into a SINGLE reschedule that fires once the switch is turned back on. Merely
        // toggling the switch (with nothing pending) must NOT reschedule. The calc events below are
        // deliberately NOT keyed on `automaticSchedule`, so flipping it does not relaunch them; instead
        // they read the live switch when they come due and either dispatch or mark this flag.
        var pendingReschedule by remember { mutableStateOf(false) }

        // PRD §9 calculation event #2 (tree change): recompute on a 1-second debounce after the task
        // tree changes — so the first task on an empty database is scheduled, editing a task's minimum
        // time reshapes the schedule, and deleting a scheduled task's cell refills around the cut.
        // Keyed on cells too (a deletion changes `cells` while the task object is briefly kept). The
        // LaunchedEffect restart-on-change cancels the prior delay, giving the debounce.
        LaunchedEffect(schedulerState.tasks, schedulerState.cells, clock) {
            delay(1_000)
            if (vm.state.value.automaticSchedule) vm.dispatch(SchedulerIntent.RefreshSchedule(clock.nowMillis()))
            else pendingReschedule = true // §7: defer until the switch is on
        }

        // PRD §9 calculation event #1 (calendar change / rolling horizon): after the panels change, the
        // next scheduling is placed 168 hours before the first moment free of task — i.e. it waits until
        // `now` reaches `firstFreeMoment − 168h`, then refills so the window stays ~168h ahead. On an empty
        // schedule the target is in the past, so it fills immediately; after a fill the target moves to
        // the new horizon and the effect re-arms. Polls the (possibly simulated) clock so accelerated
        // time is honoured. (The window stays ~168h ahead so it always covers the §9 next-168h horizon.)
        // The wait runs regardless of the switch (toggling while merely waiting is a
        // no-op); only when the event comes DUE does §7 gate it (dispatch if on, else defer).
        LaunchedEffect(schedulerState.panels, clock) {
            val target =
                SchedulerDomain.firstFreeMoment(schedulerState.panels, clock.nowMillis()) -
                    SchedulerDomain.SCHEDULE_HORIZON_MILLIS
            val pollInterval: Long = if (DebugFlags.TIME_SIMULATION) 1_000 else 30_000
            while (clock.nowMillis() < target) {
                delay(pollInterval)
            }
            if (vm.state.value.automaticSchedule) vm.dispatch(SchedulerIntent.RefreshSchedule(clock.nowMillis()))
            else pendingReschedule = true // §7: defer until the switch is on
        }

        // PRD §7: when the switch is turned on, fire the single deferred reschedule (if any §9 event
        // came due while it was off). With nothing pending this does nothing — so toggling the switch
        // off and on, by itself, never reschedules.
        LaunchedEffect(schedulerState.automaticSchedule) {
            if (schedulerState.automaticSchedule && pendingReschedule) {
                pendingReschedule = false
                vm.dispatch(SchedulerIntent.RefreshSchedule(clock.nowMillis()))
            }
        }

        // PRD §11 Notifications: whenever "the task to do now" changes to a DIFFERENT task, post a
        // system notification naming it. We track the last task we notified about (not just the
        // scheduled id) so transient id changes that resolve back to the same task don't re-notify —
        // e.g. moving the current block pins it (id → null) and the scheduler immediately re-picks the
        // same task (null → same id), which must stay silent. Clearing the schedule keeps the last
        // value so re-scheduling the same task later is still silent.
        // PRD §13 Notification: when the new task carries a schedule unit, the message also lists each
        // step's deadline, computed from the start of the task's current slot ([currentPanel] start).
        var lastNotifiedTaskId by remember { mutableStateOf<TaskId?>(null) }
        val currentPanel = SchedulerDomain.currentPanel(schedulerState, nowMillis)
        val currentTaskId = currentPanel?.taskId
        LaunchedEffect(currentTaskId) {
            val taskId = currentTaskId ?: return@LaunchedEffect
            if (taskId == lastNotifiedTaskId) return@LaunchedEffect
            val message =
                SchedulerDomain.taskSwitchNotificationMessage(
                    state = schedulerState,
                    taskId = taskId,
                    startMillis = currentPanel.startEpochMillis,
                ) { deadline -> formatClockTime(Instant.fromEpochMilliseconds(deadline).toLocalDateTime(tz)) }
                    ?: return@LaunchedEffect
            lastNotifiedTaskId = taskId
            sendSystemNotification("Task to do now", message)
        }

        // PRD §15 Notifications: a *rest pose* that becomes the current activity is notified by its title.
        // Driven from the scheduler state — NOT the calendar's display toggle — so hiding side tasks from the
        // calendar never silences them. We track the last pose title we notified (like [lastNotifiedTaskId]
        // above) rather than relying on [currentPoseTitle] being a stable key: [currentPanel] derives from two
        // separately-updated sources (`nowMillis` and `schedulerState`), and an overdue pose's panel start is
        // clamped to `now`. When `schedulerState` updates one frame before `nowMillis`, the panel start is
        // momentarily ahead of the stale `now`, so it no longer covers the now-line — [currentPoseTitle] tears
        // to null and back every tick. Keying on it alone would then re-fire indefinitely for an overdue pose
        // sliding along the now-line. The guard makes a transient null a no-op and only re-notifies on a
        // genuinely different title. The look-away is handled separately below (it does not pin to the now-line
        // and its window is too short to be reliably "current" at a tick under acceleration).
        var lastNotifiedSideTitle by remember { mutableStateOf<String?>(null) }
        val currentPoseTitle =
            currentPanel
                ?.takeIf { panel ->
                    panel.sideTask && schedulerState.sideTasks.any { it.restBreak && it.title == panel.title }
                }
                ?.title
        LaunchedEffect(currentPoseTitle) {
            val title = currentPoseTitle ?: return@LaunchedEffect
            if (title == lastNotifiedSideTitle) return@LaunchedEffect
            lastNotifiedSideTitle = title
            sendSystemNotification("Side task", title)
        }

        // PRD §15 (20s look-away): the look-away is the *cadence* side task (non-rest-break). It sits on a fixed
        // grid (see [SchedulerDomain.sideTaskNextStart]) and each occurrence has a start ("Look 20 feet away")
        // and an end 20s later ("Resume your work").
        //
        // Rather than poll the now-line each tick (whose ~20s window passes between two ticks under time
        // acceleration and is easily missed), we *schedule* every cue at the real instant the (possibly
        // accelerated) clock reaches its boundary: realDelay = (boundary − simNow) / speed. The cues therefore
        // fire at their programmed times regardless of the acceleration factor, and any that come due together
        // stack in the FIFO TTS queue (see `speak`) instead of overlapping.
        //
        // An occurrence's end is captured the moment we announce its start, because a §9 refill regenerates the
        // side panels by projecting only from `now` forward ([SchedulerDomain.sideTaskPanels]) — so once the
        // now-line passes a look-away's start, that occurrence (and its end) is pruned from [schedulerState.panels].
        // The effect re-keys each tick (and on any panel change) to pick up newly projected occurrences and clock
        // speed changes; [announcedStarts]/[pendingEnds] survive the re-key so nothing fires twice. Boundaries
        // more than [LOOK_AWAY_SWEEP_CAP_MILLIS] behind `now` (e.g. after a debug leap or a long device sleep)
        // are consumed silently rather than replayed as a backlog.
        var announcedStarts by remember { mutableStateOf(setOf<Long>()) }
        var pendingEnds by remember { mutableStateOf(setOf<Long>()) }
        LaunchedEffect(nowMillis, schedulerState.panels) {
            while (true) {
                val simNow = clock.nowMillis()
                val speed = (clock as? SimAppClock)?.speed ?: 1.0
                val voice = schedulerState.lookAwayVoiceEnabled
                // Forget long-past bookkeeping so the sets can't grow without bound over a long session;
                // such occurrences are already pruned from the projection, so they cannot reappear.
                announcedStarts = announcedStarts.filterTo(mutableSetOf()) { it >= simNow - LOOK_AWAY_SWEEP_CAP_MILLIS }

                val occurrences = schedulerState.panels.filter { panel ->
                    panel.sideTask && schedulerState.sideTasks.any { !it.restBreak && it.title == panel.title }
                }

                // Announce any start the clock has now reached, capturing its end for the resume cue.
                occurrences
                    .filter { it.startEpochMillis <= simNow && it.startEpochMillis !in announcedStarts }
                    .sortedBy { it.startEpochMillis }
                    .forEach {
                        announcedStarts = announcedStarts + it.startEpochMillis
                        pendingEnds = pendingEnds + it.endEpochMillis
                        if (it.startEpochMillis >= simNow - LOOK_AWAY_SWEEP_CAP_MILLIS) {
                            sendSystemNotification("Side task", it.title)
                            if (voice) speak("Look 20 feet away")
                        }
                    }

                // Fire (and drop) every captured end the clock has now reached.
                pendingEnds.filter { it <= simNow }.sorted().forEach { end ->
                    pendingEnds = pendingEnds - end
                    if (end >= simNow - LOOK_AWAY_SWEEP_CAP_MILLIS && voice) speak("Resume your work")
                }

                // Sleep until the next boundary's real-time instant, then loop. A re-key (tick or panel change)
                // restarts this with a fresh clock speed; a pause (speed 0) waits for that re-key.
                val nextStart = occurrences.map { it.startEpochMillis }
                    .filter { it > simNow && it !in announcedStarts }.minOrNull()
                val nextEnd = pendingEnds.filter { it > simNow }.minOrNull()
                val next = listOfNotNull(nextStart, nextEnd).minOrNull() ?: break
                if (speed <= 0.0) break
                delay(((next - simNow).toDouble() / speed).toLong().coerceAtLeast(1L))
            }
        }

        // PRD §7 calendar state, hoisted so the lateral menu (month grid) and the popup week view
        // stay in sync. "today" follows the (possibly simulated) clock so day rollovers are testable.
        val today = Instant.fromEpochMilliseconds(nowMillis).toLocalDateTime(tz).date
        var calendarOpen by remember { mutableStateOf(false) }
        // PRD §5: the sub-list whose priority-weight window is open (opened by clicking a percentage in the
        // tree), or null when closed. Drawn on the top floating-window layer below; [weightWindowBounds] is
        // its window-space rect, used to ignore presses inside it when dismissing on outside clicks.
        var weightWindowListId by remember { mutableStateOf<CellListId?>(null) }
        var weightWindowBounds by remember { mutableStateOf<Rect?>(null) }
        // PRD §13: the task whose "see text" window is open, or null when closed. Drawn on the top
        // floating-window layer below, same as the priority-weight window; [taskTextBounds] is its rect.
        var taskTextTaskId by remember { mutableStateOf<TaskId?>(null) }
        var taskTextBounds by remember { mutableStateOf<Rect?>(null) }
        // Layout of the content area, so a press position (content-local) can be mapped to window space
        // and compared against the windows' bounds.
        var contentCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
        // PRD §5: the window closes when any cell enters Edit Mode (its sub-list typing context is gone).
        LaunchedEffect(schedulerState.editSession) {
            if (schedulerState.editSession != null) weightWindowListId = null
        }
        // PRD §7/§14 Chores Manager: whether the floating chores window is open (local UI state, like the
        // calendar window; the chores data itself lives in the persisted scheduler state).
        var choresManagerOpen by remember { mutableStateOf(false) }
        // PRD §5/§6 History Manager: whether the floating history window is open (local UI state).
        var historyManagerOpen by remember { mutableStateOf(false) }

        // The floating windows are siblings in one Box, so their paint order is their declaration order.
        // To put the *currently focused* window on top of the layers, we keep an explicit stacking order
        // (last == top) and drive each window's zIndex from it. A window is raised when it is opened and on
        // every press inside it (see raiseOnPress). Unmanaged: the modal edit window (its own scrim already
        // sits above everything).
        var windowStack by remember {
            mutableStateOf(listOf(FloatingWindow.Calendar, FloatingWindow.Reminders, FloatingWindow.History, FloatingWindow.TimeSim))
        }
        fun bringWindowToFront(id: FloatingWindow) {
            if (windowStack.lastOrNull() != id) windowStack = windowStack.filterNot { it == id } + id
        }
        fun windowZ(id: FloatingWindow): Float = windowStack.indexOf(id).toFloat()
        fun isWindowOpen(id: FloatingWindow): Boolean = when (id) {
            FloatingWindow.Calendar -> calendarOpen
            FloatingWindow.Reminders -> choresManagerOpen
            FloatingWindow.History -> historyManagerOpen
            FloatingWindow.TimeSim -> DebugFlags.TIME_SIMULATION
        }
        // The focused window is the topmost open one in the stack.
        fun focusedWindow(): FloatingWindow? = windowStack.lastOrNull { isWindowOpen(it) }
        // PRD §7: the scheduler-state focus target for a floating window (null for the debug TimeSim panel,
        // which is not a navigable app window).
        fun appWindowOf(id: FloatingWindow): AppWindow? = when (id) {
            FloatingWindow.Calendar -> AppWindow.Calendar
            FloatingWindow.Reminders -> AppWindow.Reminders
            FloatingWindow.History -> AppWindow.History
            FloatingWindow.TimeSim -> null
        }
        // PRD §7 window navigation: raise [id] to the top layer AND move scheduler focus onto it, which
        // clears the tree selection, forcibly exits tree Edit Mode, and records a WindowNav history unit.
        fun focusWindow(id: FloatingWindow) {
            bringWindowToFront(id)
            appWindowOf(id)?.let { vm.dispatch(SchedulerIntent.FocusWindow(it)) }
        }
        // Lateral-menu click on a window button: open it (and focus) when closed; close it when it is the
        // focused (front) window; otherwise just bring it to focus without closing.
        fun onMenuWindowClicked(id: FloatingWindow, setOpen: (Boolean) -> Unit) {
            when {
                !isWindowOpen(id) -> {
                    setOpen(true)
                    focusWindow(id)
                }
                focusedWindow() == id -> setOpen(false)
                else -> focusWindow(id)
            }
        }

        var selectedDate by remember { mutableStateOf(today) }
        var monthAnchor by remember { mutableStateOf(LocalDate(today.year, today.month, 1)) }

        // PRD §15: side tasks are projected from now to the END OF THE FOCUSED WEEK — the week the calendar
        // window is showing ([startOfWeek] of [selectedDate], Monday-based, exclusive end = the next Monday's
        // midnight). The scheduling horizon is the floor, so the near term is unchanged and navigating to a
        // further-out week extends the side-task markers to span it. `nowMillis` is the same `now` the last
        // schedule refresh used (the tick loop sets both together), so within the schedule window this
        // reproduces the side-task panels already in [schedulerState.panels] and only adds the tail.
        val focusedWeekEndMillis =
            startOfWeek(selectedDate).plus(7, DateTimeUnit.DAY).atStartOfDayIn(tz).toEpochMilliseconds()
        val sideTaskHorizonMillis = maxOf(nowMillis + SchedulerDomain.SCHEDULE_HORIZON_MILLIS, focusedWeekEndMillis)
        val displaySidePanels =
            SchedulerDomain.sideTaskPanels(schedulerState.sideTasks, nowMillis, sideTaskHorizonMillis)

        // PRD §14: reminder flags are calculated for the WHOLE focused week — from now to the end of the
        // week the calendar is showing — so navigating to a week shows its reminders. Like the side-task
        // projection they are regenerated for display (anchored at today's midnight, out to the focused
        // week's end), with each tag's checked state carried over from the stored reminder panels by
        // matching its deterministic id.
        val todayStartMillis = today.atStartOfDayIn(tz).toEpochMilliseconds()
        val reminderHorizonDays =
            ((focusedWeekEndMillis - todayStartMillis) / (24L * 60 * 60 * 1000)).toInt().coerceAtLeast(0)
        val displayReminderPanels =
            SchedulerDomain.regenerateChorePanels(
                schedulerState.panels, schedulerState.chores, todayStartMillis, reminderHorizonDays, nowMillis,
            ).filter { SchedulerDomain.isReminder(it) }

        // Done periods (PRD §8 task record, green) plus every calendar panel (PRD §8/§9 — auto and
        // user-authored, uniform blocks) drawn the same way; reminders (PRD §14) and side tasks (PRD §15)
        // span the focused week.
        val calendarRecords =
            schedulerState.tasks.values.flatMap { task ->
                task.record.map { CalendarRecord(title = task.title, range = it, taskId = task.id) }
            } + mergePanelsForDisplay(
                schedulerState.panels, displayReminderPanels, displaySidePanels,
                schedulerState.showSideTasks, schedulerState.showReminders,
            )
        // PRD §8 edit window: the calendar block currently being edited (null = closed).
        var editingBlock by remember { mutableStateOf<PlacedRecord?>(null) }
        // PRD §8 Manual add: a not-yet-committed default panel shown in the edit window with a Save
        // button (null = not adding). Distinct from [editingBlock] so Save knows to add vs. update.
        var addingBlock by remember { mutableStateOf<PlacedRecord?>(null) }
        // PRD §14 "add a checked reminder": the right-click epoch-millis at which to open the reminder-check
        // window (null = closed).
        var addingReminderAtMillis by remember { mutableStateOf<Long?>(null) }

        // PRD §8 focus: the floating calendar window is the focused surface while it is open — so the
        // tree stops hijacking letter typing into Edit Mode and Ctrl+Z/Y route to the calendar history.
        LaunchedEffect(calendarOpen) {
            vm.dispatch(SchedulerIntent.SetCalendarFocus(calendarOpen))
        }

        // PRD §7: switching focus to another window leaves Edit Mode in any window — close the calendar's
        // edit / add-reminder surfaces so they don't linger over the newly focused window.
        LaunchedEffect(schedulerState.focusedWindow) {
            editingBlock = null
            addingReminderAtMillis = null
        }

        Box(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.background)
                .safeContentPadding()
                .fillMaxSize()
        ) {
            Row(modifier = Modifier.fillMaxSize()) {
                LateralMenu(
                    page = page,
                    onPageSelected = { page = it },
                    calendarOpen = calendarOpen,
                    onToggleCalendar = { onMenuWindowClicked(FloatingWindow.Calendar) { calendarOpen = it } },
                    monthAnchor = monthAnchor,
                    onMonthAnchorChange = { monthAnchor = it },
                    selectedDate = selectedDate,
                    today = today,
                    onSelectDate = { selectedDate = it },
                    automaticSchedule = schedulerState.automaticSchedule,
                    onToggleAutomaticSchedule = { vm.dispatch(SchedulerIntent.SetAutomaticSchedule(it)) },
                    choresManagerOpen = choresManagerOpen,
                    onToggleChoresManager = { onMenuWindowClicked(FloatingWindow.Reminders) { choresManagerOpen = it } },
                    historyManagerOpen = historyManagerOpen,
                    onToggleHistoryManager = { onMenuWindowClicked(FloatingWindow.History) { historyManagerOpen = it } },
                    lookAwayVoiceEnabled = schedulerState.lookAwayVoiceEnabled,
                    onToggleLookAwayVoice = { vm.dispatch(SchedulerIntent.SetLookAwayVoice(it)) },
                )

                // The content area is clipped so the floating calendar window can overlap the tree
                // but never spill onto the lateral menu (PRD §7).
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clipToBounds()
                        .onGloballyPositioned { contentCoords = it }
                        // PRD §5/§13: the priority-weight window and the "see text" window close when a press
                        // lands anywhere outside them, and that press still does its normal job (selecting a
                        // cell, focusing the calendar, …). We observe presses in the Initial pass without
                        // consuming them — an ancestor of every window, so it catches clicks on the tree and
                        // on other floating windows alike. A press inside a window's own bounds is ignored.
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    if (event.type != PointerEventType.Press) continue
                                    if (weightWindowListId == null && taskTextTaskId == null) continue
                                    val pos = event.changes.firstOrNull()?.position ?: continue
                                    val win = contentCoords?.localToWindow(pos) ?: continue
                                    if (weightWindowListId != null && weightWindowBounds?.contains(win) != true) {
                                        weightWindowListId = null
                                    }
                                    if (taskTextTaskId != null && taskTextBounds?.contains(win) != true) {
                                        taskTextTaskId = null
                                    }
                                }
                            }
                        },
                ) {
                    when (page) {
                        OmniPage.TaskScheduler ->
                            TaskSchedulerScreen(
                                modifier = Modifier.fillMaxSize(),
                                store = store,
                                vm = vm,
                                onSetWeightWindow = { weightWindowListId = it },
                                onSetTaskTextWindow = { taskTextTaskId = it },
                            )
                    }

                    // PRD §5: the priority-weight window, on the top floating-window layer (zIndex above the
                    // managed windows' 0..n stack, below the modal edit window's 100). Opened from a tree
                    // percentage; closed by the outside-press interceptor above.
                    weightWindowListId?.let { listId ->
                        if (schedulerState.lists[listId] == null) {
                            weightWindowListId = null
                        } else {
                            PriorityWeightWindow(
                                state = schedulerState,
                                listId = listId,
                                priorities = SchedulerDomain.absoluteTaskPriorities(schedulerState),
                                onIntent = { vm.dispatch(it) },
                                onBoundsChange = { weightWindowBounds = it },
                                modifier = Modifier.align(Alignment.Center).zIndex(50f),
                            )
                        }
                    }

                    // PRD §13: the "see text" task-text window, also on the top floating-window layer.
                    // Opened from a cell's right-click menu; auto-saves; closed by the interceptor above.
                    taskTextTaskId?.let { taskId ->
                        val task = schedulerState.tasks[taskId]
                        if (task == null) {
                            taskTextTaskId = null
                        } else {
                            TaskTextWindow(
                                taskTitle = task.title,
                                initialText = task.text,
                                onTextChange = { vm.dispatch(SchedulerIntent.SetTaskText(taskId, it)) },
                                onBoundsChange = { taskTextBounds = it },
                                modifier = Modifier.align(Alignment.Center).zIndex(50f),
                            )
                        }
                    }

                    if (calendarOpen) {
                        CalendarFloatingWindow(
                            selectedDate = selectedDate,
                            today = today,
                            nowMillis = nowMillis,
                            onDismiss = { calendarOpen = false },
                            modifier = Modifier
                                .align(Alignment.Center)
                                .zIndex(windowZ(FloatingWindow.Calendar)),
                            records = calendarRecords,
                            // PRD §8 focus: pressing in the calendar makes it the focused surface again
                            // (e.g. after a click into the tree had handed focus back) and raises it to the
                            // top of the window layers. (onFocus fires inside the window, after its offset.)
                            onFocus = { focusWindow(FloatingWindow.Calendar) },
                            // PRD §8 Manual add: open the edit window pre-filled with the default task
                            // (highest absolute priority, min-time span) and a Save button.
                            onAddTaskAt = { startMillis ->
                                val taskId = SchedulerDomain.manualAddTaskId(schedulerState)
                                val task = taskId?.let { schedulerState.tasks[it] }
                                val span = (task?.minimumMinutes?.toLong() ?: 45L) * 60_000L
                                addingBlock = PlacedRecord(
                                    title = task?.title.orEmpty(),
                                    startHour = 0f,
                                    endHour = 0f,
                                    scheduled = false,
                                    manual = true,
                                    entryId = null,
                                    taskId = taskId,
                                    pinned = true, // PRD §8: the "pin" button is on by default for a new panel
                                    fullStartMillis = startMillis,
                                    fullEndMillis = startMillis + span,
                                )
                            },
                            // PRD §14 "add reminder": open the reminder editor at the click.
                            onAddReminderAt = { atMillis -> addingReminderAtMillis = atMillis },
                            // PRD §8 (uniform blocks): committing a drag/resize updates the panel
                            // (auto blocks become user-authored), or pins a record into a panel.
                            onCommitBounds = { block, newStart, newEnd, allowOverlap ->
                                commitBoundsIntent(
                                    block, block.taskId, block.title, newStart, newEnd, block.pinned, allowOverlap,
                                )?.let(vm::dispatch)
                            },
                            onEditEntry = { block -> editingBlock = block },
                            // PRD §8 task contextual menu "Remove": delete the block by its source.
                            onRemoveEntry = { block -> removeBlockIntent(block)?.let(vm::dispatch) },
                            // PRD §14 Reminders: clicking a reminder tag toggles its checked (done) state.
                            onToggleReminder = { block ->
                                block.entryId?.let { vm.dispatch(SchedulerIntent.SetReminderChecked(it, !block.checked, nowMillis)) }
                            },
                            // PRD §8 Overlap Mode: commit re-divided panel widths from a dragged edge.
                            onAdjustWeights = { weights ->
                                if (weights.isNotEmpty()) vm.dispatch(SchedulerIntent.SetPanelWeights(weights))
                            },
                            overlapArmed = schedulerState.overlapArmed,
                            onToggleOverlap = { vm.dispatch(SchedulerIntent.ToggleCalendarOverlap) },
                            // PRD §14/§15: the calendar's "Reminders" / "Side tasks" display switches (cosmetic;
                            // notifications stay on).
                            showSideTasks = schedulerState.showSideTasks,
                            onToggleSideTasks = { vm.dispatch(SchedulerIntent.SetShowSideTasks(it)) },
                            showReminders = schedulerState.showReminders,
                            onToggleReminders = { vm.dispatch(SchedulerIntent.SetShowReminders(it)) },
                            onUndo = { vm.dispatch(SchedulerIntent.Undo) },
                            onRedo = { vm.dispatch(SchedulerIntent.Redo) },
                        )

                        // PRD §8 edit window, drawn over the calendar window and the tree — used for
                        // both editing an existing block and the Manual-add default panel. It is a modal
                        // (full-screen scrim), so it is pinned above every floating window's z-layer.
                        (editingBlock ?: addingBlock)?.let { block ->
                            val isNew = editingBlock == null
                            Box(Modifier.fillMaxSize().zIndex(100f)) {
                            ManualEntryEditWindow(
                                initialTitle = block.title,
                                initialTaskId = block.taskId,
                                startMillis = block.fullStartMillis,
                                endMillis = block.fullEndMillis,
                                tz = tz,
                                taskMenuEntries = { draft, exclude ->
                                    SchedulerDomain.calendarTaskMenuEntries(schedulerState, draft, exclude)
                                },
                                titleSuggestions = { SchedulerDomain.calendarTitleSuggestions(schedulerState, it) },
                                taskIdForTitle = { SchedulerDomain.calendarTaskIdForTitle(schedulerState, it) },
                                titleForTaskId = { schedulerState.tasks[it]?.title },
                                initialPinned = block.pinned,
                                onDismiss = { editingBlock = null; addingBlock = null },
                                onSave = { taskId, title, startMillis, endMillis, pinned ->
                                    val intent =
                                        if (isNew) {
                                            SchedulerIntent.AddTaskPanel(taskId, title, startMillis, endMillis, pinned)
                                        } else {
                                            commitBoundsIntent(block, taskId, title, startMillis, endMillis, pinned)
                                        }
                                    intent?.let(vm::dispatch)
                                    editingBlock = null
                                    addingBlock = null
                                },
                            )
                            }
                        }

                        // PRD §14 "add reminder": the floating reminder editor, above every floating window
                        // (same z-layer as the manual edit window).
                        addingReminderAtMillis?.let { atMillis ->
                            Box(Modifier.fillMaxSize().zIndex(100f)) {
                                ReminderEditWindow(
                                    initialMillis = atMillis,
                                    tz = tz,
                                    reminderMenuEntries = { SchedulerDomain.reminderMenuEntries(schedulerState, it) },
                                    titleSuggestions = { SchedulerDomain.reminderTitleSuggestions(schedulerState, it) },
                                    reminderIdForTitle = { SchedulerDomain.reminderIdForTitle(schedulerState, it) },
                                    titleForReminderId = { SchedulerDomain.reminderTitleForId(schedulerState, it) },
                                    onDismiss = { addingReminderAtMillis = null },
                                    onSave = { reminderId, title, at, checked, pinned ->
                                        vm.dispatch(SchedulerIntent.AddReminder(reminderId, title, at, checked, pinned))
                                        addingReminderAtMillis = null
                                    },
                                )
                            }
                        }
                    }

                    // PRD §14 Chores Manager: floating window over the tree (not the lateral menu).
                    if (choresManagerOpen) {
                        // PRD §14: anchor the chore scheduler at local midnight of today, in the user's tz.
                        val todayStartMillis = today.atStartOfDayIn(tz).toEpochMilliseconds()
                        ChoresManagerWindow(
                            chores = schedulerState.chores,
                            // PRD §14: pass `now` too so a reminder with no time-of-day lands at the current time.
                            onChange = { vm.dispatch(SchedulerIntent.SetChores(it, todayStartMillis, nowMillis)) },
                            onDismiss = { choresManagerOpen = false },
                            // PRD §14: pre-fill a newly added reminder's Time field with the clock time at the click.
                            newRowTimeOfDayMinutes = {
                                val t = Instant.fromEpochMilliseconds(clock.nowMillis()).toLocalDateTime(tz)
                                t.hour * 60 + t.minute
                            },
                            // PRD §14: title/id suggestion menus under the focused reminder name field —
                            // existing reminders matching the draft, and distinct reminder titles.
                            reminderMenuEntries = { SchedulerDomain.reminderMenuEntries(schedulerState, it) },
                            titleSuggestions = { SchedulerDomain.reminderTitleSuggestions(schedulerState, it) },
                            // A new row's id must avoid every known reminder id (including calendar-only ones).
                            knownReminderIds = { SchedulerDomain.allReminderEntries(schedulerState).mapTo(mutableSetOf()) { it.id } },
                            // PRD §14: reminder ids kept alive by a checked or pinned tag — the focused row
                            // shows its own id in the menu only when it is one of these (independently referenced).
                            referencedReminderIds = { SchedulerDomain.referencedReminderIds(schedulerState) },
                            // PRD §14 "constrained in": resolve a reminder name ↔ id for the constraint picker.
                            reminderIdForTitle = { SchedulerDomain.reminderIdForTitle(schedulerState, it) },
                            titleForReminderId = { SchedulerDomain.reminderTitleForId(schedulerState, it) },
                            // Cascade: open up-left of center so it isn't fully hidden behind a wider window.
                            initialOffset = Offset(-200f, -150f),
                            onRaise = { focusWindow(FloatingWindow.Reminders) },
                            modifier = Modifier
                                .align(Alignment.Center)
                                .zIndex(windowZ(FloatingWindow.Reminders)),
                        )
                    }

                    // PRD §5/§6 History Manager: floating window listing every category's history units.
                    if (historyManagerOpen) {
                        HistoryManagerWindow(
                            histories = schedulerState.histories,
                            onDismiss = { historyManagerOpen = false },
                            // Cascade: open down-right of center so the Reminders / calendar windows stay reachable.
                            initialOffset = Offset(200f, 150f),
                            onRaise = { focusWindow(FloatingWindow.History) },
                            modifier = Modifier
                                .align(Alignment.Center)
                                .zIndex(windowZ(FloatingWindow.History)),
                        )
                    }

                    // Debug-only time-acceleration control (gated by DebugFlags.TIME_SIMULATION).
                    if (DebugFlags.TIME_SIMULATION) {
                        TimeSimPanel(
                            clock = simClock,
                            nowMillis = nowMillis,
                            // Debug: simulate taking a pause — leap virtual time over it, then feed the gap
                            // through the same [onTimeGap] handler a real device sleep uses, so the side-task
                            // rhythm and schedule resume by the exact same logic as a real ≥duration pause.
                            onSimulatePause = { durationMillis ->
                                val sleepStart = clock.nowMillis()
                                simClock.leap(durationMillis)
                                onTimeGap(sleepStart, clock.nowMillis())
                            },
                            pendingRollback = schedulerState.histories.hasPendingDebugRollback,
                            modifier = Modifier
                                // Bottom-left of the content area — just to the right of the lateral menu.
                                .align(Alignment.BottomStart)
                                .padding(12.dp)
                                .zIndex(windowZ(FloatingWindow.TimeSim))
                                .raiseOnPress { focusWindow(FloatingWindow.TimeSim) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * PRD §8 (uniform blocks): the intent that commits new bounds/title/pinned for any calendar [block].
 * A panel (it has an [PlacedRecord.entryId]) is updated in place; a green task-record block is pinned
 * into a new panel. Returns null when the block has no usable identity (defensive).
 */
private fun commitBoundsIntent(
    block: PlacedRecord,
    taskId: TaskId?,
    title: String,
    startMillis: Long,
    endMillis: Long,
    pinned: Boolean,
    allowOverlap: Boolean = false,
): SchedulerIntent? = when {
    // A merged block (several same-task panels shown as one): replace the whole group with one panel.
    block.entryIds.size > 1 ->
        SchedulerIntent.ReplaceTaskPanels(block.entryIds, taskId, title, startMillis, endMillis, pinned, allowOverlap)
    block.entryId != null ->
        SchedulerIntent.UpdateTaskPanel(block.entryId, taskId, title, startMillis, endMillis, pinned, allowOverlap)
    block.taskId != null ->
        SchedulerIntent.PinRecordAsPanel(
            recordTaskId = block.taskId,
            recordStartEpochMillis = block.fullStartMillis,
            recordEndEpochMillis = block.fullEndMillis,
            taskId = taskId,
            title = title,
            startEpochMillis = startMillis,
            endEpochMillis = endMillis,
            pinned = pinned,
            allowOverlap = allowOverlap,
        )
    else -> null
}

/**
 * PRD §8 task contextual menu "Remove": the intent that deletes a calendar [block] from its source —
 * a panel is removed, a green task-record period is dropped from the task record. Returns null when
 * the block has no removable identity (defensive).
 */
private fun removeBlockIntent(block: PlacedRecord): SchedulerIntent? = when {
    block.entryIds.size > 1 -> SchedulerIntent.RemoveTaskPanels(block.entryIds)
    block.entryId != null -> SchedulerIntent.RemoveTaskPanel(block.entryId)
    block.taskId != null ->
        SchedulerIntent.RemoveRecordPeriod(block.taskId, block.fullStartMillis, block.fullEndMillis)
    else -> null
}

/**
 * PRD §8 same-task merge (display): collapse the schedulable [panels] into the blocks the calendar
 * shows — consecutive panels of the same (non-null) task with the same pin state, that touch or
 * overlap, render as one block spanning the run. Each block carries every backing panel id (see
 * [CalendarRecord.entryIds]) so an edit/drag/resize/remove acts on the whole group. The underlying
 * panels stay separate in state — auto panels are distinct scheduling sessions the reschedule must be
 * able to reshape — so this fusing is purely visual. A null-task ("New task") panel never merges.
 */
/** PRD §13: a compact `HH:MM` label for a schedule-unit step deadline in the task-switch notification. */
private fun formatClockTime(dateTime: LocalDateTime): String {
    val hh = dateTime.hour.toString().padStart(2, '0')
    val mm = dateTime.minute.toString().padStart(2, '0')
    return "$hh:$mm"
}

private fun mergePanelsForDisplay(
    panels: List<TaskPanel>,
    reminderPanels: List<TaskPanel>,
    sidePanels: List<TaskPanel>,
    showSideTasks: Boolean,
    showReminders: Boolean,
): List<CalendarRecord> {
    // PRD §14/§15: reminder tags (zero-duration) and side tasks (very short real durations, e.g. a 20-second
    // look-away) are NOT height-proportional blocks — drawn at scale they'd be invisible. They render on
    // their own fixed-height marker paths (CalendarRecord.reminder / .sideTask) and never merge with panels.
    // PRD §14/§15: reminder tags ([reminderPanels]) and side tasks ([sidePanels]) are both projected across
    // the focused week (which may run past the schedule's fixed obstacle window in [panels]) — not taken from
    // [panels]; the regular `blocks` still come from [panels]. Within the schedule window the projections are
    // identical (same `now`, same chores/side tasks), so the blocks stay split around the side tasks exactly
    // as scheduled and the checked state of each reminder is carried over by matching its deterministic id.
    val reminders = if (showReminders) reminderPanels else emptyList()
    val sides = sidePanels
    val blocks = panels.filter { !SchedulerDomain.isReminder(it) && !it.sideTask }
    val reminderRecords =
        reminders.map { tag ->
            CalendarRecord(
                title = tag.title,
                range = TaskTimeRange(tag.startEpochMillis, tag.endEpochMillis),
                entryId = tag.id,
                entryIds = listOf(tag.id),
                reminder = true,
                checked = tag.checked,
                checkedAtMillis = tag.checkedAtMillis,
            )
        }
    // PRD §15 toggle: when side tasks are hidden, draw none, and let same-task panels separated only by a
    // (now-hidden) side task fuse into one block (cosmetic — the panels and the schedule are untouched).
    val sideRecords =
        if (!showSideTasks) {
            emptyList()
        } else {
            sides.map { side ->
                CalendarRecord(
                    title = side.title,
                    range = TaskTimeRange(side.startEpochMillis, side.endEpochMillis),
                    entryId = side.id,
                    entryIds = listOf(side.id),
                    sideTask = true,
                )
            }
        }
    // PRD §15: when side tasks are hidden, fuse same-task panels across the gaps the (now-hidden) side-task
    // pauses left — structurally, so the fused block doesn't flicker as `now` advances (a moving side-task
    // projection would keep drifting out of alignment with the already-scheduled gaps).
    val blockRecords =
        SchedulerDomain.groupSameTaskPanelsForDisplay(blocks, bridgeGaps = !showSideTasks).map { group ->
            val head = group.first()
            CalendarRecord(
                title = head.title,
                range = TaskTimeRange(head.startEpochMillis, group.maxOf { it.endEpochMillis }),
                manual = true,
                entryId = head.id,
                entryIds = group.map { it.id },
                taskId = head.taskId,
                pinned = head.pinned,
                layoutWeight = head.layoutWeight,
            )
        }
    return blockRecords + reminderRecords + sideRecords
}
