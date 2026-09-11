package org.example.project.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import com.sun.jna.Native
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import java.awt.GraphicsEnvironment
import java.awt.MouseInfo
import java.awt.Rectangle
import java.awt.Toolkit
import java.awt.Window as AwtWindow
import java.awt.event.WindowEvent
import java.awt.event.WindowFocusListener

/** Desktop: the AWT pointer location. Best-effort — a headless/denied query answers null, as on a phone. */
actual fun globalPointerLocation(): ScreenPoint? =
    runCatching { MouseInfo.getPointerInfo()?.location?.let { ScreenPoint(it.x, it.y) } }.getOrNull()

/**
 * Desktop: an undecorated, always-on-top window of its own at the pointer, holding the keyboard.
 *
 * Two coordinate spaces have to agree here and only one of them is checkable at runtime, so both are used:
 * the window is *created* at the anchor through [WindowPosition.Absolute] (Compose maps a window's Dp
 * position onto AWT's own units one for one, which is the same space [MouseInfo] answers in, so the first
 * frame lands in the right place and there is no flash from the middle of the screen), and then the location
 * is re-applied through AWT itself once the window exists — the authoritative call, and the one that also
 * gets it right if that mapping ever stops being one for one.
 */
@Composable
actual fun TaskPickerOverlay(
    anchor: ScreenPoint?,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    val size = DpSize(TASK_PICKER_WIDTH, TASK_PICKER_HEIGHT)
    val placed = anchor?.let { clampToScreen(it, TASK_PICKER_WIDTH.value.toInt(), TASK_PICKER_HEIGHT.value.toInt()) }
    val state = rememberWindowState(
        size = size,
        position =
            if (placed == null) {
                WindowPosition(androidx.compose.ui.Alignment.Center)
            } else {
                WindowPosition.Absolute(x = placed.x.dpUnits(), y = placed.y.dpUnits())
            },
    )
    Window(
        onCloseRequest = onDismiss,
        state = state,
        title = "Choose the task to do now",
        undecorated = true,
        resizable = false,
        alwaysOnTop = true,
        focusable = true,
    ) {
        LaunchedEffect(placed) {
            if (placed != null) window.setLocation(placed.x, placed.y)
            window.toFront()
            window.requestFocus()
            takeForeground(window)
        }
        // `popups.md`: a MENU leaves on the first press outside it. A press outside a window of our own is
        // not something the app's own dismiss root can observe — what it looks like from here is the window
        // losing the focus, which is also what covers alt-tabbing away and the screen locking.
        DisposableEffect(window) {
            val listener = object : WindowFocusListener {
                override fun windowGainedFocus(event: WindowEvent) = Unit
                override fun windowLostFocus(event: WindowEvent) = onDismiss()
            }
            window.addWindowFocusListener(listener)
            onDispose { window.removeWindowFocusListener(listener) }
        }
        content()
    }
}

/** AWT units are what a window's Dp position maps onto — see the note on [TaskPickerOverlay]. */
private fun Int.dpUnits() = androidx.compose.ui.unit.Dp(toFloat())

/**
 * The top-left corner to open a menu of [width] × [height] at, so that a pointer near the right or bottom
 * edge of its screen does not put half the menu off it — the ordinary rule for a contextual menu. Clamped
 * against the screen the pointer is actually on (a multi-monitor desktop's bounds are one plane, and the
 * second monitor's origin can be negative), falling back to the pointer itself if no device claims it.
 */
private fun clampToScreen(anchor: ScreenPoint, width: Int, height: Int): ScreenPoint {
    val area = workAreaAt(anchor) ?: return anchor
    val x = anchor.x.coerceIn(area.x, maxOf(area.x, area.x + area.width - width))
    val y = anchor.y.coerceIn(area.y, maxOf(area.y, area.y + area.height - height))
    return ScreenPoint(x, y)
}

/**
 * The **work area** of the screen the pointer is on — its bounds minus the taskbar and anything else docked
 * to an edge. Not the raw bounds: a menu clamped to those has its last rows (the title suggestions, the very
 * thing the field raises) sitting behind the taskbar.
 */
private fun workAreaAt(anchor: ScreenPoint): Rectangle? =
    runCatching {
        val configuration = GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices
            .map { it.defaultConfiguration }
            .firstOrNull { it.bounds.contains(anchor.x, anchor.y) }
            ?: return@runCatching null
        val bounds = Rectangle(configuration.bounds)
        val insets = Toolkit.getDefaultToolkit().getScreenInsets(configuration)
        bounds.x += insets.left
        bounds.y += insets.top
        bounds.width -= insets.left + insets.right
        bounds.height -= insets.top + insets.bottom
        bounds
    }.getOrNull()

private val isWindows = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

/**
 * **Take the keyboard from the application in front** — the whole point of the menu, and the one thing
 * `Window.requestFocus()` cannot be relied on for.
 *
 * Windows only lets a process call `SetForegroundWindow` when it is already the foreground process or
 * *received the last input event*, and neither is true here: the chord that opened this menu was swallowed
 * by our low-level hook, which is not the same as our window having been typed into. A refused request
 * flashes the taskbar button instead, which would leave the menu standing there unable to be typed in or
 * answered with Enter — visible, and useless.
 *
 * The way through is the documented one: attach our input queue to the foreground window's thread for the
 * length of the call, so the two threads share a focus state and the rule is satisfied, then detach. Both
 * halves are best-effort — on a non-Windows desktop, or if any of it is refused, the window is still on
 * screen and `requestFocus` may well have been enough.
 */
private fun takeForeground(window: AwtWindow) {
    if (!isWindows) return
    runCatching {
        val user32 = User32.INSTANCE
        val hwnd = WinDef.HWND(Native.getWindowPointer(window))
        val foreground = user32.GetForegroundWindow()
        val ourThread = Kernel32.INSTANCE.GetCurrentThreadId()
        val theirThread = if (foreground == null) 0 else user32.GetWindowThreadProcessId(foreground, null)
        val attach = theirThread != 0 && theirThread != ourThread
        val ours = WinDef.DWORD(ourThread.toLong())
        val theirs = WinDef.DWORD(theirThread.toLong())
        if (attach) user32.AttachThreadInput(ours, theirs, true)
        try {
            user32.BringWindowToTop(hwnd)
            user32.SetForegroundWindow(hwnd)
            user32.SetFocus(hwnd)
        } finally {
            if (attach) user32.AttachThreadInput(ours, theirs, false)
        }
    }
}
