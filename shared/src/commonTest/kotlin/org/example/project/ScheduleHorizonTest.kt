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
 * PRD §9 "the horizon follows what is displayed": the app must **not** systematically materialize 168 h of
 * schedule. The fill horizon is the end of the week the calendar is showing (floored at
 * [SchedulerDomain.MIN_SCHEDULE_HORIZON_MILLIS] so a closed calendar still feeds the headless
 * notification/cue paths, capped at [SchedulerDomain.SCHEDULE_HORIZON_MILLIS] so a far week never enters the
 * persisted state) — so sitting on the current week computes no later day, and a far week is filled
 * asynchronously for display only.
 *
 * The complementary async/never-freeze half lives in `App.kt` (the far-week `LaunchedEffect` on
 * `Dispatchers.Default`); what is testable as pure logic is that the horizon is bounded by the screen, that
 * everything the fill projects is bounded WITH it, and that the rolling refill does not keep re-firing to
 * push the plan past the displayed week.
 */
class ScheduleHorizonTest {

    private val HOUR = 3_600_000L
    private val DAY = 24 * HOUR
    private val WEEK = 7 * DAY
    private val NOW = 1_700_000_000_000L

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

    // ----- the horizon itself ---------------------------------------------------------------------

    @Test
    fun the_horizon_is_the_displayed_week_clamped_between_one_day_and_one_week() {
        // No calendar open: only the operational floor, NOT 168h. This is the case the old code got wrong —
        // an app sitting idle with the calendar closed re-materialized a full week of plan on every refill.
        assertEquals(NOW + DAY, SchedulerDomain.scheduleHorizonEndMillis(NOW, null))

        // Showing a week that ends in three days: fill three days, and no more.
        assertEquals(NOW + 3 * DAY, SchedulerDomain.scheduleHorizonEndMillis(NOW, NOW + 3 * DAY))

        // A week ending sooner than the floor (e.g. the current week on a Sunday evening) still gets the
        // floor, so the headless notification/cue paths keep a day of plan.
        assertEquals(NOW + DAY, SchedulerDomain.scheduleHorizonEndMillis(NOW, NOW + 2 * HOUR))

        // A far week is capped: it is never materialized into the state (App.kt fills it for display only).
        assertEquals(NOW + WEEK, SchedulerDomain.scheduleHorizonEndMillis(NOW, NOW + 40 * DAY))
    }

    // ----- what the fill computes is bounded by it ------------------------------------------------

    @Test
    fun staying_on_the_current_week_schedules_no_later_day() {
        val (s, _) = stateWithOneTask()
        // The calendar is showing a week that ends in 36 h.
        val horizon = SchedulerDomain.scheduleHorizonEndMillis(NOW, NOW + 36 * HOUR)
        val near = SchedulerDomain.fillSchedule(s, NOW, horizonMillis = horizon)
        val full = SchedulerDomain.fillSchedule(s, NOW)

        assertTrue(near.isNotEmpty(), "the displayed week must still be scheduled")
        // Nothing is even STARTED beyond the displayed week (the chunk covering the horizon may end past it).
        assertTrue(
            near.none { it.startEpochMillis >= horizon },
            "no panel may start after the displayed week: ${near.filter { it.startEpochMillis >= horizon }.size} did",
        )
        // A sole task merges into one continuous panel, so the count says nothing — the COVERAGE is what
        // shows the days after the displayed week were never computed.
        val nearEnd = near.maxOf { it.endEpochMillis }
        val fullEnd = full.maxOf { it.endEpochMillis }
        assertTrue(nearEnd < NOW + 2 * DAY, "the plan must stop just past the displayed week, ended at $nearEnd")
        assertTrue(fullEnd > NOW + 6 * DAY, "the unconditional 168h fill it replaces did compute the whole week")
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
        // The seam the engine drives from `App.kt`'s focused week. A refill must honour it — this is what
        // makes "stay on the current week ⇒ compute only the current week" true of the LIVE app, not just of
        // a direct SchedulerDomain call.
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
    fun a_fill_to_the_displayed_week_is_not_immediately_due_for_refill_again() {
        // The anti-spin obligation of HorizonRefillRuleTest, restated for a horizon SHORTER than 168h: a
        // schedule reaching the end of the displayed week is complete, not "short of 168h" — otherwise the
        // engine would refill forever trying to push the plan past the week the user is looking at.
        val (s, _) = stateWithOneTask()
        val horizon = SchedulerDomain.scheduleHorizonEndMillis(NOW, NOW + 36 * HOUR)
        val filled = SchedulerDomain.fillSchedule(s, NOW, horizonMillis = horizon)
        assertTrue(
            SchedulerDomain.horizonRefillDueMillis(filled, NOW, horizon) > NOW,
            "a fresh fill to the displayed week must not be immediately due again",
        )
    }

    @Test
    fun navigating_to_a_further_week_makes_the_refill_due_at_once() {
        // The other side of the same coin: growing the horizon (the user opens a later week) must make the
        // existing plan read as short, so the engine extends it — that is `launchCalendarHorizonReschedule`.
        val (s, _) = stateWithOneTask()
        val filled =
            SchedulerDomain.fillSchedule(s, NOW, horizonMillis = SchedulerDomain.scheduleHorizonEndMillis(NOW, null))
        val grown = SchedulerDomain.scheduleHorizonEndMillis(NOW, NOW + 5 * DAY)
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
