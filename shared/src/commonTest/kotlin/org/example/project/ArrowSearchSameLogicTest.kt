package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.SearchDomain
import org.example.project.scheduler.domain.SearchDomain.ConfigurationCategory
import org.example.project.scheduler.domain.SearchDomain.ConfigurationLeaf
import org.example.project.scheduler.domain.SearchDomain.Kind
import org.example.project.scheduler.domain.SearchDomain.Setting
import org.example.project.scheduler.domain.SearchDomain.SortKey
import org.example.project.scheduler.domain.SearchDomain.SortMethod
import org.example.project.scheduler.model.ChronoEntry
import org.example.project.scheduler.state.SchedulerState

/**
 * User rule 2026-11-16: *"In down arrow > down arrow, I don't see 'Currently on' … If nothing is on in down arrow > down
 * arrow, that mean that what is shown in down arrow is random, right?"* — then *"I want the same logic everywhere, what
 * changes are the default values in search configurations in some contexts."* The ▾'s list is its own search's answer
 * ([SearchDomain.shownConfigurationNodes] through [SearchDomain.results]), whose defaults say what it lists and in what
 * order — and "Currently on" / "Default for this" say so.
 */
class ArrowSearchSameLogicTest {
    private val arrow = SearchDomain.CONFIGURATION_SEARCH
    private fun idsOf(nodes: List<SearchDomain.ConfigurationNode>) = nodes.filterIsInstance<ConfigurationCategory>().map { it.id }

    @Test
    fun what_orders_and_fills_the_arrow_list_is_currently_on_and_its_default() {
        val tree = SearchDomain.configurationTree(arrow, arrow)
        val on = tree.filterIsInstance<ConfigurationCategory>().single { it.id == SearchDomain.ON_NODE }
        val onSettings = on.children.map { (it as ConfigurationLeaf).setting }
        assertTrue(onSettings.containsAll(listOf(Setting.Types, Setting.SortResults, Setting.SearchInChildren, Setting.ChildDistance)), onSettings.toString())
        val byDefault = tree.filterIsInstance<ConfigurationCategory>().single { it.id == "default" }
        assertTrue(byDefault.children.map { (it as ConfigurationLeaf).setting }.containsAll(listOf(Setting.Types, Setting.SortResults)))
        // A plain Search window's are no news: its types and its order are the defaults of every window. User rule
        // 2026-11-26: a window with nothing on lists every element — every type checked.
        val plain = SearchDomain.configurationTree(SearchDomain.Config(kinds = SearchDomain.Kind.entries.toSet()))
        assertTrue(plain.none { it is ConfigurationCategory && it.id == SearchDomain.ON_NODE })
    }

    @Test
    fun the_arrow_search_sorts_by_the_tree_order_by_default_and_by_another_when_set() {
        assertEquals(SearchDomain.TREE_ORDER_SORTS, arrow.sorts)
        assertEquals(SearchDomain.TREE_ORDER_SORTS, SearchDomain.defaultSortsOf(arrow.copy(query = "match")), "whatever the bar holds")
        val tree = SearchDomain.configurationTree(SearchDomain.Config())
        val rows = SearchDomain.ConfigurationTreeRows(tree)
        val shown = SearchDomain.shownConfigurationNodes(SchedulerState.empty(), tree, root = true, rows, arrow)
        assertEquals(tree, shown, "the tree's own order")
        // Sorted by name, the list obeys: what the ▾ ▾ sets acts on the ▾.
        val byName = SearchDomain.shownConfigurationNodes(SchedulerState.empty(), tree, root = true, rows, arrow.copy(sorts = listOf(SortMethod(null, SortKey.Name))))
        assertEquals(tree.map { it.label.lowercase() }.sorted(), byName.map { it.label.lowercase() })
    }

    @Test
    fun the_arrow_search_lists_the_types_it_checks() {
        val state = SchedulerState.empty().copy(chronos = listOf(ChronoEntry(id = "c1", label = "kettle")))
        val tree = SearchDomain.configurationTree(SearchDomain.Config())
        val rows = SearchDomain.ConfigurationTreeRows(tree)
        val both = arrow.copy(kinds = setOf(Kind.SearchConfiguration, Kind.Chrono))
        val shown = SearchDomain.shownConfigurationNodes(state, tree, root = true, rows, both)
        assertTrue(shown.any { it is SearchDomain.ConfigurationOther && it.label == "kettle" }, shown.map { it.label }.toString())
        // Without the search configurations, the tree is gone from it.
        val chronosOnly = SearchDomain.shownConfigurationNodes(state, tree, root = true, rows, arrow.copy(kinds = setOf(Kind.Chrono)))
        assertTrue(chronosOnly.all { it is SearchDomain.ConfigurationOther })
    }

    /** "Where to search › Children: any" by default: a category is found by what it holds, at any depth. */
    @Test
    fun a_category_is_found_by_what_it_holds_at_any_depth() {
        val tree = SearchDomain.configurationTree(SearchDomain.Config())
        val found = idsOf(tree.filterConfigurationNodes("Use regular expression"))
        assertTrue(SearchDomain.TreeGroup.Bar.id in found, "Search, two levels above it: $found")
        // At distance 1 alone, not.
        val near = idsOf(tree.filterConfigurationNodes("Use regular expression", arrow.filters.copy(childDistance = 1)))
        assertTrue(SearchDomain.TreeGroup.Bar.id !in near, near.toString())
    }

    @Test
    fun a_given_list_sorted_by_tree_order_keeps_its_order_whatever_the_bar() {
        val given = listOf("b match", "match", "a match").mapIndexed { i, name -> SearchDomain.ItemResult(Kind.SearchConfiguration, "x$i", name, "") }
        val sorted = SearchDomain.results(SchedulerState.empty(), setOf(Kind.SearchConfiguration), "match", given = given, sorts = SearchDomain.TREE_ORDER_SORTS)
        assertEquals(listOf("b match", "match", "a match"), sorted.map { it.name })
    }

    /** Persisted-DB compatibility: a ▾ search stored before still follows its default sort, now the tree's order. */
    @Test
    fun a_stored_arrow_search_of_before_reads_in_the_tree_order() {
        val older = SearchDomain.Config(kinds = setOf(Kind.SearchConfiguration), query = "sort").encode()
        val registry = org.example.project.ui.ArrowSearches(read = { mapOf("▾" to older) }, write = { _, _ -> })
        val read = registry.of("Search", "▾")
        assertEquals(SearchDomain.TREE_ORDER_SORTS, read.sorts)
        assertEquals("sort", read.query)
        // The "any" distance comes back as stored.
        val any = SearchDomain.Config(filters = SearchDomain.Filters(searchChildren = true, childDistance = SearchDomain.CHILD_DISTANCE_ANY))
        assertEquals(any, SearchDomain.Config.decode(any.encode()))
    }
}
