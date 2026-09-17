package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.PlanBlock
import org.example.project.scheduler.domain.PlanTask
import org.example.project.scheduler.domain.PlanWindow
import org.example.project.scheduler.domain.ScheduleOptimizer
import org.example.project.scheduler.domain.ScoreModel
import org.example.project.scheduler.model.TaskId

/**
 * `docs/scheduler_score.md` § *Degradation*: the whole-continuation improvement pass. It may only move the plan
 * closer to the best score — never away from it — and never at the price of a hard constraint. And criterion 2's
 * charge has the two properties the definition states for it.
 */
class ScheduleImproverTest {
    private val MIN = 60_000L
    private val HOUR = 60 * MIN

    private fun task(name: String, priority: Double, minutes: Long) = PlanTask(TaskId(name), priority, minutes * MIN)

    /** Several tasks, two of them turned away for half an hour every three hours, and one pre-placed hour. */
    private fun busyModel(n: Int): ScoreModel {
        val tasks = (0 until n).map { i -> task("T$i", 1.0 + (i % 5), listOf(15L, 30L, 45L, 60L)[i % 4]) }
            .sortedByDescending { it.priority }
        val windows = (0 until 8).map { k ->
            PlanWindow(
                k * 3 * HOUR + 2 * HOUR,
                k * 3 * HOUR + 2 * HOUR + 30 * MIN,
                mapOf(TaskId("T1") to 0.0, TaskId("T2") to 0.0),
            )
        }
        val block = PlanBlock(TaskId("T0"), 7 * HOUR, 8 * HOUR)
        return ScoreModel(tasks, listOf(block), windows, 0L, 24 * HOUR)
    }

    private fun assertValid(m: ScoreModel, runs: List<ScheduleOptimizer.Run>, until: Double) {
        var u = 0.0
        for (r in runs.sortedBy { it.fromU }) {
            assertEquals(u, r.fromU, 1e-3, "the continuation is contiguous (no idling): $r")
            assertTrue(m.runLimit(r.task, r.fromU) >= r.toU - 1e-3, "task ${r.task} may run over all of $r")
            u = r.toU
        }
        assertEquals(until, u, 1e-3, "the continuation reaches the end")
    }

    @Test
    fun the_improvement_never_worsens_the_score_and_keeps_every_constraint() {
        for (n in listOf(3, 8)) {
            val m = busyModel(n)
            val until = 24.0 * HOUR
            val rollout = ScheduleOptimizer(m, improveBudget = 0).plan(m.cursor(), until, alternatives = false)
            val improved = ScheduleOptimizer(m).plan(m.cursor(), until, alternatives = false)
            assertTrue(
                improved.cost <= rollout.cost * (1 + 1e-12),
                "n=$n: improved ${improved.cost} must not exceed the rollout plan ${rollout.cost}",
            )
            assertEquals(improved.cost, ScheduleOptimizer(m).score(m.cursor(), improved.runs), 1e-9 * improved.cost)
            assertValid(m, improved.runs, until)
            // The pre-placed hour is still exactly where it was, and still its owner's.
            val t0 = m.indexOf.getValue(TaskId("T0"))
            assertTrue(improved.runs.any { it.task == t0 && it.fromU <= 7.0 * HOUR + 1e-3 && it.toU >= 8.0 * HOUR - 1e-3 })
        }
    }

    @Test
    fun a_forced_or_refused_first_task_survives_the_improvement() {
        val m = busyModel(3)
        val forced = m.indexOf.getValue(TaskId("T2"))
        val plan = ScheduleOptimizer(m).plan(m.cursor(), 6.0 * HOUR, forcedFirst = forced, alternatives = false)
        assertEquals(forced, plan.runs.first().task)
        val refused = m.indexOf.getValue(TaskId("T0"))
        val other = ScheduleOptimizer(m).plan(m.cursor(), 6.0 * HOUR, refusedFirst = refused, alternatives = false)
        assertTrue(other.runs.first().task != refused)
    }

    @Test
    fun the_improvement_does_not_trim_panels_below_their_minimum_to_chase_the_percentages() {
        // The regression that shaped criterion 2: a squared shortfall made the first minutes missed free, and the
        // improvement pass then shaved a minute or two off most panels (25 of 40 on this very model).
        val m = busyModel(3)
        val runs = ScheduleOptimizer(m).plan(m.cursor(), 24.0 * HOUR, alternatives = false).runs.sortedBy { it.fromU }
        val merged = ArrayList<Triple<Int, Double, Double>>()
        for (r in runs) {
            val last = merged.lastOrNull()
            if (last != null && last.first == r.task) merged[merged.size - 1] = Triple(r.task, last.second, r.toU)
            else merged += Triple(r.task, r.fromU, r.toU)
        }
        // The last panel is cut by the end of the continuation, not by a choice.
        val short = merged.dropLast(1).filter { (t, a, b) -> b - a < m.minimum[t] - 1.0 }
        assertTrue(short.size <= 1, "panels short of their minimum: $short")
    }

    @Test
    fun a_shortfall_costs_from_its_first_minute_and_scales_with_the_task_window() {
        val m = ScoreModel(listOf(task("A", 1.0, 30), task("B", 3.0, 30)), emptyList(), emptyList(), 0L, 24 * HOUR)
        val s = MIN.toDouble()
        for (i in 0 until m.n) {
            // Slope at zero: 2·M·τ — the first minute already costs about that much per minute.
            assertTrue(m.shortfallCost(i, s) >= 2.0 * m.minimum[i] * m.tau[i] * s)
            // Measured in the task's own window, the charge does not depend on the task's share.
            assertEquals(s * (2.0 * m.minimum[i] + s), m.shortfallCost(i, s) / m.tau[i], 1e-6 * s * m.minimum[i])
        }
        assertEquals(0.0, m.shortfallCost(0, 0.0))
    }
}
