package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import org.example.project.scheduler.domain.BreakMachine
import org.example.project.scheduler.domain.BreakMachine.Event
import org.example.project.scheduler.domain.DynamicPeriods
import org.example.project.scheduler.domain.DynamicPeriods.LABEL_15MIN
import org.example.project.scheduler.domain.DynamicPeriods.LABEL_20S
import org.example.project.scheduler.domain.DynamicPeriods.LABEL_5MIN
import org.example.project.scheduler.domain.DynamicPeriods.MODE_AT_SCREEN
import org.example.project.scheduler.domain.DynamicPeriods.MODE_AWAY
import org.example.project.scheduler.domain.DynamicPeriods.MODE_ON_BREAK
import org.example.project.scheduler.domain.DynamicPeriods.Span
import org.example.project.scheduler.domain.SchedulerDomain

/**
 * `docs/scheduler_requirements.md` § *Default restrictive periods* and § *Example behaviors*, one rule at a time, against
 * the break machine ([BreakMachine]) — the one place the three screen breaks are placed, driven forward by the line.
 */
class BreakMachineTest {

    private val SEC = 1_000L
    private val MIN = 60_000L
    private val HOUR = 3_600_000L
    private val T0 = 1_000_000_000_000L

    private val specs = SchedulerDomain.dynamicPeriodSpecs(SchedulerDomain.DEFAULT_SCREEN_BREAKS)
    private fun len(label: String) = specs.first { it.label == label }.durationMillis

    private fun run(
        state: BreakMachine.State,
        to: Long,
        chains: List<Span> = emptyList(),
        events: MutableList<Event> = ArrayList(),
    ): BreakMachine.State = BreakMachine.advance(state, to, chains, specs, events)

    private fun started(events: List<Event>) = events.filterIsInstance<Event.Started>()

    @Test
    fun the_timeline_starts_rested_so_each_break_falls_one_cadence_after_the_start() {
        val s = BreakMachine.initial(T0, specs)
        assertEquals(T0 + 20 * MIN, BreakMachine.dueOf(s, LABEL_20S, emptyList()))
        assertEquals(T0 + HOUR, BreakMachine.dueOf(s, LABEL_5MIN, emptyList()))
        assertEquals(T0 + 2 * HOUR, BreakMachine.dueOf(s, LABEL_15MIN, emptyList()))
        assertEquals(T0 + 20 * MIN, BreakMachine.nextEventMillis(s, emptyList(), specs), "the first armed trigger")
    }

    @Test
    fun a_line_at_a_screen_enters_the_20s_break_in_mode_3_and_leaves_it_in_mode_1() {
        val events = ArrayList<Event>()
        val inside = run(BreakMachine.initial(T0, specs), T0 + 20 * MIN + 5 * SEC, events = events)
        assertEquals(listOf(Event.Started(LABEL_20S, T0 + 20 * MIN, T0 + 20 * MIN + 20 * SEC)), started(events))
        assertEquals(MODE_ON_BREAK, BreakMachine.effectiveMode(inside), "can't go in mode 1 during a 20s screen break")
        val after = run(inside, T0 + 21 * MIN)
        assertEquals(MODE_AT_SCREEN, BreakMachine.effectiveMode(after))
        assertEquals(
            T0 + 40 * MIN + 20 * SEC,
            BreakMachine.dueOf(after, LABEL_20S, emptyList()),
            "after the end of a screen break, no 20s break in the next 20 minutes",
        )
    }

    @Test
    fun a_pose_reached_in_mode_1_is_dragged_and_never_starts() {
        val events = ArrayList<Event>()
        val s = run(BreakMachine.initial(T0, specs), T0 + HOUR + 3 * MIN, events = events)
        assertEquals(Event.Owed(T0 + HOUR, LABEL_5MIN), events.filterIsInstance<Event.Owed>().single())
        assertTrue(started(events).none { it.label == LABEL_5MIN }, "a pose is not entered at a screen")
        assertEquals(LABEL_5MIN, s.drag?.label)
        assertEquals(T0 + HOUR, s.drag?.dueMillis)
        assertEquals(MODE_AT_SCREEN, BreakMachine.effectiveMode(s))
    }

    @Test
    fun a_dragged_5min_break_touching_the_15min_break_makes_it_teleport_onto_the_line() {
        // The requirements' own example: dragged until its end edge touches the 15 min break (due at 2 h), the 15 min
        // break teleports 5 minutes backward, starting right after the line, and the 5 min break is removed.
        val events = ArrayList<Event>()
        val s = run(BreakMachine.initial(T0, specs), T0 + 2 * HOUR - 5 * MIN, events = events)
        val owed = events.filterIsInstance<Event.Owed>()
        assertEquals(listOf(Event.Owed(T0 + HOUR, LABEL_5MIN), Event.Owed(T0 + 2 * HOUR - 5 * MIN, LABEL_15MIN)), owed)
        assertEquals(LABEL_15MIN, s.drag?.label)
        assertTrue(LABEL_5MIN in s.drag!!.members, "the 5 min break is gone into the 15 min one")
        // The drag rides the line and every later due joins it: nothing else happens while the line stays at a screen.
        assertNull(BreakMachine.nextEventMillis(run(s, T0 + 3 * HOUR), emptyList(), specs))
    }

    @Test
    fun a_look_away_falling_due_inside_the_reach_of_a_dragged_pose_joins_it() {
        val events = ArrayList<Event>()
        run(BreakMachine.initial(T0, specs), T0 + 90 * MIN, events = events)
        // 20 s at 0:20 and 0:40 (entered), then the 5 min pose at 1:00 is dragged; the next 20 s (due 1:00:40) joins it.
        assertEquals(
            listOf(T0 + 20 * MIN, T0 + 40 * MIN + 20 * SEC),
            started(events).filter { it.label == LABEL_20S }.map { it.startMillis },
        )
    }

    @Test
    fun a_line_that_walks_away_enters_the_pose_it_was_dragging() {
        val dragging = run(BreakMachine.initial(T0, specs), T0 + HOUR + 2 * MIN)
        val events = ArrayList<Event>()
        val away = BreakMachine.switchMode(dragging, MODE_ON_BREAK, emptyList(), specs, events)
        assertEquals(listOf(Event.Started(LABEL_5MIN, T0 + HOUR + 2 * MIN, T0 + HOUR + 7 * MIN)), started(events))
        assertNull(away.drag)
        val done = run(away, T0 + HOUR + 8 * MIN)
        assertNull(done.active)
        assertEquals(T0 + HOUR + 7 * MIN + HOUR, done.bars[LABEL_5MIN], "a 5 min break bars the next for an hour")
    }

    @Test
    fun a_line_at_a_screen_inside_a_pose_removes_it_and_drags_it_again() {
        val dragging = run(BreakMachine.initial(T0, specs), T0 + HOUR + 2 * MIN)
        val away = BreakMachine.switchMode(dragging, MODE_ON_BREAK, emptyList(), specs)
        val back = run(away, T0 + HOUR + 4 * MIN)
        val events = ArrayList<Event>()
        val atScreen = BreakMachine.switchMode(back, MODE_AT_SCREEN, emptyList(), specs, events)
        assertEquals(Event.Removed(T0 + HOUR + 4 * MIN, LABEL_5MIN, T0 + HOUR + 2 * MIN, T0 + HOUR + 7 * MIN), events.single())
        assertEquals(LABEL_5MIN, atScreen.drag?.label, "the pose is owed again")
        assertNull(atScreen.active)
    }

    @Test
    fun a_20s_break_reached_in_mode_2_is_dragged_and_entered_when_the_line_is_in_mode_3() {
        val away = BreakMachine.initial(T0, specs, MODE_AWAY)
        val events = ArrayList<Event>()
        val s = run(away, T0 + 25 * MIN, events = events)
        assertTrue(started(events).isEmpty(), "the line must be in mode 1 or 3 before entering the 20s break")
        assertEquals(LABEL_20S, s.drag?.label)
        val onBreak = BreakMachine.switchMode(s, MODE_ON_BREAK, emptyList(), specs, events)
        assertEquals(Event.Started(LABEL_20S, T0 + 25 * MIN, T0 + 25 * MIN + 20 * SEC), started(events).single())
        assertNotNull(onBreak.active)
    }

    @Test
    fun a_20s_break_mode_2_drags_does_not_survive_a_fifteen_minute_stretch() {
        val s = run(BreakMachine.initial(T0, specs, MODE_AWAY), T0 + 25 * MIN)
        val back = BreakMachine.switchMode(s, MODE_AT_SCREEN, emptyList(), specs)
        assertNull(back.drag, "after >= 15 minutes of no screen, no 20s break in the next 20 minutes")
        assertEquals(T0 + 45 * MIN, back.bars[LABEL_20S])
        assertEquals(T0 + 25 * MIN + 2 * HOUR, back.bars[LABEL_15MIN])
    }

    @Test
    fun a_long_stretch_of_no_screen_takes_each_break_once() {
        val events = ArrayList<Event>()
        run(BreakMachine.initial(T0, specs, MODE_ON_BREAK), T0 + 8 * HOUR, events = events)
        val byLabel = started(events).groupBy { it.label }
        assertEquals(1, byLabel[LABEL_20S]?.size, "$events")
        assertEquals(1, byLabel[LABEL_5MIN]?.size, "$events")
        assertEquals(1, byLabel[LABEL_15MIN]?.size, "$events")
    }

    @Test
    fun the_chain_rule_grows_the_break_the_line_is_in_into_the_longest_from_the_same_start() {
        val s0 = BreakMachine.initial(T0, specs, MODE_ON_BREAK)
            .copy(bars = mapOf(LABEL_20S to T0 + 10 * MIN, LABEL_5MIN to T0 + 10 * MIN + 10 * SEC, LABEL_15MIN to T0 + 5 * HOUR))
        val events = ArrayList<Event>()
        val s = run(s0, T0 + 11 * MIN, events = events)
        assertEquals(
            Event.Grew(T0 + 10 * MIN + 10 * SEC, LABEL_5MIN, T0 + 10 * MIN, T0 + 15 * MIN, LABEL_20S, T0 + 10 * MIN + 20 * SEC),
            events.filterIsInstance<Event.Grew>().single(),
        )
        assertEquals(LABEL_5MIN, s.active?.label)
    }

    @Test
    fun a_break_due_in_a_no_screen_period_ahead_starts_at_its_start() {
        // A night: every break falling due inside it is pulled to its start, and the chain keeps the longest.
        val night = Span(T0 + 90 * MIN, T0 + 9 * HOUR)
        val chains = listOf(night)
        val placed = BreakMachine.predictExpected(BreakMachine.initial(T0, specs), T0 + 12 * HOUR, chains, specs)
        val inNight = placed.filter { it.startMillis >= night.startMillis && it.startMillis < night.endMillis }
        assertEquals(listOf(BreakMachine.Placed(LABEL_15MIN, night.startMillis, night.startMillis + 15 * MIN)), inNight)
        // After a >= 15-minute stretch: no 20 s for 20 minutes, no 5 min for an hour, no 15 min for two hours.
        val after = placed.filter { it.startMillis >= night.endMillis }
        assertEquals(night.endMillis + 20 * MIN, after.first { it.label == LABEL_20S }.startMillis)
        assertEquals(night.endMillis + HOUR, after.first { it.label == LABEL_5MIN }.startMillis)
        assertEquals(night.endMillis + 2 * HOUR, after.first { it.label == LABEL_15MIN }.startMillis)
    }

    @Test
    fun a_line_at_a_screen_inside_a_no_screen_period_takes_one_20s_break_there_not_one_every_twenty_seconds() {
        // Mode 1 retracts the period (]now line; t2]); its 20 s break is pulled to the line once, and the chain rule is
        // what keeps the next one out of the same period.
        val period = Span(T0 + 30 * MIN, T0 + 3 * HOUR)
        val s0 = BreakMachine.initial(T0, specs).copy(bars = mapOf(LABEL_20S to T0 + 45 * MIN))
        val events = ArrayList<Event>()
        run(s0, T0 + 4 * HOUR, listOf(period), events)
        val lookAways = started(events).filter { it.label == LABEL_20S && it.startMillis <= period.endMillis }.map { it.startMillis }
        assertEquals(listOf(T0 + 30 * MIN, period.endMillis), lookAways, "one at its start, the next where it ends")
    }

    @Test
    fun coming_back_to_a_screen_inside_the_night_the_line_was_away_into_owes_nothing() {
        // The start-up of 2026-09-30 on account 3: away from 11:13 (a wake walked in mode 2), into the night's no-screen
        // period (23:00 → 07:30), back at a screen at 02:33. The stretch the line was away for runs into the night: one
        // continuous no-screen period, whose occurrences it already took — so the rest of the night pulls nothing onto
        // the line, and nothing is owed.
        val night = Span(T0 + 12 * HOUR, T0 + 20 * HOUR + 30 * MIN)
        val chains = listOf(night)
        var s = BreakMachine.initial(T0 + MIN, specs, MODE_AWAY)
        s = run(s, T0 + 15 * HOUR + 20 * MIN, chains)
        val events = ArrayList<Event>()
        s = BreakMachine.switchMode(s, MODE_AT_SCREEN, chains, specs, events)
        s = run(s, T0 + 15 * HOUR + 30 * MIN, chains, events)
        assertNull(s.drag, "nothing is owed: $events")
        assertTrue(events.none { it is Event.Owed || it is Event.Started }, "$events")
        assertEquals(night.endMillis, BreakMachine.dueOf(s, LABEL_15MIN, chains), "the next 15 min break waits for the night's end")
    }

    @Test
    fun look_away_now_is_a_conducted_20s_break_that_bars_the_next_one() {
        val events = ArrayList<Event>()
        val s = BreakMachine.conduct(BreakMachine.initial(T0 + 5 * MIN, specs), emptyList(), specs, events)
        assertEquals(Event.Started(LABEL_20S, T0 + 5 * MIN, T0 + 5 * MIN + 20 * SEC, conducted = true), events.single())
        assertEquals(MODE_ON_BREAK, BreakMachine.effectiveMode(s))
        val after = run(s, T0 + 6 * MIN)
        assertEquals(T0 + 25 * MIN + 20 * SEC, after.bars[LABEL_20S])
    }

    @Test
    fun a_pause_learned_of_late_raises_the_bars_and_drops_a_pose_no_longer_owed() {
        val dragging = run(BreakMachine.initial(T0, specs), T0 + HOUR + 10 * MIN)
        assertEquals(LABEL_5MIN, dragging.drag?.label)
        val pause = Span(T0 + HOUR + MIN, T0 + HOUR + 8 * MIN)
        val healed = BreakMachine.absorbHistory(dragging, specs, stretches = listOf(pause))
        assertNull(healed.drag, "a 7-minute pause after the pose fell due is a rest: no 5 min break for an hour")
        assertEquals(pause.endMillis + HOUR, healed.bars[LABEL_5MIN])
        // The look-away the drag had taken in is due again, and a line at a screen enters it.
        val events = ArrayList<Event>()
        run(healed, healed.atMillis, events = events)
        assertEquals(listOf(LABEL_20S), started(events).map { it.label })
    }

    @Test
    fun the_plan_for_a_line_staying_at_a_screen_meets_only_the_look_aways_before_the_first_pose() {
        val placed = BreakMachine.predict(BreakMachine.initial(T0, specs), T0 + 6 * HOUR, emptyList(), specs)
        assertEquals(listOf(LABEL_20S, LABEL_20S), placed.map { it.label })
        assertTrue(placed.all { it.startMillis < T0 + HOUR })
    }

    @Test
    fun the_machine_round_trips_through_its_persisted_form() {
        val s = run(BreakMachine.initial(T0, specs), T0 + HOUR + 3 * MIN)
        val json = Json.encodeToString(BreakMachine.State.serializer(), s)
        assertEquals(s, Json.decodeFromString(BreakMachine.State.serializer(), json))
    }

    @Test
    fun moving_the_line_in_steps_or_at_once_gives_the_same_state() {
        // The runtime advances at its triggers and ticks; a prediction advances once. Both must be one machine.
        val once = run(BreakMachine.initial(T0, specs), T0 + 5 * HOUR)
        var stepped = BreakMachine.initial(T0, specs)
        var t = T0
        while (t < T0 + 5 * HOUR) {
            t += 37 * SEC
            stepped = run(stepped, minOf(t, T0 + 5 * HOUR))
        }
        assertEquals(once, stepped)
        val durations = specs.map { it.label to len(it.label) }
        assertTrue(durations.isNotEmpty())
    }
}
