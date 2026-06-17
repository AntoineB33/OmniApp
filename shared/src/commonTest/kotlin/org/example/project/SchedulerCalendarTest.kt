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
import org.example.project.scheduler.persistence.SchedulerStateCodec
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

    // ----- §8 same-task auto-merge ------------------------------------------------------------

    @Test
    fun merge_same_task_panels_fuses_touching_same_task_blocks() {
        val a = TaskId("t/a")
        val merged = SchedulerDomain.mergeSameTaskPanels(
            listOf(
                TaskPanel("p0", a, "A", 0, 10 * MIN, pinned = false, auto = false),
                TaskPanel("p1", a, "A", 10 * MIN, 25 * MIN, pinned = false, auto = false),
            ),
        )
        assertEquals(1, merged.size)
        assertEquals("p0", merged[0].id) // earlier panel survives
        assertEquals(0, merged[0].startEpochMillis)
        assertEquals(25 * MIN, merged[0].endEpochMillis)
    }

    @Test
    fun merge_same_task_panels_does_not_fuse_when_pin_state_differs() {
        val a = TaskId("t/a")
        val panels = listOf(
            TaskPanel("p0", a, "A", 0, 10 * MIN, pinned = true, auto = false),
            TaskPanel("p1", a, "A", 10 * MIN, 25 * MIN, pinned = false, auto = false),
        )
        assertEquals(panels, SchedulerDomain.mergeSameTaskPanels(panels)) // one pinned, one not → kept apart
    }

    @Test
    fun merge_same_task_panels_leaves_different_tasks_and_calendar_only_panels_alone() {
        val a = TaskId("t/a")
        val b = TaskId("t/b")
        val panels = listOf(
            TaskPanel("p0", a, "A", 0, 10 * MIN, pinned = false, auto = false),
            TaskPanel("p1", b, "B", 10 * MIN, 20 * MIN, pinned = false, auto = false),
            TaskPanel("p2", null, "new", 20 * MIN, 30 * MIN, pinned = false, auto = false),
            TaskPanel("p3", null, "new", 30 * MIN, 40 * MIN, pinned = false, auto = false),
        )
        assertEquals(panels, SchedulerDomain.mergeSameTaskPanels(panels)) // distinct tasks / null taskId never merge
    }

    @Test
    fun merge_same_task_panels_does_not_fuse_across_a_gap() {
        val a = TaskId("t/a")
        val panels = listOf(
            TaskPanel("p0", a, "A", 0, 10 * MIN, pinned = false, auto = false),
            TaskPanel("p1", a, "A", 20 * MIN, 30 * MIN, pinned = false, auto = false), // not touching p0
        )
        assertEquals(panels, SchedulerDomain.mergeSameTaskPanels(panels))
    }

    @Test
    fun merged_panel_stays_auto_only_when_both_were_auto() {
        val a = TaskId("t/a")
        val twoAuto = SchedulerDomain.mergeSameTaskPanels(
            listOf(autoPanel("auto/0", a, 0, 10 * MIN), autoPanel("auto/1", a, 10 * MIN, 20 * MIN)),
        )
        assertTrue(twoAuto.single().auto)
        val autoPlusUser = SchedulerDomain.mergeSameTaskPanels(
            listOf(
                autoPanel("auto/0", a, 0, 10 * MIN),
                TaskPanel("p1", a, "A", 10 * MIN, 20 * MIN, pinned = false, auto = false),
            ),
        )
        assertFalse(autoPlusUser.single().auto) // a user-authored half makes the result user-authored
    }

    @Test
    fun adding_a_panel_touching_a_same_task_panel_merges_them() {
        val (s0, a, _) = stateWithTwoTasks()
        val s = s0.copy(
            panels = listOf(TaskPanel("panel/0", a, "A", 0, 30 * MIN, pinned = true, auto = false)),
            nextPanelCounter = 1,
        )
        val added = SchedulerReducer.reduce(s, SchedulerIntent.AddTaskPanel(a, "A", 30 * MIN, 60 * MIN, pinned = true))
        assertEquals(1, added.panels.size)
        assertEquals(0, added.panels[0].startEpochMillis)
        assertEquals(60 * MIN, added.panels[0].endEpochMillis)
        assertTrue(added.panels[0].pinned)
    }

    // ----- §8 same-task display grouping (auto sessions shown as one block) --------------------

    @Test
    fun display_grouping_fuses_consecutive_same_task_sessions_keeping_their_ids() {
        val a = TaskId("t/a")
        // Three back-to-back auto sessions of the same task — the display-grouping input shape.
        val groups = SchedulerDomain.groupSameTaskPanelsForDisplay(
            listOf(
                autoPanel("auto/0", a, 0, 45 * MIN),
                autoPanel("auto/1", a, 45 * MIN, 90 * MIN),
                autoPanel("auto/2", a, 90 * MIN, 135 * MIN),
            ),
        )
        assertEquals(1, groups.size) // shown as a single block
        assertEquals(listOf("auto/0", "auto/1", "auto/2"), groups[0].map { it.id }) // all backing ids kept
    }

    @Test
    fun display_grouping_splits_on_task_change_pin_change_and_gaps() {
        val a = TaskId("t/a")
        val b = TaskId("t/b")
        val groups = SchedulerDomain.groupSameTaskPanelsForDisplay(
            listOf(
                autoPanel("auto/0", a, 0, 45 * MIN),
                autoPanel("auto/1", b, 45 * MIN, 90 * MIN), // different task → new block
                TaskPanel("p2", a, "A", 90 * MIN, 135 * MIN, pinned = true, auto = false),
                TaskPanel("p3", a, "A", 135 * MIN, 180 * MIN, pinned = false, auto = false), // pin differs → new block
                autoPanel("auto/4", a, 240 * MIN, 300 * MIN), // gap before it → new block
            ),
        )
        assertEquals(listOf(listOf("auto/0"), listOf("auto/1"), listOf("p2"), listOf("p3"), listOf("auto/4")),
            groups.map { g -> g.map { it.id } })
    }

    @Test
    fun display_grouping_never_groups_calendar_only_panels() {
        val groups = SchedulerDomain.groupSameTaskPanelsForDisplay(
            listOf(
                TaskPanel("p0", null, "new", 0, 45 * MIN, pinned = false, auto = false),
                TaskPanel("p1", null, "new", 45 * MIN, 90 * MIN, pinned = false, auto = false),
            ),
        )
        assertEquals(2, groups.size) // null taskId is not "the same task"
    }

    @Test
    fun display_grouping_bridges_a_same_task_gap_left_by_a_hidden_side_task() {
        // PRD §15 (side tasks hidden): two same-task panels split around a side task fuse into one block
        // when the side-task gap is given as a bridge region — purely cosmetic, the panels stay separate.
        val a = TaskId("t/a")
        val panels = listOf(
            autoPanel("auto/0", a, 0, 20 * MIN),
            autoPanel("auto/1", a, 25 * MIN, 50 * MIN), // a 5-min gap = the hidden side task
        )
        // Side tasks shown (no bridge): the gap splits them into two blocks.
        assertEquals(2, SchedulerDomain.groupSameTaskPanelsForDisplay(panels).size)
        // Side tasks hidden: the gap's side region bridges them into one block keeping both ids.
        val bridged = SchedulerDomain.groupSameTaskPanelsForDisplay(
            panels,
            bridgeRegions = listOf(TaskTimeRange(20 * MIN, 25 * MIN)),
        )
        assertEquals(1, bridged.size)
        assertEquals(listOf("auto/0", "auto/1"), bridged[0].map { it.id })
        // A bridge region that doesn't cover the gap leaves them split.
        assertEquals(
            2,
            SchedulerDomain.groupSameTaskPanelsForDisplay(panels, listOf(TaskTimeRange(30 * MIN, 35 * MIN))).size,
        )
    }

    @Test
    fun remove_task_panels_deletes_every_backing_panel_in_one_delta() {
        val a = TaskId("t/a")
        val s = SchedulerState.empty().copy(
            panels = listOf(autoPanel("auto/0", a, 0, 45 * MIN), autoPanel("auto/1", a, 45 * MIN, 90 * MIN)),
        )
        val removed = SchedulerReducer.reduce(s, SchedulerIntent.RemoveTaskPanels(listOf("auto/0", "auto/1")))
        assertTrue(removed.panels.isEmpty())
    }

    @Test
    fun replace_task_panels_swaps_a_merged_run_for_one_user_panel() {
        val a = TaskId("t/a")
        val b = TaskId("t/b")
        val s = SchedulerState.empty().copy(
            panels = listOf(
                autoPanel("auto/0", a, 0, 45 * MIN),
                autoPanel("auto/1", a, 45 * MIN, 90 * MIN),
                autoPanel("auto/2", b, 90 * MIN, 135 * MIN), // a different-task block left untouched
            ),
        )
        val replaced = SchedulerReducer.reduce(
            s,
            SchedulerIntent.ReplaceTaskPanels(listOf("auto/0", "auto/1"), a, "A", 0, 90 * MIN, pinned = true),
        )
        // The two A sessions are gone, replaced by one pinned user panel; the B block stays.
        assertTrue(replaced.panels.none { it.id == "auto/0" || it.id == "auto/1" })
        val newPanel = replaced.panels.first { it.taskId == a }
        assertEquals(0, newPanel.startEpochMillis)
        assertEquals(90 * MIN, newPanel.endEpochMillis)
        assertTrue(newPanel.pinned)
        assertFalse(newPanel.auto)
        assertTrue(replaced.panels.any { it.id == "auto/2" }) // untouched
    }

    // ----- §8 Overlap Mode (allowOverlap weight seeding) --------------------------------------

    @Test
    fun update_with_allow_overlap_keeps_raw_bounds_and_seeds_one_over_n_weight() {
        val a = TaskId("t/a")
        val b = TaskId("t/b")
        val s = SchedulerState.empty().copy(
            panels = listOf(
                userPanel("B", 0, HOUR, b, pinned = true),
                userPanel("A", 2 * HOUR, 3 * HOUR, a, pinned = true),
            ),
            nextPanelCounter = 2,
        )
        // Drop A onto B with overlap armed: bounds stay raw (overlapping), width seeded to 1/2.
        val updated = SchedulerReducer.reduce(
            s,
            SchedulerIntent.UpdateTaskPanel("A", a, "A", 0, HOUR, pinned = true, allowOverlap = true),
        )
        val pa = updated.panels.first { it.id == "A" }
        assertEquals(0, pa.startEpochMillis)
        assertEquals(HOUR, pa.endEpochMillis)
        assertEquals(1.0, pa.layoutWeight, 1e-9) // one other of weight 1 ⇒ w = S/k = 1 ⇒ fraction 1/2
    }

    @Test
    fun overlap_commit_keeps_overlapping_bounds() {
        val a = TaskId("t/a")
        val b = TaskId("t/b")
        val s = SchedulerState.empty().copy(
            panels = listOf(
                userPanel("pA", 0, 2 * HOUR, a, pinned = true),
                userPanel("pB", 3 * HOUR, 5 * HOUR, b, pinned = true),
            ),
            nextPanelCounter = 2,
        )
        val moved = SchedulerReducer.reduce(
            s,
            SchedulerIntent.UpdateTaskPanel("pB", b, "B", 1 * HOUR, 3 * HOUR, pinned = true, allowOverlap = true),
        )
        val pA = moved.panels.first { it.id == "pA" }
        val pB = moved.panels.first { it.id == "pB" }
        assertTrue(
            pB.startEpochMillis < pA.endEpochMillis && pA.startEpochMillis < pB.endEpochMillis,
            "overlap-armed commit should keep overlapping bounds: A=$pA B=$pB",
        )
    }

    @Test
    fun update_without_allow_overlap_preserves_existing_weight() {
        val a = TaskId("t/a")
        val s = SchedulerState.empty().copy(
            panels = listOf(TaskPanel("A", a, "A", 0, HOUR, pinned = true, auto = false, layoutWeight = 5.0)),
            nextPanelCounter = 1,
        )
        val updated = SchedulerReducer.reduce(
            s,
            SchedulerIntent.UpdateTaskPanel("A", a, "A2", 0, 2 * HOUR, pinned = true),
        )
        assertEquals(5.0, updated.panels.first { it.id == "A" }.layoutWeight, 1e-9)
    }

    @Test
    fun allow_overlap_seed_honours_other_panels_weight_ratios() {
        val a = TaskId("t/a")
        val b = TaskId("t/b")
        val c = TaskId("t/c")
        val s = SchedulerState.empty().copy(
            panels = listOf(
                TaskPanel("B", b, "B", 0, HOUR, pinned = true, auto = false, layoutWeight = 2.0),
                TaskPanel("C", c, "C", 0, HOUR, pinned = true, auto = false, layoutWeight = 4.0),
                userPanel("A", 5 * HOUR, 6 * HOUR, a, pinned = true),
            ),
            nextPanelCounter = 3,
        )
        val updated = SchedulerReducer.reduce(
            s,
            SchedulerIntent.UpdateTaskPanel("A", a, "A", 0, HOUR, pinned = true, allowOverlap = true),
        )
        // Overlaps B(2) + C(4): S = 6, k = 2 ⇒ w = 3.0 (fraction 3/9 = 1/3 = 1/n).
        assertEquals(3.0, updated.panels.first { it.id == "A" }.layoutWeight, 1e-9)
    }

    @Test
    fun set_panel_weights_updates_and_is_undoable() {
        val a = TaskId("t/a")
        val b = TaskId("t/b")
        val s = SchedulerState.empty().copy(
            panels = listOf(
                userPanel("A", 0, HOUR, a, pinned = true),
                userPanel("B", 0, HOUR, b, pinned = true),
            ),
            nextPanelCounter = 2,
        ).copy(calendarFocused = true)
        val adjusted = SchedulerReducer.reduce(
            s,
            SchedulerIntent.SetPanelWeights(mapOf("A" to 3.0, "B" to 1.0)),
        )
        assertEquals(3.0, adjusted.panels.first { it.id == "A" }.layoutWeight, 1e-9)
        assertEquals(1.0, adjusted.panels.first { it.id == "B" }.layoutWeight, 1e-9)
        // Recorded in the calendar history (PRD §8) → Ctrl+Z restores the prior widths.
        val undone = SchedulerReducer.reduce(adjusted, SchedulerIntent.Undo)
        assertEquals(1.0, undone.panels.first { it.id == "A" }.layoutWeight, 1e-9)
        assertEquals(1.0, undone.panels.first { it.id == "B" }.layoutWeight, 1e-9)
    }

    @Test
    fun codec_round_trips_panel_layout_weight() {
        val a = TaskId("t/a")
        val s = SchedulerState.empty().copy(
            panels = listOf(TaskPanel("A", a, "A", 0, HOUR, pinned = true, auto = false, layoutWeight = 2.5)),
            nextPanelCounter = 1,
        )
        val decoded = SchedulerStateCodec.decode(SchedulerStateCodec.encode(s))
        assertEquals(2.5, decoded!!.panels.first { it.id == "A" }.layoutWeight, 1e-9)
    }

    @Test
    fun codec_round_trips_the_show_side_tasks_preference() {
        // PRD §15: the calendar's side-task display switch persists across sessions; default is on.
        assertTrue(SchedulerState.empty().showSideTasks)
        val hidden = SchedulerState.empty().copy(showSideTasks = false)
        assertEquals(false, SchedulerStateCodec.decode(SchedulerStateCodec.encode(hidden))!!.showSideTasks)
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

    // ----- §8/§9 past panels still feed the §10 continuous-effort credit ----------------------

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

    // ----- §8 calendar edit window: leaf-only menus + first-task default ----------------------

    @Test
    fun calendar_task_menu_only_offers_leaf_tasks() {
        val (s0, a, b) = stateWithTwoTasks()
        // A gains a child → A is a parent (non-leaf); B stays a leaf.
        val s = s0.copy(tasks = s0.tasks + (a to s0.tasks[a]!!.copy(childTaskIds = listOf(b))))
        // The parent A must not appear in the calendar menu, even though its title matches exactly.
        val entriesA = SchedulerDomain.calendarTaskMenuEntries(s, "A")
        assertTrue(entriesA.none { it.taskId == a }, "parent A leaked into the calendar menu")
        assertEquals(null, SchedulerDomain.calendarDefaultMenuTaskId(entriesA)) // only "New task"
        // The leaf B appears and is the menu's default selection (not "New task").
        val entriesB = SchedulerDomain.calendarTaskMenuEntries(s, "B")
        assertTrue(entriesB.any { it.taskId == b })
        assertEquals(b, SchedulerDomain.calendarDefaultMenuTaskId(entriesB))
    }

    @Test
    fun calendar_title_suggestions_and_lookup_are_leaf_only() {
        val (s0, a, b) = stateWithTwoTasks()
        val s = s0.copy(tasks = s0.tasks + (a to s0.tasks[a]!!.copy(childTaskIds = listOf(b))))
        val suggestions = SchedulerDomain.calendarTitleSuggestions(s, "")
        assertTrue("B" in suggestions, "leaf B should be suggested")
        assertFalse("A" in suggestions, "parent A should not be suggested")
        assertEquals(b, SchedulerDomain.calendarTaskIdForTitle(s, "B"))
        assertEquals(null, SchedulerDomain.calendarTaskIdForTitle(s, "A"))
    }

    @Test
    fun manual_add_default_task_is_a_leaf() {
        val (s0, a, b) = stateWithTwoTasks()
        val s = s0.copy(tasks = s0.tasks + (a to s0.tasks[a]!!.copy(childTaskIds = listOf(b))))
        // A is the higher-priority candidate but is now a parent, so the leaf B is chosen instead.
        assertEquals(b, SchedulerDomain.manualAddTaskId(s))
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

    @Test
    fun a_device_sleep_counts_as_taking_every_side_task_it_covers() {
        // PRD §15: a device sleep is the user resting — it satisfies every side task whose duration it
        // covers (a long sleep clears the shorter pauses too), recording the wake as their last rest.
        val (s0, _, _) = stateWithTwoTasks()
        val sides = listOf(
            org.example.project.scheduler.model.SideTask("5min", 60 * MIN, 5 * MIN, restBreak = true),
            org.example.project.scheduler.model.SideTask("15min", 2 * HOUR, 15 * MIN, restBreak = true),
        )
        val start = 1_000_000_000_000L
        val s = s0.copy(sideTasks = sides)

        // A 10-min sleep covers the 5-min pause but not the 15-min one.
        val short = SchedulerReducer.reduce(s, SchedulerIntent.ReportDeviceSleep(start, start + 10 * MIN))
        assertEquals(start + 10 * MIN, short.sideTasks[0].lastRestMillis) // 5-min pause satisfied
        assertEquals(0L, short.sideTasks[1].lastRestMillis) // 15-min pause not (sleep too short)

        // A 20-min sleep covers both.
        val long = SchedulerReducer.reduce(s, SchedulerIntent.ReportDeviceSleep(start, start + 20 * MIN))
        assertEquals(start + 20 * MIN, long.sideTasks[0].lastRestMillis)
        assertEquals(start + 20 * MIN, long.sideTasks[1].lastRestMillis)
    }

    @Test
    fun an_overdue_rest_pause_tracks_the_now_line_on_refill_without_overlapping_the_task() {
        // PRD §15: an overdue rest pause must follow `now` (so an accelerated clock can't strand it) — this
        // happens on each refill (the §15 keep-up tick refills while a pause is due). Crucially the task is
        // re-split around the pause's NEW position, so the pause never overlaps the task fill.
        val (s0, _, _) = stateWithTwoTasks()
        val start = 1_000_000_000_000L
        val s = s0.copy(
            sideTasks = listOf(org.example.project.scheduler.model.SideTask("5min", 60 * MIN, 5 * MIN, restBreak = true)),
        )

        val t1 = SchedulerReducer.reduce(s, SchedulerIntent.RefreshSchedule(start))
        val pause1 = t1.panels.filter { it.sideTask }.minByOrNull { it.startEpochMillis }!!
        assertEquals(start, pause1.startEpochMillis)
        assertEquals(start + 5 * MIN, pause1.endEpochMillis)

        // The clock jumps forward 12 min: a refill re-places the pause at the new now and re-splits the task.
        val t2 = SchedulerReducer.reduce(t1, SchedulerIntent.RefreshSchedule(start + 12 * MIN))
        val pause2 = t2.panels.filter { it.sideTask }.minByOrNull { it.startEpochMillis }!!
        assertEquals(start + 12 * MIN, pause2.startEpochMillis)
        assertEquals(start + 17 * MIN, pause2.endEpochMillis)
        // No task panel overlaps the pause's region — the fill leaves it a clean place (PRD §15).
        assertTrue(
            t2.panels.none {
                !it.sideTask && it.taskId != null &&
                    it.startEpochMillis < pause2.endEpochMillis && pause2.startEpochMillis < it.endEpochMillis
            },
        )

        // Once the user rests it away (recent enough), the next refill shows it ahead at its due time, not at now.
        val rested = t2.copy(
            sideTasks = listOf(
                org.example.project.scheduler.model.SideTask(
                    "5min", 60 * MIN, 5 * MIN, restBreak = true, lastRestMillis = start + 12 * MIN,
                ),
            ),
        )
        val t3 = SchedulerReducer.reduce(rested, SchedulerIntent.RefreshSchedule(start + 13 * MIN))
        val pause3 = t3.panels.filter { it.sideTask }.minByOrNull { it.startEpochMillis }!!
        assertEquals(start + 12 * MIN + 60 * MIN, pause3.startEpochMillis) // lastRest + interval, in the future
    }
}
