package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.ScheduleUnitEntry
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.persistence.SchedulerStateCodec
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState

/**
 * PRD §13 Schedule Unit: a task's ordered list of named sub-steps (title + spanning time). Covers the
 * data model + the testable logic — the §13 "Save not clickable" budget rule
 * ([SchedulerDomain.canSaveScheduleUnit]), the notification deadlines
 * ([SchedulerDomain.scheduleUnitDeadlines] / [SchedulerDomain.taskSwitchNotificationMessage]), the
 * undoable [SchedulerIntent.SetScheduleUnit] mutation, and codec round-tripping.
 */
class SchedulerScheduleUnitTest {

    private val MIN = 60_000L

    /** A single leaf task "Solo" with the given minimum time (minutes). */
    private fun stateWithOneTask(minMinutes: Int = 45): Pair<SchedulerState, TaskId> {
        var s = SchedulerState.empty()
        val c0 = s.lists[s.rootListId]!!.cellIds[0]
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(c0, "Solo"))
        val solo = s.tasks.keys.first { s.tasks[it]!!.title == "Solo" }
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetTaskMinimumTime(solo, minMinutes))
        return s to solo
    }

    // ----- §13 Save budget rule --------------------------------------------------------------

    @Test
    fun save_allowed_when_sum_equals_or_below_minimum() {
        val under = listOf(ScheduleUnitEntry("a", 10), ScheduleUnitEntry("b", 20))
        val exact = listOf(ScheduleUnitEntry("a", 25), ScheduleUnitEntry("b", 20))
        assertEquals(30, SchedulerDomain.scheduleUnitSumMinutes(under))
        assertTrue(SchedulerDomain.canSaveScheduleUnit(under, minimumMinutes = 45))
        assertTrue(SchedulerDomain.canSaveScheduleUnit(exact, minimumMinutes = 45)) // boundary: sum == min
    }

    @Test
    fun save_blocked_when_sum_exceeds_minimum() {
        val over = listOf(ScheduleUnitEntry("a", 30), ScheduleUnitEntry("b", 20))
        assertEquals(50, SchedulerDomain.scheduleUnitSumMinutes(over))
        assertFalse(SchedulerDomain.canSaveScheduleUnit(over, minimumMinutes = 45))
    }

    @Test
    fun empty_unit_is_always_saveable() {
        assertTrue(SchedulerDomain.canSaveScheduleUnit(emptyList(), minimumMinutes = 0))
    }

    // ----- §13 notification deadlines --------------------------------------------------------

    @Test
    fun deadlines_accumulate_from_the_task_start() {
        val entries = listOf(ScheduleUnitEntry("warm up", 10), ScheduleUnitEntry("main set", 20), ScheduleUnitEntry("cool down", 5))
        val start = 1_000_000_000_000L
        val deadlines = SchedulerDomain.scheduleUnitDeadlines(entries, start)
        assertEquals(
            listOf(
                "warm up" to start + 10 * MIN,
                "main set" to start + 30 * MIN,
                "cool down" to start + 35 * MIN,
            ),
            deadlines,
        )
    }

    @Test
    fun notification_message_is_just_the_title_without_a_schedule_unit() {
        val (s, solo) = stateWithOneTask()
        val msg = SchedulerDomain.taskSwitchNotificationMessage(s, solo, startMillis = 0L) { "IGNORED" }
        assertEquals("Solo", msg)
    }

    @Test
    fun notification_message_appends_each_step_deadline() {
        val (s0, solo) = stateWithOneTask()
        val entries = listOf(ScheduleUnitEntry("read", 15), ScheduleUnitEntry("write", 15))
        val s = SchedulerReducer.reduce(s0, SchedulerIntent.SetScheduleUnit(solo, entries))
        val start = 0L
        // Format the deadline as raw minutes-from-start so the assertion is timezone-independent.
        val msg = SchedulerDomain.taskSwitchNotificationMessage(s, solo, startMillis = start) { d -> "${(d - start) / MIN}m" }
        assertEquals("Solo\n• read — 15m\n• write — 30m", msg)
    }

    @Test
    fun notification_message_is_null_for_a_blank_titled_task() {
        val (s, solo) = stateWithOneTask()
        val blanked = s.copy(tasks = s.tasks + (solo to s.tasks[solo]!!.copy(title = "")))
        assertNull(SchedulerDomain.taskSwitchNotificationMessage(blanked, solo, startMillis = 0L) { "x" })
    }

    // ----- §13 SetScheduleUnit reducer + undo -------------------------------------------------

    @Test
    fun set_schedule_unit_stores_entries_on_the_task() {
        val (s0, solo) = stateWithOneTask()
        val entries = listOf(ScheduleUnitEntry("a", 10), ScheduleUnitEntry("b", 10))
        val s = SchedulerReducer.reduce(s0, SchedulerIntent.SetScheduleUnit(solo, entries))
        assertEquals(entries, s.tasks[solo]!!.scheduleUnit)
    }

    @Test
    fun set_schedule_unit_rejects_an_over_budget_sum() {
        val (s0, solo) = stateWithOneTask(minMinutes = 30)
        val over = listOf(ScheduleUnitEntry("a", 20), ScheduleUnitEntry("b", 20)) // 40 > 30
        val s = SchedulerReducer.reduce(s0, SchedulerIntent.SetScheduleUnit(solo, over))
        assertTrue(s.tasks[solo]!!.scheduleUnit.isEmpty(), "an invalid unit must not be persisted")
    }

    @Test
    fun undo_restores_the_previous_schedule_unit() {
        val (s0, solo) = stateWithOneTask()
        val first = listOf(ScheduleUnitEntry("a", 10))
        val second = listOf(ScheduleUnitEntry("a", 10), ScheduleUnitEntry("b", 10))
        val s1 = SchedulerReducer.reduce(s0, SchedulerIntent.SetScheduleUnit(solo, first))
        val s2 = SchedulerReducer.reduce(s1, SchedulerIntent.SetScheduleUnit(solo, second))
        val undone = SchedulerReducer.reduce(s2, SchedulerIntent.Undo)
        assertEquals(first, undone.tasks[solo]!!.scheduleUnit)
        val redone = SchedulerReducer.reduce(undone, SchedulerIntent.Redo)
        assertEquals(second, redone.tasks[solo]!!.scheduleUnit)
    }

    @Test
    fun clearing_the_schedule_unit_is_allowed_and_recorded() {
        val (s0, solo) = stateWithOneTask()
        val withUnit = SchedulerReducer.reduce(s0, SchedulerIntent.SetScheduleUnit(solo, listOf(ScheduleUnitEntry("a", 10))))
        val cleared = SchedulerReducer.reduce(withUnit, SchedulerIntent.SetScheduleUnit(solo, emptyList()))
        assertTrue(cleared.tasks[solo]!!.scheduleUnit.isEmpty())
    }

    // ----- §13 the "define schedule unit" menu is leaf-only -----------------------------------

    @Test
    fun schedule_unit_menu_gate_is_leaf_only() {
        // PRD §13: the contextual menu offers "define schedule unit" only when the task has no child
        // task — the same leaf predicate the calendar/scheduler use.
        val (s, solo) = stateWithOneTask()
        assertTrue(SchedulerDomain.isLeafTask(s, solo))
    }

    // ----- §13 persistence --------------------------------------------------------------------

    @Test
    fun codec_round_trip_preserves_the_schedule_unit() {
        val (s0, solo) = stateWithOneTask()
        val entries = listOf(ScheduleUnitEntry("read", 15), ScheduleUnitEntry("write", 20))
        val s = SchedulerReducer.reduce(s0, SchedulerIntent.SetScheduleUnit(solo, entries))
        val decoded = SchedulerStateCodec.decode(SchedulerStateCodec.encode(s))
        assertNotNull(decoded)
        assertEquals(entries, decoded.tasks[solo]!!.scheduleUnit)
    }

    @Test
    fun codec_decodes_old_payload_to_an_empty_schedule_unit() {
        val json =
            """
            {"rootListId":"L","lists":[{"id":"L","parentCellId":null,"cellIds":["c0"]}],
             "cells":[{"id":"c0","parentListId":"L","taskId":null}],
             "tasks":[{"id":"t0","title":"X"}]}
            """.trimIndent()
        val decoded = SchedulerStateCodec.decode(json)
        assertNotNull(decoded)
        assertTrue(decoded.tasks[TaskId("t0")]!!.scheduleUnit.isEmpty())
    }
}
