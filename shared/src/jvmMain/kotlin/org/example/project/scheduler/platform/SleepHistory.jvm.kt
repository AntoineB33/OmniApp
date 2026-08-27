package org.example.project.scheduler.platform

/**
 * How many events the two unbounded queries fetch. They are asked once at launch and want only the recent
 * past, so the newest few hundred transitions are plenty — a machine slept and woken twenty times a day still
 * reaches back a week.
 */
private const val RECENT_EVENTS = 240

/**
 * The window query's ceiling. It is bounded by `StartTime`/`EndTime` already, so this only guards a log with
 * pathological churn; note that `MaxEvents` truncates to the NEWEST matches, so a truncation loses the far
 * side of the window rather than the near one.
 */
private const val WINDOW_EVENTS = 1000

/**
 * PRD §15 (desktop / Windows): read the power history from the System event log via PowerShell and return the
 * time (epoch millis) the machine came back from the most recent absence of at least [minSleepMillis].
 * Returns null on any failure — a non-Windows desktop (no `powershell`), a timeout, or no qualifying absence
 * — so the scheduler falls back to the midnight grid.
 *
 * "Absence" is sleep, standby, shutdown or power loss: the user was equally away for all four, and a machine
 * switched off overnight must seed the screen breaks exactly as one that slept. [WindowsPowerLog] owns the
 * event ids, the debouncing and the pairing.
 *
 * Best-effort and side-effect-free; called once at launch off the UI thread.
 */
actual fun lastWakeAfterLongSleepMillis(minSleepMillis: Long): Long? {
    val lines = WindowsPowerLog.run(WindowsPowerLog.script(RECENT_EVENTS), timeoutSeconds = 8)
    val transitions = WindowsPowerLog.transitions(lines) ?: return null
    return WindowsPowerLog.intervals(transitions)
        .lastOrNull { it.endMillis - it.startMillis >= minSleepMillis }
        ?.endMillis
}

/**
 * PRD §15 (desktop / Windows): every absence the OS recorded whose **end** is at or after [sinceMillis],
 * oldest first, as an exact `[start, end]` pair (epoch millis). Returns an empty list on any failure (a
 * non-Windows desktop with no `powershell`, a timeout, or nothing on record) so the caller falls back to the
 * coarse tick-gap interval.
 */
actual fun recentSleepGaps(sinceMillis: Long): List<DeviceSleepGap> {
    val lines = WindowsPowerLog.run(WindowsPowerLog.script(RECENT_EVENTS), timeoutSeconds = 8)
    val transitions = WindowsPowerLog.transitions(lines) ?: return emptyList()
    return WindowsPowerLog.intervals(transitions).filter { it.endMillis >= sinceMillis }
}

/**
 * PRD §8 (desktop / Windows): this device's own "not unlocked" history over `[sinceMillis, untilMillis]`.
 *
 * The spec asks for **lock/unlock** first and allows **sleep/awake** where that is not possible, and on
 * Windows it is not possible without elevation: the lock/unlock records (Security 4800/4801) need the "Other
 * Logon/Logoff Events" audit policy enabled AND an administrative reader, and the non-elevated alternatives
 * are no substitute — `Microsoft-Windows-Winlogon/Operational` only logs which notification SUBSCRIBER ran
 * (811/812), and the TerminalServices session log records logon/logoff/disconnect, not lock. So this reads the
 * non-elevated power history ([WindowsPowerLog]) instead. On a Modern-Standby machine that is closer to
 * lock/unlock than it sounds: `506` fires when the screen goes off, which is exactly the moment the device
 * stops being unlocked — and the shutdown/boot ids cover the machine that is switched off rather than slept.
 *
 * Returns **null** when the query cannot be answered at all (no `powershell`, a timeout, an error, or a log
 * this process may not read) — the caller then draws no layer for this device rather than claiming it was
 * locked, and the record bank treats it as no evidence. An empty list is the other answer: the log was read
 * and the device was never away in the window.
 *
 * Bounded by the window: `StartTime`/`EndTime` are passed to the log query, so asking about the displayed days
 * costs only those days. The state the window OPENS in comes from [WindowsPowerLog.PRIOR_EVENTS] events before
 * it, so a window that begins mid-absence is not read as time at the machine; an absence still open at the end
 * of the window is clipped to [untilMillis].
 */
actual fun deviceLockedIntervals(sinceMillis: Long, untilMillis: Long): List<DeviceSleepGap>? {
    if (untilMillis <= sinceMillis) return emptyList()
    val script = WindowsPowerLog.script(WINDOW_EVENTS, sinceMillis, untilMillis)
    val transitions = WindowsPowerLog.transitions(WindowsPowerLog.run(script, timeoutSeconds = 20)) ?: return null
    return WindowsPowerLog.intervals(transitions, openEndMillis = untilMillis)
        .map { DeviceSleepGap(maxOf(it.startMillis, sinceMillis), minOf(it.endMillis, untilMillis)) }
        .filter { it.endMillis > it.startMillis }
        .sortedBy { it.startMillis }
}
