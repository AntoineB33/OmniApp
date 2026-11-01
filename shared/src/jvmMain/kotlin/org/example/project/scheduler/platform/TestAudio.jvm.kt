package org.example.project.scheduler.platform

import java.io.File

/**
 * **A test run makes no sound and shows no notification** (anomaly 2026-11-01: the gate spoke and notified on the user's
 * speakers while they waited for it). An engine a test builds keeps the platform's own voice and notifier unless the test
 * replaces them, and no test should have to remember to. Every `Test` task of `shared/build.gradle.kts` sets
 * `omniapp.silentAudio` to a file, and the desktop's three ways out to the user answer here first; what a run would have
 * played is written there, so it can be read instead of heard.
 */
internal object TestAudio {
    private val log: File? = System.getProperty("omniapp.silentAudio")?.takeIf { it.isNotBlank() }?.let(::File)

    /** Whether this process is a test run, so [what] must not go out. */
    fun refuses(kind: String, what: String): Boolean {
        val file = log ?: return false
        runCatching { file.appendText("$kind\t${what.take(160)}\n") }
        return true
    }
}
