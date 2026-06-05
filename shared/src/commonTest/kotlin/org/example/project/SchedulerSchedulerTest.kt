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
import org.example.project.scheduler.model.ScheduledTask
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.model.TaskTimeRange
import org.example.project.scheduler.persistence.SchedulerStateCodec
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState
import org.example.project.ui.CalendarRecord
import org.example.project.ui.recordsForDay

/**
 * Tests for the v1.0.0 PRD additions: §9 scheduler / time-weighted percentage, §10 minimum time,
 * §8 task record (history-excluded, persisted, calendar-mapped).
 */
class SchedulerSchedulerTest {

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

    // ----- §9 time-weighted score / next task -------------------------------------------------

    @Test
    fun default_decay_constant_has_a_seven_day_half_life() {
        // PRD §9: a record period's weight halves every 7 days (168 h).
        val k = SchedulerDomain.DEFAULT_DECAY_PER_HOUR
        assertEquals(0.5, exp(-k * 7 * 24.0), 1e-12)
    }

    @Test
    fun time_weighted_score_matches_closed_form_integral() {
        val k = 0.02
        val now = 1_000_000_000_000L
        val range = TaskTimeRange(startEpochMillis = now - 2 * HOUR_MS, endEpochMillis = now - HOUR_MS)
        // (1/k)(e^{-k*ageEnd} - e^{-k*ageStart}) with ages in hours (1h and 2h).
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
    fun more_recent_period_scores_higher_than_older_one_of_equal_duration() {
        val now = 1_000_000_000_000L
        val recent = TaskTimeRange(now - HOUR_MS, now)
        val old = TaskTimeRange(now - 101 * HOUR_MS, now - 100 * HOUR_MS)
        assertTrue(
            SchedulerDomain.timeWeightedScore(listOf(recent), now) >
                SchedulerDomain.timeWeightedScore(listOf(old), now),
        )
    }

    @Test
    fun next_task_is_the_most_under_served() {
        val (s, a, b) = stateWithTwoTasks()
        val now = 1_000_000_000_000L
        // A has a big recent record (over-served), B has none (under-served) — both equal priority.
        val withRecord =
            s.copy(tasks = s.tasks + (a to s.tasks[a]!!.copy(record = listOf(TaskTimeRange(now - HOUR_MS, now)))))
        assertEquals(b, SchedulerDomain.nextTask(withRecord, now))
    }

    // ----- §9 schedule (current task to do + deadline) ---------------------------------------

    @Test
    fun compute_schedule_allocates_minimum_time_as_the_deadline() {
        val (s0, a, _) = stateWithTwoTasks()
        val now = 1_000_000_000_000L
        // Give A a recent record so B (under-served) is the deterministic pick, and B a 20-min floor.
        val b = s0.tasks.keys.first { s0.tasks[it]!!.title == "B" }
        val s =
            s0.copy(
                tasks = s0.tasks +
                    (a to s0.tasks[a]!!.copy(record = listOf(TaskTimeRange(now - HOUR_MS, now)))) +
                    (b to s0.tasks[b]!!.copy(minimumMinutes = 20)),
            )
        val sched = SchedulerDomain.computeSchedule(s, now)
        assertNotNull(sched)
        assertEquals(b, sched.taskId)
        assertEquals(now, sched.startEpochMillis)
        assertEquals(now + 20 * 60_000L, sched.deadlineEpochMillis)
    }

    @Test
    fun compute_schedule_keeps_current_allocation_until_deadline_then_recomputes() {
        val (s0, a, _) = stateWithTwoTasks()
        val now = 1_000_000_000_000L
        // A 10-minute minimum so the constructed deadline matches the recomputed span (no record).
        val s = s0.copy(tasks = s0.tasks + (a to s0.tasks[a]!!.copy(minimumMinutes = 10)))
        val current = org.example.project.scheduler.model.ScheduledTask(a, now, now + 10 * 60_000L)
        val withCurrent = s.copy(scheduled = current)
        // Still within the deadline → unchanged.
        assertEquals(current, SchedulerDomain.computeSchedule(withCurrent, now + 5 * 60_000L))
        // Past the deadline → a fresh allocation starting at that instant.
        val after = now + 11 * 60_000L
        val next = SchedulerDomain.computeSchedule(withCurrent, after)
        assertNotNull(next)
        assertEquals(after, next.startEpochMillis)
    }

    @Test
    fun compute_schedule_updates_current_period_when_minimum_time_changes() {
        val (s0, a, _) = stateWithTwoTasks()
        val now = 1_000_000_000_000L
        val start = now - 5 * 60_000L
        // PRD §9 update: the task is still scheduled (started 5 min ago) and still exists; raising its
        // minimum to 30 min must stretch the scheduled period to start + 30 min, same task and start.
        val current = ScheduledTask(a, start, start + 20 * 60_000L)
        val s = s0.copy(
            tasks = s0.tasks + (a to s0.tasks[a]!!.copy(minimumMinutes = 30)),
            scheduled = current,
        )
        val updated = SchedulerDomain.computeSchedule(s, now)
        assertNotNull(updated)
        assertEquals(a, updated.taskId)
        assertEquals(start, updated.startEpochMillis)
        assertEquals(start + 30 * 60_000L, updated.deadlineEpochMillis)
    }

    @Test
    fun refresh_schedule_sets_scheduled_and_is_not_undoable() {
        val (s, _, _) = stateWithTwoTasks()
        val now = 1_000_000_000_000L
        val scheduled = SchedulerReducer.reduce(s, SchedulerIntent.RefreshSchedule(now))
        assertNotNull(scheduled.scheduled)
        // §9 scheduling state lives outside history: an undo must not clear it.
        val undone = SchedulerReducer.reduce(scheduled, SchedulerIntent.Undo)
        assertEquals(scheduled.scheduled, undone.scheduled)
    }

    @Test
    fun refresh_schedule_records_the_completed_period_when_deadline_reached() {
        val (s0, a, b) = stateWithTwoTasks()
        val now = 1_000_000_000_000L
        // `a` was scheduled for a period whose deadline is now in the past.
        val finished = ScheduledTask(a, now - 30 * 60_000L, now - 60_000L)
        val s = s0.copy(scheduled = finished)

        val refreshed = SchedulerReducer.reduce(s, SchedulerIntent.RefreshSchedule(now))

        // PRD §8: the elapsed [start, deadline] span is logged as a record on `a` (so the calendar
        // keeps showing it as a green block instead of dropping the vanished blue one).
        val record = refreshed.tasks[a]!!.record
        assertEquals(1, record.size)
        assertEquals(finished.startEpochMillis, record[0].startEpochMillis)
        assertEquals(finished.deadlineEpochMillis, record[0].endEpochMillis)
        // The just-recorded task is now over-served, so the next pick is the other one, from `now`.
        assertNotNull(refreshed.scheduled)
        assertEquals(b, refreshed.scheduled!!.taskId)
        assertEquals(now, refreshed.scheduled!!.startEpochMillis)
    }

    @Test
    fun refresh_schedule_does_not_record_while_within_deadline() {
        val (s0, a, _) = stateWithTwoTasks()
        val now = 1_000_000_000_000L
        val ongoing = ScheduledTask(a, now - 5 * 60_000L, now + 25 * 60_000L)
        val s = s0.copy(scheduled = ongoing)
        val refreshed = SchedulerReducer.reduce(s, SchedulerIntent.RefreshSchedule(now))
        assertTrue(refreshed.tasks[a]!!.record.isEmpty())
    }

    @Test
    fun sole_task_reschedules_a_fresh_full_period_each_time_its_deadline_is_reached() {
        // Empty database with a single task.
        var s = SchedulerState.empty()
        val c0 = s.lists[s.rootListId]!!.cellIds[0]
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(c0, "Solo"))
        val a = s.tasks.keys.first { s.tasks[it]!!.title == "Solo" }

        val t0 = 1_000_000_000_000L
        s = SchedulerReducer.reduce(s, SchedulerIntent.RefreshSchedule(t0))
        val first = s.scheduled!!
        assertEquals(a, first.taskId)
        assertEquals(t0 + 45 * 60_000L, first.deadlineEpochMillis)

        // Reach the deadline: the period is recorded AND the sole task is rescheduled for a fresh
        // full 45 min (it "extends" rather than collapsing to a zero-length block).
        val t1 = first.deadlineEpochMillis
        s = SchedulerReducer.reduce(s, SchedulerIntent.RefreshSchedule(t1))
        assertEquals(1, s.tasks[a]!!.record.size)
        val second = s.scheduled!!
        assertEquals(a, second.taskId)
        assertEquals(t1, second.startEpochMillis)
        assertEquals(t1 + 45 * 60_000L, second.deadlineEpochMillis)
    }

    @Test
    fun codec_round_trip_preserves_scheduled_task() {
        val (s, _, _) = stateWithTwoTasks()
        val prepared = SchedulerReducer.reduce(s, SchedulerIntent.RefreshSchedule(1_000_000_000_000L))
        val decoded = SchedulerStateCodec.decode(SchedulerStateCodec.encode(prepared))
        assertNotNull(decoded)
        assertEquals(prepared.scheduled, decoded.scheduled)
    }

    // ----- §10 minimum time ------------------------------------------------------------------

    @Test
    fun scheduled_span_subtracts_recent_contiguous_effort() {
        val (s0, a, _) = stateWithTwoTasks()
        val now = 1_000_000_000_000L
        // A 20-min session ending 5 min ago is within the 10-min streak window → span = 60 - 20.
        val recent = TaskTimeRange(now - 25 * 60_000L, now - 5 * 60_000L)
        val task = s0.tasks[a]!!.copy(minimumMinutes = 60, record = listOf(recent))
        assertEquals(20L, SchedulerDomain.recentContiguousRecordMinutes(task.record, now))
        assertEquals(40L, SchedulerDomain.scheduledSpanMinutes(task, now))
    }

    @Test
    fun scheduled_span_ignores_effort_whose_streak_already_broke() {
        val (s0, a, _) = stateWithTwoTasks()
        val now = 1_000_000_000_000L
        // The same 20-min session, but it ended 15 min ago → the >10-min gap breaks the streak.
        val stale = TaskTimeRange(now - 35 * 60_000L, now - 15 * 60_000L)
        val task = s0.tasks[a]!!.copy(minimumMinutes = 60, record = listOf(stale))
        assertEquals(0L, SchedulerDomain.recentContiguousRecordMinutes(task.record, now))
        assertEquals(60L, SchedulerDomain.scheduledSpanMinutes(task, now))
    }

    @Test
    fun scheduled_span_falls_back_to_full_minimum_when_recent_effort_exceeds_it() {
        val (s0, a, _) = stateWithTwoTasks()
        val now = 1_000_000_000_000L
        // Two sessions 5 min apart (last ends 5 min ago) chain into one 50-min effort > 30-min min.
        val r1 = TaskTimeRange(now - 60 * 60_000L, now - 35 * 60_000L) // 25 min
        val r2 = TaskTimeRange(now - 30 * 60_000L, now - 5 * 60_000L) // 25 min, 5-min gap from r1
        val task = s0.tasks[a]!!.copy(minimumMinutes = 30, record = listOf(r1, r2))
        assertEquals(50L, SchedulerDomain.recentContiguousRecordMinutes(task.record, now))
        assertEquals(30L, SchedulerDomain.scheduledSpanMinutes(task, now)) // 30 - 50 < 0 → full minimum
    }

    @Test
    fun scheduled_span_is_a_fresh_full_minimum_when_recent_effort_exactly_meets_it() {
        val (s0, a, _) = stateWithTwoTasks()
        val now = 1_000_000_000_000L
        // A 45-min effort ending right now exactly meets the 45-min minimum → schedule a fresh full
        // 45 min (not a zero-length slot), so e.g. a sole task keeps extending period after period.
        val exactly = TaskTimeRange(now - 45 * 60_000L, now)
        val task = s0.tasks[a]!!.copy(minimumMinutes = 45, record = listOf(exactly))
        assertEquals(45L, SchedulerDomain.recentContiguousRecordMinutes(task.record, now))
        assertEquals(45L, SchedulerDomain.scheduledSpanMinutes(task, now)) // 45 - 45 = 0 → full minimum
    }

    @Test
    fun new_task_defaults_to_45_minute_minimum_and_schedules_a_non_empty_slot() {
        val (s, a, _) = stateWithTwoTasks()
        // PRD §10: a freshly created task's minimum time defaults to 45 minutes.
        assertEquals(45, s.tasks[a]!!.minimumMinutes)
        // …so the scheduler allocates a real (non-zero) slot — the block is visible in the calendar.
        val now = 1_000_000_000_000L
        val sched = SchedulerDomain.computeSchedule(s, now)
        assertNotNull(sched)
        assertTrue(sched.deadlineEpochMillis > sched.startEpochMillis)
        assertEquals(now + 45 * 60_000L, sched.deadlineEpochMillis)
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
        assertEquals(45, s45.tasks[a]!!.minimumMinutes)
        val undone = SchedulerReducer.reduce(s45, SchedulerIntent.Undo)
        assertEquals(30, undone.tasks[a]!!.minimumMinutes)
    }

    // ----- §8 task record is excluded from history -------------------------------------------

    @Test
    fun undo_does_not_revert_a_task_record() {
        val (s, a, _) = stateWithTwoTasks()
        val record = listOf(TaskTimeRange(1_000L, 2_000L))
        val withRecord = s.copy(tasks = s.tasks + (a to s.tasks[a]!!.copy(record = record)))
        // An undoable mutation followed by undo must leave the record intact (PRD §8).
        val mutated = SchedulerReducer.reduce(withRecord, SchedulerIntent.SetTaskMinimumTime(a, 15))
        assertEquals(record, mutated.tasks[a]!!.record)
        val undone = SchedulerReducer.reduce(mutated, SchedulerIntent.Undo)
        assertEquals(45, undone.tasks[a]!!.minimumMinutes) // min time reverted to the 45-min default
        assertEquals(record, undone.tasks[a]!!.record) // record preserved
    }

    // ----- persistence -----------------------------------------------------------------------

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
    fun codec_decodes_old_payload_without_new_task_fields() {
        val json =
            """
            {"rootListId":"L","lists":[{"id":"L","parentCellId":null,"cellIds":["c0"]}],
             "cells":[{"id":"c0","parentListId":"L","taskId":null}],
             "tasks":[{"id":"t0","title":"X"}]}
            """.trimIndent()
        val decoded = SchedulerStateCodec.decode(json)
        assertNotNull(decoded)
        // PRD §10: a payload missing the field decodes to the 45-minute default.
        assertEquals(45, decoded.tasks[TaskId("t0")]!!.minimumMinutes)
        assertTrue(decoded.tasks[TaskId("t0")]!!.record.isEmpty())
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

        // A record entirely on another day produces nothing for this day.
        assertTrue(recordsForDay(listOf(record), LocalDate(2024, 1, 2), tz).isEmpty())
    }
}
