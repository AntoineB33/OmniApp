package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.engine.PROGRESSIVE_FIRST_STAGE_MILLIS
import org.example.project.scheduler.engine.SchedulerEngine
import org.example.project.scheduler.platform.DeviceKind
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.ui.TaskSchedulerViewModel
import org.example.project.time.AppClock

/**
 * `docs/scheduler_requirements.md` § *Progressive Calculation*, the third stopping condition (user rule,
 * 2026-09-18): *"The scheduler stops when the schedule is definitive up to the end of the current week, the
 * last displayed time in the calendar, and 10 minutes after now, or if the set of rules is too heavy or if the
 * calculation time limit for on task tree change is reached."*
 *
 * The first two are instants and are pinned in `ScheduleHorizonTest`. This one is WALL TIME, and what makes it
 * a stopping condition rather than a pause is that nothing may quietly resume the stages it cut short — the
 * rolling-horizon watcher exists precisely to close the shortfall it leaves, and would otherwise restart them a
 * poll later with a fresh budget, for ever.
 *
 * The limit is injected as zero here, so the first stage is also the last: real time cannot be advanced by a
 * virtual-clock test, and the rule under test is what happens AFTER the limit is reached, not how long it is.
 */
class PlanCalculationLimitTest {

    /** Tuesday 2023-11-14, 22:13 UTC — mid-week, so $t_{goal}$ is four days out and needs many stages. */
    private val T0 = 1_700_000_000_000L
    private val DEBOUNCE_MILLIS = 1_000L

    private fun engine(
        vm: TaskSchedulerViewModel,
        scope: CoroutineScope,
        currentTime: () -> Long,
        limitMillis: Long,
    ) = SchedulerEngine(
        vm = vm,
        clock = object : AppClock {
            override fun nowMillis(): Long = T0 + currentTime()
        },
        scope = scope,
        calculationLimitMillis = limitMillis,
        tz = TimeZone.UTC,
        deviceKind = DeviceKind.Desktop,
        screenActive = { true },
    )

    private fun seedAccount(vm: TaskSchedulerViewModel) {
        val cell = vm.state.value.lists[vm.state.value.rootListId]!!.cellIds.first()
        vm.dispatch(SchedulerIntent.SetCellTitle(cell, "Task A"))
        val id = vm.state.value.tasks.entries.first { it.value.title == "Task A" }.key
        vm.dispatch(SchedulerIntent.SetTaskMinimumTime(id, 45))
    }

    /**
     * How far the fill's own PICKS reach — not [SchedulerDomain.firstFreeMoment], which walks the contiguous
     * chain of panels and so runs straight through tonight's projected sleep window (a one-hour stage on this
     * account "covers" eight hours by that measure, all but the first 47 minutes of it a night nobody planned).
     */
    private fun planFront(vm: TaskSchedulerViewModel): Long =
        vm.state.value.panels.filter { it.auto && !it.sleep && !it.screenBreak }
            .maxOfOrNull { it.endEpochMillis } ?: 0L

    @Test
    fun a_fill_that_reaches_the_limit_stops_and_nothing_resumes_it() = runTest {
        val scheduler = testScheduler
        val vm = TaskSchedulerViewModel(store = null, saveDispatcher = Dispatchers.Default)
        val engine = engine(vm, backgroundScope, { scheduler.currentTime }, limitMillis = 0)
        engine.start()
        seedAccount(vm)

        advanceTimeBy(DEBOUNCE_MILLIS + 1)
        runCurrent()

        // $t_{goal}$ is the end of the week — days away — but the limit ran out after the first stage, so the
        // front is that stage's horizon and not the goal.
        val goal = SchedulerDomain.scheduleHorizonEndMillis(T0, null, TimeZone.UTC)
        val stopped = planFront(vm)
        assertTrue(stopped > 0, "the first stage must still have been published")
        assertTrue(stopped < goal, "the fill reached the goal despite the limit: stopped at $stopped, goal $goal")
        assertTrue(
            stopped <= T0 + DEBOUNCE_MILLIS + PROGRESSIVE_FIRST_STAGE_MILLIS,
            "more than one stage ran under a zero limit (reached ${(stopped - T0) / 60_000} min in)",
        )

        // …and it STAYS there. The plan is short of $t_{goal}$, which is exactly the shortfall the
        // rolling-horizon watcher is built to close: half an hour of polling must not walk the stages on
        // again under a limit that has already been spent.
        advanceTimeBy(30 * 60_000L)
        runCurrent()
        assertEquals(stopped, planFront(vm), "an extension resumed the fill the calculation limit had stopped")
    }

    @Test
    fun a_rule_change_asks_the_question_again() = runTest {
        val scheduler = testScheduler
        val vm = TaskSchedulerViewModel(store = null, saveDispatcher = Dispatchers.Default)
        val engine = engine(vm, backgroundScope, { scheduler.currentTime }, limitMillis = 0)
        engine.start()
        seedAccount(vm)
        advanceTimeBy(DEBOUNCE_MILLIS + 1)
        runCurrent()
        // Half an hour of polling changes nothing — the scheduler has stopped for this question.
        advanceTimeBy(30 * 60_000L)
        runCurrent()
        val stopped = vm.state.value.panels

        // The stand-down is about ONE question. Ask another — a new minimum time is a rule change — and the
        // scheduler plans again from the line, however expensive the last answer was. (What it REACHES is the
        // stage it can afford, which under a zero limit is one and may be shorter than what it replaces: the
        // rule is that it re-planned at all.)
        val taskA = vm.state.value.tasks.entries.first { it.value.title == "Task A" }.key
        vm.dispatch(SchedulerIntent.SetTaskMinimumTime(taskA, 20))
        advanceTimeBy(DEBOUNCE_MILLIS + 1)
        runCurrent()
        assertTrue(
            vm.state.value.panels != stopped,
            "a rule change must void the stand-down, but the plan was left exactly as the stop left it " +
                "(before: ${stopped.size} panels ending ${stopped.maxOfOrNull { it.endEpochMillis }}, " +
                "after: ${vm.state.value.panels.size} ending ${vm.state.value.panels.maxOfOrNull { it.endEpochMillis }}, " +
                "min now ${vm.state.value.tasks.getValue(taskA).minimumMinutes})",
        )
    }

    @Test
    fun with_no_limit_in_the_way_the_stages_reach_the_end_of_the_week() = runTest {
        val scheduler = testScheduler
        val vm = TaskSchedulerViewModel(store = null, saveDispatcher = Dispatchers.Default)
        val engine = engine(vm, backgroundScope, { scheduler.currentTime }, limitMillis = Long.MAX_VALUE)
        engine.start()
        seedAccount(vm)
        advanceTimeBy(DEBOUNCE_MILLIS + 1)
        runCurrent()

        // With no calendar open at all: $t_{goal}$ is still the end of the current week (user rule, 2026-09-18).
        val goal = SchedulerDomain.scheduleHorizonEndMillis(T0 + DEBOUNCE_MILLIS, null, TimeZone.UTC)
        assertTrue(goal - T0 > 4 * 24 * 60 * 60_000L, "the goal should be days out on a Tuesday")
        assertTrue(
            SchedulerDomain.firstFreeMoment(vm.state.value.panels, T0 + DEBOUNCE_MILLIS) >= goal,
            "the stages must reach the end of the week",
        )
    }
}
