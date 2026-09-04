package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.PeriodKinds
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.model.TaskPanel

/**
 * PRD §15: a screen break that does NOT start in a no-screen period but ENDS in one says so in its start
 * notification — the user need not come back to the screen when it is over. Two things can say it: a period
 * the user drew, and §17's `before bed` hour.
 */
class ScreenBreakFollowOnTest {
    private val MIN = 60_000L
    private val NOW = 1_000_000_000_000L

    private fun userNoScreen(start: Long, end: Long) =
        TaskPanel("ns/0", null, "No screen", start, end, noScreen = true)

    private fun userInactivity(start: Long, end: Long) =
        TaskPanel("inact/0", null, "Inactivity", start, end, inactivity = true)

    private fun beforeBed(start: Long, end: Long) =
        TaskPanel(
            SchedulerDomain.BEFORE_BED_PANEL_ID_PREFIX + "2026-09-05",
            null,
            "Before bed",
            start,
            end,
            periodKind = PeriodKinds.BEFORE_BED,
        )

    @Test
    fun a_break_ending_in_a_user_period_names_it() {
        // 5-min pose 20:55 -> 21:00, the user's own "No screen" period 21:00 -> 22:00: the break ends where
        // the period begins, so the period IS what follows it (half-open coverage).
        val panels = listOf(userNoScreen(NOW + 5 * MIN, NOW + 65 * MIN))
        assertEquals(
            SchedulerDomain.ScreenBreakFollowOn.UserPeriod,
            SchedulerDomain.screenBreakFollowOn(panels, NOW, NOW + 5 * MIN),
        )
        assertEquals(
            "5 min pose — followed by a no screen period",
            SchedulerDomain.screenBreakStartNotificationMessage(panels, "5 min pose", NOW, NOW + 5 * MIN),
        )
    }

    @Test
    fun an_inactivity_period_the_user_drew_counts_too() {
        val panels = listOf(userInactivity(NOW + 5 * MIN, NOW + 65 * MIN))
        assertEquals(
            SchedulerDomain.ScreenBreakFollowOn.UserPeriod,
            SchedulerDomain.screenBreakFollowOn(panels, NOW, NOW + 5 * MIN),
        )
    }

    @Test
    fun a_break_ending_in_the_wind_down_hour_names_that() {
        val panels = listOf(beforeBed(NOW + 5 * MIN, NOW + 65 * MIN))
        assertEquals(
            SchedulerDomain.ScreenBreakFollowOn.BeforeBed,
            SchedulerDomain.screenBreakFollowOn(panels, NOW, NOW + 5 * MIN),
        )
        assertEquals(
            "15 min pose — followed by the hour before bed",
            SchedulerDomain.screenBreakStartNotificationMessage(panels, "15 min pose", NOW, NOW + 5 * MIN),
        )
    }

    @Test
    fun a_break_that_already_started_in_one_says_nothing() {
        // Wholly inside the user's period: the user was already off the screen when it began, so there is
        // nothing to add.
        val panels = listOf(userNoScreen(NOW - MIN, NOW + 65 * MIN))
        assertNull(SchedulerDomain.screenBreakFollowOn(panels, NOW, NOW + 5 * MIN))
        assertEquals(
            "5 min pose",
            SchedulerDomain.screenBreakStartNotificationMessage(panels, "5 min pose", NOW, NOW + 5 * MIN),
        )
    }

    @Test
    fun a_break_reaching_nothing_says_nothing() {
        val panels = listOf(userNoScreen(NOW + 30 * MIN, NOW + 65 * MIN))
        assertNull(SchedulerDomain.screenBreakFollowOn(panels, NOW, NOW + 5 * MIN))
    }

    @Test
    fun a_break_is_never_the_freedom_that_follows_a_break() {
        // A placed dynamic period and a break the app CONDUCTED are both `no task allowed` spans, and neither
        // is a stretch the user is free in: they are breaks.
        val placed = TaskPanel("sb/0", null, "look 20 feet away", NOW + 5 * MIN, NOW + 6 * MIN, screenBreak = true)
        val conducted =
            TaskPanel("done/0", null, "look 20 feet away", NOW + 5 * MIN, NOW + 6 * MIN, inactivity = true, conductedBreak = true)
        assertNull(SchedulerDomain.screenBreakFollowOn(listOf(placed), NOW, NOW + 5 * MIN))
        assertNull(SchedulerDomain.screenBreakFollowOn(listOf(conducted), NOW, NOW + 5 * MIN))
    }

    @Test
    fun a_task_panel_is_not_a_period() {
        val work = TaskPanel("auto/0", TaskId("task/user/1"), "Work", NOW + 5 * MIN, NOW + 65 * MIN, auto = true)
        assertNull(SchedulerDomain.screenBreakFollowOn(listOf(work), NOW, NOW + 5 * MIN))
    }

    @Test
    fun the_users_own_period_is_named_first_where_both_cover_the_end() {
        val panels = listOf(beforeBed(NOW + 5 * MIN, NOW + 65 * MIN), userNoScreen(NOW + 5 * MIN, NOW + 20 * MIN))
        assertEquals(
            SchedulerDomain.ScreenBreakFollowOn.UserPeriod,
            SchedulerDomain.screenBreakFollowOn(panels, NOW, NOW + 5 * MIN),
        )
        assertTrue(
            SchedulerDomain.screenBreakStartNotificationMessage(panels, "pose", NOW, NOW + 5 * MIN)
                .endsWith("no screen period"),
        )
    }
}
