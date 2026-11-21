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
 * app should appear in the right-click menu of the calendar."* — and user rule the same day: *"In the down arrow of the
 * left-side menu, in 'Currently on', there is only 'Types: option'. Make sure every search window of the app always
 * respect only what is in 'Currently on'."* A window of given rows says where they come from ("Listed from"), and its
 * configuration narrows them as it narrows a searched list (`ListedFromTest` holds every row source to it).
 */
class MenuSaysWhatItListsTest {
    private val entries = listOf("edit…", "add…", "Day")
    private val keys = entries.map(SearchDomain::optionKey).toSet()
    private val from = SearchDomain.ListedFrom("the entries of this menu", entries.map(SearchDomain::optionKey), entries)
    private fun given() = entries.map { SearchDomain.ItemResult(Kind.Option, it, it, "") }

    private fun leaves(config: SearchDomain.Config, label: String) =
        SearchDomain.configurationTree(config, config).filterIsInstance<SearchDomain.ConfigurationCategory>().single { it.label == label }
            .children.filterIsInstance<SearchDomain.ConfigurationLeaf>().map { it.setting }

    @Test
    fun currently_on_and_the_default_say_where_the_rows_come_from() {
        for (opened in listOf(SearchDomain.OPTION_SEARCH, org.example.project.ui.LATERAL_MENU_SEARCH)) {
            val shown = SearchDomain.withListedFrom(opened, from)
            for (label in listOf(SearchDomain.DEFAULT_NODE_LABEL, "Currently on")) {
                assertTrue(Setting.ListedFrom in leaves(shown, label), "$label: ${leaves(shown, label)}")
            }
        }
    }

    @Test
    fun where_the_rows_come_from_is_never_stored() {
        val shown = SearchDomain.withListedFrom(SearchDomain.OPTION_SEARCH, from)
        assertNull(SearchDomain.Config.decode(shown.encode())!!.filters.listedFrom)
        assertEquals(SearchDomain.OPTION_SEARCH, SearchDomain.withoutListedFrom(shown))
    }

    @Test
    fun the_configuration_narrows_the_given_rows_and_adds_none() {
        val shown = SearchDomain.withListedFrom(SearchDomain.OPTION_SEARCH, from)
        val state = SchedulerState.empty()
        assertEquals(keys, SearchDomain.keptListed(state, shown, given()))
        // An excluded entry: gone.
        val excluded = shown.copy(filters = shown.filters.copy(excludedElements = setOf(SearchDomain.optionKey("Day"))))
        assertEquals(keys - SearchDomain.optionKey("Day"), SearchDomain.keptListed(state, excluded, given()) intersect keys)
        // Their type unchecked: none.
        assertEquals(emptySet(), SearchDomain.keptListed(state, shown.copy(kinds = setOf(Kind.Task)), given()) intersect keys)
    }

    @Test
    fun an_entry_is_named_by_its_key() {
        assertEquals(entries, SearchDomain.resolve(SchedulerState.empty(), entries.map(SearchDomain::optionKey)).map { it.name })
        assertEquals("add…", SearchDomain.changedElementTitle(SchedulerState.empty(), SearchDomain.optionKey("add…")))
    }

    /** The actions section keeps the types and filters its ▾ sets: they narrow its rows, and are what its ▾ lists. */
    @Test
    fun the_actions_section_keeps_what_its_arrow_sets() {
        val own = org.example.project.ui.ACTIONS_SECTION_OWN
        val next = own.copy(filters = SearchDomain.Filters(excludedElements = setOf("Action/x")))
        assertEquals(next.filters, org.example.project.ui.keptOfSection(own, next).filters)
        assertFalse(Kind.Task in org.example.project.ui.keptOfSection(own, next).kinds)
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
