package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.ElementLink
import org.example.project.scheduler.domain.ElementTree
import org.example.project.scheduler.domain.SearchDomain
import org.example.project.scheduler.domain.SearchDomain.Kind
import org.example.project.scheduler.domain.SearchDomain.Setting
import org.example.project.scheduler.domain.SearchDomain.SettingRole
import org.example.project.scheduler.model.ChronoEntry
import org.example.project.scheduler.state.SchedulerState
import org.example.project.ui.openEndRows

/**
 * User rule 2026-11-13: the **parent distance** of the "List of parents" (1 by default: the direct parents), picked in a
 * list of **integers** with no end; the **four categories** of search configuration; and the configurations a context is
 * about, first in the configurations tree.
 */
class ParentDistanceTest {
    private fun key(id: String) = "Chrono/$id"

    /** a under b under c under d, and a under x too. */
    private val chain: SchedulerState =
        SchedulerState.empty().copy(
            chronos = listOf("a", "b", "c", "d", "x").map { ChronoEntry(id = it, label = it) },
            elementLinks = listOf("b" to "a", "c" to "b", "d" to "c", "x" to "a").map { (parent, child) ->
                ElementLink(ElementTree.linkId(key(parent), key(child)), key(parent), key(child))
            },
        )

    private fun listed(distance: Int?): Set<String> {
        val base = SearchDomain.parentsListConfig(listOf(key("a")))
        val config = if (distance == null) base else base.copy(filters = base.filters.copy(parentDistance = distance))
        return SearchDomain.results(chain, config.searchedKinds, "", filters = config.filters).mapTo(HashSet(), SearchDomain::keyOf)
    }

    @Test
    fun theListOfParentsShowsTheDirectParentsByDefault() {
        assertEquals(1, SearchDomain.parentsListConfig(listOf(key("a"))).filters.parentDistance)
        assertEquals(setOf(key("b"), key("x")), listed(null))
    }

    @Test
    fun aHigherDistanceListsTheParentsFurtherAboveToo() {
        assertEquals(setOf(key("b"), key("x"), key("c")), listed(2))
        assertEquals(setOf(key("b"), key("x"), key("c"), key("d")), listed(3))
        // Past the highest there is, nothing more; "highest" is every parent at any distance.
        assertEquals(listed(3), listed(40))
        assertEquals(listed(3), listed(SearchDomain.PARENT_DISTANCE_HIGHEST))
    }

    @Test
    fun theHighestParentDistanceIsHowManyLevelsStandAbove() {
        assertEquals(3, ElementTree.highestParentDistance(chain, listOf(key("a"))))
        assertEquals(1, ElementTree.highestParentDistance(chain, listOf(key("c"))))
        assertEquals(0, ElementTree.highestParentDistance(chain, listOf(key("d"))))
    }

    @Test
    fun anElementStandsAtItsShortestDistanceAndALoopEnds() {
        val parents = mapOf("a" to setOf("b", "c"), "b" to setOf("c"), "c" to setOf("a"))
        assertEquals(listOf(setOf("b", "c"), setOf("a")), ElementTree.parentLevels(parents, listOf("a")))
    }

    @Test
    fun theDistanceIsKeptWithTheConfigurationAndAnOlderOneReadsOne() {
        val config = SearchDomain.Config(filters = SearchDomain.Filters(parentsOf = setOf(key("a")), parentDistance = 4))
        assertEquals(4, SearchDomain.Config.decode(config.encode())!!.filters.parentDistance)
        val highest = config.copy(filters = config.filters.copy(parentDistance = SearchDomain.PARENT_DISTANCE_HIGHEST))
        assertEquals(SearchDomain.PARENT_DISTANCE_HIGHEST, SearchDomain.Config.decode(highest.encode())!!.filters.parentDistance)
        // Written before 2026-11-13: no distance.
        assertEquals(1, SearchDomain.Config.decode("""{"parentsOf":["Chrono/a"]}""")!!.filters.parentDistance)
        assertEquals(SearchDomain.PARENT_DISTANCE_HIGHEST, SearchDomain.Config.decode("""{"parentDistance":-3}""")!!.filters.parentDistance)
    }

    @Test
    fun theDistanceIsAFilterOnlyWhereParentsAreAsked() {
        assertFalse(SearchDomain.Filters(parentDistance = 3).isOn(Setting.ParentDistance))
        assertFalse(SearchDomain.Filters(parentsOf = setOf(key("a"))).isOn(Setting.ParentDistance))
        assertTrue(SearchDomain.Filters(parentsOf = setOf(key("a")), parentDistance = 3).isOn(Setting.ParentDistance))
    }

    @Test
    fun theListOfIntegersStartsWithTheHighestThenCountsFromOne() {
        val rows = SearchDomain.integerResults("highest parent distance", 5, 7)
        assertEquals(listOf("highest parent distance (5)", "1", "2", "3", "4", "5", "6", "7"), rows.map { it.name })
        assertEquals(setOf(Kind.Integer), rows.mapTo(HashSet()) { it.kind })
        assertEquals(SearchDomain.PARENT_DISTANCE_HIGHEST, SearchDomain.parentDistanceOf(rows.first().id))
        assertEquals(listOf(1, 2, 3), rows.drop(1).take(3).map { SearchDomain.parentDistanceOf(it.id) })
        assertNull(SearchDomain.parentDistanceOf("0"))
        assertNull(SearchDomain.parentDistanceOf("select-all"))
    }

    @Test
    fun theBarFindsIntegersByTheirDigits() {
        assertEquals(listOf("12", "112", "120"), SearchDomain.integerResults("highest", 5, 3, "12").map { it.name })
        assertEquals(listOf("highest (5)"), SearchDomain.integerResults("highest", 5, 3, "high").map { it.name })
        assertEquals(listOf("highest (5)", "5", "15"), SearchDomain.integerResults("highest", 5, 2, "5").map { it.name })
    }

    @Test
    fun theAccountHoldsNoInteger() {
        assertEquals(emptyList(), SearchDomain.itemResults(SchedulerState.empty(), Kind.Integer, ""))
        assertTrue(SearchDomain.isPickedRow(Kind.Integer) && SearchDomain.isPickedRow(Kind.Option))
        assertFalse(SearchDomain.isPickedRow(Kind.Task))
    }

    @Test
    fun theThumbCountsTheHighestThenWhatWasScrolledTo() {
        // Every row of an ordinary list.
        assertEquals(51, openEndRows(null, firstShown = 0, lastShown = 3, total = 51))
        // "highest (40)" and 1..40: forty-one rows, while the user has not gone near them.
        assertEquals(41, openEndRows(41, firstShown = 0, lastShown = 7, total = 51))
        // Anomaly 2026-11-14: the rows that mean something fit on screen — a screen more is counted, so there is a thumb.
        assertEquals(16, openEndRows(3, firstShown = 0, lastShown = 7, total = 51))
        // Scrolled past them: as far as the user went, and a screen more.
        assertEquals(29, openEndRows(6, firstShown = 13, lastShown = 20, total = 51))
        assertEquals(51, openEndRows(6, firstShown = 73, lastShown = 80, total = 51))
    }

    @Test
    fun everySearchConfigurationHasOneOfTheFourCategoriesButReset() {
        assertEquals(listOf(Setting.ResetSearch), Setting.entries.filter { SearchDomain.roleOf(it) == null })
        // The fifth is a node's of the tree: no configuration that changes something has it.
        assertEquals(SettingRole.entries.toSet() - SettingRole.Group, Setting.entries.mapNotNullTo(HashSet(), SearchDomain::roleOf))
        for (setting in Setting.entries.filter { it.sorts }) assertEquals(SettingRole.Sort, SearchDomain.roleOf(setting), setting.name)
        // Whatever narrows the list is a filter: every one the filters can say is on.
        val on = SearchDomain.Filters(parentsOf = setOf(key("a")), parentDistance = 2, matchCase = true)
        for (setting in Setting.entries.filter(on::isOn)) assertEquals(SettingRole.Filter, SearchDomain.roleOf(setting), setting.name)
        assertEquals(SettingRole.Filter, SearchDomain.roleOf(Setting.ParentDistance))
        assertEquals(SettingRole.Display, SearchDomain.roleOf(Setting.DropButtons))
        assertEquals(SettingRole.Children, SearchDomain.roleOf(Setting.ExpandResults))
        // A row of the search over search configurations says its category.
        val row = SearchDomain.itemResults(SchedulerState.empty(), Kind.SearchConfiguration, "Parent distance").single()
        assertEquals("general · filter", row.detail)
    }

    @Test
    fun theTreeOfTheListOfParentsStartsWithTheParentDistance() {
        val opened = SearchDomain.parentsListConfig(listOf(key("a")))
        val first = SearchDomain.configurationTree(opened, opened).first() as SearchDomain.ConfigurationCategory
        assertEquals("context", first.id)
        val settings = first.children.map { (it as SearchDomain.ConfigurationLeaf).setting }
        assertEquals(listOf(Setting.ParentDistance, Setting.ParentsOf, Setting.DropButtons), settings)
    }

    @Test
    fun aWindowOpenedOnNothingHasNoContextNode() {
        assertEquals(emptyList(), SearchDomain.contextSettings(null))
        assertEquals(emptyList(), SearchDomain.contextSettings(SearchDomain.Config()))
        assertEquals("recent", (SearchDomain.configurationTree(SearchDomain.Config(), SearchDomain.Config()).first() as SearchDomain.ConfigurationCategory).id)
        // Opened on a filter: that filter.
        assertEquals(listOf(Setting.TaskSchedulable), SearchDomain.contextSettings(SearchDomain.resilienceSearchConfig("sleep")))
    }
}
