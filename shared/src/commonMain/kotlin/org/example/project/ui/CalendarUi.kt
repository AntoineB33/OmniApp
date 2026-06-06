package org.example.project.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.Month
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.todayIn
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.coroutines.withTimeoutOrNull
import org.example.project.OmniPage
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.model.TaskTimeRange
import org.example.project.scheduler.state.CalendarEdge

/** PRD §7: visual language shared by the lateral menu and the calendar. */
private object CalColors {
    val accent = Color(0xFF1A73E8) // Google-blue accent
    val today = Color(0xFFE8F0FE)
    val now = Color(0xFFD93025)
    val grid = Color(0xFFDADCE0)
    val menuBackground = Color(0xFFF8F9FA)
    val muted = Color(0xFF5F6368)
    val record = Color(0xFF34A853) // Google-green "done" period block (PRD §8 task record)
    val scheduled = Color(0xFF1A73E8) // Google-blue "to do now" block (PRD §9 scheduled task)
    val manual = Color(0xFF8430CE) // Purple manually-placed block (PRD §8 manual entry)
}

private val WEEKDAY_SHORT = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
private val WEEKDAY_INITIAL = listOf("M", "T", "W", "T", "F", "S", "S")

/** Today in the user's local zone (PRD §7 calendar anchor). */
fun systemToday(): LocalDate = Clock.System.todayIn(TimeZone.currentSystemDefault())

/**
 * PRD §8 Task record / §9 scheduled task: one calendar period tagged with the task [title] (shown on
 * hover). [scheduled] is false for a period the user already did (§8 record, green) and true for the
 * scheduler's current "task to do now" (§9, drawn the same way in a distinct colour). Built from the
 * Task Tree and passed down from [App].
 */
data class CalendarRecord(
    val title: String,
    val range: TaskTimeRange,
    val scheduled: Boolean = false,
    /** PRD §8 manually-placed entry (add / edit window / drag) — drawn in a distinct colour. */
    val manual: Boolean = false,
    /** Identity of the backing [org.example.project.scheduler.model.ManualCalendarEntry] (manual only). */
    val entryId: String? = null,
    val taskId: TaskId? = null,
)

/** A [CalendarRecord] clipped to a single day, as start/end hour-of-day fractions in `[0, 24]`. */
data class PlacedRecord(
    val title: String,
    val startHour: Float,
    val endHour: Float,
    val scheduled: Boolean,
    val manual: Boolean = false,
    val entryId: String? = null,
    val taskId: TaskId? = null,
    /** The entry's true (un-clipped) start/end, used to compute drag/resize targets and edit times. */
    val fullStartMillis: Long = 0L,
    val fullEndMillis: Long = 0L,
)

/**
 * PRD §8: the portions of [records] that fall on [day] (in [tz]), each clipped to that day so a
 * period spanning midnight renders as one block per day it covers. Pure for unit-testing the
 * day/time math independently of Compose.
 */
fun recordsForDay(
    records: List<CalendarRecord>,
    day: LocalDate,
    tz: TimeZone,
): List<PlacedRecord> =
    records.mapNotNull { record ->
        val start = Instant.fromEpochMilliseconds(record.range.startEpochMillis).toLocalDateTime(tz)
        val end = Instant.fromEpochMilliseconds(record.range.endEpochMillis).toLocalDateTime(tz)
        if (start.date > day || end.date < day) return@mapNotNull null
        val startHour = if (start.date < day) 0f else start.hour + start.minute / 60f
        val endHour = if (end.date > day) 24f else end.hour + end.minute / 60f
        if (endHour <= startHour) return@mapNotNull null
        PlacedRecord(
            title = record.title,
            startHour = startHour.coerceIn(0f, 24f),
            endHour = endHour.coerceIn(0f, 24f),
            scheduled = record.scheduled,
            manual = record.manual,
            entryId = record.entryId,
            taskId = record.taskId,
            fullStartMillis = record.range.startEpochMillis,
            fullEndMillis = record.range.endEpochMillis,
        )
    }

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
    nowMillis: Long,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    records: List<CalendarRecord> = emptyList(),
    /** PRD §8 Manual add: invoked with the epoch-millis at a right-click position in the calendar. */
    onAddTaskAt: (Long) -> Unit = {},
    /** PRD §8 Manual drag (move): an entry was dropped; its start should move near these millis. */
    onMoveEntry: (String, Long) -> Unit = { _, _ -> },
    /** PRD §8 extend/shorten: an entry's [CalendarEdge] was dragged to these millis. */
    onResizeEntry: (String, CalendarEdge, Long) -> Unit = { _, _, _ -> },
    /** PRD §8 edit window: a double-click (without a drag) requests editing this entry. */
    onEditEntry: (String) -> Unit = {},
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
                WeekView(
                    selectedDate = selectedDate,
                    today = today,
                    nowMillis = nowMillis,
                    records = records,
                    onAddTaskAt = onAddTaskAt,
                    onMoveEntry = onMoveEntry,
                    onResizeEntry = onResizeEntry,
                    onEditEntry = onEditEntry,
                )
            }
        }
    }
}

@Composable
private fun WeekView(
    selectedDate: LocalDate,
    today: LocalDate,
    nowMillis: Long,
    records: List<CalendarRecord>,
    onAddTaskAt: (Long) -> Unit,
    onMoveEntry: (String, Long) -> Unit,
    onResizeEntry: (String, CalendarEdge, Long) -> Unit,
    onEditEntry: (String) -> Unit,
) {
    val weekStart = startOfWeek(selectedDate)
    val days = (0..6).map { weekStart.plus(it, DateTimeUnit.DAY) }
    val tz = remember { TimeZone.currentSystemDefault() }
    // Follows the (possibly simulated) clock so the now-line moves as accelerated time advances.
    val now = Instant.fromEpochMilliseconds(nowMillis).toLocalDateTime(tz).time
    val hourHeight = 48.dp
    val gutterWidth = 56.dp

    // PRD §8 (Google-Calendar style): open scrolled to the current time so today's "task to do now"
    // block (which starts at the present hour) is visible without manual scrolling. Show one hour of
    // lead context above it.
    val scrollState = rememberScrollState()
    val density = LocalDensity.current
    LaunchedEffect(Unit) {
        val target = with(density) { (hourHeight * (now.hour - 1)).toPx() }
        scrollState.scrollTo(target.roundToInt().coerceAtLeast(0))
    }

    // PRD §8: while a block is being dragged/resized, lock the grid's vertical scroll so it doesn't
    // compete with the block's own drag gesture.
    var scrollLocked by remember { mutableStateOf(false) }

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
                .verticalScroll(scrollState, enabled = !scrollLocked),
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
                        day = day,
                        tz = tz,
                        isToday = day == today,
                        hourHeight = hourHeight,
                        now = if (day == today) now else null,
                        records = recordsForDay(records, day, tz),
                        onAddTaskAt = onAddTaskAt,
                        onMoveEntry = onMoveEntry,
                        onResizeEntry = onResizeEntry,
                        onEditEntry = onEditEntry,
                        onLockScroll = { scrollLocked = it },
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

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun DayColumn(
    day: LocalDate,
    tz: TimeZone,
    isToday: Boolean,
    hourHeight: Dp,
    now: LocalTime?,
    records: List<PlacedRecord>,
    onAddTaskAt: (Long) -> Unit,
    onMoveEntry: (String, Long) -> Unit,
    onResizeEntry: (String, CalendarEdge, Long) -> Unit,
    onEditEntry: (String) -> Unit,
    onLockScroll: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    // PRD §8 Manual add: the right-click position (in this column's local pixels) that anchors the
    // contextual menu; null when no menu is open.
    var menuOffset by remember { mutableStateOf<Offset?>(null) }

    // Epoch millis at a vertical pixel offset within this 24-hour column.
    fun millisAt(offsetY: Float): Long {
        val hourHeightPx = with(density) { hourHeight.toPx() }
        val hours = (offsetY / hourHeightPx).coerceIn(0f, 23.999f)
        val hour = hours.toInt()
        val minute = ((hours - hour) * 60f).toInt().coerceIn(0, 59)
        return LocalDateTime(day.year, day.month, day.day, hour, minute)
            .toInstant(tz)
            .toEpochMilliseconds()
    }

    Box(
        modifier = modifier
            .fillMaxHeight()
            .background(if (isToday) CalColors.today.copy(alpha = 0.4f) else Color.Transparent)
            .border(width = 0.5.dp, color = CalColors.grid)
            // PRD §8: right-click anywhere on the day opens the "add a task" contextual menu.
            .pointerInput(day) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                            val change = event.changes.firstOrNull() ?: continue
                            change.consume()
                            menuOffset = change.position
                        }
                    }
                }
            },
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

        // PRD §8 Manual add: the contextual menu, anchored at the right-click position.
        val anchor = menuOffset
        DropdownMenu(
            expanded = anchor != null,
            onDismissRequest = { menuOffset = null },
            offset = anchor?.let { with(density) { DpOffset(it.x.toDp(), it.y.toDp()) } } ?: DpOffset.Zero,
        ) {
            DropdownMenuItem(
                text = { Text("add a task") },
                onClick = {
                    anchor?.let { onAddTaskAt(millisAt(it.y)) }
                    menuOffset = null
                },
            )
        }
        // PRD §8 Task record: each done-period as a green block at its time range; the task title
        // shows on hover. Manual entries are interactive (double-click to edit, double-click+drag to
        // move, grab an edge to resize); records and the scheduled block are static.
        records.forEach { record ->
            if (record.manual && record.entryId != null) {
                ManualEntryBlock(
                    record = record,
                    hourHeight = hourHeight,
                    onMove = onMoveEntry,
                    onResize = onResizeEntry,
                    onEdit = onEditEntry,
                    onLockScroll = onLockScroll,
                )
            } else {
                RecordBlock(record = record, hourHeight = hourHeight)
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

/**
 * PRD §8 Task record / §9 scheduled task block: a bar spanning the period's clipped time range within
 * its day column. Green for a done record, blue for the scheduler's "task to do now". Hovering it
 * reveals the task title (the bar itself shows no text, per the PRD's "title ... shows on hover").
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecordBlock(record: PlacedRecord, hourHeight: Dp) {
    val offsetY = hourHeight * record.startHour
    val height = (hourHeight * (record.endHour - record.startHour)).coerceAtLeast(2.dp)
    val color = when {
        record.manual -> CalColors.manual
        record.scheduled -> CalColors.scheduled
        else -> CalColors.record
    }
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(record.title.ifEmpty { "(untitled)" }) } },
        state = rememberTooltipState(),
        modifier = Modifier
            .offset(y = offsetY)
            .fillMaxWidth()
            .height(height)
            .padding(horizontal = 1.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(3.dp))
                .background(color.copy(alpha = 0.30f))
                .border(1.dp, color, RoundedCornerShape(3.dp)),
        )
    }
}

/**
 * PRD §8 manual calendar entry block: like [RecordBlock] but interactive.
 *  - Double-click then release (no drag) → [onEdit] (the edit window).
 *  - Double-click then drag while holding → [onMove] (committed once, on release).
 *  - Grab the top/bottom edge and drag → [onResize] the start/end edge.
 * A live offset/height preview follows the cursor during the gesture; the reducer applies the
 * no-overlap snapping/clamping when the gesture commits.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ManualEntryBlock(
    record: PlacedRecord,
    hourHeight: Dp,
    onMove: (String, Long) -> Unit,
    onResize: (String, CalendarEdge, Long) -> Unit,
    onEdit: (String) -> Unit,
    onLockScroll: (Boolean) -> Unit,
) {
    val id = record.entryId ?: return
    val density = LocalDensity.current
    val baseOffsetY = with(density) { (hourHeight * record.startHour).toPx() }
    val baseHeight = with(density) { (hourHeight * (record.endHour - record.startHour)).toPx() }
        .coerceAtLeast(with(density) { 2.dp.toPx() })

    // Live gesture preview, in this column's pixels.
    var dragPx by remember(id) { mutableStateOf(0f) }
    var moving by remember(id) { mutableStateOf(false) }
    var resizing by remember(id) { mutableStateOf<CalendarEdge?>(null) }

    fun millisDelta(px: Float): Long {
        val hourHeightPx = with(density) { hourHeight.toPx() }
        return ((px / hourHeightPx) * 3_600_000f).toLong()
    }

    // Apply the preview: moving shifts the whole block; a Start-edge resize shifts the top and
    // shrinks the height; an End-edge resize only grows/shrinks the height.
    val previewOffsetPx = baseOffsetY + if (moving || resizing == CalendarEdge.Start) dragPx else 0f
    val previewHeightPx = (
        baseHeight + when (resizing) {
            CalendarEdge.End -> dragPx
            CalendarEdge.Start -> -dragPx
            else -> 0f
        }
        ).coerceAtLeast(with(density) { 2.dp.toPx() })

    val color = CalColors.manual

    Box(
        modifier = Modifier
            .offset { IntOffset(0, previewOffsetPx.roundToInt()) }
            .fillMaxWidth()
            .height(with(density) { previewHeightPx.toDp() })
            .padding(horizontal = 1.dp)
            .pointerInput(id) {
                val touchSlop = viewConfiguration.touchSlop
                val doubleTapTimeout = viewConfiguration.doubleTapTimeoutMillis
                // Cap the resize edge zone at a third of the block so a short entry still has a
                // central "move / edit" region the double-click can land in.
                val edgePx = minOf(with(density) { 6.dp.toPx() }, baseHeight / 3f)

                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // Right-clicks fall through to the day column's "add a task" menu.
                    if (currentEvent.buttons.isSecondaryPressed) return@awaitEachGesture
                    val localY = down.position.y
                    val nearTop = localY <= edgePx
                    val nearBottom = localY >= baseHeight - edgePx
                    down.consume()

                    // Direct edge grab → resize that edge (PRD §8 extend/shorten).
                    if (nearTop || nearBottom) {
                        val edge = if (nearTop) CalendarEdge.Start else CalendarEdge.End
                        var started = false
                        var traveled = 0f
                        onLockScroll(true)
                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                if (!event.changes.any { it.pressed }) {
                                    if (started) {
                                        val base = if (edge == CalendarEdge.Start) record.fullStartMillis else record.fullEndMillis
                                        onResize(id, edge, base + millisDelta(dragPx))
                                    }
                                    break
                                }
                                val change = event.changes.firstOrNull { it.id == down.id } ?: event.changes.first()
                                val delta = change.positionChangeIgnoreConsumed()
                                traveled += delta.getDistance()
                                if (!started && traveled > touchSlop) {
                                    started = true
                                    resizing = edge
                                }
                                change.consume()
                                if (started) dragPx += delta.y
                            }
                        } finally {
                            onLockScroll(false)
                            resizing = null
                            dragPx = 0f
                        }
                        return@awaitEachGesture
                    }

                    // First press elsewhere: a plain drag does nothing; just wait for release.
                    var firstDragged = false
                    var t = 0f
                    while (true) {
                        val event = awaitPointerEvent()
                        if (!event.changes.any { it.pressed }) break
                        val change = event.changes.firstOrNull { it.id == down.id } ?: event.changes.first()
                        t += change.positionChange().getDistance()
                        if (t > touchSlop) firstDragged = true
                    }
                    if (firstDragged) return@awaitEachGesture

                    // A second press within the timeout makes it a double-click.
                    val second = withTimeoutOrNull(doubleTapTimeout) {
                        awaitFirstDown(requireUnconsumed = false)
                    } ?: return@awaitEachGesture
                    second.consume()

                    // Double-click then drag = move (commit on release); release with no drag = edit.
                    // Lock the grid scroll for the whole second press so a held drag can't scroll it.
                    var moveStarted = false
                    var mt = 0f
                    onLockScroll(true)
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (!event.changes.any { it.pressed }) {
                                if (moveStarted) {
                                    onMove(id, record.fullStartMillis + millisDelta(dragPx))
                                } else {
                                    onEdit(id)
                                }
                                break
                            }
                            val change = event.changes.firstOrNull { it.id == second.id } ?: event.changes.first()
                            val delta = change.positionChangeIgnoreConsumed()
                            mt += delta.getDistance()
                            if (!moveStarted && mt > touchSlop) {
                                moveStarted = true
                                moving = true
                            }
                            change.consume()
                            if (moveStarted) dragPx += delta.y
                        }
                    } finally {
                        onLockScroll(false)
                        moving = false
                        dragPx = 0f
                    }
                }
            },
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(3.dp))
                .background(color.copy(alpha = 0.30f))
                .border(1.dp, color, RoundedCornerShape(3.dp)),
        )
    }
}

private fun twoDigits(n: Int): String = n.toString().padStart(2, '0')

private fun formatHm(millis: Long, tz: TimeZone): String {
    val dt = Instant.fromEpochMilliseconds(millis).toLocalDateTime(tz)
    return "${twoDigits(dt.hour)}:${twoDigits(dt.minute)}"
}

/** Parse "H:mm" / "HH:mm" onto the calendar date of [refMillis]; null when malformed. */
private fun parseHmOnDateOf(text: String, refMillis: Long, tz: TimeZone): Long? {
    val parts = text.trim().split(":")
    if (parts.size != 2) return null
    val h = parts[0].toIntOrNull() ?: return null
    val m = parts[1].toIntOrNull() ?: return null
    if (h !in 0..23 || m !in 0..59) return null
    val date = Instant.fromEpochMilliseconds(refMillis).toLocalDateTime(tz).date
    return LocalDateTime(date.year, date.month, date.day, h, m).toInstant(tz).toEpochMilliseconds()
}

/**
 * PRD §8 edit window: a floating editor for a manual calendar entry. The title field behaves like the
 * tree's `Change Task` mode — typing keeps it a calendar-only "New task" (default), while picking an
 * existing title from the suggestion menu links that task. Two fields edit the begin/end times.
 * Rendered by [org.example.project.App] over the calendar window and the tree.
 */
@Composable
fun ManualEntryEditWindow(
    initialTitle: String,
    startMillis: Long,
    endMillis: Long,
    tz: TimeZone,
    titleSuggestions: (String) -> List<String>,
    taskIdForTitle: (String) -> TaskId?,
    onSave: (taskId: TaskId?, title: String, startMillis: Long, endMillis: Long) -> Unit,
    onDismiss: () -> Unit,
) {
    var title by remember { mutableStateOf(initialTitle) }
    // Null = calendar-only "New task" (the default); set when an existing title is picked.
    var pickedTaskId by remember { mutableStateOf<TaskId?>(taskIdForTitle(initialTitle)) }
    var startText by remember { mutableStateOf(formatHm(startMillis, tz)) }
    var endText by remember { mutableStateOf(formatHm(endMillis, tz)) }
    var showSuggestions by remember { mutableStateOf(false) }
    val suggestions = remember(title) { titleSuggestions(title).take(6) }

    // Full-screen scrim; clicking outside dismisses (PRD §8 floating window over everything).
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.25f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 12.dp,
            border = BorderStroke(1.dp, CalColors.grid),
            // Swallow clicks so they don't reach the dismissing scrim.
            modifier = Modifier.width(320.dp).clickable(enabled = false) {},
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Edit task", style = MaterialTheme.typography.titleSmall)

                OutlinedTextField(
                    value = title,
                    onValueChange = {
                        title = it
                        pickedTaskId = null // typing → "New task" (PRD §8)
                        showSuggestions = true
                    },
                    label = { Text("Task") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                // PRD §4/§8 title suggestion menu.
                if (showSuggestions && suggestions.isNotEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, CalColors.grid, RoundedCornerShape(6.dp)),
                    ) {
                        suggestions.forEach { suggestion ->
                            Text(
                                text = suggestion,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        title = suggestion
                                        pickedTaskId = taskIdForTitle(suggestion)
                                        showSuggestions = false
                                    }
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                            )
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = startText,
                        onValueChange = { startText = it },
                        label = { Text("Begins (HH:mm)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = endText,
                        onValueChange = { endText = it },
                        label = { Text("Ends (HH:mm)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val start = parseHmOnDateOf(startText, startMillis, tz) ?: startMillis
                            val end = parseHmOnDateOf(endText, endMillis, tz) ?: endMillis
                            onSave(pickedTaskId, title, start, end)
                        },
                    ) { Text("Save") }
                }
            }
        }
    }
}
