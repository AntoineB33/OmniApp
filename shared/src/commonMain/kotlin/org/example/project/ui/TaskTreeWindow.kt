package org.example.project.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp

/** The task tree window's frame id — also its `FloatingWindow` name in `App`. */
const val TASK_TREE_WINDOW_ID: String = "TaskTree"

/**
 * PRD §4: the account's **task tree, as a window** — the lateral-menu window every other one used to float
 * over. It wears the same frame as all of them and takes its turn in the one stacking order; it opens
 * **maximized** the first time (after that, as it was left: [WindowChromeMemory]).
 *
 * It does not claim the keyboard the way a dialog-like window does: the tree reads its own keys, and whether it
 * may is the tree's rule (`TaskTreeView`'s `keyboardOwned`). [content] is handed whether the tree may have
 * the keyboard at all — never while the window is reduced to the bar, where a keystroke would rename a cell
 * the user cannot see.
 *
 * Not duplicable: the tree's selection, edit session and scroll are `SchedulerState` view state, so a copy
 * would be a mirror, not a window of its own (`docs/invariants/popups.md`).
 */
@Composable
fun TaskTreeWindow(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    initialOffset: Offset = Offset.Zero,
    initialSize: Size = Size.Zero,
    onGeometryChange: (Offset, Size) -> Unit = { _, _ -> },
    onRaise: () -> Unit = {},
    content: @Composable (keyboardEnabled: Boolean) -> Unit,
) {
    val frame =
        rememberWindowFrameState(
            TASK_TREE_WINDOW_ID,
            initialOffset,
            initialSize,
            defaultChrome = WindowChrome(WindowFill.Both, minimized = false),
        )
    AppWindowFrame(
        title = "Task tree",
        state = frame,
        onClose = onDismiss,
        defaultWidth = 900.dp,
        defaultHeight = 640.dp,
        modifier = modifier,
        onRaise = onRaise,
        onGeometryChange = onGeometryChange,
    ) {
        Box(Modifier.fillMaxWidth().weight(1f)) { content(!frame.minimized) }
    }
}
