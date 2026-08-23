package org.example.project.scheduler.platform

/**
 * PRD §15: inert on Android. A backgrounded app cannot claim a system-wide key chord, and a phone has no
 * keyboard to strike one on — the lateral menu's own buttons are the phone's whole surface for this.
 */
actual fun installGlobalHotkeys(onShortcut: (GlobalShortcut) -> Unit) =
    GlobalHotkeys.reportClaim(GlobalHotkeyClaim.Unsupported)
