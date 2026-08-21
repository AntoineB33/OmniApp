package org.example.project.scheduler.platform

/**
 * PRD §15: register the **system-wide** "I'm away" shortcut — `Ctrl+Shift+Alt+A` — and poke [onPressed]
 * every time it is struck.
 *
 * Registered with the OS, not as a Compose key handler, because the shortcut is used at exactly the moment
 * the app is *not* the focused window: the user is leaving the machine and presses it from whatever they
 * were working in. A focus-scoped handler would only fire when OmniApp already has the keyboard, which is
 * the one case the button beside it already covers.
 *
 * Toggling semantics belong to the caller (the shortcut flips the same per-device away flag the left-menu
 * button does), so this seam reports the press and nothing else.
 *
 * **Desktop-only.** Android/iOS have no system-wide shortcut for a background app to claim, so their actuals
 * are inert. Best-effort even on desktop: a non-Windows host, or a chord another application already owns,
 * leaves the app running with no shortcut rather than failing to start (the failure is logged to
 * [Diagnostics], which is where `scripts\collect-diagnostics.bat` will show it).
 *
 * Idempotent, mirroring [installPlatformActivityListener]: the OS registration is made at most once per
 * process and later calls only replace [onPressed], so recomposition can never register the chord twice.
 */
expect fun installGlobalAwayHotkey(onPressed: () -> Unit)
