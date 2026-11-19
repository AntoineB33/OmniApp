package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.example.project.ui.PinItem
import org.example.project.ui.pinnedListRow
import org.example.project.ui.pinnedParent

/**
 * Anomaly 2026-11-19 (*"In the default calendar window, when I scroll down in the action section, both 'Calendar:
 * Calendar' and 'Calendar' are showing on the top … The correct behavior is shown in the children of the 'AI' task
 * element, where only one element is stuck at the top"*): **every list pins the task tree's one row** — the direct parent
 * of the row right below the band, once its own row has started to leave the top — never a stack of its ancestors.
 * One function ([pinnedParent]) for the tree and the lists ([pinnedListRow]).
 */
class PinnedParentTest {
    private val band = 20f

    /** Rows of [heights] laid out from [scroll] pixels down the list. */
    private fun laid(heights: List<Int>, scroll: Int): List<PinItem> {
        var y = -scroll
        return heights.mapIndexed { i, h -> PinItem(i, y, h).also { y += h } }
    }

    // 0: "Calendar: Calendar" (group, depth 0, open), 1..8: its actions (depth 1), 3 is "Calendar", open with 100 px of
    // fields; 9: the next group.
    private val depths = listOf(0, 1, 1, 1, 1, 1, 1, 1, 1, 0)
    private val heights = listOf(20, 20, 20, 120, 20, 20, 20, 20, 20, 20)
    private fun pinned(scroll: Int) =
        pinnedListRow(laid(heights, scroll), 0, band, depthOf = { depths.getOrNull(it) }, pinnable = { it == 3 })

    @Test
    fun nothing_is_pinned_at_the_top_of_the_list() {
        assertNull(pinned(0))
    }

    @Test
    fun the_open_group_stays_while_its_actions_scroll() {
        assertEquals(0, pinned(30))
    }

    @Test
    fun inside_the_open_action_s_fields_the_action_ALONE_stays() {
        // "Calendar" begins at 60: scrolled to 70 its head has left the top, its fields still in view. Its group is NOT
        // stacked over it (the anomaly): the tree pins the direct parent of what shows, and that is the action.
        assertEquals(3, pinned(70))
        assertEquals(3, pinned(120))
    }

    @Test
    fun past_the_open_action_its_group_stays_again() {
        assertEquals(0, pinned(200))
    }

    @Test
    fun past_the_group_nothing_of_it_stays() {
        // The next top row (9) begins at 280.
        assertNull(pinned(285))
    }

    /** The Life plan's steps: a step inside a step inside a step — the direct parent alone. */
    @Test
    fun a_deep_level_pins_its_own_parent_alone() {
        val depths = listOf(0, 1, 2, 2, 2, 2, 2, 2, 2, 2, 1, 0)
        val items = laid(List(depths.size) { 20 }, 75)
        assertEquals(1, pinnedListRow(items, 0, band, depthOf = { depths.getOrNull(it) }, pinnable = { false }))
    }

    @Test
    fun an_ancestor_scrolled_out_of_the_laid_out_items_is_still_pinned() {
        val depths = listOf(0, 1, 1, 1, 1, 1, 1, 1, 1)
        val items = laid(List(depths.size) { 20 }, 100).drop(4) // items 0..3 are no longer laid out
        assertEquals(0, pinnedListRow(items, 0, band, depthOf = { depths.getOrNull(it) }, pinnable = { false }))
    }

    @Test
    fun a_list_of_no_nesting_pins_its_open_row_alone() {
        val items = laid(listOf(20, 200, 20), 50)
        assertEquals(1, pinnedListRow(items, 0, band, depthOf = { null }, pinnable = { it == 1 }))
        // A row whose content pins a deeper parent itself (an expanded task row's sub-tree): the list pins nothing.
        assertNull(pinnedListRow(items, 0, band, depthOf = { null }, pinnable = { false }))
    }

    /** The tree's own reading, row by row: a row the band half covers is still the row "right below" it. */
    @Test
    fun the_tree_reads_the_row_the_band_half_covers() {
        // parent (0..20), child A (20..40), child B (40..60), scrolled 25: A lies under the band, B straddles its edge.
        val bands = listOf(-25f..-5f, -5f..15f, 15f..35f)
        val parents = listOf(null, 0, 0)
        val row = pinnedParent(listOf(0, 1, 2), 0f, band, { bands[it] }, { parents[it] })
        assertEquals(0, row)
        // The parent fully in view: nothing.
        val shown = listOf(0f..20f, 20f..40f, 40f..60f)
        assertNull(pinnedParent(listOf(0, 1, 2), 0f, band, { shown[it] }, { parents[it] }))
    }
}
