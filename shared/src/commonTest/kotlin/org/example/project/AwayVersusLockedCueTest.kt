package org.example.project

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.example.project.scheduler.engine.SchedulerEngine
import org.example.project.scheduler.model.SleepSchedule
import org.example.project.scheduler.platform.DeviceKind
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.ui.TaskSchedulerViewModel
import org.example.project.time.AppClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * PRD §15: **"I'm away" declares an empty screen; a LOCK silences the device — and they are not the same
 * statement.**
 *
 * A locked device says nothing: nobody can read a notification or be spoken to at a lock screen, which is
 * precisely why the break-over message for a locked device comes from the server's push and not from the
 * app's own sweep (`docs/PAUSE_CUE_DELIVERY.md`). "I'm away" says something else entirely — *nobody is at
 * this screen* — which feeds the no-screen periods, the `t_p` mode and the account-wide idleness the pause
 * cue is judged on, and nothing else. The user who presses it is routinely still at the machine, which they
 * left unlocked so a program keeps running; the "task to do now" notification is exactly what they are still
 * there to act on, so the button must not take it away.
 *
 * Both tests drive the engine's real cue sweep over one task panel and differ in nothing but which lever is
 * pulled ([SchedulerEngine.setUserAway] vs. the platform lock behind `screenActive`), which is what makes
 * them a pair: the two reach two different readings of the screen (`effectiveScreenActive`, the presence one,
 * vs. `deviceUnlocked`, the output one).
 */
class AwayVersusLockedCueTest {

    private val start = 5L * 24 * 60 * 60 * 1_000

    /** One schedulable task and no night, so the plan has something to place at the now-line. */
    private fun configure(vm: TaskSchedulerViewModel) {
        val cellId = vm.state.value.lists[vm.state.value.rootListId]!!.cellIds.first()
        vm.dispatch(SchedulerIntent.SetCellTitle(cellId, "Daily"))
        vm.dispatch(SchedulerIntent.SetSleepSchedule(SleepSchedule(sleepDurationMinutes = 0), todayEpochDay = 5L))
    }

    private fun taskNotifications(vm: TaskSchedulerViewModel) =
        vm.state.value.notificationLog.filter { it.title == "Task to do now" }

    @Test
    fun an_away_device_is_still_told_which_task_to_do() = runTest {
        val scheduler = testScheduler
        val vm = TaskSchedulerViewModel(store = null, saveDispatcher = Dispatchers.Default)
        val engine = SchedulerEngine(
            vm = vm,
            clock = object : AppClock { override fun nowMillis(): Long = start + scheduler.currentTime },
            scope = backgroundScope,
            deviceKind = DeviceKind.Desktop,
            // The machine stays UNLOCKED throughout — that is the case the button is for.
            screenActive = { true },
            playCue = {},
        )
        configure(vm)
        engine.start()
        advanceTimeBy(2_000) // the rule-change watcher's debounce, then its fill
        runCurrent()

        engine.setUserAway(true)
        runCurrent()
        assertTrue(engine.userAway.value)

        // Let the now-line walk into the plan the away re-fill laid ahead of it.
        repeat(10) {
            advanceTimeBy(30_000)
            runCurrent()
        }

        assertEquals(
            listOf("Daily"),
            taskNotifications(vm).map { it.message },
            "\"I'm away\" silenced the task notification; it declares an empty screen, not a muted app",
        )
    }

    @Test
    fun a_locked_device_is_told_nothing() = runTest {
        val scheduler = testScheduler
        var unlocked = false
        val vm = TaskSchedulerViewModel(store = null, saveDispatcher = Dispatchers.Default)
        val engine = SchedulerEngine(
            vm = vm,
            clock = object : AppClock { override fun nowMillis(): Long = start + scheduler.currentTime },
            scope = backgroundScope,
            deviceKind = DeviceKind.Desktop,
            // The same absence, declared by the OS instead of by the button.
            screenActive = { unlocked },
            playCue = {},
        )
        configure(vm)
        engine.start()
        advanceTimeBy(2_000)
        runCurrent()

        // Well past the 15-min pose the bars owe at the origin, so the now-line is inside a task panel the
        // whole second half of this walk — the sweep has a task to announce and stays silent anyway.
        repeat(80) {
            advanceTimeBy(30_000)
            runCurrent()
        }

        assertTrue(
            taskNotifications(vm).isEmpty(),
            "a locked device announced ${taskNotifications(vm).map { it.message }}",
        )

        // ...and it is suppressed, not spent: the level is left untouched, so the task is announced the
        // moment the machine is unlocked again rather than being lost to a de-dupe nobody heard.
        unlocked = true
        engine.onPlatformActivityChanged()
        repeat(4) {
            advanceTimeBy(30_000)
            runCurrent()
        }
        assertEquals(
            listOf("Daily"),
            taskNotifications(vm).map { it.message },
            "the task the user came back to was never announced",
        )
    }
}
