package org.example.project

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
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
 * It is `max(` end of **the first day that does not appear in the calendar** `,` end of the first day of the
 * week after **the current week** `)` ([SchedulerDomain.scheduleGoalEndMillis]), and it is what the
 * fill is computed out to ([SchedulerDomain.scheduleHorizonEndMillis], which only caps a CALENDAR-driven goal
 * so a far week never enters the persisted state — the current week's own goal is never clipped).
 *
 * The complementary async/never-freeze half lives in `App.kt` (the far-week `LaunchedEffect` on
 * `Dispatchers.Default`); what is testable as pure logic is where the goal falls, that everything the fill
 * projects is bounded by it, and that the rolling refill does not keep re-firing once the goal is reached.
 */
class ScheduleHorizonTest {

    private val HOUR = 3_600_000L
    private val DAY = 24 * HOUR
    private val WEEK = 7 * DAY
    private val UTC = TimeZone.UTC

    /** Midnight UTC opening [day] of September 2026. */
    private fun sep(day: Int): Long = LocalDate(2026, 9, day).atStartOfDayIn(UTC).toEpochMilliseconds()

    /** Wednesday 2026-09-02, 10:00 UTC. Its week is Mon 08-31 … Sun 09-06. */
    private val NOW = sep(2) + 10 * HOUR

    /** The goal the current week alone asks for: the week after is Mon 09-07 …, so the end of Mon 09-07. */
    private val CURRENT_WEEK_GOAL = sep(8)

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
    fun the_goal_is_the_end_of_the_first_day_of_the_week_after() {
        // No calendar open: the current week's own goal. NOW is a Wednesday, so the week after starts on
        // Monday 09-07 and the goal is the midnight that CLOSES that Monday.
        assertEquals(CURRENT_WEEK_GOAL, SchedulerDomain.scheduleGoalEndMillis(NOW, null, UTC))

        // The same answer from anywhere inside the week — the goal is a staircase, not a rolling window.
        assertEquals(CURRENT_WEEK_GOAL, SchedulerDomain.scheduleGoalEndMillis(sep(1) + 5 * HOUR, null, UTC))
        assertEquals(
            CURRENT_WEEK_GOAL,
            SchedulerDomain.scheduleGoalEndMillis(sep(6) + 23 * HOUR + 59 * 60_000, null, UTC),
        )
        // ...and one step forward once the week rolls: from Monday 09-07 the week after starts on 09-14.
        assertEquals(sep(15), SchedulerDomain.scheduleGoalEndMillis(sep(7), null, UTC))
    }

    @Test
    fun the_calendar_half_is_one_day_past_the_bottom_of_the_grid() {
        // The spec's own example. The calendar opens on the current week (Mon 08-31 … Sun 09-06) — its last
        // day is 09-06, the first day that does not appear is Mon 09-07, and the goal closes it. Which is
        // also what the current week alone asks for, so the two halves agree.
        assertEquals(CURRENT_WEEK_GOAL, SchedulerDomain.scheduleGoalEndMillis(NOW, sep(7), UTC))

        // Now scroll down until the Monday of the next week is on screen. The first day that does not appear
        // is Tuesday 09-08, so the goal becomes the end of that Tuesday — a scroll of one day moved it by
        // one day, where a rule about the week SHOWN would have jumped a whole week.
        assertEquals(sep(9), SchedulerDomain.scheduleGoalEndMillis(NOW, sep(8), UTC))
        // ...and one more day of scroll is one more day of goal.
        assertEquals(sep(10), SchedulerDomain.scheduleGoalEndMillis(NOW, sep(9), UTC))
    }

    @Test
    fun the_calendar_can_only_push_the_goal_further_out() {
        // Scrolling back inside the current week, or off the end of it into the past, never shortens the goal
        // below what the current week asks for.
        assertEquals(CURRENT_WEEK_GOAL, SchedulerDomain.scheduleGoalEndMillis(NOW, sep(3), UTC))
        assertEquals(CURRENT_WEEK_GOAL, SchedulerDomain.scheduleGoalEndMillis(NOW, NOW - 3 * DAY, UTC))
        // The default grid opens on eight days, so it already reaches past the current week's own goal.
        assertEquals(sep(12), SchedulerDomain.scheduleGoalEndMillis(NOW, sep(11), UTC))
    }

    // ----- the horizon that honours it -------------------------------------------------------------

    @Test
    fun the_horizon_is_the_goal_capped_only_for_a_far_calendar_week() {
        // Calendar closed, or on the current week: the goal itself, whatever its distance — this is the half
        // the headless notification/cue paths read, so it is never capped.
        assertEquals(CURRENT_WEEK_GOAL, SchedulerDomain.scheduleHorizonEndMillis(NOW, null, UTC))
        assertEquals(CURRENT_WEEK_GOAL, SchedulerDomain.scheduleHorizonEndMillis(NOW, sep(7), UTC))
        // A Monday's own goal is eight days out and still materialized whole.
        assertEquals(sep(15), SchedulerDomain.scheduleHorizonEndMillis(sep(7), null, UTC))

        // A far week the user scrolled to is capped: it is never materialized into the state (App.kt fills it
        // for display only, out to the real goal).
        assertEquals(NOW + WEEK, SchedulerDomain.scheduleHorizonEndMillis(NOW, NOW + 40 * DAY, UTC))
        assertTrue(SchedulerDomain.scheduleGoalEndMillis(NOW, NOW + 40 * DAY, UTC) > NOW + WEEK)
    }

    // ----- what the fill computes is bounded by it ------------------------------------------------

    @Test
    fun the_fill_stops_at_the_goal() {
        val (s, _) = stateWithOneTask()
        val horizon = SchedulerDomain.scheduleHorizonEndMillis(NOW, null, UTC)
        val near = SchedulerDomain.fillSchedule(s, NOW, horizonMillis = horizon)

        assertTrue(near.isNotEmpty(), "the goal must still be scheduled")
        // Nothing is even STARTED beyond the goal (the chunk covering it may end past it).
        assertTrue(
            near.none { it.startEpochMillis >= horizon },
            "no panel may start after the goal: ${near.filter { it.startEpochMillis >= horizon }.size} did",
        )
        // A sole task merges into one continuous panel, so the count says nothing — the COVERAGE is what
        // shows the days after the goal were never computed.
        val nearEnd = near.maxOf { it.endEpochMillis }
        assertTrue(nearEnd < horizon + DAY, "the plan must stop just past the goal, ended at $nearEnd")
        assertTrue(
            SchedulerDomain.fillSchedule(s, NOW).maxOf { it.endEpochMillis } > horizon,
            "the unconditional 168h fill does reach past this week's goal",
        )
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

    // ----- the rolling refill agrees with it -------------------------------------------------------

    @Test
    fun a_fill_to_the_goal_is_not_immediately_due_for_refill_again() {
        // The anti-spin obligation of HorizonRefillRuleTest, restated for the goal: a schedule reaching
        // $t_goal$ is complete, not "short of 168h" — otherwise the engine would refill forever trying to push
        // the plan past the instant the requirement lets it stop at.
        val (s, _) = stateWithOneTask()
        val horizon = SchedulerDomain.scheduleHorizonEndMillis(NOW, null, UTC)
        val filled = SchedulerDomain.fillSchedule(s, NOW, horizonMillis = horizon)
        assertTrue(
            SchedulerDomain.horizonRefillDueMillis(filled, NOW, horizon) > NOW,
            "a fresh fill to the goal must not be immediately due again",
        )
    }

    @Test
    fun navigating_to_a_further_week_makes_the_refill_due_at_once() {
        // The other side of the same coin: growing the goal (the user opens a later week) must make the
        // existing plan read as short, so the engine extends it — that is `launchCalendarHorizonReschedule`.
        val (s, _) = stateWithOneTask()
        val filled =
            SchedulerDomain.fillSchedule(
                s, NOW, horizonMillis = SchedulerDomain.scheduleHorizonEndMillis(NOW, null, UTC),
            )
        val grown = SchedulerDomain.scheduleHorizonEndMillis(NOW, NOW + 20 * DAY, UTC)
        assertTrue(grown > CURRENT_WEEK_GOAL, "a far week must grow the horizon in force")
        assertTrue(
            SchedulerDomain.horizonRefillDueMillis(filled, NOW, grown) <= NOW,
            "a horizon grown past the materialized plan must be due immediately",
        )
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
