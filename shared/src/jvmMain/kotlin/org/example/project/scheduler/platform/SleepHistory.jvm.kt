package org.example.project.scheduler.platform

import java.util.concurrent.TimeUnit

/**
 * PRD §15 (desktop / Windows): read the Kernel-Power sleep (42/506) and wake (1/131/507) events from the
 * System event log via PowerShell and return the wake time (epoch millis) of the most recent cycle whose
 * sleep lasted at least [minSleepMillis]. Returns null on any failure — a non-Windows desktop (no
 * `powershell`), a timeout, or no qualifying cycle — so the scheduler falls back to the midnight grid.
 *
 * The two event kinds are merged into ONE time-ordered timeline and walked oldest→newest, pairing each wake
 * with the most recent unmatched sleep. Index-pairing two separate most-recent-first lists (the previous
 * approach) is wrong: the lists hold unequal counts and don't alternate cleanly (e.g. two `506` enters in a
 * row, or a brief enter one second before the real wake), so `sleep[i]`/`wake[i]` mismatch and a genuine
 * 10-hour overnight sleep is reported as never having happened. A run of consecutive enters counts from the
 * first (the away period started there); the most recent qualifying wake wins.
 *
 * Best-effort and side-effect-free; called once at launch off the UI thread.
 */
actual fun lastWakeAfterLongSleepMillis(minSleepMillis: Long): Long? = runCatching {
    val minMinutes = minSleepMillis / 60_000.0
    // PowerShell variables are written as ${'$'}name so Kotlin doesn't try to interpolate them.
    val script =
        """
        ${'$'}enter = Get-WinEvent -FilterHashtable @{LogName='System';ProviderName='Microsoft-Windows-Kernel-Power';ID=42,506} -MaxEvents 60 -ErrorAction SilentlyContinue
        ${'$'}exit  = Get-WinEvent -FilterHashtable @{LogName='System';ProviderName='Microsoft-Windows-Kernel-Power';ID=1,131,507} -MaxEvents 60 -ErrorAction SilentlyContinue
        ${'$'}all = @()
        foreach (${'$'}e in ${'$'}enter) { ${'$'}all += [pscustomobject]@{ t = ${'$'}e.TimeCreated; sleep = ${'$'}true } }
        foreach (${'$'}e in ${'$'}exit)  { ${'$'}all += [pscustomobject]@{ t = ${'$'}e.TimeCreated; sleep = ${'$'}false } }
        ${'$'}all = ${'$'}all | Sort-Object t
        ${'$'}lastSleep = ${'$'}null
        ${'$'}best = ${'$'}null
        foreach (${'$'}e in ${'$'}all) {
            if (${'$'}e.sleep) {
                if (${'$'}null -eq ${'$'}lastSleep) { ${'$'}lastSleep = ${'$'}e.t }
            } else {
                if (${'$'}null -ne ${'$'}lastSleep) {
                    if ((${'$'}e.t - ${'$'}lastSleep).TotalMinutes -ge $minMinutes) { ${'$'}best = ${'$'}e.t }
                    ${'$'}lastSleep = ${'$'}null
                }
            }
        }
        if (${'$'}null -ne ${'$'}best) { [long]([DateTimeOffset]${'$'}best).ToUnixTimeMilliseconds() }
        """.trimIndent()
    val process =
        ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command", script)
            .redirectErrorStream(true)
            .start()
    if (!process.waitFor(8, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        return@runCatching null
    }
    process.inputStream.bufferedReader().readText()
        .lineSequence()
        .mapNotNull { it.trim().toLongOrNull() }
        .firstOrNull()
}.getOrNull()

/**
 * PRD §15 (desktop / Windows): read every Kernel-Power sleep/wake cycle from the System event log via the
 * same non-elevated PowerShell `Get-WinEvent` query as [lastWakeAfterLongSleepMillis], and return each cycle
 * whose **wake** is at or after [sinceMillis] as an exact `[sleep_start, sleep_end]` pair (epoch millis),
 * oldest first. Returns an empty list on any failure (a non-Windows desktop with no `powershell`, a timeout,
 * or no qualifying cycle) so the caller falls back to the coarse tick-gap interval.
 *
 * The two event kinds are merged into ONE time-ordered timeline and walked oldest→newest, pairing each wake
 * with the most recent unmatched sleep — the exact pairing rationale in [lastWakeAfterLongSleepMillis]. Each
 * line of output is `sleepStartMillis,wakeMillis`.
 */
actual fun recentSleepGaps(sinceMillis: Long): List<DeviceSleepGap> = runCatching {
    // PowerShell variables are written as ${'$'}name so Kotlin doesn't try to interpolate them.
    val script =
        """
        ${'$'}enter = Get-WinEvent -FilterHashtable @{LogName='System';ProviderName='Microsoft-Windows-Kernel-Power';ID=42,506} -MaxEvents 120 -ErrorAction SilentlyContinue
        ${'$'}exit  = Get-WinEvent -FilterHashtable @{LogName='System';ProviderName='Microsoft-Windows-Kernel-Power';ID=1,131,507} -MaxEvents 120 -ErrorAction SilentlyContinue
        ${'$'}all = @()
        foreach (${'$'}e in ${'$'}enter) { ${'$'}all += [pscustomobject]@{ t = ${'$'}e.TimeCreated; sleep = ${'$'}true } }
        foreach (${'$'}e in ${'$'}exit)  { ${'$'}all += [pscustomobject]@{ t = ${'$'}e.TimeCreated; sleep = ${'$'}false } }
        ${'$'}all = ${'$'}all | Sort-Object t
        ${'$'}lastSleep = ${'$'}null
        foreach (${'$'}e in ${'$'}all) {
            if (${'$'}e.sleep) {
                if (${'$'}null -eq ${'$'}lastSleep) { ${'$'}lastSleep = ${'$'}e.t }
            } else {
                if (${'$'}null -ne ${'$'}lastSleep) {
                    ${'$'}s = [long]([DateTimeOffset]${'$'}lastSleep).ToUnixTimeMilliseconds()
                    ${'$'}w = [long]([DateTimeOffset]${'$'}e.t).ToUnixTimeMilliseconds()
                    if (${'$'}w -ge $sinceMillis) { "${'$'}s,${'$'}w" }
                    ${'$'}lastSleep = ${'$'}null
                }
            }
        }
        """.trimIndent()
    val process =
        ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command", script)
            .redirectErrorStream(true)
            .start()
    if (!process.waitFor(8, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        return@runCatching emptyList()
    }
    process.inputStream.bufferedReader().readText()
        .lineSequence()
        .mapNotNull { line ->
            val parts = line.trim().split(',')
            if (parts.size != 2) return@mapNotNull null
            val start = parts[0].toLongOrNull() ?: return@mapNotNull null
            val end = parts[1].toLongOrNull() ?: return@mapNotNull null
            if (end > start) DeviceSleepGap(start, end) else null
        }
        .toList()
}.getOrElse { emptyList() }
