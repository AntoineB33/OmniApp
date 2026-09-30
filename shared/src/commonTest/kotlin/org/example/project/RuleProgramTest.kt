package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.PeriodKinds
import org.example.project.scheduler.domain.RuleProgram
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.AlternativeSpan
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.model.TaskPanel

/**
 * `docs/scheduler_requirements.md` § *Rule Structure*: the task side of the rules compiled into sequential branches with
 * their trigger boundaries, read by a forward cursor — and it answers what the timeline-wide readings it replaces at the
 * line answered ([SchedulerDomain.panelAt], [SchedulerDomain.alternativeTaskAt]).
 */
class RuleProgramTest {
    private val MIN = 60_000L
    private val T = 1_000_000_000_000L
    private val a = TaskId("a")
    private val b = TaskId("b")
    private val c = TaskId("c")

    private val panels =
        listOf(
            TaskPanel("auto/0", a, "A", T, T + 30 * MIN, auto = true, alternativeTaskId = b),
            TaskPanel(
                "auto/1", b, "B", T + 30 * MIN, T + 60 * MIN, auto = true, alternativeTaskId = a,
                alternativeSpans = listOf(AlternativeSpan(T + 45 * MIN, c)),
            ),
            TaskPanel("pin/0", c, "C", T + 90 * MIN, T + 100 * MIN, pinned = true),
            TaskPanel("bb", null, "Before bed", T + 120 * MIN, T + 180 * MIN, periodKind = PeriodKinds.BEFORE_BED),
            TaskPanel("chore/r/0", null, "Water plants", T + 50 * MIN, T + 50 * MIN, chore = true),
        )

    @Test
    fun the_cursor_answers_what_the_panels_say_at_every_position_of_the_line() {
        val program = RuleProgram.compile(panels)
        val cursor = RuleProgram.Cursor(program, T - MIN)
        var t = T - MIN
        while (t < T + 200 * MIN) {
            cursor.moveTo(t)
            val workAt = panels.filter { it.taskId != null && !it.chore && !it.isRestrictivePeriod }
            assertEquals(SchedulerDomain.panelAt(workAt, t)?.id, cursor.panel?.id, "the task held at +${(t - T) / MIN} min")
            if (SchedulerDomain.panelAt(workAt, t)?.auto == true) {
                assertEquals(SchedulerDomain.alternativeTaskAt(panels, t), cursor.alternative, "the alternative at +${(t - T) / MIN} min")
            }
            t += 7 * 1_000L + MIN
        }
    }

    @Test
    fun the_cursor_reports_what_it_crossed_and_arms_the_next_trigger() {
        val cursor = RuleProgram.Cursor(RuleProgram.compile(panels), T)
        assertEquals(T + 30 * MIN, cursor.nextTriggerMillis(), "the next boundary is the first panel's end")
        val first = cursor.moveTo(T + 40 * MIN)
        assertTrue(first.elapsed, "a plan panel ended")
        assertTrue(first.windDowns.isEmpty() && first.reminders.isEmpty())
        val second = cursor.moveTo(T + 150 * MIN)
        assertEquals(listOf(T + 120 * MIN), second.windDowns, "the wind-down hour began")
        assertEquals(listOf("chore/r/0"), second.reminders.map { it.id }, "the reminder fell due")
        assertNull(cursor.panel, "nothing is held inside the wind-down")
        assertTrue(cursor.moveTo(T + 140 * MIN).windDowns.isEmpty(), "the line never moves back")
    }
}
