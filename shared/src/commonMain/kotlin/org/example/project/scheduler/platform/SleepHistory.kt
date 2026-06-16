package org.example.project.scheduler.platform

/**
 * PRD §15 Side tasks: the epoch millis of the most recent time the machine **woke after a sleep of at least
 * [minSleepMillis]**, read from the OS sleep/wake history, or null when it can't be determined (unsupported
 * platform, no permission, or no qualifying sleep on record). At launch the scheduler seeds each side task's
 * last-rest time with this (using the task's own pause length as [minSleepMillis]) so a pause the user already
 * slept through is shown next at its due time rather than overdue; null leaves it overdue at the now-line.
 */
expect fun lastWakeAfterLongSleepMillis(minSleepMillis: Long): Long?
