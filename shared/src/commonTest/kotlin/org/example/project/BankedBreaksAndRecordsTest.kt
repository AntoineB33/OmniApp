package org.example.project

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.example.project.scheduler.domain.BankedBreak
import org.example.project.scheduler.domain.DynamicPeriods
import org.example.project.scheduler.domain.FrozenScreenBreaks
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.engine.SchedulerEngine
import org.example.project.scheduler.model.SleepSchedule
import org.example.project.scheduler.model.TaskPanel
import org.example.project.scheduler.model.TaskTimeRange
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState
import org.example.project.scheduler.ui.TaskSchedulerViewModel
import org.example.project.time.AppClock

/**
 * Account 3, 2026-09-27 21:09: **a 20 s screen break drawn over a task panel in the past**, and past breaks moving.
 * `docs/scheduler_requirements.md`: the three have the kind "no task allowed", and the past is frozen.
 *
 * Three things met there, each pinned here:
 *  - the app had not been running for seven hours and nothing walked that stretch at start-up, so the old plan was
 *    banked as work done while nothing ran;
 *  - the breaks banked for the past and the work recorded there were decided apart, and a break landed on the work
 *    (a first banking now invents no past at all: the break machine starts rested at the line).
 */
class BankedBreaksAndRecordsTest {
    private val SEC = 1_000L
    private val MIN = 60_000L
    private val HOUR = 60 * MIN
    private val NOW = 1_700_000_000_000L

    @AfterTest
    fun resetSeams() {
        SchedulerReducer.frozenScreenBreaks = { null }
        SchedulerReducer.tpMode = { DynamicPeriods.MODE_AT_SCREEN }
    }

    private fun oneTask(): SchedulerState {
        var s = SchedulerState.empty()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(s.lists[s.rootListId]!!.cellIds[0], "Work"))
        return s.copy(sleep = SleepSchedule(sleepDurationMinutes = 0), screenBreaks = SchedulerDomain.DEFAULT_SCREEN_BREAKS)
    }

    @Test
    fun work_is_never_recorded_inside_a_banked_break() {
        val s0 = oneTask()
        val work = s0.tasks.values.single { it.title == "Work" }.id
        val panel = TaskPanel("auto/0", work, "Work", NOW - 30 * MIN, NOW - MIN, auto = true)
        val lookAway = BankedBreak(DynamicPeriods.LABEL_20S, NOW - 10 * MIN, NOW - 10 * MIN + 20 * SEC)
        SchedulerReducer.frozenScreenBreaks = { FrozenScreenBreaks(listOf(lookAway), NOW) }
        val advanced = SchedulerReducer.reduce(s0.copy(panels = listOf(panel)), SchedulerIntent.AdvanceSchedule(NOW))
        val record = advanced.tasks.getValue(work).record
        assertEquals(
            listOf(TaskTimeRange(NOW - 30 * MIN, lookAway.startMillis), TaskTimeRange(lookAway.endMillis, NOW - MIN)),
            record.sortedBy { it.startEpochMillis },
            "the panel is recorded around the break the line banked in it",
        )
    }

    @Test
    fun a_first_banking_invents_no_past() {
        // The line's first moment has nothing to continue from: the break machine starts rested at the line and banks
        // nothing behind it — a break there would be a break nobody placed, and could lie over work already recorded.
        val banked =
            SchedulerDomain.stepScreenBreaks(SchedulerDomain.DEFAULT_SCREEN_BREAKS, null, NOW, emptyList(), DynamicPeriods.MODE_AT_SCREEN)
        assertTrue(banked.record.breaks.isEmpty(), "nothing is banked behind the line: ${banked.record.breaks}")
        assertEquals(NOW, banked.record.untilMillis, "the front is the line")
        assertTrue(banked.record.machine != null, "and the machine it carries on from starts there")
    }

    @Test
    fun an_app_that_was_not_running_walks_the_stretch_instead_of_banking_the_old_plan_as_work() {
        // The last thing this device recorded ended seven hours ago; the plan it left runs on across the whole gap.
        val s0 = oneTask()
        val work = s0.tasks.values.single { it.title == "Work" }.id
        val lastLine = NOW - 7 * HOUR
        val recorded = s0.tasks.getValue(work).copy(record = listOf(TaskTimeRange(lastLine - HOUR, lastLine)))
        val stale = (0 until 8).map { i -> TaskPanel("auto/$i", work, "Work", lastLine + i * HOUR, lastLine + (i + 1) * HOUR, auto = true) }
        val state = s0.copy(tasks = s0.tasks + (work to recorded), panels = stale)
        val vm = TaskSchedulerViewModel(initial = state, store = null, saveDispatcher = Dispatchers.Default)
        val engine =
            SchedulerEngine(
                vm = vm,
                clock = object : AppClock { override fun nowMillis(): Long = NOW },
                scope = CoroutineScope(Dispatchers.Unconfined),
                screenActive = { true },
                speak = {},
            )
        engine.start()
        val after = vm.state.value.tasks.getValue(work).record
        assertTrue(
            SchedulerDomain.intersectRegions(after, listOf(TaskTimeRange(lastLine + MIN, NOW - MIN))).isEmpty(),
            "the on-screen task is not recorded across a stretch the app did not run in: $after",
        )
    }
}
