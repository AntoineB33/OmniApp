package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.SearchDomain
import org.example.project.scheduler.platform.GlobalShortcut
import org.example.project.scheduler.platform.ShortcutBinding
import org.example.project.scheduler.platform.ShortcutKey
import org.example.project.scheduler.state.SchedulerState
import org.example.project.ui.WindowShortcut
import org.example.project.ui.WindowShortcuts

/**
 * User rule 2026-10-12: *"Allow the user to map any shortcut to any window. The defined shortcut would then put focus
 * on the app and put focus on the mapped window."* — the mappings (each a chord and a window), their rules, how they
 * are kept, and what the Search window makes of them.
 */
class WindowShortcutsTest {
    private val ctrlAltK = ShortcutBinding(ShortcutKey.K, ctrl = true, shift = false, alt = true)
    private val ctrlShiftF2 = ShortcutBinding(ShortcutKey.F2, ctrl = true, shift = true, alt = false)

    @Test
    fun a_chord_is_kept_as_its_spelling_and_read_back() {
        for (binding in listOf(ctrlAltK, ctrlShiftF2, ShortcutBinding(ShortcutKey.Digit7))) {
            assertEquals(binding, ShortcutBinding.parse(binding.chord))
        }
        assertNull(ShortcutBinding.parse("Ctrl+Alt+"))
        assertNull(ShortcutBinding.parse("Win+Alt+K"), "no modifier the claim cannot hold")
        assertNull(ShortcutBinding.parse(""))

        val list =
            listOf(
                WindowShortcut("ws-0", "Search", ctrlAltK),
                WindowShortcut("ws-1", "Search#2", ctrlShiftF2),
                // Made with "New", and given nothing yet — or one of the two only.
                WindowShortcut("ws-2"),
                WindowShortcut("ws-3", window = "Calendar"),
                WindowShortcut("ws-4", chord = ShortcutBinding(ShortcutKey.Digit7)),
            )
        assertEquals(list, WindowShortcuts.decode(WindowShortcuts.encode(list)))
        assertEquals("Search#2", WindowShortcuts.windowOf(list, ctrlShiftF2))
        assertNull(WindowShortcuts.windowOf(list, ShortcutBinding(ShortcutKey.Digit7)), "a chord with no window brings nothing")
        // Only a mapping with both is claimed from the OS.
        assertEquals(setOf(ctrlAltK, ctrlShiftF2), WindowShortcuts.chordsOf(list))
        assertEquals("ws-5", WindowShortcuts.mintId(list))
    }

    @Test
    fun what_was_never_written_or_cannot_be_read_is_no_mapping_and_a_forbidden_one_is_healed() {
        assertEquals(emptyList(), WindowShortcuts.decode(null))
        assertEquals(emptyList(), WindowShortcuts.decode("not json"))
        assertEquals(emptyList(), WindowShortcuts.decode("""{"other":1}"""), "a row written by another build")
        // The first shape written, the same day: frame id → chord.
        assertEquals(
            listOf(WindowShortcut("ws-0", "Calendar", ctrlAltK)),
            WindowShortcuts.decode("""{"chords":{"Calendar":"Ctrl+Alt+K"}}"""),
        )
        // A hand-edited row: a chord under the modifiers' floor, one that spells none, a chord and an id twice.
        val healed =
            WindowShortcuts.decode(
                """{"maps":[{"id":"a","window":"Calendar","chord":"Ctrl+Alt+K"},{"id":"a","window":"History","chord":"Ctrl+K"},""" +
                    """{"id":"b","window":"Sleep","chord":"Ctrl+Alt+?"},{"id":"c","window":"Alarms","chord":"Ctrl+Alt+K"}]}""",
            )
        assertEquals(
            listOf(
                WindowShortcut("a", "Calendar", ctrlAltK),
                WindowShortcut("ws-0", "History", null),
                WindowShortcut("b", "Sleep", null),
                WindowShortcut("c", "Alarms", null),
            ),
            healed,
        )
    }

    @Test
    fun one_chord_is_one_thing_across_the_shortcuts_and_the_windows() {
        val list = listOf(WindowShortcut("ws-0", "Calendar", ctrlAltK), WindowShortcut("ws-1"))
        // Free, and with the modifiers a system-wide claim needs: accepted — also for the mapping that has it already.
        assertNull(WindowShortcuts.rejection(emptyMap(), list, "ws-1", ctrlShiftF2))
        assertNull(WindowShortcuts.rejection(emptyMap(), list, "ws-0", ctrlAltK))
        // One modifier would take Ctrl+K from every application.
        assertNotNull(WindowShortcuts.rejection(emptyMap(), list, "ws-1", ShortcutBinding(ShortcutKey.K, ctrl = true, shift = false, alt = false)))
        // Another mapping's chord, named by the name its window goes by.
        assertEquals(
            "Already the chord of the window \"Calendar (week)\".",
            WindowShortcuts.rejection(emptyMap(), list, "ws-1", ctrlAltK) { if (it == "Calendar") "Calendar (week)" else it },
        )
        // One of the app's shortcuts: at its shipped chord, and where the account moved it.
        val away = GlobalShortcut.ToggleAway
        assertEquals("Already bound to \"${away.action}\".", WindowShortcuts.rejection(emptyMap(), list, "ws-1", away.defaultBinding))
        val moved = mapOf(away to ctrlShiftF2)
        assertEquals("Already bound to \"${away.action}\".", WindowShortcuts.rejection(moved, list, "ws-1", ctrlShiftF2))
        assertNull(WindowShortcuts.rejection(moved, list, "ws-1", away.defaultBinding), "the chord it left is free")
        // …and the other way round: a shortcut may not take a mapping's chord.
        assertNotNull(WindowShortcuts.heldByWindow(list, ctrlAltK))
        assertNull(WindowShortcuts.heldByWindow(list, ctrlShiftF2))
        // A change puts the mapping in its place; a new one joins the list.
        assertEquals(listOf(list[0], WindowShortcut("ws-1", "Search")), WindowShortcuts.with(list, WindowShortcut("ws-1", "Search")))
        assertEquals(list + WindowShortcut("ws-2"), WindowShortcuts.with(list, WindowShortcut("ws-2")))
    }

    /**
     * Anomaly 2026-10-12: *"The action section for a keyboard shortcut element in the Search window is empty. The user
     * is supposed to create a new shortcut/window map with the 'new' button in the actions of a keyboard element or a
     * keyboard creation element."*
     */
    @Test
    fun a_keyboard_shortcut_is_a_search_element_with_actions_and_new_makes_a_chord_for_a_window() {
        val state = SchedulerState.empty()
        // What `App` hands in beside its windows: the mappings (never listed as windows).
        val handed =
            listOf(
                SearchDomain.WindowEntry("Calendar", "Calendar", SearchDomain.WindowStatus.Open),
                SearchDomain.WindowEntry(
                    "ws-0", "ws-0", SearchDomain.WindowStatus.NotOpen,
                    mapping = SearchDomain.WindowEntry.WindowChord("Calendar", "Ctrl+Alt+K"),
                ),
                SearchDomain.WindowEntry("ws-1", "ws-1", SearchDomain.WindowStatus.NotOpen, mapping = SearchDomain.WindowEntry.WindowChord(null, null)),
            )
        val shortcuts = SearchDomain.results(state, setOf(SearchDomain.Kind.Shortcut), "", windows = handed).map { it as SearchDomain.ItemResult }
        assertEquals(GlobalShortcut.entries.size + 2, shortcuts.size)
        val mapped = shortcuts.filter { it.id.startsWith(WindowShortcuts.SEARCH_PREFIX) }
        assertEquals(listOf("window:ws-0" to "Ctrl+Alt+K", "window:ws-1" to "no chord yet"), mapped.map { it.id to it.detail }.sortedBy { it.first })
        assertTrue(mapped.any { it.name == "Window: Calendar" } && mapped.any { it.name == "Window: none chosen yet" })
        assertEquals(listOf("Calendar"), SearchDomain.results(state, setOf(SearchDomain.Kind.Window), "", windows = handed).map { it.name })
        // A Search window on shortcuts asks `App` for them.
        assertTrue(SearchDomain.Config(kinds = setOf(SearchDomain.Kind.Shortcut)).readsWindows)
        assertTrue(SearchDomain.elementSearchConfig(SearchDomain.Kind.Shortcut, "window:ws-0").readsWindows)
        assertEquals(listOf("Window: Calendar"), SearchDomain.resolve(state, listOf("Shortcut/window:ws-0"), windows = handed).map { it.name })

        fun groups(added: List<SearchDomain.Result>) =
            SearchDomain.actionGroups(
                SearchDomain.actionsFor(
                    SearchDomain.addedActions("", SearchDomain.Kind.entries.toSet(), SearchDomain.actionKindsOf(added)), added,
                ),
                added,
            )
        val new = SearchDomain.AddedAction.ShortcutNew
        val chord = SearchDomain.AddedAction.ShortcutChord
        val window = SearchDomain.AddedAction.ShortcutWindow
        val delete = SearchDomain.AddedAction.ShortcutDelete
        // One shortcut element: its chord, its window, "New" and the bin.
        val one = groups(listOf(mapped.first())).single().actions
        assertTrue(one.containsAll(listOf(chord, window, new, delete)), one.toString())
        // One of the app's own shortcuts has the same section ("New" among it).
        assertTrue(groups(listOf(shortcuts.first { it.id == GlobalShortcut.ToggleAway.name })).single().actions.containsAll(listOf(chord, new)))
        // Several: "New" and the bin for all of them, the chord and the window each one's own.
        val several = groups(mapped)
        assertTrue(several.first().actions.containsAll(listOf(new, delete)) && several.first().actions.none { it == chord || it == window })
        assertTrue(several.drop(1).all { it.actions.containsAll(listOf(chord, window)) })
        // The creation row "New keyboard shortcut": "New", and nothing that needs a shortcut that exists.
        val creation = SearchDomain.ItemResult(SearchDomain.Kind.Creation, SearchDomain.Kind.Shortcut.name, "New keyboard shortcut", "")
        val offered = groups(listOf(creation)).flatMap { it.actions }
        assertTrue(new in offered, offered.toString())
        assertTrue(offered.none { it == chord || it == window || it == delete })
    }
}
