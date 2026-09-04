package org.example.project

import kotlin.test.Test
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.RelativePriorityDomain
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState

/**
 * ADR 0004 / PRD §5, and the reason the release account's scheduler had gone degenerate.
 *
 * `setCellShare` grows its bisection bracket by DOUBLING until it covers the target share — and the target is
 * not always reachable. A cell whose weight lives only in a column of absolute weight `0.9` can never hold
 * more than 90 % of its list, however large the weight; asked for more, the doubling ran its full guard and
 * the bisection returned the top of the bracket, multiplying the cell's stored weight by up to `2^60`. And
 * because the write SCALES the weight already there, every further impossible ask multiplied it again: on the
 * release account one stored weight had reached `4.99e42` while every other weight in the account was `<= 90`.
 *
 * The consequence is not cosmetic. Its sibling's absolute priority collapsed to `8.4e-44`, which put
 * `SchedulerPlanner`'s minimal period (`max(mᵢ / pᵢ)`) at `9e42` HOURS — see [DegeneratePlanScaleTest] for
 * what that does to the fill.
 */
class RelativePriorityWeightBoundTest {

    /** Three siblings under "main", so no one of them can ever hold the whole list. */
    private fun threeSiblings(): SchedulerState {
        var s = SchedulerState.empty()
        val root = s.rootListId
        for (title in listOf("A", "B", "C")) {
            val cell = s.lists[root]!!.cellIds.first { s.cells[it]!!.taskId == null }
            s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(cell, title))
        }
        // The shape that makes a target UNREACHABLE, and the one the release account is in: a second weight
        // column, so column 0's absolute weight is only 0.9 and a cell carrying weight there alone can never
        // hold more than 90 % of its list however large that weight grows.
        s = SchedulerReducer.reduce(s, SchedulerIntent.AddPriorityColumn(root))
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetPriorityColumnWeight(root, 0, 0.9))
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetPriorityColumnWeight(root, 1, 1.0))
        // ...and one sibling holding weight in the second column, so that column's sum is non-zero.
        val cB = s.lists[root]!!.cellIds.first { s.tasks[s.cells[it]!!.taskId]?.title == "B" }
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetPriorityWeight(cB, 1, 1.0))
        return s
    }

    private fun maxWeight(state: SchedulerState): Double =
        state.cells.values.flatMap { it.priorityWeights }.maxOrNull() ?: 0.0

    @Test
    fun an_unreachable_target_never_runs_the_weight_away() {
        var s = threeSiblings()
        val a = s.tasks.keys.first { s.tasks[it]!!.title == "A" }
        val main = org.example.project.scheduler.model.WellKnownIds.MAIN_TASK
        val before = maxWeight(s)

        // 100 % of the tree is unreachable while B and C are in it, and the window commits every keystroke —
        // so this is the gesture that ran the bracket away, repeated.
        repeat(6) { s = RelativePriorityDomain.setRelativePriority(s, a, main, 1.0, pinned = emptySet()) }

        val after = maxWeight(s)
        assertTrue(
            after < before * 1e7,
            "an unreachable target must not compound the stored weight: $before -> $after",
        )
        // And the tree is still one the planner can scale: no sibling has been pushed to a denormal share.
        val priorities = SchedulerDomain.absoluteTaskPriorities(s)
        val smallest = priorities.values.filter { it > 0.0 }.minOrNull() ?: 1.0
        assertTrue(smallest > 1e-9, "no sibling may be scaled out of existence (smallest share $smallest)")
    }

    @Test
    fun a_reachable_target_is_still_reached() {
        var s = threeSiblings()
        val a = s.tasks.keys.first { s.tasks[it]!!.title == "A" }
        val main = org.example.project.scheduler.model.WellKnownIds.MAIN_TASK
        s = RelativePriorityDomain.setRelativePriority(s, a, main, 0.5, pinned = emptySet())
        val got = SchedulerDomain.absoluteTaskPriorities(s)[a] ?: 0.0
        assertTrue(kotlin.math.abs(got - 0.5) < 1e-6, "the cap must not disturb a reachable target (got $got)")
    }
}
