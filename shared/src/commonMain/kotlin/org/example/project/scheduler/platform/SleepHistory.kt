package org.example.project.scheduler.platform

/**
 * PRD §15 Side tasks cadence anchor: the epoch millis of the most recent time the machine **woke after a
 * sleep of at least [minSleepMillis]**, read from the OS sleep/wake history, or null when it can't be
 * determined (unsupported platform, no permission, or no qualifying sleep on record). The scheduler uses
 * this to restart the side-task cadence when the user returns to the machine; null falls back to the grid.
 */
expect fun lastWakeAfterLongSleepMillis(minSleepMillis: Long): Long?
