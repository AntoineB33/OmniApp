package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.PeriodKinds
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.model.TaskPanel

/**
 * 2026-10-08: the set of rules output, pasted to an outside reader, was found not to satisfy
 * `docs/scheduler_requirements.md` on four counts — every one of them the WORDING of the output. This is the wording.
 */
class ScheduleRulesOutputTest {
    private val MIN = 60_000L
    private val NOW = 1_800_000_000_000L
    private val a = TaskId("task/user/1")
    private val b = TaskId("task/user/2")

    private fun run(id: String, task: TaskId, title: String, from: Long, to: Long, alternative: TaskId?) =
        TaskPanel(id, task, title, NOW + from, NOW + to, auto = true, alternativeTaskId = alternative)

    private fun pause(kind: String, title: String, from: Long, to: Long) =
        TaskPanel("side/$title", null, title, NOW + from, NOW + to, screenBreak = true, periodKind = kind)

    /** Every rule's two offsets, in seconds from the line. */
    private fun spans(rules: List<String>): List<Pair<Long, Long>> =
        rules.drop(1).map { rule ->
            val (from, to) = rule.lineSequence().first().split(" → ").map { text ->
                val (h, m, s) = text.drop(1).split(":").map { it.toLong() }
                (h * 3600 + m * 60 + s) * if (text.startsWith("-")) -1 else 1
            }
            from to to
        }

    @Test
    fun the_rules_are_sequential_a_break_inside_a_run_is_its_own_piece() {
        val panels = listOf(
            run("auto/0", a, "Write", 0, 90 * MIN, alternative = b),
            pause(PeriodKinds.BREAK_5MIN, "take a 5min pose", 60 * MIN, 65 * MIN),
            run("auto/1", b, "Read", 90 * MIN, 120 * MIN, alternative = a),
        )
        val rules = SchedulerDomain.describeScheduleRules(panels, NOW, tpMode = 1)
        val at = spans(rules)
        assertEquals(listOf(0L to 3600L, 3600L to 3900L, 3900L to 5400L, 5400L to 7200L), at)
        // No two overlap, and each starts where the one before it ends.
        at.zipWithNext().forEach { (one, next) -> assertEquals(one.second, next.first) }
        // The piece inside the break is the break alone: the run does not hold there.
        assertTrue("restrict [${PeriodKinds.BREAK_5MIN}]" in rules[2] && "run Write" !in rules[2], rules[2])
        // And the run is back after it, to its own end.
        assertTrue("run Write until +1:30:00" in rules[3] && "else run Read" in rules[3], rules[3])
        assertTrue("run Write until +1:00:00" in rules[1], rules[1])
    }

    @Test
    fun a_line_standing_in_a_20s_break_is_said_to_be_in_mode_3() {
        val lookAway = pause(PeriodKinds.BREAK_20S, "look 20 feet away", -18_500L, 1_500L)
        val panels = listOf(lookAway, run("auto/0", a, "Write", 1_500L, 30 * MIN, alternative = null))
        val rules = SchedulerDomain.describeScheduleRules(panels, NOW, tpMode = 1)
        assertTrue(rules[0].startsWith("now-line mode 3"), rules[0])
        // Twenty seconds read twenty seconds: both offsets are floored.
        assertEquals(-19L to 1L, spans(rules).first())
        // Outside a 20s break the mode is the one the rules were found in.
        val ahead = listOf(pause(PeriodKinds.BREAK_20S, "look 20 feet away", 10 * MIN, 10 * MIN + 20_000L))
        assertTrue(SchedulerDomain.describeScheduleRules(ahead, NOW, tpMode = 1)[0].startsWith("now-line mode 1"))
        assertTrue(SchedulerDomain.describeScheduleRules(listOf(lookAway), NOW, tpMode = 2)[0].startsWith("now-line mode 2"))
    }

    @Test
    fun two_tasks_with_one_title_are_told_apart() {
        val panels = listOf(
            run("auto/0", a, "planning", 0, 30 * MIN, alternative = b),
            run("auto/1", b, "planning", 30 * MIN, 60 * MIN, alternative = a),
        )
        val rules = SchedulerDomain.describeScheduleRules(panels, NOW, tpMode = 1)
        assertTrue("if planning [task/user/1] is accepted" in rules[1] && "else run planning [task/user/2]" in rules[1], rules[1])
        // A title only one task has stays bare.
        val distinct = listOf(run("auto/0", a, "Write", 0, 30 * MIN, alternative = b), run("auto/1", b, "Read", 30 * MIN, 60 * MIN, alternative = a))
        assertTrue("if Write is accepted" in SchedulerDomain.describeScheduleRules(distinct, NOW, tpMode = 1)[1])
    }
}
