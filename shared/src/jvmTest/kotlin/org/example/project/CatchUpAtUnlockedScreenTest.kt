package org.example.project

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
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
import org.example.project.scheduler.model.TaskTimeRange
import org.example.project.scheduler.persistence.FrozenScreenBreakStore
import org.example.project.scheduler.platform.DeviceSleepGap
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState
import org.example.project.scheduler.ui.TaskSchedulerViewModel
import org.example.project.time.AppClock

/**
 * Account 3, 2026-09-30: the app was closed 15:52:21 → 16:29:49 while the computer stayed unlocked (no screen-off
 * event all afternoon), and after the restart **the next 5-min break was 17:29:49** — the restart + 1 h — instead of
 * dragged by the line. The restart catch-up walked the whole stretch in mode 2 and noted it as "no screen", so
 * *"after a ≥ 5-minute of 'no screen', no 5min break in the next 1 hour"* fired at the landing.
 *
 * An app that was not running is not a device asleep: the stretch the OS says the device was unlocked for is mode 1.
 */
class CatchUpAtUnlockedScreenTest {
    private val SEC = 1_000L
    private val MIN = 60_000L
    private val HOUR = 60 * MIN
    private val T0 = 1_700_000_000_000L
    private val CLOSED = T0 - 37 * MIN

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
            machine: org.example.project.scheduler.domain.BreakMachine.State?,
        ) {}
    }

    /**
     * Starts an engine whose line was last at `T0 − 37 min`, its 5-min break due ten minutes later, with the OS answering
     * [locked]; returns the machine the line carries once it has caught up, and the "no screen" the engine now knows of.
     */
    private fun afterRestart(locked: List<DeviceSleepGap>?): Pair<BreakMachine.State, List<TaskTimeRange>> {
        var s = SchedulerState.empty()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(s.lists[s.rootListId]!!.cellIds[0], "Work"))
        s = s.copy(sleep = SleepSchedule(sleepDurationMinutes = 0), screenBreaks = SchedulerDomain.DEFAULT_SCREEN_BREAKS)
        val closed = CLOSED
        val lastLookAway = BankedBreak(DynamicPeriods.LABEL_20S, closed - 20 * SEC, closed)
        val machine =
            BreakMachine.State(
                atMillis = closed,
                baseMode = DynamicPeriods.MODE_AT_SCREEN,
                bars = mapOf(
                    DynamicPeriods.LABEL_20S to closed + 60 * MIN,
                    DynamicPeriods.LABEL_5MIN to closed + 10 * MIN,
                    DynamicPeriods.LABEL_15MIN to closed + 90 * MIN,
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
                    lockedIntervalsQuery = { _, _ -> locked },
                    frozenBreakStore = MemoryStore(FrozenScreenBreaks(listOf(lastLookAway), closed, closed, machine = machine)),
                )
            engine.start()
            // The walk waits for the OS's answer off the caller's thread.
            val deadline = System.currentTimeMillis() + 20_000
            while ((engine.frozenBreaks.value?.machine?.atMillis ?: Long.MIN_VALUE) < T0 - SEC) {
                assertTrue(System.currentTimeMillis() < deadline, "the catch-up never walked the line to the clock")
                Thread.sleep(20)
            }
            return engine.frozenBreaks.value!!.machine!! to engine.noScreenEvidence.value
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun an_app_closed_at_an_unlocked_computer_is_no_rest() {
        val (m, noScreen) = afterRestart(locked = emptyList())
        assertEquals(emptyList(), noScreen, "the stretch the app was closed at an unlocked computer is not \"no screen\"")
        assertEquals(DynamicPeriods.LABEL_5MIN, m.drag?.label, "the 5-min break due in it is dragged by the line")
        assertEquals(CLOSED + 10 * MIN, m.drag?.dueMillis, "…from where it fell due, in mode 1")
        assertTrue(m.bars.getValue(DynamicPeriods.LABEL_5MIN) < T0 + HOUR, "and not barred an hour past the restart")
    }

    @Test
    fun an_app_closed_with_its_device_asleep_is_no_screen() {
        val (_, noScreen) = afterRestart(locked = listOf(DeviceSleepGap(CLOSED, T0)))
        assertEquals(listOf(TaskTimeRange(CLOSED, T0)), noScreen)
    }

    @Test
    fun a_lock_history_that_cannot_be_read_walks_it_in_mode_2_as_before() {
        val (_, noScreen) = afterRestart(locked = null)
        assertEquals(listOf(TaskTimeRange(CLOSED, T0)), noScreen)
    }

    @Test
    fun only_the_unlocked_part_of_the_stretch_is_walked_at_a_screen() {
        // Asleep for the first twenty minutes, then unlocked with the app still closed.
        val (m, noScreen) = afterRestart(locked = listOf(DeviceSleepGap(CLOSED, CLOSED + 20 * MIN)))
        assertEquals(listOf(TaskTimeRange(CLOSED, CLOSED + 20 * MIN)), noScreen)
        assertEquals(null, m.stretchStart, "the line lands at a screen")
    }
}
