package org.example.project.scheduler.platform

import java.util.concurrent.TimeUnit

/**
 * PRD §15 (desktop / Windows): read the Kernel-Power sleep (42/506) and wake (1/131/507) events from the
 * System event log via PowerShell, pair them most-recent-first, and return the wake time (epoch millis) of
 * the most recent cycle whose sleep lasted at least [minSleepMillis]. PowerShell does the pairing and emits
 * a single epoch-millis number. Returns null on any failure — a non-Windows desktop (no `powershell`), a
 * timeout, or no qualifying cycle — so the scheduler falls back to the midnight grid.
 *
 * Best-effort and side-effect-free; called once at launch off the UI thread.
 */
actual fun lastWakeAfterLongSleepMillis(minSleepMillis: Long): Long? = runCatching {
    val minMinutes = minSleepMillis / 60_000.0
    // PowerShell variables are written as ${'$'}name so Kotlin doesn't try to interpolate them.
    val script =
        """
        ${'$'}sleep = Get-WinEvent -FilterHashtable @{LogName='System';ProviderName='Microsoft-Windows-Kernel-Power';ID=42,506} -MaxEvents 40 -ErrorAction SilentlyContinue
        ${'$'}wake  = Get-WinEvent -FilterHashtable @{LogName='System';ProviderName='Microsoft-Windows-Kernel-Power';ID=1,131,507} -MaxEvents 40 -ErrorAction SilentlyContinue
        ${'$'}n = [Math]::Min(@(${'$'}sleep).Count, @(${'$'}wake).Count)
        for (${'$'}i = 0; ${'$'}i -lt ${'$'}n; ${'$'}i++) {
            ${'$'}s = ${'$'}sleep[${'$'}i].TimeCreated
            ${'$'}w = ${'$'}wake[${'$'}i].TimeCreated
            if (${'$'}w -gt ${'$'}s -and (${'$'}w - ${'$'}s).TotalMinutes -ge $minMinutes) {
                [long]([DateTimeOffset]${'$'}w).ToUnixTimeMilliseconds()
                break
            }
        }
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
