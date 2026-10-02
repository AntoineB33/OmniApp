package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import org.example.project.ui.CalendarDisplayMode
import org.example.project.ui.columnDayShift
import org.example.project.ui.columnOffsetPx
import org.example.project.ui.columnStepPx
import org.example.project.ui.dayModeColumnCount
import org.example.project.ui.zoomAnchoredOffset

/**
 * PRD §8: the calendar's two display modes are one grid — every column is the same endless timeline read
 * further along than its left neighbour — and differ only in how much further: a day (week mode) or the
 * viewport's height (day mode, where the timeline leaving the bottom of a column resumes at the top of the
 * next one).
 */
class CalendarDisplayModeTest {

    private val day = 2400f
    private val viewport = 500f

    /** Where on the timeline (px past the anchor day's midnight) [column] shows at height [y]. */
    private fun timelineAt(mode: CalendarDisplayMode, column: Int, offset: Float, y: Float, dayPx: Float = day): Float {
        val shift = columnDayShift(mode, column, offset, dayPx, viewport)
        return shift * dayPx + columnOffsetPx(mode, column, shift, offset, dayPx, viewport) + y
    }

    @Test
    fun `week mode reads each column one whole day after its neighbour at the same offset`() {
        for (column in 0..6) {
            assertEquals(column, columnDayShift(CalendarDisplayMode.Week, column, 1234.5f, day, viewport))
            assertEquals(1234.5f, columnOffsetPx(CalendarDisplayMode.Week, column, column, 1234.5f, day, viewport))
        }
        assertEquals(day, columnStepPx(CalendarDisplayMode.Week, day, viewport))
    }

    @Test
    fun `day mode resumes at the top of the next column where the previous one ends`() {
        val offset = 700f
        for (column in 0..3) {
            val bottom = timelineAt(CalendarDisplayMode.Day, column, offset, viewport)
            val nextTop = timelineAt(CalendarDisplayMode.Day, column + 1, offset, 0f)
            assertEquals(bottom, nextTop, 0.01f)
        }
    }

    @Test
    fun `a day mode column rolls onto the next day when its top passes midnight`() {
        // Column 4 starts 2000 px past the leftmost one: with 700 px scrolled that is 2700 px, 300 px into the next day.
        assertEquals(0, columnDayShift(CalendarDisplayMode.Day, 3, 700f, day, viewport))
        assertEquals(1, columnDayShift(CalendarDisplayMode.Day, 4, 700f, day, viewport))
        assertEquals(300f, columnOffsetPx(CalendarDisplayMode.Day, 4, 1, 700f, day, viewport), 0.01f)
    }

    @Test
    fun `scrolling down moves every day mode column forward by the same amount`() {
        for (column in 0..3) {
            val before = timelineAt(CalendarDisplayMode.Day, column, 700f, 100f)
            val after = timelineAt(CalendarDisplayMode.Day, column, 760f, 100f)
            assertEquals(60f, after - before, 0.01f)
        }
    }

    @Test
    fun `a day mode zoom keeps the instant under the pointer in the same column at the same height`() {
        // The pointer is 120 px down column 2; the timeline doubles in scale.
        val column = 2
        val y = 120f
        val offset = 700f
        val factor = 2f
        val instantBefore = timelineAt(CalendarDisplayMode.Day, column, offset, y) / day
        val zoomed = zoomAnchoredOffset(offset, y + column * viewport, factor)
        val instantAfter = timelineAt(CalendarDisplayMode.Day, column, zoomed, y, day * factor) / (day * factor)
        assertEquals(instantBefore, instantAfter, 0.0001f)
    }

    @Test
    fun `before the viewport is measured day mode steps by a day`() {
        assertEquals(day, columnStepPx(CalendarDisplayMode.Day, day, 0f))
    }

    @Test
    fun `day mode fits as many columns as the width allows between one and seven`() {
        assertEquals(1, dayModeColumnCount(0f, 200f))
        assertEquals(1, dayModeColumnCount(150f, 200f))
        assertEquals(3, dayModeColumnCount(700f, 200f))
        assertEquals(7, dayModeColumnCount(5000f, 200f))
    }
}
