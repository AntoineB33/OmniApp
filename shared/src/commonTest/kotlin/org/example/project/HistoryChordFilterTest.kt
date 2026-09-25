package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.example.project.scheduler.state.HistoryWindow
import org.example.project.scheduler.state.HistoryCategory
import org.example.project.scheduler.state.HistoryChord
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState
import org.example.project.scheduler.state.chords
import org.example.project.ui.FilteredHistoryEntry
import org.example.project.ui.HistoryFilterConfig
import org.example.project.ui.filteredHistoryUnits

/**
 * PRD §5/§6: the History window's **undo chord** field — show only the units `Ctrl+Z` walks, only the ones
 * `Alt+←`/`Alt+→` walk, or both.
 *
 * The field is only honest if the mapping it filters on is the same one the keyboard obeys, so these pin
 * both halves: what [chord] says of each category, and what the two chords actually move.
 */
class HistoryChordFilterTest {
    /** A state carrying one unit in each of the three categories the field has to tell apart. */
    private fun stateWithOneUnitPerChord(): SchedulerState {
        var s = SchedulerState.empty()
        val cellId = s.lists[s.rootListId]!!.cellIds.first()
        // Main — walked by Ctrl+Z.
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(cellId, "Deep work"))
        // Selection — walked by Alt+arrows.
        s = SchedulerReducer.reduce(
            s,
            SchedulerIntent.ClickCell(cellId, ctrl = false, shift = false, visibleOrder = listOf(cellId)),
        )
        // WindowNav — walked by Shift+Alt+arrows alone. The focus goes back to the tree, where the two units
        // above were made, because Ctrl+Z and Alt+arrows are relative to the focused window.
        s = SchedulerReducer.reduce(s, SchedulerIntent.FocusWindow(HistoryWindow.Reminders))
        s = SchedulerReducer.reduce(s, SchedulerIntent.FocusWindow(HistoryWindow.Tree))
        return s
    }

    @Test
    fun every_category_names_the_chord_that_walks_it() {
        assertEquals(setOf(HistoryChord.Undo), HistoryCategory.Edit.chords)
        assertEquals(setOf(HistoryChord.Undo), HistoryCategory.Calendar.chords)
        assertEquals(setOf(HistoryChord.Undo), HistoryCategory.Main.chords)
        assertEquals(setOf(HistoryChord.Selection, HistoryChord.Position), HistoryCategory.Selection.chords)
        assertEquals(setOf(HistoryChord.Position), HistoryCategory.WindowNav.chords)
    }

    @Test
    fun the_mapping_is_what_the_two_chords_actually_do() {
        // The half that would make the filter a lie if it drifted: Ctrl+Z must move a Main unit and leave the
        // Selection stack where it is, and Alt+arrows the reverse. Each stack's pointer walks only its own.
        val s = stateWithOneUnitPerChord()
        val mainPointer = s.histories.forCategory(HistoryCategory.Main).pointer
        val selectionPointer = s.histories.forCategory(HistoryCategory.Selection).pointer

        val undone = SchedulerReducer.reduce(s, SchedulerIntent.Undo)
        assertEquals(mainPointer - 1, undone.histories.forCategory(HistoryCategory.Main).pointer)
        assertEquals(selectionPointer, undone.histories.forCategory(HistoryCategory.Selection).pointer)

        val selectionUndone = SchedulerReducer.reduce(s, SchedulerIntent.UndoSelection)
        assertEquals(mainPointer, selectionUndone.histories.forCategory(HistoryCategory.Main).pointer)
        assertEquals(
            selectionPointer - 1,
            selectionUndone.histories.forCategory(HistoryCategory.Selection).pointer,
        )

        // Shift+Alt+← takes the newest position, which is the last move of the focus — never a change.
        val navPointer = s.histories.forCategory(HistoryCategory.WindowNav).pointer
        val positionUndone = SchedulerReducer.reduce(s, SchedulerIntent.UndoPosition)
        assertEquals(mainPointer, positionUndone.histories.forCategory(HistoryCategory.Main).pointer)
        assertEquals(selectionPointer, positionUndone.histories.forCategory(HistoryCategory.Selection).pointer)
        assertEquals(navPointer - 1, positionUndone.histories.forCategory(HistoryCategory.WindowNav).pointer)
    }

    private fun categoriesUnder(chords: Set<HistoryChord>?): List<HistoryCategory> =
        filteredHistoryUnits(stateWithOneUnitPerChord().histories, HistoryFilterConfig(chords = chords))
            .filterIsInstance<FilteredHistoryEntry.Unit>()
            .map { it.category }
            .distinct()
            .sorted()

    @Test
    fun the_field_isolates_each_chord() {
        assertEquals(listOf(HistoryCategory.Main), categoriesUnder(setOf(HistoryChord.Undo)))
        assertEquals(listOf(HistoryCategory.Selection), categoriesUnder(setOf(HistoryChord.Selection)))
        assertEquals(
            listOf(HistoryCategory.Selection, HistoryCategory.WindowNav),
            categoriesUnder(setOf(HistoryChord.Position)),
        )
    }

    @Test
    fun both_means_everything_a_chord_walks_and_any_means_everything() {
        // "Ctrl+Z or Alt+arrows" is the union of those two chords, NOT "no restriction": a move of the focus is
        // walked by neither, so it must not be smuggled in under a label that names two chords.
        assertEquals(
            listOf(HistoryCategory.Selection, HistoryCategory.Main),
            categoriesUnder(setOf(HistoryChord.Undo, HistoryChord.Selection)),
        )
        // "Any" is the field's default and admits it, so nothing is ever unreachable.
        assertEquals(
            listOf(HistoryCategory.Selection, HistoryCategory.Main, HistoryCategory.WindowNav),
            categoriesUnder(null),
        )
        assertEquals(categoriesUnder(null), categoriesUnder(HistoryFilterConfig().chords))
    }

    @Test
    fun the_chord_field_narrows_the_same_half_the_window_field_does() {
        // PRD §6: it is a further restriction on the History Units, not a third origin — so it composes with
        // the window field, and the check box takes it out of play along with it.
        val s = stateWithOneUnitPerChord()
        val bySource = filteredHistoryUnits(
            s.histories,
            HistoryFilterConfig(filterBySource = true, chords = setOf(HistoryChord.Undo)),
        )
        assertTrue(bySource.isEmpty(), "the source half lists no History Unit, whatever the chord field says")
    }
}
