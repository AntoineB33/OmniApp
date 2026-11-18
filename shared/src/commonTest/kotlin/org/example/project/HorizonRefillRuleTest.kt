package org.example.project

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.datetime.TimeZone
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState

/**
 * PRD §9 calculation event #1 (rolling horizon), the anti-spin rule.
 *
 * `SchedulerEngine.launchHorizonReschedule` waits until [SchedulerDomain.horizonRefillDueMillis], refills,
 * and re-evaluates on the resulting `panels` change. That closes a loop, so the rule has a hard obligation:
 * **a fill must push its own next due instant into the future.** It once did not — a fill materialized to
 * exactly the rolling horizon it was then judged against, so the best it could produce was "due right now",
 * every refill triggered the next with no delay, and the desktop app came up as a tray icon whose window never
 * presented a frame (2026-07-28).
 *
 * The goal is `now + 168 h` (user rule 2026-11-18), which rolls with the line: a fill reaching it is judged against
 * a target that moves, so the refill is due only once the coverage falls [SchedulerDomain.HORIZON_REFILL_MARGIN_MILLIS]
 * (an hour) short of it — one extension per hour of the line, never one per tick.
 */
class HorizonRefillRuleTest {

    private val HOUR = 60 * 60_000L
    private val WEEK = SchedulerDomain.SCHEDULE_HORIZON_MILLIS
    private val MARGIN = SchedulerDomain.HORIZON_REFILL_MARGIN_MILLIS
    private val UTC = TimeZone.UTC

    /** Tuesday 2023-11-14, 22:13 UTC. */
    private val NOW = 1_700_000_000_000L

    /** One schedulable task under "main", so the fill has something to lay down across the horizon. */
    private fun stateWithOneTask(): Pair<SchedulerState, TaskId> {
        var s = SchedulerState.empty()
        val c0 = s.lists[s.rootListId]!!.cellIds[0]
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(c0, "A"))
        return s to s.tasks.keys.first { s.tasks[it]!!.title == "A" }
    }

    private fun fillTo(state: SchedulerState, now: Long) =
        SchedulerDomain.fillSchedule(
            state, now, horizonMillis = SchedulerDomain.scheduleHorizonEndMillis(now, null, UTC),
        )

    @Test
    fun a_fill_pushes_its_own_next_refill_an_hour_into_the_future() {
        val (state, _) = stateWithOneTask()
        val filled = fillTo(state, NOW)
        assertTrue(SchedulerDomain.firstFreeMoment(filled, NOW) >= NOW + WEEK, "the fill reaches the goal")
        val due = SchedulerDomain.horizonRefillDueMillis(filled, NOW, null, UTC)
        assertTrue(due >= NOW + MARGIN, "a fresh fill must not be due again for an hour (due = now + ${(due - NOW) / 60_000} min)")
    }

    @Test
    fun the_refill_comes_due_before_the_plan_runs_out() {
        // An empty schedule covers nothing, so a refill is overdue.
        assertTrue(SchedulerDomain.horizonRefillDueMillis(emptyList(), NOW, null, UTC) <= NOW)
        val (state, _) = stateWithOneTask()
        val filled = fillTo(state, NOW)
        val due = SchedulerDomain.horizonRefillDueMillis(filled, NOW, null, UTC)
        assertTrue(SchedulerDomain.horizonRefillDueMillis(filled, due - 1, null, UTC) > due - 1, "not due before")
        assertTrue(SchedulerDomain.horizonRefillDueMillis(filled, due, null, UTC) <= due, "due then")
        // …with a week less the margin of plan still ahead of the line.
        assertTrue(SchedulerDomain.firstFreeMoment(filled, due) >= due + WEEK - MARGIN)
    }

    @Test
    fun a_bounded_reach_keeps_half_of_it_as_margin() {
        // A test engine bounding the run to one hour refills when half an hour is left, not at once.
        val (state, _) = stateWithOneTask()
        val filled = SchedulerDomain.fillSchedule(state, NOW, horizonMillis = NOW + HOUR)
        val due = SchedulerDomain.horizonRefillDueMillis(filled, NOW, null, UTC, reachMillis = HOUR)
        assertTrue(due > NOW, "not due at once")
    }
}
