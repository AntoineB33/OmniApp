package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.datetime.TimeZone
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState

/**
 * `docs/scheduler_requirements.md` § *No idling* and § *Priority, Granularity and Compensation*, on the tree
 * that breaks the arithmetic behind both.
 *
 * Nothing bounds a priority percentage from below, so one leaf with a near-zero share makes its own window
 * `τ = M / π` (and the score's discount horizon `Θ`) astronomically large. The previous scheduler's arithmetic
 * saturated on it and left the rest of the horizon EMPTY; the score must stay finite and the fill must still
 * cover the horizon.
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
}
