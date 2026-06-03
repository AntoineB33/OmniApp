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
import kotlinx.datetime.LocalDate
import org.example.project.scheduler.persistence.SchedulerStore
import org.example.project.scheduler.persistence.createDefaultSchedulerStore
import org.example.project.scheduler.ui.TaskSchedulerScreen
import org.example.project.ui.CalendarFloatingWindow
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
                            TaskSchedulerScreen(modifier = Modifier.fillMaxSize(), store = store)
                    }

                    if (calendarOpen) {
                        CalendarFloatingWindow(
                            selectedDate = selectedDate,
                            today = today,
                            onDismiss = { calendarOpen = false },
                            modifier = Modifier.align(Alignment.Center),
                        )
                    }
                }
            }
        }
    }
}
