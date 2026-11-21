package org.example.project.ui

import androidx.compose.foundation.layout.Box
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
    if (popup == null) row() else HoverPopupRow(popup, row)
}

/**
 * User rule 2026-11-21: *"Right-clicking in the calendar must open a search window with options such like 'add' or
 * 'edit'. Those two options open when hovered over windows to precise what to add or edit."* — **a menu's row that shows
 * a pop-up of its own when hovered** ([MenuAction.hoverPopup]), the configurations tree's category row made general: the
 * pop-up hangs from the row on [MenuSide.SUBMENU]'s sides, never over the list the row is in ([LocalCallerList]), and
 * vanishes once the pointer has left the row, it and the pop-ups opened inside it ([MenuHoverTracker]). A click on the
 * row opens it pinned — a finger's way to it too, a finger making no hover — and a press inside it pins it.
 */
@Composable
internal fun HoverPopupRow(
    popup: @Composable () -> Unit,
    row: @Composable () -> Unit,
) {
    // The list of rows the row is in (the menu's result list gives it), which the pop-up must not hide.
    val callerList = LocalCallerList.current
    var open by remember { mutableStateOf(false) }
    val around = LocalMenuHover.current
    val branch = rememberMenuHoverTracker(parent = around) { org.example.project.scheduler.platform.Diagnostics.log("[popup] hover row: closed by its tracker (pointer left)"); open = false }
    val opener = rememberPopupOpener()
    val latestOpen by rememberUpdatedState(open)
    Box(
        Modifier
            .reportsHover(branch)
            .popupOpener(opener, open)
            .onHoverEnter {
                val acts = MenuHoverTracker.hoverActs(around)
                org.example.project.scheduler.platform.Diagnostics.log("[popup] hover row: pointer in (opens=$acts, open=$open)")
                if (acts) open = true
            }
            // The row's own press handling (it picks the option) is left alone: the release is read, never consumed.
            .pointerInput(branch, opener) {
                awaitPointerEventScope {
                    while (true) {
                        if (awaitPointerEvent().type != PointerEventType.Release) continue
                        val keep = branch.clickedOpener(opener, latestOpen, toggles = false)
                        org.example.project.scheduler.platform.Diagnostics.log("[popup] hover row: released on it (was open=$latestOpen, open after=$keep)")
                        if (keep) {
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
                        onDismissRequest = { org.example.project.scheduler.platform.Diagnostics.log("[popup] hover row: its pop-up dismissed"); open = false },
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
