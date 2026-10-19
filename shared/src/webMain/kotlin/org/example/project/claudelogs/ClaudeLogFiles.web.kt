package org.example.project.claudelogs

// No file system to read Claude Code's logs from here: the page says so.
actual val claudeLogFilesSupported: Boolean = false

actual fun listClaudeLogFiles(directory: String): List<ClaudeLogFile>? = null

actual fun forEachClaudeLogLine(path: String, onLine: (String) -> Unit) {}

actual fun defaultClaudeLogsDirectory(): String? = null

// Held in the page's memory only (the web store keeps nothing): what the server sends, with room to spare.
actual val claudeLogArchiveLimitBytes: Long = 5L * 1024 * 1024
