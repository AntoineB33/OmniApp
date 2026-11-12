package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import org.example.project.scheduler.domain.SearchDomain
import org.example.project.scheduler.domain.SearchDomain.Kind
import org.example.project.scheduler.state.SchedulerState

/**
 * User rule 2026-11-12: *"All the drop-down menus of the app must be Search windows as well. Add the element 'Select all'
 * that when clicked selects all the elements of the same list that have a check box."*
 */
class OptionSearchTest {
    private val options = listOf("a" to "Apple", "b" to "Banana", "c" to "Cherry")

    @Test
    fun a_drop_downs_rows_are_its_options_in_its_order() {
        val rows = SearchDomain.optionResults(options, "", checked = null)
        assertEquals(listOf("a", "b", "c"), rows.map { it.id })
        assertEquals(setOf(Kind.Option), rows.map { it.kind }.toSet())
    }

    @Test
    fun a_list_with_check_boxes_starts_with_select_all_which_reads_deselect_once_all_are() {
        val none = SearchDomain.optionResults(options, "", checked = emptySet())
        assertEquals(SearchDomain.SELECT_ALL_OPTION, none.first().id)
        assertEquals("Select all", none.first().name)
        assertEquals("Deselect all", SearchDomain.optionResults(options, "", checked = setOf("a", "b", "c")).first().name)
    }

    @Test
    fun the_bar_finds_among_the_options_and_select_all_acts_on_those_listed() {
        val found = SearchDomain.optionResults(options, "an", checked = emptySet())
        assertEquals(listOf(SearchDomain.SELECT_ALL_OPTION, "b"), found.map { it.id })
        val listed = found.map { it.id }.filter { it != SearchDomain.SELECT_ALL_OPTION }
        assertEquals(setOf("c", "b"), SearchDomain.selectAllApplied(setOf("c"), listed))
        assertEquals(setOf("c"), SearchDomain.selectAllApplied(setOf("b", "c"), listed), "all listed checked: unchecked")
    }

    @Test
    fun the_account_holds_no_option() {
        assertEquals(emptyList(), SearchDomain.itemResults(SchedulerState.empty(), Kind.Option, ""))
        assertEquals(setOf(Kind.Option), SearchDomain.OPTION_SEARCH.kinds)
    }
}
