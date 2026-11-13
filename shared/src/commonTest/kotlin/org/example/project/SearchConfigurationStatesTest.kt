package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.ElementTree
import org.example.project.scheduler.domain.SearchDomain
import org.example.project.scheduler.domain.SearchDomain.AddedAction
import org.example.project.scheduler.domain.SearchDomain.Kind
import org.example.project.scheduler.domain.SearchDomain.Setting
import org.example.project.scheduler.state.SchedulerState
import org.example.project.ui.ArrowSearches
import org.example.project.ui.listedArrowIds

/**
 * User rules 2026-11-13: a fifth category for the search configurations that have search configuration children and
 * change nothing themselves ("Recently changed"); an added search configuration with no such children shows its state
 * for each window id; the ▾ windows have ids while they are open or saved.
 */
class SearchConfigurationStatesTest {
    private val empty = SchedulerState.empty()

    private fun rows(query: String = "") = SearchDomain.itemResults(empty, Kind.SearchConfiguration, query)

    private fun key(id: String) = Kind.SearchConfiguration.name + "/" + id

    private fun actionsOf(vararg ids: String): Set<AddedAction> {
        val added = SearchDomain.resolve(empty, ids.map(::key), { emptyMap() })
        assertEquals(ids.size, added.size)
        return SearchDomain.actionsFor(SearchDomain.addedActions("", Kind.entries.toSet(), SearchDomain.actionKindsOf(added)), added)
            .flatMap { it.second }.toSet()
    }

    @Test
    fun theNodesOfTheTreeAreSearchConfigurationsOfTheFifthCategory() {
        val recent = rows("Recently changed").single()
        assertEquals(SearchDomain.CONFIGURATION_NODE_PREFIX + "recent", recent.id)
        assertEquals("group", recent.detail)
        val groups = rows().filter { it.detail == "group" }.map { it.name }
        assertTrue(groups.containsAll(listOf("Recently changed", "General", "Task Scheduler", "task")), groups.toString())
        // Every configuration that changes something is still a row, with one of the four.
        assertEquals(Setting.entries.size, rows().count { it.detail != "group" })
    }

    @Test
    fun aNodeHasItsSearchConfigurationsAsChildren() {
        val general = ElementTree.childrenOf(empty, key(SearchDomain.CONFIGURATION_NODE_PREFIX + "general"))
        assertTrue(key(Setting.ParentDistance.name) in general)
        assertEquals(Setting.entries.count { it.section == null }, general.size)
        // A page holds its types' nodes, which hold their configurations.
        val page = ElementTree.childrenOf(empty, key(SearchDomain.CONFIGURATION_NODE_PREFIX + "page/"))
        assertTrue(key(SearchDomain.CONFIGURATION_NODE_PREFIX + "kind/Task") in page)
        assertTrue(key(Setting.TaskSchedulable.name) in ElementTree.childrenOf(empty, key(SearchDomain.CONFIGURATION_NODE_PREFIX + "kind/Task")))
        // One that changes something has none; and what the tree makes a child is not taken out.
        assertEquals(emptyList(), ElementTree.childrenOf(empty, key(Setting.ParentDistance.name)))
        assertFalse(ElementTree.canUnlink(empty, key(SearchDomain.CONFIGURATION_NODE_PREFIX + "general"), key(Setting.ParentDistance.name)))
        // Every child is a row the list can show.
        assertEquals(general.size, SearchDomain.resolve(empty, general, { emptyMap() }).size)
    }

    @Test
    fun onlyAConfigurationWithNoChildrenShowsItsStateInEachWindow() {
        assertTrue(AddedAction.ConfigurationStates in actionsOf(Setting.ParentDistance.name))
        assertFalse(AddedAction.ConfigurationStates in actionsOf(SearchDomain.CONFIGURATION_NODE_PREFIX + "recent"))
        assertTrue(AddedAction.ConfigurationStates in actionsOf(SearchDomain.CONFIGURATION_NODE_PREFIX + "recent", Setting.MatchCase.name))
        val added = SearchDomain.resolve(empty, listOf(key(SearchDomain.CONFIGURATION_NODE_PREFIX + "recent"), key(Setting.MatchCase.name)), { emptyMap() })
        assertEquals(listOf(null, Setting.MatchCase), added.map(SearchDomain::settingOf))
        assertNull(SearchDomain.settingOf(SearchDomain.ItemResult(Kind.Option, Setting.MatchCase.name, "", "")))
    }

    @Test
    fun aDownArrowWindowHasAnIdWhileOpenOrSaved() {
        val arrows = ArrowSearches()
        assertEquals(emptyList(), arrows.listed())
        // Open: listed, with the configuration every ▾ window opens with.
        arrows.owned("Search▾", "Search")
        arrows.open["Search▾"] = true
        assertEquals(listOf("Search▾"), arrows.listed())
        assertEquals(SearchDomain.CONFIGURATION_SEARCH, arrows.of("Search▾"))
        // Closed with nothing of its own: no id any more.
        arrows.open.remove("Search▾")
        assertEquals(emptyList(), arrows.listed())
        // A configuration of its own: saved, open or not.
        arrows.set("Search▾", SearchDomain.CONFIGURATION_SEARCH.copy(query = "sort"))
        assertEquals(listOf("Search▾"), arrows.listed())
        assertEquals("sort", arrows.of("Search▾").query)
        // Put back as it opens: saved no more.
        arrows.set("Search▾", SearchDomain.CONFIGURATION_SEARCH)
        assertEquals(emptyList(), arrows.listed())
    }

    @Test
    fun aWindowWithItsOwnDownArrowWindowIsSavedToo() {
        val owners = mapOf("Search▾" to "Search", "Search▾/general" to "Search▾", "Search▾/general▾" to "Search▾/general")
        // The ▾ window of a category's pop-up holds a configuration: the two it hangs from have ids too, not the frame.
        assertEquals(
            listOf("Search▾", "Search▾/general", "Search▾/general▾"),
            listedArrowIds(open = emptySet(), saved = setOf("Search▾/general▾"), owners = owners),
        )
        assertEquals(listOf("Search#2▾"), listedArrowIds(setOf("Search#2▾"), emptySet(), mapOf("Search#2▾" to "Search#2")))
    }

    @Test
    fun theDownArrowWindowsOfAWindowAreForgottenWithIt() {
        val arrows = ArrowSearches()
        for (id in listOf("Search▾", "Search#2▾")) {
            arrows.owned(id, id.removeSuffix("▾"))
            arrows.set(id, SearchDomain.CONFIGURATION_SEARCH.copy(query = "x"))
        }
        arrows.forget("Search")
        assertEquals(listOf("Search#2▾"), arrows.listed())
    }
}
