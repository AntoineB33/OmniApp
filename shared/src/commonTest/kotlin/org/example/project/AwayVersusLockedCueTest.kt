package org.example.project

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.example.project.scheduler.domain.DynamicPeriods
import org.example.project.scheduler.domain.PeriodKinds
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.engine.SchedulerEngine
import org.example.project.scheduler.model.SleepSchedule
import org.example.project.scheduler.platform.DeviceKind
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.ui.TaskSchedulerViewModel
import org.example.project.time.AppClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNotNull

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
 * **The button changes WHAT IS SCHEDULED, not what may be said** — and that is the whole of how the two
 * halves fit together. Away puts the line in mode 3, where
 * `docs/scheduler_requirements.md` § *$now line$ 3 modes* requires it to be *"covered by the period 'no
 * on-screen task'"*, so an ON-SCREEN task is not at the line to be announced (2026-09-12: the app spoke one
 * anyway, off a plan built for a `t_p` the line had walked away from). A task with a non-zero resilience to
 * that kind IS scheduled there, and the away device is told about it exactly as it always was — which is
 * what still separates this from a lock, where nothing is announced at all because nobody can read it.
 *
 * Both tests drive the engine's real cue sweep over one task panel and differ in nothing but which lever is
 * pulled ([SchedulerEngine.setUserAway] vs. the platform lock behind `screenActive`), which is what makes
 * them a pair: the two reach two different readings of the screen (`effectiveScreenActive`, the presence one,
 * vs. `deviceUnlocked`, the output one).
 */
class AwayVersusLockedCueTest {

    private val start = 5L * 24 * 60 * 60 * 1_000

    /**
     * TWO schedulable tasks and no night, so the plan has something to place at the now-line AND switches
     * between them as the line walks — a switch is what the cue announces, so one task would leave the
     * whole walk at one level and prove nothing either way.
     *
     * [offScreen] gives both a non-zero resilience to "no on-screen task" — the one thing that decides
     * whether they may be scheduled at a line the away modes require to be covered.
     */
    private fun configure(vm: TaskSchedulerViewModel, offScreen: Boolean = false) {
        // Titling the last cell appends a fresh empty one, so the list is re-read between the two.
        listOf("Daily", "Other").forEach { title ->
            val cellIds = vm.state.value.lists[vm.state.value.rootListId]!!.cellIds
            val cellId = assertNotNull(
                cellIds.firstOrNull { vm.state.value.cells[it]?.taskId == null },
                "no empty cell left to title",
            )
            vm.dispatch(SchedulerIntent.SetCellTitle(cellId, title))
        }
        vm.dispatch(SchedulerIntent.SetSleepSchedule(SleepSchedule(sleepDurationMinutes = 0), todayEpochDay = 5L))
        if (offScreen) {
            listOf("Daily", "Other").forEach { title ->
                val taskId = assertNotNull(
                    vm.state.value.tasks.values.firstOrNull { it.title == title }?.id,
                    "no task called $title",
                )
                vm.dispatch(SchedulerIntent.SetTaskResilience(taskId, PeriodKinds.NO_SCREEN, 1.0))
            }
        }
    }

    private fun taskNotifications(vm: TaskSchedulerViewModel) =
        vm.state.value.notificationLog.filter { it.title == "Task to do now" }

    private fun awayEngine(vm: TaskSchedulerViewModel, nowMillis: () -> Long, scope: CoroutineScope) =
        SchedulerEngine(
            vm = vm,
            clock = object : AppClock { override fun nowMillis(): Long = nowMillis() },
            scope = scope,
            deviceKind = DeviceKind.Desktop,
            // The machine stays UNLOCKED throughout — that is the case the button is for, and the only
            // difference from the locked test below.
            screenActive = { true },
            speak = {},
        )

    /**
     * The half the button really owns: a task that CAN be scheduled while nobody is at the screen (a
     * non-zero resilience to "no on-screen task") is announced to the away device exactly as before.
     * Nothing about the away flag reaches the output gate.
     */
    @Test
    fun an_away_device_is_still_told_which_task_to_do() = runTest {
        val scheduler = testScheduler
        val vm = TaskSchedulerViewModel(store = null, saveDispatcher = Dispatchers.Default)
        configure(vm, offScreen = true)
        val engine = awayEngine(vm, { start + scheduler.currentTime }, backgroundScope)
        engine.start()
        advanceTimeBy(2_000) // the rule-change watcher's debounce, then its fill (and its first beat)
        runCurrent()

        engine.setUserAway(true)
        runCurrent()
        assertTrue(engine.userAway.value)

        // The away device is still told, and — the half that matters — the task is still SCHEDULED at the
        // line while the account is away, because it is resilient to "no on-screen task".
        var scheduledAtTheLineWhileAway = false
        repeat(80) {
            advanceTimeBy(30_000)
            runCurrent()
            val st = vm.state.value
            val atLine = SchedulerDomain.currentPanel(st, start + scheduler.currentTime, engine.tpModeNow())
            val task = atLine?.taskId?.let { st.tasks[it] }
            if (task != null && !task.onScreen) scheduledAtTheLineWhileAway = true
        }

        assertEquals(DynamicPeriods.MODE_ON_BREAK, engine.tpModeNow(), "the account never reached mode 3")
        assertTrue(
            scheduledAtTheLineWhileAway,
            "an off-screen task stopped being scheduled at the line while away",
        )
        assertTrue(
            taskNotifications(vm).isNotEmpty(),
            "\"I'm away\" silenced the task notification; it declares an empty screen, not a muted app",
        )
    }

    /**
     * The other half, and the 2026-09-12 anomaly: an ON-SCREEN task is **not scheduled** at a line the away
     * modes require to be covered by "no on-screen task", so there is nothing to announce there.
     *
     * The app announced one anyway (account 3, "Task to do now — planning" at 15:08:40, away since
     * 14:54:15). The cover the fill builds for mode 2/3 is one millisecond wide at the `t_p` it was built
     * for, and time passing never re-plans — so the line walked out of it into the task the plan had put
     * after it, and the cue read that stored panel without ever asking the mode. The calendar was already
     * cutting the panel and the §9 bank was already refusing the record over the same stretch; the cue was
     * the third reading of that rule and the only one that had never been written.
     *
     * Identical to the test above in every line but the resilience, which is the point.
     */
    @Test
    fun an_away_device_is_not_told_to_start_an_on_screen_task() = runTest {
        val scheduler = testScheduler
        val vm = TaskSchedulerViewModel(store = null, saveDispatcher = Dispatchers.Default)
        configure(vm)
        val engine = awayEngine(vm, { start + scheduler.currentTime }, backgroundScope)
        engine.start()
        advanceTimeBy(2_000)
        runCurrent()
        val beforeAway = taskNotifications(vm).size

        engine.setUserAway(true)
        runCurrent()
        assertTrue(engine.userAway.value)

        // Walk well past any pose the bars owe at the origin, sampling what the PLAN put at the line as we
        // go: the cue reads exactly that, so a walk that never met an on-screen task would pass for the
        // wrong reason.
        var planPlacedAnOnScreenTask = false
        repeat(80) {
            advanceTimeBy(30_000)
            runCurrent()
            val st = vm.state.value
            val planned = SchedulerDomain.currentPanel(st, start + scheduler.currentTime)
            val task = planned?.taskId?.let { st.tasks[it] }
            if (task != null && task.onScreen) planPlacedAnOnScreenTask = true
        }

        assertEquals(DynamicPeriods.MODE_ON_BREAK, engine.tpModeNow(), "the account never reached mode 3")
        assertTrue(planPlacedAnOnScreenTask, "the walk never met an on-screen task — the test proves nothing")
        assertEquals(
            beforeAway,
            taskNotifications(vm).size,
            "an on-screen task was announced while the account was away: " +
                "${taskNotifications(vm).drop(beforeAway).map { it.message }}",
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
            speak = {},
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
