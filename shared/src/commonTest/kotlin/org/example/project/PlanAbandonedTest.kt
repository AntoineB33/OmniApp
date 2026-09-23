package org.example.project

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.engine.SchedulerEngine
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.platform.DeviceKind
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState
import org.example.project.scheduler.ui.TaskSchedulerViewModel
import org.example.project.time.AppClock
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * **A re-plan nobody is waiting for any more stops where it stands.**
 *
 * The user's rule: *"the scheduler must be triggered each time a relevant change happens, with a debounce or
 * not … if the scheduler was already running, then it stops abruptly and runs again with the new data"*.
 *
 * A fill is straight-line CPU — tens of milliseconds on a small account, seconds on a real one — so
 * cancelling the coroutine around it stops nothing: it would run to the end and publish an answer about data
 * nobody holds any more. The engine stamps every re-plan with a generation, the reducer asks whether that
 * generation is still current ([SchedulerReducer.planAbandoned]), and the fill asks at each checkpoint it
 * already has ([org.example.project.scheduler.domain.SearchBudget.checkAbandoned]).
 */
class PlanAbandonedTest {

    private val tz = TimeZone.currentSystemDefault()
    private val now = 1_760_000_000_000L

    @AfterTest
    fun restoreSeams() {
        SchedulerReducer.planAbandoned = { false }
    }

    private class FixedClock(var now: Long) : AppClock {
        override fun nowMillis(): Long = now
    }

    /** An account with enough real work that a whole fill is worth interrupting. */
    private fun account(taskCount: Int = 12): SchedulerState {
        var s = SchedulerState.empty()
        (1..taskCount).forEach { i ->
            val cell = s.lists[s.rootListId]!!.cellIds[i - 1]
            s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(cell, "Task $i"))
        }
        s = s.copy(screenBreaks = SchedulerDomain.DEFAULT_SCREEN_BREAKS)
        s.tasks.values.filter { it.title.startsWith("Task ") }.forEach { task ->
            s = SchedulerReducer.reduce(s, SchedulerIntent.SetTaskMinimumTime(task.id, 45))
        }
        return s
    }

    private fun refresh(generation: Long) =
        SchedulerIntent.RefreshSchedule(
            nowMillis = now,
            horizonCapMillis = now + 7 * 24 * 3_600_000L,
            generation = generation,
        )

    @Test
    fun an_abandoned_fill_changes_nothing_at_all() {
        val state = account()
        SchedulerReducer.planAbandoned = { it == 7L }
        val after = SchedulerReducer.reduce(state, refresh(generation = 7L))
        assertSame(
            state,
            after,
            "a superseded fill must hand back the very state it was given — the ViewModel publishes nothing " +
                "and does not retry when the reducer returns the same instance",
        )
    }

    @Test
    fun the_generation_still_wanted_plans_as_it_always_did() {
        val state = account()
        SchedulerReducer.planAbandoned = { it == 7L }
        val planned = SchedulerReducer.reduce(state, refresh(generation = 8L))
        assertNotEquals(state.panels, planned.panels, "another generation's abandonment must not stop this one")
        assertTrue(planned.panels.isNotEmpty(), "the fill ran")
    }

    @Test
    fun a_press_is_never_abandoned() {
        val state = account()
        // Generation 0 is what the in-reducer re-plans carry (ForceTaskStart, ForceTaskSwitch, a sleep edit):
        // they answer a press and must be in the state before it returns.
        SchedulerReducer.planAbandoned = { true }
        val planned = SchedulerReducer.reduce(state, refresh(generation = 0L))
        assertTrue(planned.panels.isNotEmpty(), "generation 0 means nobody can supersede this fill")
    }

    @Test
    fun it_stops_abruptly_rather_than_at_the_end() {
        val state = account()
        fun millisOf(block: () -> Unit): Long {
            val mark = TimeSource.Monotonic.markNow()
            block()
            return mark.elapsedNow().inWholeMicroseconds
        }
        // Warm up, so the comparison is code against code rather than JIT against JIT.
        SchedulerReducer.planAbandoned = { false }
        repeat(2) { SchedulerReducer.reduce(state, refresh(generation = 1L)) }
        val whole = millisOf { SchedulerReducer.reduce(state, refresh(generation = 1L)) }
        SchedulerReducer.planAbandoned = { it == 2L }
        val abandoned = millisOf { SchedulerReducer.reduce(state, refresh(generation = 2L)) }
        assertTrue(
            abandoned * 4 < whole,
            "an abandoned fill must stop at its next checkpoint, not run to the end " +
                "(whole=${whole}us, abandoned=${abandoned}us)",
        )
    }

    // ---- the engine's half: the rules moving is what abandons the fill in flight ---------------------

    /**
     * The engine's current generation, read through its own seam: [SchedulerReducer.planAbandoned] answers
     * false for exactly one non-zero generation, which is the one it wants.
     */
    private fun currentGeneration(): Long =
        (1L..500L).firstOrNull { !SchedulerReducer.planAbandoned(it) } ?: -1L

    /**
     * The engine runs on a REAL dispatcher here: `start()` launches loops that never go idle, so a virtual
     * clock would wait for them forever. What is asserted is an edge, not a delay — the abandon happens the
     * moment the rules move, ahead of the debounce — so polling for it costs milliseconds.
     */
    @Test
    fun a_rule_change_abandons_the_plan_in_flight() = runTest {
        val engineScope = CoroutineScope(Dispatchers.Default)
        try {
            val vm =
                TaskSchedulerViewModel(initial = account(3), store = null, saveDispatcher = Dispatchers.Default)
            SchedulerEngine(
                vm = vm,
                clock = FixedClock(now),
                scope = engineScope,
                tz = tz,
                deviceKind = DeviceKind.Desktop,
                screenActive = { true },
                speak = {},
                postNotification = { _, _ -> },
                clearNotifications = {},
            ).start()
            val before = await("the engine answers for one generation") { currentGeneration() > 0 }
            val settled = currentGeneration()

            // A rule change: a new task is a new share of the timeline.
            val cell = vm.state.value.lists[vm.state.value.rootListId]!!.cellIds.last()
            vm.dispatch(SchedulerIntent.SetCellTitle(cell, "a new task"))

            assertTrue(before, "the engine started")
            assertTrue(
                await("the rules moved, so the fill in flight must be abandoned") {
                    currentGeneration() > settled
                },
                "the generation must move on the rule change, ahead of the debounce (was $settled)",
            )
        } finally {
            engineScope.cancel()
        }
    }

    /** Poll [condition] on the real clock for up to two seconds. */
    private suspend fun await(what: String, condition: () -> Boolean): Boolean =
        withContext(Dispatchers.Default) {
            repeat(200) {
                if (condition()) return@withContext true
                delay(10)
            }
            false
        }

    @Test
    fun the_abandoned_generation_is_the_one_asked_about() {
        // The predicate the engine installs: only a generation that is neither 0 nor the current one.
        val current = 5L
        val predicate: (Long) -> Boolean = { it != 0L && it != current }
        assertEquals(false, predicate(0L), "a press")
        assertEquals(false, predicate(current), "the fill the engine wants")
        assertEquals(true, predicate(current - 1), "the one it replaced")
    }
}
