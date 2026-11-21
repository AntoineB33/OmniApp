package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.SearchDomain
import org.example.project.scheduler.domain.SearchDomain.Kind
import org.example.project.scheduler.domain.SearchDomain.Setting
import org.example.project.scheduler.state.SchedulerState

/**
 * Anomaly 2026-11-21: *"I right-click in the calendar, then in the down arrow, then in 'Default for this', and I only find
 * type: option. Same thing in 'Currently on'. If the logic was correct, that would mean every single option element of the
 * app should appear in the right-click menu of the calendar."* A menu's Search window says what it lists: its entries,
 * the elements always in it, and no type searched.
 */
class MenuSaysWhatItListsTest {
    private val entries = listOf("edit…", "add…", "Day")

    @Test
    fun a_menu_searches_no_type_and_lists_its_entries_as_included_elements() {
        val config = SearchDomain.optionListConfig(entries)
        assertEquals(emptySet(), config.kinds)
        assertEquals(entries.map(SearchDomain::optionKey).toSet(), config.filters.includedElements)
        assertFalse(Kind.Option in config.searchedKinds, "\"type: option\" is every option of the app")
    }

    @Test
    fun its_default_and_what_is_on_name_its_entries() {
        val config = SearchDomain.optionListConfig(entries)
        val tree = SearchDomain.configurationTree(config, config)
        val roots = tree.filterIsInstance<SearchDomain.ConfigurationCategory>()
        for (label in listOf(SearchDomain.DEFAULT_NODE_LABEL, "Currently on")) {
            val node = roots.single { it.label == label }
            val listed = node.children.filterIsInstance<SearchDomain.ConfigurationLeaf>().map { it.setting }
            assertTrue(Setting.IncludedElements in listed, "$label: $listed")
        }
    }

    @Test
    fun an_entry_is_named_by_its_key() {
        val keys = entries.map(SearchDomain::optionKey)
        assertEquals(entries, SearchDomain.resolve(SchedulerState.empty(), keys).map { it.name })
        assertEquals("add…", SearchDomain.changedElementTitle(SchedulerState.empty(), SearchDomain.optionKey("add…")))
    }
}

/**
 * User rule 2026-11-21: *"The 'added elements' list section of a window must be preserved between closing and
 * re-appearing, except if the caller changes. If the caller is from a group in the action section that involves a
 * different set of elements, then it is considered to be changed."*
 */
class FieldWindowKeepsItsAddedListTest {
    private val opened = SearchDomain.Config(kinds = setOf(Kind.Task))
    private val e1e2 = SearchDomain.fieldCallerOf(listOf("Task/e2", "Task/e1"))
    private val e1e2e3 = SearchDomain.fieldCallerOf(listOf("Task/e1", "Task/e2", "Task/e3"))

    @Test
    fun the_caller_is_the_set_of_the_groups_elements() {
        assertEquals(SearchDomain.fieldCallerOf(listOf("Task/e1", "Task/e2")), e1e2, "in any order")
        assertTrue(e1e2 != e1e2e3)
    }

    @Test
    fun opened_again_by_the_same_caller_it_keeps_what_was_added() {
        val closed = opened.copy(added = listOf("Task/x", "Task/y"), addedFor = e1e2, query = "old")
        val again = SearchDomain.fieldWindowReopened(closed, opened, e1e2, query = "")
        assertEquals(listOf("Task/x", "Task/y"), again.added)
        assertEquals(e1e2, again.addedFor)
        assertEquals("", again.query)
    }

    @Test
    fun opened_by_another_caller_it_has_no_added_element_of_the_first() {
        val closed = opened.copy(added = listOf("Task/x"), addedFor = e1e2)
        val again = SearchDomain.fieldWindowReopened(closed, opened, e1e2e3, query = "")
        assertEquals(emptyList(), again.added)
        assertNull(again.addedFor)
    }

    @Test
    fun what_was_added_before_a_caller_was_said_is_kept() {
        val stored = opened.copy(added = listOf("Task/x"))
        assertEquals(listOf("Task/x"), SearchDomain.fieldWindowReopened(stored, opened, e1e2, query = "").added)
    }

    /** Persisted-DB compatibility: the caller is kept with the window, and a configuration written before it reads none. */
    @Test
    fun the_caller_is_stored_and_an_older_configuration_reads_none() {
        val config = opened.copy(added = listOf("Task/x"), addedFor = e1e2)
        assertEquals(e1e2, SearchDomain.Config.decode(config.encode())?.addedFor)
        val older = opened.copy(added = listOf("Task/x")).encode()
        assertFalse("addedFor" in older)
        val read = SearchDomain.Config.decode(older)
        assertEquals(listOf("Task/x"), read?.added)
        assertNull(read?.addedFor)
    }

    @Test
    fun the_caller_is_no_part_of_the_search() {
        val config = opened.copy(added = listOf("Task/x"), addedFor = e1e2)
        assertNull(SearchDomain.searchConfigurations(config).addedFor)
    }
}
