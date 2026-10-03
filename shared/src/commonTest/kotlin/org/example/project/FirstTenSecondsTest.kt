package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import org.example.project.scheduler.domain.PeriodKinds
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.engine.PROGRESSIVE_FIRST_STAGE_MILLIS
import org.example.project.scheduler.engine.SchedulerEngine
import org.example.project.scheduler.model.TaskPanel
import org.example.project.scheduler.platform.DeviceKind
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState
import org.example.project.scheduler.ui.TaskSchedulerViewModel
import org.example.project.time.AppClock

/**
 * `docs/scheduler_requirements.md` § *Progressive Calculation*, **first 10s**: *"When there is a change that will make
 * the scheduler engine run from scratch, it must firstly check if in the next 10 seconds there are gaps with no task
 * and if there are tasks that can be scheduled in those gaps. If so, then almost instantly, a new set of rules is
 * returned and the first 10 seconds are definitive."*
 */
class FirstTenSecondsTest {

    private val T0 = 1_700_000_000_000L
    private val TEN_S = SchedulerDomain.FIRST_DEFINITIVE_MILLIS

    /** T0 is 22:13 UTC, inside the default sleep window; twelve hours later is 10:13, a waking hour. */
    private val DAY_OFFSET = 12 * 3_600_000L

    private fun withTask(): SchedulerState {
        val s = SchedulerState.empty()
        return SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(s.lists[s.rootListId]!!.cellIds.first(), "Task A"))
    }

    private fun taskId(s: SchedulerState) = s.tasks.values.first { it.title == "Task A" }.id

    private fun work(s: SchedulerState, from: Long, to: Long) =
        TaskPanel(id = "auto/$from", taskId = taskId(s), title = "Task A", startEpochMillis = from, endEpochMillis = to, auto = true)

    private fun inactivity(from: Long, to: Long) =
        TaskPanel(id = "period/$from", taskId = null, title = "Inactivity", startEpochMillis = from, endEpochMillis = to, periodKind = PeriodKinds.INACTIVITY)

    @Test
    fun an_empty_line_with_a_schedulable_task_is_a_fillable_gap() {
        assertTrue(SchedulerDomain.firstSecondsGapFillable(withTask(), T0))
    }

    @Test
    fun ten_seconds_a_task_already_covers_are_no_gap() {
        val s = withTask()
        assertFalse(SchedulerDomain.firstSecondsGapFillable(s.copy(panels = listOf(work(s, T0 - 60_000, T0 + 60_000))), T0))
        // Two panels meeting inside the ten seconds leave no gap either.
        val split = listOf(work(s, T0 - 1_000, T0 + 4_000), work(s, T0 + 4_000, T0 + 20_000))
        assertFalse(SchedulerDomain.firstSecondsGapFillable(s.copy(panels = split), T0))
    }

    @Test
    fun a_gap_inside_the_ten_seconds_is_found() {
        val s = withTask()
        // The task panel ends three seconds in: seven seconds have no task.
        assertTrue(SchedulerDomain.firstSecondsGapFillable(s.copy(panels = listOf(work(s, T0 - 60_000, T0 + 3_000))), T0))
        // …and a panel starting later leaves its head open too.
        assertTrue(SchedulerDomain.firstSecondsGapFillable(s.copy(panels = listOf(work(s, T0 + 5_000, T0 + 60_000))), T0))
    }

    @Test
    fun a_derived_panel_of_a_task_no_longer_schedulable_is_a_gap() {
        val s = withTask()
        val gone = work(s, T0 - 60_000, T0 + 60_000).copy(taskId = org.example.project.scheduler.model.TaskId("task/deleted"))
        assertTrue(SchedulerDomain.firstSecondsGapFillable(s.copy(panels = listOf(gone)), T0), "the next rules drop it")
        // A panel the user placed is kept by every set of rules: it covers.
        assertFalse(SchedulerDomain.firstSecondsGapFillable(s.copy(panels = listOf(gone.copy(auto = false, pinned = true))), T0))
    }

    @Test
    fun a_gap_no_task_may_run_in_is_not_fillable() {
        val s = withTask()
        // Inactivity accepts nobody: the gap is there, but nothing can be scheduled in it.
        assertFalse(SchedulerDomain.firstSecondsGapFillable(s.copy(panels = listOf(inactivity(T0 - 60_000, T0 + 60_000))), T0))
        // Where the period ends inside the ten seconds, the rest of the gap is fillable.
        assertTrue(SchedulerDomain.firstSecondsGapFillable(s.copy(panels = listOf(inactivity(T0 - 60_000, T0 + 6_000))), T0))
    }

    @Test
    fun no_schedulable_task_means_nothing_to_fill() {
        assertFalse(SchedulerDomain.firstSecondsGapFillable(SchedulerState.empty(), T0))
    }

    @Test
    fun a_rule_change_leaving_a_fillable_gap_publishes_ten_definitive_seconds_before_the_first_stage() = runTest {
        val scheduler = testScheduler
        val vm = TaskSchedulerViewModel(store = null, saveDispatcher = Dispatchers.Default)
        val engine =
            SchedulerEngine(
                vm = vm,
                clock = object : AppClock {
                    override fun nowMillis(): Long = T0 + DAY_OFFSET + scheduler.currentTime
                },
                scope = backgroundScope,
                tz = TimeZone.UTC,
                deviceKind = DeviceKind.Desktop,
                screenActive = { true },
            )
        val stages = mutableListOf<Long>()
        engine.stageSink = { stages += it }
        engine.start()
        // Two tasks, planned; then the one the line is on is deleted — its panels go, and the line is left in a gap
        // the other task can fill.
        val root = vm.state.value.rootListId
        vm.dispatch(SchedulerIntent.SetCellTitle(vm.state.value.lists[root]!!.cellIds.last(), "Task A"))
        vm.dispatch(SchedulerIntent.SetCellTitle(vm.state.value.lists[root]!!.cellIds.last(), "Task B"))
        // Past the first rest pose, where a task may run.
        advanceTimeBy(16 * 60_000L)
        runCurrent()
        val at = T0 + DAY_OFFSET + 16 * 60_000L
        val onLine = vm.state.value.panels.first { it.auto && it.taskId != null && it.startEpochMillis <= at && at < it.endEpochMillis }
        val cellOnLine = vm.state.value.cells.entries.first { it.value.taskId == onLine.taskId }.key
        stages.clear()
        vm.dispatch(SchedulerIntent.SetCellTitle(cellOnLine, ""))
        assertTrue(
            SchedulerDomain.firstSecondsGapFillable(vm.state.value, at),
            "the deletion leaves a gap Task B can fill: leaves ${SchedulerDomain.schedulableLeaves(vm.state.value)}, kinds " +
                SchedulerDomain.restrictiveKindsAt(vm.state.value, at),
        )

        advanceTimeBy(1_001)
        runCurrent()

        assertTrue(stages.size >= 2, "the ten seconds and then the first stage: ${stages.map { it - T0 }}")
        val start = at + 1_000 // the debounce
        assertEquals(start + TEN_S, stages[0], "the first set of rules reaches ten seconds: ${stages.map { it - start }}")
        assertTrue(stages[1] > stages[0] && stages[1] <= start + PROGRESSIVE_FIRST_STAGE_MILLIS, "then the first stage extends it")

        // What the ten seconds put on the line is still there after the extension: they were definitive.
        val head = vm.state.value.panels.filter { it.auto && it.taskId != null && it.startEpochMillis < start + TEN_S }
        assertTrue(head.isNotEmpty(), "the ten seconds were filled")
        assertEquals(start, head.minOf { it.startEpochMillis })
    }

    @Test
    fun a_rule_change_with_the_ten_seconds_covered_goes_straight_to_the_first_stage() = runTest {
        val scheduler = testScheduler
        val vm = TaskSchedulerViewModel(store = null, saveDispatcher = Dispatchers.Default)
        val engine =
            SchedulerEngine(
                vm = vm,
                clock = object : AppClock {
                    override fun nowMillis(): Long = T0 + DAY_OFFSET + scheduler.currentTime
                },
                scope = backgroundScope,
                tz = TimeZone.UTC,
                deviceKind = DeviceKind.Desktop,
                screenActive = { true },
            )
        val stages = mutableListOf<Long>()
        engine.stageSink = { stages += it }
        engine.start()
        val cell = vm.state.value.lists[vm.state.value.rootListId]!!.cellIds.first()
        vm.dispatch(SchedulerIntent.SetCellTitle(cell, "Task A"))
        // Past the first rest pose, with Task A on the line.
        advanceTimeBy(16 * 60_000L)
        runCurrent()
        val at = T0 + DAY_OFFSET + 16 * 60_000L
        assertTrue(
            vm.state.value.panels.any { it.auto && it.taskId != null && it.startEpochMillis <= at && it.endEpochMillis >= at + TEN_S },
            "Task A covers the next ten seconds",
        )
        stages.clear()

        // A minimum-time edit re-plans from scratch, but the task already covers the line.
        val id = vm.state.value.tasks.values.first { it.title == "Task A" }.id
        vm.dispatch(SchedulerIntent.SetTaskMinimumTime(id, 30))
        advanceTimeBy(1_001)
        runCurrent()

        assertTrue(stages.isNotEmpty(), "it re-planned")
        assertTrue(stages[0] - (at + 1_000) > TEN_S, "no ten-second stage when nothing is left to fill: ${stages[0] - at - 1_000}")
    }
}
