package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.ScreenBreak

/**
 * CLAUDE.md "hot-path display derivations must scale with the SCREEN, not with total history": the calendar
 * draws a future week's screen-break markers with [SchedulerDomain.screenBreakPanelsInWindow], reconstructing only
 * that window from the fixed grid instead of projecting every occurrence between `now` and the week. These
 * pin the reported freeze — opening a distant day under a shrunk 5-min-break interval projected tens of thousands
 * of markers, and (before the O(n) fix) ran them through an O(n²) placement scan. The last test pins the
 * placement itself as linear so a dense projection stays cheap rather than needing an occurrence cap.
 */
class ScreenBreakWindowTest {
    private val MIN = 60_000L
    private val SEC = 1_000L
    private val WEEK = 7L * 24 * 60 * MIN
    private val lookAway = ScreenBreak("look 20 feet away", intervalMillis = 20 * MIN, durationMillis = 20 * SEC)
    private val pose5 =
        ScreenBreak("take a 5min pose", intervalMillis = 60 * MIN, durationMillis = 5 * MIN, restBreak = true)

    @Test
    fun window_returns_only_occurrences_inside_it() {
        val la = lookAway.copy(lastRestMillis = 0) // grid: 20, 40, 60, 80… min
        val starts = SchedulerDomain.screenBreakPanelsInWindow(listOf(la), 35 * MIN, 105 * MIN)
            .map { it.startEpochMillis }.sorted()
        assertEquals(listOf(40 * MIN, 60 * MIN, 80 * MIN, 100 * MIN), starts)
    }

    @Test
    fun cost_is_bounded_by_the_window_not_its_distance_from_the_grid_origin() {
        // The freeze: a forward projection from `now` costs O(distance). The windowed reconstruction must
        // cost only O(window), so the same one-week window yields the same number of markers whether it sits
        // one week out or a hundred weeks out.
        val tasks = listOf(lookAway.copy(lastRestMillis = 0), pose5.copy(lastRestMillis = 0))
        val near = SchedulerDomain.screenBreakPanelsInWindow(tasks, WEEK, 2 * WEEK).size
        val far = SchedulerDomain.screenBreakPanelsInWindow(tasks, 100 * WEEK, 101 * WEEK).size
        assertTrue(near > 0)
        assertEquals(near, far)
    }

    @Test
    fun window_reproduces_the_forward_projections_occurrences_over_the_same_span() {
        // Over a span the forward projection reaches, the windowed reconstruction draws the same markers —
        // it is the same grid engine, only seeded from the past rather than walked from `now`.
        val tasks = listOf(lookAway.copy(lastRestMillis = 0), pose5.copy(lastRestMillis = 0))
        val forward = SchedulerDomain.screenBreakPanels(tasks, nowMillis = 0, horizonMillis = 6 * 60 * MIN)
            .map { it.title to it.startEpochMillis }.sortedBy { it.second }
        val windowed = SchedulerDomain.screenBreakPanelsInWindow(tasks, 0, 6 * 60 * MIN)
            .map { it.title to it.startEpochMillis }.sortedBy { it.second }
        assertEquals(forward, windowed)
    }

    @Test
    fun window_unlike_occurrences_between_keeps_every_look_away_no_dragging_pose_shadow() {
        // An overdue-unserved pose casts a cue "shadow" that drops later look-aways from
        // [screenBreakOccurrencesBetween]; the display window must NOT — it shows every occurrence the grid
        // places. Pose overdue since its due 60 min ago (lastRest anchored, due already passed).
        val la = lookAway.copy(lastRestMillis = 0)
        val overduePose = pose5.copy(lastRestMillis = 0) // due at 60 min
        val window = SchedulerDomain.screenBreakPanelsInWindow(listOf(la, overduePose), 60 * MIN, 90 * MIN)
        val shadowed = SchedulerDomain.screenBreakOccurrencesBetween(listOf(la, overduePose), 60 * MIN, 90 * MIN)
        assertTrue(window.count { it.title == la.title } >= shadowed.count { it.title == la.title })
    }

    @Test
    fun dense_seconds_interval_pose_projects_linearly_without_a_cap() {
        // Fast-break mode shrinks the 5-min pose to seconds; over a multi-hour fill horizon that is thousands
        // of occurrences. The O(n) placement projects them all — well past the old 2000-occurrence cap that
        // was needed while the scan was O(n²) — with an exact (duration + interval) cadence and no clipping.
        val fast = pose5.copy(intervalMillis = 5 * SEC, durationMillis = 5 * SEC, lastRestMillis = 0)
        val horizon = 6 * 60 * MIN // 6 hours
        val starts = SchedulerDomain.screenBreakPanels(listOf(fast), nowMillis = 0, horizonMillis = horizon)
            .map { it.startEpochMillis }.sorted()
        assertTrue(starts.size > 2_000, "expected >2000 dense occurrences, got ${starts.size}")
        assertTrue(starts.last() <= horizon)
        // Each pose resumes (duration + interval) = 10 s after the previous start — nothing is clipped.
        assertEquals(setOf(10 * SEC), starts.zipWithNext { a, b -> b - a }.toSet())
    }
}
