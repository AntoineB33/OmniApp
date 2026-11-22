package org.example.project

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.example.project.scheduler.persistence.SchedulerStateCodec
import org.example.project.scheduler.state.ExternalDelta
import org.example.project.scheduler.state.ExternalKeys
import org.example.project.scheduler.state.HistoryCategory
import org.example.project.scheduler.state.HistoryWindow
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState
import org.example.project.ui.PopupAddress
import org.example.project.ui.PopupFocus
import org.example.project.ui.TreeKeeper

/**
 * User rule 2026-11-22 (*"fold them in"*): the keyboard going to a pop-up of a ▾ tree is a History Unit, a position
 * walked by `Shift+Alt+←/→` like a move of the focus — and the user's walk: *"History unit (window A) -> down arrow
 * (window Aa) -> history unit (window Ab), then I click on another tab (window B) … then shift + alt + <-, and it should
 * bring focus to window A … If I do shift + alt + <- again, it should bring focus to window Ab (with window Aa and A open
 * of course)."*
 */
class PopupFocusTest {

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

    private val arrow = "Search▾"
    private val aa = listOf(emptyList<String>())
    private val ab = listOf(listOf("kind/HistoryUnit"))

    /** The app as far as pop-ups go: the state, its [PopupFocus], and the outermost arrow's keeper. */
    private inner class App {
        var s: SchedulerState = SchedulerReducer.reduce(SchedulerState.empty(), SchedulerIntent.FocusWindow(HistoryWindow.Search, ""))
        var closed = 0
        private var restored = s.externalRestores.maxOfOrNull { it.seq } ?: 0L
        val keeper = TreeKeeper().also { it.arrowId = arrow; it.closeAll = { closed++ } }
        val focus =
            PopupFocus({ SchedulerReducer.popupHolder(s) }) { before, after, label ->
                s = SchedulerReducer.reduce(s, SchedulerIntent.RecordExternal(ExternalKeys.POPUP, before, after, label))
            }.also { it.keepers[arrow] = keeper }

        /** What a tree's pop-up does as the keyboard comes to it. */
        fun keyboardTo(address: List<List<String>>) = keeper.focused(address) { focus.focused(keeper.arrowId, address) }

        /** A chord, and the restores it queued applied, as `App` applies them. */
        fun walk(intent: SchedulerIntent) {
            s = SchedulerReducer.reduce(s, intent)
            for (r in s.externalRestores.filter { it.seq > restored }) {
                restored = r.seq
                if (r.key == ExternalKeys.POPUP) focus.restore(r.from, r.to)
            }
        }

        fun popupUnits() = s.histories.forCategory(HistoryCategory.WindowNav).units.filter { (it.delta as? ExternalDelta)?.key == ExternalKeys.POPUP }
    }

    @Test
    fun the_user_s_walk_lands_on_the_window_then_on_the_pop_up() {
        val app = App()
        app.keyboardTo(aa)
        app.keyboardTo(ab)
        // A click on another tab: the pop-up gives the keyboard back to its window first, then the focus moves.
        app.s = SchedulerReducer.reduce(app.s, SchedulerIntent.FocusWindow(HistoryWindow.Calendar, ""))
        assertEquals(listOf(null to PopupAddress.encode(arrow, aa), PopupAddress.encode(arrow, aa) to PopupAddress.encode(arrow, ab), PopupAddress.encode(arrow, ab) to null),
            app.popupUnits().map { (it.delta as ExternalDelta).let { d -> d.before to d.after } })
        // The platform telling late that the pop-up lost the keyboard adds nothing: the history says it has.
        app.focus.left()
        assertEquals(3, app.popupUnits().size)

        // Shift+Alt+←: window A, no pop-up.
        app.walk(SchedulerIntent.UndoPosition)
        assertEquals(HistoryWindow.Search, app.s.focusedWindow)
        assertNull(app.keeper.wanted)
        // Shift+Alt+← again: Ab, its trees opening down to it.
        app.walk(SchedulerIntent.UndoPosition)
        assertEquals(arrow to ab, app.keeper.wanted?.let { arrow to it })
        // Its coming there is no new unit, nor is Aa's opening on the way.
        app.keyboardTo(aa)
        app.keyboardTo(ab)
        assertEquals(3, app.popupUnits().size)
        assertTrue(app.popupUnits().last().undone)
        // Back: Aa, then the window (the tree closes).
        app.walk(SchedulerIntent.UndoPosition)
        assertEquals(aa, app.keeper.wanted)
        app.keyboardTo(aa)
        app.walk(SchedulerIntent.UndoPosition)
        assertEquals(1, app.closed)
        // Forward again, as far as the other tab.
        app.walk(SchedulerIntent.RedoPosition)
        assertEquals(aa, app.keeper.wanted)
        app.keyboardTo(aa)
        app.walk(SchedulerIntent.RedoPosition)
        assertEquals(ab, app.keeper.wanted)
        app.keyboardTo(ab)
        app.walk(SchedulerIntent.RedoPosition)
        assertEquals(2, app.closed)
        app.walk(SchedulerIntent.RedoPosition)
        assertEquals(HistoryWindow.Calendar, app.s.focusedWindow)
    }

    @Test
    fun ctrl_page_up_walks_the_windows_alone_and_a_pop_up_unit_stays_on_the_device() {
        val app = App()
        app.s = SchedulerReducer.reduce(app.s, SchedulerIntent.FocusWindow(HistoryWindow.Calendar, ""))
        app.keyboardTo(aa)
        app.walk(SchedulerIntent.UndoFocus)
        assertEquals(HistoryWindow.Search, app.s.focusedWindow, "Ctrl+Page Up passes over the pop-up, to the window before")
        assertTrue(app.popupUnits().none { it.undone })
        val unit = app.popupUnits().single()
        assertTrue(unit.delta.localOnly && !unit.delta.synced, "this device's view alone")
        // Kept by the store, so a walk after a restart still finds it.
        val back = assertNotNull(SchedulerStateCodec.decodeSnapshot(SchedulerStateCodec.encodeSnapshot(app.s)))
        assertEquals(PopupAddress.encode(arrow, aa), SchedulerReducer.popupHolder(back))
    }

    @Test
    fun an_address_survives_its_text() {
        val address = listOf(emptyList(), listOf("kind/HistoryUnit", "sort"), emptyList<String>())
        assertEquals("Search#3▾" to address, PopupAddress.decode(PopupAddress.encode("Search#3▾", address)))
        assertNull(PopupAddress.decode("no arrow here"))
    }
}
