package org.example.project.ui

import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.ptr.IntByReference
import java.awt.EventQueue
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.util.concurrent.atomic.AtomicBoolean

/**
 * User rule 2026-11-15: *"For the Windows desktop app, is it possible to also include the picture-in-picture window of
 * Firefox? I'd like the pop-up windows to avoid hiding the pip as the user moves the app or the pip."* — **the other
 * applications' windows a menu keeps off** ([menuSpot]'s `avoid`), and the one event that says one of them moved.
 *
 * What a picture-in-picture window is, as Windows tells it: a window of `firefox.exe` that is visible, always on top
 * (`WS_EX_TOPMOST`: Firefox's own windows are not) and of a video's size — not one of its menus or tooltips, which are
 * topmost too but of a drop-shadow class, and short-lived.
 *
 * **Asked as a menu is placed, never on a timer** (`CLAUDE.md`: no poll). Its moves are heard through a WinEvent hook
 * (`EVENT_OBJECT_LOCATIONCHANGE`, out of context) on a thread of its own, set only while a menu is open and a
 * picture-in-picture stands, filtered to Firefox's process: a drag of the video, or the video resized, places the open
 * menus again ([onMoved]).
 *
 * Where Windows is not there to ask (JNA has no `user32`), there is nothing to avoid: the seam answers empty — no
 * question of which build this is (`docs/PLATFORMS.md`).
 */
internal object PictureInPictureWindows {
    private const val GWL_EXSTYLE = -20
    private const val WS_EX_TOPMOST = 0x00000008
    private const val PROCESS_QUERY_LIMITED_INFORMATION = 0x1000
    private const val EVENT_OBJECT_LOCATIONCHANGE = 0x800B
    private const val EVENT_OBJECT_HIDE = 0x8003
    private const val WINEVENT_OUTOFCONTEXT = 0x0000
    private const val OBJID_WINDOW = 0
    private const val WM_QUIT = 0x0012

    /** The smallest a video window is; Firefox's topmost menus and tooltips are smaller on one side at least. */
    private const val MIN_SIDE_PX = 120

    private val user32: User32? = runCatching { User32.INSTANCE }.getOrNull()
    private val kernel32: Kernel32? = runCatching { Kernel32.INSTANCE }.getOrNull()

    /** Whether a process is Firefox, by its id: asked once per process. */
    private val firefox = HashMap<Int, Boolean>()

    /** The picture-in-picture windows on screen now, each with its process id. */
    private fun find(): List<Pair<WinDef.HWND, Int>> {
        val user = user32 ?: return emptyList()
        val found = ArrayList<Pair<WinDef.HWND, Int>>()
        runCatching {
            user.EnumWindows({ hwnd, _ ->
                if (user.IsWindowVisible(hwnd) && user.GetWindowLong(hwnd, GWL_EXSTYLE) and WS_EX_TOPMOST != 0) {
                    val pid = IntByReference()
                    user.GetWindowThreadProcessId(hwnd, pid)
                    if (isFirefox(pid.value) && isVideoWindow(user, hwnd)) found += hwnd to pid.value
                }
                true
            }, null)
        }
        return found
    }

    private fun isVideoWindow(user: User32, hwnd: WinDef.HWND): Boolean {
        val name = CharArray(256)
        val length = user.GetClassName(hwnd, name, name.size)
        if (String(name, 0, length.coerceAtLeast(0)).contains("DropShadow", ignoreCase = true)) return false
        val rect = WinDef.RECT()
        if (!user.GetWindowRect(hwnd, rect)) return false
        // A minimized window stands far off every screen.
        if (rect.left <= -30000 || rect.top <= -30000) return false
        return rect.right - rect.left >= MIN_SIDE_PX && rect.bottom - rect.top >= MIN_SIDE_PX
    }

    private fun isFirefox(pid: Int): Boolean =
        firefox.getOrPut(pid) {
            val kernel = kernel32 ?: return@getOrPut false
            val process = kernel.OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, false, pid) ?: return@getOrPut false
            try {
                val path = CharArray(1024)
                val size = IntByReference(path.size)
                kernel.QueryFullProcessImageName(process, 0, path, size) &&
                    String(path, 0, size.value).substringAfterLast('\\').equals("firefox.exe", ignoreCase = true)
            } finally {
                kernel.CloseHandle(process)
            }
        }

    /**
     * **The picture-in-picture windows, in AWT's units on the screen** — what a menu's place is said in. Windows gives
     * a window's rectangle in the screen's pixels; AWT keeps a screen's top left corner in pixels and its size in its
     * own units, so a point is brought into the screen it stands on, scaled from that corner.
     */
    fun bounds(): List<Rectangle> {
        val user = user32 ?: return emptyList()
        return find().mapNotNull { (hwnd, _) ->
            val rect = WinDef.RECT()
            if (!user.GetWindowRect(hwnd, rect)) null else toAwt(rect.left, rect.top, rect.right, rect.bottom)
        }
    }

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

    // ---- What is told when one moves --------------------------------------------------------------------------------

    private val listeners = LinkedHashSet<() -> Unit>()
    private var hook: Thread? = null
    private var hookThreadId = 0

    /** A placement already asked of the event queue: a drag's many moves are one placement per turn of the queue. */
    private val pending = AtomicBoolean(false)

    /**
     * [placeAgain] is called on the event queue each time a picture-in-picture window moves or goes, until the returned
     * function is called. Only while a picture-in-picture stands as it is asked: one that opens later is avoided at
     * the next placement of a menu, not heard moving.
     */
    fun onMoved(placeAgain: () -> Unit): () -> Unit {
        listeners += placeAgain
        if (hook == null) find().firstOrNull()?.let { (_, pid) -> startHook(pid) }
        return {
            listeners -= placeAgain
            if (listeners.isEmpty()) stopHook()
        }
    }

    private fun startHook(pid: Int) {
        val user = user32 ?: return
        val started = java.util.concurrent.CountDownLatch(1)
        hook = Thread({
            hookThreadId = kernel32?.GetCurrentThreadId() ?: 0
            // Kept referenced by this frame for as long as the hook lives: JNA frees a callback nobody holds.
            val callback = WinUser.WinEventProc { _, event, _, idObject, idChild, _, _ ->
                if (idObject.toInt() == OBJID_WINDOW && idChild.toInt() == 0 &&
                    (event.toInt() == EVENT_OBJECT_LOCATIONCHANGE || event.toInt() == EVENT_OBJECT_HIDE) &&
                    pending.compareAndSet(false, true)
                ) {
                    EventQueue.invokeLater {
                        pending.set(false)
                        listeners.toList().forEach { it() }
                    }
                }
            }
            val handle: WinNT.HANDLE? = runCatching {
                user.SetWinEventHook(EVENT_OBJECT_HIDE, EVENT_OBJECT_LOCATIONCHANGE, null, callback, pid, 0, WINEVENT_OUTOFCONTEXT)
            }.getOrNull()
            started.countDown()
            if (handle == null) return@Thread
            try {
                val message = WinUser.MSG()
                while (user.GetMessage(message, null, 0, 0) > 0) Unit
            } finally {
                user.UnhookWinEvent(handle)
            }
        }, "pip-watch").apply {
            isDaemon = true
            start()
        }
        started.await(1, java.util.concurrent.TimeUnit.SECONDS)
    }

    private fun stopHook() {
        val thread = hook ?: return
        hook = null
        if (hookThreadId != 0) user32?.PostThreadMessage(hookThreadId, WM_QUIT, WinDef.WPARAM(0), WinDef.LPARAM(0))
        hookThreadId = 0
        thread.join(500)
    }
}
