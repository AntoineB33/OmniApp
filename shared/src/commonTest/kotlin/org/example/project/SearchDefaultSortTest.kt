package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.example.project.claudelogs.ClaudeLogCatalog
import org.example.project.claudelogs.ClaudeLogEntry
import org.example.project.claudelogs.ClaudeLogKind
import org.example.project.claudelogs.ClaudeLogSession
import org.example.project.scheduler.domain.SearchDomain
import org.example.project.scheduler.domain.SearchDomain.Config
import org.example.project.scheduler.domain.SearchDomain.Kind
import org.example.project.scheduler.model.AlarmEntry
import org.example.project.scheduler.state.SchedulerState

/**
 * User rule 2026-10-30: a creation row has two types (the creation kind and the kind it makes); a search configuration
 * may have a DEFAULT sorting — one kind and nothing else puts its creation row at the top — which the Sort by's "Default"
 * button applies, and which every new configuration takes until the user changes the sorting.
 */
class SearchDefaultSortTest {

    private val state = SchedulerState.empty().copy(alarms = listOf(AlarmEntry(id = "a1", label = "Wake")))

    private fun keys(rows: List<SearchDomain.Result>) = rows.map { SearchDomain.keyOf(it) }

    @Test
    fun aCreationRowIsListedUnderTheKindItMakes() {
        val rows = SearchDomain.results(state, setOf(Kind.Alarm), "")
        assertTrue(SearchDomain.keyOf(SearchDomain.ItemResult(Kind.Creation, Kind.Alarm.name, "New alarm", "")) in keys(rows))
        assertEquals(1, rows.count { it.kind == Kind.Creation })
        // Both kinds checked: listed once.
        val both = SearchDomain.results(state, setOf(Kind.Alarm, Kind.Creation), "")
        assertEquals(1, both.count { it.kind == Kind.Creation && (it as SearchDomain.ItemResult).id == Kind.Alarm.name })
        // A kind that is not checked brings no creation row.
        assertTrue(SearchDomain.results(state, setOf(Kind.Category), "").none { (it as? SearchDomain.ItemResult)?.id == Kind.Alarm.name })
    }

    @Test
    fun oneKindAndNothingElseHasTheCreationFirstDefault() {
        assertEquals(SearchDomain.CREATION_FIRST_SORTS, SearchDomain.defaultSortsOf(Config(kinds = setOf(Kind.Alarm))))
        // Added elements are not the search.
        assertEquals(SearchDomain.CREATION_FIRST_SORTS, SearchDomain.defaultSortsOf(Config(kinds = setOf(Kind.Alarm), added = listOf("Alarm/a1"))))
        assertNull(SearchDomain.defaultSortsOf(Config(kinds = setOf(Kind.Alarm, Kind.Timer))))
        assertNull(SearchDomain.defaultSortsOf(Config(kinds = setOf(Kind.Alarm), query = "w")))
        assertNull(SearchDomain.defaultSortsOf(Config(kinds = setOf(Kind.Alarm), filters = SearchDomain.Filters(alarmState = SearchDomain.AlarmState.On))))
        // A kind the user cannot make has no creation row to put first.
        assertNull(SearchDomain.defaultSortsOf(Config(kinds = setOf(Kind.HistoryUnit))))
        assertNull(SearchDomain.defaultSortApplied(Config(kinds = setOf(Kind.HistoryUnit))))
    }

    @Test
    fun theDefaultPutsTheCreationRowAtTheTop() {
        val rows = SearchDomain.results(state, setOf(Kind.Alarm), "", sorts = SearchDomain.CREATION_FIRST_SORTS)
        assertEquals(Kind.Creation, rows.first().kind)
        assertEquals(Kind.Alarm, rows.last().kind)
    }

    @Test
    fun aNewConfigurationTakesItsDefaultWhileTheSortingFollowsIt() {
        val opened = Config(kinds = setOf(Kind.Alarm, Kind.Timer))
        val oneKind = SearchDomain.withDefaultSortFollowed(opened, opened.copy(kinds = setOf(Kind.Alarm)))
        assertEquals(SearchDomain.CREATION_FIRST_SORTS, oneKind.sorts)
        assertTrue(oneKind.sortFollowsDefault)
        // A configuration with no default keeps the sorting it has.
        val typed = SearchDomain.withDefaultSortFollowed(oneKind, oneKind.copy(query = "w"))
        assertEquals(SearchDomain.CREATION_FIRST_SORTS, typed.sorts)
        // Another one-kind configuration: its default again.
        val other = SearchDomain.withDefaultSortFollowed(typed, typed.copy(query = "", kinds = setOf(Kind.Timer)))
        assertEquals(SearchDomain.CREATION_FIRST_SORTS, other.sorts)
    }

    @Test
    fun theUsersSortingStopsTheFollowingUntilDefaultIsClicked() {
        val start = Config(kinds = setOf(Kind.Alarm, Kind.Timer))
        val byName = listOf(SearchDomain.SortMethod(null, SearchDomain.SortKey.Name))
        val sorted = SearchDomain.withDefaultSortFollowed(start, start.copy(sorts = byName))
        assertFalse(sorted.sortFollowsDefault)
        val oneKind = SearchDomain.withDefaultSortFollowed(sorted, sorted.copy(kinds = setOf(Kind.Alarm)))
        assertEquals(byName, oneKind.sorts)
        // "Default": the default sorting, followed again.
        val clicked = SearchDomain.withDefaultSortFollowed(oneKind, SearchDomain.defaultSortApplied(oneKind)!!)
        assertEquals(SearchDomain.CREATION_FIRST_SORTS, clicked.sorts)
        assertTrue(clicked.sortFollowsDefault)
        val back = SearchDomain.withDefaultSortFollowed(clicked, clicked.copy(kinds = setOf(Kind.Timer)))
        assertEquals(SearchDomain.CREATION_FIRST_SORTS, back.sorts)
    }

    @Test
    fun theFollowingIsKeptAndAnOlderPayloadReadsAsFollowing() {
        val config = Config(kinds = setOf(Kind.Alarm), sorts = SearchDomain.CREATION_FIRST_SORTS, sortFollowsDefault = false)
        assertEquals(config, Config.decode(config.encode()))
        // Written before 2026-10-30: no `sortFollowsDefault`.
        val older = Config.decode("""{"query":"","kinds":["Alarm"],"sortMethods":[{"key":"Name"}]}""")!!
        assertTrue(older.sortFollowsDefault)
        assertEquals(listOf(SearchDomain.SortMethod(null, SearchDomain.SortKey.Name)), older.sorts)
    }

    private val newestFirst = listOf(SearchDomain.SortMethod(null, SearchDomain.SortKey.Date, descending = true))

    @Test
    fun theClaudeLogsPageSortsByOneDateCommonToItsThreeKinds() {
        val page = SearchDomain.claudeLogsConfig()
        assertEquals(newestFirst, page.sorts)
        assertEquals(newestFirst, SearchDomain.defaultSortsOf(page))
        assertEquals(newestFirst, SearchDomain.defaultSortsOf(page.copy(kinds = setOf(Kind.ClaudeAnswer))))
        assertNull(SearchDomain.defaultSortsOf(page.copy(query = "gradle")))
        // The same window on both pages: the logs' kinds alone have that default wherever it stands.
        assertEquals(newestFirst, SearchDomain.defaultSortsOf(Config(kinds = page.kinds)))
        assertTrue(SearchDomain.SortKey.Date in SearchDomain.sortKeysOf(null))
        assertTrue(SearchDomain.sortKeysOf(Kind.ClaudePrompt).none { it.kind != null })

        // One method orders the three kinds together, newest first.
        val session = ClaudeLogSession(
            "s.jsonl", "s", "f", null, null,
            listOf(
                ClaudeLogEntry(ClaudeLogKind.Prompt, "first", 1_000L, null, 0),
                ClaudeLogEntry(ClaudeLogKind.Reasoning, "second", 2_000L, null, 1),
                ClaudeLogEntry(ClaudeLogKind.Answer, "third", 3_000L, null, 2),
                ClaudeLogEntry(ClaudeLogKind.Prompt, "fourth", 4_000L, null, 3),
            ),
        )
        val rows = SearchDomain.results(
            state, page.kinds, "", sorts = page.sorts, claudeLogs = ClaudeLogCatalog("dir", listOf(session)),
        )
        assertEquals(listOf("fourth", "third", "second", "first"), rows.map { it.name })
    }

    @Test
    fun theThreeStackedDateMethodsOfAnOlderBuildReadAsTheOne() {
        val older = Config.decode(
            """{"kinds":["ClaudePrompt","ClaudeAnswer","ClaudeReasoning"],"page":"ClaudeLogs","sortMethods":[""" +
                """{"kind":"ClaudePrompt","key":"PromptDate","descending":true},""" +
                """{"kind":"ClaudeAnswer","key":"AnswerDate","descending":true},""" +
                """{"kind":"ClaudeReasoning","key":"ReasoningDate","descending":true}]}""",
        )!!
        assertEquals(newestFirst, older.sorts)
        assertEquals(older, Config.decode(older.encode()))
    }
}
