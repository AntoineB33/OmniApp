package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.example.project.ui.CustomMenuButton
import org.example.project.ui.CustomMenuButtons
import org.example.project.ui.CustomMenuButtons.MenuRow

/**
 * User rule 2026-11-01: *"the user can put one above another button, which makes the two buttons inside a folder button.
 * A folder button has an expansion arrow to reveal below its sub buttons. Clicking on the folder button is like clicking
 * on all the buttons in this folder that open a window (including any depth in the folder)."*
 */
class CustomMenuFoldersTest {
    private fun window(id: String, parent: String? = null) = CustomMenuButton(id, "Search", id, parent = parent)

    private fun ids(list: List<CustomMenuButton>) = list.map { it.id }

    private fun tree(list: List<CustomMenuButton>) = CustomMenuButtons.visibleRows(list).map { it.button.id to it.depth }

    private fun group(list: List<CustomMenuButton>, id: String, target: String) =
        CustomMenuButtons.grouped(list, id, target, list.mapTo(HashSet()) { it.id })

    private val menu = listOf(window("a"), window("b"), window("c"))

    @Test
    fun a_button_dropped_on_another_makes_a_folder_of_the_two_where_it_stood() {
        val (list, folder) = group(menu, "c", "a")
        assertEquals("f1", folder)
        assertEquals(listOf("f1" to 0, "a" to 1, "c" to 1, "b" to 0), tree(list))
        val made = list.first()
        assertTrue(made.folder)
        assertEquals(CustomMenuButtons.FOLDER_TITLE, made.title)
        assertEquals("", made.windowId)
    }

    @Test
    fun a_folder_id_is_new_on_every_page() {
        val (_, folder) = CustomMenuButtons.grouped(menu, "c", "a", setOf("a", "b", "c", "f1", "f2"))
        assertEquals("f3", folder)
    }

    @Test
    fun a_button_dropped_on_a_folder_joins_it_at_its_end_and_opens_it() {
        val (made, folder) = group(menu, "c", "a")
        val shut = CustomMenuButtons.toggled(made, folder!!)
        val (joined, none) = group(shut, "b", folder)
        assertNull(none, "no new folder, so no title in edit")
        assertEquals(listOf("f1" to 0, "a" to 1, "c" to 1, "b" to 1), tree(joined))
        assertFalse(joined.first().collapsed)
    }

    @Test
    fun folders_nest_to_any_depth_and_a_folder_moves_with_what_is_in_it() {
        val (one, inner) = group(menu, "b", "a")
        val (two, outer) = group(one, "c", inner!!.let { "b" })
        assertEquals(listOf("f1" to 0, "a" to 1, "f2" to 1, "b" to 2, "c" to 2), tree(two))
        val carried = CustomMenuButtons.moved(two + window("d"), "f2", "f1")
        assertEquals(listOf("f2" to 0, "b" to 1, "c" to 1, "f1" to 0, "a" to 1, "d" to 0), tree(carried))
        assertEquals("f2", outer)
    }

    @Test
    fun a_folder_cannot_go_inside_itself() {
        val (list, folder) = group(menu, "b", "a")
        assertEquals(list to null, CustomMenuButtons.grouped(list, folder!!, "a", emptySet()))
        assertEquals(list, CustomMenuButtons.moved(list, folder, "b"))
    }

    @Test
    fun an_item_takes_the_folder_of_the_one_it_lands_before_and_at_the_end_the_top() {
        val (list, _) = group(menu + window("d"), "b", "a")
        assertEquals(listOf("f1" to 0, "a" to 1, "b" to 1, "c" to 0, "d" to 0), tree(list))
        assertEquals(listOf("f1" to 0, "d" to 1, "a" to 1, "b" to 1, "c" to 0), tree(CustomMenuButtons.moved(list, "d", "a")))
        assertEquals(listOf("f1" to 0, "a" to 1, "c" to 0, "d" to 0, "b" to 0), tree(CustomMenuButtons.moved(list, "b", null)))
    }

    @Test
    fun a_folder_emptied_goes_and_one_removed_takes_what_is_in_it() {
        val (list, folder) = group(menu, "b", "a")
        val one = CustomMenuButtons.moved(list, "a", null)
        val none = CustomMenuButtons.moved(one, "b", null)
        assertFalse(none.any { it.folder })
        assertEquals(listOf("c", "a", "b"), ids(none))
        assertEquals(listOf("c"), ids(CustomMenuButtons.removed(list, folder!!)))
    }

    @Test
    fun a_retracted_folder_hides_every_depth_under_it() {
        val (one, _) = group(menu, "b", "a")
        val (two, _) = group(one, "c", "b")
        assertEquals(listOf(MenuRow(two.first { it.id == "f1" }.copy(collapsed = true), 0)), CustomMenuButtons.visibleRows(CustomMenuButtons.toggled(two, "f1")))
    }

    @Test
    fun a_press_on_a_folder_is_a_press_on_its_window_buttons_at_any_depth() {
        val (one, _) = group(menu, "b", "a")
        val (two, _) = group(one, "c", "b")
        val switch = CustomMenuButton("s", "", "", control = "Voice", parent = "f1")
        val action = CustomMenuButton("x", "", "t", action = "Duplicate", parent = "f2")
        val list = two + switch + action
        assertEquals(listOf("a", "b", "c"), ids(CustomMenuButtons.windowButtonsIn(list, "f1")))
        assertEquals(listOf("b", "c"), ids(CustomMenuButtons.windowButtonsIn(list, "f2")))
    }

    @Test
    fun an_item_whose_folder_is_on_another_page_stands_at_the_top() {
        assertEquals(listOf("a" to 0), tree(listOf(window("a", parent = "gone"))))
    }

    @Test
    fun a_loop_in_a_stored_menu_ends_nothing() {
        val list = listOf(window("a", parent = "b"), window("b", parent = "a"))
        assertEquals(2, tree(list).size)
    }

    @Test
    fun folders_are_kept_and_a_menu_stored_before_them_reads_the_same() {
        val (list, _) = group(menu, "b", "a")
        val shut = CustomMenuButtons.toggled(list, "f1")
        assertEquals(shut, CustomMenuButtons.decode(CustomMenuButtons.encode(shut)))
        val older = """{"buttons":[{"id":"a","window":"Search","title":"a"}],"seeded":true,"globalSeeded":true,"muteSeeded":true}"""
        val read = CustomMenuButtons.decode(older).single()
        assertFalse(read.folder)
        assertNull(read.parent)
        assertFalse(read.collapsed)
    }

    /** Rows 40 tall, 10 apart, the gaps' middles at 45, 95, 145. */
    private fun rows(vararg rows: Pair<String, CustomMenuButton>): List<CustomMenuButtons.DropRow> {
        val depth = rows.toList().associate { it.first to it.second.parent }
        fun of(id: String?): Int = id?.let { 1 + of(depth[it]) } ?: 0
        return rows.mapIndexed { i, (id, b) -> CustomMenuButtons.DropRow(id, b.parent, of(b.parent), i * 50f, 40f) }
    }

    @Test
    fun under_a_folder_closer_to_its_first_item_is_in_it_and_closer_to_the_folder_is_after_what_is_in_it() {
        val rows = rows("f" to window("f"), "c1" to window("c1", "f"), "c2" to window("c2", "f"), "r" to window("r"))
        val deep = CustomMenuButtons.dropPlace(rows, 47f)
        assertEquals(1, deep.depth, "the line starts a level in: shorter")
        assertEquals("c1" to "f", deep.beforeId to deep.parent)
        val shallow = CustomMenuButtons.dropPlace(rows, 43f)
        assertEquals(0, shallow.depth)
        assertEquals("r", shallow.beforeId, "at the folder's depth: past what is in it")
        assertEquals(null, shallow.parent)
        assertEquals("f" to "c1", shallow.lineBelow to shallow.lineAbove, "the line stands where the hand is")
    }

    @Test
    fun under_a_folders_last_item_closer_to_it_stays_in_the_folder_and_further_leaves_it() {
        val rows = rows("f" to window("f"), "c" to window("c", "f"), "r" to window("r"))
        val stays = CustomMenuButtons.dropPlace(rows, 93f)
        assertEquals(Triple("r", "f", 1), Triple(stays.beforeId, stays.parent, stays.depth))
        val leaves = CustomMenuButtons.dropPlace(rows, 97f)
        assertEquals(Triple("r", null, 0), Triple(leaves.beforeId, leaves.parent, leaves.depth))
    }

    @Test
    fun at_the_very_end_closer_to_a_last_item_in_a_folder_stays_in_it() {
        val rows = rows("f" to window("f"), "c" to window("c", "f"))
        val stays = CustomMenuButtons.dropPlace(rows, 95f)
        assertEquals(Triple(null, "f", 1), Triple(stays.beforeId, stays.parent, stays.depth))
        val leaves = CustomMenuButtons.dropPlace(rows, 140f)
        assertEquals(Triple(null, null, 0), Triple(leaves.beforeId, leaves.parent, leaves.depth))
    }

    @Test
    fun rows_of_one_depth_have_one_place_in_the_gap() {
        val rows = rows("a" to window("a"), "b" to window("b"))
        assertEquals(CustomMenuButtons.dropPlace(rows, 43f), CustomMenuButtons.dropPlace(rows, 47f))
    }

    @Test
    fun a_place_chosen_is_where_the_item_lands() {
        val list = listOf(window("x"), window("f").copy(folder = true), window("c1", "f"), window("c2", "f"), window("r"))
        assertEquals(
            listOf("f" to 0, "c1" to 1, "c2" to 1, "x" to 1, "r" to 0),
            tree(CustomMenuButtons.placed(list, "x", "r", "f")),
            "the end of the folder",
        )
        assertEquals(
            listOf("f" to 0, "c1" to 1, "c2" to 1, "x" to 0, "r" to 0),
            tree(CustomMenuButtons.placed(list, "x", "r", null)),
            "after the folder",
        )
        assertEquals(list, CustomMenuButtons.placed(list, "f", null, "f"), "never inside itself")
    }

    @Test
    fun over_the_middle_of_a_row_a_drop_groups_and_at_its_edges_it_moves() {
        val rows = listOf(CustomMenuButtons.DropRow("a", null, 0, 0f, 40f), CustomMenuButtons.DropRow("b", null, 0, 50f, 40f))
        assertEquals("a", CustomMenuButtons.dropOnto(rows, 20f))
        assertEquals("b", CustomMenuButtons.dropOnto(rows, 70f))
        assertNull(CustomMenuButtons.dropOnto(rows, 5f))
        assertNull(CustomMenuButtons.dropOnto(rows, 45f))
    }
}
