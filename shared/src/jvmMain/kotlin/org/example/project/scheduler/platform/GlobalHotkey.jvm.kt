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

/**
 * Our own thread message, posted to the hot-key loop to make it re-read [GlobalHotkeyClaimant]'s desired
 * registrations. `RegisterHotKey(NULL, …)` binds to the **calling thread**, so a rebinding made on the UI
 * thread cannot touch the table itself — it can only ask the loop to. Comfortably above `WM_USER` (0x0400).
 */
private const val WM_OMNIAPP_RECONFIGURE = 0x0400 + 0x51

// The four key messages a low-level keyboard hook is handed. A chord whose modifiers include Alt arrives as
// the SYS variant, so both spellings of down and up have to be recognised.
private const val WM_KEYDOWN = 0x0100
private const val WM_KEYUP = 0x0101
private const val WM_SYSKEYDOWN = 0x0104
private const val WM_SYSKEYUP = 0x0105

// Virtual-key codes (winuser.h) for the modifier halves of a chord.
private const val VK_LSHIFT = 0xA0
private const val VK_RSHIFT = 0xA1
private const val VK_LCONTROL = 0xA2
private const val VK_RCONTROL = 0xA3
private const val VK_LMENU = 0xA4
private const val VK_RMENU = 0xA5

/** `VK_F1`; the twelve function keys are consecutive from there. */
private const val VK_F1 = 0x70

/** The high bit of `GetAsyncKeyState`'s answer: "this key is down right now". */
private const val KEY_DOWN_BIT = 0x8000

actual fun installGlobalHotkeys(
    bindings: Map<GlobalShortcut, ShortcutBinding>,
    onShortcut: (GlobalShortcut) -> Unit,
) = GlobalHotkeyClaimant.install(bindings, onShortcut)

actual fun setGlobalHotkeyCapture(capturing: Boolean) = GlobalHotkeyClaimant.setCapturing(capturing)

/**
 * The Win32 virtual-key code for a bindable key.
 *
 * `winuser.h` leaves the letter and digit keys at their ASCII values (`VK_A` = 0x41 = `'A'`, `VK_0` = 0x30 =
 * `'0'`), so [ShortcutKey.label] *is* the code for those two families; the function keys run consecutively
 * from [VK_F1]. That is exactly why [ShortcutKey] is a closed set of these three families — every one of them
 * has a code Windows guarantees, independent of the user's keyboard layout.
 */
private fun ShortcutKey.virtualKey(): Int =
    when {
        label.length == 1 -> label[0].code
        else -> VK_F1 + (label.drop(1).toInt() - 1)
    }

/** One system-wide chord as the OS sees it: which [GlobalShortcut] it fires, and how it is claimed. */
private class Chord(val shortcut: GlobalShortcut, val binding: ShortcutBinding) {
    val vk: Int = binding.key.virtualKey()

    /** Stable per shortcut, so re-registering after a rebinding replaces the right entry in the table. */
    val hotkeyId: Int = shortcut.ordinal + 1

    val modifiers: Int =
        (if (binding.ctrl) MOD_CONTROL else 0) or
            (if (binding.shift) MOD_SHIFT else 0) or
            (if (binding.alt) MOD_ALT else 0) or
            MOD_NOREPEAT
}

/**
 * PRD §7/§15: the OS-level claim behind [installGlobalHotkeys], built like [DesktopSessionTracker] — a
 * dedicated daemon thread owning a Win32 message loop.
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
 *
 * **The chords are not fixed.** [chords] is what the account is currently bound to (PRD §7, the
 * keyboard-shortcuts window), so both claims have to be re-made when the user rebinds one. The hook simply
 * re-reads the volatile field; the hot-key table can only be rewritten from the loop thread, so [reconfigure]
 * posts [WM_OMNIAPP_RECONFIGURE] to it.
 */
internal object GlobalHotkeyClaimant {
    @Volatile
    private var onShortcut: ((GlobalShortcut) -> Unit)? = null

    /** The live chords, re-read by the hook on every keystroke. Replaced whole, never mutated in place. */
    @Volatile
    private var chords: List<Chord> = emptyList()

    /**
     * True while the keyboard-shortcuts window is listening for a new chord: the hook passes everything
     * through, the hot-key table is emptied, and nothing fires — see [setGlobalHotkeyCapture] for why the
     * claim has to stand down for the window to be able to hear the chords the app itself owns.
     */
    @Volatile
    private var capturing = false

    /** The hot-key loop's Win32 thread id, so a rebinding can wake it. 0 until the loop is running. */
    @Volatile
    private var loopThreadId = 0

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
                // While the window is listening for a chord the claim stands down entirely — including for
                // the chords we own, which are precisely the ones the user needs to be able to press there.
                if (nCode >= 0 && !capturing) {
                    val chord = chords.firstOrNull { it.vk == lParam.vkCode }
                    if (chord != null) {
                        when (wParam.toInt()) {
                            WM_KEYDOWN, WM_SYSKEYDOWN ->
                                if (chordModifiersHeld(chord.binding)) {
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
    fun install(bindings: Map<GlobalShortcut, ShortcutBinding>, callback: (GlobalShortcut) -> Unit) {
        onShortcut = callback
        val next = GlobalShortcutBindings.resolve(bindings).map { (shortcut, binding) -> Chord(shortcut, binding) }
        val changed = next.map { it.shortcut to it.binding } != chords.map { it.shortcut to it.binding }
        chords = next
        // Later calls only re-point the callback and re-register: the OS claim is made once per process.
        if (installed) {
            if (changed) {
                Diagnostics.log("global hotkeys: rebound to ${next.joinToString { it.binding.chord }}")
                reconfigure()
            }
            return
        }
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

    /** See [setGlobalHotkeyCapture]. Balanced by the window; a no-op when the flag is already right. */
    @Synchronized
    fun setCapturing(active: Boolean) {
        if (capturing == active) return
        capturing = active
        // The hook reads the flag itself, but the hot-key table has to be emptied and refilled by its own
        // thread — otherwise RegisterHotKey would still consume the very press the window is waiting for.
        reconfigure()
        // A chord half-struck when the claim stood down would otherwise stay latched and swallow its own
        // key-up for ever.
        latched.clear()
    }

    /** Ask the loop thread to re-read [chords] / [capturing] and rewrite the hot-key table. */
    private fun reconfigure() {
        val threadId = loopThreadId
        if (threadId == 0) return
        runCatching {
            User32.INSTANCE.PostThreadMessage(threadId, WM_OMNIAPP_RECONFIGURE, WPARAM(0), LPARAM(0))
        }.onFailure { Diagnostics.log("global hotkeys: reconfigure post failed: ${it.message}") }
    }

    private fun runMessageLoop() {
        var hook: HHOOK? = null
        try {
            val user32 = User32.INSTANCE
            hook = installKeyboardHook(user32)
            loopThreadId = Kernel32.INSTANCE.GetCurrentThreadId()
            val registered = applyHotkeyRegistrations(user32)
            val claim = when {
                hook != null -> GlobalHotkeyClaim.Exclusive
                registered > 0 -> GlobalHotkeyClaim.Shared
                else -> GlobalHotkeyClaim.Unavailable
            }
            GlobalHotkeys.reportClaim(claim)
            Diagnostics.log(
                "global hotkeys: claim=$claim, hook=${hook != null}, registered=$registered of ${chords.size}",
            )
            if (claim == GlobalHotkeyClaim.Unavailable) return

            val msg = MSG()
            while (true) {
                val result = user32.GetMessage(msg, null, 0, 0)
                if (result <= 0) break // 0 = WM_QUIT, -1 = error
                when (msg.message) {
                    WM_HOTKEY ->
                        // Only reached when the hook is absent or was dropped by Windows: a swallowed key
                        // never reaches the hot-key table, so the two claims cannot both fire for one press.
                        if (!capturing) {
                            chords.firstOrNull { it.hotkeyId == msg.wParam.toInt() }?.let { fire(it.shortcut) }
                        }
                    // A rebinding, or the window standing the claim down to listen for a chord.
                    WM_OMNIAPP_RECONFIGURE -> applyHotkeyRegistrations(user32)
                    else -> Unit
                }
                user32.TranslateMessage(msg)
                user32.DispatchMessage(msg)
            }
        } catch (t: Throwable) {
            // Any linkage/native failure: the app runs on without the shortcuts (the buttons still work).
            Diagnostics.log("global hotkeys failed: ${t.message}; shortcuts unavailable")
            GlobalHotkeys.reportClaim(GlobalHotkeyClaim.Unavailable)
        } finally {
            loopThreadId = 0
            runCatching {
                hook?.let { User32.INSTANCE.UnhookWindowsHookEx(it) }
                GlobalShortcut.entries.forEach { User32.INSTANCE.UnregisterHotKey(null, it.ordinal + 1) }
            }
        }
    }

    /**
     * Rewrite the fallback hot-key table to match [chords] — or empty it while [capturing]. Runs on the loop
     * thread only, because `RegisterHotKey(NULL, …)`'s registration belongs to whichever thread made it.
     *
     * Every id is unregistered first: a rebinding has to release the chord it left, or the previous chord
     * would keep being consumed by a registration nothing fires any more.
     *
     * @return how many chords the OS granted.
     */
    private fun applyHotkeyRegistrations(user32: User32): Int {
        GlobalShortcut.entries.forEach { runCatching { user32.UnregisterHotKey(null, it.ordinal + 1) } }
        if (capturing) return 0
        // hWnd = null: this thread's queue receives WM_HOTKEY, so no window is needed at all.
        return chords.count { chord ->
            user32.RegisterHotKey(null, chord.hotkeyId, chord.modifiers, chord.vk).also { ok ->
                // A chord is exclusive across the whole session: another application already owns it.
                if (!ok) Diagnostics.log("global hotkeys: RegisterHotKey(${chord.binding.chord}) refused")
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
     * Whether exactly [binding]'s modifiers are down — the half of the chord the hook has to read for itself
     * (unlike `RegisterHotKey`, which matches the whole combination for us). A modifier the binding does not
     * ask for must be **up**, or `Ctrl+Alt+K` would also fire on `Ctrl+Shift+Alt+K`.
     *
     * The one subtlety is **AltGr**: Windows delivers right-Alt as a synthetic left-Ctrl plus right-Alt, so on
     * a French/AZERTY layout `Shift+AltGr+E` — an ordinary way to type a character — carries exactly the
     * modifier flags of Ctrl+Shift+Alt+E. Recognising that pattern and passing it through keeps the hook from
     * eating text the user meant to type; the price is that such a chord must be struck with the *left* Alt.
     */
    private fun chordModifiersHeld(binding: ShortcutBinding): Boolean {
        val lCtrl = isKeyDown(VK_LCONTROL)
        val rCtrl = isKeyDown(VK_RCONTROL)
        val lAlt = isKeyDown(VK_LMENU)
        val rAlt = isKeyDown(VK_RMENU)
        val shift = isKeyDown(VK_LSHIFT) || isKeyDown(VK_RSHIFT)
        if (shift != binding.shift) return false
        if ((lCtrl || rCtrl) != binding.ctrl) return false
        if ((lAlt || rAlt) != binding.alt) return false
        val altGr = rAlt && !lAlt && lCtrl && !rCtrl
        return !(altGr && binding.ctrl && binding.alt)
    }

    private fun isKeyDown(vk: Int): Boolean =
        (User32.INSTANCE.GetAsyncKeyState(vk).toInt() and KEY_DOWN_BIT) != 0

    private fun fire(shortcut: GlobalShortcut) {
        val chord = chords.firstOrNull { it.shortcut == shortcut }?.binding?.chord ?: shortcut.defaultChord
        dispatchExecutor.execute {
            Diagnostics.log("global hotkey pressed: $chord (${shortcut.action})")
            // Never let a throw kill the loop, or the shortcuts would silently stop working for the session.
            runCatching { onShortcut?.invoke(shortcut) }
                .onFailure { Diagnostics.log("global hotkey handler failed: ${it.message}") }
        }
    }
}
