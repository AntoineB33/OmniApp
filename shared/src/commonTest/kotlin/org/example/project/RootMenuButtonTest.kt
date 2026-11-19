package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.SearchDomain
import org.example.project.scheduler.model.WellKnownIds
import org.example.project.scheduler.state.SchedulerState
import org.example.project.ui.ButtonExpandedRows
import org.example.project.ui.CustomMenuButtons
import org.example.project.ui.SearchSplits

/**
 * User rule 2026-11-19: *"By default, there is a button in the left-side menu of the 'task scheduler' feature page to show the
 * search window with only the global root task cell. Add a search configuration element that when on updates the button in
 * the left-side menu automatically only for which rows are expanded."*
 */
class RootMenuButtonTest {
    @Test
    fun the_root_button_lists_the_global_root_alone() {
        val config = SearchDomain.rootSearchConfig()
        val state = SchedulerState.empty()
        val rows = SearchDomain.results(state, config.kinds, "", filters = config.filters)
        assertEquals(listOf(SearchDomain.taskKey(WellKnownIds.ROOT_TASK)), rows.map(SearchDomain::keyOf))
        assertTrue(config.buttonFollowsExpansion)
        assertTrue(SearchDomain.Setting.ButtonFollowsExpansion in SearchDomain.activeSettings(config))
        assertEquals(config, SearchDomain.Config.decode(config.encode()), "the setting is kept with the configuration")
    }

    @Test
    fun the_scheduler_menu_holds_it_under_the_search_button_and_an_older_menu_is_given_it_once() {
        val defaults = CustomMenuButtons.DEFAULTS.map { it.id }
        assertEquals(defaults.indexOf("d-calendar") - 1, defaults.indexOf(CustomMenuButtons.ROOT_SEARCH.id), "with the windows' buttons, above the calendar's")
        assertTrue(CustomMenuButtons.CLAUDE_LOGS_DEFAULTS.none { it.id == CustomMenuButtons.ROOT_SEARCH.id }, "the scheduler's page alone")
        // A menu written before (no rootSeeded): given it once, above the calendar's button.
        val older = CustomMenuButtons.encode(CustomMenuButtons.DEFAULTS - CustomMenuButtons.ROOT_SEARCH).replace(",\"rootSeeded\":true", "")
        val given = CustomMenuButtons.decode(older)
        assertEquals(CustomMenuButtons.DEFAULTS.map { it.id }, given.map { it.id })
        // Removed by the user since: it stays removed.
        val removed = CustomMenuButtons.removed(given, CustomMenuButtons.ROOT_SEARCH.id)
        assertEquals(removed, CustomMenuButtons.decode(CustomMenuButtons.encode(removed)))
    }

    @Test
    fun the_button_keeps_the_open_rows_and_nothing_else() {
        val splits = SearchSplits(left = 0.3f, expandedResults = setOf("task/root", "task/a"), expandedElements = setOf("r:Chrono/c"))
        val rows = ButtonExpandedRows.of(splits)
        val lists = mapOf("" to CustomMenuButtons.DEFAULTS)
        val next = CustomMenuButtons.withExpandedRows(lists, setOf(CustomMenuButtons.ROOT_SEARCH.id), rows)
        val root = next.getValue("").first { it.id == CustomMenuButtons.ROOT_SEARCH.id }
        assertEquals(rows, root.expandedRows)
        assertEquals(CustomMenuButtons.ROOT_SEARCH.config, root.config, "its configuration is not touched")
        assertEquals(next.getValue("").filter { it.id != root.id }, CustomMenuButtons.DEFAULTS.filter { it.id != root.id })
        assertEquals(next.getValue(""), CustomMenuButtons.decode(CustomMenuButtons.encode(next.getValue(""))), "kept by the store")
        // Put back on a window it opens: these rows open, its own lines left as they are.
        val opened = rows.applyTo(SearchSplits(left = 0.7f, expandedResults = setOf("task/x")))
        assertEquals(setOf("task/root", "task/a"), opened.expandedResults)
        assertEquals(0.7f, opened.left)
    }
}
