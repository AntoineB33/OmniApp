package org.example.project.scheduler.platform

import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinUser.MSG

// Win32 hot-key modifiers (winuser.h). MOD_NOREPEAT stops a held-down chord from firing over and over —
// without it, resting on the keys would toggle "away" dozens of times a second.
private const val MOD_ALT = 0x0001
private const val MOD_CONTROL = 0x0002
private const val MOD_SHIFT = 0x0004
private const val MOD_NOREPEAT = 0x4000
private const val WM_HOTKEY = 0x0312

/** Virtual-key code of `A` (winuser.h leaves the letter keys at their ASCII values). */
private const val VK_A = 0x41

/** This process's id for the chord; only has to be unique among the hot-keys THIS thread registers. */
private const val AWAY_HOTKEY_ID = 1

actual fun installGlobalAwayHotkey(onPressed: () -> Unit) = GlobalAwayHotkey.install(onPressed)

/**
 * PRD §15: the OS-level `Ctrl+Shift+Alt+A` registration behind [installGlobalAwayHotkey], built like
 * [DesktopSessionTracker] — a dedicated daemon thread owning a Win32 message loop.
 *
 * The thread is not incidental. `RegisterHotKey(NULL, …)` posts `WM_HOTKEY` to the **message queue of the
 * thread that registered it**, so the registration and the loop that drains it have to be the same thread,
 * and it must be one we own: the AWT event thread's queue is Compose's, and a hot-key delivered there is a
 * message nothing in the app looks at.
 */
internal object GlobalAwayHotkey {
    @Volatile
    private var onPressed: (() -> Unit)? = null

    private var installed = false

    @Synchronized
    fun install(callback: () -> Unit) {
        // Later calls only re-point the callback: the chord is claimed from the OS once per process.
        onPressed = callback
        if (installed) return
        installed = true
        val os = System.getProperty("os.name").orEmpty()
        if (!os.startsWith("Windows")) {
            Diagnostics.log("global away hotkey: unsupported host ($os); shortcut not installed")
            return
        }
        Thread({ runMessageLoop() }, "omniapp-away-hotkey").apply {
            isDaemon = true
            start()
        }
    }

    private fun runMessageLoop() {
        try {
            val user32 = User32.INSTANCE
            // hWnd = null: this thread's queue receives WM_HOTKEY, so no window is needed at all.
            val registered = user32.RegisterHotKey(
                null,
                AWAY_HOTKEY_ID,
                MOD_CONTROL or MOD_SHIFT or MOD_ALT or MOD_NOREPEAT,
                VK_A,
            )
            if (!registered) {
                // A chord is exclusive across the whole session: another application already owns this one.
                Diagnostics.log("global away hotkey: RegisterHotKey(ctrl+shift+alt+A) refused; shortcut unavailable")
                return
            }
            Diagnostics.log("global away hotkey installed (ctrl+shift+alt+A)")

            val msg = MSG()
            while (true) {
                val result = user32.GetMessage(msg, null, 0, 0)
                if (result <= 0) break // 0 = WM_QUIT, -1 = error
                if (msg.message == WM_HOTKEY && msg.wParam.toInt() == AWAY_HOTKEY_ID) {
                    Diagnostics.log("global away hotkey pressed")
                    // The handler hops to the engine's scope; never let a throw kill the loop, or the
                    // shortcut would silently stop working for the rest of the session.
                    runCatching { onPressed?.invoke() }
                        .onFailure { Diagnostics.log("global away hotkey handler failed: ${it.message}") }
                }
                user32.TranslateMessage(msg)
                user32.DispatchMessage(msg)
            }
            user32.UnregisterHotKey(null, AWAY_HOTKEY_ID)
        } catch (t: Throwable) {
            // Any linkage/native failure: the app runs on without the shortcut (the button still works).
            Diagnostics.log("global away hotkey failed: ${t.message}; shortcut unavailable")
        }
    }
}
