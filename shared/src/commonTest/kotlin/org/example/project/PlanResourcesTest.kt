package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.engine.PROGRESSIVE_STAGE_SEARCH_MILLIS
import org.example.project.scheduler.engine.SchedulerEngine
import org.example.project.scheduler.engine.pacedStepSearchMillis
import org.example.project.scheduler.persistence.SchedulerStateCodec
import org.example.project.scheduler.platform.DeviceKind
import org.example.project.scheduler.state.DEFAULT_PLAN_RESOURCES
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState
import org.example.project.scheduler.ui.TaskSchedulerViewModel
import org.example.project.time.AppClock

/**
 * `docs/scheduler/scheduler_requirements.md` § *Progressive Calculation*, § *Two paces* (user rule 2026-11-18): *"the 10 minute
 * per 10 seconds minimum scheduler from now to now + 168h with a pace varying with the hyperparameter q"*.
 *
 * A run walks ten-minute steps from the line to $t_{goal}$ = `now + 168 h`; each step searches `q` of what the
 * ten-second pace leaves it. `q` ([SchedulerState.planResources]) replaced the calculation time limit after a change
 * (2026-10-04), which stopped a run wherever it had reached.
 */
class PlanResourcesTest {

    /** Tuesday 2023-11-14, 22:13 UTC. */
    private val T0 = 1_700_000_000_000L
    private val DEBOUNCE_MILLIS = 1_000L

    @Test
    fun a_step_searches_q_of_what_the_pace_leaves() {
        // A device filling an hour in a tenth of a second: a ten-minute step costs ~17 ms of its own passes.
        val fast = 10.0
        val full = pacedStepSearchMillis(10 * 60_000L, fast, 1.0)
        assertTrue(full in PROGRESSIVE_STAGE_SEARCH_MILLIS - 20..PROGRESSIVE_STAGE_SEARCH_MILLIS, "q = 1: all the pace leaves ($full ms)")
        assertEquals(0L, pacedStepSearchMillis(10 * 60_000L, fast, 0.0), "q = 0: no search, the fastest run")
        assertEquals(full / 4, pacedStepSearchMillis(10 * 60_000L, fast, 0.25), "q = 0.25: a quarter of it", )
        // Out of bounds is clamped, NaN searches nothing.
        assertEquals(full, pacedStepSearchMillis(10 * 60_000L, fast, 7.0))
        assertEquals(0L, pacedStepSearchMillis(10 * 60_000L, fast, -1.0))
        assertEquals(0L, pacedStepSearchMillis(10 * 60_000L, fast, Double.NaN))
        // A device whose own passes already take the pace searches nothing, whatever q is: the pace is never given up.
        assertEquals(0L, pacedStepSearchMillis(10 * 60_000L, 0.01, 1.0))
    }

    @Test
    fun q_is_a_local_setting_kept_in_its_bounds_persisted_and_never_synced() {
        val base = SchedulerState.empty()
        assertEquals(DEFAULT_PLAN_RESOURCES, base.planResources)
        val r = { s: SchedulerState, q: Double -> SchedulerReducer.reduce(s, SchedulerIntent.SetPlanResources(q)) }
        assertEquals(0.6, r(base, 0.6).planResources)
        assertEquals(0.0, r(base, -3.0).planResources)
        assertEquals(1.0, r(base, 9.0).planResources)
        assertEquals(DEFAULT_PLAN_RESOURCES, r(r(base, 0.6), Double.NaN).planResources)
        assertTrue(r(base, DEFAULT_PLAN_RESOURCES) === base, "nothing changed")

        val set = r(base, 0.6)
        // Persisted…
        assertEquals(0.6, SchedulerStateCodec.decode(SchedulerStateCodec.encode(set))!!.planResources)
        // …never synced: a device's budget is not an authoritative change, and a pull keeps this device's.
        assertEquals(SchedulerStateCodec.syncFingerprint(base), SchedulerStateCodec.syncFingerprint(set))
        assertEquals(0.6, base.withLocalViewStateFrom(set).planResources)
        // A payload written before q existed, holding the time limit it replaced: the default, the old key ignored.
        val untouched = SchedulerStateCodec.encode(base)
        assertTrue("planResources" !in untouched, "an untouched q writes no field")
        val previous = untouched.replaceFirst("{", "{\"planCalculationLimitSeconds\":30,")
        assertEquals(DEFAULT_PLAN_RESOURCES, SchedulerStateCodec.decode(previous)!!.planResources)
        // A hand-edited payload out of bounds is healed.
        val wild = untouched.replaceFirst("{", "{\"planResources\":4.5,")
        assertEquals(1.0, SchedulerStateCodec.decode(wild)!!.planResources)
    }

    @Test
    fun a_run_reaches_a_week_from_the_line_whatever_q_is() = runTest {
        for (q in listOf(0.0, 1.0)) {
            val scheduler = testScheduler
            val start = scheduler.currentTime
            val vm = TaskSchedulerViewModel(store = null, saveDispatcher = Dispatchers.Default)
            vm.dispatch(SchedulerIntent.SetPlanResources(q))
            val engine = SchedulerEngine(
                vm = vm,
                clock = object : AppClock {
                    override fun nowMillis(): Long = T0 + scheduler.currentTime
                },
                scope = backgroundScope,
                tz = TimeZone.UTC,
                deviceKind = DeviceKind.Desktop,
                screenActive = { true },
            )
            engine.start()
            val cell = vm.state.value.lists[vm.state.value.rootListId]!!.cellIds.first()
            vm.dispatch(SchedulerIntent.SetCellTitle(cell, "Task A"))
            advanceTimeBy(DEBOUNCE_MILLIS + 1)
            runCurrent()
            val now = T0 + scheduler.currentTime
            assertTrue(scheduler.currentTime - start < 60_000L)
            // Every hour of the week is planned (the 1-hour refill margin aside), with no calendar open at all.
            assertTrue(
                SchedulerDomain.firstFreeMoment(vm.state.value.panels, now) >= now + SchedulerDomain.SCHEDULE_HORIZON_MILLIS - 60 * 60_000L,
                "q = $q: the run stopped ${(now + SchedulerDomain.SCHEDULE_HORIZON_MILLIS - SchedulerDomain.firstFreeMoment(vm.state.value.panels, now)) / 3_600_000} h short of now + 168 h",
            )
        }
    }
}
