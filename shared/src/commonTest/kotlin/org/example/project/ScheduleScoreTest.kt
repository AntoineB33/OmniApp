package org.example.project

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.PlanBlock
import org.example.project.scheduler.domain.PlanTask
import org.example.project.scheduler.domain.PlanWindow
import org.example.project.scheduler.domain.ScheduleOptimizer
import org.example.project.scheduler.domain.ScoreModel
import org.example.project.scheduler.domain.SearchBudget
import org.example.project.scheduler.model.TaskId

/**
 * `docs/scheduler_score.md`: the score reproduces the requirements' own examples, and
 * the optimizer finds the continuation the score calls best.
 */
class ScheduleScoreTest {
    private val MIN = 60_000L
    private val HOUR = 60 * MIN

    private fun task(name: String, priority: Double, minutes: Long) = PlanTask(TaskId(name), priority, minutes * MIN)

    private fun model(
        tasks: List<PlanTask>,
        hours: Long,
        blocks: List<PlanBlock> = emptyList(),
        windows: List<PlanWindow> = emptyList(),
    ) = ScoreModel(tasks, blocks, windows, 0L, hours * HOUR)

    /**
     * The steady score RATE of a cyclic pattern: repeated from zero lag for a warm-up, then measured over [periods]
     * more periods one minute at a time with the discount restarted every minute, so patterns of different
     * periods are compared on the same undiscounted footing. Returned per period of 2 hours.
     */
    private fun periodCost(m: ScoreModel, pattern: List<Pair<Int, Long>>, periods: Int): Double {
        val period = pattern.sumOf { it.second }
        var c = m.cursor()
        var u = 0.0
        repeat(periods) { for ((t, d) in pattern) { u += d * MIN; m.serve(c, t, u) } }
        var total = 0.0
        repeat(periods) {
            for ((t, d) in pattern) {
                repeat(d.toInt()) {
                    val fresh = m.rebase(c)
                    u += MIN
                    m.serve(fresh, t, u)
                    m.settle(fresh)
                    total += fresh.cost
                    c = fresh
                }
            }
        }
        return total / (periods * period) * 120
    }

    /** Consecutive runs of one task (split only where the alternative changes) as (task, whole minutes). */
    private fun merged(runs: List<ScheduleOptimizer.Run>): List<Pair<Int, Long>> {
        val out = ArrayList<Pair<Int, Double>>()
        for (r in runs) {
            val last = out.lastOrNull()
            if (last != null && last.first == r.task) out[out.size - 1] = r.task to last.second + (r.toU - r.fromU)
            else out += r.task to (r.toU - r.fromU)
        }
        return out.map { it.first to kotlin.math.round(it.second / MIN).toLong() }
    }

    @Test
    fun two_50_percent_tasks_alternate_at_their_minimum_not_at_an_hour() {
        val m = model(listOf(task("A", 0.5, 10), task("B", 0.5, 10)), 48)
        val ten = periodCost(m, listOf(0 to 10L, 1 to 10L), 30)
        val twenty = periodCost(m, listOf(0 to 20L, 1 to 20L), 15)
        val sixty = periodCost(m, listOf(0 to 60L, 1 to 60L), 5)
        val five = periodCost(m, listOf(0 to 5L, 1 to 5L), 60)
        assertTrue(ten < twenty, "10/10 ($ten) must beat 20/20 ($twenty)")
        assertTrue(twenty < sixty, "20/20 ($twenty) must beat 60/60 ($sixty)")
        assertTrue(ten < five, "10/10 ($ten) must beat 5/5 below the minimum ($five)")

        val plan = ScheduleOptimizer(m).plan(m.cursor(), 4.0 * HOUR)
        val lengths = merged(plan.runs).drop(1).dropLast(1).map { it.second }
        assertTrue(lengths.all { it == 10L }, "the plan alternates every 10 min: $lengths")
    }

    @Test
    fun the_requirements_three_task_example_is_the_best_score() {
        val m = model(listOf(task("A", 1.0, 30), task("B", 1.0, 15), task("C", 1.0, 15)), 48)
        val expected = periodCost(m, listOf(0 to 30L, 1 to 15L, 2 to 15L, 1 to 15L, 2 to 15L), 20)
        val blocks = periodCost(m, listOf(0 to 30L, 1 to 30L, 2 to 30L), 20)
        val splitA = periodCost(m, listOf(0 to 15L, 1 to 15L, 2 to 15L), 40)
        val bc = periodCost(m, listOf(0 to 30L, 1 to 15L, 2 to 30L, 1 to 15L), 20)
        assertTrue(expected < blocks, "A30 B15 C15 B15 C15 ($expected) must beat A30 B30 C30 ($blocks)")
        assertTrue(expected < splitA, "… and splitting A below its minimum ($splitA)")
        assertTrue(expected < bc, "… and A30 B15 C30 B15 ($bc)")

        val plan = ScheduleOptimizer(m).plan(m.cursor(), 9.0 * HOUR)
        val seq = merged(plan.runs)
        // After the first A, the pattern repeats: A30 then B15 C15 B15 C15 (or C/B swapped).
        val firstA = seq.indexOfFirst { it.first == 0 }
        val steady = seq.drop(firstA).dropLast(5)
        for ((k, run) in steady.withIndex()) {
            if (run.first == 0) {
                assertEquals(30L, run.second, "A runs 30 min: $seq")
                val between = steady.drop(k + 1).take(4)
                if (between.size == 4) {
                    assertTrue(between.all { it.first != 0 && it.second == 15L }, "four 15-min B/C runs between two A: $seq")
                }
            }
        }
    }

    @Test
    fun no_idling_and_resilience_zero_forbids() {
        // B may not run in [1h, 3h).
        val tasks = listOf(task("A", 0.5, 20), task("B", 0.5, 20))
        val w = PlanWindow(HOUR, 3 * HOUR, mapOf(TaskId("B") to 0.0))
        val m = model(tasks, 12, windows = listOf(w))
        val plan = ScheduleOptimizer(m).plan(m.cursor(), 6.0 * HOUR)
        var u = 0.0
        for (r in plan.runs) {
            assertEquals(u, r.fromU, 1e-3, "the plan leaves no schedulable time empty")
            u = r.toU
            if (r.task == 1) assertTrue(r.toU <= HOUR + 1e-3 || r.fromU >= 3 * HOUR - 1e-3, "B inside its ban: $r")
        }
        assertEquals(6.0 * HOUR, u, 1e-3)
    }

    @Test
    fun a_stretch_nobody_may_run_in_is_invisible_to_the_score() {
        val tasks = listOf(task("A", 0.5, 30), task("B", 0.5, 30))
        val plain = model(tasks, 12)
        val gap = PlanWindow(HOUR, HOUR + 20_000, emptyMap(), defaultMultiplier = 0.0)
        val broken = model(tasks, 12, windows = listOf(gap))
        assertEquals(plain.uEnd - 20_000.0, broken.uEnd, 1e-6)
        val a = ScheduleOptimizer(plain).plan(plain.cursor(), 4.0 * HOUR, alternatives = false)
        val b = ScheduleOptimizer(broken).plan(broken.cursor(), 4.0 * HOUR, alternatives = false)
        assertEquals(a.runs.map { it.task }, b.runs.map { it.task })
        assertEquals(a.cost, b.cost, 1e-6 * abs(a.cost))
    }

    @Test
    fun compensation_decays_with_distance_and_is_bounded() {
        val tasks = listOf(task("A", 0.5, 30), task("B", 0.5, 30))
        fun targetAfter(banHours: Long, afterMinutes: Long): Double {
            val w = PlanWindow(10 * HOUR, (10 + banHours) * HOUR, mapOf(TaskId("B") to 0.0))
            val m = model(tasks, 60, windows = listOf(w))
            return m.targetAt(((10 + banHours) * HOUR + afterMinutes * MIN).toDouble())[1]
        }
        val near = targetAfter(4, 0)
        val far = targetAfter(4, 240)
        assertTrue(near > 0.6, "B is repaid right after its ban: $near")
        assertTrue(far < 0.51, "the repayment has decayed four hours later: $far")
        // A ban 6x longer buys almost no more at the edge.
        assertTrue(targetAfter(24, 0) - near < 0.02)
        // Before the ban, the influence reaches back too.
        val m = model(tasks, 60, windows = listOf(PlanWindow(10 * HOUR, 14 * HOUR, mapOf(TaskId("B") to 0.0))))
        assertTrue(m.targetAt((10 * HOUR - MIN).toDouble())[1] > 0.6)
        // A stretch that turns everybody away compensates nobody.
        val night = model(tasks, 60, windows = listOf(PlanWindow(10 * HOUR, 18 * HOUR, emptyMap(), defaultMultiplier = 0.0)))
        assertEquals(0.5, night.targetAt((10 * HOUR).toDouble())[1], 1e-9)
    }

    @Test
    fun the_exhaustive_search_certifies_the_best_continuation_on_a_small_case() {
        val tasks = listOf(task("A", 0.6, 20), task("B", 0.4, 20))
        val block = PlanBlock(TaskId("A"), 50 * MIN, 70 * MIN)
        val m = model(tasks, 6, blocks = listOf(block))
        val opt = ScheduleOptimizer(m, searchBudget = 2_000_000)
        val start = m.cursor()
        val until = 2.0 * HOUR
        val rolled = opt.plan(start, until, alternatives = false)
        val best = opt.certify(start, until)
        assertTrue(best.certified, "the search finishes on a case this small")
        assertTrue(best.cost <= rolled.cost * (1 + 1e-9), "certified ${best.cost} vs rollout ${rolled.cost}")
        assertEquals(best.cost, opt.score(start, best.runs), 1e-6 * best.cost)
        // The pre-placed block is kept.
        assertTrue(best.runs.any { it.task == 0 && it.fromU <= 50.0 * MIN + 1e-3 && it.toU >= 70.0 * MIN - 1e-3 })
    }

    @Test
    fun a_seed_that_scores_better_than_the_search_is_what_the_plan_returns() {
        // `docs/scheduler_score.md` § *Degradation*: the plan a re-plan replaces, or another device's plan for the
        // same rules, competes on the score — a re-plan never returns a continuation worse than one it was shown.
        val tasks = listOf(task("A", 0.6, 20), task("B", 0.4, 20))
        val m = model(tasks, 6, blocks = listOf(PlanBlock(TaskId("A"), 50 * MIN, 70 * MIN)))
        val until = 2.0 * HOUR
        val best = ScheduleOptimizer(m, searchBudget = 2_000_000).certify(m.cursor(), until)
        assertTrue(best.certified)
        val seeded = ScheduleOptimizer(m).plan(m.cursor(), until, alternatives = false, seeds = listOf(best.runs))
        assertTrue(seeded.cost <= best.cost * (1 + 1e-9), "the seed's score is reached: ${seeded.cost} vs ${best.cost}")
    }

    @Test
    fun a_seed_that_breaks_a_hard_constraint_is_cut_where_it_breaks_it() {
        // A seed laid under other rules: A over a stretch A may no longer run in. What is kept of it stops there and
        // the rest is searched, so the plan never puts a task where it may not run.
        val tasks = listOf(task("A", 0.5, 20), task("B", 0.5, 20))
        val ban = PlanWindow(HOUR, 2 * HOUR, mapOf(TaskId("A") to 0.0))
        val m = model(tasks, 6, windows = listOf(ban))
        val until = 3.0 * HOUR
        val stale = listOf(ScheduleOptimizer.Run(0, 0.0, until, -1))
        val plan = ScheduleOptimizer(m).plan(m.cursor(), until, alternatives = false, seeds = listOf(stale))
        assertTrue(plan.runs.none { it.task == 0 && it.fromU < 2.0 * HOUR && it.toU > HOUR + 1e-3 }, "A never runs inside its ban")
        var u = 0.0
        for (r in plan.runs.sortedBy { it.fromU }) {
            assertEquals(u, r.fromU, 1e-3, "no idling")
            u = r.toU
        }
        assertEquals(until, u, 1e-3)
    }

    @Test
    fun with_the_time_to_finish_the_plan_is_the_certified_best() {
        val tasks = listOf(task("A", 0.6, 20), task("B", 0.4, 20))
        val m = model(tasks, 6, blocks = listOf(PlanBlock(TaskId("A"), 50 * MIN, 70 * MIN)))
        val until = 2.0 * HOUR
        val best = ScheduleOptimizer(m, searchBudget = 2_000_000).certify(m.cursor(), until)
        val plan = ScheduleOptimizer(m).plan(m.cursor(), until, alternatives = false, budget = SearchBudget.of(20_000))
        assertTrue(plan.certified, "${plan.report}")
        assertTrue(plan.cost <= best.cost * (1 + 1e-9), "${plan.cost} vs ${best.cost}")
        val untimed = ScheduleOptimizer(m).plan(m.cursor(), until, alternatives = false)
        assertTrue(!untimed.certified && !untimed.report.exhausted, "no time granted, nothing claimed: ${untimed.report}")
    }

    @Test
    fun the_alternative_is_another_permitted_task() {
        val tasks = listOf(task("A", 0.5, 20), task("B", 0.3, 20), task("C", 0.2, 20))
        val m = model(tasks, 12)
        val plan = ScheduleOptimizer(m).plan(m.cursor(), 3.0 * HOUR)
        for (r in plan.runs) {
            assertTrue(r.alternative >= 0 && r.alternative != r.task, "every run names another task: $r")
        }
        val alone = model(listOf(task("A", 1.0, 20)), 12)
        assertTrue(ScheduleOptimizer(alone).plan(alone.cursor(), HOUR.toDouble()).runs.all { it.alternative == -1 })
    }
}
