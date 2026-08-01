package org.example.project.scheduler.platform

/** PRD §18 Alarms: no ring in the browser build (no reliable background audio). */
actual fun ringAlarmPlatform(label: String, soundSeconds: Int, vibrate: Boolean) = Unit
