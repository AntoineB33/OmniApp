package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.SearchDomain
import org.example.project.scheduler.state.HistoryCategory
import org.example.project.scheduler.state.SchedulerHistory
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState

/**
 * Anomaly 2026-10-03 (account3): double-clicking the only row of a history-unit Search window again and again grew the
 * added elements. A row's id was the unit's INDEX in its stack; the stack was full, so every new unit — adding an
 * element is one — evicted the front and shifted every index: the same row came back under a new key, and the old key
 * named another unit. A row's id is now the unit's identity ([SearchDomain.historyUnitId]).
 */
class HistoryUnitSearchIdTest {

    private fun committedUnit(): SchedulerState {
        val s = SchedulerState.empty()
        return SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(s.lists[s.rootListId]!!.cellIds[0], "Write"))
    }

    /** [state] with its Main stack holding [count] units, the i-th committed at instant `i`. */
    private fun withMainUnits(state: SchedulerState, from: Int, count: Int): SchedulerState {
        val template = state.histories.forCategory(HistoryCategory.Main).units.first()
        val units = (from until from + count).map { i ->
            template.copy(timeMillis = i.toLong(), deviceSeq = SchedulerReducer.deviceSeqOf(i.toLong(), 0))
        }
        return state.copy(histories = state.histories.withCategory(HistoryCategory.Main, SchedulerHistory(units.size - 1, units)))
    }

    private fun mainRows(state: SchedulerState) =
        SearchDomain.results(state, setOf(SearchDomain.Kind.HistoryUnit), "")
            .filterIsInstance<SearchDomain.ItemResult>()
            .filter { it.id.startsWith(HistoryCategory.Main.name + "#") }

    @Test
    fun a_row_keeps_its_id_and_its_unit_when_the_full_stack_evicts_its_front() {
        val base = committedUnit()
        val before = withMainUnits(base, from = 0, count = 1000)
        val row = mainRows(before).single { SearchDomain.historyUnitOf(before, it.id)?.timeMillis == 500L }

        // One more unit: the front one is evicted, every index shifts by one.
        val after = withMainUnits(base, from = 1, count = 1000)
        val same = mainRows(after).single { SearchDomain.historyUnitOf(after, it.id)?.timeMillis == 500L }
        assertEquals(row.id, same.id, "the same unit is the same row")
        assertEquals(500L, SearchDomain.historyUnitOf(after, row.id)?.timeMillis, "the key still names the unit added")

        // So adding it again adds nothing.
        val key = SearchDomain.keyOf(row)
        assertEquals(listOf(key), SearchDomain.withAdded(listOf(key), listOf(SearchDomain.keyOf(same))))
    }

    @Test
    fun an_evicted_unit_is_named_by_no_row_and_resolves_to_nothing() {
        val base = committedUnit()
        val before = withMainUnits(base, from = 0, count = 1000)
        val first = mainRows(before).single { SearchDomain.historyUnitOf(before, it.id)?.timeMillis == 0L }
        val after = withMainUnits(base, from = 1, count = 1000)
        assertNull(SearchDomain.historyUnitOf(after, first.id), "an evicted unit's key never names the unit that took its place")
        assertNull(org.example.project.ui.historyUnitEntryOfSearchId(after, first.id))
    }

    @Test
    fun the_lookup_follows_the_histories_it_is_asked_about() {
        val a = withMainUnits(committedUnit(), from = 0, count = 3)
        val b = withMainUnits(committedUnit(), from = 10, count = 3)
        val idA = mainRows(a).first().id
        assertSame(a.histories.forCategory(HistoryCategory.Main).units.first(), SearchDomain.historyUnitOf(a, idA))
        assertNull(SearchDomain.historyUnitOf(b, idA), "a map built for one histories value is never read for another")
    }

    @Test
    fun a_stored_configuration_drops_the_index_shaped_keys_of_older_builds() {
        val unit = SearchDomain.Kind.HistoryUnit.name
        val stored = SearchDomain.Config(added = listOf("Task/t1", "$unit/Main#12", "$unit/Main#dev#42")).encode()
        val decoded = SearchDomain.Config.decode(stored)!!
        assertEquals(listOf("Task/t1", "$unit/Main#dev#42"), decoded.added)
        assertTrue(SearchDomain.isLegacyHistoryUnitKey("$unit/Main#12"))
        assertFalse(SearchDomain.isLegacyHistoryUnitKey("$unit/Main#dev#42"))
    }
}
