package org.example.project.scheduler.platform

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.example.project.scheduler.persistence.stateDirectory

private fun onWindows(): Boolean = System.getProperty("os.name").orEmpty().startsWith("Windows")

/** On Windows, where a terminal is opened by `start` (the build's other desktops: not yet). */
actual fun claudeCodeOpensHere(): Boolean = onWindows()

/**
 * The folder the md elements are written to: the state directory's own, so a dev or test build (its own `omniapp.stateDir`)
 * never writes into the release app's.
 */
internal fun mdFolder(): File = File(stateDirectory(), "md")

/** Writes [files] into [folder], its `.md` files written before taken out first; nothing else of it is touched. */
internal fun writeMdFolder(folder: File, files: Map<String, String>) {
    folder.mkdirs()
    folder.listFiles { f -> f.isFile && f.name.endsWith(".md", ignoreCase = true) }?.forEach { it.delete() }
    for ((name, text) in files) File(folder, name).writeText(text, Charsets.UTF_8)
}

actual fun openClaudeCodeOn(files: Map<String, String>, onDone: (folder: String?, error: String?) -> Unit) {
    thread(isDaemon = true, name = "claude-code-launch") {
        val result = runCatching {
            if (!onWindows()) error("This device does not open Claude Code yet: Windows only.")
            val folder = mdFolder()
            writeMdFolder(folder, files)
            val where = ProcessBuilder("cmd", "/c", "where", "claude").redirectErrorStream(true).start()
            if (!where.waitFor(10, TimeUnit.SECONDS) || where.exitValue() != 0) {
                where.destroyForcibly()
                error("The md files are in ${folder.path}, but Claude Code (claude) is not on this device's PATH.")
            }
            // A terminal of its own in the folder, Claude Code in it: `start` returns at once and the terminal stays.
            ProcessBuilder("cmd", "/c", "start", "Claude Code", "/D", folder.path, "cmd", "/k", "claude")
                .directory(folder).redirectErrorStream(true).start()
            folder.path
        }
        result.fold({ onDone(it, null) }, { onDone(null, it.message ?: "Claude Code could not be opened.") })
    }
}
