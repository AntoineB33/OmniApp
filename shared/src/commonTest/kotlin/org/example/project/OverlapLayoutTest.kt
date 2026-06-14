package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.model.TaskTimeRange
import org.example.project.ui.CalendarRecord
import org.example.project.ui.PanelSlice
import org.example.project.ui.PlacedRecord
import androidx.compose.ui.unit.dp
import org.example.project.ui.calendarTickMinutes
import org.example.project.ui.overlapLayout
import org.example.project.ui.recordsForDay
import org.example.project.ui.zoomAnchoredScroll

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
    fun overlappingPanelsFromTheDisplayPipelineAreSliced() {
        val tz = TimeZone.UTC
        val day = LocalDate(2024, 1, 1)
        fun ms(hour: Int) = LocalDateTime(2024, 1, 1, hour, 0).toInstant(tz).toEpochMilliseconds()
        // Two DIFFERENT-task panels overlapping in [1h, 2h], as App.mergePanelsForDisplay would emit.
        val recA = CalendarRecord(
            title = "A", range = TaskTimeRange(ms(0), ms(2)), manual = true,
            entryId = "pA", entryIds = listOf("pA"), taskId = TaskId("t/a"), pinned = true,
        )
        val recB = CalendarRecord(
            title = "B", range = TaskTimeRange(ms(1), ms(3)), manual = true,
            entryId = "pB", entryIds = listOf("pB"), taskId = TaskId("t/b"), pinned = true,
        )
        val placed = recordsForDay(listOf(recA, recB), day, tz)
        val layout = overlapLayout(placed)
        assertTrue(layout.containsKey("pA"), "pA missing from layout: ${layout.keys}")
        assertTrue(layout.containsKey("pB"), "pB missing from layout: ${layout.keys}")
        // In the overlap each panel must be ~half width and offset to opposite sides.
        assertTrue(layout.getValue("pA").any { it.widthFraction in 0.4f..0.6f }, "A not narrowed: ${layout["pA"]}")
        assertTrue(layout.getValue("pB").any { it.widthFraction in 0.4f..0.6f }, "B not narrowed: ${layout["pB"]}")
    }

    @Test
    fun threeWayOverlapEachGetsAThird() {
        val layout = overlapLayout(listOf(block("a", 0f, 3f), block("b", 0f, 3f), block("c", 0f, 3f)))
        assertSlice(slices(layout, "a").single(), 0f, 3f, 0f, 1f / 3f)
        assertSlice(slices(layout, "b").single(), 0f, 3f, 1f / 3f, 1f / 3f)
        assertSlice(slices(layout, "c").single(), 0f, 3f, 2f / 3f, 1f / 3f)
    }

    // ----- §8 zoom-to-cursor anchor math ------------------------------------------------------

    @Test
    fun zoom_keeps_the_content_under_the_cursor_fixed() {
        // PRD §8: the new scroll must leave the time under the cursor (focalY px from the viewport top)
        // under that same pixel after the grid scales. Generic check: the content offset under the cursor,
        // (scroll + focal), scales by the factor, and (newScroll + focal) must equal that scaled offset.
        fun assertAnchored(scroll: Int, focal: Float, factor: Float) {
            val newScroll = zoomAnchoredScroll(scroll, focal, factor)
            val expectedContentUnderCursor = (scroll + focal) * factor
            assertEquals(expectedContentUnderCursor, newScroll + focal, 1f, "anchor drifted")
        }
        // Zoom in at the top, in the middle of the day, and zoom out — the cursor's time stays put.
        assertAnchored(scroll = 0, focal = 100f, factor = 2f) // top → newScroll 100
        assertAnchored(scroll = 480, focal = 100f, factor = 2f) // 10:00 region, zoom in → 1060
        assertAnchored(scroll = 1000, focal = 250f, factor = 1.15f) // evening, small zoom-in step
        assertAnchored(scroll = 800, focal = 150f, factor = 0.5f) // zoom out

        // Spot-check exact values for the simplest cases.
        assertEquals(100, zoomAnchoredScroll(0, 100f, 2f))
        assertEquals(1060, zoomAnchoredScroll(480, 100f, 2f))
        // Never scrolls above the top.
        assertEquals(0, zoomAnchoredScroll(0, 100f, 0.5f))
    }

    @Test
    fun graduation_gets_finer_as_the_row_height_grows() {
        // PRD §8: 60 → 30 → 15 → 10 → 5 → 1 minute ticks, the finest whose tick is still tall enough.
        assertEquals(60, calendarTickMinutes(48.dp)) // default zoom → hourly
        assertEquals(60, calendarTickMinutes(24.dp)) // zoomed out / cramped → still hourly (fallback)
        assertEquals(30, calendarTickMinutes(96.dp)) // 2× → half-hours
        assertEquals(15, calendarTickMinutes(144.dp)) // 3× → quarter-hours
        assertEquals(10, calendarTickMinutes(192.dp)) // 4×
        assertEquals(5, calendarTickMinutes(384.dp)) // 8×
        assertEquals(1, calendarTickMinutes(1560.dp)) // very tall rows (60×26dp) → minute ticks
        // Monotonic: a taller row never yields a coarser graduation.
        var prev = 60
        var h = 24
        while (h <= 2000) {
            val t = calendarTickMinutes(h.dp)
            assertTrue(t <= prev, "graduation got coarser at ${h}dp: $t after $prev")
            prev = t
            h += 8
        }
    }
}
