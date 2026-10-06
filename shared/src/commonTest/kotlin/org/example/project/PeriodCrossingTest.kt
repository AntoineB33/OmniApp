package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.PeriodKinds
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.domain.SearchDomain
import org.example.project.scheduler.model.PanelPins
import org.example.project.scheduler.model.TaskPanel
import org.example.project.scheduler.model.TaskTimeRange
import org.example.project.scheduler.persistence.SchedulerStateCodec
import org.example.project.scheduler.state.PeriodCrossing
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState
import org.example.project.time.AppClock

/**
 * `docs/scheduler_requirements.md` § *$now line$ 3 modes* and its examples: *"When the $now line$ is in mode 1 and
 * reaches a 'no screen' period that extends to [t1;t2], I want the 'no screen' period to become ]$now line$;t2] when
 * $now line$ is in [t1;t2[. When $now line$ >= t2, then this 'no screen' period is removed."*
 *
 * For a period the USER stated, what the line crossed at a screen is kept beside the period
 * ([SchedulerState.periodCrossings]) and taken from it wherever periods are read ([SchedulerDomain.statedPanels]) —
 * the stored statement is never rewritten, so the rules do not change and nothing re-plans.
 */
class PeriodCrossingTest {
    private val hour = 3_600_000L
    private val minute = 60_000L
    private val t1 = 1_800_000_000_000L
    private val t2 = t1 + 4 * hour

    private class At(var now: Long) : AppClock {
        override fun nowMillis(): Long = now
    }

    private fun <T> at(now: Long, block: (At) -> T): T {
        val previous = SchedulerReducer.clock
        val clock = At(now)
        SchedulerReducer.clock = clock
        try {
            return block(clock)
        } finally {
            SchedulerReducer.clock = previous
        }
    }

    private fun r(state: SchedulerState, intent: SchedulerIntent) = SchedulerReducer.reduce(state, intent)

    /** A period of [kind] the user drew over `[t1, t2)`, stated a day before it starts. */
    private fun drawn(kind: String = PeriodKinds.NO_SCREEN): SchedulerState =
        at(t1 - 24 * hour) { r(SchedulerState.empty().copy(automaticSchedule = false), SchedulerIntent.AddRestrictivePeriod(kind, t1, t2)) }

    private fun period(state: SchedulerState): List<Pair<Long, Long>> =
        SchedulerDomain.statedPanels(state).filter { it.isRestrictivePeriod }.sortedBy { it.startEpochMillis }
            .map { (it.startEpochMillis - t1) / minute to (it.endEpochMillis - t1) / minute }

    private fun crossed(state: SchedulerState, from: Long, until: Long) =
        r(state, SchedulerIntent.RecordAtScreenCrossing(from, until))

    @Test
    fun a_period_the_line_is_in_at_a_screen_starts_at_the_line_and_one_it_has_left_is_gone() {
        val s = drawn()
        assertEquals(listOf(0L to 240L), period(s))
        // The line reached it at a screen and is half an hour in: ]line; t2].
        val half = crossed(s, t1 - hour, t1 + 30 * minute)
        assertEquals(listOf(30L to 240L), period(half))
        // Further: the same period, shorter — banked in two triggers or in one.
        val further = crossed(half, t1 + 30 * minute, t1 + 3 * hour)
        assertEquals(listOf(180L to 240L), period(further))
        assertEquals(period(further), period(crossed(s, t1 - hour, t1 + 3 * hour)))
        // The line is past its end: the period is removed.
        assertEquals(emptyList(), period(crossed(further, t1 + 3 * hour, t2 + hour)))
        // Less than a minute left is no period.
        assertEquals(emptyList(), period(crossed(s, t1, t2 - 30_000L)))
    }

    @Test
    fun the_statement_is_never_rewritten_so_the_rules_do_not_change_and_nothing_is_synced() {
        val s = drawn()
        val after = crossed(s, t1 - hour, t2 + hour)
        assertEquals(s.panels, after.panels, "the stored period is the user's statement, whole")
        assertEquals(SchedulerDomain.schedulingSignature(s), SchedulerDomain.schedulingSignature(after), "time passing must never re-plan")
        assertEquals(SchedulerStateCodec.syncFingerprint(s), SchedulerStateCodec.syncFingerprint(after), "the line's own history is this device's")
        // …and it is carried across a pull, like the rest of what only this device knows.
        assertEquals(after.periodCrossings, s.withLocalViewStateFrom(after).periodCrossings)
        // Nothing crossed: the same instance.
        assertTrue(crossed(s, t1 - 3 * hour, t1 - hour) === s)
        assertTrue(crossed(after, t1, t2) === after)
    }

    @Test
    fun what_gives_way_is_a_period_the_user_stated_that_is_or_carries_no_screen() {
        // A period that carries "no screen" (`when sleep then no screen`) cannot stand where its "no screen" cannot.
        assertEquals(listOf(60L to 240L), period(crossed(drawn(PeriodKinds.SLEEP), t1, t1 + hour)))
        // A kind that says nothing about a screen is where the user put it.
        val own = at(t1 - 24 * hour) {
            var s = r(SchedulerState.empty().copy(automaticSchedule = false), SchedulerIntent.AddPeriodKind("deep work"))
            s = r(s, SchedulerIntent.AddRestrictivePeriod("deep work", t1, t2))
            s
        }
        assertTrue(crossed(own, t1, t2) === own)
        // A period a rule laid (the sleep schedule's window, fill-laid) is cut where it is drawn, not here; a task
        // panel is no period at all.
        val laid = drawn().let {
            it.copy(
                panels = it.panels +
                    TaskPanel("sleep/1", null, "Sleep", t1, t2, sleep = true, auto = true) +
                    TaskPanel("panel/900", null, "x", t1, t2, pinned = true, auto = false, pins = PanelPins(existence = true)),
            )
        }
        val after = crossed(laid, t1, t2)
        assertEquals(1, after.periodCrossings.size, "the user's no-screen period alone")
        assertEquals(2, SchedulerDomain.statedPanels(after).size, "the two others stand as they were")
    }

    @Test
    fun a_period_crossed_in_its_middle_stands_in_two_pieces() {
        // Entered away from the screen, back at it for an hour, away again: only that hour is taken.
        val s = crossed(drawn(), t1 + hour, t1 + 2 * hour)
        assertEquals(listOf(0L to 60L, 120L to 240L), period(s))
        val pieces = SchedulerDomain.statedPanels(s).filter { it.isRestrictivePeriod }.sortedBy { it.startEpochMillis }
        assertEquals(s.panels.single().id, pieces.first().id, "the first piece is the period itself")
        assertTrue(pieces[1].id.startsWith(s.panels.single().id + SchedulerDomain.CROSSED_PIECE_SEPARATOR))
    }

    @Test
    fun a_period_stated_again_keeps_nothing_of_what_was_crossed_before() {
        // The line crossed half of it at a screen; then the user moves it back over that very stretch — rewriting
        // history, which the requirements allow: it stands whole, and only what the line crosses from now on is taken.
        val half = crossed(drawn(), t1, t1 + 2 * hour)
        assertEquals(listOf(120L to 240L), period(half))
        val id = half.panels.single().id
        val restated = at(t1 + 2 * hour) {
            r(half, SchedulerIntent.UpdateTaskPanel(id, null, half.panels.single().title, t1, t2, PanelPins(existence = true), true))
        }
        assertEquals(listOf(0L to 240L), period(restated))
        // A stretch walked BEFORE it was stated again is not taken from it, whenever it is banked…
        assertEquals(listOf(0L to 240L), period(crossed(restated, t1, t1 + 2 * hour)))
        // …what the line walks after is.
        assertEquals(listOf(0L to 120L, 180L to 240L), period(crossed(restated, t1, t1 + 3 * hour)))
    }

    @Test
    fun the_live_stretch_is_taken_before_it_is_banked() {
        val s = drawn()
        fun live(from: Long, until: Long) =
            SchedulerDomain.afterCrossings(s.panels, s.periodCrossings, s.periodKindConfig, live = TaskTimeRange(from, until))
                .filter { it.isRestrictivePeriod }.map { (it.startEpochMillis - t1) / minute to (it.endEpochMillis - t1) / minute }
        assertEquals(listOf(0L to 240L), live(t1 - 2 * hour, t1 - hour), "the line has not reached it")
        assertEquals(listOf(45L to 240L), live(t1 - 2 * hour, t1 + 45 * minute), "]line; t2]")
        assertEquals(emptyList(), live(t1 - 2 * hour, t2), "removed")
    }

    @Test
    fun the_trigger_is_the_end_of_the_next_period_that_gives_way() {
        val s = drawn()
        assertEquals(t2, SchedulerDomain.nextStatedNoScreenEndAfter(s, t1 - hour))
        assertEquals(t2, SchedulerDomain.nextStatedNoScreenEndAfter(s, t1 + hour))
        assertEquals(Long.MAX_VALUE, SchedulerDomain.nextStatedNoScreenEndAfter(s, t2))
        assertEquals(Long.MAX_VALUE, SchedulerDomain.nextStatedNoScreenEndAfter(SchedulerState.empty(), t1))
    }

    @Test
    fun the_search_window_reads_the_period_as_it_stands() {
        val s = crossed(drawn(), t1, t1 + 2 * hour)
        assertTrue(PeriodKinds.NO_SCREEN !in SearchDomain.calendarKindsAt(s, t1 + hour), "crossed at a screen: not there")
        assertTrue(PeriodKinds.NO_SCREEN in SearchDomain.calendarKindsAt(s, t1 + 3 * hour))
        val block = SearchDomain.calendarBlocks(s).single()
        assertEquals(t1 + 2 * hour, block.startMillis)
        assertEquals(emptyList(), SearchDomain.calendarBlocks(crossed(s, t1, t2)), "removed: no block of it is left")
    }

    @Test
    fun the_crossings_are_kept_across_a_restart_and_a_payload_without_them_has_none() {
        val s = crossed(drawn(), t1, t1 + hour)
        val loaded = SchedulerStateCodec.decode(SchedulerStateCodec.encode(s))!!
        assertEquals(s.periodCrossings, loaded.periodCrossings)
        assertEquals(period(s), period(loaded))
        // Written by a build before the crossings existed: nothing crossed, the period whole.
        val previous = SchedulerStateCodec.encode(drawn().copy(periodCrossings = emptyMap()))
        assertTrue("periodCrossings" !in previous, "no crossing writes no field — the older shape")
        assertEquals(emptyMap(), SchedulerStateCodec.decode(previous)!!.periodCrossings)
        // Healed on the way in: a crossing of a panel the state does not hold is dropped.
        val stray = s.copy(periodCrossings = s.periodCrossings + ("panel/gone" to PeriodCrossing(ranges = listOf(TaskTimeRange(t1, t2)))))
        assertEquals(s.periodCrossings, SchedulerStateCodec.decode(SchedulerStateCodec.encode(stray))!!.periodCrossings)
    }
}

/**
 * `docs/scheduler_requirements.md` § *Rule Structure*: the line's walk at a screen holds ONE armed trigger and banks
 * the stretch it crossed at exactly two events — that trigger, and the edge out of mode 1.
 */
class AtScreenWalkTest {
    private val minute = 60_000L
    private val hour = 60 * minute
    private val t0 = 1_800_000_000_000L

    /** A walk over periods ending at [ends], counting how often it asks where the next one ends. */
    private class Fixture(val ends: List<Long>) {
        var asked = 0
        val walk = org.example.project.scheduler.domain.AtScreenWalk { at ->
            asked++
            ends.filter { it > at }.minOrNull() ?: Long.MAX_VALUE
        }
    }

    @Test
    fun a_stretch_is_banked_where_a_period_ends_and_where_the_line_leaves_the_screen() {
        val f = Fixture(ends = listOf(t0 + 3 * hour))
        assertEquals(null, f.walk.moveTo(t0, atScreen = true), "the first position only says where the walk starts")
        // Minute after minute at a screen: nothing banked, and the timeline is asked once, when the trigger is armed.
        for (m in 1..179) assertEquals(null, f.walk.moveTo(t0 + m * minute, atScreen = true))
        assertEquals(1, f.asked)
        assertEquals(t0, f.walk.since, "the calendar takes the stretch live meanwhile")
        // The period's end: the three hours are banked, and the walk goes on from there.
        assertEquals(TaskTimeRange(t0, t0 + 3 * hour), f.walk.moveTo(t0 + 3 * hour, atScreen = true))
        assertEquals(t0 + 3 * hour, f.walk.since)
        // The edge out of mode 1: banked up to the last position at a screen — the step that left it is no part of it.
        assertEquals(null, f.walk.moveTo(t0 + 4 * hour, atScreen = true))
        assertEquals(TaskTimeRange(t0 + 3 * hour, t0 + 4 * hour), f.walk.moveTo(t0 + 5 * hour, atScreen = false))
        assertEquals(null, f.walk.since)
        // Away: nothing is walked at a screen, nothing banked, nothing asked.
        val askedAway = f.asked
        assertEquals(null, f.walk.moveTo(t0 + 6 * hour, atScreen = false))
        assertEquals(askedAway, f.asked)
        // Back at a screen: a new stretch starts where the line was.
        assertEquals(null, f.walk.moveTo(t0 + 7 * hour, atScreen = true))
        assertEquals(t0 + 6 * hour, f.walk.since)
    }

    @Test
    fun the_trigger_is_armed_again_when_the_periods_change_and_never_as_the_line_moves() {
        var ends = listOf<Long>()
        var asked = 0
        val walk = org.example.project.scheduler.domain.AtScreenWalk { at ->
            asked++
            ends.filter { it > at }.minOrNull() ?: Long.MAX_VALUE
        }
        walk.moveTo(t0, atScreen = true)
        walk.moveTo(t0 + hour, atScreen = true)
        assertEquals(1, asked)
        // The user draws a period ending in an hour: the walk hears of it when the panels change, not before.
        ends = listOf(t0 + 2 * hour)
        assertEquals(null, walk.moveTo(t0 + 90 * minute, atScreen = true), "armed for no period: nothing to bank")
        assertEquals(1, asked)
        assertEquals(TaskTimeRange(t0, t0 + 2 * hour), walk.moveTo(t0 + 2 * hour, atScreen = true, periodsChanged = true))
        assertEquals(2, asked)
        // A line that does not move, or a clock that steps back, banks nothing.
        assertEquals(null, walk.moveTo(t0 + 2 * hour, atScreen = true))
        assertEquals(null, walk.moveTo(t0 + hour, atScreen = false))
    }
}
