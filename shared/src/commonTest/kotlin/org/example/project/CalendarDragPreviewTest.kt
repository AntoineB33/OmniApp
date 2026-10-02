package org.example.project

import org.example.project.scheduler.domain.PeriodKinds
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.model.TaskTimeRange
import org.example.project.ui.PanelSlice
import org.example.project.ui.PlacedRecord
import org.example.project.ui.layoutWithBreakHoles
import org.example.project.ui.periodsForBlockDrag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * PRD §8 (user rule 2026-10-02): while a block is dragged the calendar is drawn the way the release would
 * leave it — and nothing of that is stored. These pin the two parts the reducer has no say in, because they
 * are DERIVED: the Inactivity bands (whatever nothing covers) and the hole a screen break cuts in a panel.
 */
class CalendarDragPreviewTest {
    private val HOUR = 3_600_000L
    private val task = TaskId("t/a")

    private fun range(start: Long, end: Long) = TaskTimeRange(start, end)

    private fun block(id: String, start: Long, end: Long) =
        PlacedRecord(
            title = id, startHour = start / HOUR.toFloat(), endHour = end / HOUR.toFloat(), scheduled = false,
            entryId = id, entryIds = listOf(id), taskId = task, fullStartMillis = start, fullEndMillis = end,
        )

    private fun inactivity(start: Long, end: Long) =
        PlacedRecord(
            title = "Inactivity", startHour = start / HOUR.toFloat(), endHour = end / HOUR.toFloat(),
            scheduled = false, inactivity = true, restrictiveKind = PeriodKinds.INACTIVITY,
            fullStartMillis = start, fullEndMillis = end,
        )

    @Test
    fun the_stretch_a_moved_block_leaves_is_idle_and_the_idle_stretch_it_enters_gives_way() {
        val bands = listOf(range(0, HOUR), range(3 * HOUR, 5 * HOUR))
        assertEquals(
            listOf(range(0, 4 * HOUR)),
            SchedulerDomain.inactivityBandsAfterMove(bands, range(HOUR, 3 * HOUR), range(4 * HOUR, 6 * HOUR), emptyList()),
        )
        // What another block still covers is not idle, though the dragged one has left it.
        assertEquals(
            listOf(range(0, 2 * HOUR), range(3 * HOUR, 4 * HOUR)),
            SchedulerDomain.inactivityBandsAfterMove(
                bands, range(HOUR, 3 * HOUR), range(4 * HOUR, 6 * HOUR), listOf(range(2 * HOUR, 3 * HOUR)),
            ),
        )
        // Asked from the bands at rest every time: carried back home, nothing has changed.
        assertEquals(
            bands,
            SchedulerDomain.inactivityBandsAfterMove(bands, range(HOUR, 3 * HOUR), range(HOUR, 3 * HOUR), emptyList()),
        )
    }

    @Test
    fun the_preview_redraws_the_derived_inactivity_bands_around_the_dragged_block() {
        val dragged = block("p", HOUR, 2 * HOUR)
        val live =
            periodsForBlockDrag(
                periods = listOf(inactivity(0, HOUR)),
                blocks = listOf(dragged),
                sleepBands = emptyList(),
                dragged = dragged,
                range = range(HOUR / 2, 3 * HOUR / 2),
                midnightMillis = 0L,
                refuses = { _, _ -> true },
            )
        assertEquals(
            listOf(range(0, HOUR / 2), range(3 * HOUR / 2, 2 * HOUR)),
            live.map { range(it.fullStartMillis, it.fullEndMillis) }.sortedBy { it.startEpochMillis },
        )
        assertEquals(listOf(0.5f, 2f), live.map { it.endHour }.sorted())
    }

    @Test
    fun a_break_cuts_a_hole_in_the_drawing_of_a_panel_it_refuses_and_only_there() {
        val panel = block("p", HOUR, 3 * HOUR)
        val lookAway =
            PlacedRecord(
                title = "Look away", startHour = 2f, endHour = 2.5f, scheduled = false, screenBreak = true,
                breakKind = PeriodKinds.INACTIVITY,
            )
        val rest = mapOf("p" to listOf(PanelSlice(1f, 3f, 0f, 1f)))
        val holed = layoutWithBreakHoles(rest, listOf(panel), listOf(lookAway)) { _, _ -> true }
        assertEquals(listOf(PanelSlice(1f, 2f, 0f, 1f), PanelSlice(2.5f, 3f, 0f, 1f)), holed["p"])
        // Dragged an hour later, the hole is where the break is — not where it was on the panel.
        val moved = panel.copy(startHour = 2f, endHour = 4f)
        val movedLayout = layoutWithBreakHoles(mapOf("p" to listOf(PanelSlice(2f, 4f, 0f, 1f))), listOf(moved), listOf(lookAway)) { _, _ -> true }
        assertEquals(listOf(PanelSlice(2.5f, 4f, 0f, 1f)), movedLayout["p"])
        // A task resilient to the break's kind is drawn straight through it.
        assertSame(rest, layoutWithBreakHoles(rest, listOf(panel), listOf(lookAway)) { _, _ -> false })
    }
}
