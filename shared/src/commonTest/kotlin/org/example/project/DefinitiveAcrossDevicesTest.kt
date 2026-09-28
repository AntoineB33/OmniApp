package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.RulePlacement
import org.example.project.scheduler.model.SleepSchedule
import org.example.project.scheduler.model.TaskPanel
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState

/**
 * `docs/scheduler_requirements.md` § *Progressive Calculation*: *"When the schedule is definitive for any t < t1, it
 * means that for all the next set of rules the scheduler will return until it is done, they will all indicate the
 * same schedule rules for any t < t1."* — held across the account's devices (`docs/invariants/scheduler.md` § *One
 * device plans*).
 *
 * Plans made apart compete on the score, and that used to be done by REPLACING a plan: a device that had planned for
 * itself (past the rules deadline) took the leader's rules in from the line, and a leader answered a counter with a
 * re-plan from the line — each rewriting a schedule already published as definitive. Now a device that holds a plan
 * for the very rules it takes in keeps what it has materialized, and the leader folds a counter in as a seed of an
 * extension: the continuations compete past the front only.
 */
class DefinitiveAcrossDevicesTest {
    private val MIN = 60_000L
    private val HOUR = 60 * MIN
    private val NOW = 1_700_000_000_000L

    private fun planned(): SchedulerState {
        var s = SchedulerState.empty()
        listOf("A", "B").forEachIndexed { i, title ->
            val row = s.lists[s.rootListId]!!.cellIds.let { it.getOrNull(i) ?: it.last() }
            s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(row, title))
        }
        s = s.copy(sleep = SleepSchedule(sleepDurationMinutes = 0))
        return s.copy(panels = SchedulerDomain.fillSchedule(s, NOW, horizonMillis = NOW + 2 * HOUR))
    }

    private fun head(panels: List<TaskPanel>, until: Long) =
        panels.filter { it.auto && it.startEpochMillis < until }.map { Triple(it.taskId, it.startEpochMillis, minOf(it.endEpochMillis, until)) }

    @Test
    fun rules_taken_in_for_rules_already_planned_keep_the_materialized_head() {
        val s = planned()
        val front = SchedulerDomain.firstFreeMoment(s.panels, NOW)
        assertTrue(front >= NOW + HOUR, "the case needs a materialized plan")
        val b = s.tasks.values.single { it.title == "B" }.id
        // Another device's rules: B from the line, for four hours — not what this device made definitive.
        val theirs = listOf(RulePlacement(b, NOW, NOW + 4 * HOUR, null))
        val kept =
            SchedulerReducer.reduce(s, SchedulerIntent.AdoptScheduleRules(NOW, theirs, null, NOW + 4 * HOUR, keepHead = true))
        assertEquals(head(s.panels, front), head(kept.panels, front), "what was definitive stays so")
        val replaced =
            SchedulerReducer.reduce(s, SchedulerIntent.AdoptScheduleRules(NOW, theirs, null, NOW + 4 * HOUR, keepHead = false))
        assertTrue(head(replaced.panels, front) != head(s.panels, front), "control: rules for NEW rules replace the old plan")
    }

    @Test
    fun a_counter_competes_as_a_seed_of_an_extension_past_the_front() {
        val s = planned()
        val front = SchedulerDomain.firstFreeMoment(s.panels, NOW)
        val b = s.tasks.values.single { it.title == "B" }.id
        val goal = SchedulerReducer.scheduleHorizonEndMillis
        SchedulerReducer.scheduleHorizonEndMillis = { it + 6 * HOUR }
        val extended =
            try {
                SchedulerReducer.reduce(
                    s,
                    SchedulerIntent.ExtendSchedule(NOW, NOW + 4 * HOUR, seeds = listOf(listOf(RulePlacement(b, NOW, NOW + 4 * HOUR, null)))),
                )
            } finally {
                SchedulerReducer.scheduleHorizonEndMillis = goal
            }
        assertEquals(head(s.panels, front), head(extended.panels, front), "the seed competes past the front only")
        assertTrue(extended.panels.any { it.auto && it.startEpochMillis >= front }, "and the plan is extended")
    }
}
