package org.example.project

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.example.project.scheduler.state.HistoryCategory
import org.example.project.scheduler.state.HistoryUnit
import org.example.project.scheduler.state.HistoryWindow
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState

/**
 * User rule 2026-11-22: **one behaviour for every list several pins walk** — here the positions, walked by a window's
 * `Alt+←/→`, by `Shift+Alt+←/→` and by `Ctrl+Page Up/Down`:
 *  - a unit undone by a pin is passed over by every other pin;
 *  - a new unit drops the units undone by every pin whose list it joins, and no other pin's.
 */
class HistoryPinsTest {

    private var stamped = false

    @BeforeTest
    fun stampWindows() {
        stamped = SchedulerReducer.stampsWindow
        SchedulerReducer.stampsWindow = true
    }

    @AfterTest
    fun restore() {
        SchedulerReducer.stampsWindow = stamped
    }

    private fun r(state: SchedulerState, intent: SchedulerIntent) = SchedulerReducer.reduce(state, intent)

    private fun select(state: SchedulerState, key: String) = r(state, SchedulerIntent.SelectInWindow(HistoryWindow.Search, "", key))

    private fun selected(state: SchedulerState): String? =
        state.windowSelections.entries.singleOrNull { it.key.startsWith(HistoryWindow.Search.name) }?.value

    private fun selections(state: SchedulerState): List<HistoryUnit> = state.histories.forCategory(HistoryCategory.Selection).units

    /** In the Search window, row "a" then row "b" selected. */
    private fun twoSelections(): SchedulerState {
        var s = r(SchedulerState.empty(), SchedulerIntent.FocusWindow(HistoryWindow.Search, ""))
        s = select(s, "a")
        s = select(s, "b")
        assertEquals("b", selected(s))
        return s
    }

    @Test
    fun what_alt_left_undid_is_passed_over_by_shift_alt_right_and_redone_by_alt_right() {
        var s = r(twoSelections(), SchedulerIntent.UndoSelection)
        assertEquals("a", selected(s))
        assertEquals(s, r(s, SchedulerIntent.RedoPosition), "Shift+Alt+→ passes over what Alt+← undid")
        s = r(s, SchedulerIntent.RedoSelection)
        assertEquals("b", selected(s))
    }

    @Test
    fun what_shift_alt_left_undid_is_passed_over_by_alt_right() {
        var s = r(twoSelections(), SchedulerIntent.UndoPosition)
        assertEquals("a", selected(s))
        assertEquals(s, r(s, SchedulerIntent.RedoSelection), "Alt+→ passes over what Shift+Alt+← undid")
        s = r(s, SchedulerIntent.RedoPosition)
        assertEquals("b", selected(s))
    }

    @Test
    fun a_focus_move_drops_what_shift_alt_left_undid_but_not_what_alt_left_undid() {
        // Shift+Alt+← then a move of the focus: the move joins Shift+Alt's list, so its redo is gone.
        var s = r(twoSelections(), SchedulerIntent.UndoPosition)
        s = r(s, SchedulerIntent.FocusWindow(HistoryWindow.Calendar, ""))
        assertTrue(selections(s).none { it.undone }, "Shift+Alt+←'s redo is dropped")
        // Alt+← then the same move: the move is on no list of the Search window's Alt pin, so its redo stays.
        s = r(twoSelections(), SchedulerIntent.UndoSelection)
        s = r(s, SchedulerIntent.FocusWindow(HistoryWindow.Calendar, ""))
        assertTrue(selections(s).any { it.undone }, "Alt+←'s redo is kept")
        s = r(s, SchedulerIntent.FocusWindow(HistoryWindow.Search, ""))
        s = r(s, SchedulerIntent.RedoSelection)
        assertEquals("b", selected(s))
        // A new selection in the window is on its Alt pin's list: that redo goes.
        s = r(s, SchedulerIntent.UndoSelection)
        s = select(s, "c")
        assertTrue(selections(s).none { it.undone }, "a new selection drops Alt+←'s redo")
    }
}
