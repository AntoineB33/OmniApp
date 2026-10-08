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

    /** A list read from a payload written BEFORE the whole menu was the user's, less the items it is then given. */
    private fun own(list: List<CustomMenuButton>): List<CustomMenuButton> = list.filterNot { it in CustomMenuButtons.DEFAULTS }

    /**
     * User rule 2026-10-08: "Make the entire left-side menu of the app customizable, except for the top button … There
     * can be any kind of button in it, like switch buttons or fields."
     */
    @Test
    fun the_whole_menu_is_the_users_list_and_starts_as_what_the_menu_held() {
        val defaults = CustomMenuButtons.DEFAULTS
        assertEquals(listOf("TaskTree", "Calendar"), defaults.filter { it.control == null }.map { it.windowId })
        assertEquals(
            listOf("Voice", "Notifications", "LookAwayNow", "SwitchTask", "SleepWork", "Away", "Online"),
            defaults.mapNotNull { it.control },
            "the controls the fixed part held, in its order",
        )
        assertTrue(defaults.mapNotNull { it.control }.all { org.example.project.ui.MenuControl.of(it) != null })
        assertEquals(defaults.size, defaults.map { it.id }.toSet().size)
        // A menu never stored is that list; one written before is given it ON TOP, once…
        assertEquals(defaults, CustomMenuButtons.decode(null))
        val before = """{"buttons":[{"id":"b1","window":"Search","title":"Mine","config":"c1"}]}"""
        val seeded = CustomMenuButtons.decode(before)
        assertEquals(defaults + CustomMenuButton("b1", "Search", "Mine", "c1"), seeded)
        // …so what the user then removes stays removed: nothing is given twice.
        val emptied = CustomMenuButtons.removed(CustomMenuButtons.removed(seeded, "d-Voice"), "d-calendar")
        assertEquals(emptied, CustomMenuButtons.decode(CustomMenuButtons.encode(emptied)))
        assertEquals(emptyList(), CustomMenuButtons.decode(CustomMenuButtons.encode(emptyList())), "an emptied menu stays empty")
    }

    /** User rule 2026-10-08: "in customization mode, the user can drag the buttons or fields in the left-side menu". */
    @Test
    fun an_item_dragged_in_the_menu_lands_before_the_item_under_it() {
        val list = listOf("a", "b", "c", "d").map { CustomMenuButton(it, "Search", it) }
        fun ids(l: List<CustomMenuButton>) = l.joinToString("") { it.id }
        assertEquals("bcad", ids(CustomMenuButtons.moved(list, "a", "d")))
        assertEquals("dabc", ids(CustomMenuButtons.moved(list, "d", "a")))
        assertEquals("abdc", ids(CustomMenuButtons.moved(list, "c", null)), "no item under it: at the end")
        assertEquals("abcd", ids(CustomMenuButtons.moved(list, "b", "c")), "dropped where it stood")
        assertEquals(list, CustomMenuButtons.moved(list, "b", "b"))
        assertEquals(list, CustomMenuButtons.moved(list, "zz", "a"), "no such item")
        // Said by the item it lands before: an item the menu does not draw (its window is gone) keeps its place.
        assertEquals("bacd", ids(CustomMenuButtons.moved(list, "a", "c")))
        // Where a dragged row lands, by the middles of the other rows: 10, 50, 90.
        val others = listOf("b" to 10f, "c" to 50f, "d" to 90f)
        assertEquals("b", CustomMenuButtons.dropBefore(others, 0f))
        assertEquals("c", CustomMenuButtons.dropBefore(others, 30f))
        assertEquals("d", CustomMenuButtons.dropBefore(others, 60f))
        assertEquals(null, CustomMenuButtons.dropBefore(others, 120f))
    }

    /** "…the right-click menu will have one option: add in the left-side menu." */
    @Test
    fun a_control_added_to_the_menu_lands_at_the_bottom_and_stands_there_once() {
        val (list, id) = CustomMenuButtons.addedControl(CustomMenuButtons.DEFAULTS, org.example.project.ui.MenuControl.AutoSchedule)
        assertEquals("AutoSchedule", list.last().control)
        assertEquals(id, list.last().id)
        assertEquals(list.size, list.map { it.id }.toSet().size)
        // Already there: the same item, nothing added.
        assertEquals(list to id, CustomMenuButtons.addedControl(list, org.example.project.ui.MenuControl.AutoSchedule))
        assertEquals("d-Voice", CustomMenuButtons.addedControl(list, org.example.project.ui.MenuControl.Voice).second)
        // Removed, it can be added back; renamed, it keeps the name it was given; and it survives its encoding.
        val back = CustomMenuButtons.addedControl(CustomMenuButtons.removed(list, "d-Voice"), org.example.project.ui.MenuControl.Voice).first
        assertEquals("Voice", back.last().control)
        val renamed = CustomMenuButtons.renamed(back, back.last().id, "Speak")
        assertEquals("Speak", renamed.last().title)
        assertEquals(renamed, CustomMenuButtons.decode(CustomMenuButtons.encode(renamed)))
    }

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
        // Nothing stored, or nothing readable, is the menu's own items — never a failed start.
        assertEquals(CustomMenuButtons.DEFAULTS, CustomMenuButtons.decode(null))
        assertEquals(CustomMenuButtons.DEFAULTS, CustomMenuButtons.decode("{not json"))
        // A field a later build adds is ignored.
        assertEquals(
            listOf(CustomMenuButton("b1", "Search", "S")),
            own(CustomMenuButtons.decode("""{"buttons":[{"id":"b1","window":"Search","title":"S","icon":"star"}],"v":2}""")),
        )
    }

    @Test
    fun a_button_stored_without_a_configuration_is_given_its_windows_once_and_keeps_it() {
        // The anomaly (2026-09-26): a "timers" button made before the snapshot existed read its Search window's
        // configuration at every click, so after the types were changed in the window it opened, the button still
        // "found" that window. It now keeps ONE configuration, taken at load.
        val old = own(CustomMenuButtons.decode("""{"buttons":[{"id":"b4","window":"Search","title":"timers"},""" +
            """{"id":"b1","window":"object:Timer:live:timer-3","title":"job offers"}]}"""))
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
        val old = own(CustomMenuButtons.decode("""{"buttons":[{"id":"b1","window":"Search","title":"Search"}]}""")).single()
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
        val read = own(CustomMenuButtons.decode(before)).single()
        assertEquals(CustomMenuButton("b1", "Search", "Mine", "c1"), read)
        assertEquals(null, read.layout)
        val layout = org.example.project.ui.WindowLayout(1f, 2f, 3f, 4f, listOf(0.25f, 0.75f))
        val kept = CustomMenuButtons.updated(listOf(read), "b1", "c1", layout)
        assertEquals(kept, CustomMenuButtons.decode(CustomMenuButtons.encode(kept)))
        assertEquals(layout, CustomMenuButtons.decodeLayout(kept.single().layout))
        assertEquals(null, CustomMenuButtons.decodeLayout("not json"))
    }

    /** User rule 2026-10-08: an action of a Search window, added to the menu with that window's elements. */
    @Test
    fun an_action_added_to_the_menu_keeps_its_elements_and_survives_its_encoding() {
        val config = """{"kinds":["Task"],"added":["Task/task/user/3"]}"""
        val (list, id) = CustomMenuButtons.addedAction(CustomMenuButtons.DEFAULTS, "TaskDuplicate", "Duplicate  ·  Apple", config)
        val item = list.last()
        assertEquals(CustomMenuButton(id, "", "Duplicate  ·  Apple", config, action = "TaskDuplicate"), item)
        // The same action on other elements is another item: nothing says two are one.
        val (two, second) = CustomMenuButtons.addedAction(list, "TaskDuplicate", "Duplicate  ·  Banana", config)
        assertTrue(second != id && two.size == list.size + 1)
        assertEquals(two, CustomMenuButtons.decode(CustomMenuButtons.encode(two)))
        // A list written before items could be actions reads with none.
        assertTrue(CustomMenuButtons.decode("""{"buttons":[{"id":"b1","window":"Search","title":"S"}],"seeded":true}""").single().action == null)
    }
}
