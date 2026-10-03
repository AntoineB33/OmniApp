package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.engine.SchedulerEngine
import org.example.project.scheduler.persistence.SchedulerStateCodec
import org.example.project.scheduler.platform.DeviceKind
import org.example.project.scheduler.state.PlanBasis
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState
import org.example.project.scheduler.ui.TaskSchedulerViewModel
import org.example.project.time.AppClock

/**
 * `docs/invariants/scheduler.md` § *When the plan is recomputed*: **a restart is not a rule change.** Every launch
 * used to re-plan (the rule-change watcher took the loaded rules for an edit), rewriting a schedule
 * `docs/scheduler_requirements.md` § *Progressive Calculation* had already made definitive. The plan now carries the
 * rules it was made for ([SchedulerState.planBasis]) through the store, and a launch re-plans only when they moved.
 *
 * A "restart" here is what the app does: the state is encoded as the store writes it, decoded and prepared as a
 * launch loads it, and handed to a NEW view model and engine; the first engine's scope is torn down first.
 */
class RestartKeepsPlanTest {

    private val T0 = 1_700_000_000_000L

    // The debounce the rule-change watcher applies before its fill.
    private val DEBOUNCE_MILLIS = 1_000L

    private val HOUR = 60L * 60 * 1_000

    private class Harness(val engine: SchedulerEngine, val vm: TaskSchedulerViewModel, val scope: CoroutineScope)

    private fun TestScope.harness(initial: SchedulerState = SchedulerState.empty()): Harness {
        val scheduler = testScheduler
        val clock = object : AppClock {
            override fun nowMillis(): Long = T0 + scheduler.currentTime
        }
        val scope = CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]))
        val vm = TaskSchedulerViewModel(initial = initial, store = null, saveDispatcher = Dispatchers.Default)
        val engine = SchedulerEngine(
            vm = vm,
            clock = clock,
            scope = scope,
            deviceKind = DeviceKind.Desktop,
            screenActive = { true },
        )
        return Harness(engine, vm, scope)
    }

    /** What the store keeps of [h]'s state, as the next launch reads it back. */
    private fun closed(h: Harness): SchedulerState {
        h.scope.cancel()
        return SchedulerStateCodec.decodeSnapshot(SchedulerStateCodec.encodeSnapshot(h.vm.state.value))!!
    }

    /** An account with one schedulable task, so the plan has runs to keep. */
    private fun withATask(): SchedulerState {
        val empty = SchedulerState.empty()
        return SchedulerReducer.reduce(empty, SchedulerIntent.SetCellTitle(empty.lists[empty.rootListId]!!.cellIds[0], "A"))
    }

    @Test
    fun a_restart_with_unchanged_rules_keeps_the_plan() = runTest {
        val first = harness(withATask())
        first.engine.start()
        advanceTimeBy(DEBOUNCE_MILLIS + 1)
        runCurrent()
        assertNotNull(first.engine.lastRescheduleMillis, "a fresh account plans at its first launch")
        val planned = first.vm.state.value
        val basis = assertNotNull(planned.planBasis, "a re-plan records the rules it was made for")
        assertEquals(SchedulerDomain.schedulingSignature(planned), basis.signature)

        advanceTimeBy(HOUR / 4)
        val reloaded = closed(first)
        assertEquals(basis, reloaded.planBasis, "the basis survives the store")

        val second = harness(reloaded)
        second.engine.start()
        advanceTimeBy(DEBOUNCE_MILLIS + 1)
        runCurrent()
        assertNull(second.engine.lastRescheduleMillis, "a launch re-planned rules nobody had touched")
        assertEquals(basis, second.vm.state.value.planBasis, "the kept plan keeps its basis")
        // Still the plan the first launch made: every run it had laid past the line is there, unmoved.
        val line = T0 + testScheduler.currentTime
        val ahead = planned.panels.filter { it.auto && it.startEpochMillis > line }
        assertTrue(ahead.isNotEmpty(), "the first plan laid runs ahead of the line")
        val kept = second.vm.state.value.panels.map { Triple(it.taskId, it.startEpochMillis, it.endEpochMillis) }.toSet()
        for (run in ahead) {
            assertTrue(
                Triple(run.taskId, run.startEpochMillis, run.endEpochMillis) in kept,
                "the run at ${run.startEpochMillis - T0} was rewritten by the launch",
            )
        }

        // And a rule change after the launch still re-plans on the debounce.
        val root = second.vm.state.value.lists[second.vm.state.value.rootListId]!!
        second.vm.dispatch(SchedulerIntent.SetCellTitle(root.cellIds[0], ""))
        advanceTimeBy(DEBOUNCE_MILLIS + 1)
        runCurrent()
        assertNotNull(second.engine.lastRescheduleMillis, "a rule change after the launch re-plans")
        second.scope.cancel()
    }

    @Test
    fun an_edit_the_app_closed_inside_the_debounce_of_re_plans_at_launch() = runTest {
        val first = harness()
        first.engine.start()
        advanceTimeBy(DEBOUNCE_MILLIS + 1)
        runCurrent()
        val root = first.vm.state.value.lists[first.vm.state.value.rootListId]!!
        first.vm.dispatch(SchedulerIntent.SetCellTitle(root.cellIds[0], "A"))
        // Closed before the debounce ran its re-plan: the plan stored is the one made for the old rules.
        val reloaded = closed(first)
        assertFalse(SchedulerDomain.planHoldsAtLaunch(reloaded, T0 + testScheduler.currentTime))

        val second = harness(reloaded)
        second.engine.start()
        advanceTimeBy(DEBOUNCE_MILLIS + 1)
        runCurrent()
        assertNotNull(second.engine.lastRescheduleMillis, "the edit made before closing was never planned")
        assertEquals(
            SchedulerDomain.schedulingSignature(second.vm.state.value),
            second.vm.state.value.planBasis?.signature,
        )
        second.scope.cancel()
    }

    @Test
    fun a_state_with_no_basis_re_plans_at_launch() {
        // A payload written before the basis existed, and a fresh account, hold no plan made for known rules.
        assertFalse(SchedulerDomain.planHoldsAtLaunch(SchedulerState.empty(), T0))
    }

    @Test
    fun a_payload_written_before_the_basis_existed_decodes_to_none() {
        val state = SchedulerState.empty().copy(planBasis = PlanBasis(signature = 42, madeAtMillis = T0))
        val snapshot = SchedulerStateCodec.encodeSnapshot(state)
        assertTrue(snapshot.statePayload.contains("\"planBasis\""))
        assertEquals(state.planBasis, SchedulerStateCodec.decodeSnapshot(snapshot)!!.planBasis)

        val older = snapshot.copy(statePayload = snapshot.statePayload.replace(Regex(""","planBasis":\{[^}]*\}"""), ""))
        assertFalse(older.statePayload.contains("planBasis"))
        assertNull(SchedulerStateCodec.decodeSnapshot(older)!!.planBasis)
    }

    @Test
    fun the_basis_never_reaches_the_wire() {
        val state = SchedulerState.empty()
        val based = state.copy(planBasis = PlanBasis(signature = 42, madeAtMillis = T0))
        assertEquals(SchedulerStateCodec.syncFingerprint(state), SchedulerStateCodec.syncFingerprint(based))
        assertFalse(SchedulerStateCodec.syncFingerprint(based).statePayload.contains("planBasis"))
    }
}
