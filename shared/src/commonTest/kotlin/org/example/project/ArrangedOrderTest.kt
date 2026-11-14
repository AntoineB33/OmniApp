package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import org.example.project.scheduler.domain.SearchDomain
import org.example.project.scheduler.domain.SearchDomain.ItemResult
import org.example.project.scheduler.domain.SearchDomain.Kind

/**
 * User rule 2026-11-14: "Arrange by dragging" works in any Search window — *"disable clicks on buttons and preserve the
 * order the user defines by dragging elements"*. A list other than the left-side menu keeps that order in
 * [SearchDomain.Config.arranged].
 */
class ArrangedOrderTest {
    private fun row(id: String) = ItemResult(Kind.Alarm, id, id, "")

    private val list = listOf(row("a"), row("b"), row("c"), row("d"))
    private fun keys(rows: List<SearchDomain.Result>) = rows.map { (it as ItemResult).id }

    @Test
    fun the_named_rows_come_first_in_the_dragged_order_and_the_others_follow() {
        val arranged = listOf("Alarm/c", "Alarm/a", "Alarm/gone")
        assertEquals(listOf("c", "a", "b", "d"), keys(SearchDomain.arrangedResults(list, arranged)))
        assertEquals(listOf("a", "b", "c", "d"), keys(SearchDomain.arrangedResults(list, emptyList())))
    }

    @Test
    fun a_drop_puts_the_moved_rows_before_the_row_in_their_order_and_keeps_what_is_not_shown() {
        val shown = list.map(SearchDomain::keyOf)
        assertEquals(
            listOf("Alarm/b", "Alarm/a", "Alarm/d", "Alarm/c", "Alarm/hidden"),
            SearchDomain.arrangedAfterDrop(shown, listOf("Alarm/hidden"), moved = listOf("Alarm/a", "Alarm/d"), beforeKey = "Alarm/c"),
        )
        assertEquals(
            listOf("Alarm/b", "Alarm/c", "Alarm/d", "Alarm/a"),
            SearchDomain.arrangedAfterDrop(shown, emptyList(), moved = listOf("Alarm/a"), beforeKey = null),
        )
    }

    /** Persisted-DB compatibility: a configuration written before the field loads with none; one with it keeps it. */
    @Test
    fun the_order_is_kept_and_a_configuration_written_before_it_loads_with_none() {
        val older = SearchDomain.Config(menuCustomizing = true).encode()
        assertFalse("arranged" in older, "none written while empty: the shape before it")
        assertEquals(emptyList(), SearchDomain.Config.decode(older)!!.arranged)
        val kept = SearchDomain.Config(menuCustomizing = true, arranged = listOf("Alarm/b", "Alarm/a"))
        assertEquals(kept, SearchDomain.Config.decode(kept.encode()))
    }
}
