package org.example.project.scheduler.platform

/**
 * The browser cannot claim a system-wide chord while the app is backgrounded, so the live claim stays
 * unsupported and the rest of the app keeps the same best-effort behavior as iOS/Android.
 */
actual fun installGlobalHotkeys(
    bindings: Map<GlobalShortcut, ShortcutBinding>,
    onShortcut: (GlobalShortcut) -> Unit,
) = GlobalHotkeys.reportClaim(GlobalHotkeyClaim.Unsupported)

actual fun setGlobalHotkeyCapture(capturing: Boolean) = Unit
