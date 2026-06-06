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

        // PRD §9: the single time tick — advance "now" and recompute "the task to do now", so a task
        // is re-picked as soon as the last one's deadline is reached (a no-op while still within it).
        // Ticks every second under time simulation so accelerated time shows promptly; otherwise the
        // production 30s cadence.
        var nowMillis by remember { mutableStateOf(clock.nowMillis()) }
        LaunchedEffect(clock) {
            while (true) {
                nowMillis = clock.nowMillis()
                vm.dispatch(SchedulerIntent.RefreshSchedule(nowMillis))
                delay(if (DebugFlags.TIME_SIMULATION) 1_000 else 30_000)
            }
        }

        // PRD §9: recompute as soon as the task tree changes — so the first task created on an empty
        // database is scheduled immediately (not only on the next tick), editing the tree (e.g. the
        // scheduled task's minimum time) updates its scheduled period right away, and deleting the
        // scheduled task's cell cuts its period at that instant. Keyed on cells too, since a deletion
        // changes `cells` while the task object is briefly kept. A no-op when nothing relevant changed.
        LaunchedEffect(schedulerState.tasks, schedulerState.cells) {
            vm.dispatch(SchedulerIntent.RefreshSchedule(clock.nowMillis()))
        }

        // Done periods (PRD §8 task record, green) plus the scheduler's current task to do (PRD §9,
        // blue) — both drawn the same way in the calendar. PRD §8 manual entries are drawn too.
        val calendarRecords =
            schedulerState.tasks.values.flatMap { task ->
                task.record.map { CalendarRecord(title = task.title, range = it, taskId = task.id) }
            } +
                (schedulerState.scheduled?.let { sch ->
                    schedulerState.tasks[sch.taskId]?.let { task ->
                        CalendarRecord(
                            title = task.title,
                            range = TaskTimeRange(sch.startEpochMillis, sch.deadlineEpochMillis),
                            scheduled = true,
                            taskId = sch.taskId,
                        )
                    }
                }?.let(::listOf) ?: emptyList()) +
                schedulerState.manualEntries.map {
                    CalendarRecord(
                        title = it.title,
                        range = TaskTimeRange(it.startEpochMillis, it.endEpochMillis),
                        manual = true,
                        entryId = it.id,
                        taskId = it.taskId,
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
                            onAddTaskAt = { startMillis ->
                                vm.dispatch(SchedulerIntent.AddManualCalendarEntry(startMillis))
                            },
                            // PRD §8 (uniform blocks): committing a drag/resize either updates the
                            // manual entry or pins the auto block (record/scheduled) into one.
                            onCommitBounds = { block, newStart, newEnd ->
                                commitBoundsIntent(block, block.taskId, block.title, newStart, newEnd)
                                    ?.let(vm::dispatch)
                            },
                            onEditEntry = { block -> editingBlock = block },
                        )

                        // PRD §8 edit window, drawn over the calendar window and the tree.
                        editingBlock?.let { block ->
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
                                onDismiss = { editingBlock = null },
                                onSave = { taskId, title, startMillis, endMillis ->
                                    commitBoundsIntent(block, taskId, title, startMillis, endMillis)
                                        ?.let(vm::dispatch)
                                    editingBlock = null
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
 * PRD §8 (uniform blocks): the intent that commits new bounds/title for any calendar [block].
 * A manual entry is updated in place; an auto block (scheduled or task record) is pinned into a new
 * manual entry. Returns null when the block has no usable identity (defensive).
 */
private fun commitBoundsIntent(
    block: PlacedRecord,
    taskId: TaskId?,
    title: String,
    startMillis: Long,
    endMillis: Long,
): SchedulerIntent? = when {
    block.entryId != null ->
        SchedulerIntent.UpdateManualCalendarEntry(block.entryId, taskId, title, startMillis, endMillis)
    block.scheduled ->
        SchedulerIntent.PinScheduledAsManual(taskId, title, startMillis, endMillis)
    block.taskId != null ->
        SchedulerIntent.PinRecordAsManual(
            recordTaskId = block.taskId,
            recordStartEpochMillis = block.fullStartMillis,
            recordEndEpochMillis = block.fullEndMillis,
            taskId = taskId,
            title = title,
            startEpochMillis = startMillis,
            endEpochMillis = endMillis,
        )
    else -> null
}
