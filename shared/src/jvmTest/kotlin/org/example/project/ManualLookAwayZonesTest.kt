package org.example.project

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.PeriodKindConfig
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.engine.SchedulerEngine
import org.example.project.scheduler.model.ScreenBreak
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.ui.TaskSchedulerViewModel
import org.example.project.time.AppClock

/**
 * [ManualLookAwayTest]'s scenario under a time zone of its own. Anomaly 2026-11-15: that test was red whenever its fixed
 * instant (1970-01-06 00:00 UTC) fell inside the default Sleep window in the MACHINE's zone (Paris: 01:00). There a
 * machine started from history ([org.example.project.scheduler.domain.BreakMachine.absorbHistory]) knew the bar the
 * conducted look-away set, but not that it took the night's one 20 s break, and the pull ("starts at max(now line, t_s)")
 * put the next one a second after it. Both zones now, whatever the machine's.
 */
class ManualLookAwayZonesTest {
    private val SEC = 1_000L
    private val MIN = 60 * SEC

    private fun inZone(id: String) {
        val before = java.util.TimeZone.getDefault()
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone(id))
        try {
            scenario()
        } finally {
            java.util.TimeZone.setDefault(before)
        }
    }

    /** 01:00 local: inside the default Sleep window, a "no screen" period. */
    @Test
    fun at_night() = inZone("Europe/Paris")

    /** 16:00 local: no restrictive period around. */
    @Test
    fun in_the_afternoon() = inZone("America/Los_Angeles")

    private fun scenario() = runTest {
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
            speak = {},
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
                    ),
                ),
            ),
        )
        // A conducted look-away is recorded as what it is: a restrictive period the app placed, 20 seconds
        // of "no task allowed" ending where the break ended. The recurrence bars then read it out of the
        // timeline like any other rest stretch.
        fun conducted() =
            vm.state.value.panels.filter {
                // A period the APP placed — not one the recurrence bars projected (those carry `screenBreak`).
                it.inactivity && it.endEpochMillis - it.startEpochMillis == 20 * SEC
            }
        fun messages() = vm.state.value.notificationLog.map { it.message }

        // The press announces the break — and records nothing. A break that just started has not happened.
        engine.restartLookAway()
        runCurrent()
        assertEquals(listOf("look 20 feet away"), messages())
        assertTrue(conducted().isEmpty(), "nothing is written into the past on the press")

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
        assertTrue(conducted().isEmpty(), "an interrupted look-away leaves no trace")
        assertTrue(messages().none { it == "Resume your work" })

        // The second one runs its full 20 s. NOW it is recorded, exactly where it happened.
        advanceTimeBy(8 * SEC)
        runCurrent()
        assertEquals("Resume your work", messages().last())
        assertTrue(
            conducted().any { it.startEpochMillis == manualStart && it.endEpochMillis == manualStart + 20 * SEC },
            "the conducted break is recorded where it happened: ${conducted().map { it.startEpochMillis }}",
        )
        // Not the interrupted run, and not the 20 s before the press (the old anchor-at-the-start bug).
        assertTrue(conducted().none { it.startEpochMillis == origin })
        assertTrue(conducted().none { it.startEpochMillis == manualStart - 20 * SEC })
        // And it bars the next 20 s period for twenty minutes, by the README's own rule — no anchor needed.
        val next = SchedulerDomain.nextScreenBreakStartMillis(
            vm.state.value.screenBreaks,
            "look 20 feet away",
            clock.nowMillis(),
            basePeriods = SchedulerDomain.restrictivePeriodsOf(vm.state.value.panels, PeriodKindConfig.DEFAULT),
        )
        assertTrue(
            next != null && next >= manualStart + 20 * SEC + 20 * MIN,
            "the conducted break must bar the next one for twenty minutes; got $next",
        )
    }
}
