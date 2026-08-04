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
import org.example.project.scheduler.engine.SCHEDULE_STALENESS_MILLIS
import org.example.project.scheduler.engine.SchedulerEngine
import org.example.project.scheduler.platform.DeviceKind
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.ui.TaskSchedulerViewModel
import org.example.project.time.AppClock

/**
 * PRD §9 calculation events: the schedule is re-planned when a rule CHANGES (debounced), **and** when the
 * last re-plan was [SCHEDULE_STALENESS_MILLIS] ago or more.
 *
 * The staleness half is a BOUND, not a tick — that distinction is the whole rule, so it is what these tests
 * pin down: every re-plan re-arms the hour, so an account being edited never reaches it and a quiet one
 * costs exactly one fill per hour. (Time passing still re-plans nothing on its own: the advance only banks
 * records and the rolling horizon only extends the tail.)
 *
 * The trigger is observed through `SchedulerEngine.lastRescheduleMillis` rather than through `panels`,
 * because re-planning an unchanged account is deliberately a no-op — the same inputs at a later `now`
 * produce the same continuation, so the reducer returns the state unchanged and nothing is persisted.
 */
class ScheduleStalenessRuleTest {

    private val T0 = 1_700_000_000_000L

    // The engine polls the bound at its production cadence, so a re-plan lands in the first poll at or after
    // the due instant — never before it, never a whole poll late.
    private val POLL_MILLIS = 30_000L

    // The debounce the rule-change watcher applies before its fill.
    private val DEBOUNCE_MILLIS = 1_000L

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

    private fun assertRePlannedAt(dueMillis: Long, actual: Long?, message: String) {
        assertNotNull(actual, message)
        assertTrue(
            actual >= dueMillis && actual <= dueMillis + POLL_MILLIS,
            "$message: expected a re-plan in [${dueMillis - T0}, ${dueMillis - T0 + POLL_MILLIS}] after T0, " +
                "got ${actual - T0}",
        )
    }

    @Test
    fun an_untouched_plan_is_re_planned_once_an_hour() = runTest {
        val scheduler = testScheduler
        val h = harness({ scheduler.currentTime }, backgroundScope)
        h.engine.start()
        // Launch itself re-plans (the rule-change watcher's first emission), which starts the hour.
        advanceTimeBy(DEBOUNCE_MILLIS + 1)
        runCurrent()
        val launchFill = h.engine.lastRescheduleMillis
        assertNotNull(launchFill)

        // Time alone does not re-plan — right up to the hour.
        advanceTimeBy(SCHEDULE_STALENESS_MILLIS - 60_000)
        runCurrent()
        assertEquals(launchFill, h.engine.lastRescheduleMillis, "nothing re-plans before the hour is up")

        // ...and at the hour it re-plans, re-arming for the next one.
        advanceTimeBy(2 * POLL_MILLIS + 60_000)
        runCurrent()
        val second = h.engine.lastRescheduleMillis
        assertRePlannedAt(launchFill + SCHEDULE_STALENESS_MILLIS, second, "a plan standing an hour is re-planned")

        // The next hour is counted from that re-plan, not from launch.
        advanceTimeBy(SCHEDULE_STALENESS_MILLIS - 60_000)
        runCurrent()
        assertEquals(second, h.engine.lastRescheduleMillis, "the bound is re-armed by the re-plan it fired")

        advanceTimeBy(2 * POLL_MILLIS + 60_000)
        runCurrent()
        assertRePlannedAt(
            second!! + SCHEDULE_STALENESS_MILLIS,
            h.engine.lastRescheduleMillis,
            "an untouched account costs exactly one fill per hour",
        )
    }

    @Test
    fun a_rule_change_re_arms_the_hour_so_an_edited_account_never_reaches_the_bound() = runTest {
        val scheduler = testScheduler
        val h = harness({ scheduler.currentTime }, backgroundScope)
        h.engine.start()
        advanceTimeBy(DEBOUNCE_MILLIS + 1)
        runCurrent()

        // Half an hour in, the user edits the task tree: the debounced rule-change watcher re-plans...
        val editedAt = SCHEDULE_STALENESS_MILLIS / 2
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

        // ...so the hour that started at launch elapses with no stale re-plan of its own.
        advanceTimeBy(SCHEDULE_STALENESS_MILLIS - editedAt)
        runCurrent()
        assertEquals(afterEdit, h.engine.lastRescheduleMillis, "the bound is measured from the LAST re-plan")

        // A full hour after the edit is what triggers the next one.
        advanceTimeBy(SCHEDULE_STALENESS_MILLIS)
        runCurrent()
        assertRePlannedAt(
            afterEdit + SCHEDULE_STALENESS_MILLIS,
            h.engine.lastRescheduleMillis,
            "the stale re-plan comes an hour after the edit",
        )
    }
}
