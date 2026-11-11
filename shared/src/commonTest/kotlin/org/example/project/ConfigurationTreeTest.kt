package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.SearchDomain
import org.example.project.scheduler.domain.SearchDomain.ConfigurationCategory
import org.example.project.scheduler.domain.SearchDomain.ConfigurationLeaf
import org.example.project.scheduler.domain.SearchDomain.ConfigurationNode
import org.example.project.scheduler.domain.SearchDomain.Setting
import org.example.project.ui.filterConfigurationNodes

/**
 * User rule 2026-11-04: *"add a downward arrow at the right of the search bar. When hovering over it, it opens a pop-up
 * tree, where an end node is a search configuration, and a not-end node is a search configuration category revealing
 * its children when the user hovers over it. The first element of the root gives all the search configurations sorted
 * from more to less recently changed. All the search configurations must be found in this tree."*
 */
class ConfigurationTreeTest {
    private fun leavesOf(nodes: List<ConfigurationNode>): List<Setting> =
        nodes.flatMap { node ->
            when (node) {
                is ConfigurationLeaf -> listOf(node.setting)
                is ConfigurationCategory -> leavesOf(node.children)
            }
        }

    @Test
    fun every_configuration_is_in_the_tree_under_a_category_of_its_own() {
        val tree = SearchDomain.configurationTree(SearchDomain.Config())
        assertTrue(tree.all { it is ConfigurationCategory }, "the root holds categories")
        // Past the first node, each configuration stands once.
        val rest = leavesOf(tree.drop(1))
        assertEquals(Setting.entries.toSet(), rest.toSet())
        assertEquals(rest.size, rest.toSet().size)
        // A category holds categories or configurations, never both, and none is empty.
        fun check(nodes: List<ConfigurationNode>) {
            for (node in nodes.filterIsInstance<ConfigurationCategory>()) {
                assertTrue(node.children.isNotEmpty(), node.label)
                assertTrue(node.children.all { it is ConfigurationLeaf } || node.children.all { it is ConfigurationCategory }, node.label)
                check(node.children)
            }
        }
        check(tree)
        val ids = mutableListOf<String>()
        fun collect(nodes: List<ConfigurationNode>): Unit = nodes.filterIsInstance<ConfigurationCategory>().forEach { ids += it.id; collect(it.children) }
        collect(tree)
        assertEquals(ids.size, ids.toSet().size, "ids unique")
    }

    @Test
    fun the_first_node_is_every_configuration_from_the_most_to_the_least_recently_changed() {
        var config = SearchDomain.Config()
        config = SearchDomain.withChanged(config, config.copy(query = "a"), listOf(Setting.SearchText), 100)
        config = SearchDomain.withChanged(config, config.copy(filters = config.filters.copy(matchCase = true)), listOf(Setting.MatchCase), 200)
        val first = SearchDomain.configurationTree(config).first() as ConfigurationCategory
        assertEquals("Recently changed", first.label)
        val order = first.children.map { (it as ConfigurationLeaf).setting }
        assertEquals(listOf(Setting.MatchCase, Setting.SearchText), order.take(2))
        assertEquals(Setting.entries.toSet(), order.toSet(), "all of them")
        assertEquals(Setting.entries.filter { it != Setting.MatchCase && it != Setting.SearchText }, order.drop(2), "the others in their order")
        // A change that changes nothing stamps nothing.
        assertEquals(config, SearchDomain.withChanged(config, config, listOf(Setting.Types), 300))
    }

    @Test
    fun the_change_times_are_kept_and_are_no_part_of_the_search() {
        val config = SearchDomain.withChanged(SearchDomain.Config(), SearchDomain.Config(query = "a"), listOf(Setting.SearchText), 100)
        assertEquals(config, SearchDomain.Config.decode(config.encode()))
        val older = SearchDomain.Config(query = "a").encode()
        assertFalse("settingsChangedAt" in older, "none written: the shape before them")
        assertEquals(emptyMap(), SearchDomain.Config.decode(older)!!.settingsChangedAt)
        // A setting this build does not know is dropped.
        val later = config.encode().replace("\"SearchText\"", "\"Telepathy\"")
        assertEquals(emptyMap(), SearchDomain.Config.decode(later)!!.settingsChangedAt)
        assertTrue(SearchDomain.sameSearchConfigurations(config.encode(), older))
    }

    /**
     * Anomaly 2026-11-11: a letter typed in a child pop-up's search field was written in its parent's too. A child
     * pop-up's field narrows its own category's children alone ([filterConfigurationNodes]).
     */
    @Test
    fun a_child_pop_up_query_narrows_its_own_children_alone() {
        val first = SearchDomain.configurationTree(SearchDomain.Config()).first() as ConfigurationCategory
        val needle = Setting.MatchCase.label
        val narrowed = first.children.filterConfigurationNodes(needle)
        assertTrue(narrowed.isNotEmpty())
        assertTrue(narrowed.all { (it as ConfigurationLeaf).label.contains(needle, ignoreCase = true) })
        assertEquals(first.children, first.children.filterConfigurationNodes(""), "an empty field narrows nothing")
    }

    /**
     * Anomaly 2026-11-11: *"Task Scheduler then timer, but the final Search window is empty"* — the tree was cut to the
     * kinds the Search window searches, its categories left standing empty. Nothing but a search field narrows it, a
     * category found by its own label keeps all it holds, and none is ever shown empty.
     */
    @Test
    fun the_tree_holds_every_configuration_and_no_category_is_ever_empty() {
        // A window searching one kind alone still finds every configuration in its tree.
        val tree = SearchDomain.configurationTree(SearchDomain.Config(kinds = setOf(SearchDomain.Kind.Task)))
        fun leaves(nodes: List<ConfigurationNode>): List<Setting> = nodes.flatMap {
            when (it) { is ConfigurationLeaf -> listOf(it.setting); is ConfigurationCategory -> leaves(it.children) }
        }
        assertEquals(Setting.entries.toSet(), leaves(tree.filterConfigurationNodes("")).toSet())
        fun categories(nodes: List<ConfigurationNode>): List<ConfigurationCategory> =
            nodes.filterIsInstance<ConfigurationCategory>().flatMap { listOf(it) + categories(it.children) }
        // Every kind with configurations has them under its category.
        for (category in categories(tree)) assertTrue(category.children.isNotEmpty(), category.label)
        // Found by its own label, a category keeps everything under it.
        val timer = categories(tree).first { it.id == "kind/" + SearchDomain.Kind.Timer.name }
        val found = categories(tree.filterConfigurationNodes(timer.label)).first { it.id == timer.id }
        assertEquals(timer.children, found.children)
        // Whatever is typed, no category is left empty.
        for (needle in listOf("t", "ti", "zzz", "time", Setting.MatchCase.label)) {
            for (category in categories(tree.filterConfigurationNodes(needle))) assertTrue(category.children.isNotEmpty(), "$needle: ${category.label}")
        }
    }
}
