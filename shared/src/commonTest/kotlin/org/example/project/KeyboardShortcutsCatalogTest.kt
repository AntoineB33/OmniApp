package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.example.project.scheduler.platform.GlobalShortcut
import org.example.project.scheduler.platform.ShortcutBinding
import org.example.project.scheduler.platform.ShortcutKey
import org.example.project.ui.KeyboardShortcutCatalog

/**
 * PRD §7 Keyboard shortcuts: what can be checked mechanically about the window's catalogue.
 *
 * The per-surface chords are prose — nothing reads the `onPreviewKeyEvent` branches back — but the
 * **system-wide** block is derived from [GlobalShortcut], the same enum the platform actual registers with the
 * OS, and that link is worth pinning: a chord the window advertises but the app never claims is the one kind
 * of entry that actively misleads (the user presses it, nothing happens, and the list says it should).
 */
class KeyboardShortcutsCatalogTest {

    @Test
    fun everyGlobalShortcutIsListed() {
        val listed = KeyboardShortcutCatalog.globalGroup().shortcuts.map { it.keys }
        assertEquals(GlobalShortcut.entries.map { it.defaultChord }, listed)
        assertEquals("System-wide", KeyboardShortcutCatalog.groups().first().title)
    }

    /**
     * The system-wide block prints the chord the ACCOUNT is bound to, not the shipped one — the whole point
     * of the rebinding. A window still advertising `Ctrl+Shift+Alt+A` while the claim is on `Ctrl+Alt+K` is
     * the one kind of entry that actively misleads, in the one block where it can now happen.
     */
    @Test
    fun theGlobalBlockPrintsTheAccountsOwnChords() {
        val rebound = ShortcutBinding(ShortcutKey.K, ctrl = true, shift = false, alt = true)
        val group = KeyboardShortcutCatalog.globalGroup(mapOf(GlobalShortcut.ToggleAway to rebound))

        assertEquals("Ctrl+Alt+K", group.shortcuts.first().keys)
        // Everything the user did NOT rebind still reads as the chord it ships with.
        assertEquals(
            GlobalShortcut.entries.drop(1).map { it.defaultChord },
            group.shortcuts.drop(1).map { it.keys },
        )
    }

    /** The three chords the app ships with — spelled out here so a silent change of default fails the build. */
    @Test
    fun theGlobalChordsAreTheDocumentedOnes() {
        assertEquals("Ctrl+Shift+Alt+A", GlobalShortcut.ToggleAway.defaultChord)
        assertEquals("Ctrl+Shift+Alt+E", GlobalShortcut.LookAwayNow.defaultChord)
        assertEquals("Ctrl+Shift+Alt+Z", GlobalShortcut.SwitchTask.defaultChord)
    }

    @Test
    fun noGroupListsTheSameChordTwice() {
        KeyboardShortcutCatalog.groups().forEach { group ->
            val keys = group.shortcuts.map { it.keys }
            assertEquals(keys.distinct(), keys, "duplicate chord in \"${group.title}\"")
            assertTrue(group.shortcuts.isNotEmpty(), "empty group \"${group.title}\"")
            assertTrue(
                group.shortcuts.none { it.keys.isBlank() || it.description.isBlank() },
                "blank entry in \"${group.title}\"",
            )
        }
    }
}
