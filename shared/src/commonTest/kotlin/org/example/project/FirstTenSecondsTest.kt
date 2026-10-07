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

    /**
     * Anomaly 2026-10-07: at the screen at night, the line holds a task through the schedule's Sleep window (the window
     * gives way to a mode-1 line). The user dragged that panel into the past: the line went on bare, and a new panel
     * came only with the first stage — the check read the window as stored, where it refuses every task.
     */
    @Test
    fun a_line_left_bare_inside_a_sleep_window_is_a_fillable_gap_at_a_screen_and_not_away_from_it() {
        val s0 = withTask()
        val night = TaskPanel(id = "sleep/1", taskId = null, title = "Sleep", startEpochMillis = T0 - 3_600_000, endEpochMillis = T0 + 6 * 3_600_000, sleep = true)
        val s = s0.copy(panels = listOf(night))
        val atScreen = org.example.project.scheduler.domain.DynamicPeriods.MODE_AT_SCREEN
        val away = org.example.project.scheduler.domain.DynamicPeriods.MODE_ON_BREAK
        assertTrue(SchedulerDomain.firstSecondsGapFillable(s, T0, atScreen), "the window gives way to a line at a screen")
        assertFalse(SchedulerDomain.firstSecondsGapFillable(s, T0, away), "away from the screen the window stands")
        // A period that does not give way still refuses: nothing to lay, at a screen or not.
        assertFalse(SchedulerDomain.firstSecondsGapFillable(s0.copy(panels = listOf(inactivity(T0 - 60_000, T0 + 60_000))), T0, atScreen))
        // And the window ahead of a line that has not reached it yet stands too.
        val later = night.copy(startEpochMillis = T0 + 5_000)
        assertTrue(SchedulerDomain.firstSecondsGapFillable(s0.copy(panels = listOf(later)), T0, atScreen), "the five seconds before it are free")
    }

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

        // "…the input that makes the scheduler engine run from scratch each time it changes": the run starts AT the
        // change, and its first act is the ten seconds.
        runCurrent()

        assertTrue(stages.size >= 2, "the ten seconds and then the first stage: ${stages.map { it - T0 }}")
        assertEquals(at + TEN_S, stages[0], "the first set of rules reaches ten seconds: ${stages.map { it - at }}")
        assertTrue(stages[1] > stages[0] && stages[1] <= at + PROGRESSIVE_FIRST_STAGE_MILLIS, "then the first stage extends it")

        // What the ten seconds put on the line is still there after the extension: they were definitive.
        val head = vm.state.value.panels.filter { it.auto && it.taskId != null && it.startEpochMillis < at + TEN_S }
        assertTrue(head.isNotEmpty(), "the ten seconds were filled")
        assertEquals(at, head.minOf { it.startEpochMillis })
    }

    /**
     * Anomaly 2026-10-07, end to end: at a screen inside the night's periods the line holds a task; the user drags that
     * panel into the past; ten seconds of rules must come at once, and the line must have a panel again.
     */
    @Test
    fun the_panel_on_a_line_at_a_screen_at_night_dragged_away_is_replaced_within_the_ten_seconds() = runTest {
        val scheduler = testScheduler
        val vm = TaskSchedulerViewModel(store = null, saveDispatcher = Dispatchers.Default)
        val engine =
            SchedulerEngine(
                vm = vm,
                // No day offset: T0 is at night, inside the periods the sleep schedule lays.
                clock = object : AppClock {
                    override fun nowMillis(): Long = T0 + scheduler.currentTime
                },
                scope = backgroundScope,
                tz = TimeZone.UTC,
                deviceKind = DeviceKind.Desktop,
                screenActive = { true },
            )
        val stages = mutableListOf<Long>()
        engine.stageSink = { stages += it }
        engine.start()
        val root = vm.state.value.rootListId
        vm.dispatch(SchedulerIntent.SetCellTitle(vm.state.value.lists[root]!!.cellIds.last(), "Task A"))
        advanceTimeBy(16 * 60_000L)
        runCurrent()
        val at = T0 + 16 * 60_000L
        assertTrue(
            SchedulerDomain.restrictiveKindsAt(vm.state.value, at).isNotEmpty(),
            "the line is inside a period of the night: ${vm.state.value.panels.filter { it.isRestrictivePeriod }.map { it.id }}",
        )
        val onLine = vm.state.value.panels.first { it.auto && it.taskId != null && it.startEpochMillis <= at && at < it.endEpochMillis }
        stages.clear()
        // Dragged into the past — a change of the pre-placed tasks, which are part of the input: the line is bare
        // until the scheduler has run.
        vm.dispatch(
            SchedulerIntent.UpdateTaskPanel(
                onLine.id, onLine.taskId, onLine.title, at - 3 * 3_600_000L, at - 2 * 3_600_000L,
                org.example.project.scheduler.model.PanelPins(existence = true),
            ),
        )
        assertTrue(vm.state.value.panels.none { it.auto && it.taskId != null && it.startEpochMillis <= at && at < it.endEpochMillis })

        // The run starts at the change: the ten seconds first ("almost instantly"), definitive…
        runCurrent()
        assertEquals(at + TEN_S, stages.firstOrNull(), "the first set of rules is the ten seconds: ${stages.map { it - at }}")
        val laid = vm.state.value.panels.filter { it.auto && it.taskId != null && it.startEpochMillis <= at && at < it.endEpochMillis }
        assertEquals(1, laid.size, "the line has a task panel again")
        // …"then, 10 seconds later, at least the 10 next minutes of the scheduler are definitive as well."
        advanceTimeBy(TEN_S)
        runCurrent()
        assertTrue(stages.last() >= at + TEN_S + 10 * 60_000L, "ten seconds on, ten more minutes are definitive: ${stages.map { it - at }}")
        val head = vm.state.value.panels.filter { it.auto && it.taskId != null && it.startEpochMillis < at + TEN_S }
        assertEquals(at, head.minOf { it.startEpochMillis }, "and the ten seconds were not rewritten")
        assertEquals(laid.single().taskId, head.minBy { it.startEpochMillis }.taskId)
    }

    /**
     * `docs/scheduler_requirements.md` § *Rule state input evolution*: the pre-placed tasks are part of *"the input that
     * makes the scheduler engine run from scratch each time it changes"* — and a block HELD on the calendar changes
     * them. The user's example is its consequence, not a rule of its own: *"the scheduler engine doesn't do anything
     * because restarted repeatedly while I move the mouse and doesn't have time to do anything. When I don't move the
     * mouse … the scheduler engine stops being restarted repeatedly and can run normally. At first … the first 10
     * seconds … Then, 10 seconds later, at least the 10 next minutes."*
     */
    @Test
    fun a_held_block_is_an_input_each_change_of_it_runs_the_scheduler_from_scratch_and_nothing_is_saved() = runTest {
        val scheduler = testScheduler
        val vm = TaskSchedulerViewModel(store = null, saveDispatcher = Dispatchers.Default)
        val engine =
            SchedulerEngine(
                vm = vm,
                clock = object : AppClock {
                    override fun nowMillis(): Long = T0 + scheduler.currentTime
                },
                scope = backgroundScope,
                tz = TimeZone.UTC,
                deviceKind = DeviceKind.Desktop,
                screenActive = { true },
            )
        val stages = mutableListOf<Long>()
        engine.heldStageSink = { stages += it }
        engine.start()
        val root = vm.state.value.rootListId
        vm.dispatch(SchedulerIntent.SetCellTitle(vm.state.value.lists[root]!!.cellIds.last(), "Task A"))
        advanceTimeBy(16 * 60_000L)
        runCurrent()
        val at = T0 + 16 * 60_000L
        val stored = vm.state.value
        val onLine = stored.panels.first { it.auto && it.taskId != null && it.startEpochMillis <= at && at < it.endEpochMillis }
        // What releasing the block in the past would leave, as the calendar makes it: the release's own move, on a copy.
        fun heldAt(hoursBack: Int): SchedulerState =
            SchedulerReducer.reduce(
                stored.copy(automaticSchedule = false),
                SchedulerIntent.UpdateTaskPanel(
                    onLine.id, onLine.taskId, onLine.title, at - (hoursBack + 1) * 3_600_000L, at - hoursBack * 3_600_000L,
                    org.example.project.scheduler.model.PanelPins(existence = true),
                ),
            ).copy(automaticSchedule = true)
        fun onLine(s: SchedulerState, t: Long) = s.panels.any { it.auto && it.taskId != null && it.startEpochMillis <= t && t < it.endEpochMillis }
        assertFalse(onLine(heldAt(2), at), "held away from the line, the line is bare")

        // The input changes again and again before the engine gets any time: each change abandons the run of the one
        // before it, and nothing is found for any of them.
        for (hours in 2..5) engine.planHeld(heldAt(hours))
        assertTrue(stages.isEmpty() && engine.heldPlan.value == null, "restarted at each change, with no time to find anything")

        // It stops changing: the run of the LAST input goes on — the ten seconds first, then the pace.
        val held = heldAt(6)
        engine.planHeld(held)
        runCurrent()
        assertEquals(at + TEN_S, stages.firstOrNull(), "the ten seconds are the first thing found: ${stages.map { it - at }}")
        val found = engine.heldPlan.value
        assertTrue(found != null && found.source === held && onLine(found.planned, at), "the held line has a task panel again")
        advanceTimeBy(TEN_S)
        runCurrent()
        assertTrue(stages.last() >= at + TEN_S + 10 * 60_000L, "ten seconds on, ten more minutes: ${stages.map { it - at }}")
        // Nothing is saved: the stored state is what it was, which is what remembers what the held block removed.
        assertTrue(vm.state.value.panels.any { it.id == onLine.id })
        assertEquals(stored.tasks, vm.state.value.tasks)

        // The hold is over.
        engine.planHeld(null)
        assertEquals(null, engine.heldPlan.value)
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
