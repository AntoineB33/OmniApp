package org.example.project

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.ScreenBreak
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState

/**
 * `docs/scheduler_requirements.md` § *Progressive Calculation* — **$t_goal$**: *"The scheduler can have a time
 * $t goal$ such as when definitive schedule is found for any t < $t goal$ the scheduler can stop."*
 *
 * It is **the end of the timeline the calendar shows, or `now + 10 min` if that is further**
 * ([SchedulerDomain.scheduleGoalEndMillis], user rule 2026-09-16), and the fill is computed out to
 * [SchedulerDomain.scheduleHorizonEndMillis] — the same goal with its rolling floor doubled and a far calendar
 * end capped at 168 h, so a far week never enters the persisted state.
 *
 * The complementary async/never-freeze half lives in `App.kt` (the far-week `LaunchedEffect` on
 * `Dispatchers.Default`); what is testable as pure logic is where the goal falls, that everything the fill
 * projects is bounded by it, and that the refill neither re-fires once the goal is reached nor lets the plan
 * run out.
 */
class ScheduleHorizonTest {

    private val MINUTE = 60_000L
    private val HOUR = 60 * MINUTE
    private val DAY = 24 * HOUR
    private val WEEK = 7 * DAY
    private val FLOOR = SchedulerDomain.SCHEDULE_GOAL_FLOOR_MILLIS

    /** Wednesday 2026-09-02, 10:00 UTC. */
    private val NOW = 1_788_343_200_000L

    @AfterTest
    fun resetReducerSeam() {
        SchedulerReducer.scheduleHorizonEndMillis = { SchedulerDomain.scheduleHorizonEndMillis(it, null) }
    }

    /** One schedulable task under "main", so the fill has something to lay down across the horizon. */
    private fun stateWithOneTask(): Pair<SchedulerState, TaskId> {
        var s = SchedulerState.empty()
        val c0 = s.lists[s.rootListId]!!.cellIds[0]
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(c0, "A"))
        return s to s.tasks.keys.first { s.tasks[it]!!.title == "A" }
    }

    // ----- $t_goal$ itself -------------------------------------------------------------------------

    @Test
    fun the_goal_is_the_end_of_the_displayed_timeline() {
        assertEquals(NOW + 2 * DAY, SchedulerDomain.scheduleGoalEndMillis(NOW, NOW + 2 * DAY))
        // No day is added past it, and a scroll of one hour moves it by one hour.
        assertEquals(NOW + 2 * DAY + HOUR, SchedulerDomain.scheduleGoalEndMillis(NOW, NOW + 2 * DAY + HOUR))
    }

    @Test
    fun the_goal_is_never_less_than_ten_minutes_after_now() {
        assertEquals(10 * MINUTE, FLOOR)
        // No calendar open.
        assertEquals(NOW + FLOOR, SchedulerDomain.scheduleGoalEndMillis(NOW, null))
        // A calendar whose end is within ten minutes, or entirely in the past.
        assertEquals(NOW + FLOOR, SchedulerDomain.scheduleGoalEndMillis(NOW, NOW + 3 * MINUTE))
        assertEquals(NOW + FLOOR, SchedulerDomain.scheduleGoalEndMillis(NOW, NOW - 3 * DAY))
        // The floor rolls with the line.
        assertEquals(NOW + HOUR + FLOOR, SchedulerDomain.scheduleGoalEndMillis(NOW + HOUR, null))
    }

    // ----- the horizon that honours it -------------------------------------------------------------

    @Test
    fun the_horizon_is_the_goal_with_the_floor_doubled_and_a_far_week_capped() {
        // A calendar end past the floor: the goal itself.
        assertEquals(NOW + 2 * DAY, SchedulerDomain.scheduleHorizonEndMillis(NOW, NOW + 2 * DAY))
        // The floor governs: a fill reaches twice it, so it is not due again at once.
        assertEquals(NOW + 2 * FLOOR, SchedulerDomain.scheduleHorizonEndMillis(NOW, null))
        assertEquals(NOW + 2 * FLOOR, SchedulerDomain.scheduleHorizonEndMillis(NOW, NOW + 15 * MINUTE))

        // A far week the user scrolled to is capped: it is never materialized into the state (App.kt fills it
        // for display only, out to the real goal).
        assertEquals(NOW + WEEK, SchedulerDomain.scheduleHorizonEndMillis(NOW, NOW + 40 * DAY))
        assertTrue(SchedulerDomain.scheduleGoalEndMillis(NOW, NOW + 40 * DAY) > NOW + WEEK)
    }

    // ----- what the fill computes is bounded by it ------------------------------------------------

    @Test
    fun the_fill_stops_at_the_horizon() {
        val (s, _) = stateWithOneTask()
        for (displayed in listOf(null, NOW + DAY)) {
            val horizon = SchedulerDomain.scheduleHorizonEndMillis(NOW, displayed)
            val near = SchedulerDomain.fillSchedule(s, NOW, horizonMillis = horizon)
            assertTrue(near.isNotEmpty(), "the goal must still be scheduled")
            assertTrue(
                near.none { it.startEpochMillis >= horizon },
                "no panel may start after the horizon: ${near.filter { it.startEpochMillis >= horizon }.size} did",
            )
            assertTrue(
                SchedulerDomain.firstFreeMoment(near, NOW) >= SchedulerDomain.scheduleGoalEndMillis(NOW, displayed),
                "the plan must cover the goal",
            )
        }
    }

    @Test
    fun screen_breaks_are_projected_only_to_the_fill_horizon() {
        // Regression: `fillSchedule` used to project the screen breaks with their own DEFAULT horizon
        // (now + 168h) whatever horizon it was itself filling, so a one-day fill still carried a week of
        // break panels — and a far-week DISPLAY fill stopped its breaks dead at 168h.
        val breaks = SchedulerDomain.DEFAULT_SCREEN_BREAKS
        val s = SchedulerState.empty().copy(screenBreaks = breaks)
        val horizon = NOW + DAY

        val panels = SchedulerDomain.fillSchedule(s, NOW, horizonMillis = horizon).filter { it.screenBreak }
        assertTrue(panels.isNotEmpty(), "a day still holds screen breaks")
        assertTrue(
            panels.none { it.startEpochMillis >= horizon },
            "screen breaks must stop at the fill horizon, not at a fixed 168h",
        )

        // And the far-week direction: a display fill past the ceiling projects breaks across it.
        val far = SchedulerDomain.fillSchedule(s, NOW, horizonMillis = NOW + 3 * WEEK).filter { it.screenBreak }
        assertTrue(
            far.any { it.startEpochMillis > NOW + 2 * WEEK },
            "a far-week display fill must project screen breaks across the week it is showing",
        )
    }

    @Test
    fun the_reducer_refill_uses_the_injected_display_horizon() {
        // The seam the engine drives from `App.kt`'s displayed span. A refill must honour it — this is what
        // makes "the plan is computed to $t_goal$ and no further" true of the LIVE app, not just of a direct
        // SchedulerDomain call.
        val (s, _) = stateWithOneTask()
        SchedulerReducer.scheduleHorizonEndMillis = { it + 6 * HOUR }
        val filled = SchedulerReducer.reduce(s, SchedulerIntent.RefreshSchedule(NOW)).panels
        assertTrue(filled.isNotEmpty())
        assertTrue(
            filled.none { it.startEpochMillis >= NOW + 6 * HOUR },
            "the refill must stop at the injected horizon",
        )
    }

    @Test
    fun a_closed_calendar_plans_only_twenty_minutes() {
        val (s, _) = stateWithOneTask()
        val filled = SchedulerReducer.reduce(s, SchedulerIntent.RefreshSchedule(NOW)).panels
        assertTrue(filled.none { it.startEpochMillis >= NOW + 2 * FLOOR }, "nothing past now + 20 min")
        assertTrue(SchedulerDomain.firstFreeMoment(filled, NOW) >= NOW + FLOOR, "the goal is covered")
    }

    // ----- the rolling refill agrees with it -------------------------------------------------------

    @Test
    fun a_fill_to_the_calendar_end_is_not_due_again_until_ten_minutes_before_it() {
        // The anti-spin obligation of HorizonRefillRuleTest, restated for a calendar goal: a schedule reaching
        // $t_goal$ is complete — otherwise the engine would refill forever trying to push the plan past the
        // instant the requirement lets it stop at.
        val (s, _) = stateWithOneTask()
        val displayed = NOW + DAY
        val filled =
            SchedulerDomain.fillSchedule(s, NOW, horizonMillis = SchedulerDomain.scheduleHorizonEndMillis(NOW, displayed))
        val coverage = SchedulerDomain.firstFreeMoment(filled, NOW)
        assertTrue(SchedulerDomain.horizonRefillDueMillis(filled, NOW, displayed) > NOW, "not due right after")
        assertEquals(coverage - FLOOR, SchedulerDomain.horizonRefillDueMillis(filled, NOW, displayed))
    }

    @Test
    fun scrolling_further_than_the_plan_makes_the_refill_due_at_once() {
        // Growing the goal (the user scrolls further out) must make the existing plan read as short, so the
        // engine extends it — that is `launchCalendarHorizonReschedule`.
        val (s, _) = stateWithOneTask()
        val filled =
            SchedulerDomain.fillSchedule(s, NOW, horizonMillis = SchedulerDomain.scheduleHorizonEndMillis(NOW, NOW + DAY))
        assertTrue(SchedulerDomain.horizonRefillDueMillis(filled, NOW, NOW + 2 * DAY) <= NOW)
        // Scrolling back to a nearer end asks for nothing.
        assertTrue(SchedulerDomain.horizonRefillDueMillis(filled, NOW, NOW + HOUR) > NOW)
    }

    @Test
    fun a_week_capped_at_the_ceiling_is_extended_once_an_hour_not_every_tick() {
        val (s, _) = stateWithOneTask()
        val displayed = NOW + 40 * DAY
        val filled =
            SchedulerDomain.fillSchedule(s, NOW, horizonMillis = SchedulerDomain.scheduleHorizonEndMillis(NOW, displayed))
        val margin = SchedulerDomain.HORIZON_REFILL_MARGIN_MILLIS
        val coverage = SchedulerDomain.firstFreeMoment(filled, NOW)
        assertTrue(coverage >= NOW + WEEK - margin, "the capped fill reaches the ceiling")
        val due = SchedulerDomain.horizonRefillDueMillis(filled, NOW, displayed)
        assertTrue(due > NOW, "a capped fill is not due at once")
        assertTrue(due <= NOW + margin, "…but is due within one margin")
    }

    @Test
    fun the_look_away_grid_is_unchanged_by_the_shorter_horizon() {
        // Bounding the projection must not move any occurrence — the same breaks land at the same instants,
        // there are simply fewer of them. (The grid is a fixed function of the anchors, PRD §15.)
        val breaks = listOf(
            ScreenBreak("look 20 feet away", intervalMillis = 20 * 60_000, durationMillis = 20_000),
        )
        val short = SchedulerDomain.screenBreakPanels(breaks, NOW, NOW + 6 * HOUR).map { it.startEpochMillis }
        val long = SchedulerDomain.screenBreakPanels(breaks, NOW, NOW + WEEK)
            .map { it.startEpochMillis }.filter { it <= NOW + 6 * HOUR }
        assertEquals(long, short)
    }
}
