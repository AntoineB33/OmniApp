package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.datetime.TimeZone
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.domain.SchedulerPlanner
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState

/**
 * `docs/scheduler_requirements.md` § *No idling* and § *Priority, Granularity and Compensation*, on the tree
 * that breaks the arithmetic behind both.
 *
 * The plan's whole scale is `periodOf = max(mᵢ / pᵢ)`, and nothing bounds a priority percentage from below.
 * One leaf with a near-zero share therefore makes the minimal period astronomically large, `coarseCycle`'s
 * slot `pᵢ · period` saturate at `Long.MAX_VALUE`, and every `cursor + span` that follows wrap round to a
 * NEGATIVE instant — which the `end <= cursor` guard reads as "this slot places nothing", stopping the
 * materialization dead and leaving the rest of the horizon EMPTY.
 *
 * Found on the release account, where the relative-priority solver had written a stored weight of `4.99e42`
 * (see [RelativePriorityWeightBoundTest]): the minimal period was `9e42` hours and every fill left the
 * stretch between the last restrictive-period edge and the horizon unscheduled — 5 h 11 min of a 24 h
 * horizon.
 */
class DegeneratePlanScaleTest {

    private val HOUR = 3_600_000L

    /** Two siblings, the second given a share small enough to blow the minimal period past any horizon. */
    private fun degenerateState(): SchedulerState {
        var s = SchedulerState.empty()
        val root = s.rootListId
        val c0 = s.lists[root]!!.cellIds[0]
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(c0, "A"))
        val c1 = s.lists[root]!!.cellIds[1]
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(c1, "B"))
        // The shape the runaway bisection leaves behind: one weight enormously larger than its sibling's.
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetPriorityWeight(c0, 0, 1e40))
        for (id in s.tasks.keys) s = SchedulerReducer.reduce(s, SchedulerIntent.SetTaskMinimumTime(id, 45))
        return s
    }

    @Test
    fun the_minimal_period_really_does_explode() {
        val now = 1_700_000_000_000L
        val tasks = SchedulerDomain.planTasksOf(degenerateState(), now)
        val planner = SchedulerPlanner(tasks)
        assertTrue(
            planner.minPeriodMillis > 1e20,
            "the premise of this test: minPeriod = max(m/p) = ${planner.minPeriodMillis}",
        )
        // ...and the analytic cycle it feeds hands out a slot that has saturated.
        val allowed = tasks.map { it.id }
        assertTrue(
            planner.coarseCycle(allowed).any { it.durationMillis == SchedulerPlanner.FOREVER },
            "a slot of p*period saturates at Long.MAX_VALUE",
        )
    }

    @Test
    fun a_saturated_slot_still_fills_the_horizon() {
        val now = 1_700_000_000_000L
        val horizon = now + 24 * HOUR
        val panels =
            SchedulerDomain.fillSchedule(
                degenerateState(), now, TimeZone.UTC, horizonMillis = horizon,
            )
        // § *No idling*: nothing here restricts anybody, so every instant of the horizon carries a task.
        val covered =
            panels.filter { it.taskId != null && it.endEpochMillis > now && it.startEpochMillis < horizon }
                .map { maxOf(it.startEpochMillis, now) to minOf(it.endEpochMillis, horizon) }
                .sortedBy { it.first }
        var reach = now
        for ((start, end) in covered) {
            assertTrue(start <= reach, "gap of ${(start - reach) / 60_000} min at ${(reach - now) / 60_000} min")
            reach = maxOf(reach, end)
        }
        assertEquals(horizon, reach, "the fill must reach the horizon it was asked for")
    }

    @Test
    fun advance_never_wraps_round() {
        val cursor = 1_700_000_000_000L
        val bound = cursor + 24 * HOUR
        assertEquals(bound, SchedulerPlanner.advance(cursor, SchedulerPlanner.FOREVER, bound))
        assertEquals(bound, SchedulerPlanner.advance(cursor, 24 * HOUR, bound))
        assertEquals(cursor + HOUR, SchedulerPlanner.advance(cursor, HOUR, bound))
        // Already at (or past) the bound: never go backwards.
        assertEquals(bound, SchedulerPlanner.advance(bound, HOUR, bound))
    }
}
