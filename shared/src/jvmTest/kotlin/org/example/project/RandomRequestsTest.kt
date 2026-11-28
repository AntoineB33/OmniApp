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
     * User 2026-11-28: *"The flat list after the tree structure was to enable the user to find any search configuration by
     * its title with the search bar"* — and a new user types their own words. In the ▾'s first pop-up: "checked on sunday"
     * is "Checked on" alone (its name, and its Su chip said "sunday"); "sunday" lists the three day filters.
     */
    @Test
    fun the_arrow_bar_finds_checked_on_by_the_words_of_the_request() {
        val tree = SearchDomain.configurationTree(SearchDomain.Config(kinds = Kind.entries.toSet()))
        val rows = SearchDomain.ConfigurationTreeRows(tree)
        fun typed(q: String) = SearchDomain.shownConfigurationNodes(SchedulerState.empty(), tree, root = true, rows, SearchDomain.CONFIGURATION_SEARCH.copy(query = q))
            .filterIsInstance<SearchDomain.ConfigurationLeaf>().map { it.setting }
        assertEquals(listOf(SearchDomain.Setting.CheckedOnDays), typed("checked on sunday"))
        assertEquals(setOf(SearchDomain.Setting.DatedOnDays, SearchDomain.Setting.CheckedOnDays, SearchDomain.Setting.AlarmDays), typed("sunday").toSet())
        assertEquals(true, SearchDomain.Setting.CheckedOnDays in typed("checked"))
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

    /**
     * User 2026-11-28, the same request walked as a new user would, nothing typed but two letters: ▾ › Which elements ›
     * Types › context; ▾ › Find by text › **Letters at places**, In "row 3 of its list", box 1 "d", box 5 "h"; ▾ › Order ›
     * Sort by › "id, from its last character". The same contexts as the regular expression, any capitals; a context whose
     * row 3 is "Dash" (too short) or "Delta" (no h fifth) is not listed; the setting survives its stored shape, and a shape
     * stored before it reads as no letters asked.
     */
    @Test
    fun contexts_whose_third_row_reads_d___h_by_boxes_of_the_tree() {
        val alarms = listOf(AlarmEntry(id = "alarm-1", label = "Alpha"), AlarmEntry(id = "alarm-2", label = "Beta"), AlarmEntry(id = "alarm-3", label = "Death"))
        val byName = SearchDomain.Config(kinds = setOf(Kind.Alarm), sorts = listOf(SortMethod(null, SortKey.Name)))
        val ids = ContextDomain.CONTEXTS.map { it.id }.filter { it != ContextDomain.SEARCH_WINDOW }.take(3)
        val state = SchedulerState.empty().copy(
            alarms = alarms,
            contextDefaults = listOf(
                ContextDefault(ids[0], byName.encode()),
                ContextDefault(ids[1], byName.encode()),
                ContextDefault(ids[2], byName.copy(sorts = listOf(SortMethod(null, SortKey.Name, descending = true))).encode()),
            ),
        )
        val boxes = SearchDomain.Config(
            kinds = setOf(Kind.Context),
            filters = SearchDomain.Filters(lettersIn = "row 3", letters = mapOf(1 to 'd', 5 to 'H')),
            sorts = listOf(SortMethod(null, SortKey.IdFromEnd)),
        )
        val regex = SearchDomain.Config(
            kinds = setOf(Kind.Context),
            query = "^Row 3: d...h",
            filters = SearchDomain.Filters(searchIn = setOf(SearchDomain.SearchField.Facts), regex = true),
            sorts = listOf(SortMethod(null, SortKey.IdFromEnd)),
        )
        val found = search(state, boxes).map { (it as SearchDomain.ItemResult).id }
        assertEquals(true, ids[0] in found && ids[1] in found && ids[2] !in found, found.toString())
        assertEquals(search(state, regex).map { (it as SearchDomain.ItemResult).id }, found)
        assertEquals(found.sortedBy { it.reversed() }, found)
        // Each box at its place, any capitals; a text too short answers nothing.
        assertEquals(true, SearchDomain.lettersAnswer(mapOf(1 to 'd', 5 to 'h'), "Death"))
        assertEquals(false, SearchDomain.lettersAnswer(mapOf(1 to 'd', 5 to 'h'), "Dash"))
        assertEquals(false, SearchDomain.lettersAnswer(mapOf(1 to 'd', 5 to 'h'), "Delta"))
        // Where a new user looks: under "Find by text" first, and under "Which elements" beside Types.
        assertEquals(listOf("Find by text"), SearchDomain.homePathOf(SearchDomain.Setting.LettersAt))
        assertEquals(true, SearchDomain.Setting.LettersAt in SearchDomain.CONFIGURATION_LAYOUT.first { it.id == "which" }.items)
        // Stored and read back; an older build's shape reads as no letters, its name.
        val decoded = SearchDomain.Config.decode(boxes.encode())!!.filters
        assertEquals("row 3" to mapOf(1 to 'd', 5 to 'H'), decoded.lettersIn to decoded.letters)
        val old = SearchDomain.Config.decode(SearchDomain.Config(kinds = setOf(Kind.Task)).encode().replace(Regex(""","?"letters(In)?":(\{\}|null|"[^"]*")"""), ""))!!.filters
        assertEquals(SearchDomain.LETTERS_IN_NAME to emptyMap(), old.lettersIn to old.letters)
    }

    /** The ▾'s bar finds "Letters at places" by the request's own words. */
    @Test
    fun the_arrow_bar_finds_letters_at_places_by_the_words_of_the_request() {
        val tree = SearchDomain.configurationTree(SearchDomain.Config(kinds = Kind.entries.toSet()))
        val rows = SearchDomain.ConfigurationTreeRows(tree)
        fun typed(q: String) = SearchDomain.shownConfigurationNodes(SchedulerState.empty(), tree, root = true, rows, SearchDomain.CONFIGURATION_SEARCH.copy(query = q))
            .filterIsInstance<SearchDomain.ConfigurationLeaf>().map { it.setting }
        assertEquals(listOf(SearchDomain.Setting.LettersAt), typed("5th letter").distinct())
        assertEquals(true, SearchDomain.Setting.LettersAt in typed("starts with"))
        assertEquals(true, SearchDomain.Setting.LettersAt in typed("letter"))
    }
}
