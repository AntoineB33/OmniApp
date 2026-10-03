package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.SearchDomain
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState
import org.example.project.ui.historyEntryInfos
import org.example.project.ui.historyUnitEntryOfSearchId

/**
 * User rule 2026-10-03: a history unit's row in the Search window no longer opens the History window — it is added, and
 * its "Information" action shows everything the History window showed of it: the row's facts (category, position,
 * current / applied / undone, window, time) and the information window's (label, chrono id, every detail line).
 */
class HistoryUnitInformationTest {

    private fun r(state: SchedulerState, intent: SchedulerIntent) = SchedulerReducer.reduce(state, intent)

    @Test
    fun the_history_unit_kind_has_its_information_action() {
        val sections = SearchDomain.addedActions("", setOf(SearchDomain.Kind.HistoryUnit))
        assertTrue(
            sections.any { (kind, actions) -> kind == SearchDomain.Kind.HistoryUnit && SearchDomain.AddedAction.HistoryInformation in actions },
        )
    }

    @Test
    fun the_information_holds_every_fact_the_history_window_showed() {
        var s = SchedulerState.empty()
        s = r(s, SchedulerIntent.SetCellTitle(s.lists[s.rootListId]!!.cellIds[0], "Write"))
        val row =
            SearchDomain.results(s, setOf(SearchDomain.Kind.HistoryUnit), "").filterIsInstance<SearchDomain.ItemResult>().first()
        val entry = assertNotNull(historyUnitEntryOfSearchId(s, row.id))
        val infos = historyEntryInfos(entry).associate { it.label to it.value }
        for (label in listOf("Label", "Category", "Position", "State", "Window", "Time", "Chrono id")) {
            assertTrue(label in infos, "the information lacks $label: ${infos.keys}")
        }
        assertEquals(entry.unit.delta.label, infos["Label"])
        assertEquals("current", infos["State"], "the only unit is at the pointer")
        assertEquals(entry.unit.delta.details.size, infos.keys.count { it.startsWith("Detail ") })

        // Undone: the state follows, as the History window's row did.
        s = r(s, SchedulerIntent.Undo)
        val undone = historyEntryInfos(historyUnitEntryOfSearchId(s, row.id)!!).associate { it.label to it.value }
        assertEquals("undone", undone["State"])
    }
}
