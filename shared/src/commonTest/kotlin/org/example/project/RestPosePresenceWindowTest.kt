package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.engine.SchedulerEngine
import org.example.project.scheduler.model.ScreenBreak

/**
 * PRD §15: **when each of the two screen breaks next comes due** — the whole content of the account's
 * `device_break` row, from which the server decides whether the account went idle with a break owed and times
 * the phone's pause-end cue as `idleInstant + length`.
 *
 * Since migration 20260726000000 the published instant is the pose's mathematical **due** time
 * (`lastRest + interval`), not its drawn start (`maxOf(due, now)`). That is what lets the row be written
 * event-driven — an overdue pose's drawn start rides the now-line and changes at every sample — and it puts the
 * server on the same boundary the client's own rest-pose cue keys on. Migration 20260728000000 then made the row
 * account-keyed and published BOTH dues, moving the "longest overdue governs" selection to the server.
 *
 * Regression: the dues MUST come from the live [ScreenBreak] config. They were previously derived from the
 * engine's stored `state.panels`, where the short fast-break now-line break expires within seconds and is
 * replaced by a far-future sleep-anchored occurrence; the cue was then aimed hours out and the phone stayed
 * silent. [SchedulerEngine.restPoseDueMillisByKey] never sees a placed panel, so that cannot recur.
 */
class RestPosePresenceWindowTest {
    private val MIN = 60_000L
    private val SEC = 1_000L
    private val HOUR = 60 * MIN

    private fun pose5(lastRest: Long, interval: Long = 60 * MIN, duration: Long = 5 * MIN) = ScreenBreak(
        title = "take a 5min pose", intervalMillis = interval, durationMillis = duration,
        restBreak = true, lastRestMillis = lastRest, key = SchedulerDomain.FIVE_MIN_BREAK_KEY,
    )

    private fun pose15(lastRest: Long, interval: Long = 3 * HOUR) = ScreenBreak(
        title = "take a 15min pose", intervalMillis = interval, durationMillis = 15 * MIN,
        restBreak = true, lastRestMillis = lastRest, key = SchedulerDomain.FIFTEEN_MIN_BREAK_KEY,
    )

    @Test
    fun both_screen_breaks_are_published_each_under_its_own_key() {
        // The spec's row: the account plus the scheduled time of apparition of BOTH breaks. No selection is made
        // client-side any more — the server picks the longest one that was already overdue at the last beat.
        val now = 1_000_000_000L
        val dues = SchedulerEngine.restPoseDueMillisByKey(
            listOf(pose5(lastRest = now - 10 * MIN), pose15(lastRest = now - 10 * MIN)),
        )
        assertEquals(
            mapOf(
                SchedulerDomain.FIVE_MIN_BREAK_KEY to now + 50 * MIN,
                SchedulerDomain.FIFTEEN_MIN_BREAK_KEY to now + 2 * HOUR + 50 * MIN,
            ),
            dues,
        )
    }

    @Test
    fun overdue_decoupled_pose_reports_its_past_due_instant_not_a_future_sleep_break() {
        // account1 fast-break: a decoupled 5 s pose (2 h qualifying pause) whose last pause was 60 s ago, so it
        // is overdue — it came due 55 s ago and has been waiting ever since.
        val now = 1_000_000_000L
        val pose = pose5(lastRest = now - 60 * SEC, interval = 5 * SEC, duration = 5 * SEC)
            .copy(pauseThresholdMillis = 2 * HOUR)
        val dues = SchedulerEngine.restPoseDueMillisByKey(listOf(pose))
        // The DUE instant, in the past — NOT the now-line and NOT `now + interval-after-a-future-sleep`.
        assertEquals(now - 55 * SEC, dues[SchedulerDomain.FIVE_MIN_BREAK_KEY])
    }

    @Test
    fun an_overdue_poses_due_instant_does_not_move_as_the_now_line_advances() {
        // The property the event-driven `device_break` write rests on: while the pose is unchanged the published
        // value is CONSTANT, so a device sitting at its desk makes no requests. The drawn start (`maxOf(due,
        // now)`) would instead advance with every sample, which is why it used to have to ride the `t_a` beat.
        // The function does not even take `now` — the published value cannot depend on when it is sampled.
        val now = 1_000_000_000L
        val pose = pose5(lastRest = now - 2 * HOUR) // due 1 h ago
        assertEquals(now - 1 * HOUR, SchedulerEngine.restPoseDueMillisByKey(listOf(pose))[SchedulerDomain.FIVE_MIN_BREAK_KEY])
    }

    @Test
    fun look_aways_and_unkeyed_ad_hoc_breaks_are_never_published() {
        // The 20 s look-away has its own local cue and is not a rest break; an ad-hoc pose with no key has no
        // `break_config` entry the server could resolve a length or a vocal message from.
        val now = 1_000_000_000L
        val lookAway = ScreenBreak(
            "look 20 feet away", intervalMillis = 20 * MIN, durationMillis = 20 * SEC,
            lastRestMillis = now - 10 * MIN, key = SchedulerDomain.LOOK_AWAY_KEY,
        )
        val adHoc = ScreenBreak(
            "custom pose", intervalMillis = 30 * MIN, durationMillis = 3 * MIN,
            restBreak = true, lastRestMillis = now - 10 * MIN,
        )
        assertEquals(emptyMap<String, Long>(), SchedulerEngine.restPoseDueMillisByKey(listOf(lookAway, adHoc)))
    }

    @Test
    fun an_unanchored_pose_is_not_published_so_a_fresh_account_is_never_owed_a_cue() {
        // A pose that has never been anchored carries `lastRestMillis == 0`, so `lastRest + interval` is an
        // instant in 1970 — permanently "overdue". Publishing it would tell the server the account walked away
        // with a break already owed, and a freshly emptied account that locked its phone would be spoken a
        // pause-end cue it never earned. Same gate the local cue uses (`reachedRestPoseDueByTitle`).
        val defaults = SchedulerDomain.DEFAULT_SCREEN_BREAKS
        assertTrue(defaults.any { it.restBreak }, "the defaults do configure rest poses")
        assertTrue(defaults.all { it.lastRestMillis == 0L }, "…and they load unanchored")
        assertEquals(emptyMap<String, Long>(), SchedulerEngine.restPoseDueMillisByKey(defaults))

        // Once the startup derive anchors one, that one — and only that one — is published.
        val now = 1_000_000_000L
        val anchored = defaults.map {
            if (it.key == SchedulerDomain.FIVE_MIN_BREAK_KEY) it.copy(lastRestMillis = now - 3 * HOUR) else it
        }
        val dues = SchedulerEngine.restPoseDueMillisByKey(anchored)
        assertEquals(setOf(SchedulerDomain.FIVE_MIN_BREAK_KEY), dues.keys)
        assertFalse(SchedulerDomain.FIFTEEN_MIN_BREAK_KEY in dues)
    }

    @Test
    fun no_rest_pose_configured_publishes_nothing() {
        val lookAway = ScreenBreak(
            "look 20 feet away", intervalMillis = 20 * MIN, durationMillis = 20 * SEC,
            lastRestMillis = 1L, key = SchedulerDomain.LOOK_AWAY_KEY,
        )
        assertEquals(emptyMap<String, Long>(), SchedulerEngine.restPoseDueMillisByKey(listOf(lookAway)))
    }
}
