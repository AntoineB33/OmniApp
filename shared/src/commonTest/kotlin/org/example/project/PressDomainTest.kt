package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.ContextDomain
import org.example.project.scheduler.domain.PressDomain
import org.example.project.scheduler.domain.PressDomain.Press
import org.example.project.scheduler.domain.SearchDomain
import org.example.project.scheduler.domain.SearchDomain.Kind
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.state.SchedulerState
import org.example.project.ui.SearchRowOpeners

/**
 * User rule 2026-11-26: *"Make sure each element has in the action section the behavior of when [it] is clicked, when it
 * is double-clicked etc…"* and its state for all contexts — the contexts grouped by what the presses do ([PressDomain]).
 */
class PressDomainTest {
    private val calendarMenu = "menu:calendar grid menu"

    @Test
    fun every_kind_has_the_action_and_says_every_press() {
        val action = SearchDomain.AddedAction.ElementPresses
        for (kind in Kind.entries) {
            val sections = SearchDomain.actionsFor(listOf(null to listOf(action)), listOf(SearchDomain.ItemResult(kind, "x", "x", "")))
            assertTrue(sections.any { action in it.second }, "$kind lists \"${action.label}\"")
            for (context in ContextDomain.CONTEXTS) {
                val presses = PressDomain.behaviours(kind, context.id, context.default).map { it.press }
                for (press in listOf(Press.Click, Press.DoubleClick, Press.RightClick, Press.Enter, Press.HeldPress, Press.Hover)) {
                    assertTrue(press in presses, "$kind in ${context.id} says its $press")
                }
            }
        }
    }

    @Test
    fun the_same_everywhere_is_one_group_arrange_by_dragging_in_one_context_makes_two() {
        val one = PressDomain.groups(emptyList(), Kind.Alarm)
        assertEquals(1, one.size)
        assertEquals(ContextDomain.CONTEXTS, one.single().contexts)

        val arranged = ContextDomain.written(
            emptyList(), ContextDomain.SEARCH_WINDOW, ContextDomain.configOf(emptyList(), ContextDomain.SEARCH_WINDOW).copy(menuCustomizing = true),
        )
        val two = PressDomain.groups(arranged, Kind.Alarm)
        assertEquals(listOf(listOf(ContextDomain.SEARCH_WINDOW)), two.take(1).map { g -> g.contexts.map { it.id } })
        assertEquals(ContextDomain.CONTEXTS.size - 1, two[1].contexts.size)
        assertTrue(two[0].behaviours.single { it.press == Press.HeldPress }.does.startsWith("drags"))
    }

    @Test
    fun a_menu_entry_runs_in_a_menu_and_is_picked_in_a_drop_down() {
        val groups = PressDomain.groups(emptyList(), Kind.Option)
        fun clickIn(id: String) = groups.single { g -> g.contexts.any { it.id == id } }.behaviours.single { it.press == Press.Click }.does
        assertTrue(clickIn(calendarMenu).startsWith("runs the entry"))
        assertTrue(clickIn(ContextDomain.DROP_DOWN).startsWith("picks it"))
        assertEquals("selects it", clickIn(ContextDomain.SEARCH_WINDOW))
    }

    /** What the action says opening a row does is what [SearchRowOpeners.open] does: something, or nothing. */
    @Test
    fun opening_said_matches_the_openers() {
        val state = SchedulerState.empty()
        for (kind in Kind.entries) {
            // A block's opening reads its block off the calendar, which an empty state has none of.
            if (kind == Kind.CalendarBlock) continue
            var called = false
            val mark: (Any?) -> Unit = { called = true }
            val openers = SearchRowOpeners(
                onOpenTaskEdit = mark, onOpenCategory = mark, onOpenPeriodKind = mark, onEditAlarmOrTimer = mark,
                onEditReminder = mark, onOpenHistoryUnit = mark, onOpenQuota = mark, onOpenTaskRelations = { called = true },
                onOpenShortcuts = { called = true }, onOpenWindow = mark, onCreate = mark, onOpenAppSetting = mark,
                onOpenCalendarAt = mark, onOpenCalendar = { called = true }, onApplyWorkplaceSetup = mark,
            )
            val row: SearchDomain.Result =
                if (kind == Kind.Task) SearchDomain.TaskResult(TaskId("t"), "t", emptyList(), false)
                else SearchDomain.ItemResult(kind, if (kind == Kind.Creation) Kind.Alarm.name else "x", "x", "")
            val withTask = if (kind == Kind.Task) state.copy(tasks = mapOf(TaskId("t") to org.example.project.scheduler.model.Task(TaskId("t"), "t"))) else state
            openers.open(withTask, row)
            assertEquals(PressDomain.opening(kind) != null, called, "$kind: said ${PressDomain.opening(kind)}")
        }
    }
}
