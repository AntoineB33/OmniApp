package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.MipScheduleSolver
import org.example.project.scheduler.domain.OrTools
import org.example.project.scheduler.domain.PlanBlock
import org.example.project.scheduler.domain.PlanTask
import org.example.project.scheduler.domain.PlanWindow
import org.example.project.scheduler.domain.ScheduleOptimizer
import org.example.project.scheduler.domain.ScoreModel
import org.example.project.scheduler.domain.SearchBudget
import org.example.project.scheduler.model.TaskId

/**
 * `docs/invariants/scheduler.md` § *The best score*: the desktop's own solver (OR-Tools SCIP over windows of the
 * continuation). What is pinned: it loads on the desktop, what it returns is always legal and always scores lower than
 * what it was given, and inside [ScheduleOptimizer.plan] it can only move the result closer to the best score.
 */
class MipScheduleSolverTest {
    private val MIN = 60_000L
    private val HOUR = 60 * MIN

    private fun task(name: String, priority: Double, minutes: Long) = PlanTask(TaskId(name), priority, minutes * MIN)

    private fun assertLegal(m: ScoreModel, runs: List<ScheduleOptimizer.Run>, from: Double, until: Double) {
        var u = from
        for (r in runs.sortedBy { it.fromU }) {
            assertEquals(u, r.fromU, 1e-3, "runs are contiguous from the start (no idling)")
            assertTrue(m.runLimit(r.task, r.fromU) >= r.toU - 1e-3, "task ${r.task} may run over [${r.fromU}, ${r.toU})")
            u = r.toU
        }
        assertEquals(until, u, 1e-3, "the continuation reaches the end")
    }

    @Test
    fun or_tools_loads_on_the_desktop() {
        assertTrue(OrTools.available, "OR-Tools and SCIP load on Windows x86-64")
        assertNotNull(MipScheduleSolver.instance)
    }

    @Test
    fun a_monolithic_incumbent_is_broken_up_into_a_lower_score() {
        val m = ScoreModel(listOf(task("A", 0.5, 20), task("B", 0.5, 20)), emptyList(), emptyList(), 0L, 12 * HOUR)
        val until = 6.0 * HOUR
        // A for three hours, then B for three: legal, and far from the best score.
        val bad = listOf(ScheduleOptimizer.Run(0, 0.0, 3.0 * HOUR, -1), ScheduleOptimizer.Run(1, 3.0 * HOUR, until, -1))
        val scorer = ScheduleOptimizer(m, improveBudget = 0)
        val solver = MipScheduleSolver()
        val better = solver.improve(m, m.cursor(), until, bad, pinFirstRun = false, budget = SearchBudget.of(20_000))
        assertNotNull(better, "the program finds something better than two monoliths")
        assertLegal(m, better, 0.0, until)
        val before = scorer.score(m.cursor(), bad)
        val after = scorer.score(m.cursor(), better)
        println("monolith ${"%.4e".format(before)} → ${"%.4e".format(after)} (${solver.windowsKept}/${solver.windowsAsked} windows kept)")
        assertTrue(after < before, "the score went down: $before → $after")
    }

    @Test
    fun the_solver_never_breaks_a_restriction_or_a_pre_placed_block() {
        val tasks = listOf(task("A", 0.4, 30), task("B", 0.35, 15), task("C", 0.25, 15))
        val ban = PlanWindow(2 * HOUR, 4 * HOUR, mapOf(TaskId("A") to 0.0))
        val block = PlanBlock(TaskId("C"), 5 * HOUR, 5 * HOUR + 30 * MIN)
        val m = ScoreModel(tasks, listOf(block), listOf(ban), 0L, 12 * HOUR)
        val until = 8.0 * HOUR
        val incumbent = ScheduleOptimizer(m, improveBudget = 0).plan(m.cursor(), until, alternatives = false).runs
        val proposal = MipScheduleSolver().improve(m, m.cursor(), until, incumbent, pinFirstRun = false, budget = SearchBudget.of(15_000))
        val runs = proposal ?: incumbent
        assertLegal(m, runs, 0.0, until)
        assertTrue(runs.none { it.task == 0 && it.fromU < 4.0 * HOUR && it.toU > 2.0 * HOUR }, "A never runs inside its ban")
        assertTrue(runs.any { it.task == 2 && it.fromU <= 5.0 * HOUR + 1e-3 && it.toU >= 5.5 * HOUR - 1e-3 }, "C's block is kept")
    }

    @Test
    fun inside_the_optimizer_it_only_ever_lowers_the_score() {
        val tasks = listOf(task("A", 0.3, 45), task("B", 0.25, 20), task("C", 0.2, 30), task("D", 0.15, 15), task("E", 0.1, 10))
        val night = PlanWindow(14 * HOUR, 22 * HOUR, emptyMap(), defaultMultiplier = 0.0)
        val m = ScoreModel(tasks, emptyList(), listOf(night), 0L, 30 * HOUR)
        val until = m.uAt(24 * HOUR)
        val plain = ScheduleOptimizer(m).plan(m.cursor(), until, alternatives = false)
        val solved = ScheduleOptimizer(m, solver = MipScheduleSolver()).plan(m.cursor(), until, alternatives = false, budget = SearchBudget.of(10_000))
        println("five tasks, a day: rollout+improver ${"%.4e".format(plain.cost)}, with the solver ${"%.4e".format(solved.cost)} ${solved.report}")
        assertTrue(solved.cost <= plain.cost * (1 + 1e-9), "${solved.cost} vs ${plain.cost}")
        assertLegal(m, solved.runs, 0.0, until)
    }

    @Test
    fun a_small_case_is_certified_when_the_time_allows() {
        val tasks = listOf(task("A", 0.6, 20), task("B", 0.4, 20))
        val block = PlanBlock(TaskId("A"), 50 * MIN, 70 * MIN)
        val m = ScoreModel(tasks, listOf(block), emptyList(), 0L, 6 * HOUR)
        val until = 2.0 * HOUR
        val exact = ScheduleOptimizer(m, searchBudget = 2_000_000).certify(m.cursor(), until)
        assertTrue(exact.certified)
        val plan = ScheduleOptimizer(m, solver = MipScheduleSolver()).plan(m.cursor(), until, alternatives = false, budget = SearchBudget.of(30_000))
        assertTrue(plan.certified, "with the time to finish, the exhaustive search finishes: ${plan.report}")
        assertTrue(plan.cost <= exact.cost * (1 + 1e-9), "the plan reaches the certified best: ${plan.cost} vs ${exact.cost}")
    }
}
