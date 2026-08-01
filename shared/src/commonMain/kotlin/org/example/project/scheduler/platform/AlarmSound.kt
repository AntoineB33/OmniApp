package org.example.project.scheduler.platform

/**
 * PRD §18 Alarms: play the alarm sound on THIS device for [soundSeconds], vibrating alongside it when
 * [vibrate] and the platform can. Returns immediately — the ring plays in the background.
 *
 * The seam the engine `App()` builds itself uses (desktop/web/iOS), mirroring
 * [scheduleLocalPauseCuePlatform]. Android does **not** come through here: its engine is built by
 * `SchedulerHolder`, which injects `AlarmRingService` directly (a foreground service, because a phone's ring
 * must outlive the broadcast receiver's process); its actual is inert so the wiring can never double-ring.
 *
 * A new ring supersedes one still sounding, so two alarms due together never overlap.
 */
expect fun ringAlarmPlatform(label: String, soundSeconds: Int, vibrate: Boolean)
