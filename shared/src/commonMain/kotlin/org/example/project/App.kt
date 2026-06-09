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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlin.time.Instant
import kotlinx.coroutines.delay
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.model.TaskPanel
import org.example.project.scheduler.model.TaskTimeRange
import org.example.project.scheduler.persistence.SchedulerStore
import org.example.project.scheduler.persistence.createDefaultSchedulerStore
import org.example.project.scheduler.platform.sendSystemNotification
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.ui.TaskSchedulerScreen
import org.example.project.scheduler.ui.TaskSchedulerViewModel
import org.example.project.time.AppClock
import org.example.project.time.SimAppClock
import org.example.project.time.SystemAppClock
import org.example.project.ui.CalendarFloatingWindow
import org.example.project.ui.CalendarRecord
import org.example.project.ui.ChoresManagerWindow
import org.example.project.ui.LateralMenu
import org.example.project.ui.ManualEntryEditWindow
import org.example.project.ui.PlacedRecord
import org.example.project.ui.TimeSimPanel

enum class OmniPage(val label: String) {
    TaskScheduler("Task Scheduler"),
}

@Composable
@Preview
fun App(store: SchedulerStore? = createDefaultSchedulerStore()) {
    MaterialTheme {
        var page by remember { mutableStateOf(OmniPage.TaskScheduler) }

        // The scheduler view-model is hoisted here so the floating calendar can read the Task Tree's
        // records (PRD §8) while the Task Scheduler screen drives the same state.
        val vm: TaskSchedulerViewModel = viewModel { TaskSchedulerViewModel(store = store) }
        val schedulerState by vm.state.collectAsState()

        // Time source: a virtual clock when the debug time-sim flag is on (so deadlines, the calendar
        // now-line and day rollovers can be exercised in seconds), else the real wall clock.
        val simClock = remember { SimAppClock() }
        val clock: AppClock = if (DebugFlags.TIME_SIMULATION) simClock else SystemAppClock
        val tz = remember { TimeZone.currentSystemDefault() }

        // PRD §9: the frequent tick — advance "now" and the schedule, recording any completed panel so
        // the next panel becomes the current "task to do now". This does NOT refill the window (that is
        // the §9 calculation events below); it only advances. Ticks every second under time simulation
        // so accelerated time shows promptly; otherwise the production 30s cadence.
        var nowMillis by remember { mutableStateOf(clock.nowMillis()) }
        LaunchedEffect(clock) {
            val interval: Long = if (DebugFlags.TIME_SIMULATION) 1_000 else 30_000
            // PRD §12 Device sleep is detected from *real* elapsed time, not the active clock's: a tick
            // gap far larger than the cadence in wall-clock seconds means the process was suspended.
            // Using the sim clock here would misread fast-forwarded time (e.g. 300×) as constant sleep
            // and keep cutting the current panel's past portion.
            var lastRealTick = SystemAppClock.nowMillis()
            var lastClockTick = clock.nowMillis()
            while (true) {
                val realNow = SystemAppClock.nowMillis()
                val now = clock.nowMillis()
                if (realNow - lastRealTick > interval * 3) {
                    // Report the gap in the active clock's domain (sim time when accelerated), so the
                    // hole lands where the panel actually is on the calendar.
                    vm.dispatch(SchedulerIntent.ReportDeviceSleep(lastClockTick, now))
                }
                lastRealTick = realNow
                lastClockTick = now
                nowMillis = now
                vm.dispatch(SchedulerIntent.AdvanceSchedule(now))
                delay(interval)
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
        // next scheduling is placed 24 hours before the first moment free of task — i.e. it waits until
        // `now` reaches `firstFreeMoment − 24h`, then refills so the window stays ~24h ahead. On an empty
        // schedule the target is in the past, so it fills immediately; after a fill the target moves to
        // the new horizon and the effect re-arms. Polls the (possibly simulated) clock so accelerated
        // time is honoured. The wait runs regardless of the switch (toggling while merely waiting is a
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

        // Done periods (PRD §8 task record, green) plus every calendar panel (PRD §8/§9 — auto and
        // user-authored, uniform blocks) drawn the same way.
        val calendarRecords =
            schedulerState.tasks.values.flatMap { task ->
                task.record.map { CalendarRecord(title = task.title, range = it, taskId = task.id) }
            } + mergePanelsForDisplay(schedulerState.panels)

        // PRD §7 calendar state, hoisted so the lateral menu (month grid) and the popup week view
        // stay in sync. "today" follows the (possibly simulated) clock so day rollovers are testable.
        val today = Instant.fromEpochMilliseconds(nowMillis).toLocalDateTime(tz).date
        var calendarOpen by remember { mutableStateOf(false) }
        // PRD §7/§14 Chores Manager: whether the floating chores window is open (local UI state, like the
        // calendar window; the chores data itself lives in the persisted scheduler state).
        var choresManagerOpen by remember { mutableStateOf(false) }
        var selectedDate by remember { mutableStateOf(today) }
        var monthAnchor by remember { mutableStateOf(LocalDate(today.year, today.month, 1)) }
        // PRD §8 edit window: the calendar block currently being edited (null = closed).
        var editingBlock by remember { mutableStateOf<PlacedRecord?>(null) }
        // PRD §8 Manual add: a not-yet-committed default panel shown in the edit window with a Save
        // button (null = not adding). Distinct from [editingBlock] so Save knows to add vs. update.
        var addingBlock by remember { mutableStateOf<PlacedRecord?>(null) }

        // PRD §8 focus: the floating calendar window is the focused surface while it is open — so the
        // tree stops hijacking letter typing into Edit Mode and Ctrl+Z/Y route to the calendar history.
        LaunchedEffect(calendarOpen) {
            vm.dispatch(SchedulerIntent.SetCalendarFocus(calendarOpen))
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
                    onToggleCalendar = { calendarOpen = !calendarOpen },
                    monthAnchor = monthAnchor,
                    onMonthAnchorChange = { monthAnchor = it },
                    selectedDate = selectedDate,
                    today = today,
                    onSelectDate = { selectedDate = it },
                    automaticSchedule = schedulerState.automaticSchedule,
                    onToggleAutomaticSchedule = { vm.dispatch(SchedulerIntent.SetAutomaticSchedule(it)) },
                    choresManagerOpen = choresManagerOpen,
                    onToggleChoresManager = { choresManagerOpen = !choresManagerOpen },
                )

                // The content area is clipped so the floating calendar window can overlap the tree
                // but never spill onto the lateral menu (PRD §7).
                Box(modifier = Modifier.weight(1f).fillMaxHeight().clipToBounds()) {
                    when (page) {
                        OmniPage.TaskScheduler ->
                            TaskSchedulerScreen(modifier = Modifier.fillMaxSize(), store = store, vm = vm)
                    }

                    if (calendarOpen) {
                        CalendarFloatingWindow(
                            selectedDate = selectedDate,
                            today = today,
                            nowMillis = nowMillis,
                            onDismiss = { calendarOpen = false },
                            modifier = Modifier.align(Alignment.Center),
                            records = calendarRecords,
                            // PRD §8 focus: pressing in the calendar makes it the focused surface again
                            // (e.g. after a click into the tree had handed focus back).
                            onFocus = {
                                if (!schedulerState.calendarFocused) {
                                    vm.dispatch(SchedulerIntent.SetCalendarFocus(true))
                                }
                            },
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
                                    pinned = false,
                                    fullStartMillis = startMillis,
                                    fullEndMillis = startMillis + span,
                                )
                            },
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
                            // PRD §8 Overlap Mode: commit re-divided panel widths from a dragged edge.
                            onAdjustWeights = { weights ->
                                if (weights.isNotEmpty()) vm.dispatch(SchedulerIntent.SetPanelWeights(weights))
                            },
                            overlapArmed = schedulerState.overlapArmed,
                            onToggleOverlap = { vm.dispatch(SchedulerIntent.ToggleCalendarOverlap) },
                            onUndo = { vm.dispatch(SchedulerIntent.Undo) },
                            onRedo = { vm.dispatch(SchedulerIntent.Redo) },
                        )

                        // PRD §8 edit window, drawn over the calendar window and the tree — used for
                        // both editing an existing block and the Manual-add default panel.
                        (editingBlock ?: addingBlock)?.let { block ->
                            val isNew = editingBlock == null
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

                    // PRD §14 Chores Manager: floating window over the tree (not the lateral menu).
                    if (choresManagerOpen) {
                        ChoresManagerWindow(
                            chores = schedulerState.chores,
                            onChange = { vm.dispatch(SchedulerIntent.SetChores(it)) },
                            onDismiss = { choresManagerOpen = false },
                            modifier = Modifier.align(Alignment.Center),
                        )
                    }

                    // Debug-only time-acceleration control (gated by DebugFlags.TIME_SIMULATION).
                    if (DebugFlags.TIME_SIMULATION) {
                        TimeSimPanel(
                            clock = simClock,
                            nowMillis = nowMillis,
                            modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
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

private fun mergePanelsForDisplay(panels: List<TaskPanel>): List<CalendarRecord> =
    SchedulerDomain.groupSameTaskPanelsForDisplay(panels).map { group ->
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
