package org.example.project.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A point on the **whole desktop**, in the OS's own screen coordinates — not in any window's space, and not
 * in Compose's. The only thing the app ever asks it of is where the pointer was when a system-wide chord was
 * struck, which is by definition a moment no window of ours is under it.
 */
data class ScreenPoint(val x: Int, val y: Int)

/** The size of the task-picker menu. Fixed: it is a menu, not a window, and nothing resizes it. */
val TASK_PICKER_WIDTH: Dp = 460.dp

/** See [TASK_PICKER_WIDTH]. Tall enough for a useful stretch of the list plus the field under it. */
val TASK_PICKER_HEIGHT: Dp = 520.dp

/**
 * Where the pointer is right now, or null where the platform has no pointer (phones) or will not say.
 *
 * Read at the instant the chord arrives, never when the menu is composed: the two are a frame or more apart
 * and the user's hand does not stop moving in between.
 */
expect fun globalPointerLocation(): ScreenPoint?

/**
 * PRD §7 the **task picker** ([org.example.project.scheduler.platform.GlobalShortcut.PickTask]): put [content]
 * on screen at [anchor], over every other application, with the keyboard.
 *
 * **This is the one surface of the app that is not drawn inside the app** (`docs/invariants/popups.md`), and
 * it has to be: the chord that opens it is struck precisely while OmniApp is *not* the focused window, so a
 * menu drawn inside our own window would appear behind whatever the user is looking at, nowhere near their
 * pointer, and could not be typed into. So it is an OS window of its own — undecorated, always on top,
 * focusable — and it **takes the keyboard** from the application in front. That is the cost of the feature,
 * not an accident of it: the menu exists to be typed in and answered with Enter.
 *
 * It is a **menu** in the sense `popups.md` fixes, not a window: it carries no frame, nothing reduces or
 * maximizes it, and it leaves on the first press outside it — which, for a window of its own, is the moment
 * it loses the focus. Every way out (Escape, a pick, a press elsewhere) goes through [onDismiss], and
 * [onDismiss] may be called more than once for one dismissal, so it must be idempotent.
 *
 * **Desktop-only**, exactly like the claim that opens it
 * ([org.example.project.scheduler.platform.installGlobalHotkeys]): on Android/iOS/web there is no system-wide
 * chord to strike and no pointer to put a menu at, so the actual is inert and nothing ever asks for it.
 */
@Composable
expect fun TaskPickerOverlay(
    anchor: ScreenPoint?,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
)
