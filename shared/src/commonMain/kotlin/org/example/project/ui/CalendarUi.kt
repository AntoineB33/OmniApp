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
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
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
import androidx.compose.ui.input.pointer.isCtrlPressed as pointerCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed as pointerMetaPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
import org.example.project.scheduler.model.ChoreEntry
import org.example.project.scheduler.model.ChoreRecurrenceUnit
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.model.TaskTimeRange
import org.example.project.scheduler.state.CalendarEdge
import org.example.project.scheduler.state.HistoryCategory
import org.example.project.scheduler.state.HistoryUnit
import org.example.project.scheduler.state.SchedulerHistories
import org.example.project.scheduler.state.SchedulerHistory

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
    /** PRD §14 Reminders: a zero-duration, checkable tag (not a height-proportional panel). */
    val reminder: Boolean = false,
    /** PRD §14 Reminders: whether this reminder tag has been checked off (done). */
    val checked: Boolean = false,
    /** PRD §14 Reminders: epoch millis at which the tag was checked (freeze point), or null while unchecked. */
    val checkedAtMillis: Long? = null,
    /** PRD §15 Side tasks: a periodic side task, drawn as a time-positioned band spanning its real duration. */
    val sideTask: Boolean = false,
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
    /** PRD §14 Reminders: a zero-duration, checkable tag rendered at [startHour] (not a draggable block). */
    val reminder: Boolean = false,
    /** PRD §14 Reminders: whether this reminder tag has been checked off (done). */
    val checked: Boolean = false,
    /** PRD §14 Reminders: epoch millis at which the tag was checked (freeze point), or null while unchecked. */
    val checkedAtMillis: Long? = null,
    /** PRD §15 Side tasks: a periodic side task rendered as a time-positioned band over [startHour, endHour]. */
    val sideTask: Boolean = false,
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
        // PRD §14/§15: reminders (zero-duration) render as fixed-height tags and side tasks (down to sub-
        // minute durations) as min-height bands, so keep them even though the block path would drop a
        // ~zero-height period.
        if (!record.reminder && !record.sideTask && endHour <= startHour) return@mapNotNull null
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
            reminder = record.reminder,
            checked = record.checked,
            checkedAtMillis = record.checkedAtMillis,
            sideTask = record.sideTask,
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
internal fun startOfWeek(date: LocalDate): LocalDate =
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
    /** PRD §7 Chores Manager: whether the chores window is open + toggle callback. */
    choresManagerOpen: Boolean = false,
    onToggleChoresManager: () -> Unit = {},
    /** PRD §5/§6 History Manager: whether the history window is open + toggle callback. */
    historyManagerOpen: Boolean = false,
    onToggleHistoryManager: () -> Unit = {},
    /** PRD §15 (20s look-away): whether the spoken voice cue is enabled + toggle callback. */
    lookAwayVoiceEnabled: Boolean = true,
    onToggleLookAwayVoice: (Boolean) -> Unit = {},
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

        // PRD §15 (20s look-away): spoken voice cue on/off.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Look-away voice",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = lookAwayVoiceEnabled,
                onCheckedChange = onToggleLookAwayVoice,
            )
        }

        // PRD §7 Reminders: toggles the floating reminders window over the tree.
        MenuButton(
            label = "Reminders",
            active = choresManagerOpen,
            onClick = onToggleChoresManager,
        )

        // PRD §5/§6 History: toggles the floating history manager (all the history unit lists).
        MenuButton(
            label = "History",
            active = historyManagerOpen,
            onClick = onToggleHistoryManager,
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

/**
 * Raise a floating window to the top of the stack when the user presses anywhere inside it. The press is
 * observed on the **Initial** pointer pass and is *not* consumed, so the window's own drag / click / button
 * handlers still receive it — this only records the interaction so the caller can re-order the z-stack.
 */
fun Modifier.raiseOnPress(onPress: () -> Unit): Modifier =
    pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                if (awaitPointerEvent(PointerEventPass.Initial).type == PointerEventType.Press) onPress()
            }
        }
    }

/**
 * PRD §14 Reminders: a floating, draggable in-app window (not a modal dialog) holding a vertical list of
 * rows, each three input fields — a title, a recurrence in **days** (a floating-point number) and a time
 * of day. Like the §7 calendar window it floats over the tree, not the lateral menu; grab the title bar
 * to move it. Rows are edited live: every change pushes the parsed list up via [onChange]. Each row has a
 * bin button (remove) and a `+` (insert above); a trailing `+` appends a row.
 */
@Composable
fun ChoresManagerWindow(
    chores: List<ChoreEntry>,
    onChange: (List<ChoreEntry>) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    /** Initial position relative to centered; staggered per window so they open in a clickable cascade. */
    initialOffset: Offset = Offset.Zero,
    /** Raise this window to the top of the layers — fired on a press anywhere inside it. */
    onRaise: () -> Unit = {},
    /**
     * PRD §14: the time-of-day (minutes since midnight) to pre-fill a newly added reminder's "Time" field —
     * the current clock time at the moment the `+` is clicked. A negative value (the default) leaves it blank.
     */
    newRowTimeOfDayMinutes: () -> Int = { -1 },
    /**
     * PRD §14: existing reminders whose title matches the focused row's draft — the **Reminders** id menu
     * shown under the focused title field (mirrors the "add a checked reminder" window). Picking one fills
     * the row's title.
     */
    reminderMenuEntries: (draftText: String) -> List<SchedulerDomain.ReminderMenuEntry> = { emptyList() },
    /** PRD §14: distinct reminder titles matching the focused row's draft — the **Title suggestions** menu. */
    titleSuggestions: (String) -> List<String> = { emptyList() },
    /**
     * PRD §14: every known reminder id (manager rows + calendar-only "add a checked reminder" reminders).
     * A new row's minted id must dodge all of these, not just the rows here, or it could collide with a
     * calendar-only reminder and filter that reminder out of the id menu (it would land in `rowIds`).
     */
    knownReminderIds: () -> Set<String> = { emptySet() },
) {
    var offset by remember { mutableStateOf(initialOffset) }
    // PRD §14: which row's title field currently holds focus — drives the edit-mode menus shown beneath it.
    var focusedIndex by remember { mutableStateOf<Int?>(null) }
    // PRD §14: the focused row's edit mode (Change vs Rename), reset to Change whenever focus moves to a
    // different row (keyed on focusedIndex so each row's editor starts in Change mode).
    var editMode by remember(focusedIndex) { mutableStateOf(ReminderEditMode.Change) }
    // Per-row editable text (title, days, time-of-day) so an in-progress "3." / "9:" isn't reformatted
    // each keystroke. Seeded once from the incoming chores; live edits drive both this and the pushed list.
    val rows = remember {
        mutableStateListOf<ChoreRow>().apply {
            // PRD §14: the recurrence field shows the raw formula the user typed (e.g. "31/21") in its unit;
            // fall back to the numeric span (in days) for reminders saved before formulas existed.
            addAll(
                chores.map {
                    ChoreRow(
                        title = it.title,
                        daysText = it.daysFormula.ifBlank { formatDays(it.recurrenceUnit.fromDays(it.spanDays)) },
                        timeText = formatTimeOfDay(it.timeOfDayMinutes),
                        unit = it.recurrenceUnit,
                        id = it.id,
                    )
                },
            )
        }
    }
    // PRD §14: reminder ids that already exist — the rows seeded from `chores` when the window opened, plus
    // any id a row later adopts from the id menu. A row whose id is NOT here is still *being created*, so
    // (like the "add a checked reminder" window) it shows no Mode selector: it is always in Change Reminder
    // mode, with no prior title to Rename yet.
    val existingReminderIds = remember { chores.mapTo(mutableSetOf<String>()) { it.id } }
    // A new row gets a stable, locally-unique id right away (mirroring the reducer's `reminder-{n}` scheme)
    // so it has an identity before the round-trip through onChange — the id menu can then exclude the row
    // being edited (otherwise a brand-new reminder would suggest itself). The minted id must also dodge ids
    // owned by calendar-only "add a checked reminder" reminders: colliding with one would make the id menu
    // filter that reminder out (it appears in `rowIds`), so it would never be offered for adoption.
    fun newRow() = ChoreRow(
        timeText = formatTimeOfDay(newRowTimeOfDayMinutes()),
        id = run {
            val used = rows.mapTo(mutableSetOf()) { it.id }
            used.addAll(knownReminderIds())
            var n = 0
            while (used.contains("reminder-$n")) n++
            "reminder-$n"
        },
    )
    fun push() {
        // PRD §14: resolve each row's effective reminder id. A row still being created (its minted id is not
        // an existing reminder) and not explicitly marked "New Reminder" adopts the reminder its id menu shows
        // selected by default — the first matching calendar-only reminder not already owned by another row —
        // exactly as the "add a checked reminder" window resolves its id from the title. Without this,
        // relying on the default selection (not clicking it) would leave the row a distinct reminder, so a
        // past checked reminder of the matching title would not act as its scheduling tie-breaker (PRD §14).
        val taken = rows.filter { it.id in existingReminderIds }.mapTo(mutableSetOf()) { it.id }
        onChange(
            rows.map { row ->
                // PRD §14: the chosen unit maps the entered number to a cadence in days (interval vs rate units).
                val number = SchedulerDomain.evaluateDayFormula(row.daysText) ?: 0.0
                val effectiveId =
                    if (row.id in existingReminderIds || row.explicitNew) row.id
                    else reminderMenuEntries(row.title).firstOrNull { it.id !in taken }?.id ?: row.id
                taken.add(effectiveId)
                ChoreEntry(
                    title = row.title,
                    spanDays = row.unit.toDays(number),
                    timeOfDayMinutes = parseTimeOfDay(row.timeText),
                    daysFormula = row.daysText,
                    recurrenceUnit = row.unit,
                    id = effectiveId,
                )
            },
        )
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 10.dp,
        border = BorderStroke(1.dp, CalColors.grid),
        modifier = modifier
            .offset { IntOffset(offset.x.roundToInt(), offset.y.roundToInt()) }
            .size(width = 560.dp, height = 480.dp)
            // Raise on press AFTER the offset so the hit region tracks the (possibly dragged) window.
            .raiseOnPress(onRaise),
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
                    text = "Reminders",
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

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                rows.forEachIndexed { index, row ->
                  // The row is a focus group so that opening the Mode dropdown (a focusable anchor) keeps the
                  // editor open rather than collapsing it. Entering Edit mode still requires focusing the
                  // *title* field (set below); the group only governs *staying* in edit mode — the menus
                  // vanish once focus leaves the whole row.
                  Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { if (!it.hasFocus && focusedIndex == index) focusedIndex = null }
                        .focusGroup(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                  ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        OutlinedTextField(
                            value = row.title,
                            // Editing the title reverts an explicit "New Reminder" pick so the id resolves from
                            // the title again (mirrors the "add a checked reminder" window, PRD §14).
                            onValueChange = { rows[index] = row.copy(title = it, explicitNew = false); push() },
                            singleLine = true,
                            label = { Text("Reminder") },
                            modifier = Modifier
                                .weight(1f)
                                .onFocusChanged { if (it.isFocused) focusedIndex = index },
                        )
                        OutlinedTextField(
                            value = row.daysText,
                            onValueChange = { rows[index] = row.copy(daysText = sanitizeFormula(it)); push() },
                            singleLine = true,
                            label = { Text("Every") },
                            modifier = Modifier.width(72.dp),
                        )
                        // PRD §14: when the recurrence field holds a formula, show its evaluated value (rounded
                        // to two decimals, comma separator) just to its right — e.g. "30/21" → "=1,43".
                        val daysResult =
                            if (isDayFormula(row.daysText)) SchedulerDomain.evaluateDayFormula(row.daysText) else null
                        if (daysResult != null && daysResult.isFinite()) {
                            Text(
                                text = "=${formatFormulaResult(daysResult)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = CalColors.muted,
                            )
                        }
                        // PRD §14: unit selector — every n days (default) / months / years, or n times per
                        // month / per year (rate units divide the period instead of multiplying).
                        RecurrenceUnitDropdown(
                            unit = row.unit,
                            onSelect = { rows[index] = row.copy(unit = it); push() },
                        )
                        OutlinedTextField(
                            value = row.timeText,
                            onValueChange = { rows[index] = row.copy(timeText = sanitizeTimeOfDay(it)); push() },
                            singleLine = true,
                            label = { Text("Time") },
                            modifier = Modifier.width(80.dp),
                        )
                        // Bin: remove this row.
                        TextButton(onClick = { rows.removeAt(index); push() }) { Text("🗑") }
                        // Plus: insert a new row above this one.
                        TextButton(onClick = { rows.add(index, newRow()); push() }) { Text("+") }
                    }

                    // PRD §14: the row's Edit mode — the shared mode selector + menus show beneath the fields
                    // (and vanish when focus leaves the row). The id menu lists reminders matching the draft
                    // that are NOT already a row in this window — i.e. reminders that exist only as "add a
                    // checked reminder" panels on the calendar. Picking one adopts its id (adding that reminder
                    // to the manager); "New Reminder" keeps this row's own freshly-minted id. The default
                    // highlight is the first such reminder, or "New Reminder" when there are none.
                    if (focusedIndex == index) {
                        val rowIds = rows.mapTo(mutableSetOf()) { it.id }
                        val entries = reminderMenuEntries(row.title).filter { it.id !in rowIds }
                        ReminderEditModeMenus(
                            mode = editMode,
                            onSelectMode = { editMode = it },
                            // A row still being created (a brand-new id, not yet an existing reminder) is
                            // always in Change Reminder mode — there is nothing to Rename, so hide the selector.
                            showModeSelector = row.id in existingReminderIds,
                            idMenuEntries = entries,
                            // The default highlight mirrors the resolved id in push(): "New Reminder" when the
                            // user explicitly chose it (or nothing matches), else the first matching reminder.
                            newReminderSelected = row.explicitNew || entries.isEmpty(),
                            selectedEntryId = if (row.explicitNew) null else entries.firstOrNull()?.id,
                            // Explicitly choosing "New Reminder" keeps this row's own freshly-minted id even
                            // though its title matches a calendar-only reminder (PRD §14).
                            onPickNewReminder = { rows[index] = row.copy(explicitNew = true); push() },
                            onPickEntry = { entry ->
                                // Adopting an existing reminder makes this row an existing reminder too —
                                // record its id so the Mode selector (Change/Rename) now appears for it.
                                existingReminderIds.add(entry.id)
                                rows[index] = row.copy(id = entry.id, title = entry.title, explicitNew = false)
                                push()
                            },
                            titleSuggestions = titleSuggestions(row.title).filter { it != row.title }.take(8),
                            onPickSuggestion = { suggestion -> rows[index] = row.copy(title = suggestion); push() },
                        )
                    }
                  }
                }
                // Trailing single plus: append a new row at the end of the list.
                TextButton(onClick = { rows.add(newRow()); push() }) { Text("+ add reminder") }
            }
        }
    }
}

/**
 * PRD §5/§6 History Manager: a floating, draggable in-app window (like the calendar / reminders windows)
 * that **shows all the history unit lists** — one section per [HistoryCategory] (Main "the rest", Calendar,
 * Edit Mode, Selection), each listing its [HistoryUnit]s oldest-first with the current pointer marked.
 * Read-only: it reflects the live history so the user can see what Ctrl+Z / Ctrl+Y (and Alt+←/→) will walk.
 */
@Composable
fun HistoryManagerWindow(
    histories: SchedulerHistories,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    /** Initial position relative to centered; staggered per window so they open in a clickable cascade. */
    initialOffset: Offset = Offset.Zero,
    /** Raise this window to the top of the layers — fired on a press anywhere inside it. */
    onRaise: () -> Unit = {},
) {
    var offset by remember { mutableStateOf(initialOffset) }
    // Display order: the content stacks first (most-used), then selection.
    val sections = listOf(
        "Main (the rest)" to HistoryCategory.Main,
        "Calendar" to HistoryCategory.Calendar,
        "Edit Mode" to HistoryCategory.Edit,
        "Selection" to HistoryCategory.Selection,
    )

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 10.dp,
        border = BorderStroke(1.dp, CalColors.grid),
        modifier = modifier
            .offset { IntOffset(offset.x.roundToInt(), offset.y.roundToInt()) }
            .size(width = 780.dp, height = 520.dp)
            // Raise on press AFTER the offset so the hit region tracks the (possibly dragged) window.
            .raiseOnPress(onRaise),
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
                    text = "History",
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

            // The four category lists sit side by side so every list's head is aligned at the top
            // (PRD §5/§6). Each column scrolls its units independently.
            Row(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                sections.forEachIndexed { index, (title, category) ->
                    HistoryCategorySection(
                        title = title,
                        history = histories.forCategory(category),
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                    if (index < sections.lastIndex) {
                        Box(Modifier.fillMaxHeight().width(1.dp).background(CalColors.grid))
                    }
                }
            }
        }
    }
}

/**
 * One column of the history manager: a category's header (with `applied/total`) pinned at the top, then
 * its History Units listed **newest-first** (last at the top, first at the bottom). Each unit shows its
 * label plus all of its data ([Delta.details]); undone units (ahead of the pointer) are dimmed and the
 * current pointer position is marked.
 */
@Composable
private fun HistoryCategorySection(
    title: String,
    history: SchedulerHistory,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            // pointer is 0-based on the last applied unit; `pointer + 1` units can be undone.
            Text(
                text = "${history.pointer + 1} / ${history.units.size}",
                style = MaterialTheme.typography.labelSmall,
                color = CalColors.muted,
            )
        }
        if (history.units.isEmpty()) {
            Text(
                text = "(empty)",
                style = MaterialTheme.typography.bodySmall,
                color = CalColors.muted,
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Newest at the top, oldest at the bottom: walk the units in reverse.
                items(history.units.size) { row ->
                    val index = history.units.lastIndex - row
                    HistoryUnitRow(
                        position = index + 1,
                        unit = history.units[index],
                        applied = index <= history.pointer,
                        isCurrent = index == history.pointer,
                    )
                }
            }
        }
    }
}

/** One History Unit: its position, label, the current-pointer marker, and every line of its data. */
@Composable
private fun HistoryUnitRow(
    position: Int,
    unit: HistoryUnit,
    applied: Boolean,
    isCurrent: Boolean,
) {
    // Undone units (past the pointer, redoable) are dimmed.
    val labelColor = if (applied) MaterialTheme.colorScheme.onSurface else CalColors.muted
    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "$position.",
                style = MaterialTheme.typography.bodySmall,
                color = CalColors.muted,
                modifier = Modifier.width(24.dp),
            )
            Text(
                text = unit.delta.label,
                style = MaterialTheme.typography.bodyMedium,
                color = labelColor,
                modifier = Modifier.weight(1f),
            )
            if (isCurrent) {
                Text(text = "●", style = MaterialTheme.typography.labelSmall, color = CalColors.accent)
            }
        }
        unit.delta.details.forEach { line ->
            Text(
                text = line,
                style = MaterialTheme.typography.labelSmall,
                color = CalColors.muted,
                modifier = Modifier.padding(start = 30.dp),
            )
        }
    }
}

/** One editable chores row: title, recurrence text + unit, and time-of-day text (raw strings while typing). */
private data class ChoreRow(
    val title: String = "",
    val daysText: String = "",
    val timeText: String = "",
    val unit: ChoreRecurrenceUnit = ChoreRecurrenceUnit.Days,
    // PRD §14: carry the reminder's stable id through edits so it isn't reassigned on every change (a blank
    // id on a brand-new row is filled by the reducer's assignReminderIds).
    val id: String = "",
    // PRD §14: the user explicitly picked "New Reminder" for this still-being-created row, so it keeps its
    // own freshly-minted id even when its title matches a calendar-only reminder (otherwise the default is to
    // adopt that matching reminder's id, like the "add a checked reminder" window). Reset when the title is
    // edited, mirroring the check window where typing reverts to resolving the id from the title.
    val explicitNew: Boolean = false,
)

/** PRD §14: the unit selector beside the recurrence field — every n days (default) / months / years, or n times per week / month / year. */
@Composable
private fun RecurrenceUnitDropdown(unit: ChoreRecurrenceUnit, onSelect: (ChoreRecurrenceUnit) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .border(1.dp, CalColors.grid, RoundedCornerShape(4.dp))
                .clickable { expanded = true }
                .padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(unit.label, style = MaterialTheme.typography.bodyMedium)
            Text("▾", style = MaterialTheme.typography.bodySmall, color = CalColors.muted)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ChoreRecurrenceUnit.entries.forEach { entry ->
                DropdownMenuItem(
                    text = { Text(entry.label) },
                    onClick = {
                        onSelect(entry)
                        expanded = false
                    },
                )
            }
        }
    }
}

/** Render a day span without a forced ".0" tail for whole numbers (e.g. 7.0 → "7", 3.5 → "3.5"). */
private fun formatDays(days: Double): String =
    if (days == days.toLong().toDouble()) days.toLong().toString() else days.toString()

/**
 * PRD §14: true when the "Days" text is an arithmetic *formula* (carries an operator / parenthesis) rather
 * than a plain number — used to decide whether to show its evaluated result beside the field.
 */
private fun isDayFormula(text: String): Boolean =
    text.any { it == '+' || it == '-' || it == '*' || it == '/' || it == '(' || it == ')' }

/** Format a formula's evaluated day span with exactly two decimals and a comma separator (e.g. 1.4285 → "1,43"). */
private fun formatFormulaResult(value: Double): String {
    val neg = value < 0
    val scaled = (abs(value) * 100).roundToInt()
    val text = "${scaled / 100},${(scaled % 100).toString().padStart(2, '0')}"
    return if (neg) "-$text" else text
}

/**
 * PRD §14: keep the "Days" field to characters of an arithmetic formula — digits, a decimal point, the
 * operators `+ - * /`, parentheses and spaces — so it can hold a plain number (`7`, `0.5`) or an expression
 * (`31/21`). A `,` is normalised to `.`; anything else is dropped. [SchedulerDomain.evaluateDayFormula]
 * does the actual parsing.
 */
private fun sanitizeFormula(raw: String): String {
    val sb = StringBuilder()
    for (c in raw) {
        when {
            c.isDigit() -> sb.append(c)
            c == '.' -> sb.append('.')
            c == ',' -> sb.append('.')
            c == '+' || c == '-' || c == '*' || c == '/' || c == '(' || c == ')' -> sb.append(c)
            c == ' ' -> sb.append(' ')
        }
    }
    return sb.toString()
}

/**
 * PRD §14 "time in the day": render minutes-since-midnight as `HH:MM`. A negative value means the time is
 * **not defined** (the reminder is placed at the current time) and shows as a blank field.
 */
private fun formatTimeOfDay(minutes: Int): String {
    if (minutes < 0) return ""
    val m = minutes.coerceIn(0, 24 * 60 - 1)
    return (m / 60).toString().padStart(2, '0') + ":" + (m % 60).toString().padStart(2, '0')
}

/** Keep only digits and a single colon so the "Time" field stays an `HH:MM`-shaped value while typing. */
private fun sanitizeTimeOfDay(raw: String): String {
    val sb = StringBuilder()
    var colonSeen = false
    for (c in raw) {
        when {
            c.isDigit() -> sb.append(c)
            c == ':' && !colonSeen -> { sb.append(':'); colonSeen = true }
        }
    }
    return sb.toString()
}

/**
 * Parse an `HH:MM` (or bare-hour / bare-minutes) field into minutes since midnight, clamped to a day. An
 * empty field is **not defined** (PRD §14: the reminder is then placed at the current time) → returns -1.
 */
private fun parseTimeOfDay(text: String): Int {
    if (text.isEmpty()) return -1
    val parts = text.split(':')
    val hours = parts.getOrNull(0)?.toIntOrNull() ?: 0
    val mins = parts.getOrNull(1)?.toIntOrNull() ?: 0
    return (hours * 60 + mins).coerceIn(0, 24 * 60 - 1)
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
    /** PRD §14: "add a checked reminder" — invoked with the epoch-millis at a right-click position. */
    onAddCheckedReminderAt: (Long) -> Unit = {},
    /**
     * PRD §8 drag/resize commit: the block, its new start/end millis, and whether Overlap Mode was armed
     * (the bounds are raw/overlapping when armed, else already no-overlap snapped).
     */
    onCommitBounds: (PlacedRecord, Long, Long, Boolean) -> Unit = { _, _, _, _ -> },
    /** PRD §8 task contextual menu "Edit": requests opening the edit window for this block. */
    onEditEntry: (PlacedRecord) -> Unit = {},
    /** PRD §8 task contextual menu "Remove": requests deleting this block. */
    onRemoveEntry: (PlacedRecord) -> Unit = {},
    /** PRD §14 Reminders: a reminder tag was clicked → toggle its checked (done) state. */
    onToggleReminder: (PlacedRecord) -> Unit = {},
    /** PRD §8 Overlap Mode: new horizontal weights for panels whose shared-width edge was dragged. */
    onAdjustWeights: (Map<String, Double>) -> Unit = {},
    /** PRD §8 Overlap Mode: whether overlap is currently armed (toggled by `O` while the calendar is focused). */
    overlapArmed: Boolean = false,
    /** PRD §8 Overlap Mode: `O` toggles "allow overlap" for the next move/resize. */
    onToggleOverlap: () -> Unit = {},
    /** PRD §15: whether the calendar draws the side tasks (cosmetic display toggle). */
    showSideTasks: Boolean = true,
    /** PRD §15: flip the "Side tasks" display switch. */
    onToggleSideTasks: (Boolean) -> Unit = {},
    /** PRD §14: whether the calendar draws the reminder tags (cosmetic display toggle). */
    showReminders: Boolean = true,
    /** PRD §14: flip the "Reminders" display switch. */
    onToggleReminders: (Boolean) -> Unit = {},
    /** PRD §8/§9 calendar history: Ctrl+Z / Ctrl+Y while the calendar holds keyboard focus. */
    onUndo: () -> Unit = {},
    onRedo: () -> Unit = {},
) {
    var offset by remember { mutableStateOf(Offset.Zero) }
    // PRD §8 zoom: the zoom mechanics live in WeekView (which owns the scroll state + viewport geometry,
    // so it can keep the point under the cursor fixed). The keyboard shortcuts here drive it through this
    // action holder; [ctrlHeld] tracks the Ctrl modifier so WeekView's scroll handler knows when a wheel
    // turn means "zoom toward the cursor".
    val zoomActions = remember { CalendarZoomActions() }
    var ctrlHeld by remember { mutableStateOf(false) }
    // PRD §8: the calendar owns the keyboard while it is the active surface, so its own shortcuts (O to
    // toggle overlap, Ctrl+Z/Y to undo/redo the calendar history, Ctrl +/- to zoom) work even though the
    // tree normally holds focus. Focus is (re)claimed when the window opens and on every press inside it.
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
                // Track Ctrl/Cmd on every event (down and up) so Ctrl+scroll zoom (below) knows the state.
                ctrlHeld = event.isCtrlPressed || event.isMetaPressed
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val mod = ctrlHeld
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
                    // PRD §8 zoom (toward the cursor): Ctrl + '+'/'=' (or numpad +) in, Ctrl + '-' out, Ctrl+0 reset.
                    mod && (event.key == Key.Equals || event.key == Key.Plus || event.key == Key.NumPadAdd) -> {
                        zoomActions.zoomIn()
                        true
                    }
                    mod && (event.key == Key.Minus || event.key == Key.NumPadSubtract) -> {
                        zoomActions.zoomOut()
                        true
                    }
                    mod && (event.key == Key.Zero || event.key == Key.NumPad0) -> {
                        zoomActions.reset()
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
                // PRD §14/§15: toggle whether reminders / side tasks are drawn (cosmetic; notifications keep
                // firing). The Switch consumes its own presses, so toggling it never starts the title-bar drag.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(end = 8.dp),
                ) {
                    Text(
                        text = "Reminders",
                        style = MaterialTheme.typography.labelMedium,
                        color = CalColors.muted,
                    )
                    Switch(
                        checked = showReminders,
                        onCheckedChange = onToggleReminders,
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(end = 8.dp),
                ) {
                    Text(
                        text = "Side tasks",
                        style = MaterialTheme.typography.labelMedium,
                        color = CalColors.muted,
                    )
                    Switch(
                        checked = showSideTasks,
                        onCheckedChange = onToggleSideTasks,
                    )
                }
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
                    zoomActions = zoomActions,
                    ctrlHeld = ctrlHeld,
                    onAddTaskAt = onAddTaskAt,
                    onAddCheckedReminderAt = onAddCheckedReminderAt,
                    onCommitBounds = onCommitBounds,
                    onEditEntry = onEditEntry,
                    onRemoveEntry = onRemoveEntry,
                    onToggleReminder = onToggleReminder,
                    onAdjustWeights = onAdjustWeights,
                    overlapArmed = overlapArmed,
                )
            }
        }
    }
}

/** PRD §8 zoom: the week grid's hour-row height at zoom 1f, and the zoom bounds / per-step factor. */
private val BASE_HOUR_HEIGHT = 48.dp
private const val MIN_CALENDAR_ZOOM = 0.5f
private const val MAX_CALENDAR_ZOOM = 16f
private const val CALENDAR_ZOOM_STEP = 1.15f

/** PRD §8: a graduation tick must be at least this tall (dp) to label legibly; below it we use a coarser one. */
private const val MIN_TICK_DP = 26f

/**
 * PRD §8 graduation: the minutes between time ticks for a given row [hourHeight] — finer as the user zooms
 * in. The finest of 60/30/15/10/5/1 minutes whose tick is still at least [MIN_TICK_DP] tall; falls back to
 * hourly when the grid is too short. Pure, so the zoom→graduation mapping is unit-tested.
 */
internal fun calendarTickMinutes(hourHeight: Dp): Int {
    val dpPerMinute = hourHeight.value / 60f
    return listOf(60, 30, 15, 10, 5, 1).lastOrNull { it * dpPerMinute >= MIN_TICK_DP } ?: 60
}

/**
 * PRD §8 zoom-to-cursor: the new vertical scroll (px) that keeps the content currently under [focalY] (px
 * from the viewport top) under that same pixel after the grid's height is scaled by [scaleFactor]. The
 * content offset under the cursor is `currentScroll + focalY`; scaling moves it to `(…)*scaleFactor`, so the
 * scroll that re-pins it is `(…)*scaleFactor - focalY`. Clamped to ≥ 0 (the caller clamps the upper bound to
 * the post-zoom scroll range). Pure, so the anchor math is unit-tested independently of Compose.
 */
internal fun zoomAnchoredScroll(currentScroll: Int, focalY: Float, scaleFactor: Float): Int =
    ((currentScroll + focalY) * scaleFactor - focalY).coerceAtLeast(0f).roundToInt()

/**
 * PRD §8 zoom: a holder the calendar's keyboard shortcuts (in [CalendarFloatingWindow]) use to drive the
 * zoom whose mechanics live in [WeekView] (which owns the scroll state + viewport geometry needed to keep
 * the point under the cursor fixed). WeekView assigns the lambdas; the key handler invokes them.
 */
private class CalendarZoomActions {
    var zoomIn: () -> Unit = {}
    var zoomOut: () -> Unit = {}
    var reset: () -> Unit = {}
}

@Composable
private fun WeekView(
    selectedDate: LocalDate,
    today: LocalDate,
    nowMillis: Long,
    records: List<CalendarRecord>,
    zoomActions: CalendarZoomActions,
    ctrlHeld: Boolean,
    onAddTaskAt: (Long) -> Unit,
    onAddCheckedReminderAt: (Long) -> Unit,
    onCommitBounds: (PlacedRecord, Long, Long, Boolean) -> Unit,
    onEditEntry: (PlacedRecord) -> Unit,
    onRemoveEntry: (PlacedRecord) -> Unit,
    onToggleReminder: (PlacedRecord) -> Unit,
    onAdjustWeights: (Map<String, Double>) -> Unit,
    overlapArmed: Boolean,
) {
    val weekStart = startOfWeek(selectedDate)
    val days = (0..6).map { weekStart.plus(it, DateTimeUnit.DAY) }
    val tz = remember { TimeZone.currentSystemDefault() }
    // Follows the (possibly simulated) clock so the now-line moves as accelerated time advances.
    val now = Instant.fromEpochMilliseconds(nowMillis).toLocalDateTime(tz).time
    // PRD §8 zoom: the row height scales with [zoom]; the gestures keep the point under the cursor fixed.
    var zoom by remember { mutableStateOf(1f) }
    val hourHeight = BASE_HOUR_HEIGHT * zoom
    val gutterWidth = 56.dp

    val scrollState = rememberScrollState()
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    // PRD §8 zoom-to-cursor: the pointer's Y within the scroll viewport (the focal point a zoom pivots
    // around) and the viewport height (the fallback focal — its centre — for keyboard zoom with no cursor).
    var focalYpx by remember { mutableStateOf<Float?>(null) }
    var viewportHpx by remember { mutableStateOf(0f) }
    // PRD §8: serialize zoom steps. A fast wheel/keyboard burst launches several applyZoom coroutines; if
    // they interleaved, each would read a `scrollState.value`/`zoom` that doesn't yet reflect the others'
    // pending scrollTo while `zoom` had already jumped several steps, so the final scroll would be computed
    // for a smaller zoom than actually applied and the anchored point would jump (teleport up). The lock
    // makes each step read settled state and apply its own scrollTo before the next begins.
    val zoomMutex = remember { Mutex() }
    // Apply a zoom [factor] keeping the time at [focal] (px from the viewport top) under that same pixel:
    // the content offset there is `scroll + focal`; after scaling it becomes `(scroll + focal) * f`, so the
    // new scroll that puts it back under `focal` is `(scroll + focal) * f - focal`.
    suspend fun applyZoom(factor: Float, focal: Float) = zoomMutex.withLock {
        val next = (zoom * factor).coerceIn(MIN_CALENDAR_ZOOM, MAX_CALENDAR_ZOOM)
        if (next == zoom) return@withLock
        val f = next / zoom
        val target = zoomAnchoredScroll(scrollState.value, focal, f)
        zoom = next
        // scrollTo clamps to the *current* scroll range, which only grows after the taller grid re-lays
        // out. So when zooming in (especially near the bottom, e.g. the evening) wait — bounded — for the
        // range to catch up to `target`; otherwise the target is clamped short and the point drifts up.
        var guard = 0
        while (scrollState.maxValue < target && guard < 8) {
            withFrameNanos { }
            guard++
        }
        scrollState.scrollTo(target)
    }
    // Register the keyboard shortcuts (driven from CalendarFloatingWindow). They pivot around the cursor if
    // it is over the grid, else the viewport centre.
    zoomActions.zoomIn = { scope.launch { applyZoom(CALENDAR_ZOOM_STEP, focalYpx ?: viewportHpx / 2f) } }
    zoomActions.zoomOut = { scope.launch { applyZoom(1f / CALENDAR_ZOOM_STEP, focalYpx ?: viewportHpx / 2f) } }
    zoomActions.reset = { scope.launch { applyZoom(1f / zoom, focalYpx ?: viewportHpx / 2f) } }
    val ctrl = rememberUpdatedState(ctrlHeld)

    // PRD §8 (Google-Calendar style): open scrolled to the current time so today's "task to do now"
    // block (which starts at the present hour) is visible without manual scrolling. Show one hour of
    // lead context above it.
    LaunchedEffect(Unit) {
        val target = with(density) { (hourHeight * (now.hour - 1)).toPx() }
        scrollState.scrollTo(target.roundToInt().coerceAtLeast(0))
    }

    // PRD §8: while a block is being dragged/resized, lock the grid's vertical scroll so it doesn't
    // compete with the block's own drag gesture.
    var scrollLocked by remember { mutableStateOf(false) }

    // PRD §8 "there must not be overlaps" (default mode): every block on the calendar (records,
    // scheduled, manual) as (key, range), so a dragged block snaps around ALL of them live. Reminder tags
    // (zero-duration, §14) and side-task markers (§15) are not blocks and are excluded.
    val allBlocks = records.filterNot { it.reminder || it.sideTask }.map { calendarBlockKey(it) to it.range }

    // PRD §8 hover title: the block/side-task under the cursor, reported up from each element so a single
    // non-interactive overlay (below) draws the bubble. [viewportCoords] anchors the bubble in viewport
    // (non-scrolling) space; [hoverScope] is threaded down to every hoverable element.
    var titleHover by remember { mutableStateOf<CalendarTitleHover?>(null) }
    var viewportCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val hoverScope = remember {
        CalendarTitleHoverScope(
            viewportCoords = { viewportCoords },
            currentOwner = { titleHover?.ownerId },
            onHover = { titleHover = it },
        )
    }

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

        // The viewport box wraps the scrolling grid and the hover-bubble overlay. The overlay is a sibling
        // of (and drawn above) the scroll content, so the bubble floats over every column without being
        // clipped or occluded by a neighbouring column, and it does not scroll with the grid.
        Box(Modifier.fillMaxSize().onGloballyPositioned { viewportCoords = it }) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { viewportHpx = it.height.toFloat() }
                // PRD §8 zoom-to-cursor: track the cursor's Y in the viewport, and on Ctrl+scroll zoom
                // toward it (consumed at the Initial pass so the grid doesn't also scroll). A plain wheel
                // turn isn't consumed, so it falls through to the verticalScroll below. Ctrl is read from the
                // scroll event's own keyboard modifiers (not the focus-tracked [ctrl]) so zoom works whenever
                // the cursor is over the calendar — even if it doesn't hold keyboard focus. Pointer hit-testing
                // means a panel drawn over the calendar receives the wheel instead, so it correctly "doesn't count".
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            event.changes.firstOrNull()?.let { focalYpx = it.position.y }
                            val zoomModifier = event.keyboardModifiers.pointerCtrlPressed ||
                                event.keyboardModifiers.pointerMetaPressed || ctrl.value
                            if (event.type == PointerEventType.Scroll && zoomModifier) {
                                val change = event.changes.firstOrNull()
                                val dy = change?.scrollDelta?.y ?: 0f
                                if (dy != 0f) {
                                    val focal = change!!.position.y
                                    scope.launch {
                                        applyZoom(if (dy < 0f) CALENDAR_ZOOM_STEP else 1f / CALENDAR_ZOOM_STEP, focal)
                                    }
                                    event.changes.forEach { it.consume() }
                                }
                            }
                        }
                    }
                }
                .verticalScroll(scrollState, enabled = !scrollLocked),
        ) {
            Row(Modifier.fillMaxWidth().height(hourHeight * 24)) {
                // Time gutter: hour labels, plus sub-hour minute labels (":30", ":15", …) once zoomed in.
                Column(Modifier.width(gutterWidth)) {
                    val tick = calendarTickMinutes(hourHeight)
                    val tickHeight = hourHeight * (tick / 60f)
                    var minutes = 0
                    while (minutes < 24 * 60) {
                        val hour = minutes / 60
                        val minute = minutes % 60
                        Box(Modifier.height(tickHeight).fillMaxWidth().padding(end = 6.dp)) {
                            Text(
                                text = if (minute == 0) hourLabel(hour) else ":" + minute.toString().padStart(2, '0'),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (minute == 0) CalColors.muted else CalColors.muted.copy(alpha = 0.5f),
                                textAlign = TextAlign.End,
                                modifier = Modifier.fillMaxWidth().offset(y = (-6).dp),
                            )
                        }
                        minutes += tick
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
                        onAddCheckedReminderAt = onAddCheckedReminderAt,
                        onCommitBounds = onCommitBounds,
                        onEditEntry = onEditEntry,
                        onRemoveEntry = onRemoveEntry,
                        onToggleReminder = onToggleReminder,
                        onLockScroll = { scrollLocked = it },
                        onAdjustWeights = onAdjustWeights,
                        allBlocks = allBlocks,
                        overlapArmed = overlapArmed,
                        hoverScope = hoverScope,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        // PRD §8 hover title bubble, drawn above all columns; non-interactive so the cursor passes through.
        titleHover?.let { CalendarTitleBubble(it.title, it.pos, it.subtitle) }
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
    onAddCheckedReminderAt: (Long) -> Unit,
    onCommitBounds: (PlacedRecord, Long, Long, Boolean) -> Unit,
    onEditEntry: (PlacedRecord) -> Unit,
    onRemoveEntry: (PlacedRecord) -> Unit,
    onToggleReminder: (PlacedRecord) -> Unit,
    onLockScroll: (Boolean) -> Unit,
    onAdjustWeights: (Map<String, Double>) -> Unit,
    allBlocks: List<Pair<String, TaskTimeRange>>,
    overlapArmed: Boolean,
    hoverScope: CalendarTitleHoverScope,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    // PRD §14/§15: reminders (zero-duration) and side tasks (sub-minute durations) render on their own
    // fixed-height marker paths; everything else is a height-proportional, draggable block. Split them so
    // the block pipeline only sees real blocks (drawing side tasks to scale would make them invisible).
    val reminderTags = records.filter { it.reminder }
    val sideTaskMarkers = records.filter { it.sideTask }
    val blockRecords = records.filterNot { it.reminder || it.sideTask }
    // The right-click position (in this column's local pixels) that anchors the contextual menu; null
    // when no menu is open. [menuTarget] is the block the click landed on (null = empty space).
    var menuOffset by remember { mutableStateOf<Offset?>(null) }
    var menuTarget by remember { mutableStateOf<PlacedRecord?>(null) }
    // Latest records, so the right-click hit-test closure never reads a stale list (records change
    // every scheduler tick) without restarting the long-lived gesture coroutine.
    val currentRecords by rememberUpdatedState(blockRecords)

    // PRD §8 Overlap Mode: live width-edge drag. While a weight handle is held this maps panel ids to
    // their in-progress weights so the layout (and the handle position) follow the drag; on release the
    // weights are committed via [onAdjustWeights] and this clears.
    var weightDrag by remember { mutableStateOf<Map<String, Double>?>(null) }
    val effRecords =
        weightDrag?.let { wd ->
            blockRecords.map { r -> r.entryIds.firstNotNullOfOrNull { wd[it] }?.let { r.copy(layoutWeight = it) } ?: r }
        } ?: blockRecords

    // PRD §8 Overlap Mode: live move/resize preview. The block being moved reports its in-progress bounds
    // here; [liveRecords] places it there so the shared (sliced) layout is recomputed and drawn as an
    // overlay — the panels narrow and sit side by side live instead of one literally covering another. The
    // resting slices keep their committed positions (so the drag gesture node never changes and is never
    // cancelled); they are hidden while the overlay shows.
    var dragPreview by remember { mutableStateOf<Pair<String, TaskTimeRange>?>(null) }
    val previewActive = dragPreview != null
    val midnightMillis = LocalDateTime(day.year, day.month, day.day, 0, 0)
        .toInstant(tz).toEpochMilliseconds()
    val liveRecords =
        dragPreview?.let { (dragKey, range) ->
            val startHour = ((range.startEpochMillis - midnightMillis) / 3_600_000f).coerceIn(0f, 24f)
            val endHour = ((range.endEpochMillis - midnightMillis) / 3_600_000f).coerceIn(0f, 24f)
            if (endHour <= startHour) {
                effRecords
            } else {
                effRecords.map {
                    if (calendarBlockKey(it) == dragKey) it.copy(startHour = startHour, endHour = endHour) else it
                }
            }
        } ?: effRecords

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
        // PRD §8 graduation: once zoomed in, faint sub-hour lines at the current tick (every 30/15/10/5/1
        // min); the on-hour lines are already drawn above. One draw pass (cheap regardless of zoom).
        val tickMinutes = calendarTickMinutes(hourHeight)
        if (tickMinutes < 60) {
            val faint = CalColors.grid.copy(alpha = 0.4f)
            Box(
                Modifier.fillMaxSize().drawBehind {
                    val stepPx = hourHeight.toPx() * (tickMinutes / 60f)
                    val ticksPerHour = 60 / tickMinutes
                    var i = 1
                    var y = stepPx
                    while (y <= size.height) {
                        if (i % ticksPerHour != 0) { // skip on-hour lines (drawn above)
                            drawLine(faint, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
                        }
                        i++
                        y += stepPx
                    }
                },
            )
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
                DropdownMenuItem(
                    text = { Text("add a checked reminder") },
                    onClick = {
                        anchor?.let { onAddCheckedReminderAt(millisAt(it.y)) }
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
                // Every other block — everything but itself — so a non-overlap drag/resize snaps around them.
                others = allBlocks.filter { it.first != key }.map { it.second },
                overlapArmed = overlapArmed,
                // Hide the resting (gesture-holding) slices while any move/resize preview overlay shows.
                previewActive = previewActive,
                onPreviewChange = { range -> dragPreview = range?.let { key to it } },
                onCommitBounds = onCommitBounds,
                onLockScroll = onLockScroll,
                hoverScope = hoverScope,
                tz = tz,
            )
        }

        // PRD §8 Overlap Mode: draggable vertical edges between overlapping panels (re-divide width).
        // Overlaid above the slices at each shared boundary; a horizontal drag moves weight between the
        // two adjacent panels (the others' shares stay fixed), committed on release.
        val handles = weightHandles(effRecords)
        if (handles.isNotEmpty() && !previewActive) {
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

        // PRD §8 Overlap Mode: live move/resize preview overlay — the panels at their in-progress layout
        // (the dragged one substituted to its preview position), sliced so overlaps share width side by
        // side as the drag happens. Purely visual; the resting slices underneath hold the gesture.
        if (previewActive) {
            val liveLayout = overlapLayout(liveRecords)
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val colWidth = maxWidth
                liveRecords.forEach { rec ->
                    val recKey = calendarBlockKey(rec)
                    val recSlices = liveLayout[recKey]
                        ?: listOf(PanelSlice(rec.startHour, rec.endHour, xFraction = 0f, widthFraction = 1f))
                    recSlices.forEachIndexed { idx, slice ->
                        Box(
                            modifier = Modifier
                                .offset(x = colWidth * slice.xFraction, y = hourHeight * slice.topHour)
                                .width(colWidth * slice.widthFraction)
                                .height(hourHeight * (slice.bottomHour - slice.topHour))
                                .padding(horizontal = 1.dp),
                        ) {
                            CalendarBlockBody(CalColors.event, rec.title, showTitle = idx == 0)
                        }
                    }
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

        // PRD §14 Reminders: zero-duration checkable tags, placed by three rules. A still-future, unchecked
        // reminder sits at its scheduled time. An unchecked reminder whose time has passed is *overdue* and
        // accumulates on the live now-line, stacked top-down (so it tracks the clock until dealt with). A
        // checked reminder FREEZES at the moment it was checked ([checkedAtMillis]): it neither snaps back to
        // its scheduled slot nor keeps following the now-line. Clicking a tag toggles its checked state.
        val nowHour = now?.let { it.hour + it.minute / 60f }
        fun checkedAtHour(tag: PlacedRecord): Float? =
            tag.checkedAtMillis?.let {
                val t = Instant.fromEpochMilliseconds(it).toLocalDateTime(tz).time
                t.hour + t.minute / 60f
            }
        fun onNowLine(tag: PlacedRecord) = nowHour != null && !tag.checked && tag.startHour <= nowHour
        reminderTags.filterNot(::onNowLine).forEach { tag ->
            val y = checkedAtHour(tag) ?: tag.startHour
            ReminderTag(tag, Modifier.offset(y = hourHeight * y)) { onToggleReminder(tag) }
        }
        reminderTags.filter(::onNowLine).forEachIndexed { i, tag ->
            ReminderTag(tag, Modifier.offset(y = hourHeight * (nowHour ?: 0f) + REMINDER_TAG_HEIGHT * i)) {
                onToggleReminder(tag)
            }
        }

        // PRD §15 Side tasks: drawn as real time-positioned bands spanning their true duration, so the §9
        // fill leaves an exact gap for each one (no overlap with the surrounding task, no stray white where
        // a multi-minute rest pause sits). A sub-minute look-away therefore renders as a hairline; the 5/15-
        // min rest pauses fill their region. A small minimum height keeps even a hairline visible/hoverable.
        // Coinciding side tasks (e.g. the hourly and 2-hourly pose both due now) share the column width side
        // by side via [overlapLayout], exactly like overlapping task blocks.
        if (sideTaskMarkers.isNotEmpty()) {
            val sideLayout = overlapLayout(sideTaskMarkers)
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val colWidth = maxWidth
                sideTaskMarkers.forEach { marker ->
                    val key = calendarBlockKey(marker)
                    val slices = sideLayout[key]
                        ?: listOf(PanelSlice(marker.startHour, marker.endHour, xFraction = 0f, widthFraction = 1f))
                    slices.forEach { slice ->
                        SideTaskBand(marker, slice, hourHeight, colWidth, tz, hoverScope)
                    }
                }
            }
        }
    }
}

/** PRD §14: a reminder rendered as a small checkable chip on the calendar (not a draggable block). */
private val REMINDER_TAG_HEIGHT = 18.dp

/** PRD §15: smallest rendered height for a side-task band, so a sub-minute look-away stays a visible hairline. */
private val SIDE_TASK_MIN_HEIGHT = 3.dp

/** PRD §15: a side-task band only draws its title (●/name) once it is at least this tall; shorter ones are bare. */
private val SIDE_TASK_LABEL_MIN_HEIGHT = 13.dp

/**
 * PRD §15 Side task, rendered as a real time-positioned band (one [overlapLayout] slice of it) spanning its
 * true duration so the §9 fill leaves an exact gap for it. Sub-minute side tasks render at [SIDE_TASK_MIN_HEIGHT]
 * (a hairline); the title is drawn only when the band is tall enough ([SIDE_TASK_LABEL_MIN_HEIGHT]). The full
 * name always shows on hover (PRD §8), anchored at the cursor so zoom never floats the bubble off-screen, with
 * the side task's true (un-clipped) start–end times on a second line — the same bubble blocks get.
 */
@Composable
private fun SideTaskBand(
    marker: PlacedRecord,
    slice: PanelSlice,
    hourHeight: Dp,
    colWidth: Dp,
    tz: TimeZone,
    hoverScope: CalendarTitleHoverScope,
) {
    val height = (hourHeight * (slice.bottomHour - slice.topHour)).coerceAtLeast(SIDE_TASK_MIN_HEIGHT)
    val showLabel = height >= SIDE_TASK_LABEL_MIN_HEIGHT
    val timeRange = "${formatHm(marker.fullStartMillis, tz)} – ${formatHm(marker.fullEndMillis, tz)}"
    Row(
        modifier = Modifier
            .offset(x = colWidth * slice.xFraction, y = hourHeight * slice.topHour)
            .width(colWidth * slice.widthFraction)
            .height(height)
            .calendarTitleHover(marker.title, hoverScope, subtitle = timeRange)
            .padding(horizontal = 1.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(CalColors.accent)
            .then(if (showLabel) Modifier.padding(horizontal = 4.dp) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (showLabel) {
            Text(
                text = "●",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
            )
            Text(
                text = marker.title,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * PRD §8 hover title: which block/side-task the cursor is currently over and where. [pos] is the cursor
 * position in the calendar **viewport's** coordinates (not the scrolling content), so the bubble overlay
 * sits next to the pointer and follows it even while the grid scrolls under a still cursor. [ownerId]
 * identifies the reporting element so a stale `Exit` from the element the cursor just left can't clear a
 * hover the newly entered element has already set.
 */
private class CalendarTitleHover(
    val ownerId: Any,
    val title: String,
    val pos: Offset,
    /** PRD §8: a second bubble line — the panel's start–end times; null for elements without a time range. */
    val subtitle: String? = null,
)

/**
 * Plumbing handed to every hoverable calendar element so it can report the title under the cursor up to the
 * single viewport-level bubble overlay. Driving the bubble from the elements (rather than a foundation
 * `TooltipArea`/`Popup`) is what fixes the "catch the bubble" bug: the popup used to be its own hit-test
 * layer that stole hover the instant the cursor reached it, freezing the title; the overlay this feeds is a
 * non-interactive layer (no pointer-input node), so the cursor passes through it to the block beneath, which
 * keeps reporting and the bubble keeps tracking.
 */
private class CalendarTitleHoverScope(
    val viewportCoords: () -> LayoutCoordinates?,
    val currentOwner: () -> Any?,
    val onHover: (CalendarTitleHover?) -> Unit,
)

/**
 * Reports [title] (and the cursor's viewport position) to [scope] while the pointer is over this element, and
 * clears it on exit. Observes pointer events at the Main pass without consuming them, so it never interferes
 * with the block's drag/resize gesture or the column's right-click menu.
 */
@OptIn(ExperimentalComposeUiApi::class)
private fun Modifier.calendarTitleHover(
    title: String,
    scope: CalendarTitleHoverScope,
    subtitle: String? = null,
): Modifier = composed {
    val ownerId = remember { Any() }
    var coords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val report: (Offset) -> Unit = report@{ local ->
        val viewport = scope.viewportCoords()?.takeIf { it.isAttached } ?: return@report
        val self = coords?.takeIf { it.isAttached } ?: return@report
        scope.onHover(CalendarTitleHover(ownerId, title, viewport.localPositionOf(self, local), subtitle))
    }
    this
        .onGloballyPositioned { coords = it }
        .onPointerEvent(PointerEventType.Enter) { report(it.changes.first().position) }
        .onPointerEvent(PointerEventType.Move) { report(it.changes.first().position) }
        .onPointerEvent(PointerEventType.Exit) { if (scope.currentOwner() === ownerId) scope.onHover(null) }
}

/**
 * The hover title bubble (PRD §8), drawn at [pos] (+16dp below the cursor, mirroring the old cursor-anchored
 * placement). Rendered with plain draw modifiers only — no `Surface`/clickable/pointer-input — so it is
 * invisible to hit-testing and the cursor falls through to the block underneath even when it overtakes the
 * bubble during a fast scroll, keeping the title live.
 */
@Composable
private fun CalendarTitleBubble(text: String, pos: Offset, subtitle: String? = null) {
    val yOffsetPx = with(LocalDensity.current) { 16.dp.roundToPx() }
    Column(
        Modifier
            .offset { IntOffset(pos.x.roundToInt(), pos.y.roundToInt() + yOffsetPx) }
            .shadow(4.dp, RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.inverseSurface, RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(
            text = text.ifEmpty { "(untitled)" },
            color = MaterialTheme.colorScheme.inverseOnSurface,
            style = MaterialTheme.typography.labelMedium,
        )
        // PRD §8: the panel's start–end times, shown as a second line when the hovered element has them.
        if (subtitle != null) {
            Text(
                text = subtitle,
                color = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.8f),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun ReminderTag(tag: PlacedRecord, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(REMINDER_TAG_HEIGHT)
            .padding(horizontal = 2.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(if (tag.checked) CalColors.muted.copy(alpha = 0.3f) else CalColors.accent)
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = if (tag.checked) "☑" else "☐",
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
        )
        Text(
            text = tag.title,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
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
    others: List<TaskTimeRange>,
    overlapArmed: Boolean,
    /** True while a move/resize preview overlay is showing — hide these (gesture-only) resting slices. */
    previewActive: Boolean,
    /** Reports the in-progress drag bounds (null when not dragging) so the column can draw the live overlay. */
    onPreviewChange: (TaskTimeRange?) -> Unit,
    onCommitBounds: (PlacedRecord, Long, Long, Boolean) -> Unit,
    onLockScroll: (Boolean) -> Unit,
    hoverScope: CalendarTitleHoverScope,
    tz: TimeZone,
) {
    val key = calendarBlockKey(record)
    // Read inside the long-lived gesture closure so a mid-drag `O` toggle is picked up immediately.
    val armed = rememberUpdatedState(overlapArmed)
    val density = LocalDensity.current
    val hourHeightPx = with(density) { hourHeight.toPx() }
    val minPx = with(density) { 2.dp.toPx() }
    val entry = TaskTimeRange(record.fullStartMillis, record.fullEndMillis)
    val duration = record.fullEndMillis - record.fullStartMillis

    // Accumulated drag distance (px) for the active gesture; reset after each commit. The live position
    // is reported via [onPreviewChange] and drawn by the column's overlay, not from local preview state.
    var dragPx by remember(key) { mutableStateOf(0f) }

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
                    // These resting slices (at committed positions) hold the move/resize gesture. While a
                    // preview overlay shows they are hidden — but stay mounted so the gesture is never
                    // cancelled — and the column's live overlay draws the in-progress shared layout.
                    .alpha(if (previewActive) 0f else 1f)
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
                                    }
                                    change.consume()
                                    if (started) {
                                        dragPx += delta.y
                                        // Report the live bounds so the column draws the shared preview overlay.
                                        onPreviewChange(if (edge != null) resizedBounds(edge) else movedBounds())
                                    }
                                }
                            } finally {
                                onLockScroll(false)
                                dragPx = 0f
                                onPreviewChange(null)
                            }
                        }
                    },
            ) {
                // PRD §8: the title shows on hover. Reported up to the viewport-level bubble (anchored to the
                // cursor, not the block's top) so a tall, zoomed-in block still pops its bubble right where the
                // pointer is — and the cursor can pass through the bubble without freezing it.
                // PRD §8: the hover bubble also shows the panel's true (un-clipped) start–end times.
                val timeRange = "${formatHm(record.fullStartMillis, tz)} – ${formatHm(record.fullEndMillis, tz)}"
                Box(Modifier.fillMaxSize().calendarTitleHover(record.title, hoverScope, subtitle = timeRange)) {
                    // The title is written only on the topmost slice so a stepped block reads as one.
                    CalendarBlockBody(color, record.title, showTitle = isFirst)
                }
                // PRD §8 extend/shorten: a thin hover zone on the block's true top/bottom edge shows the
                // standard resize cursor, indicating the user can grab the edge to resize.
                if (isFirst) {
                    Box(
                        Modifier.align(Alignment.TopCenter).fillMaxWidth().height(6.dp)
                            .pointerHoverIcon(verticalResizePointerIcon()),
                    )
                }
                if (isLast) {
                    Box(
                        Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(6.dp)
                            .pointerHoverIcon(verticalResizePointerIcon()),
                    )
                }
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
                    EditMenuSectionLabel("Tasks")
                    taskEntries.forEachIndexed { index, entry ->
                        EditMenuRow(
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
                    EditMenuSectionLabel("Title suggestions")
                    suggestions.forEach { suggestion ->
                        EditMenuRow(
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

/**
 * PRD §14 "add a checked reminder": a floating editor opened from the calendar's right-click menu. Mirrors
 * [ManualEntryEditWindow] but for reminders (which have a title and a stable id): a title field with a
 * **Reminders** id menu — a leading **"New Reminder"** row (record the check against a brand-new, distinct
 * reminder) followed by existing reminders by id (pick one → fills the title) — and a **Title suggestions**
 * menu, plus a single **Time** field on the right pre-filled with the right-click time. Save records an
 * already-checked reminder at that time for the chosen reminder id. Rendered by [org.example.project.App].
 */
@Composable
fun ReminderCheckEditWindow(
    initialMillis: Long,
    tz: TimeZone,
    reminderMenuEntries: (draftText: String) -> List<SchedulerDomain.ReminderMenuEntry>,
    titleSuggestions: (String) -> List<String>,
    reminderIdForTitle: (String) -> String?,
    titleForReminderId: (String) -> String?,
    onSave: (reminderId: String, title: String, atMillis: Long) -> Unit,
    onDismiss: () -> Unit,
) {
    var title by remember { mutableStateOf("") }
    // The explicitly-picked reminder id, if any; typing reverts to resolving the id from the title. The
    // empty string is a distinct sentinel meaning the user explicitly picked "New Reminder" (a brand-new,
    // distinct reminder) even when the typed title matches an existing one; null means "no explicit pick".
    var selectedReminderId by remember { mutableStateOf<String?>(null) }
    var timeText by remember { mutableStateOf(formatHm(initialMillis, tz)) }

    val entries = reminderMenuEntries(title)
    // The reminder this window will save against: an explicit "New Reminder" pick (blank), else the explicit
    // id pick (while still in the menu), else the reminder whose title matches what's typed, else blank (a
    // brand-new reminder with no recurrence yet).
    val effectiveReminderId =
        when {
            selectedReminderId == "" -> ""
            selectedReminderId != null && entries.any { it.id == selectedReminderId } -> selectedReminderId!!
            else -> reminderIdForTitle(title) ?: ""
        }

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
            modifier = Modifier.width(320.dp).clickable(enabled = false) {},
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Add a checked reminder", style = MaterialTheme.typography.titleSmall)

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = title,
                        onValueChange = {
                            title = it
                            selectedReminderId = null // typing resolves the id from the title again
                        },
                        label = { Text("Reminder") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = timeText,
                        onValueChange = { timeText = it },
                        label = { Text("Time") },
                        singleLine = true,
                        modifier = Modifier.width(96.dp),
                    )
                }

                // --- Reminders id menu + Title suggestions, shared with the reminders manager. No mode
                // selector here: this window is always in "Change Reminder" mode (PRD §14). "New Reminder"
                // records the check against a brand-new, distinct reminder (effective id blank); picking an
                // existing reminder attaches the check to that id. ---
                ReminderEditModeMenus(
                    mode = ReminderEditMode.Change,
                    onSelectMode = {},
                    showModeSelector = false,
                    idMenuEntries = entries,
                    newReminderSelected = effectiveReminderId == "",
                    selectedEntryId = effectiveReminderId.takeIf { it.isNotEmpty() },
                    onPickNewReminder = { selectedReminderId = "" },
                    onPickEntry = { entry ->
                        selectedReminderId = entry.id
                        titleForReminderId(entry.id)?.let { title = it }
                    },
                    titleSuggestions = titleSuggestions(title).take(8),
                    onPickSuggestion = { suggestion ->
                        title = suggestion
                        selectedReminderId = reminderIdForTitle(suggestion)
                    },
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        enabled = title.isNotBlank(),
                        onClick = {
                            val at = parseHmOnDateOf(timeText, initialMillis, tz) ?: initialMillis
                            onSave(effectiveReminderId, title, at)
                        },
                    ) { Text("Save") }
                }
            }
        }
    }
}

/**
 * PRD §14: the edit modes for a reminder title field, mirroring a task cell's Edit Mode (PRD §4):
 * [Change] picks *which* reminder this editor refers to (the id menu is shown), [Rename] edits the current
 * reminder's title in place (the id menu is hidden). Title suggestions show in both modes.
 */
private enum class ReminderEditMode { Change, Rename }

/** One choice in the shared [EditModeSelector]: its [label], whether it is [selected], and the pick handler. */
data class EditModeOption(val label: String, val selected: Boolean, val onSelect: () -> Unit)

/**
 * The shared **Mode** selector used by every edit-mode editor — the task tree cell (PRD §4), the reminders
 * manager and the "add a checked reminder" window (PRD §14) — so they all render the identical control: a
 * "Mode" header above a drop-down **button** (a bordered anchor showing the current mode and a ▾ caret) that
 * opens a menu of the [options].
 *
 * [focusPreserving] keeps a focus-gated editor (the reminders manager, whose menus live only while the title
 * field holds focus) open while the menu is used: the anchor opens via a raw tap (not `Modifier.clickable`,
 * which would steal focus) and the popup is **non-focusable**, so the title field never loses focus — a focus
 * change there would collapse the editor before a pick could register. The same control works as a normal
 * focusable dropdown everywhere else.
 */
@Composable
fun EditModeSelector(options: List<EditModeOption>, focusPreserving: Boolean = false) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = options.firstOrNull { it.selected }?.label ?: options.firstOrNull()?.label ?: ""
    EditMenuSectionLabel("Mode")
    Box {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .border(1.dp, CalColors.grid, RoundedCornerShape(4.dp))
                .then(
                    if (focusPreserving)
                        Modifier.pointerInput(Unit) { detectTapGestures { expanded = !expanded } }
                    else Modifier.clickable { expanded = !expanded }
                )
                .padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(selectedLabel, style = MaterialTheme.typography.bodyMedium)
            Text("▾", style = MaterialTheme.typography.bodySmall, color = CalColors.muted)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            // Non-focusable in a focus-gated editor so opening the menu does not blur the title field and
            // collapse the editor; a normal focusable popup (with outside-tap dismissal) everywhere else.
            properties = PopupProperties(focusable = !focusPreserving),
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    onClick = {
                        option.onSelect()
                        expanded = false
                    },
                )
            }
        }
    }
}

/** Shared section header above an edit-mode menu list ("Mode", "Tasks", "Reminders", "Title suggestions"). */
@Composable
fun EditMenuSectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * The shared look for every edit-mode menu row — the task tree cell, the calendar windows and the reminders
 * manager all render through this one composable, so the Mode / id / title menus look identical everywhere.
 * Callers differ only in behaviour:
 *  - [enabled] dims and disables the row (e.g. an unassignable task).
 *  - [focusPreserving] selects via a raw tap gesture instead of `Modifier.clickable`, so a pick does NOT pull
 *    focus off a title field. The reminders manager's editor stays open only while its row has focus, so a
 *    focus change there would collapse it before the pick registered; elsewhere `Modifier.clickable` is used.
 *    [onClick] is read through [rememberUpdatedState] so the once-started tap detector always runs the latest closure.
 */
@Composable
fun EditMenuRow(
    label: String,
    selected: Boolean = false,
    enabled: Boolean = true,
    focusPreserving: Boolean = false,
    onClick: () -> Unit,
) {
    val currentOnClick by rememberUpdatedState(onClick)
    val clickModifier = when {
        !enabled -> Modifier
        focusPreserving -> Modifier.pointerInput(Unit) { detectTapGestures { currentOnClick() } }
        else -> Modifier.clickable(onClick = onClick)
    }
    Text(
        modifier = Modifier
            .fillMaxWidth()
            .then(clickModifier)
            // Selected rows are marked with an obvious outline rather than a (subtle) purple font.
            .then(
                if (selected)
                    Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp))
                else Modifier
            )
            .padding(vertical = 4.dp, horizontal = 8.dp),
        text = label,
        style =
            if (selected) MaterialTheme.typography.bodyMedium
            else MaterialTheme.typography.bodySmall,
        color =
            if (enabled) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
    )
}

/**
 * PRD §14: the shared edit-mode menu block for a reminder title field — used by both the reminders manager
 * ([ChoresManagerWindow]) and the "add a checked reminder" window ([ReminderCheckEditWindow]), the reminder
 * counterpart of the task cell's `EditModeMenus`. It renders a **Mode** selector ([ReminderEditMode]), then —
 * in [ReminderEditMode.Change] only, and only when at least one existing reminder matches — a **Reminders**
 * id menu led by a "New Reminder" row, and finally a **Title suggestions** menu shown in both modes. All rows
 * use [ReminderMenuRow] (a focus-preserving tap), so the block works inside the manager's focus-gated editor
 * without a dropdown stealing focus. Selection/picking semantics are supplied by the caller.
 */
@Composable
private fun ReminderEditModeMenus(
    mode: ReminderEditMode,
    onSelectMode: (ReminderEditMode) -> Unit,
    idMenuEntries: List<SchedulerDomain.ReminderMenuEntry>,
    newReminderSelected: Boolean,
    selectedEntryId: String?,
    onPickNewReminder: () -> Unit,
    onPickEntry: (SchedulerDomain.ReminderMenuEntry) -> Unit,
    titleSuggestions: List<String>,
    onPickSuggestion: (String) -> Unit,
    /**
     * PRD §14: whether to show the Change Reminder / Rename mode selector. The "add a checked reminder"
     * window passes false — it is always in Change Reminder mode, so the selector never appears there.
     */
    showModeSelector: Boolean = true,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (showModeSelector) {
            // focusPreserving = true: this is a focus-gated editor (the menus live only while the title field
            // keeps focus), so the dropdown must open/select without pulling focus off the field.
            EditModeSelector(
                options = listOf(
                    EditModeOption(
                        label = "Change Reminder",
                        selected = mode == ReminderEditMode.Change,
                        onSelect = { onSelectMode(ReminderEditMode.Change) },
                    ),
                    EditModeOption(
                        label = "Rename",
                        selected = mode == ReminderEditMode.Rename,
                        onSelect = { onSelectMode(ReminderEditMode.Rename) },
                    ),
                ),
                focusPreserving = true,
            )
        }

        // Identity (id) menu — Change mode only, and (mirroring the task cell, which shows its Tasks menu
        // only beyond "New task") only when an existing reminder matches. Leads with a "New Reminder" row.
        if (mode == ReminderEditMode.Change && idMenuEntries.isNotEmpty()) {
            EditMenuSectionLabel("Reminders")
            EditMenuRow(
                label = "New Reminder",
                selected = newReminderSelected,
                focusPreserving = true,
                onClick = onPickNewReminder,
            )
            idMenuEntries.forEach { entry ->
                EditMenuRow(
                    label = entry.title,
                    selected = entry.id == selectedEntryId,
                    focusPreserving = true,
                    onClick = { onPickEntry(entry) },
                )
            }
        }

        // Title suggestions — shown in both modes (in Rename mode, picking one renames to that title).
        if (titleSuggestions.isNotEmpty()) {
            EditMenuSectionLabel("Title suggestions")
            titleSuggestions.forEach { suggestion ->
                EditMenuRow(label = suggestion, focusPreserving = true, onClick = { onPickSuggestion(suggestion) })
            }
        }
    }
}

