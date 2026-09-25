package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.example.project.scheduler.model.CellId
import org.example.project.scheduler.state.CellEditMode
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState
import org.example.project.scheduler.ui.cellEditModeOptions

/**
 * PRD §4: a pick in a task cell's **Mode** selector puts the caret back in the edit field, whichever mode is
 * picked — the one already chosen included — so the user can type straight away.
 *
 * The focus itself is Compose's; what is pinned here is the wiring that decides it: every option ends in the
 * refocus call, and that call cannot be left to the state, because re-picking the current mode is a reducer
 * no-op (the same state object comes back, so nothing would re-run).
 */
class EditModeRefocusTest {

    private fun r(state: SchedulerState, intent: SchedulerIntent) = SchedulerReducer.reduce(state, intent)

    /** A root cell titled "Apple", in Edit Mode. */
    private fun editing(): Pair<SchedulerState, CellId> {
        var s = SchedulerState.empty()
        val cell = s.lists[s.rootListId]!!.cellIds.last()
        s = r(s, SchedulerIntent.SetCellTitle(cell, "Apple"))
        s = r(s, SchedulerIntent.BeginEdit(cell))
        return s to cell
    }

    @Test
    fun every_mode_pick_refocuses_the_field_including_the_current_mode() {
        var (s, cell) = editing()
        var refocuses = 0
        val onIntent: (SchedulerIntent) -> Unit = { s = r(s, it) }

        fun pick(label: String) =
            cellEditModeOptions(s, cell, onIntent = onIntent) { refocuses++ }
                .single { it.label == label }
                .onSelect()

        assertEquals(CellEditMode.ChangeTask, s.editSession!!.mode)
        pick("Change Task") // already the mode
        assertEquals(1, refocuses)
        pick("Rename")
        assertEquals(CellEditMode.Rename, s.editSession!!.mode)
        assertEquals(2, refocuses)
        pick("Rename") // already the mode
        assertEquals(3, refocuses)
        pick("Change Task")
        assertEquals(CellEditMode.ChangeTask, s.editSession!!.mode)
        assertEquals(4, refocuses)
    }

    @Test
    fun re_picking_the_current_mode_leaves_the_state_untouched() {
        val (s, _) = editing()
        // Why the refocus is its own call: there is no state change for the field to react to.
        assertSame(s, r(s, SchedulerIntent.SetEditMode(CellEditMode.ChangeTask)))
    }

    @Test
    fun a_mode_switch_keeps_the_draft_the_caret_returns_to() {
        var (s, cell) = editing()
        s = r(s, SchedulerIntent.UpdateEditText("Apple pie"))
        cellEditModeOptions(s, cell, { s = r(s, it) }) {}.single { it.label == "Rename" }.onSelect()
        assertEquals("Apple pie", s.editSession!!.draftText)
        cellEditModeOptions(s, cell, { s = r(s, it) }) {}.single { it.label == "Change Task" }.onSelect()
        assertEquals("Apple pie", s.editSession!!.draftText)
    }

    @Test
    fun no_selector_where_there_is_no_choice() {
        var fresh = SchedulerState.empty()
        val empty = fresh.lists[fresh.rootListId]!!.cellIds.last()
        fresh = r(fresh, SchedulerIntent.BeginEdit(empty))
        assertTrue(cellEditModeOptions(fresh, empty, onIntent = {}) {}.isEmpty())
    }
}
