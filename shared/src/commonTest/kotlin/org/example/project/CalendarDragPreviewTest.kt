package org.example.project

import org.example.project.scheduler.domain.PeriodKinds
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.model.TaskTimeRange
import org.example.project.ui.PanelSlice
import org.example.project.ui.PlacedRecord
import org.example.project.scheduler.domain.PeriodKindConfig
import org.example.project.ui.derivedLayerPeriods
import org.example.project.ui.layerBandsAroundPlaced
import org.example.project.ui.layoutWithBreakHoles
import org.example.project.ui.placedPanelSpans
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

    private fun layer(which: SchedulerDomain.ActivityLayer, start: Long, end: Long) =
        PlacedRecord(
            title = which.calendarLabel, startHour = start / HOUR.toFloat(), endHour = end / HOUR.toFloat(),
            scheduled = false, layer = which, fullStartMillis = start, fullEndMillis = end,
        )

    private val computer = SchedulerDomain.ActivityLayer.NoComputerUnlocked
    private val phone = SchedulerDomain.ActivityLayer.entries.first { it != computer }
    /** An on-screen task: refused by "no screen", and by nothing else. */
    private val onScreen = { _: TaskId?, kind: String -> kind == PeriodKinds.NO_SCREEN }

    /** Anomaly 2026-10-02: both layers fall here, so the rules say "no screen" — and the bubble must name it. */
    @Test
    fun no_screen_is_derived_where_both_layers_fall_and_only_there() {
        val bands = listOf(layer(computer, 0, 4 * HOUR), layer(phone, 2 * HOUR, 6 * HOUR))
        val derived = derivedLayerPeriods(bands, emptyList(), PeriodKindConfig.DEFAULT, midnightMillis = 0L)
        assertEquals(listOf(PeriodKinds.NO_SCREEN), derived.map { it.restrictiveKind })
        assertEquals(listOf(range(2 * HOUR, 4 * HOUR)), derived.map { range(it.fullStartMillis, it.fullEndMillis) })
        // One layer alone derives nothing.
        assertEquals(emptyList(), derivedLayerPeriods(bands.take(1), emptyList(), PeriodKindConfig.DEFAULT, 0L))
    }

    /** Anomaly 2026-10-02: a task panel placed by hand that "no screen" refuses makes all three give way. */
    @Test
    fun the_layers_give_way_to_a_placed_panel_no_screen_refuses_and_grow_back_as_it_leaves() {
        val bands = listOf(layer(computer, 0, 4 * HOUR), layer(phone, 2 * HOUR, 6 * HOUR))
        val panel = block("p", 7 * HOUR, 8 * HOUR)
        fun spans(live: List<PlacedRecord>) =
            live.map { it.layer to range(it.fullStartMillis, it.fullEndMillis) }.sortedBy { it.second.startEpochMillis }
        // Dragged onto 2:30–3:30, under both: each band is cut there, so no "no screen" is left under the panel.
        val over = range(5 * HOUR / 2, 7 * HOUR / 2)
        val live = layerBandsAroundPlaced(bands, placedPanelSpans(listOf(panel), "p" to over), PeriodKindConfig.DEFAULT, onScreen, 0L)
        assertEquals(
            listOf(
                computer to range(0, 5 * HOUR / 2), phone to range(2 * HOUR, 5 * HOUR / 2),
                computer to range(7 * HOUR / 2, 4 * HOUR), phone to range(7 * HOUR / 2, 6 * HOUR),
            ),
            spans(live),
        )
        assertEquals(
            listOf(range(2 * HOUR, 5 * HOUR / 2), range(7 * HOUR / 2, 4 * HOUR)),
            derivedLayerPeriods(live, emptyList(), PeriodKindConfig.DEFAULT, 0L).map { range(it.fullStartMillis, it.fullEndMillis) },
        )
        // Under ONE layer nothing refuses it, and a task resilient to "no screen" cuts nothing either.
        assertSame(bands, layerBandsAroundPlaced(bands, placedPanelSpans(listOf(panel), "p" to range(0, HOUR)), PeriodKindConfig.DEFAULT, onScreen, 0L))
        assertSame(bands, layerBandsAroundPlaced(bands, placedPanelSpans(listOf(panel), "p" to over), PeriodKindConfig.DEFAULT, { _, _ -> false }, 0L))
        // At rest, only a panel the USER placed counts; the scheduler's own leaves the layers alone.
        val resting = block("q", 5 * HOUR / 2, 7 * HOUR / 2)
        assertSame(bands, layerBandsAroundPlaced(bands, placedPanelSpans(listOf(resting)), PeriodKindConfig.DEFAULT, onScreen, 0L))
        val placed = resting.copy(outline = SchedulerDomain.PanelOutline.User)
        assertEquals(spans(live), spans(layerBandsAroundPlaced(bands, placedPanelSpans(listOf(placed)), PeriodKindConfig.DEFAULT, onScreen, 0L)))
    }

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
