package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import org.example.project.ui.WindowTabGroups
import org.example.project.ui.WindowTabGroups.Underline

/**
 * User rule 2026-10-06: *"In the system tray, I want the tab for the Search configurations window next to the
 * corresponding Search window (if present) at its right side, with a line underlining the two tabs. Same thing for
 * Search windows coming from a right-click in the calendar. If there is the calendar, a Search window from the calendar
 * and a Search configurations window from this Search window, then there is a line under the two first, and a line
 * under the two last put above the first line."*
 */
class WindowTabGroupsTest {
    private fun layout(opened: List<String>, parents: Map<String, String>) = WindowTabGroups.layout(opened) { parents[it] }

    @Test
    fun windows_opened_from_no_other_keep_the_order_they_were_opened_in_and_have_no_line() {
        val bar = layout(listOf("Calendar", "Alarms", "Search"), emptyMap())
        assertEquals(listOf("Calendar", "Alarms", "Search"), bar.order)
        assertEquals(emptyList(), bar.underlines)
        assertEquals(0, bar.levels)
    }

    @Test
    fun a_configurations_window_stands_right_of_its_search_window_whatever_was_opened_between() {
        val bar = layout(listOf("Search", "Alarms", "ConfigSearch"), mapOf("ConfigSearch" to "Search"))
        assertEquals(listOf("Search", "ConfigSearch", "Alarms"), bar.order)
        assertEquals(listOf(Underline("Search", "ConfigSearch", depth = 0)), bar.underlines)
    }

    @Test
    fun each_search_window_keeps_its_own_configurations_window_beside_it() {
        val bar =
            layout(
                listOf("Search", "Search#2", "ConfigSearch", "ConfigSearch#2"),
                mapOf("ConfigSearch" to "Search", "ConfigSearch#2" to "Search#2"),
            )
        assertEquals(listOf("Search", "ConfigSearch", "Search#2", "ConfigSearch#2"), bar.order)
        assertEquals(
            listOf(Underline("Search", "ConfigSearch", 0), Underline("Search#2", "ConfigSearch#2", 0)),
            bar.underlines,
        )
    }

    @Test
    fun the_calendar_its_search_window_and_that_ones_configurations_are_two_lines_the_deeper_one_above() {
        val bar =
            layout(
                listOf("TaskTree", "Calendar", "ConfigSearch", "Search"),
                mapOf("Search" to "Calendar", "ConfigSearch" to "Search"),
            )
        assertEquals(listOf("TaskTree", "Calendar", "Search", "ConfigSearch"), bar.order)
        // Depth 0 is the lower line (under the calendar and the Search window); depth 1 is drawn above it.
        assertEquals(listOf(Underline("Calendar", "Search", 0), Underline("Search", "ConfigSearch", 1)), bar.underlines)
        assertEquals(2, bar.levels)
    }

    @Test
    fun one_line_runs_from_a_window_to_the_last_of_those_opened_from_it() {
        val bar =
            layout(
                listOf("Search", "ConfigSearch", "AddedConfig"),
                mapOf("ConfigSearch" to "Search", "AddedConfig" to "Search"),
            )
        assertEquals(listOf("Search", "ConfigSearch", "AddedConfig"), bar.order)
        assertEquals(listOf(Underline("Search", "AddedConfig", 0)), bar.underlines)
    }

    @Test
    fun a_window_whose_parent_is_closed_stands_alone() {
        val bar = layout(listOf("Alarms", "Search"), mapOf("Search" to "Calendar"))
        assertEquals(listOf("Alarms", "Search"), bar.order)
        assertEquals(emptyList(), bar.underlines)
    }

    @Test
    fun windows_named_as_each_others_parent_are_all_kept_once() {
        val bar = layout(listOf("a", "b", "c"), mapOf("a" to "b", "b" to "a", "c" to "c"))
        assertEquals(setOf("a", "b", "c"), bar.order.toSet())
        assertEquals(3, bar.order.size)
        assertEquals(emptyList(), bar.underlines)
    }
}
