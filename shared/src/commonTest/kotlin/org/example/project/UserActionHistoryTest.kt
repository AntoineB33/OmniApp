package org.example.project

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.PeriodDrawing
import org.example.project.scheduler.domain.PeriodKinds
import org.example.project.scheduler.model.CellId
import org.example.project.scheduler.model.TaskPanel
import org.example.project.scheduler.persistence.SchedulerStateCodec
import org.example.project.scheduler.state.ExternalRestore
import org.example.project.scheduler.state.HistoryCategory
import org.example.project.scheduler.state.HistoryUnit
import org.example.project.scheduler.state.HistoryWindow
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState

/**
 * User rule 2026-10-01: *"There must be history units for almost every user action"* — the anomaly was an element
 * added to a Search window's list, which recorded nothing. A change to what `App` keeps (a Search window's
 * configuration, a window's layout, the menu's buttons) is an external unit put back through
 * [SchedulerState.externalRestores]; an account setting (a kind of period, its drawing and rules, a category, the two
 * switches) is a settings unit. Both are undone from the window they were made in, and both survive the store.
 */
class UserActionHistoryTest {
    private var stamped = false

    @BeforeTest
    fun stampWindows() {
        stamped = SchedulerReducer.stampsWindow
        SchedulerReducer.stampsWindow = true
    }

    @AfterTest
    fun restore() {
        SchedulerReducer.stampsWindow = stamped
    }

    private fun r(state: SchedulerState, intent: SchedulerIntent) = SchedulerReducer.reduce(state, intent)

    private fun freeRootCell(state: SchedulerState): CellId = state.lists[state.rootListId]!!.cellIds.last()

    private fun main(state: SchedulerState): List<HistoryUnit> = state.histories.forCategory(HistoryCategory.Main).units

    @Test
    fun adding_an_element_to_a_search_windows_list_is_a_unit_undone_and_redone_through_app() {
        var s = r(SchedulerState.empty(), SchedulerIntent.FocusWindow(HistoryWindow.Search, "#2"))
        s = r(s, SchedulerIntent.RecordExternal("search/Search#2", "{\"added\":[]}", "{\"added\":[\"Task/t\"]}", "Add to the added elements"))
        val unit = main(s).last()
        assertEquals("Add to the added elements", unit.delta.label)
        assertEquals(HistoryWindow.Search, unit.window)
        // Undone from the Search window: the value goes back to App, which puts it back while it is still the unit's.
        val undone = r(s, SchedulerIntent.Undo)
        assertEquals(
            ExternalRestore(1, "search/Search#2", from = "{\"added\":[\"Task/t\"]}", to = "{\"added\":[]}"),
            undone.externalRestores.single(),
        )
        val redone = r(undone, SchedulerIntent.Redo)
        assertEquals(ExternalRestore(2, "search/Search#2", "{\"added\":[]}", "{\"added\":[\"Task/t\"]}"), redone.externalRestores.last())
        // Nothing changed is nothing recorded.
        assertEquals(s, r(s, SchedulerIntent.RecordExternal("menu", "x", "x", "Menu buttons")))
    }

    @Test
    fun a_field_typed_into_is_one_unit_for_the_run_of_its_keystrokes() {
        var s = SchedulerState.empty()
        for ((before, after) in listOf("" to "a", "a" to "ab", "ab" to "abc")) {
            s = r(s, SchedulerIntent.RecordExternal("search/Search", before, after, "Search text", coalesceKey = "search/Search/query"))
        }
        assertEquals(1, main(s).size)
        val undone = r(s, SchedulerIntent.Undo)
        assertEquals(ExternalRestore(1, "search/Search", from = "abc", to = ""), undone.externalRestores.single())
    }

    @Test
    fun the_account_settings_are_units_and_undoing_one_puts_it_back() {
        var s = SchedulerState.empty()
        s = r(s, SchedulerIntent.AddPeriodKind("deep work"))
        assertEquals("New period", main(s).last().delta.label)
        s = r(s, SchedulerIntent.SetPeriodDrawing("deep work", PeriodDrawing.Zigzags))
        s = r(s, SchedulerIntent.SetNotificationVoice(false))
        assertEquals(3, main(s).size)
        s = r(s, SchedulerIntent.Undo)
        assertTrue(s.notificationVoiceEnabled)
        s = r(s, SchedulerIntent.Undo)
        assertTrue(s.periodKindConfig.drawing("deep work") != PeriodDrawing.Zigzags)
        s = r(s, SchedulerIntent.Undo)
        assertTrue("deep work" !in s.allPeriodKinds)
        s = r(s, SchedulerIntent.Redo)
        assertTrue("deep work" in s.allPeriodKinds)
    }

    @Test
    fun deleting_a_period_is_one_unit_and_undoing_it_brings_back_the_tasks_values_and_its_periods() {
        var s = r(SchedulerState.empty(), SchedulerIntent.SetCellTitle(freeRootCell(SchedulerState.empty()), "Read"))
        val read = s.tasks.values.first { it.title == "Read" }.id
        s = r(s, SchedulerIntent.AddPeriodKind("deep work"))
        s = r(s, SchedulerIntent.SetTaskResilience(read, "deep work", 0.5))
        s = s.copy(panels = s.panels + TaskPanel("panel/deep", null, "deep work", 0L, 3_600_000L, periodKind = "deep work"))
        val before = main(s).size
        s = r(s, SchedulerIntent.RemovePeriodKind("deep work"))
        assertEquals(before + 1, main(s).size)
        assertTrue(s.panels.none { it.periodKind == "deep work" })
        val undone = r(s, SchedulerIntent.Undo)
        assertTrue("deep work" in undone.allPeriodKinds)
        assertEquals(0.5, undone.tasks[read]!!.resilienceFor("deep work"))
        assertTrue(undone.panels.any { it.id == "panel/deep" })
    }

    @Test
    fun a_renamed_category_is_one_unit_however_many_keystrokes() {
        var s = r(SchedulerState.empty(), SchedulerIntent.CreateCategory("H"))
        val id = s.categories.single().id
        for (title in listOf("Ho", "Hom", "Home")) s = r(s, SchedulerIntent.RenameCategory(id, title))
        assertEquals(2, main(s).size, "the creation, then the rename")
        assertEquals("H", r(s, SchedulerIntent.Undo).categories.single().title)
    }

    @Test
    fun both_new_kinds_of_unit_survive_the_store() {
        var s = r(SchedulerState.empty(), SchedulerIntent.CreateCategory("Home"))
        s = r(s, SchedulerIntent.AddPeriodKind("deep work"))
        s = r(s, SchedulerIntent.SetNotificationsEnabled(false))
        s = r(s, SchedulerIntent.RecordExternal("window/Search", "0.0;0.0;0.0;0.0;false;false;false;false", "1.0;2.0;0.0;0.0;true;false;false;false", "Open window"))
        val back = assertNotNull(SchedulerStateCodec.decode(SchedulerStateCodec.encode(s)))
        assertEquals(main(s).map { it.delta.label }, main(back).map { it.delta.label })
        // And what they undo is the same after the round trip.
        var a = s
        var b = back
        repeat(4) {
            a = r(a, SchedulerIntent.Undo)
            b = r(b, SchedulerIntent.Undo)
        }
        assertEquals(a.categories, b.categories)
        assertEquals(a.allPeriodKinds, b.allPeriodKinds)
        assertEquals(a.notificationsEnabled, b.notificationsEnabled)
        assertEquals(a.externalRestores.map { it.key to it.to }, b.externalRestores.map { it.key to it.to })
        assertTrue(a.categories.isEmpty() && "deep work" !in a.allPeriodKinds && a.notificationsEnabled)
    }

    @Test
    fun the_restores_are_never_stored() {
        val s = r(r(SchedulerState.empty(), SchedulerIntent.RecordExternal("menu", "a", "b", "Menu buttons")), SchedulerIntent.Undo)
        assertTrue(s.externalRestores.isNotEmpty())
        assertTrue(assertNotNull(SchedulerStateCodec.decode(SchedulerStateCodec.encode(s))).externalRestores.isEmpty())
    }
}
