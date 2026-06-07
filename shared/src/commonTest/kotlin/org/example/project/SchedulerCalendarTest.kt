package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.Cell
import org.example.project.scheduler.model.CellId
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.model.TaskPanel
import org.example.project.scheduler.model.TaskTimeRange
import org.example.project.scheduler.state.CalendarEdge
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState

/**
 * Tests for the v1.2.0 calendar/panel layer: §8 task panels (add / edit window / drag-move with
 * no-overlap snapping / extend-shorten with neighbour clamping / pin toggle), §5 calendar-focus-aware
 * Undo/Redo routing, §10 New Task overlap-avoidance with a pinned panel, and §12 device sleep.
 */
class SchedulerCalendarTest {

    private val MIN = 60_000L
    private val HOUR = 3_600_000L

    private fun userPanel(id: String, start: Long, end: Long, taskId: TaskId? = null, pinned: Boolean = false) =
        TaskPanel(id, taskId, id, start, end, pinned = pinned, auto = false)

    private fun autoPanel(id: String, taskId: TaskId, start: Long, end: Long) =
        TaskPanel(id, taskId, "x", start, end, pinned = false, auto = true)

    private fun range(start: Long, end: Long) = TaskTimeRange(start, end)

    /** Two equal-priority sibling tasks A and B under "main". */
    private fun stateWithTwoTasks(): Triple<SchedulerState, TaskId, TaskId> {
        var s = SchedulerState.empty()
        val root = s.rootListId
        val c0 = s.lists[root]!!.cellIds[0]
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(c0, "A"))
        val c1 = s.lists[root]!!.cellIds[1]
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(c1, "B"))
        val a = s.tasks.keys.first { s.tasks[it]!!.title == "A" }
        val b = s.tasks.keys.first { s.tasks[it]!!.title == "B" }
        return Triple(s, a, b)
    }

    // ----- §8 manual add (default task) -------------------------------------------------------

    @Test
    fun manual_add_picks_highest_priority_task_breaking_ties_alphabetically() {
        val (s, a, _) = stateWithTwoTasks()
        assertEquals(a, SchedulerDomain.manualAddTaskId(s)) // A and B tie → "A" wins
    }

    @Test
    fun manual_add_returns_null_when_there_is_no_real_task() {
        assertNull(SchedulerDomain.manualAddTaskId(SchedulerState.empty()))
    }

    @Test
    fun add_task_panel_adds_a_user_authored_panel_with_the_given_bounds_and_pin() {
        val (s, a, _) = stateWithTwoTasks()
        val start = 5_000_000L
        val withPanel =
            SchedulerReducer.reduce(s, SchedulerIntent.AddTaskPanel(a, "A", start, start + 45 * MIN, pinned = true))
        assertEquals(1, withPanel.panels.size)
        val panel = withPanel.panels[0]
        assertEquals(a, panel.taskId)
        assertEquals(start, panel.startEpochMillis)
        assertEquals(start + 45 * MIN, panel.endEpochMillis)
        assertTrue(panel.pinned)
        assertFalse(panel.auto)
    }

    // ----- §8 edit window ---------------------------------------------------------------------

    @Test
    fun update_panel_can_make_it_a_calendar_only_new_task() {
        val (s, a, _) = stateWithTwoTasks()
        val withPanel =
            SchedulerReducer.reduce(s, SchedulerIntent.AddTaskPanel(a, "A", 1_000_000L, 1_000_000L + 45 * MIN, false))
        val id = withPanel.panels[0].id
        val updated =
            SchedulerReducer.reduce(
                withPanel,
                SchedulerIntent.UpdateTaskPanel(id, null, "Brand new", 2_000_000L, 2_000_000L + 30 * MIN, false),
            )
        val panel = updated.panels[0]
        assertNull(panel.taskId)
        assertEquals("Brand new", panel.title)
        assertEquals(s.tasks.keys, updated.tasks.keys) // §8: no tree task created
    }

    @Test
    fun editing_an_auto_panel_makes_it_user_authored_and_pinned_with_a_fresh_id() {
        val (s0, a, _) = stateWithTwoTasks()
        val now = 1_000_000_000_000L
        val s = s0.copy(panels = listOf(autoPanel("auto/0", a, now, now + 45 * MIN)))
        val updated =
            SchedulerReducer.reduce(s, SchedulerIntent.UpdateTaskPanel("auto/0", a, "A", now, now + 45 * MIN, pinned = true))
        val panel = updated.panels[0]
        assertTrue(panel.pinned)
        assertFalse(panel.auto)
        assertNotEquals("auto/0", panel.id) // re-id'd out of the ephemeral auto namespace
    }

    // ----- §8 manual drag: no-overlap snapping (placeDraggedEntry / mergeOccupied) -------------

    @Test
    fun merge_occupied_fuses_touching_and_overlapping_blocks() {
        val merged =
            SchedulerDomain.mergeOccupied(listOf(range(0, 10), range(10, 20), range(30, 40), range(35, 50)))
        assertEquals(listOf(range(0, 20), range(30, 50)), merged)
    }

    @Test
    fun drag_sticks_to_the_end_of_a_group_when_past_its_midpoint() {
        val placed = SchedulerDomain.placeDraggedEntry(listOf(range(100, 200)), desiredStart = 160, duration = 40)
        assertEquals(range(200, 240), placed)
    }

    @Test
    fun drag_jumps_before_a_group_when_nearer_its_start() {
        val placed = SchedulerDomain.placeDraggedEntry(listOf(range(100, 200)), desiredStart = 90, duration = 40)
        assertEquals(range(60, 100), placed)
    }

    @Test
    fun move_intent_snaps_a_dragged_panel_before_an_occupied_block() {
        val s = SchedulerState.empty().copy(
            panels = listOf(userPanel("m1", 100 * MIN, 200 * MIN), userPanel("m2", 0, 40 * MIN)),
            nextPanelCounter = 2,
        )
        val moved = SchedulerReducer.reduce(s, SchedulerIntent.MoveTaskPanel("m2", 90 * MIN))
        val m2 = moved.panels.first { it.id == "m2" }
        assertEquals(60 * MIN, m2.startEpochMillis)
        assertEquals(100 * MIN, m2.endEpochMillis)
    }

    // ----- §8 extend / shorten (clampResize) --------------------------------------------------

    @Test
    fun resize_end_cannot_cross_the_next_neighbour() {
        val entry = range(100, 150)
        val clamped =
            SchedulerDomain.clampResize(listOf(range(200, 250)), entry, CalendarEdge.End, value = 300, minLength = 1)
        assertEquals(range(100, 200), clamped)
    }

    @Test
    fun resize_intent_clamps_the_end_at_the_neighbour() {
        val s = SchedulerState.empty().copy(
            panels = listOf(userPanel("m1", 0, 30 * MIN), userPanel("m2", 60 * MIN, 90 * MIN)),
            nextPanelCounter = 2,
        )
        val resized = SchedulerReducer.reduce(s, SchedulerIntent.ResizeTaskPanel("m1", CalendarEdge.End, 200 * MIN))
        assertEquals(60 * MIN, resized.panels.first { it.id == "m1" }.endEpochMillis)
    }

    // ----- §5 calendar-focus-aware Undo/Redo --------------------------------------------------

    @Test
    fun calendar_edits_are_undone_only_while_the_calendar_is_focused() {
        val (s, _, _) = stateWithTwoTasks()
        val withPanel =
            SchedulerReducer.reduce(s, SchedulerIntent.AddTaskPanel(null, "x", 1_000_000L, 1_000_000L + 45 * MIN, false))
        val focused = SchedulerReducer.reduce(withPanel, SchedulerIntent.SetCalendarFocus(true))

        val undone = SchedulerReducer.reduce(focused, SchedulerIntent.Undo)
        assertTrue(undone.panels.isEmpty())
        assertEquals(withPanel.cells, undone.cells) // tree untouched

        val redone = SchedulerReducer.reduce(undone, SchedulerIntent.Redo)
        assertEquals(1, redone.panels.size)
    }

    @Test
    fun unfocused_undo_skips_calendar_units_and_undoes_the_tree() {
        val (s, _, _) = stateWithTwoTasks()
        val withPanel =
            SchedulerReducer.reduce(s, SchedulerIntent.AddTaskPanel(null, "x", 1_000_000L, 1_000_000L + 45 * MIN, false))
        val undone = SchedulerReducer.reduce(withPanel, SchedulerIntent.Undo)
        assertEquals(1, undone.panels.size) // panel preserved
        assertNotEquals(withPanel.cells, undone.cells) // a tree mutation was reverted
    }

    // ----- §8 (uniform blocks): pinning a record into a panel ---------------------------------

    @Test
    fun pinning_a_record_period_moves_it_out_of_the_record_into_a_panel() {
        val (s0, a, _) = stateWithTwoTasks()
        val recorded = TaskTimeRange(10_000_000L, 10_000_000L + 45 * MIN)
        val s = s0.copy(tasks = s0.tasks + (a to s0.tasks[a]!!.copy(record = listOf(recorded))))

        val pinnedS =
            SchedulerReducer.reduce(
                s,
                SchedulerIntent.PinRecordAsPanel(
                    recordTaskId = a,
                    recordStartEpochMillis = recorded.startEpochMillis,
                    recordEndEpochMillis = recorded.endEpochMillis,
                    taskId = a,
                    title = s.tasks[a]!!.title,
                    startEpochMillis = recorded.startEpochMillis + 60 * MIN,
                    endEpochMillis = recorded.endEpochMillis + 60 * MIN,
                    pinned = true,
                ),
            )
        assertTrue(pinnedS.tasks[a]!!.record.isEmpty())
        assertEquals(1, pinnedS.panels.size)
        assertEquals(a, pinnedS.panels[0].taskId)
        assertTrue(pinnedS.panels[0].pinned)
    }

    // ----- §10 New Task overlap avoidance (pinned panels only) --------------------------------

    @Test
    fun next_pinned_start_after_only_considers_pinned_panels() {
        val (_, a, _) = stateWithTwoTasks()
        val pinned = listOf(userPanel("p", 100L, 200L, taskId = a, pinned = true))
        assertEquals(100L, SchedulerDomain.nextPinnedStartAfter(pinned, 0L))
        assertNull(SchedulerDomain.nextPinnedStartAfter(pinned, 150L)) // 100 not > 150
        // A non-pinned panel never constrains the auto fill.
        assertNull(SchedulerDomain.nextPinnedStartAfter(listOf(autoPanel("a", a, 100L, 200L)), 0L))
    }

    // ----- §8/§9 past panels count as time done ----------------------------------------------

    @Test
    fun past_panels_count_toward_time_weighting_so_an_over_served_task_is_not_re_picked() {
        val (s0, a, b) = stateWithTwoTasks()
        val now = 1_000_000_000_000L
        val s =
            s0.copy(
                panels =
                    listOf(
                        userPanel("e1", now - 5 * HOUR, now - 4 * HOUR, taskId = a),
                        userPanel("e2", now - 3 * HOUR, now - 2 * HOUR, taskId = a),
                        userPanel("e3", now - 90 * MIN, now - 30 * MIN, taskId = a),
                    ),
            )
        assertEquals(b, SchedulerDomain.nextTask(s, now))
    }

    @Test
    fun past_periods_for_task_clips_to_now_and_merges_records_and_panels() {
        val (s0, a, _) = stateWithTwoTasks()
        val now = 1_000_000L
        val s =
            s0.copy(
                tasks = s0.tasks + (a to s0.tasks[a]!!.copy(record = listOf(range(now - 2 * HOUR, now - HOUR)))),
                panels =
                    listOf(
                        userPanel("past", now - 30 * MIN, now + 30 * MIN, taskId = a), // straddles now → clipped
                        userPanel("future", now + HOUR, now + 2 * HOUR, taskId = a), // entirely future → dropped
                    ),
            )
        val periods = SchedulerDomain.pastPeriodsForTask(s, a, now)
        assertEquals(2, periods.size)
        assertTrue(periods.any { it == range(now - 2 * HOUR, now - HOUR) })
        assertTrue(periods.any { it == range(now - 30 * MIN, now) })
    }

    // ----- §9 next task is leaf-only (no child task) ------------------------------------------

    @Test
    fun next_task_excludes_tasks_that_have_child_tasks() {
        val (s0, a, b) = stateWithTwoTasks()
        val s = s0.copy(tasks = s0.tasks + (a to s0.tasks[a]!!.copy(childTaskIds = listOf(b))))
        assertFalse(SchedulerDomain.isLeafTask(s, a))
        assertTrue(SchedulerDomain.isLeafTask(s, b))
        assertEquals(b, SchedulerDomain.nextTask(s, 1_000_000_000_000L))
    }

    @Test
    fun an_auto_panel_whose_task_gains_a_child_is_cut_and_recorded_then_replaced() {
        val (s0, a, b) = stateWithTwoTasks()
        val now0 = 1_000_000_000_000L
        val s1 = s0.copy(panels = listOf(autoPanel("auto/0", a, now0, now0 + 45 * MIN)))
        // A gains a child (a populated cell pointing at B in A's child list) → A is no longer a leaf.
        val childListId = s0.tasks[a]!!.childListId!!
        val childCellId = CellId("a-child")
        val list = s0.lists[childListId]!!
        val s2 =
            s1.copy(
                cells = s1.cells + (childCellId to Cell(childCellId, childListId, taskId = b)),
                lists = s1.lists + (childListId to list.copy(cellIds = list.cellIds + childCellId)),
            )
        val now1 = now0 + 10 * MIN
        val advanced = SchedulerReducer.reduce(s2, SchedulerIntent.AdvanceSchedule(now1))
        assertTrue(range(now0, now1) in advanced.tasks[a]!!.record) // cut [start, now] recorded
        assertTrue(advanced.panels.isEmpty())
        // A subsequent refresh schedules the leaf B starting at the cut point.
        val refilled = SchedulerReducer.reduce(advanced, SchedulerIntent.RefreshSchedule(now1))
        assertEquals(b, refilled.panels.first().taskId)
        assertEquals(now1, refilled.panels.first().startEpochMillis)
    }

    // ----- §8 task contextual menu "Remove" ---------------------------------------------------

    @Test
    fun removing_a_panel_deletes_it_and_can_be_undone_when_focused() {
        val (s, _, _) = stateWithTwoTasks()
        val withPanel =
            SchedulerReducer.reduce(s, SchedulerIntent.AddTaskPanel(null, "x", 1_000_000L, 1_000_000L + 45 * MIN, false))
        val id = withPanel.panels[0].id
        val removed = SchedulerReducer.reduce(withPanel, SchedulerIntent.RemoveTaskPanel(id))
        assertTrue(removed.panels.isEmpty())
        val focused = SchedulerReducer.reduce(removed, SchedulerIntent.SetCalendarFocus(true))
        val undone = SchedulerReducer.reduce(focused, SchedulerIntent.Undo)
        assertEquals(1, undone.panels.size)
    }

    @Test
    fun removing_a_record_period_drops_it_from_the_task_record() {
        val (s0, a, _) = stateWithTwoTasks()
        val recorded = range(10_000_000L, 10_000_000L + 45 * MIN)
        val s = s0.copy(tasks = s0.tasks + (a to s0.tasks[a]!!.copy(record = listOf(recorded))))
        val removed =
            SchedulerReducer.reduce(
                s,
                SchedulerIntent.RemoveRecordPeriod(a, recorded.startEpochMillis, recorded.endEpochMillis),
            )
        assertTrue(removed.tasks[a]!!.record.isEmpty())
    }

    // ----- §12 device sleep ------------------------------------------------------------------

    @Test
    fun device_sleep_cuts_the_current_panel_leaving_a_hole_for_the_sleep_period() {
        val (s0, a, _) = stateWithTwoTasks()
        val start = 1_000_000_000_000L
        val sleepStart = start + 15 * MIN
        val wake = start + 90 * MIN
        val s = s0.copy(panels = listOf(autoPanel("auto/0", a, start, start + 45 * MIN)))

        val slept = SchedulerReducer.reduce(s, SchedulerIntent.ReportDeviceSleep(sleepStart, wake))
        assertTrue(slept.panels.isEmpty()) // the in-progress panel was cut & dropped
        assertTrue(range(start, sleepStart) in slept.tasks[a]!!.record) // only pre-sleep work recorded
        assertTrue(slept.tasks[a]!!.record.none { it.endEpochMillis > sleepStart && it.startEpochMillis < wake })

        val after = SchedulerReducer.reduce(slept, SchedulerIntent.RefreshSchedule(wake))
        assertEquals(wake, after.panels.first().startEpochMillis) // fresh panel starts after the sleep
    }

    @Test
    fun device_sleep_with_nothing_scheduled_is_a_no_op() {
        val (s0, _, _) = stateWithTwoTasks()
        val after = SchedulerReducer.reduce(s0, SchedulerIntent.ReportDeviceSleep(1_000L, 2_000L))
        assertTrue(after.panels.isEmpty())
        assertEquals(s0.tasks, after.tasks)
    }
}
