package org.example.project.scheduler.platform

/**
 * PRD §18 Alarms: unimplemented on iOS, like the rest of its ring/push path (which waits on the Mac build).
 * A silent no-op rather than a partial ring, so nothing half-works.
 */
actual fun ringAlarmPlatform(label: String, soundSeconds: Int, vibrate: Boolean) = Unit
