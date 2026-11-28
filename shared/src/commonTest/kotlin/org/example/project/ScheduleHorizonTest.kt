package org.example.project

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.datetime.TimeZone
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.ScreenBreak
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState

/**
 * `docs/scheduler/scheduler_requirements.md` § *Progressive Calculation* — **$t_goal$**: *"The scheduler can have a time
 * $t goal$ such as when definitive schedule is found for any t < $t goal$ the scheduler can stop."*
 *
 * It is **`now + 168 h`** ([SchedulerDomain.scheduleGoalEndMillis], user rule 2026-11-18: *"the 10 minute per 10
 * seconds minimum scheduler from now to now + 168h"*), whatever the calendar shows, and the fill is computed out to
 * it ([SchedulerDomain.scheduleHorizonEndMillis]). Until then it was the latest of the end of the current week, the
 * last displayed time in the calendar and `now + 10 min` (2026-09-18).
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

    private val UTC = TimeZone.UTC

    /** Wednesday 2026-09-02, 10:00 UTC. */
    private val NOW = 1_788_343_200_000L


    @AfterTest
    fun resetReducerSeam() {
        CalendarHorizonFixture.close()
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
    fun the_goal_is_a_week_from_the_line_whatever_the_calendar_shows() {
        for (displayed in listOf(null, NOW - 3 * DAY, NOW + 15 * MINUTE, NOW + 6 * DAY, NOW + 40 * DAY)) {
            assertEquals(NOW + WEEK, SchedulerDomain.scheduleGoalEndMillis(NOW, displayed, UTC))
            assertEquals(NOW + WEEK, SchedulerDomain.scheduleHorizonEndMillis(NOW, displayed, UTC))
        }
        // It rolls with the line.
        assertEquals(NOW + HOUR + WEEK, SchedulerDomain.scheduleGoalEndMillis(NOW + HOUR, null, UTC))
    }

    // ----- what the fill computes is bounded by it ------------------------------------------------

    @Test
    fun the_fill_stops_at_the_horizon() {
        val (s, _) = stateWithOneTask()
        for (displayed in listOf(null, NOW + DAY)) {
            val horizon = SchedulerDomain.scheduleHorizonEndMillis(NOW, displayed, UTC)
            val near = SchedulerDomain.fillSchedule(s, NOW, horizonMillis = horizon)
            assertTrue(near.isNotEmpty(), "the goal must still be scheduled")
            assertTrue(
                near.none { it.startEpochMillis >= horizon },
                "no panel may start after the horizon: ${near.filter { it.startEpochMillis >= horizon }.size} did",
            )
            assertTrue(
                SchedulerDomain.firstFreeMoment(near, NOW) >= SchedulerDomain.scheduleGoalEndMillis(NOW, displayed, UTC),
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
    fun a_reducer_no_engine_set_up_plans_only_twenty_minutes() {
        // The seam unset (a test, no engine): twenty minutes, never the week.
        val (s, _) = stateWithOneTask()
        val filled = SchedulerReducer.reduce(s, SchedulerIntent.RefreshSchedule(NOW)).panels
        assertTrue(filled.none { it.startEpochMillis >= NOW + 20 * MINUTE }, "nothing past now + 20 min")
        assertTrue(SchedulerDomain.firstFreeMoment(filled, NOW) >= NOW + 10 * MINUTE, "ten minutes are covered")
    }

    // ----- the rolling refill agrees with it -------------------------------------------------------

    @Test
    fun a_fill_to_the_goal_is_extended_once_an_hour_not_every_tick() {
        // The anti-spin obligation of HorizonRefillRuleTest: a schedule reaching $t_goal$ is complete for an hour of
        // the line — otherwise the engine would refill forever. The calendar's scroll asks for nothing either way.
        val (s, _) = stateWithOneTask()
        val margin = SchedulerDomain.HORIZON_REFILL_MARGIN_MILLIS
        val filled = SchedulerDomain.fillSchedule(s, NOW, horizonMillis = SchedulerDomain.scheduleHorizonEndMillis(NOW, null, UTC))
        val coverage = SchedulerDomain.firstFreeMoment(filled, NOW)
        assertTrue(coverage >= NOW + WEEK, "the fill reaches the goal")
        for (displayed in listOf(null, NOW + DAY, NOW + 40 * DAY)) {
            val due = SchedulerDomain.horizonRefillDueMillis(filled, NOW, displayed, UTC)
            assertEquals(coverage - WEEK + margin, due)
            assertTrue(due > NOW, "not due right after")
        }
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
