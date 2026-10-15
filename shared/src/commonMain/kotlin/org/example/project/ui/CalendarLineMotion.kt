package org.example.project.ui

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.TaskTimeRange

// ---------------------------------------------------------------------------------------------------------
// ADR 0009 § *Everything that follows the line moves continuously*: the calendar's picture is a PIECEWISE
// AFFINE function of the now-line. Between two instants the rules name, every bound the calendar draws is
// either FIXED (`t = a`) or FOLLOWS THE LINE (`t = now + a`) — a pose the line drags, the panel growing behind
// it, the panel resuming after it, a live band ending at it. So the set of rules is read ONCE, the motion of
// every edge is known from that reading until the next instant it stops being true, and the screen's pixels
// are the only rounding left.
// ---------------------------------------------------------------------------------------------------------

/**
 * How far apart the two readings of the display derivation are that [withLineMotion] compares — one
 * millisecond, the app's own unit of time.
 *
 * A bound that follows the line moves by exactly this much between them and a fixed one by nothing, so the
 * slope is read exactly rather than estimated. The only way to misread one is a boundary falling inside
 * this one millisecond, and that is answered by leaving the record still (see [withLineMotion]).
 */
internal const val LINE_MOTION_PROBE_MILLIS: Long = 1

/**
 * [at] (the derivation read at the line) with each record's two edges marked as FOLLOWING THE LINE or not, by
 * comparing it with [ahead] — the same derivation read [probeMillis] later.
 *
 * Records are matched by position, because the derivation is deterministic and a boundary is the only thing
 * that can reorder or add one. A pair is compared on everything BUT what may legitimately move with the line
 * — the range, the "no screen" hover range, and the ids (a dragged pose is named after its own start, so its
 * id moves with it). An edge follows the line when it moved by exactly [probeMillis]; it is fixed when it did
 * not move. Anything else — a different record at that position, a different count, an edge that moved by
 * some other amount — means a boundary fell between the two readings, and that record is left STILL: it is
 * drawn exactly as the derivation read it, which is how the calendar drew everything before this, and the
 * resample the boundary triggers reads it again.
 */
fun withLineMotion(
    at: List<CalendarRecord>,
    ahead: List<CalendarRecord>,
    probeMillis: Long = LINE_MOTION_PROBE_MILLIS,
): List<CalendarRecord> = lineMotionOf(at, ahead, probeMillis).records

/** [withLineMotion]'s answer, and whether a boundary fell between the two readings (a record was left still for it). */
class LineMotion(val records: List<CalendarRecord>, val straddled: Boolean)

/** [withLineMotion], telling a record that is still apart from one left still because a boundary fell in the probe. */
fun lineMotionOf(
    at: List<CalendarRecord>,
    ahead: List<CalendarRecord>,
    probeMillis: Long = LINE_MOTION_PROBE_MILLIS,
): LineMotion {
    if (at.size != ahead.size || probeMillis <= 0L) return LineMotion(at, straddled = true)
    var straddled = false
    val records = at.mapIndexed { index, record ->
        val next = ahead[index]
        if (motionNeutral(record) != motionNeutral(next)) {
            straddled = true
            return@mapIndexed record
        }
        val startMoved = next.range.startEpochMillis - record.range.startEpochMillis
        val endMoved = next.range.endEpochMillis - record.range.endEpochMillis
        if ((startMoved != 0L && startMoved != probeMillis) || (endMoved != 0L && endMoved != probeMillis)) {
            straddled = true
            return@mapIndexed record
        }
        if (startMoved == 0L && endMoved == 0L) return@mapIndexed record
        record.copy(startFollowsLine = startMoved == probeMillis, endFollowsLine = endMoved == probeMillis)
    }
    return LineMotion(records, straddled)
}

// ---------------------------------------------------------------------------------------------------------
// THE RULES OVER A LONGER INTERVAL (user rule 2026-10-15: *"the dynamic change of the calendar over time must be as
// performant as the graph by the Python program, with the scheduler engine returning a set of rules that covers all
// the situations over a bigger time interval"*). One reading of the rules says what the calendar is until the next
// boundary it names; the reading AT that boundary says what it is after it, and so on. A chain of those readings —
// PIECES — is the rules over the whole interval it spans: `if line in [B_k, B_k+1) then piece k, moved along the line
// by line − B_k`, the very branches `C:\code\scheduler\test.py` evaluates. The calendar switches from one piece to
// the next ITSELF, at the boundary's own instant, and nothing is derived as time passes: the readings are taken
// AHEAD of the line ([calendarPieceAt]), while nothing else is happening.
// ---------------------------------------------------------------------------------------------------------

/**
 * One branch of the rules: the calendar from [startMillis] — the instant [records] were read at, their motion
 * included ([withLineMotion]) — until [endMillis], the next boundary they name. [today] is the date at [startMillis].
 */
data class CalendarPiece(
    val startMillis: Long,
    val endMillis: Long,
    val today: LocalDate,
    val records: List<CalendarRecord>,
) {
    /** The records as the rules draw them at [lineMillis] inside this piece: every edge that follows the line moved. */
    fun recordsAt(lineMillis: Long): List<CalendarRecord> {
        val elapsed = lineMillis - startMillis
        if (elapsed == 0L) return records
        return records.map { r ->
            if (!r.followsLine) {
                r
            } else {
                r.copy(
                    range = TaskTimeRange(
                        r.range.startEpochMillis + if (r.startFollowsLine) elapsed else 0L,
                        r.range.endEpochMillis + if (r.endFollowsLine) elapsed else 0L,
                    ),
                )
            }
        }
    }
}

/** The piece of [pieces] (sorted, contiguous) the line at [lineMillis] is in: the last one starting at or before it. */
fun pieceIndexAt(pieces: List<CalendarPiece>, lineMillis: Long): Int {
    var index = 0
    for (i in pieces.indices) {
        if (pieces[i].startMillis <= lineMillis) index = i else break
    }
    return index
}

/** How many milliseconds past a boundary a piece's reading may move when the one AT it straddles another boundary. */
internal const val PIECE_READ_ATTEMPTS: Int = 4

/**
 * The longest a piece is trusted for, whatever its boundaries say — a safety net for a change the records would not
 * show (none is known). It costs two derivations per this long, taken ahead of the line.
 */
const val CALENDAR_PIECE_MAX_MILLIS: Long = 10 * 60_000L

/** The longest a piece whose motion could not be read in full is trusted for. */
internal const val STRADDLED_PIECE_MAX_MILLIS: Long = 250L

/**
 * The piece starting at [startMillis]: the rules read there and one millisecond later ([derive], [lineMotionOf]).
 * When another boundary falls inside that millisecond the reading moves on by one, so a piece never starts with a
 * record whose motion was not read. It ends at the first boundary its records name ahead of its start
 * ([SchedulerDomain.displayResampleDelayMillis] — a fixed bound, a moving bound meeting one, a midnight), at most
 * [maxMillis] on.
 */
fun calendarPieceAt(
    startMillis: Long,
    tz: TimeZone,
    derive: (Long) -> List<CalendarRecord>,
    maxMillis: Long = CALENDAR_PIECE_MAX_MILLIS,
): CalendarPiece {
    var at = startMillis
    var motion = lineMotionOf(derive(at), derive(at + LINE_MOTION_PROBE_MILLIS))
    var attempt = 1
    while (motion.straddled && attempt < PIECE_READ_ATTEMPTS) {
        at = startMillis + attempt
        motion = lineMotionOf(derive(at), derive(at + LINE_MOTION_PROBE_MILLIS))
        attempt++
    }
    val (fixed, offsets) = displayBoundsOf(motion.records, at)
    // A bound ON the line at the start is the piece's own, not a boundary ahead of it.
    val ahead = fixed.filter { it > at + SchedulerDomain.NOW_LINE_ANCHOR_SLACK }
    val delay = SchedulerDomain.displayResampleDelayMillis(ahead, at, tz, millisPerPixel = 1L, lineOffsets = offsets)
    // Still straddling after every attempt: some record's motion is unknown, so the piece is short-lived — read
    // again as soon as the old resample floor would have, never trusted still for minutes.
    val longest = if (motion.straddled) minOf(maxMillis, STRADDLED_PIECE_MAX_MILLIS) else maxMillis
    return CalendarPiece(
        startMillis = at,
        endMillis = at + delay.coerceAtLeast(1L).coerceAtMost(longest.coerceAtLeast(1L)),
        today = Instant.fromEpochMilliseconds(at).toLocalDateTime(tz).date,
        records = motion.records,
    )
}

/** How far ahead of the line the chain is kept read — app time. */
const val CALENDAR_CHAIN_LOOKAHEAD_MILLIS: Long = 2 * 60_000L

/** The most pieces a chain holds; the oldest, already behind the line, go first. */
internal const val CALENDAR_CHAIN_MAX_PIECES: Int = 64

/** Does the chain read so far end within [lookaheadMillis] of the line at [lineMillis]? */
fun calendarChainWantsPiece(
    pieces: List<CalendarPiece>,
    lineMillis: Long,
    lookaheadMillis: Long = CALENDAR_CHAIN_LOOKAHEAD_MILLIS,
): Boolean = pieces.isEmpty() || pieces.last().endMillis <= lineMillis + lookaheadMillis

/**
 * [pieces] with ONE more piece read: the one starting where the chain ends, and the pieces wholly behind the line
 * dropped. A line already past the chain's end (a time simulation leaping, a slow device) starts it again where it
 * is: a piece is never read for an interval the line has left.
 */
fun extendCalendarChain(
    pieces: List<CalendarPiece>,
    lineMillis: Long,
    tz: TimeZone,
    derive: (Long) -> List<CalendarRecord>,
): List<CalendarPiece> {
    val last = pieces.lastOrNull()
    if (last == null || last.endMillis <= lineMillis) return listOf(calendarPieceAt(lineMillis, tz, derive))
    val kept = pieces.drop(pieceIndexAt(pieces, lineMillis))
    return (kept + calendarPieceAt(last.endMillis, tz, derive)).takeLast(CALENDAR_CHAIN_MAX_PIECES)
}

private val NEUTRAL_RANGE = TaskTimeRange(0L, 0L)

private fun motionNeutral(record: CalendarRecord): CalendarRecord =
    record.copy(
        range = NEUTRAL_RANGE,
        entryId = null,
        entryIds = emptyList(),
        startFollowsLine = false,
        endFollowsLine = false,
    )

/** Does either edge of this record follow the line? */
val CalendarRecord.followsLine: Boolean get() = startFollowsLine || endFollowsLine

/** Does either edge of this placed record follow the line? */
val PlacedRecord.followsLine: Boolean get() = startFollowsLine || endFollowsLine

/**
 * The bounds [records] name that do NOT follow the line — the boundaries of the display's piecewise model —
 * and, for each bound that does, its offset from the line ([nowMillis]). The two lists
 * [org.example.project.scheduler.domain.SchedulerDomain.displayResampleDelayMillis] takes.
 */
fun displayBoundsOf(records: List<CalendarRecord>, nowMillis: Long): Pair<List<Long>, List<Long>> {
    val fixed = ArrayList<Long>(records.size * 2)
    val offsets = ArrayList<Long>()
    for (record in records) {
        if (record.startFollowsLine) offsets += record.range.startEpochMillis - nowMillis
        else fixed += record.range.startEpochMillis
        if (record.endFollowsLine) offsets += record.range.endEpochMillis - nowMillis
        else fixed += record.range.endEpochMillis
    }
    return fixed to offsets
}

/**
 * This placed record as the rules draw it [elapsedMillis] after the instant it was derived at: every edge that
 * follows the line moved by exactly that much, every other edge where it was.
 *
 * Only valid until the next boundary the rules name (that is what re-derives it), so the one clamp here is the
 * day's own `[0, 24]` and a start that may not pass its own end — both of which are themselves boundaries the
 * resample is already waiting for.
 */
fun PlacedRecord.advancedAlongLine(elapsedMillis: Long): PlacedRecord {
    if (elapsedMillis == 0L || !followsLine) return this
    val hours = elapsedMillis / 3_600_000f
    val end = if (endFollowsLine) (endHour + hours).coerceIn(0f, 24f) else endHour
    val start = if (startFollowsLine) (startHour + hours).coerceIn(0f, 24f).coerceAtMost(end) else startHour
    return copy(
        startHour = start,
        endHour = end,
        fullStartMillis = if (startFollowsLine) fullStartMillis + elapsedMillis else fullStartMillis,
        fullEndMillis = if (endFollowsLine) fullEndMillis + elapsedMillis else fullEndMillis,
    )
}

/**
 * The instant the COMPOSITION reads the line at: [frameMillis] (the exact clock) floored to a whole number of
 * [millisPerPixel] after [sampleMillis] (the instant the records were derived at).
 *
 * Everything a pointer, a hover tile or a label's fit reads is a composition-time answer, and none of them can
 * tell apart two positions less than a pixel apart — so they are re-derived once per pixel of travel and not
 * once per frame. What is DRAWN is not: [timelineSpan] adds the remaining fraction of a pixel in the layout
 * phase, so the picture moves continuously while the composition steps.
 */
fun lineCompositionMillis(sampleMillis: Long, frameMillis: Long, millisPerPixel: Long): Long {
    val elapsed = frameMillis - sampleMillis
    if (elapsed <= 0L) return sampleMillis
    val step = millisPerPixel.coerceAtLeast(1L)
    return sampleMillis + (elapsed / step) * step
}

/**
 * PRD §8: **where a calendar element spanning `[topHour, bottomHour]` of its day column is placed** — the one
 * placement every block, band, period box and label goes through.
 *
 * An element with no edge following the line is placed exactly as it always was: whole-pixel `Dp` geometry
 * fixed in composition, which is what keeps its text crisp.
 *
 * An element with an edge that follows the line ([topFollowsLine] / [bottomFollowsLine]) has that edge moved
 * by [lineDriftHours] — the part of the line's travel the composition has not caught up with yet, under a
 * pixel — and is placed BETWEEN pixels, in the LAYOUT phase, so a frame re-places this one node and recomposes
 * nothing:
 *  • the node is laid out on whole pixels covering the exact span (`floor(top)`, `ceil(height)`),
 *  • its layer is translated by the fraction `top − floor(top)`, which carries everything inside it — the
 *    title, the hover tiles, the outline — rigidly with the edge, and
 *  • scaled vertically by `height / ceil(height)` about its top, which puts the bottom edge exactly where the
 *    rules say it is. The scale differs from one by less than one pixel over the whole element, so nothing
 *    inside it visibly changes shape — and an element whose two edges both follow the line keeps one constant
 *    scale and simply glides.
 *
 * A FIXED edge of a moving element is rounded to the pixel exactly as the fixed-element path rounds it, so a
 * moving element and the still one it touches share their pixel row.
 */
internal fun Modifier.timelineSpan(
    hourHeight: Dp,
    topHour: Float,
    bottomHour: Float,
    topFollowsLine: Boolean = false,
    bottomFollowsLine: Boolean = false,
    lineDriftHours: () -> Double = { 0.0 },
    x: Dp = 0.dp,
    minHeight: Dp = 0.dp,
): Modifier =
    if (!topFollowsLine && !bottomFollowsLine) {
        offset(x = x, y = hourHeight * topHour)
            .height((hourHeight * (bottomHour - topHour)).coerceAtLeast(minHeight))
    } else {
        layout { measurable, constraints ->
            val hourPx = hourHeight.toPx().toDouble()
            val drift = lineDriftHours()
            val top =
                if (topFollowsLine) (topHour + drift) * hourPx
                else (hourHeight * topHour).roundToPx().toDouble()
            val bottom =
                if (bottomFollowsLine) (bottomHour + drift) * hourPx
                else (hourHeight * bottomHour).roundToPx().toDouble()
            val height = maxOf(bottom - top, minHeight.toPx().toDouble(), 0.0)
            val slotTop = floor(top)
            val slotHeight = ceil(height).toInt().coerceAtLeast(1)
            val placeable =
                measurable.measure(constraints.copy(minHeight = slotHeight, maxHeight = slotHeight))
            layout(placeable.width, constraints.constrainHeight(slotHeight)) {
                placeable.placeWithLayer(x.roundToPx(), slotTop.toInt()) {
                    translationY = (top - slotTop).toFloat()
                    scaleY = (height / slotHeight).toFloat()
                    transformOrigin = TransformOrigin(0.5f, 0f)
                }
            }
        }
    }
