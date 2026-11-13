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
import org.example.project.ui.arrowsWith
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

    /** A registry over one window's configuration, as `App` gives it: [windows] by id. */
    private fun registry(windows: MutableMap<String, SearchDomain.Config>) =
        ArrowSearches(
            read = { windows.getValue(it).arrows },
            write = { id, arrows -> windows[id] = windows.getValue(id).copy(arrows = arrows) },
        )

    @Test
    fun aDownArrowWindowHasAnIdWhileOpenOrSaved() {
        val windows = mutableMapOf("Search" to SearchDomain.Config())
        val arrows = registry(windows)
        assertEquals(emptyList(), arrows.listed("Search"))
        // Open: listed, with the configuration every ▾ window opens with.
        arrows.open["Search▾"] = true
        assertEquals(listOf("▾"), arrows.listed("Search"))
        assertEquals(SearchDomain.CONFIGURATION_SEARCH, arrows.of("Search", "▾"))
        // Closed with nothing of its own: no id any more.
        arrows.open.remove("Search▾")
        assertEquals(emptyList(), arrows.listed("Search"))
        // A configuration of its own: saved, open or not — kept WITH the window it hangs from.
        arrows.set("Search", "▾", SearchDomain.CONFIGURATION_SEARCH.copy(query = "sort"))
        assertEquals(listOf("▾"), arrows.listed("Search"))
        assertEquals("sort", arrows.of("Search", "▾").query)
        assertEquals(setOf("▾"), windows.getValue("Search").arrows.keys)
        // Put back as it opens: saved no more, and nothing left in the window's configuration.
        arrows.set("Search", "▾", SearchDomain.CONFIGURATION_SEARCH)
        assertEquals(emptyList(), arrows.listed("Search"))
        assertEquals(SearchDomain.Config(), windows.getValue("Search"))
    }

    @Test
    fun aWindowWithItsOwnDownArrowWindowIsSavedToo() {
        // The ▾ window of the ▾ window of a category's pop-up holds a configuration: every ▾ window it hangs from has an id.
        assertEquals(listOf("▾", "▾/general▾", "▾/general▾▾"), listedArrowIds(open = emptySet(), saved = setOf("▾/general▾▾")))
        assertEquals(listOf("▾"), listedArrowIds(setOf("▾"), emptySet()))
        // The actions section's ▾ window is its own: not the window's bar's.
        assertEquals(listOf("/actions▾"), listedArrowIds(setOf("/actions▾"), emptySet()))
        // One window's ▾ windows are not another's.
        val windows = mutableMapOf("Search" to SearchDomain.Config(), "Search#2" to SearchDomain.Config())
        val arrows = registry(windows)
        arrows.set("Search#2", "▾", SearchDomain.CONFIGURATION_SEARCH.copy(query = "x"))
        arrows.open["Search#2▾▾"] = true
        assertEquals(emptyList(), arrows.listed("Search"))
        assertEquals(listOf("▾", "▾▾"), arrows.listed("Search#2"))
    }

    /**
     * User rule 2026-11-13: *"the user closes the app without closing A, then when the app opens again A appears the same
     * as before, with the right changes in the search configurations"* — at every depth: the ▾ configurations are in A's
     * stored configuration, which is what its placement row holds.
     */
    @Test
    fun theDownArrowConfigurationsComeBackWithTheirWindow() {
        val windows = mutableMapOf("Search" to SearchDomain.Config(query = "tea"))
        val arrows = registry(windows)
        arrows.set("Search", "▾", SearchDomain.CONFIGURATION_SEARCH.copy(query = "sort"))
        arrows.set("Search", "▾▾", SearchDomain.CONFIGURATION_SEARCH.copy(filters = SearchDomain.Filters(matchCase = true)))
        val stored = windows.getValue("Search").encode()
        // The app restarts: the row is read again.
        val back = mutableMapOf("Search" to SearchDomain.Config.decode(stored)!!)
        val again = registry(back)
        assertEquals("tea", back.getValue("Search").query)
        assertEquals("sort", again.of("Search", "▾").query)
        assertTrue(again.of("Search", "▾▾").filters.matchCase)
        assertEquals(listOf("▾", "▾▾"), again.listed("Search"))
        // Written before 2026-11-13: none.
        assertEquals(emptyMap(), SearchDomain.Config.decode("""{"query":"tea"}""")!!.arrows)
    }

    /**
     * User rule 2026-11-13: a menu button's "Update" keeps them — they are part of the window's whole state, which the
     * button saves and compares; they are not the search, which is what brings a window back.
     */
    @Test
    fun aMenuButtonKeepsTheDownArrowConfigurationsAndTheSearchIsNotThem() {
        val plain = SearchDomain.Config()
        val changed = plain.copy(arrows = arrowsWith(emptyMap(), "▾", SearchDomain.CONFIGURATION_SEARCH.copy(query = "sort")))
        assertFalse(SearchDomain.sameSearchWindowState(plain.encode(), changed.encode()))
        assertTrue(SearchDomain.sameSearchWindowState(changed.encode(), SearchDomain.Config.decode(changed.encode())!!.encode()))
        assertTrue(SearchDomain.sameSearchConfigurations(plain.encode(), changed.encode()))
        assertEquals("sort", SearchDomain.Config.decode(SearchDomain.Config.decode(changed.encode())!!.arrows["▾"])!!.query)
    }
}
