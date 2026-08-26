package org.example.project.scheduler.platform

/**
 * PRD §15: inert on Android. A backgrounded app cannot claim a system-wide key chord, and a phone has no
 * keyboard to strike one on — the lateral menu's own buttons are the phone's whole surface for this. The
 * account's bindings still ride the sync (the desktop is what they are for), so this actual ignores them.
 */
actual fun installGlobalHotkeys(
    bindings: Map<GlobalShortcut, ShortcutBinding>,
    onShortcut: (GlobalShortcut) -> Unit,
) = GlobalHotkeys.reportClaim(GlobalHotkeyClaim.Unsupported)

/** Nothing is claimed here, so there is nothing to stand down while a chord is being captured. */
actual fun setGlobalHotkeyCapture(capturing: Boolean) = Unit
