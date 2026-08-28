package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.example.project.scheduler.platform.GlobalShortcut
import org.example.project.scheduler.platform.ShortcutBinding
import org.example.project.scheduler.platform.ShortcutKey
import org.example.project.scheduler.platform.GlobalShortcutBindings
import org.example.project.ui.ControlChords
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

    /** The chords the app ships with — spelled out here so a silent change of default fails the build. */
    @Test
    fun theGlobalChordsAreTheDocumentedOnes() {
        assertEquals("Ctrl+Shift+Alt+A", GlobalShortcut.ToggleAway.defaultChord)
        assertEquals("Ctrl+Shift+Alt+E", GlobalShortcut.LookAwayNow.defaultChord)
        assertEquals("Ctrl+Shift+Alt+Z", GlobalShortcut.SwitchTask.defaultChord)
        assertEquals("Ctrl+Shift+Alt+N", GlobalShortcut.ToggleNotifications.defaultChord)
    }

    /**
     * Every shipped default must satisfy the rules the reducer enforces on a REBINDING — two modifiers at
     * least, and no two shortcuts on one chord. A default that broke either would ship a chord the window
     * refuses to let the user set, and (for a collision) two actions racing for one press.
     */
    @Test
    fun theShippedDefaultsObeyTheBindingRules() {
        GlobalShortcut.entries.forEach { shortcut ->
            assertEquals(
                null,
                GlobalShortcutBindings.rejection(emptyMap(), shortcut, shortcut.defaultBinding),
                "the shipped chord for \"${shortcut.action}\" is one the app would refuse",
            )
        }
    }

    /**
     * PRD §7 hover bubble: a control that duplicates a chord names it on hover, and the window lists the same
     * chord a few lines below. For the system-wide ones both sides go through
     * [GlobalShortcutBindings.chordOf], so a rebinding cannot leave one of them printing the shipped chord.
     */
    @Test
    fun aButtonsBubbleAndTheWindowPrintOneChord() {
        val rebound = ShortcutBinding(ShortcutKey.J, ctrl = true, shift = true, alt = false)
        val bindings = mapOf(GlobalShortcut.LookAwayNow to rebound)
        val group = KeyboardShortcutCatalog.globalGroup(bindings)

        GlobalShortcut.entries.forEachIndexed { index, shortcut ->
            assertEquals(
                group.shortcuts[index].keys,
                GlobalShortcutBindings.chordOf(bindings, shortcut),
                "the bubble on the \"${shortcut.action}\" button would disagree with the window",
            )
        }
        assertEquals("Ctrl+Shift+J", GlobalShortcutBindings.chordOf(bindings, GlobalShortcut.LookAwayNow))
    }

    /**
     * The fixed chords a control duplicates ([ControlChords] — the find bar's arrows and ✕, the deep-copy
     * window's accept) are spelled once and read by both the button and the catalogue. A constant that is no
     * longer listed means the window and a hover bubble have started describing two different chords.
     */
    @Test
    fun everyControlChordIsListedInTheWindow() {
        val listed = KeyboardShortcutCatalog.fixedGroups.flatMap { group -> group.shortcuts.map { it.keys } }
        listOf(
            ControlChords.ENTER,
            ControlChords.SHIFT_ENTER,
            ControlChords.ESCAPE,
            ControlChords.ENTER_IN_REPLACE_FIELD,
        ).forEach { chord -> assertTrue(chord in listed, "\"$chord\" is hinted on a button but never listed") }
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
