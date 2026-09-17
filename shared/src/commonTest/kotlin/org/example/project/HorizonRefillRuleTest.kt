package org.example.project

import kotlin.test.Test
import kotlin.test.assertTrue
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
 * With no calendar open the goal is the ROLLING `now + 10 min` floor, which is that same shape; the fill
 * reaching twice the floor is what keeps it satisfiable.
 */
class HorizonRefillRuleTest {

    private val MINUTE_MS = 60_000L
    private val FLOOR = SchedulerDomain.SCHEDULE_GOAL_FLOOR_MILLIS

    /** One schedulable task under "main", so the fill has something to lay down across the horizon. */
    private fun stateWithOneTask(): Pair<SchedulerState, TaskId> {
        var s = SchedulerState.empty()
        val c0 = s.lists[s.rootListId]!!.cellIds[0]
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(c0, "A"))
        return s to s.tasks.keys.first { s.tasks[it]!!.title == "A" }
    }

    private fun fillAtFloor(state: SchedulerState, now: Long) =
        SchedulerDomain.fillSchedule(state, now, horizonMillis = SchedulerDomain.scheduleHorizonEndMillis(now, null))

    @Test
    fun a_fill_pushes_its_own_next_refill_into_the_future() {
        val (state, _) = stateWithOneTask()
        val now = 1_700_000_000_000L
        val filled = fillAtFloor(state, now)

        val coverage = SchedulerDomain.firstFreeMoment(filled, now) - now
        assertTrue(coverage >= 2 * FLOOR, "expected the fill to cover twice the floor, got ${coverage / MINUTE_MS} min")

        val due = SchedulerDomain.horizonRefillDueMillis(filled, now, null)
        assertTrue(due > now, "a fresh fill must not be immediately due again (due = now + ${due - now} ms)")
    }

    @Test
    fun the_refill_comes_due_once_per_floor_and_never_lets_the_plan_run_out() {
        val now = 1_700_000_000_000L
        // An empty schedule covers nothing, so a refill is overdue.
        assertTrue(SchedulerDomain.horizonRefillDueMillis(emptyList(), now, null) <= now)

        // A fill made at `now` comes due once the line has eaten one floor into its coverage — while ten
        // minutes of plan are still ahead of it.
        val (state, _) = stateWithOneTask()
        val filled = fillAtFloor(state, now)
        val due = now + FLOOR
        assertTrue(SchedulerDomain.horizonRefillDueMillis(filled, due - 1, null) > due - 1, "not due yet")
        assertTrue(SchedulerDomain.horizonRefillDueMillis(filled, due, null) <= due, "due once a floor elapsed")
        assertTrue(SchedulerDomain.firstFreeMoment(filled, due) >= due + FLOOR, "the goal is still covered when due")

        // Extending there (keeping the head) again pushes the next due a floor later.
        val extended =
            SchedulerDomain.fillSchedule(
                state.copy(panels = filled), due,
                horizonMillis = SchedulerDomain.scheduleHorizonEndMillis(due, null),
                keepExistingUntilMillis = SchedulerDomain.firstFreeMoment(filled, due),
            )
        assertTrue(SchedulerDomain.horizonRefillDueMillis(extended, due, null) > due, "the extension is not due at once")
    }
}
