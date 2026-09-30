package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.BreakMachine
import org.example.project.scheduler.domain.BreakMachine.Event
import org.example.project.scheduler.domain.DynamicPeriods
import org.example.project.scheduler.domain.DynamicPeriods.MODE_AT_SCREEN
import org.example.project.scheduler.domain.DynamicPeriods.MODE_AWAY
import org.example.project.scheduler.domain.DynamicPeriods.MODE_ON_BREAK
import org.example.project.scheduler.domain.DynamicPeriods.Span
import org.example.project.scheduler.domain.SchedulerDomain

/**
 * `docs/scheduler_requirements.md` § *Default restrictive periods*, last bullet — *"In a 'no screen' period, if t_b is
 * the start of a screen break and $now line$ < t_b, then this screen break must now start at max($now line$, t_s)"* —
 * and the line going on in an away mode.
 *
 * The machine applies the rule forward only: a break is never placed behind the line (the frozen past), so a break
 * falling due while the user is away starts where the line meets it, and a known no-screen period AHEAD pulls the
 * breaks falling due in it onto its start. (The walk this replaced re-derived the past and back-dated a break to the
 * start of the pause the user was in; the requirements' `max($now line$, t_s)` never reaches behind the line.)
 */
class ScreenBreakChainPullBackTest {

    private val SEC = 1_000L
    private val MIN = 60_000L
    private val HOUR = 3_600_000L

    /** A look-away (cadence 25 min) alone. */
    private val lookAwayOnly = listOf(DynamicPeriods.Spec(DynamicPeriods.LABEL_20S, 20 * SEC, 25 * MIN))

    /** A look-away pushed past the horizon, so the 5-min pose (positional labels) is the one in play. */
    private val poseOnly =
        listOf(
            DynamicPeriods.Spec(DynamicPeriods.LABEL_20S, 20 * SEC, 10 * HOUR),
            DynamicPeriods.Spec(DynamicPeriods.LABEL_5MIN, 5 * MIN, 50 * MIN),
        )

    /** The line at a screen from 0 to [awayAt], then in [mode] up to [toMillis]; every transition on the way. */
    private fun walkAway(
        specs: List<DynamicPeriods.Spec>,
        awayAt: Long,
        mode: Int,
        toMillis: Long,
        chains: List<Span> = emptyList(),
        events: MutableList<Event> = ArrayList(),
    ): BreakMachine.State {
        var s = BreakMachine.advance(BreakMachine.initial(0L, specs), awayAt, chains, specs, events)
        s = BreakMachine.switchMode(s, mode, chains, specs, events)
        return BreakMachine.advance(s, toMillis, chains, specs, events)
    }

    @Test
    fun a_break_falling_due_while_the_user_is_away_starts_where_the_line_meets_it() {
        // Away from 20 min; the look-away falls due at 25 min.
        for (mode in listOf(MODE_ON_BREAK, MODE_AT_SCREEN)) {
            val events = ArrayList<Event>()
            walkAway(lookAwayOnly, 20 * MIN, mode, 26 * MIN, events = events)
            val started = events.filterIsInstance<Event.Started>().single()
            assertEquals(25 * MIN, started.startMillis, "mode $mode: the line enters it where it falls due")
            assertEquals(20 * SEC, started.endMillis - started.startMillis, "and it is NOT stretched to reach the line")
        }
        // Mode 2 may not enter one — "the $now line$ must be in mode 1 or 3 before entering the 20s break" — so it rides
        // the line instead.
        val events = ArrayList<Event>()
        val locked = walkAway(lookAwayOnly, 20 * MIN, MODE_AWAY, 26 * MIN, events = events)
        assertTrue(events.filterIsInstance<Event.Started>().isEmpty(), "mode 2 enters no look-away: $events")
        assertEquals(DynamicPeriods.LABEL_20S, locked.drag?.label)
    }

    @Test
    fun a_known_no_screen_period_ahead_pulls_the_break_falling_due_in_it_onto_its_start() {
        // A no-screen period from 20 min to 40 min: the look-away due at 25 min starts at 20 min, where the period
        // does — before the line gets there, so nothing behind the line is touched.
        val period = Span(20 * MIN, 40 * MIN)
        val specs = lookAwayOnly
        assertEquals(20 * MIN, BreakMachine.dueOf(BreakMachine.initial(0L, specs), DynamicPeriods.LABEL_20S, listOf(period)))
        val events = ArrayList<Event>()
        BreakMachine.advance(BreakMachine.initial(0L, specs), 30 * MIN, listOf(period), specs, events)
        assertEquals(20 * MIN, events.filterIsInstance<Event.Started>().single().startMillis)
    }

    @Test
    fun a_break_the_line_has_passed_stays_where_it_was_banked() {
        // What the line entered is banked, and nothing moves it once the line is past it — the frozen past.
        var record: org.example.project.scheduler.domain.FrozenScreenBreaks? = null
        val breaks = SchedulerDomain.DEFAULT_SCREEN_BREAKS
        val t0 = 1_700_000_000_000L
        for (t in listOf(t0, t0 + 20 * MIN + SEC, t0 + 25 * MIN, t0 + 2 * HOUR)) {
            record = SchedulerDomain.stepScreenBreaks(breaks, record, t, emptyList(), MODE_AT_SCREEN).record
        }
        val first = record!!.breaks.first()
        assertEquals(t0 + 20 * MIN, first.startMillis)
        val later = SchedulerDomain.stepScreenBreaks(breaks, record, t0 + 5 * HOUR, emptyList(), MODE_ON_BREAK).record
        assertEquals(first, later.breaks.first(), "a later mode does not move it")
    }

    @Test
    fun a_pause_shorter_than_the_pose_leaves_the_pose_owed() {
        // Away at the pose's due and back two minutes later: a line at a screen may not be inside a pose, so the one it
        // was taking is REMOVED (the requirements' exception to the frozen past) and owed again.
        val events = ArrayList<Event>()
        val away = walkAway(poseOnly, 49 * MIN, MODE_ON_BREAK, 52 * MIN, events = events)
        assertNotNull(away.active, "the pose started where it fell due: $events")
        val back = BreakMachine.switchMode(away, MODE_AT_SCREEN, emptyList(), poseOnly, events)
        assertTrue(events.any { it is Event.Removed }, "a two-minute pause is no five-minute pose")
        assertEquals(DynamicPeriods.LABEL_5MIN, back.drag?.label)
        assertNull(back.active)
    }

    @Test
    fun mode_one_drags_the_pose_the_away_modes_enter() {
        // Mode 1: the now-line must NOT be covered by "no screen", so the pose rides it, `]t_p; t_p + d]`. Modes 2 and 3:
        // the line must be covered, and the pose is what covers it.
        val atScreen = walkAway(poseOnly, 10 * MIN, MODE_AT_SCREEN, 52 * MIN)
        assertEquals(DynamicPeriods.LABEL_5MIN, atScreen.drag?.label)
        assertNull(atScreen.active)
        for (mode in listOf(MODE_AWAY, MODE_ON_BREAK)) {
            val away = walkAway(poseOnly, 10 * MIN, mode, 52 * MIN)
            val active = away.active
            assertNotNull(active, "mode $mode")
            assertEquals(50 * MIN, active.startMillis, "mode $mode: the pose is taken where it falls due")
            assertTrue(active.startMillis <= 52 * MIN && 52 * MIN < active.endMillis, "and it covers the line")
        }
    }

    @Test
    fun a_pose_the_line_crossed_while_away_is_banked_and_one_it_dragged_is_not() {
        val t0 = 1_700_000_000_000L
        val breaks = SchedulerDomain.DEFAULT_SCREEN_BREAKS
        val poseLabels = setOf(DynamicPeriods.LABEL_5MIN, DynamicPeriods.LABEL_15MIN)
        fun banked(mode: Int): List<org.example.project.scheduler.domain.BankedBreak> {
            var record: org.example.project.scheduler.domain.FrozenScreenBreaks? = null
            var t = t0
            while (t <= t0 + 6 * HOUR) {
                record = SchedulerDomain.stepScreenBreaks(breaks, record, t, emptyList(), mode).record
                t += MIN
            }
            return record!!.breaks.filter { it.label in poseLabels }
        }
        assertTrue(banked(MODE_AT_SCREEN).isEmpty(), "mode 1: a pose the line reached was dragged and never happened")
        for (mode in listOf(MODE_AWAY, MODE_ON_BREAK)) {
            val elapsed = banked(mode)
            assertTrue(elapsed.isNotEmpty(), "mode $mode: a pose the line crossed stays banked where it happened")
            assertTrue(elapsed.all { it.endMillis - it.startMillis in setOf(5 * MIN, 15 * MIN) }, "each keeps its length")
        }
    }
}
