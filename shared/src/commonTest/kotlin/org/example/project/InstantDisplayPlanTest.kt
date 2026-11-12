package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.datetime.TimeZone
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.model.TaskPanel
import org.example.project.scheduler.model.TaskTimeRange
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState

/**
 * `docs/scheduler_requirements.md` § *Two paces*, Example 3: *"the user goes to see thousands of years in the future:
 * the schedule displayed doesn't break any rules, and the ratio targets and minimum lengths are roughly respected …
 * no inactivity area where a task with a ratio target can fit in"* — through [SchedulerDomain.instantDisplayPlan], the
 * one funnel the calendar asks. A window two years out was empty on a real account (2026-11-12): the fill walked to it
 * from the front, for 140 s.
 */
class InstantDisplayPlanTest {
    private val MIN = 60_000L
    private val HOUR = 3_600_000L
    private val DAY = 24 * HOUR
    private val NOW = 1_700_000_000_000L - 1_700_000_000_000L % DAY + 9 * HOUR
    private val tz = TimeZone.UTC

    /** Three tasks at 50 / 30 / 20 %, minimums 30 / 20 / 15 min, the default nights and screen breaks. */
    private fun state(): Pair<SchedulerState, List<TaskId>> {
        var s = SchedulerState.empty()
        listOf("A", "B", "C").forEachIndexed { i, name ->
            s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(s.lists[s.rootListId]!!.cellIds[i], name))
        }
        val ids = listOf("A", "B", "C").map { n -> s.tasks.keys.first { s.tasks[it]!!.title == n } }
        listOf(30, 20, 15).zip(ids).forEach { (m, id) -> s = SchedulerReducer.reduce(s, SchedulerIntent.SetTaskMinimumTime(id, m)) }
        listOf(5.0, 3.0, 2.0).forEachIndexed { i, w ->
            s = SchedulerReducer.reduce(s, SchedulerIntent.SetPriorityWeight(s.lists[s.rootListId]!!.cellIds[i], 0, w))
        }
        return s to ids
    }

    private fun isTask(p: TaskPanel) = p.taskId != null && !p.chore && !p.isRestrictivePeriod && !p.screenBreak && !p.sleep

    /** What of [from, to) no panel of the plan covers — a task, a night, the hour before bed, a break. */
    private fun uncovered(plan: List<TaskPanel>, from: Long, to: Long): List<TaskTimeRange> =
        SchedulerDomain.subtractRegions(
            listOf(TaskTimeRange(from, to)),
            plan.filter { it.endEpochMillis > from && it.startEpochMillis < to }.map { TaskTimeRange(it.startEpochMillis, it.endEpochMillis) },
        ).filter { it.endEpochMillis - it.startEpochMillis >= MIN }

    private fun assertNothingObviouslyWrong(label: String, plan: List<TaskPanel>, ids: List<TaskId>, from: Long, to: Long) {
        assertEquals(emptyList(), uncovered(plan, from, to), "$label: a stretch with no task where one may run")
        val served = ids.map { id ->
            plan.filter { isTask(it) && it.taskId == id }
                .sumOf { (minOf(it.endEpochMillis, to) - maxOf(it.startEpochMillis, from)).coerceAtLeast(0L) }
        }
        val total = served.sum().toDouble()
        assertTrue(total > 0.0, "$label: no task at all")
        listOf(0.5, 0.3, 0.2).zip(served).forEach { (target, ms) ->
            val share = ms / total
            assertTrue(share in target * 0.6..target * 1.4, "$label: share $share for a target of $target ($served)")
        }
    }

    @Test
    fun a_window_two_years_out_is_planned_on_its_own_and_is_full() {
        val (s, ids) = state()
        val start = NOW + 730 * DAY - (NOW + 730 * DAY) % DAY
        val end = start + 7 * DAY
        assertTrue(SchedulerDomain.instantDisplayDetached(NOW, start))
        val plan = SchedulerDomain.instantDisplayPlan(s, NOW, NOW, start, end, tz)
        assertNothingObviouslyWrong("two years", plan, ids, start, end)
    }

    @Test
    fun a_window_a_thousand_years_out_is_full_too() {
        val (s, ids) = state()
        val far = NOW + 365_250L * DAY
        val start = far - far % DAY
        val end = start + 3 * DAY
        val plan = SchedulerDomain.instantDisplayPlan(s, NOW, NOW, start, end, tz)
        assertNothingObviouslyWrong("a thousand years", plan, ids, start, end)
    }

    @Test
    fun a_window_near_the_front_continues_the_plan() {
        val (s, ids) = state()
        val start = NOW + 2 * DAY - (NOW + 2 * DAY) % DAY
        val end = start + 2 * DAY
        assertFalse(SchedulerDomain.instantDisplayDetached(NOW, start))
        val plan = SchedulerDomain.instantDisplayPlan(s, NOW, NOW, start, end, tz)
        assertEquals(
            SchedulerDomain.fillSchedule(
                s, NOW, timeZone = tz, horizonMillis = maxOf(end, SchedulerDomain.scheduleGoalEndMillis(NOW, end, tz)),
                keepExistingUntilMillis = NOW, instant = true,
            ).map { Triple(it.taskId, it.startEpochMillis, it.endEpochMillis) },
            plan.map { Triple(it.taskId, it.startEpochMillis, it.endEpochMillis) },
        )
        assertNothingObviouslyWrong("near", plan, ids, start, end)
    }
}
