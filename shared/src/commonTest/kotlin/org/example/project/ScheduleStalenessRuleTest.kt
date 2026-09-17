package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.example.project.scheduler.engine.SchedulerEngine
import org.example.project.scheduler.platform.DeviceKind
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.ui.TaskSchedulerViewModel
import org.example.project.time.AppClock

/**
 * PRD §9 calculation events, against `docs/scheduler_requirements.md` § *Progressive Calculation*: once the schedule
 * for `t < t1` is definitive, *"all the next set of rules the scheduler will return … will all indicate the same
 * schedule rules for any t < t1"*. A re-plan of rules that did not change can only break that, so **time passing
 * never re-plans**: a rule CHANGE does (debounced), and the rolling horizon only extends the tail.
 *
 * This file used to pin the opposite — an hourly "staleness bound" that re-planned an untouched account — which is
 * exactly the re-plan the requirement forbids (removed 2026-09-17). The trigger is observed through
 * `SchedulerEngine.lastRescheduleMillis`, the one stamp every re-plan leaves.
 */
class ScheduleStalenessRuleTest {

    private val T0 = 1_700_000_000_000L

    // The debounce the rule-change watcher applies before its fill.
    private val DEBOUNCE_MILLIS = 1_000L

    private val HOUR = 60L * 60 * 1_000

    private class Harness(val engine: SchedulerEngine, val vm: TaskSchedulerViewModel)

    /** An engine whose clock follows virtual time, so `advanceTimeBy` moves the now-line with the scheduler. */
    private fun harness(currentTime: () -> Long, scope: CoroutineScope): Harness {
        val clock = object : AppClock {
            override fun nowMillis(): Long = T0 + currentTime()
        }
        val vm = TaskSchedulerViewModel(store = null, saveDispatcher = Dispatchers.Default)
        val engine = SchedulerEngine(
            vm = vm,
            clock = clock,
            scope = scope,
            deviceKind = DeviceKind.Desktop,
            screenActive = { true },
        )
        return Harness(engine, vm)
    }

    @Test
    fun an_untouched_plan_is_never_re_planned_by_time_alone() = runTest {
        val scheduler = testScheduler
        val h = harness({ scheduler.currentTime }, backgroundScope)
        h.engine.start()
        // Launch itself re-plans (the rule-change watcher's first emission).
        advanceTimeBy(DEBOUNCE_MILLIS + 1)
        runCurrent()
        val launchFill = h.engine.lastRescheduleMillis
        assertNotNull(launchFill)

        // Three hours of nothing changing: the definitive schedule is extended, never re-planned.
        advanceTimeBy(3 * HOUR)
        runCurrent()
        assertEquals(launchFill, h.engine.lastRescheduleMillis, "time passing re-planned an unchanged account")
    }

    @Test
    fun a_rule_change_re_plans_on_the_debounce() = runTest {
        val scheduler = testScheduler
        val h = harness({ scheduler.currentTime }, backgroundScope)
        h.engine.start()
        advanceTimeBy(DEBOUNCE_MILLIS + 1)
        runCurrent()

        val editedAt = HOUR / 2
        advanceTimeBy(editedAt - DEBOUNCE_MILLIS - 1)
        val root = h.vm.state.value.lists[h.vm.state.value.rootListId]!!
        h.vm.dispatch(SchedulerIntent.SetCellTitle(root.cellIds[0], "A"))
        advanceTimeBy(DEBOUNCE_MILLIS + 1)
        runCurrent()
        val afterEdit = h.engine.lastRescheduleMillis
        assertNotNull(afterEdit)
        assertTrue(
            afterEdit >= T0 + editedAt && afterEdit <= T0 + editedAt + DEBOUNCE_MILLIS + 1,
            "a rule change re-plans on the debounce, got ${afterEdit - T0}",
        )

        // And nothing after it but another change would.
        advanceTimeBy(2 * HOUR)
        runCurrent()
        assertEquals(afterEdit, h.engine.lastRescheduleMillis, "the edit's re-plan is the last one")
    }
}
