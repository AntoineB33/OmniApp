package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.ScreenBreak

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
    private val pose5 = ScreenBreak("take a 5min pose", intervalMillis = 60 * MIN, durationMillis = 5 * MIN, restBreak = true)
    private val pose15 = ScreenBreak("take a 15min pose", intervalMillis = 120 * MIN, durationMillis = 15 * MIN, restBreak = true)
    private val lookAway = ScreenBreak("look 20 feet away", intervalMillis = 20 * MIN, durationMillis = 20_000L)

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
        val anchor = 9L * 24 * 60 * MIN
        val t5 = pose5.copy(lastRestMillis = anchor)
        val t15 = pose15.copy(lastRestMillis = anchor)
        // now = anchor + the LONGER interval, so both poses' dues have been reached.
        val now = anchor + t15.intervalMillis
        assertEquals(
            mapOf(t15.title to anchor + t15.intervalMillis),
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
    fun an_un_anchored_pose_is_not_announced_until_it_is_seeded() {
        // The freshly-emptied-account bug: `DEFAULT_SCREEN_BREAKS` load with `lastRestMillis == 0` and the cue
        // sweep can sample them in the ~90 ms before the startup derive anchors them. Their due `0 + interval`
        // is a 1970 sentinel, not a real crossed boundary, so nothing is announced — this is what stopped the
        // "take a 15min pose" cue from firing the instant the account opened.
        val now = 5L * 24 * 60 * MIN
        assertTrue(SchedulerDomain.reachedRestPoseDueByTitle(listOf(pose5, pose15), now).isEmpty())
    }

    @Test
    fun once_seeded_the_pose_announces_at_its_real_due_not_at_startup() {
        // The startup derive anchors a fresh account's poses to ~now (its whole past is inactivity). The 15-min
        // pose is then silent until its real due `anchor + 2h`, NOT at the anchor instant itself.
        val anchor = 5L * 24 * 60 * MIN // stands in for "startup", set by the derive
        val seeded = pose15.copy(lastRestMillis = anchor)
        assertTrue(SchedulerDomain.reachedRestPoseDueByTitle(listOf(seeded), anchor).isEmpty())
        assertEquals(
            mapOf(seeded.title to anchor + seeded.intervalMillis),
            SchedulerDomain.reachedRestPoseDueByTitle(listOf(seeded), anchor + seeded.intervalMillis),
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
