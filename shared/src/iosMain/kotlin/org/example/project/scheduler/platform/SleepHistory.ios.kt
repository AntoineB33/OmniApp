package org.example.project.scheduler.platform

// iOS exposes no public OS sleep/wake log, so device-sleep gaps are not recoverable here.
actual fun lastWakeAfterLongSleepMillis(minSleepMillis: Long): Long? = null

actual fun recentSleepGaps(sinceMillis: Long): List<DeviceSleepGap> = emptyList()

// PRD §8: no public lock/unlock or sleep/wake history on iOS either — "cannot tell", so no layer is drawn.
actual fun deviceLockedIntervals(sinceMillis: Long, untilMillis: Long): List<DeviceSleepGap>? = null
