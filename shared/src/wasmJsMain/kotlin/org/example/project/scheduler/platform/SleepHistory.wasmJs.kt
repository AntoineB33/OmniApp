package org.example.project.scheduler.platform

actual fun lastWakeAfterLongSleepMillis(minSleepMillis: Long): Long? = null

actual fun recentSleepGaps(sinceMillis: Long): List<DeviceSleepGap> = emptyList()

actual fun deviceLockedIntervals(sinceMillis: Long, untilMillis: Long): List<DeviceSleepGap>? = null
