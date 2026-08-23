package org.example.project.scheduler.platform

/**
 * PRD §15: inert on iOS, for the same reason as Android — there is no system-wide shortcut an app can
 * register for while it is not frontmost.
 */
actual fun installGlobalHotkeys(onShortcut: (GlobalShortcut) -> Unit) =
    GlobalHotkeys.reportClaim(GlobalHotkeyClaim.Unsupported)
