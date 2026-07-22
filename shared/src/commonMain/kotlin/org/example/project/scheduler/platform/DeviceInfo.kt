package org.example.project.scheduler.platform

/**
 * What kind of device this install runs on. Used by PRD §15 cross-device presence: only the [Phone]
 * speaks the "pause finished" voice cue, and the kind is published with each device's presence heartbeat.
 */
enum class DeviceKind { Phone, Desktop, Other }

/** The kind of device this install runs on (derived from the platform, never persisted). */
expect fun currentDeviceKind(): DeviceKind

/**
 * Whether THIS device is currently **active** (the user is present) — Android: the app is in the
 * FOREGROUND (the phone's only activity signal; screen-on with the app backgrounded is inactive);
 * desktop: there was recent pointer activity. Drives PRD §15's "no device on the account is active"
 * gate and the active-session tracking. Best-effort; returns `false` when it cannot tell (headless).
 */
expect fun isScreenActive(): Boolean

/**
 * PRD §15: register a listener that pokes [onChanged] whenever this device's platform activity signal flips,
 * so the device_heartbeat resumes/closes within moments of the change instead of at the next minute beat.
 * On **desktop** this hooks the OS session **lock/unlock** events (Windows `WM_WTSSESSION_CHANGE`), mirroring
 * Android's unlock broadcasts. A **no-op** where the engine is already poked directly (Android wires
 * `AndroidUnlockTracker.onChanged` in `SchedulerHolder`) or where there is no such signal (iOS/web).
 * Idempotent: the listener is installed at most once; later calls only replace [onChanged].
 */
expect fun installPlatformActivityListener(onChanged: () -> Unit)
