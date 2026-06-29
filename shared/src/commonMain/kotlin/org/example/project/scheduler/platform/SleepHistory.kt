package org.example.project.scheduler.platform

/**
 * PRD §15 Side tasks: the epoch millis of the most recent time the machine **woke after a sleep of at least
 * [minSleepMillis]**, read from the OS sleep/wake history, or null when it can't be determined (unsupported
 * platform, no permission, or no qualifying sleep on record). At launch the scheduler seeds each side task's
 * last-rest time with this (using the task's own pause length as [minSleepMillis]) so a pause the user already
 * slept through is shown next at its due time rather than overdue; null leaves it overdue at the now-line.
 */
expect fun lastWakeAfterLongSleepMillis(minSleepMillis: Long): Long?

/**
 * PRD §15 device-sleep gaps: one exact interval the machine was asleep — went to sleep at [startMillis],
 * woke at [endMillis] (epoch millis), read from the OS sleep/wake history.
 */
data class DeviceSleepGap(val startMillis: Long, val endMillis: Long)

/**
 * PRD §15: every device-sleep interval the OS recorded whose **wake** is at or after [sinceMillis], oldest
 * first. On wake the scheduler queries this to record the *exact* `[sleep_start, sleep_end]` of the pause it
 * just missed (rather than the coarse tick-gap boundaries) into the synced gaps table, so other devices pull
 * exact pause times. Returns an empty list when it can't be determined (unsupported platform, no permission,
 * or nothing on record) — the caller then falls back to the inexact tick-gap interval.
 */
expect fun recentSleepGaps(sinceMillis: Long): List<DeviceSleepGap>
