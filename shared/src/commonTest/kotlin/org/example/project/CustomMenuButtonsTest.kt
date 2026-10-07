package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.example.project.ui.CustomMenuButton
import org.example.project.ui.CustomMenuButtons

/**
 * PRD §7 *Lateral menu*: the buttons the user makes at the bottom of the menu, one per window (the head's ☆).
 * User spec 2026-09-25: the new button lands at the BOTTOM, its title in edit mode with the default name all
 * selected (typing replaces it, Enter keeps it); settled with the user: for the lateral-menu windows (and their
 * copies), and a right-click renames or removes one.
 */
class CustomMenuButtonsTest {

    @Test
    fun a_new_button_lands_at_the_bottom_with_an_id_of_its_own() {
        val (one, first) = CustomMenuButtons.added(emptyList(), "Search", "Search")
        val (two, second) = CustomMenuButtons.added(one, "Search#2", "Search #2")
        assertEquals(listOf("Search", "Search#2"), two.map { it.windowId })
        assertTrue(first != second)
        // Two buttons for the same window are two buttons.
        val (three, third) = CustomMenuButtons.added(two, "Search", "Search again")
        assertEquals(3, three.map { it.id }.toSet().size)
        assertEquals(third, three.last().id)
        // An id freed by a removal may be given again, never one still in use.
        val (again, reused) = CustomMenuButtons.added(CustomMenuButtons.removed(three, first), "Calendar", "Calendar")
        assertEquals(again.map { it.id }.toSet().size, again.size)
        assertEquals(first, reused)
    }

    @Test
    fun a_rename_keeps_the_old_title_when_left_blank_and_a_removal_takes_one_button() {
        val (list, id) = CustomMenuButtons.added(emptyList(), "Categories", "Categories")
        assertEquals("My categories", CustomMenuButtons.renamed(list, id, "  My categories ").single().title)
        assertEquals("Categories", CustomMenuButtons.renamed(list, id, "   ").single().title)
        assertEquals(emptyList(), CustomMenuButtons.removed(list, id))
    }

    @Test
    fun the_buttons_survive_their_local_encoding() {
        val buttons = listOf(CustomMenuButton("b1", "Search#2", "Findings"), CustomMenuButton("b2", "TaskTree", "Tree"))
        assertEquals(buttons, CustomMenuButtons.decode(CustomMenuButtons.encode(buttons)))
        // Nothing stored, or nothing readable, is no button — never a failed start.
        assertEquals(emptyList(), CustomMenuButtons.decode(null))
        assertEquals(emptyList(), CustomMenuButtons.decode("{not json"))
        // A field a later build adds is ignored.
        assertEquals(
            listOf(CustomMenuButton("b1", "Search", "S")),
            CustomMenuButtons.decode("""{"buttons":[{"id":"b1","window":"Search","title":"S","icon":"star"}],"v":2}"""),
        )
    }

    @Test
    fun a_button_stored_without_a_configuration_is_given_its_windows_once_and_keeps_it() {
        // The anomaly (2026-09-26): a "timers" button made before the snapshot existed read its Search window's
        // configuration at every click, so after the types were changed in the window it opened, the button still
        // "found" that window. It now keeps ONE configuration, taken at load.
        val old = CustomMenuButtons.decode("""{"buttons":[{"id":"b4","window":"Search","title":"timers"},""" +
            """{"id":"b1","window":"object:Timer:live:timer-3","title":"job offers"}]}""")
        val atLoad = """{"kinds":["Timer"]}"""
        val frozen = CustomMenuButtons.withConfigsFrozen(old) { if (it == "Search") atLoad else null }
        assertEquals(atLoad, frozen.first().config)
        assertEquals(null, frozen.last().config, "a window with no configuration leaves its button as it is")
        // Frozen: what the window becomes later no longer reaches it.
        assertEquals(frozen, CustomMenuButtons.withConfigsFrozen(frozen) { if (it == "Search") """{"kinds":["Timer","Chrono"]}""" else null })
    }

    @Test
    fun a_button_keeps_the_configuration_its_window_had_and_one_stored_before_that_reads_none() {
        // User spec 2026-09-26: the ☆ of a Search window keeps its configuration as a button that opens a new
        // window with exactly it.
        val saved = """{"query":"tea","kinds":["Timer"]}"""
        val (list, _) = CustomMenuButtons.added(emptyList(), "Search#2", "Search", saved)
        val decoded = CustomMenuButtons.decode(CustomMenuButtons.encode(list)).single()
        assertEquals(saved, decoded.config)
        // A button stored before the snapshot existed.
        val old = CustomMenuButtons.decode("""{"buttons":[{"id":"b1","window":"Search","title":"Search"}]}""").single()
        assertEquals(CustomMenuButton("b1", "Search", "Search"), old)
        assertEquals(null, old.config)
    }

    /**
     * User rule 2026-10-07, the button menu's "Update": it "will save the current state of the window that the user
     * opened by clicking this button. The option doesn't appear when the state hasn't changed or the window is
     * closed. The state includes the search configurations and the added elements list for the Search window, the
     * position of the window and its lines that separate the sections."
     */
    @Test
    fun update_is_offered_while_the_open_window_is_no_longer_what_the_button_holds() {
        val layout = org.example.project.ui.WindowLayout(10f, 20f, 800f, 600f, splits = listOf(0.5f, 0.5f))
        val (list, id) = CustomMenuButtons.added(emptyList(), "Search", "Mine", config = "c1")
        val made = list.single()
        fun offered(button: CustomMenuButton, config: String?, now: org.example.project.ui.WindowLayout?, opened: org.example.project.ui.WindowLayout? = layout) =
            CustomMenuButtons.needsUpdate(button, savedConfig = button.config, config = config, layout = now, opened = opened)

        // The window is closed: nothing to save.
        assertTrue(!offered(made, "c1", now = null))
        // Open and as the button opened it — a button that kept no layout is read against the one its window had then.
        assertTrue(!offered(made, "c1", now = layout))
        assertTrue(!offered(made, "c1", now = layout.copy(x = 10.4f)), "half a pixel is no change")
        // The configuration (the Search window's, its added elements included), the place, a line between sections.
        assertTrue(offered(made, "c2", now = layout))
        assertTrue(offered(made, "c1", now = layout.copy(x = 120f)))
        assertTrue(offered(made, "c1", now = layout.copy(width = 900f)))
        assertTrue(offered(made, "c1", now = layout.copy(splits = listOf(0.3f, 0.5f))))
        // Anomaly 2026-10-08: "I hide one section in the 'Claude quota' Search window … but the option 'update'
        // doesn't appear." A section retracted to its arrow is a change of the window's state.
        assertTrue(offered(made, "c1", now = layout.copy(hidden = listOf(false, true, false))))
        assertTrue(!offered(made, "c1", now = layout.copy(hidden = listOf(false, false, false))), "none hidden is what a layout kept before said")
        // …and a window that came back at a restart, for a button that never kept a layout: there is a state to save.
        assertTrue(offered(made, "c1", now = layout, opened = null))

        // Updated: the button holds that state, and the option is gone until the window changes again.
        val moved = layout.copy(x = 120f, splits = listOf(0.3f, 0.5f))
        val updated = CustomMenuButtons.updated(list, id, "c2", moved).single()
        assertEquals("c2", updated.config)
        assertEquals(moved, CustomMenuButtons.decodeLayout(updated.layout))
        assertTrue(!offered(updated, "c2", now = moved, opened = null))
        assertTrue(offered(updated, "c2", now = layout, opened = null), "its own layout is the reference from now on")
    }

    /** Persisted-view compatibility: a button written before it kept a layout still reads, with none. */
    @Test
    fun a_button_written_before_the_layout_still_reads_and_the_layout_round_trips() {
        val before = """{"buttons":[{"id":"b1","window":"Search","title":"Mine","config":"c1"}]}"""
        val read = CustomMenuButtons.decode(before).single()
        assertEquals(CustomMenuButton("b1", "Search", "Mine", "c1"), read)
        assertEquals(null, read.layout)
        val layout = org.example.project.ui.WindowLayout(1f, 2f, 3f, 4f, listOf(0.25f, 0.75f))
        val kept = CustomMenuButtons.updated(listOf(read), "b1", "c1", layout)
        assertEquals(kept, CustomMenuButtons.decode(CustomMenuButtons.encode(kept)))
        assertEquals(layout, CustomMenuButtons.decodeLayout(kept.single().layout))
        assertEquals(null, CustomMenuButtons.decodeLayout("not json"))
    }
}
