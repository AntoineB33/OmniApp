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
import org.example.project.scheduler.state.SchedulerState

/**
 * User rule 2026-11-04: *"add a downward arrow at the right of the search bar. When hovering over it, it opens a pop-up
 * tree, where an end node is a search configuration, and a not-end node is a search configuration category revealing
 * its children when the user hovers over it. The first element of the root gives all the search configurations sorted
 * from more to less recently changed. All the search configurations must be found in this tree."*
 *
 * User rule 2026-11-14: *"it must be easy with a very intuitive navigation in the tree of search configurations …
 * directly have the children of 'task scheduler', and then an element called something like 'other feature pages'"*.
 */
class ConfigurationTreeTest {
    private fun leavesOf(nodes: List<ConfigurationNode>): List<Setting> =
        nodes.flatMap { node ->
            when (node) {
                is ConfigurationLeaf -> listOf(node.setting)
                is ConfigurationCategory -> leavesOf(node.children)
                is SearchDomain.ConfigurationOther -> emptyList()
            }
        }

    private fun categoriesOf(nodes: List<ConfigurationNode>): List<ConfigurationCategory> =
        nodes.filterIsInstance<ConfigurationCategory>().filterNot { it.id.startsWith(SearchDomain.FLAT_NODE_PREFIX) }
            .flatMap { listOf(it) + categoriesOf(it.children) }

    /** The nodes listing configurations away from their own place: "Recent", "Currently on", "Relevant here", "Default for this", the flat list. */
    private fun ConfigurationCategory.elsewhere() =
        id == "recent" || id.startsWith("recent/") || id == "context" || id == "on" || id == "default" || id.startsWith(SearchDomain.FLAT_NODE_PREFIX)

    /** How many categories are hovered from the root to reach [setting], the nodes listing it away from its place left out. */
    private fun hoversTo(tree: List<ConfigurationNode>, setting: Setting): Int? {
        fun depth(nodes: List<ConfigurationNode>, at: Int): Int? =
            nodes.mapNotNull { node ->
                when (node) {
                    is ConfigurationLeaf -> at.takeIf { node.setting == setting }
                    is ConfigurationCategory -> depth(node.children, at + 1)
                    is SearchDomain.ConfigurationOther -> null
                }
            }.minOrNull()
        return depth(tree.filter { it is ConfigurationCategory && !it.elsewhere() }, 0)
    }

    @Test
    fun every_configuration_is_in_the_tree_under_a_category_of_its_own() {
        for (page in SearchDomain.FEATURE_PAGES) {
            val tree = SearchDomain.configurationTree(SearchDomain.Config(page = page.id))
            // User rule 2026-11-14: the rows that open a pop-up first, then every configuration itself, each once —
            // so a name typed in the first pop-up's field leaves the configuration right there.
            val firstLeaf = tree.indexOfFirst { it is ConfigurationLeaf }
            assertTrue(firstLeaf > 0 && tree.take(firstLeaf).all { it is ConfigurationCategory }, "the rows that open a pop-up first")
            assertTrue(tree.drop(firstLeaf).all { it is ConfigurationLeaf }, "the configurations last")
            val flat = tree.drop(firstLeaf).map { (it as ConfigurationLeaf).setting }
            assertEquals(Setting.entries.toSet(), flat.toSet(), page.label)
            assertEquals(Setting.entries.size, flat.size)
            assertEquals(flat.size, tree.drop(firstLeaf).map { it.label }.toSet().size, "no two read the same")
            assertEquals(
                listOf(Setting.MatchCase),
                tree.filterConfigurationNodes(Setting.MatchCase.label).filterIsInstance<ConfigurationLeaf>().map { it.setting },
            )
            // ...and every one of them but Reset, a command, is under a category too, the two first left out.
            assertEquals(Setting.entries.toSet() - Setting.ResetSearch, leavesOf(tree.take(firstLeaf).filterIsInstance<ConfigurationCategory>().filterNot { it.elsewhere() }).toSet(), page.label)
            // User rule 2026-11-16: every category below the root is in the flat list too, by its path.
            val flatCategories = tree.filterIsInstance<ConfigurationCategory>().filter { it.id.startsWith(SearchDomain.FLAT_NODE_PREFIX) }
            val below = tree.filterIsInstance<ConfigurationCategory>().filterNot { it.elsewhere() }.flatMap { categoriesOf(it.children) }
                .filterNot { it.label == SearchDomain.DEFAULT_NODE_LABEL }
            assertEquals(below.map { SearchDomain.FLAT_NODE_PREFIX + it.id }.toSet(), flatCategories.map { it.id }.toSet(), page.label)
            assertEquals(tree.size, tree.map { it.label }.toSet().size, "no two rows of the root read the same")
            // None is empty.
            for (node in categoriesOf(tree)) {
                assertTrue(node.children.isNotEmpty(), node.label)
                // No two rows of one pop-up read the same: a "Sort by" among other types' says its type.
                assertEquals(node.children.size, node.children.map { it.label }.toSet().size, node.label)
            }
            val ids = categoriesOf(tree).map { it.id }
            assertEquals(ids.size, ids.toSet().size, "ids unique")
        }
    }

    /**
     * User rule 2026-11-28 (the layout the user gave): the root's categories in its order; the other pages under one node,
     * the window's own page — another than the Task Scheduler — out of it, at the root.
     */
    @Test
    fun the_root_holds_the_layouts_categories_and_the_other_pages_under_one_node() {
        val scheduler = SearchDomain.configurationTree(SearchDomain.pageDefaultConfig("")).filterIsInstance<ConfigurationCategory>()
            .filterNot { it.id.startsWith(SearchDomain.FLAT_NODE_PREFIX) || it.id == "recent" }
        assertEquals(
            listOf("Find by text", "Which elements", "Order", "Display", "Tasks & targets", "Calendar", "Alarms, timers & alerts", "App & history", "Other pages"),
            scheduler.map { it.label },
        )
        val others = scheduler.single { it.id == SearchDomain.OTHER_PAGES_NODE }
        assertEquals(listOf("Claude Code logs", "Settings"), others.children.map { it.label })
        // The logs page: its own category at the root, before "Other pages", which holds the Settings page alone.
        val logs = SearchDomain.configurationTree(SearchDomain.pageDefaultConfig(SearchDomain.CLAUDE_LOGS_PAGE)).filterIsInstance<ConfigurationCategory>()
        val logsNode = logs.single { it.id == "page/" + SearchDomain.CLAUDE_LOGS_PAGE }
        assertEquals(SearchDomain.CLAUDE_KINDS.map { "kind/" + it.name }, logsNode.children.filterIsInstance<ConfigurationCategory>().map { it.id })
        assertEquals(listOf("Settings"), logs.single { it.id == SearchDomain.OTHER_PAGES_NODE }.children.map { it.label })
    }

    /**
     * User rule 2026-11-28, the use cases walked through the layout: a configuration is reached in two or three hovers,
     * and stands wherever a user could look for it.
     */
    @Test
    fun what_the_user_is_after_is_a_few_hovers_from_the_root() {
        val tree = SearchDomain.configurationTree(SearchDomain.pageDefaultConfig(""))
        fun places(setting: Setting) = categoriesOf(tree).filter { c -> !c.elsewhere() && c.children.any { (it as? ConfigurationLeaf)?.setting == setting } }.map { it.label }
        // "Display all the root cells of every tree"; "only the running timers"; "match the case"; "only tasks and alarms".
        assertEquals(listOf("Tasks"), places(Setting.TaskTreeRoot))
        assertEquals(listOf("Timers"), places(Setting.TimerStateSetting))
        assertEquals(listOf("Matching"), places(Setting.MatchCase))
        assertEquals(listOf("Which elements"), places(Setting.Types))
        // "The parents of these elements"; "what is on the calendar on Monday": in each place a user would look.
        assertEquals(listOf("By parent or child"), places(Setting.ParentDistance))
        assertEquals(listOf("Date & calendar", "Calendar"), places(Setting.CalendarAt))
        assertEquals(listOf("Tasks", "Tasks on calendar"), places(Setting.TaskOnCalendar))
        for (setting in Setting.entries - Setting.ResetSearch) {
            val hovers = hoversTo(tree, setting)
            assertTrue(hovers != null && hovers <= 3, "${setting.name}: $hovers")
            // Its home is one of the ways to it: never deeper than the nearest.
            assertTrue(hovers!! <= SearchDomain.homePathOf(setting).size, setting.name)
        }
    }

    /** User rule 2026-11-16: what is on now is "Currently on", after "Recent", each saying the way to its place. */
    @Test
    fun what_is_on_now_is_currently_on() {
        // Every type checked: none set (user rule 2026-11-26), the filter alone on.
        val config = SearchDomain.Config(kinds = SearchDomain.Kind.entries.toSet(), filters = SearchDomain.Filters(taskTreeRoot = SearchDomain.Tri.Yes))
        val tree = SearchDomain.configurationTree(config)
        assertEquals(listOf("recent", "on"), tree.take(2).map { (it as ConfigurationCategory).id })
        val on = tree[1] as ConfigurationCategory
        assertEquals("Currently on", on.label)
        assertEquals(listOf("Tasks & targets › Tasks › Root of a tree"), on.children.map { it.label })
        assertEquals(listOf(listOf("Tasks & targets", "Tasks")), on.children.map { (it as ConfigurationLeaf).path })
        // Nothing on: no such node.
        assertTrue(SearchDomain.configurationTree(SearchDomain.Config(kinds = SearchDomain.Kind.entries.toSet())).none { it is ConfigurationCategory && it.id == "on" })
    }

    /** The pop-up of "General" is gone with its node (2026-11-14): what a window kept under it is dropped on load. */
    @Test
    fun what_was_kept_for_the_pop_up_of_general_is_dropped_on_load() {
        val kept = SearchDomain.CONFIGURATION_SEARCH.copy(query = "sort").encode()
        val older = SearchDomain.Config(inside = mapOf("▾/general" to kept, "/actions▾/general▾" to kept, "▾" to kept, "▾/kind/Task" to kept)).encode()
        assertEquals(setOf("▾", "▾/kind/Task"), SearchDomain.Config.decode(older)!!.inside.keys)
    }

    @Test
    fun the_first_node_is_every_configuration_from_the_most_to_the_least_recently_changed() {
        var config = SearchDomain.Config()
        config = SearchDomain.withChanged(config, config.copy(query = "a"), listOf(Setting.SearchText), 100)
        config = SearchDomain.withChanged(config, config.copy(filters = config.filters.copy(matchCase = true)), listOf(Setting.MatchCase), 200)
        val first = SearchDomain.configurationTree(config).first() as ConfigurationCategory
        assertEquals("Recent", first.label)
        assertEquals("recent", (SearchDomain.configurationTree(SearchDomain.Config()).first() as ConfigurationCategory).id)
        // User rule 2026-11-16: "Here" — what was changed in this window, the last first, each with its path.
        val here = first.children.first() as ConfigurationCategory
        assertEquals("Here", here.label)
        assertEquals(listOf("Find by text › Matching › Match case", "Find by text › Search text"), here.children.map { it.label })
        // Nothing changed here: no "Here".
        assertTrue((SearchDomain.configurationTree(SearchDomain.Config()).first() as ConfigurationCategory).children.none { it.label == "Here" })
        // User rule 2026-11-27: the list of every configuration under a node of its own, in parts of fifteen at most.
        val order = first.recentOrder()
        assertEquals(listOf(Setting.MatchCase, Setting.SearchText), order.take(2))
        assertEquals(Setting.entries.toSet(), order.toSet(), "all of them")
        assertEquals(Setting.entries.filter { it != Setting.MatchCase && it != Setting.SearchText }, order.drop(2), "the others in their order")
        // A change that changes nothing stamps nothing.
        assertEquals(config, SearchDomain.withChanged(config, config, listOf(Setting.Types), 300))
    }

    /**
     * User rule 2026-11-16: *"if somewhere in the app the search bar by default matches case, then in down arrow > search
     * > matching > default for this, the user can find matches case"* — what the window was opened on is its default:
     * "Default for this" at the root, and in each group the part of it in that group.
     */
    @Test
    fun what_is_on_by_default_is_found_in_default_for_this() {
        val opened = SearchDomain.Config(kinds = SearchDomain.Kind.entries.toSet(), filters = SearchDomain.Filters(matchCase = true), menuCustomizing = true)
        val tree = SearchDomain.configurationTree(opened.copy(menuCustomizing = false), opened)
        val byDefault = tree.filterIsInstance<ConfigurationCategory>().single { it.id == "default" }
        assertEquals(SearchDomain.DEFAULT_NODE_LABEL, byDefault.label)
        // Every setting it opened with, its own included (anomaly 2026-11-19: "Menu keeps open rows" was in "Currently on"
        // and not here — "Default for this" kept a shorter list of the window's own settings than "Currently on").
        assertEquals(listOf(Setting.MatchCase, Setting.MenuCustomizing), byDefault.children.map { (it as ConfigurationLeaf).setting })
        val search = tree.filterIsInstance<ConfigurationCategory>().single { it.id == "find" }
        assertEquals("Find by text", search.label)
        val matching = search.children.filterIsInstance<ConfigurationCategory>().single { it.label == "Matching" }
        assertEquals(listOf("Match case", "Match whole word", "Use regular expression", SearchDomain.DEFAULT_NODE_LABEL), matching.children.map { it.label })
        val matchingDefault = matching.children.last() as ConfigurationCategory
        assertEquals(listOf(Setting.MatchCase), matchingDefault.children.map { (it as ConfigurationLeaf).setting })
        // ...and in "Find by text" itself, last.
        assertEquals(SearchDomain.DEFAULT_NODE_LABEL, search.children.last().label)
        // A category none of whose configurations is on by default has none.
        val sorting = tree.filterIsInstance<ConfigurationCategory>().single { it.id == "calendar" }
        assertTrue(sorting.children.none { it.label == SearchDomain.DEFAULT_NODE_LABEL })
        // What is on now is "Currently on", not the default.
        assertEquals(listOf(Setting.MatchCase), tree.filterIsInstance<ConfigurationCategory>().single { it.id == "on" }.children.map { (it as ConfigurationLeaf).setting })
    }

    /**
     * User rule 2026-11-14: *"this search configuration is something relevant for the left-side menu, so it should be
     * accessible intuitively in the down arrow of the left-side menu"* — what a window is about leads "Relevant here",
     * off as well as on, and a window about nothing has no such node while nothing is on.
     */
    @Test
    fun what_a_window_is_about_leads_relevant_here_even_off() {
        val menu = org.example.project.ui.LATERAL_MENU_SEARCH
        val about = SearchDomain.configurationTree(menu, menu, org.example.project.ui.LATERAL_MENU_RELEVANT)
            .filterIsInstance<ConfigurationCategory>().single { it.id == "context" }
        assertEquals("Relevant here", about.label)
        assertEquals(Setting.MenuCustomizing, (about.children.first() as ConfigurationLeaf).setting)
        assertEquals("Arrange by dragging", Setting.MenuCustomizing.label)
        assertTrue(SearchDomain.configurationTree(menu, menu).none { it is ConfigurationCategory && it.id == "context" })
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
        val level = first.recentLeaves()
        val narrowed = level.filterConfigurationNodes(needle)
        assertTrue(narrowed.isNotEmpty())
        assertTrue(narrowed.all { (it as ConfigurationLeaf).label.contains(needle, ignoreCase = true) })
        assertEquals(level, level.filterConfigurationNodes(""), "an empty field narrows nothing")
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
            when (it) { is ConfigurationLeaf -> listOf(it.setting); is ConfigurationCategory -> leaves(it.children); is SearchDomain.ConfigurationOther -> emptyList() }
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
    /**
     * User rule 2026-11-11: *"this is the search configuration of the search configuration of the default search
     * window"* — the arrow in a pop-up's bar opens the configuration of the search that pop-up is: its type, the
     * search configuration alone, and every configuration one of its elements.
     */
    @Test
    fun the_tree_is_a_search_over_search_configurations() {
        val level = SearchDomain.CONFIGURATION_SEARCH
        assertEquals(setOf(SearchDomain.Kind.SearchConfiguration), level.searchedKinds)
        assertEquals("", level.query)
        val rows = SearchDomain.results(SchedulerState.empty(), setOf(SearchDomain.Kind.SearchConfiguration), "")
        // User rule 2026-11-13: the tree's nodes are search configurations too (the fifth category), beside every setting.
        val nodes = SearchDomain.configurationCategories().map { SearchDomain.CONFIGURATION_NODE_PREFIX + it.id }
        // User request 2026-11-27: and each check box of a check-box configuration, a configuration element of its own.
        val checkBoxes = SearchDomain.checkBoxOptionRows().map { it.id }
        assertEquals((Setting.entries.map { it.name } + nodes + checkBoxes).toSet(), rows.map { (it as SearchDomain.ItemResult).id }.toSet())
        // Its search text finds configurations by name, as the pop-up's field does.
        val found = SearchDomain.results(SchedulerState.empty(), setOf(SearchDomain.Kind.SearchConfiguration), Setting.MatchCase.label)
        assertTrue(found.isNotEmpty() && found.all { it.name.contains(Setting.MatchCase.label, ignoreCase = true) })
    }
}
