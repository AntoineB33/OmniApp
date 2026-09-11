package org.example.project.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import kotlinx.coroutines.delay
import org.example.project.scheduler.platform.Diagnostics

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
        // False until Windows has actually handed this window the foreground — see [takeForegroundWhenShown]
        // and the listener below, which must not read a focus lost on the way THERE as a press outside.
        var holdsForeground by remember { mutableStateOf(false) }
        LaunchedEffect(placed) {
            if (placed != null) window.setLocation(placed.x, placed.y)
            holdsForeground = takeForegroundWhenShown(window)
            if (!holdsForeground) {
                Diagnostics.log(
                    "task picker: the foreground was refused — dismissing the menu, which could not have " +
                        "been typed into, answered with Enter, or closed",
                )
                onDismiss()
            }
        }
        // `popups.md`: a MENU leaves on the first press outside it. A press outside a window of our own is
        // not something the app's own dismiss root can observe — what it looks like from here is the window
        // losing the focus, which is also what covers alt-tabbing away and the screen locking.
        //
        // Only once the menu HAS the foreground, though. Taking it from the application in front is not one
        // atomic act — the window is shown, asked for, and activated over several frames, and the focus can
        // legitimately cross back in the middle of that. Read as a press outside, one of those crossings
        // closed the menu ~300 ms after it opened.
        DisposableEffect(window) {
            val listener = object : WindowFocusListener {
                override fun windowGainedFocus(event: WindowEvent) = Unit
                override fun windowLostFocus(event: WindowEvent) {
                    if (holdsForeground) onDismiss()
                }
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

/** How often [takeForegroundWhenShown] looks at the window while it is coming up. */
private const val FOREGROUND_POLL_MILLIS = 25L

/** How long Compose is given to actually put the window on screen (it does not do it synchronously). */
private const val FOREGROUND_SHOW_ATTEMPTS = 80

/** How long the activation is then given to land, once the window is up. */
private const val FOREGROUND_SETTLE_ATTEMPTS = 40

/** Re-ask for the foreground only this often — see the note on fighting a request already in flight. */
private const val FOREGROUND_REASK_EVERY = 8

/** Two readings in a row, not one: activation is asynchronous and can bounce straight back. */
private const val FOREGROUND_CONFIRMATIONS = 2

/**
 * **Take the keyboard from the application in front, and do not believe it until Windows agrees** — true
 * once it does.
 *
 * Asking once and assuming it worked is what the menu used to do, and it was half of the "the menu will not
 * go away" bug. Both halves of the request can be dropped without saying so:
 *
 *  - Compose does not show this window from the composition that builds it — `AwtWindow` sets `isVisible`
 *    from a coroutine of its own, posted to the UI thread — so the first frame of the menu's content runs
 *    while the window may still be off screen, and `requestFocus()`/`SetForegroundWindow` on a window that
 *    is not showing yet do nothing at all.
 *  - `SetForegroundWindow` is refused outright unless the caller is already the foreground process or
 *    *received the last input event*, and neither is true here: the chord that opened the menu was swallowed
 *    by our low-level hook, which is not the same as our window having been typed into.
 *
 * What the user then meets is worse than a menu that opens behind: **AWT believes it is focused while
 * Windows never made it foreground.** The real keystrokes keep going to the other application, so Escape
 * does nothing, and no deactivation is ever delivered for an activation that never happened, so
 * `windowLostFocus` — the only way a window of our own can see a press outside it (`popups.md`) — never
 * fires either. The menu is left with no way out at all until a click on it hands it the real focus.
 *
 * So: wait for the window to be **showing**, then ask, then **check against `GetForegroundWindow`**. Two
 * things the first attempt at this got wrong, both found by watching the real window from outside the
 * process rather than by reading:
 *
 *  - **the check cannot follow the request immediately.** Activation is asynchronous, so the reading taken
 *    straight after `SetForegroundWindow` still names the old foreground, and a loop that believes it
 *    re-asks at once — forty requests in a second, each with its own `AttachThreadInput` either side.
 *  - **that thrashing loses the focus it just won.** Re-asked every poll, the menu took the foreground and
 *    handed it straight back, and the `windowLostFocus` listener read the hand-back as a press outside and
 *    closed the menu ~300 ms after it opened.
 *
 * Hence: re-ask at most every [FOREGROUND_REASK_EVERY] polls, and take [FOREGROUND_CONFIRMATIONS] readings
 * in a row before believing it. None of this is a poll in the sense `CLAUDE.md` forbids — it re-asks nothing
 * of the server or the plan, it is bounded, and it stops the moment the focus is ours.
 */
private suspend fun takeForegroundWhenShown(window: AwtWindow): Boolean {
    repeat(FOREGROUND_SHOW_ATTEMPTS) {
        // There is nothing to ask for until the window is actually on screen — see above.
        if (window.isShowing) return settleForeground(window)
        delay(FOREGROUND_POLL_MILLIS)
    }
    return false
}

/** [takeForegroundWhenShown] once the window is up: ask sparingly, and believe only a settled answer. */
private suspend fun settleForeground(window: AwtWindow): Boolean {
    var confirmations = 0
    repeat(FOREGROUND_SETTLE_ATTEMPTS) { attempt ->
        if (hasForeground(window)) {
            if (++confirmations >= FOREGROUND_CONFIRMATIONS) return true
        } else {
            confirmations = 0
            if (attempt % FOREGROUND_REASK_EVERY == 0) {
                window.toFront()
                window.requestFocus()
                requestForeground(window)
            }
        }
        delay(FOREGROUND_POLL_MILLIS)
    }
    return false
}

/**
 * Does **Windows** have this window in front? Not "does AWT think it is focused" — the two disagreeing is
 * the bug this whole file is about. Off Windows there is nothing else to ask, so AWT's answer is the answer.
 */
private fun hasForeground(window: AwtWindow): Boolean {
    if (!isWindows) return window.isFocused
    return runCatching {
        User32.INSTANCE.GetForegroundWindow() == WinDef.HWND(Native.getWindowPointer(window))
    }.getOrDefault(false)
}

/**
 * One request for the foreground. Says nothing about whether it was granted — [hasForeground] answers that,
 * and only after the activation has had time to land.
 *
 * The way past the refusal is the documented one: attach our input queue to the foreground window's thread
 * for the length of the call, so the two share a focus state and the rule is satisfied, then detach. Both
 * the thread that is calling and the thread that owns the window are attached — on Windows an AWT window is
 * pumped by the toolkit thread, not by the one this runs on, so attaching only the caller leaves the
 * window's own queue out of the shared state. Best-effort throughout: off Windows, `requestFocus` is all
 * there is.
 */
private fun requestForeground(window: AwtWindow) {
    if (!isWindows) return
    runCatching {
        val user32 = User32.INSTANCE
        val hwnd = WinDef.HWND(Native.getWindowPointer(window))
        val foreground = user32.GetForegroundWindow()
        val theirThread = if (foreground == null) 0 else user32.GetWindowThreadProcessId(foreground, null)
        val callingThread = Kernel32.INSTANCE.GetCurrentThreadId()
        val windowThread = user32.GetWindowThreadProcessId(hwnd, null)
        val theirs = WinDef.DWORD(theirThread.toLong())
        val attached =
            if (theirThread == 0) {
                emptyList()
            } else {
                listOf(callingThread, windowThread)
                    .distinct()
                    .filter { it != 0 && it != theirThread }
                    .filter { user32.AttachThreadInput(WinDef.DWORD(it.toLong()), theirs, true) }
            }
        try {
            user32.ShowWindow(hwnd, SW_SHOW)
            user32.BringWindowToTop(hwnd)
            user32.SetForegroundWindow(hwnd)
            user32.SetFocus(hwnd)
        } finally {
            attached.forEach { user32.AttachThreadInput(WinDef.DWORD(it.toLong()), theirs, false) }
        }
    }
}

/** `ShowWindow(SW_SHOW)` — show the window at its current size and position, activating it. */
private const val SW_SHOW = 5
