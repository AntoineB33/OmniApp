package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.MdDomain
import org.example.project.scheduler.domain.MdElement
import org.example.project.scheduler.domain.SearchDomain
import org.example.project.scheduler.state.SchedulerState
import org.example.project.ui.CustomMenuButtons

/**
 * User rule 2026-11-20: *"By default, in 'Task Scheduler', add two buttons in the left-side menu: one that opens a search
 * window showing all the history units, one that opens a search window showing one single md file"* — the one called
 * Life context.
 */
class HistoryAndLifeContextButtonsTest {
    @Test
    fun the_scheduler_menu_holds_both_above_the_calendar_and_no_other_page_does() {
        val ids = CustomMenuButtons.DEFAULTS.map { it.id }
        val calendar = ids.indexOf("d-calendar")
        assertEquals(listOf(CustomMenuButtons.HISTORY_SEARCH.id, CustomMenuButtons.LIFE_CONTEXT_SEARCH.id), ids.subList(calendar - 2, calendar))
        for (page in listOf(CustomMenuButtons.CLAUDE_LOGS_DEFAULTS, CustomMenuButtons.SETTINGS_DEFAULTS)) {
            assertTrue(page.none { it.id == CustomMenuButtons.HISTORY_SEARCH.id || it.id == CustomMenuButtons.LIFE_CONTEXT_SEARCH.id })
        }
    }

    @Test
    fun an_older_menu_is_given_them_once_and_a_removal_stays() {
        val older = CustomMenuButtons.encode(CustomMenuButtons.DEFAULTS - CustomMenuButtons.HISTORY_SEARCH - CustomMenuButtons.LIFE_CONTEXT_SEARCH)
            .replace(",\"historyMdSeeded\":true", "")
        val given = CustomMenuButtons.decode(older)
        assertEquals(CustomMenuButtons.DEFAULTS.map { it.id }, given.map { it.id })
        val removed = CustomMenuButtons.removed(given, CustomMenuButtons.HISTORY_SEARCH.id)
        assertEquals(removed, CustomMenuButtons.decode(CustomMenuButtons.encode(removed)))
        // A Reset brings them back.
        val reset = CustomMenuButtons.withPageReset(mapOf("" to removed), "")
        assertEquals(CustomMenuButtons.DEFAULTS.map { it.id }, reset.getValue("").map { it.id })
    }

    @Test
    fun the_history_button_lists_every_history_unit() {
        val config = SearchDomain.Config.decode(CustomMenuButtons.HISTORY_SEARCH.config)!!
        assertEquals(setOf(SearchDomain.Kind.HistoryUnit), config.kinds)
        assertEquals(SearchDomain.Filters(), config.filters)
        assertTrue(config.query.isEmpty())
    }

    @Test
    fun the_life_context_button_shows_that_md_element_alone_made_at_first_press() {
        val config = SearchDomain.Config.decode(CustomMenuButtons.LIFE_CONTEXT_SEARCH.config)
        assertEquals(MdDomain.LIFE_CONTEXT_ID, SearchDomain.soleMdOf(config))
        val other = MdElement("md-1", "# Other")
        val made = MdDomain.withLifeContext(listOf(other))!!
        assertEquals("Life context", MdDomain.title(made.last()))
        assertNull(MdDomain.withLifeContext(made.map { it.copy(text = "# Renamed") }), "there already, whatever its text")
        val state = SchedulerState.empty().copy(mdElements = made)
        val rows = SearchDomain.results(state, config!!.kinds, "", filters = config.filters)
        assertEquals(listOf("Md/" + MdDomain.LIFE_CONTEXT_ID), rows.map(SearchDomain::keyOf))
    }
}
