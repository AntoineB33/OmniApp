package org.example.project.scheduler.platform

import java.io.File

// Same state-dir resolution as FileSchedulerStore (kept in lockstep): -Domniapp.stateDir →
// OMNIAPP_STATE_DIR → ~/.omniapp. The log sits NEXT to the DB so the account scripts' `[script]`
// markers and this app's lines land in the same per-account file — and an account-empty (which
// deletes only scheduler-state.db*) leaves the timeline intact.
private val diagnosticsFile: File by lazy {
    val override =
        (System.getProperty("omniapp.stateDir")?.takeIf { it.isNotBlank() }
            ?: System.getenv("OMNIAPP_STATE_DIR")?.takeIf { it.isNotBlank() })
    val dir = if (override != null) File(override) else File(System.getProperty("user.home"), ".omniapp")
    dir.mkdirs()
    File(dir, "diagnostics.log")
}

private val diagnosticsLock = Any()
private const val DIAGNOSTICS_MAX_BYTES = 2_000_000L

actual fun appendDiagnosticsLine(line: String) {
    // Echo to the console too, so a `gradlew :desktopApp:run` window shows the timeline live.
    println(line)
    runCatching {
        synchronized(diagnosticsLock) {
            val f = diagnosticsFile
            if (f.length() > DIAGNOSTICS_MAX_BYTES) {
                val old = File(f.parentFile, "diagnostics.log.old")
                old.delete()
                f.renameTo(old)
            }
            f.appendText(line + "\n")
        }
    }
}
