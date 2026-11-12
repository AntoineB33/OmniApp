package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import org.example.project.ui.RowHeights

/**
 * User rule 2026-11-12: a list's scrollbar reads the content as **the sum of its rows' heights** — it changes when a row
 * expands, collapses or is resized, or the list changes, never because the list scrolled (anomaly: the actions section's
 * thumb grew and shrank as the calendar's row came and went).
 */
class RowHeightsTest {

    @Test
    fun scrolling_changes_nothing_once_the_rows_are_known() {
        val heights = RowHeights()
        // Five rows: a title, the calendar, three titles. The first two on screen.
        heights.learn(5, listOf(Triple(0, "title", 24), Triple(1, "calendar", 500)))
        heights.learn(5, listOf(Triple(2, "a", 24), Triple(3, "b", 24), Triple(4, "c", 24)))
        assertEquals(596f, heights.total(5))
        // Back at the top: the same sum.
        heights.learn(5, listOf(Triple(0, "title", 24), Triple(1, "calendar", 500)))
        assertEquals(596f, heights.total(5))
        assertEquals(24f, heights.total(1), "what is above the calendar")
    }

    @Test
    fun a_row_never_shown_counts_at_the_average_of_the_known_ones() {
        val heights = RowHeights()
        heights.learn(4, listOf(Triple(0, "a", 20), Triple(1, "b", 40)))
        assertEquals(20f + 40f + 30f + 30f, heights.total(4))
    }

    @Test
    fun a_row_collapsed_on_screen_takes_its_content_off_and_the_rows_after_keep_their_heights() {
        val heights = RowHeights()
        heights.learn(4, listOf(Triple(0, "head", 24), Triple(1, "calendar", 500)))
        heights.learn(4, listOf(Triple(2, "x", 30), Triple(3, "y", 50)))
        assertEquals(604f, heights.total(4))
        // On screen again, the calendar's title pressed: the calendar's row goes.
        heights.learn(4, listOf(Triple(0, "head", 24), Triple(1, "calendar", 500)))
        heights.learn(3, listOf(Triple(0, "head", 24), Triple(1, "x", 30)))
        assertEquals(24f + 30f + 50f, heights.total(3))
        // A row resized: its new height.
        heights.learn(3, listOf(Triple(0, "head", 24), Triple(1, "x", 60)))
        assertEquals(24f + 60f + 50f, heights.total(3))
    }
}
