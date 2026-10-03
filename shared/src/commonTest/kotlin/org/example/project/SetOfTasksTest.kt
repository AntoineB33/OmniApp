package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.CellId
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.persistence.SchedulerStateCodec
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState

/**
 * PRD §9 (user rule 2026-10-03): **a task's set of tasks** — what it fulfils while it is on the calendar, each at a
 * percentage ([org.example.project.scheduler.model.Task.fulfilment]). The user's own walk-through: "watch videos
 * explaining chemistry in Spanish" is made with no path from the Search window, given "watch videos explaining
 * chemistry" at 80% and "listen to Spanish" at 80%; it is in no task tree, it is kept because the tasks it fulfils are,
 * and having no children it is schedulable. A task with a set that gets children hands it to them.
 */
class SetOfTasksTest {

    private fun r(state: SchedulerState, intent: SchedulerIntent) = SchedulerReducer.reduce(state, intent)

    private fun freeRootCell(state: SchedulerState): CellId = state.lists[state.rootListId]!!.cellIds.last()

    private fun freeChildCell(state: SchedulerState, cellId: CellId): CellId {
        val taskId = state.cells[cellId]!!.taskId!!
        return state.lists[state.tasks[taskId]!!.childListId!!]!!.cellIds.last()
    }

    private fun taskWithTitle(state: SchedulerState, title: String): TaskId = state.tasks.values.first { it.title == title }.id

    private fun tree(): SchedulerState {
        var s = SchedulerState.empty()
        s = r(s, SchedulerIntent.SetCellTitle(freeRootCell(s), "watch videos explaining chemistry"))
        s = r(s, SchedulerIntent.SetCellTitle(freeRootCell(s), "listen to Spanish"))
        return s
    }

    /** A tree edit, which purges what nothing keeps. */
    private fun treeEdit(s: SchedulerState): SchedulerState = r(s, SchedulerIntent.SetCellTitle(freeRootCell(s), "something else"))

    private fun withSpanishVideos(): Pair<SchedulerState, TaskId> {
        var s = tree()
        s = r(s, SchedulerIntent.CreatePathlessTask("watch videos explaining chemistry in Spanish"))
        val x = taskWithTitle(s, "watch videos explaining chemistry in Spanish")
        s = r(s, SchedulerIntent.SetTaskFulfilment(x, taskWithTitle(s, "watch videos explaining chemistry"), 0.8))
        s = r(s, SchedulerIntent.SetTaskFulfilment(x, taskWithTitle(s, "listen to Spanish"), 0.8))
        return s to x
    }

    @Test
    fun a_pathless_task_with_no_set_is_dropped_by_the_next_purge() {
        var s = r(tree(), SchedulerIntent.CreatePathlessTask("New task"))
        val x = taskWithTitle(s, "New task")
        assertFalse(SchedulerDomain.taskHasCells(s, x), "it has no path")
        s = treeEdit(s)
        assertNull(s.tasks[x], "nothing keeps a pathless task with no set")
    }

    @Test
    fun the_user_s_walk_through_a_pathless_task_kept_by_its_set_and_schedulable() {
        val (s0, x) = withSpanishVideos()
        val s = treeEdit(s0)
        assertNotNull(s.tasks[x], "the tasks its set holds are in the tree, so it is kept")
        assertEquals(mapOf(taskWithTitle(s, "watch videos explaining chemistry") to 0.8, taskWithTitle(s, "listen to Spanish") to 0.8), s.tasks[x]!!.fulfilment)
        assertFalse(SchedulerDomain.taskHasCells(s, x), "and it is still in no task tree")
        assertTrue(x in SchedulerDomain.schedulableLeaves(s), "no children: it is schedulable")
        assertTrue(SchedulerDomain.isPlaceableTask(s, x))
    }

    @Test
    fun a_chain_of_sets_keeps_every_task_on_it_and_breaking_it_drops_them() {
        var (s, x) = withSpanishVideos()
        s = r(s, SchedulerIntent.CreatePathlessTask("review"))
        val y = taskWithTitle(s, "review")
        // y fulfils x, which fulfils tree tasks: y is kept through x.
        s = r(s, SchedulerIntent.SetTaskFulfilment(y, x, 0.5))
        s = treeEdit(s)
        assertNotNull(s.tasks[y])
        // x's set emptied: nothing keeps x, so nothing keeps y either.
        s = r(s, SchedulerIntent.SetTaskFulfilment(x, taskWithTitle(s, "watch videos explaining chemistry"), null))
        s = r(s, SchedulerIntent.SetTaskFulfilment(x, taskWithTitle(s, "listen to Spanish"), null))
        assertNull(s.tasks[x])
        assertNull(s.tasks[y])
    }

    @Test
    fun the_credits_are_transitive_by_the_best_chain_and_a_cycle_is_cut() {
        val (s0, x) = withSpanishVideos()
        var s = r(s0, SchedulerIntent.CreatePathlessTask("review"))
        val y = taskWithTitle(s, "review")
        val chemistry = taskWithTitle(s, "watch videos explaining chemistry")
        s = r(s, SchedulerIntent.SetTaskFulfilment(y, x, 0.5))
        s = r(s, SchedulerIntent.SetTaskFulfilment(x, y, 0.9)) // a cycle
        val credits = SchedulerDomain.fulfilmentCredits(s.tasks, y, s.tasks.keys)
        assertEquals(0.5, credits[x])
        assertEquals(0.4, credits.getValue(chemistry), 1e-9)
        assertFalse(y in credits, "a task never counts for itself")
    }

    @Test
    fun a_task_with_a_set_that_gets_children_hands_it_to_them() {
        var s = tree()
        val chemistry = taskWithTitle(s, "watch videos explaining chemistry")
        val spanish = taskWithTitle(s, "listen to Spanish")
        s = r(s, SchedulerIntent.SetTaskFulfilment(spanish, chemistry, 0.3))
        val spanishCell = s.cells.values.first { it.taskId == spanish }.id
        s = r(s, SchedulerIntent.SetCellTitle(freeChildCell(s, spanishCell), "podcasts"))
        assertEquals(mapOf(chemistry to 0.3), s.tasks.getValue(taskWithTitle(s, "podcasts")).fulfilment)
    }

    @Test
    fun a_set_is_undone_and_is_a_rule() {
        val (s0, x) = withSpanishVideos()
        val before = SchedulerDomain.schedulingSignature(s0)
        val chemistry = taskWithTitle(s0, "watch videos explaining chemistry")
        val s1 = r(s0, SchedulerIntent.SetTaskFulfilment(x, chemistry, 0.5))
        assertNotEquals(before, SchedulerDomain.schedulingSignature(s1), "editing a set re-plans")
        val undone = r(s1, SchedulerIntent.Undo)
        assertEquals(0.8, undone.tasks.getValue(x).fulfilment[chemistry])
    }

    /** CLAUDE.md § *Persisted-DB compatibility*: a payload without sets, and one holding what decode must heal. */
    @Test
    fun the_set_survives_the_store_and_an_older_payload_reads_as_none() {
        val (s, x) = withSpanishVideos()
        val back = SchedulerStateCodec.decodeSnapshot(SchedulerStateCodec.encodeSnapshot(s))!!
        assertEquals(s.tasks.getValue(x).fulfilment, back.tasks.getValue(x).fulfilment)

        val tree = tree()
        val payload = SchedulerStateCodec.encodeSnapshot(tree)
        assertFalse(payload.statePayload.contains("fulfilment"), "nothing is written for a task with no set")
        assertTrue(SchedulerStateCodec.decodeSnapshot(payload)!!.tasks.values.all { it.fulfilment.isEmpty() })

        // Healed: above 100% is 100%, zero is nothing, and a task never fulfils itself.
        val spanish = taskWithTitle(tree, "listen to Spanish").value
        val chemistry = taskWithTitle(tree, "watch videos explaining chemistry").value
        val bad = payload.copy(
            statePayload = payload.statePayload.replaceFirst(
                "\"title\":\"listen to Spanish\"",
                "\"title\":\"listen to Spanish\",\"fulfilment\":{\"$chemistry\":1.7,\"$spanish\":0.5,\"task/user/77\":0.0}",
            ),
        )
        assertEquals(mapOf(TaskId(chemistry) to 1.0), SchedulerStateCodec.decodeSnapshot(bad)!!.tasks.getValue(TaskId(spanish)).fulfilment)
    }

    /**
     * The user's rule, end to end: a task whose set holds every schedulable task at 100% fills the best schedule.
     */
    @Test
    fun a_task_fulfilling_every_task_at_100_percent_fills_the_plan() {
        var s = tree()
        s = r(s, SchedulerIntent.CreatePathlessTask("everything"))
        val x = taskWithTitle(s, "everything")
        for (id in SchedulerDomain.schedulableLeaves(s).filter { it != x }) {
            s = r(s, SchedulerIntent.SetTaskFulfilment(x, id, 1.0))
        }
        val now = 1_700_000_000_000L
        val planned = r(s, SchedulerIntent.RefreshSchedule(now, horizonCapMillis = now + 4 * 3_600_000L))
        val runs = planned.panels.filter { it.auto && it.taskId != null && it.startEpochMillis >= now }
        assertTrue(runs.isNotEmpty(), "something was planned")
        assertTrue(runs.all { it.taskId == x }, "the plan is filled by the task: ${runs.map { planned.tasks[it.taskId]?.title }}")
    }
}
