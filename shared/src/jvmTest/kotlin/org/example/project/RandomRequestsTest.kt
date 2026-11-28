package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import org.example.project.scheduler.domain.ContextDefault
import org.example.project.scheduler.domain.ContextDomain
import org.example.project.scheduler.domain.SearchDomain
import org.example.project.scheduler.domain.SearchDomain.Kind
import org.example.project.scheduler.domain.SearchDomain.SortKey
import org.example.project.scheduler.domain.SearchDomain.SortMethod
import org.example.project.scheduler.model.AlarmEntry
import org.example.project.scheduler.state.ExternalDelta
import org.example.project.scheduler.state.ExternalKeys
import org.example.project.scheduler.state.HistoryCategory
import org.example.project.scheduler.state.HistoryUnit
import org.example.project.scheduler.state.SchedulerHistory
import org.example.project.scheduler.state.SchedulerState

/**
 * User 2026-11-27: *"Find out a completely random query, check if the user can get the correct result list, and if not fix
 * it."* Each request here is typed as a user would type it in a Search window — its types, its bars, "Where to search",
 * its sorting — and answered by the one [SearchDomain.results], no code of its own.
 */
class RandomRequestsTest {
    private val zone = TimeZone.UTC

    private fun at(y: Int, m: Int, d: Int): Long = LocalDateTime(y, m, d, 12, 0).toInstant(zone).toEpochMilliseconds()

    private fun search(state: SchedulerState, config: SearchDomain.Config) =
        SearchDomain.results(
            state, config.searchedKinds, config.query, filters = config.filters, sorts = config.sorts, timeZone = zone,
            match = config.filters.queryOptions, extraQueries = config.extraQueries, queryFormula = config.queryFormula,
        )

    private fun withUnits(units: List<HistoryUnit>) = SchedulerState.empty().let { s ->
        s.copy(histories = s.histories.withCategory(HistoryCategory.Main, SchedulerHistory(pointer = units.size - 1, units = units)))
    }

    /** A Search window's configuration changed from [before] to [after] at [time]. */
    private fun change(time: Long, before: SearchDomain.Config, after: SearchDomain.Config) =
        HistoryUnit(
            timeMillis = time,
            delta = ExternalDelta(ExternalKeys.SEARCH + "Search#2", before.encode(), after.encode(), "Search configuration"),
            deviceId = "desk",
            deviceSeq = time,
        )

    /**
     * Iteration 1: *"the list of all the rows with a check box the user checked on a Sunday"*. Each check box of a
     * check-box configuration is a row (a search configuration element, "Types › alarm"); when it was checked is a fact of it.
     * Types: search configuration; Where to search › Facts; bar `"Checked: on" sunday`.
     */
    @Test
    fun rows_with_a_check_box_checked_on_a_sunday() {
        val tasks = SearchDomain.Config(kinds = setOf(Kind.Task))
        val withAlarms = tasks.copy(kinds = setOf(Kind.Task, Kind.Alarm))
        val sunday = at(2026, 11, 22)
        val monday = at(2026, 11, 23)
        val state = withUnits(
            listOf(
                change(sunday, tasks, withAlarms), // checks "alarm" on a Sunday: found
                change(monday, withAlarms, withAlarms.copy(kinds = withAlarms.kinds + Kind.Timer)), // a Monday
                change(sunday + 60_000, withAlarms.copy(filters = SearchDomain.Filters(noParentOfKinds = setOf(Kind.Task))), withAlarms), // unchecked
                change(sunday + 120_000, withAlarms, withAlarms.copy(filters = SearchDomain.Filters(notificationSources = setOf(org.example.project.scheduler.state.NotificationSource.entries.first())))),
            ),
        )
        val config = SearchDomain.Config(
            kinds = setOf(Kind.SearchConfiguration),
            query = "\"Checked: on\" sunday",
            filters = SearchDomain.Filters(searchIn = setOf(SearchDomain.SearchField.Facts)),
        )
        val found = search(state, config).map(SearchDomain::keyOf).toSet()
        assertEquals(
            setOf(
                "SearchConfiguration/Types.Alarm",
                "SearchConfiguration/NotificationSourceSetting." + org.example.project.scheduler.state.NotificationSource.entries.first().name,
            ),
            found,
        )
        // The unit says it too, so the history units that checked a box are found the same way.
        val unit = SearchDomain.resolve(state, listOf(Kind.HistoryUnit.name + "/" + SearchDomain.historyUnitId(HistoryCategory.Main, state.histories.forCategory(HistoryCategory.Main).units[0])), { emptyMap() }).single()
        val types = (SearchDomain.homePathOf(SearchDomain.Setting.Types) + SearchDomain.Setting.Types.label).joinToString(" › ")
        assertEquals(listOf("on · Search#2 › $types › alarm"), SearchDomain.factsOf(state, unit, timeZone = zone).filter { it.first == "Checked" }.map { it.second })
    }

    /**
     * User 2026-11-28, the same request walked as a new user would: ▾ › Which elements › Date & calendar › **Checked on**,
     * Su ticked — nothing typed, no type chosen, no "Facts". A box unchecked on a Sunday, or checked on a Monday, is not
     * listed; the setting survives its stored shape, and a shape stored before it reads as any day.
     */
    @Test
    fun checked_on_a_sunday_is_one_tick_of_the_tree() {
        val tasks = SearchDomain.Config(kinds = setOf(Kind.Task))
        val withAlarms = tasks.copy(kinds = setOf(Kind.Task, Kind.Alarm))
        val sunday = at(2026, 11, 22)
        val monday = at(2026, 11, 23)
        val state = withUnits(
            listOf(
                change(sunday, tasks, withAlarms),
                change(monday, withAlarms, withAlarms.copy(kinds = withAlarms.kinds + Kind.Timer)),
                change(sunday + 60_000, withAlarms.copy(filters = SearchDomain.Filters(noParentOfKinds = setOf(Kind.Task))), withAlarms),
            ),
        )
        val config = SearchDomain.pageDefaultConfig("scheduler").let { it.copy(filters = it.filters.copy(checkedOnDays = setOf(kotlinx.datetime.DayOfWeek.SUNDAY))) }
        assertEquals(listOf("SearchConfiguration/Types.Alarm"), search(state, config).map(SearchDomain::keyOf))
        assertEquals(listOf("Which elements", "Date & calendar"), SearchDomain.homePathOf(SearchDomain.Setting.CheckedOnDays))
        assertEquals(config.filters.checkedOnDays, SearchDomain.Config.decode(config.encode())!!.filters.checkedOnDays)
        assertEquals(emptySet(), SearchDomain.Config.decode(SearchDomain.Config(kinds = setOf(Kind.Task)).encode().replace("\"checkedOnDays\":[]", ""))!!.filters.checkedOnDays)
    }

    /**
     * Iteration 2:*"every context where search configurations show a result list where the 3rd row has a title that starts
     * with the letter d and the 5th letter is h, sorted by the last character of their id"*. A context's first rows are facts
     * of it ("Row 3: Death"). Types: context; Where to search › Facts; regular expression `^Row 3: d...h`; Sort by: id, from
     * its last character.
     */
    @Test
    fun contexts_whose_third_row_reads_d___h_sorted_by_the_last_character_of_their_id() {
        val alarms = listOf(AlarmEntry(id = "alarm-1", label = "Alpha"), AlarmEntry(id = "alarm-2", label = "Beta"), AlarmEntry(id = "alarm-3", label = "Death"))
        val byName = SearchDomain.Config(kinds = setOf(Kind.Alarm), sorts = listOf(SortMethod(null, SortKey.Name)))
        val ids = ContextDomain.CONTEXTS.map { it.id }.filter { it != ContextDomain.SEARCH_WINDOW }.take(3)
        val state = SchedulerState.empty().copy(
            alarms = alarms,
            contextDefaults = listOf(
                ContextDefault(ids[0], byName.encode()), // Alpha, Beta, Death
                ContextDefault(ids[1], byName.encode()),
                ContextDefault(ids[2], byName.copy(sorts = listOf(SortMethod(null, SortKey.Name, descending = true))).encode()), // Death, Beta, Alpha
            ),
        )
        val config = SearchDomain.Config(
            kinds = setOf(Kind.Context),
            query = "^Row 3: d...h",
            filters = SearchDomain.Filters(searchIn = setOf(SearchDomain.SearchField.Facts), regex = true),
            sorts = listOf(SortMethod(null, SortKey.IdFromEnd)),
        )
        val found = search(state, config).map { (it as SearchDomain.ItemResult).id }
        // The two set to list "Death" third, not the one listing it first — and the code's own contexts that list it third.
        assertEquals(true, ids[0] in found && ids[1] in found && ids[2] !in found, found.toString())
        assertEquals(found.sortedBy { it.reversed() }, found)
    }
}
