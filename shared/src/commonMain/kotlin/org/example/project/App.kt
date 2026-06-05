package org.example.project

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlin.time.Clock
import kotlinx.coroutines.delay
import kotlinx.datetime.LocalDate
import org.example.project.scheduler.model.TaskTimeRange
import org.example.project.scheduler.persistence.SchedulerStore
import org.example.project.scheduler.persistence.createDefaultSchedulerStore
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.ui.TaskSchedulerScreen
import org.example.project.scheduler.ui.TaskSchedulerViewModel
import org.example.project.ui.CalendarFloatingWindow
import org.example.project.ui.CalendarRecord
import org.example.project.ui.LateralMenu
import org.example.project.ui.systemToday

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

        // PRD §9: recompute "the task to do now" on app start and then on a timer, so a new task is
        // picked as soon as the last one's deadline is reached (a no-op while still within it).
        LaunchedEffect(vm) {
            while (true) {
                vm.dispatch(SchedulerIntent.RefreshSchedule(Clock.System.now().toEpochMilliseconds()))
                delay(30_000)
            }
        }

        // PRD §9 ("anytime there is no task to do at this moment"): also recompute as soon as the set
        // of tasks changes — so the very first task created on an empty database is scheduled
        // immediately, not only on the next 30s tick. A no-op while a current allocation is still
        // within its deadline.
        LaunchedEffect(schedulerState.tasks.keys) {
            vm.dispatch(SchedulerIntent.RefreshSchedule(Clock.System.now().toEpochMilliseconds()))
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
        // stay in sync.
        val today = remember { systemToday() }
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
                            onDismiss = { calendarOpen = false },
                            modifier = Modifier.align(Alignment.Center),
                            records = calendarRecords,
                        )
                    }
                }
            }
        }
    }
}
