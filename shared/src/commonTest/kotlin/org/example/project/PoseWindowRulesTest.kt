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
                breaks, NOW, NOW + SchedulerDomain.NEXT_BREAK_SEARCH_MILLIS, anchorMillis = NOW,
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
        val rules = SchedulerDomain.poseWindowsBetween(breaks, NOW)
        val first = rules.first()
        val inside = first.startMillis + 1
        val fromInside = SchedulerDomain.poseWindowsBetween(breaks, inside)
        assertTrue(
            fromInside.any { it.startMillis <= inside && inside < it.endMillis },
            "the break the line is inside must be in the set: $fromInside",
        )
        // …and one that has wholly elapsed is not a rule about the future any more.
        val after = SchedulerDomain.poseWindowsBetween(breaks, first.endMillis)
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
        // The set is a function of the same environment the fill and the cue sweep are handed — a night the
        // account is asleep through holds no break, so the server must not be told one falls there. (An
        // open-ended period nobody may run in suspends the bars indefinitely.)
        val blocked =
            listOf(
                org.example.project.scheduler.domain.RestrictivePeriod(
                    NOW, NOW + 12 * HOUR,
                    org.example.project.scheduler.domain.PeriodKinds.NO_TASK,
                    "Inactivity",
                ),
            )
        val rules = SchedulerDomain.poseWindowsBetween(breaks, NOW, basePeriods = blocked)
        assertTrue(
            rules.none { it.startMillis >= NOW && it.startMillis < NOW + 12 * HOUR },
            "no rule may START inside a stretch nobody can run in: $rules",
        )
        // A break that began BEFORE the stretch and runs into it is a different matter, and is kept: it is a
        // window the line may legitimately be inside, which is the whole question the server asks.
        assertTrue(rules.all { it.endMillis > NOW })
    }
}
