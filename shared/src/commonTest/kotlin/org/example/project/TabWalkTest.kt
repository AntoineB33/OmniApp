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
 * User rule 2026-10-11: *"Create a shortcut to easily navigate through the tabs in the system tray in the recent order
 * (one for each direction), and a shortcut for the order of the tabs in the system tray (one for each direction)."* —
 * [TabWalk]'s chords and arithmetic, and the two walks [WindowFrameHost] makes of them over the window bar's tabs.
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
        assertEquals(TabWalk.Move.RecentOlder, move(Key.Tab))
        assertEquals(TabWalk.Move.RecentNewer, move(Key.Tab, shift = true))
        assertEquals(TabWalk.Move.BarNext, move(Key.PageDown))
        assertEquals(TabWalk.Move.BarPrevious, move(Key.PageUp))
        // A plain Tab is the tree's and a field's; Alt makes it another chord; a key going up is no stroke.
        assertNull(move(Key.Tab, ctrl = false))
        assertNull(move(Key.Tab, alt = true))
        assertNull(move(Key.PageDown, ctrl = false))
        assertNull(move(Key.Tab, down = false))
        assertNull(move(Key.A))
        // The keyboard-shortcuts window lists the four, as they are answered.
        val listed = KeyboardShortcutCatalog.fixedGroups.single { it.title == "Window bar" }.shortcuts.map { it.keys }
        assertEquals(listOf(TabWalk.RECENT_BACK, TabWalk.RECENT_FORWARD, TabWalk.BAR_NEXT, TabWalk.BAR_PREVIOUS), listed)
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

    @Test
    fun the_recent_order_goes_back_through_the_windows_as_they_last_had_the_focus() {
        val host = host("Calendar", "Search", "Alarms", "Timers")
        // Focused in this order: Calendar, Alarms, Search — Search has the focus, Alarms had it before, then Calendar.
        host.focus("Calendar")
        host.focus("Alarms")
        host.focus("Search")
        // One press and Ctrl released: the window before. Again: back where it was — the task switcher's toggle.
        host.walkTabsByRecency(+1)
        assertEquals("Alarms", host.focusedId)
        host.endRecentWalk()
        host.walkTabsByRecency(+1)
        assertEquals("Search", host.focusedId)
        host.endRecentWalk()
        // Ctrl held: each press goes one further back through the order as it stood — then the windows that never
        // had the focus, in the bar's order — and round again.
        host.walkTabsByRecency(+1)
        assertEquals("Alarms", host.focusedId)
        host.walkTabsByRecency(+1)
        assertEquals("Calendar", host.focusedId)
        host.walkTabsByRecency(+1)
        assertEquals("Timers", host.focusedId, "never focused: after the ones that were")
        host.walkTabsByRecency(+1)
        assertEquals("Search", host.focusedId, "round to where the walk began")
        // The other way, within the same walk.
        host.walkTabsByRecency(-1)
        assertEquals("Timers", host.focusedId)
        host.walkTabsByRecency(-1)
        assertEquals("Calendar", host.focusedId)
        host.endRecentWalk()
        // Released on Calendar: it is the most recent now, and Search — where the walk began — the one before.
        host.walkTabsByRecency(+1)
        assertEquals("Search", host.focusedId)
        host.endRecentWalk()
    }

    @Test
    fun a_press_elsewhere_ends_the_walk_and_a_closed_window_leaves_the_order() {
        val host = host("Calendar", "Search", "Alarms")
        host.focus("Calendar")
        host.focus("Search")
        host.focus("Alarms")
        host.walkTabsByRecency(+1)
        assertEquals("Search", host.focusedId)
        // A press in a window, mid-walk: that window is the most recent, and the next walk starts from it.
        host.focus("Calendar")
        host.walkTabsByRecency(+1)
        assertEquals("Search", host.focusedId, "the window the walk had reached is where the focus last was before Calendar")
        host.endRecentWalk()
        // A window that closed is in no order.
        host.unregister("Calendar")
        host.walkTabsByRecency(+1)
        assertEquals("Alarms", host.focusedId)
        host.walkTabsByRecency(+1)
        assertEquals("Search", host.focusedId)
        host.endRecentWalk()
        // From no focused window: the most recent one first. A reduced window comes back.
        state(host, "Search").minimize()
        host.blur()
        host.walkTabsByRecency(+1)
        assertEquals("Search", host.focusedId)
        assertFalse(state(host, "Search").minimized)
        host.endRecentWalk()
        assertFalse(WindowFrameHost().walkTabsByRecency(+1))
        // The order itself: the focused one, the recent ones still open, then the rest in the bar's order.
        assertEquals(listOf("b", "c", "a", "d"), TabWalk.recentOrder(listOf("c", "gone", "b"), listOf("a", "b", "c", "d"), "b"))
        assertEquals(listOf("c", "b", "a"), TabWalk.recentOrder(listOf("c", "b"), listOf("a", "b", "c"), null))
    }
}
