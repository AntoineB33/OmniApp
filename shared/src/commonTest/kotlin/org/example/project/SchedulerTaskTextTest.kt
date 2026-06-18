package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.persistence.SchedulerStateCodec
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState

/**
 * "See text": a free-form text document attached to a task, opened from a populated cell's right-click
 * menu. Covers the undoable [SchedulerIntent.SetTaskText] mutation and codec round-tripping.
 */
class SchedulerTaskTextTest {

    /** A single task "Solo". */
    private fun stateWithOneTask(): Pair<SchedulerState, TaskId> {
        var s = SchedulerState.empty()
        val c0 = s.lists[s.rootListId]!!.cellIds[0]
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(c0, "Solo"))
        val solo = s.tasks.keys.first { s.tasks[it]!!.title == "Solo" }
        return s to solo
    }

    @Test
    fun set_task_text_stores_it_on_the_task() {
        val (s0, solo) = stateWithOneTask()
        val s = SchedulerReducer.reduce(s0, SchedulerIntent.SetTaskText(solo, "buy milk\ncall Bob"))
        assertEquals("buy milk\ncall Bob", s.tasks[solo]!!.text)
    }

    @Test
    fun undo_restores_the_previous_text() {
        val (s0, solo) = stateWithOneTask()
        val s1 = SchedulerReducer.reduce(s0, SchedulerIntent.SetTaskText(solo, "first"))
        val s2 = SchedulerReducer.reduce(s1, SchedulerIntent.SetTaskText(solo, "second"))
        val undone = SchedulerReducer.reduce(s2, SchedulerIntent.Undo)
        assertEquals("first", undone.tasks[solo]!!.text)
        val redone = SchedulerReducer.reduce(undone, SchedulerIntent.Redo)
        assertEquals("second", redone.tasks[solo]!!.text)
    }

    @Test
    fun clearing_the_text_is_allowed() {
        val (s0, solo) = stateWithOneTask()
        val withText = SchedulerReducer.reduce(s0, SchedulerIntent.SetTaskText(solo, "notes"))
        val cleared = SchedulerReducer.reduce(withText, SchedulerIntent.SetTaskText(solo, ""))
        assertTrue(cleared.tasks[solo]!!.text.isEmpty())
    }

    @Test
    fun codec_round_trip_preserves_the_text() {
        val (s0, solo) = stateWithOneTask()
        val s = SchedulerReducer.reduce(s0, SchedulerIntent.SetTaskText(solo, "remember this"))
        val decoded = SchedulerStateCodec.decode(SchedulerStateCodec.encode(s))
        assertNotNull(decoded)
        assertEquals("remember this", decoded.tasks[solo]!!.text)
    }

    @Test
    fun codec_decodes_old_payload_to_an_empty_text() {
        val json =
            """
            {"rootListId":"L","lists":[{"id":"L","parentCellId":null,"cellIds":["c0"]}],
             "cells":[{"id":"c0","parentListId":"L","taskId":null}],
             "tasks":[{"id":"t0","title":"X"}]}
            """.trimIndent()
        val decoded = SchedulerStateCodec.decode(json)
        assertNotNull(decoded)
        assertTrue(decoded.tasks[TaskId("t0")]!!.text.isEmpty())
    }
}
