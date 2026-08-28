package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.PeriodKinds
import org.example.project.scheduler.domain.RestrictivePeriod
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.engine.SchedulerEngine
import org.example.project.scheduler.model.ScreenBreak

/**
 * PRD §15: **where each of the two poses' next period is placed** — the whole content of the account's
 * `device_break` row, from which the server decides whether the account went idle with a break owed and times
 * the phone's pause-end cue as `idleInstant + length`.
 *
 * The published instant used to be the pose's anchored due `lastRest + interval`, and it had to be: an owed
 * break *slid* along the now-line, so its drawn start changed at every sample and could not be written
 * event-driven. Nothing slides now (ADR 0003) — the recurrence bars pin every break to a fixed instant — so
 * the server and the client key on ONE instant, [SchedulerDomain.nextScreenBreakStartMillis], which is also
 * the instant the calendar draws and the local cue sweep fires on.
 *
 * Regression this still guards: the dues must come from the bars over the LIVE environment, never from the
 * engine's stored `state.panels` (a frozen snapshot whose short fast-break now-line break expires within
 * seconds and is replaced by a far-future occurrence, aiming the cue hours out).
 */
class RestPosePresenceWindowTest {
    private val MIN = 60_000L
    private val SEC = 1_000L
    private val HOUR = 60 * MIN
    private val NOW = 1_000_000_000_000L

    private fun pose5(interval: Long = 60 * MIN, duration: Long = 5 * MIN) = ScreenBreak(
        title = "take a 5min pose", intervalMillis = interval, durationMillis = duration,
        restBreak = true, key = SchedulerDomain.FIVE_MIN_BREAK_KEY,
    )

    private fun pose15(interval: Long = 2 * HOUR) = ScreenBreak(
        title = "take a 15min pose", intervalMillis = interval, durationMillis = 15 * MIN,
        restBreak = true, key = SchedulerDomain.FIFTEEN_MIN_BREAK_KEY,
    )

    @Test
    fun both_poses_are_published_each_under_its_own_key() {
        // The spec's row: the account plus the scheduled time of apparition of BOTH breaks. No selection is
        // made client-side — the server picks the longest one that was already overdue at the last beat.
        val breaks = listOf(pose5(), pose15())
        val dues = SchedulerEngine.restPoseDueMillisByKey(breaks, NOW)
        assertEquals(
            setOf(SchedulerDomain.FIVE_MIN_BREAK_KEY, SchedulerDomain.FIFTEEN_MIN_BREAK_KEY),
            dues.keys,
        )
        assertTrue(dues.values.all { it >= NOW }, "a placed start is never behind the now-line: $dues")
    }

    @Test
    fun the_published_instant_is_the_one_the_cue_keys_on() {
        // One instant, derived once. What the server is told and what this device announces are the same
        // number by construction, not two derivations kept in step — the break's DUE, where the recurrence
        // bars put it. (Not where mode 1 leaves the period: the line pushes a period it has reached, so the
        // period's own start moves with the line and is no instant to publish.)
        val breaks = listOf(pose5(), pose15())
        val dues = SchedulerEngine.restPoseDueMillisByKey(breaks, NOW)
        val announced = SchedulerDomain.screenBreakOccurrencesBetween(
            breaks, NOW, NOW + 12 * HOUR, anchorMillis = NOW,
        )
        for (side in breaks) {
            val next = announced.firstOrNull { it.title == side.title && it.startEpochMillis >= NOW } ?: continue
            assertEquals(
                next.startEpochMillis,
                dues[side.key],
                "${side.title}: the published due must be the next occurrence the cue announces",
            )
        }
    }

    @Test
    fun the_bars_are_walked_over_the_environment_they_are_given() {
        // Asked without the standing periods the bars answer a different timeline — which is exactly how the
        // server ends up timing the cue to a break the user never sees. A long "no task allowed" period ahead
        // of the now-line is a rest stretch, so it bars the poses that follow it and pushes the next one out.
        val breaks = listOf(pose5())
        val bare = SchedulerEngine.restPoseDueMillisByKey(breaks, NOW)[SchedulerDomain.FIVE_MIN_BREAK_KEY]
        val withRest = SchedulerEngine.restPoseDueMillisByKey(
            breaks,
            NOW,
            basePeriods = listOf(
                RestrictivePeriod(NOW, NOW + 30 * MIN, PeriodKinds.NO_TASK, "away"),
            ),
        )[SchedulerDomain.FIVE_MIN_BREAK_KEY]
        assertNotNull(bare)
        assertNotNull(withRest)
        assertTrue(
            withRest > bare,
            "a 30-minute rest stretch must push the next 5-min pose out (bare=$bare, withRest=$withRest)",
        )
    }

    @Test
    fun look_aways_and_unkeyed_ad_hoc_breaks_are_never_published() {
        // The 20 s look-away has its own local cue and is not a rest break; an ad-hoc pose with no key has no
        // `break_config` entry the server could resolve a length or a vocal message from.
        val lookAway = ScreenBreak(
            "look 20 feet away", intervalMillis = 20 * MIN, durationMillis = 20 * SEC,
            key = SchedulerDomain.LOOK_AWAY_KEY,
        )
        val adHoc = ScreenBreak(
            "custom pose", intervalMillis = 30 * MIN, durationMillis = 3 * MIN, restBreak = true,
        )
        assertEquals(
            emptyMap<String, Long>(),
            SchedulerEngine.restPoseDueMillisByKey(listOf(lookAway, adHoc), NOW),
        )
    }

    @Test
    fun a_pose_the_environment_suspends_indefinitely_has_no_next_instant_to_name() {
        // An open-ended "no task allowed" period — a night, a hand-drawn inactivity period with no end —
        // places nothing inside the search window, and a key with no placed occurrence is simply absent rather
        // than published at some invented instant.
        val dues = SchedulerEngine.restPoseDueMillisByKey(
            listOf(pose5()),
            NOW,
            basePeriods = listOf(
                RestrictivePeriod(
                    NOW - HOUR,
                    NOW + SchedulerDomain.NEXT_BREAK_SEARCH_MILLIS + HOUR,
                    PeriodKinds.NO_TASK,
                    "asleep",
                ),
            ),
        )
        assertFalse(SchedulerDomain.FIVE_MIN_BREAK_KEY in dues, "nothing placed ⇒ nothing published: $dues")
    }

    @Test
    fun no_rest_pose_configured_publishes_nothing() {
        val lookAway = ScreenBreak(
            "look 20 feet away", intervalMillis = 20 * MIN, durationMillis = 20 * SEC,
            key = SchedulerDomain.LOOK_AWAY_KEY,
        )
        assertEquals(emptyMap<String, Long>(), SchedulerEngine.restPoseDueMillisByKey(listOf(lookAway), NOW))
    }
}
