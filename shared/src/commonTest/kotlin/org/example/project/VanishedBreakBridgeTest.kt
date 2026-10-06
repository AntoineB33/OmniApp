package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.PeriodKinds
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.CellId
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.model.TaskPanel
import org.example.project.scheduler.model.TaskTimeRange
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState

/**
 * User spec 2026-09-27: when a 20 s look-away disappears because the user pressed "Look away now" (the conducted
 * break re-anchors the recurrence bars — pressed just before a look-away falls due, it takes that look-away's
 * place), and ONE task touches both of its edges, the look-away is replaced by that task.
 *
 * The scenario is a real plan: one on-screen task, the default breaks, and an earlier look-away that ends so that the
 * next one falls due at +20 s (only the end of a 20 s break bars the next: requirements 2026-10-05) — the plan is `Write` up to +20 s, the look-away over
 * [+20 s, +40 s), and `Write` again from +40 s.
 */
class VanishedBreakBridgeTest {

    private val SEC = 1_000L
    private val MIN = 60_000L
    private val NOW = 1_000_000_000_000L

    private val breaks = SchedulerDomain.DEFAULT_SCREEN_BREAKS
    private val lookAway = breaks.first { !it.restBreak }
    private val hole = TaskTimeRange(NOW + 20 * SEC, NOW + 40 * SEC)

    private fun r(state: SchedulerState, intent: SchedulerIntent) = SchedulerReducer.reduce(state, intent)

    private fun freeRootCell(state: SchedulerState): CellId = state.lists[state.rootListId]!!.cellIds.last()

    private fun taskId(s: SchedulerState, title: String): TaskId = s.tasks.values.first { it.title == title }.id

    private fun planned(): SchedulerState {
        var s = SchedulerState.empty().copy(screenBreaks = breaks)
        s = r(s, SchedulerIntent.SetCellTitle(freeRootCell(s), "Write"))
        // A look-away ending so that the next one, barred for twenty minutes, falls due twenty seconds past NOW.
        s = r(s, SchedulerIntent.RecordConductedBreak(lookAway.title, NOW - 20 * MIN, NOW - 20 * MIN + 20 * SEC))
        s = r(s, SchedulerIntent.RefreshSchedule(NOW - 5 * MIN))
        val write = taskId(s, "Write")
        val around = s.panels.filter { it.endEpochMillis > NOW - MIN && it.startEpochMillis < NOW + MIN }
        assertTrue(around.any { it.taskId == write && it.endEpochMillis == hole.startEpochMillis }, "the scenario: Write up to the look-away")
        assertTrue(around.any { it.screenBreak && it.startEpochMillis == hole.startEpochMillis && it.endEpochMillis == hole.endEpochMillis })
        assertTrue(around.any { it.taskId == write && it.startEpochMillis == hole.endEpochMillis }, "…and Write again after it")
        return s
    }

    /** "Look away now", completed at [endMillis]. */
    private fun press(s: SchedulerState, endMillis: Long): SchedulerState =
        r(s, SchedulerIntent.RecordConductedBreak(lookAway.title, endMillis - lookAway.durationMillis, endMillis))

    /** Every box of [taskId] on the calendar, joined where they touch. */
    private fun boxes(s: SchedulerState, taskId: TaskId): List<TaskTimeRange> =
        SchedulerDomain.mergeOccupied(SchedulerDomain.calendarBoxesOfTask(s, taskId).toList())

    private fun covers(ranges: List<TaskTimeRange>, from: Long, to: Long) =
        ranges.any { it.startEpochMillis <= from && it.endEpochMillis >= to }

    @Test
    fun pressing_just_before_a_look_away_falls_due_makes_it_vanish() {
        val s = planned()
        val after = press(s, NOW + 30 * SEC)
        assertEquals(
            listOf(hole),
            SchedulerDomain.vanishedPastBreaks(s, after, null, NOW - 4 * 60 * MIN, NOW + 30 * SEC),
        )
    }

    @Test
    fun the_vanished_look_away_is_replaced_by_the_task_on_both_its_sides() {
        val s = planned()
        val write = taskId(s, "Write")
        val after = press(s, NOW + 30 * SEC)
        assertTrue(covers(boxes(after, write), NOW - MIN, NOW + MIN), "Write runs straight through where the look-away was")
        // The two plan panels meet, so the calendar draws one block.
        val group = SchedulerDomain.groupSameTaskPanelsForDisplay(after.panels.filter { it.taskId == write })
            .first { g -> g.any { it.endEpochMillis >= hole.endEpochMillis && it.startEpochMillis <= hole.startEpochMillis } }
        assertTrue(group.maxOf { it.endEpochMillis } > hole.endEpochMillis, "one block across the old look-away")
        // …and the re-plan the press asks for keeps it whole.
        val replanned = r(after, SchedulerIntent.RefreshSchedule(NOW + 30 * SEC))
        assertTrue(covers(boxes(replanned, write), NOW - MIN, NOW + MIN), "still one stretch of Write after the re-plan")
    }

    @Test
    fun when_the_side_before_is_already_banked_the_elapsed_part_is_banked_and_joined() {
        val banked = r(planned(), SchedulerIntent.AdvanceSchedule(NOW + 25 * SEC))
        val write = taskId(banked, "Write")
        assertTrue(banked.tasks.getValue(write).record.any { it.endEpochMillis == hole.startEpochMillis }, "the side before is a record")
        val after = press(banked, NOW + 30 * SEC)
        val record = after.tasks.getValue(write).record
        // The conducted look-away itself, [+10 s, +30 s), is a 20 s screen break: it allows no task, so no work is
        // recorded inside it (2026-10-03 — this used to expect one record straight through it, which is the task panel
        // drawn inside a conducted break that account 3 reported). The task replaces the VANISHED look-away up to it.
        val conducted = TaskTimeRange(NOW + 10 * SEC, NOW + 30 * SEC)
        assertTrue(record.any { it.endEpochMillis == conducted.startEpochMillis && it.startEpochMillis < NOW }, "one record up to the conducted break: $record")
        assertTrue(record.none { it.startEpochMillis < conducted.endEpochMillis && conducted.startEpochMillis < it.endEpochMillis }, "no work inside the conducted break: $record")
        assertTrue(after.panels.any { it.taskId == write && it.auto && it.startEpochMillis == NOW + 30 * SEC }, "the plan resumes at the line")
        assertTrue(covers(boxes(after, write), NOW - MIN, conducted.startEpochMillis))
        assertTrue(covers(boxes(after, write), conducted.endEpochMillis, NOW + MIN))
    }

    @Test
    fun a_look_away_between_two_different_tasks_is_left_as_it_is() {
        var s = planned()
        s = r(s, SchedulerIntent.SetCellTitle(freeRootCell(s), "Read"))
        val read = taskId(s, "Read")
        // The side after the look-away is another task's.
        s = s.copy(panels = s.panels.map { if (it.startEpochMillis == hole.endEpochMillis && it.taskId != null) it.copy(taskId = read) else it })
        val after = press(s, NOW + 30 * SEC)
        assertEquals(s.panels.filterNot { it.conductedBreak }, after.panels.filterNot { it.conductedBreak })
        assertEquals(s.tasks, after.tasks)
    }

    @Test
    fun a_press_that_leaves_the_look_away_where_it_was_changes_nothing_else() {
        val s = planned()
        // Pressed just after the look-away's cue: it stays drawn, so it has no hole to give back.
        val after = press(s, NOW + 42 * SEC)
        // (The earlier look-away the scenario is built on is a conducted break too.)
        assertEquals(s.panels.filterNot { it.conductedBreak }, after.panels.filterNot { it.conductedBreak })
        assertEquals(s.tasks, after.tasks)
    }
}
