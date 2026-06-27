package org.example.project.scheduler.platform

/**
 * What kind of device this install runs on. Used by PRD §15 cross-device presence: only the [Phone]
 * speaks the "pause finished" voice cue, and the kind is published with each device's presence heartbeat.
 */
enum class DeviceKind { Phone, Desktop, Other }

/** The kind of device this install runs on (derived from the platform, never persisted). */
expect fun currentDeviceKind(): DeviceKind

/**
 * Whether THIS device currently has an **active screen** (the user is present) — Android: the screen is
 * interactive; desktop: there was recent pointer activity. Drives PRD §15's "no device on the account has
 * an active screen" gate. Best-effort; returns `false` when it cannot tell (headless / no context).
 */
expect fun isScreenActive(): Boolean
