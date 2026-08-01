package org.example.project.scheduler.platform

/**
 * PRD §18 Alarms: inert on Android. The phone's engine is built by `SchedulerHolder`, which wires the
 * `ringAlarm` seam straight to `AlarmRingService` (a foreground service playing the alarm ringtone on the
 * alarm audio stream + vibrating), so this common seam must never ring a second time.
 */
actual fun ringAlarmPlatform(label: String, soundSeconds: Int, vibrate: Boolean) = Unit
