package org.example.project.scheduler.platform

import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.example.project.scheduler.domain.WorkplaceDomain
import org.example.project.scheduler.domain.WorkplaceOs
import org.example.project.scheduler.domain.WorkplaceSetup

actual fun workplaceOsHere(): WorkplaceOs? {
    val name = System.getProperty("os.name").orEmpty()
    if (!name.startsWith("Windows")) return null
    // A runtime older than Windows 11 calls it Windows 10: the build number tells them apart.
    return WorkplaceOs.Windows11.takeIf { name == "Windows 11" || windowsBuild() >= FIRST_WINDOWS_11_BUILD }
}

private const val FIRST_WINDOWS_11_BUILD = 22000

private fun windowsBuild(): Int =
    runCatching {
        val process = ProcessBuilder("cmd", "/c", "ver").redirectErrorStream(true).start()
        val text = process.inputStream.bufferedReader().readText()
        process.waitFor(5, TimeUnit.SECONDS)
        Regex("""\d+\.\d+\.(\d+)""").find(text)?.groupValues?.get(1)?.toInt()
    }.getOrNull() ?: 0

/** The longest a setup may take: a window a few seconds, a tab about two. */
private const val APPLY_TIMEOUT_SECONDS = 120L

/** Listing the tabs types nothing and waits for nothing. */
private const val LIST_TIMEOUT_SECONDS = 20L

/** One at a time: a script needs the foreground for its keys, and two would take it from each other. */
private val applying = Any()

/** Runs [script], encoded so no path can reach the command line as anything but its text; its output, or what went wrong. */
private fun runPowerShell(script: String, timeoutSeconds: Long): Result<String> =
    runCatching {
        val encoded = Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE))
        val process =
            ProcessBuilder(
                "powershell", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-ExecutionPolicy", "Bypass",
                "-EncodedCommand", encoded,
            ).redirectErrorStream(true).start()
        // Read aside: read here, a script that hangs would never reach the timeout, and would hold the others.
        var output = ""
        val reading = thread(isDaemon = true, name = "workplace-setup-output") {
            output = process.inputStream.bufferedReader(Charsets.UTF_8).readText()
        }
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            error("The workplace setup took too long and was stopped.")
        }
        reading.join(1_000)
        if (process.exitValue() != 0) {
            error(output.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: "The workplace setup failed.")
        }
        output
    }

actual fun applyWorkplaceSetup(setup: WorkplaceSetup, onDone: (error: String?) -> Unit) {
    thread(isDaemon = true, name = "workplace-setup") {
        val error =
            synchronized(applying) {
                runPowerShell(WorkplaceDomain.WINDOWS_11_LISTING, LIST_TIMEOUT_SECONDS).mapCatching { listing ->
                    val steps = WorkplaceDomain.plan(setup, WorkplaceDomain.openWindows(listing))
                    // All there already: nothing is opened, nothing typed.
                    if (steps.isNotEmpty()) runPowerShell(WorkplaceDomain.windows11Script(steps), APPLY_TIMEOUT_SECONDS).getOrThrow()
                }.exceptionOrNull()?.let { it.message ?: "The workplace setup failed." }
            }
        org.example.project.scheduler.platform.Diagnostics.log("workplace setup ${setup.id}: " + (error ?: "applied"))
        onDone(error)
    }
}
