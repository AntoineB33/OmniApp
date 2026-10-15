package org.example.project

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import org.example.project.ui.HourWindow
import org.example.project.ui.exactVisibleHourWindow
import org.example.project.ui.keptInView

/**
 * User rule 2026-10-15: *"In the calendar, if a chip is attached to a time that is in the window of the calendar view,
 * then it must be fully visible in the window. For example, if 10 reminders are attached to a time very close to the
 * bottom edge of the calendar but in the window of the timeline, then they must all vertically stack."*
 */
class ChipsKeptInViewTest {
    private val h = 20.dp

    /** The downward sweep the calendar makes: each chip at its time, pushed below the one before it. */
    private fun swept(times: List<Dp>): List<Pair<Dp, Dp>> {
        var last: Dp? = null
        return times.map { t ->
            val y = last?.let { maxOf(t, it) } ?: t
            last = y + h
            t to y
        }
    }

    @Test
    fun ten_reminders_at_a_time_just_above_the_bottom_edge_all_stack_inside_the_view() {
        val view = 100.dp to 500.dp
        val placed = swept(List(10) { 490.dp })
        val kept = keptInView(placed, h, view.first, view.second) { it }
        // The last ends on the edge, each one above the next, none overlapping.
        assertEquals(List(10) { i -> 300.dp + h * i }, kept.map { it.second })
        kept.forEach { (_, y) -> assert(y >= view.first && y + h <= view.second) { "$y is out of the view" } }
    }

    @Test
    fun a_pile_that_fits_is_left_exactly_as_it_was_and_one_below_the_view_stays_out_of_it() {
        val placed = swept(listOf(150.dp, 150.dp, 200.dp))
        assertSame(placed, keptInView(placed, h, 100.dp, 500.dp) { it })
        // A chip whose time is below the view is not pulled into it; the one in view above it is still kept inside.
        val mixed = swept(listOf(495.dp, 600.dp))
        assertEquals(listOf(480.dp, 600.dp), keptInView(mixed, h, 100.dp, 500.dp) { it }.map { it.second })
        // No view (a row scrolled away): nothing moves.
        assertSame(mixed, keptInView(mixed, h, 0.dp, 0.dp) { it })
    }

    @Test
    fun a_pile_taller_than_the_view_shows_its_last_chips_and_sends_the_first_above() {
        val kept = keptInView(swept(List(5) { 90.dp }), h, 50.dp, 100.dp) { it }
        assertEquals(listOf(0.dp, 20.dp, 40.dp, 60.dp, 80.dp), kept.map { it.second })
    }

    @Test
    fun the_exact_window_is_the_culling_one_without_its_quantum() {
        // A 2400 px day (100 px an hour), scrolled 950 px, a 300 px viewport: 9.5 h to 12.5 h.
        assertEquals(HourWindow(9.5f, 12.5f), exactVisibleHourWindow(0, 950f, 2400f, 300f))
        assertEquals(HourWindow.Empty, exactVisibleHourWindow(1, 0f, 2400f, 300f), "the next row is not on screen")
        assertEquals(HourWindow.WholeDay, exactVisibleHourWindow(0, 0f, 2400f, 0f), "not measured yet")
    }
}
