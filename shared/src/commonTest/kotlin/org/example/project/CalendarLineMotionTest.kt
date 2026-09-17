package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import org.example.project.scheduler.domain.DynamicPeriods
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.TaskPanel
import org.example.project.scheduler.model.TaskTimeRange
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState
import org.example.project.ui.CalendarRecord
import org.example.project.ui.advancedAlongLine
import org.example.project.ui.displayBoundsOf
import org.example.project.ui.lineCompositionMillis
import org.example.project.ui.recordsForDay
import org.example.project.ui.withLineMotion

/**
 * ADR 0009 § *Everything that follows the line moves continuously*: **the set of rules is read once, and the
 * motion of everything it draws is known from that reading until the next instant the rules name.**
 *
 * `docs/scheduler_requirements.md` § *$now line$*: in mode 1 the line reaching a pose *"would continuously
 * delay that period (while creating task panels in its passing)"* — so the pose, the panel growing behind it
 * and the panel resuming after it all move WITH the line. The calendar used to draw them as still pictures
 * re-derived once per pixel, so they stepped while the line beside them glided and a dragged pose sat visibly
 * behind the line that is supposed to be pushing it. Now every edge is read as fixed or following, and drawn
 * by that reading.
 *
 * The contract this pins is the one that makes that honest: **extrapolating the reading must give exactly
 * what reading the rules again would**, for every instant before the next boundary the reading itself
 * predicts. If it did not, the calendar would be drawing a motion the rules do not describe.
 */
class CalendarLineMotionTest {

    private val MIN = 60_000L
    private val HOUR = 60 * MIN
    private val NOW = 1_700_000_000_000L
    private val tz = TimeZone.UTC

    private fun record(start: Long, end: Long, title: String = "A") =
        CalendarRecord(title = title, range = TaskTimeRange(start, end))

    // ----- reading the motion ---------------------------------------------------------------------

    @Test
    fun an_edge_that_moved_by_the_probe_follows_the_line_and_one_that_did_not_is_fixed() {
        val at = listOf(record(NOW - HOUR, NOW - 1), record(NOW + 1, NOW + 5 * MIN + 1), record(NOW, NOW + HOUR))
        val ahead = listOf(record(NOW - HOUR, NOW), record(NOW + 2, NOW + 5 * MIN + 2), record(NOW, NOW + HOUR))
        val read = withLineMotion(at, ahead)
        assertFalse(read[0].startFollowsLine, "a panel's elapsed start stays where it is")
        assertTrue(read[0].endFollowsLine, "the panel behind the line grows with it")
        assertTrue(read[1].startFollowsLine && read[1].endFollowsLine, "a dragged pose moves rigidly")
        assertFalse(read[2].startFollowsLine || read[2].endFollowsLine, "a still panel is still")
        assertEquals(at.map { it.range }, read.map { it.range }, "reading the motion moves nothing")
    }

    @Test
    fun a_dragged_pose_is_matched_although_its_id_is_named_after_its_own_start() {
        val at = listOf(record(NOW + 1, NOW + 5 * MIN + 1).copy(entryId = "side/1/${NOW + 1}/dragged"))
        val ahead = listOf(record(NOW + 2, NOW + 5 * MIN + 2).copy(entryId = "side/1/${NOW + 2}/dragged"))
        assertTrue(withLineMotion(at, ahead).single().startFollowsLine)
    }

    @Test
    fun a_boundary_between_the_two_readings_leaves_the_records_still() {
        // A different count, a different record at a position, or an edge moving by anything but the probe
        // means the two readings straddle a boundary: nothing is guessed, the resample reads it again.
        val at = listOf(record(NOW, NOW + HOUR), record(NOW + HOUR, NOW + 2 * HOUR))
        assertEquals(at, withLineMotion(at, at.take(1)), "a different count")
        assertEquals(at, withLineMotion(at, listOf(at[0], at[1].copy(title = "B"))), "a different record")
        val jumped = listOf(at[0].copy(range = TaskTimeRange(NOW, NOW + HOUR + 7)), at[1])
        assertEquals(at, withLineMotion(at, jumped), "an edge that jumped rather than followed")
    }

    @Test
    fun a_placed_record_follows_the_line_only_on_the_day_its_edge_falls_on() {
        val day = LocalDate(2023, 11, 14)
        val dayStart = LocalDateTime(2023, 11, 14, 0, 0).toInstant(tz).toEpochMilliseconds()
        // A band from the previous evening to 10:00 whose END follows the line.
        val band = record(dayStart - 2 * HOUR, dayStart + 10 * HOUR).copy(endFollowsLine = true, startFollowsLine = true)
        val placed = recordsForDay(listOf(band), day, tz).single()
        assertFalse(placed.startFollowsLine, "its start is clipped to this day's midnight, which does not move")
        assertTrue(placed.endFollowsLine)
        val later = placed.advancedAlongLine(30 * MIN)
        assertEquals(0f, later.startHour)
        assertEquals(10.5f, later.endHour, 1e-4f)
        assertEquals(placed.fullEndMillis + 30 * MIN, later.fullEndMillis)
        assertEquals(placed.fullStartMillis, later.fullStartMillis)
    }

    @Test
    fun the_composition_reads_the_line_once_per_pixel_of_travel() {
        val perPixel = 600L
        assertEquals(NOW, lineCompositionMillis(NOW, NOW + 599, perPixel))
        assertEquals(NOW + 600, lineCompositionMillis(NOW, NOW + 600, perPixel))
        assertEquals(NOW + 1_200, lineCompositionMillis(NOW, NOW + 1_799, perPixel))
        assertEquals(NOW, lineCompositionMillis(NOW, NOW - 50, perPixel), "never behind the reading")
    }

    // ----- the resample boundaries a moving edge adds ---------------------------------------------

    @Test
    fun a_moving_edge_meeting_a_fixed_one_is_a_boundary() {
        // A pose dragged at `(t_p, t_p + 5 min]` reaches a period starting at 10:20 when the line is at 10:15.
        val now = 1_700_000_400_000L
        val fixed = listOf(now + 20 * MIN)
        val offsets = listOf(1L, 5 * MIN + 1)
        assertEquals(
            15 * MIN - 1,
            SchedulerDomain.displayResampleDelayMillis(fixed, now, tz, millisPerPixel = 1L, lineOffsets = offsets),
        )
    }

    @Test
    fun a_moving_edge_is_never_a_busy_loop() {
        // The pose's own start is `t_p + 1`: as a FOLLOWING edge it is not a boundary a millisecond away and
        // not a pin either — with its motion read, nothing asks the display to re-derive it per pixel.
        val now = 1_700_000_400_000L
        val delay =
            SchedulerDomain.displayResampleDelayMillis(
                emptyList(), now, tz, millisPerPixel = 600L, lineOffsets = listOf(1L, 5 * MIN + 1),
            )
        assertTrue(delay > 600L, "a read motion is drawn, not resampled: $delay")
    }

    @Test
    fun a_moving_edge_crossing_midnight_is_a_boundary() {
        val midnight = 1_700_006_400_000L // 00:00 UTC, 2023-11-15
        val now = midnight - 10 * MIN
        // The line is at 23:50; the pose it drags ends 5 min after it, so it reaches tomorrow's row at 23:55.
        assertEquals(
            5 * MIN - 1,
            SchedulerDomain.displayResampleDelayMillis(
                emptyList(), now, tz, millisPerPixel = 1L, lineOffsets = listOf(1L, 5 * MIN + 1),
            ),
        )
    }

    // ----- the contract, on the real rules --------------------------------------------------------

    /** Two ordinary on-screen tasks and the three production breaks — the fixture DraggedPoseNoIdlingTest uses. */
    private fun account(): SchedulerState {
        var s = SchedulerState.empty()
        listOf("Alpha", "Beta").forEachIndexed { i, title ->
            val row = s.lists[s.rootListId]!!.cellIds.let { it.getOrNull(i) ?: it.last() }
            s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(row, title))
        }
        return s.copy(screenBreaks = SchedulerDomain.DEFAULT_SCREEN_BREAKS)
    }

    /**
     * The calendar's reading of the rules at [now] over a plan made ONCE at [NOW] (time passing never re-plans):
     * the breaks projected at the line, and the plan clipped around the one the line is dragging — the two
     * calls `App.kt`'s derivation makes for exactly these records.
     */
    private fun readRules(state: SchedulerState, plan: List<TaskPanel>, now: Long): List<CalendarRecord> {
        val work = plan.filterNot { it.screenBreak }
        val breaks =
            SchedulerDomain.screenBreakPanels(
                screenBreaks = state.screenBreaks,
                nowMillis = now,
                horizonMillis = NOW + 3 * HOUR,
                basePeriods = SchedulerDomain.restrictivePeriodsOf(work),
                tasks = SchedulerDomain.planTasksOf(state, now),
                mode = DynamicPeriods.MODE_AT_SCREEN,
            ).filter { it.startEpochMillis >= now }
        val drawn = SchedulerDomain.clipPlanForPinnedScreenBreak(work, breaks, now, state.screenBreaks, state.tasks)
        return drawn.map { CalendarRecord(it.title, TaskTimeRange(it.startEpochMillis, it.endEpochMillis), entryId = it.id) } +
            breaks.map {
                CalendarRecord(it.title, TaskTimeRange(it.startEpochMillis, it.endEpochMillis), entryId = it.id, screenBreak = true)
            }
    }

    private fun extrapolated(records: List<CalendarRecord>, elapsed: Long): List<TaskTimeRange> =
        records.map {
            TaskTimeRange(
                it.range.startEpochMillis + if (it.startFollowsLine) elapsed else 0L,
                it.range.endEpochMillis + if (it.endFollowsLine) elapsed else 0L,
            )
        }

    @Test
    fun extrapolating_one_reading_draws_what_reading_the_rules_again_would_until_the_next_boundary() {
        val state = account()
        val plan = SchedulerDomain.fillSchedule(state, NOW, horizonMillis = NOW + 3 * HOUR)
        val t0 = NOW + 2 * MIN
        val read = withLineMotion(readRules(state, plan, t0), readRules(state, plan, t0 + 1))

        val pose = read.firstOrNull { it.screenBreak && it.startFollowsLine }
        assertTrue(pose != null, "the case needs a pose the line is dragging: ${read.map { it.title to it.range }}")
        assertTrue(pose.endFollowsLine, "a dragged pose moves rigidly")
        assertTrue(read.any { !it.screenBreak && it.endFollowsLine }, "the panel behind the line grows with it")

        val (fixed, offsets) = displayBoundsOf(read, t0)
        val boundary =
            SchedulerDomain.displayResampleDelayMillis(fixed, t0, tz, millisPerPixel = 1L, lineOffsets = offsets)
        val checked = listOf(1L, 1_000L, 37_123L, boundary / 2, boundary - 1).filter { it in 1 until boundary }
        assertTrue(checked.size >= 3, "the boundary must leave room to move: $boundary ms")
        for (elapsed in checked) {
            val again = readRules(state, plan, t0 + elapsed)
            assertEquals(read.size, again.size, "no record appears or vanishes before the boundary (+$elapsed ms)")
            assertEquals(
                again.map { it.range },
                extrapolated(read, elapsed),
                "the reading's motion must be the rules' own, $elapsed ms on",
            )
        }
    }
}
