package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.example.project.scheduler.domain.DynamicPeriods
import org.example.project.scheduler.domain.PeriodKinds
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.Task
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.model.TaskPanel
import org.example.project.scheduler.state.SchedulerState

/**
 * `docs/scheduler_requirements.md` § *$now line$ 3 modes*: **"Mode 2 & 3: $now line$ must be covered by the
 * period 'no on-screen task'"** — so *what is the task to do now* is a question about the line AND the mode,
 * and [SchedulerDomain.currentPanel] is where the two meet.
 *
 * The fill expresses the same rule as [DynamicPeriods.awayCover], but only one millisecond wide at the `t_p`
 * it was built for (`[now, now + 1)`), and time passing never re-plans (CLAUDE.md) — so the line walks out
 * of that millisecond into whatever the plan put after it. Reading the stored panel alone therefore reads an
 * answer computed for a `t_p` the line has left, which is how the app came to announce "Task to do now" in
 * the middle of a declared-away spell (account 3, 15:08:40 on 2026-09-12, away since 14:54:15).
 *
 * Who survives the mode is the resilience and nothing else — the same predicate
 * [SchedulerDomain.clipPanelsForObservedNoScreen] cuts the display with and
 * [SchedulerDomain.clipRecordsForObservedNoScreen] refuses to bank a record with.
 */
class CurrentTaskAtLineModeTest {

    private val NOW = 1_000_000_000_000L
    private val HOUR = 3_600_000L

    private val onScreenId = TaskId("on-screen")
    private val offScreenId = TaskId("off-screen")

    private fun stateWith(vararg panels: TaskPanel): SchedulerState =
        SchedulerState.empty().copy(
            tasks =
                mapOf(
                    onScreenId to Task(id = onScreenId, title = "planning"),
                    offScreenId to
                        Task(id = offScreenId, title = "listen to a podcast")
                            .withResilience(PeriodKinds.NO_SCREEN, 1.0),
                ),
            panels = panels.toList(),
        )

    private fun taskPanel(taskId: TaskId, title: String) =
        TaskPanel(
            id = "panel/$title",
            taskId = taskId,
            title = title,
            startEpochMillis = NOW - HOUR,
            endEpochMillis = NOW + HOUR,
        )

    private fun breakPanel() =
        TaskPanel(
            id = "side/0",
            taskId = null,
            title = "take a 15min pose",
            startEpochMillis = NOW - HOUR,
            endEpochMillis = NOW + HOUR,
            screenBreak = true,
        )

    @Test
    fun mode_1_answers_with_whatever_the_plan_put_at_the_line() {
        val state = stateWith(taskPanel(onScreenId, "planning"))
        assertEquals(
            "planning",
            SchedulerDomain.currentPanel(state, NOW, DynamicPeriods.MODE_AT_SCREEN)?.title,
        )
        assertEquals("planning", SchedulerDomain.currentPanel(state, NOW)?.title, "the default is mode 1")
    }

    @Test
    fun neither_away_mode_has_an_on_screen_task_at_the_line() {
        val state = stateWith(taskPanel(onScreenId, "planning"))
        assertNull(
            SchedulerDomain.currentPanel(state, NOW, DynamicPeriods.MODE_ON_BREAK),
            "mode 3 (a device said it is away) put an on-screen task at a line that must be covered",
        )
        assertNull(
            SchedulerDomain.currentPanel(state, NOW, DynamicPeriods.MODE_AWAY),
            "mode 2 (every device locked) put an on-screen task at a line that must be covered",
        )
    }

    /** The cover is "no ON-SCREEN task": a task resilient to that kind is scheduled there like any other. */
    @Test
    fun an_off_screen_task_is_still_the_task_at_the_line_in_either_away_mode() {
        val state = stateWith(taskPanel(offScreenId, "listen to a podcast"))
        assertEquals(
            "listen to a podcast",
            SchedulerDomain.currentPanel(state, NOW, DynamicPeriods.MODE_ON_BREAK)?.title,
        )
        assertEquals(
            "listen to a podcast",
            SchedulerDomain.currentPanel(state, NOW, DynamicPeriods.MODE_AWAY)?.title,
        )
    }

    /** A period, a break or any panel of no task at all is not work — the mode says nothing about it. */
    @Test
    fun a_panel_that_is_not_a_task_is_returned_in_every_mode() {
        val state = stateWith(breakPanel())
        assertEquals(
            "take a 15min pose",
            SchedulerDomain.currentPanel(state, NOW, DynamicPeriods.MODE_ON_BREAK)?.title,
        )
    }

    @Test
    fun a_line_outside_every_panel_has_no_task_in_any_mode() {
        val state = stateWith(taskPanel(onScreenId, "planning"))
        assertNull(SchedulerDomain.currentPanel(state, NOW + 2 * HOUR, DynamicPeriods.MODE_AT_SCREEN))
        assertNull(SchedulerDomain.currentPanel(state, NOW + 2 * HOUR, DynamicPeriods.MODE_ON_BREAK))
    }
}
