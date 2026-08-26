package org.example.project.scheduler.platform

/**
 * PRD §15: inert on iOS, for the same reason as Android — there is no system-wide shortcut an app can
 * register for while it is not frontmost. The account's bindings still ride the sync; this actual ignores them.
 */
actual fun installGlobalHotkeys(
    bindings: Map<GlobalShortcut, ShortcutBinding>,
    onShortcut: (GlobalShortcut) -> Unit,
) = GlobalHotkeys.reportClaim(GlobalHotkeyClaim.Unsupported)

/** Nothing is claimed here, so there is nothing to stand down while a chord is being captured. */
actual fun setGlobalHotkeyCapture(capturing: Boolean) = Unit
