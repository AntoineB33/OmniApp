package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.SearchBarFormula
import org.example.project.scheduler.domain.SearchDomain
import org.example.project.scheduler.domain.SearchDomain.Config
import org.example.project.scheduler.domain.SearchDomain.Kind
import org.example.project.scheduler.domain.TaskTreeSearch
import org.example.project.scheduler.model.AlarmEntry
import org.example.project.scheduler.state.SchedulerState

/**
 * User rule 2026-11-06: the Search window's configurations have "Use regular expression", and a group where the user
 * adds other search bars and puts them together by a formula — *"“main search bar” AND (“search bar 2” OR “search bar
 * 3”)"*.
 */
class SearchBarsAndRegexTest {
    private val state =
        SchedulerState.empty().copy(
            alarms = listOf("Wake up early", "Wake up late", "Gym early", "Lunch").mapIndexed { i, label -> AlarmEntry(id = "a$i", label = label) },
        )

    private fun names(config: Config): List<String> =
        SearchDomain.results(
            state, setOf(Kind.Alarm), config.query, filters = config.filters, match = config.filters.queryOptions,
            extraQueries = config.extraQueries, queryFormula = config.queryFormula,
        ).map { it.name }.sorted()

    @Test
    fun the_users_own_formula_reads() {
        val f = "“main search bar” AND (“search bar 2” OR “search bar 3”)"
        assertNull(SearchBarFormula.problem(f, 3))
        // main and (2 or 3)
        assertTrue(SearchBarFormula.evaluate(f, 3) { it == 0 || it == 2 })
        assertFalse(SearchBarFormula.evaluate(f, 3) { it == 0 })
        assertFalse(SearchBarFormula.evaluate(f, 3) { it == 1 || it == 2 })
        // By number, with NOT, and two bars side by side are an AND.
        assertTrue(SearchBarFormula.evaluate("1 AND NOT 2", 2) { it == 0 })
        assertFalse(SearchBarFormula.evaluate("1 2", 2) { it == 0 })
        assertTrue(SearchBarFormula.evaluate("1 or 2", 2) { it == 1 })
    }

    @Test
    fun a_blank_or_unreadable_formula_is_every_bar_at_once() {
        assertTrue(SearchBarFormula.evaluate("", 2) { true })
        assertFalse(SearchBarFormula.evaluate("", 2) { it == 0 })
        assertNotNull(SearchBarFormula.problem("1 AND (2", 2))
        assertNotNull(SearchBarFormula.problem("1 OR 4", 3), "there is no bar 4")
        assertFalse(SearchBarFormula.evaluate("1 OR (2", 2) { it == 0 }, "unreadable: ANDed")
    }

    @Test
    fun the_bars_and_their_formula_choose_the_rows() {
        val config = Config(query = "early", extraQueries = listOf("wake", "gym"), queryFormula = "1 AND (2 OR 3)")
        assertEquals(listOf("Gym early", "Wake up early"), names(config))
        assertEquals(emptyList(), names(config.copy(queryFormula = "")), "blank: every bar — none holds all three")
        assertEquals(listOf("Wake up early"), names(config.copy(extraQueries = listOf("wake"), queryFormula = "")))
        assertEquals(listOf("Gym early", "Lunch", "Wake up early"), names(config.copy(queryFormula = "NOT 2 OR 1")))
        // A blank added bar asks nothing.
        assertEquals(listOf("Gym early", "Wake up early"), names(Config(query = "early", extraQueries = listOf(""))))
    }

    @Test
    fun use_regular_expression_reads_the_bar_as_one() {
        val regex = SearchDomain.Filters(regex = true)
        assertEquals(listOf("Wake up early", "Wake up late"), names(Config(query = "^wake.*(early|late)$", filters = regex)))
        assertEquals(emptyList(), names(Config(query = "^wake", filters = regex.copy(matchCase = true))), "case kept")
        assertEquals(emptyList(), names(Config(query = "(", filters = regex)), "an invalid expression matches nothing")
        // What it matches is what is highlighted.
        assertEquals(listOf(0..3), SearchDomain.matchRanges("Wake up", "w.ke", TaskTreeSearch.Options(regex = true)))
        // The added bars read as expressions too.
        assertEquals(
            listOf("Gym early"),
            names(Config(query = "early$", extraQueries = listOf("^g"), filters = regex)),
        )
    }

    @Test
    fun a_configuration_stored_before_them_reads_with_none() {
        val older = Config(query = "x").encode()
        assertFalse(older.contains("\"regex\":true"))
        val decoded = Config.decode(older.replace(",\"regex\":false", "").replace(",\"extraQueries\":[]", "").replace(",\"queryFormula\":\"\"", ""))!!
        assertEquals(false, decoded.filters.regex)
        assertEquals(emptyList(), decoded.extraQueries)
        assertEquals("", decoded.queryFormula)
        val round = Config(query = "a", extraQueries = listOf("b"), queryFormula = "1 OR 2", filters = SearchDomain.Filters(regex = true))
        assertEquals(round, Config.decode(round.encode()))
    }
}
