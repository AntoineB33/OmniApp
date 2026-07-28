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
 * **a fill must push its own next due instant into the future.** It did not — the due instant was
 * `firstFreeMoment − 168h`, and since a fill materializes to exactly `now + 168h`, the best it could ever
 * produce was "due right now". Every refill therefore triggered the next one with no delay, which pegged
 * the UI thread and left the desktop app as a tray icon whose window never presented a frame (2026-07-28).
 */
class HorizonRefillRuleTest {

    private val HOUR_MS = 3_600_000L

    /** One schedulable task under "main", so the fill has something to lay down across the horizon. */
    private fun stateWithOneTask(): Pair<SchedulerState, TaskId> {
        var s = SchedulerState.empty()
        val c0 = s.lists[s.rootListId]!!.cellIds[0]
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(c0, "A"))
        return s to s.tasks.keys.first { s.tasks[it]!!.title == "A" }
    }

    @Test
    fun a_fill_pushes_its_own_next_refill_into_the_future() {
        val (state, _) = stateWithOneTask()
        val now = 1_700_000_000_000L
        val filled = SchedulerDomain.fillSchedule(state, now)

        // The fill covers the horizon...
        val coverage = SchedulerDomain.firstFreeMoment(filled, now) - now
        assertTrue(
            coverage > SchedulerDomain.SCHEDULE_HORIZON_MILLIS - SchedulerDomain.HORIZON_REFILL_MARGIN_MILLIS,
            "expected the fill to cover the horizon, got ${coverage / HOUR_MS}h",
        )

        // ...so the next refill it implies must be strictly LATER than the instant it ran at. This is the
        // regression: with no margin the due instant was `now` itself, and the refill re-triggered forever.
        val due = SchedulerDomain.horizonRefillDueMillis(filled, now)
        assertTrue(due > now, "a fresh fill must not be immediately due again (due = now + ${due - now} ms)")
    }

    @Test
    fun the_refill_stays_due_while_the_schedule_falls_short_of_the_horizon() {
        val now = 1_700_000_000_000L
        // An empty schedule covers nothing, so a refill is overdue — the trigger must still fire on a
        // genuine shortfall, which is the whole point of the rolling horizon.
        assertTrue(SchedulerDomain.horizonRefillDueMillis(emptyList(), now) <= now)

        // And a fill made at `now` comes due exactly one margin later, as the clock eats into its coverage:
        // not before, and from then on for good.
        val (state, _) = stateWithOneTask()
        val filled = SchedulerDomain.fillSchedule(state, now)
        val due = now + SchedulerDomain.HORIZON_REFILL_MARGIN_MILLIS
        assertTrue(SchedulerDomain.horizonRefillDueMillis(filled, due - 1) > due - 1, "not due yet")
        assertTrue(SchedulerDomain.horizonRefillDueMillis(filled, due) <= due, "due once the margin elapsed")
        assertTrue(
            SchedulerDomain.horizonRefillDueMillis(filled, due + HOUR_MS) <= due + HOUR_MS,
            "stays due afterwards",
        )
    }
}
