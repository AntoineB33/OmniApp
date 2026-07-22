package org.example.project.scheduler.platform

// An iOS install is a phone for PRD §15 purposes (only the phone speaks the pause-finished cue).
actual fun currentDeviceKind(): DeviceKind = DeviceKind.Phone

// Best-effort: UIApplication.applicationState is main-thread-only and this may be called off-thread, so we
// report the conservative "cannot tell" (false). On iOS the pause-end cue is delivered as an OS-scheduled
// local notification anyway, so this gate mainly affects whether this phone counts as an active *peer*.
actual fun isScreenActive(): Boolean = false

// No-op: iOS has no desktop-style session lock signal here, and the pause-end cue is an OS-scheduled local
// notification regardless of this device's active-peer state.
actual fun installPlatformActivityListener(onChanged: () -> Unit) {}
