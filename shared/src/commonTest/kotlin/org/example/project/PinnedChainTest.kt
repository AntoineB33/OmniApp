package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import org.example.project.ui.PinItem
import org.example.project.ui.pinnedChain

/**
 * Anomaly 2026-11-16 (*"I see in the action section … the row 'Task: chalazion' expanded. When I scroll down on the
 * children, 'Task: chalazion' gets hidden at the top. The correct logic however is used with the child 'Parents', that
 * when expanded stick to the top fully visible when scrolling down"*): **every open row above the row at the top stays
 * pinned**, then the open row whose content is scrolled through — one rule for both.
 */
class PinnedChainTest {
    private val band = 20f

    /** Rows of [heights] laid out from [scroll] pixels down the list. */
    private fun laid(heights: List<Int>, scroll: Int): List<PinItem> {
        var y = -scroll
        return heights.mapIndexed { i, h -> PinItem(i, y, h).also { y += h } }
    }

    // 0: "Task: chalazion" (group, depth 0, open), 1..8: its actions (depth 1), 3 is "Parents", open with 100 px of fields.
    private val depths = listOf(0, 1, 1, 1, 1, 1, 1, 1, 1, 0)
    private val heights = listOf(20, 20, 20, 120, 20, 20, 20, 20, 20, 20)
    private fun chain(scroll: Int) =
        pinnedChain(laid(heights, scroll), 0, band, depthOf = { depths.getOrNull(it) }, pinnable = { it == 3 })

    @Test
    fun nothing_is_pinned_at_the_top_of_the_list() {
        assertEquals(emptyList(), chain(0))
    }

    @Test
    fun the_open_group_stays_while_its_closed_actions_scroll() {
        assertEquals(listOf(0), chain(30))
    }

    @Test
    fun the_group_and_the_open_action_under_it_stack() {
        // "Parents" begins at 60: scrolled to 70 its head is under the group's pin, its fields still in view.
        assertEquals(listOf(0, 3), chain(70))
    }

    @Test
    fun past_the_open_action_the_group_alone_stays() {
        assertEquals(listOf(0), chain(200))
    }

    @Test
    fun past_the_group_nothing_of_it_stays() {
        // The next top row (9) begins at 280.
        assertEquals(emptyList(), chain(285))
    }

    @Test
    fun a_list_of_no_nesting_pins_its_open_row_alone() {
        val items = laid(listOf(20, 200, 20), 50)
        assertEquals(listOf(1), pinnedChain(items, 0, band, depthOf = { null }, pinnable = { it == 1 }))
        assertEquals(emptyList(), pinnedChain(items, 0, band, depthOf = { null }, pinnable = { false }))
    }
}
