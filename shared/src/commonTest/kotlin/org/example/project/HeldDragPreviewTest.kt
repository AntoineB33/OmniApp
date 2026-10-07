package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.datetime.TimeZone
import org.example.project.scheduler.domain.PeriodKinds
import org.example.project.scheduler.model.SleepSchedule
import org.example.project.scheduler.model.TaskPanel
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState
import org.example.project.ui.PlacedRecord

/**
 * The user's drag specification: *"When a block A is dragged … if C (a period or task) must disappear from the presence
 * of A or B, then it disappears but is remembered. When block A and C leave, C reappears. But if the mouse click is
 * released, then C won't reappear even if the user double clicks again A to make it leave."*
 *
 * While a block is held the calendar draws [calendarMovePreview] — the release's own moves ([calendarMoveOf]) run on
 * the stored state, nothing saved — so "remembered" is the stored state itself, asked again at every step of the drag.
 */
class HeldDragPreviewTest {
    private val HOUR = 3_600_000L
    // Ahead of the real clock: the plan's side of the line, where a period trims the panels it refuses.
    private val DAY = 4_000_000_000_000L / (24 * HOUR) * (24 * HOUR)
    private val tz = TimeZone.currentSystemDefault()

    private fun at(hour: Double) = DAY + (hour * HOUR).toLong()

    private fun drawn(panel: TaskPanel) =
        PlacedRecord(
            title = panel.title, startHour = 0f, endHour = 1f, scheduled = false, manual = true,
            entryId = panel.id, entryIds = listOf(panel.id), restrictiveKind = panel.restrictiveKind,
            fullStartMillis = panel.startEpochMillis, fullEndMillis = panel.endEpochMillis,
        )

    /**
     * The user, 2026-10-07: *"The only difference there must be between keeping the mouse click and having released it
     * is that whatever got removed when the dragged element got there is remembered if the mouse click is not released.
     * If period A is dragged to period B and period B doesn't have to retract, then it doesn't. If it must retract, it
     * retracts."* — held, the calendar is the released one, for a period over a period as for anything else.
     */
    @Test
    fun a_period_held_over_another_period_is_drawn_exactly_as_its_release_would_leave_them() {
        var s = SchedulerState.empty().copy(sleep = SleepSchedule(sleepDurationMinutes = 0), automaticSchedule = false)
        s = SchedulerReducer.reduce(s, SchedulerIntent.AddRestrictivePeriod(PeriodKinds.NO_SCREEN, at(14.0), at(15.0)))
        s = SchedulerReducer.reduce(s, SchedulerIntent.AddRestrictivePeriod(PeriodKinds.NO_SCREEN, at(10.0), at(11.0)))
        s = SchedulerReducer.reduce(s, SchedulerIntent.AddRestrictivePeriod(PeriodKinds.INACTIVITY, at(18.0), at(19.0)))
        val a = s.panels.single { it.restrictiveKind == PeriodKinds.NO_SCREEN && it.startEpochMillis == at(14.0) }
        fun spans(state: SchedulerState, kind: String) =
            state.panels.filter { it.restrictiveKind == kind }.map { it.startEpochMillis to it.endEpochMillis }.sortedBy { it.first }
        fun move(from: Double, to: Double) = calendarMoveOf(s, drawn(a), at(from), at(to), true, at(0.0), tz)
        fun held(from: Double, to: Double) = calendarMovePreview(s, null, listOfNotNull(move(from, to)), at(0.0)).first
        fun released(from: Double, to: Double) = SchedulerReducer.reduce(s, (move(from, to) as CalendarMove.Dispatch).intent)

        for ((from, to) in listOf(10.5 to 11.5, 18.5 to 19.5, 16.0 to 17.0)) {
            for (kind in listOf(PeriodKinds.NO_SCREEN, PeriodKinds.INACTIVITY)) {
                assertEquals(spans(released(from, to), kind), spans(held(from, to), kind), "held at $from is the release at $from ($kind)")
            }
        }
        // Onto a period of its own kind: the two are one — B "retracts" into the union. Onto another kind: B stays.
        assertEquals(listOf(at(10.0) to at(11.5)), spans(held(10.5, 11.5), PeriodKinds.NO_SCREEN))
        assertEquals(listOf(at(18.0) to at(19.0)), spans(held(18.5, 19.5), PeriodKinds.INACTIVITY))
        // And moved on, B is back as it was: the next step is asked of the stored state.
        assertEquals(listOf(at(10.0) to at(11.0), at(16.0) to at(17.0)), spans(held(16.0, 17.0), PeriodKinds.NO_SCREEN))
    }

    @Test
    fun what_a_held_period_makes_disappear_is_back_when_it_moves_on_and_gone_for_good_once_released() {
        var s = SchedulerState.empty()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(s.lists[s.rootListId]!!.cellIds[0], "Work"))
        s = s.copy(sleep = SleepSchedule(sleepDurationMinutes = 0), automaticSchedule = false)
        val work = s.tasks.values.single { it.title == "Work" }.id
        // C: the task's panel at 10–11. A: a "no screen" period at 14–15, which refuses that task.
        s = SchedulerReducer.reduce(s, SchedulerIntent.AddTaskPanel(work, "Work", at(10.0), at(11.0), org.example.project.scheduler.model.PanelPins(existence = true)))
        s = SchedulerReducer.reduce(s, SchedulerIntent.AddRestrictivePeriod(PeriodKinds.NO_SCREEN, at(14.0), at(15.0)))
        val period = s.panels.single { it.restrictiveKind == PeriodKinds.NO_SCREEN }
        fun cOf(state: SchedulerState) = state.panels.filter { it.taskId == work }.map { it.startEpochMillis to it.endEpochMillis }
        fun held(from: Double, to: Double) =
            calendarMovePreview(
                s, null, listOfNotNull(calendarMoveOf(s, drawn(period), at(from), at(to), true, at(0.0), tz)), at(0.0),
            ).first

        // Held over C: C disappears where the period refuses it — and the stored state is untouched.
        val over = held(10.0, 11.0)
        assertTrue(cOf(over).isEmpty(), "C gives way while A is held over it: ${cOf(over)}")
        assertEquals(listOf(at(10.0) to at(11.0)), cOf(s), "nothing is saved while the press is held")
        assertEquals(s.automaticSchedule, over.automaticSchedule)
        // A moves on: C is back, because the next step is asked of the stored state again.
        assertEquals(listOf(at(10.0) to at(11.0)), cOf(held(16.0, 17.0)))
        // Half over it: C is cut, not gone.
        assertEquals(listOf(at(10.0) to at(10.5)), cOf(held(10.5, 11.5)))

        // Released over C, then dragged away again: C does not come back.
        val released = SchedulerReducer.reduce(s, (calendarMoveOf(s, drawn(period), at(10.0), at(11.0), true, at(0.0), tz) as CalendarMove.Dispatch).intent)
        assertTrue(cOf(released).isEmpty())
        val moved = released.panels.single { it.restrictiveKind == PeriodKinds.NO_SCREEN }
        val away = SchedulerReducer.reduce(released, (calendarMoveOf(released, drawn(moved), at(16.0), at(17.0), true, at(0.0), tz) as CalendarMove.Dispatch).intent)
        assertTrue(cOf(away).isEmpty(), "released, C won't reappear even if A is made to leave")
    }
}
