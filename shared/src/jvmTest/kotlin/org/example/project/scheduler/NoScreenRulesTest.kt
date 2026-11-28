package org.example.project.scheduler

import org.example.project.scheduler.domain.BreakMachine
import org.example.project.scheduler.domain.DynamicPeriods
import org.example.project.scheduler.domain.RuleEngineBridge
import org.example.project.scheduler.ruleline.Interval
import org.example.project.scheduler.ruleline.IntervalSet
import org.example.project.scheduler.ruleline.LineState
import org.example.project.scheduler.ruleline.Op
import org.example.project.scheduler.ruleline.Owner
import org.example.project.scheduler.ruleline.Pos
import org.example.project.scheduler.ruleline.Rational
import org.example.project.scheduler.ruleline.Region
import org.example.project.scheduler.ruleline.applyEdit
import org.example.project.scheduler.ruleline.ruleSetOf
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The user's rules about the screen (2026-11-28): any screen break is "no screen", and "no screen" is ("no computer
 * unlocked" or "not on computer") and ("no phone unlocked" or "not on phone").
 */
class NoScreenRulesTest {

    private fun span(a: Int, b: Int) = Region.of(IntervalSet.of(listOf(Interval.of(Pos(Rational.of(a)), Pos(Rational.of(b)), false, true))))

    @Test
    fun noScreenIsWhereNeitherDeviceIsInUse_andEveryBreak() {
        val rules = ruleSetOf(RuleEngineBridge.BREAK_RULES)
        val s = applyEdit(
            rules, LineState.empty(),
            listOf(
                Op.put("NoComputerUnlocked", span(0, 10)), // the computer locked on [0, 10)
                Op.put("NotOnComputer", span(10, 15)), // then unlocked, but the user away from it on [10, 15)
                Op.put("NotOnPhone", span(5, 30)), // the phone not in use on [5, 30)
                Op.put("Break20s", span(40, 41)),
            ),
            Owner.USER,
        )
        assertEquals((span(5, 15) or span(40, 41)).toString(), s.line.presence(RuleEngineBridge.NO_SCREEN).toString())
    }

    @Test
    fun theBreakTakenOnReturnEndsTheRest_soTheFiveMinuteHourRunsFromItsEnd() {
        val min = 60_000L
        val specs = listOf(
            DynamicPeriods.Spec(DynamicPeriods.LABEL_20S, 20_000, 20 * min),
            DynamicPeriods.Spec(DynamicPeriods.LABEL_5MIN, 5 * min, 60 * min),
            DynamicPeriods.Spec(DynamicPeriods.LABEL_15MIN, 15 * min, 120 * min),
        )
        val t0 = 1_800_000_000_000L
        // A night of "no screen" from 30 min to 9 h 30 after the start, the line at a screen around it.
        val night = DynamicPeriods.Span(t0 + 30 * min, t0 + 570 * min)
        val placed = BreakMachine.predictExpected(BreakMachine.initial(t0, specs), t0 + 720 * min, listOf(night), specs)
        val first20s = placed.first { it.label == DynamicPeriods.LABEL_20S && it.startMillis >= night.endMillis }
        assertEquals(night.endMillis, first20s.startMillis, "a 20 s break on return")
        val first5 = placed.first { it.label == DynamicPeriods.LABEL_5MIN && it.startMillis >= night.endMillis }
        assertEquals(first20s.endMillis + 60 * min, first5.startMillis, "the hour runs from the end of that break")
        val first15 = placed.first { it.label == DynamicPeriods.LABEL_15MIN && it.startMillis >= night.endMillis }
        assertEquals(first20s.endMillis + 120 * min, first15.startMillis)
    }
}
