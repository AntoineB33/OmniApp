package org.example.project

import androidx.compose.ui.input.key.Key
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.example.project.ui.KeyboardShortcutCatalog
import org.example.project.ui.TabWalk
import org.example.project.ui.WindowFrameHost
import org.example.project.ui.WindowFrameState

/**
 * User rule 2026-10-15: *"Ctrl + tab and ctrl + shift + tab must do the same as in Chrome, and ctrl + PgUp must do the
 * same as Ctrl + Z but only on history units about change of focus between windows"* — [TabWalk]'s chords and
 * arithmetic, and the walk [WindowFrameHost] makes of the window bar's tabs. The focus moves' own walk is the
 * reducer's (`HistoryChordsByWindowTest`).
 */
class TabWalkTest {

    private fun host(vararg ids: String): WindowFrameHost {
        val host = WindowFrameHost()
        for (id in ids) host.register(WindowFrameHost.Registration(id, id, WindowFrameState(id), false, null) {})
        return host
    }

    private fun state(host: WindowFrameHost, id: String) = host.registrations.first { it.id == id }.state

    @Test
    fun the_four_chords() {
        fun move(key: Key, shift: Boolean = false, ctrl: Boolean = true, alt: Boolean = false, down: Boolean = true) =
            TabWalk.moveFor(key, down, ctrl, shift, alt)
        // Chrome's: the next tab to the right, Shift the one to the left.
        assertEquals(TabWalk.Move.BarNext, move(Key.Tab))
        assertEquals(TabWalk.Move.BarPrevious, move(Key.Tab, shift = true))
        // Ctrl + Z / Ctrl + Y of the moves of the focus between windows.
        assertEquals(TabWalk.Move.FocusUndo, move(Key.PageUp))
        assertEquals(TabWalk.Move.FocusRedo, move(Key.PageDown))
        assertNull(move(Key.PageUp, shift = true))
        // A plain Tab is the tree's and a field's; Alt makes it another chord; a key going up is no stroke.
        assertNull(move(Key.Tab, ctrl = false))
        assertNull(move(Key.Tab, alt = true))
        assertNull(move(Key.PageDown, ctrl = false))
        assertNull(move(Key.Tab, down = false))
        assertNull(move(Key.A))
        // The keyboard-shortcuts window lists the four, as they are answered.
        val listed = KeyboardShortcutCatalog.fixedGroups.single { it.title == "Window bar" }.shortcuts.map { it.keys }
        assertEquals(listOf(TabWalk.BAR_NEXT, TabWalk.BAR_PREVIOUS, TabWalk.FOCUS_UNDO, TabWalk.FOCUS_REDO), listed)
    }

    @Test
    fun the_bar_order_walks_the_tabs_as_they_stand_and_wraps() {
        val host = host("Calendar", "Search", "Alarms")
        host.focus("Search")
        assertTrue(host.walkTabsInBarOrder(+1))
        assertEquals("Alarms", host.focusedId)
        host.walkTabsInBarOrder(+1)
        assertEquals("Calendar", host.focusedId, "past the last tab, the first")
        host.walkTabsInBarOrder(-1)
        assertEquals("Alarms", host.focusedId)
        host.walkTabsInBarOrder(-1)
        assertEquals("Search", host.focusedId)
        // A reduced window comes back as its tab is reached.
        state(host, "Alarms").minimize()
        host.walkTabsInBarOrder(+1)
        assertEquals("Alarms", host.focusedId)
        assertFalse(state(host, "Alarms").minimized)
        // From no focused window: the first tab to the right, the last to the left. No tab: nothing.
        host.blur()
        host.walkTabsInBarOrder(+1)
        assertEquals("Calendar", host.focusedId)
        host.blur()
        host.walkTabsInBarOrder(-1)
        assertEquals("Alarms", host.focusedId)
        assertFalse(WindowFrameHost().walkTabsInBarOrder(+1))
        assertEquals(null, TabWalk.next(emptyList(), null, 1))
    }
}
