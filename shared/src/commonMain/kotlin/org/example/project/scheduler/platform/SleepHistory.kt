package org.example.project.scheduler.platform

/**
 * PRD §15 Screen breaks: the epoch millis of the most recent time the machine **woke after a sleep of at least
 * [minSleepMillis]**, read from the OS sleep/wake history, or null when it can't be determined (unsupported
 * platform, no permission, or no qualifying sleep on record). At launch the scheduler seeds each screen break's
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

/**
 * PRD §8 calendar layers: the intervals within `[sinceMillis, untilMillis]` during which THIS device was
 * **not unlocked** — the screen was locked where the OS exposes that, otherwise the machine was asleep or in
 * standby (the spec's own fallback: "lock/unlock, or if not possible sleep/awake"). Oldest first, clipped to
 * the window; an interval still open at [untilMillis] ends there.
 *
 * **null means "this device cannot tell"**, and that is a different answer from an empty list. Empty says the
 * device was unlocked for the whole window; null says nothing is known, and the calendar then draws no layer
 * for that device at all — a device whose history is unavailable is **assumed to have been unlocked**, so a
 * first run on a computer never claims the user's phone was locked all week.
 *
 * Called off the UI thread, bounded by the span the calendar is DISPLAYING — there is no reason to ask the OS
 * about days that are not on screen, and scrolling further back asks again.
 */
expect fun deviceLockedIntervals(sinceMillis: Long, untilMillis: Long): List<DeviceSleepGap>?
