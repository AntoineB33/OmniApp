package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.SleepSchedule
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.model.TaskPanel
import org.example.project.scheduler.model.TaskTimeRange
import org.example.project.scheduler.persistence.SchedulerStateCodec
import org.example.project.scheduler.state.HistoryCategory
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState

/** Tests for the sleep schedule: wake drift, nightly sleep windows, and scheduler avoidance. */
class SchedulerSleepTest {
    private val tz = TimeZone.UTC
    private val MIN = 60_000L
    private val HOUR_MS = 3_600_000L

    private fun utc(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        LocalDateTime(year, month, day, hour, minute).toInstant(tz).toEpochMilliseconds()

    /** A single "Solo" task (45-min minimum) to fill the schedule around the sleep windows. */
    private fun soloTask(): SchedulerState {
        var s = SchedulerState.empty()
        val c0 = s.lists[s.rootListId]!!.cellIds[0]
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(c0, "Solo"))
        val solo = s.tasks.keys.first { s.tasks[it]!!.title == "Solo" }
        return SchedulerReducer.reduce(s, SchedulerIntent.SetTaskMinimumTime(solo, 45))
    }

    // ----- Wake drift ---------------------------------------------------------------------------

    @Test
    fun effective_wake_drifts_15_min_per_two_days_toward_an_earlier_goal() {
        // Wake 07:30 (450), goal 06:30 (390): 60 min earlier, anchored at day 100.
        val sleep = SleepSchedule(wakeMinutes = 450, goalWakeMinutes = 390, sleepDurationMinutes = 510, anchorEpochDay = 100)
        assertEquals(450, SchedulerDomain.effectiveWakeMinutes(sleep, 100)) // day 0
        assertEquals(450, SchedulerDomain.effectiveWakeMinutes(sleep, 101)) // < 2 days → no step
        assertEquals(435, SchedulerDomain.effectiveWakeMinutes(sleep, 102)) // 1 step → −15
        assertEquals(390, SchedulerDomain.effectiveWakeMinutes(sleep, 108)) // 4 steps = −60 → clamp at goal
        assertEquals(390, SchedulerDomain.effectiveWakeMinutes(sleep, 200)) // stays clamped
    }

    @Test
    fun effective_wake_drifts_toward_a_later_goal_and_is_static_without_an_anchor() {
        val sleep = SleepSchedule(wakeMinutes = 450, goalWakeMinutes = 480, sleepDurationMinutes = 510, anchorEpochDay = 100)
        assertEquals(465, SchedulerDomain.effectiveWakeMinutes(sleep, 102)) // +15
        assertEquals(480, SchedulerDomain.effectiveWakeMinutes(sleep, 106)) // clamp at goal
        // No anchor → no drift, ever.
        assertEquals(450, SchedulerDomain.effectiveWakeMinutes(sleep.copy(anchorEpochDay = null), 999))
    }

    // ----- Sleep windows ------------------------------------------------------------------------

    @Test
    fun sleep_panels_are_nightly_windows_ending_at_wake() {
        // Default: wake 07:30, 8h30 in bed → each window is [23:00, 07:30].
        val now = utc(2024, 1, 1, 10, 0)
        val panels = SchedulerDomain.sleepPanels(SchedulerDomain.DEFAULT_SLEEP, now, now + 3L * 24 * HOUR_MS, tz)
        // The just-ended window (07:30 today) is in the past, so the first one starts tonight at 23:00.
        assertEquals(utc(2024, 1, 1, 23, 0), panels.minByOrNull { it.startEpochMillis }!!.startEpochMillis)
        panels.forEach {
            assertTrue(it.sleep && it.taskId == null && it.title == "Sleep")
            assertEquals(510L * MIN, it.endEpochMillis - it.startEpochMillis) // 8h30
        }
        // null schedule ⇒ no windows.
        assertTrue(SchedulerDomain.sleepPanels(null, now, now + HOUR_MS, tz).isEmpty())
    }

    // ----- Sleep-band carving by activity (working through a scheduled sleep window) -------------

    @Test
    fun carve_sleep_panels_splits_the_band_where_the_device_was_active() {
        // One "Sleep" window [23:00, 07:30]; the user was active [01:00, 03:00] in the middle of it.
        val start = utc(2024, 1, 1, 23, 0)
        val end = utc(2024, 1, 2, 7, 30)
        val panel = TaskPanel("sleep/1", null, "Sleep", start, end, sleep = true)
        val active = listOf(TaskTimeRange(utc(2024, 1, 2, 1, 0), utc(2024, 1, 2, 3, 0)))
        val carved = SchedulerDomain.carveSleepPanels(listOf(panel), active).sortedBy { it.startEpochMillis }
        assertEquals(2, carved.size)
        assertEquals(start, carved[0].startEpochMillis)
        assertEquals(utc(2024, 1, 2, 1, 0), carved[0].endEpochMillis)
        assertEquals(utc(2024, 1, 2, 3, 0), carved[1].startEpochMillis)
        assertEquals(end, carved[1].endEpochMillis)
        // The pieces stay distinct (id suffixed) so they don't collide as calendar entries.
        assertEquals("sleep/1", carved[0].id)
        assertTrue(carved[1].id != carved[0].id)
        // No activity ⇒ the band is returned untouched (conservative: absent evidence, sleep stays solid).
        assertEquals(listOf(panel), SchedulerDomain.carveSleepPanels(listOf(panel), emptyList()))
        // Activity covering the whole window ⇒ the band drops out entirely.
        assertTrue(SchedulerDomain.carveSleepPanels(listOf(panel), listOf(TaskTimeRange(start, end))).isEmpty())
    }

    // ----- Incremental OS-log backfill checkpoint ------------------------------------------------

    @Test
    fun sleep_scan_floor_resumes_from_the_checkpoint_but_clamps_to_the_three_day_floor() {
        val now = utc(2024, 1, 10, 12, 0)
        val horizon = 3L * 24 * HOUR_MS
        val floor = now - horizon
        // First run (no checkpoint) ⇒ the 3-day floor.
        assertEquals(floor, SchedulerDomain.sleepScanFloor(now, null, horizon))
        // A recent checkpoint (within the horizon) ⇒ resume from it, don't re-read older log.
        val recent = now - HOUR_MS
        assertEquals(recent, SchedulerDomain.sleepScanFloor(now, recent, horizon))
        // A stale checkpoint (older than the floor, e.g. long offline) ⇒ clamp back up to the floor.
        assertEquals(floor, SchedulerDomain.sleepScanFloor(now, now - 10L * 24 * HOUR_MS, horizon))
    }

    // ----- Scheduler avoidance ------------------------------------------------------------------

    @Test
    fun fill_schedule_places_no_task_inside_a_sleep_window() {
        // PRD §8/§17: a sleep window IS an inactivity period — one labelled "Sleep" — and an inactivity
        // period is grey, which means the scheduler places nothing in it. (This reverses the earlier rule
        // that projected the plan straight through the night; the two readings differ only here, and the
        // user's spec makes every grey period a period accepting nobody.)
        val now = utc(2024, 1, 1, 10, 0)
        val state = soloTask().copy(sleep = SchedulerDomain.DEFAULT_SLEEP)
        val panels = SchedulerDomain.fillSchedule(state, now, tz)
        val autos = panels.filter { it.auto }
        val sleeps = panels.filter { it.sleep }
        assertTrue(autos.isNotEmpty() && sleeps.isNotEmpty())
        sleeps.forEach { s ->
            assertTrue(
                autos.none { a -> a.startEpochMillis < s.endEpochMillis && a.endEpochMillis > s.startEpochMillis },
                "a task was scheduled inside the sleep window [${s.startEpochMillis},${s.endEpochMillis}]",
            )
        }
    }

    @Test
    fun the_work_plan_stops_at_the_wind_down_hour_and_resumes_after_the_sleep_window() {
        // PRD §17: work stops an hour BEFORE bedtime, because that hour is covered by the period "before
        // bed" and no task is resilient to it by default. So the plan runs up to the wind-down's start —
        // not to the sleep window's — and opens a fresh chunk on the far side of the night.
        val now = utc(2024, 1, 1, 10, 0)
        val panels = SchedulerDomain.fillSchedule(soloTask().copy(sleep = SchedulerDomain.DEFAULT_SLEEP), now, tz)
        val autos = panels.filter { it.auto }
        val firstNight = panels.filter { it.sleep }.minByOrNull { it.startEpochMillis }
        assertNotNull(firstNight, "no sleep window was projected")
        val windDownStart = firstNight.startEpochMillis - SchedulerDomain.BEFORE_BED_MILLIS
        assertTrue(
            autos.any { it.endEpochMillis == windDownStart },
            "the work plan does not run up to the start of the wind-down hour",
        )
        assertTrue(
            autos.none { it.startEpochMillis < firstNight.startEpochMillis && it.endEpochMillis > windDownStart },
            "a task was scheduled inside the wind-down hour",
        )
        assertTrue(
            autos.any { it.startEpochMillis == firstNight.endEpochMillis },
            "the work plan does not resume at the end of the sleep window",
        )
    }

    @Test
    fun screen_breaks_are_projected_across_a_sleep_window() {
        // PRD §15: a user may work at the computer during the night, so the eye-rest / pose cues keep firing
        // straight through the nightly sleep windows (and render over the "Sleep" band). The projection no
        // longer skips sleep, so at least one screen break starts inside each night's window.
        val now = utc(2024, 1, 1, 10, 0)
        val to = now + 2L * 24 * HOUR_MS
        val regions = SchedulerDomain.sleepRegions(SchedulerDomain.DEFAULT_SLEEP, now, to, tz)
        assertTrue(regions.isNotEmpty())
        val sides = SchedulerDomain.screenBreakPanels(SchedulerDomain.DEFAULT_SCREEN_BREAKS, now, to)
        regions.forEach { r ->
            assertTrue(
                sides.any { it.startEpochMillis >= r.startEpochMillis && it.startEpochMillis < r.endEpochMillis },
                "no screen break projected inside the sleep window [${r.startEpochMillis},${r.endEpochMillis}]",
            )
        }
    }

    @Test
    fun a_sleep_window_breaks_the_display_merge_even_when_screen_break_gaps_are_bridged() {
        // Two same-task panels straddling tonight's [23:00, 07:30] sleep window.
        val taskId = TaskId("t")
        val a = TaskPanel("a", taskId, "Solo", utc(2024, 1, 1, 20, 0), utc(2024, 1, 1, 23, 0), auto = true)
        val b = TaskPanel("b", taskId, "Solo", utc(2024, 1, 2, 7, 30), utc(2024, 1, 2, 9, 0), auto = true)
        val sleep = SchedulerDomain.sleepRegions(SchedulerDomain.DEFAULT_SLEEP, utc(2024, 1, 1, 20, 0), utc(2024, 1, 2, 9, 0), tz)
        // The sleep window in the gap keeps them as two display blocks despite gap-bridging.
        assertEquals(2, SchedulerDomain.groupSameTaskPanelsForDisplay(listOf(a, b), bridgeGaps = true, sleepRegions = sleep).size)
        // Without a sleep window in the gap, bridging fuses them into one block (the existing behavior).
        assertEquals(1, SchedulerDomain.groupSameTaskPanelsForDisplay(listOf(a, b), bridgeGaps = true).size)
    }

    @Test
    fun a_screen_break_gap_inside_a_sleep_window_still_bridges_when_screen_breaks_are_hidden() {
        // Regression: work is now scheduled straight through the night, so a same-task run inside the
        // [23:00, 07:30] sleep window is split by pose-cue screen breaks. Hiding screen breaks must fuse those
        // pieces into one continuous block — the sleep band overlaps the gap but does not sit *inside* it,
        // so it must not cut the run (the previous "any sleep overlap" guard left a hole during sleep).
        val taskId = TaskId("t")
        val a = TaskPanel("a", taskId, "Solo", utc(2024, 1, 2, 1, 0), utc(2024, 1, 2, 2, 0), auto = true)
        val b = TaskPanel("b", taskId, "Solo", utc(2024, 1, 2, 2, 5), utc(2024, 1, 2, 4, 0), auto = true)
        val sleep = SchedulerDomain.sleepRegions(SchedulerDomain.DEFAULT_SLEEP, utc(2024, 1, 1, 20, 0), utc(2024, 1, 2, 9, 0), tz)
        assertEquals(1, SchedulerDomain.groupSameTaskPanelsForDisplay(listOf(a, b), bridgeGaps = true, sleepRegions = sleep).size)
    }

    // ----- Persistence --------------------------------------------------------------------------

    @Test
    fun codec_round_trips_the_sleep_schedule_and_the_panel_flag() {
        val state = soloTask().copy(
            sleep = SleepSchedule(wakeMinutes = 420, goalWakeMinutes = 390, sleepDurationMinutes = 480, anchorEpochDay = 100),
            panels = listOf(TaskPanel("sleep/1", null, "Sleep", 0, 1_000, sleep = true)),
        )
        val decoded = SchedulerStateCodec.decode(SchedulerStateCodec.encode(state))!!
        assertEquals(state.sleep, decoded.sleep)
        assertTrue(decoded.panels.any { it.sleep })
        // A state without a sleep schedule round-trips to null (old payloads decode to null too).
        assertNull(SchedulerStateCodec.decode(SchedulerStateCodec.encode(SchedulerState.empty()))!!.sleep)
    }

    // ----- History (undoable, shown in the History window) --------------------------------------

    /** A base state with a known sleep schedule and no history yet. */
    private fun stateWithSleep(): SchedulerState =
        soloTask().copy(sleep = SleepBaseline)

    private val SleepBaseline =
        SleepSchedule(wakeMinutes = 450, goalWakeMinutes = 450, sleepDurationMinutes = 510, anchorEpochDay = null)

    @Test
    fun setting_the_sleep_schedule_records_a_main_history_unit() {
        val s0 = stateWithSleep()
        val before = s0.histories.forCategory(HistoryCategory.Main).units.size
        val s1 = SchedulerReducer.reduce(
            s0,
            SchedulerIntent.SetSleepSchedule(SleepBaseline.copy(sleepDurationMinutes = 480), todayEpochDay = 100),
        )
        val main = s1.histories.forCategory(HistoryCategory.Main)
        assertEquals(before + 1, main.units.size)
        assertEquals(main.units.lastIndex, main.pointer)
        assertEquals("Sleep schedule", main.units.last().delta.label)
        assertTrue(main.units.last().delta.details.any { it.contains("duration") })
    }

    @Test
    fun setting_an_unchanged_sleep_schedule_adds_no_history_unit() {
        val s0 = stateWithSleep()
        // SetSleepSchedule anchors, so pass a schedule that anchors to the same value (goal == wake ⇒ null anchor).
        val s1 = SchedulerReducer.reduce(s0, SchedulerIntent.SetSleepSchedule(SleepBaseline, todayEpochDay = 100))
        assertEquals(s0.histories, s1.histories)
    }

    @Test
    fun undo_and_redo_restore_the_sleep_schedule() {
        val s0 = stateWithSleep()
        val changed = SleepBaseline.copy(wakeMinutes = 420, sleepDurationMinutes = 480)
        val s1 = SchedulerReducer.reduce(s0, SchedulerIntent.SetSleepSchedule(changed, todayEpochDay = 100))
        assertEquals(480, s1.sleep!!.sleepDurationMinutes)

        val undone = SchedulerReducer.reduce(s1, SchedulerIntent.Undo)
        assertEquals(SleepBaseline, undone.sleep)

        val redone = SchedulerReducer.reduce(undone, SchedulerIntent.Redo)
        assertEquals(420, redone.sleep!!.wakeMinutes)
        assertEquals(480, redone.sleep!!.sleepDurationMinutes)
    }

    @Test
    fun codec_round_trips_a_sleep_schedule_history_unit() {
        val s0 = stateWithSleep()
        val s1 = SchedulerReducer.reduce(
            s0,
            SchedulerIntent.SetSleepSchedule(SleepBaseline.copy(wakeMinutes = 400), todayEpochDay = 100),
        )
        val decoded = SchedulerStateCodec.decode(SchedulerStateCodec.encode(s1))!!
        // The unit survives the round-trip and still undoes to the original schedule.
        val undone = SchedulerReducer.reduce(decoded, SchedulerIntent.Undo)
        assertEquals(SleepBaseline, undone.sleep)
    }
}
