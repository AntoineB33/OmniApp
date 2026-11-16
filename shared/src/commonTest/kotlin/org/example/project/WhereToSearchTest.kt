package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.ElementLink
import org.example.project.scheduler.domain.ElementTree
import org.example.project.scheduler.domain.SearchDomain
import org.example.project.scheduler.domain.SearchDomain.ConfigurationCategory
import org.example.project.scheduler.domain.SearchDomain.ConfigurationLeaf
import org.example.project.scheduler.domain.SearchDomain.Kind
import org.example.project.scheduler.domain.SearchDomain.SearchField
import org.example.project.scheduler.domain.SearchDomain.Setting
import org.example.project.scheduler.model.ChronoEntry
import org.example.project.scheduler.state.SchedulerState
import org.example.project.ui.filterConfigurationNodes

/**
 * User rule 2026-11-16, the ▾ sketch: *"search > where to search > [] id, [] name, [] description, [] buttons,
 * [] children > [] id, [] parent distance [1], [] name, [] description, [] button"* — "Where to search", for every
 * Search window (*"Every Search window"*).
 */
class WhereToSearchTest {
    private fun key(id: String) = "Chrono/$id"

    /** "tea" under "kettle" under "kitchen"; "garden" alone. Ids differ from the labels. */
    private val state: SchedulerState =
        SchedulerState.empty().copy(
            chronos = listOf("c1" to "kitchen", "c2" to "kettle", "c3" to "tea", "c4" to "garden").map { (id, label) -> ChronoEntry(id = id, label = label) },
            elementLinks = listOf("c1" to "c2", "c2" to "c3").map { (parent, child) ->
                ElementLink(ElementTree.linkId(key(parent), key(child)), key(parent), key(child))
            },
        )

    private fun found(query: String, filters: SearchDomain.Filters = SearchDomain.Filters()): Set<String> =
        SearchDomain.results(state, setOf(Kind.Chrono), query, filters = filters).mapTo(HashSet()) { it.name }

    @Test
    fun the_names_alone_by_default() {
        assertEquals(setOf("tea"), found("tea"))
        assertEquals(emptySet(), found("c3"), "an id is not searched by default")
        assertTrue(SearchDomain.Filters().searchesNamesOnly)
    }

    @Test
    fun an_id_is_found_once_ids_are_searched() {
        val ids = SearchDomain.Filters().withSearches(Setting.SearchInId, true)
        assertEquals(setOf("tea"), found("c3", ids))
        // ...the names still, while they are checked; not once they are not.
        assertEquals(setOf("tea"), found("tea", ids))
        assertEquals(emptySet(), found("tea", ids.withSearches(Setting.SearchInName, false)))
        // A blank bar lists everything.
        assertTrue(found("", ids).containsAll(setOf("kitchen", "kettle", "tea", "garden")))
    }

    @Test
    fun a_row_is_found_by_its_children_at_the_distance_asked() {
        val children = SearchDomain.Filters().withSearches(Setting.SearchInChildren, true)
        // "tea" is a child of "kettle": both are found, the child by its name, the parent by its child's.
        assertEquals(setOf("kettle", "tea"), found("tea", children))
        // Two levels down: "kitchen" by its grandchild, not "kettle" (its child is not two levels below it).
        assertEquals(setOf("kitchen", "tea"), found("tea", children.copy(childDistance = 2)))
        // The children's ids, once checked.
        val childIds = children.withSearches(Setting.ChildSearchInId, true).withSearches(Setting.ChildSearchInName, false)
        assertEquals(setOf("kettle"), found("c3", childIds.withSearches(Setting.SearchInName, false)))
    }

    @Test
    fun a_search_configuration_is_found_by_its_buttons() {
        val buttons = SearchDomain.Filters(searchIn = setOf(SearchField.Buttons))
        val rows = SearchDomain.results(SchedulerState.empty(), setOf(Kind.SearchConfiguration), "paused", filters = buttons)
        assertEquals(setOf(Setting.TimerStateSetting, Setting.ChronoStateSetting), rows.mapNotNullTo(HashSet(), SearchDomain::settingOf))
    }

    /** User rule 2026-11-16: the ▾ tree's place for them — "Search › Where to search", its "Children" last. */
    @Test
    fun where_to_search_stands_under_search_in_the_sketch_order() {
        val tree = SearchDomain.configurationTree(SearchDomain.Config())
        val search = tree.filterIsInstance<ConfigurationCategory>().single { it.id == SearchDomain.TreeGroup.Bar.id }
        assertEquals(listOf("Where to search", "Matching"), search.children.filterIsInstance<ConfigurationCategory>().map { it.label }.take(2))
        val where = search.children.first() as ConfigurationCategory
        assertEquals(listOf("Id", "Name", "Description", "Buttons", "Children"), where.children.map { it.label })
        val children = where.children.last() as ConfigurationCategory
        assertEquals(listOf("Search the children", "Id", "Parent distance", "Name", "Description", "Buttons"), children.children.map { it.label })
        assertEquals(listOf("Search", "Where to search", "Children"), SearchDomain.homePathOf(Setting.ChildDistance))
        // The flat list names them by their path.
        assertTrue(tree.any { it.label == "Search › Where to search › Children › Parent distance" })
    }

    /** What is on by default (the name) is no news in "Currently on"; what is set otherwise is. */
    @Test
    fun what_is_checked_otherwise_than_by_default_is_currently_on() {
        assertEquals(emptyList(), SearchDomain.whereSettingsOn(SearchDomain.Filters()))
        val filters = SearchDomain.Filters().withSearches(Setting.SearchInId, true).withSearches(Setting.SearchInChildren, true).copy(childDistance = 2)
        assertEquals(listOf(Setting.SearchInId, Setting.SearchInChildren, Setting.ChildDistance), SearchDomain.whereSettingsOn(filters))
        val on = SearchDomain.configurationTree(SearchDomain.Config(filters = filters)).filterIsInstance<ConfigurationCategory>().single { it.id == SearchDomain.ON_NODE }
        assertEquals(listOf(Setting.SearchInId, Setting.SearchInChildren, Setting.ChildDistance), on.children.map { (it as ConfigurationLeaf).setting })
    }

    /** The ▾'s own field is a Search window's bar: it reads the tree's ids when its own configuration says so. */
    @Test
    fun the_tree_field_follows_its_own_where_to_search() {
        val tree = SearchDomain.configurationTree(SearchDomain.Config())
        assertTrue(tree.filterConfigurationNodes("WholeWord").none { it is ConfigurationLeaf }, "an id is not a name")
        val ids = SearchDomain.Filters().withSearches(Setting.SearchInId, true)
        assertEquals(listOf(Setting.WholeWord), tree.filterConfigurationNodes("WholeWord", ids).filterIsInstance<ConfigurationLeaf>().map { it.setting })
    }

    /** Persisted-DB compatibility: a configuration written before "Where to search" reads as the names alone. */
    @Test
    fun a_configuration_written_before_reads_the_names_alone_and_a_new_one_comes_back() {
        val older = SearchDomain.Config(query = "a").encode()
        assertTrue("searchIn" !in older && "childDistance" !in older, "nothing written while it is the default: $older")
        assertEquals(SearchDomain.Filters(), SearchDomain.Config.decode(older)!!.filters)
        val set = SearchDomain.Config(
            filters = SearchDomain.Filters(searchIn = setOf(SearchField.Id, SearchField.Buttons), searchChildren = true, childSearchIn = setOf(SearchField.Description), childDistance = 3),
        )
        assertEquals(set, SearchDomain.Config.decode(set.encode()))
        // A field a later build knows and this one does not is dropped.
        val later = set.encode().replace("\"Buttons\"", "\"Telepathy\"")
        assertEquals(setOf(SearchField.Id), SearchDomain.Config.decode(later)!!.filters.searchIn)
    }
}
