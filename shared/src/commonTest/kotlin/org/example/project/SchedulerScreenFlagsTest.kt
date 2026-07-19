package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.persistence.SchedulerStateCodec
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState

/**
 * PRD §8 screen switches (calendar edit window): a task's "On screen" / "Doable during a screen break"
 * flags. Covers the undoable [SchedulerIntent.SetTaskScreenFlags] mutation, codec round-tripping, and —
 * per the persisted-DB compatibility rule — that a payload written before the fields existed decodes to
 * the pre-switch behaviour (every task on-screen, none doable during a break).
 */
class SchedulerScreenFlagsTest {

    /** A single task "Solo". */
    private fun stateWithOneTask(): Pair<SchedulerState, TaskId> {
        var s = SchedulerState.empty()
        val c0 = s.lists[s.rootListId]!!.cellIds[0]
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(c0, "Solo"))
        val solo = s.tasks.keys.first { s.tasks[it]!!.title == "Solo" }
        return s to solo
    }

    @Test
    fun a_new_task_defaults_to_on_screen_and_not_break_doable() {
        val (s, solo) = stateWithOneTask()
        assertTrue(s.tasks[solo]!!.onScreen)
        assertFalse(s.tasks[solo]!!.doableDuringBreak)
    }

    @Test
    fun set_screen_flags_stores_them_on_the_task() {
        val (s0, solo) = stateWithOneTask()
        val s = SchedulerReducer.reduce(s0, SchedulerIntent.SetTaskScreenFlags(solo, onScreen = false, doableDuringBreak = true))
        assertFalse(s.tasks[solo]!!.onScreen)
        assertTrue(s.tasks[solo]!!.doableDuringBreak)
    }

    @Test
    fun undo_restores_the_previous_flags() {
        val (s0, solo) = stateWithOneTask()
        val s1 = SchedulerReducer.reduce(s0, SchedulerIntent.SetTaskScreenFlags(solo, onScreen = false, doableDuringBreak = true))
        val undone = SchedulerReducer.reduce(s1, SchedulerIntent.Undo)
        assertTrue(undone.tasks[solo]!!.onScreen)
        assertFalse(undone.tasks[solo]!!.doableDuringBreak)
        val redone = SchedulerReducer.reduce(undone, SchedulerIntent.Redo)
        assertFalse(redone.tasks[solo]!!.onScreen)
        assertTrue(redone.tasks[solo]!!.doableDuringBreak)
    }

    @Test
    fun setting_unchanged_flags_adds_no_history_unit() {
        val (s0, solo) = stateWithOneTask()
        val s = SchedulerReducer.reduce(s0, SchedulerIntent.SetTaskScreenFlags(solo, onScreen = true, doableDuringBreak = false))
        assertEquals(s0.histories, s.histories)
    }

    @Test
    fun codec_round_trip_preserves_the_flags() {
        val (s0, solo) = stateWithOneTask()
        val s = SchedulerReducer.reduce(s0, SchedulerIntent.SetTaskScreenFlags(solo, onScreen = false, doableDuringBreak = true))
        val decoded = SchedulerStateCodec.decode(SchedulerStateCodec.encode(s))
        assertNotNull(decoded)
        assertFalse(decoded.tasks[solo]!!.onScreen)
        assertTrue(decoded.tasks[solo]!!.doableDuringBreak)
    }

    @Test
    fun codec_decodes_old_payload_to_the_pre_switch_defaults() {
        // A representative payload written by the previous shape — no onScreen / doableDuringBreak
        // fields. It must load with every task on-screen and not break-doable.
        val json =
            """
            {"rootListId":"L","lists":[{"id":"L","parentCellId":null,"cellIds":["c0"]}],
             "cells":[{"id":"c0","parentListId":"L","taskId":null}],
             "tasks":[{"id":"t0","title":"X"}]}
            """.trimIndent()
        val decoded = SchedulerStateCodec.decode(json)
        assertNotNull(decoded)
        assertTrue(decoded.tasks[TaskId("t0")]!!.onScreen)
        assertFalse(decoded.tasks[TaskId("t0")]!!.doableDuringBreak)
    }
}
