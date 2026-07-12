package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.SideTask

/**
 * PRD §15 / CLAUDE.md rule: the rest-pose ("take a 5/15-min break") notification must be **mathematically
 * accurate** — a pure function of which due instant the now-line crossed, never of how a sweep/heartbeat
 * happens to align with the calendar. [SchedulerDomain.reachedRestPoseDueByTitle] is that pure rule; the
 * engine loop only dedupes its output on the returned (stable) due. These pin the two anomalies that keep
 * recurring: a fast/leaping clock must NOT skip the reach, and an overdue pose sliding along the now-line
 * must NOT keep re-announcing (the due is stable, so the caller's dedupe holds).
 */
class RestPoseNotificationRuleTest {
    private val MIN = 60_000L
    private val pose5 = SideTask("take a 5min pose", intervalMillis = 60 * MIN, durationMillis = 5 * MIN, restBreak = true)
    private val pose15 = SideTask("take a 15min pose", intervalMillis = 120 * MIN, durationMillis = 15 * MIN, restBreak = true)
    private val lookAway = SideTask("look 20 feet away", intervalMillis = 20 * MIN, durationMillis = 20_000L)

    @Test
    fun a_pose_whose_due_the_now_line_has_reached_is_announced_with_its_stable_due() {
        val task = pose5.copy(lastRestMillis = 1_000 * MIN)
        val due = task.lastRestMillis + task.intervalMillis
        assertEquals(mapOf(task.title to due), SchedulerDomain.reachedRestPoseDueByTitle(listOf(task), nowMillis = due))
    }

    @Test
    fun a_pose_not_yet_due_is_not_announced() {
        val task = pose5.copy(lastRestMillis = 1_000 * MIN)
        val due = task.lastRestMillis + task.intervalMillis
        assertEquals(emptyMap(), SchedulerDomain.reachedRestPoseDueByTitle(listOf(task), nowMillis = due - 1))
    }

    @Test
    fun a_clock_that_leaps_far_past_the_due_still_announces_it_once() {
        // The core anomaly: at 300× / a time-link re-anchor, the now-line jumps clean over the whole pose
        // window in one frame. `now >= due` is a LEVEL condition, so the reach is still reported however far
        // the jump overshot — and the due is unchanged, so the engine's per-due dedupe fires it exactly once.
        val task = pose5.copy(lastRestMillis = 1_000 * MIN)
        val due = task.lastRestMillis + task.intervalMillis
        val leaptWayPast = due + 500 * MIN // far beyond the 5-min pose window
        val a = SchedulerDomain.reachedRestPoseDueByTitle(listOf(task), nowMillis = leaptWayPast)
        val b = SchedulerDomain.reachedRestPoseDueByTitle(listOf(task), nowMillis = leaptWayPast + 999)
        assertEquals(mapOf(task.title to due), a)
        assertEquals(a, b) // identical due across advancing nows ⇒ the caller announces once, not per tick
    }

    @Test
    fun both_poses_reached_the_longer_absorbs_the_shorter_5_15_merge() {
        // Both due at the same now-line: only the 15-min pose is announced (it absorbs the coincident 5-min).
        val t5 = pose5.copy(lastRestMillis = 0)
        val t15 = pose15.copy(lastRestMillis = 0)
        val now = 10L * 24 * 60 * MIN
        assertEquals(
            mapOf(t15.title to t15.intervalMillis),
            SchedulerDomain.reachedRestPoseDueByTitle(listOf(t5, t15), now),
        )
    }

    @Test
    fun the_shorter_pose_alone_is_announced_when_the_longer_is_not_yet_due() {
        val now = 1_000 * MIN
        val t5 = pose5.copy(lastRestMillis = now - 60 * MIN) // due exactly now
        val t15 = pose15.copy(lastRestMillis = now) // due 2h from now — not reached
        assertEquals(
            mapOf(t5.title to now),
            SchedulerDomain.reachedRestPoseDueByTitle(listOf(t5, t15), now),
        )
    }

    @Test
    fun a_never_rested_pose_is_due_immediately() {
        val now = 5L * 24 * 60 * MIN
        assertEquals(
            mapOf(pose5.title to pose5.intervalMillis),
            SchedulerDomain.reachedRestPoseDueByTitle(listOf(pose5), now),
        )
    }

    @Test
    fun the_look_away_cadence_is_never_a_rest_pose_announcement() {
        val now = 1_000 * MIN
        assertTrue(SchedulerDomain.reachedRestPoseDueByTitle(listOf(lookAway.copy(lastRestMillis = 0)), now).isEmpty())
    }

    @Test
    fun invalid_rows_are_skipped() {
        val blank = pose5.copy(title = "  ", lastRestMillis = 0)
        val zeroInterval = pose15.copy(intervalMillis = 0, lastRestMillis = 0)
        assertTrue(SchedulerDomain.reachedRestPoseDueByTitle(listOf(blank, zeroInterval), nowMillis = 1_000 * MIN).isEmpty())
    }
}
