package org.example.project

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.engine.SchedulerEngine
import org.example.project.scheduler.model.ScreenBreak
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.ui.TaskSchedulerViewModel
import org.example.project.time.AppClock

/**
 * PRD §15, the manual "Look away now" (`SchedulerEngine.restartLookAway`): what a screen break leaves behind
 * on the calendar is decided by the ANCHOR, and the anchor is an END — so it may only move when a break
 * really finished.
 *
 * The bug this pins: the press stamped `lastRestMillis = now`, i.e. an END at the break's START. That drew a
 * 20-s break over the 20 s BEFORE the manual one (the run it had just interrupted, offset by however late the
 * press came), and the manual break itself — the one that actually happened — was never drawn at all, since
 * nothing moved the anchor when it finished.
 */
class ManualLookAwayTest {
    private val SEC = 1_000L
    private val MIN = 60 * SEC

    @Test
    fun a_manual_look_away_is_recorded_when_it_ENDS_and_the_run_it_interrupted_is_erased() = runTest {
        val origin = 5L * 24 * 60 * 60 * 1_000
        val scheduler = testScheduler
        val clock = object : AppClock {
            override fun nowMillis(): Long = origin + scheduler.currentTime
        }
        val vm = TaskSchedulerViewModel(store = null, saveDispatcher = Dispatchers.Default)
        val engine = SchedulerEngine(
            vm = vm,
            clock = clock,
            scope = backgroundScope,
            screenActive = { true },
            playCue = {},
        )
        // Anchored 10 min back, so its own occurrence is not due for another 10 min: every break drawn here is
        // a manual one, and the anchor can only have moved because a manual break finished.
        val anchor0 = origin - 10 * MIN
        vm.dispatch(
            SchedulerIntent.SetScreenBreaks(
                listOf(
                    ScreenBreak(
                        title = "look 20 feet away",
                        intervalMillis = 20 * MIN,
                        durationMillis = 20 * SEC,
                        lastRestMillis = anchor0,
                    ),
                ),
            ),
        )
        fun anchor(): Long = vm.state.value.screenBreaks.single().lastRestMillis
        fun taken() =
            SchedulerDomain.takenScreenBreakPanels(vm.state.value.screenBreaks, origin - MIN, clock.nowMillis() - 1)
        fun messages() = vm.state.value.notificationLog.map { it.message }

        // The press announces the break — and moves nothing. The anchor is the end of the last rest that was
        // TAKEN, and this one has not been taken yet.
        engine.restartLookAway()
        runCurrent()
        assertEquals(listOf("look 20 feet away"), messages())
        assertEquals(anchor0, anchor(), "the anchor is an END: a break that just started has not happened")
        assertTrue(taken().isEmpty(), "and nothing is written into the past on the press")

        // 8 s in, press again: the run in progress is superseded and never finishes.
        advanceTimeBy(8 * SEC)
        runCurrent()
        val manualStart = clock.nowMillis()
        engine.restartLookAway()
        runCurrent()
        assertEquals(listOf("look 20 feet away", "look 20 feet away"), messages())

        // Past the interrupted run's would-be end (origin + 20 s) it is still nowhere: it is erased, not
        // half-drawn, and it did not announce "resume your work" either.
        advanceTimeBy(13 * SEC)
        runCurrent()
        assertEquals(anchor0, anchor(), "an interrupted look-away leaves no trace")
        assertTrue(taken().isEmpty())
        assertTrue(messages().none { it == "Resume your work" })

        // The second one runs its full 20 s. NOW the anchor moves — to its END — and the calendar keeps it,
        // drawn exactly where it happened.
        advanceTimeBy(8 * SEC)
        runCurrent()
        assertEquals(manualStart + 20 * SEC, anchor(), "a break that finished is served at its end")
        assertEquals("Resume your work", messages().last())
        val drawn = taken().single()
        assertTrue(drawn.screenBreak)
        assertEquals(manualStart, drawn.startEpochMillis)
        assertEquals(manualStart + 20 * SEC, drawn.endEpochMillis)
        // Not the interrupted run, and not the 20 s before the press (the old anchor-at-the-start bug).
        assertTrue(drawn.startEpochMillis != origin && drawn.startEpochMillis != manualStart - 20 * SEC)
        // And the next occurrence recurs an interval after it ENDED.
        assertEquals(
            manualStart + 20 * SEC + 20 * MIN,
            SchedulerDomain.screenBreakNextStart(vm.state.value.screenBreaks.single(), clock.nowMillis()),
        )
    }
}
