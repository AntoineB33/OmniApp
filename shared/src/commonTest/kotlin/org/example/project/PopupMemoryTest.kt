package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.example.project.ui.PopupMemory

/**
 * Anomaly 2026-11-15: *"I pinned the window …, I restart the app, and all the windows disappeared... Only the first one,
 * the default search window, remains"* — the pop-ups left open come back after a restart ([PopupMemory]).
 */
class PopupMemoryTest {
    private val list = "Search#3/actions/field:action/kind/Task/ElementParents/list of parents"
    private val nested = "$list/actions/field:action/element/Task/x/ElementParents/add a parent"
    private val tree = nested + "▾"

    @Test
    fun what_was_left_open_comes_back_and_a_pop_up_closed_forgets_what_was_opened_inside_it() {
        var row: String? = null
        val memory = PopupMemory({ row }) { row = it }
        memory.setField(list, true)
        memory.setField(nested, true)
        memory.setTree(tree, listOf("kind/Task"))
        // A restart: read back from the row.
        val again = PopupMemory({ row }) { row = it }
        assertTrue(again.fieldOpen(list) && again.fieldOpen(nested))
        assertEquals(listOf("kind/Task"), again.tree(tree))
        // The tree no longer pinned: forgotten; the outer field closed: everything inside it too.
        again.setTree(tree, null)
        assertNull(again.tree(tree))
        again.setTree(tree, listOf("kind/Task"))
        again.setField(list, false)
        assertFalse(again.fieldOpen(list) || again.fieldOpen(nested))
        assertNull(again.tree(tree))
    }

    @Test
    fun the_pop_ups_of_a_window_no_longer_open_are_dropped_as_it_is_read() {
        var row: String? = null
        PopupMemory({ row }) { row = it }.apply {
            setField(list, true)
            setTree("Search▾", listOf("kind/Task"))
        }
        val kept = PopupMemory.kept(row) { it == "Search" }
        val read = PopupMemory({ kept }) { }
        assertFalse(read.fieldOpen(list), "Search#3 is closed: a later Search#3 is another window")
        assertEquals(listOf("kind/Task"), read.tree("Search▾"))
        assertEquals("Search#3", PopupMemory.windowOf(list))
        assertEquals("Search", PopupMemory.windowOf("Search▾"))
    }
}
