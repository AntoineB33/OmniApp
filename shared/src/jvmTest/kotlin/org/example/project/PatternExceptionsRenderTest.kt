package org.example.project

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.unit.Density
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.datetime.TimeZone
import org.example.project.scheduler.domain.PatternExceptions
import org.example.project.scheduler.domain.PeriodKinds
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.SleepSchedule
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState
import org.example.project.ui.LocalPatternExceptionDispatch
import org.example.project.ui.LocalPatternExceptionState
import org.example.project.ui.PatternExceptionsNote

/**
 * User rule 2026-10-11: *"The exception block must be displayed the same way an occurence block is displayed under
 * 'Blocks on the calendar'."* The exceptions of a configuration are drawn as that list's rows — a name, a start and
 * an end, in fields — so the note is laid out here with a moved night (a block) and a removed one (a line), inside
 * and outside the app's own providers, to see that it lays out at all.
 */
@OptIn(InternalComposeUiApi::class)
class PatternExceptionsRenderTest {
    @Test
    fun the_exceptions_lay_out_as_block_rows_with_and_without_the_apps_state() {
        val tz = TimeZone.currentSystemDefault()
        val hour = 3_600_000L
        val day = 24 * hour
        val from = 2_000_000_000_000L - 2_000_000_000_000L % day
        val s = SchedulerState.empty().copy(sleep = SleepSchedule())
        val nights = SchedulerDomain.sleepPanels(s.sleep, from, from + 10 * day, tz)
        val moved =
            SchedulerReducer.reduce(
                s,
                SchedulerIntent.PlaceDerivedPeriod(PeriodKinds.SLEEP, nights[2].startEpochMillis, nights[2].endEpochMillis, nights[2].startEpochMillis + hour, nights[2].endEpochMillis + hour),
            )
        val state = moved.copy(sleep = assertNotNull(moved.sleep).let { it.copy(skippedWakeEpochDays = it.skippedWakeEpochDays + (nights[4].id.removePrefix("sleep/").toLong())) })
        val entries = PatternExceptions.ofSleep(state, tz)
        assertEquals(listOf(true, false), entries.map { it.blockId != null })
        for (inApp in listOf(true, false)) {
            val scene =
                ImageComposeScene(width = 500, height = 700, density = Density(1f)) {
                    MaterialTheme {
                        if (inApp) {
                            CompositionLocalProvider(LocalPatternExceptionState provides state, LocalPatternExceptionDispatch provides {}) {
                                PatternExceptionsNote(entries)
                            }
                        } else {
                            PatternExceptionsNote(entries)
                        }
                    }
                }
            try {
                repeat(5) { frame -> scene.render(frame * 16_000_000L) }
            } finally {
                scene.close()
            }
        }
    }
}
