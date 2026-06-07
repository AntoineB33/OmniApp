package org.example.project

import kotlin.math.exp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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

    private fun auto(id: String, taskId: TaskId, start: Long, end: Long) =
        TaskPanel(id, taskId, "x", start, end, pinned = false, auto = true)

    private fun pinned(id: String, taskId: TaskId, start: Long, end: Long) =
        TaskPanel(id, taskId, "x", start, end, pinned = true, auto = false)

    // ----- §9 time-weighted score / next task -------------------------------------------------

    @Test
    fun default_decay_constant_has_a_seven_day_half_life() {
        val k = SchedulerDomain.DEFAULT_DECAY_PER_HOUR
        assertEquals(0.5, exp(-k * 7 * 24.0), 1e-12)
    }

    @Test
    fun time_weighted_score_matches_closed_form_integral() {
        val k = 0.02
        val now = 1_000_000_000_000L
        val range = TaskTimeRange(startEpochMillis = now - 2 * HOUR_MS, endEpochMillis = now - HOUR_MS)
        val expected = (exp(-k * 1.0) - exp(-k * 2.0)) / k
        assertEquals(expected, SchedulerDomain.timeWeightedScore(listOf(range), now, k), 1e-9)
    }

    @Test
    fun time_weighted_score_is_zero_for_empty_record_or_nonpositive_k() {
        val now = 1_000_000_000_000L
        assertEquals(0.0, SchedulerDomain.timeWeightedScore(emptyList(), now, 0.02))
        val range = TaskTimeRange(now - HOUR_MS, now)
        assertEquals(0.0, SchedulerDomain.timeWeightedScore(listOf(range), now, 0.0))
    }

    @Test
    fun next_task_is_the_most_under_served() {
        val (s, a, b) = stateWithTwoTasks()
        val now = 1_000_000_000_000L
        val withRecord =
            s.copy(tasks = s.tasks + (a to s.tasks[a]!!.copy(record = listOf(TaskTimeRange(now - HOUR_MS, now)))))
        assertEquals(b, SchedulerDomain.nextTask(withRecord, now))
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
        // The window reaches at least 24h ahead.
        assertTrue(panels.last().endEpochMillis >= now + SchedulerDomain.SCHEDULE_HORIZON_MILLIS)
    }

    @Test
    fun fill_schedule_on_empty_database_is_empty() {
        assertTrue(SchedulerDomain.fillSchedule(SchedulerState.empty(), 1_000L).isEmpty())
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
    fun fill_schedule_keeps_the_in_progress_panel_covering_now() {
        val (s0, a, _) = stateWithTwoTasks()
        val now = 1_000_000_000_000L
        val current = auto("auto/0", a, now - 10 * MIN, now + 35 * MIN)
        val s = s0.copy(panels = listOf(current))

        val panels = SchedulerDomain.fillSchedule(s, now)

        // The current panel is preserved unchanged and the fill resumes after it.
        assertTrue(current in panels)
        assertTrue(panels.any { it.startEpochMillis == current.endEpochMillis })
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
}
