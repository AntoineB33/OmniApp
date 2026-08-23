package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.example.project.scheduler.platform.GlobalShortcut
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
        val listed = KeyboardShortcutCatalog.globalGroup.shortcuts.map { it.keys }
        assertEquals(GlobalShortcut.entries.map { it.chord }, listed)
        assertTrue(KeyboardShortcutCatalog.groups.first() === KeyboardShortcutCatalog.globalGroup)
    }

    /** The two chords the app actually claims — spelled out here so a silent re-binding fails the build. */
    @Test
    fun theGlobalChordsAreTheDocumentedOnes() {
        assertEquals("Ctrl+Shift+Alt+A", GlobalShortcut.ToggleAway.chord)
        assertEquals("Ctrl+Shift+Alt+E", GlobalShortcut.LookAwayNow.chord)
    }

    @Test
    fun noGroupListsTheSameChordTwice() {
        KeyboardShortcutCatalog.groups.forEach { group ->
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
