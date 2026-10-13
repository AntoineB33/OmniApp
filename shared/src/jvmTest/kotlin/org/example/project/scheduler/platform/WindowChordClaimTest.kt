package org.example.project.scheduler.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * User rule 2026-10-12: the chords mapped to a window are claimed with the app's own shortcuts
 * ([GlobalHotkeyClaimant.chordsOf]) — the one table the hook and the fallback registrations read.
 */
class WindowChordClaimTest {
    private val ctrlAltK = ShortcutBinding(ShortcutKey.K, ctrl = true, shift = false, alt = true)
    private val ctrlAltB = ShortcutBinding(ShortcutKey.B, ctrl = true, shift = false, alt = true)

    @Test
    fun the_windows_chords_follow_the_shortcuts_each_under_an_id_of_its_own() {
        val claimed = GlobalHotkeyClaimant.chordsOf(emptyMap(), setOf(ctrlAltK, ctrlAltB))
        assertEquals(GlobalShortcut.entries.toList() + listOf(null, null), claimed.map { it.first })
        // The same set always gets the same ids, whatever order it is handed in: the table is rewritten by id.
        assertEquals(listOf(ctrlAltB, ctrlAltK), claimed.takeLast(2).map { it.second })
        assertEquals(claimed, GlobalHotkeyClaimant.chordsOf(emptyMap(), setOf(ctrlAltB, ctrlAltK)))
        assertEquals(claimed.size, claimed.map { it.third }.toSet().size, "no two chords share a hot-key id")
        assertTrue(claimed.takeLast(2).all { it.third > GlobalShortcut.entries.size })
    }

    @Test
    fun a_chord_a_shortcut_holds_is_that_shortcuts_and_a_chord_under_the_floor_is_not_claimed() {
        // A shortcut moved onto a window's chord on another device, or an older row: never claimed twice.
        val moved = mapOf(GlobalShortcut.ToggleAway to ctrlAltK)
        val oneModifier = ShortcutBinding(ShortcutKey.C, ctrl = true, shift = false, alt = false)
        val claimed = GlobalHotkeyClaimant.chordsOf(moved, setOf(ctrlAltK, ctrlAltB, oneModifier, GlobalShortcut.SwitchTask.defaultBinding))
        assertEquals(listOf(ctrlAltB), claimed.filter { it.first == null }.map { it.second })
        assertEquals(ctrlAltK, claimed.single { it.first == GlobalShortcut.ToggleAway }.second)
    }
}
