package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.datetime.TimeZone
import org.example.project.scheduler.domain.PeriodKinds
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.SleepSchedule
import org.example.project.scheduler.persistence.SchedulerStateCodec
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState

/**
 * 2026-10-09. Anomaly: "In the sleep configurations in the calendar, when editing the fields, it freezes a lot. The
 * scheduler engine should not freeze what the user does." And: "add a drop-down list to select which field to edit
 * between wake up, go to bed or stop screens. Add a field for the duration of the no screen before bed period. Rename
 * the hour before bed period that way."
 */
class SleepScheduleEditTest {
    private val MIN = 60_000L
    // Wake 07:30 after 8h30: bed at 23:00, screens off at 22:00.
    private val sleep = SleepSchedule()

    /**
     * "When modifying the time to wake up, it should update the time to go to sleep and stops screens. Right now, it
     * updates the total sleep time… if stop screens is selected, then it must not be goal wake time but goal stop
     * screens time."
     */
    @Test
    fun the_three_times_are_one_night_and_stating_any_of_them_moves_the_whole_night() {
        fun times(s: SleepSchedule) = SchedulerDomain.NightTime.entries.map { SchedulerDomain.nightTimeMinutes(s, it) }
        assertEquals(listOf(7 * 60 + 30, 23 * 60, 22 * 60), times(sleep))
        // Whichever time is stated, the night moves whole: neither length changes.
        val moves = listOf(
            SchedulerDomain.withNightTime(sleep, SchedulerDomain.NightTime.WakeUp, 8 * 60 + 15),
            SchedulerDomain.withNightTime(sleep, SchedulerDomain.NightTime.GoToBed, 23 * 60 + 45),
            SchedulerDomain.withNightTime(sleep, SchedulerDomain.NightTime.StopScreens, 22 * 60 + 45),
        )
        for (moved in moves) {
            assertEquals(listOf(8 * 60 + 15, 23 * 60 + 45, 22 * 60 + 45), times(moved), "$moved")
            assertEquals(sleep.sleepDurationMinutes, moved.sleepDurationMinutes)
            assertEquals(sleep.beforeBedMinutes, moved.beforeBedMinutes)
            assertEquals(sleep.goalWakeMinutes, moved.goalWakeMinutes, "the goal is its own statement")
        }
        // The goal is said as the same time of the night: "goal stop screens time" 21:00 is a goal wake time of 06:30.
        assertEquals(22 * 60, SchedulerDomain.goalNightTimeMinutes(sleep, SchedulerDomain.NightTime.StopScreens))
        val goal = SchedulerDomain.withGoalNightTime(sleep, SchedulerDomain.NightTime.StopScreens, 21 * 60)
        assertEquals(6 * 60 + 30, goal.goalWakeMinutes)
        assertEquals(21 * 60, SchedulerDomain.goalNightTimeMinutes(goal, SchedulerDomain.NightTime.StopScreens))
        assertEquals(22 * 60, SchedulerDomain.goalNightTimeMinutes(goal, SchedulerDomain.NightTime.GoToBed))
        assertEquals(sleep.wakeMinutes, goal.wakeMinutes)
        // A longer period before bed moves the time screens stop, and nothing else.
        val longer = sleep.copy(beforeBedMinutes = 105)
        assertEquals(listOf(7 * 60 + 30, 23 * 60, 21 * 60 + 15), times(longer))
        // Round the clock: bed at 00:15 is a wake at 08:45.
        assertEquals(8 * 60 + 45, SchedulerDomain.withNightTime(sleep, SchedulerDomain.NightTime.GoToBed, 15).wakeMinutes)
    }

    @Test
    fun the_period_before_bed_is_as_long_as_the_schedule_says_and_named_for_what_it_is() {
        val tz = TimeZone.UTC
        val from = 1_800_000_000_000L
        val to = from + 3 * 24 * 60 * MIN
        fun lengths(schedule: SleepSchedule) =
            SchedulerDomain.beforeBedPanels(schedule, from, to, tz).map { it.endEpochMillis - it.startEpochMillis }.distinct()
        assertEquals(listOf(60 * MIN), lengths(sleep))
        assertEquals(listOf(105 * MIN), lengths(sleep.copy(beforeBedMinutes = 105)))
        assertEquals(emptyList(), lengths(sleep.copy(beforeBedMinutes = 0)))
        // It ends where the night starts, whatever its length.
        val nights = SchedulerDomain.sleepPanels(sleep, from, to, tz).map { it.startEpochMillis }.toSet()
        val periods = SchedulerDomain.beforeBedPanels(sleep.copy(beforeBedMinutes = 105), from, to, tz)
        assertTrue(periods.isNotEmpty() && periods.all { it.endEpochMillis in nights })
        assertEquals("No screen before bed", periods.first().title)
        assertEquals("No screen before bed", PeriodKinds.periodTitle(PeriodKinds.BEFORE_BED))
        // The kind is what resilience values and drawings are stored under: it keeps its name.
        assertEquals("before bed", PeriodKinds.BEFORE_BED)
    }

    @Test
    fun editing_the_schedule_runs_no_fill_on_the_thread_the_edit_came_in_on() {
        var s = SchedulerState.empty()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(s.lists[s.rootListId]!!.cellIds[0], "Solo"))
        assertTrue(s.automaticSchedule)
        val edited = SchedulerReducer.reduce(s, SchedulerIntent.SetSleepSchedule(sleep.copy(wakeMinutes = 8 * 60), 20_000L))
        assertEquals(8 * 60, edited.sleep?.wakeMinutes)
        // The plan is the engine's to make, off this thread: the reducer hands the panels back as they were.
        assertSame(s.panels, edited.panels)
        // And the schedule is what makes the engine run again.
        assertTrue(SchedulerDomain.schedulingSignature(s) != SchedulerDomain.schedulingSignature(edited))
        // A length out of reach is kept in reach; undo takes the edit back.
        val long = SchedulerReducer.reduce(edited, SchedulerIntent.SetSleepSchedule(sleep.copy(beforeBedMinutes = 5000), 20_000L))
        assertEquals(SchedulerDomain.MAX_BEFORE_BED_MINUTES, long.sleep?.beforeBedMinutes)
        assertEquals(edited.sleep, SchedulerReducer.reduce(long, SchedulerIntent.Undo).sleep)
    }

    @Test
    fun the_length_is_stored_and_a_payload_written_before_it_reads_an_hour() {
        val s = SchedulerReducer.reduce(SchedulerState.empty(), SchedulerIntent.SetSleepSchedule(sleep.copy(beforeBedMinutes = 105), 20_000L))
        val payload = SchedulerStateCodec.encode(s)
        assertEquals(105, assertNotNull(SchedulerStateCodec.decode(payload)).sleep?.beforeBedMinutes)
        assertEquals(105, assertNotNull(SchedulerStateCodec.decodeSnapshot(SchedulerStateCodec.encodeSnapshot(s))).sleep?.beforeBedMinutes)
        // The shape before 2026-10-09: a schedule with no such field (one at the default writes none).
        val before = SchedulerStateCodec.encode(SchedulerReducer.reduce(SchedulerState.empty(), SchedulerIntent.SetSleepSchedule(sleep.copy(wakeMinutes = 480), 20_000L)))
        assertTrue("beforeBedMinutes" !in before, "a schedule at the hour writes nothing new")
        assertEquals(60, assertNotNull(SchedulerStateCodec.decode(before)).sleep?.beforeBedMinutes)
    }
}
