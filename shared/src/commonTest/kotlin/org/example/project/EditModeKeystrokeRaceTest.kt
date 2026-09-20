package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import org.example.project.scheduler.model.CellId
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState
import org.example.project.time.AppClock

/**
 * PRD §4: **the first letters typed onto a selected cell are never lost, however late the frame is.**
 *
 * A cell's Edit Mode is opened by the first printable key typed on it, and that key is carried in
 * [SchedulerIntent.BeginEdit.initialText]. The keys that follow are meant for the field the session puts on
 * screen — but the tree's key handler reads the state of the last **composition**, and Compose delivers key
 * events without recomposing between them. On a frame the app owes elsewhere (a fill, a big derivation, a
 * save) the second and third letters are therefore typed against a snapshot that still says "no session
 * open", and reach the reducer as further `BeginEdit`s of their own.
 *
 * Each of those used to START A FRESH SESSION, throwing away everything typed before it — so a burst of
 * typing on a busy app kept only its LAST letter, which is the "the first letter is missed" the user
 * reports. The rule that closes it is that the reducer, which is the only thing that sees the true current
 * state, absorbs such a keystroke into the session already running on that cell: it is
 * [SchedulerIntent.UpdateEditText] arriving by another route, and it is reduced as exactly that.
 *
 * (The UI half of the same race — the window in which the session exists but its field has not taken the
 * caret yet — is `TaskTreeView`'s `treeSelfFocused` branch, which turns the keystroke into this very
 * intent rather than dropping it.)
 */
class EditModeKeystrokeRaceTest {

    private val NOW = 1_000_000_000_000L

    private class FixedClock(val now: Long) : AppClock {
        override fun nowMillis(): Long = now
    }

    /** History Units are wall-clock stamped; pin the clock so two reductions can be compared whole. */
    private fun withClock(body: () -> Unit) {
        val previous = SchedulerReducer.clock
        SchedulerReducer.clock = FixedClock(NOW)
        try {
            body()
        } finally {
            SchedulerReducer.clock = previous
        }
    }

    private fun r(state: SchedulerState, intent: SchedulerIntent) = SchedulerReducer.reduce(state, intent)

    /** An empty account and its lone empty root cell. */
    private fun emptyCell(): Pair<SchedulerState, CellId> {
        val s = SchedulerState.empty()
        return s to s.lists[s.rootListId]!!.cellIds.last()
    }

    /** Types [text] the way a burst on a stalled frame does: every letter as its own `BeginEdit`. */
    private fun typeAllAsBeginEdit(state: SchedulerState, cell: CellId, text: String): SchedulerState =
        text.fold(state) { acc, ch -> r(acc, SchedulerIntent.BeginEdit(cell, initialText = ch.toString())) }

    @Test
    fun letters_typed_before_the_session_is_on_screen_are_kept_in_order() = withClock {
        val (s0, cell) = emptyCell()
        val s = typeAllAsBeginEdit(s0, cell, "Plan")

        val session = assertNotNull(s.editSession, "the burst opens exactly one session")
        assertEquals(cell, session.cellId)
        assertEquals("Plan", session.draftText, "no letter of the burst is dropped and none is reordered")
        // PRD §4: every keystroke commits the title, so the cell itself says the same thing.
        assertEquals("Plan", s.tasks[s.cells[cell]!!.taskId]!!.title)
    }

    @Test
    fun an_absorbed_keystroke_is_update_edit_text_by_another_route() = withClock {
        val (s0, cell) = emptyCell()
        val opened = r(s0, SchedulerIntent.BeginEdit(cell, initialText = "P"))

        // The whole state, histories included: a late keystroke must be indistinguishable from the same
        // letter reaching the field, or the two ways of typing would be two rules.
        assertEquals(
            r(opened, SchedulerIntent.UpdateEditText("Pl")),
            r(opened, SchedulerIntent.BeginEdit(cell, initialText = "l")),
        )
    }

    @Test
    fun a_whole_burst_is_the_same_state_as_the_same_letters_typed_into_the_field() = withClock {
        val (s0, cell) = emptyCell()
        val opened = r(s0, SchedulerIntent.BeginEdit(cell, initialText = "P"))

        // Everything a keystroke decides rides on this: the committed title, the id menu's
        // `selectedAssignTaskId`, the "New task" draft it keeps or drops, and the single coalesced Edit
        // unit the whole gesture is worth. Asserting the states whole is what says none of them may be
        // answered a second way by the late path.
        val burst = typeAllAsBeginEdit(opened, cell, "lan")
        val field = "lan".fold(opened) { acc, ch ->
            r(acc, SchedulerIntent.UpdateEditText(acc.editSession!!.draftText + ch))
        }

        assertEquals("Plan", field.editSession!!.draftText)
        assertEquals(field, burst)
    }

    @Test
    fun a_stale_re_entry_carrying_no_text_leaves_the_session_alone() = withClock {
        val (s0, cell) = emptyCell()
        val typed = typeAllAsBeginEdit(s0, cell, "Plan")

        // A second Enter / double-click delivered against the stale snapshot. Restarting the session here
        // would recapture `treeBefore` — the baseline Escape reverts to — over the half-typed title.
        assertSame(typed, r(typed, SchedulerIntent.BeginEdit(cell)))
    }

    @Test
    fun the_burst_still_reverts_to_what_the_cell_held_before_it() = withClock {
        // The session's `treeBefore` must be the state the FIRST letter opened it on, not the one the last
        // letter saw — otherwise Escape leaves part of the burst behind.
        var s = SchedulerState.empty()
        val cell = s.lists[s.rootListId]!!.cellIds.last()
        s = r(s, SchedulerIntent.SetCellTitle(cell, "Apple"))
        val before = s.tasks[s.cells[cell]!!.taskId]!!.title

        s = typeAllAsBeginEdit(s, cell, "Pear")
        assertEquals("Pear", s.editSession!!.draftText)

        s = r(s, SchedulerIntent.CancelEdit)
        assertEquals(null, s.editSession)
        assertEquals(before, s.tasks[s.cells[cell]!!.taskId]!!.title)
    }
}
