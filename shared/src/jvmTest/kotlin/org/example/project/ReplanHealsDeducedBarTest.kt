package org.example.project

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import org.example.project.scheduler.domain.BankedBreak
import org.example.project.scheduler.domain.BreakMachine
import org.example.project.scheduler.domain.DynamicPeriods
import org.example.project.scheduler.domain.FrozenScreenBreaks
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.engine.SchedulerEngine
import org.example.project.scheduler.model.SleepSchedule
import org.example.project.scheduler.persistence.FrozenScreenBreakStore
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState
import org.example.project.scheduler.ui.TaskSchedulerViewModel
import org.example.project.time.AppClock

/**
 * Account 3, 2026-09-30: the persisted break machine carried a 5-min bar at restart + 1 h that nothing in history held
 * (a stretch at an unlocked computer counted as "no screen"). `docs/scheduler_requirements.md` § *Use of the set of
 * rules output*: the scheduler's next run drops what was deduced but not saved — so the start-up re-plan heals it, and
 * the 5-min break is dragged by the line.
 */
class ReplanHealsDeducedBarTest {
    private val MIN = 60_000L
    private val HOUR = 60 * MIN
    private val T0 = 1_700_000_000_000L

    @AfterTest
    fun resetSeams() {
        SchedulerReducer.frozenScreenBreaks = { null }
        SchedulerReducer.tpMode = { DynamicPeriods.MODE_AT_SCREEN }
    }

    private class MemoryStore(var frozen: FrozenScreenBreaks?) : FrozenScreenBreakStore {
        override fun loadFrozenScreenBreaks() = frozen
        override fun saveFrozenScreenBreaks(
            added: List<BankedBreak>,
            untilMillis: Long,
            lineMillis: Long,
            pruneBeforeMillis: Long,
            removed: List<BankedBreak>,
            machine: BreakMachine.State?,
        ) {}
    }

    @Test
    fun the_start_up_re_plan_drops_a_bar_history_does_not_hold() {
        var s = SchedulerState.empty()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(s.lists[s.rootListId]!!.cellIds[0], "Work"))
        s = s.copy(sleep = SleepSchedule(sleepDurationMinutes = 0), screenBreaks = SchedulerDomain.DEFAULT_SCREEN_BREAKS)
        val line = T0 - 10_000L
        val lastPose = BankedBreak(DynamicPeriods.LABEL_5MIN, T0 - 2 * HOUR - 5 * MIN, T0 - 2 * HOUR)
        val lastLookAway = BankedBreak(DynamicPeriods.LABEL_20S, T0 - 5 * MIN, T0 - 5 * MIN + 20_000L)
        val machine =
            BreakMachine.State(
                atMillis = line,
                baseMode = DynamicPeriods.MODE_AT_SCREEN,
                bars = mapOf(
                    DynamicPeriods.LABEL_20S to lastLookAway.endMillis + 20 * MIN,
                    // The deduced bar: nothing in history sets it.
                    DynamicPeriods.LABEL_5MIN to T0 + HOUR - 20 * MIN,
                    DynamicPeriods.LABEL_15MIN to T0 + HOUR,
                ),
            )
        val scope = CoroutineScope(Dispatchers.Unconfined)
        try {
            val vm = TaskSchedulerViewModel(initial = s, store = null, saveDispatcher = Dispatchers.Default)
            val engine =
                SchedulerEngine(
                    vm = vm,
                    clock = object : AppClock { override fun nowMillis(): Long = T0 },
                    scope = scope,
                    screenActive = { true },
                    speak = {},
                    postNotification = { _, _ -> },
                    clearNotifications = {},
                    lockedIntervalsQuery = { _, _ -> emptyList() },
                    frozenBreakStore = MemoryStore(FrozenScreenBreaks(listOf(lastPose, lastLookAway), line, line, machine = machine)),
                )
            engine.start()
            // The rule-change collector's first run re-plans after its debounce (real time on this scope).
            val deadline = System.currentTimeMillis() + 20_000
            while (engine.frozenBreaks.value?.machine?.bars?.get(DynamicPeriods.LABEL_5MIN) != T0 - HOUR) {
                assertTrue(
                    System.currentTimeMillis() < deadline,
                    "the re-plan did not rebuild the 5-min bar from history: ${engine.frozenBreaks.value?.machine}",
                )
                Thread.sleep(20)
            }
            // The next step moves the line on from there: the 5-min break is due, and a line at a screen drags it.
            engine.advanceTo(T0)
            val drag = engine.frozenBreaks.value?.machine?.drag
            assertTrue(drag?.label == DynamicPeriods.LABEL_5MIN, "the 5-min break is dragged by the line: $drag")
        } finally {
            scope.cancel()
        }
    }
}
