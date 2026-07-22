package org.example.project.scheduler.platform

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinDef.HINSTANCE
import com.sun.jna.platform.win32.WinDef.LPARAM
import com.sun.jna.platform.win32.WinDef.LRESULT
import com.sun.jna.platform.win32.WinDef.WPARAM
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.platform.win32.WinUser.MSG
import com.sun.jna.platform.win32.WinUser.WNDCLASSEX
import com.sun.jna.win32.W32APIOptions

/** PRD §15: the desktop build is not the phone. */
actual fun currentDeviceKind(): DeviceKind = DeviceKind.Desktop

/**
 * PRD §15: the desktop screen is "active" purely while the OS session is **unlocked** — event-driven via
 * [DesktopSessionTracker] (Windows `WM_WTSSESSION_CHANGE`), so a lock reads inactive and an unlock reads active
 * instantly, mirroring Android's unlock-gated signal. No pointer polling: an unlocked-but-idle desktop stays
 * active until it is actually locked. Non-Windows hosts / a failed native install keep [unlocked] `true`.
 */
actual fun isScreenActive(): Boolean = DesktopSessionTracker.unlocked

actual fun installPlatformActivityListener(onChanged: () -> Unit) {
    DesktopSessionTracker.onChanged = onChanged
    DesktopSessionTracker.install()
}

// --- Win32 session lock/unlock (event-driven, mirrors AndroidUnlockTracker) -------------------------------

private const val WM_WTSSESSION_CHANGE = 0x02B1
private const val WTS_SESSION_LOCK = 0x7
private const val WTS_SESSION_UNLOCK = 0x8
private const val NOTIFY_FOR_THIS_SESSION = 0

/** Native binding for `wtsapi32.dll` session-change registration (not exposed by jna-platform's helpers). */
private interface Wtsapi32 : Library {
    fun WTSRegisterSessionNotification(hWnd: HWND, dwFlags: Int): Boolean
    fun WTSUnRegisterSessionNotification(hWnd: HWND): Boolean

    companion object {
        val INSTANCE: Wtsapi32 =
            Native.load("wtsapi32", Wtsapi32::class.java, W32APIOptions.DEFAULT_OPTIONS)
    }
}

/**
 * PRD §15: tracks whether the desktop session is unlocked, from the Windows `WM_WTSSESSION_CHANGE`
 * (`WTS_SESSION_LOCK` / `_UNLOCK`) notifications delivered to a hidden message-only window on a dedicated
 * daemon thread. [onChanged] (set by the app via [installPlatformActivityListener]) pokes the engine so the
 * presence socket reconnects/drops within moments of the flip instead of at the next minute beat.
 *
 * Windows-only: on any other JVM host (or if the native pump fails to install) [install] is a no-op and
 * [unlocked] stays `true`, so [isScreenActive] falls back to the pure pointer poll — same behaviour as before.
 */
internal object DesktopSessionTracker {
    // Default true so a non-Windows host / a failed native install degrades to the pointer-only poll.
    @Volatile
    var unlocked: Boolean = true
        private set

    @Volatile
    var onChanged: (() -> Unit)? = null

    private var installed = false

    // Strong references kept for the lifetime of the window: the WndProc callback must not be GC'd, and the
    // HWND is needed to unregister (best-effort) if we ever tear down.
    private var windowProc: WinUser.WindowProc? = null

    @Synchronized
    fun install() {
        if (installed) return
        installed = true
        val os = System.getProperty("os.name").orEmpty()
        if (!os.startsWith("Windows")) return // Only Windows session notifications are supported here.
        Thread({ runMessageLoop() }, "omniapp-session-listener").apply {
            isDaemon = true
            start()
        }
    }

    private fun setUnlocked(next: Boolean) {
        if (next == unlocked) return
        unlocked = next
        Diagnostics.log("desktop session ${if (next) "unlock" else "lock"}")
        onChanged?.invoke()
    }

    private fun runMessageLoop() {
        try {
            val user32 = User32.INSTANCE
            val hInst = HINSTANCE().apply { pointer = Kernel32.INSTANCE.GetModuleHandle(null).pointer }

            val proc = WinUser.WindowProc { hwnd: HWND, uMsg: Int, wParam: WPARAM, lParam: LPARAM ->
                if (uMsg == WM_WTSSESSION_CHANGE) {
                    when (wParam.toInt()) {
                        WTS_SESSION_LOCK -> setUnlocked(false)
                        WTS_SESSION_UNLOCK -> setUnlocked(true)
                    }
                }
                user32.DefWindowProc(hwnd, uMsg, wParam, lParam)
            }
            windowProc = proc // retain so the native callback survives GC

            val className = "OmniAppSessionListenerWindow"
            val wClass = WNDCLASSEX().apply {
                hInstance = hInst
                lpfnWndProc = proc
                lpszClassName = className
            }
            user32.RegisterClassEx(wClass)

            val hwnd: HWND? = user32.CreateWindowEx(
                0, className, className, 0, 0, 0, 0, 0, null, null, hInst, null,
            )
            if (hwnd == null) {
                Diagnostics.log("desktop session listener: CreateWindowEx returned null; falling back to poll")
                return
            }

            Wtsapi32.INSTANCE.WTSRegisterSessionNotification(hwnd, NOTIFY_FOR_THIS_SESSION)
            Diagnostics.log("desktop session listener installed")

            val msg = MSG()
            while (true) {
                val r = user32.GetMessage(msg, null, 0, 0)
                if (r <= 0) break // 0 = WM_QUIT, -1 = error
                user32.TranslateMessage(msg)
                user32.DispatchMessage(msg)
            }
            Wtsapi32.INSTANCE.WTSUnRegisterSessionNotification(hwnd)
        } catch (t: Throwable) {
            // Any linkage/native failure: leave `unlocked = true` so isScreenActive stays on the pointer poll.
            Diagnostics.log("desktop session listener failed: ${t.message}; falling back to poll")
        }
    }
}
