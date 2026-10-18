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

actual fun forEachClaudeLogLine(path: String, onLine: (String) -> Unit) {
    runCatching { File(path).bufferedReader(Charsets.UTF_8).useLines { lines -> lines.forEach(onLine) } }
}
