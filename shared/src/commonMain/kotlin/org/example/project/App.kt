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
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.TaskId
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

        // PRD §9 calculation event #2 (tree change): recompute on a 1-second debounce after the task
        // tree changes — so the first task on an empty database is scheduled, editing a task's minimum
        // time reshapes the schedule, and deleting a scheduled task's cell refills around the cut.
        // Keyed on cells too (a deletion changes `cells` while the task object is briefly kept). Gated
        // by §7: skipped while auto-scheduling is off (re-runs when it turns back on). The LaunchedEffect
        // restart-on-change cancels the prior delay, giving the debounce.
        LaunchedEffect(schedulerState.tasks, schedulerState.cells, schedulerState.automaticSchedule, clock) {
            if (!schedulerState.automaticSchedule) return@LaunchedEffect
            delay(1_000)
            vm.dispatch(SchedulerIntent.RefreshSchedule(clock.nowMillis()))
        }

        // PRD §9 calculation event #1 (calendar change / rolling horizon): after the panels change, the
        // next scheduling is placed 24 hours before the first moment free of task — i.e. it waits until
        // `now` reaches `firstFreeMoment − 24h`, then refills so the window stays ~24h ahead. On an empty
        // schedule the target is in the past, so it fills immediately; after a fill the target moves to
        // the new horizon and the effect re-arms. Polls the (possibly simulated) clock so accelerated
        // time is honoured. Gated by §7.
        LaunchedEffect(schedulerState.panels, schedulerState.automaticSchedule, clock) {
            if (!schedulerState.automaticSchedule) return@LaunchedEffect
            val target =
                SchedulerDomain.firstFreeMoment(schedulerState.panels, clock.nowMillis()) -
                    SchedulerDomain.SCHEDULE_HORIZON_MILLIS
            val pollInterval: Long = if (DebugFlags.TIME_SIMULATION) 1_000 else 30_000
            while (clock.nowMillis() < target) {
                delay(pollInterval)
            }
            vm.dispatch(SchedulerIntent.RefreshSchedule(clock.nowMillis()))
        }

        // PRD §11 Notifications: whenever "the task to do now" changes to a DIFFERENT task, post a
        // system notification naming it. We track the last task we notified about (not just the
        // scheduled id) so transient id changes that resolve back to the same task don't re-notify —
        // e.g. moving the current block pins it (id → null) and the scheduler immediately re-picks the
        // same task (null → same id), which must stay silent. Clearing the schedule keeps the last
        // value so re-scheduling the same task later is still silent.
        var lastNotifiedTaskId by remember { mutableStateOf<TaskId?>(null) }
        val currentTaskId = SchedulerDomain.currentPanel(schedulerState, nowMillis)?.taskId
        LaunchedEffect(currentTaskId) {
            val taskId = currentTaskId ?: return@LaunchedEffect
            if (taskId == lastNotifiedTaskId) return@LaunchedEffect
            val title = schedulerState.tasks[taskId]?.title?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
            lastNotifiedTaskId = taskId
            sendSystemNotification("Task to do now", title)
        }

        // Done periods (PRD §8 task record, green) plus every calendar panel (PRD §8/§9 — auto and
        // user-authored, uniform blocks) drawn the same way.
        val calendarRecords =
            schedulerState.tasks.values.flatMap { task ->
                task.record.map { CalendarRecord(title = task.title, range = it, taskId = task.id) }
            } +
                schedulerState.panels.map {
                    CalendarRecord(
                        title = it.title,
                        range = TaskTimeRange(it.startEpochMillis, it.endEpochMillis),
                        manual = true,
                        entryId = it.id,
                        taskId = it.taskId,
                        pinned = it.pinned,
                    )
                }

        // PRD §7 calendar state, hoisted so the lateral menu (month grid) and the popup week view
        // stay in sync. "today" follows the (possibly simulated) clock so day rollovers are testable.
        val today = Instant.fromEpochMilliseconds(nowMillis).toLocalDateTime(tz).date
        var calendarOpen by remember { mutableStateOf(false) }
        var selectedDate by remember { mutableStateOf(today) }
        var monthAnchor by remember { mutableStateOf(LocalDate(today.year, today.month, 1)) }
        // PRD §8 edit window: the calendar block currently being edited (null = closed).
        var editingBlock by remember { mutableStateOf<PlacedRecord?>(null) }
        // PRD §8 Manual add: a not-yet-committed default panel shown in the edit window with a Save
        // button (null = not adding). Distinct from [editingBlock] so Save knows to add vs. update.
        var addingBlock by remember { mutableStateOf<PlacedRecord?>(null) }

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
                            onCommitBounds = { block, newStart, newEnd ->
                                commitBoundsIntent(block, block.taskId, block.title, newStart, newEnd, block.pinned)
                                    ?.let(vm::dispatch)
                            },
                            onEditEntry = { block -> editingBlock = block },
                            // PRD §8 task contextual menu "Remove": delete the block by its source.
                            onRemoveEntry = { block -> removeBlockIntent(block)?.let(vm::dispatch) },
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
                                titleSuggestions = { SchedulerDomain.titleSuggestions(schedulerState, it) },
                                taskIdForTitle = { schedulerState.titleToTaskIds[it]?.firstOrNull() },
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
): SchedulerIntent? = when {
    block.entryId != null ->
        SchedulerIntent.UpdateTaskPanel(block.entryId, taskId, title, startMillis, endMillis, pinned)
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
        )
    else -> null
}

/**
 * PRD §8 task contextual menu "Remove": the intent that deletes a calendar [block] from its source —
 * a panel is removed, a green task-record period is dropped from the task record. Returns null when
 * the block has no removable identity (defensive).
 */
private fun removeBlockIntent(block: PlacedRecord): SchedulerIntent? = when {
    block.entryId != null -> SchedulerIntent.RemoveTaskPanel(block.entryId)
    block.taskId != null ->
        SchedulerIntent.RemoveRecordPeriod(block.taskId, block.fullStartMillis, block.fullEndMillis)
    else -> null
}
