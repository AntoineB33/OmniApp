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
