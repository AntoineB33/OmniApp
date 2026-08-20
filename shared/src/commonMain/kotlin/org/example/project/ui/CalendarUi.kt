package org.example.project.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
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
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
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
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.roundToInt
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
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import org.example.project.OmniPage
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.ChoreEntry
import org.example.project.scheduler.model.ChoreRecurrenceUnit
import org.example.project.scheduler.model.PanelPins
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.model.TaskTimeRange
import org.example.project.scheduler.persistence.ActiveSessionRecord
import org.example.project.scheduler.state.CalendarEdge
import org.example.project.scheduler.state.HistoryCategory
import org.example.project.scheduler.state.HistoryUnit
import org.example.project.scheduler.state.NotificationLogEntry
import org.example.project.scheduler.state.SupabaseUsageEntry
import org.example.project.scheduler.state.SchedulerHistories
import org.example.project.scheduler.state.SchedulerHistory
import org.example.project.scheduler.state.SchedulerState

/** PRD §7: visual language shared by the lateral menu and the calendar. */
private object CalColors {
    val accent = Color(0xFF1A73E8) // Google-blue accent
    val today = Color(0xFFE8F0FE)
    val now = Color(0xFFD93025)
    val grid = Color(0xFFDADCE0)
    val menuBackground = Color(0xFFF8F9FA)
    val muted = Color(0xFF5F6368)
    /** PRD §18 Alarms: a ring marker, deliberately unlike the blue task/reminder chips — it is not work. */
    val alarm = Color(0xFFE8710A)
    // PRD §8 (uniform blocks): every calendar period — record, scheduled, or manual — is drawn in this
    // single colour, with no visual distinction between auto-calculated and manually-added tasks.
    val event = Color(0xFF1A73E8) // Google-blue calendar event
}

/** Opacity of the greyed "Sleep"/"Inactivity" band overlay (and the matching tint on blocks under it). */
private const val SLEEP_BAND_ALPHA = 0.16f

private val WEEKDAY_SHORT = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
private val WEEKDAY_INITIAL = listOf("M", "T", "W", "T", "F", "S", "S")

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
    /** PRD §8 the backing panel's four pin dimensions (head panel for a merged block); seeds the edit-window switches. */
    val pins: PanelPins = PanelPins(),
    /** PRD §8 Overlap Mode: horizontal weight of the backing panel (head panel for a merged block). */
    val layoutWeight: Double = 1.0,
    /** PRD §14 Reminders: a zero-duration, checkable tag (not a height-proportional panel). */
    val reminder: Boolean = false,
    /** PRD §14 Reminders: whether this reminder tag has been checked off (done). */
    val checked: Boolean = false,
    /** PRD §14 Reminders: epoch millis at which the tag was checked (freeze point), or null while unchecked. */
    val checkedAtMillis: Long? = null,
    /** PRD §15 Screen breaks: a periodic screen break, drawn as a time-positioned band spanning its real duration. */
    val screenBreak: Boolean = false,
    /**
     * PRD §15: for a [screenBreak] band, the instant its **closed head** ends and its open period begins —
     * the part of the break that accepts the tasks needing no screen (the 5-minute pose's last four minutes,
     * the 15-minute pose end to end). That part is drawn **hollow** so the off-screen work the break accepts
     * shows through it instead of being covered by a solid band. Null when the break accepts nobody over its
     * whole length (the 20-second look-away), which draws solid throughout.
     * Computed by [SchedulerDomain.screenBreakOpenStartMillis] — the same reading of the break's shape the §9
     * fill schedules from, so what is drawn hollow is exactly what is open to a task.
     */
    val screenBreakOpenFromMillis: Long? = null,
    /**
     * PRD §18 Alarms: one ring of an alarm, drawn as a zero-duration marker at its instant (like a reminder
     * tag, but not checkable — an alarm is not a task, it just goes off). One record per occurrence, so an
     * everyday alarm draws on each day of the week the calendar shows.
     */
    val alarm: Boolean = false,
    /** The user's sleep window, drawn as a labeled greyed band behind the task blocks. */
    val sleep: Boolean = false,
    /**
     * PRD §15 device-sleep gaps: a past pause (the user was away/the device slept), drawn as a labeled
     * greyed band exactly like [sleep] but reading "Inactivity". Derived bands carry no [entryId]
     * (display-only); a user-authored inactivity *panel* (PRD §8/§12) sets [inactivity] WITH an [entryId]
     * and renders as a real, removable block instead.
     */
    val inactivity: Boolean = false,
    /**
     * PRD §8/§9 no-screen period: a user-authored "No screen" panel, drawn as a decorative hatched block
     * (a pattern over the real panels). Off-screen tasks schedule inside it; on-screen tasks never do.
     */
    val noScreen: Boolean = false,
    /**
     * For a [sleep] band: the enclosing account-offline "No screen" window. A sleep window is by
     * definition also a no-screen period, and that no-screen stretch may extend past the sleep into a
     * directly-following (or preceding) awake-offline window — so this range is >= the sleep range. It
     * drives the "No screen" line's time span in the hover bubble; null for every non-sleep record.
     */
    val noScreenRange: TaskTimeRange? = null,
    /**
     * PRD §12: this derived Inactivity/No-screen band is open-ended into the past — nothing precedes it, so
     * the inactivity extends indefinitely back (its rendered start is only the display floor, not a real
     * boundary). The hover bubble / phone menu then shows "∞" as the start instead of a wall-clock time.
     */
    val openStart: Boolean = false,
    /**
     * The elapsed part of this panel, segmented by WHICH DEVICES were open (from the stored active
     * sessions — own + Sync-pulled peers; see [deviceActivitySegments]). Consecutive segments differ in
     * device set; the block draws a dashed separator at each interior boundary and the hover bubble names
     * the segment's devices. Empty when no activity data covers the panel (bubble falls back to times only).
     */
    val deviceSegments: List<DeviceActivitySegment> = emptyList(),
)

/**
 * One sub-range of a panel during which the set of open devices was constant. [devices] holds the
 * human-readable labels ("Desktop", "Phone", "Phone 2"…), empty = no device was open.
 */
data class DeviceActivitySegment(
    val startMillis: Long,
    val endMillis: Long,
    val devices: List<String>,
)

/**
 * Segments `[range.start, min(range.end, untilMillis)]` of a panel by the set of devices with an active
 * session covering each instant — the data behind the hover bubble's "which devices were open" line and
 * the dashed set-change separators. Pure so the sweep is unit-testable.
 *
 * Only the region the data can speak for is segmented: nothing is claimed before the oldest known session
 * start (a panel predating all activity data gets no segments, not a false "no device"), and nothing after
 * [untilMillis] (the future part of a still-running panel). Within that region, a covered instant lists the
 * open devices and an uncovered one is a real "no device was open" segment. Device labels come from each
 * session's recorded [ActiveSessionRecord.kind] ("desktop" → "Desktop"; blank/legacy rows → "Device"),
 * numbered "Phone 2", "Phone 3"… when several distinct installs share a kind.
 */
fun deviceActivitySegments(
    range: TaskTimeRange,
    sessions: List<ActiveSessionRecord>,
    untilMillis: Long,
): List<DeviceActivitySegment> {
    if (sessions.isEmpty()) return emptyList()
    val knownSince = sessions.minOf { it.startMillis }
    val start = maxOf(range.startEpochMillis, knownSince)
    val end = minOf(range.endEpochMillis, untilMillis)
    if (end <= start) return emptyList()

    // Stable per-install labels: kind capitalized, numbered by order of first appearance within a kind.
    val labelById = LinkedHashMap<String, String>()
    val kindCounts = mutableMapOf<String, Int>()
    for (s in sessions.sortedBy { it.startMillis }) {
        labelById.getOrPut(s.deviceId) {
            val base = when (s.kind.trim().lowercase()) {
                "" -> "Device"
                else -> s.kind.trim().lowercase().replaceFirstChar { it.uppercase() }
            }
            val n = (kindCounts[base] ?: 0) + 1
            kindCounts[base] = n
            if (n == 1) base else "$base $n"
        }
    }

    // Sweep the clipped window over every session boundary; between two consecutive cuts the open set is
    // constant. Consecutive equal sets merge, so the result is minimal. (distinct+sorted, not sortedSetOf —
    // that is JVM-only and breaks the non-JVM targets.)
    val cuts = mutableListOf(start, end)
    for (s in sessions) {
        val a = maxOf(s.startMillis, start)
        val b = minOf(s.endMillis, end)
        if (b > a) {
            cuts.add(a)
            cuts.add(b)
        }
    }
    val bounds = cuts.distinct().sorted()
    val segments = mutableListOf<DeviceActivitySegment>()
    for (i in 0 until bounds.size - 1) {
        val a = bounds[i]
        val b = bounds[i + 1]
        val open =
            sessions.asSequence()
                .filter { it.startMillis < b && it.endMillis > a }
                .map { labelById.getValue(it.deviceId) }
                .distinct()
                .sorted()
                .toList()
        val last = segments.lastOrNull()
        if (last != null && last.devices == open && last.endMillis == a) {
            segments[segments.lastIndex] = last.copy(endMillis = b)
        } else {
            segments.add(DeviceActivitySegment(a, b, open))
        }
    }
    return segments
}

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
    /** PRD §8 the backing panel's four pin dimensions; seeds the edit-window switches. */
    val pins: PanelPins = PanelPins(),
    /** PRD §8 Overlap Mode: horizontal weight of the backing panel; drives [overlapLayout] widths. */
    val layoutWeight: Double = 1.0,
    /** PRD §14 Reminders: a zero-duration, checkable tag rendered at [startHour] (not a draggable block). */
    val reminder: Boolean = false,
    /** PRD §14 Reminders: whether this reminder tag has been checked off (done). */
    val checked: Boolean = false,
    /** PRD §14 Reminders: epoch millis at which the tag was checked (freeze point), or null while unchecked. */
    val checkedAtMillis: Long? = null,
    /** PRD §15 Screen breaks: a periodic screen break rendered as a time-positioned band over [startHour, endHour]. */
    val screenBreak: Boolean = false,
    /**
     * PRD §15: hour-of-day at which this screen break's open (hollow) part begins — see
     * [CalendarRecord.screenBreakOpenFromMillis], clipped to this day. Null when the break is closed end to
     * end here, so the whole band draws solid.
     */
    val screenBreakOpenFromHour: Float? = null,
    /** PRD §18 Alarms: one ring, rendered as a fixed-height marker at [startHour]. See [CalendarRecord.alarm]. */
    val alarm: Boolean = false,
    /** The user's sleep window, rendered as a labeled greyed band over [startHour, endHour]. */
    val sleep: Boolean = false,
    /** PRD §15 device-sleep gaps: a past pause, rendered as a labeled greyed band reading "Inactivity". */
    val inactivity: Boolean = false,
    /** PRD §8/§9 no-screen period: a user-authored "No screen" panel, rendered as a hatched block. */
    val noScreen: Boolean = false,
    /** For a [sleep] band: its enclosing "No screen" window (>= the sleep range). See [CalendarRecord.noScreenRange]. */
    val noScreenRange: TaskTimeRange? = null,
    /** PRD §12: this derived band is open-ended into the past; the hover bubble shows "∞" as its start. */
    val openStart: Boolean = false,
    /** The entry's true (un-clipped) start/end, used to compute drag/resize targets and edit times. */
    val fullStartMillis: Long = 0L,
    val fullEndMillis: Long = 0L,
    /** [CalendarRecord.deviceSegments] clipped to this day, as hour-of-day sub-ranges. */
    val deviceSegments: List<PlacedDeviceSegment> = emptyList(),
)

/** One [DeviceActivitySegment] clipped to a day: hour-of-day bounds + the open devices' labels. */
data class PlacedDeviceSegment(
    val startHour: Float,
    val endHour: Float,
    val devices: List<String>,
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
        // Seconds are kept (not just hour+minute): a sub-minute look-away (e.g. a 20-s screen break that
        // stays within one minute) would otherwise collapse to startHour == endHour, giving its band a
        // zero-length span — and [decorativeHoverZones] tiles nothing over a zero-length range, so the band
        // (drawn at [SCREEN_BREAK_MIN_HEIGHT]) would be visible but un-hoverable (no info bubble). The
        // device-segment math below already carries seconds for the same reason.
        val startHour = if (start.date < day) 0f else start.hour + start.minute / 60f + start.second / 3600f
        val endHour = if (end.date > day) 24f else end.hour + end.minute / 60f + end.second / 3600f
        // PRD §14/§15/§18: reminders and alarm rings (zero-duration) render as fixed-height markers and
        // screen breaks (down to sub-minute durations) as min-height bands, so keep them even though the
        // block path would drop a ~zero-height period.
        if (!record.reminder && !record.screenBreak && !record.alarm && endHour <= startHour) {
            return@mapNotNull null
        }
        // Clip the device-set segments to this day too, in the same hour-of-day space as the block.
        val daySegments =
            record.deviceSegments.mapNotNull { seg ->
                val s = Instant.fromEpochMilliseconds(seg.startMillis).toLocalDateTime(tz)
                val e = Instant.fromEpochMilliseconds(seg.endMillis).toLocalDateTime(tz)
                if (s.date > day || e.date < day) return@mapNotNull null
                val sh = if (s.date < day) 0f else s.hour + s.minute / 60f + s.second / 3600f
                val eh = if (e.date > day) 24f else e.hour + e.minute / 60f + e.second / 3600f
                if (eh <= sh) null else PlacedDeviceSegment(sh.coerceIn(0f, 24f), eh.coerceIn(0f, 24f), seg.devices)
            }
        // PRD §15: where the break's open (hollow) part begins, in this day's hour space. Clipped like the
        // band itself — a break straddling midnight whose head ended yesterday is open from the day's top —
        // and dropped when nothing of the open part falls on this day.
        val dayStartHour = startHour.coerceIn(0f, 24f)
        val dayEndHour = endHour.coerceIn(0f, 24f)
        val openFromHour =
            record.screenBreakOpenFromMillis?.let { millis ->
                val opens = Instant.fromEpochMilliseconds(millis).toLocalDateTime(tz)
                val raw =
                    when {
                        opens.date < day -> 0f
                        opens.date > day -> 24f
                        else -> opens.hour + opens.minute / 60f + opens.second / 3600f
                    }
                raw.coerceIn(dayStartHour, dayEndHour)
            }?.takeIf { it < dayEndHour }
        PlacedRecord(
            title = record.title,
            startHour = dayStartHour,
            endHour = dayEndHour,
            scheduled = record.scheduled,
            manual = record.manual,
            entryId = record.entryId,
            entryIds = record.entryIds,
            taskId = record.taskId,
            pinned = record.pinned,
            pins = record.pins,
            layoutWeight = record.layoutWeight,
            reminder = record.reminder,
            checked = record.checked,
            checkedAtMillis = record.checkedAtMillis,
            screenBreak = record.screenBreak,
            screenBreakOpenFromHour = openFromHour,
            alarm = record.alarm,
            sleep = record.sleep,
            inactivity = record.inactivity,
            noScreen = record.noScreen,
            noScreenRange = record.noScreenRange,
            openStart = record.openStart,
            fullStartMillis = record.range.startEpochMillis,
            fullEndMillis = record.range.endEpochMillis,
            deviceSegments = daySegments,
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
    val boundaries = mutableSetOf<Float>()
    for (b in blocks) {
        boundaries.add(b.startHour)
        boundaries.add(b.endHour)
    }
    // `sortedSetOf` is JVM-only (java.util.TreeSet); collect distinct boundaries then sort so this
    // file also compiles for the Kotlin/Native (iOS) and JS targets.
    val bounds = boundaries.sorted()
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
    val boundaries = mutableSetOf<Float>()
    for (b in blocks) {
        boundaries.add(b.startHour)
        boundaries.add(b.endHour)
    }
    // `sortedSetOf` is JVM-only (java.util.TreeSet); collect distinct boundaries then sort so this
    // file also compiles for the Kotlin/Native (iOS) and JS targets.
    val bounds = boundaries.sorted()
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
    /** Sleep schedule window: whether it is open + toggle callback. */
    sleepWindowOpen: Boolean = false,
    onToggleSleep: () -> Unit = {},
    /** PRD §18 Alarms: whether the alarms window is open + toggle callback. */
    alarmWindowOpen: Boolean = false,
    onToggleAlarms: () -> Unit = {},
    /** All task trees (the timeline of dated task trees): whether that window is open + toggle callback. */
    taskTreesWindowOpen: Boolean = false,
    onToggleTaskTrees: () -> Unit = {},
    /**
     * Sleep/Work toggle: whether the user is currently in "sleeping" mode (pressed **Sleep**). The button reads
     * **Work** while sleeping and **Sleep** while working; pressing it flips the mode ([onToggleSleepWork]) and
     * tells the server so the pause-end cue is suppressed while the user is deliberately away.
     */
    sleeping: Boolean = false,
    onToggleSleepWork: () -> Unit = {},
    /**
     * PRD §15 "I'm away" toggle: whether the user declared they are away from **this device**. Pressing it closes
     * this device's heartbeat (the server sees the device stop working); pressing it again ("I'm back")
     * resumes it. Distinct from Sleep/Work, which is the account-wide sleep mode.
     */
    away: Boolean = false,
    onToggleAway: () -> Unit = {},
    /** PRD §15 (20s look-away): whether the spoken voice cue is enabled + toggle callback. */
    lookAwayVoiceEnabled: Boolean = true,
    onToggleLookAwayVoice: (Boolean) -> Unit = {},
    /**
     * PRD §15 (20s look-away): re-runs the 20s pause now (superseding any look-away still sounding/pending).
     * The button is **always** in the menu — a look-away is something the user may decide to take at any
     * moment, so its availability must not depend on what the last past screen break happened to be.
     */
    onLookAwayNow: () -> Unit = {},
    /** Whether any floating window is open — gates the "close all windows" button + the callback to do so. */
    anyWindowOpen: Boolean = false,
    onCloseAllWindows: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .width(188.dp)
            .background(CalColors.menuBackground)
            .border(1.dp, CalColors.grid)
            // Scroll when the buttons exceed the available height (e.g. short windows / calendar
            // expanded) so nothing is clipped off the bottom.
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // First element: page navigation (persistent across all feature pages), styled like the buttons below.
        // The collapse toggle is NOT here — it's a bookmark on this menu's right border (in App), so it stays
        // visible after the whole menu (this button included) slides off-screen.
        PageNavButton(page = page, onPageSelected = onPageSelected)

        // Shown only while a floating window is open: closes every floating window at once.
        if (anyWindowOpen) {
            MenuButton(label = "✕ Close windows", active = false, onClick = onCloseAllWindows)
        }

        MenuButton(
            label = "Calendar",
            active = calendarOpen,
            onClick = onToggleCalendar,
        )

        // PRD §7 Calendar: the day selector (month grid) sits right below the Calendar button, and only
        // appears while the calendar is displayed.
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

        // PRD §15 (20s look-away): take the 20s pause now. ALWAYS present — the user may choose to look away
        // at any moment, so this is never gated on the cadence's current state.
        MenuButton(label = "Look away now", active = false, onClick = onLookAwayNow)

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

        // Sleep/Work toggle: "Sleep" when working (press it when going away), "Work" when sleeping (press it
        // when resuming). Tells the server so the phone's pause-end cue is suppressed while deliberately away.
        MenuButton(
            label = if (sleeping) "Work" else "Sleep",
            active = sleeping,
            onClick = onToggleSleepWork,
        )

        // PRD §15 "I'm away" toggle: declares this DEVICE idle. Pressing "I'm away" drops this device's presence
        // WebSocket (the server sees it stop working); "I'm back" reopens it. Per-device, unlike Sleep/Work.
        MenuButton(
            label = if (away) "I'm back" else "I'm away",
            active = away,
            onClick = onToggleAway,
        )

        // Sleep schedule: toggles the floating window for the nightly sleep window the scheduler avoids.
        MenuButton(
            label = "Sleep schedule",
            active = sleepWindowOpen,
            onClick = onToggleSleep,
        )

        // PRD §18 Alarms: toggles the window where the account's alarms (time, sound length, vibration) are set.
        MenuButton(
            label = "Alarms",
            active = alarmWindowOpen,
            onClick = onToggleAlarms,
        )

        // The task-tree timeline: every named task tree, and the dates that make them the keyframes the
        // scheduler blends its priorities between.
        MenuButton(
            label = "All task trees",
            active = taskTreesWindowOpen,
            onClick = onToggleTaskTrees,
        )
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
    /** Persists the window's new drag position when a drag gesture ends (local-only geometry). */
    onOffsetChange: (Offset) -> Unit = {},
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
    /**
     * PRD §14: reminder ids kept alive by a **checked or pinned** tag on the calendar. A focused row whose
     * own id is in here is a *real* reminder (independently referenced), so the id menu offers it (the row can
     * detach from it and the id survives via that tag) instead of hiding it as a provisional self-identity.
     */
    referencedReminderIds: () -> Set<String> = { emptySet() },
    /** PRD §14 "constrained in": the reminder id of the first known reminder whose title is exactly this. */
    reminderIdForTitle: (String) -> String? = { null },
    /** PRD §14 "constrained in": the title of a known reminder id (shown beside the "constrained in" button). */
    titleForReminderId: (String) -> String? = { null },
) {
    var offset by remember { mutableStateOf(initialOffset) }
    val focusManager = LocalFocusManager.current
    // PRD §14 "constrained in": the index of the row whose constraint picker is open, or null when closed.
    var constrainingRowIndex by remember { mutableStateOf<Int?>(null) }
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
                        constrainedToReminderId = it.constrainedToReminderId,
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
    // PRD §14: the reminder id each row resolves to. A row still being created (its minted id is not yet an
    // existing reminder) and not explicitly marked "New Reminder" adopts the reminder its id menu shows
    // selected by default — the first title-matching calendar-only reminder not already taken by an earlier
    // row (earlier rows win, so two rows can't claim one reminder) — exactly as the "add a checked reminder"
    // window resolves its id from the title. Without this, relying on the default selection (not clicking it)
    // would leave the row a distinct reminder, so a past checked reminder of the matching title would not act
    // as its scheduling tie-breaker (PRD §14). Shared by [push] (the saved id) and the id menu (so it never
    // re-suggests a reminder already represented by a row, even before the manager round-trips).
    fun resolvedRowIds(): List<String> {
        val taken = rows.filter { it.id in existingReminderIds }.mapTo(mutableSetOf()) { it.id }
        return rows.map { row ->
            val id =
                if (row.id in existingReminderIds || row.explicitNew) row.id
                else reminderMenuEntries(row.title).firstOrNull { it.id !in taken }?.id ?: row.id
            taken.add(id)
            id
        }
    }
    fun push() {
        val ids = resolvedRowIds()
        onChange(
            rows.mapIndexed { index, row ->
                // PRD §14: the chosen unit maps the entered number to a cadence in days (interval vs rate units).
                val number = SchedulerDomain.evaluateDayFormula(row.daysText) ?: 0.0
                ChoreEntry(
                    title = row.title,
                    spanDays = row.unit.toDays(number),
                    timeOfDayMinutes = parseTimeOfDay(row.timeText),
                    daysFormula = row.daysText,
                    recurrenceUnit = row.unit,
                    id = ids[index],
                    constrainedToReminderId = row.constrainedToReminderId,
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
            // requiredSize (not size) so the window keeps its fixed size and does not adapt to the app's
            // width when the content area is narrower than it.
            .requiredSize(width = 560.dp, height = 480.dp)
            // Raise on press AFTER the offset so the hit region tracks the (possibly dragged) window.
            .raiseOnPress(onRaise),
    ) {
        Column(
            // PRD §14: clicking anywhere in the window that is not the focused title field or its edit-mode
            // menus leaves Edit mode — a tap on empty/non-interactive space clears focus, which the title
            // field's onFocusChanged turns into focusedIndex = null (hiding the menus). Interactive children
            // (the title field, menu rows, other inputs/buttons) consume their own taps, so this only fires
            // for clicks that land on bare window chrome.
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) { detectTapGestures { focusManager.clearFocus() } },
        ) {
            // Title bar doubles as the drag handle for moving the window.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(CalColors.menuBackground)
                    .pointerInput(Unit) {
                        detectDragGestures(onDragEnd = { onOffsetChange(offset) }) { change, dragAmount ->
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

                    // PRD §14 "constrained in": a button opening the constraint picker, with the chosen
                    // constraining reminder's name shown to its right. A constrained reminder is only placed
                    // on the days the chosen reminder also occurs (averaging its own cadence).
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        TextButton(onClick = { constrainingRowIndex = index }) { Text("constrained in") }
                        val constrainedName =
                            row.constrainedToReminderId.takeIf { it.isNotBlank() }?.let(titleForReminderId)
                        if (constrainedName != null) {
                            Text(constrainedName, style = MaterialTheme.typography.bodyMedium)
                            // A small clear affordance to detach the constraint.
                            TextButton(
                                onClick = {
                                    rows[index] = row.copy(constrainedToReminderId = ""); push()
                                },
                            ) { Text("✕", color = CalColors.muted) }
                        } else {
                            Text("(none)", style = MaterialTheme.typography.bodyMedium, color = CalColors.muted)
                        }
                    }

                    // PRD §14: the row's Edit mode — the shared mode selector + menus show beneath the fields
                    // (and vanish when focus leaves the row). The id menu lists reminders matching the draft
                    // that are NOT already a row in this window — i.e. reminders that exist only as "add a
                    // checked reminder" panels on the calendar. Picking one adopts its id (adding that reminder
                    // to the manager); "New Reminder" keeps this row's own freshly-minted id. The default
                    // highlight is the first such reminder, or "New Reminder" when there are none.
                    if (focusedIndex == index) {
                        // PRD §14: exclude reminders already represented by a row — every OTHER row's resolved
                        // id, plus the focused row's **own** id. Excluding the focused row's own id hides its
                        // provisional "New Reminder" self-identity (only persisted while "New Reminder" is
                        // selected; it must not reappear as a selectable entry). EXCEPTION: when the row's own
                        // id is independently referenced by a checked or pinned tag, it is a real reminder —
                        // show it so the user can re-pick it or detach (picking "New Reminder") while that tag
                        // keeps it alive. The reminder a row merely adopts by default always has a different id
                        // (minted ids dodge known reminder ids), so it is never the excluded one.
                        val referencedIds = referencedReminderIds()
                        val ownReferenced = row.id in referencedIds
                        val resolved = resolvedRowIds()
                        val excludedIds = buildSet {
                            rows.forEachIndexed { i, r ->
                                if (i == index) { if (!ownReferenced) add(r.id) } else add(resolved[i])
                            }
                        }
                        val entries = reminderMenuEntries(row.title).filter { it.id !in excludedIds }
                        // A referenced own id makes the row a real reminder, so its "New Reminder" pick no
                        // longer forces the New highlight: the menu defaults to the first matching reminder.
                        val provisionalNew = row.explicitNew && !ownReferenced
                        // The default highlight mirrors the resolved id in push(): "New Reminder" (null) when
                        // the user explicitly chose it or nothing matches, else the first matching reminder.
                        val selectedEntryId = if (provisionalNew) null else entries.firstOrNull()?.id
                        ReminderEditModeMenus(
                            mode = editMode,
                            onSelectMode = { editMode = it },
                            // The Mode selector must appear whenever the id menu resolves to a real reminder —
                            // i.e. anything other than "New Reminder". That covers a row that already is/became a
                            // real reminder (adopted via onPickEntry or kept alive by a checked/pinned tag) AND a
                            // row whose id menu merely defaults to a matching existing reminder (e.g. after
                            // picking a title suggestion). Only hide it when "New Reminder" is selected (PRD §14).
                            showModeSelector =
                                row.id in existingReminderIds || ownReferenced || selectedEntryId != null,
                            idMenuEntries = entries,
                            selectedEntryId = selectedEntryId,
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
                            titleSuggestions = titleSuggestions(row.title),
                            onPickSuggestion = { suggestion -> rows[index] = row.copy(title = suggestion); push() },
                            // The row editor lives only while the row has focus, so a pick must not blur it.
                            focusPreserving = true,
                        )
                    }
                  }
                }
                // Trailing single plus: append a new row at the end of the list.
                TextButton(onClick = { rows.add(newRow()); push() }) { Text("+ add reminder") }
            }
        }
    }

    // PRD §14 "constrained in": the constraint picker, shown over the manager when a row's button is tapped.
    val idx = constrainingRowIndex
    val constrainingRow = idx?.let { rows.getOrNull(it) }
    if (idx != null && constrainingRow != null) {
        ReminderConstraintEditWindow(
            initialReminderId = constrainingRow.constrainedToReminderId,
            // A reminder can't be constrained to itself, so hide its own identity from the picker.
            excludeReminderId = resolvedRowIds().getOrNull(idx) ?: constrainingRow.id,
            reminderMenuEntries = reminderMenuEntries,
            titleSuggestions = titleSuggestions,
            reminderIdForTitle = reminderIdForTitle,
            titleForReminderId = titleForReminderId,
            onDismiss = { constrainingRowIndex = null },
            onSave = { reminderId ->
                rows[idx] = constrainingRow.copy(constrainedToReminderId = reminderId)
                push()
                constrainingRowIndex = null
            },
        )
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
    /** The local-only diagnostic notification log, shown as the "Notifications" column. */
    notificationLog: List<NotificationLogEntry> = emptyList(),
    /** The local-only Supabase-usage diagnostic log, shown as the rightmost "Supabase usage" column. */
    supabaseUsageLog: List<SupabaseUsageEntry> = emptyList(),
    /** Initial position relative to centered; staggered per window so they open in a clickable cascade. */
    initialOffset: Offset = Offset.Zero,
    /** Persists the window's new drag position when a drag gesture ends (local-only geometry). */
    onOffsetChange: (Offset) -> Unit = {},
    /** Raise this window to the top of the layers — fired on a press anywhere inside it. */
    onRaise: () -> Unit = {},
) {
    var offset by remember { mutableStateOf(initialOffset) }
    // PRD §6: the history unit whose information window is open (clicked from a row); null when closed.
    var infoUnit by remember { mutableStateOf<HistoryUnit?>(null) }
    // Display order: the content stacks first (most-used), then selection.
    val sections = listOf(
        "Main (the rest)" to HistoryCategory.Main,
        "Calendar" to HistoryCategory.Calendar,
        "Edit Mode" to HistoryCategory.Edit,
        "Selection" to HistoryCategory.Selection,
        "Window nav" to HistoryCategory.WindowNav,
    )

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 10.dp,
        border = BorderStroke(1.dp, CalColors.grid),
        modifier = modifier
            .offset { IntOffset(offset.x.roundToInt(), offset.y.roundToInt()) }
            // requiredSize (not size) so the window keeps its fixed size and does not adapt to the app's
            // width when the content area is narrower than it. Wider than the category-only layout to make
            // room for the Notifications and Supabase-usage diagnostic columns.
            .requiredSize(width = 1480.dp, height = 520.dp)
            // Raise on press AFTER the offset so the hit region tracks the (possibly dragged) window.
            .raiseOnPress(onRaise),
    ) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                // Title bar doubles as the drag handle for moving the window.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(CalColors.menuBackground)
                        .pointerInput(Unit) {
                            detectDragGestures(onDragEnd = { onOffsetChange(offset) }) { change, dragAmount ->
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

                // The category lists sit side by side so every list's head is aligned at the top (PRD §5/§6),
                // with the local-only Notifications diagnostic column last. Each column scrolls independently.
                Row(
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    sections.forEach { (title, category) ->
                        HistoryCategorySection(
                            title = title,
                            history = histories.forCategory(category),
                            // PRD §6: clicking a unit row opens its information window.
                            onSelectUnit = { infoUnit = it },
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                        Box(Modifier.fillMaxHeight().width(1.dp).background(CalColors.grid))
                    }
                    // The local-only notification log — a diagnostic column (not a history category), wider
                    // than the others because it carries the notification text.
                    NotificationLogSection(
                        log = notificationLog,
                        modifier = Modifier.weight(2f).fillMaxHeight(),
                    )
                    Box(Modifier.fillMaxHeight().width(1.dp).background(CalColors.grid))
                    // The local-only Supabase-usage log — a diagnostic column tracking the account's draw-down
                    // on the Supabase free-plan limits (egress bandwidth, Auth MAU, request count).
                    SupabaseUsageSection(
                        log = supabaseUsageLog,
                        modifier = Modifier.weight(2f).fillMaxHeight(),
                    )
                }
            }

            // PRD §6: the information window for a clicked history unit, overlaid (modal) on the manager.
            infoUnit?.let { unit ->
                HistoryUnitInfoWindow(unit = unit, onDismiss = { infoUnit = null })
            }
        }
    }
}

/**
 * One column of the history manager: a category's header (with `applied/total`) pinned at the top, then
 * its History Units listed **newest-first** (last at the top, first at the bottom). Each unit is a single
 * row showing its label (PRD §6); clicking it opens an information window with all of the unit's data via
 * [onSelectUnit]. Undone units (ahead of the pointer) are dimmed and the current pointer position is marked.
 */
@Composable
private fun HistoryCategorySection(
    title: String,
    history: SchedulerHistory,
    onSelectUnit: (HistoryUnit) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Wrap the whole column (header + rows) in a SelectionContainer so all of its text is
    // selectable/copyable and a single drag can select the whole column. Per the note on
    // SupabaseUsageSection, the list MUST be a plain scrollable Column, not a LazyColumn:
    // selection holds references to the composed row nodes, and a LazyColumn disposes rows
    // scrolled out of view — so the selection can't extend as you scroll and a shift+click onto
    // a recycled anchor row crashes. Composing every row keeps the whole column selectable
    // (bounded by SchedulerHistory's MAX_HISTORY_UNITS).
    SelectionContainer(modifier = modifier) {
        Column {
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
                Column(
                    modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // Newest at the top, oldest at the bottom: walk the units in reverse.
                    for (row in history.units.indices) {
                        val index = history.units.lastIndex - row
                        val unit = history.units[index]
                        val applied = index <= history.pointer
                        val isCurrent = index == history.pointer
                        HistoryUnitRow(
                            position = index + 1,
                            unit = unit,
                            applied = applied,
                            isCurrent = isCurrent,
                            onClick = { onSelectUnit(unit) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * The History Manager's **Notifications** column: a read-only, local-only diagnostic list of the notification
 * text the app has posted (see [org.example.project.scheduler.state.SchedulerState.notificationLog]). Newest
 * at the top; the header shows how many of the capped
 * [org.example.project.scheduler.state.SchedulerState.MAX_NOTIFICATION_LOG] entries have been recorded. Each
 * row shows the fire time, the notification title, and its message text.
 */
@Composable
private fun NotificationLogSection(
    log: List<NotificationLogEntry>,
    modifier: Modifier = Modifier,
) {
    // Wrap the whole column in a SelectionContainer so all of its text is selectable/copyable and a
    // single drag can select the whole column. Same constraint as SupabaseUsageSection: the list is a
    // plain scrollable Column, not a LazyColumn, because selection can't survive row recycling
    // (composing every row is bounded by MAX_NOTIFICATION_LOG).
    SelectionContainer(modifier = modifier) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Notifications",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "${log.size} / ${SchedulerState.MAX_NOTIFICATION_LOG}",
                    style = MaterialTheme.typography.labelSmall,
                    color = CalColors.muted,
                )
            }
            if (log.isEmpty()) {
                Text(text = "(none)", style = MaterialTheme.typography.bodySmall, color = CalColors.muted)
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // Newest at the top, oldest at the bottom: walk the entries in reverse.
                    for (row in log.indices) {
                        NotificationLogRow(entry = log[log.lastIndex - row])
                    }
                }
            }
        }
    }
}

/** One notification-log entry: its fire time above the title, then the message text below (PRD-adjacent diagnostic). */
@Composable
private fun NotificationLogRow(entry: NotificationLogEntry) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        Text(
            text = formatHistoryTime(entry.timeMillis),
            style = MaterialTheme.typography.labelSmall,
            color = CalColors.muted,
        )
        Text(
            text = entry.title,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (entry.message.isNotBlank()) {
            Text(
                text = entry.message,
                style = MaterialTheme.typography.bodySmall,
                color = CalColors.muted,
            )
        }
    }
}

/**
 * The History Manager's **Supabase usage** column: a read-only, local-only diagnostic list of every Supabase
 * HTTP call the app has made (see [org.example.project.scheduler.state.SchedulerState.supabaseUsageLog]) — the
 * account's draw-down on the Supabase **free-plan** limits (egress bandwidth, Auth MAU, request count). Newest
 * at the top; the header shows the count against the rolling cap
 * ([org.example.project.scheduler.state.SchedulerState.MAX_SUPABASE_USAGE_LOG]) plus the total bytes over the
 * kept window. Each row shows the fire time, the resource + operation, and the up/down byte counts with status.
 */
@Composable
private fun SupabaseUsageSection(
    log: List<SupabaseUsageEntry>,
    modifier: Modifier = Modifier,
) {
    // Wrap the whole column (header + total + rows) in a SelectionContainer so all of its text is
    // selectable/copyable and a single drag can select the whole column. This MUST be a plain
    // scrollable Column, not a LazyColumn: text selection holds references to the composed row
    // nodes, but a LazyColumn disposes rows scrolled out of view — so the selection can't extend as
    // you scroll, and a shift+click onto a recycled anchor row dereferences a disposed selectable and
    // crashes. Composing every row keeps the whole column selectable (bounded by MAX_SUPABASE_USAGE_LOG).
    SelectionContainer(modifier = modifier) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Supabase usage",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "${log.size} / ${SchedulerState.MAX_SUPABASE_USAGE_LOG}",
                    style = MaterialTheme.typography.labelSmall,
                    color = CalColors.muted,
                )
            }
            if (log.isEmpty()) {
                Text(text = "(none)", style = MaterialTheme.typography.bodySmall, color = CalColors.muted)
            } else {
                // Running total of the bytes over the kept (rolling) window — a rough egress/ingress gauge.
                val totalUp = log.sumOf { it.requestBytes }
                val totalDown = log.sumOf { it.responseBytes }
                Text(
                    text = "Σ ↑${formatBytes(totalUp)}  ↓${formatBytes(totalDown)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = CalColors.muted,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                Column(
                    modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // Newest at the top, oldest at the bottom: walk the entries in reverse.
                    for (row in log.indices) {
                        SupabaseUsageRow(entry = log[log.lastIndex - row])
                    }
                }
            }
        }
    }
}

/** One Supabase-usage entry: its fire time above the resource/operation, then the byte counts + status below. */
@Composable
private fun SupabaseUsageRow(entry: SupabaseUsageEntry) {
    // A non-2xx status is worth flagging (a failed call still spends bandwidth).
    val ok = entry.status in 200..299
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        Text(
            text = formatHistoryTime(entry.timeMillis),
            style = MaterialTheme.typography.labelSmall,
            color = CalColors.muted,
        )
        Text(
            text = "${entry.resource} · ${entry.operation}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = "↑${formatBytes(entry.requestBytes)}  ↓${formatBytes(entry.responseBytes)}  ·  ${entry.status}",
            style = MaterialTheme.typography.bodySmall,
            color = if (ok) CalColors.muted else MaterialTheme.colorScheme.error,
        )
    }
}

/** Human-readable byte count (B / KB / MB) for the Supabase-usage column. */
private fun formatBytes(bytes: Long): String =
    when {
        bytes < 1_024 -> "$bytes B"
        bytes < 1_024 * 1_024 -> "${(bytes * 10 / 1_024) / 10.0} KB"
        else -> "${(bytes * 10 / (1_024 * 1_024)) / 10.0} MB"
    }

/**
 * One History Unit as a **single clickable row** (PRD §6): its position, label, and the current-pointer
 * marker. Clicking it ([onClick]) opens the information window with all of the unit's data — the row itself
 * no longer lists the per-change detail lines.
 */
@Composable
private fun HistoryUnitRow(
    position: Int,
    unit: HistoryUnit,
    applied: Boolean,
    isCurrent: Boolean,
    onClick: () -> Unit,
) {
    // Undone units (past the pointer, redoable) are dimmed.
    val labelColor = if (applied) MaterialTheme.colorScheme.onSurface else CalColors.muted
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp, horizontal = 2.dp),
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
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (isCurrent) {
            Text(text = "●", style = MaterialTheme.typography.labelSmall, color = CalColors.accent)
        }
    }
}

/**
 * PRD §6: the information window for a clicked history unit — a modal overlay (scrim + centered card,
 * dismissed by clicking outside or the ✕) listing **all of the unit's own data**: its label, chrono id, and
 * every per-change detail line ([Delta.details]). It deliberately shows nothing list- or pointer-derived
 * (position, category, applied/current status) — those belong to the history list, not to the unit.
 */
@Composable
private fun HistoryUnitInfoWindow(unit: HistoryUnit, onDismiss: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.3f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 14.dp,
            border = BorderStroke(1.dp, CalColors.grid),
            modifier = Modifier
                .padding(24.dp)
                .widthIn(min = 280.dp, max = 460.dp)
                // Swallow clicks on the card so they don't fall through to the dismiss scrim.
                .clickable(enabled = false) {},
        ) {
            Column(
                modifier = Modifier.padding(16.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = unit.delta.label,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                    )
                    Box(
                        modifier = Modifier.size(28.dp).clip(CircleShape).clickable(onClick = onDismiss),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("✕", style = MaterialTheme.typography.titleSmall, color = CalColors.muted)
                    }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(CalColors.grid))

                HistoryInfoLine("Time", formatHistoryTime(unit.timeMillis))
                HistoryInfoLine("Chrono id", unit.chronoId.toString())

                Text(
                    text = "Details",
                    style = MaterialTheme.typography.labelMedium,
                    color = CalColors.muted,
                )
                val details = unit.delta.details
                if (details.isEmpty()) {
                    Text(
                        text = "(no further detail)",
                        style = MaterialTheme.typography.bodySmall,
                        color = CalColors.muted,
                    )
                } else {
                    details.forEach { line ->
                        Text(text = line, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

/** PRD §6: the History Unit's exact timestamp, rendered as `YYYY-MM-DD HH:MM:SS` in the local zone. */
private fun formatHistoryTime(millis: Long): String {
    val dt = Instant.fromEpochMilliseconds(millis).toLocalDateTime(TimeZone.currentSystemDefault())
    fun p2(v: Int) = v.toString().padStart(2, '0')
    return "${dt.year}-${p2(dt.monthNumber)}-${p2(dt.dayOfMonth)} ${p2(dt.hour)}:${p2(dt.minute)}:${p2(dt.second)}"
}

/** One `label: value` line in the history-unit information window (PRD §6). */
@Composable
private fun HistoryInfoLine(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "$label:",
            style = MaterialTheme.typography.labelSmall,
            color = CalColors.muted,
            modifier = Modifier.width(76.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
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
    // PRD §14 "constrained in": id of the reminder this row is constrained to (blank = unconstrained).
    val constrainedToReminderId: String = "",
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

/**
 * The feature-page navigation dropdown — the first item in the lateral menu, styled and sized exactly like
 * the other menu buttons (Calendar, etc.). It never moves relative to the menu; only the menu itself moves
 * (it collapses off-screen via the bookmark toggle in App).
 */
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

/**
 * A compact, self-sizing menu button — used for the lateral-menu collapse control (`«`) and its floating
 * re-open button (`☰`). Unlike [MenuButton] it does not stretch to fill its parent's width.
 */
@Composable
fun IconMenuButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(CalColors.menuBackground)
            .border(1.dp, CalColors.grid, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
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
 * PRD §7/§8 Calendar: a floating, draggable in-app window (not a modal dialog) showing a
 * Google-Calendar style time grid that scrolls through the days ENDLESSLY — [selectedDate] only says
 * where it opens (the leftmost column), and each column runs on into its own next day below. It is
 * meant to be rendered inside
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
    /**
     * PRD §9/§17: true while the work plan for a focused future week beyond the near horizon is still being
     * computed off the UI thread. Surfaces a "Calculating…" hint so the (necessarily slower) distant-week
     * fill reads as "loading", never as a frozen calendar.
     */
    calculating: Boolean = false,
    /** PRD §8 focus: a press anywhere in the window makes the calendar the focused surface again. */
    onFocus: () -> Unit = {},
    /** PRD §8 Manual add: invoked with the epoch-millis at a right-click position in the calendar. */
    onAddTaskAt: (Long) -> Unit = {},
    /** PRD §14: "add reminder" — invoked with the epoch-millis at a right-click position. */
    onAddReminderAt: (Long) -> Unit = {},
    /** PRD §8: "add a no-screen period" — invoked with the epoch-millis at a right-click position. */
    onAddNoScreenAt: (Long) -> Unit = {},
    /** PRD §8/§12: "add an inactivity period" — invoked with the epoch-millis at a right-click position. */
    onAddInactivityAt: (Long) -> Unit = {},
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
    /** PRD §15: whether the calendar draws the screen breaks (cosmetic display toggle). */
    showScreenBreaks: Boolean = true,
    /** PRD §15: flip the "Screen breaks" display switch. */
    onToggleScreenBreaks: (Boolean) -> Unit = {},
    /** PRD §14: whether the calendar draws the reminder tags (cosmetic display toggle). */
    showReminders: Boolean = true,
    /** PRD §14: flip the "Reminders" display switch. */
    onToggleReminders: (Boolean) -> Unit = {},
    /** PRD §8/§9 calendar history: Ctrl+Z / Ctrl+Y while the calendar holds keyboard focus. */
    onUndo: () -> Unit = {},
    onRedo: () -> Unit = {},
    /** Initial (persisted) drag position; centered by default. */
    initialOffset: Offset = Offset.Zero,
    /** Persists the window's new drag position when a drag gesture ends (local-only geometry). */
    onOffsetChange: (Offset) -> Unit = {},
    /**
     * PRD §8/§9: the day span the endless scroll currently has on screen — its first (top-left) day and
     * how many days the grid covers. There is no focused WEEK to derive it from any more, so the
     * schedule horizon and every display projection follow this instead.
     */
    onVisibleDaysChanged: (LocalDate, Int) -> Unit = { _, _ -> },
    /**
     * PRD §7: bumped on every date pick in the lateral month calendar. The grid scrolls away from
     * [selectedDate] freely, so re-picking the day already selected must still jump back — this is what
     * makes that pick observable.
     */
    jumpNonce: Int = 0,
) {
    var offset by remember { mutableStateOf(initialOffset) }
    // Keep the title bar (the only drag handle) reachable: this window has a fixed [requiredSize] that, on a
    // small screen (e.g. Android), is taller than the content area it is centered in. Centering an over-tall
    // window puts its head above the top edge, where it can't be grabbed to move it. We track the parent /
    // window / header heights and clamp the vertical offset so the header stays within the parent's bounds.
    var containerHeightPx by remember { mutableStateOf(0) }
    var windowHeightPx by remember { mutableStateOf(0) }
    var headerHeightPx by remember { mutableStateOf(0) }
    // Lowest (most negative) and highest offset.y that keep the header on-screen, given a centered placement:
    // resting top = (containerH - windowH) / 2, and we want that top + offset.y to stay in
    // [0, containerH - headerH]. Returns [y] unchanged until the sizes are known.
    fun clampOffsetY(y: Float): Float {
        if (containerHeightPx == 0 || windowHeightPx == 0) return y
        val restingTop = (containerHeightPx - windowHeightPx) / 2f
        val maxTop = (containerHeightPx - headerHeightPx).coerceAtLeast(0).toFloat()
        val a = -restingTop // offset that places the header's top at 0
        val b = maxTop - restingTop // offset that places the header's top at the lowest visible row
        return y.coerceIn(minOf(a, b), maxOf(a, b))
    }
    // PRD §8 zoom: the zoom mechanics live in WeekView (which owns the scroll state + viewport geometry,
    // so it can keep the point under the cursor fixed). The keyboard shortcuts here drive it through this
    // action holder; [ctrlHeld] tracks the Ctrl modifier so WeekView's scroll handler knows when a wheel
    // turn means "zoom toward the cursor".
    val zoomActions = remember { CalendarZoomActions() }
    var ctrlHeld by remember { mutableStateOf(false) }
    // PRD §8 now-line lock: while on, the grid is held with the now-line at the MIDDLE of the viewport and
    // a zoom pivots around it instead of the cursor; scrolling (or a date pick) releases it. Like the zoom
    // itself this is pure per-device view state with no meaning beyond the open window, so it lives only in
    // Compose state — neither persisted nor synced (CLAUDE.md: local-only view state).
    var lockNowLine by remember { mutableStateOf(false) }
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
            // requiredSize (not size) so the window keeps its fixed size and does not adapt to the app's
            // width when the content area is narrower than it.
            .requiredSize(width = 720.dp, height = 540.dp)
            // Measure the window and its parent so an over-tall window (small screens) is nudged down until
            // its header is visible; clamping a fixed point converges, so re-applying on each layout is safe.
            .onGloballyPositioned { coords ->
                containerHeightPx = coords.parentLayoutCoordinates?.size?.height ?: 0
                windowHeightPx = coords.size.height
                offset = offset.copy(y = clampOffsetY(offset.y))
            }
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
                    .onSizeChanged { headerHeightPx = it.height }
                    .background(CalColors.menuBackground)
                    .pointerInput(Unit) {
                        detectDragGestures(onDragEnd = { onOffsetChange(offset) }) { change, dragAmount ->
                            change.consume()
                            // Clamp the vertical drag so the header can't be dragged off the top/bottom edge.
                            offset = Offset(offset.x + dragAmount.x, clampOffsetY(offset.y + dragAmount.y))
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
                // PRD §9/§17: a distant future week fills its plan off the UI thread — show it's working.
                if (calculating) {
                    Text(
                        text = "Calculating…",
                        style = MaterialTheme.typography.labelMedium,
                        color = CalColors.muted,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                }
                // PRD §8: hold the now-line at the middle of the view (see [WeekView]'s lock). The Switch
                // consumes its own presses, so toggling it never starts the title-bar drag.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(end = 8.dp),
                ) {
                    Text(
                        text = "Lock to now",
                        style = MaterialTheme.typography.labelMedium,
                        color = CalColors.muted,
                    )
                    Switch(
                        checked = lockNowLine,
                        onCheckedChange = { lockNowLine = it },
                    )
                }
                // PRD §14/§15: toggle whether reminders / screen breaks are drawn (cosmetic; notifications keep
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
                        text = "Screen breaks",
                        style = MaterialTheme.typography.labelMedium,
                        color = CalColors.muted,
                    )
                    Switch(
                        checked = showScreenBreaks,
                        onCheckedChange = onToggleScreenBreaks,
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
                    onAddReminderAt = onAddReminderAt,
                    onAddNoScreenAt = onAddNoScreenAt,
                    onAddInactivityAt = onAddInactivityAt,
                    onCommitBounds = onCommitBounds,
                    onEditEntry = onEditEntry,
                    onRemoveEntry = onRemoveEntry,
                    onToggleReminder = onToggleReminder,
                    onAdjustWeights = onAdjustWeights,
                    overlapArmed = overlapArmed,
                    jumpNonce = jumpNonce,
                    onVisibleDaysChanged = onVisibleDaysChanged,
                    lockNowLine = lockNowLine,
                    onLockNowLineChange = { lockNowLine = it },
                )
            }
        }
    }
}

/** PRD §8 zoom: the week grid's hour-row height at zoom 1f, and the zoom bounds / per-step factor. */
private val BASE_HOUR_HEIGHT = 48.dp
// Low enough that a WHOLE day (24 x 48 dp = 1152 dp) still fits in a short viewport, which is what the
// date pick's whole-day fit ([wholeDayZoom]) asks for on a scaled/small window; the fit is clamped into
// these bounds, so a floor above what the window needs would silently stop showing the whole day.
private const val MIN_CALENDAR_ZOOM = 0.25f
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
 * PRD §8 zoom-to-cursor: the new vertical offset (px along the timeline) that keeps the content currently
 * under [focalY] (px from the viewport top) under that same pixel after the grid's height is scaled by
 * [scaleFactor]. The content offset under the cursor is `currentOffset + focalY`; scaling moves it to
 * `(…)*scaleFactor`, so the offset that re-pins it is `(…)*scaleFactor - focalY`. Deliberately UNCLAMPED:
 * the grid scrolls endlessly in both directions, so a negative result is not "above the top" — it is the
 * previous day, which [rollingDayShift] folds into the anchor. Pure, so the anchor math is unit-tested
 * independently of Compose.
 */
internal fun zoomAnchoredOffset(currentOffset: Float, focalY: Float, scaleFactor: Float): Float =
    (currentOffset + focalY) * scaleFactor - focalY

/**
 * PRD §8 infinite scroll: how many WHOLE days [offsetPx] has run past the top of the day it is measured
 * from, given a day's rendered height [dayHeightPx]. The grid holds the invariant
 * `0 <= offsetPx < dayHeightPx` by adding this to its anchor day and subtracting the matching pixels after
 * every scroll and zoom — which is what makes the scrolling endless without any scroll RANGE to run out
 * of: the anchor rolls, the offset stays inside one day. Negative when the user scrolled up past the
 * anchor day's midnight.
 */
internal fun rollingDayShift(offsetPx: Float, dayHeightPx: Float): Int =
    if (dayHeightPx <= 0f) 0 else floor(offsetPx / dayHeightPx).toInt()

/**
 * PRD §8 infinite scroll: how many day-tall rows the grid must compose to cover a [viewportPx]-tall
 * viewport under the `0 <= offset < dayHeightPx` invariant — the days that fit, plus one for the
 * partly-scrolled row at the top. This is what bounds the work to the SCREEN (CLAUDE.md hot-path rule):
 * the number of columns composed follows the viewport and the zoom, never how far the user has scrolled
 * from today.
 */
internal fun rollingRowCount(viewportPx: Float, dayHeightPx: Float): Int =
    if (dayHeightPx <= 0f || viewportPx <= 0f) 1 else ceil(viewportPx / dayHeightPx).toInt() + 1

/**
 * PRD §8 now-line lock: the vertical offset (px along the timeline) that puts the now-line at the MIDDLE of
 * a [viewportPx]-tall viewport. [daysFromAnchorToToday] is how many days today sits below the anchor day
 * (negative above it) and [dayFraction] how far into today the now-line is drawn, so the now-line's content
 * y is `(days + fraction) * dayHeightPx` and centring it is that minus half a viewport. Deliberately
 * UNCLAMPED for the same reason [zoomAnchoredOffset] is: a result outside the anchor day is not "off the
 * calendar" but the neighbouring day, which [rollingDayShift] folds back into the anchor — so the lock
 * works just as well at 00:10 (where the middle of the view is yesterday) as at noon. Pure, so the lock's
 * anchor math is unit-tested independently of Compose.
 */
internal fun nowLineCenterOffset(
    daysFromAnchorToToday: Int,
    dayFraction: Float,
    dayHeightPx: Float,
    viewportPx: Float,
): Float = (daysFromAnchorToToday + dayFraction) * dayHeightPx - viewportPx / 2f

/**
 * PRD §7 date pick: the zoom at which exactly one whole day fills a [viewportPx]-tall viewport, given the
 * day's height [dayHeightPxAtZoom1] at zoom 1f. Picking a day in the side menu's month rail resets the
 * calendar to that day at the top of the viewport at this zoom, so the user sees the whole day (and, with
 * the day columns beside it, the whole week) rather than whatever slice of it the scroll had landed on.
 * Clamped into the zoom bounds like any other zoom, and defensive about the pre-layout viewport of 0 px.
 * The week the columns show is the picked day's own ([weekAnchorDay]), not the six days after it.
 * Pure, so the fit is unit-tested independently of Compose.
 */
internal fun wholeDayZoom(viewportPx: Float, dayHeightPxAtZoom1: Float): Float =
    if (viewportPx <= 0f || dayHeightPxAtZoom1 <= 0f) 1f
    else (viewportPx / dayHeightPxAtZoom1).coerceIn(MIN_CALENDAR_ZOOM, MAX_CALENDAR_ZOOM)

/**
 * PRD §7 date pick: the day that must sit in the LEFTMOST column so the picked [date] is shown with its
 * whole WEEK around it — the Monday of that week, matching the side menu's Monday-first month rail. The
 * columns are consecutive days ([rollingDayAt]), so anchoring on the picked day itself would put it at the
 * left edge and show the six days AFTER it: picking Friday would hide Monday-Thursday, which is not "go to
 * that week" but "start the timeline there". Pure, so the week fit is unit-tested independently of Compose.
 */
internal fun weekAnchorDay(date: LocalDate): LocalDate =
    date.minus(date.dayOfWeek.isoDayNumber - 1, DateTimeUnit.DAY)

/**
 * PRD §8 infinite scroll: the day drawn in column [column] of day-row [row], anchored at [anchorDay] — the
 * day at the top of the viewport in the LEFTMOST column. Columns are consecutive days left→right, and each
 * column continues into its own next day below: under day d sits day d+1, and above it d-1. So the
 * calendar is one endless vertical timeline per column, each phase-shifted a day from its left neighbour,
 * and the day headers roll over as the timeline scrolls past midnight.
 */
internal fun rollingDayAt(anchorDay: LocalDate, row: Int, column: Int): LocalDate =
    anchorDay.plus(row + column, DateTimeUnit.DAY)

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

/** PRD §8: how many day columns the grid shows side by side. Each is its own endless timeline. */
private const val DAY_COLUMNS = 7

@Composable
private fun WeekView(
    selectedDate: LocalDate,
    today: LocalDate,
    nowMillis: Long,
    records: List<CalendarRecord>,
    zoomActions: CalendarZoomActions,
    ctrlHeld: Boolean,
    onAddTaskAt: (Long) -> Unit,
    onAddReminderAt: (Long) -> Unit,
    onAddNoScreenAt: (Long) -> Unit,
    onAddInactivityAt: (Long) -> Unit,
    onCommitBounds: (PlacedRecord, Long, Long, Boolean) -> Unit,
    onEditEntry: (PlacedRecord) -> Unit,
    onRemoveEntry: (PlacedRecord) -> Unit,
    onToggleReminder: (PlacedRecord) -> Unit,
    onAdjustWeights: (Map<String, Double>) -> Unit,
    overlapArmed: Boolean,
    jumpNonce: Int,
    onVisibleDaysChanged: (LocalDate, Int) -> Unit,
    /** PRD §8: hold the now-line at the middle of the viewport (see the lock block below). */
    lockNowLine: Boolean,
    /** Releases the lock: the user scrolled, or picked another date — either way, they looked elsewhere. */
    onLockNowLineChange: (Boolean) -> Unit,
) {
    val tz = remember { TimeZone.currentSystemDefault() }
    // Follows the (possibly simulated) clock so the now-line moves as accelerated time advances.
    val now = Instant.fromEpochMilliseconds(nowMillis).toLocalDateTime(tz).time
    // PRD §8 zoom: the row height scales with [zoom]; the gestures keep the point under the cursor fixed.
    var zoom by remember { mutableStateOf(1f) }
    val hourHeight = BASE_HOUR_HEIGHT * zoom
    val gutterWidth = 56.dp
    val density = LocalDensity.current
    val dayHeightPx = with(density) { (hourHeight * 24).toPx() }

    // PRD §8 infinite scroll: the grid is not a week of 24-hour columns any more but a CONTINUOUS timeline.
    // [anchorDay] is the day at the top of the viewport in the leftmost column and [offsetPx] how far into
    // that day we are scrolled; every scroll/zoom restores `0 <= offsetPx < dayHeightPx` by rolling whole
    // days into the anchor ([rollingDayShift]). That is the whole trick: there is no scroll range to hit,
    // so scrolling down past midnight simply rolls every column onto its own next day, forever, and up
    // onto the previous one. Column i draws [anchorDay] + i (see [rollingDayAt]).
    var anchorDay by remember { mutableStateOf(selectedDate) }
    var offsetPx by remember { mutableStateOf(0f) }
    // PRD §8 zoom-to-cursor: the pointer's Y within the scroll viewport (the focal point a zoom pivots
    // around) and the viewport height (the fallback focal — its centre — for keyboard zoom with no cursor).
    var focalYpx by remember { mutableStateOf<Float?>(null) }
    var viewportHpx by remember { mutableStateOf(0f) }
    // PRD §8: while a block is being dragged/resized, lock the grid's vertical scroll so it doesn't
    // compete with the block's own drag gesture.
    var scrollLocked by remember { mutableStateOf(false) }
    // The gesture handlers below outlive the composition that created them (`pointerInput(Unit)`), so they
    // must never close over a plain per-composition value. State delegates and this holder are remembered
    // objects, so reading through them is always current; the day height is recomputed from [zoom].
    val densityState = rememberUpdatedState(density)

    fun dayHeightPxAt(z: Float): Float = with(densityState.value) { (BASE_HOUR_HEIGHT * z * 24).toPx() }

    // Restore the `0 <= offsetPx < dayHeight` invariant by rolling the whole days scrolled past into the
    // anchor day. Called after every offset change; [dayH] is the height in force AFTER the change.
    fun rebase(dayH: Float) {
        val roll = rollingDayShift(offsetPx, dayH)
        if (roll != 0) {
            anchorDay = anchorDay.plus(roll, DateTimeUnit.DAY)
            offsetPx -= roll * dayH
        }
    }

    // PRD §8 now-line lock: while the title bar's switch is on, the timeline is held with the now-line at
    // the middle of the viewport — so the plan around the present stays centred as the clock advances, and
    // a zoom pivots on the now-line instead of the cursor. The gesture handlers below outlive the
    // composition that created them (`pointerInput(Unit)` / the scrollable lambda), so they read the flag
    // and the release callback through [rememberUpdatedState] rather than closing over them.
    val lockNow = rememberUpdatedState(lockNowLine)
    val releaseLock = rememberUpdatedState(onLockNowLineChange)
    // How far into today the now-line sits, as a fraction of the day. Deliberately to the MINUTE and not
    // finer: that is exactly where DayColumn's current-time indicator is drawn, and the lock must centre on
    // the line the user can see, not on a truer instant a pixel away from it.
    val nowFraction = (now.hour + now.minute / 60f) / 24f
    val nowFractionState = rememberUpdatedState(nowFraction)
    val todayState = rememberUpdatedState(today)

    // Scroll so the now-line lands on the middle of the viewport, then roll the whole days out as usual.
    // [dayH] is the day height in force AFTER whatever change prompted the re-centring.
    fun centerOnNowLine(dayH: Float) {
        if (dayH <= 0f || viewportHpx <= 0f) return
        offsetPx = nowLineCenterOffset(
            daysFromAnchorToToday = anchorDay.daysUntil(todayState.value),
            dayFraction = nowFractionState.value,
            dayHeightPx = dayH,
            viewportPx = viewportHpx,
        )
        rebase(dayH)
    }

    // PRD §8 zoom-to-cursor: scale the timeline and re-pin the time under [focal]. A pinch's centroid also
    // travels between events (two-finger pan): [focalAfter] is where the anchored time must land, so the
    // content follows the fingers; keyboard/wheel zooms leave it at [focal]. Unlike the old bounded scroll
    // state this needs no mutex and no wait for the scroll range to grow — the offset is a plain number
    // with no upper bound, so a burst of zoom steps composes exactly, one synchronous update each.
    fun applyZoom(factor: Float, focal: Float, focalAfter: Float = focal) {
        val next = (zoom * factor).coerceIn(MIN_CALENDAR_ZOOM, MAX_CALENDAR_ZOOM)
        if (next == zoom && focal == focalAfter) return
        val f = next / zoom
        zoom = next
        val dayH = dayHeightPxAt(next)
        // PRD §8 now-line lock: the zoom is anchored on the NOW-LINE, not on the cursor — so re-centre
        // rather than re-pinning whatever happened to be under [focal]. A pinch's pan component
        // ([focalAfter]) is dropped for the same reason: while locked, the middle of the view is the
        // now-line and nothing else may move it.
        if (lockNow.value && viewportHpx > 0f) {
            centerOnNowLine(dayH)
            return
        }
        offsetPx = zoomAnchoredOffset(offsetPx, focal, f) + (focal - focalAfter)
        rebase(dayH)
    }

    // The scroll itself: an unbounded offset instead of a clamped ScrollState. Mirrors `verticalScroll`'s
    // sign convention (same `reverseDirection`), so wheel, drag and fling behave exactly as before.
    val scrollableState = rememberScrollableState { delta ->
        // PRD §8 now-line lock: a scroll — wheel, drag or fling — is the user taking the view somewhere
        // else, so it switches the lock off rather than being fought by it on the next tick.
        if (delta != 0f && lockNow.value) releaseLock.value(false)
        offsetPx += delta
        rebase(dayHeightPxAt(zoom))
        delta
    }
    val reverseScroll =
        ScrollableDefaults.reverseDirection(LocalLayoutDirection.current, Orientation.Vertical, false)

    // Register the keyboard shortcuts (driven from CalendarFloatingWindow). They pivot around the cursor if
    // it is over the grid, else the viewport centre.
    zoomActions.zoomIn = { applyZoom(CALENDAR_ZOOM_STEP, focalYpx ?: viewportHpx / 2f) }
    zoomActions.zoomOut = { applyZoom(1f / CALENDAR_ZOOM_STEP, focalYpx ?: viewportHpx / 2f) }
    zoomActions.reset = { applyZoom(1f / zoom, focalYpx ?: viewportHpx / 2f) }
    val ctrl = rememberUpdatedState(ctrlHeld)

    // PRD §8 (Google-Calendar style): open scrolled to the current time so today's "task to do now"
    // block (which starts at the present hour) is visible without manual scrolling. Show one hour of
    // lead context above it.
    LaunchedEffect(Unit) {
        offsetPx = (with(densityState.value) { BASE_HOUR_HEIGHT.toPx() } * (now.hour - 1)).coerceAtLeast(0f)
    }
    // PRD §7: picking a date in the lateral month calendar RESETS the view onto that day's WHOLE WEEK —
    // the week's Monday becomes the leftmost column's day ([weekAnchorDay]), scrolled to its midnight and
    // zoomed so exactly one whole day fills the viewport. So the pick always lands on the same, legible
    // view (the picked day's week of columns, each showing its whole day) however deep into a zoom or how
    // far into a day's middle the endless scroll had wandered; an earlier behaviour kept the zoom and the
    // time of day, which meant "go to the 12th" could show four hours of it, and anchoring on the picked
    // day itself put it at the LEFT EDGE — showing the six days after it instead of the week around it.
    // Keyed on [jumpNonce] as well as the date because the scroll can carry the grid AWAY
    // from [selectedDate] (that is what endless scrolling is), so picking the day already selected — "take
    // me back to today" — is a real jump with nothing changed to key on. The nonce is the pick itself.
    LaunchedEffect(jumpNonce, selectedDate) {
        anchorDay = weekAnchorDay(selectedDate)
        // Before the first layout the viewport height is unknown, so there is no whole-day fit to compute:
        // leave the zoom and the offset to the initial "open at the current hour" effect above. Only a real
        // pick (which can only happen once the calendar is on screen and measured) resets the view.
        if (viewportHpx > 0f) {
            zoom = wholeDayZoom(viewportHpx, dayHeightPxAt(1f))
            offsetPx = 0f
        }
        // PRD §8 now-line lock: a date pick is the same intent as a scroll — "take me elsewhere" — so it
        // releases the lock, which would otherwise pull the grid straight back off that day on the next tick.
        if (lockNow.value) releaseLock.value(false)
    }

    // PRD §8 now-line lock: re-apply the centring whenever anything it is a function of moves — the clock,
    // the zoom, the viewport height — and once when the switch is turned on. It is an effect rather than a
    // layout-phase read of a derived offset because centring can roll the ANCHOR DAY over (at either end of
    // the day the middle of the view belongs to the neighbouring one), which is a composition-level change.
    // Cost follows the screen, not the history (CLAUDE.md hot-path rule): it is a handful of arithmetic per
    // observed now-line, and the observed now-line is already quantized upstream.
    LaunchedEffect(lockNowLine, nowMillis, zoom, viewportHpx) {
        if (lockNowLine) centerOnNowLine(dayHeightPxAt(zoom))
    }

    // How many day-rows cover the viewport, and therefore which days are on screen: the columns span
    // [anchorDay, anchorDay + DAY_COLUMNS - 1] at the top row and one day further down per row.
    val rowCount = rollingRowCount(viewportHpx, dayHeightPx)
    val visibleDayCount = rowCount + DAY_COLUMNS - 1
    // PRD §9: the schedule horizon and every display projection follow what is actually on screen, so the
    // span the scroll has landed on is reported up rather than derived from a "focused week" that no
    // longer exists.
    LaunchedEffect(anchorDay, visibleDayCount) { onVisibleDaysChanged(anchorDay, visibleDayCount) }

    // PRD §8 "there must not be overlaps" (default mode): every block on the calendar (records,
    // scheduled, manual) as (key, range), so a dragged block snaps around ALL of them live. Reminder tags
    // (zero-duration, §14), alarm rings (zero-duration, §18) and screen-break markers (§15) are not blocks
    // and are excluded.
    val allBlocks = records.filterNot { it.reminder || it.screenBreak || it.alarm || it.sleep || it.inactivity }
        .map { calendarBlockKey(it) to it.range }

    // PRD §8 hover title: the block/screen-break under the cursor, reported up from each element so a single
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
                text = monthLabel(anchorDay),
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

        // Day-of-week + date headers, aligned over their columns. They name each column's day at the TOP of
        // the viewport, so they roll forward one day at a time as the grid scrolls past midnight.
        Row(Modifier.fillMaxWidth()) {
            Spacer(Modifier.width(gutterWidth))
            repeat(DAY_COLUMNS) { column ->
                val day = rollingDayAt(anchorDay, row = 0, column = column)
                DayHeader(
                    weekday = WEEKDAY_SHORT[day.dayOfWeek.isoDayNumber - 1],
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
                .clipToBounds()
                .onSizeChanged { viewportHpx = it.height.toFloat() }
                // PRD §8 zoom-to-cursor: track the cursor's Y in the viewport, and on Ctrl+scroll zoom
                // toward it (consumed at the Initial pass so the grid doesn't also scroll). A plain wheel
                // turn isn't consumed, so it falls through to the scrollable below. Ctrl is read from the
                // scroll event's own keyboard modifiers (not the focus-tracked [ctrl]) so zoom works whenever
                // the cursor is over the calendar — even if it doesn't hold keyboard focus. Pointer hit-testing
                // means a panel drawn over the calendar receives the wheel instead, so it correctly "doesn't count".
                .pointerInput(Unit) {
                    // PRD §8 double-tap-and-drag zoom (touch): a quick tap followed by a held press that
                    // drags vertically zooms around the tap point (drag down = in, up = out). A second tap
                    // RELEASED without dragging is left unconsumed — the day column reads it as the
                    // "double click and release" contextual-menu gesture.
                    var tapUpAtMs = 0L
                    var tapPos = Offset.Zero
                    var pressAtMs = 0L
                    var pressPos = Offset.Zero
                    var pressMoved = false
                    var secondPress = false
                    var dtZooming = false
                    var lastY = 0f
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            event.changes.firstOrNull()?.let { focalYpx = it.position.y }
                            val touch = event.changes.singleOrNull()?.takeIf { it.type == PointerType.Touch }
                            when {
                                touch == null -> {
                                    // Multi-finger (pinch) or non-touch input: abandon the tap sequence.
                                    if (event.changes.count { it.pressed } >= 2) {
                                        secondPress = false; dtZooming = false; tapUpAtMs = 0L
                                    }
                                }
                                event.type == PointerEventType.Press -> {
                                    pressAtMs = touch.uptimeMillis
                                    pressPos = touch.position
                                    pressMoved = false
                                    lastY = touch.position.y
                                    secondPress =
                                        touch.uptimeMillis - tapUpAtMs <= viewConfiguration.doubleTapTimeoutMillis &&
                                        (touch.position - tapPos).getDistance() <= viewConfiguration.touchSlop * 4
                                    dtZooming = false
                                }
                                event.type == PointerEventType.Move -> {
                                    if ((touch.position - pressPos).getDistance() > viewConfiguration.touchSlop) {
                                        pressMoved = true
                                        if (secondPress) dtZooming = true
                                    }
                                    if (dtZooming) {
                                        val dy = touch.position.y - lastY
                                        if (dy != 0f) {
                                            // Exponential so equal drags give equal zoom ratios; anchored
                                            // at the first tap's point.
                                            applyZoom(exp(dy / 200f), pressPos.y)
                                        }
                                        touch.consume()
                                    }
                                    lastY = touch.position.y
                                }
                                event.type == PointerEventType.Release -> {
                                    if (dtZooming) {
                                        touch.consume()
                                        tapUpAtMs = 0L
                                    } else if (!pressMoved &&
                                        touch.uptimeMillis - pressAtMs <= viewConfiguration.longPressTimeoutMillis
                                    ) {
                                        // A clean tap: remember it as the (possible) first of a pair; a
                                        // second tap-release is the day column's contextual-menu gesture.
                                        tapUpAtMs = touch.uptimeMillis
                                        tapPos = touch.position
                                    } else {
                                        tapUpAtMs = 0L
                                    }
                                    secondPress = false
                                    dtZooming = false
                                }
                            }
                            val zoomModifier = event.keyboardModifiers.pointerCtrlPressed ||
                                event.keyboardModifiers.pointerMetaPressed || ctrl.value
                            if (event.type == PointerEventType.Scroll && zoomModifier) {
                                val change = event.changes.firstOrNull()
                                val dy = change?.scrollDelta?.y ?: 0f
                                if (dy != 0f) {
                                    val focal = change!!.position.y
                                    applyZoom(if (dy < 0f) CALENDAR_ZOOM_STEP else 1f / CALENDAR_ZOOM_STEP, focal)
                                    event.changes.forEach { it.consume() }
                                }
                            }
                            // PRD §8 pinch-to-zoom (touch — the only zoom input on Android): with two+
                            // fingers down, zoom by the pinch ratio anchored at the fingers' centroid,
                            // following the centroid's travel (two-finger pan). Consumed at Initial so the
                            // grid's vertical scroll and the blocks' drag handles don't fight the pinch.
                            if (event.type == PointerEventType.Move && event.changes.count { it.pressed } >= 2) {
                                val pinch = event.calculateZoom()
                                val oldFocal = event.calculateCentroid(useCurrent = false).y
                                val newFocal = event.calculateCentroid(useCurrent = true).y
                                if (oldFocal.isFinite() && newFocal.isFinite() &&
                                    (pinch != 1f || newFocal != oldFocal)
                                ) {
                                    applyZoom(pinch, oldFocal, newFocal)
                                }
                                event.changes.forEach { it.consume() }
                            }
                        }
                    }
                }
                .scrollable(
                    state = scrollableState,
                    orientation = Orientation.Vertical,
                    enabled = !scrollLocked,
                    reverseDirection = reverseScroll,
                ),
        ) {
            // Each day-row is positioned by a LAYOUT-phase read of [offsetPx] (`offset { … }`), never a
            // composition-phase one: scrolling then only re-lays-out, and the DAY_COLUMNS × [rowCount]
            // columns are recomposed once per day boundary crossed instead of once per scrolled pixel.
            Row(Modifier.fillMaxSize()) {
                // Time gutter: hour labels, plus sub-hour minute labels (":30", ":15", …) once zoomed in.
                // Repeated per day-row — every column crosses midnight at the same height, so one gutter
                // serves them all.
                Box(Modifier.width(gutterWidth).fillMaxHeight()) {
                    repeat(rowCount) { row ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                // requiredHeight, not height: [Modifier.height] enforces the INCOMING
                                // constraints, and the scroll viewport hands down its own (bounded) height —
                                // so a day taller than the viewport would be clamped to it. See [DayColumn]'s
                                // own note below.
                                .requiredHeight(hourHeight * 24)
                                .offset { IntOffset(0, (row * dayHeightPx - offsetPx).roundToInt()) },
                        ) {
                            val tick = calendarTickMinutes(hourHeight)
                            val tickHeight = hourHeight * (tick / 60f)
                            var minutes = 0
                            while (minutes < 24 * 60) {
                                val hour = minutes / 60
                                val minute = minutes % 60
                                Box(Modifier.height(tickHeight).fillMaxWidth().padding(end = 6.dp)) {
                                    Text(
                                        text = if (minute == 0) hourLabel(hour)
                                        else ":" + minute.toString().padStart(2, '0'),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (minute == 0) CalColors.muted
                                        else CalColors.muted.copy(alpha = 0.5f),
                                        textAlign = TextAlign.End,
                                        modifier = Modifier.fillMaxWidth().offset(y = (-6).dp),
                                    )
                                }
                                minutes += tick
                            }
                        }
                    }
                }
                repeat(DAY_COLUMNS) { column ->
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        repeat(rowCount) { row ->
                            val day = rollingDayAt(anchorDay, row, column)
                            // Keyed on the day so a column's transient state (an open contextual menu, an
                            // armed move) belongs to the date it was opened on and cannot be inherited by
                            // the next day that rolls into the same slot.
                            key(day) {
                                DayColumn(
                                    day = day,
                                    tz = tz,
                                    isToday = day == today,
                                    hourHeight = hourHeight,
                                    now = if (day == today) now else null,
                                    records = recordsForDay(records, day, tz),
                                    onAddTaskAt = onAddTaskAt,
                                    onAddReminderAt = onAddReminderAt,
                                    onAddNoScreenAt = onAddNoScreenAt,
                                    onAddInactivityAt = onAddInactivityAt,
                                    onCommitBounds = onCommitBounds,
                                    onEditEntry = onEditEntry,
                                    onRemoveEntry = onRemoveEntry,
                                    onToggleReminder = onToggleReminder,
                                    onLockScroll = { scrollLocked = it },
                                    onAdjustWeights = onAdjustWeights,
                                    allBlocks = allBlocks,
                                    overlapArmed = overlapArmed,
                                    hoverScope = hoverScope,
                                    // requiredHeight, not height: a day-row is `dayHeightPx` tall by
                                    // construction (that is what the placement above assumes), but the
                                    // scroll viewport is not — [Modifier.scrollable] passes its own bounded
                                    // constraints straight through, where the old [verticalScroll] measured
                                    // its content with maxHeight = Infinity. [Modifier.height] ENFORCES the
                                    // incoming constraints, so it would clamp each row to the viewport
                                    // height: the hour-line Column would then run out of main-axis space and
                                    // measure its trailing hour boxes at zero height (the grid vanishing in
                                    // the lower part of the view), and the rows would no longer tile.
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .requiredHeight(hourHeight * 24)
                                        .offset { IntOffset(0, (row * dayHeightPx - offsetPx).roundToInt()) },
                                )
                            }
                            // The header only names each column's TOP day, so every boundary scrolled into
                            // view names the day it opens.
                            if (row > 0) {
                                Text(
                                    text = "${WEEKDAY_SHORT[day.dayOfWeek.isoDayNumber - 1]} ${day.dayOfMonth}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (day == today) CalColors.accent else CalColors.muted,
                                    modifier = Modifier
                                        .offset {
                                            IntOffset(0, (row * dayHeightPx - offsetPx).roundToInt() + 2)
                                        }
                                        .background(
                                            CalColors.menuBackground.copy(alpha = 0.85f),
                                            RoundedCornerShape(4.dp),
                                        )
                                        .padding(horizontal = 4.dp),
                                )
                            }
                        }
                    }
                }
            }
            Box(
                Modifier.fillMaxSize().drawBehind {
                    // The day boundaries, drawn over the columns (a block crossing midnight must not hide
                    // the seam) and at one height for the whole grid — every column crosses into its own
                    // next day at the same pixel.
                    var row = 0
                    while (row <= rowCount) {
                        val y = row * dayHeightPx - offsetPx
                        drawLine(
                            color = CalColors.muted.copy(alpha = 0.55f),
                            start = Offset(0f, y),
                            end = Offset(size.width, y),
                            strokeWidth = 1.5.dp.toPx(),
                        )
                        row++
                    }
                },
            )
        }
        // PRD §8 hover title bubble, drawn above all columns; non-interactive so the cursor passes through.
        titleHover?.let { CalendarTitleBubble(it.title, it.pos, it.subtitle, it.underTitle, it.underSubtitle) }
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
    onAddReminderAt: (Long) -> Unit,
    onAddNoScreenAt: (Long) -> Unit,
    onAddInactivityAt: (Long) -> Unit,
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
    // PRD §14/§15/§18: reminders and alarm rings (zero-duration) and screen breaks (sub-minute durations)
    // render on their own fixed-height marker paths; everything else is a height-proportional, draggable
    // block. Split them so the block pipeline only sees real blocks (drawing screen breaks to scale would
    // make them invisible).
    val reminderTags = records.filter { it.reminder }
    val screenBreakMarkers = records.filter { it.screenBreak }
    val alarmMarkers = records.filter { it.alarm }
    val sleepBands = records.filter { it.sleep }
    // Derived bands (account-offline "No screen" windows, legacy Inactivity) carry no entryId; a
    // user-authored no-screen / inactivity PANEL (entryId set) is a real, removable block and stays in
    // the block pipeline (PRD §8/§12).
    val inactivityBands = records.filter { (it.inactivity || it.noScreen) && it.entryId == null }
    val blockRecords =
        records.filterNot {
            it.reminder || it.screenBreak || it.alarm || it.sleep ||
                ((it.inactivity || it.noScreen) && it.entryId == null)
        }
    // PRD §17: the fill schedules the work plan straight through the nightly sleep windows, so a block may
    // land (partly) inside one. The overlapping sub-range is greyed "as if under the Sleep band" while the
    // block stays a normal interactive block. Bands and blocks are both clipped to this day, so hour ranges
    // compare directly.
    val sleepHourRanges = sleepBands.map { it.startHour..it.endHour }
    // The right-click position (in this column's local pixels) that anchors the contextual menu; null
    // when no menu is open. [menuTarget] is the block the click landed on (null = empty space).
    var menuOffset by remember { mutableStateOf<Offset?>(null) }
    var menuTarget by remember { mutableStateOf<PlacedRecord?>(null) }
    // PRD §8 (phone): whether the open menu came from the touch double-tap — it then carries the panel
    // info at its top (no hover bubble on a phone) and offers "move" (no direct touch drag).
    var menuFromTouch by remember { mutableStateOf(false) }
    // PRD §8 (phone) "move": the block armed by the menu's "move" option — the next touch drag moves it
    // (live preview through [dragPreview]) and the release commits.
    var movePending by remember { mutableStateOf<PlacedRecord?>(null) }
    // Latest records, so the right-click hit-test closure never reads a stale list (records change
    // every scheduler tick) without restarting the long-lived gesture coroutine.
    val currentRecords by rememberUpdatedState(blockRecords)
    // PRD §8: the sleep bands are menu targets too (their menu leads with "Edit" → the §17 sleep-schedule
    // window), hit-tested behind the real blocks. Same staleness guard as [currentRecords].
    val currentSleepBands by rememberUpdatedState(sleepBands)

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

    // PRD §8: the contextual-menu target at [offsetY] — a real block first, else the sleep band under
    // the click (the §17 band is a panel too: its menu leads with "Edit", opening the sleep window).
    fun menuTargetAt(offsetY: Float): PlacedRecord? {
        blockAt(offsetY)?.let { return it }
        val hourHeightPx = with(density) { hourHeight.toPx() }
        return currentSleepBands.lastOrNull {
            offsetY >= it.startHour * hourHeightPx && offsetY <= it.endHour * hourHeightPx
        }
    }

    Box(
        modifier = modifier
            .fillMaxHeight()
            .background(if (isToday) CalColors.today.copy(alpha = 0.4f) else Color.Transparent)
            .border(width = 0.5.dp, color = CalColors.grid)
            // PRD §8: right-click (desktop) or a touch double-tap-and-release (phone) opens the
            // contextual menu. On a task block it offers "Edit"/"Remove" (+ "move" and the panel info on
            // touch); on empty space it offers the "add" actions. The whole column owns this so the menu
            // choice is a reliable hit-test (no fragile cross-node pointer-consumption ordering). The
            // same handler runs the menu-armed "move" drag (phone), previewing through [dragPreview].
            .pointerInput(day) {
                var tapUpAtMs = 0L
                var tapPos = Offset.Zero
                var pressPos = Offset.Zero
                var pressAtMs = 0L
                var pressMoved = false
                var moveStartY = 0f
                var movePlaced: TaskTimeRange? = null
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                            val change = event.changes.firstOrNull() ?: continue
                            change.consume()
                            menuTarget = menuTargetAt(change.position.y)
                            menuOffset = change.position
                            menuFromTouch = false
                            continue
                        }
                        val touch = event.changes.singleOrNull()?.takeIf { it.type == PointerType.Touch }
                        // PRD §8 (phone) "move": while armed, a touch drag moves the block (snapped live
                        // like a desktop drag) and the release commits; a bare tap cancels the mode.
                        val moving = movePending
                        if (moving != null && touch != null) {
                            val hourHeightPx = with(density) { hourHeight.toPx() }
                            when (event.type) {
                                PointerEventType.Press -> {
                                    moveStartY = touch.position.y
                                    movePlaced = null
                                    onLockScroll(true)
                                    touch.consume()
                                }
                                PointerEventType.Move -> {
                                    val duration = moving.fullEndMillis - moving.fullStartMillis
                                    val rawStart = moving.fullStartMillis +
                                        (((touch.position.y - moveStartY) / hourHeightPx) * 3_600_000f).toLong()
                                    val others = allBlocks
                                        .filter { it.first != calendarBlockKey(moving) }
                                        .map { it.second }
                                    val placed = SchedulerDomain.placeDraggedEntry(others, rawStart, duration)
                                    movePlaced = placed
                                    dragPreview = calendarBlockKey(moving) to placed
                                    touch.consume()
                                }
                                PointerEventType.Release -> {
                                    movePlaced?.let {
                                        onCommitBounds(moving, it.startEpochMillis, it.endEpochMillis, false)
                                    }
                                    movePlaced = null
                                    dragPreview = null
                                    movePending = null
                                    onLockScroll(false)
                                    touch.consume()
                                }
                            }
                            continue
                        }
                        // PRD §8 (phone): double tap AND release → contextual menu. The zoom gesture
                        // (double-tap-drag, handled by the week viewport) consumes its events, so a
                        // consumed release never opens the menu.
                        if (touch != null) {
                            when (event.type) {
                                PointerEventType.Press -> {
                                    pressPos = touch.position
                                    pressAtMs = touch.uptimeMillis
                                    pressMoved = false
                                }
                                PointerEventType.Move -> {
                                    if ((touch.position - pressPos).getDistance() > viewConfiguration.touchSlop) {
                                        pressMoved = true
                                    }
                                }
                                PointerEventType.Release -> {
                                    val cleanTap = !pressMoved && !touch.isConsumed &&
                                        touch.uptimeMillis - pressAtMs <= viewConfiguration.longPressTimeoutMillis
                                    if (cleanTap &&
                                        touch.uptimeMillis - tapUpAtMs <= viewConfiguration.doubleTapTimeoutMillis &&
                                        (touch.position - tapPos).getDistance() <= viewConfiguration.touchSlop * 4
                                    ) {
                                        menuTarget = menuTargetAt(touch.position.y)
                                        menuOffset = touch.position
                                        menuFromTouch = true
                                        tapUpAtMs = 0L
                                    } else {
                                        tapUpAtMs = if (cleanTap) touch.uptimeMillis else 0L
                                        tapPos = touch.position
                                    }
                                }
                            }
                        } else if (event.changes.count { it.pressed } >= 2) {
                            tapUpAtMs = 0L
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

        // The user's sleep windows: a greyed band drawn behind the task blocks so sleep time reads as
        // greyed even in the gaps between blocks. Past pauses (PRD §15 device-sleep gaps) render as the
        // same band. The blocks the fill now schedules through the window are drawn on top, each greying
        // its own sleep sub-range to match (see CalendarBlock's sleepHourRanges); the band's
        // "Sleep"/"Inactivity" label is drawn on top of them below, so it stays legible at the band's start.
        // PRD §8 decorative bands, drawn behind the task blocks. Two kinds stack here:
        //   • an awake account-offline window reads "Inactivity" as its base + a "No screen" oblique-line
        //     hatch ("/") on top;
        //   • a sleep window is BY DEFINITION also a no-screen period, so it carries BOTH decorative
        //     patterns — its own sleep hatch (oblique lines in the OPPOSITE direction, "\") crossed with
        //     the no-screen "/" hatch — and its hover lists "Sleep" first, then "No screen". The no-screen
        //     stretch may run past the sleep into a directly-following/preceding offline window, so the
        //     sleep band's "No screen" line shows that enclosing window's (>=) span ([noScreenRange]).
        // No-screen bands are composed FIRST and sleep bands ON TOP, so a slept-through night's hover
        // resolves to the sleep band ("Sleep" first) rather than the offline band beneath it.
        data class DecorBand(
            val band: PlacedRecord,
            val baseLabel: String,
            val decorLabel: String?,
            val decorRange: TaskTimeRange?,
            val noScreenHatch: Boolean,
            val sleepHatch: Boolean,
        )
        (inactivityBands.map {
            DecorBand(it, "Inactivity", if (it.noScreen) "No screen" else null, null, it.noScreen, false)
        } + sleepBands.map {
            DecorBand(it, "Sleep", "No screen", it.noScreenRange, noScreenHatch = true, sleepHatch = true)
        }).forEach { (band, baseLabel, decorLabel, decorRange, noScreenHatch, sleepHatch) ->
                // PRD §8: like every other block, hovering the band pops the title + true (un-clipped) start–end times.
                // PRD §12: a band open-ended into the past shows "∞" as its start (the drawn start is only the floor).
                val timeRange = "${hmOrInfinity(band.fullStartMillis, band.openStart, tz)} – ${formatHm(band.fullEndMillis, tz)}"
                // The "No screen" decorative line spans its enclosing offline window (>= the band itself).
                val decorTimeRange =
                    decorRange?.let { "${formatHm(it.startEpochMillis, tz)} – ${formatHm(it.endEpochMillis, tz)}" }
                        ?: timeRange
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .offset(y = hourHeight * band.startHour)
                        .height(hourHeight * (band.endHour - band.startHour))
                        .clipToBounds()
                        .background(CalColors.muted.copy(alpha = SLEEP_BAND_ALPHA))
                        // PRD §8 decorative panels: the no-screen "/" hatch, and — for a sleep window — the
                        // sleep "\" hatch crossed over it, so the two stacked decorative panels read distinctly.
                        .then(if (noScreenHatch) Modifier.obliqueHatch(CalColors.muted, reversed = false) else Modifier)
                        .then(if (sleepHatch) Modifier.obliqueHatch(CalColors.muted, reversed = true) else Modifier),
                ) {
                    // PRD §8: the fill schedules work straight through the night / off-screen tasks land
                    // inside a no-screen window, so a real panel (or the derived Inactivity/No-screen band
                    // itself, elsewhere) often sits under this decorative band. Tiled by which panel is
                    // under each sub-range, so the hover bubble stacks that panel's title/time below this
                    // band's own — instead of the two elements' independent hover reports racing/overwriting
                    // each other (see [deviceHoverZones]).
                    decorativeHoverZones(band.startHour, band.endHour, blockRecords).forEach { zone ->
                        // Line 1 (read first) = the real panel under this sub-range: an off-screen task the
                        // no-screen sits over, else the band's own base ("Inactivity"/"Sleep"). Line 2 = the
                        // "No screen" decorative on top. (A reachable hover is almost always a null zone —
                        // any real task here is drawn as its own block on top and reports itself.)
                        val baseTitle = zone.under?.let(::underHoverTitle) ?: baseLabel
                        val baseTimes = zone.under
                            ?.let { "${formatHm(it.fullStartMillis, tz)} – ${formatHm(it.fullEndMillis, tz)}" }
                            ?: timeRange
                        Box(
                            Modifier
                                .offset(y = hourHeight * (zone.top - band.startHour))
                                .fillMaxWidth()
                                .height(hourHeight * (zone.bottom - zone.top))
                                .calendarTitleHover(
                                    baseTitle,
                                    hoverScope,
                                    subtitle = baseTimes,
                                    underTitle = decorLabel,
                                    underSubtitle = decorLabel?.let { decorTimeRange },
                                ),
                        )
                    }
                }
            }

        // PRD §8 contextual menu, anchored at the right-click position. A block gets Edit/Remove; both a
        // block and a gap also get the "add" actions (anchored at the right-click time), so a panel's menu
        // is a superset of the gap's.
        val anchor = menuOffset
        fun closeMenu() { menuOffset = null; menuTarget = null }
        DropdownMenu(
            expanded = anchor != null,
            onDismissRequest = { closeMenu() },
            offset = anchor?.let { with(density) { DpOffset(it.x.toDp(), it.y.toDp()) } } ?: DpOffset.Zero,
        ) {
            val target = menuTarget
            // PRD §8 (phone): the panel info tops the touch contextual menu — a phone has no hover bubble.
            if (menuFromTouch && target != null) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                    Text(
                        text = target.title.ifEmpty { "(untitled)" },
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Text(
                        text = "${hmOrInfinity(target.fullStartMillis, target.openStart, tz)} – ${formatHm(target.fullEndMillis, tz)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = CalColors.muted,
                    )
                }
            }
            if (target != null) {
                // PRD §8: the menu on a task / no-screen / sleep panel leads with "Edit" — a task panel
                // opens the calendar edit window, a no-screen period a times-only editor, a sleep band
                // the §17 sleep-schedule window (routed by the App's onEditEntry). An inactivity period
                // has nothing to edit (no task behind it) and only offers Remove.
                if (!target.inactivity) {
                    DropdownMenuItem(
                        text = { Text("Edit") },
                        onClick = { closeMenu(); onEditEntry(target) },
                    )
                }
                // The generated sleep band is not a removable/movable entity (no panel behind it).
                if (!target.sleep) {
                    DropdownMenuItem(
                        text = { Text("Remove") },
                        onClick = { closeMenu(); onRemoveEntry(target) },
                    )
                    // PRD §8 (phone): a touch drag scrolls the grid, so a block is moved by arming this
                    // menu option and then dragging — the release commits (see the column's gesture
                    // handler).
                    if (menuFromTouch) {
                        DropdownMenuItem(
                            text = { Text("move") },
                            onClick = { closeMenu(); movePending = target },
                        )
                    }
                }
            }
            DropdownMenuItem(
                text = { Text("add a task") },
                onClick = {
                    anchor?.let { onAddTaskAt(millisAt(it.y)) }
                    closeMenu()
                },
            )
            DropdownMenuItem(
                text = { Text("add a no-screen period") },
                onClick = {
                    anchor?.let { onAddNoScreenAt(millisAt(it.y)) }
                    closeMenu()
                },
            )
            DropdownMenuItem(
                text = { Text("add an inactivity period") },
                onClick = {
                    anchor?.let { onAddInactivityAt(millisAt(it.y)) }
                    closeMenu()
                },
            )
            DropdownMenuItem(
                text = { Text("add reminder") },
                onClick = {
                    anchor?.let { onAddReminderAt(millisAt(it.y)) }
                    closeMenu()
                },
            )
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
                sleepHourRanges = sleepHourRanges,
                overlapArmed = overlapArmed,
                // Hide the resting (gesture-holding) slices while any move/resize preview overlay shows.
                previewActive = previewActive,
                onPreviewChange = { range -> dragPreview = range?.let { key to it } },
                onCommitBounds = onCommitBounds,
                onLockScroll = onLockScroll,
                hoverScope = hoverScope,
                tz = tz,
                onEditEntry = onEditEntry,
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
                            // Transient drag preview: left untinted; the resting block greys its sleep
                            // sub-range on release (see [CalendarBlock]'s sleepHourRanges overlay).
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
        // PRD §14: scheduled reminders that fall at the same time — or close enough that their fixed-height
        // tags would overlap — stack downward instead of drawing on top of each other. Sweep them in time
        // order, pushing each tag below the previous one whenever its natural slot would collide.
        var lastScheduledBottom: Dp? = null
        reminderTags.filterNot(::onNowLine)
            .sortedBy { checkedAtHour(it) ?: it.startHour }
            .forEach { tag ->
                val naturalY = hourHeight * (checkedAtHour(tag) ?: tag.startHour)
                val y = lastScheduledBottom?.let { maxOf(naturalY, it) } ?: naturalY
                lastScheduledBottom = y + REMINDER_TAG_HEIGHT
                ReminderTag(tag, Modifier.offset(y = y)) { onToggleReminder(tag) }
            }
        reminderTags.filter(::onNowLine).forEachIndexed { i, tag ->
            ReminderTag(tag, Modifier.offset(y = hourHeight * (nowHour ?: 0f) + REMINDER_TAG_HEIGHT * i)) {
                onToggleReminder(tag)
            }
        }

        // PRD §18 Alarms: each ring of each alarm is drawn at its own instant — a fixed-height marker, since
        // an alarm has no duration. Unlike a reminder it is never checked off and never follows the now-line:
        // it is a fixed wall-clock boundary, so a past ring stays where it went off. Alarms falling at (or
        // within a marker's height of) the same time stack downward, exactly like the reminder tags.
        var lastAlarmBottom: Dp? = null
        alarmMarkers.sortedBy { it.startHour }.forEach { marker ->
            val naturalY = hourHeight * marker.startHour
            val y = lastAlarmBottom?.let { maxOf(naturalY, it) } ?: naturalY
            lastAlarmBottom = y + ALARM_MARKER_HEIGHT
            AlarmMarker(marker, Modifier.offset(y = y))
        }

        // PRD §15 Screen breaks: drawn as real time-positioned bands spanning their true duration, so the §9
        // fill leaves an exact gap for each one (no overlap with the surrounding task, no stray white where
        // a multi-minute rest pause sits). A sub-minute look-away therefore renders as a hairline; the 5/15-
        // min rest pauses fill their region. A small minimum height keeps even a hairline visible/hoverable.
        // Coinciding screen breaks (e.g. the hourly and 2-hourly pose both due now) share the column width side
        // by side via [overlapLayout], exactly like overlapping task blocks.
        if (screenBreakMarkers.isNotEmpty()) {
            val sideLayout = overlapLayout(screenBreakMarkers)
            // PRD §8: a screen break is drawn on top of everything else, so whatever sits under it (a
            // sleep/no-screen/inactivity band, or — rarely, since the fill normally carves an exact gap for
            // the break — a real panel) is otherwise hidden. Hover stacks that element's info below the
            // break's own (see [decorativeHoverZones]).
            val sideUnders = sleepBands + inactivityBands + blockRecords
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val colWidth = maxWidth
                screenBreakMarkers.forEach { marker ->
                    val key = calendarBlockKey(marker)
                    val slices = sideLayout[key]
                        ?: listOf(PanelSlice(marker.startHour, marker.endHour, xFraction = 0f, widthFraction = 1f))
                    slices.forEach { slice ->
                        ScreenBreakBand(marker, slice, hourHeight, colWidth, tz, hoverScope, sideUnders)
                    }
                }
            }
        }

        // The "Sleep"/"Inactivity" band label, drawn ON TOP of everything so it stays legible at the start
        // of the band even though the work plan now projects tinted blocks through the window. Non-
        // interactive (a plain Text Box consumes no pointer events), so the blocks beneath stay clickable.
        (sleepBands.map { it to "Sleep" } + inactivityBands.map { it to "Inactivity" }).forEach { (band, label) ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .offset(y = hourHeight * band.startHour)
                    .height(hourHeight * (band.endHour - band.startHour)),
                contentAlignment = Alignment.TopCenter,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = CalColors.muted,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

/** PRD §14: a reminder rendered as a small checkable chip on the calendar (not a draggable block). */
private val REMINDER_TAG_HEIGHT = 18.dp

/** PRD §18: an alarm ring rendered as a small marker on the calendar (zero duration, so a fixed height). */
private val ALARM_MARKER_HEIGHT = 18.dp

/** PRD §15: smallest rendered height for a screen-break band, so a sub-minute look-away stays a visible hairline. */
private val SCREEN_BREAK_MIN_HEIGHT = 3.dp

/** PRD §15: a screen-break band only draws its title (●/name) once it is at least this tall; shorter ones are bare. */
private val SCREEN_BREAK_LABEL_MIN_HEIGHT = 13.dp

/**
 * PRD §15: tint of the HOLLOW half of a screen-break band — the part that accepts the tasks needing no
 * screen. Light enough that a task block scheduled inside the break reads through it, strong enough that the
 * region still reads as part of the break.
 */
private const val SCREEN_BREAK_OPEN_ALPHA = 0.12f

/**
 * PRD §15 Screen break, rendered as a real time-positioned band (one [overlapLayout] slice of it) spanning its
 * true duration so the §9 fill leaves an exact gap for it. Sub-minute screen breaks render at [SCREEN_BREAK_MIN_HEIGHT]
 * (a hairline); the title is drawn only when the band is tall enough ([SCREEN_BREAK_LABEL_MIN_HEIGHT]). The full
 * name always shows on hover (PRD §8), anchored at the cursor so zoom never floats the bubble off-screen, with
 * the screen break's true (un-clipped) start–end times on a second line — the same bubble blocks get.
 */
@Composable
private fun ScreenBreakBand(
    marker: PlacedRecord,
    slice: PanelSlice,
    hourHeight: Dp,
    colWidth: Dp,
    tz: TimeZone,
    hoverScope: CalendarTitleHoverScope,
    /** PRD §8: real panels / derived activity bands that may sit under this (topmost) decorative band. */
    underCandidates: List<PlacedRecord>,
) {
    val height = (hourHeight * (slice.bottomHour - slice.topHour)).coerceAtLeast(SCREEN_BREAK_MIN_HEIGHT)
    val timeRange = "${formatHm(marker.fullStartMillis, tz)} – ${formatHm(marker.fullEndMillis, tz)}"
    // PRD §15: the break is split where its CLOSED head ends and its open period begins — the part that
    // accepts the tasks needing no screen (a 5-min pose's last four minutes; a 15-min pose end to end). The
    // closed part is a solid band, the open one is drawn HOLLOW — a tinted outline the blocks beneath show
    // through — because it is not a stop at all for an off-screen task, it is a period reserved for one.
    val closedHeight =
        height * screenBreakClosedFraction(marker.screenBreakOpenFromHour, slice.topHour, slice.bottomHour)
    val openHeight = height - closedHeight
    // The title is drawn once, in whichever half can hold it — the closed one by preference, since a solid
    // band carries small white text best.
    val labelInClosed = closedHeight >= SCREEN_BREAK_LABEL_MIN_HEIGHT
    val labelInOpen = !labelInClosed && openHeight >= SCREEN_BREAK_LABEL_MIN_HEIGHT
    Box(
        modifier = Modifier
            .offset(x = colWidth * slice.xFraction, y = hourHeight * slice.topHour)
            .width(colWidth * slice.widthFraction)
            .height(height),
    ) {
        if (closedHeight > 0.dp) {
            ScreenBreakSegment(
                title = marker.title,
                showLabel = labelInClosed,
                hollow = false,
                modifier = Modifier.fillMaxWidth().height(closedHeight),
            )
        }
        if (openHeight > 0.dp) {
            ScreenBreakSegment(
                title = marker.title,
                showLabel = labelInOpen,
                hollow = true,
                modifier = Modifier.offset(y = closedHeight).fillMaxWidth().height(openHeight),
            )
        }
        // PRD §8: tiled by whichever underlying panel/band covers each sub-range (see [decorativeHoverZones]),
        // so the hover bubble stacks it below this screen break's own title/time. The zones are mapped onto
        // the RENDERED [height] (not the break's true span): a sub-minute look-away draws at the coerced
        // [SCREEN_BREAK_MIN_HEIGHT] hairline, so sizing its hover zones by the ~0-height true duration would
        // leave the visible band un-hoverable — the pointer would fall through to the sleep band beneath and
        // the bubble would name that instead of the break (which must come first).
        val span = slice.bottomHour - slice.topHour
        decorativeHoverZones(slice.topHour, slice.bottomHour, underCandidates).forEach { zone ->
            val fracTop = if (span > 0f) (zone.top - slice.topHour) / span else 0f
            val fracBottom = if (span > 0f) (zone.bottom - slice.topHour) / span else 1f
            Box(
                Modifier
                    .offset(y = height * fracTop)
                    .fillMaxWidth()
                    .height(height * (fracBottom - fracTop))
                    .calendarTitleHover(
                        marker.title,
                        hoverScope,
                        subtitle = timeRange,
                        underTitle = zone.under?.let(::underHoverTitle),
                        underSubtitle = zone.under?.let {
                            "${formatHm(it.fullStartMillis, tz)} – ${formatHm(it.fullEndMillis, tz)}"
                        },
                    ),
            )
        }
    }
}

/**
 * PRD §15: how much of a screen-break band's height is its CLOSED head — the part that accepts no task and is
 * drawn solid; the rest is the open period, drawn hollow. [openFromHour] is where the band stops accepting
 * nobody (null = never: closed end to end), and the band is the slice `[topHour, bottomHour]` of it.
 *
 * A fraction rather than an hour because a band is drawn at a MINIMUM height (a 20-second look-away is a
 * hairline, far taller than its true span), so the split has to be mapped onto the rendered height. A
 * zero-length slice — a sub-second break, or one clipped to a day boundary — has no room for a head: it draws
 * as whatever it mostly is, which for a band with an open part is the open part.
 */
fun screenBreakClosedFraction(openFromHour: Float?, topHour: Float, bottomHour: Float): Float {
    if (openFromHour == null) return 1f
    val span = bottomHour - topHour
    if (span <= 0f) return 0f
    return ((openFromHour - topHour) / span).coerceIn(0f, 1f)
}

/**
 * PRD §15: one half of a [ScreenBreakBand] — its closed head or its open period.
 *
 * [hollow] is the whole difference, and it is what the break's period MEANS: a closed stretch accepts no task
 * at all and is painted solid, while the open one accepts every task that needs no screen (the 5-minute pose's
 * break-doable tail, the 15-minute pose end to end) and is painted as a tinted outline the work beneath shows
 * through — the same "decorative, not occupied" idiom the no-screen panels use.
 */
@Composable
private fun ScreenBreakSegment(
    title: String,
    showLabel: Boolean,
    hollow: Boolean,
    modifier: Modifier,
) {
    Row(
        modifier = modifier
            .padding(horizontal = 1.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(if (hollow) CalColors.accent.copy(alpha = SCREEN_BREAK_OPEN_ALPHA) else CalColors.accent)
            .then(if (hollow) Modifier.border(1.dp, CalColors.accent, RoundedCornerShape(4.dp)) else Modifier)
            .then(if (showLabel) Modifier.padding(horizontal = 4.dp) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (showLabel) {
            val labelColor = if (hollow) CalColors.accent else Color.White
            Text(text = "●", style = MaterialTheme.typography.labelSmall, color = labelColor)
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = labelColor,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * PRD §8 hover title: which block/screen-break the cursor is currently over and where. [pos] is the cursor
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
    /**
     * PRD §8: hovering a decorative panel (sleep/no-screen/screen-break) also names whatever real panel or
     * derived activity/inactivity period sits underneath it, stacked below the decorative element's own
     * title/subtitle. Null for an element with nothing underneath (or that isn't decorative).
     */
    val underTitle: String? = null,
    val underSubtitle: String? = null,
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
    underTitle: String? = null,
    underSubtitle: String? = null,
): Modifier = composed {
    val ownerId = remember { Any() }
    var coords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val report: (Offset) -> Unit = report@{ local ->
        val viewport = scope.viewportCoords()?.takeIf { it.isAttached } ?: return@report
        val self = coords?.takeIf { it.isAttached } ?: return@report
        scope.onHover(
            CalendarTitleHover(
                ownerId,
                title,
                viewport.localPositionOf(self, local),
                subtitle,
                underTitle,
                underSubtitle,
            ),
        )
    }
    this
        .onGloballyPositioned { coords = it }
        .onPointerEventCompat(PointerEventType.Enter) { report(it.changes.first().position) }
        .onPointerEventCompat(PointerEventType.Move) { report(it.changes.first().position) }
        .onPointerEventCompat(PointerEventType.Exit) { if (scope.currentOwner() === ownerId) scope.onHover(null) }
}

/**
 * Multiplatform replacement for desktop Compose's `Modifier.onPointerEvent`: invoke [onEvent] for each
 * pointer event of type [eventType] seen on [pass]. Built on the common [pointerInput]/[awaitPointerEvent]
 * primitives so it compiles on every target (the desktop extension is JVM/skiko-only). On touch-only
 * Android these hover events (Enter/Move/Exit) simply never fire, which is the correct no-op.
 */
private fun Modifier.onPointerEventCompat(
    eventType: PointerEventType,
    pass: PointerEventPass = PointerEventPass.Main,
    onEvent: (PointerEvent) -> Unit,
): Modifier = pointerInput(eventType, pass) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(pass)
            if (event.type == eventType) onEvent(event)
        }
    }
}

/**
 * The hover title bubble (PRD §8), drawn at [pos] (+16dp below the cursor, mirroring the old cursor-anchored
 * placement). Rendered with plain draw modifiers only — no `Surface`/clickable/pointer-input — so it is
 * invisible to hit-testing and the cursor falls through to the block underneath even when it overtakes the
 * bubble during a fast scroll, keeping the title live.
 */
@Composable
private fun CalendarTitleBubble(
    text: String,
    pos: Offset,
    subtitle: String? = null,
    underTitle: String? = null,
    underSubtitle: String? = null,
) {
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
        // PRD §8: hovering a decorative panel (sleep/no-screen/screen-break) stacks the real panel or
        // derived activity/inactivity period underneath it below a thin divider, so both are visible at once
        // instead of one element's report silently overwriting the other's.
        if (underTitle != null) {
            HorizontalDivider(
                Modifier.padding(vertical = 4.dp),
                color = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.3f),
            )
            Text(
                text = underTitle.ifEmpty { "(untitled)" },
                color = MaterialTheme.colorScheme.inverseOnSurface,
                style = MaterialTheme.typography.labelMedium,
            )
            if (underSubtitle != null) {
                Text(
                    text = underSubtitle,
                    color = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.8f),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
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
 * PRD §18 Alarms: one ring of an alarm on the calendar. Zero duration, so it draws as a fixed-height marker
 * at its instant rather than a height-proportional block, and it is inert — an alarm is not a task, so there
 * is nothing to check off, drag or edit here (the Alarms window owns it). The label falls back to the ring
 * time so a nameless alarm still says what it is.
 */
@Composable
private fun AlarmMarker(marker: PlacedRecord, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(ALARM_MARKER_HEIGHT)
            .padding(horizontal = 2.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(CalColors.alarm)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(text = "⏰", style = MaterialTheme.typography.labelSmall, color = Color.White)
        Text(
            text = marker.title,
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
 *  - Double-click (no drag) → open the edit window via [onEditEntry] (same as the right-click "Edit").
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
    /**
     * PRD §17: this day's sleep windows as hour-of-day ranges. Where a block overlaps one, only that
     * sub-range is greyed (as if under the "Sleep" band) — so a block spanning day + a sleep sliver isn't
     * tinted whole. The block stays a normal, fully interactive block drawn on top of the band.
     */
    sleepHourRanges: List<ClosedFloatingPointRange<Float>> = emptyList(),
    overlapArmed: Boolean,
    /** True while a move/resize preview overlay is showing — hide these (gesture-only) resting slices. */
    previewActive: Boolean,
    /** Reports the in-progress drag bounds (null when not dragging) so the column can draw the live overlay. */
    onPreviewChange: (TaskTimeRange?) -> Unit,
    onCommitBounds: (PlacedRecord, Long, Long, Boolean) -> Unit,
    onLockScroll: (Boolean) -> Unit,
    hoverScope: CalendarTitleHoverScope,
    tz: TimeZone,
    onEditEntry: (PlacedRecord) -> Unit,
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

    // PRD §8: no-screen / inactivity periods are decorative-patterned, muted blocks; every real task
    // period keeps the single uniform event colour.
    val color = if (record.noScreen || record.inactivity) CalColors.muted else CalColors.event

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
                        // PRD §8 double-click → open the edit window. Track the previous no-drag tap's
                        // press time across gestures so a second tap within the platform double-tap
                        // window is recognised here (a tap doesn't move/commit, so this is additive).
                        val doubleTapWindowMs = viewConfiguration.doubleTapTimeoutMillis
                        var lastTapUptime = 0L

                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)

                            // Right-click → leave it unconsumed so the enclosing day column shows the
                            // Edit/Remove contextual menu for this block (PRD §8).
                            if (currentEvent.buttons.isSecondaryPressed) return@awaitEachGesture
                            // PRD §8 (phone): touch never drags/edits a block directly — a single-finger
                            // drag scrolls the grid, a double tap opens the column's contextual menu
                            // (info at the top, Edit/Remove/move inside). Leave the press unconsumed so
                            // the column and the scroll container see it.
                            if (down.type == PointerType.Touch) return@awaitEachGesture
                            val downUptime = down.uptimeMillis

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
                                        } else if (downUptime - lastTapUptime <= doubleTapWindowMs) {
                                            // Second quick tap with no drag → open the edit window (a
                                            // no-screen period gets the times-only editor), then reset so
                                            // a third tap starts a fresh pair. An inactivity period has
                                            // nothing to edit (PRD §8).
                                            if (!record.inactivity) onEditEntry(record)
                                            lastTapUptime = 0L
                                        } else {
                                            lastTapUptime = downUptime
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
                // PRD §8: the hover bubble also shows the panel's true (un-clipped) start–end times, and — for
                // the elapsed part with activity data — which devices were open under the cursor. The slice is
                // tiled into hover zones (one per device-set segment + plain leftovers) instead of nesting
                // hover handlers, because a parent hover Move would overwrite a child's report.
                val timeRange = "${formatHm(record.fullStartMillis, tz)} – ${formatHm(record.fullEndMillis, tz)}"
                val hoverZones = deviceHoverZones(record.deviceSegments, slice.topHour, slice.bottomHour)
                Box(Modifier.fillMaxSize()) {
                    // The title is written only on the topmost slice so a stepped block reads as one.
                    CalendarBlockBody(color, record.title, showTitle = isFirst, hatched = record.noScreen)
                    // PRD §17: grey the sub-range of this slice that falls inside a sleep window (the fill now
                    // projects the plan through the night). A plain overlay Box with no pointer handler, so
                    // the block underneath stays clickable/moveable — the block reads "as if under the band".
                    sleepHourRanges.forEach { sleepRange ->
                        val top = maxOf(slice.topHour, sleepRange.start)
                        val bottom = minOf(slice.bottomHour, sleepRange.endInclusive)
                        if (bottom > top) {
                            Box(
                                Modifier
                                    .offset(y = hourHeight * (top - slice.topHour))
                                    .fillMaxWidth()
                                    .height(hourHeight * (bottom - top))
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(CalColors.muted.copy(alpha = SLEEP_BAND_ALPHA)),
                            )
                        }
                    }
                    hoverZones.forEach { zone ->
                        val line = zone.devices?.let { d ->
                            if (d.isEmpty()) "Open: no device" else "Open: ${d.joinToString(", ")}"
                        }
                        Box(
                            Modifier
                                .offset(y = hourHeight * (zone.top - slice.topHour))
                                .fillMaxWidth()
                                .height(hourHeight * (zone.bottom - zone.top))
                                .calendarTitleHover(
                                    record.title,
                                    hoverScope,
                                    subtitle = if (line == null) timeRange else "$timeRange\n$line",
                                ),
                        )
                    }
                    // A dashed separator at every interior boundary where the device set changed (two
                    // adjacent segments always differ — equal neighbours were merged in the sweep).
                    record.deviceSegments.forEachIndexed { segIndex, seg ->
                        val prev = record.deviceSegments.getOrNull(segIndex - 1) ?: return@forEachIndexed
                        if (!approxEq(prev.endHour, seg.startHour)) return@forEachIndexed
                        if (seg.startHour <= slice.topHour + 1e-4f || seg.startHour >= slice.bottomHour - 1e-4f) {
                            return@forEachIndexed
                        }
                        Box(
                            Modifier
                                .offset(y = hourHeight * (seg.startHour - slice.topHour))
                                .fillMaxWidth()
                                .height(1.dp)
                                .drawBehind {
                                    drawLine(
                                        color = color,
                                        start = Offset(0f, size.height / 2f),
                                        end = Offset(size.width, size.height / 2f),
                                        strokeWidth = size.height,
                                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)),
                                    )
                                },
                        )
                    }
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

/** One hover tile of a block slice: `devices == null` means "no activity data here" (times-only bubble). */
private data class DeviceHoverZone(val top: Float, val bottom: Float, val devices: List<String>?)

/**
 * Tiles a slice's `[top, bottom]` hour range with the device-set segments overlapping it, filling the
 * uncovered leftovers (a future part / a part predating all activity data) with `devices == null` zones,
 * so every point of the block reports a hover bubble. No segments ⇒ one whole-slice null zone.
 */
private fun deviceHoverZones(
    segments: List<PlacedDeviceSegment>,
    top: Float,
    bottom: Float,
): List<DeviceHoverZone> {
    val zones = mutableListOf<DeviceHoverZone>()
    var cursor = top
    for (seg in segments.sortedBy { it.startHour }) {
        val a = maxOf(seg.startHour, top)
        val b = minOf(seg.endHour, bottom)
        if (b <= a) continue
        if (a > cursor) zones.add(DeviceHoverZone(cursor, a, null))
        zones.add(DeviceHoverZone(maxOf(a, cursor), b, seg.devices))
        cursor = b
    }
    if (cursor < bottom) zones.add(DeviceHoverZone(cursor, bottom, null))
    return zones
}

/**
 * One hover tile of a decorative panel's `[top, bottom]` hour range: [under] is the real panel or derived
 * activity/inactivity period whose time range is directly beneath this sub-range, if any — its title/time
 * are stacked below the decorative element's own title/subtitle in the hover bubble (PRD §8). Two
 * independent hover reporters at the same position race unpredictably (see [deviceHoverZones]'s note on
 * why nested/overlapping hover handlers aren't used), so this tiles the region instead, the same way.
 */
private data class DecorativeHoverZone(val top: Float, val bottom: Float, val under: PlacedRecord?)

/** Tiles `[top, bottom]` by whichever of [unders] overlaps each sub-range; uncovered leftovers get `under = null`. */
private fun decorativeHoverZones(top: Float, bottom: Float, unders: List<PlacedRecord>): List<DecorativeHoverZone> {
    val zones = mutableListOf<DecorativeHoverZone>()
    var cursor = top
    for (u in unders.filter { it.startHour < bottom && it.endHour > top }.sortedBy { it.startHour }) {
        val a = maxOf(u.startHour, top)
        val b = minOf(u.endHour, bottom)
        if (b <= cursor) continue
        if (a > cursor) zones.add(DecorativeHoverZone(cursor, a, null))
        zones.add(DecorativeHoverZone(maxOf(a, cursor), b, u))
        cursor = maxOf(cursor, b)
    }
    if (cursor < bottom) zones.add(DecorativeHoverZone(cursor, bottom, null))
    return zones
}

/** The band label for a derived (no-[entryId]) sleep/no-screen/inactivity record — see the drawing loop below. */
private fun decorativeBandLabel(r: PlacedRecord): String = when {
    r.sleep -> "Sleep"
    r.noScreen -> "No screen"
    else -> "Inactivity"
}

/**
 * The hover title for a [DecorativeHoverZone.under] record: a derived band's own label ("Sleep"/"No
 * screen"/"Inactivity") if it is one, otherwise the real panel's title.
 */
private fun underHoverTitle(u: PlacedRecord): String =
    if (u.entryId == null && (u.sleep || u.inactivity || u.noScreen)) decorativeBandLabel(u) else u.title

/**
 * PRD §8 decorative panels: an oblique-line hatch. [reversed] flips the slope — the no-screen pattern
 * draws "/" (bottom-left → top-right); the sleep pattern draws "\" (top-left → bottom-right), so a sleep
 * window (which is also a no-screen period) reads as the two crossed.
 */
private fun Modifier.obliqueHatch(color: Color, reversed: Boolean): Modifier =
    this.drawBehind {
        val step = 10.dp.toPx()
        val stroke = 1.dp.toPx()
        var x = -size.height
        while (x < size.width) {
            val start = if (reversed) Offset(x, 0f) else Offset(x, size.height)
            val end = if (reversed) Offset(x + size.height, size.height) else Offset(x + size.height, 0f)
            drawLine(color = color.copy(alpha = 0.35f), start = start, end = end, strokeWidth = stroke)
            x += step
        }
    }

/** PRD §8: the coloured body + title of a calendar block (or one of its overlap slices). */
@Composable
private fun CalendarBlockBody(color: Color, title: String, showTitle: Boolean, hatched: Boolean = false) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(3.dp))
            .background(color.copy(alpha = if (hatched) 0.10f else 0.30f))
            .border(1.dp, color, RoundedCornerShape(3.dp))
            // PRD §8 decorative panels: a no-screen period reads as an oblique-line pattern laid over the
            // calendar rather than a solid task block.
            .then(if (hatched) Modifier.obliqueHatch(color, reversed = false) else Modifier),
    ) {
        if (showTitle) {
            Text(
                text = title.ifEmpty { "(untitled)" },
                style = MaterialTheme.typography.labelSmall,
                color = if (hatched) CalColors.muted else CalColors.event,
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

/**
 * PRD §12: the start label for a derived band. "∞" when the band is open-ended into the past ([openStart] —
 * the inactivity extends indefinitely back and the drawn start is only the display floor), else the
 * wall-clock `HH:MM`.
 */
private fun hmOrInfinity(millis: Long, openStart: Boolean, tz: TimeZone): String =
    if (openStart) "∞" else formatHm(millis, tz)

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

/** PRD §8 one labeled pin-dimension switch row in the calendar edit window. */
@Composable
private fun PinSwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
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
    onSave: (taskId: TaskId?, title: String, startMillis: Long, endMillis: Long, pins: PanelPins, onScreen: Boolean, doableDuringBreak: Boolean) -> Unit,
    onDismiss: () -> Unit,
    /** PRD §8: the panel's current four pin dimensions, toggled by the switches in this window. */
    initialPins: PanelPins = PanelPins(),
    /**
     * PRD §8 screen switches: the current (onScreen, doableDuringBreak) flags of a task, or null when
     * unknown. Seeds the two switches whenever the effective task changes; a calendar-only "New task"
     * (null taskId) has no task object to carry the flags, so the switches are hidden for it.
     */
    screenFlagsForTaskId: (TaskId) -> Pair<Boolean, Boolean>? = { null },
    /**
     * PRD §8: times-only mode, used to edit a **no-screen period** — it has no task behind it, so the
     * window shows just the begin/end time fields (no task field/menus, no pins, no screen switches).
     */
    timesOnly: Boolean = false,
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
    // PRD §8: the four independent pin dimensions, toggled by the switches below. Only [existence] is
    // enforced today (a pinned panel survives a reschedule); position/spanning/distance are stored and
    // shown so they round-trip across edits until their enforcement lands.
    var pins by remember { mutableStateOf(initialPins) }

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
                Text(
                    if (timesOnly) "Edit no-screen period" else "Edit task",
                    style = MaterialTheme.typography.titleSmall,
                )

                if (!timesOnly) {
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
                }

                // --- Tasks menu: New task + existing leaf matches. Pass no exclusion so the matching
                // task shows (and highlights) even when it was picked from the suggestions below. PRD §8:
                // the first real task is selected by default; "New task" only when explicitly chosen. ---
                val taskEntries = if (timesOnly) emptyList() else taskMenuEntries(title, null)
                // The effective task this window will save: the explicit pick, else (unless the user
                // chose "New task") the first real task of the menu.
                val effectiveTaskId =
                    when {
                        timesOnly -> initialTaskId
                        newTaskChosen -> null
                        selectedTaskId != null && taskEntries.any { it.taskId == selectedTaskId } -> selectedTaskId
                        else -> SchedulerDomain.calendarDefaultMenuTaskId(taskEntries)
                    }
                // No Mode selector: this window is always in "Change Task" mode (PRD §8) — the identity
                // and title menus are the shared block every other naming field uses.
                EditModeMenuBlock(
                    identityLabel = "Tasks",
                    identityRows =
                        if (taskEntries.size > 1) {
                            val selectedIndex =
                                SchedulerDomain.changeTaskMenuSelectedIndex(taskEntries, effectiveTaskId)
                            taskEntries.mapIndexed { index, entry ->
                                EditMenuItem(label = entry.label, selected = index == selectedIndex) {
                                    if (entry.taskId == null) {
                                        selectedTaskId = null // "New task" → calendar-only
                                        newTaskChosen = true
                                    } else {
                                        selectedTaskId = entry.taskId
                                        newTaskChosen = false
                                        titleForTaskId(entry.taskId)?.let { title = it }
                                    }
                                }
                            }
                        } else {
                            emptyList()
                        },
                    suggestions =
                        if (timesOnly) {
                            emptyList()
                        } else {
                            titleSuggestions(title).map { suggestion ->
                                EditMenuItem(suggestion) {
                                    title = suggestion
                                    selectedTaskId = taskIdForTitle(suggestion)
                                    newTaskChosen = false
                                }
                            }
                        },
                )

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

                // PRD §8 pin switches: the four independent pin dimensions. Only "existence" is enforced
                // today (a pinned panel survives a reschedule); the rest are stored and shown. A
                // no-screen period (times-only mode) is not rescheduled, so it has no pins to show.
                if (!timesOnly) {
                    EditMenuSectionLabel("Pins")
                    PinSwitchRow("Existence", pins.existence) { pins = pins.copy(existence = it) }
                    PinSwitchRow("Position", pins.position) { pins = pins.copy(position = it) }
                    PinSwitchRow("Spanning", pins.spanning) { pins = pins.copy(spanning = it) }
                    PinSwitchRow("Distance", pins.distance) { pins = pins.copy(distance = it) }
                }

                // PRD §8 screen switches: task-level flags (not per-panel), re-seeded whenever the
                // effective task changes. Hidden for a calendar-only "New task" (no task object yet).
                var screenFlags by remember(effectiveTaskId) {
                    mutableStateOf(effectiveTaskId?.let(screenFlagsForTaskId) ?: (true to false))
                }
                if (effectiveTaskId != null) {
                    EditMenuSectionLabel("Screen")
                    // PRD §8/§15 invariant: doable-during-a-screen-break implies not-on-screen (a screen
                    // break is time away from the screen), so turning "On screen" on clears the break
                    // switch, and the break switch is offered only while "On screen" is off.
                    PinSwitchRow("On screen", screenFlags.first) { on -> screenFlags = on to (screenFlags.second && !on) }
                    if (!screenFlags.first) {
                        PinSwitchRow("Doable during a screen break", screenFlags.second) { screenFlags = screenFlags.first to it }
                    }
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
                            onSave(effectiveTaskId, title, start, end, pins, screenFlags.first, screenFlags.second)
                        },
                    ) { Text("Save") }
                }
            }
        }
    }
}

/**
 * PRD §14 "add reminder": a floating editor opened from the calendar's right-click menu. Mirrors
 * [ManualEntryEditWindow] but for reminders (which have a title and a stable id): a title field with a
 * **Reminders** id menu — a leading **"New Reminder"** row (record against a brand-new, distinct reminder)
 * followed by existing reminders by id (pick one → fills the title) — and a **Title suggestions** menu, plus
 * a single **Time** field on the right pre-filled with the right-click time, and **checked** / **pinned**
 * switches. Save places a reminder tag at that time for the chosen reminder id with those flags (checked =
 * already done; pinned = stays put across regeneration). Rendered by [org.example.project.App].
 */
@Composable
fun ReminderEditWindow(
    initialMillis: Long,
    tz: TimeZone,
    reminderMenuEntries: (draftText: String) -> List<SchedulerDomain.ReminderMenuEntry>,
    titleSuggestions: (String) -> List<String>,
    reminderIdForTitle: (String) -> String?,
    titleForReminderId: (String) -> String?,
    onSave: (reminderId: String, title: String, atMillis: Long, checked: Boolean, pinned: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var title by remember { mutableStateOf("") }
    // The explicitly-picked reminder id, if any; typing reverts to resolving the id from the title. The
    // empty string is a distinct sentinel meaning the user explicitly picked "New Reminder" (a brand-new,
    // distinct reminder) even when the typed title matches an existing one; null means "no explicit pick".
    var selectedReminderId by remember { mutableStateOf<String?>(null) }
    var timeText by remember { mutableStateOf(formatHm(initialMillis, tz)) }
    // PRD §14: the two switches. "checked" defaults on (the common case — recording something just done);
    // "pinned" off. At least one keeps the tag alive across regeneration.
    var checked by remember { mutableStateOf(true) }
    var pinned by remember { mutableStateOf(false) }

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
                Text("Add reminder", style = MaterialTheme.typography.titleSmall)

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
                    selectedEntryId = effectiveReminderId.takeIf { it.isNotEmpty() },
                    onPickNewReminder = { selectedReminderId = "" },
                    onPickEntry = { entry ->
                        selectedReminderId = entry.id
                        titleForReminderId(entry.id)?.let { title = it }
                    },
                    titleSuggestions = titleSuggestions(title),
                    onPickSuggestion = { suggestion ->
                        title = suggestion
                        selectedReminderId = reminderIdForTitle(suggestion)
                    },
                )

                // PRD §14: the two switches — "checked" (already done) and "pinned" (stays put across
                // reminder regeneration even when not done).
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Checked", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Switch(checked = checked, onCheckedChange = { checked = it })
                }
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Pinned", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Switch(checked = pinned, onCheckedChange = { pinned = it })
                }

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
                            onSave(effectiveReminderId, title, at, checked, pinned)
                        },
                    ) { Text("Save") }
                }
            }
        }
    }
}

/**
 * PRD §14 "constrained in": the floating picker opened from a reminder row's "constrained in" button. It
 * holds a single reminder-name input with the shared **Title suggestions** + **Reminders** id menu (no mode
 * selector — there is nothing to rename), and Cancel / Save on the bottom right. Picking / typing an existing
 * reminder constrains the row to it (its name then shows beside the button); "No constraint" (or an empty
 * field) clears the constraint. The reminder being edited is hidden from the menu so it can't constrain
 * itself. Rendered in a [Popup] so it overlays the manager window regardless of nesting.
 */
@Composable
fun ReminderConstraintEditWindow(
    initialReminderId: String,
    excludeReminderId: String,
    reminderMenuEntries: (draftText: String) -> List<SchedulerDomain.ReminderMenuEntry>,
    titleSuggestions: (String) -> List<String>,
    reminderIdForTitle: (String) -> String?,
    titleForReminderId: (String) -> String?,
    onSave: (reminderId: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var title by remember {
        mutableStateOf(initialReminderId.takeIf { it.isNotBlank() }?.let(titleForReminderId).orEmpty())
    }
    // The explicitly-picked reminder id; typing reverts to resolving the id from the title. "" is a distinct
    // sentinel meaning the user explicitly picked "No constraint"; null means "no explicit pick".
    var selectedReminderId by remember { mutableStateOf<String?>(initialReminderId.takeIf { it.isNotBlank() }) }

    val entries = reminderMenuEntries(title).filter { it.id != excludeReminderId }
    val effectiveReminderId =
        when {
            selectedReminderId == "" -> ""
            selectedReminderId != null && entries.any { it.id == selectedReminderId } -> selectedReminderId!!
            else -> reminderIdForTitle(title)?.takeIf { it != excludeReminderId } ?: ""
        }

    Popup(
        alignment = Alignment.Center,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
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
                    Text("Constrain to reminder", style = MaterialTheme.typography.titleSmall)

                    OutlinedTextField(
                        value = title,
                        onValueChange = {
                            title = it
                            selectedReminderId = null // typing resolves the id from the title again
                        },
                        label = { Text("Reminder") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    ReminderEditModeMenus(
                        mode = ReminderEditMode.Change,
                        onSelectMode = {},
                        showModeSelector = false,
                        newReminderLabel = "No constraint",
                        idMenuEntries = entries,
                        selectedEntryId = effectiveReminderId.takeIf { it.isNotEmpty() },
                        onPickNewReminder = {
                            selectedReminderId = ""
                            title = ""
                        },
                        onPickEntry = { entry ->
                            selectedReminderId = entry.id
                            titleForReminderId(entry.id)?.let { title = it }
                        },
                        titleSuggestions = titleSuggestions(title)
                            .filter { reminderIdForTitle(it) != excludeReminderId },
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
                        Button(onClick = { onSave(effectiveReminderId) }) { Text("Save") }
                    }
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

/** One choice in an [EditModeMenuBlock]'s Mode selector: its [label], whether it is [selected], and the pick handler. */
data class EditModeOption(val label: String, val selected: Boolean, val onSelect: () -> Unit)

/**
 * The **Mode** selector of [EditModeMenuBlock] — the task tree cell and the tree selector (PRD §4), the
 * reminders manager and the "add a checked reminder" window (PRD §14) all render this identical control: a
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
private fun EditModeSelector(options: List<EditModeOption>, focusPreserving: Boolean = false) {
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
private fun EditMenuSectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * The one look for every edit-mode menu row — the task tree cell and selector, the calendar windows and the
 * reminders manager all render through this composable, so the Mode / id / title menus look identical
 * everywhere. Callers differ only in behaviour:
 *  - [focusPreserving] selects via a raw tap gesture instead of `Modifier.clickable`, so a pick does NOT pull
 *    focus off a title field. The reminders manager's editor stays open only while its row has focus, so a
 *    focus change there would collapse it before the pick registered; elsewhere `Modifier.clickable` is used.
 *    [onClick] is read through [rememberUpdatedState] so the once-started tap detector always runs the latest closure.
 */
@Composable
private fun EditMenuRow(
    label: String,
    selected: Boolean = false,
    focusPreserving: Boolean = false,
    onClick: () -> Unit,
) {
    val currentOnClick by rememberUpdatedState(onClick)
    val clickModifier =
        if (focusPreserving) Modifier.pointerInput(Unit) { detectTapGestures { currentOnClick() } }
        else Modifier.clickable(onClick = onClick)
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
        color = MaterialTheme.colorScheme.onSurface,
    )
}

/** One row of an [EditModeMenuBlock] menu — an identity row or a title suggestion. */
data class EditMenuItem(
    val label: String,
    val selected: Boolean = false,
    val onClick: () -> Unit,
)

/** How many title suggestions any edit-mode menu lists (PRD §4); the same cap everywhere. */
private const val EDIT_MENU_SUGGESTION_LIMIT = 8

/**
 * **The** edit-mode menu block, in the one order PRD §4 fixes for every field that names an object:
 * the **Mode** selector, then the **identity** menu (whose rows *act* — they pick/create the object the
 * field refers to), then the **Title suggestions** menu (whose rows only fill the field). Every such field
 * in the app renders through this: a task-tree cell's Edit Mode, the task-tree selector above the tree, the
 * calendar's block editor (PRD §8) and the three reminder editors (PRD §14, via [ReminderEditModeMenus]).
 *
 * Each section is shown exactly when its content is non-empty, so a caller hides one by passing an empty
 * list — that is where the per-site rules live (a cell shows its Tasks menu only beyond the lone "New task"
 * row, a reminder editor only in Change mode, and so on). Suggestions are capped at
 * [EDIT_MENU_SUGGESTION_LIMIT] here, so no caller has to remember to.
 *
 * [focusPreserving] is passed straight through to [EditModeSelector]/[EditMenuRow]: set it in a focus-gated
 * editor (one whose menus live only while its field holds focus), where a pick must not blur that field.
 *
 * Emits into the caller's `Column`, so the surrounding spacing/padding stays the caller's own.
 */
@Composable
fun EditModeMenuBlock(
    modeOptions: List<EditModeOption> = emptyList(),
    identityLabel: String = "",
    identityRows: List<EditMenuItem> = emptyList(),
    suggestions: List<EditMenuItem> = emptyList(),
    focusPreserving: Boolean = false,
) {
    if (modeOptions.isNotEmpty()) {
        EditModeSelector(options = modeOptions, focusPreserving = focusPreserving)
    }
    EditMenuSection(identityLabel, identityRows, focusPreserving)
    EditMenuSection("Title suggestions", suggestions.take(EDIT_MENU_SUGGESTION_LIMIT), focusPreserving)
}

/** One labelled section of an [EditModeMenuBlock]; nothing at all when it has no [rows]. */
@Composable
private fun EditMenuSection(label: String, rows: List<EditMenuItem>, focusPreserving: Boolean) {
    if (rows.isEmpty()) return
    EditMenuSectionLabel(label)
    rows.forEach { row ->
        EditMenuRow(
            label = row.label,
            selected = row.selected,
            focusPreserving = focusPreserving,
            onClick = row.onClick,
        )
    }
}

/**
 * PRD §14: the reminder adapter over [EditModeMenuBlock] — used by the reminders manager
 * ([ChoresManagerWindow]), the "add reminder" window ([ReminderEditWindow]) and the "constrained in" picker
 * ([ReminderConstraintEditWindow]), the reminder counterpart of the task cell's `EditModeMenus`. It renders a
 * **Mode** selector ([ReminderEditMode]), then — in [ReminderEditMode.Change] only, and only when at least one
 * existing reminder matches — a **Reminders** id menu led by a "new" row, and finally a **Title suggestions**
 * menu shown in both modes. Selection/picking semantics are supplied by the caller.
 */
@Composable
private fun ReminderEditModeMenus(
    mode: ReminderEditMode,
    onSelectMode: (ReminderEditMode) -> Unit,
    idMenuEntries: List<SchedulerDomain.ReminderMenuEntry>,
    /**
     * The id-menu row that is currently selected — an entry's id, or **null** for the leading
     * [newReminderLabel] row. One value rather than a flag beside it, so "new" and "an existing reminder"
     * cannot both read as selected.
     */
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
    /**
     * PRD §14: label of the leading id-menu row that means "not an existing reminder". "New Reminder" for
     * the manager / check window; the "constrained in" window overrides it to "No constraint".
     */
    newReminderLabel: String = "New Reminder",
    /**
     * True only in the reminders manager, whose menus live inside a focus-gated row editor: there a pick must
     * not blur the title field. The two floating windows show their menus unconditionally, so they use
     * ordinary clickable rows (which keep the ripple and the click semantics).
     */
    focusPreserving: Boolean = false,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        EditModeMenuBlock(
            modeOptions =
                if (showModeSelector) {
                    listOf(
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
                    )
                } else {
                    emptyList()
                },
            identityLabel = "Reminders",
            // Identity (id) menu — Change mode only, and (mirroring the task cell, which shows its Tasks
            // menu only beyond "New task") only when an existing reminder matches. Leads with a
            // "New Reminder" row.
            identityRows =
                if (mode == ReminderEditMode.Change && idMenuEntries.isNotEmpty()) {
                    buildList {
                        add(
                            EditMenuItem(
                                label = newReminderLabel,
                                selected = selectedEntryId == null,
                                onClick = onPickNewReminder,
                            ),
                        )
                        idMenuEntries.forEach { entry ->
                            add(
                                EditMenuItem(
                                    label = entry.title,
                                    selected = entry.id == selectedEntryId,
                                    onClick = { onPickEntry(entry) },
                                ),
                            )
                        }
                    }
                } else {
                    emptyList()
                },
            // Title suggestions — shown in both modes (in Rename mode, picking one renames to that title).
            suggestions = titleSuggestions.map { EditMenuItem(it) { onPickSuggestion(it) } },
            focusPreserving = focusPreserving,
        )
    }
}

