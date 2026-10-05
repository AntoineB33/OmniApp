package org.example.project

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.datetime.TimeZone
import org.example.project.scheduler.domain.BankedBreak
import org.example.project.scheduler.domain.BreakMachine
import org.example.project.scheduler.domain.DynamicPeriods
import org.example.project.scheduler.domain.FrozenScreenBreaks
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.engine.SchedulerEngine
import org.example.project.scheduler.model.SleepSchedule
import org.example.project.scheduler.model.TaskTimeRange
import org.example.project.scheduler.persistence.ActiveSessionRecord
import org.example.project.scheduler.persistence.ActiveSessionStore
import org.example.project.scheduler.persistence.FrozenScreenBreakStore
import org.example.project.scheduler.platform.DeviceSleepGap
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState
import org.example.project.scheduler.ui.TaskSchedulerViewModel
import org.example.project.time.AppClock

/**
 * Account 3, 2026-10-05: **no Sleep period anywhere in the past**. The computer is shut down every night, so the app
 * starts each morning AFTER the night, and past sleep was recorded only for what elapsed "while this session ran" —
 * which, on such a machine, is never a night. (A quota read 64.5 % for it, the nights counted as time at work.)
 *
 * `docs/scheduler_requirements.md` § *frozen past*: the LINE freezes the Sleep period it crosses away from a screen
 * (mode 2 or 3, covered by "no screen"), and the restart catch-up walks the stretch the app did not run in — in mode 1
 * where the OS says the device was unlocked, in mode 2 elsewhere. So the night is frozen by that walk.
 */
class PastSleepAfterShutdownTest {
    private val MIN = 60_000L
    private val HOUR = 60 * MIN
    // 2023-11-15 12:00 UTC; the night before it is 23:00 → 07:30 (the default schedule).
    private val NOON = 1_700_049_600_000L
    private val CLOSED = NOON - 10 * HOUR // 02:00, three hours into the night
    private val WAKE = NOON - 4 * HOUR - 30 * MIN // 07:30

    @AfterTest
    fun resetSeams() {
        SchedulerReducer.frozenScreenBreaks = { null }
        SchedulerReducer.tpMode = { DynamicPeriods.MODE_AT_SCREEN }
    }

    private class MemoryBreaks(val frozen: FrozenScreenBreaks?) : FrozenScreenBreakStore {
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

    private class MemorySessions(first: ActiveSessionRecord) : ActiveSessionStore {
        private val rows = LinkedHashMap<Pair<String, Long>, ActiveSessionRecord>().apply { put(first.deviceId to first.startMillis, first) }
        @Synchronized override fun loadActiveSessions() = rows.values.sortedBy { it.startMillis }
        @Synchronized override fun saveActiveSessions(records: List<ActiveSessionRecord>) {
            records.forEach { rows[it.deviceId to it.startMillis] = it }
        }
        @Synchronized override fun deleteActiveSessionsForDevice(deviceId: String) {
            rows.keys.removeAll { it.first == deviceId }
        }
    }

    /** Starts the app at noon, last run until 02:00, the OS answering [locked]; the past Sleep it then holds. */
    private fun sleepRecordedAfterRestart(locked: List<DeviceSleepGap>?, awaySince: Long? = null): List<TaskTimeRange> {
        var s = SchedulerState.empty()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(s.lists[s.rootListId]!!.cellIds[0], "Work"))
        s = s.copy(sleep = SleepSchedule(), screenBreaks = SchedulerDomain.DEFAULT_SCREEN_BREAKS)
        val machine =
            BreakMachine.State(
                atMillis = CLOSED,
                baseMode = if (awaySince == null) DynamicPeriods.MODE_AT_SCREEN else DynamicPeriods.MODE_AWAY,
                stretchStart = awaySince,
                bars = mapOf(
                    DynamicPeriods.LABEL_20S to CLOSED + 60 * MIN,
                    DynamicPeriods.LABEL_5MIN to CLOSED + 10 * MIN,
                    DynamicPeriods.LABEL_15MIN to CLOSED + 90 * MIN,
                ),
            )
        val scope = CoroutineScope(Dispatchers.Unconfined)
        try {
            val vm = TaskSchedulerViewModel(initial = s, store = null, saveDispatcher = Dispatchers.Default)
            val engine =
                SchedulerEngine(
                    vm = vm,
                    clock = object : AppClock { override fun nowMillis(): Long = NOON },
                    scope = scope,
                    tz = TimeZone.UTC,
                    screenActive = { true },
                    speak = {},
                    postNotification = { _, _, _ -> },
                    clearNotifications = {},
                    lockedIntervalsQuery = { _, _ -> locked },
                    frozenBreakStore = MemoryBreaks(FrozenScreenBreaks(emptyList(), CLOSED, CLOSED, machine = machine)),
                    // The evening before, at work until the app was closed.
                    activeSessionStore = MemorySessions(ActiveSessionRecord("evening", CLOSED - 6 * HOUR, CLOSED, CLOSED)),
                )
            engine.start()
            // The walk waits for the OS's answer off the caller's thread.
            val deadline = System.currentTimeMillis() + 20_000
            while ((engine.frozenBreaks.value?.machine?.atMillis ?: Long.MIN_VALUE) < NOON - 1_000L) {
                assertTrue(System.currentTimeMillis() < deadline, "the catch-up never walked the line to the clock")
                Thread.sleep(20)
            }
            Thread.sleep(300)
            return vm.state.value.panels
                .filter { it.sleep && !it.id.startsWith("sleep/") && it.endEpochMillis <= NOON }
                .map { TaskTimeRange(it.startEpochMillis, it.endEpochMillis) }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun the_night_a_shut_down_computer_slept_through_is_recorded_as_sleep() {
        val recorded = sleepRecordedAfterRestart(locked = listOf(DeviceSleepGap(CLOSED, NOON)))
        assertEquals(listOf(TaskTimeRange(CLOSED, WAKE)), recorded, "from where the app stopped to the scheduled wake")
    }

    @Test
    fun a_process_ended_at_a_locked_computer_keeps_the_part_of_the_night_before_it() {
        // Locked at 22:00, the computer restarted by itself at 02:00: the line was in mode 2 from 22:00 on.
        val recorded = sleepRecordedAfterRestart(locked = listOf(DeviceSleepGap(CLOSED, NOON)), awaySince = CLOSED - 4 * HOUR)
        assertEquals(listOf(TaskTimeRange(NOON - 13 * HOUR, WAKE)), recorded, "the whole Sleep period, 23:00 → 07:30")
    }

    @Test
    fun a_night_spent_at_an_unlocked_computer_with_the_app_closed_is_no_sleep() {
        assertEquals(emptyList(), sleepRecordedAfterRestart(locked = emptyList()))
    }

    @Test
    fun only_the_locked_part_of_the_night_is_sleep() {
        val recorded = sleepRecordedAfterRestart(locked = listOf(DeviceSleepGap(CLOSED + HOUR, NOON)))
        assertEquals(listOf(TaskTimeRange(CLOSED + HOUR, WAKE)), recorded)
    }

    @Test
    fun a_lock_history_that_cannot_be_read_is_walked_in_mode_2_and_so_freezes_the_night() {
        // The whole stretch is walked in mode 2 then (`CatchUpAtUnlockedScreenTest`), and the mode is what decides.
        assertEquals(listOf(TaskTimeRange(CLOSED, WAKE)), sleepRecordedAfterRestart(locked = null))
    }

    /** A device asleep with the app in it: the wake's fast move, from 22:00 to noon, [atScreen] walked in mode 1. */
    private fun sleepFrozenByWake(atScreen: List<TaskTimeRange>): List<TaskTimeRange> {
        var s = SchedulerState.empty()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(s.lists[s.rootListId]!!.cellIds[0], "Work"))
        s = s.copy(sleep = SleepSchedule(), screenBreaks = SchedulerDomain.DEFAULT_SCREEN_BREAKS)
        val scope = CoroutineScope(Dispatchers.Unconfined)
        try {
            val vm = TaskSchedulerViewModel(initial = s, store = null, saveDispatcher = Dispatchers.Default)
            val engine =
                SchedulerEngine(
                    vm = vm,
                    clock = object : AppClock { override fun nowMillis(): Long = NOON },
                    scope = scope,
                    tz = TimeZone.UTC,
                    screenActive = { true },
                    speak = {},
                    postNotification = { _, _, _ -> },
                    clearNotifications = {},
                )
            engine.reportTimeGap(NOON - 14 * HOUR, NOON, atScreenSpans = atScreen)
            return vm.state.value.panels
                .filter { it.sleep && !it.id.startsWith("sleep/") && it.endEpochMillis <= NOON }
                .map { TaskTimeRange(it.startEpochMillis, it.endEpochMillis) }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun the_fast_move_of_a_wake_freezes_the_whole_sleep_period_it_crosses_in_mode_2() {
        assertEquals(listOf(TaskTimeRange(NOON - 13 * HOUR, WAKE)), sleepFrozenByWake(emptyList()), "23:00 → 07:30")
    }

    @Test
    fun the_part_of_a_sleep_period_the_line_crosses_in_mode_1_is_not_frozen() {
        // At a screen until 01:00: the Sleep period's "no screen" retracted at the line till then.
        val frozen = sleepFrozenByWake(listOf(TaskTimeRange(NOON - 14 * HOUR, NOON - 11 * HOUR)))
        assertEquals(listOf(TaskTimeRange(NOON - 11 * HOUR, WAKE)), frozen)
    }
}
