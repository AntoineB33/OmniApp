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

    /**
     * User rule 2026-11-21: *"In the down arrow of the left-side menu, in 'Currently on', there is only 'Types: option'.
     * Make sure every search window of the app always respect only what is in 'Currently on'."* A window of given rows —
     * a drop-down, a right-click menu, the left-side menu — goes through ONE funnel: its rows are its "Included elements".
     */
    @Test
    fun the_left_side_menu_lists_its_items_as_its_included_elements() {
        val menu = org.example.project.ui.LATERAL_MENU_SEARCH
        assertFalse(Kind.Option in menu.searchedKinds, "the menu opens on \"Types: option\" no more")
        val shown = SearchDomain.givenListShown(entries, menu)
        val on = SearchDomain.configurationTree(shown, shown).filterIsInstance<SearchDomain.ConfigurationCategory>().single { it.label == "Currently on" }
        assertTrue(Setting.IncludedElements in on.children.filterIsInstance<SearchDomain.ConfigurationLeaf>().map { it.setting })
        assertTrue(entries.all { SearchDomain.keepsGiven(shown, it) })
    }

    @Test
    fun a_stored_types_option_is_healed_away() {
        val older = SearchDomain.Config(kinds = setOf(Kind.Option), pageTypesOnly = false)
        val read = SearchDomain.Config.decode(older.encode())!!
        val shown = SearchDomain.givenListShown(entries, read)
        assertFalse(Kind.Option in shown.searchedKinds)
        assertEquals(entries.map(SearchDomain::optionKey).toSet(), shown.filters.includedElements)
    }

    @Test
    fun its_rows_are_those_its_configuration_keeps() {
        val shown = SearchDomain.givenListShown(entries, SearchDomain.OPTION_SEARCH)
        // Excluded in its ▾: gone from the rows.
        val excluded = shown.copy(filters = shown.filters.copy(excludedElements = setOf(SearchDomain.optionKey("Day"))))
        assertEquals(listOf("edit…", "add…"), entries.filter { SearchDomain.keepsGiven(excluded, it) })
        // Taken off its "Included elements": kept so, and gone from the rows.
        val fewer = shown.copy(filters = shown.filters.copy(includedElements = setOf(SearchDomain.optionKey("add…"))))
        val kept = SearchDomain.givenListKept(fewer, entries)
        assertEquals(fewer, kept)
        assertEquals(listOf("add…"), entries.filter { SearchDomain.keepsGiven(SearchDomain.givenListShown(entries, kept), it) })
        // As given: no setting of its own, nothing stored — a new entry is listed as it comes.
        assertEquals(emptySet(), SearchDomain.givenListKept(shown, entries).filters.includedElements)
        // A type checked in its ▾ stays checked.
        val typed = shown.copy(kinds = setOf(Kind.Task))
        assertEquals(setOf(Kind.Task), SearchDomain.givenListShown(entries, SearchDomain.givenListKept(typed, entries)).kinds)
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

    /**
     * Anomaly 2026-11-21: *"I add a task element in the added elements list of the window A, add an element in the 'added
     * elements' list in the window B from 'presence ratio target evolutions' field, remove the task from the added elements
     * list in A, add it again, re-open B, and find the same element in the added elements list..."*
     */
    @Test
    fun the_field_s_window_is_lost_as_its_group_goes_and_not_back_with_it() {
        val b = "/actions/field:action/element/Task/x/PresenceTarget/Presence ratio target evolutions"
        val inB = "$b/actions/field:action/element/PresenceEvolution/e/ElementParents/list of parents"
        val bConfig = opened.copy(added = listOf("PresenceEvolution/e"), addedFor = SearchDomain.fieldCallerOf(listOf("Task/x")))
        val inBConfig = opened.copy(added = listOf("Task/z"), addedFor = SearchDomain.fieldCallerOf(listOf("PresenceEvolution/e")))
        val a = SearchDomain.Config(added = listOf("Task/x"), inside = mapOf(b to bConfig.encode(), inB to inBConfig.encode()))
        assertEquals(a, SearchDomain.withLostCallersDropped(a), "its group is there: kept")
        // The task out of A: the field of the group of the task alone is gone, B with it, and what was opened inside B.
        val removed = SearchDomain.withLostCallersDropped(a.copy(added = emptyList()))
        assertEquals(emptyMap(), removed.inside)
        // Added again: a new group, a new caller — nothing of B comes back.
        assertEquals(emptyMap(), SearchDomain.withLostCallersDropped(removed.copy(added = listOf("Task/x"))).inside)
        // Another element added beside it: the group of the task alone is still there.
        assertEquals(a.inside, SearchDomain.withLostCallersDropped(a.copy(added = listOf("Task/x", "Task/y"))).inside)
    }

    @Test
    fun the_field_of_a_group_of_several_is_lost_as_one_joins_or_leaves() {
        val f = "/actions/field:action/kind/Task/ElementParents/list of parents"
        val e1e2Config = opened.copy(added = listOf("Task/p"), addedFor = e1e2)
        val a = SearchDomain.Config(added = listOf("Task/e1", "Task/e2"), inside = mapOf(f to e1e2Config.encode()))
        assertEquals(a, SearchDomain.withLostCallersDropped(a))
        assertEquals(emptyMap(), SearchDomain.withLostCallersDropped(a.copy(added = listOf("Task/e1", "Task/e2", "Task/e3"))).inside, "E3 joins")
        assertEquals(emptyMap(), SearchDomain.withLostCallersDropped(a.copy(added = listOf("Task/e1"))).inside, "E2 leaves")
        // An element of another kind beside them: the group of the tasks E1, E2 is still there.
        assertEquals(a.inside, SearchDomain.withLostCallersDropped(a.copy(added = listOf("Task/e1", "Task/e2", "Chrono/c"))).inside)
        // A context of the action, drawn one section deeper, is about the same group.
        val context = "/actions/actions/field:action/kind/Task/ElementParents/context/0/list of parents"
        val withContext = a.copy(inside = mapOf(context to e1e2Config.encode()))
        assertEquals(withContext, SearchDomain.withLostCallersDropped(withContext))
    }

    @Test
    fun a_field_whose_added_elements_are_its_caller_s_state_is_never_lost() {
        val f = "/actions/field:action/element/Task/x/PresenceTarget/tasks that get it"
        val a = SearchDomain.Config(added = listOf("Task/x"), inside = mapOf(f to opened.copy(query = "q", addedFor = "").encode()))
        assertEquals(a.inside, SearchDomain.withLostCallersDropped(a.copy(added = emptyList())).inside)
    }

    @Test
    fun the_caller_is_no_part_of_the_search() {
        val config = opened.copy(added = listOf("Task/x"), addedFor = e1e2)
        assertNull(SearchDomain.searchConfigurations(config).addedFor)
    }
}
