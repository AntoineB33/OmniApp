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
): List<CalendarRecord> {
    if (at.size != ahead.size || probeMillis <= 0L) return at
    return at.mapIndexed { index, record ->
        val next = ahead[index]
        if (motionNeutral(record) != motionNeutral(next)) return@mapIndexed record
        val startMoved = next.range.startEpochMillis - record.range.startEpochMillis
        val endMoved = next.range.endEpochMillis - record.range.endEpochMillis
        if ((startMoved != 0L && startMoved != probeMillis) || (endMoved != 0L && endMoved != probeMillis)) {
            return@mapIndexed record
        }
        if (startMoved == 0L && endMoved == 0L) return@mapIndexed record
        record.copy(startFollowsLine = startMoved == probeMillis, endFollowsLine = endMoved == probeMillis)
    }
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
