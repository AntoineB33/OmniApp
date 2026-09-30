package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.BreakMachine
import org.example.project.scheduler.domain.DynamicPeriods
import org.example.project.scheduler.domain.FrozenScreenBreaks
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.ScreenBreak

/**
 * PRD §15 / CLAUDE.md: a break cue must be **mathematically accurate** — a pure function of which boundary
 * instants the clock crossed, never of how a sweep or heartbeat happens to align with the calendar.
 *
 * `docs/scheduler_requirements.md` § *Rule Structure*: every cue is a transition of the break machine as the line
 * crosses it — a look-away where the line enters it, a pose where it falls due — so what is announced and what is
 * banked are one instant by construction.
 *
 * What survives unchanged is the property that mattered: a fast or leaping clock must not skip a crossing,
 * and a window swept twice must not announce twice.
 */
class ScreenBreakCueRuleTest {
    private val SEC = 1_000L
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

    /** The transitions a line at a screen crosses over `(from, to]`, from a machine rested at [NOW]. */
    private fun events(from: Long, to: Long): List<BreakMachine.Event> {
        val specs = SchedulerDomain.dynamicPeriodSpecs(breaks)
        val s = BreakMachine.advance(BreakMachine.initial(NOW, specs), from, emptyList(), specs)
        val out = ArrayList<BreakMachine.Event>()
        BreakMachine.advance(s, to, emptyList(), specs, out)
        return out
    }

    private fun crossings(
        from: Long,
        to: Long,
        automatic: Boolean = true,
        notified: Map<String, Long> = emptyMap(),
    ) = SchedulerDomain.cueCrossings(
        screenBreaks = breaks,
        breakEvents = events(from, to),
        mode = DynamicPeriods.MODE_AT_SCREEN,
        windDownInstants = emptyList(),
        automaticSchedule = automatic,
        alreadyNotifiedPoseDues = notified,
        fromMillis = from,
        toMillis = to,
    )

    @Test
    fun a_look_away_cue_fires_at_the_instant_the_line_entered_it() {
        // What the app SAYS and what it banks are one transition of the break machine: the line entering the break.
        val to = NOW + 6 * HOUR
        var record: FrozenScreenBreaks? = null
        var t = NOW
        while (t <= to) {
            record = SchedulerDomain.stepScreenBreaks(breaks, record, t, emptyList(), DynamicPeriods.MODE_AT_SCREEN).record
            t += 7 * SEC
        }
        val banked = record!!.breaks.filter { it.label == DynamicPeriods.LABEL_20S }.map { it.startMillis }
        val fired = crossings(NOW, to).filter { it.kind == SchedulerDomain.CueKind.LookAwayStart }.map { it.instant }
        assertTrue(banked.isNotEmpty(), "the case needs look-aways to be about")
        assertEquals(banked, fired, "every look-away the line entered is announced, and every one announced was entered")
    }

    @Test
    fun a_pose_cue_fires_where_it_falls_due() {
        val to = NOW + 6 * HOUR
        val fired = crossings(NOW, to).filter { it.kind == SchedulerDomain.CueKind.RestPoseDue }.map { it.instant }
        assertEquals(NOW + HOUR, fired.first(), "the 5 min pose falls due one hour after a rested start")
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
        val halves = crossings(NOW, mid) + crossings(mid, to)
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
