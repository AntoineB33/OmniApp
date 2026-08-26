package org.example.project.scheduler.platform

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One key a system-wide chord may end on. A **closed** set, not "whatever the OS can report": the chord has
 * to survive a round trip through the persisted snapshot and the sync wire, and every platform actual has to
 * be able to name it to its own OS — so a key nobody can spell is a key nobody may bind.
 *
 * The three families are the ones a keyboard has in the same place everywhere. Deliberately absent: the
 * modifiers themselves, Escape/Tab/Enter/Backspace (a chord on those is a chord on the shape of typing), and
 * everything whose position moves with the layout (punctuation, the numeric pad) — an AZERTY user rebinding
 * onto a key a QWERTY peer does not have would produce a chord that works on one of their machines only, and
 * the binding is the **account's**.
 *
 * The entry *names* are the persisted form, so they must not be renamed.
 */
enum class ShortcutKey(
    /** How the key is printed in a chord, in the keyboard-shortcuts window and in the diagnostics. */
    val label: String,
) {
    A("A"), B("B"), C("C"), D("D"), E("E"), F("F"), G("G"), H("H"), I("I"),
    J("J"), K("K"), L("L"), M("M"), N("N"), O("O"), P("P"), Q("Q"), R("R"),
    S("S"), T("T"), U("U"), V("V"), W("W"), X("X"), Y("Y"), Z("Z"),

    Digit0("0"), Digit1("1"), Digit2("2"), Digit3("3"), Digit4("4"),
    Digit5("5"), Digit6("6"), Digit7("7"), Digit8("8"), Digit9("9"),

    F1("F1"), F2("F2"), F3("F3"), F4("F4"), F5("F5"), F6("F6"),
    F7("F7"), F8("F8"), F9("F9"), F10("F10"), F11("F11"), F12("F12"),
    ;

    companion object {
        /** The key printed as [label], or null — used to read a chord back out of a UI / parse surface. */
        fun byLabel(label: String): ShortcutKey? =
            entries.firstOrNull { it.label.equals(label, ignoreCase = true) }
    }
}

/**
 * What a [GlobalShortcut] is actually bound to: a key, plus the modifiers that must be held with it.
 *
 * There is no Win/Meta flag. Windows reserves that modifier for the shell, so a chord carrying it is one the
 * app cannot reliably claim — and the two claims below ([GlobalHotkeyClaim]) would disagree about it.
 */
data class ShortcutBinding(
    val key: ShortcutKey,
    val ctrl: Boolean = true,
    val shift: Boolean = true,
    val alt: Boolean = true,
) {
    /** How many of Ctrl/Shift/Alt this chord holds — see [GlobalShortcutBindings.MIN_MODIFIERS]. */
    val modifierCount: Int get() = (if (ctrl) 1 else 0) + (if (shift) 1 else 0) + (if (alt) 1 else 0)

    /**
     * The one human-readable spelling of this chord — what the keyboard-shortcuts window prints, what the
     * receipt notification names, and what goes into the diagnostics. The modifiers always come in the same
     * order, so one chord can never appear under two spellings.
     */
    val chord: String
        get() = buildString {
            if (ctrl) append("Ctrl+")
            if (shift) append("Shift+")
            if (alt) append("Alt+")
            append(key.label)
        }
}

/**
 * PRD §7/§15: the app's **system-wide** chords — the ones that must fire while OmniApp is *not* the focused
 * window, because the moment each of them is wanted is a moment the user is looking at some other
 * application.
 *
 * Each carries the chord it *ships* with. The **live** chord is the account's
 * ([GlobalShortcutBindings.resolve]) — the user may rebind any of them in the keyboard-shortcuts window,
 * which is why nothing outside that resolution may print [defaultBinding] as though it were what the app is
 * listening for.
 *
 * Every default is `Ctrl+Shift+Alt+<letter>`: a three-modifier chord is deliberately awkward, so it is
 * unlikely to be sitting under the user's fingers by accident, and it is the shape Windows lets a background
 * process claim without a driver.
 */
enum class GlobalShortcut(
    /** The chord this shortcut ships with, and what "reset" puts back. */
    val defaultBinding: ShortcutBinding,
    /** What pressing it does, in the words the lateral menu uses. */
    val action: String,
) {
    /** Flips the per-device "I'm away" / "I'm back" flag — the user is leaving (or back at) this machine. */
    ToggleAway(ShortcutBinding(ShortcutKey.A), "I'm away / I'm back"),

    /** Takes the 20-second look-away now, superseding any look-away still sounding or pending. */
    LookAwayNow(ShortcutBinding(ShortcutKey.E), "Look away now"),

    /**
     * PRD §7: refuses the task the now-line is on, so the plan starts a different one from now. Struck from
     * inside whatever the user is working in — which is precisely where they realise they want to be doing
     * something else — so it is system-wide like the other two.
     */
    SwitchTask(ShortcutBinding(ShortcutKey.Z), "Switch task"),
    ;

    /** The chord this shortcut ships with, spelled out. Never the live one — see [defaultBinding]. */
    val defaultChord: String get() = defaultBinding.chord
}

/**
 * PRD §7: the account's answer to "what is each [GlobalShortcut] bound to" — the user's **overrides** over
 * [GlobalShortcut.defaultBinding], plus the two rules that say which overrides are allowed.
 *
 * Only the overrides are stored (`SchedulerState.shortcutBindings`), never the whole table: a shortcut the
 * user has never touched must keep following the default it ships with, so that changing a default in a later
 * build reaches every account that never rebound it — and so that a payload written before the window could
 * rebind anything decodes to exactly the behaviour it already had.
 */
object GlobalShortcutBindings {

    /**
     * **At least two of Ctrl/Shift/Alt.** The claim below swallows the chord for the whole session, so a
     * one-modifier binding would take an everyday shortcut (Ctrl+C, Alt+F4, Shift+letter) away from every
     * other application the user runs, and a zero-modifier one would eat a letter out of their typing. Two is
     * the floor at which a chord stops colliding with the shortcuts applications actually use; the shipped
     * defaults hold all three.
     */
    const val MIN_MODIFIERS: Int = 2

    /** The full live table: every shortcut, carrying the user's override where there is one. */
    fun resolve(overrides: Map<GlobalShortcut, ShortcutBinding>): Map<GlobalShortcut, ShortcutBinding> =
        GlobalShortcut.entries.associateWith { overrides[it] ?: it.defaultBinding }

    /** What [shortcut] is bound to right now — the one lookup every printing surface goes through. */
    fun bindingOf(
        overrides: Map<GlobalShortcut, ShortcutBinding>,
        shortcut: GlobalShortcut,
    ): ShortcutBinding = overrides[shortcut] ?: shortcut.defaultBinding

    /** The live chord of [shortcut], spelled out — for the window, the receipt and the diagnostics. */
    fun chordOf(overrides: Map<GlobalShortcut, ShortcutBinding>, shortcut: GlobalShortcut): String =
        bindingOf(overrides, shortcut).chord

    /**
     * Why binding [shortcut] to [binding] cannot be accepted, or null when it can.
     *
     * The reducer refuses on exactly this answer and the window shows exactly this sentence, so "the app
     * ignored the chord I asked for" is never something the user has to guess at. Re-binding a shortcut to
     * the chord it already holds is **accepted** — that is a no-op, not a conflict with itself.
     */
    fun rejection(
        overrides: Map<GlobalShortcut, ShortcutBinding>,
        shortcut: GlobalShortcut,
        binding: ShortcutBinding,
    ): String? {
        if (binding.modifierCount < MIN_MODIFIERS) {
            return "A system-wide chord needs at least $MIN_MODIFIERS of Ctrl, Shift and Alt — it is " +
                "swallowed before any other application sees it."
        }
        val clash = resolve(overrides).entries.firstOrNull { it.key != shortcut && it.value == binding }
        return clash?.let { "Already bound to \"${it.key.action}\"." }
    }
}

/**
 * How the OS is delivering [GlobalShortcut] right now — reported by [installGlobalHotkeys] and shown in the
 * keyboard-shortcuts window, because "the chord did something else as well" and "the chord did nothing" are
 * both things the user can only diagnose if the app says which claim it actually got.
 */
enum class GlobalHotkeyClaim {
    /** Not attempted yet (the app has not reached the install site).*/
    NotInstalled,

    /** No system-wide chord exists to claim on this platform (Android/iOS/web). */
    Unsupported,

    /**
     * A low-level keyboard hook is in place: the chord is **swallowed** before anything else in the system
     * sees it, so no other application can act on the same press. This is the first-come, first-served claim.
     */
    Exclusive,

    /**
     * Fallback: the chord is a plain `RegisterHotKey` registration. The app is notified, but an application
     * that watches the keyboard through its own low-level hook can still see (and act on) the same press.
     */
    Shared,

    /** Neither claim could be made — another application owns the chords, or the host is not Windows. */
    Unavailable,
}

/** The live [GlobalHotkeyClaim], for the keyboard-shortcuts window. Written only by the platform actual. */
object GlobalHotkeys {
    private val _claim = MutableStateFlow(GlobalHotkeyClaim.NotInstalled)
    val claim: StateFlow<GlobalHotkeyClaim> = _claim.asStateFlow()

    /** Platform-actual seam: record what the OS granted. */
    fun reportClaim(claim: GlobalHotkeyClaim) {
        _claim.value = claim
    }
}

/**
 * PRD §7/§15: register every [GlobalShortcut] with the OS — not as a Compose key handler — at the chord
 * [bindings] resolves it to, and poke [onShortcut] each time one is struck.
 *
 * Registered with the OS because these chords are used at exactly the moment the app is *not* the focused
 * window: the user is walking away from, or resting their eyes in the middle of, whatever they were working
 * in. A focus-scoped handler would only ever fire when OmniApp already has the keyboard — the one case where
 * the lateral-menu button is already one click away.
 *
 * **The chord must be consumed, not merely observed.** `Ctrl+Shift+Alt+A` is also a shortcut inside other
 * applications (Google Docs opens its comments pane on it), and a chord that fires two unrelated actions at
 * once is worse than no chord at all. So on Windows the claim is a **low-level keyboard hook** that swallows
 * the key ([GlobalHotkeyClaim.Exclusive]) — first come, first served, the app that hooks the event first
 * decides nobody else sees it — with `RegisterHotKey` kept underneath as the fallback
 * ([GlobalHotkeyClaim.Shared]).
 *
 * Semantics belong to the caller (the toggle flips the same per-device flag the left-menu button does), so
 * this seam reports the press and nothing else.
 *
 * **Desktop-only.** Android/iOS have no system-wide shortcut for a background app to claim, so their actuals
 * are inert. Best-effort even on desktop: a non-Windows host, or chords another application already owns,
 * leaves the app running with no shortcut rather than failing to start (the outcome is logged to
 * [Diagnostics] and published as [GlobalHotkeys.claim], which is what `scripts\collect-diagnostics.bat` and
 * the keyboard-shortcuts window show).
 *
 * Idempotent, mirroring [installPlatformActivityListener]: the OS claim is made at most once per process, so
 * recomposition can never take the chords twice. A later call **re-points [onShortcut] and re-registers
 * [bindings]** — which is how a rebinding made in the keyboard-shortcuts window takes effect with no restart.
 */
expect fun installGlobalHotkeys(
    bindings: Map<GlobalShortcut, ShortcutBinding>,
    onShortcut: (GlobalShortcut) -> Unit,
)

/**
 * PRD §7: suspend the claim while the keyboard-shortcuts window is **listening for a new chord**.
 *
 * Without this, the chords the app already owns would be the one set of chords the user cannot rebind onto:
 * the hook swallows them (and `RegisterHotKey` consumes them underneath) before Compose is ever handed the
 * key, so pressing the chord you want to *move* would fire its action instead of being captured. While
 * capturing, the hook passes every key straight through, the hot-key registrations are dropped, and no press
 * fires an action.
 *
 * Must be balanced — the window turns it off when the capture is taken, cancelled, or the window closes.
 * Inert wherever [installGlobalHotkeys] is.
 */
expect fun setGlobalHotkeyCapture(capturing: Boolean)
