package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.example.project.scheduler.domain.CalendarLockDomain
import org.example.project.scheduler.model.CellId
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.model.TaskPanel
import org.example.project.scheduler.model.TaskTimeRange
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState

/**
 * PRD §8 "go to calendar" / "locked on task" (user spec 2026-09-26): the calendar is held on the middle of the
 * task's panel closest to the now-line; with no panel yet but one to come, on the definitive-schedule front (and the
 * menu shows a loading mark); with none possible, the menu does not offer it.
 */
class CalendarLockTest {
    private val minute = 60_000L
    private val now = 1_800_000_000_000L

    private fun r(state: SchedulerState, intent: SchedulerIntent) = SchedulerReducer.reduce(state, intent)

    private fun freeRootCell(state: SchedulerState): CellId = state.lists[state.rootListId]!!.cellIds.last()

    private fun freeChildCell(state: SchedulerState, taskId: TaskId): CellId =
        state.lists[state.tasks[taskId]!!.childListId!!]!!.cellIds.last()

    private fun taskWithTitle(state: SchedulerState, title: String): TaskId = state.tasks.values.first { it.title == title }.id

    private fun panel(id: String, taskId: TaskId, start: Long, end: Long) =
        TaskPanel(id = id, taskId = taskId, title = "", startEpochMillis = start, endEpochMillis = end, auto = true)

    /** One schedulable leaf ("Write") under a parent ("Work"). */
    private fun tree(): SchedulerState {
        var s = SchedulerState.empty()
        s = r(s, SchedulerIntent.SetCellTitle(freeRootCell(s), "Work"))
        s = r(s, SchedulerIntent.SetCellTitle(freeChildCell(s, taskWithTitle(s, "Work")), "Write"))
        return s
    }

    @Test
    fun the_lock_holds_the_middle_of_the_panel_closest_to_the_now_line() {
        val base = tree()
        val write = taskWithTitle(base, "Write")
        val s = base.copy(
            panels = listOf(
                panel("far", write, now + 300 * minute, now + 360 * minute),
                panel("near", write, now + 20 * minute, now + 40 * minute),
            ),
        )
        assertEquals(now + 30 * minute, CalendarLockDomain.closestPanelCenterMillis(s, write, now))
        // The panel the now-line is inside wins, whatever its length.
        val running = s.copy(panels = s.panels + panel("now", write, now - 60 * minute, now + 60 * minute))
        assertEquals(now, CalendarLockDomain.closestPanelCenterMillis(running, write, now))
        // A recorded period is a panel of the task too.
        val recorded = base.copy(
            tasks = base.tasks + (write to base.tasks.getValue(write).copy(record = listOf(TaskTimeRange(now - 10 * minute, now - 2 * minute)))),
        )
        assertEquals(now - 6 * minute, CalendarLockDomain.closestPanelCenterMillis(recorded, write, now))
        // When the panel moves, the lock follows: it is re-read off the panels.
        val moved = s.copy(panels = listOf(panel("near", write, now + 50 * minute, now + 70 * minute)))
        assertEquals(now + 60 * minute, CalendarLockDomain.lockCenterMillis(moved, write, now, now + 1_000 * minute))
        assertEquals(CalendarLockDomain.Reach.Panel, CalendarLockDomain.reach(s, write, now))
    }

    @Test
    fun a_provisional_panel_counts_as_the_tasks_panel() {
        // User spec 2026-09-26: the panels drawn past the definitive front by the rules in force — not settled yet —
        // are panels to lock on too.
        val s = tree()
        val write = taskWithTitle(s, "Write")
        val provisional = listOf(panel("far-week", write, now + 200 * 60 * minute, now + 200 * 60 * minute + 30 * minute))
        val front = now + 90 * minute
        assertEquals(CalendarLockDomain.Reach.Panel, CalendarLockDomain.reach(s, write, now, provisional))
        assertEquals(
            now + 200 * 60 * minute + 15 * minute,
            CalendarLockDomain.lockCenterMillis(s, write, now, front, provisional),
            "held on the provisional panel, not on the front",
        )
        // A settled panel closer to the now-line still wins over it.
        val settled = s.copy(panels = listOf(panel("near", write, now + 10 * minute, now + 20 * minute)))
        assertEquals(now + 15 * minute, CalendarLockDomain.closestPanelCenterMillis(settled, write, now, provisional))
    }

    @Test
    fun with_no_panel_yet_it_holds_the_definitive_front_and_with_none_possible_it_is_not_offered() {
        val s = tree()
        val write = taskWithTitle(s, "Write")
        val work = taskWithTitle(s, "Work")
        val front = now + 90 * minute
        assertNull(CalendarLockDomain.closestPanelCenterMillis(s, write, now))
        assertEquals(CalendarLockDomain.Reach.Pending, CalendarLockDomain.reach(s, write, now))
        assertEquals(front, CalendarLockDomain.lockCenterMillis(s, write, now, front))
        // A parent is a grouping the scheduler never places: no panel can come.
        assertEquals(CalendarLockDomain.Reach.None, CalendarLockDomain.reach(s, work, now))
        assertNull(CalendarLockDomain.lockCenterMillis(s, work, now, front))
    }
}
