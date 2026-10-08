package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import org.example.project.scheduler.domain.DynamicPeriods
import org.example.project.scheduler.domain.SchedulerDomain

/**
 * User rule 2026-10-08: "Switching to now line mode 3 doesn't change the input in itself … The previous set of rules
 * output is therefore still applied and the schedule in the future doesn't change." The mode a plan is FOR is one of
 * modes 1 and 2; mode 3 keeps whichever was in force.
 */
class PlanModeTest {
    private val atScreen = DynamicPeriods.MODE_AT_SCREEN
    private val away = DynamicPeriods.MODE_AWAY
    private val onBreak = DynamicPeriods.MODE_ON_BREAK

    /** The plan modes a line walking through [modes] is under, and at which steps a plan of another class is needed. */
    private fun walk(vararg modes: Int): Pair<List<Int>, List<Int>> {
        var held = atScreen
        val planModes = ArrayList<Int>()
        val flips = ArrayList<Int>()
        modes.forEachIndexed { i, mode ->
            val after = SchedulerDomain.planModeAfter(held, mode)
            if (i > 0 && SchedulerDomain.tpModeFlipChangesPlan(held, after)) flips += i
            held = after
            planModes += after
        }
        return planModes to flips
    }

    @Test
    fun mode_3_keeps_the_plan_in_force_and_only_an_arrival_in_mode_1_or_2_under_the_others_plan_changes_it() {
        // At a screen, "I'm away", back: nothing changes.
        assertEquals(listOf(atScreen, atScreen, atScreen) to emptyList(), walk(atScreen, onBreak, atScreen))
        // Locked, then declared away: still the covered plan; unlocked: the at-screen plan, at that step.
        assertEquals(listOf(away, away, atScreen) to listOf(2), walk(away, onBreak, atScreen))
        // At a screen, away, then every device locked with no declaration: the covered plan, at the last step.
        assertEquals(listOf(atScreen, atScreen, away) to listOf(2), walk(atScreen, onBreak, away))
        // A lock and an unlock are what they were.
        assertEquals(listOf(atScreen, away, atScreen) to listOf(1, 2), walk(atScreen, away, atScreen))
        // Launched in mode 3: the plan is the at-screen one until the line says otherwise.
        assertEquals(listOf(atScreen) to emptyList(), walk(onBreak))
    }
}
