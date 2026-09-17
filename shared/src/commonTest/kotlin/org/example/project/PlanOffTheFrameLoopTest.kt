package org.example.project

import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.engine.PROGRESSIVE_FIRST_STAGE_MILLIS
import org.example.project.scheduler.engine.SchedulerEngine
import org.example.project.scheduler.platform.DeviceKind
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.ui.TaskSchedulerViewModel
import org.example.project.time.AppClock

/**
 * CLAUDE.md hot path: **the plan is the one derivation allowed to be expensive** — 25-80 ms on a real
 * account ([PerfBenchmarkTest]) — and both production hosts run the engine on a main-thread scope (the
 * composition's on desktop, the foreground service's on Android). Spending those milliseconds there costs
 * four dropped frames every time a rule change settles, which is exactly when the user is typing.
 *
 * So the two expensive plan intents — and ONLY those two — are reduced on
 * [SchedulerEngine]'s `planDispatcher`. Nothing else moves: the same intents go through the same
 * [org.example.project.scheduler.state.SchedulerReducer], so there is still one definition of what a re-plan
 * is. These tests pin the routing, and [PlanConcurrencyTest] pins what the routing makes possible (two
 * threads reducing at once).
 */
class PlanOffTheFrameLoopTest {

    private val T0 = 1_700_000_000_000L
    private val DEBOUNCE_MILLIS = 1_000L

    /** Counts the coroutine dispatches routed through it, then hands them to [delegate] unchanged. */
    private class RecordingDispatcher(private val delegate: CoroutineDispatcher) : CoroutineDispatcher() {
        var dispatches = 0
            private set

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            dispatches++
            delegate.dispatch(context, block)
        }
    }

    private fun engine(
        vm: TaskSchedulerViewModel,
        scope: CoroutineScope,
        currentTime: () -> Long,
        planDispatcher: CoroutineDispatcher?,
    ) = SchedulerEngine(
        vm = vm,
        clock = object : AppClock {
            override fun nowMillis(): Long = T0 + currentTime()
        },
        scope = scope,
        planDispatcher = planDispatcher,
        deviceKind = DeviceKind.Desktop,
        screenActive = { true },
    )

    /** A titled task with the production screen breaks — enough for a fill to lay something down. */
    private fun seedAccount(vm: TaskSchedulerViewModel) {
        val cell = vm.state.value.lists[vm.state.value.rootListId]!!.cellIds.first()
        vm.dispatch(SchedulerIntent.SetCellTitle(cell, "Task A"))
        val id = vm.state.value.tasks.keys.first()
        vm.dispatch(SchedulerIntent.SetTaskMinimumTime(id, 45))
    }

    @Test
    fun the_engines_re_plan_is_reduced_on_the_plan_dispatcher_and_still_lands() = runTest {
        val scheduler = testScheduler
        val plan = RecordingDispatcher(StandardTestDispatcher(scheduler))
        val vm = TaskSchedulerViewModel(store = null, saveDispatcher = Dispatchers.Default)
        val engine = engine(vm, backgroundScope, { scheduler.currentTime }, plan)
        engine.start()
        seedAccount(vm)

        // The rule-change watcher's debounce, then its fill.
        advanceTimeBy(DEBOUNCE_MILLIS + 1)
        runCurrent()

        assertTrue(
            plan.dispatches > 0,
            "the re-plan was reduced on the caller's thread — the fill is back on the frame loop",
        )
        assertTrue(
            vm.state.value.panels.isNotEmpty(),
            "the re-plan went off-thread and never came back: no plan was committed",
        )
    }

    /**
     * The default is `null` — reduce inline, exactly as before this seam existed — so a host that has no
     * background dispatcher to give (a test, a headless shell) is not silently made asynchronous.
     */
    @Test
    fun with_no_plan_dispatcher_the_re_plan_is_reduced_inline() = runTest {
        val scheduler = testScheduler
        val vm = TaskSchedulerViewModel(store = null, saveDispatcher = Dispatchers.Default)
        val engine = engine(vm, backgroundScope, { scheduler.currentTime }, planDispatcher = null)
        engine.start()
        seedAccount(vm)

        advanceTimeBy(DEBOUNCE_MILLIS + 1)
        runCurrent()

        assertTrue(vm.state.value.panels.isNotEmpty(), "the inline path must still plan")
    }

    /**
     * The routing must not change WHAT is planned. Same account, same clock, same horizon: the plan the
     * engine commits through its dispatcher is the plan the reducer computes on the spot — for the same
     * progressive stages (`docs/scheduler_requirements.md` § *Progressive Calculation*): a re-plan to the first
     * stage, then extensions to twice as far each time, up to $t_{goal}$.
     */
    @Test
    fun going_off_thread_does_not_change_the_plan() = runTest {
        val scheduler = testScheduler
        val vm = TaskSchedulerViewModel(store = null, saveDispatcher = Dispatchers.Default)
        val engine = engine(vm, backgroundScope, { scheduler.currentTime }, StandardTestDispatcher(scheduler))
        engine.start()
        seedAccount(vm)
        val seeded = vm.state.value

        advanceTimeBy(DEBOUNCE_MILLIS + 1)
        runCurrent()

        // The instants the stages planned at, and they are TWO. The throttle is leading-scheduled, so the
        // re-plan is timed from the FIRST edit and the first stage runs at the edit's own instant; every stage
        // after it runs once the debounce has elapsed. A stage's cap is relative to its own `now` — and so now
        // is the plan itself, since a period the line has RETRACTED gives up exactly `[now, its end)`
        // (`docs/scheduler_requirements.md` § *mode 1*), so a run inside a §17 window starts at the line.
        // Asked with one instant for both, this compared two fills made a second apart and only passed while
        // nothing in the plan depended on where the line was: the panels agreed and the last stage's horizon
        // was a second out.
        var direct = seeded
        // TWO re-plans, at the two instants the engine made them: the one the edit itself triggers, and the
        // throttled one at the end of the debounce — which KEEPS the elapsed head of the first (a panel
        // straddling the line is truncated to it and merged with the new run, PRD §9). A single pass at a
        // single instant was what this compared against, and it only matched while nothing in the plan
        // depended on where the line was: a period the line has RETRACTED gives up exactly `[now, its end)`
        // (`docs/scheduler_requirements.md` § *mode 1*), so inside a §17 window the run starts at the line and
        // the second's difference between the two instants became a second's difference in the plan.
        for (now in listOf(T0, T0 + DEBOUNCE_MILLIS)) {
            val goal = SchedulerDomain.scheduleHorizonEndMillis(now, null)
            var stage = PROGRESSIVE_FIRST_STAGE_MILLIS
            var first = true
            while (true) {
                val cap = (now + stage).takeIf { it < goal }
                direct = SchedulerReducer.reduce(
                    direct,
                    if (first) SchedulerIntent.RefreshSchedule(now, cap) else SchedulerIntent.ExtendSchedule(now, cap),
                )
                first = false
                if (cap == null) break
                stage *= 2
            }
        }
        assertEquals(direct.panels, vm.state.value.panels)
    }
}
