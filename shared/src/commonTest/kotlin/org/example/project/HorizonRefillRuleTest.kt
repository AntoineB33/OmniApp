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
 * With no calendar open the goal is the END OF THE CURRENT WEEK — a fixed instant, so the dangerous shape is
 * confined to the week's last ten minutes, where the ROLLING `now + 10 min` floor takes over and a fill is once
 * again judged against a target that moves with the line. The fill reaching TWICE the floor is what keeps it
 * satisfiable there, and both regimes are pinned below.
 */
class HorizonRefillRuleTest {

    private val MINUTE_MS = 60_000L
    private val FLOOR = SchedulerDomain.SCHEDULE_GOAL_FLOOR_MILLIS
    private val UTC = TimeZone.UTC

    /** Sunday 2023-11-19, 23:55 UTC — five minutes before the week ends, so the rolling floor governs. */
    private val LATE_SUNDAY = 1_700_438_100_000L

    /** Monday 2023-11-20, 00:00 UTC, the end of [LATE_SUNDAY]'s week. */
    private val WEEK_END = 1_700_438_400_000L

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
    fun a_fill_pushes_its_own_next_refill_into_the_future() {
        val (state, _) = stateWithOneTask()
        val filled = fillTo(state, LATE_SUNDAY)

        val coverage = SchedulerDomain.firstFreeMoment(filled, LATE_SUNDAY) - LATE_SUNDAY
        assertTrue(coverage >= 2 * FLOOR, "expected the fill to cover twice the floor, got ${coverage / MINUTE_MS} min")

        val due = SchedulerDomain.horizonRefillDueMillis(filled, LATE_SUNDAY, null, UTC)
        assertTrue(due > LATE_SUNDAY, "a fresh fill must not be immediately due again (due = now + ${due - LATE_SUNDAY} ms)")
    }

    @Test
    fun the_refill_comes_due_at_the_rollover_and_never_lets_the_plan_run_out() {
        // An empty schedule covers nothing, so a refill is overdue.
        assertTrue(SchedulerDomain.horizonRefillDueMillis(emptyList(), LATE_SUNDAY, null, UTC) <= LATE_SUNDAY)

        // The refill's own cadence is now the WEEK'S: a plan that reached its week's end is complete for as
        // long as that week lasts, and the goal steps a week forward the instant the line crosses into the
        // next one. (The rolling floor survives underneath it, but it can only ever govern a week's last ten
        // minutes — and the rollover falls inside that window, which is why it is what this reads.)
        val (state, _) = stateWithOneTask()
        val filled = fillTo(state, LATE_SUNDAY)
        assertTrue(
            SchedulerDomain.horizonRefillDueMillis(filled, WEEK_END - 1, null, UTC) > WEEK_END - 1,
            "not due while the week it reached is still running",
        )
        assertTrue(
            SchedulerDomain.horizonRefillDueMillis(filled, WEEK_END, null, UTC) <= WEEK_END,
            "due as the week rolls over and the goal steps a week on",
        )
        // …and never at the price of running out: ten minutes of plan are still ahead of the line when it does.
        assertTrue(
            SchedulerDomain.firstFreeMoment(filled, WEEK_END) >= WEEK_END + FLOOR,
            "the goal is still covered when due",
        )
    }

    @Test
    fun a_fill_to_the_end_of_the_week_is_not_due_again_until_the_floor_bites() {
        // The other regime, which is where the app spends every week but its last ten minutes: the goal is a
        // FIXED instant, so a fill that reached it is complete and the loop simply does not turn again — until
        // the line has eaten a floor into the coverage, which is the same anti-spin arithmetic.
        val (state, _) = stateWithOneTask()
        val now = 1_700_000_000_000L // Tuesday 2023-11-14, 22:13 UTC
        val filled = fillTo(state, now)
        assertTrue(
            SchedulerDomain.firstFreeMoment(filled, now) >= WEEK_END,
            "a fill with no calendar open must reach the end of the week",
        )
        assertTrue(SchedulerDomain.horizonRefillDueMillis(filled, now, null, UTC) >= WEEK_END - FLOOR)
    }
}
