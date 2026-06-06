package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.Cell
import org.example.project.scheduler.model.CellId
import org.example.project.scheduler.model.ManualCalendarEntry
import org.example.project.scheduler.model.ScheduledTask
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.model.TaskTimeRange
import org.example.project.scheduler.persistence.SchedulerStateCodec
import org.example.project.scheduler.state.CalendarEdge
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState

/**
 * Tests for the v1.1.0 PRD additions (logic layer): §8 manual calendar entries (add / edit window /
 * drag-move with no-overlap snapping / extend-shorten with neighbour clamping), §5 calendar-focus-
 * aware Undo/Redo routing, and §10 New Task overlap-avoidance with a manually-placed future task.
 */
class SchedulerCalendarTest {

    private val MIN = 60_000L
    private val HOUR = 3_600_000L

    private fun manual(id: String, start: Long, end: Long) =
        ManualCalendarEntry(id, taskId = null, title = id, startEpochMillis = start, endEpochMillis = end)

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

    // ----- §8 manual add ----------------------------------------------------------------------

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
    fun add_manual_entry_spans_the_tasks_minimum_time_from_the_click_position() {
        val (s, a, _) = stateWithTwoTasks()
        val start = 5_000_000L
        val withEntry = SchedulerReducer.reduce(s, SchedulerIntent.AddManualCalendarEntry(start))
        assertEquals(1, withEntry.manualEntries.size)
        val entry = withEntry.manualEntries[0]
        assertEquals(a, entry.taskId)
        assertEquals(start, entry.startEpochMillis)
        assertEquals(start + 45 * MIN, entry.endEpochMillis) // default 45-min minimum span
    }

    @Test
    fun add_manual_entry_on_empty_database_is_a_no_op() {
        val after = SchedulerReducer.reduce(SchedulerState.empty(), SchedulerIntent.AddManualCalendarEntry(1_000L))
        assertTrue(after.manualEntries.isEmpty())
    }

    // ----- §8 edit window ---------------------------------------------------------------------

    @Test
    fun update_manual_entry_can_make_it_a_calendar_only_new_task() {
        val (s, _, _) = stateWithTwoTasks()
        val withEntry = SchedulerReducer.reduce(s, SchedulerIntent.AddManualCalendarEntry(1_000_000L))
        val id = withEntry.manualEntries[0].id
        val updated =
            SchedulerReducer.reduce(
                withEntry,
                SchedulerIntent.UpdateManualCalendarEntry(
                    id = id,
                    taskId = null,
                    title = "Brand new",
                    startEpochMillis = 2_000_000L,
                    endEpochMillis = 2_000_000L + 30 * MIN,
                ),
            )
        val entry = updated.manualEntries[0]
        assertNull(entry.taskId)
        assertEquals("Brand new", entry.title)
        // §8: creating a calendar "new task" does NOT add a task to the tree.
        assertEquals(s.tasks.keys, updated.tasks.keys)
    }

    // ----- §8 manual drag: no-overlap snapping (placeDraggedEntry / mergeOccupied) -------------

    @Test
    fun merge_occupied_fuses_touching_and_overlapping_blocks() {
        val merged =
            SchedulerDomain.mergeOccupied(listOf(range(0, 10), range(10, 20), range(30, 40), range(35, 50)))
        assertEquals(listOf(range(0, 20), range(30, 50)), merged)
    }

    @Test
    fun drag_into_free_space_lands_exactly_at_the_desired_position() {
        val placed = SchedulerDomain.placeDraggedEntry(emptyList(), desiredStart = 50, duration = 40)
        assertEquals(range(50, 90), placed)
    }

    @Test
    fun drag_sticks_to_the_end_of_a_group_when_past_its_midpoint() {
        // Drop [160,200) over the group [100,200): centre 180 > midpoint 150 → after the group.
        val placed = SchedulerDomain.placeDraggedEntry(listOf(range(100, 200)), desiredStart = 160, duration = 40)
        assertEquals(range(200, 240), placed)
    }

    @Test
    fun drag_jumps_before_a_group_when_nearer_its_start() {
        // Drop [90,130) over the group [100,200): centre 110 < midpoint 150 → before the group.
        val placed = SchedulerDomain.placeDraggedEntry(listOf(range(100, 200)), desiredStart = 90, duration = 40)
        assertEquals(range(60, 100), placed)
    }

    @Test
    fun drag_shrinks_to_fit_a_narrow_gap_before_a_group() {
        // Gap between [0,70) and [100,200) is only 30 wide; a 40-wide drag before the group shrinks.
        val placed =
            SchedulerDomain.placeDraggedEntry(listOf(range(0, 70), range(100, 200)), desiredStart = 90, duration = 40)
        assertEquals(range(70, 100), placed)
    }

    @Test
    fun drag_shrinks_to_fit_a_narrow_gap_after_a_group() {
        // Gap between [100,200) and [210,300) is only 10 wide; a 40-wide drag after the group shrinks.
        val placed =
            SchedulerDomain.placeDraggedEntry(listOf(range(100, 200), range(210, 300)), desiredStart = 160, duration = 40)
        assertEquals(range(200, 210), placed)
    }

    @Test
    fun move_intent_snaps_a_dragged_entry_before_an_occupied_block() {
        val s = SchedulerState.empty().copy(manualEntries = listOf(manual("m1", 100 * MIN, 200 * MIN), manual("m2", 0, 40 * MIN)))
        // Drag m2 (40-min wide) so it overlaps m1 nearer m1's start → it lands just before m1.
        val moved = SchedulerReducer.reduce(s, SchedulerIntent.MoveManualCalendarEntry("m2", 90 * MIN))
        val m2 = moved.manualEntries.first { it.id == "m2" }
        assertEquals(60 * MIN, m2.startEpochMillis)
        assertEquals(100 * MIN, m2.endEpochMillis)
    }

    // ----- §8 extend / shorten (clampResize) --------------------------------------------------

    @Test
    fun resize_end_cannot_cross_the_next_neighbour() {
        val entry = range(100, 150)
        val clamped =
            SchedulerDomain.clampResize(listOf(range(200, 250)), entry, CalendarEdge.End, value = 300, minLength = 1)
        assertEquals(range(100, 200), clamped) // clamped to the neighbour's start
    }

    @Test
    fun resize_start_cannot_cross_the_previous_neighbour() {
        val entry = range(100, 150)
        val clamped =
            SchedulerDomain.clampResize(listOf(range(0, 60)), entry, CalendarEdge.Start, value = 10, minLength = 1)
        assertEquals(range(60, 150), clamped) // clamped to the neighbour's end
    }

    @Test
    fun resize_never_shrinks_below_the_minimum_length() {
        val entry = range(0, 45 * MIN)
        // Pull the end far below the start: clamped up to start + minimum length.
        val clamped = SchedulerDomain.clampResize(emptyList(), entry, CalendarEdge.End, value = -5 * MIN)
        assertEquals(range(0, SchedulerDomain.MIN_MANUAL_ENTRY_MILLIS), clamped)
    }

    @Test
    fun resize_intent_clamps_the_end_at_the_neighbour() {
        val s = SchedulerState.empty().copy(manualEntries = listOf(manual("m1", 0, 30 * MIN), manual("m2", 60 * MIN, 90 * MIN)))
        val resized = SchedulerReducer.reduce(s, SchedulerIntent.ResizeManualCalendarEntry("m1", CalendarEdge.End, 200 * MIN))
        assertEquals(60 * MIN, resized.manualEntries.first { it.id == "m1" }.endEpochMillis)
    }

    // ----- §5 calendar-focus-aware Undo/Redo --------------------------------------------------

    @Test
    fun calendar_edits_are_undone_only_while_the_calendar_is_focused() {
        val (s, _, _) = stateWithTwoTasks()
        val withEntry = SchedulerReducer.reduce(s, SchedulerIntent.AddManualCalendarEntry(1_000_000L))
        val focused = SchedulerReducer.reduce(withEntry, SchedulerIntent.SetCalendarFocus(true))

        val undone = SchedulerReducer.reduce(focused, SchedulerIntent.Undo)
        assertTrue(undone.manualEntries.isEmpty())
        assertEquals(withEntry.cells, undone.cells) // tree untouched

        // A further Ctrl+Z while focused finds the calendar stack empty → skips everything else.
        val undoneAgain = SchedulerReducer.reduce(undone, SchedulerIntent.Undo)
        assertEquals(undone.cells, undoneAgain.cells)
        assertTrue(undoneAgain.manualEntries.isEmpty())

        val redone = SchedulerReducer.reduce(undone, SchedulerIntent.Redo)
        assertEquals(1, redone.manualEntries.size)
    }

    @Test
    fun unfocused_undo_skips_calendar_units_and_undoes_the_tree() {
        val (s, _, _) = stateWithTwoTasks()
        val withEntry = SchedulerReducer.reduce(s, SchedulerIntent.AddManualCalendarEntry(1_000_000L))
        val undone = SchedulerReducer.reduce(withEntry, SchedulerIntent.Undo)
        assertEquals(1, undone.manualEntries.size) // calendar entry preserved
        assertNotEquals(withEntry.cells, undone.cells) // a tree mutation was reverted
    }

    @Test
    fun undo_restores_a_moved_entry_to_its_prior_position() {
        val s =
            SchedulerState.empty().copy(manualEntries = listOf(manual("m1", 0, 40 * MIN)), nextManualEntryCounter = 1)
        val before = s.manualEntries[0]
        val moved = SchedulerReducer.reduce(s, SchedulerIntent.MoveManualCalendarEntry("m1", 10 * MIN))
        assertNotEquals(before, moved.manualEntries[0])
        val focused = SchedulerReducer.reduce(moved, SchedulerIntent.SetCalendarFocus(true))
        val undone = SchedulerReducer.reduce(focused, SchedulerIntent.Undo)
        assertEquals(before, undone.manualEntries[0])
    }

    // ----- §8 uniform blocks: pinning auto blocks into manual entries -------------------------

    @Test
    fun pinning_the_scheduled_block_makes_a_manual_entry_and_clears_the_schedule() {
        val (s0, a, _) = stateWithTwoTasks()
        val now = 1_000_000_000_000L
        val s = SchedulerReducer.reduce(s0, SchedulerIntent.RefreshSchedule(now))
        assertNotNull(s.scheduled)
        val sch = s.scheduled!!

        val pinned =
            SchedulerReducer.reduce(
                s,
                SchedulerIntent.PinScheduledAsManual(
                    taskId = sch.taskId,
                    title = s.tasks[sch.taskId]!!.title,
                    startEpochMillis = sch.startEpochMillis + 30 * MIN,
                    endEpochMillis = sch.deadlineEpochMillis + 30 * MIN,
                ),
            )
        assertNull(pinned.scheduled) // schedule cleared
        assertEquals(1, pinned.manualEntries.size)
        assertEquals(sch.taskId, pinned.manualEntries[0].taskId)
        assertEquals(sch.startEpochMillis + 30 * MIN, pinned.manualEntries[0].startEpochMillis)
    }

    @Test
    fun pinning_a_record_period_moves_it_out_of_the_record_into_a_manual_entry() {
        val (s0, a, _) = stateWithTwoTasks()
        val recorded = TaskTimeRange(10_000_000L, 10_000_000L + 45 * MIN)
        val s = s0.copy(tasks = s0.tasks + (a to s0.tasks[a]!!.copy(record = listOf(recorded))))

        val pinned =
            SchedulerReducer.reduce(
                s,
                SchedulerIntent.PinRecordAsManual(
                    recordTaskId = a,
                    recordStartEpochMillis = recorded.startEpochMillis,
                    recordEndEpochMillis = recorded.endEpochMillis,
                    taskId = a,
                    title = s.tasks[a]!!.title,
                    startEpochMillis = recorded.startEpochMillis + 60 * MIN,
                    endEpochMillis = recorded.endEpochMillis + 60 * MIN,
                ),
            )
        // The period left the record and became a manual entry at the new position.
        assertTrue(pinned.tasks[a]!!.record.isEmpty())
        assertEquals(1, pinned.manualEntries.size)
        assertEquals(a, pinned.manualEntries[0].taskId)
        assertEquals(recorded.startEpochMillis + 60 * MIN, pinned.manualEntries[0].startEpochMillis)
    }

    // ----- §10 New Task overlap avoidance -----------------------------------------------------

    @Test
    fun clamp_deadline_reduces_span_to_the_next_future_manual_entry() {
        val withEntry = SchedulerState.empty().copy(manualEntries = listOf(manual("m0", 100L, 200L)))
        assertEquals(100L, SchedulerDomain.clampDeadlineToManualEntries(withEntry, 0L, 150L))
        assertEquals(80L, SchedulerDomain.clampDeadlineToManualEntries(withEntry, 0L, 80L)) // no overlap
    }

    @Test
    fun clamp_deadline_ignores_entries_that_already_started() {
        val started = SchedulerState.empty().copy(manualEntries = listOf(manual("m0", -10L, 50L)))
        assertEquals(150L, SchedulerDomain.clampDeadlineToManualEntries(started, 0L, 150L))
    }

    @Test
    fun compute_schedule_shortens_a_new_task_to_avoid_a_future_manual_entry() {
        val (s0, a, b) = stateWithTwoTasks()
        val now = 1_000_000_000_000L
        val s =
            s0.copy(
                tasks =
                    s0.tasks +
                        (a to s0.tasks[a]!!.copy(record = listOf(range(now - HOUR, now)))) +
                        (b to s0.tasks[b]!!.copy(minimumMinutes = 60)),
            )
        assertEquals(now + 60 * MIN, SchedulerDomain.computeSchedule(s, now)!!.deadlineEpochMillis)

        val withManual = s.copy(manualEntries = listOf(manual("m0", now + 30 * MIN, now + 90 * MIN)))
        val sched = SchedulerDomain.computeSchedule(withManual, now)
        assertNotNull(sched)
        assertEquals(b, sched.taskId)
        assertEquals(now + 30 * MIN, sched.deadlineEpochMillis)
    }

    @Test
    fun refreshing_keeps_an_already_scheduled_task_from_overlapping_a_future_manual_entry() {
        val (s0, a, _) = stateWithTwoTasks()
        val now = 1_000_000_000_000L
        // A is already scheduled for a full 45-min minimum, but a manual entry starts in 20 min.
        val s =
            s0.copy(
                scheduled = ScheduledTask(a, now, now + 45 * MIN),
                manualEntries = listOf(manual("m", now + 20 * MIN, now + 80 * MIN)),
            )
        // A refresh a moment later must NOT re-extend A's deadline over the manual entry — it stays
        // clamped to the entry's start (the §10 overlap rule must hold on every refresh, not just at
        // first scheduling).
        val after = SchedulerReducer.reduce(s, SchedulerIntent.RefreshSchedule(now + 1_000))
        assertEquals(a, after.scheduled?.taskId)
        assertEquals(now + 20 * MIN, after.scheduled?.deadlineEpochMillis)
    }

    // ----- §8/§9 manual past entries count as time done (uniform blocks) ----------------------

    private fun manualFor(id: String, taskId: TaskId, start: Long, end: Long) =
        ManualCalendarEntry(id, taskId = taskId, title = id, startEpochMillis = start, endEpochMillis = end)

    @Test
    fun past_manual_entries_count_toward_time_weighting_so_an_over_served_task_is_not_re_picked() {
        val (s0, a, b) = stateWithTwoTasks() // A and B tie on absolute priority (50% each)
        val now = 1_000_000_000_000L
        // Task A was worked a lot in the past — but only via *manual* calendar entries (no record).
        val s =
            s0.copy(
                manualEntries =
                    listOf(
                        manualFor("e1", a, now - 5 * HOUR, now - 4 * HOUR),
                        manualFor("e2", a, now - 3 * HOUR, now - 2 * HOUR),
                        manualFor("e3", a, now - 90 * MIN, now - 30 * MIN),
                    ),
            )
        // A is now over-served relative to its 50% share, so the next task must be B.
        assertEquals(b, SchedulerDomain.nextTask(s, now))
    }

    @Test
    fun past_periods_for_task_clips_to_now_and_merges_records_and_manual_entries() {
        val (s0, a, _) = stateWithTwoTasks()
        val now = 1_000_000L
        val s =
            s0.copy(
                tasks = s0.tasks + (a to s0.tasks[a]!!.copy(record = listOf(range(now - 2 * HOUR, now - HOUR)))),
                manualEntries =
                    listOf(
                        manualFor("past", a, now - 30 * MIN, now + 30 * MIN), // straddles now → clipped
                        manualFor("future", a, now + HOUR, now + 2 * HOUR), // entirely future → dropped
                    ),
            )
        val periods = SchedulerDomain.pastPeriodsForTask(s, a, now)
        assertEquals(2, periods.size)
        assertTrue(periods.any { it == range(now - 2 * HOUR, now - HOUR) }) // the record
        assertTrue(periods.any { it == range(now - 30 * MIN, now) }) // clipped manual entry
    }

    // ----- §9 next task is leaf-only (no child task) ------------------------------------------

    @Test
    fun next_task_excludes_tasks_that_have_child_tasks() {
        val (s0, a, b) = stateWithTwoTasks()
        // Make A a parent of B → A now has a child task, so it is not schedulable (PRD §9).
        val s = s0.copy(tasks = s0.tasks + (a to s0.tasks[a]!!.copy(childTaskIds = listOf(b))))
        assertFalse(SchedulerDomain.isLeafTask(s, a))
        assertTrue(SchedulerDomain.isLeafTask(s, b))
        // Only the leaf B may be picked, even though A and B tie on absolute priority.
        assertEquals(b, SchedulerDomain.nextTask(s, 1_000_000_000_000L))
    }

    @Test
    fun next_task_excludes_a_parent_detected_structurally_when_childTaskIds_is_stale() {
        val (s0, a, b) = stateWithTwoTasks()
        // Put a populated cell into A's (always-present) child list, but leave A.childTaskIds empty —
        // i.e. the denormalized cache is out of sync, which is the real-world anomaly cause.
        val childListId = s0.tasks[a]!!.childListId!!
        val childCellId = CellId("a-child")
        val list = s0.lists[childListId]!!
        val s =
            s0.copy(
                cells = s0.cells + (childCellId to Cell(childCellId, childListId, taskId = b)),
                lists = s0.lists + (childListId to list.copy(cellIds = list.cellIds + childCellId)),
            )
        assertTrue(s.tasks[a]!!.childTaskIds.isEmpty()) // stale cache
        assertFalse(SchedulerDomain.isLeafTask(s, a)) // but structurally A has a child
        assertEquals(b, SchedulerDomain.nextTask(s, 1_000_000_000_000L))
    }

    @Test
    fun a_scheduled_task_that_gains_a_child_is_cut_and_recorded_then_replaced() {
        val (s0, a, b) = stateWithTwoTasks()
        val now0 = 1_000_000_000_000L
        // A is the task to do now, mid-period.
        val s1 = s0.copy(scheduled = ScheduledTask(a, now0, now0 + 45 * MIN))
        // A gains a child (a populated cell pointing at B in A's child list) → A is no longer a leaf.
        val childListId = s0.tasks[a]!!.childListId!!
        val childCellId = CellId("a-child")
        val list = s0.lists[childListId]!!
        val s2 =
            s1.copy(
                cells = s1.cells + (childCellId to Cell(childCellId, childListId, taskId = b)),
                lists = s1.lists + (childListId to list.copy(cellIds = list.cellIds + childCellId)),
            )
        // Refresh BEFORE A's deadline: A must be cut at `now`, recorded, and replaced by a leaf.
        val now1 = now0 + 10 * MIN
        val after = SchedulerReducer.reduce(s2, SchedulerIntent.RefreshSchedule(now1))
        assertTrue(range(now0, now1) in after.tasks[a]!!.record) // cut [start, now] recorded
        assertEquals(b, after.scheduled?.taskId) // A excluded; the leaf B is scheduled now
        assertEquals(now1, after.scheduled?.startEpochMillis) // next period starts where A was cut
    }

    // ----- §8 task contextual menu "Remove" ---------------------------------------------------

    @Test
    fun removing_a_manual_entry_deletes_it_and_can_be_undone_when_focused() {
        val (s, _, _) = stateWithTwoTasks()
        val withEntry = SchedulerReducer.reduce(s, SchedulerIntent.AddManualCalendarEntry(1_000_000L))
        val id = withEntry.manualEntries[0].id
        val removed = SchedulerReducer.reduce(withEntry, SchedulerIntent.RemoveManualCalendarEntry(id))
        assertTrue(removed.manualEntries.isEmpty())
        // Calendar delta → undoable while the calendar is focused.
        val focused = SchedulerReducer.reduce(removed, SchedulerIntent.SetCalendarFocus(true))
        val undone = SchedulerReducer.reduce(focused, SchedulerIntent.Undo)
        assertEquals(1, undone.manualEntries.size)
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

    @Test
    fun removing_the_scheduled_block_clears_the_current_allocation() {
        val (s0, _, _) = stateWithTwoTasks()
        val s = SchedulerReducer.reduce(s0, SchedulerIntent.RefreshSchedule(1_000_000_000_000L))
        assertNotNull(s.scheduled)
        val cleared = SchedulerReducer.reduce(s, SchedulerIntent.RemoveScheduledNow)
        assertNull(cleared.scheduled)
    }

    // ----- persistence -------------------------------------------------------------------------

    @Test
    fun codec_round_trip_preserves_manual_entries_and_counter() {
        val (s, _, _) = stateWithTwoTasks()
        val prepared = SchedulerReducer.reduce(s, SchedulerIntent.AddManualCalendarEntry(123_000L))
        val decoded = SchedulerStateCodec.decode(SchedulerStateCodec.encode(prepared))
        assertNotNull(decoded)
        assertEquals(prepared.manualEntries, decoded.manualEntries)
        assertEquals(prepared.nextManualEntryCounter, decoded.nextManualEntryCounter)
    }

    @Test
    fun codec_decodes_old_payload_without_manual_entries() {
        val json =
            """
            {"rootListId":"L","lists":[{"id":"L","parentCellId":null,"cellIds":["c0"]}],
             "cells":[{"id":"c0","parentListId":"L","taskId":null}],
             "tasks":[{"id":"t0","title":"X"}]}
            """.trimIndent()
        val decoded = SchedulerStateCodec.decode(json)
        assertNotNull(decoded)
        assertTrue(decoded.manualEntries.isEmpty())
        assertEquals(0, decoded.nextManualEntryCounter)
    }
}
