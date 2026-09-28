package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.datetime.TimeZone
import org.example.project.scheduler.domain.DynamicPeriods
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.TaskPanel
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState

/**
 * `docs/scheduler_requirements.md` § *No idling*, checked at the line ([SchedulerDomain.planMismatchAtLine]): the
 * rules place the three dynamic periods as a function of the line, so a plan built around one placement can be
 * left, as the line advances, either empty where somebody may run or running a task inside a break. The engine
 * re-plans from the line on either, once per mismatch.
 */
class PlanMismatchAtLineTest {
    private val MIN = 60_000L
    private val NOW = 1_700_000_000_000L
    private val tz = TimeZone.UTC

    private fun account(panels: List<TaskPanel>): SchedulerState {
        var s = SchedulerState.empty()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(s.lists[s.rootListId]!!.cellIds[0], "Work"))
        val work = s.tasks.values.single { it.title == "Work" }.id
        return s.copy(screenBreaks = SchedulerDomain.DEFAULT_SCREEN_BREAKS, panels = panels.map { if (it.taskId != null) it.copy(taskId = work) else it })
    }

    private fun auto(id: String, start: Long, end: Long) =
        TaskPanel(id, org.example.project.scheduler.model.TaskId("placeholder"), "Work", start, end, auto = true)

    private fun rest(endMillis: Long) = TaskPanel("rest/0", null, "No screen", endMillis - 25 * MIN, endMillis, noScreen = true)

    private fun mismatch(s: SchedulerState, mode: Int = DynamicPeriods.MODE_AT_SCREEN) =
        SchedulerDomain.planMismatchAtLine(s, NOW, mode, SchedulerDomain.breakEnvironment(s, NOW, NOW + 1, tz, mode = mode), tz)

    @Test
    fun a_plan_with_a_task_at_the_line_and_no_break_there_matches() {
        val s = account(listOf(rest(NOW - 5 * MIN), auto("auto/0", NOW - 10 * MIN, NOW + 30 * MIN)))
        assertNull(mismatch(s))
    }

    @Test
    fun a_hole_at_the_line_where_somebody_may_run_is_a_mismatch() {
        // The plan left the line empty for a break that is not there (it has moved on): somebody may run, so the
        // line is idle.
        val s = account(listOf(rest(NOW - 5 * MIN), auto("auto/0", NOW - 10 * MIN, NOW), auto("auto/1", NOW + 5 * MIN, NOW + 40 * MIN)))
        assertEquals("idle", mismatch(s)?.first())
    }

    @Test
    fun a_task_inside_a_break_the_line_is_in_is_a_mismatch() {
        // A 25-minute rest ending twenty minutes ago bars the look-away until exactly now: the line is in it, and
        // the plan still has the on-screen task there.
        val s = account(listOf(rest(NOW - 20 * MIN), auto("auto/0", NOW - 10 * MIN, NOW + 30 * MIN)))
        assertEquals("task inside a break", mismatch(s)?.first())
    }

    @Test
    fun a_stretch_nobody_may_run_in_is_not_idle() {
        // Mode 2 covers the line with "no on-screen task", and the only task is on screen: the README's "no task".
        val s = account(listOf(rest(NOW - 5 * MIN), auto("auto/0", NOW - 10 * MIN, NOW), auto("auto/1", NOW + 5 * MIN, NOW + 40 * MIN)))
        assertNull(mismatch(s, DynamicPeriods.MODE_AWAY))
    }
}
