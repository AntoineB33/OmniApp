package org.example.project

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNotNull
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
import org.example.project.scheduler.state.SchedulerRunEntry
import org.example.project.scheduler.state.SchedulerState
import org.example.project.scheduler.ui.TaskSchedulerViewModel
import org.example.project.time.AppClock

/**
 * `docs/scheduler_requirements.md` § *Progressive Calculation*, direct consequence: *"If the device bearing the running
 * process is put to sleep, then when the program wakes up, the $now line$ does a fast move forward in mode 2 to the
 * current date. If the current date is beyond the definitive schedule, then it is similar to a case where no CPU were
 * available during this period and the current set of rules output … is used to define the schedule as the $now line$
 * does its fast move, while no better set of rules output was found."*
 *
 * So the journey walks the rules ALREADY HELD for its mode — the plan found for the covered class with the last plan
 * ([SchedulerState.otherModePlan]), laid rather than searched — and searches nothing on the way; the landing re-plans.
 * (It used to make a mode-2 plan at the journey's first instant and extend it as it went: computing that the
 * requirements say did not happen.)
 */
class WakeJourneyNoIdlingTest {
    private val MIN = 60_000L
    private val HOUR = 60 * MIN
    private val NOW = 1_700_000_000_000L

    @AfterTest
    fun resetSeams() {
        SchedulerReducer.tpMode = { DynamicPeriods.MODE_AT_SCREEN }
        SchedulerReducer.recordSchedulerRun = {}
        SchedulerReducer.scheduleHorizonEndMillis = { it + 2 * SchedulerDomain.SCHEDULE_GOAL_FLOOR_MILLIS }
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
    fun a_wake_walks_the_rules_held_for_its_mode_and_searches_nothing() {
        val sleepStart = NOW - 30 * HOUR
        var now = sleepStart
        val vm = TaskSchedulerViewModel(initial = account(), store = null, saveDispatcher = Dispatchers.Default)
        val engine =
            SchedulerEngine(
                vm = vm,
                clock = object : AppClock { override fun nowMillis(): Long = now },
                scope = CoroutineScope(Dispatchers.Unconfined),
                screenActive = { true },
                speak = {},
            )
        SchedulerReducer.tpMode = { engine.tpModeNow() }
        SchedulerReducer.scheduleHorizonEndMillis = { it + 3 * HOUR }
        // The plan in force before the sleep, made at the screen — and with it, the plan for the covered class.
        vm.dispatch(SchedulerIntent.RefreshSchedule(sleepStart))
        assertNotNull(vm.state.value.otherModePlan, "every plan reduction finds the plan for the other mode class")

        val runs = mutableListOf<SchedulerRunEntry>()
        SchedulerReducer.recordSchedulerRun = { runs += it }
        now = NOW
        engine.reportTimeGap(sleepStart, NOW)

        val journeyRuns = runs.filter { it.nowMillis < NOW }
        assertTrue(
            journeyRuns.none { it.kind == SchedulerRunEntry.Kind.Replan },
            "the journey searches nothing: ${journeyRuns.map { it.kind to (it.nowMillis - sleepStart) / MIN }}",
        )
        assertTrue(journeyRuns.any { it.kind == SchedulerRunEntry.Kind.ModeSwitch }, "it LAYS the rules held for mode 2")

        val st = vm.state.value
        val walk = st.tasks.values.single { it.title == "Walk" }
        val screen = st.tasks.values.single { it.title == "Screen" }
        val swept = TaskTimeRange(sleepStart + 5 * MIN, NOW - 2 * HOUR)
        assertTrue(
            SchedulerDomain.intersectRegions(screen.record, listOf(swept)).isEmpty(),
            "no on-screen work may be recorded over a stretch swept in mode 2",
        )
        // As far as the held rules reach, the resilient task works the journey.
        val held = TaskTimeRange(sleepStart + 5 * MIN, sleepStart + 3 * HOUR - 10 * MIN)
        val worked = SchedulerDomain.intersectRegions(walk.record, listOf(held)).sumOf { it.endEpochMillis - it.startEpochMillis }
        assertTrue(worked > (held.endEpochMillis - held.startEpochMillis) / 2, "the held rules for mode 2 are walked: ${worked / MIN} min")
        // …and the landing is back on the live reading.
        assertTrue(engine.tpModeNow(NOW) == DynamicPeriods.MODE_AT_SCREEN)
    }
}
