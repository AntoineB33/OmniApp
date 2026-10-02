package org.example.project

import org.example.project.ui.CalendarBubbleSection
import org.example.project.ui.CalendarLabelBox
import org.example.project.ui.CalendarLabelObstacle
import org.example.project.ui.calendarLabelSlots
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * PRD §8: no two texts of a day column share a point, and no marker is drawn over one
 * ([calendarLabelSlots]). Where a task panel's title and a period's label would overlap, one is written below
 * the other — the lower one, or at the very same point the one the hover bubble ranks lower.
 */
class CalendarLabelSlotsTest {
    private val line = 18f
    private val task = CalendarBubbleSection.Kind.Task.rank
    private val period = CalendarBubbleSection.Kind.Sleep.rank

    private fun panel(key: String, top: Float, bottom: Float, xStart: Float = 0f, xEnd: Float = 1f) =
        CalendarLabelBox(key, top, bottom, xStart, xEnd, task, line)

    private fun band(key: String, top: Float, bottom: Float) =
        CalendarLabelBox(key, top, bottom, 0f, 1f, period, line)

    @Test
    fun an_element_too_short_for_a_whole_line_writes_no_text_even_unpushed() {
        // A one-minute "Inactivity" a few dp tall: nothing is in its way, and it still has no room.
        val slots = calendarLabelSlots(listOf(band("p", 100f, 106f), panel("t", 300f, 310f)), emptyList())
        assertNull(slots["p"])
        assertNull(slots["t"])
    }

    @Test
    fun a_text_goes_below_the_bottom_line_of_a_period_in_its_way() {
        // A period ending 6 dp into the panel: its bottom line (2 dp) would cross the panel's title.
        val bottomLine = CalendarLabelObstacle(104f, 106f)
        val slots = calendarLabelSlots(listOf(panel("t", 100f, 300f)), listOf(bottomLine))
        assertEquals(6f, slots.getValue("t").inset)
        // …and a period opening exactly where another one ends is not pushed by that line.
        assertEquals(0f, calendarLabelSlots(listOf(band("p", 106f, 900f)), listOf(bottomLine)).getValue("p").inset)
    }

    @Test
    fun texts_that_do_not_meet_stay_at_the_top_of_their_element() {
        val slots = calendarLabelSlots(listOf(panel("t", 100f, 300f), band("p", 400f, 900f)), emptyList())
        assertEquals(0f, slots.getValue("t").inset)
        assertEquals(0f, slots.getValue("p").inset)
    }

    @Test
    fun at_the_very_same_point_the_lower_ranked_text_goes_below() {
        // Whatever order they are handed in: the task outranks the period in the hover bubble.
        listOf(
            listOf(band("p", 100f, 900f), panel("t", 100f, 300f)),
            listOf(panel("t", 100f, 300f), band("p", 100f, 900f)),
        ).forEach { labels ->
            val slots = calendarLabelSlots(labels, emptyList())
            assertEquals(0f, slots.getValue("t").inset)
            assertEquals(line, slots.getValue("p").inset)
        }
    }

    @Test
    fun overlapping_texts_at_different_points_push_the_lower_one_down() {
        // The period opens 5 dp above the panel: it keeps its place and the panel's title goes below it.
        val slots = calendarLabelSlots(listOf(panel("t", 105f, 300f), band("p", 100f, 900f)), emptyList())
        assertEquals(0f, slots.getValue("p").inset)
        assertEquals(13f, slots.getValue("t").inset)
    }

    @Test
    fun the_text_follows_its_element_as_it_moves() {
        fun insets(panelTop: Float) =
            calendarLabelSlots(listOf(panel("t", panelTop, panelTop + 200f), band("p", 100f, 900f)), emptyList())
                .let { it.getValue("t").inset to it.getValue("p").inset }
        assertEquals(0f to 0f, insets(60f))
        assertEquals(0f to 8f, insets(90f)) // the panel is above now: the period's label gives way
        assertEquals(0f to line, insets(100f))
        assertEquals(8f to 0f, insets(110f)) // the panel is below now: its title gives way
        assertEquals(0f to 0f, insets(130f))
    }

    @Test
    fun panels_side_by_side_do_not_push_each_other() {
        val slots = calendarLabelSlots(
            listOf(panel("a", 100f, 300f, 0f, 0.5f), panel("b", 100f, 300f, 0.5f, 1f)),
            emptyList(),
        )
        assertEquals(0f, slots.getValue("a").inset)
        assertEquals(0f, slots.getValue("b").inset)
    }

    @Test
    fun a_text_with_no_room_below_is_not_written() {
        // The panel is too short to hold its title under the period's label.
        val slots = calendarLabelSlots(listOf(panel("t", 105f, 125f), band("p", 100f, 900f)), emptyList())
        assertNull(slots["t"])
        assertEquals(0f, slots.getValue("p").inset)
    }

    @Test
    fun a_marker_is_never_drawn_over_a_text() {
        // The day's date badge at the top, a reminder tag right under it.
        val obstacles = listOf(CalendarLabelObstacle(0f, 18f), CalendarLabelObstacle(18f, 36f))
        val slots = calendarLabelSlots(listOf(panel("t", 0f, 300f), band("p", 0f, 900f)), obstacles)
        assertEquals(36f, slots.getValue("t").inset)
        assertEquals(54f, slots.getValue("p").inset)
    }

    @Test
    fun a_wrapping_title_stops_before_the_next_text_below_it() {
        val slots = calendarLabelSlots(listOf(panel("t", 100f, 600f), band("p", 140f, 900f)), emptyList())
        // 40 dp down to the period's label: the 2 dp of padding, then two 16 dp lines.
        assertEquals(2, slots.getValue("t").maxLines)
        assertTrue(slots.getValue("p").maxLines > 100, "nothing is written below the period's label")
    }
}
