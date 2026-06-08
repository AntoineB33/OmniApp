package org.example.project.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material3.Switch
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
import org.example.project.OmniPage
import org.example.project.scheduler.domain.SchedulerDomain
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
    // PRD §8 (uniform blocks): every calendar period — record, scheduled, or manual — is drawn in this
    // single colour, with no visual distinction between auto-calculated and manually-added tasks.
    val event = Color(0xFF1A73E8) // Google-blue calendar event
}

private val WEEKDAY_SHORT = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
private val WEEKDAY_INITIAL = listOf("M", "T", "W", "T", "F", "S", "S")

/** Today in the user's local zone (PRD §7 calendar anchor). */
fun systemToday(): LocalDate = Clock.System.todayIn(TimeZone.currentSystemDefault())

/**
 * PRD §8 Task record / §9 scheduled task: one calendar period tagged with the task [title] (written
 * on the panel and shown on hover). [scheduled] is false for a period the user already did (§8 record, green) and true for the
 * scheduler's current "task to do now" (§9, drawn the same way in a distinct colour). Built from the
 * Task Tree and passed down from [App].
 */
data class CalendarRecord(
    val title: String,
    val range: TaskTimeRange,
    val scheduled: Boolean = false,
    /** PRD §8 a calendar panel (auto or user-authored), as opposed to a green task-record block. */
    val manual: Boolean = false,
    /** Identity of the backing [org.example.project.scheduler.model.TaskPanel] (panels only). */
    val entryId: String? = null,
    /**
     * PRD §8 same-task merge: the ids of every panel fused into this displayed block (consecutive
     * same-task, same-pin panels are shown as one). Holds one id for an unmerged panel and is empty for
     * a green task-record block. Interactions on a merged block act on all of these at once.
     */
    val entryIds: List<String> = emptyList(),
    val taskId: TaskId? = null,
    /** PRD §9 whether the backing panel is pinned (seeds the edit-window pin toggle). */
    val pinned: Boolean = false,
    /** PRD §8 Overlap Mode: horizontal weight of the backing panel (head panel for a merged block). */
    val layoutWeight: Double = 1.0,
)

/** A [CalendarRecord] clipped to a single day, as start/end hour-of-day fractions in `[0, 24]`. */
data class PlacedRecord(
    val title: String,
    val startHour: Float,
    val endHour: Float,
    val scheduled: Boolean,
    val manual: Boolean = false,
    val entryId: String? = null,
    /** PRD §8 same-task merge: every backing panel id of this (possibly merged) block. See [CalendarRecord.entryIds]. */
    val entryIds: List<String> = emptyList(),
    val taskId: TaskId? = null,
    val pinned: Boolean = false,
    /** PRD §8 Overlap Mode: horizontal weight of the backing panel; drives [overlapLayout] widths. */
    val layoutWeight: Double = 1.0,
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
            entryIds = record.entryIds,
            taskId = record.taskId,
            pinned = record.pinned,
            layoutWeight = record.layoutWeight,
            fullStartMillis = record.range.startEpochMillis,
            fullEndMillis = record.range.endEpochMillis,
        )
    }

/**
 * Stable identity for a calendar block across the [CalendarRecord] (full) and [PlacedRecord]
 * (per-day) representations, so a dragged block can exclude itself from the overlap set. Manual
 * entries key on their id; auto blocks (records / scheduled) key on their source + range.
 */
private fun calendarBlockKey(
    entryId: String?,
    scheduled: Boolean,
    taskId: TaskId?,
    startMillis: Long,
    endMillis: Long,
): String = entryId ?: "auto/${if (scheduled) "s" else "r"}/${taskId?.value}/$startMillis/$endMillis"

private fun calendarBlockKey(r: CalendarRecord): String =
    calendarBlockKey(r.entryId, r.scheduled, r.taskId, r.range.startEpochMillis, r.range.endEpochMillis)

private fun calendarBlockKey(r: PlacedRecord): String =
    calendarBlockKey(r.entryId, r.scheduled, r.taskId, r.fullStartMillis, r.fullEndMillis)

/**
 * PRD §8 Overlap Mode: one horizontal slice of a panel's render. A panel that never overlaps yields a
 * single full-width slice (`xFraction = 0`, `widthFraction = 1`) spanning its whole height; where it
 * overlaps others it yields a narrower slice for that sub-range only (a stepped, variable-width shape).
 */
data class PanelSlice(
    val topHour: Float,
    val bottomHour: Float,
    val xFraction: Float,
    val widthFraction: Float,
)

private fun approxEq(a: Float, b: Float): Boolean = kotlin.math.abs(a - b) < 1e-4f

/**
 * PRD §8 Overlap Mode horizontal layout. Splits the day at every block start/end boundary; within each
 * resulting `[a, b)` time slice the panels active there are ordered left→right by `(startHour, key)` and
 * share the column width in proportion to [PlacedRecord.layoutWeight] (equal weights ⇒ each `1/n`).
 * Vertically adjacent slices of the same block with the same x/width are coalesced, so a non-overlapping
 * panel collapses back to one full-width slice. Pure, for unit testing independently of Compose. Keyed
 * by [calendarBlockKey].
 */
fun overlapLayout(blocks: List<PlacedRecord>): Map<String, List<PanelSlice>> {
    if (blocks.isEmpty()) return emptyMap()
    val boundaries = sortedSetOf<Float>()
    for (b in blocks) {
        boundaries.add(b.startHour)
        boundaries.add(b.endHour)
    }
    val bounds = boundaries.toList()
    val raw = HashMap<String, MutableList<PanelSlice>>()
    for (i in 0 until bounds.size - 1) {
        val a = bounds[i]
        val b = bounds[i + 1]
        if (b <= a) continue
        val active = blocks
            .filter { it.startHour <= a && it.endHour >= b }
            .sortedWith(compareBy({ it.startHour }, { calendarBlockKey(it) }))
        if (active.isEmpty()) continue
        val total = active.sumOf { it.layoutWeight }.let { if (it <= 0.0) active.size.toDouble() else it }
        var x = 0f
        for (block in active) {
            val w = (block.layoutWeight / total).toFloat()
            raw.getOrPut(calendarBlockKey(block)) { mutableListOf() }
                .add(PanelSlice(topHour = a, bottomHour = b, xFraction = x, widthFraction = w))
            x += w
        }
    }
    return coalesceSlices(raw)
}

/** PRD §8 Overlap Mode: a draggable boundary between two horizontally-adjacent panels in one time slice. */
data class WeightHandle(
    val topHour: Float,
    val bottomHour: Float,
    /** Current split position as a fraction of the column width (where the boundary sits). */
    val boundaryFraction: Float,
    /** Backing panel ids of the panel left / right of the boundary (every id of a merged block). */
    val leftIds: List<String>,
    val rightIds: List<String>,
    /** Weight of the panels left of this pair in the slice, the slice total, and the pair's combined weight. */
    val leftSumWeight: Double,
    val totalWeight: Double,
    val pairWeight: Double,
)

/**
 * PRD §8 Overlap Mode: the vertical edges the user can drag to re-divide shared width. One handle per
 * adjacent panel pair within each overlap time slice (only between panels — a green record block has no
 * weight to adjust). The geometry ([leftSumWeight]/[totalWeight]/[pairWeight]) lets a drag map a pointer
 * x to new weights while the other panels' shares stay fixed.
 */
fun weightHandles(blocks: List<PlacedRecord>): List<WeightHandle> {
    if (blocks.size < 2) return emptyList()
    val boundaries = sortedSetOf<Float>()
    for (b in blocks) {
        boundaries.add(b.startHour)
        boundaries.add(b.endHour)
    }
    val bounds = boundaries.toList()
    val out = mutableListOf<WeightHandle>()
    for (i in 0 until bounds.size - 1) {
        val a = bounds[i]
        val b = bounds[i + 1]
        if (b <= a) continue
        val active = blocks
            .filter { it.startHour <= a && it.endHour >= b }
            .sortedWith(compareBy({ it.startHour }, { calendarBlockKey(it) }))
        if (active.size < 2) continue
        val total = active.sumOf { it.layoutWeight }.let { if (it <= 0.0) active.size.toDouble() else it }
        var leftSum = 0.0
        for (j in active.indices) {
            val w = active[j].layoutWeight
            val right = active.getOrNull(j + 1)
            if (right != null && active[j].entryIds.isNotEmpty() && right.entryIds.isNotEmpty()) {
                out.add(
                    WeightHandle(
                        topHour = a,
                        bottomHour = b,
                        boundaryFraction = ((leftSum + w) / total).toFloat(),
                        leftIds = active[j].entryIds,
                        rightIds = right.entryIds,
                        leftSumWeight = leftSum,
                        totalWeight = total,
                        pairWeight = w + right.layoutWeight,
                    ),
                )
            }
            leftSum += w
        }
    }
    return out
}

private fun coalesceSlices(raw: Map<String, MutableList<PanelSlice>>): Map<String, List<PanelSlice>> {
    // Coalesce vertically adjacent slices with matching x/width (the common no-overlap case → 1 slice).
    return raw.mapValues { (_, slices) ->
        val merged = mutableListOf<PanelSlice>()
        for (s in slices) {
            val last = merged.lastOrNull()
            if (last != null && approxEq(last.bottomHour, s.topHour) &&
                approxEq(last.xFraction, s.xFraction) && approxEq(last.widthFraction, s.widthFraction)
            ) {
                merged[merged.lastIndex] = last.copy(bottomHour = s.bottomHour)
            } else {
                merged.add(s)
            }
        }
        merged
    }
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
    /** PRD §7 Automatic Schedule Switch: current state + toggle callback. */
    automaticSchedule: Boolean = true,
    onToggleAutomaticSchedule: (Boolean) -> Unit = {},
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

        // PRD §7 Automatic Schedule Switch: while off, the §9 scheduling events wait.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Auto schedule",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = automaticSchedule,
                onCheckedChange = onToggleAutomaticSchedule,
            )
        }

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
    /** PRD §8 focus: a press anywhere in the window makes the calendar the focused surface again. */
    onFocus: () -> Unit = {},
    /** PRD §8 Manual add: invoked with the epoch-millis at a right-click position in the calendar. */
    onAddTaskAt: (Long) -> Unit = {},
    /**
     * PRD §8 drag/resize commit: the block, its new start/end millis, and whether Overlap Mode was armed
     * (the bounds are raw/overlapping when armed, else already no-overlap snapped).
     */
    onCommitBounds: (PlacedRecord, Long, Long, Boolean) -> Unit = { _, _, _, _ -> },
    /** PRD §8 task contextual menu "Edit": requests opening the edit window for this block. */
    onEditEntry: (PlacedRecord) -> Unit = {},
    /** PRD §8 task contextual menu "Remove": requests deleting this block. */
    onRemoveEntry: (PlacedRecord) -> Unit = {},
    /** PRD §8 Overlap Mode: new horizontal weights for panels whose shared-width edge was dragged. */
    onAdjustWeights: (Map<String, Double>) -> Unit = {},
    /** PRD §8 Overlap Mode: whether overlap is currently armed (toggled by `O` while the calendar is focused). */
    overlapArmed: Boolean = false,
    /** PRD §8 Overlap Mode: `O` toggles "allow overlap" for the next move/resize. */
    onToggleOverlap: () -> Unit = {},
    /** PRD §8/§9 calendar history: Ctrl+Z / Ctrl+Y while the calendar holds keyboard focus. */
    onUndo: () -> Unit = {},
    onRedo: () -> Unit = {},
) {
    var offset by remember { mutableStateOf(Offset.Zero) }
    // PRD §8: the calendar owns the keyboard while it is the active surface, so its own shortcuts (O to
    // toggle overlap, Ctrl+Z/Y to undo/redo the calendar history) work even though the tree normally
    // holds focus. Focus is (re)claimed when the window opens and on every press inside it.
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 10.dp,
        border = BorderStroke(1.dp, CalColors.grid),
        modifier = modifier
            .offset { IntOffset(offset.x.roundToInt(), offset.y.roundToInt()) }
            .size(width = 720.dp, height = 540.dp)
            .focusRequester(focusRequester)
            .focusable()
            // PRD §8: calendar-owned keyboard shortcuts while it is the focused surface.
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val mod = event.isCtrlPressed || event.isMetaPressed
                when {
                    event.key == Key.O && !mod -> {
                        onToggleOverlap()
                        true
                    }
                    mod && event.key == Key.Z -> {
                        onUndo()
                        true
                    }
                    mod && event.key == Key.Y -> {
                        onRedo()
                        true
                    }
                    else -> false
                }
            }
            // PRD §8 focus: observe presses on the Initial pass (without consuming, so the week view /
            // blocks still get them) to mark the calendar as the focused surface — and reclaim the
            // keyboard so its shortcuts fire — so clicking back into the calendar re-engages focus.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.type == PointerEventType.Press) {
                            onFocus()
                            runCatching { focusRequester.requestFocus() }
                        }
                    }
                }
            },
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
                    onCommitBounds = onCommitBounds,
                    onEditEntry = onEditEntry,
                    onRemoveEntry = onRemoveEntry,
                    onAdjustWeights = onAdjustWeights,
                    overlapArmed = overlapArmed,
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
    onCommitBounds: (PlacedRecord, Long, Long, Boolean) -> Unit,
    onEditEntry: (PlacedRecord) -> Unit,
    onRemoveEntry: (PlacedRecord) -> Unit,
    onAdjustWeights: (Map<String, Double>) -> Unit,
    overlapArmed: Boolean,
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

    // PRD §8 "there must not be overlaps" (default mode): every block on the calendar (records,
    // scheduled, manual) as (key, range), so a dragged block snaps around ALL of them live.
    val allBlocks = records.map { calendarBlockKey(it) to it.range }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(
            modifier = Modifier.padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = monthLabel(weekStart),
                style = MaterialTheme.typography.titleMedium,
            )
            if (overlapArmed) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Overlap mode (O)",
                    style = MaterialTheme.typography.labelSmall,
                    color = CalColors.accent,
                )
            }
        }

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
                        onCommitBounds = onCommitBounds,
                        onEditEntry = onEditEntry,
                        onRemoveEntry = onRemoveEntry,
                        onLockScroll = { scrollLocked = it },
                        onAdjustWeights = onAdjustWeights,
                        allBlocks = allBlocks,
                        overlapArmed = overlapArmed,
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
    onCommitBounds: (PlacedRecord, Long, Long, Boolean) -> Unit,
    onEditEntry: (PlacedRecord) -> Unit,
    onRemoveEntry: (PlacedRecord) -> Unit,
    onLockScroll: (Boolean) -> Unit,
    onAdjustWeights: (Map<String, Double>) -> Unit,
    allBlocks: List<Pair<String, TaskTimeRange>>,
    overlapArmed: Boolean,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    // The right-click position (in this column's local pixels) that anchors the contextual menu; null
    // when no menu is open. [menuTarget] is the block the click landed on (null = empty space).
    var menuOffset by remember { mutableStateOf<Offset?>(null) }
    var menuTarget by remember { mutableStateOf<PlacedRecord?>(null) }
    // Latest records, so the right-click hit-test closure never reads a stale list (records change
    // every scheduler tick) without restarting the long-lived gesture coroutine.
    val currentRecords by rememberUpdatedState(records)

    // PRD §8 Overlap Mode: live width-edge drag. While a weight handle is held this maps panel ids to
    // their in-progress weights so the layout (and the handle position) follow the drag; on release the
    // weights are committed via [onAdjustWeights] and this clears.
    var weightDrag by remember { mutableStateOf<Map<String, Double>?>(null) }
    val effRecords =
        weightDrag?.let { wd ->
            records.map { r -> r.entryIds.firstNotNullOfOrNull { wd[it] }?.let { r.copy(layoutWeight = it) } ?: r }
        } ?: records

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

    // PRD §8: the block whose vertical span contains [offsetY], if any (topmost wins).
    fun blockAt(offsetY: Float): PlacedRecord? {
        val hourHeightPx = with(density) { hourHeight.toPx() }
        return currentRecords.lastOrNull {
            offsetY >= it.startHour * hourHeightPx && offsetY <= it.endHour * hourHeightPx
        }
    }

    Box(
        modifier = modifier
            .fillMaxHeight()
            .background(if (isToday) CalColors.today.copy(alpha = 0.4f) else Color.Transparent)
            .border(width = 0.5.dp, color = CalColors.grid)
            // PRD §8: right-click opens a contextual menu. On a task block it offers "Edit"/"Remove";
            // on empty space it offers "add a task". The whole column owns this so the menu choice is
            // a reliable hit-test (no fragile cross-node pointer-consumption ordering).
            .pointerInput(day) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                            val change = event.changes.firstOrNull() ?: continue
                            change.consume()
                            menuTarget = blockAt(change.position.y)
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

        // PRD §8 contextual menu, anchored at the right-click position. Edit/Remove for a block,
        // else "add a task".
        val anchor = menuOffset
        fun closeMenu() { menuOffset = null; menuTarget = null }
        DropdownMenu(
            expanded = anchor != null,
            onDismissRequest = { closeMenu() },
            offset = anchor?.let { with(density) { DpOffset(it.x.toDp(), it.y.toDp()) } } ?: DpOffset.Zero,
        ) {
            val target = menuTarget
            if (target == null) {
                DropdownMenuItem(
                    text = { Text("add a task") },
                    onClick = {
                        anchor?.let { onAddTaskAt(millisAt(it.y)) }
                        closeMenu()
                    },
                )
            } else {
                DropdownMenuItem(
                    text = { Text("Edit") },
                    onClick = { closeMenu(); onEditEntry(target) },
                )
                DropdownMenuItem(
                    text = { Text("Remove") },
                    onClick = { closeMenu(); onRemoveEntry(target) },
                )
            }
        }
        // PRD §8 (uniform blocks): every period — task record, scheduled "to do now", or manual entry
        // — renders as the same interactive block (click+drag to move, grab an edge to resize). The
        // right-click Edit/Remove menu is owned by the day column (above). Auto blocks convert to a
        // manual entry on first edit (handled by the callbacks in App), so there is no difference.
        // PRD §8 Overlap Mode: split each block into horizontal slices so overlapping panels share the
        // column width (only over the overlapping sub-range). A non-overlapping block yields one
        // full-width slice = the original look.
        val layout = overlapLayout(effRecords)
        effRecords.forEach { record ->
            val key = calendarBlockKey(record)
            CalendarBlock(
                record = record,
                slices = layout[key] ?: listOf(
                    PanelSlice(record.startHour, record.endHour, xFraction = 0f, widthFraction = 1f),
                ),
                hourHeight = hourHeight,
                day = day,
                tz = tz,
                // Every other block — everything but itself — so a non-overlap drag/resize snaps around them.
                others = allBlocks.filter { it.first != key }.map { it.second },
                overlapArmed = overlapArmed,
                onCommitBounds = onCommitBounds,
                onLockScroll = onLockScroll,
            )
        }

        // PRD §8 Overlap Mode: draggable vertical edges between overlapping panels (re-divide width).
        // Overlaid above the slices at each shared boundary; a horizontal drag moves weight between the
        // two adjacent panels (the others' shares stay fixed), committed on release.
        val handles = weightHandles(effRecords)
        if (handles.isNotEmpty()) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val colWidth = maxWidth
                val colWidthPx = with(density) { colWidth.toPx() }
                val handleWidth = 10.dp
                handles.forEach { handle ->
                    Box(
                        modifier = Modifier
                            .offset(
                                x = colWidth * handle.boundaryFraction - handleWidth / 2,
                                y = hourHeight * handle.topHour,
                            )
                            .width(handleWidth)
                            .height(hourHeight * (handle.bottomHour - handle.topHour))
                            .pointerInput(handle.leftIds, handle.rightIds, handle.topHour) {
                                var accumX = 0f
                                detectHorizontalDragGestures(
                                    onDragStart = { onLockScroll(true) },
                                    onDragEnd = {
                                        weightDrag?.let(onAdjustWeights)
                                        weightDrag = null
                                        onLockScroll(false)
                                    },
                                    onDragCancel = {
                                        weightDrag = null
                                        onLockScroll(false)
                                    },
                                ) { change, dragAmount ->
                                    change.consume()
                                    accumX += dragAmount
                                    val newFraction = handle.boundaryFraction + (if (colWidthPx > 0f) accumX / colWidthPx else 0f)
                                    val eps = handle.pairWeight * 0.05
                                    val wLeft = (newFraction * handle.totalWeight - handle.leftSumWeight)
                                        .coerceIn(eps, handle.pairWeight - eps)
                                    val wRight = handle.pairWeight - wLeft
                                    weightDrag =
                                        buildMap {
                                            handle.leftIds.forEach { put(it, wLeft) }
                                            handle.rightIds.forEach { put(it, wRight) }
                                        }
                                }
                            },
                    )
                }
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
 * PRD §8 calendar block — one interactive component for EVERY period (task record, scheduled "to do
 * now", or manual entry), drawn identically (same colour) with the same behaviour:
 *  - Click and drag while holding → move; committed once, on release, via [onCommitBounds].
 *  - Grab the top/bottom edge and drag → resize that edge, also committed via [onCommitBounds].
 * Right-click (the Edit/Remove menu) is handled by the enclosing day column, so a secondary press is
 * left unconsumed here for it to pick up. The live preview applies the SAME no-overlap snapping/
 * clamping the reducer commits with, so a block never visually overlaps another. Auto blocks
 * (records/scheduled) are pinned into manual entries when edited, so afterwards they are
 * indistinguishable. The title is written on the block and also shows on hover (PRD §8).
 */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun CalendarBlock(
    record: PlacedRecord,
    slices: List<PanelSlice>,
    hourHeight: Dp,
    day: LocalDate,
    tz: TimeZone,
    others: List<TaskTimeRange>,
    overlapArmed: Boolean,
    onCommitBounds: (PlacedRecord, Long, Long, Boolean) -> Unit,
    onLockScroll: (Boolean) -> Unit,
) {
    val key = calendarBlockKey(record)
    // Read inside the long-lived gesture closure so a mid-drag `O` toggle is picked up immediately.
    val armed = rememberUpdatedState(overlapArmed)
    val density = LocalDensity.current
    val hourHeightPx = with(density) { hourHeight.toPx() }
    val minPx = with(density) { 2.dp.toPx() }
    val baseOffsetY = with(density) { (hourHeight * record.startHour).toPx() }
    val baseHeight = (with(density) { (hourHeight * (record.endHour - record.startHour)).toPx() })
        .coerceAtLeast(minPx)

    // Pixel <-> epoch-millis mapping for this day (linear; midnight of [day] is the column origin).
    val midnightMillis = LocalDateTime(day.year, day.month, day.day, 0, 0)
        .toInstant(tz).toEpochMilliseconds()
    val pxPerMs = hourHeightPx / 3_600_000f
    val entry = TaskTimeRange(record.fullStartMillis, record.fullEndMillis)
    val duration = record.fullEndMillis - record.fullStartMillis

    // Live gesture preview, in this column's pixels.
    var dragPx by remember(key) { mutableStateOf(0f) }
    var moving by remember(key) { mutableStateOf(false) }
    var resizing by remember(key) { mutableStateOf<CalendarEdge?>(null) }
    val dragging = moving || resizing != null

    fun millisDelta(px: Float): Long = ((px / hourHeightPx) * 3_600_000f).toLong()

    // The bounds for the current gesture (also what gets committed on release). In the default mode they
    // are snapped/clamped to never overlap; while Overlap Mode is armed they are the raw dragged bounds
    // (only kept from collapsing below the minimum length), so the panel can overlap others (PRD §8).
    val minLen = SchedulerDomain.MIN_MANUAL_ENTRY_MILLIS
    fun movedBounds(): TaskTimeRange {
        val rawStart = record.fullStartMillis + millisDelta(dragPx)
        return if (armed.value) {
            TaskTimeRange(rawStart, rawStart + duration)
        } else {
            SchedulerDomain.placeDraggedEntry(others, rawStart, duration)
        }
    }
    fun resizedBounds(edge: CalendarEdge): TaskTimeRange {
        val base = if (edge == CalendarEdge.Start) record.fullStartMillis else record.fullEndMillis
        val target = base + millisDelta(dragPx)
        if (!armed.value) return SchedulerDomain.clampResize(others, entry, edge, target)
        return when (edge) {
            CalendarEdge.Start -> entry.copy(startEpochMillis = minOf(target, entry.endEpochMillis - minLen))
            CalendarEdge.End -> entry.copy(endEpochMillis = maxOf(target, entry.startEpochMillis + minLen))
        }
    }

    val preview: TaskTimeRange? = when {
        moving -> movedBounds()
        resizing != null -> resizedBounds(resizing!!)
        else -> null
    }
    val previewOffsetPx =
        if (preview != null) (preview.startEpochMillis - midnightMillis) * pxPerMs else baseOffsetY
    val previewHeightPx =
        if (preview != null) ((preview.endEpochMillis - preview.startEpochMillis) * pxPerMs) else baseHeight
    val previewHeightCoerced = previewHeightPx.coerceAtLeast(minPx)

    val color = CalColors.event

    // PRD §8 Overlap Mode: a transparent full-column layer (no pointer handler of its own, so it never
    // steals clicks) that positions this block's horizontal slices absolutely. A non-overlapping block
    // has a single full-width slice = the original block. Each slice carries the shared move/resize
    // gesture; because every slice stays mounted for the whole drag, the gesture that began on one slice
    // is never cancelled by the rest↔drag visual switch.
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val colWidth = maxWidth
        slices.forEachIndexed { index, slice ->
            val isFirst = index == 0
            val isLast = index == slices.lastIndex
            val sliceTop = hourHeight * slice.topHour
            val sliceHeight = hourHeight * (slice.bottomHour - slice.topHour)
            val sliceHeightPx = with(density) { sliceHeight.toPx() }.coerceAtLeast(minPx)
            Box(
                modifier = Modifier
                    .offset(x = colWidth * slice.xFraction, y = sliceTop)
                    .width(colWidth * slice.widthFraction)
                    .height(sliceHeight)
                    // Kept mounted but invisible while dragging — the single preview box below shows the
                    // live position; this slice only needs to survive to keep the gesture alive.
                    .alpha(if (dragging) 0f else 1f)
                    .padding(horizontal = 1.dp)
                    // Re-key on the entry's position so the gesture closure re-captures fresh values after
                    // a commit; on slice role so edge zones recompute when the layout changes.
                    .pointerInput(key, record.fullStartMillis, record.fullEndMillis, isFirst, isLast) {
                        val touchSlop = viewConfiguration.touchSlop
                        // Cap the resize edge zone at a third of the slice so a short slice still has a
                        // central "move" region the press can land in.
                        val edgePx = minOf(with(density) { 6.dp.toPx() }, sliceHeightPx / 3f)

                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)

                            // Right-click → leave it unconsumed so the enclosing day column shows the
                            // Edit/Remove contextual menu for this block (PRD §8).
                            if (currentEvent.buttons.isSecondaryPressed) return@awaitEachGesture

                            // Resize only on the block's true top (first slice) / bottom (last slice);
                            // an interior slice edge is just a slice boundary, so it moves the block.
                            val localY = down.position.y
                            val edge = when {
                                isFirst && localY <= edgePx -> CalendarEdge.Start
                                isLast && localY >= sliceHeightPx - edgePx -> CalendarEdge.End
                                else -> null
                            }
                            down.consume()

                            var started = false
                            var traveled = 0f
                            // Lock the grid scroll for the whole press so a held drag can't scroll it.
                            onLockScroll(true)
                            try {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    if (!event.changes.any { it.pressed }) {
                                        if (started) {
                                            val b = if (edge != null) resizedBounds(edge) else movedBounds()
                                            onCommitBounds(record, b.startEpochMillis, b.endEpochMillis, armed.value)
                                        }
                                        break
                                    }
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: event.changes.first()
                                    val delta = change.positionChangeIgnoreConsumed()
                                    traveled += delta.getDistance()
                                    if (!started && traveled > touchSlop) {
                                        started = true
                                        if (edge != null) resizing = edge else moving = true
                                    }
                                    change.consume()
                                    if (started) dragPx += delta.y
                                }
                            } finally {
                                onLockScroll(false)
                                moving = false
                                resizing = null
                                dragPx = 0f
                            }
                        }
                    },
            ) {
                if (!dragging) {
                    TooltipBox(
                        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                        tooltip = { PlainTooltip { Text(record.title.ifEmpty { "(untitled)" }) } },
                        state = rememberTooltipState(),
                    ) {
                        // The title is written only on the topmost slice so a stepped block reads as one.
                        CalendarBlockBody(color, record.title, showTitle = isFirst)
                    }
                }
            }
        }

        // Single full-width live preview while dragging (PRD §8 v1: the side-by-side narrowing settles on
        // release). Drawn over the (now-invisible) slices; purely visual, no gesture of its own.
        if (dragging) {
            Box(
                modifier = Modifier
                    .offset { IntOffset(0, previewOffsetPx.roundToInt()) }
                    .fillMaxWidth()
                    .height(with(density) { previewHeightCoerced.toDp() })
                    .padding(horizontal = 1.dp),
            ) {
                CalendarBlockBody(color, record.title, showTitle = true)
            }
        }
    }
}

/** PRD §8: the coloured body + title of a calendar block (or one of its overlap slices). */
@Composable
private fun CalendarBlockBody(color: Color, title: String, showTitle: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(3.dp))
            .background(color.copy(alpha = 0.30f))
            .border(1.dp, color, RoundedCornerShape(3.dp)),
    ) {
        if (showTitle) {
            Text(
                text = title.ifEmpty { "(untitled)" },
                style = MaterialTheme.typography.labelSmall,
                color = CalColors.event,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 3.dp, vertical = 1.dp),
            )
        }
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
 * PRD §8 edit window: a floating editor for a calendar block that mirrors the tree's Edit Mode in
 * "Change Task" (the only relevant mode here — a calendar block always changes/assigns its task):
 *  - a **Tasks** menu whose first row is always "New task" (so the user can create a brand-new
 *    calendar task, [TaskId] left null) followed by existing tasks whose title matches what's typed
 *    (or the title currently picked from the suggestions);
 *  - a **Title suggestions** menu reusing an existing task's title.
 * Two fields edit the begin/end times. Rendered by [org.example.project.App] over everything.
 */
@Composable
fun ManualEntryEditWindow(
    initialTitle: String,
    initialTaskId: TaskId?,
    startMillis: Long,
    endMillis: Long,
    tz: TimeZone,
    taskMenuEntries: (draftText: String, excludeTaskId: TaskId?) -> List<SchedulerDomain.ChangeTaskMenuEntry>,
    titleSuggestions: (String) -> List<String>,
    taskIdForTitle: (String) -> TaskId?,
    titleForTaskId: (TaskId) -> String?,
    onSave: (taskId: TaskId?, title: String, startMillis: Long, endMillis: Long, pinned: Boolean) -> Unit,
    onDismiss: () -> Unit,
    /** PRD §8/§9: the panel's current pinned state, toggled by the "Pin" button in this window. */
    initialPinned: Boolean = false,
) {
    var title by remember { mutableStateOf(initialTitle) }
    // The explicitly-picked existing task, if any. PRD §8: unlike the tree, the calendar does NOT
    // default to "New task" — the first real task of the menu is pre-selected. [newTaskChosen] records
    // when the user explicitly picks the "New task" row instead, so a fresh window / typing reverts to
    // the first-task default.
    var selectedTaskId by remember { mutableStateOf(initialTaskId) }
    var newTaskChosen by remember { mutableStateOf(false) }
    var startText by remember { mutableStateOf(formatHm(startMillis, tz)) }
    var endText by remember { mutableStateOf(formatHm(endMillis, tz)) }
    // PRD §9: pinned panels survive a reschedule; toggled here.
    var pinned by remember { mutableStateOf(initialPinned) }

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
                        // Typing reverts to the default (first task of the menu), not "New task".
                        selectedTaskId = null
                        newTaskChosen = false
                    },
                    label = { Text("Task") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                // --- Tasks menu: New task + existing leaf matches. Pass no exclusion so the matching
                // task shows (and highlights) even when it was picked from the suggestions below. PRD §8:
                // the first real task is selected by default; "New task" only when explicitly chosen. ---
                val taskEntries = taskMenuEntries(title, null)
                // The effective task this window will save: the explicit pick, else (unless the user
                // chose "New task") the first real task of the menu.
                val effectiveTaskId =
                    when {
                        newTaskChosen -> null
                        selectedTaskId != null && taskEntries.any { it.taskId == selectedTaskId } -> selectedTaskId
                        else -> SchedulerDomain.calendarDefaultMenuTaskId(taskEntries)
                    }
                if (taskEntries.size > 1) {
                    val selectedIndex =
                        SchedulerDomain.changeTaskMenuSelectedIndex(taskEntries, effectiveTaskId)
                    Text(
                        text = "Tasks",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    taskEntries.forEachIndexed { index, entry ->
                        CalendarMenuRow(
                            label = entry.label,
                            selected = index == selectedIndex,
                            onClick = {
                                if (entry.taskId == null) {
                                    selectedTaskId = null // "New task" → calendar-only
                                    newTaskChosen = true
                                } else {
                                    selectedTaskId = entry.taskId
                                    newTaskChosen = false
                                    titleForTaskId(entry.taskId)?.let { title = it }
                                }
                            },
                        )
                    }
                }

                // --- Title suggestions menu ---
                val suggestions = titleSuggestions(title).take(8)
                if (suggestions.isNotEmpty()) {
                    Text(
                        text = "Title suggestions",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    suggestions.forEach { suggestion ->
                        CalendarMenuRow(
                            label = suggestion,
                            onClick = {
                                title = suggestion
                                selectedTaskId = taskIdForTitle(suggestion)
                                newTaskChosen = false
                            },
                        )
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

                // PRD §8/§9 "pin" button: toggle whether this panel survives a reschedule.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Pin",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(checked = pinned, onCheckedChange = { pinned = it })
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
                            onSave(effectiveTaskId, title, start, end, pinned)
                        },
                    ) { Text("Save") }
                }
            }
        }
    }
}

/** A clickable menu row matching the tree's `TaskMenuRow` (selected = current pick). */
@Composable
private fun CalendarMenuRow(
    label: String,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    Text(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp, horizontal = 8.dp),
        text = label,
        style =
            if (selected) MaterialTheme.typography.bodyMedium
            else MaterialTheme.typography.bodySmall,
        color =
            if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurface,
    )
}
