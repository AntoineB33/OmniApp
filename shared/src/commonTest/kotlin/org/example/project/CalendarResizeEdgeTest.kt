package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.example.project.scheduler.state.CalendarEdge
import org.example.project.ui.PanelResizeEdge
import org.example.project.ui.PlacedRecord
import org.example.project.ui.overlapLayout
import org.example.project.ui.panelResizeEdgeOf
import org.example.project.ui.weightHandleEdge
import org.example.project.ui.weightHandles

/**
 * PRD §8: a resize grab strip names the SIDE of the panel it would move, and the column's own borders are
 * not one of those sides.
 *
 * Two rules, both about a promise the user can only otherwise test by pressing:
 *
 * 1. Every strip's shape is the edge the press takes — the time edges through [panelResizeEdgeOf], the
 *    shared-width halves through [weightHandleEdge]. Each is the ONE reading, shared by the hover tile and
 *    the gesture, so they can never point at different edges.
 * 2. **A panel at the far left of its day column cannot be resized from the left** (and the far-right one
 *    not from the right): [weightHandles] emits a boundary only BETWEEN two panels, so nothing is drawn,
 *    shaped or grabbable on the column's own borders.
 */
class CalendarResizeEdgeTest {

    private fun block(
        id: String,
        startHour: Float,
        endHour: Float,
        weight: Double = 1.0,
        entryId: String? = id,
    ) = PlacedRecord(
        title = id,
        startHour = startHour,
        endHour = endHour,
        scheduled = false,
        manual = true,
        entryId = entryId,
        // What the overlap layout re-divides is a panel's weight, so a block with no backing entry (a
        // banked green record) is a block with no width edge to grab.
        entryIds = listOfNotNull(entryId),
        layoutWeight = weight,
    )

    @Test
    fun aTimeEdgeWearsTheShapeOfTheSideItMoves() {
        assertEquals(PanelResizeEdge.Top, panelResizeEdgeOf(CalendarEdge.Start))
        assertEquals(PanelResizeEdge.Bottom, panelResizeEdgeOf(CalendarEdge.End))
    }

    @Test
    fun aHandleHalfWearsTheShapeOfTheNeighbourItLiesOn() {
        // The left half lies over the LEFT panel, so a press there moves that panel's RIGHT edge.
        assertEquals(PanelResizeEdge.Right, weightHandleEdge(onLeftHalf = true))
        assertEquals(PanelResizeEdge.Left, weightHandleEdge(onLeftHalf = false))
    }

    @Test
    fun aLonePanelHasNoWidthEdgeAtAll() {
        // It is both the leftmost and the rightmost panel of its column, and full width: no side to grab.
        assertEquals(emptyList(), weightHandles(listOf(block("a", 1f, 3f))))
    }

    @Test
    fun theLeftmostPanelHasNoBoundaryAtTheColumnsLeftBorder() {
        val blocks = listOf(block("a", 1f, 3f), block("b", 1f, 3f), block("c", 1f, 3f))
        val handles = weightHandles(blocks)
        // Three panels sharing the width give the two INTERIOR boundaries and nothing else.
        assertEquals(listOf(1f / 3f, 2f / 3f), handles.map { it.boundaryFraction }.sorted().map { it })
        val layout = overlapLayout(blocks)
        val leftmost = layout.values.minByOrNull { it.first().xFraction }!!.first()
        assertEquals(0f, leftmost.xFraction, 1e-3f, "one panel does start at the column's left border")
        assertTrue(
            handles.none { it.boundaryFraction <= leftmost.xFraction + 1e-3f },
            "no grab strip on the column's left border",
        )
    }

    @Test
    fun noBoundaryEverLandsOnEitherColumnBorder() {
        // Every shape of overlap, weight and partial cover: a handle is always strictly inside the column,
        // so the outermost panels keep their outer edges un-resizable.
        val cases = listOf(
            listOf(block("a", 0f, 24f), block("b", 2f, 5f)),
            listOf(block("a", 1f, 4f), block("b", 2f, 6f), block("c", 3f, 8f)),
            listOf(block("a", 1f, 4f, weight = 19.0), block("b", 1f, 4f, weight = 1.0)),
            listOf(block("a", 1f, 4f), block("b", 1f, 4f), block("c", 1f, 4f), block("d", 1f, 4f)),
            // A green record block carries no entry id, so it has no weight to re-divide either.
            listOf(block("a", 1f, 4f), block("r", 1f, 4f, entryId = null)),
        )
        var seen = 0
        for ((index, blocks) in cases.withIndex()) {
            for (handle in weightHandles(blocks)) {
                seen++
                assertTrue(
                    handle.boundaryFraction > 1e-4f && handle.boundaryFraction < 1f - 1e-4f,
                    "case $index put a grab strip on a column border at ${handle.boundaryFraction}",
                )
            }
        }
        // Guard against the whole sweep passing because it found nothing to look at.
        assertTrue(seen >= 6, "expected interior boundaries across the cases, saw $seen")
    }
}
