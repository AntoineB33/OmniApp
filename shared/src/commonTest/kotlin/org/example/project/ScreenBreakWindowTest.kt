package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.BreakMachine
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.ScreenBreak

/**
 * CLAUDE.md "hot-path display derivations must scale with the SCREEN, not with total history": the breaks ahead of the
 * line are the break machine run forward ([BreakMachine.predict]) — one transition per break, never a search — so a far
 * span costs what it holds. Pinned here as the count the machine places, which is bounded by the requirements' own
 * bars whatever the configuration (the reported freeze was tens of thousands of markers under a shrunk interval).
 */
class ScreenBreakWindowTest {
    private val MIN = 60_000L
    private val SEC = 1_000L
    private val WEEK = 7L * 24 * 60 * MIN
    private val lookAway = ScreenBreak("look 20 feet away", intervalMillis = 20 * MIN, durationMillis = 20 * SEC)
    private val pose5 = ScreenBreak("take a 5min pose", intervalMillis = MIN, durationMillis = 5 * MIN, restBreak = true)

    @Test
    fun a_week_ahead_holds_at_most_what_the_bars_allow() {
        val specs = SchedulerDomain.dynamicPeriodSpecs(listOf(lookAway, pose5))
        val placed = BreakMachine.predictExpected(BreakMachine.initial(0L, specs), WEEK, emptyList(), specs)
        assertTrue(placed.isNotEmpty())
        // No 20 s break within 20 minutes of another break, and no 5 min one within an hour of a 5-minute stretch: at
        // most three look-aways and one pose an hour.
        assertTrue(placed.size <= 4 * 24 * 7, "${placed.size} breaks in a week")
    }

    @Test
    fun a_week_far_ahead_is_the_same_pattern_as_one_near_by() {
        // With nothing but the bars in play the machine settles into one rhythm: a week a hundred weeks out holds the
        // same number of breaks as the next one.
        val specs = SchedulerDomain.dynamicPeriodSpecs(listOf(lookAway, pose5))
        val far = BreakMachine.predictExpected(BreakMachine.initial(0L, specs), 101 * WEEK, emptyList(), specs)
        fun inWeek(k: Int) = far.count { it.startMillis >= k * WEEK && it.startMillis < (k + 1) * WEEK }
        // A week's edges cut the rhythm at different phases, so one break either side is the same pattern.
        assertTrue(kotlin.math.abs(inWeek(1) - inWeek(100)) <= 1, "week 1: ${inWeek(1)}, week 100: ${inWeek(100)}")
    }
}
