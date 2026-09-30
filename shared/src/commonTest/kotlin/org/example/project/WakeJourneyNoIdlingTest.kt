package org.example.project

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.example.project.scheduler.domain.DynamicPeriods
import org.example.project.scheduler.domain.PeriodKinds
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.engine.SchedulerEngine
import org.example.project.scheduler.model.SleepSchedule
import org.example.project.scheduler.model.TaskTimeRange
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState
import org.example.project.scheduler.ui.TaskSchedulerViewModel
import org.example.project.time.AppClock

/**
 * `docs/scheduler_requirements.md` § *Progressive Calculation*, direct consequence: **the line a
 * wake walks in mode 2 is walked over the plan for mode 2, and that plan reaches as far as the line goes.**
 *
 * A device sleep dropped the plan's tail and the journey never re-planned, so the whole swept stretch came out with
 * no task in it — even where a task resilient to "no on-screen task" could have run, and past the plan's front in
 * any case. The journey now makes the plan for its own mode at its first instant, extends it as the line reaches its
 * front, and the landing re-plans for the mode the line arrives in.
 */
class WakeJourneyNoIdlingTest {
    private val MIN = 60_000L
    private val HOUR = 60 * MIN
    private val NOW = 1_700_000_000_000L

    @AfterTest
    fun resetSeams() {
        SchedulerReducer.tpMode = { DynamicPeriods.MODE_AT_SCREEN }
    }

    /** An on-screen task and one resilient to "no on-screen task", the three production breaks, and no night. */
    private fun account(): SchedulerState {
        var s = SchedulerState.empty()
        listOf("Screen", "Walk").forEachIndexed { i, title ->
            val row = s.lists[s.rootListId]!!.cellIds.let { it.getOrNull(i) ?: it.last() }
            s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(row, title))
        }
        val walk = s.tasks.values.single { it.title == "Walk" }
        return s.copy(
            tasks = s.tasks + (walk.id to walk.withResilience(PeriodKinds.NO_SCREEN, 1.0)),
            screenBreaks = SchedulerDomain.DEFAULT_SCREEN_BREAKS,
            sleep = SleepSchedule(sleepDurationMinutes = 0),
        )
    }

    @Test
    fun a_long_wake_is_worked_by_the_resilient_task_the_whole_way() {
        val sleepStart = NOW - 30 * HOUR
        var now = sleepStart
        // The plan in force before the sleep: made at the screen, a few hours long.
        val before = SchedulerDomain.fillSchedule(account(), sleepStart, horizonMillis = sleepStart + 2 * HOUR)
        val vm = TaskSchedulerViewModel(initial = account().copy(panels = before), store = null, saveDispatcher = Dispatchers.Default)
        val engine =
            SchedulerEngine(
                vm = vm,
                clock = object : AppClock { override fun nowMillis(): Long = now },
                scope = CoroutineScope(Dispatchers.Unconfined),
                screenActive = { true },
                speak = {},
            )
        SchedulerReducer.tpMode = { engine.tpModeNow() }
        now = NOW
        engine.reportTimeGap(sleepStart, NOW)

        val st = vm.state.value
        val walk = st.tasks.values.single { it.title == "Walk" }
        val screen = st.tasks.values.single { it.title == "Screen" }
        val swept = TaskTimeRange(sleepStart + 5 * MIN, NOW - 2 * HOUR)
        assertTrue(
            SchedulerDomain.intersectRegions(screen.record, listOf(swept)).isEmpty(),
            "no on-screen work may be recorded over a stretch swept in mode 2",
        )
        // The resilient task works the journey: the failure this pins left the WHOLE swept stretch empty, the plan
        // never having been made for mode 2. Time the plan leaves to nobody is its own priced decision now
        // (`docs/scheduler_requirements.md` requires no task anywhere) — a task well ahead of its share is let off
        // for a while — so what is asked is that it works nearly all of it, with no hole anywhere near that long.
        val breaks = engine.frozenBreaks.value?.breaks.orEmpty().map { TaskTimeRange(it.startMillis, it.endMillis) }
        val idle = SchedulerDomain.subtractRegions(listOf(swept), SchedulerDomain.mergeOccupied(walk.record + breaks))
        val sweptLength = swept.endEpochMillis - swept.startEpochMillis
        val breakTime = SchedulerDomain.mergeOccupied(breaks).sumOf {
            (minOf(it.endEpochMillis, swept.endEpochMillis) - maxOf(it.startEpochMillis, swept.startEpochMillis)).coerceAtLeast(0L)
        }
        assertTrue(
            idle.sumOf { it.endEpochMillis - it.startEpochMillis } < (sweptLength - breakTime) / 2,
            "the journey left most of the swept stretch empty although the resilient task could run: " +
                idle.map { (it.startEpochMillis - sleepStart) / MIN to (it.endEpochMillis - it.startEpochMillis) / MIN },
        )
        assertTrue(
            idle.none { it.endEpochMillis - it.startEpochMillis > 2 * HOUR },
            "the journey left stretches with no task although the resilient one could run: " +
                idle.filter { it.endEpochMillis - it.startEpochMillis > 2 * HOUR }.map { (it.startEpochMillis - sleepStart) / MIN to (it.endEpochMillis - it.startEpochMillis) / MIN },
        )
        // …and the landing is back on the live reading, planned for it.
        assertTrue(engine.tpModeNow(NOW) == DynamicPeriods.MODE_AT_SCREEN)
    }
}
