package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.datetime.TimeZone
import org.example.project.perf.Perf
import org.example.project.scheduler.domain.PeriodKinds
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.SleepSchedule
import org.example.project.scheduler.model.TaskTimeRange
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState
import org.example.project.time.AppClock
import org.example.project.time.SystemAppClock

/**
 * Anomaly 2026-10-11: *"when I changed the starting time of the Sleep period, it froze a moment. Make sure the
 * modification of the calendar doesn't freeze the user when they modify fields in the app."*
 *
 * **An edit of the calendar never plans inside its own reducer.** The edit is reduced on the thread the keystroke came
 * in on; a fill there (with its search) held it — once per text that read as a time, and again each time the engine's
 * own run published under it. The plan is the engine's, off that thread: an edit that changes a rule moves
 * `SchedulerDomain.schedulingSignature`, and one that only rewrites the RECORDS counts itself in
 * `SchedulerState.recordRewrites`, which the engine watches beside it.
 *
 * Read off the fill's own instrument (`scheduler.fillSchedule`): zero calls while the edits below are reduced.
 */
class CalendarEditNeverPlansInlineTest {
    private val hour = 3_600_000L
    private val day = 24 * hour
    private val now = 1_900_000_000_000L - 1_900_000_000_000L % day + 12 * hour

    private fun fills(): Long = Perf.snapshot().sections.firstOrNull { it.name == "scheduler.fillSchedule" }?.calls ?: 0L

    private fun account(): SchedulerState {
        var s = SchedulerState.empty()
        val cell = s.lists.getValue(s.rootListId).cellIds.last()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(cell, "Apple"))
        return SchedulerReducer.reduce(s, SchedulerIntent.SetSleepSchedule(SleepSchedule(), now / day))
    }

    @Test
    fun editing_a_period_removing_work_or_moving_a_night_plans_nothing_on_the_spot() {
        val clockBefore = SchedulerReducer.clock
        val perfBefore = Perf.enabled
        SchedulerReducer.clock = object : AppClock { override fun nowMillis(): Long = now }
        Perf.enabled = true
        try {
            var s = account()
            // Work the line banked this morning: three hours of the first schedulable task (the account's own, if any).
            val task = s.tasks.values.first { it.title == "Apple" }
            val worked = TaskTimeRange(now - 5 * hour, now - 2 * hour)
            s = s.copy(tasks = s.tasks + (task.id to task.copy(record = listOf(worked))))
            Perf.reset()

            // A period laid over that work: the work is hidden under it — a record rewrite, counted, not planned for.
            val laid = SchedulerReducer.reduce(s, SchedulerIntent.AddRestrictivePeriod(PeriodKinds.SLEEP, now - 4 * hour, now - 3 * hour))
            assertTrue(laid.recordRewrites > s.recordRewrites, "the work under the period left the record: the engine is asked")
            // Its start typed, text by text, as a time field writes it.
            var typed = laid
            val period = laid.panels.first { it.restrictiveKind == PeriodKinds.SLEEP && SchedulerDomain.isUserPlaced(it) }
            for (minutes in listOf(-14L, -11L, 30L)) {
                val at = typed.panels.first { it.id == period.id || (it.restrictiveKind == PeriodKinds.SLEEP && SchedulerDomain.isUserPlaced(it)) }
                typed =
                    SchedulerReducer.reduce(
                        typed,
                        SchedulerIntent.UpdateTaskPanel(at.id, at.taskId, at.title, period.startEpochMillis + minutes * 60_000L, at.endEpochMillis, at.pins),
                    )
            }
            assertTrue(typed.recordRewrites > laid.recordRewrites, "each write that moved work asked again")
            // A night of the schedule moved (the anomaly's own field), and work removed from the calendar by hand.
            val night = SchedulerDomain.sleepPanels(typed.sleep, now + day, now + 2 * day, TimeZone.currentSystemDefault()).first()
            val moved =
                SchedulerReducer.reduce(
                    typed,
                    SchedulerIntent.PlaceDerivedPeriod(PeriodKinds.SLEEP, night.startEpochMillis, night.endEpochMillis, night.startEpochMillis + hour, night.endEpochMillis),
                )
            val left = moved.tasks.getValue(task.id).record.firstOrNull()
            val removed = if (left == null) moved else SchedulerReducer.reduce(moved, SchedulerIntent.RemoveRecordPeriod(task.id, left.startEpochMillis, left.endEpochMillis))
            if (left != null) assertEquals(moved.recordRewrites + 1, removed.recordRewrites)

            assertEquals(0L, fills(), "an edit planned inside its reducer")
            // The request is in memory only: it is no part of what is saved or sent.
            assertEquals(0, removed.withLocalViewStateNeutralized().recordRewrites)
        } finally {
            Perf.enabled = perfBefore
            SchedulerReducer.clock = clockBefore
        }
    }
}
