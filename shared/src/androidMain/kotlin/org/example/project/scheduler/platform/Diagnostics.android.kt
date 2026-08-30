package org.example.project.scheduler.platform

import android.util.Log
import java.io.File
import org.example.project.scheduler.persistence.AndroidSchedulerStoreHolder

private val diagnosticsLock = Any()
private const val DIAGNOSTICS_MAX_BYTES = 2_000_000L

actual fun appendDiagnosticsLine(line: String) {
    // Logcat mirror for live viewing: `adb logcat -s OmniDiag`.
    // Android host tests intentionally do not mock `Log`, so a diagnostics write must be best-effort and
    // never crash the app or the test harness: the log is diagnostic-only, not business logic.
    runCatching { Log.i("OmniDiag", line) }
    // Durable copy in filesDir; on a debug build scripts/collect-diagnostics.bat reads it with
    // `adb shell run-as org.example.project cat files/diagnostics.log`.
    val ctx = AndroidSchedulerStoreHolder.context ?: return
    runCatching {
        synchronized(diagnosticsLock) {
            val f = File(ctx.filesDir, "diagnostics.log")
            if (f.length() > DIAGNOSTICS_MAX_BYTES) {
                val old = File(ctx.filesDir, "diagnostics.log.old")
                old.delete()
                f.renameTo(old)
            }
            f.appendText(line + "\n")
        }
    }
}
