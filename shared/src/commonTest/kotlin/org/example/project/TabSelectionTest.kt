package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import org.example.project.ui.ClickSelection
import org.example.project.ui.WindowFrameHost
import org.example.project.ui.WindowFrameState

/**
 * User rule 2026-10-01: *"The user must be able to click on a tab in the system tray, then shift+click on another,
 * which selects all the tabs in between. The user can also do ctrl+click on the tabs."* And: *"Right-clicking on the
 * system tray opens a menu with the options close selection, minimize selection, open selection. When a window has the
 * focus, its tab is selected."*
 */
class TabSelectionTest {
    private val order = listOf("a", "b", "c", "d", "e")

    private fun click(
        selected: Set<String>,
        anchor: String?,
        clicked: String,
        shift: Boolean = false,
        ctrl: Boolean = false,
    ) = ClickSelection.click(order, selected, anchor, clicked, shift, ctrl)

    @Test
    fun a_click_then_a_shift_click_selects_every_tab_between_both_included_in_either_direction() {
        val first = click(emptySet(), null, "b")
        assertEquals(setOf("b"), first.selected)
        assertEquals(setOf("b", "c", "d"), click(first.selected, first.anchor, "d", shift = true).selected)
        assertEquals(setOf("a", "b"), click(first.selected, first.anchor, "a", shift = true).selected)
    }

    @Test
    fun a_second_shift_click_redraws_the_range_from_the_same_anchor() {
        val range = click(setOf("b"), "b", "e", shift = true)
        assertEquals("b", range.anchor)
        assertEquals(setOf("b", "c"), click(range.selected, range.anchor, "c", shift = true).selected)
    }

    @Test
    fun ctrl_click_adds_or_takes_one_tab_and_moves_the_anchor() {
        val added = click(setOf("a"), "a", "d", ctrl = true)
        assertEquals(setOf("a", "d"), added.selected)
        assertEquals("d", added.anchor)
        assertEquals(setOf("d"), click(added.selected, added.anchor, "a", ctrl = true).selected)
    }

    @Test
    fun ctrl_shift_click_adds_the_range_to_what_is_selected() {
        assertEquals(setOf("a", "c", "d", "e"), click(setOf("a"), "c", "e", shift = true, ctrl = true).selected)
    }

    @Test
    fun a_plain_click_selects_its_tab_alone_and_a_shift_click_without_an_anchor_selects_one() {
        assertEquals(setOf("c"), click(setOf("a", "b"), "a", "c").selected)
        assertEquals(setOf("c"), click(emptySet(), null, "c", shift = true).selected)
    }

    @Test
    fun only_a_plain_click_toggles_a_window_and_a_closed_window_leaves_the_selection() {
        val host = WindowFrameHost()
        val states = order.associateWith { WindowFrameState(it) }
        for (id in order) host.register(WindowFrameHost.Registration(id, id, states.getValue(id), false, null) {})
        host.onTabPressed("b", shift = false, ctrl = false)
        assertEquals("b", host.focusedId)
        host.onTabPressed("d", shift = true, ctrl = false)
        host.onTabPressed("e", shift = false, ctrl = true)
        // Selecting never moved the focus nor reduced a window.
        assertEquals("b", host.focusedId)
        assertEquals(false, states.getValue("d").minimized)
        assertEquals(setOf("b", "c", "d", "e"), host.selectedTabs)
        host.unregister("c")
        assertEquals(setOf("b", "d", "e"), host.selectedTabs)
    }

    private fun hostOf(closed: MutableList<String> = mutableListOf()): Pair<WindowFrameHost, Map<String, WindowFrameState>> {
        val host = WindowFrameHost()
        val states = order.associateWith { WindowFrameState(it) }
        for (id in order) {
            host.register(WindowFrameHost.Registration(id, id, states.getValue(id), false, null) { closed += id; host.unregister(id) })
        }
        return host to states
    }

    @Test
    fun a_focused_window_selects_its_tab_alone_unless_it_is_already_among_the_selected() {
        val (host, _) = hostOf()
        host.focus("a")
        assertEquals(setOf("a"), host.selectedTabs)
        host.onTabPressed("c", shift = true, ctrl = false)
        assertEquals(setOf("a", "b", "c"), host.selectedTabs)
        // A press inside one of the selected windows keeps the selection.
        host.focus("b")
        assertEquals(setOf("a", "b", "c"), host.selectedTabs)
        // A press inside another selects its tab alone.
        host.focus("e")
        assertEquals(setOf("e"), host.selectedTabs)
    }

    @Test
    fun the_bar_menu_minimizes_opens_and_closes_the_selected_windows_only() {
        val closed = mutableListOf<String>()
        val (host, states) = hostOf(closed)
        host.focus("b")
        host.onTabPressed("d", shift = true, ctrl = false)
        host.minimizeSelection()
        assertEquals(listOf(true, true, true), listOf("b", "c", "d").map { states.getValue(it).minimized })
        assertEquals(false, states.getValue("a").minimized)
        assertEquals(null, host.focusedId)
        assertEquals(false, host.selectionHasShown)

        host.openSelection()
        assertEquals(listOf(false, false, false), listOf("b", "c", "d").map { states.getValue(it).minimized })
        assertEquals("d", host.focusedId)
        assertEquals("d", host.frontId)
        assertEquals(setOf("b", "c", "d"), host.selectedTabs)

        host.closeSelection()
        assertEquals(listOf("b", "c", "d"), closed)
        assertEquals(listOf("a", "e"), host.registrations.map { it.id })
        assertEquals(emptySet(), host.selectedTabs)
    }

    /**
     * Anomaly 2026-10-01: "close selection" over every tab (and the bar's Reset) left a window open. Each close hands
     * the focus to the window under it at once, while the closed windows only unregister a frame later — so the focus
     * went to a window of the same batch, and `App` reopened the window the focus named.
     */
    @Test
    fun a_batch_close_hands_the_focus_only_to_a_window_that_stays() {
        val host = WindowFrameHost()
        val focusedAfterClose = mutableListOf<String?>()
        val closedNotYetDisposed = mutableListOf<String>()
        for (id in order) {
            host.register(
                WindowFrameHost.Registration(id, id, WindowFrameState(id), false, null) {
                    // App's focusAfterClose, synchronous inside the close; the window leaves composition later.
                    focusedAfterClose += host.focusHeirOf(id)
                    closedNotYetDisposed += id
                },
            )
        }
        host.focus("a")
        host.onTabPressed("e", shift = true, ctrl = false)
        host.closeSelection()
        assertEquals(List<String?>(order.size) { null }, focusedAfterClose)
        closedNotYetDisposed.forEach(host::unregister)

        // Closing some: the focus goes to the one left.
        val survivors = listOf("x", "y", "z")
        focusedAfterClose.clear()
        for (id in survivors) {
            host.register(WindowFrameHost.Registration(id, id, WindowFrameState(id), false, null) { focusedAfterClose += host.focusHeirOf(id) })
        }
        host.focus("y")
        host.onTabPressed("z", shift = true, ctrl = false)
        host.closeSelection()
        assertEquals(listOf<String?>("x", "x"), focusedAfterClose)
    }

    /**
     * User rule 2026-10-06: *"When the user closes a window which another window originates from, then this window
     * gets closed too."* The calendar, a Search window opened from it and that one's configurations window: closing
     * the calendar closes all three, and the focus goes to the window that stays.
     */
    @Test
    fun a_window_that_closes_takes_the_windows_opened_from_it_and_theirs() {
        val host = WindowFrameHost()
        val parents = mapOf("Search" to "Calendar", "ConfigSearch" to "Search")
        host.tabParentOf = { parents[it] }
        val closed = mutableListOf<String>()
        val focusedAfterClose = mutableListOf<String?>()
        // App's close of a window: the windows opened from it first, then the focus handed on.
        fun close(id: String) {
            host.closeOpenedFrom(id)
            closed += id
            focusedAfterClose += host.focusHeirOf(id)
        }
        for (id in listOf("Alarms", "Calendar", "Search", "ConfigSearch")) {
            host.register(WindowFrameHost.Registration(id, id, WindowFrameState(id), false, null) { close(id) })
        }
        close("Calendar")
        assertEquals(listOf("ConfigSearch", "Search", "Calendar"), closed)
        assertEquals(listOf<String?>("Alarms", "Alarms", "Alarms"), focusedAfterClose)
    }

    /**
     * Anomaly 2026-10-14: "timers Search" focused, the app rebuilt, the "unfocused notif" window closed — and the focus
     * went to "Claude quota", the front of a stack the restart rebuilt in the order the windows came back. A window
     * that closes with the focus hands it to the window the user was in before it; one closing without it leaves the
     * focus alone.
     */
    @Test
    fun a_closing_window_hands_the_focus_to_the_one_focused_before_it_and_no_other() {
        val host = WindowFrameHost()
        host.tabParentOf = { if (it == "Calendar#2") "Search#2" else null }
        val focusedAfterClose = mutableListOf<String?>()
        // App's close: the windows opened from it first, then the focus handed on.
        fun close(id: String) {
            host.closeOpenedFrom(id)
            host.focusHeirOf(id)?.let(host::focus)
            focusedAfterClose += host.focusedId
        }
        // Put back by a restart in this order — the stack's — and the focus handed to "timers Search" at launch.
        for (id in listOf("Calendar", "Search#3", "Search", "Search#2", "Calendar#2")) {
            host.register(WindowFrameHost.Registration(id, id, WindowFrameState(id), false, null) { close(id) })
        }
        host.focus("Search#3")
        // The calendar under the notif window raised since (a lock re-fitting it, say): still not where the focus goes.
        host.raise("Search")
        host.raise("Search#2")
        host.raise("Calendar#2")

        // Closed without being pressed (its tab's ✕): the focus does not move.
        host.registrations.first { it.id == "Search#2" }.onClose()
        assertEquals(listOf<String?>("Search#3", "Search#3"), focusedAfterClose)
        listOf("Search#2", "Calendar#2").forEach(host::unregister)

        // Pressed first (its head's ✕ is in it): the focus goes back to the window it came from.
        focusedAfterClose.clear()
        host.register(WindowFrameHost.Registration("Search#2", "Search#2", WindowFrameState("Search#2"), false, null) { close("Search#2") })
        host.raise("Search")
        host.focus("Search#2")
        host.registrations.first { it.id == "Search#2" }.onClose()
        assertEquals(listOf<String?>("Search#3"), focusedAfterClose)
    }

    /**
     * User rule 2026-10-15: the head's "Rename" gives a window a name of its own, which its title — a Search window's,
     * worked out from its configuration — never overwrites; renamed to nothing, or to that very title, it follows its
     * title again.
     */
    @Test
    fun a_renamed_window_keeps_its_name_until_it_is_given_back() {
        val host = WindowFrameHost()
        var saves = 0
        host.onTabTitlesChanged = { saves++ }
        val registration = WindowFrameHost.Registration("Search#2", "all timers", WindowFrameState("Search#2"), false, null) {}
        host.register(registration)
        host.rename("Search#2", "  Tea timers ", ownTitle = "all timers")
        assertEquals("Tea timers", host.tabTitleOf(registration))
        // The configuration changes: the window's own title moves, its name does not.
        host.retitle("Search#2", "all timers and alarms")
        assertEquals("Tea timers", host.tabTitleOf(registration))
        // Renamed to its own title, or to nothing: it follows the title again.
        host.rename("Search#2", "all timers and alarms", ownTitle = "all timers and alarms")
        assertEquals("all timers and alarms", host.tabTitleOf(registration))
        host.rename("Search#2", "Mine", ownTitle = "all timers and alarms")
        host.rename("Search#2", " ", ownTitle = "all timers and alarms")
        assertEquals("all timers and alarms", host.tabTitleOf(registration))
        assertEquals(4, saves, "each change written down, and only a change")
        host.rename("Search#2", "", ownTitle = "all timers and alarms")
        assertEquals(4, saves)
    }

    @Test
    fun a_window_that_closes_leaves_the_one_it_was_opened_from_and_the_others_open() {
        val host = WindowFrameHost()
        val parents = mapOf("ConfigSearch" to "Search", "ConfigSearch#2" to "Search#2")
        host.tabParentOf = { parents[it] }
        val closed = mutableListOf<String>()
        for (id in listOf("Search", "Search#2", "ConfigSearch", "ConfigSearch#2")) {
            host.register(
                WindowFrameHost.Registration(id, id, WindowFrameState(id), false, null) {
                    host.closeOpenedFrom(id)
                    closed += id
                },
            )
        }
        host.registrations.first { it.id == "ConfigSearch" }.onClose()
        assertEquals(listOf("ConfigSearch"), closed)
        closed.clear()
        host.registrations.first { it.id == "Search#2" }.onClose()
        assertEquals(listOf("ConfigSearch#2", "Search#2"), closed)
    }
}
