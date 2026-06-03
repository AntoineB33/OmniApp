package org.example.project.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlin.time.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.Month
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.todayIn
import kotlinx.datetime.toLocalDateTime
import org.example.project.OmniPage

/** PRD §7: visual language shared by the lateral menu and the calendar. */
private object CalColors {
    val accent = Color(0xFF1A73E8) // Google-blue accent
    val today = Color(0xFFE8F0FE)
    val now = Color(0xFFD93025)
    val grid = Color(0xFFDADCE0)
    val menuBackground = Color(0xFFF8F9FA)
    val muted = Color(0xFF5F6368)
}

private val WEEKDAY_SHORT = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
private val WEEKDAY_INITIAL = listOf("M", "T", "W", "T", "F", "S", "S")

/** Today in the user's local zone (PRD §7 calendar anchor). */
fun systemToday(): LocalDate = Clock.System.todayIn(TimeZone.currentSystemDefault())

private fun nowLocalTime(): LocalTime =
    Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).time

/** Monday of the week containing [date] (PRD §7 week view starts on Monday, like the mock-up). */
private fun startOfWeek(date: LocalDate): LocalDate =
    date.minus(date.dayOfWeek.isoDayNumber - 1, DateTimeUnit.DAY)

private fun monthLabel(date: LocalDate): String =
    "${date.month.displayName} ${date.year}"

private val Month.displayName: String
    get() = name.lowercase().replaceFirstChar { it.uppercase() }

private fun hourLabel(hour: Int): String {
    val period = if (hour < 12) "AM" else "PM"
    val twelve = when (val h = hour % 12) {
        0 -> 12
        else -> h
    }
    return "$twelve $period"
}

/**
 * PRD §7 Lateral menu: a persistent left rail. Its first element is the page-navigation button
 * (present on every feature page); below it a button toggles the calendar popup. While the calendar
 * is open the rail also hosts the month grid for day/month selection (mirroring Google Calendar).
 */
@Composable
fun LateralMenu(
    page: OmniPage,
    onPageSelected: (OmniPage) -> Unit,
    calendarOpen: Boolean,
    onToggleCalendar: () -> Unit,
    monthAnchor: LocalDate,
    onMonthAnchorChange: (LocalDate) -> Unit,
    selectedDate: LocalDate,
    today: LocalDate,
    onSelectDate: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .width(if (calendarOpen) 248.dp else 188.dp)
            .background(CalColors.menuBackground)
            .border(1.dp, CalColors.grid)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // First element: page navigation (persistent across all feature pages).
        PageNavButton(page = page, onPageSelected = onPageSelected)

        MenuButton(
            label = "Calendar",
            active = calendarOpen,
            onClick = onToggleCalendar,
        )

        // PRD §7 Calendar: the month grid only appears while the calendar is displayed.
        if (calendarOpen) {
            Spacer(Modifier.height(4.dp))
            MiniMonth(
                monthAnchor = monthAnchor,
                onMonthAnchorChange = onMonthAnchorChange,
                selectedDate = selectedDate,
                today = today,
                onSelectDate = onSelectDate,
            )
        }
    }
}

@Composable
private fun PageNavButton(page: OmniPage, onPageSelected: (OmniPage) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        MenuButton(label = page.label, active = false, onClick = { expanded = true })
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            OmniPage.entries.forEach { entry ->
                DropdownMenuItem(
                    text = { Text(entry.label) },
                    onClick = {
                        onPageSelected(entry)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun MenuButton(label: String, active: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (active) CalColors.today else Color.Transparent)
            .border(
                1.dp,
                if (active) CalColors.accent else CalColors.grid,
                RoundedCornerShape(8.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (active) CalColors.accent else MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** PRD §7 Calendar: month grid in the lateral menu — pick a day, or page months with ‹ / ›. */
@Composable
private fun MiniMonth(
    monthAnchor: LocalDate,
    onMonthAnchorChange: (LocalDate) -> Unit,
    selectedDate: LocalDate,
    today: LocalDate,
    onSelectDate: (LocalDate) -> Unit,
) {
    val firstOfMonth = LocalDate(monthAnchor.year, monthAnchor.month, 1)
    val daysInMonth = firstOfMonth.daysUntil(firstOfMonth.plus(1, DateTimeUnit.MONTH))
    val leadingBlanks = firstOfMonth.dayOfWeek.isoDayNumber - 1 // Monday-first

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = monthLabel(monthAnchor),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            MonthArrow("‹") { onMonthAnchorChange(firstOfMonth.minus(1, DateTimeUnit.MONTH)) }
            MonthArrow("›") { onMonthAnchorChange(firstOfMonth.plus(1, DateTimeUnit.MONTH)) }
        }

        Row(Modifier.fillMaxWidth()) {
            WEEKDAY_INITIAL.forEach { initial ->
                Text(
                    text = initial,
                    style = MaterialTheme.typography.labelSmall,
                    color = CalColors.muted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // Day cells, 7 per row, with leading blanks so the 1st lands under its weekday.
        val totalCells = leadingBlanks + daysInMonth
        val rows = (totalCells + 6) / 7
        for (rowIndex in 0 until rows) {
            Row(Modifier.fillMaxWidth()) {
                for (col in 0 until 7) {
                    val cellIndex = rowIndex * 7 + col
                    val dayNumber = cellIndex - leadingBlanks + 1
                    if (dayNumber in 1..daysInMonth) {
                        val date = LocalDate(monthAnchor.year, monthAnchor.month, dayNumber)
                        MiniMonthDay(
                            day = dayNumber,
                            isToday = date == today,
                            isSelected = date == selectedDate,
                            onClick = { onSelectDate(date) },
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun MonthArrow(glyph: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(24.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, style = MaterialTheme.typography.titleMedium, color = CalColors.muted)
    }
}

@Composable
private fun MiniMonthDay(
    day: Int,
    isToday: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val background = when {
        isSelected -> CalColors.accent
        isToday -> CalColors.today
        else -> Color.Transparent
    }
    val textColor = when {
        isSelected -> Color.White
        isToday -> CalColors.accent
        else -> MaterialTheme.colorScheme.onSurface
    }
    Box(
        modifier = modifier
            .padding(1.dp)
            .height(30.dp)
            .clip(CircleShape)
            .background(background)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = day.toString(),
            style = MaterialTheme.typography.bodySmall,
            color = textColor,
            fontWeight = if (isToday || isSelected) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

/**
 * PRD §7 Calendar: a floating, draggable in-app window (not a modal dialog) showing the week
 * containing [selectedDate] as a Google-Calendar style time grid. It is meant to be rendered inside
 * the page-content area so it floats over the tree but never over the lateral menu; grab the title
 * bar to move it around.
 */
@Composable
fun CalendarFloatingWindow(
    selectedDate: LocalDate,
    today: LocalDate,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var offset by remember { mutableStateOf(Offset.Zero) }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 10.dp,
        border = BorderStroke(1.dp, CalColors.grid),
        modifier = modifier
            .offset { IntOffset(offset.x.roundToInt(), offset.y.roundToInt()) }
            .size(width = 720.dp, height = 540.dp),
    ) {
        Column(Modifier.fillMaxSize()) {
            // Title bar doubles as the drag handle for moving the window.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(CalColors.menuBackground)
                    .pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            offset += dragAmount
                        }
                    }
                    .padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Calendar",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("✕", style = MaterialTheme.typography.titleSmall, color = CalColors.muted)
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(CalColors.grid))
            Box(Modifier.weight(1f).fillMaxWidth()) {
                WeekView(selectedDate = selectedDate, today = today)
            }
        }
    }
}

@Composable
private fun WeekView(selectedDate: LocalDate, today: LocalDate) {
    val weekStart = startOfWeek(selectedDate)
    val days = (0..6).map { weekStart.plus(it, DateTimeUnit.DAY) }
    val now = remember { nowLocalTime() }
    val hourHeight = 48.dp
    val gutterWidth = 56.dp

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Text(
            text = monthLabel(weekStart),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = 8.dp),
        )

        // Day-of-week + date headers, aligned over their columns.
        Row(Modifier.fillMaxWidth()) {
            Spacer(Modifier.width(gutterWidth))
            days.forEachIndexed { index, day ->
                DayHeader(
                    weekday = WEEKDAY_SHORT[index],
                    dayOfMonth = day.dayOfMonth,
                    isToday = day == today,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            Row(Modifier.fillMaxWidth().height(hourHeight * 24)) {
                // Hour labels gutter.
                Column(Modifier.width(gutterWidth)) {
                    for (hour in 0..23) {
                        Box(Modifier.height(hourHeight).fillMaxWidth().padding(end = 6.dp)) {
                            Text(
                                text = hourLabel(hour),
                                style = MaterialTheme.typography.labelSmall,
                                color = CalColors.muted,
                                textAlign = TextAlign.End,
                                modifier = Modifier.fillMaxWidth().offset(y = (-6).dp),
                            )
                        }
                    }
                }
                days.forEach { day ->
                    DayColumn(
                        isToday = day == today,
                        hourHeight = hourHeight,
                        now = if (day == today) now else null,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun DayHeader(
    weekday: String,
    dayOfMonth: Int,
    isToday: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = weekday,
            style = MaterialTheme.typography.labelSmall,
            color = if (isToday) CalColors.accent else CalColors.muted,
        )
        Box(
            modifier = Modifier
                .padding(top = 2.dp)
                .size(30.dp)
                .clip(CircleShape)
                .background(if (isToday) CalColors.accent else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = dayOfMonth.toString(),
                style = MaterialTheme.typography.titleSmall,
                color = if (isToday) Color.White else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun DayColumn(
    isToday: Boolean,
    hourHeight: Dp,
    now: LocalTime?,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxHeight()
            .background(if (isToday) CalColors.today.copy(alpha = 0.4f) else Color.Transparent)
            .border(width = 0.5.dp, color = CalColors.grid),
    ) {
        // Hour grid lines.
        Column(Modifier.fillMaxSize()) {
            for (hour in 0..23) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(hourHeight)
                        .border(width = 0.5.dp, color = CalColors.grid),
                )
            }
        }
        // Current-time indicator (only on today's column).
        if (now != null) {
            val offsetY = hourHeight * (now.hour + now.minute / 60f)
            Box(
                modifier = Modifier
                    .offset(y = offsetY)
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(CalColors.now),
            )
            Box(
                modifier = Modifier
                    .offset(y = offsetY - 4.dp)
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(CalColors.now),
            )
        }
    }
}
