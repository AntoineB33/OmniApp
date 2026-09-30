package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.BreakMachine
import org.example.project.scheduler.domain.BreakMachine.Event
import org.example.project.scheduler.domain.DynamicPeriods
import org.example.project.scheduler.domain.DynamicPeriods.MODE_AT_SCREEN
import org.example.project.scheduler.domain.DynamicPeriods.MODE_AWAY
import org.example.project.scheduler.domain.DynamicPeriods.MODE_ON_BREAK
import org.example.project.scheduler.domain.FrozenScreenBreaks
import org.example.project.scheduler.domain.SchedulerDomain

/**
 * `docs/scheduler_requirements.md` § *Mode switching*: *"The current $now line$ mode can be decided anytime by a
 * program, but can't go in mode 1 during a '20s screen break' restrictive period. When $now line$ enters a '20s screen
 * break' restrictive period in mode 1, it gets in mode 3, and when it leaves it, it gets in mode 1 unless the user
 * wanted it to stay in mode 3, or unless it is in mode 2."* — the break machine's effective mode
 * ([BreakMachine.effectiveMode], read by the app through [SchedulerDomain.effectiveTpMode]).
 */
class ModeSwitchingTest {
    private val SEC = 1_000L
    private val MIN = 60_000L
    private val specs = SchedulerDomain.dynamicPeriodSpecs(SchedulerDomain.DEFAULT_SCREEN_BREAKS)

    /** The machine a line at a screen carries into its first look-away (due at 20 min), [intoIt] later. */
    private fun inside(intoIt: Long, events: MutableList<Event> = ArrayList()): BreakMachine.State =
        BreakMachine.advance(BreakMachine.initial(0L, specs), 20 * MIN + intoIt, emptyList(), specs, events)

    private fun record(machine: BreakMachine.State) = FrozenScreenBreaks(emptyList(), machine.atMillis, machine.atMillis, machine)

    @Test
    fun a_line_at_a_screen_that_enters_a_look_away_is_in_mode_3_until_it_leaves_it() {
        val s = inside(5 * SEC)
        assertEquals(MODE_ON_BREAK, SchedulerDomain.effectiveTpMode(record(s), MODE_AT_SCREEN, s.atMillis))
        val left = BreakMachine.advance(s, 20 * MIN + 21 * SEC, emptyList(), specs)
        assertEquals(MODE_AT_SCREEN, SchedulerDomain.effectiveTpMode(record(left), MODE_AT_SCREEN, left.atMillis))
    }

    @Test
    fun it_cannot_go_to_mode_1_during_a_look_away_it_entered_in_mode_3() {
        // Entered by a line away (mode 3, the button); the button clears at the unlock — the line stays in mode 3.
        var s = BreakMachine.initial(0L, specs, MODE_ON_BREAK)
        s = BreakMachine.advance(s, 20 * MIN + 5 * SEC, emptyList(), specs)
        assertEquals(DynamicPeriods.LABEL_20S, s.active?.label)
        s = BreakMachine.switchMode(s, MODE_AT_SCREEN, emptyList(), specs)
        assertEquals(MODE_ON_BREAK, BreakMachine.effectiveMode(s), "can't go in mode 1 during a 20s screen break")
    }

    @Test
    fun mode_2_is_never_held() {
        // "…unless it is in mode 2": a line in a look-away it entered at a screen that the devices now report locked
        // is in mode 2, not 3.
        val s = BreakMachine.switchMode(inside(5 * SEC), MODE_AWAY, emptyList(), specs)
        assertEquals(MODE_AWAY, BreakMachine.effectiveMode(s))
    }

    @Test
    fun the_hold_follows_the_break_the_look_away_grew_into() {
        // The chain rule can make the look-away the start of a longer break: "it" is the break the line entered.
        val s0 =
            BreakMachine.initial(0L, specs).copy(
                bars = mapOf(DynamicPeriods.LABEL_20S to 20 * MIN, DynamicPeriods.LABEL_5MIN to 20 * MIN + 10 * SEC, DynamicPeriods.LABEL_15MIN to 5 * 60 * MIN),
            )
        val events = ArrayList<Event>()
        val s = BreakMachine.advance(s0, 20 * MIN + 15 * SEC, emptyList(), specs, events)
        assertTrue(events.any { it is Event.Grew }, "$events")
        assertEquals(MODE_ON_BREAK, BreakMachine.effectiveMode(s), "held through the pose it grew into")
        val after = BreakMachine.advance(s, 26 * MIN, emptyList(), specs)
        assertEquals(MODE_AT_SCREEN, BreakMachine.effectiveMode(after))
    }

    @Test
    fun a_pose_entered_while_away_does_not_hold_a_line_that_comes_back_to_the_screen() {
        // Only a look-away holds the line; a pose the line is inside when it comes back is removed instead (the
        // requirements' exception to the frozen past).
        var s = BreakMachine.initial(0L, specs, MODE_ON_BREAK)
        s = BreakMachine.advance(s, 61 * MIN, emptyList(), specs)
        assertEquals(DynamicPeriods.LABEL_5MIN, s.active?.label, "the pose was entered where it fell due")
        val events = ArrayList<Event>()
        s = BreakMachine.switchMode(s, MODE_AT_SCREEN, emptyList(), specs, events)
        assertEquals(MODE_AT_SCREEN, BreakMachine.effectiveMode(s))
        assertTrue(events.any { it is Event.Removed })
    }
}
