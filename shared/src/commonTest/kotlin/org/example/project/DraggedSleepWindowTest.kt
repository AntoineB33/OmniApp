package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.datetime.TimeZone
import org.example.project.scheduler.domain.PeriodKinds
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.SleepSchedule
import org.example.project.scheduler.model.TaskPanel
import org.example.project.scheduler.persistence.SchedulerStateCodec
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState

/**
 * User rule 2026-10-07: *"Any block can be dragged, it then gets a blue outline"* — and *"If the user drags a period A
 * elsewhere, what appears at its original place can't be period A."* A Sleep window of the sleep schedule and its hour
 * before bed have no object of their own: dragged, the occurrence leaves the schedule (the way a dragged ring leaves
 * its alarm) and stands as a period the user placed ([SchedulerIntent.PlaceDerivedPeriod]).
 */
class DraggedSleepWindowTest {
    private val HOUR = 3_600_000L
    private val DAY = 24 * HOUR
    private val tz = TimeZone.currentSystemDefault()
    private val FROM = 1_700_000_000_000L

    private fun state() = SchedulerState.empty().copy(sleep = SleepSchedule(), automaticSchedule = false)

    private fun windows(s: SchedulerState) = SchedulerDomain.sleepPanels(s.sleep, FROM, FROM + 4 * DAY, tz)
    private fun hours(s: SchedulerState) = SchedulerDomain.beforeBedPanels(s.sleep, FROM, FROM + 4 * DAY, tz)
    private fun placed(s: SchedulerState, kind: String) =
        s.panels.filter { it.restrictiveKind == kind && SchedulerDomain.isUserPlaced(it) }

    private fun drag(s: SchedulerState, kind: String, origin: TaskPanel, by: Long) =
        SchedulerReducer.reduce(
            s,
            SchedulerIntent.PlaceDerivedPeriod(
                kind, origin.startEpochMillis, origin.endEpochMillis, origin.startEpochMillis + by, origin.endEpochMillis + by,
            ),
        )

    @Test
    fun a_dragged_sleep_window_leaves_the_schedule_and_stands_as_the_users_period() {
        val s0 = state()
        val night = windows(s0)[1]
        val s1 = drag(s0, PeriodKinds.SLEEP, night, 2 * HOUR)

        assertFalse(windows(s1).any { it.id == night.id }, "what appears at its original place cannot be the Sleep window")
        assertEquals(windows(s0).size - 1, windows(s1).size, "the other nights are the schedule's still")
        assertFalse(hours(s1).any { it.endEpochMillis == night.startEpochMillis }, "the hour before bed comes with a window")
        val period = placed(s1, PeriodKinds.SLEEP).single()
        assertEquals(night.startEpochMillis + 2 * HOUR to night.endEpochMillis + 2 * HOUR, period.startEpochMillis to period.endEpochMillis)
        assertEquals(SchedulerDomain.PanelOutline.User, SchedulerDomain.panelOutline(period), "it then gets a blue outline")
    }

    @Test
    fun the_panel_a_fill_laid_for_that_night_leaves_with_it_and_one_undo_each_walks_the_move_back() {
        val night = windows(state())[1]
        val s0 = state().copy(panels = listOf(night))
        val s1 = drag(s0, PeriodKinds.SLEEP, night, 2 * HOUR)
        assertFalse(s1.panels.any { it.id == night.id })

        // Walked back from the calendar, where the drag was made.
        val focused = SchedulerReducer.reduce(s1, SchedulerIntent.SetCalendarFocus(true))
        val undone = SchedulerReducer.reduce(focused, SchedulerIntent.Undo)
        assertTrue(placed(undone, PeriodKinds.SLEEP).isEmpty(), "the first undo takes the period away")
        assertTrue(undone.panels.any { it.id == night.id }, "and puts the fill's panel back")
        val back = SchedulerReducer.reduce(undone, SchedulerIntent.Undo)
        assertEquals(windows(s0), windows(back), "the second gives the night back to the schedule")
    }

    @Test
    fun a_dragged_hour_before_bed_leaves_its_window_where_it_is() {
        val s0 = state()
        val hour = hours(s0)[1]
        val s1 = drag(s0, PeriodKinds.BEFORE_BED, hour, -3 * HOUR)

        assertEquals(windows(s0), windows(s1))
        assertFalse(hours(s1).any { it.id == hour.id })
        val period = placed(s1, PeriodKinds.BEFORE_BED).single()
        assertEquals(hour.startEpochMillis - 3 * HOUR, period.startEpochMillis)
        assertEquals(SchedulerDomain.PanelOutline.User, SchedulerDomain.panelOutline(period))
    }

    @Test
    fun a_sleep_window_the_past_recorded_is_the_users_period_once_moved() {
        val recorded = TaskPanel("panel/1", null, "Sleep", FROM - 9 * HOUR, FROM - HOUR, sleep = true)
        val s0 = state().copy(panels = listOf(recorded))
        val s1 =
            SchedulerReducer.reduce(
                s0,
                SchedulerIntent.UpdateTaskPanel(
                    recorded.id, null, "Sleep", recorded.startEpochMillis - HOUR, recorded.endEpochMillis - HOUR,
                    org.example.project.scheduler.model.PanelPins(existence = true),
                ),
            )
        val period = placed(s1, PeriodKinds.SLEEP).single()
        assertEquals(recorded.startEpochMillis - HOUR, period.startEpochMillis)
        assertEquals(SchedulerDomain.PanelOutline.User, SchedulerDomain.panelOutline(period))
        assertFalse(s1.panels.any { it.sleep })
    }

    @Test
    fun a_span_that_names_no_occurrence_of_the_schedule_changes_nothing() {
        val s0 = state().copy(sleep = SleepSchedule(sleepDurationMinutes = 0))
        val s1 =
            SchedulerReducer.reduce(s0, SchedulerIntent.PlaceDerivedPeriod(PeriodKinds.SLEEP, FROM, FROM + HOUR, FROM + DAY, FROM + DAY + HOUR))
        assertEquals(s0, s1)
    }

    @Test
    fun restating_the_schedules_hours_keeps_the_nights_dragged_away() {
        val s1 = drag(state(), PeriodKinds.SLEEP, windows(state())[1], 2 * HOUR)
        val skipped = assertNotNull(s1.sleep).skippedWakeEpochDays
        assertEquals(1, skipped.size)
        val s2 = SchedulerReducer.reduce(s1, SchedulerIntent.SetSleepSchedule(SleepSchedule(wakeMinutes = 480, goalWakeMinutes = 480), 0L))
        assertEquals(skipped, s2.sleep?.skippedWakeEpochDays)
    }

    /** Persisted-DB compatibility (CLAUDE.md): the new fields have defaults, and a payload without them still loads. */
    @Test
    fun the_skipped_nights_round_trip_and_a_payload_written_before_them_skips_none() {
        val s1 = drag(state(), PeriodKinds.BEFORE_BED, hours(state())[1], -3 * HOUR)
        val s2 = drag(s1, PeriodKinds.SLEEP, windows(s1)[2], 2 * HOUR)
        val decoded = assertNotNull(SchedulerStateCodec.decode(SchedulerStateCodec.encode(s2)))
        assertEquals(s2.sleep, decoded.sleep)

        // The previous shape: a sleep schedule with neither list.
        val previous =
            SchedulerStateCodec.encode(s2)
                // The history units carry the schedule too, as JSON inside the JSON (escaped quotes).
                // Defaults are not written, so either list may open its object: the comma is taken on whichever side.
                .replace(Regex("""\\*"skippedWakeEpochDays\\*":\[[^\]]*\],?"""), "")
                .replace(Regex("""\\*"skippedBeforeBedEpochDays\\*":\[[^\]]*\],?"""), "")
                .replace(",}", "}")
        assertFalse("skippedWakeEpochDays" in previous)
        val old = assertNotNull(SchedulerStateCodec.decode(previous)).sleep
        assertEquals(s2.sleep?.copy(skippedWakeEpochDays = emptySet(), skippedBeforeBedEpochDays = emptySet()), old)
    }
}
