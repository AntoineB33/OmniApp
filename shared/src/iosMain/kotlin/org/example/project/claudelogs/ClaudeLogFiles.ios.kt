package org.example.project.claudelogs

// No file system to read Claude Code's logs from here: the page says so.
actual val claudeLogFilesSupported: Boolean = false

actual fun listClaudeLogFiles(directory: String): List<ClaudeLogFile>? = null

actual fun forEachClaudeLogLine(path: String, onLine: (String) -> Unit) {}

actual fun defaultClaudeLogsDirectory(): String? = null

// A phone's storage is shared with everything else on it: about half a year of logs.
actual val claudeLogArchiveLimitBytes: Long = 20L * 1024 * 1024
