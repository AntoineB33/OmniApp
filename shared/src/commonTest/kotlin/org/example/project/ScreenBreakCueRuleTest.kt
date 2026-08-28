package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.ScreenBreak

/**
 * PRD §15 / CLAUDE.md: a break cue must be **mathematically accurate** — a pure function of which boundary
 * instants the clock crossed, never of how a sweep or heartbeat happens to align with the calendar.
 *
 * This replaces `RestPoseNotificationRuleTest`, which pinned the same rule on the retired mechanism: a pose
 * SLID along the now-line while owed, so its drawn start was not a boundary and the cue had to key on the
 * separate anchored due `lastRest + interval` (`reachedRestPoseDueByTitle`). Nothing slides now — the
 * recurrence bars put every break at a fixed instant (ADR 0003) — so **every cue keys on the START of the
 * placed period**, and what is announced and what is drawn are one instant by construction.
 *
 * What survives unchanged is the property that mattered: a fast or leaping clock must not skip a crossing,
 * and a window swept twice must not announce twice.
 */
class ScreenBreakCueRuleTest {
    private val MIN = 60_000L
    private val HOUR = 60 * MIN
    private val NOW = 1_000_000_000_000L

    private val pose5 =
        ScreenBreak("take a 5min pose", intervalMillis = 60 * MIN, durationMillis = 5 * MIN, restBreak = true)
    private val pose15 =
        ScreenBreak("take a 15min pose", intervalMillis = 120 * MIN, durationMillis = 15 * MIN, restBreak = true)
    private val lookAway =
        ScreenBreak("look 20 feet away", intervalMillis = 20 * MIN, durationMillis = 20_000L)
    private val breaks = listOf(lookAway, pose5, pose15)

    private fun crossings(
        from: Long,
        to: Long,
        automatic: Boolean = true,
        notified: Map<String, Long> = emptyMap(),
    ) = SchedulerDomain.cueCrossings(
        screenBreaks = breaks,
        windDownInstants = emptyList(),
        automaticSchedule = automatic,
        alreadyNotifiedPoseDues = notified,
        fromMillis = from,
        toMillis = to,
    )

    @Test
    fun a_cue_fires_at_the_instant_the_period_is_drawn_at() {
        // The whole rule in one assertion: every crossing sits on a placed occurrence's start.
        val to = NOW + 6 * HOUR
        val drawn = SchedulerDomain.screenBreakPanelsInWindow(breaks, NOW, to, anchorMillis = to)
            .map { it.title to it.startEpochMillis }.toSet()
        val fired = crossings(NOW, to)
        assertTrue(fired.isNotEmpty(), "the case needs breaks to be about")
        for (c in fired) {
            assertTrue(
                (c.title to c.instant) in drawn,
                "${c.title} announced at ${c.instant}, which the calendar draws nothing at",
            )
        }
    }

    @Test
    fun a_leap_across_several_boundaries_fires_every_one_of_them_in_order() {
        // The anomaly this exists for: an accelerated clock jumps a whole window in one tick. Sweeping it
        // must yield each crossing inside it exactly once, sorted by its true boundary instant — never only
        // the last one, and never in sampling order.
        val to = NOW + 3 * HOUR
        val fired = crossings(NOW, to)
        assertEquals(fired.sortedBy { it.instant }, fired, "crossings must come out in boundary order")
        assertEquals(fired.map { it.instant }.distinct().size, fired.map { it.instant }.size)
        // Sweeping the same span in two halves finds the same set: consecutive scans tile the timeline.
        val mid = NOW + 90 * MIN
        val halves = crossings(NOW, mid) + crossings(mid + 1, to)
        assertEquals(
            fired.map { it.title to it.instant }.toSet(),
            halves.map { it.title to it.instant }.toSet(),
        )
    }

    @Test
    fun a_pose_already_announced_is_not_announced_again() {
        // The de-dupe key is the placed START, and it is STABLE — a break does not move while it is owed, so
        // a window swept twice announces once. (The old bug: an overdue pose rode the now-line, so its "due"
        // changed at every sample and the dedupe never matched.)
        val to = NOW + 6 * HOUR
        val first = crossings(NOW, to).firstOrNull { it.kind == SchedulerDomain.CueKind.RestPoseDue }
        assertTrue(first != null, "the case needs a pose crossing")
        val again = crossings(NOW, to, notified = mapOf(first.title to first.instant))
        assertTrue(
            again.none { it.title == first.title && it.instant == first.instant },
            "an already-announced pose start must not be offered again",
        )
        // Only THAT start is suppressed — a later occurrence of the same pose is still a cue of its own.
        assertEquals(
            crossings(NOW, to).filterNot { it.title == first.title && it.instant == first.instant },
            again,
        )
        // And it is the same instant on a second, independent sweep of the same window.
        assertEquals(first.instant, crossings(NOW, to).first { it.title == first.title }.instant)
    }

    @Test
    fun the_look_away_is_announced_with_its_resume_instant_and_survives_the_switch_being_off() {
        // PRD §7: turning the automatic schedule off silences the poses (nothing is being scheduled to pause
        // from) but not the 20-second look-away, which is about the user's eyes.
        val to = NOW + 3 * HOUR
        val off = crossings(NOW, to, automatic = false)
        assertTrue(off.isNotEmpty() && off.all { it.kind == SchedulerDomain.CueKind.LookAwayStart })
        for (c in off) assertEquals(c.instant + lookAway.durationMillis, c.endInstant)
    }
}
