package org.example.project.scheduler.platform

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * PRD §15: the app's **system-wide** chords — the ones that must fire while OmniApp is *not* the focused
 * window, because the moment each of them is wanted is a moment the user is looking at some other
 * application.
 *
 * Both are `Ctrl+Shift+Alt+<letter>`: a three-modifier chord is deliberately awkward, so it is unlikely to
 * be sitting under the user's fingers by accident, and it is the shape Windows lets a background process
 * claim without a driver.
 */
enum class GlobalShortcut(
    /** Human-readable chord, the one string the UI and the diagnostics both print. */
    val chord: String,
    /** What pressing it does, in the words the lateral menu uses. */
    val action: String,
) {
    /** Flips the per-device "I'm away" / "I'm back" flag — the user is leaving (or back at) this machine. */
    ToggleAway("Ctrl+Shift+Alt+A", "I'm away / I'm back"),

    /** Takes the 20-second look-away now, superseding any look-away still sounding or pending. */
    LookAwayNow("Ctrl+Shift+Alt+E", "Look away now"),
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
 * PRD §15: register every [GlobalShortcut] with the OS — not as a Compose key handler — and poke
 * [onShortcut] each time one is struck.
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
 * Idempotent, mirroring [installPlatformActivityListener]: the OS registration is made at most once per
 * process and later calls only replace [onShortcut], so recomposition can never claim the chords twice.
 */
expect fun installGlobalHotkeys(onShortcut: (GlobalShortcut) -> Unit)
