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
        // Every break recurs an interval after it ENDS, so the grid is 20:00, 40:20, 60:40, 81:00, 101:20…
        val la = lookAway.copy(lastRestMillis = 0)
        val cycle = 20 * MIN + 20 * SEC
        val starts = SchedulerDomain.screenBreakPanelsInWindow(listOf(la), 35 * MIN, 105 * MIN)
            .map { it.startEpochMillis }.sorted()
        assertEquals((1..4).map { 20 * MIN + it * cycle }, starts)
    }

    @Test
    fun the_past_side_of_the_calendar_draws_the_breaks_that_were_taken_and_not_the_owed_one() {
        // PRD §15: a break stays drawn where it happened instead of vanishing as the now-line passes it. What
        // separates "happened" from "still owed" is the anchor — the end of the last rest that served the break
        // — so the pending occurrence is left to the forward projection, which slides it to the now-line. Drawn
        // by both, an owed break would appear twice at once: at its fixed due AND at the now-line.
        // Only a break the app CONDUCTED is drawn, which is only ever the 20-s look-away.
        val now = 10 * 60 * MIN
        val cycle = 20 * MIN + 20 * SEC
        // Anchored 25 min back: the look-away it conducted ended there, and the ones before it a cycle apart.
        val la = lookAway.copy(lastRestMillis = now - 25 * MIN)
        val taken = SchedulerDomain.takenScreenBreakPanels(listOf(la), now - 2 * 60 * MIN, now - 1)
        assertTrue(taken.all { it.screenBreak && it.taskId == null })
        // Each is the break's own 20-s span, the latest ending exactly at the anchor, one cycle apart.
        assertEquals(20 * SEC, taken.last().endEpochMillis - taken.last().startEpochMillis)
        assertEquals(la.lastRestMillis, taken.last().endEpochMillis)
        assertEquals(listOf(cycle), taken.map { it.startEpochMillis }.zipWithNext { a, b -> b - a }.distinct())
        // The OWED occurrence — due at lastRest + 20 min, already past — is not drawn here: it slides to the
        // now-line and the forward projection draws it, so drawing it here too would show it twice at once.
        assertTrue(taken.none { it.startEpochMillis == la.lastRestMillis + 20 * MIN })
        assertTrue(
            SchedulerDomain.screenBreakPanels(listOf(la), now).any { it.startEpochMillis == now },
        )

        // A POSE draws NOTHING in the past, however well anchored. Nothing about a 5-/15-min pose ever
        // happens in the app — it is only recognized after the fact from a pause, and that pause is already
        // drawn as itself (the device layers, the no-screen period, the Inactivity band). A pose band there
        // would restate the same fact as a second object with the wrong (nominal) extent.
        val pose = pose5.copy(lastRestMillis = now - 25 * MIN)
        assertTrue(SchedulerDomain.takenScreenBreakPanels(listOf(pose), now - 2 * 60 * MIN, now - 1).isEmpty())
        // …while the occurrence still AHEAD of the now-line is the forward projection's to draw, as ever.
        assertTrue(
            SchedulerDomain.screenBreakPanels(listOf(pose), now)
                .any { it.startEpochMillis == pose.lastRestMillis + 60 * MIN },
        )

        // An unanchored break has taken nothing.
        assertTrue(SchedulerDomain.takenScreenBreakPanels(listOf(lookAway), 0, now).isEmpty())

        // Cost follows the window, not its distance from the anchor: a week-long window a hundred weeks back
        // holds the same number of markers as one a week back (it holds none of them at all, in fact — but the
        // point is that neither call walks the cycles in between).
        assertEquals(
            SchedulerDomain.takenScreenBreakPanels(listOf(la), now - 2 * WEEK, now - WEEK).size,
            SchedulerDomain.takenScreenBreakPanels(listOf(la), now - 101 * WEEK, now - 100 * WEEK).size,
        )
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
    fun dense_seconds_interval_pose_is_bounded_not_flooded_over_a_window() {
        // Fast-break mode shrinks the 5-min pose to seconds. The placement is O(n) (open-pause tracking, not an
        // O(n²) scan), but the project also *deliberately bounds* a dense sub-minute pose so it never floods
        // `state.panels`: a COUPLED dense pose (qualifying pause == the 5 s drawn length, the real-time account2
        // shape) is held to one at a time by the dense-interval cap
        // ([SchedulerDomain] `DENSE_SCREEN_BREAK_INTERVAL_FLOOR_MILLIS`). So the windowed reconstruction over a
        // multi-hour span yields a bounded count, not thousands — the fix for "lots of 5-min breaks in a day".
        val fast = pose5.copy(intervalMillis = 5 * SEC, durationMillis = 5 * SEC, lastRestMillis = 0)
        val windowed = SchedulerDomain.screenBreakPanelsInWindow(listOf(fast), 0, 6 * 60 * MIN)
        assertEquals(1, windowed.size)

        // A DECOUPLED dense pose (qualifying pause 2 h > the 5 s drawn length, the account1 fast-break shape)
        // does not grid-recur at all — it appears once per real qualifying pause, so the forward projection
        // likewise emits exactly one, anchored an interval after its lastRest.
        val decoupled = fast.copy(pauseThresholdMillis = 2 * 60 * 60 * SEC, lastRestMillis = 0)
        val forward = SchedulerDomain.screenBreakPanels(listOf(decoupled), nowMillis = 0, horizonMillis = 6 * 60 * MIN)
        assertEquals(1, forward.size)
        assertEquals(5 * SEC, forward.single().startEpochMillis)
    }
}
