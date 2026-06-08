package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import org.example.project.ui.PanelSlice
import org.example.project.ui.PlacedRecord
import org.example.project.ui.overlapLayout

/**
 * PRD §8 Overlap Mode: unit tests for the pure [overlapLayout] horizontal-slicing algorithm — no
 * overlap collapses to one full-width slice, simultaneous panels share width (1/n or by weight), and a
 * partial overlap narrows only the overlapping sub-range (a stepped, variable-width shape).
 */
class OverlapLayoutTest {

    private fun block(
        id: String,
        startHour: Float,
        endHour: Float,
        weight: Double = 1.0,
    ) = PlacedRecord(
        title = id,
        startHour = startHour,
        endHour = endHour,
        scheduled = false,
        manual = true,
        entryId = id,
        layoutWeight = weight,
    )

    // entryId is the calendarBlockKey for a manual entry, so slices are keyed by the block id.
    private fun slices(layout: Map<String, List<PanelSlice>>, id: String) = layout.getValue(id)

    private fun assertSlice(s: PanelSlice, top: Float, bottom: Float, x: Float, width: Float) {
        assertEquals(top, s.topHour, 1e-3f, "topHour")
        assertEquals(bottom, s.bottomHour, 1e-3f, "bottomHour")
        assertEquals(x, s.xFraction, 1e-3f, "xFraction")
        assertEquals(width, s.widthFraction, 1e-3f, "widthFraction")
    }

    @Test
    fun nonOverlappingPanelsAreEachOneFullWidthSlice() {
        val layout = overlapLayout(listOf(block("a", 1f, 2f), block("b", 5f, 6f)))
        val a = slices(layout, "a")
        val b = slices(layout, "b")
        assertEquals(1, a.size)
        assertEquals(1, b.size)
        assertSlice(a[0], 1f, 2f, 0f, 1f)
        assertSlice(b[0], 5f, 6f, 0f, 1f)
    }

    @Test
    fun twoEqualSimultaneousPanelsSplitHalfHalf() {
        val layout = overlapLayout(listOf(block("a", 0f, 4f), block("b", 0f, 4f)))
        assertSlice(slices(layout, "a").single(), 0f, 4f, 0f, 0.5f)
        assertSlice(slices(layout, "b").single(), 0f, 4f, 0.5f, 0.5f)
    }

    @Test
    fun partialOverlapNarrowsOnlyTheOverlappingSubRange() {
        // A [0,4], B [2,6]: only [2,4] overlaps. The lone portions stay full width (decision #2).
        val layout = overlapLayout(listOf(block("a", 0f, 4f), block("b", 2f, 6f)))
        val a = slices(layout, "a")
        val b = slices(layout, "b")
        assertEquals(2, a.size)
        assertSlice(a[0], 0f, 2f, 0f, 1f)
        assertSlice(a[1], 2f, 4f, 0f, 0.5f)
        assertEquals(2, b.size)
        assertSlice(b[0], 2f, 4f, 0.5f, 0.5f)
        assertSlice(b[1], 4f, 6f, 0f, 1f)
    }

    @Test
    fun weightSkewsTheSplit() {
        // A weight 3, B weight 1 ⇒ A takes 0.75, B 0.25.
        val layout = overlapLayout(listOf(block("a", 0f, 4f, weight = 3.0), block("b", 0f, 4f, weight = 1.0)))
        assertSlice(slices(layout, "a").single(), 0f, 4f, 0f, 0.75f)
        assertSlice(slices(layout, "b").single(), 0f, 4f, 0.75f, 0.25f)
    }

    @Test
    fun threeWayOverlapEachGetsAThird() {
        val layout = overlapLayout(listOf(block("a", 0f, 3f), block("b", 0f, 3f), block("c", 0f, 3f)))
        assertSlice(slices(layout, "a").single(), 0f, 3f, 0f, 1f / 3f)
        assertSlice(slices(layout, "b").single(), 0f, 3f, 1f / 3f, 1f / 3f)
        assertSlice(slices(layout, "c").single(), 0f, 3f, 2f / 3f, 1f / 3f)
    }
}
