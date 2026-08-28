package org.example.project.ui

import org.example.project.scheduler.platform.GlobalShortcut
import org.example.project.scheduler.platform.GlobalShortcutBindings
import org.example.project.scheduler.platform.ShortcutBinding

/** One line of the keyboard-shortcuts window: the chord, and what striking it does. */
data class KeyboardShortcut(val keys: String, val description: String)

/**
 * A titled block of [KeyboardShortcut]s — the surface they belong to (the task tree, the calendar, …), plus
 * the one sentence saying **when** they apply, since almost every chord below is scoped to whichever surface
 * currently holds the keyboard.
 */
data class KeyboardShortcutGroup(
    val title: String,
    val note: String? = null,
    val shortcuts: List<KeyboardShortcut>,
)

/**
 * PRD §7: the catalogue behind the lateral menu's **Keyboard shortcuts** window.
 *
 * It is written down rather than derived, because the handlers it describes are spread across
 * `TaskSchedulerScreen`, `CalendarUi` and `DefaultSubtreeWindow` as ordinary `onPreviewKeyEvent` branches and
 * there is nothing to read them off. The one part that *is* derived is the system-wide block: those chords
 * come straight from [GlobalShortcut] resolved against the account's own bindings, which is also exactly what
 * the platform actual registers with the OS — so the window can never advertise a chord the app does not
 * actually claim, and it is the only block the user can rebind.
 *
 * Keep a new chord and its entry here in the same change; `KeyboardShortcutsCatalogTest` only pins the half
 * that can be checked mechanically (that every [GlobalShortcut] is listed, and that nothing is listed twice
 * within a group).
 */
/**
 * The chord spellings a **control** duplicates: the find bar's arrows and ✕, the deep-copy window's accept.
 * Each of those buttons names its chord in a hover bubble ([ShortcutHint]), and the keyboard-shortcuts window
 * lists the same chord a few lines below — so the spelling exists once and both read it, exactly as the
 * system-wide block reads its own off [GlobalShortcutBindings].
 *
 * Only the fixed, per-surface chords belong here. A rebindable one must never be a constant: what the app is
 * listening for is the account's binding, not what it ships with.
 */
object ControlChords {
    const val ENTER = "Enter"
    const val SHIFT_ENTER = "Shift + Enter"
    const val ESCAPE = "Escape"

    /** The find bar's replacement: Enter *while the replace field holds the keyboard*, not the bar's Enter. */
    const val ENTER_IN_REPLACE_FIELD = "Enter (in the replace field)"
}

object KeyboardShortcutCatalog {

    /**
     * The system-wide chords, listed straight off [GlobalShortcut] so the two can never disagree — at
     * whatever chord the account has each of them bound to ([bindings], the user's overrides). It is a
     * function and not a `val` for exactly that reason: the system-wide ones are the only entries in the window
     * that are not a constant, and a stored list would print the shipped chord next to a claim on a different
     * one.
     */
    fun globalGroup(bindings: Map<GlobalShortcut, ShortcutBinding> = emptyMap()): KeyboardShortcutGroup =
        KeyboardShortcutGroup(
            title = "System-wide",
            note = "Claimed from the operating system, so they work while OmniApp is not the focused window. " +
                "Desktop only. Each press posts a \"Shortcut received\" notification naming the chord, so a " +
                "press that never reached the app is distinguishable from one that did. These can be " +
                "rebound below; the rest of this window is fixed.",
            shortcuts =
                GlobalShortcut.entries.map {
                    KeyboardShortcut(GlobalShortcutBindings.chordOf(bindings, it), it.action)
                },
        )

    /** Every block the window prints, the system-wide one first and bound to the account's own chords. */
    fun groups(bindings: Map<GlobalShortcut, ShortcutBinding> = emptyMap()): List<KeyboardShortcutGroup> =
        listOf(globalGroup(bindings)) + fixedGroups

    /** Everything below the system-wide block: prose, and the same on every account. */
    val fixedGroups: List<KeyboardShortcutGroup> =
        listOf(
            KeyboardShortcutGroup(
                title = "Task tree",
                note = "While the tree holds the keyboard and no cell is being edited.",
                shortcuts = listOf(
                    KeyboardShortcut("↑ / ↓", "Move the selection to the previous / next cell"),
                    KeyboardShortcut("← / →", "Same as ↑ / ↓"),
                    KeyboardShortcut("Shift + arrow", "Extend the selection"),
                    KeyboardShortcut("Tab", "Step into the first child (expanding it if needed)"),
                    KeyboardShortcut("Shift + Tab", "Step back to the previous cell"),
                    KeyboardShortcut("Enter", "Edit the selected cell — or, with several selected, cycle which one is main"),
                    KeyboardShortcut("Shift + Enter", "Cycle the main selection backwards"),
                    KeyboardShortcut("Any character", "Start editing the selected cell with that character"),
                    KeyboardShortcut("Backspace / Delete", "Empty the selected cells (a blank title is what deletes)"),
                    KeyboardShortcut("Ctrl + A", "Select every visible cell"),
                    KeyboardShortcut(
                        "Ctrl + F",
                        "Open the find & replace bar — pressed again, it re-selects what is in the field",
                    ),
                    KeyboardShortcut(
                        "Ctrl + C / Ctrl + X",
                        "Copy the selected sub-trees whole, however deep — Ctrl + X also empties them",
                    ),
                    KeyboardShortcut(
                        "Ctrl + V",
                        "Paste — the copied cell and its sub-tree replace the selected cell, keeping their task ids",
                    ),
                ),
            ),
            KeyboardShortcutGroup(
                title = "Editing a cell",
                note = "While a cell is in Edit Mode. The default sub-tree window uses the same set.",
                shortcuts = listOf(
                    KeyboardShortcut("Enter", "Commit and move down"),
                    KeyboardShortcut("Shift + Enter", "Commit and move up"),
                    KeyboardShortcut("Tab", "Commit and step into the child"),
                    KeyboardShortcut("Shift + Tab", "Commit and move up"),
                    KeyboardShortcut("Ctrl + Enter", "Break the line inside the cell"),
                    KeyboardShortcut("Escape", "Cancel — every cell the session touched reverts"),
                ),
            ),
            KeyboardShortcutGroup(
                title = "Find & replace bar",
                note = "While the tree's Ctrl + F bar holds the keyboard. It searches the whole tree, " +
                    "collapsed rows included, and replacing renames the task — so every cell pointing at " +
                    "it follows.",
                shortcuts = listOf(
                    KeyboardShortcut(ControlChords.ENTER, "Go to the next match"),
                    KeyboardShortcut(ControlChords.SHIFT_ENTER, "Go to the previous match"),
                    KeyboardShortcut(ControlChords.ENTER_IN_REPLACE_FIELD, "Replace the current match"),
                    KeyboardShortcut(ControlChords.ESCAPE, "Close the bar and hand the keyboard back to the tree"),
                ),
            ),
            KeyboardShortcutGroup(
                title = "History",
                note = "Undo and redo are routed to whichever surface has the focus.",
                shortcuts = listOf(
                    KeyboardShortcut("Ctrl + Z / Ctrl + Y", "Undo / redo"),
                    KeyboardShortcut("Alt + ← / Alt + →", "Undo / redo the selection alone"),
                ),
            ),
            KeyboardShortcutGroup(
                title = "Calendar",
                note = "While the calendar window is the focused surface.",
                shortcuts = listOf(
                    KeyboardShortcut("O", "Toggle overlapping blocks side by side"),
                    KeyboardShortcut("Ctrl + + / Ctrl + −", "Zoom in / out"),
                    KeyboardShortcut("Ctrl + 0", "Reset the zoom"),
                    KeyboardShortcut("Ctrl + wheel", "Zoom toward the pointer"),
                ),
            ),
            KeyboardShortcutGroup(
                title = "Deep copy window",
                note = "While the task cell's \"deep copy\" window is open (its depth and its three switches).",
                shortcuts = listOf(
                    KeyboardShortcut(ControlChords.ENTER, "Copy down to the chosen depth and close the window"),
                ),
            ),
            KeyboardShortcutGroup(
                title = "Task-tree name field",
                note = "While the name box above the tree holds the keyboard.",
                shortcuts = listOf(
                    KeyboardShortcut("Enter", "Commit the typed name and hand focus back to the tree"),
                    KeyboardShortcut("Escape", "Restore the selected tree's name"),
                ),
            ),
        )
}
