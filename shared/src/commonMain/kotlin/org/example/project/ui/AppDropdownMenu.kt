package org.example.project.ui

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties

/**
 * **Every menu of the app** — a right-click contextual menu, a drop-down — and the ONE way to open one
 * (`docs/invariants/popups.md`): material's `DropdownMenu`, with its parameters and its anchoring (the menu hangs
 * from the layout it is declared in, moved by [offset]).
 *
 * On the desktop a menu is an OS window of its own, owned by the app's window (`AppDropdownMenu.jvm.kt`): placed and sized
 * inside the app window's edges (user rule 2026-11-16, *"No window must appear outside the app"*), and it stands above the app and under whatever stands above the app — user rule
 * 2026-11-15, *"I want the search windows to never appear above what is supposed to be above the app (like the
 * Firefox's picture-in-picture)"*, which took back the 2026-10-13 rule that menus be over it too. Everywhere else it
 * IS material's menu.
 *
 * Never call material's `DropdownMenu` directly: a menu that does is bound by the app window's edges.
 */
@Composable
expect fun AppDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset(0.dp, 0.dp),
    properties: PopupProperties = PopupProperties(focusable = true),
    /**
     * Opens the menu AT THE POINTER, not on the layout it is declared in — a right-click menu of a row that is much
     * wider than the menu, where "under the row, at its left" is far from the hand ([LocalRowMenusAtPointer]). The
     * desktop's alone: where there is no pointer (a finger), the menu hangs from its layout as ever.
     */
    atPointer: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
)

/**
 * User rule 2026-10-13 (*"for the elements in the result list of the Search window, the menu still appears at the
 * left of the element instead of the mouse position"*): **whether the right-click menu of a ROW opens at the
 * pointer**. True inside the Search window, whose rows run the whole width of a list — a result's, an added
 * element's, and the task rows it borrows from the task tree. False elsewhere: a cell of the tree is hardly wider than
 * its menu, which stays under the cell.
 */
val LocalRowMenusAtPointer = androidx.compose.runtime.compositionLocalOf { false }
