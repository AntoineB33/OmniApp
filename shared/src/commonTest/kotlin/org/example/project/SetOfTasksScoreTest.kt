package org.example.project

import kotlin.test.Test
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.PlanTask
import org.example.project.scheduler.domain.ScoreModel
import org.example.project.scheduler.model.TaskId

/**
 * `docs/scheduler_score.md` § *Sets of tasks* (user rule 2026-10-03): while a task runs, each task of its set is served
 * too, at its fraction and only up to its share. The user's own statement of it: **a task whose set holds every
 * schedulable task at 100% is what the best schedule is filled with** — it keeps every task exactly on its share,
 * where any schedule of the tasks themselves leaves them oscillating around it.
 */
class SetOfTasksScoreTest {
    private val MIN = 60_000L
    private val HOUR = 60 * MIN
    private val a = TaskId("task/a")
    private val b = TaskId("task/b")
    private val x = TaskId("task/x")

    private fun model(credit: Double) =
        ScoreModel(
            tasks = listOf(
                PlanTask(a, priority = 0.5, minimumMillis = 30 * MIN),
                PlanTask(b, priority = 0.5, minimumMillis = 30 * MIN),
                // No priority of its own (no path): worth time only through its set.
                PlanTask(x, priority = 0.0, minimumMillis = 30 * MIN, credits = mapOf(a to credit, b to credit)),
            ),
            blocks = emptyList(),
            windows = emptyList(),
            fromMillis = 0L,
            toMillis = 24 * HOUR,
        )

    /** The score of running [runs] (task index or IDLE, minutes) in order from u = 0, settled at the end. */
    private fun score(m: ScoreModel, runs: List<Pair<Int, Long>>): Double {
        val c = m.cursor()
        var u = 0.0
        for ((task, minutes) in runs) {
            u += minutes * MIN
            m.serve(c, task, u)
        }
        m.chargeShortfall(c)
        m.settle(c)
        return c.cost
    }

    private fun alternating(m: ScoreModel, hours: Int): List<Pair<Int, Long>> =
        (0 until hours * 2).map { (if (it % 2 == 0) m.indexOf.getValue(a) else m.indexOf.getValue(b)) to 30L }

    @Test
    fun a_task_fulfilling_every_task_at_100_percent_beats_any_schedule_of_the_tasks_themselves() {
        val m = model(credit = 1.0)
        val onlyX = listOf(m.indexOf.getValue(x) to 10L * 60)
        val ab = alternating(m, 10)
        val idle = listOf(ScoreModel.IDLE to 10L * 60)
        assertTrue(score(m, onlyX) < score(m, ab), "X alone ${score(m, onlyX)} vs A/B ${score(m, ab)}")
        assertTrue(score(m, onlyX) < score(m, idle), "X alone must beat leaving the time to nobody")
    }

    @Test
    fun a_set_worth_more_than_an_hour_of_either_task_still_wins_and_a_weaker_one_does_not() {
        // At 80% each, an hour of X is 0.8 h of A AND 0.8 h of B — more than either 50% task needs an hour, so X keeps
        // both at their share, as the chemistry video in Spanish serves both goals at once.
        val strong = model(credit = 0.8)
        assertTrue(score(strong, listOf(strong.indexOf.getValue(x) to 10L * 60)) < score(strong, alternating(strong, 10)))
        // At 30% each it cannot keep a 50% task on pace: both fall behind for as long as it runs, so the tasks
        // themselves score better.
        val weak = model(credit = 0.3)
        assertTrue(score(weak, alternating(weak, 10)) < score(weak, listOf(weak.indexOf.getValue(x) to 10L * 60)))
    }

    @Test
    fun a_set_never_over_serves_a_task() {
        // A task already ahead of its share gets nothing from the set: X's credit leaves A's lag where its own
        // forgetting takes it, never above.
        val m = model(credit = 1.0)
        val c = m.cursor()
        val ia = m.indexOf.getValue(a)
        m.serve(c, ia, 60.0 * MIN) // A an hour ahead of a 50% share
        m.settle(c)
        val ahead = c.lag[ia]
        m.serve(c, m.indexOf.getValue(x), 120.0 * MIN)
        m.settle(c)
        assertTrue(ahead > 0.0 && c.lag[ia] < ahead, "the credit pushed A further ahead: $ahead → ${c.lag[ia]}")
        assertTrue(c.lag[ia] >= -1.0, "and A is held at its share, not left behind: ${c.lag[ia]}")
    }
}
