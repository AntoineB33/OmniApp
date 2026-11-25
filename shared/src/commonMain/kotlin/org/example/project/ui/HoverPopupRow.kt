package org.example.project.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.window.PopupProperties

/** [row] as a [HoverPopupRow] showing [popup], or as it is where [popup] is null. */
@Composable
internal fun WithHoverPopup(popup: (@Composable () -> Unit)?, row: @Composable () -> Unit) {
    if (popup == null) row() else HoverPopupRow(popup, row = row)
}

/**
 * User rule 2026-11-21: *"When a row shows a window when hovered over, there must be an arrow indicating it."* — **the
 * mark at the end of every row that opens a pop-up when hovered**: a configurations tree's category ([ConfigurationTree])
 * and a menu entry with a pop-up of its own ([HoverPopupRow]). One drawing, so the two read as the same promise.
 */
@Composable
internal fun HoverPopupArrow(modifier: Modifier = Modifier) {
    Text("◂", modifier = modifier, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/**
 * User rule 2026-11-21: *"Right-clicking in the calendar must open a search window with options such like 'add' or
 * 'edit'. Those two options open when hovered over windows to precise what to add or edit."* — **a menu's row that shows
 * a pop-up of its own when hovered** ([MenuAction.hoverPopup]), the configurations tree's category row made general: the
 * pop-up hangs from the row on [MenuSide.SUBMENU]'s sides, never over the list the row is in ([LocalCallerList]), and
 * vanishes once the pointer has left the row, it and the pop-ups opened inside it ([MenuHoverTracker]). A click on the
 * row opens it pinned — a finger's way to it too, a finger making no hover — and a press inside it pins it. The row
 * draws [HoverPopupArrow] before its name (the Search window's option rows: their `leading` mark).
 */
@Composable
internal fun HoverPopupRow(
    popup: @Composable () -> Unit,
    /** User rule 2026-11-25: raised, the pop-up is shown pinned — a blank child row's double-click or Enter. */
    ask: Int = 0,
    row: @Composable () -> Unit,
) {
    // The list of rows the row is in (the menu's result list gives it), which the pop-up must not hide.
    val callerList = LocalCallerList.current
    var open by remember { mutableStateOf(false) }
    val around = LocalMenuHover.current
    val branch = rememberMenuHoverTracker(parent = around) { open = false }
    val opener = rememberPopupOpener()
    val latestOpen by rememberUpdatedState(open)
    androidx.compose.runtime.LaunchedEffect(ask) {
        if (ask > 0) {
            open = true
            branch.pin()
        }
    }
    Box(
        Modifier
            .reportsHover(branch)
            .popupOpener(opener, open)
            .onHoverEnter { if (MenuHoverTracker.hoverActs(around)) open = true }
            // The row's own press handling (it picks the option) is left alone: the release is read, never consumed.
            .pointerInput(branch, opener) {
                awaitPointerEventScope {
                    while (true) {
                        if (awaitPointerEvent().type != PointerEventType.Release) continue
                        if (branch.clickedOpener(opener, latestOpen, toggles = false)) {
                            open = true
                            branch.pin()
                        }
                    }
                }
            },
    ) {
        row()
        if (open) {
            androidx.compose.runtime.CompositionLocalProvider(
                LocalMenuSides provides MenuSide.SUBMENU,
                LocalCallerList provides callerList,
                LocalMenuHover provides branch,
                LocalPopupOpener provides opener,
            ) {
                androidx.compose.runtime.CompositionLocalProvider(LocalBareMenu provides true) {
                    AppDropdownMenu(
                        expanded = true,
                        onDismissRequest = { open = false },
                        // The whole pop-up is the branch, wherever the platform's menu does not report its surface itself.
                        modifier = Modifier.reportsHover(branch),
                        properties = PopupProperties(focusable = true),
                    ) {
                        // It is a Search window: it wears a window's surface itself.
                        androidx.compose.runtime.CompositionLocalProvider(LocalPopupChrome provides PopupChrome()) {
                            popup()
                        }
                    }
                }
            }
        }
    }
}
