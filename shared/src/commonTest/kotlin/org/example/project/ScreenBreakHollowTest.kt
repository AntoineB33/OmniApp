package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.ScreenBreak
import org.example.project.scheduler.model.ScreenBreakPeriod
import org.example.project.scheduler.model.Task
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.model.TaskPanel
import org.example.project.scheduler.model.TaskTimeRange
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState
import org.example.project.ui.CalendarRecord
import org.example.project.ui.recordsForDay
import org.example.project.ui.screenBreakClosedFraction

/**
 * PRD §15: a screen break is a **period**, and the 5-/15-minute poses are periods that ACCEPT the tasks
 * needing no screen over part (the 5-min pose's last four minutes) or all (the 15-min pose) of their span.
 * The calendar must show that: the open part is drawn **hollow** — an outline the work beneath shows through
 * — rather than covered by the solid band that means "this instant accepts nobody".
 *
 * These pin the one function both readers use, [SchedulerDomain.screenBreakOpenStartMillis]: the §9 fill
 * schedules from it and the calendar draws from it, so what is drawn hollow is exactly what is open to a
 * task — the divergence this exists to prevent being a band that says "stop" over a period the scheduler is
 * busy filling with off-screen work.
 */
class ScreenBreakHollowTest {

    private val MIN = 60_000L
    private val HOUR = 3_600_000L
    private val NOW = 1_000_000_000_000L
    private val tz = TimeZone.UTC

    private fun panel(start: Long, end: Long, title: String) =
        TaskPanel(
            id = "side/0/$start",
            taskId = null,
            title = title,
            startEpochMillis = start,
            endEpochMillis = end,
            screenBreak = true,
        )

    // ----- the shapes ---------------------------------------------------------------------------

    @Test
    fun the_look_away_has_no_open_part_at_all() {
        // `side-dev` test 11's sliding 20 s window: a period that accepts nothing, end to end. Nothing of it
        // is hollow — a solid band is the honest drawing, because no task may be placed in it.
        assertNull(SchedulerDomain.screenBreakOpenStartMillis(ScreenBreakPeriod.Closed, NOW, NOW + 20_000L))
    }

    @Test
    fun the_5min_pose_opens_after_its_closed_first_minute() {
        assertEquals(
            NOW + SchedulerDomain.SCREEN_BREAK_CLOSED_HEAD_MILLIS,
            SchedulerDomain.screenBreakOpenStartMillis(
                ScreenBreakPeriod.ClosedMinuteThenBreakDoable, NOW, NOW + 5 * MIN,
            ),
        )
    }

    @Test
    fun the_15min_pose_is_open_from_its_very_first_instant() {
        // No closed head at all (PRD §15): the whole band is hollow.
        assertEquals(
            NOW,
            SchedulerDomain.screenBreakOpenStartMillis(ScreenBreakPeriod.OffScreenOnly, NOW, NOW + 15 * MIN),
        )
    }

    @Test
    fun a_pose_retimed_shorter_than_its_closed_head_is_closed_end_to_end() {
        // The debug fast-break knobs only retime a break, never reshape it — so a 5-second "5-min pose" is
        // all head and has no open part to draw hollow (the same clamp the fill applies).
        assertNull(
            SchedulerDomain.screenBreakOpenStartMillis(
                ScreenBreakPeriod.ClosedMinuteThenBreakDoable, NOW, NOW + 5_000L,
            ),
        )
    }

    @Test
    fun a_bands_shape_is_looked_up_by_title_and_an_unknown_one_draws_solid() {
        val pose15 =
            ScreenBreak(
                title = "take a 15min pose",
                intervalMillis = 2 * HOUR,
                durationMillis = 15 * MIN,
                restBreak = true,
                shape = ScreenBreakPeriod.OffScreenOnly,
            )
        assertEquals(
            NOW,
            SchedulerDomain.screenBreakOpenStartMillis(listOf(pose15), panel(NOW, NOW + 15 * MIN, pose15.title)),
        )
        // A band whose break is gone from the config is drawn solid rather than guessed open — the
        // conservative reading, since a hollow band claims the scheduler may fill it.
        assertNull(
            SchedulerDomain.screenBreakOpenStartMillis(listOf(pose15), panel(NOW, NOW + 15 * MIN, "who is this")),
        )
    }

    // ----- what is drawn hollow is what the fill schedules in -----------------------------------

    @Test
    fun the_hollow_part_is_exactly_the_span_the_fill_gives_to_off_screen_work() {
        // One off-screen, break-doable task, small enough that its minimum fits either pose, and the two
        // poses as the only periods. Every auto panel that lands in a break must lie inside that break's
        // HOLLOW part — nothing may touch a closed head, which is the instant-for-instant statement of what
        // the calendar now draws.
        var s = SchedulerState.empty()
        val c0 = s.lists[s.rootListId]!!.cellIds[0]
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(c0, "Solo"))
        val solo: TaskId = s.tasks.keys.first { s.tasks[it]!!.title == "Solo" }
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetTaskMinimumTime(solo, 3))
        s = SchedulerReducer.reduce(
            s,
            SchedulerIntent.SetTaskScreenFlags(solo, onScreen = false, doableDuringBreak = true),
        )
        val breaks =
            listOf(
                ScreenBreak(
                    title = "take a 5min pose and blink hard",
                    intervalMillis = HOUR,
                    durationMillis = 5 * MIN,
                    restBreak = true,
                    lastRestMillis = NOW,
                    shape = ScreenBreakPeriod.ClosedMinuteThenBreakDoable,
                ),
                ScreenBreak(
                    title = "take a 15min pose",
                    intervalMillis = 2 * HOUR,
                    durationMillis = 15 * MIN,
                    restBreak = true,
                    lastRestMillis = NOW,
                    shape = ScreenBreakPeriod.OffScreenOnly,
                ),
            )
        s = s.copy(screenBreaks = breaks)
        val panels = SchedulerDomain.fillSchedule(s, NOW, horizonMillis = NOW + 8 * HOUR)
        val bands = panels.filter { it.screenBreak }
        assertTrue(bands.isNotEmpty(), "the poses must be materialized")
        val work = panels.filter { it.auto && it.taskId == solo }
        assertTrue(work.isNotEmpty(), "an off-screen break-doable task must fill the poses")
        var seenInsideABand = 0
        for (band in bands) {
            val opens = SchedulerDomain.screenBreakOpenStartMillis(breaks, band)
            for (w in work) {
                if (w.startEpochMillis >= band.endEpochMillis || w.endEpochMillis <= band.startEpochMillis) continue
                seenInsideABand++
                assertTrue(
                    opens != null && w.startEpochMillis >= opens,
                    "work at ${w.startEpochMillis} is inside the CLOSED head of ${band.title} (opens $opens)",
                )
            }
        }
        assertTrue(seenInsideABand > 0, "the whole point is work scheduled inside the breaks' open part")
    }

    @Test
    fun a_break_sliding_over_the_plan_cuts_only_what_it_refuses() {
        // PRD §15 / `side-dev` tests 10-11: an owed break slides right with the now-line over a plan the fill
        // made when it was elsewhere, so the display cuts the plan out of it. What it cuts is what the period
        // REFUSES: the closed head takes everything, while the open one keeps the off-screen work it accepts
        // — which is what makes the hollow part of the band worth drawing hollow.
        val screen = TaskId("screen")
        val off = TaskId("off")
        val tasks =
            mapOf(
                screen to Task(id = screen, title = "Screen work", onScreen = true),
                off to Task(id = off, title = "Stretch", minimumMinutes = 3, onScreen = false, doableDuringBreak = true),
            )
        val pose =
            ScreenBreak(
                title = "take a 5min pose and blink hard",
                intervalMillis = HOUR,
                durationMillis = 5 * MIN,
                restBreak = true,
                shape = ScreenBreakPeriod.ClosedMinuteThenBreakDoable,
            )
        val band = panel(NOW, NOW + 5 * MIN, pose.title)
        fun auto(id: String, taskId: TaskId, start: Long, end: Long) =
            TaskPanel(
                id = id, taskId = taskId, title = tasks.getValue(taskId).title,
                startEpochMillis = start, endEpochMillis = end, auto = true,
            )
        val plan =
            listOf(
                auto("s", screen, NOW - 10 * MIN, NOW + 30 * MIN),
                auto("o", off, NOW, NOW + 5 * MIN),
            )
        val out = SchedulerDomain.clipPlanForPinnedScreenBreak(plan, listOf(band), NOW, listOf(pose), tasks)

        // The screen task keeps its elapsed head and resumes past the break: the pose refuses it throughout.
        assertEquals(
            listOf(NOW - 10 * MIN to NOW, NOW + 5 * MIN to NOW + 30 * MIN),
            out.filter { it.taskId == screen }.map { it.startEpochMillis to it.endEpochMillis },
        )
        // The off-screen break-doable task loses only the closed first minute.
        assertEquals(
            listOf(NOW + MIN to NOW + 5 * MIN),
            out.filter { it.taskId == off }.map { it.startEpochMillis to it.endEpochMillis },
        )
        // A 15-min pose has no closed head at all, so the same work survives whole.
        val pose15 = pose.copy(title = "take a 15min pose", durationMillis = 15 * MIN, shape = ScreenBreakPeriod.OffScreenOnly)
        val whole =
            SchedulerDomain.clipPlanForPinnedScreenBreak(
                listOf(auto("o", off, NOW, NOW + 15 * MIN)),
                listOf(panel(NOW, NOW + 15 * MIN, pose15.title)),
                NOW, listOf(pose15), tasks,
            )
        assertEquals(listOf(NOW to NOW + 15 * MIN), whole.map { it.startEpochMillis to it.endEpochMillis })
        // With no break configuration in hand the caller gets the conservative reading: everything is cut.
        val blind = SchedulerDomain.clipPlanForPinnedScreenBreak(plan, listOf(band), NOW)
        assertTrue(blind.none { it.taskId == off }, "an unknown period must be read as accepting nobody")
    }

    // ----- the calendar carries it into the day it draws ----------------------------------------

    @Test
    fun the_open_start_reaches_the_drawn_band_as_an_hour_of_the_day() {
        // 10:00 -> a 5-minute pose whose head closes at 10:01.
        val start = Instant.parse("2026-08-20T10:00:00Z").toEpochMilliseconds()
        val record =
            CalendarRecord(
                title = "take a 5min pose and blink hard",
                range = TaskTimeRange(start, start + 5 * MIN),
                screenBreak = true,
                screenBreakOpenFromMillis = start + MIN,
            )
        val placed = recordsForDay(listOf(record), LocalDate(2026, 8, 20), tz).single()
        assertEquals(10f + 1f / 60f, placed.screenBreakOpenFromHour!!, 1e-4f)
    }

    @Test
    fun a_band_crossing_midnight_is_open_from_the_top_of_the_second_day() {
        // The head closed yesterday, so today's slice of the band is hollow from its very first pixel —
        // the clip has to answer that, not drop the open part because its instant is not on this day.
        val start = Instant.parse("2026-08-20T23:59:30Z").toEpochMilliseconds()
        val record =
            CalendarRecord(
                title = "take a 15min pose",
                range = TaskTimeRange(start, start + 15 * MIN),
                screenBreak = true,
                screenBreakOpenFromMillis = start,
            )
        val second = recordsForDay(listOf(record), LocalDate(2026, 8, 21), tz).single()
        assertEquals(0f, second.screenBreakOpenFromHour!!, 1e-6f)
        val first = recordsForDay(listOf(record), LocalDate(2026, 8, 20), tz).single()
        assertEquals(first.startHour, first.screenBreakOpenFromHour!!, 1e-6f)
    }

    @Test
    fun the_band_splits_its_drawn_height_where_the_period_opens() {
        // The split is a FRACTION of the rendered height, not an hour: a band is drawn at a minimum height
        // (a 20-second look-away is a hairline far taller than its true span), so the head has to be mapped
        // onto whatever height it ends up with.
        assertEquals(1f, screenBreakClosedFraction(null, 10f, 10.1f), 1e-6f) // closed end to end
        assertEquals(0f, screenBreakClosedFraction(10f, 10f, 10.25f), 1e-6f) // open from its first instant
        assertEquals(0.2f, screenBreakClosedFraction(10f + 1f / 60f, 10f, 10f + 5f / 60f), 1e-5f) // 1 of 5 min
        // Degenerate slices: nothing to split, and never a NaN reaching a layout.
        assertEquals(0f, screenBreakClosedFraction(10f, 10f, 10f), 1e-6f)
        // An out-of-range open hour (a clip that landed outside its own band) still yields a drawable fraction.
        assertEquals(1f, screenBreakClosedFraction(11f, 10f, 10.5f), 1e-6f)
        assertEquals(0f, screenBreakClosedFraction(9f, 10f, 10.5f), 1e-6f)
    }

    @Test
    fun a_solid_band_carries_no_open_hour() {
        val start = Instant.parse("2026-08-20T10:00:00Z").toEpochMilliseconds()
        val record =
            CalendarRecord(
                title = "look 20 feet away",
                range = TaskTimeRange(start, start + 20_000L),
                screenBreak = true,
                screenBreakOpenFromMillis = null,
            )
        assertNull(recordsForDay(listOf(record), LocalDate(2026, 8, 20), tz).single().screenBreakOpenFromHour)
    }

    /** The local-time conversion the assertions above lean on, so a wrong tz shows up as a failure here. */
    @Test
    fun the_fixture_reads_the_wall_clock_the_test_thinks_it_does() {
        val start = Instant.parse("2026-08-20T10:00:00Z").toEpochMilliseconds()
        assertEquals(10, Instant.fromEpochMilliseconds(start).toLocalDateTime(tz).hour)
    }
}
