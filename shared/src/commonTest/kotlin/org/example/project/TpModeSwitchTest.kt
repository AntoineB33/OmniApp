package org.example.project

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.DynamicPeriods
import org.example.project.scheduler.domain.PeriodKinds
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.RulePlacement
import org.example.project.scheduler.model.SleepSchedule
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState

/**
 * `docs/scheduler_requirements.md` § *$now line$ 3 modes* against § *Progressive Calculation*: **the rules are
 * parameterized by the mode, and what they make definitive stays definitive for every mode.**
 *
 * *"When the schedule is definitive for any t < t₁, it means that for all the next set of rules the scheduler will
 * return until it is done, they will all indicate the same schedule rules for any t < t₁ (task panel scheduling
 * parameterized by now line and now line mode …)"*. The modes switch at any instant, so the rules' answer for every
 * path of modes cannot be a list of plans (user rule 2026-11-18: *"The schedule must take all the infinite
 * branches"*): it is the searched plan for the path it was made for, and **the instant schedule's policy** for every
 * other — laid from the line at a flip ([SchedulerIntent.SwitchTpMode]), nothing searched, the same on every device.
 * (2026-09-28 → 2026-11-18 the plan for the other mode class was found beside each plan and laid instead.)
 */
class TpModeSwitchTest {

    private val MIN = 60_000L
    private val HOUR = 60 * MIN
    private val NOW = 1_000_000_020_000L
    private val UNTIL = NOW + 6 * HOUR

    @BeforeTest
    fun openCalendar() {
        // A plan of hours, not the closed calendar's twenty minutes: a flip is about the definitive span ahead.
        CalendarHorizonFixture.show(8 * HOUR)
    }

    @AfterTest
    fun resetSeams() {
        SchedulerReducer.tpMode = { DynamicPeriods.MODE_AT_SCREEN }
        CalendarHorizonFixture.close()
    }

    /**
     * Two on-screen tasks and one a line covered by "no on-screen task" may still run ([PeriodKinds.NO_SCREEN]
     * resilience 1), so the two classes have two different plans; no night, so the whole span is schedulable. The
     * account's default screen breaks are kept: the plan found for the other class does not know where they will
     * fall, and laying it through them is part of what is pinned.
     */
    private fun account(): SchedulerState {
        var s = SchedulerState.empty()
        for (title in listOf("Code", "Mail", "Read")) {
            val cell = s.lists[s.rootListId]!!.cellIds.first { s.cells[it]?.taskId == null }
            s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(cell, title))
        }
        for (task in s.tasks.values) s = SchedulerReducer.reduce(s, SchedulerIntent.SetTaskMinimumTime(task.id, 20))
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetTaskResilience(id(s, "Read"), PeriodKinds.NO_SCREEN, 1.0))
        return SchedulerReducer.reduce(s, SchedulerIntent.SetSleepSchedule(SleepSchedule(sleepDurationMinutes = 0), todayEpochDay = 11_574L))
    }

    private fun id(s: SchedulerState, title: String): TaskId = s.tasks.values.first { it.title == title }.id

    private fun planned(mode: Int = DynamicPeriods.MODE_AT_SCREEN): SchedulerState {
        SchedulerReducer.tpMode = { mode }
        return SchedulerReducer.reduce(account(), SchedulerIntent.RefreshSchedule(NOW, horizonCapMillis = UNTIL))
    }

    private fun switchTo(s: SchedulerState, mode: Int, at: Long): SchedulerState {
        SchedulerReducer.tpMode = { mode }
        return SchedulerReducer.reduce(s, SchedulerIntent.SwitchTpMode(at))
    }

    private fun taskAt(runs: List<RulePlacement>, t: Long): TaskId? = runs.firstOrNull { it.startMillis <= t && t < it.endMillis }?.taskId

    /** Every minute from [from] to [until] where BOTH name a run (a dynamic period suspends one): the tasks named. */
    private fun agreeing(a: List<RulePlacement>, b: List<RulePlacement>, from: Long, until: Long): Pair<Int, List<Long>> {
        var compared = 0
        val disagreements = ArrayList<Long>()
        var t = from + 30_000
        while (t < until) {
            val x = taskAt(a, t)
            val y = taskAt(b, t)
            if (x != null && y != null) {
                compared++
                if (x != y) disagreements += t
            }
            t += MIN
        }
        return compared to disagreements
    }

    @Test
    fun a_flip_lays_the_instant_policy_from_the_line_to_the_plans_front() {
        val s = planned()
        val x = NOW + 37 * MIN
        val runs = mutableListOf<org.example.project.scheduler.state.SchedulerRunEntry>()
        SchedulerReducer.recordSchedulerRun = { runs += it }
        try {
            val away = switchTo(s, DynamicPeriods.MODE_AWAY, x)
            val laid = SchedulerDomain.placementsAhead(away.panels, x)
            assertEquals(setOf(id(s, "Read")), laid.map { it.taskId }.toSet(), "a covered line may only be given a resilient task")
            assertTrue(laid.maxOf { it.endMillis } >= UNTIL - 30 * MIN, "the policy is laid out to where the plan reached")
            val run = runs.single()
            assertEquals(org.example.project.scheduler.state.SchedulerRunEntry.Kind.ModeSwitch, run.kind)
        } finally {
            SchedulerReducer.recordSchedulerRun = { }
        }
    }

    @Test
    fun a_flip_is_the_same_answer_on_every_device() {
        // Nothing searched, no wall time read: the same state and the same switch give the same line, wherever.
        val s = planned()
        val x = NOW + 37 * MIN
        val one = switchTo(s, DynamicPeriods.MODE_AWAY, x)
        val two = switchTo(s, DynamicPeriods.MODE_AWAY, x)
        assertEquals(SchedulerDomain.placementsAhead(one.panels, x), SchedulerDomain.placementsAhead(two.panels, x))
        val backOne = switchTo(one, DynamicPeriods.MODE_AT_SCREEN, x + 34 * MIN)
        val backTwo = switchTo(two, DynamicPeriods.MODE_AT_SCREEN, x + 34 * MIN)
        assertEquals(
            SchedulerDomain.placementsAhead(backOne.panels, x + 34 * MIN),
            SchedulerDomain.placementsAhead(backTwo.panels, x + 34 * MIN),
        )
    }

    @Test
    fun a_flip_leaves_no_stretch_without_a_task_where_one_may_run() {
        val s = planned()
        val x = NOW + 37 * MIN
        val away = switchTo(s, DynamicPeriods.MODE_AWAY, x)
        val back = switchTo(away, DynamicPeriods.MODE_AT_SCREEN, x + 34 * MIN)
        // At a screen every task may run and nothing else restricts anybody, so no edge makes time left to nobody score
        // lower: an instant with no run is inside a dynamic period.
        var t = x + 34 * MIN + 1_000
        while (t < UNTIL) {
            val run = SchedulerDomain.placementsAhead(back.panels, x).any { it.startMillis <= t && t < it.endMillis }
            if (!run) {
                assertTrue(
                    back.panels.any { it.screenBreak && it.startEpochMillis <= t && t < it.endEpochMillis },
                    "no task at ${t - NOW} ms past the start, and no break there either",
                )
            }
            t += 29_000
        }
    }

    @Test
    fun only_a_change_of_class_changes_the_plan() {
        // Modes 2 and 3 differ in the cue alone (DynamicPeriods.breaksAreNotifiedAt).
        assertFalse(SchedulerDomain.tpModeFlipChangesPlan(DynamicPeriods.MODE_AWAY, DynamicPeriods.MODE_ON_BREAK))
        assertFalse(SchedulerDomain.tpModeFlipChangesPlan(DynamicPeriods.MODE_ON_BREAK, DynamicPeriods.MODE_AWAY))
        assertTrue(SchedulerDomain.tpModeFlipChangesPlan(DynamicPeriods.MODE_AT_SCREEN, DynamicPeriods.MODE_AWAY))
        assertTrue(SchedulerDomain.tpModeFlipChangesPlan(DynamicPeriods.MODE_ON_BREAK, DynamicPeriods.MODE_AT_SCREEN))
        // … and the placement itself agrees: the same plan in both covered modes.
        val locked = planned(DynamicPeriods.MODE_AWAY)
        val onBreak = planned(DynamicPeriods.MODE_ON_BREAK)
        assertEquals(SchedulerDomain.placementsAhead(locked.panels, NOW), SchedulerDomain.placementsAhead(onBreak.panels, NOW))
    }
}
