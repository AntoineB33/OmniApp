package org.example.project.ui

import org.example.project.scheduler.platform.GlobalShortcut

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
 * come straight from [GlobalShortcut], which is also what the platform actual registers with the OS — so the
 * window can never advertise a chord the app does not actually claim.
 *
 * Keep a new chord and its entry here in the same change; `KeyboardShortcutsCatalogTest` only pins the half
 * that can be checked mechanically (that every [GlobalShortcut] is listed, and that nothing is listed twice
 * within a group).
 */
object KeyboardShortcutCatalog {

    /** The system-wide chords, listed straight off [GlobalShortcut] so the two can never disagree. */
    val globalGroup: KeyboardShortcutGroup =
        KeyboardShortcutGroup(
            title = "System-wide",
            note = "Claimed from the operating system, so they work while OmniApp is not the focused window. " +
                "Desktop only.",
            shortcuts = GlobalShortcut.entries.map { KeyboardShortcut(it.chord, it.action) },
        )

    val groups: List<KeyboardShortcutGroup> =
        listOf(
            globalGroup,
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
                    KeyboardShortcut("Enter", "Go to the next match"),
                    KeyboardShortcut("Shift + Enter", "Go to the previous match"),
                    KeyboardShortcut("Enter (in the replace field)", "Replace the current match"),
                    KeyboardShortcut("Escape", "Close the bar and hand the keyboard back to the tree"),
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
                    KeyboardShortcut("Enter", "Copy down to the chosen depth and close the window"),
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
