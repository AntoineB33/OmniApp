package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.example.project.scheduler.domain.BankedBreak
import org.example.project.scheduler.domain.BreakMachine
import org.example.project.scheduler.domain.DynamicPeriods
import org.example.project.scheduler.domain.DynamicPeriods.Span
import org.example.project.scheduler.domain.SchedulerDomain

/**
 * `docs/scheduler_requirements.md` § *Use of the set of rules output*: a re-run of the scheduler *"removes everything
 * deduced from the previous set of rules but not saved in history"*. The break machine's bars are deduced; history is
 * the banked breaks, the breaks the app conducted and the "no screen" the devices observed
 * ([BreakMachine.rebuildFromHistory]).
 *
 * Account 3, 2026-09-30: a restart counted 37 minutes at an unlocked computer as "no screen" and barred the 5-min break
 * until 17:29:49. Nothing in history held that stretch, so the next re-run must drop the bar.
 */
class BreaksRebuiltFromHistoryTest {
    private val MIN = 60_000L
    private val HOUR = 60 * MIN
    private val T0 = 1_700_000_000_000L
    private val specs = SchedulerDomain.dynamicPeriodSpecs(SchedulerDomain.DEFAULT_SCREEN_BREAKS)

    private fun machine(bars: Map<String, Long>, drag: BreakMachine.Drag? = null) =
        BreakMachine.State(atMillis = T0, baseMode = DynamicPeriods.MODE_AT_SCREEN, bars = bars, drag = drag)

    @Test
    fun a_bar_history_does_not_hold_is_dropped_for_the_one_it_does() {
        // The last 5-min break ended two hours ago, so history bars the next one an hour after it: already due.
        val banked = listOf(BankedBreak(DynamicPeriods.LABEL_5MIN, T0 - 2 * HOUR - 5 * MIN, T0 - 2 * HOUR))
        val carried = machine(mapOf(DynamicPeriods.LABEL_20S to T0 + 5 * MIN, DynamicPeriods.LABEL_5MIN to T0 + HOUR - 10 * MIN, DynamicPeriods.LABEL_15MIN to T0 + HOUR))
        val rebuilt = BreakMachine.rebuildFromHistory(carried, specs, banked)
        assertEquals(T0 - HOUR, rebuilt.bars[DynamicPeriods.LABEL_5MIN], "the 5-min bar is the one history sets")
        // Due at the line in mode 1: the next step drags it, from the line.
        val stepped = BreakMachine.advance(rebuilt, T0, emptyList(), specs)
        assertEquals(DynamicPeriods.LABEL_5MIN, stepped.drag?.label)
    }

    @Test
    fun a_label_history_says_nothing_about_keeps_its_carried_bar() {
        // Nothing banked, nothing observed: the carried bars are the rested start, and a re-run must not push them on.
        val carried = machine(mapOf(DynamicPeriods.LABEL_20S to T0 + 5 * MIN, DynamicPeriods.LABEL_5MIN to T0 + 30 * MIN, DynamicPeriods.LABEL_15MIN to T0 + HOUR))
        assertEquals(carried, BreakMachine.rebuildFromHistory(carried, specs, emptyList()))
    }

    @Test
    fun an_observed_stretch_of_no_screen_is_history() {
        // A 10-min pause observed twenty minutes ago bars the 5-min break an hour past its end — the carried bar was lower.
        val carried = machine(mapOf(DynamicPeriods.LABEL_20S to T0 + 5 * MIN, DynamicPeriods.LABEL_5MIN to T0, DynamicPeriods.LABEL_15MIN to T0 + HOUR))
        val rebuilt = BreakMachine.rebuildFromHistory(carried, specs, emptyList(), stretches = listOf(Span(T0 - 30 * MIN, T0 - 20 * MIN)))
        assertEquals(T0 + 40 * MIN, rebuilt.bars[DynamicPeriods.LABEL_5MIN])
    }

    @Test
    fun the_drag_is_the_same_break_while_history_still_owes_it() {
        val banked = listOf(BankedBreak(DynamicPeriods.LABEL_5MIN, T0 - 2 * HOUR - 5 * MIN, T0 - 2 * HOUR))
        val drag = BreakMachine.Drag(DynamicPeriods.LABEL_5MIN, T0 - HOUR, listOf(DynamicPeriods.LABEL_5MIN))
        val carried = machine(mapOf(DynamicPeriods.LABEL_20S to T0 + 5 * MIN, DynamicPeriods.LABEL_5MIN to T0 - HOUR, DynamicPeriods.LABEL_15MIN to T0 + HOUR), drag)
        val rebuilt = BreakMachine.rebuildFromHistory(carried, specs, banked)
        assertEquals(drag, rebuilt.drag, "the owed pose keeps the instant it fell due: announced once")
    }

    @Test
    fun a_drag_history_no_longer_owes_is_dropped() {
        // The carried machine dragged a 5-min break, but history shows one taken forty minutes ago.
        val banked = listOf(BankedBreak(DynamicPeriods.LABEL_5MIN, T0 - 45 * MIN, T0 - 40 * MIN))
        val drag = BreakMachine.Drag(DynamicPeriods.LABEL_5MIN, T0 - 10 * MIN, listOf(DynamicPeriods.LABEL_5MIN))
        val carried = machine(mapOf(DynamicPeriods.LABEL_20S to T0 + 5 * MIN, DynamicPeriods.LABEL_5MIN to T0 - 10 * MIN, DynamicPeriods.LABEL_15MIN to T0 + HOUR), drag)
        val rebuilt = BreakMachine.rebuildFromHistory(carried, specs, banked)
        assertNull(rebuilt.drag)
        assertEquals(T0 + 20 * MIN, rebuilt.bars[DynamicPeriods.LABEL_5MIN])
    }
}
