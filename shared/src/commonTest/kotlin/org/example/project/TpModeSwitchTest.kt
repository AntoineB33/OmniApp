package org.example.project

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.DynamicPeriods
import org.example.project.scheduler.domain.PeriodKinds
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.RulePlacement
import org.example.project.scheduler.model.SleepSchedule
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.persistence.SchedulerStateCodec
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState

/**
 * `docs/scheduler_requirements.md` § *$now line$ 3 modes* against § *Progressive Calculation*: **the rules are
 * parameterized by the mode, and what they make definitive stays definitive for every mode.**
 *
 * *"When the schedule is definitive for any t < t₁, it means that for all the next set of rules the scheduler will
 * return until it is done, they will all indicate the same schedule rules for any t < t₁ (task panel scheduling
 * parameterized by now line and now line mode …)"*. A flip used to re-plan from the line — a search whose answer
 * depends on the time it is given — so the line locking and unlocking again could hand it a different schedule from
 * the one already published for the mode it came back to. The plan for the other mode class is now found beside
 * the line's own ([SchedulerState.otherModePlan]) and a flip LAYS it ([SchedulerIntent.SwitchTpMode]).
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
    fun a_plan_is_found_for_the_other_mode_class_to_the_same_front() {
        val s = planned()
        val other = assertNotNull(s.otherModePlan, "no plan was found for the covered modes")
        assertTrue(other.covered, "the line is at a screen: the other class is the covered one")
        assertEquals(UNTIL, other.untilMillis)
        assertEquals(UNTIL, other.lineModeUntilMillis)
        assertEquals(SchedulerDomain.schedulingSignature(s), other.rulesKey)
        assertTrue(other.placements.isNotEmpty(), "a task resilient to no-screen may run: the covered plan is not empty")
        assertEquals(
            setOf(id(s, "Read")),
            other.placements.map { it.taskId }.toSet(),
            "a covered line may only be given a task resilient to 'no on-screen task'",
        )
        val own = SchedulerDomain.placementsAhead(s.panels, NOW).map { it.taskId }.toSet()
        assertTrue(id(s, "Code") in own && id(s, "Mail") in own, "the line's own plan is the at-screen one")
    }

    @Test
    fun a_flip_lays_the_plan_found_for_the_new_class() {
        val s = planned()
        val found = s.otherModePlan!!.placements
        val x = NOW + 37 * MIN
        val away = switchTo(s, DynamicPeriods.MODE_AWAY, x)
        val laid = SchedulerDomain.placementsAhead(away.panels, x)
        val (compared, wrong) = agreeing(laid, found, x, UNTIL)
        assertTrue(compared > 200, "too little of the span compared ($compared minutes) for this to be about anything")
        assertTrue(wrong.isEmpty(), "the flip did not lay the rules already found for mode 2: differs at ${wrong.take(5)}")
        assertEquals(setOf(id(s, "Read")), laid.map { it.taskId }.toSet())
    }

    @Test
    fun a_flip_and_a_flip_back_return_to_the_schedule_already_published() {
        val s = planned()
        val published = SchedulerDomain.placementsAhead(s.panels, NOW)
        val away = switchTo(s, DynamicPeriods.MODE_AWAY, NOW + 37 * MIN)
        val leftBehind = assertNotNull(away.otherModePlan)
        assertFalse(leftBehind.covered, "the plan the line left is the at-screen one")
        val back = NOW + 71 * MIN
        val atScreen = switchTo(away, DynamicPeriods.MODE_AT_SCREEN, back)
        val (compared, wrong) = agreeing(SchedulerDomain.placementsAhead(atScreen.panels, back), published, back, UNTIL)
        assertTrue(compared > 200, "too little of the span compared ($compared minutes) for this to be about anything")
        assertTrue(
            wrong.isEmpty(),
            "locking and unlocking rewrote the at-screen schedule already published as definitive: differs at ${wrong.take(5)}",
        )
        // … and the covered plan is held again, for the next lock.
        assertTrue(assertNotNull(atScreen.otherModePlan).covered)
        // Control: what a flip used to do — re-plan from the line — does NOT come back to it. The half hour away
        // served the resilient task, so the lags the search reads have moved.
        val replanned = SchedulerReducer.reduce(away.copy(otherModePlan = null), SchedulerIntent.RefreshSchedule(back, horizonCapMillis = UNTIL))
        val (_, rewritten) = agreeing(SchedulerDomain.placementsAhead(replanned.panels, back), published, back, UNTIL)
        assertTrue(rewritten.isNotEmpty(), "control: a re-plan at the flip back happens to reproduce the published plan")
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
    fun under_other_rules_the_plan_held_is_no_answer_and_the_flip_re_plans() {
        val s = planned()
        val edited = SchedulerReducer.reduce(s, SchedulerIntent.SetTaskMinimumTime(id(s, "Read"), 35))
        assertNotEquals(s.otherModePlan!!.rulesKey, SchedulerDomain.schedulingSignature(edited))
        assertNull(SchedulerDomain.otherModePlanFor(edited, DynamicPeriods.MODE_AWAY, NOW + MIN))
        val away = switchTo(edited, DynamicPeriods.MODE_AWAY, NOW + MIN)
        val plan = assertNotNull(away.otherModePlan, "the re-plan finds the other class's plan for the new rules")
        assertEquals(SchedulerDomain.schedulingSignature(away), plan.rulesKey)
        assertFalse(plan.covered, "the line is covered now: the plan found beside it is the at-screen one")
    }

    @Test
    fun an_extension_keeps_what_the_other_class_already_had_and_reaches_the_new_front() {
        SchedulerReducer.tpMode = { DynamicPeriods.MODE_AT_SCREEN }
        val first = SchedulerReducer.reduce(account(), SchedulerIntent.RefreshSchedule(NOW, horizonCapMillis = NOW + HOUR))
        val before = first.otherModePlan!!
        assertEquals(NOW + HOUR, before.untilMillis)
        val later = NOW + 10 * MIN
        val extended = SchedulerReducer.reduce(first, SchedulerIntent.ExtendSchedule(later, horizonCapMillis = NOW + 3 * HOUR))
        val after = assertNotNull(extended.otherModePlan)
        assertEquals(NOW + 3 * HOUR, after.untilMillis)
        val (compared, wrong) = agreeing(after.placements, before.placements, later, NOW + HOUR)
        assertTrue(compared > 30, "too little compared ($compared)")
        assertTrue(wrong.isEmpty(), "an extension rewrote the other class's definitive plan at ${wrong.take(5)}")
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

    @Test
    fun the_other_mode_plan_is_in_memory_only() {
        val s = planned()
        assertNotNull(s.otherModePlan)
        val decoded = assertNotNull(SchedulerStateCodec.decode(SchedulerStateCodec.encode(s)))
        assertNull(decoded.otherModePlan, "derived: never persisted")
        assertEquals(
            SchedulerStateCodec.syncFingerprint(s),
            SchedulerStateCodec.syncFingerprint(s.copy(otherModePlan = null)),
            "derived: never on the wire",
        )
    }
}
