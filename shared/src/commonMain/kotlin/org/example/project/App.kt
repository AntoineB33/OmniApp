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
        // database is scheduled immediately (not only on the next tick), and editing the tree (e.g.
        // the scheduled task's minimum time) updates its scheduled period right away. A no-op when the
        // recomputed allocation matches the current one.
        LaunchedEffect(schedulerState.tasks) {
            vm.dispatch(SchedulerIntent.RefreshSchedule(clock.nowMillis()))
        }

        // Done periods (PRD §8 task record, green) plus the scheduler's current task to do (PRD §9,
        // blue) — both drawn the same way in the calendar.
        val calendarRecords =
            schedulerState.tasks.values.flatMap { task ->
                task.record.map { CalendarRecord(title = task.title, range = it) }
            } +
                (schedulerState.scheduled?.let { sch ->
                    schedulerState.tasks[sch.taskId]?.let { task ->
                        CalendarRecord(
                            title = task.title,
                            range = TaskTimeRange(sch.startEpochMillis, sch.deadlineEpochMillis),
                            scheduled = true,
                        )
                    }
                }?.let(::listOf) ?: emptyList())

        // PRD §7 calendar state, hoisted so the lateral menu (month grid) and the popup week view
        // stay in sync. "today" follows the (possibly simulated) clock so day rollovers are testable.
        val today = Instant.fromEpochMilliseconds(nowMillis).toLocalDateTime(tz).date
        var calendarOpen by remember { mutableStateOf(false) }
        var selectedDate by remember { mutableStateOf(today) }
        var monthAnchor by remember { mutableStateOf(LocalDate(today.year, today.month, 1)) }

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
