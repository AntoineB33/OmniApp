package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.example.project.scheduler.model.ChoreEntry
import org.example.project.scheduler.persistence.SchedulerStateCodec
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState

/**
 * PRD §14 Chores Manager: a standalone vertical list of pairs (title + spanning time in days, a
 * floating-point number). Covers the testable core — the [SchedulerIntent.SetChores] mutation, its
 * persistence, and that it lives outside the tree Undo/Redo history (PRD §6/§14). The floating window
 * and its lateral-menu toggle are Compose UI (like the §7 calendar window) and are not unit-tested here.
 */
class SchedulerChoresTest {

    @Test
    fun chores_default_to_an_empty_list() {
        assertTrue(SchedulerState.empty().chores.isEmpty())
    }

    @Test
    fun set_chores_stores_the_list_with_floating_point_day_spans() {
        val entries = listOf(ChoreEntry("Water plants", 3.5), ChoreEntry("Vacuum", 7.0))
        val s = SchedulerReducer.reduce(SchedulerState.empty(), SchedulerIntent.SetChores(entries))
        assertEquals(entries, s.chores)
    }

    @Test
    fun set_chores_replaces_the_whole_list() {
        var s = SchedulerReducer.reduce(SchedulerState.empty(), SchedulerIntent.SetChores(listOf(ChoreEntry("A", 1.0))))
        val replacement = listOf(ChoreEntry("B", 2.0), ChoreEntry("C", 0.25))
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetChores(replacement))
        assertEquals(replacement, s.chores)
    }

    @Test
    fun set_chores_can_clear_the_list() {
        val seeded = SchedulerReducer.reduce(SchedulerState.empty(), SchedulerIntent.SetChores(listOf(ChoreEntry("A", 1.0))))
        val cleared = SchedulerReducer.reduce(seeded, SchedulerIntent.SetChores(emptyList()))
        assertTrue(cleared.chores.isEmpty())
    }

    @Test
    fun set_chores_with_the_same_list_is_a_no_op() {
        val entries = listOf(ChoreEntry("A", 1.0))
        val s = SchedulerReducer.reduce(SchedulerState.empty(), SchedulerIntent.SetChores(entries))
        val again = SchedulerReducer.reduce(s, SchedulerIntent.SetChores(entries))
        assertEquals(s, again)
    }

    @Test
    fun editing_chores_is_not_part_of_the_tree_undo_history() {
        // PRD §14/§6: chores live outside the Task Tree snapshot, so Ctrl+Z must not revert them (mirrors
        // the §7 automatic-schedule switch / the task record).
        val s0 = SchedulerState.empty()
        val withChores = SchedulerReducer.reduce(s0, SchedulerIntent.SetChores(listOf(ChoreEntry("A", 1.0))))
        val undone = SchedulerReducer.reduce(withChores, SchedulerIntent.Undo)
        assertEquals(listOf(ChoreEntry("A", 1.0)), undone.chores)
    }

    @Test
    fun codec_round_trip_preserves_the_chores_list() {
        val entries = listOf(ChoreEntry("Water plants", 3.5), ChoreEntry("Bins out", 14.0))
        val s = SchedulerReducer.reduce(SchedulerState.empty(), SchedulerIntent.SetChores(entries))
        val decoded = SchedulerStateCodec.decode(SchedulerStateCodec.encode(s))
        assertNotNull(decoded)
        assertEquals(entries, decoded.chores)
    }

    @Test
    fun codec_decodes_old_payload_to_an_empty_chores_list() {
        val json =
            """
            {"rootListId":"L","lists":[{"id":"L","parentCellId":null,"cellIds":["c0"]}],
             "cells":[{"id":"c0","parentListId":"L","taskId":null}],
             "tasks":[{"id":"t0","title":"X"}]}
            """.trimIndent()
        val decoded = SchedulerStateCodec.decode(json)
        assertNotNull(decoded)
        assertTrue(decoded.chores.isEmpty())
    }
}
