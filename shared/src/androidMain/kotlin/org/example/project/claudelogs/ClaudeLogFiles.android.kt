package org.example.project.claudelogs

import java.io.File

actual val claudeLogFilesSupported: Boolean = true

actual fun listClaudeLogFiles(directory: String): List<ClaudeLogFile>? {
    val root = File(directory.trim())
    if (directory.isBlank() || !root.isDirectory) return null
    val out = ArrayList<ClaudeLogFile>()
    fun addLogsOf(folder: File) {
        folder.listFiles { f -> f.isFile && f.name.endsWith(".jsonl") }?.forEach {
            out += ClaudeLogFile(it.path, folder.name, it.length(), it.lastModified())
        }
    }
    addLogsOf(root)
    root.listFiles { f -> f.isDirectory }?.forEach(::addLogsOf)
    return out
}

// A phone holds no Claude Code logs of its own: the directory is the user's to give (a folder copied there).
actual fun defaultClaudeLogsDirectory(): String? = null

actual fun forEachClaudeLogLine(path: String, onLine: (String) -> Unit) {
    runCatching { File(path).bufferedReader(Charsets.UTF_8).useLines { lines -> lines.forEach(onLine) } }
}

// A phone's storage is shared with everything else on it: about half a year of logs.
actual val claudeLogArchiveLimitBytes: Long = 20L * 1024 * 1024
