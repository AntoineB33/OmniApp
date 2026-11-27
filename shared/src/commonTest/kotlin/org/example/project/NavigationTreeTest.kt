package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.SearchDomain
import org.example.project.scheduler.domain.SearchDomain.ConfigurationCategory
import org.example.project.scheduler.domain.SearchDomain.ConfigurationLeaf
import org.example.project.scheduler.domain.SearchDomain.ConfigurationNode
import org.example.project.scheduler.domain.SearchDomain.Kind
import org.example.project.scheduler.domain.SearchDomain.Setting
import org.example.project.ui.ArrowSearches

/**
 * User rule 2026-11-27: *"When the user wants to do something, the navigation towards the goal must be very intuitive.
 * For this, you should set to on (and add if didn't exist) search configurations for some contexts, so that in some
 * contexts the elements are organized in an intuitive tree structure. … Make sure there are not lots of elements in one
 * node of the tree structure, or the user will take time to find the right branch."*
 *
 * "Tree to navigate" ([SearchDomain.Config.navigationTree]) is the configuration; the ▾'s context has it on. Measured
 * before: "Recent" held 113 rows, "Sorting" 29, the Task Scheduler's root 23 categories.
 */
class NavigationTreeTest {
    private val max = SearchDomain.MAX_TREE_NODE_ROWS

    private fun isFlat(node: ConfigurationNode) =
        (node is ConfigurationCategory && node.id.startsWith(SearchDomain.FLAT_NODE_PREFIX))

    /** Every category below the root but the flat list's, with its path. */
    private fun categories(nodes: List<ConfigurationNode>, above: String = ""): List<Pair<String, ConfigurationCategory>> =
        nodes.filterIsInstance<ConfigurationCategory>().filterNot(::isFlat).flatMap { listOf("$above/${it.label}" to it) + categories(it.children, "$above/${it.label}") }

    /** A tree as full as it gets: every configuration changed here and elsewhere, many on, many by default. */
    private fun fullTree(page: SearchDomain.FeaturePage): List<ConfigurationNode> {
        val changed = Setting.entries.mapIndexed { i, s -> s to (1_000L + i) }.toMap()
        val busy = SearchDomain.Config(page = page.id, settingsChangedAt = changed, dropButtons = true, separators = true, actionsExpanded = true,
            menuCustomizing = true, rightClickChecksAlone = true, buttonFollowsExpansion = true, kinds = setOf(Kind.Task))
        return SearchDomain.configurationTree(
            busy, opened = busy, relevant = Setting.entries.take(40),
            elsewhere = SearchDomain.Elsewhere(changed = changed),
        )
    }

    @Test
    fun no_node_of_the_tree_holds_more_than_the_bound() {
        for (page in SearchDomain.FEATURE_PAGES) {
            for (tree in listOf(fullTree(page), SearchDomain.configurationTree(SearchDomain.Config(page = page.id)))) {
                // The root: its rows that open a node, the flat list (there to be typed into, user rule 2026-11-14) apart.
                val root = tree.filterNot { isFlat(it) || it is ConfigurationLeaf }
                assertTrue(root.size <= max, "root of '${page.label}' holds ${root.size}: ${root.map { it.label }}")
                for ((path, node) in categories(tree)) {
                    assertTrue(node.children.size <= max, "$path holds ${node.children.size}")
                    assertTrue(node.children.isNotEmpty(), "$path is empty")
                }
            }
        }
    }

    /**
     * Anomaly 2026-11-27 (*"I rebuilt and opened the down arrow of the default search window … Are you sure you satisfied my
     * prompt?"*): the first pop-up listed its flat list, some 140 rows, under its categories. What the pop-up LISTS — the
     * one funnel, [SearchDomain.shownConfigurationNodes] — is bounded with nothing typed; a name typed finds its row there.
     */
    @Test
    fun the_first_pop_up_lists_its_categories_alone_until_a_name_is_typed() {
        val state = org.example.project.scheduler.state.SchedulerState.empty()
        for (page in SearchDomain.FEATURE_PAGES) {
            for (tree in listOf(fullTree(page), SearchDomain.configurationTree(SearchDomain.pageDefaultConfig(page.id)))) {
                val rows = SearchDomain.ConfigurationTreeRows(tree)
                val shown = SearchDomain.shownConfigurationNodes(state, tree, root = true, rows, SearchDomain.CONFIGURATION_SEARCH)
                assertTrue(shown.size <= max, "the root of '${page.label}' lists ${shown.size}: ${shown.map { it.label }}")
                assertTrue(shown.none(SearchDomain::isFlatEntry))
                // Typed, the configuration is right there, at the root.
                val typed = SearchDomain.shownConfigurationNodes(state, tree, root = true, rows, SearchDomain.CONFIGURATION_SEARCH.copy(query = "Match case"))
                assertTrue(typed.any { it is ConfigurationLeaf && it.setting == Setting.MatchCase }, typed.map { it.label }.toString())
            }
        }
        // "Tree to navigate" off: every configuration, flat.
        val flat = SearchDomain.flatConfigurationList()
        val off = SearchDomain.shownConfigurationNodes(state, flat, root = true, SearchDomain.ConfigurationTreeRows(flat), SearchDomain.CONFIGURATION_SEARCH.copy(navigationTree = false))
        assertEquals(Setting.entries.size, off.size)
    }

    @Test
    fun every_configuration_is_still_reached_through_the_structure() {
        for (page in SearchDomain.FEATURE_PAGES) {
            val tree = SearchDomain.configurationTree(SearchDomain.Config(page = page.id))
            fun leaves(nodes: List<ConfigurationNode>): Set<Setting> =
                nodes.flatMapTo(HashSet()) { if (it is ConfigurationLeaf) listOf(it.setting) else if (it is ConfigurationCategory) leaves(it.children) else emptyList() }
            // "Recent" alone already lists every one; the parts of its last-changed list hold every one too.
            val recent = tree.first() as ConfigurationCategory
            assertEquals(SearchDomain.RECENT_NODE, recent.id)
            assertEquals(Setting.entries.toSet(), leaves(recent.children))
            assertEquals(Setting.entries.toSet(), leaves(tree.filterNot { it is ConfigurationLeaf }))
        }
    }

    @Test
    fun the_task_schedulers_types_stand_by_family() {
        val tree = SearchDomain.configurationTree(SearchDomain.Config(page = ""))
        val labels = tree.filterIsInstance<ConfigurationCategory>().filterNot(::isFlat).map { it.label }
        for (family in listOf("Tasks", "Calendar", "Alarms and timers", "App")) assertTrue(family in labels, "$family not at the root: $labels")
        assertFalse(Kind.Alarm.label in labels, "the alarm type still at the root: $labels")
        val alarms = tree.filterIsInstance<ConfigurationCategory>().first { it.label == "Alarms and timers" }
        assertTrue(alarms.children.any { it.label == Kind.Alarm.label })
        // The path a configuration listed away from its place shows leads through its family.
        val alarmSetting = Setting.entries.first { it.section == Kind.Alarm && !it.sorts }
        assertEquals(listOf("Alarms and timers", SearchDomain.treeNameOf(Kind.Alarm)), SearchDomain.homePathOf(alarmSetting))
        // A page of few types keeps them one hover from the root.
        val logs = SearchDomain.configurationTree(SearchDomain.Config(page = SearchDomain.CLAUDE_LOGS_PAGE))
        assertTrue(logs.any { it.label == Kind.ClaudePrompt.label }, logs.map { it.label }.toString())
    }

    @Test
    fun sorting_lists_every_types_order_by_family() {
        val sorting = SearchDomain.configurationTree(SearchDomain.Config(page = "")).filterIsInstance<ConfigurationCategory>().first { it.id == "sort" }
        assertTrue(sorting.children.size <= max)
        val families = sorting.children.filterIsInstance<ConfigurationCategory>().map { it.label }
        // This page's families first, then the others'.
        assertEquals("Tasks", families.first())
        assertTrue(families.indexOf("Claude Code logs") > families.indexOf("App"), families.toString())
    }

    @Test
    fun the_down_arrows_context_has_the_tree_on_and_a_page_search_has_it_off() {
        assertTrue(SearchDomain.CONFIGURATION_SEARCH.navigationTree)
        assertTrue(Setting.NavigationTree in SearchDomain.activeSettings(SearchDomain.CONFIGURATION_SEARCH))
        assertFalse(SearchDomain.pageDefaultConfig("").navigationTree)
        assertFalse(Setting.NavigationTree in SearchDomain.activeSettings(SearchDomain.pageDefaultConfig("")))
        assertEquals(SearchDomain.TreeGroup.Parents, SearchDomain.treeGroupOf(Setting.NavigationTree))
        // Off, the ▾ lists every configuration flat.
        assertEquals(Setting.entries.toSet(), SearchDomain.flatConfigurationList().map { (it as ConfigurationLeaf).setting }.toSet())
    }

    @Test
    fun a_down_arrow_search_stored_before_reads_the_tree_on() {
        // Stored after contexts were kept, before "Tree to navigate": its context's default.
        val inContext = SearchDomain.CONFIGURATION_SEARCH.copy(query = "sort").encode().replace("\"navigationTree\":true,", "")
        assertFalse("navigationTree" in inContext)
        assertTrue(SearchDomain.Config.decode(inContext)!!.navigationTree)
        // Stored before contexts were kept: what the ▾ opens with.
        val older = SearchDomain.CONFIGURATION_SEARCH.copy(query = "sort", context = null).encode().replace("\"navigationTree\":true,", "")
        assertFalse(SearchDomain.Config.decode(older)!!.navigationTree)
        val arrows = ArrowSearches(read = { mapOf(ArrowSearches.ARROW to older) }, write = { _, _ -> })
        assertTrue(arrows.of("Search", ArrowSearches.ARROW).navigationTree)
        // Turned off and stored, it stays off.
        val off = SearchDomain.CONFIGURATION_SEARCH.copy(navigationTree = false)
        assertEquals(off, SearchDomain.Config.decode(off.encode()))
    }
}
