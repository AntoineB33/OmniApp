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

    /**
     * User rule 2026-10-13: "for the goal field, add a date field. If the user sets 13/10/2026 for go to sleep goal
     * field which is at 22PM, and sleep time is 7h45min, then the date field of the wake up goal field is set to
     * 14/10/2026."
     */
    @Test
    fun the_goal_has_a_day_said_as_the_day_of_the_chosen_time_of_its_night() {
        val oct13 = kotlinx.datetime.LocalDate(2026, 10, 13).toEpochDays().toLong()
        val night = SchedulerDomain.withGoalNightTime(sleep.copy(sleepDurationMinutes = 7 * 60 + 45), SchedulerDomain.NightTime.GoToBed, 22 * 60)
        assertEquals(5 * 60 + 45, night.goalWakeMinutes)
        assertEquals(null, SchedulerDomain.goalNightEpochDay(night, SchedulerDomain.NightTime.GoToBed), "no date until one is stated")
        val dated = SchedulerDomain.withGoalNightDay(night, SchedulerDomain.NightTime.GoToBed, oct13)
        assertEquals(oct13, SchedulerDomain.goalNightEpochDay(dated, SchedulerDomain.NightTime.GoToBed))
        assertEquals(oct13 + 1, SchedulerDomain.goalNightEpochDay(dated, SchedulerDomain.NightTime.WakeUp))
        assertEquals(oct13 + 1, dated.goalWakeEpochDay)
        // Screens stop an hour before bed, the same evening.
        assertEquals(oct13, SchedulerDomain.goalNightEpochDay(dated, SchedulerDomain.NightTime.StopScreens))
        // Stated as the wake's day, it is that day; a bedtime after midnight is on the wake's day too.
        assertEquals(dated, SchedulerDomain.withGoalNightDay(night, SchedulerDomain.NightTime.WakeUp, oct13 + 1))
        val late = SchedulerDomain.withGoalNightTime(dated, SchedulerDomain.NightTime.GoToBed, 30)
        assertEquals(oct13, SchedulerDomain.goalNightEpochDay(late, SchedulerDomain.NightTime.GoToBed), "the day beside the time typed in is kept")
        assertEquals(oct13, SchedulerDomain.goalNightEpochDay(late, SchedulerDomain.NightTime.WakeUp))
        // The date taken off again.
        assertEquals(null, SchedulerDomain.withGoalNightDay(dated, SchedulerDomain.NightTime.WakeUp, null).goalWakeEpochDay)
    }

    @Test
    fun a_dated_goal_is_reached_on_its_day_and_an_undated_one_drifts_as_before() {
        // Wake 07:30 → 05:30, saved on day 100: 15 min every 2 days without a date.
        val drifting = SleepSchedule(wakeMinutes = 450, goalWakeMinutes = 330, anchorEpochDay = 100)
        assertEquals(listOf(450, 450, 435, 420, 330), listOf(100L, 101L, 102L, 104L, 200L).map { SchedulerDomain.effectiveWakeMinutes(drifting, it) })
        // The goal on day 110: evenly, 12 min a day, the goal's on that day and after, the start's before the anchor.
        val dated = drifting.copy(goalWakeEpochDay = 110)
        assertEquals(
            listOf(450, 450, 438, 390, 342, 330, 330),
            listOf(90L, 100L, 101L, 105L, 109L, 110L, 300L).map { SchedulerDomain.effectiveWakeMinutes(dated, it) },
        )
        // Later, toward a later wake; and a date not after the anchor is the goal at once.
        val later = SleepSchedule(wakeMinutes = 420, goalWakeMinutes = 480, anchorEpochDay = 100, goalWakeEpochDay = 104)
        assertEquals(listOf(420, 435, 450, 465, 480), (100L..104L).map { SchedulerDomain.effectiveWakeMinutes(later, it) })
        assertEquals(330, SchedulerDomain.effectiveWakeMinutes(dated.copy(goalWakeEpochDay = 100), 100))
        // The reducer anchors it on the day it is saved, and the date is stored; a payload written before it has none.
        val s = SchedulerReducer.reduce(SchedulerState.empty(), SchedulerIntent.SetSleepSchedule(dated.copy(anchorEpochDay = null), 100L))
        assertEquals(100L, s.sleep?.anchorEpochDay)
        assertEquals(110L, assertNotNull(SchedulerStateCodec.decode(SchedulerStateCodec.encode(s))).sleep?.goalWakeEpochDay)
        assertEquals(110L, assertNotNull(SchedulerStateCodec.decodeSnapshot(SchedulerStateCodec.encodeSnapshot(s))).sleep?.goalWakeEpochDay)
        val before = SchedulerStateCodec.encode(SchedulerReducer.reduce(SchedulerState.empty(), SchedulerIntent.SetSleepSchedule(drifting, 100L)))
        assertTrue("goalWakeEpochDay" !in before, "a goal with no date writes nothing new")
        val old = assertNotNull(SchedulerStateCodec.decode(before)).sleep
        assertEquals(null, old?.goalWakeEpochDay)
        assertEquals(435, SchedulerDomain.effectiveWakeMinutes(assertNotNull(old), 102))
        // Undo takes the date back.
        val redated = SchedulerReducer.reduce(s, SchedulerIntent.SetSleepSchedule(s.sleep!!.copy(goalWakeEpochDay = 120), 100L))
        assertEquals(110L, SchedulerReducer.reduce(redated, SchedulerIntent.Undo).sleep?.goalWakeEpochDay)
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
