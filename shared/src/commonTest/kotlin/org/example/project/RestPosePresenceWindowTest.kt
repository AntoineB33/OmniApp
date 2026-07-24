package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.engine.SchedulerEngine
import org.example.project.scheduler.model.ScreenBreak

/**
 * PRD §15: the rest-pose window a device writes into its device_heartbeat row so the server cron can time the
 * phone's pause-end cue as `max(idleInstant, start) + length`.
 *
 * Regression: the window MUST be the pose the user is waiting on right now — an overdue/decoupled pose rides
 * the now-line. It was previously derived from the engine's stored `state.panels`, where the short fast-break
 * now-line break expires within seconds and is replaced by a far-future sleep-anchored occurrence; the cue was
 * then aimed hours out and the phone stayed silent. [SchedulerEngine.nextRestPoseWindowMillis] now computes the
 * window live from the [ScreenBreak] config, so a far-future occurrence can never surface.
 */
class RestPosePresenceWindowTest {
    private val MIN = 60_000L
    private val SEC = 1_000L
    private val HOUR = 60 * MIN

    @Test
    fun overdue_decoupled_pose_reports_the_now_line_window_not_a_future_sleep_break() {
        // account1 fast-break: a decoupled 5 s pose (2 h qualifying pause) whose last pause was 60 s ago, so
        // it is overdue and drags along the now-line.
        val now = 1_000_000_000L
        val pose = ScreenBreak(
            title = "take a 5min pose",
            intervalMillis = 5 * SEC,
            durationMillis = 5 * SEC,
            restBreak = true,
            lastRestMillis = now - 60 * SEC,
            pauseThresholdMillis = 2 * HOUR,
        )
        val window = SchedulerEngine.nextRestPoseWindowMillis(listOf(pose), now)
        assertNotNull(window)
        // Rides the now-line (start == now), NOT `now + interval-after-a-future-sleep`; length is the drawn 5 s.
        assertEquals(now, window.startMillis)
        assertEquals(5 * SEC, window.lengthMillis)
    }

    @Test
    fun a_not_yet_due_pose_reports_its_future_start() {
        val now = 1_000_000_000L
        val pose = ScreenBreak(
            title = "take a 5min pose",
            intervalMillis = 60 * MIN,
            durationMillis = 5 * MIN,
            restBreak = true,
            lastRestMillis = now - 20 * MIN, // due in 40 min
        )
        val window = SchedulerEngine.nextRestPoseWindowMillis(listOf(pose), now)
        assertNotNull(window)
        assertEquals(now + 40 * MIN, window.startMillis)
        assertEquals(5 * MIN, window.lengthMillis)
    }

    @Test
    fun with_none_overdue_the_earliest_starting_rest_pose_wins_and_look_aways_are_ignored() {
        val now = 1_000_000_000L
        val lookAway = ScreenBreak("look 20 feet away", intervalMillis = 20 * MIN, durationMillis = 20 * SEC)
        val pose5 = ScreenBreak(
            "take a 5min pose", intervalMillis = 60 * MIN, durationMillis = 5 * MIN, restBreak = true,
            lastRestMillis = now - 10 * MIN, // due in 50 min
        )
        val pose15 = ScreenBreak(
            "take a 15min pose", intervalMillis = 3 * HOUR, durationMillis = 15 * MIN, restBreak = true,
            lastRestMillis = now - 10 * MIN, // due in 2h50
        )
        val window = SchedulerEngine.nextRestPoseWindowMillis(listOf(lookAway, pose5, pose15), now)
        assertNotNull(window)
        // Neither pose is overdue, so the soonest-starting (5-min) wins; the look-away is never reported.
        assertEquals(now + 50 * MIN, window.startMillis)
        assertEquals(5 * MIN, window.lengthMillis)
    }

    @Test
    fun when_both_poses_are_overdue_the_longest_one_governs() {
        // PRD §15: the user walked away long enough to be overdue for BOTH the 5-min and 15-min poses (both ride
        // the now-line). Resting 15 min discharges the 5-min too, so the reported window is the 15-min one — the
        // server then times the cue to `idleInstant + 15 min`, not 5.
        val now = 1_000_000_000L
        val pose5 = ScreenBreak(
            "take a 5min pose", intervalMillis = 60 * MIN, durationMillis = 5 * MIN, restBreak = true,
            lastRestMillis = now - 2 * HOUR, // due 1h ago
        )
        val pose15 = ScreenBreak(
            "take a 15min pose", intervalMillis = 2 * HOUR, durationMillis = 15 * MIN, restBreak = true,
            lastRestMillis = now - 3 * HOUR, // due 1h ago
        )
        // List order deliberately puts the SHORTER pose first, to prove selection is by length, not order.
        val window = SchedulerEngine.nextRestPoseWindowMillis(listOf(pose5, pose15), now)
        assertNotNull(window)
        assertEquals(now, window.startMillis) // both at the now-line
        assertEquals(15 * MIN, window.lengthMillis) // the longest overdue governs
    }

    @Test
    fun the_published_window_names_the_break_type_the_server_configures() {
        // PRD §15 (migration 20260724000000): the presence row carries the break KIND, not just its length —
        // that is what lets the server look the configured length + vocal message up in `break_config`. The key
        // survives the debug fast-break retiming, which rewrites the durations but never the identity.
        val now = 1_000_000_000L
        val breaks = SchedulerDomain.DEFAULT_SCREEN_BREAKS.map {
            if (it.key == SchedulerDomain.FIFTEEN_MIN_BREAK_KEY) it.copy(lastRestMillis = now - 3 * HOUR) else it
        }
        val window = SchedulerEngine.nextRestPoseWindowMillis(breaks, now)
        assertNotNull(window)
        assertEquals(SchedulerDomain.FIFTEEN_MIN_BREAK_KEY, window.key)

        // An ad-hoc break with no key publishes none, and the server then falls back to its own default.
        val adHoc = ScreenBreak("custom pose", intervalMillis = 30 * MIN, durationMillis = 3 * MIN, restBreak = true)
        assertEquals("", SchedulerEngine.nextRestPoseWindowMillis(listOf(adHoc), now)!!.key)
    }

    @Test
    fun no_rest_pose_configured_reports_nothing() {
        val lookAway = ScreenBreak("look 20 feet away", intervalMillis = 20 * MIN, durationMillis = 20 * SEC)
        assertNull(SchedulerEngine.nextRestPoseWindowMillis(listOf(lookAway), now = 1_000_000_000L))
    }
}
