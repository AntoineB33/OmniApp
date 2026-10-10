package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.PeriodKinds
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.PanelPins
import org.example.project.scheduler.model.SleepSchedule
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.model.TaskPanel
import org.example.project.scheduler.model.TaskTimeRange
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState

/**
 * User rule 2026-10-07: *"If the user drags a period A elsewhere, what appears at its original place can't be period A.
 * To choose what is placed instead, the program looks at what is at the edges … If at both edges, it is … task A, then
 * it is what is chosen. If it is different at the start and at the end, then the simplest solution is the chosen one,
 * which is only 'inactivity'."* ([SchedulerDomain.vacatedPastFill]) — behind the line only: ahead of it the plan fills.
 */
class VacatedPeriodFillTest {
    private val HOUR = 3_600_000L
    // Well behind the real clock, which is the reducer's line.
    private val DAY = 1_700_000_000_000L / (24 * HOUR) * (24 * HOUR)

    private fun at(hour: Double) = DAY + (hour * HOUR).toLong()
    private fun span(from: Double, to: Double) = TaskTimeRange(at(from), at(to))

    private fun oneTask(): Pair<SchedulerState, TaskId> {
        var s = SchedulerState.empty()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(s.lists[s.rootListId]!!.cellIds[0], "Work"))
        s = s.copy(sleep = SleepSchedule(sleepDurationMinutes = 0), automaticSchedule = false)
        return s to s.tasks.values.single { it.title == "Work" }.id
    }

    private fun withRecord(s: SchedulerState, id: TaskId, vararg ranges: TaskTimeRange) =
        s.copy(tasks = s.tasks + (id to s.tasks.getValue(id).copy(record = ranges.toList())))

    private fun withPeriod(s: SchedulerState, kind: String, from: Double, to: Double): Pair<SchedulerState, TaskPanel> {
        val next = SchedulerReducer.reduce(s, SchedulerIntent.AddRestrictivePeriod(kind, at(from), at(to)))
        return next to next.panels.single { it.restrictiveKind == kind && it.startEpochMillis == at(from) }
    }

    private fun move(s: SchedulerState, panel: TaskPanel, from: Double, to: Double) =
        SchedulerReducer.reduce(
            s,
            SchedulerIntent.UpdateTaskPanel(panel.id, null, panel.title, at(from), at(to), PanelPins(existence = true)),
        )

    private fun inactivity(s: SchedulerState) = s.panels.filter { it.restrictiveKind == PeriodKinds.INACTIVITY }

    @Test
    fun the_task_working_on_both_sides_gets_the_span_back_in_the_same_unit() {
        val (s0, work) = oneTask()
        // The period was laid over the work: 10–11 is the hole it cut.
        val (s1, period) = withPeriod(withRecord(s0, work, span(8.0, 10.0), span(11.0, 12.0)), PeriodKinds.NO_SCREEN, 10.0, 11.0)
        val s2 = move(s1, period, 14.0, 15.0)

        assertEquals(
            listOf(span(8.0, 10.0), span(10.0, 11.0), span(11.0, 12.0)),
            s2.tasks.getValue(work).record.sortedBy { it.startEpochMillis },
        )
        assertTrue(inactivity(s2).isEmpty())
        assertTrue(s2.panels.none { it.restrictiveKind == PeriodKinds.NO_SCREEN && it.startEpochMillis == at(10.0) }, "never the period again")

        val undone = SchedulerReducer.reduce(SchedulerReducer.reduce(s2, SchedulerIntent.SetCalendarFocus(true)), SchedulerIntent.Undo)
        assertEquals(listOf(span(8.0, 10.0), span(11.0, 12.0)), undone.tasks.getValue(work).record.sortedBy { it.startEpochMillis })
        assertTrue(undone.panels.any { it.restrictiveKind == PeriodKinds.NO_SCREEN && it.startEpochMillis == at(10.0) }, "one undo puts both back")
    }

    @Test
    fun different_edges_leave_only_inactivity() {
        val (s0, work) = oneTask()
        val (s1, period) = withPeriod(withRecord(s0, work, span(8.0, 10.0)), PeriodKinds.NO_SCREEN, 10.0, 11.0)
        val s2 = move(s1, period, 14.0, 15.0)
        assertEquals(listOf(at(10.0) to at(11.0)), inactivity(s2).map { it.startEpochMillis to it.endEpochMillis })
        assertEquals(listOf(span(8.0, 10.0)), s2.tasks.getValue(work).record)
        // Anomaly 2026-10-10 (*"an 'Inactivity' blue outlined block filling the vacated time interval"*): what the
        // hole rule lays is the bottom level's, not a block a hand placed — no outline, until a hand edits it.
        val fill = inactivity(s2).single()
        assertEquals(SchedulerDomain.PanelOutline.None, SchedulerDomain.panelOutline(fill))
        assertEquals(SchedulerDomain.PanelOutline.User, SchedulerDomain.panelOutline(s2.panels.single { it.restrictiveKind == PeriodKinds.NO_SCREEN }))
        val kept = org.example.project.scheduler.persistence.SchedulerStateCodec.decode(org.example.project.scheduler.persistence.SchedulerStateCodec.encode(s2))!!
        assertEquals(SchedulerDomain.PanelOutline.None, SchedulerDomain.panelOutline(kept.panels.single { it.restrictiveKind == PeriodKinds.INACTIVITY }))
        val edited = move(s2, fill, 10.0, 10.5)
        assertEquals(SchedulerDomain.PanelOutline.User, SchedulerDomain.panelOutline(inactivity(edited).single()))
    }

    @Test
    fun the_hole_a_dragged_break_leaves_between_different_edges_is_filled_with_no_outline() {
        val (s0, work) = oneTask()
        val s1 = withRecord(s0, work, span(8.0, 10.0))
        val s2 = SchedulerReducer.reduce(s1, SchedulerIntent.FillVacatedBreak(PeriodKinds.BREAK_15MIN, at(10.0), at(10.25)))
        val fill = inactivity(s2).single()
        assertEquals(at(10.0) to at(10.25), fill.startEpochMillis to fill.endEpochMillis)
        assertEquals(SchedulerDomain.PanelOutline.None, SchedulerDomain.panelOutline(fill))
    }

    @Test
    fun the_period_at_its_new_place_is_what_is_at_that_edge() {
        val (s0, work) = oneTask()
        val (s1, period) = withPeriod(withRecord(s0, work, span(8.0, 10.0)), PeriodKinds.NO_SCREEN, 10.0, 12.0)
        // Dragged by an hour: 10–11 is left, with the task before it and the period itself after it.
        val s2 = move(s1, period, 11.0, 13.0)
        assertEquals(listOf(at(10.0) to at(11.0)), inactivity(s2).map { it.startEpochMillis to it.endEpochMillis })
    }

    @Test
    fun bare_edges_a_resize_and_a_span_ahead_of_the_line_put_nothing() {
        val (s0, work) = oneTask()
        val (bare, period) = withPeriod(s0, PeriodKinds.NO_SCREEN, 10.0, 11.0)
        assertTrue(inactivity(move(bare, period, 14.0, 15.0)).isEmpty(), "nothing on either side: nothing chosen")

        val (s1, wide) = withPeriod(withRecord(s0, work, span(8.0, 10.0)), PeriodKinds.NO_SCREEN, 10.0, 12.0)
        assertTrue(inactivity(move(s1, wide, 10.0, 11.0)).isEmpty(), "a resize is not a drag elsewhere")

        val ahead = TaskTimeRange(4_000_000_000_000L, 4_000_000_000_000L + HOUR)
        assertNull(SchedulerDomain.vacatedPastFill(withRecord(s0, work, span(8.0, 10.0)), ahead, PeriodKinds.NO_SCREEN, at(20.0)))
    }

    /**
     * The user's own example: *"If at both edges, it is 'no phone unlocked' and not ('no computer unlocked' or 'not on a
     * computer') and task A, then it is what is chosen."*
     */
    @Test
    fun the_layers_count_at_the_edges() {
        val (s0, work) = oneTask()
        val s1 = withRecord(s0, work, span(8.0, 10.0), span(11.0, 12.0))
        val phone = PeriodKinds.layerKind(SchedulerDomain.ActivityLayer.NoPhoneUnlocked)
        val computer = PeriodKinds.layerKind(SchedulerDomain.ActivityLayer.NoComputerUnlocked)
        val gap = span(10.0, 11.0)
        // The phone was locked all morning but over the hour the period stood on; no computer layer anywhere.
        fun phoneRoundTheGap(t: Long) = if (t < gap.startEpochMillis || t >= gap.endEpochMillis) setOf(phone) else emptySet()
        val chosen = SchedulerDomain.vacatedPastFill(s1, gap, PeriodKinds.NO_SCREEN, at(20.0), layerKindsAt = ::phoneRoundTheGap)
        assertEquals(listOf(phone to gap), chosen?.periods, "'no phone unlocked', and nothing about the computer")
        assertEquals(work to listOf(gap), chosen?.taskId to chosen?.work, "and task A")

        // Already drawn across the span by the devices' own history: nothing to lay but the work.
        val drawn = SchedulerDomain.vacatedPastFill(s1, gap, PeriodKinds.NO_SCREEN, at(20.0)) { setOf(phone) }
        assertEquals(emptyList(), drawn?.periods)
        assertEquals(listOf(gap), drawn?.work)

        // Both layers at both edges would be the "no screen" that left: only inactivity.
        val again = SchedulerDomain.vacatedPastFill(s1, gap, PeriodKinds.NO_SCREEN, at(20.0)) { setOf(phone, computer) }
        assertEquals(listOf(PeriodKinds.INACTIVITY to gap), again?.periods)

        // A layer on one side only: different edges.
        val oneSide = SchedulerDomain.vacatedPastFill(s1, gap, PeriodKinds.NO_SCREEN, at(20.0)) { t -> if (t < gap.startEpochMillis) setOf(phone) else emptySet() }
        assertEquals(listOf(PeriodKinds.INACTIVITY to gap), oneSide?.periods)
    }

    @Test
    fun a_break_dragged_out_of_the_past_gives_its_hole_back_to_the_task_round_it() {
        val (s0, work) = oneTask()
        val s1 = withRecord(s0, work, span(8.0, 10.0), span(10.25, 12.0))
        val s2 = SchedulerReducer.reduce(s1, SchedulerIntent.FillVacatedBreak(PeriodKinds.BREAK_15MIN, at(10.0), at(10.25)))
        assertEquals(
            listOf(span(8.0, 10.0), span(10.0, 10.25), span(10.25, 12.0)),
            s2.tasks.getValue(work).record.sortedBy { it.startEpochMillis },
        )
    }
}
