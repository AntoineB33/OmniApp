package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.SleepSchedule
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.model.TaskPanel
import org.example.project.scheduler.persistence.SchedulerStateCodec
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

    // ----- Scheduler avoidance ------------------------------------------------------------------

    @Test
    fun fill_schedule_never_places_a_task_inside_a_sleep_window() {
        val now = utc(2024, 1, 1, 10, 0)
        val state = soloTask().copy(sleep = SchedulerDomain.DEFAULT_SLEEP)
        val panels = SchedulerDomain.fillSchedule(state, now, tz)
        val autos = panels.filter { it.auto }
        val sleeps = panels.filter { it.sleep }
        assertTrue(autos.isNotEmpty() && sleeps.isNotEmpty())
        // Every auto panel sits entirely outside every sleep window (split + resumed after, not overlapping).
        autos.forEach { a ->
            sleeps.forEach { s ->
                assertTrue(
                    a.endEpochMillis <= s.startEpochMillis || a.startEpochMillis >= s.endEpochMillis,
                    "auto [${a.startEpochMillis},${a.endEpochMillis}] overlaps sleep [${s.startEpochMillis},${s.endEpochMillis}]",
                )
            }
        }
        // A continuous solo task resumes at wake: some auto panel starts exactly at a window's end.
        assertTrue(autos.any { a -> sleeps.any { it.endEpochMillis == a.startEpochMillis } })
    }

    @Test
    fun no_task_is_scheduled_in_the_hour_before_bed() {
        val now = utc(2024, 1, 1, 10, 0)
        val state = soloTask().copy(sleep = SchedulerDomain.DEFAULT_SLEEP)
        val autos = SchedulerDomain.fillSchedule(state, now, tz).filter { it.auto }
        // Tonight's no-task window: [22:00 (bedtime − 1h), 07:30 next day (wake)].
        val windDownStart = utc(2024, 1, 1, 22, 0)
        val wake = utc(2024, 1, 2, 7, 30)
        autos.forEach { a ->
            assertTrue(
                a.endEpochMillis <= windDownStart || a.startEpochMillis >= wake,
                "auto [${a.startEpochMillis},${a.endEpochMillis}] intrudes on the wind-down hour or sleep",
            )
        }
        // A task still runs right up to the start of the wind-down hour.
        assertTrue(autos.any { it.endEpochMillis == windDownStart })
    }

    @Test
    fun side_tasks_are_not_projected_into_a_sleep_window() {
        val now = utc(2024, 1, 1, 10, 0)
        val to = now + 2L * 24 * HOUR_MS
        val regions = SchedulerDomain.sleepRegions(SchedulerDomain.DEFAULT_SLEEP, now, to, tz)
        val sides = SchedulerDomain.sideTaskPanels(SchedulerDomain.DEFAULT_SIDE_TASKS, now, to, regions)
        assertTrue(sides.isNotEmpty())
        sides.forEach { p ->
            regions.forEach { r ->
                assertFalse(
                    p.startEpochMillis >= r.startEpochMillis && p.startEpochMillis < r.endEpochMillis,
                    "side task starts inside a sleep window",
                )
            }
        }
    }

    @Test
    fun a_sleep_window_breaks_the_display_merge_even_when_side_task_gaps_are_bridged() {
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
}
