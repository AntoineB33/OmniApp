package org.example.project.scheduler.platform

import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.LPARAM
import com.sun.jna.platform.win32.WinDef.LRESULT
import com.sun.jna.platform.win32.WinDef.WPARAM
import com.sun.jna.platform.win32.WinUser.HHOOK
import com.sun.jna.platform.win32.WinUser.KBDLLHOOKSTRUCT
import com.sun.jna.platform.win32.WinUser.LowLevelKeyboardProc
import com.sun.jna.platform.win32.WinUser.MSG
import com.sun.jna.platform.win32.WinUser.WH_KEYBOARD_LL
import java.util.concurrent.Executors

// Win32 hot-key modifiers (winuser.h). MOD_NOREPEAT stops a held-down chord from firing over and over —
// without it, resting on the keys would toggle "away" dozens of times a second.
private const val MOD_ALT = 0x0001
private const val MOD_CONTROL = 0x0002
private const val MOD_SHIFT = 0x0004
private const val MOD_NOREPEAT = 0x4000
private const val WM_HOTKEY = 0x0312

// The four key messages a low-level keyboard hook is handed. A chord whose modifiers include Alt arrives as
// the SYS variant, so both spellings of down and up have to be recognised.
private const val WM_KEYDOWN = 0x0100
private const val WM_KEYUP = 0x0101
private const val WM_SYSKEYDOWN = 0x0104
private const val WM_SYSKEYUP = 0x0105

// Virtual-key codes (winuser.h leaves the letter keys at their ASCII values).
private const val VK_A = 0x41
private const val VK_E = 0x45
private const val VK_Z = 0x5A
private const val VK_LSHIFT = 0xA0
private const val VK_RSHIFT = 0xA1
private const val VK_LCONTROL = 0xA2
private const val VK_RCONTROL = 0xA3
private const val VK_LMENU = 0xA4
private const val VK_RMENU = 0xA5

/** The high bit of `GetAsyncKeyState`'s answer: "this key is down right now". */
private const val KEY_DOWN_BIT = 0x8000

actual fun installGlobalHotkeys(onShortcut: (GlobalShortcut) -> Unit) = GlobalHotkeyClaimant.install(onShortcut)

/** One system-wide chord: which [GlobalShortcut] it fires, its letter, and its `RegisterHotKey` id. */
private class Chord(val shortcut: GlobalShortcut, val vk: Int, val hotkeyId: Int)

private val CHORDS = listOf(
    Chord(GlobalShortcut.ToggleAway, vk = VK_A, hotkeyId = 1),
    Chord(GlobalShortcut.LookAwayNow, vk = VK_E, hotkeyId = 2),
    Chord(GlobalShortcut.SwitchTask, vk = VK_Z, hotkeyId = 3),
)

/**
 * PRD §7/§15: the OS-level `Ctrl+Shift+Alt+A` / `+E` / `+Z` claim behind [installGlobalHotkeys], built
 * like [DesktopSessionTracker] — a dedicated daemon thread owning a Win32 message loop.
 *
 * The thread is not incidental. Both claims below are delivered to the **thread that made them**: a
 * `WH_KEYBOARD_LL` callback runs on the installing thread's message pump, and `RegisterHotKey(NULL, …)` posts
 * `WM_HOTKEY` to the registering thread's queue. So the claim and the loop that drains it have to be the same
 * thread, and it must be one we own: the AWT event thread's queue is Compose's, and a hot-key delivered there
 * is a message nothing in the app looks at.
 *
 * **Two claims, deliberately stacked.**
 *
 *  1. A **low-level keyboard hook** ([WH_KEYBOARD_LL]) is what makes the chord *ours alone*. Hooks run before
 *     the system's hot-key table and before the focused window's message queue, so returning a non-zero
 *     result **swallows** the key: nothing else in the session — Google Docs' `Ctrl+Shift+Alt+A` comments
 *     pane, say — ever sees it. That is the "first come, first served" rule. `RegisterHotKey` alone does not
 *     give it: an application watching the keyboard through its own low-level hook is called *before* the
 *     hot-key table, so it can act on the very press the hot-key then consumes, and both actions happen.
 *  2. `RegisterHotKey` stays underneath as the **fallback**. It cannot double-fire — a swallowed key never
 *     reaches the hot-key table — but it keeps the shortcuts working when the hook cannot be installed at
 *     all, and if Windows silently drops the hook for exceeding `LowLevelHooksTimeout` (a long GC pause, say)
 *     the shortcuts degrade to shared delivery instead of dying.
 *
 * Because the hook sits on the critical path of **every keystroke in the session**, its callback does the
 * minimum: compare a virtual-key code, read the modifier state, latch, and hand the work to
 * [dispatchExecutor]. Nothing that can block — no logging, no engine call — happens inside it.
 */
internal object GlobalHotkeyClaimant {
    @Volatile
    private var onShortcut: ((GlobalShortcut) -> Unit)? = null

    private var installed = false

    /** The chords whose key-down we swallowed, so auto-repeat fires once and the key-up is swallowed too. */
    private val latched = HashSet<GlobalShortcut>()

    // Every press hops off the hook thread onto this one: the hook must return within milliseconds or Windows
    // drops it, and the handler reaches the engine (and the diagnostics file).
    private val dispatchExecutor =
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "omniapp-hotkey-dispatch").apply { isDaemon = true }
        }

    /** A non-zero hook result: the key is consumed here and nothing further in the system sees it. */
    private val swallow = LRESULT(1)

    // Held for the process's lifetime: JNA frees the native trampoline of a callback nothing references, and
    // a collected hook proc crashes the first keystroke after the GC.
    private val hookProc =
        object : LowLevelKeyboardProc {
            override fun callback(nCode: Int, wParam: WPARAM, lParam: KBDLLHOOKSTRUCT): LRESULT {
                if (nCode >= 0) {
                    val chord = CHORDS.firstOrNull { it.vk == lParam.vkCode }
                    if (chord != null) {
                        when (wParam.toInt()) {
                            WM_KEYDOWN, WM_SYSKEYDOWN ->
                                if (chordModifiersHeld()) {
                                    // Auto-repeat re-sends the down; only the transition is a press.
                                    if (latched.add(chord.shortcut)) fire(chord.shortcut)
                                    return swallow
                                }
                            // Swallow the up only for a down we swallowed, so no window is ever handed a
                            // release for a key it was never told about.
                            WM_KEYUP, WM_SYSKEYUP -> if (latched.remove(chord.shortcut)) return swallow
                        }
                    }
                }
                return User32.INSTANCE.CallNextHookEx(
                    null,
                    nCode,
                    wParam,
                    LPARAM(Pointer.nativeValue(lParam.pointer)),
                )
            }
        }

    @Synchronized
    fun install(callback: (GlobalShortcut) -> Unit) {
        // Later calls only re-point the callback: the chords are claimed from the OS once per process.
        onShortcut = callback
        if (installed) return
        installed = true
        val os = System.getProperty("os.name").orEmpty()
        if (!os.startsWith("Windows")) {
            Diagnostics.log("global hotkeys: unsupported host ($os); shortcuts not installed")
            GlobalHotkeys.reportClaim(GlobalHotkeyClaim.Unsupported)
            return
        }
        Thread({ runMessageLoop() }, "omniapp-global-hotkeys").apply {
            isDaemon = true
            start()
        }
    }

    private fun runMessageLoop() {
        var hook: HHOOK? = null
        try {
            val user32 = User32.INSTANCE
            hook = installKeyboardHook(user32)
            // hWnd = null: this thread's queue receives WM_HOTKEY, so no window is needed at all.
            val registered = CHORDS.filter { chord ->
                user32.RegisterHotKey(
                    null,
                    chord.hotkeyId,
                    MOD_CONTROL or MOD_SHIFT or MOD_ALT or MOD_NOREPEAT,
                    chord.vk,
                ).also { ok ->
                    // A chord is exclusive across the whole session: another application already owns it.
                    if (!ok) Diagnostics.log("global hotkeys: RegisterHotKey(${chord.shortcut.chord}) refused")
                }
            }
            val claim = when {
                hook != null -> GlobalHotkeyClaim.Exclusive
                registered.isNotEmpty() -> GlobalHotkeyClaim.Shared
                else -> GlobalHotkeyClaim.Unavailable
            }
            GlobalHotkeys.reportClaim(claim)
            val registeredChords = registered.joinToString { it.shortcut.chord }.ifEmpty { "none" }
            Diagnostics.log("global hotkeys: claim=$claim, hook=${hook != null}, registered=$registeredChords")
            if (claim == GlobalHotkeyClaim.Unavailable) return

            val msg = MSG()
            while (true) {
                val result = user32.GetMessage(msg, null, 0, 0)
                if (result <= 0) break // 0 = WM_QUIT, -1 = error
                if (msg.message == WM_HOTKEY) {
                    // Only reached when the hook is absent or was dropped by Windows: a swallowed key never
                    // reaches the hot-key table, so the two claims cannot both fire for one press.
                    CHORDS.firstOrNull { it.hotkeyId == msg.wParam.toInt() }?.let { fire(it.shortcut) }
                }
                user32.TranslateMessage(msg)
                user32.DispatchMessage(msg)
            }
        } catch (t: Throwable) {
            // Any linkage/native failure: the app runs on without the shortcuts (the buttons still work).
            Diagnostics.log("global hotkeys failed: ${t.message}; shortcuts unavailable")
            GlobalHotkeys.reportClaim(GlobalHotkeyClaim.Unavailable)
        } finally {
            runCatching {
                hook?.let { User32.INSTANCE.UnhookWindowsHookEx(it) }
                CHORDS.forEach { User32.INSTANCE.UnregisterHotKey(null, it.hotkeyId) }
            }
        }
    }

    /** Best-effort [WH_KEYBOARD_LL] claim; null (with a diagnostics line) when the OS refuses it. */
    private fun installKeyboardHook(user32: User32): HHOOK? =
        runCatching {
            user32.SetWindowsHookEx(WH_KEYBOARD_LL, hookProc, Kernel32.INSTANCE.GetModuleHandle(null), 0)
        }.getOrElse {
            Diagnostics.log("global hotkeys: keyboard hook threw ${it.message}")
            null
        }.also {
            if (it == null) {
                Diagnostics.log("global hotkeys: keyboard hook refused; falling back to RegisterHotKey")
            }
        }

    /**
     * Whether Ctrl **and** Shift **and** Alt are all down — the modifier half of the chord, which the hook has
     * to read for itself (unlike `RegisterHotKey`, which matches the whole combination for us).
     *
     * The one subtlety is **AltGr**: Windows delivers right-Alt as a synthetic left-Ctrl plus right-Alt, so on
     * a French/AZERTY layout `Shift+AltGr+E` — an ordinary way to type a character — carries exactly the
     * modifier flags of Ctrl+Shift+Alt+E. Recognising that pattern and passing it through keeps the hook from
     * eating text the user meant to type; the price is that the chord must be struck with the *left* Alt.
     */
    private fun chordModifiersHeld(): Boolean {
        val lCtrl = isKeyDown(VK_LCONTROL)
        val rCtrl = isKeyDown(VK_RCONTROL)
        val lAlt = isKeyDown(VK_LMENU)
        val rAlt = isKeyDown(VK_RMENU)
        if (!isKeyDown(VK_LSHIFT) && !isKeyDown(VK_RSHIFT)) return false
        if (!lCtrl && !rCtrl) return false
        if (!lAlt && !rAlt) return false
        val altGr = rAlt && !lAlt && lCtrl && !rCtrl
        return !altGr
    }

    private fun isKeyDown(vk: Int): Boolean =
        (User32.INSTANCE.GetAsyncKeyState(vk).toInt() and KEY_DOWN_BIT) != 0

    private fun fire(shortcut: GlobalShortcut) {
        dispatchExecutor.execute {
            Diagnostics.log("global hotkey pressed: ${shortcut.chord} (${shortcut.action})")
            // Never let a throw kill the loop, or the shortcuts would silently stop working for the session.
            runCatching { onShortcut?.invoke(shortcut) }
                .onFailure { Diagnostics.log("global hotkey handler failed: ${it.message}") }
        }
    }
}
