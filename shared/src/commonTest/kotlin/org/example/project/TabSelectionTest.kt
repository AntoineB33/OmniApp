package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import org.example.project.ui.TabSelection
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
    ) = TabSelection.click(order, selected, anchor, clicked, shift, ctrl)

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
                    focusedAfterClose += host.frontIdExcluding(id)
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
            host.register(WindowFrameHost.Registration(id, id, WindowFrameState(id), false, null) { focusedAfterClose += host.frontIdExcluding(id) })
        }
        host.focus("y")
        host.onTabPressed("z", shift = true, ctrl = false)
        host.closeSelection()
        assertEquals(listOf<String?>("x", "x"), focusedAfterClose)
    }
}
