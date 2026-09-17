package org.example.project

import org.example.project.perf.Perf
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.ui.TaskSchedulerViewModel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What moving the re-plan off the frame loop costs, and the guard that pays it.
 *
 * Once [org.example.project.scheduler.engine.SchedulerEngine] reduces `RefreshSchedule` on a background
 * dispatcher, two threads read-modify-write `SchedulerState`: the engine, spending 25-80 ms inside one fill,
 * and the UI, spending microseconds on a keystroke. A plain `_state.value = next` would let the slow one
 * publish a state derived from a snapshot the fast one has already moved past — the keystroke would simply
 * vanish when the plan landed, seconds after it was typed, which is the worst kind of bug this state can
 * have (it is indistinguishable from the sync clobbers in `docs/adr/` and would be blamed on them).
 *
 * [TaskSchedulerViewModel.dispatch] therefore publishes by compare-and-set and re-reduces on a lost race.
 * These tests run REAL threads — the race is the subject, so a virtual-time scheduler cannot pin it.
 */
class PlanConcurrencyTest {

    // The rules under test only show over a plan longer than the calendar-closed ten-minute goal.
    @BeforeTest
    fun showTheCalendar() = CalendarHorizonFixture.show()

    @AfterTest
    fun closeTheCalendar() = CalendarHorizonFixture.close()

    private val now = 1_760_000_000_000L

    /** Enough equal-priority tasks with the production screen breaks that one fill takes tens of ms. */
    private fun seed(vm: TaskSchedulerViewModel, taskCount: Int) {
        val root = vm.state.value.rootListId
        repeat(taskCount) { i ->
            vm.dispatch(SchedulerIntent.SetCellTitle(vm.state.value.lists[root]!!.cellIds[i], "Task ${i + 1}"))
        }
        for (id in vm.state.value.tasks.keys.toList()) {
            vm.dispatch(SchedulerIntent.SetTaskMinimumTime(id, 45))
        }
        vm.dispatch(SchedulerIntent.SetScreenBreaks(SchedulerDomain.DEFAULT_SCREEN_BREAKS))
    }

    /**
     * The regression this guard exists for: an edit made WHILE a re-plan is being reduced survives it.
     *
     * The fill is started first and the title is typed 5 ms in, so the edit lands squarely inside a
     * derivation that takes tens of milliseconds. Repeated a few times because the race is what is under
     * test: one round that happened not to overlap would prove nothing, so the test also requires that the
     * contention it is measuring actually occurred (`reduce.contended`).
     */
    @Test
    fun an_edit_typed_during_a_re_plan_survives_it() {
        Perf.enabled = true
        Perf.reset()
        var contended = 0L
        val typed = "typed while the planner was running"
        repeat(5) {
            val vm = TaskSchedulerViewModel(store = null)
            seed(vm, 25)
            val cell = vm.state.value.lists[vm.state.value.rootListId]!!.cellIds.first()
            val taskId = vm.state.value.cells[cell]!!.taskId!!

            val planner = Thread { vm.dispatch(SchedulerIntent.RefreshSchedule(now)) }
            planner.start()
            Thread.sleep(5)
            vm.dispatch(SchedulerIntent.SetCellTitle(cell, typed))
            planner.join()

            assertEquals(
                typed,
                vm.state.value.tasks[taskId]?.title,
                "the re-plan published a state derived from before the edit — the keystroke was reverted",
            )
            assertTrue(vm.state.value.panels.isNotEmpty(), "and the plan itself must still have landed")
            contended = Perf.snapshot().counters.firstOrNull { it.name == "reduce.contended" }?.calls ?: 0L
        }
        Perf.enabled = false
        assertTrue(
            contended > 0,
            "no round actually raced (the fill finished inside 5 ms?), so this test proved nothing — " +
                "raise the task count in seed()",
        )
    }

    /**
     * The other half of the same guard: the re-plan is not LOST either. Losing the race must cost a
     * re-derivation, not the intent — a dropped `RefreshSchedule` leaves the calendar showing a plan that
     * no longer matches the tree, and nothing re-fires until the next rule change.
     */
    @Test
    fun the_re_plan_is_re_derived_rather_than_dropped_when_it_loses_the_race() {
        val vm = TaskSchedulerViewModel(store = null)
        seed(vm, 25)
        val cell = vm.state.value.lists[vm.state.value.rootListId]!!.cellIds.first()

        val planner = Thread { vm.dispatch(SchedulerIntent.RefreshSchedule(now)) }
        planner.start()
        Thread.sleep(5)
        vm.dispatch(SchedulerIntent.SetCellTitle(cell, "Renamed mid-plan"))
        planner.join()

        // The committed plan is the one the reducer computes from the state that WON the race — i.e. the
        // plan was re-derived against the edit, not against the snapshot it started from. That state held no
        // plan yet: a state holding one hands it to the fill as a seed that competes on the score
        // (`docs/scheduler_score.md` § *Degradation*), so the plan is recomputed from the panels the race saw.
        val expected =
            SchedulerDomain.fillSchedule(
                vm.state.value.copy(panels = emptyList(), scheduleCycle = null),
                now,
                horizonMillis = SchedulerReducer.scheduleHorizonEndMillis(now),
            )
        assertEquals(expected, vm.state.value.panels)
    }
}
