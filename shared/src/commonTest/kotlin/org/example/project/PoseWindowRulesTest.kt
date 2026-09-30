package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.ScreenBreak

/**
 * `docs/scheduler_requirements.md` § *$now line$ 3 modes*: **the scheduler returns a SET OF RULES, and that set
 * is the whole of what the server is given about where breaks fall.**
 *
 * *"I don't want the server to run the scheduler, but it to read the resulting set of rules to determine if
 * $now line$ is in a 5/15min screen break."* [SchedulerDomain.poseWindowsBetween] is that set: the placed 5- and
 * 15-minute dynamic restrictive periods over the next day, as plain windows. The server's whole question is
 * then a comparison — `start <= now < end` — which is legitimate exactly because mode 3 is the mode in which
 * nothing drags a pose, so where the bars put one IS where it happens.
 *
 * Both things the server is told come out of this ONE query: the windows, and the two dues that are the first
 * of each kind ([org.example.project.scheduler.engine.SchedulerEngine] projects them). A second derivation is
 * how the walk-away gate and the mode-3 evaluation would start naming different breaks.
 */
class PoseWindowRulesTest {

    private val SEC = 1_000L
    private val MIN = 60_000L
    private val HOUR = 60 * MIN
    private val NOW = 1_000_000_000_000L

    private val breaks = SchedulerDomain.DEFAULT_SCREEN_BREAKS

    @Test
    fun the_rules_are_the_two_poses_placed_over_the_next_day_and_nothing_else() {
        val rules = SchedulerDomain.poseWindowsBetween(breaks, NOW)
        assertTrue(rules.isNotEmpty(), "there must be rules for this to be about")

        // Only the poses. The 20 s look-away is assumed taken as it falls due, so it is never cued, and its
        // 20-minute cadence would rewrite the published set for an answer nothing reads.
        val poseKeys = breaks.filter { it.restBreak }.map { it.key }.toSet()
        assertTrue(rules.all { it.key in poseKeys }, "only the 5/15-minute poses: ${rules.map { it.key }}")
        assertEquals(poseKeys, rules.map { it.key }.toSet(), "and both of them")

        // Each window lasts exactly as long as its break, and the set is ordered and bounded by the search
        // horizon — the server does arithmetic on these, so a malformed one is not a rule.
        val durationOfKey = breaks.filter { it.restBreak }.associate { it.key to it.durationMillis }
        assertTrue(rules.all { it.endMillis - it.startMillis == durationOfKey[it.key] })
        assertEquals(rules.sortedBy { it.startMillis }, rules)
        assertTrue(rules.all { it.startMillis < NOW + SchedulerDomain.NEXT_BREAK_SEARCH_MILLIS })
    }

    @Test
    fun they_are_where_the_bars_put_them_with_nothing_dragged() {
        // The undragged run, like every other question that is not about the line itself: the server is told
        // where the scheduler PLACED a break, never where a now-line has pushed one. Same instants the calendar
        // draws and the local cue sweep fires on.
        val rules = SchedulerDomain.poseWindowsBetween(breaks, NOW)
        val bars =
            SchedulerDomain.screenBreakOccurrencesBetween(
                breaks, NOW, NOW + SchedulerDomain.NEXT_BREAK_SEARCH_MILLIS, nowMillis = NOW,
            )
        val posesFromBars =
            bars.filter { panel -> breaks.any { it.restBreak && it.title == panel.title } }
                .map { it.startEpochMillis }
        assertTrue(posesFromBars.isNotEmpty())
        assertTrue(
            rules.map { it.startMillis }.containsAll(posesFromBars),
            "the rules must be the bars' own answer: ${rules.map { it.startMillis }} vs $posesFromBars",
        )
    }

    @Test
    fun a_window_the_line_is_inside_is_kept_and_one_wholly_elapsed_is_not() {
        // The server's question is "is the line inside a break", so the window straddling the instant asked
        // about is precisely the one that must survive. Asking a moment after a pose has begun must still
        // return it.
        // A line away (mode 3, the mode the server is asked about) enters the first pose where it falls due; the record
        // it carries is what the set is read from, as the engine reads it.
        val mode = org.example.project.scheduler.domain.DynamicPeriods.MODE_ON_BREAK
        val rules = SchedulerDomain.poseWindowsBetween(breaks, NOW, mode = mode)
        val first = rules.first()
        val inside = first.startMillis + 1
        var record: org.example.project.scheduler.domain.FrozenScreenBreaks? = null
        for (t in listOf(NOW, inside)) record = SchedulerDomain.stepScreenBreaks(breaks, record, t, emptyList(), mode).record
        val fromInside = SchedulerDomain.poseWindowsBetween(breaks, inside, mode = mode, frozen = record)
        assertTrue(
            fromInside.any { it.startMillis <= inside && inside < it.endMillis },
            "the break the line is inside must be in the set: $fromInside",
        )
        // …and one that has wholly elapsed is not a rule about the future any more.
        record = SchedulerDomain.stepScreenBreaks(breaks, record, first.endMillis, emptyList(), mode).record
        val after = SchedulerDomain.poseWindowsBetween(breaks, first.endMillis, mode = mode, frozen = record)
        assertTrue(after.none { it.endMillis <= first.endMillis })
    }

    @Test
    fun an_account_with_no_pose_publishes_no_rules() {
        // Nothing to say is said as nothing: the server then finds no window and pass (c) never fires.
        val lookAwayOnly = listOf(ScreenBreak("20s", intervalMillis = 20 * MIN, durationMillis = 20 * SEC))
        assertEquals(emptyList(), SchedulerDomain.poseWindowsBetween(lookAwayOnly, NOW))
        assertEquals(emptyList(), SchedulerDomain.poseWindowsBetween(emptyList(), NOW))
    }

    @Test
    fun the_environment_reaches_the_rules() {
        // The set is a function of the same environment the fill and the cue sweep are handed. A no-screen period ahead
        // takes every pose falling due in it at its own start (the chain rule keeps the longest), and bars the ones
        // after it — so the server is told exactly that.
        val night =
            listOf(
                org.example.project.scheduler.domain.RestrictivePeriod(
                    NOW + 90 * MIN, NOW + 12 * HOUR,
                    org.example.project.scheduler.domain.PeriodKinds.NO_SCREEN,
                    "No screen",
                ),
            )
        val rules = SchedulerDomain.poseWindowsBetween(breaks, NOW, basePeriods = night)
        val inside = rules.filter { it.startMillis >= NOW + 90 * MIN && it.startMillis < NOW + 12 * HOUR }
        assertEquals(listOf(NOW + 90 * MIN), inside.map { it.startMillis }, "one pose, at the period's start: $rules")
        assertTrue(rules.none { it.startMillis in NOW + 12 * HOUR until NOW + 13 * HOUR }, "and the stretch bars the next")
    }
}
