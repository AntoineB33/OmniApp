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

actual fun forEachClaudeLogLine(path: String, fromByte: Long, onLine: (String) -> Unit): Long =
    runCatching { readLogLines(File(path), fromByte, onLine) }.getOrDefault(fromByte)

private fun readLogLines(file: File, fromByte: Long, onLine: (String) -> Unit): Long {
    val tail = file.inputStream().use { input ->
        var left = fromByte
        while (left > 0) {
            val skipped = input.skip(left)
            if (skipped <= 0) return fromByte
            left -= skipped
        }
        input.readBytes()
    }
    val end = tail.lastIndexOf('\n'.code.toByte())
    if (end < 0) return fromByte
    String(tail, 0, end + 1, Charsets.UTF_8).lineSequence().filter { it.isNotEmpty() }.forEach(onLine)
    return fromByte + end + 1
}

// No Claude Code runs on a phone: its logs are written elsewhere.
actual fun watchClaudeLogs(directory: String, onChange: () -> Unit): AutoCloseable? = null

// A phone's storage is shared with everything else on it: about half a year of logs.
actual val claudeLogArchiveLimitBytes: Long = 20L * 1024 * 1024
