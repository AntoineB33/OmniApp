package org.example.project

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import org.example.project.scheduler.domain.ContextDefault
import org.example.project.scheduler.domain.ContextDomain
import org.example.project.scheduler.domain.SearchDomain
import org.example.project.scheduler.domain.SearchDomain.Kind
import org.example.project.scheduler.domain.SearchDomain.Setting
import org.example.project.scheduler.domain.SearchDomain.SortKey
import org.example.project.scheduler.domain.SearchDomain.SortMethod
import org.example.project.scheduler.state.ContextDefaultsDelta
import org.example.project.scheduler.state.HistoryCategory
import org.example.project.scheduler.state.HistoryUnit
import org.example.project.scheduler.state.SchedulerHistory
import org.example.project.scheduler.state.SchedulerState
import org.example.project.scheduler.state.changedConfigurations

/**
 * User 2026-11-27: *"What if the user wants to get a list of all the history units of user actions that changed 'default
 * search window > down arrow > Sorting > Sort by', sorted from recent to old, excluding all those made from a specific
 * phone, and excluding those made the 4th of october of every year?"* — and *"can you guarantee that any random request …
 * the user will be able to get the result list?"*. The answer is no filter of its own: every fact of a row is a
 * "label: value" line ([SearchDomain.factsOf]) the bars search ("Where to search › Facts"), and the bars' formula puts
 * them together. This is that request, typed as a user would.
 */
class FactsSearchTest {
    private val zone = TimeZone.UTC

    @AfterTest
    fun reset() {
        SearchDomain.deviceKinds = { emptyMap() }
    }

    private fun at(y: Int, m: Int, d: Int): Long = LocalDateTime(y, m, d, 12, 0).toInstant(zone).toEpochMilliseconds()

    /** A unit that set the default of [context] to [config], made on [device] at [time]. */
    private fun unit(time: Long, device: String, config: SearchDomain.Config, context: String = ContextDomain.SEARCH_WINDOW) =
        HistoryUnit(
            timeMillis = time,
            delta = ContextDefaultsDelta(emptyList(), listOf(ContextDefault(context, config.encode()))),
            deviceId = device,
            deviceSeq = time,
        )

    private val sortByName = SearchDomain.Config(sorts = listOf(SortMethod(null, SortKey.Name)))
    private val calendarMenu = ContextDomain.CONTEXTS.first { it.id != ContextDomain.SEARCH_WINDOW }.id

    private val units = listOf(
        unit(at(2026, 3, 1), "desk", sortByName), // kept
        unit(at(2026, 5, 1), "phone-1", sortByName), // made on the phone
        unit(at(2025, 10, 4), "desk", sortByName), // a 4th of October
        unit(at(2026, 6, 1), "desk", SearchDomain.Config(dropButtons = true)), // another configuration
        unit(at(2026, 7, 1), "desk", sortByName, context = calendarMenu), // another context
        unit(at(2026, 8, 1), "desk", sortByName), // kept, the most recent
    )

    private val state = SchedulerState.empty().let { s ->
        s.copy(histories = s.histories.withCategory(HistoryCategory.Main, SchedulerHistory(pointer = units.size - 1, units = units)))
    }

    @Test
    fun the_unit_says_which_configuration_it_changed_and_where() {
        val said = units[0].delta.changedConfigurations
        val path = (SearchDomain.homePathOf(Setting.SortResults) + Setting.SortResults.label).joinToString(" › ")
        assertEquals(listOf("default of a Search window › $path"), said)
        assertTrue(units[3].delta.changedConfigurations.single().endsWith(Setting.DropButtons.label), units[3].delta.changedConfigurations.toString())
    }

    @Test
    fun the_request_is_answered_by_three_bars_and_a_formula() {
        SearchDomain.deviceKinds = { mapOf("phone-1" to "phone", "desk" to "desktop") }
        val path = (SearchDomain.homePathOf(Setting.SortResults) + Setting.SortResults.label).joinToString(" › ")
        val config = SearchDomain.Config(
            kinds = setOf(Kind.HistoryUnit),
            // Bar 1: the configuration it changed, and where. Bar 2: made on the phone. Bar 3: on a 4th of October.
            query = "\"Changed configuration: default of a Search window › $path\"",
            extraQueries = listOf("Device phone", "Time -10-04"),
            queryFormula = "1 AND NOT 2 AND NOT 3",
            filters = SearchDomain.Filters(searchIn = setOf(SearchDomain.SearchField.Facts)),
            // From recent to old.
            sorts = listOf(SortMethod(Kind.HistoryUnit, SortKey.HistoryDate, descending = true)),
        )
        // The window comes back with it after a restart (kept by name: a configuration written before "Facts" loads as it was).
        assertEquals(config.filters.searchIn, SearchDomain.Config.decode(config.encode())!!.filters.searchIn)
        val found = SearchDomain.results(
            state, config.kinds, config.query, filters = config.filters, sorts = config.sorts, timeZone = zone,
            extraQueries = config.extraQueries, queryFormula = config.queryFormula,
        )
        val times = found.map { SearchDomain.historyUnitOf(state, (it as SearchDomain.ItemResult).id)!!.timeMillis }
        assertEquals(listOf(at(2026, 8, 1), at(2026, 3, 1)), times, found.toString())
    }

    /** What is searched is what is copied: the unit's deep copy holds the same lines. */
    @Test
    fun what_is_searched_is_what_a_deep_copy_says() {
        SearchDomain.deviceKinds = { mapOf("phone-1" to "phone") }
        val row = SearchDomain.resolve(state, listOf(Kind.HistoryUnit.name + "/" + SearchDomain.historyUnitId(HistoryCategory.Main, units[1])), { emptyMap() }).single()
        val facts = SearchDomain.factsOf(state, row, timeZone = zone).toMap()
        assertEquals("phone-1, phone", facts["Device"])
        assertTrue(facts.getValue("Time").startsWith("2026-05-01 12:00"), facts.toString())
        val copy = SearchDomain.elementsCopyText(state, listOf(SearchDomain.keyOf(row)), 1, includeIds = false, excludeTitle = "")
        assertTrue("- Device: phone-1, phone" in copy && "- Changed configuration: default of a Search window" in copy, copy)
    }
}
