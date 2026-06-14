package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.model.TaskPanel
import org.example.project.scheduler.model.TaskTimeRange
import org.example.project.scheduler.persistence.SchedulerStateCodec
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState
import org.example.project.ui.CalendarRecord
import org.example.project.ui.recordsForDay

/**
 * Tests for the v1.2.0 scheduler: §9 windowed multi-panel fill ([SchedulerDomain.fillSchedule]),
 * the §9 advance/record tick, §10 minimum time, §7 automatic-schedule switch, and §8 task record
 * (history-excluded, persisted, calendar-mapped).
 */
class SchedulerSchedulerTest {

    private val MIN = 60_000L
    private val HOUR_MS = 3_600_000L

    /** Two equal-priority sibling tasks A and B under "main". */
    private fun stateWithTwoTasks(): Triple<SchedulerState, TaskId, TaskId> {
        var s = SchedulerState.empty()
        val root = s.rootListId
        val c0 = s.lists[root]!!.cellIds[0]
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(c0, "A"))
        val c1 = s.lists[root]!!.cellIds[1] // auto-appended empty placeholder
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(c1, "B"))
        val a = s.tasks.keys.first { s.tasks[it]!!.title == "A" }
        val b = s.tasks.keys.first { s.tasks[it]!!.title == "B" }
        return Triple(s, a, b)
    }

    /** Two siblings A and B with priority weights 3 / 1 → absolute priorities 0.75 / 0.25. */
    private fun stateWithWeightedTasks(): Triple<SchedulerState, TaskId, TaskId> {
        val (s0, a, b) = stateWithTwoTasks()
        val cA = s0.lists[s0.rootListId]!!.cellIds[0]
        val s = SchedulerReducer.reduce(s0, SchedulerIntent.SetPriorityWeight(cA, 0, 3.0))
        return Triple(s, a, b)
    }

    private fun auto(id: String, taskId: TaskId, start: Long, end: Long) =
        TaskPanel(id, taskId, "x", start, end, pinned = false, auto = true)

    private fun pinned(id: String, taskId: TaskId, start: Long, end: Long) =
        TaskPanel(id, taskId, "x", start, end, pinned = true, auto = false)

    /** A single task "Solo" with the given minimum time (minutes). */
    private fun stateWithOneTask(minMinutes: Int): Pair<SchedulerState, TaskId> {
        var s = SchedulerState.empty()
        val c0 = s.lists[s.rootListId]!!.cellIds[0]
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(c0, "Solo"))
        val solo = s.tasks.keys.first { s.tasks[it]!!.title == "Solo" }
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetTaskMinimumTime(solo, minMinutes))
        return s to solo
    }

    // ----- §9 task choice: Earliest Deadline First --------------------------------------------

    @Test
    fun edf_period_is_minimum_time_over_priority() {
        // PRD §9: T = m / p. A 45-min task at 50% priority recurs every 90 min; at 25% every 180 min.
        assertEquals(90.0 * MIN, SchedulerDomain.edfPeriodMillis(45, 0.5), 1e-6)
        assertEquals(180.0 * MIN, SchedulerDomain.edfPeriodMillis(45, 0.25), 1e-6)
        // Zero priority → infinite period (only scheduled as a last resort, never "due").
        assertTrue(SchedulerDomain.edfPeriodMillis(45, 0.0).isInfinite())
    }

    @Test
    fun next_task_picks_the_earliest_deadline_highest_priority_first() {
        // PRD §9 EDF: A (p=0.75) has the shorter period T=45/0.75=60min, so it is due before B
        // (p=0.25, T=180min) → the earliest-deadline task A is chosen first.
        val (s, a, _) = stateWithWeightedTasks()
        assertEquals(a, SchedulerDomain.nextTask(s, 1_000_000_000_000L))
    }

    @Test
    fun next_task_breaks_equal_period_ties_alphabetically() {
        // A and B: equal priority (0.5) and equal minimum → equal period → the alphabetically-first wins.
        val (s, a, _) = stateWithTwoTasks()
        assertEquals(a, SchedulerDomain.nextTask(s, 1_000_000_000_000L))
    }

    @Test
    fun next_task_is_null_when_there_is_no_real_leaf_task() {
        assertNull(SchedulerDomain.nextTask(SchedulerState.empty(), 1_000_000_000_000L))
    }

    @Test
    fun schedulable_leaves_exclude_root_main_and_removed_tasks() {
        val (s0, a, b) = stateWithTwoTasks()
        assertEquals(setOf(a, b), SchedulerDomain.schedulableLeaves(s0).toSet())
        // Emptying A's cell removes it from the tree → no longer schedulable.
        val c0 = s0.lists[s0.rootListId]!!.cellIds[0]
        val s1 = SchedulerReducer.reduce(s0, SchedulerIntent.SetCellTitle(c0, ""))
        assertFalse(a in SchedulerDomain.schedulableLeaves(s1))
    }

    @Test
    fun removed_task_is_not_scheduled() {
        val (s0, a, b) = stateWithTwoTasks()
        val now = 1_000_000_000_000L
        val c0 = s0.lists[s0.rootListId]!!.cellIds[0]
        // "Remove" A from the tree by emptying its cell.
        val s1 = SchedulerReducer.reduce(s0, SchedulerIntent.SetCellTitle(c0, ""))
        val next = SchedulerDomain.nextTask(s1, now)
        val priorities = SchedulerDomain.absoluteTaskPriorities(s1)
        val panels = SchedulerDomain.fillSchedule(s1, now)
        assertEquals(b, next, "nextTask should pick B, not removed A. priorities=$priorities")
        assertTrue(panels.none { it.taskId == a }, "removed A still scheduled: ${panels.map { it.taskId }}")
    }

    @Test
    fun advance_tick_drops_a_removed_tasks_lingering_panels() {
        val (s0, a, _) = stateWithTwoTasks()
        val now = 1_000_000_000_000L
        // A schedule already laid down with A panels; auto-scheduling OFF, so no refill runs and only
        // the frequent advance tick can clean up after the task is removed (PRD §7/§9).
        val scheduled = s0.copy(
            panels = SchedulerDomain.fillSchedule(s0, now),
            automaticSchedule = false,
        )
        assertTrue(scheduled.panels.any { it.taskId == a })
        // Remove A via the Delete-key flow (select A's cell, EmptySelectedCells).
        val cA = scheduled.lists[scheduled.rootListId]!!.cellIds[0]
        val withSel = scheduled.copy(
            selection = org.example.project.scheduler.state.SchedulerSelection(main = cA, selected = setOf(cA)),
        )
        val removed = SchedulerReducer.reduce(withSel, SchedulerIntent.EmptySelectedCells)
        val ticked = SchedulerReducer.reduce(removed, SchedulerIntent.AdvanceSchedule(now))
        assertTrue(
            ticked.panels.none { it.taskId == a },
            "removed A lingers in the schedule: ${ticked.panels.map { it.taskId }}",
        )
    }

    // ----- §9 fillSchedule (windowed multi-panel) --------------------------------------------

    @Test
    fun fill_schedule_lays_a_contiguous_chain_from_now_out_past_the_horizon() {
        val (s, a, b) = stateWithTwoTasks()
        val now = 1_000_000_000_000L
        val panels = SchedulerDomain.fillSchedule(s, now)

        assertTrue(panels.isNotEmpty())
        assertEquals(now, panels.first().startEpochMillis) // first panel starts exactly at now
        assertEquals(a, panels.first().taskId) // A and B tie → A picked first, then rotates
        assertEquals(b, panels[1].taskId)
        // Contiguous & non-overlapping, every panel a real leaf task, all auto.
        for (i in panels.indices) {
            assertTrue(panels[i].auto)
            assertTrue(panels[i].taskId == a || panels[i].taskId == b)
            if (i > 0) assertEquals(panels[i - 1].endEpochMillis, panels[i].startEpochMillis)
        }
        // The window reaches at least 168h ahead.
        assertTrue(panels.last().endEpochMillis >= now + SchedulerDomain.SCHEDULE_HORIZON_MILLIS)
    }

    @Test
    fun fill_schedule_on_empty_database_is_empty() {
        assertTrue(SchedulerDomain.fillSchedule(SchedulerState.empty(), 1_000L).isEmpty())
    }

    @Test
    fun refill_at_the_same_now_gives_every_panel_a_unique_id_without_overlap() {
        // A re-fill (e.g. the second of §9's two calculation events) cuts every in-window non-pinned
        // panel and regenerates from now, so ids stay unique (auto/0, auto/1, …) and nothing overlaps.
        val (s0, a, b) = stateWithTwoTasks()
        val now = 1_000_000_000_000L
        val firstFill = SchedulerDomain.fillSchedule(s0, now)
        val refilled = SchedulerDomain.fillSchedule(s0.copy(panels = firstFill), now)

        val ids = refilled.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "panel ids must be unique, got $ids")
        val sorted = refilled.sortedBy { it.startEpochMillis }
        for (i in 1 until sorted.size) {
            assertTrue(
                sorted[i].startEpochMillis >= sorted[i - 1].endEpochMillis,
                "panels overlap: ${sorted[i - 1]} vs ${sorted[i]}",
            )
        }
        // The rotation still reads A, B, A, … from now.
        assertEquals(a, sorted.first().taskId)
        assertEquals(b, sorted[1].taskId)
    }

    @Test
    fun steady_state_refill_at_the_same_now_is_a_no_op() {
        // The id normalization must stay deterministic: refilling the kept schedule at the same instant
        // must reproduce the identical panels, so reduceRefreshSchedule's `filled == panels` short-circuit
        // still fires (no spurious calendar history unit on every tick).
        val (s0, _, _) = stateWithTwoTasks()
        val now = 1_000_000_000_000L
        val firstFill = SchedulerDomain.fillSchedule(s0, now)
        val refilled = SchedulerDomain.fillSchedule(s0.copy(panels = firstFill), now)
        assertEquals(firstFill, refilled)
    }

    @Test
    fun fill_schedule_keeps_a_pinned_panel_and_flows_auto_panels_around_it() {
        val (s0, a, _) = stateWithTwoTasks()
        val now = 1_000_000_000_000L
        // A pinned panel sits 2–3h ahead; the auto fill must not overlap it (PRD §10) and must keep it.
        val pin = pinned("panel/0", a, now + 2 * HOUR_MS, now + 3 * HOUR_MS)
        val s = s0.copy(panels = listOf(pin))

        val panels = SchedulerDomain.fillSchedule(s, now)

        assertTrue(pin in panels) // pinned panel survives
        val autos = panels.filter { it.auto }
        // No auto panel overlaps the pinned window.
        assertTrue(autos.none { it.startEpochMillis < pin.endEpochMillis && pin.startEpochMillis < it.endEpochMillis })
        // Some auto panel ends exactly where the pinned one begins (shortened to fit, PRD §10).
        assertTrue(autos.any { it.endEpochMillis == pin.startEpochMillis })
    }

    @Test
    fun fill_schedule_re_derives_the_current_task_from_now() {
        // PRD §9: the in-progress non-pinned panel is cut and the window re-filled from now; the current
        // task is re-picked deterministically (continuity), now starting exactly at `now`.
        val (s0, a, _) = stateWithTwoTasks()
        val now = 1_000_000_000_000L
        val current = auto("auto/0", a, now - 10 * MIN, now + 35 * MIN)
        val s = s0.copy(panels = listOf(current))

        val panels = SchedulerDomain.fillSchedule(s, now)

        assertTrue(panels.none { it.startEpochMillis < now }) // the stale (non-pinned) panel is gone
        val firstAuto = panels.first { it.auto }
        assertEquals(now, firstAuto.startEpochMillis) // the fresh fill starts at now
        assertEquals(a, firstAuto.taskId) // A and B tie → A re-picked first (continuity)
    }

    @Test
    fun fill_schedule_shows_a_sole_task_as_one_merged_block() {
        // PRD §9 merge: a single task's consecutive auto sessions fuse into one continuous panel that
        // covers the whole window from now past the horizon.
        val (s, solo) = stateWithOneTask(45)
        val now = 1_000_000_000_000L

        val autos = SchedulerDomain.fillSchedule(s, now).filter { it.auto }

        assertEquals(1, autos.size, "a sole task should be one merged block, got ${autos.size}")
        assertEquals(solo, autos[0].taskId)
        assertEquals(now, autos[0].startEpochMillis)
        assertTrue(autos[0].endEpochMillis >= now + SchedulerDomain.SCHEDULE_HORIZON_MILLIS)
    }

    @Test
    fun fill_schedule_allocates_time_in_proportion_to_priority() {
        // PRD §9 EDF: over the window, A (p=0.75) and B (p=0.25) receive ~3:1 of the scheduled time —
        // each task's utilization m/T equals its priority share.
        val (s, a, b) = stateWithWeightedTasks()
        val now = 1_000_000_000_000L
        val byTask =
            SchedulerDomain.fillSchedule(s, now)
                .filter { it.auto }
                .groupBy { it.taskId }
                .mapValues { (_, ps) -> ps.sumOf { it.endEpochMillis - it.startEpochMillis } }
        val aTime = byTask[a] ?: 0L
        val bTime = byTask[b] ?: 0L
        val share = aTime.toDouble() / (aTime + bTime)
        assertTrue(share in 0.70..0.80, "A should get ~75% of the time, got $share")
    }

    @Test
    fun fill_schedule_lets_pre_now_excess_set_the_starting_phase_without_balancing() {
        // PRD §9 example 1: A (50%, 45min) and B (50%, 45min) with lots of A pinned before now. The
        // excess of A must NOT be balanced (B is not over-served to catch up — they still split the
        // window 50/50) but it sets the starting phase: B goes first → B, A, B, A.
        val (s0, a, b) = stateWithTwoTasks()
        val now = 1_000_000_000_000L
        // "lots of A before now": four back-to-back pinned A panels ending exactly at now.
        val pastA = (1..4).map { k ->
            pinned("pin/$k", a, now - k * 45 * MIN, now - (k - 1) * 45 * MIN)
        }
        val s = s0.copy(panels = pastA)

        val autos = SchedulerDomain.fillSchedule(s, now).filter { it.auto }.sortedBy { it.startEpochMillis }

        assertTrue(autos.size >= 4, "expected an alternating fill, got ${autos.map { it.taskId }}")
        assertEquals(now, autos[0].startEpochMillis) // fresh fill starts at now
        assertEquals(listOf(b, a, b, a), autos.take(4).map { it.taskId }) // B first, then alternates
        // Excess is not balanced: across the whole window A and B still get equal time (~50/50).
        val byTask = autos.groupBy { it.taskId }
            .mapValues { (_, ps) -> ps.sumOf { it.endEpochMillis - it.startEpochMillis } }
        val aTime = byTask[a] ?: 0L
        val bTime = byTask[b] ?: 0L
        val share = bTime.toDouble() / (aTime + bTime)
        assertTrue(share in 0.45..0.55, "B should still get ~50% in-window (no catch-up), got $share")
    }

    // ----- §9 RefreshSchedule (calculation event) --------------------------------------------

    @Test
    fun refresh_schedule_fills_panels_and_is_a_calendar_history_unit() {
        val (s, _, _) = stateWithTwoTasks()
        val now = 1_000_000_000_000L
        val scheduled = SchedulerReducer.reduce(s, SchedulerIntent.RefreshSchedule(now))
        assertTrue(scheduled.panels.isNotEmpty())

        // PRD §9 "each scheduling is saved in a History Unit": undoable while the calendar is focused.
        val focused = SchedulerReducer.reduce(scheduled, SchedulerIntent.SetCalendarFocus(true))
        val undone = SchedulerReducer.reduce(focused, SchedulerIntent.Undo)
        assertTrue(undone.panels.isEmpty()) // restored to the pre-fill (empty) panel list
    }

    @Test
    fun refresh_schedule_wipes_non_pinned_panels_but_keeps_pinned_ones() {
        val (s0, a, b) = stateWithTwoTasks()
        val now = 1_000_000_000_000L
        val pin = pinned("panel/0", a, now + 10 * HOUR_MS, now + 11 * HOUR_MS)
        val stale = auto("auto/9", b, now + 2 * HOUR_MS, now + 3 * HOUR_MS) // not pinned, not current
        val s = s0.copy(panels = listOf(pin, stale))

        val refreshed = SchedulerReducer.reduce(s, SchedulerIntent.RefreshSchedule(now))

        assertTrue(pin in refreshed.panels) // pinned kept
        assertTrue(stale !in refreshed.panels) // stale non-pinned wiped & regenerated
        assertTrue(refreshed.panels.any { it.auto && it.startEpochMillis == now }) // fresh fill from now
    }

    @Test
    fun adding_a_second_task_after_a_full_schedule_reschedules_to_include_it() {
        // Regression: a sole task fills the window as one merged block. Because a refill cuts every
        // in-window non-pinned panel (including that block) and re-derives the schedule from now, a
        // newly added task always enters the schedule — the merged block can never swallow the window.
        val (s0, solo) = stateWithOneTask(45)
        val now = 1_000_000_000_000L
        val scheduled = SchedulerReducer.reduce(s0, SchedulerIntent.RefreshSchedule(now))
        assertTrue(scheduled.panels.all { it.taskId == solo }) // only the sole task so far

        // Add a second sibling task in the same sublist (the auto-appended placeholder cell).
        val placeholder = scheduled.lists[scheduled.rootListId]!!.cellIds[1]
        val withSecond = SchedulerReducer.reduce(scheduled, SchedulerIntent.SetCellTitle(placeholder, "Two"))
        val two = withSecond.tasks.keys.first { withSecond.tasks[it]!!.title == "Two" }

        val refreshed = SchedulerReducer.reduce(withSecond, SchedulerIntent.RefreshSchedule(now))

        assertTrue(refreshed.panels.any { it.taskId == two }) // the new task is now scheduled
        assertTrue(refreshed.panels.any { it.taskId == solo }) // and the original still appears
    }

    @Test
    fun deleting_a_task_by_emptying_its_cell_drops_it_from_the_schedule() {
        // Regression: "deleting" a task clears its cell title but the cell keeps its id and the task
        // lingers (its panels/records still reference it). A blank-titled task must NOT be scheduled —
        // otherwise the refill alternates the survivor with "(untitled)" blocks.
        val (s0, a, b) = stateWithTwoTasks()
        val now = 1_000_000_000_000L
        val scheduled = SchedulerReducer.reduce(s0, SchedulerIntent.RefreshSchedule(now))
        assertTrue(scheduled.panels.any { it.taskId == a } && scheduled.panels.any { it.taskId == b })

        // Delete task B by emptying the cell that titled it.
        val bCell = scheduled.tasks[b]!!.occurrences.first()
        val deleted = SchedulerReducer.reduce(scheduled, SchedulerIntent.SetCellTitle(bCell, ""))

        val refreshed = SchedulerReducer.reduce(deleted, SchedulerIntent.RefreshSchedule(now))

        assertTrue(SchedulerDomain.nextTask(refreshed, now) != b) // the emptied task is never chosen
        assertTrue(refreshed.panels.any { it.taskId == a }) // the survivor fills the window
        assertTrue(refreshed.panels.none { it.taskId == b }) // no blank "(untitled)" B panels
        assertTrue(refreshed.panels.none { it.title.isBlank() }) // nothing renders as "(untitled)"
    }

    @Test
    fun refresh_schedule_keeps_a_non_pinned_panel_that_is_in_the_past() {
        val (s0, a, _) = stateWithTwoTasks()
        val now = 1_000_000_000_000L
        // A non-pinned user panel sitting just before now — e.g. dragged from just-after to just-before
        // the current time. Scheduling regenerates only the future, so it must NOT be wiped.
        val past = TaskPanel("panel/0", a, "A", now - 30 * MIN, now - MIN, pinned = false, auto = false)
        val s = s0.copy(panels = listOf(past), nextPanelCounter = 1)

        val refreshed = SchedulerReducer.reduce(s, SchedulerIntent.RefreshSchedule(now))

        assertTrue(past in refreshed.panels) // PRD §9: past panels are history, not removed
        assertTrue(refreshed.panels.any { it.auto && it.startEpochMillis == now }) // future still filled
    }

    @Test
    fun refresh_schedule_keeps_a_non_pinned_panel_beyond_the_168h_horizon() {
        val (s0, a, _) = stateWithTwoTasks()
        val now = 1_000_000_000_000L
        // A non-pinned user panel added more than 168h out is outside the scheduling window, so a
        // reschedule must not wipe it.
        val far = TaskPanel("panel/0", a, "A", now + 200 * HOUR_MS, now + 201 * HOUR_MS, pinned = false, auto = false)
        val s = s0.copy(panels = listOf(far), nextPanelCounter = 1)

        val refreshed = SchedulerReducer.reduce(s, SchedulerIntent.RefreshSchedule(now))

        assertTrue(far in refreshed.panels) // PRD §9: panels beyond the horizon are outside scope
    }

    @Test
    fun refresh_schedule_is_deferred_while_automatic_schedule_is_off() {
        val (s0, _, _) = stateWithTwoTasks()
        val now = 1_000_000_000_000L
        val off = s0.copy(automaticSchedule = false)
        val after = SchedulerReducer.reduce(off, SchedulerIntent.RefreshSchedule(now))
        assertTrue(after.panels.isEmpty()) // PRD §7: the scheduling event waits

        // Turning it on then refreshing fills the schedule.
        val on = SchedulerReducer.reduce(after, SchedulerIntent.SetAutomaticSchedule(true))
        val filled = SchedulerReducer.reduce(on, SchedulerIntent.RefreshSchedule(now))
        assertTrue(filled.panels.isNotEmpty())
    }

    // ----- §9 AdvanceSchedule (frequent tick) ------------------------------------------------

    @Test
    fun advance_records_a_completed_auto_panel_and_drops_it() {
        val (s0, a, _) = stateWithTwoTasks()
        val now = 1_000_000_000_000L
        val completed = auto("auto/0", a, now - 30 * MIN, now - MIN)
        val s = s0.copy(panels = listOf(completed))

        val advanced = SchedulerReducer.reduce(s, SchedulerIntent.AdvanceSchedule(now))

        assertTrue(advanced.panels.isEmpty()) // the elapsed panel is gone…
        // …and its span was logged as a record so the calendar keeps showing it (green).
        assertEquals(listOf(TaskTimeRange(now - 30 * MIN, now - MIN)), advanced.tasks[a]!!.record)
    }

    @Test
    fun advance_keeps_an_in_progress_panel_and_is_not_undoable() {
        val (s0, a, _) = stateWithTwoTasks()
        val now = 1_000_000_000_000L
        val current = auto("auto/0", a, now - 5 * MIN, now + 25 * MIN)
        val s = s0.copy(panels = listOf(current))
        val advanced = SchedulerReducer.reduce(s, SchedulerIntent.AdvanceSchedule(now))
        assertTrue(advanced.tasks[a]!!.record.isEmpty()) // not yet completed → nothing recorded
        assertTrue(current in advanced.panels)
        // Touches only history-excluded record/panel-progress state → undo must not bring it back.
        val undone = SchedulerReducer.reduce(advanced, SchedulerIntent.Undo)
        assertEquals(advanced.panels, undone.panels)
    }

    @Test
    fun advance_cuts_and_records_an_in_progress_panel_whose_task_left_the_tree() {
        val (s0, a, _) = stateWithTwoTasks()
        val now = 1_000_000_000_000L
        val current = auto("auto/0", a, now - 10 * MIN, now + 20 * MIN)
        // `a` is removed from the tree (no cell points at it); the panel must be cut at `now`.
        val s = s0.copy(cells = s0.cells.filterValues { it.taskId != a }, panels = listOf(current))
        val advanced = SchedulerReducer.reduce(s, SchedulerIntent.AdvanceSchedule(now))
        assertTrue(advanced.panels.isEmpty())
        assertEquals(listOf(TaskTimeRange(now - 10 * MIN, now)), advanced.tasks[a]!!.record)
    }

    // ----- §10 minimum time ------------------------------------------------------------------

    @Test
    fun scheduled_span_subtracts_recent_contiguous_effort() {
        val (s0, a, _) = stateWithTwoTasks()
        val now = 1_000_000_000_000L
        val recent = TaskTimeRange(now - 25 * MIN, now - 5 * MIN)
        val task = s0.tasks[a]!!.copy(minimumMinutes = 60, record = listOf(recent))
        assertEquals(20L, SchedulerDomain.recentContiguousRecordMinutes(task.record, now))
        assertEquals(40L, SchedulerDomain.scheduledSpanMinutes(task, now))
    }

    @Test
    fun scheduled_span_ignores_effort_whose_streak_already_broke() {
        val (s0, a, _) = stateWithTwoTasks()
        val now = 1_000_000_000_000L
        val stale = TaskTimeRange(now - 35 * MIN, now - 15 * MIN)
        val task = s0.tasks[a]!!.copy(minimumMinutes = 60, record = listOf(stale))
        assertEquals(0L, SchedulerDomain.recentContiguousRecordMinutes(task.record, now))
        assertEquals(60L, SchedulerDomain.scheduledSpanMinutes(task, now))
    }

    @Test
    fun new_task_defaults_to_45_minute_minimum() {
        val (s, a, _) = stateWithTwoTasks()
        assertEquals(45, s.tasks[a]!!.minimumMinutes)
        val now = 1_000_000_000_000L
        val panels = SchedulerDomain.fillSchedule(s, now)
        assertEquals(now + 45 * MIN, panels.first().endEpochMillis)
    }

    @Test
    fun set_minimum_time_updates_and_clamps_negative_to_zero() {
        val (s, a, _) = stateWithTwoTasks()
        val set = SchedulerReducer.reduce(s, SchedulerIntent.SetTaskMinimumTime(a, 30))
        assertEquals(30, set.tasks[a]!!.minimumMinutes)
        val clamped = SchedulerReducer.reduce(set, SchedulerIntent.SetTaskMinimumTime(a, -5))
        assertEquals(0, clamped.tasks[a]!!.minimumMinutes)
    }

    @Test
    fun undo_restores_previous_minimum_time() {
        val (s, a, _) = stateWithTwoTasks()
        val s30 = SchedulerReducer.reduce(s, SchedulerIntent.SetTaskMinimumTime(a, 30))
        val s45 = SchedulerReducer.reduce(s30, SchedulerIntent.SetTaskMinimumTime(a, 45))
        val undone = SchedulerReducer.reduce(s45, SchedulerIntent.Undo)
        assertEquals(30, undone.tasks[a]!!.minimumMinutes)
    }

    // ----- §8 task record is excluded from history -------------------------------------------

    @Test
    fun undo_does_not_revert_a_task_record() {
        val (s, a, _) = stateWithTwoTasks()
        val record = listOf(TaskTimeRange(1_000L, 2_000L))
        val withRecord = s.copy(tasks = s.tasks + (a to s.tasks[a]!!.copy(record = record)))
        val mutated = SchedulerReducer.reduce(withRecord, SchedulerIntent.SetTaskMinimumTime(a, 15))
        val undone = SchedulerReducer.reduce(mutated, SchedulerIntent.Undo)
        assertEquals(45, undone.tasks[a]!!.minimumMinutes)
        assertEquals(record, undone.tasks[a]!!.record)
    }

    // ----- persistence -----------------------------------------------------------------------

    @Test
    fun codec_round_trip_preserves_panels_and_switch() {
        val (s, a, _) = stateWithTwoTasks()
        val prepared =
            s.copy(
                panels = listOf(pinned("panel/0", a, 10_000L, 20_000L), auto("auto/0", a, 20_000L, 30_000L)),
                nextPanelCounter = 1,
                automaticSchedule = false,
            )
        val decoded = SchedulerStateCodec.decode(SchedulerStateCodec.encode(prepared))
        assertNotNull(decoded)
        assertEquals(prepared.panels, decoded.panels)
        assertEquals(1, decoded.nextPanelCounter)
        assertEquals(false, decoded.automaticSchedule)
    }

    @Test
    fun codec_round_trip_preserves_minimum_time_and_record() {
        val (s, a, _) = stateWithTwoTasks()
        val record = listOf(TaskTimeRange(10_000L, 20_000L), TaskTimeRange(30_000L, 40_000L))
        val prepared =
            SchedulerReducer.reduce(s, SchedulerIntent.SetTaskMinimumTime(a, 25))
                .let { it.copy(tasks = it.tasks + (a to it.tasks[a]!!.copy(record = record))) }
        val decoded = SchedulerStateCodec.decode(SchedulerStateCodec.encode(prepared))
        assertNotNull(decoded)
        assertEquals(25, decoded.tasks[a]!!.minimumMinutes)
        assertEquals(record, decoded.tasks[a]!!.record)
    }

    @Test
    fun codec_decodes_old_payload_without_new_fields() {
        val json =
            """
            {"rootListId":"L","lists":[{"id":"L","parentCellId":null,"cellIds":["c0"]}],
             "cells":[{"id":"c0","parentListId":"L","taskId":null}],
             "tasks":[{"id":"t0","title":"X"}]}
            """.trimIndent()
        val decoded = SchedulerStateCodec.decode(json)
        assertNotNull(decoded)
        assertEquals(45, decoded.tasks[TaskId("t0")]!!.minimumMinutes)
        assertTrue(decoded.panels.isEmpty())
        assertEquals(true, decoded.automaticSchedule) // §7 default-on for pre-1.2.0 payloads
    }

    // ----- §8 calendar mapping ----------------------------------------------------------------

    @Test
    fun records_for_day_clips_to_the_day_and_maps_hour_offsets() {
        val tz = TimeZone.UTC
        val day = LocalDate(2024, 1, 1)
        fun millis(h: Int, m: Int) =
            LocalDateTime(2024, 1, 1, h, m).toInstant(tz).toEpochMilliseconds()
        val record = CalendarRecord("Practice English", TaskTimeRange(millis(9, 0), millis(10, 30)))

        val placed = recordsForDay(listOf(record), day, tz)
        assertEquals(1, placed.size)
        assertEquals(9.0f, placed[0].startHour)
        assertEquals(10.5f, placed[0].endHour)
        assertEquals("Practice English", placed[0].title)
        assertTrue(recordsForDay(listOf(record), LocalDate(2024, 1, 2), tz).isEmpty())
    }

    @Test
    fun records_for_day_keeps_zero_duration_reminder_tags_but_drops_zero_duration_blocks() {
        // PRD §14: a reminder is a zero-duration tag rendered at its time — it must survive recordsForDay
        // (the block path drops zero-height periods). A non-reminder zero-duration period is still dropped.
        val tz = TimeZone.UTC
        val day = LocalDate(2024, 1, 1)
        val at20 = LocalDateTime(2024, 1, 1, 20, 0).toInstant(tz).toEpochMilliseconds()
        val reminder = CalendarRecord(
            "Water plants", TaskTimeRange(at20, at20), entryId = "chore/0/0", reminder = true, checked = true,
        )
        val degenerateBlock = CalendarRecord("Oops", TaskTimeRange(at20, at20))

        val placed = recordsForDay(listOf(reminder, degenerateBlock), day, tz)
        assertEquals(1, placed.size) // only the reminder tag survives
        assertTrue(placed[0].reminder)
        assertTrue(placed[0].checked)
        assertEquals(20.0f, placed[0].startHour) // rendered at 20:00
        assertEquals("chore/0/0", placed[0].entryId)
    }
}
