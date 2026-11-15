package org.example.project.ui

import com.sun.jna.Native
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.Window as AwtWindow
import javax.swing.RootPaneContainer

/**
 * User rule 2026-11-15: *"I want every window to adjust, and not just to the pip but to anything above the app, when a
 * window in the app opens, it must adjust its position and size. If the user drags something over the app, it doesn't
 * adjust in live, only when the window in the app opens or stops being minimized in the system tray of the app."* —
 * **the other applications' windows standing above the app**, in AWT's units on the screen: what a menu keeps off
 * ([menuPlace]'s `avoid`) and what a window of the app moves and shrinks away from ([fitClear]).
 *
 * Above: before the app's window in the order Windows stacks top-level windows (`EnumWindows` walks it from the top),
 * Firefox's picture-in-picture included — it is always on top. Not counted: the app's own windows (its menus, the task
 * picker), a window that is not shown (hidden, minimized, or cloaked — a Store app's window Windows keeps "visible"
 * off screen), a click-through overlay (`WS_EX_TRANSPARENT`: it covers nothing the hand can reach), the taskbar and the
 * desktop, and anything of no size.
 *
 * **Asked as a window or a menu opens, never on a timer nor on a hook** (`CLAUDE.md`: no poll; the rule above: no live
 * adjustment). Where Windows is not there to ask (JNA has no `user32`), nothing is above: the seam answers empty
 * (`docs/PLATFORMS.md`).
 */
internal object WindowsAboveApp {
    private const val GWL_EXSTYLE = -20
    private const val WS_EX_TRANSPARENT = 0x00000020
    private const val DWMWA_CLOAKED = 14

    /** The desktop's and the taskbar's windows: always there, never "a window over the app". */
    private val SHELL_CLASSES = setOf("Shell_TrayWnd", "Shell_SecondaryTrayWnd", "Progman", "WorkerW")

    private interface Dwm : StdCallLibrary {
        fun DwmGetWindowAttribute(hwnd: WinDef.HWND, attribute: Int, value: IntByReference, size: Int): Int
    }

    private val user32: User32? = runCatching { User32.INSTANCE }.getOrNull()
    private val dwm: Dwm? = runCatching { Native.load("dwmapi", Dwm::class.java, W32APIOptions.DEFAULT_OPTIONS) }.getOrNull()
    private val ownPid: Long = ProcessHandle.current().pid()

    /** The windows above [app] (the app's main window, or any window it owns — its root is asked). */
    fun bounds(app: AwtWindow): List<Rectangle> {
        val user = user32 ?: return emptyList()
        val root = generateSequence(app) { it.owner }.last()
        val appHwnd = runCatching { WinDef.HWND(Native.getWindowPointer(root)) }.getOrNull() ?: return emptyList()
        val above = ArrayList<WinDef.RECT>()
        var reached = false
        runCatching {
            user.EnumWindows({ hwnd, _ ->
                if (hwnd == appHwnd) {
                    reached = true
                    return@EnumWindows false
                }
                if (covers(user, hwnd)) {
                    val rect = WinDef.RECT()
                    if (user.GetWindowRect(hwnd, rect)) above += rect
                }
                true
            }, null)
        }
        // The app's window never met: nothing can be said to be above it.
        if (!reached) return emptyList()
        return above.map { toAwt(it.left, it.top, it.right, it.bottom) }
    }

    /**
     * The windows above [app], in pixels of [app]'s content — what the windows of the app are placed in
     * ([LocalCoveringWindows]).
     */
    fun inContentOf(app: AwtWindow): List<androidx.compose.ui.geometry.Rect> {
        val pane = (app as? RootPaneContainer)?.contentPane ?: app
        val origin = runCatching { pane.locationOnScreen }.getOrNull() ?: return emptyList()
        val scale = app.graphicsConfiguration?.defaultTransform?.scaleX ?: 1.0
        return bounds(app).map {
            androidx.compose.ui.geometry.Rect(
                ((it.x - origin.x) * scale).toFloat(),
                ((it.y - origin.y) * scale).toFloat(),
                ((it.x + it.width - origin.x) * scale).toFloat(),
                ((it.y + it.height - origin.y) * scale).toFloat(),
            )
        }
    }

    private fun covers(user: User32, hwnd: WinDef.HWND): Boolean {
        if (!user.IsWindowVisible(hwnd)) return false
        if (user.GetWindowLong(hwnd, GWL_EXSTYLE) and WS_EX_TRANSPARENT != 0) return false
        val pid = IntByReference()
        user.GetWindowThreadProcessId(hwnd, pid)
        if (pid.value.toLong() == ownPid) return false
        val name = CharArray(64)
        val length = user.GetClassName(hwnd, name, name.size)
        if (String(name, 0, length.coerceAtLeast(0)) in SHELL_CLASSES) return false
        val cloaked = IntByReference()
        if (dwm?.DwmGetWindowAttribute(hwnd, DWMWA_CLOAKED, cloaked, 4) == 0 && cloaked.value != 0) return false
        val rect = WinDef.RECT()
        if (!user.GetWindowRect(hwnd, rect)) return false
        // A minimized window stands far off every screen.
        if (rect.left <= -30000 || rect.top <= -30000) return false
        return rect.right > rect.left && rect.bottom > rect.top
    }

    /**
     * A window's rectangle from Windows (the screen's pixels) in AWT's units: AWT keeps a screen's top left corner in
     * pixels and its size in its own units, so a point is brought into the screen it stands on, scaled from that corner.
     */
    private fun toAwt(left: Int, top: Int, right: Int, bottom: Int): Rectangle {
        val screens = runCatching { GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.map { it.defaultConfiguration } }
            .getOrDefault(emptyList())
        val screen = screens.firstOrNull { configuration ->
            val b = configuration.bounds
            val scale = configuration.defaultTransform.scaleX
            left >= b.x && left < b.x + b.width * scale && top >= b.y && top < b.y + b.height * scale
        }
        val scale = screen?.defaultTransform?.scaleX ?: 1.0
        val ox = screen?.bounds?.x ?: 0
        val oy = screen?.bounds?.y ?: 0
        fun x(px: Int) = ox + ((px - ox) / scale).toInt()
        fun y(px: Int) = oy + ((px - oy) / scale).toInt()
        return Rectangle(x(left), y(top), x(right) - x(left), y(bottom) - y(top))
    }
}

/** What the desktop's `main` gives as [LocalCoveringWindows]: the windows above [app], in pixels of its content. */
fun coveringWindowsOf(app: AwtWindow): List<androidx.compose.ui.geometry.Rect> = WindowsAboveApp.inContentOf(app)
