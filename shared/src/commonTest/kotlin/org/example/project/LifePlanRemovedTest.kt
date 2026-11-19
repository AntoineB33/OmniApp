package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.example.project.scheduler.persistence.SchedulerStateCodec
import org.example.project.scheduler.state.HistoryWindow
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState
import org.example.project.ui.CustomMenuButtons

/**
 * User rule 2026-11-19: *"Remove Life plan"* — and its data with it. What an older build wrote still loads: the state's
 * `lifePlan` is read past and written no more (so the next save and the next sync drop it, the server's rows deleted as
 * any row no longer in the state), a history unit of it is skipped as a newer build's unknown unit is, a unit or a focus
 * stamped with its window reads as no window, and a menu that kept its button loses it.
 */
class LifePlanRemovedTest {

    private fun account(): SchedulerState {
        var s = SchedulerState.empty()
        val cell = s.lists[s.rootListId]!!.cellIds.last()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(cell, "Apple"))
        return s
    }

    @Test
    fun a_state_written_with_a_life_plan_loads_and_is_written_without_it() {
        val s = account()
        val encoded = SchedulerStateCodec.encode(s)
        assertFalse("\"lifePlan\"" in encoded, "nothing of it is written any more")
        // The payload as the previous build wrote it: the same state, with its life plan.
        val older = encoded.removeSuffix("}") +
            ""","lifePlan":[{"id":"s1","kind":"Step","parent":null,"rank":1.0,"text":"Run a marathon"},""" +
            """{"id":"t1","kind":"Tab","parent":null,"rank":2.0,"text":"Notes"}]}"""
        val decoded = assertNotNull(SchedulerStateCodec.decode(older), "the older payload still loads")
        assertEquals(s.tasks.values.map { it.title }.sorted(), decoded.tasks.values.map { it.title }.sorted(), "the rest is intact")
        assertFalse("\"lifePlan\"" in SchedulerStateCodec.encode(decoded), "saved again, the life plan is gone")
    }

    @Test
    fun a_history_unit_of_the_life_plan_is_skipped() {
        val unit = SchedulerStateCodec.decodeUnit(
            timeMillis = 1L, chronoId = 0L, tainted = false, window = "LifePlan", deviceId = "d", deviceSeq = 1L, undone = false,
            text = """{"type":"lifePlan","before":[],"after":[{"id":"s1","kind":"Step","rank":1.0,"text":"Run"}]}""",
        )
        assertNull(unit, "a unit this build cannot read is skipped, never a failed load")
        // Another unit made in the Life plan's window: kept, in no window.
        val focus = SchedulerStateCodec.decodeUnit(
            timeMillis = 1L, chronoId = 0L, tainted = false, window = "LifePlan", deviceId = "d", deviceSeq = 2L, undone = false,
            text = """{"type":"focus","before":"Tree","after":"Calendar"}""",
        )
        assertNotNull(focus)
        assertNull(focus.window)
        assertTrue(HistoryWindow.entries.none { it.name == "LifePlan" })
    }

    @Test
    fun a_menu_that_kept_the_life_plan_button_loses_it() {
        val stored =
            """{"buttons":[{"id":"d-calendar","window":"Calendar","title":"Calendar"},""" +
                """{"id":"d-life-plan","window":"LifePlan","title":"Life plan"},{"id":"b1","window":"LifePlan#2","title":"My plan"}],""" +
                """"seeded":true,"lifePlanSeeded":true}"""
        val buttons = CustomMenuButtons.decode(stored)
        assertTrue(buttons.none { it.windowId.substringBefore('#') == "LifePlan" }, "no button opens it: ${buttons.map { it.id }}")
        assertTrue(buttons.any { it.id == "d-calendar" }, "the others stay")
        assertTrue(CustomMenuButtons.DEFAULTS.none { it.windowId == "LifePlan" }, "and no new menu is given one")
    }
}
