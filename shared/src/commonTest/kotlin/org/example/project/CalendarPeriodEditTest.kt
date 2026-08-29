package org.example.project

import org.example.project.scheduler.domain.PeriodKinds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.time.AppClock
import org.example.project.scheduler.model.PanelPins
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.model.TaskPanel
import org.example.project.scheduler.model.TaskTimeRange
import org.example.project.scheduler.persistence.SchedulerStateCodec
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState

/**
 * PRD §8 the period editor: "add a no-screen period" / "add an inactivity period" no longer lay a fixed
 * hour at the right-click — the user gives the bounds, which may be **open** ("∞"). What that unlocks is
 * the case this file pins down: an **inactivity period from ∞ to now**, which says nothing at all happened
 * in the recorded past and therefore clears it.
 *
 * The two rules being tested are the two halves of "grey means the scheduler places nothing here":
 *  - a grey period overrides every TASK PANEL it covers, on-screen or off-screen (a no-screen period still
 *    only overrides the on-screen ones — §9 lets an off-screen task run inside one);
 *  - a hand-laid period strips the RECORDS banked under its elapsed part right away, instead of waiting for
 *    the next engine start's [SchedulerIntent.StripNoScreenRecords] pass.
 *
 * The window itself is Compose-only state (the bounds it resolves arrive as ordinary intents), so what is
 * verifiable here is the reducer side plus the "∞" sentinels the window writes.
 */
class CalendarPeriodEditTest {

    private val HOUR = 3_600_000L
    private val NOW = 1_000_000_000_000L // fixed reference instant

    private class FixedClock(var now: Long) : AppClock {
        override fun nowMillis(): Long = now
    }

    /** Runs [body] with the reducer's clock pinned at [NOW] (the strip refills the schedule off it). */
    private fun withClock(body: () -> Unit) {
        val previous = SchedulerReducer.clock
        SchedulerReducer.clock = FixedClock(NOW)
        try {
            body()
        } finally {
            SchedulerReducer.clock = previous
        }
    }

    /** Two leaf tasks, one on screen ("Screen") and one off ("Away"). */
    private fun twoTasks(): Triple<SchedulerState, TaskId, TaskId> {
        var s = SchedulerState.empty()
        val c0 = s.lists[s.rootListId]!!.cellIds[0]
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(c0, "Screen"))
        val screen = s.tasks.keys.first { s.tasks[it]!!.title == "Screen" }
        val c1 = s.lists[s.rootListId]!!.cellIds[1]
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(c1, "Away"))
        val away = s.tasks.keys.first { s.tasks[it]!!.title == "Away" }
        s = SchedulerReducer.reduce(
            s,
            SchedulerIntent.SetTaskResilience(away, PeriodKinds.NO_SCREEN, 1.0),
        )
        return Triple(s, screen, away)
    }

    /** Banks `[start, end]` as completed work for [taskId], bypassing the advance tick. */
    private fun withRecord(s: SchedulerState, taskId: TaskId, start: Long, end: Long): SchedulerState {
        val task = s.tasks.getValue(taskId)
        return s.copy(tasks = s.tasks + (taskId to task.copy(record = task.record + TaskTimeRange(start, end))))
    }

    // ----- the "∞" bounds ---------------------------------------------------------------------

    @Test
    fun the_open_bounds_are_recognized_as_infinity() {
        assertTrue(SchedulerDomain.isOpenPast(SchedulerDomain.OPEN_PAST_MILLIS))
        assertTrue(SchedulerDomain.isOpenFuture(SchedulerDomain.OPEN_FUTURE_MILLIS))
        assertFalse(SchedulerDomain.isOpenPast(NOW))
        assertFalse(SchedulerDomain.isOpenFuture(NOW))
        // Real instants, not saturating sentinels: every consumer does ordinary arithmetic on a panel's
        // bounds, and the whole open span must still be a positive, non-overflowing duration.
        val span = SchedulerDomain.OPEN_FUTURE_MILLIS - SchedulerDomain.OPEN_PAST_MILLIS
        assertTrue(span > 0)
        assertTrue(SchedulerDomain.OPEN_PAST_MILLIS + SchedulerDomain.MIN_MANUAL_ENTRY_MILLIS < NOW)
    }

    @Test
    fun an_open_period_round_trips_through_the_codec() = withClock {
        val s = SchedulerReducer.reduce(
            SchedulerState.empty(),
            SchedulerIntent.AddInactivityPeriod(SchedulerDomain.OPEN_PAST_MILLIS, NOW),
        )
        val decoded = SchedulerStateCodec.decode(SchedulerStateCodec.encode(s))
        assertNotNull(decoded)
        val panel = decoded.panels.single { it.inactivity }
        assertEquals(SchedulerDomain.OPEN_PAST_MILLIS, panel.startEpochMillis)
        assertTrue(SchedulerDomain.isOpenPast(panel.startEpochMillis))
        assertEquals(NOW, panel.endEpochMillis)
    }

    @Test
    fun an_open_ended_inactivity_period_keeps_the_scheduler_out_of_the_whole_future() = withClock {
        val (s0, screen, _) = twoTasks()
        val s = SchedulerReducer.reduce(
            s0,
            SchedulerIntent.AddInactivityPeriod(NOW + HOUR, SchedulerDomain.OPEN_FUTURE_MILLIS),
        )
        val taskPanels = SchedulerDomain.fillSchedule(s, NOW).filter { it.taskId == screen }
        assertTrue(
            taskPanels.all { it.endEpochMillis <= NOW + HOUR },
            "nothing may be scheduled past the start of a never-ending grey period: $taskPanels",
        )
    }

    // ----- grey overrides every task panel ----------------------------------------------------

    @Test
    fun an_inactivity_period_overrides_the_off_screen_task_panel_too() = withClock {
        val (s0, screen, away) = twoTasks()
        var s = SchedulerReducer.reduce(
            s0,
            SchedulerIntent.AddTaskPanel(screen, "Screen", NOW, NOW + 2 * HOUR, PanelPins(existence = true)),
        )
        s = SchedulerReducer.reduce(
            s,
            SchedulerIntent.AddTaskPanel(away, "Away", NOW + 2 * HOUR, NOW + 4 * HOUR, PanelPins(existence = true)),
        )
        // Grey refuses everybody (PRD §8/§9), so BOTH panels are cut back to the period's start — unlike a
        // no-screen period, which an off-screen task is allowed to run inside.
        s = SchedulerReducer.reduce(s, SchedulerIntent.AddInactivityPeriod(NOW + HOUR, NOW + 5 * HOUR))
        val kept = s.panels.filter { it.taskId != null }
        assertEquals(1, kept.size, "the off-screen panel is fully covered and must be deleted: $kept")
        assertEquals(screen, kept[0].taskId)
        assertEquals(NOW + HOUR, kept[0].endEpochMillis)
    }

    @Test
    fun an_off_screen_task_panel_laid_over_an_inactivity_period_trims_it() = withClock {
        val (s0, _, away) = twoTasks()
        var s = SchedulerReducer.reduce(s0, SchedulerIntent.AddInactivityPeriod(NOW, NOW + 2 * HOUR))
        s = SchedulerReducer.reduce(
            s,
            SchedulerIntent.AddTaskPanel(away, "Away", NOW + HOUR, NOW + 3 * HOUR, PanelPins(existence = true)),
        )
        val period = s.panels.single { it.inactivity }
        assertEquals(NOW, period.startEpochMillis)
        assertEquals(NOW + HOUR, period.endEpochMillis)
    }

    @Test
    fun a_no_screen_period_still_leaves_the_off_screen_task_panel_alone() = withClock {
        val (s0, _, away) = twoTasks()
        var s = SchedulerReducer.reduce(
            s0,
            SchedulerIntent.AddTaskPanel(away, "Away", NOW, NOW + 2 * HOUR, PanelPins(existence = true)),
        )
        s = SchedulerReducer.reduce(s, SchedulerIntent.AddNoScreenPeriod(NOW, NOW + 2 * HOUR))
        val panel = s.panels.single { it.taskId == away }
        assertEquals(NOW to NOW + 2 * HOUR, panel.startEpochMillis to panel.endEpochMillis)
    }

    // ----- a laid period strips the work banked under it --------------------------------------

    @Test
    fun an_inactivity_period_from_infinity_to_now_erases_the_recorded_past() = withClock {
        val (s0, screen, away) = twoTasks()
        var s = withRecord(s0, screen, NOW - 5 * HOUR, NOW - 4 * HOUR)
        s = withRecord(s, away, NOW - 3 * HOUR, NOW - 2 * HOUR)
        s = s.copy(
            panels = s.panels + TaskPanel(
                id = "panel/kept",
                taskId = screen,
                title = "Screen",
                startEpochMillis = NOW - 6 * HOUR,
                endEpochMillis = NOW - 5 * HOUR,
                pinned = true,
            ),
        )
        s = SchedulerReducer.reduce(
            s,
            SchedulerIntent.AddInactivityPeriod(SchedulerDomain.OPEN_PAST_MILLIS, NOW),
        )

        assertTrue(s.tasks.getValue(screen).record.isEmpty(), "on-screen work in the erased past must go")
        assertTrue(s.tasks.getValue(away).record.isEmpty(), "grey refuses the off-screen task too")
        assertTrue(
            s.panels.none { it.taskId != null && it.startEpochMillis < NOW },
            "every past task panel is covered by the period: ${s.panels}",
        )
        val period = s.panels.single { it.inactivity }
        assertTrue(SchedulerDomain.isOpenPast(period.startEpochMillis))
        assertEquals(NOW, period.endEpochMillis)
    }

    @Test
    fun a_no_screen_period_strips_only_the_on_screen_work_banked_under_it() = withClock {
        val (s0, screen, away) = twoTasks()
        var s = withRecord(s0, screen, NOW - 3 * HOUR, NOW - 2 * HOUR)
        s = withRecord(s, away, NOW - 3 * HOUR, NOW - 2 * HOUR)
        s = SchedulerReducer.reduce(s, SchedulerIntent.AddNoScreenPeriod(NOW - 4 * HOUR, NOW))

        assertTrue(s.tasks.getValue(screen).record.isEmpty(), "the app must not assume on-screen work happened")
        assertEquals(
            listOf(NOW - 3 * HOUR to NOW - 2 * HOUR),
            s.tasks.getValue(away).record.map { it.startEpochMillis to it.endEpochMillis },
            "§9 lets an off-screen task run in a no-screen period, so its record over one is true",
        )
        // The stripped span lays no period of its own: the past stays fully accounted for because the
        // calendar DERIVES a grey band over whatever no task panel covers, not because the app writes one.
        assertTrue(s.panels.none { it.inactivity }, "stripping must not lay a period: ${s.panels}")
    }

    @Test
    fun a_period_dragged_over_past_work_strips_it_on_the_new_span_too() = withClock {
        val (s0, screen, _) = twoTasks()
        var s = withRecord(s0, screen, NOW - 3 * HOUR, NOW - 2 * HOUR)
        // Laid where it covers nothing: the record survives.
        s = SchedulerReducer.reduce(s, SchedulerIntent.AddNoScreenPeriod(NOW - 8 * HOUR, NOW - 7 * HOUR))
        assertEquals(1, s.tasks.getValue(screen).record.size)
        // Dragged onto the banked hour: the rule is re-applied over the period's NEW span.
        val period = s.panels.single { it.noScreen }
        s = SchedulerReducer.reduce(
            s,
            SchedulerIntent.UpdateTaskPanel(
                id = period.id,
                taskId = null,
                title = period.title,
                startEpochMillis = NOW - 3 * HOUR,
                endEpochMillis = NOW - 2 * HOUR,
                pins = period.pins,
            ),
        )
        assertTrue(s.tasks.getValue(screen).record.isEmpty())
    }

    @Test
    fun a_period_that_covers_no_elapsed_work_changes_no_record() = withClock {
        val (s0, screen, _) = twoTasks()
        val s = withRecord(s0, screen, NOW - 3 * HOUR, NOW - 2 * HOUR)
        val after = SchedulerReducer.reduce(s, SchedulerIntent.AddInactivityPeriod(NOW + HOUR, NOW + 2 * HOUR))
        assertEquals(
            s.tasks.getValue(screen).record,
            after.tasks.getValue(screen).record,
            "a period laid in the future may not touch what was already recorded",
        )
    }
}
