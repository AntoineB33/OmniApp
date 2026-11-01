package org.example.project.claudelogs

import java.io.File
import java.nio.file.ClosedWatchServiceException
import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
import java.nio.file.StandardWatchEventKinds.OVERFLOW
import kotlin.concurrent.thread

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

actual fun defaultClaudeLogsDirectory(): String? =
    System.getProperty("user.home")?.takeIf { it.isNotBlank() }
        ?.let { File(File(it, ".claude"), "projects") }
        ?.takeIf { it.isDirectory }
        ?.path

actual fun forEachClaudeLogLine(path: String, fromByte: Long, onLine: (String) -> Unit): Long =
    runCatching { readLogLines(File(path), fromByte, onLine) }.getOrDefault(fromByte)

private fun readLogLines(file: File, fromByte: Long, onLine: (String) -> Unit): Long {
    val tail = file.inputStream().use { input ->
        input.skipNBytes(fromByte)
        input.readBytes()
    }
    val end = tail.lastIndexOf('\n'.code.toByte())
    if (end < 0) return fromByte
    String(tail, 0, end + 1, Charsets.UTF_8).lineSequence().filter { it.isNotEmpty() }.forEach(onLine)
    return fromByte + end + 1
}

actual fun watchClaudeLogs(directory: String, onChange: () -> Unit): AutoCloseable? {
    val root = File(directory.trim())
    if (directory.isBlank() || !root.isDirectory) return null
    val service = runCatching { FileSystems.getDefault().newWatchService() }.getOrNull() ?: return null
    fun watch(folder: File) {
        runCatching { folder.toPath().register(service, ENTRY_CREATE, ENTRY_MODIFY) }
    }
    // The folders [listClaudeLogFiles] reads: the directory and its sub-folders, one per project.
    watch(root)
    root.listFiles { f -> f.isDirectory }?.forEach(::watch)
    // Blocked in `take` until the system has something to say: no timer, no poll.
    thread(isDaemon = true, name = "claude-log-watch") {
        try {
            while (true) {
                val key = service.take()
                val folder = key.watchable() as Path
                var written = false
                for (event in key.pollEvents()) {
                    val name = event.context() as? Path
                    when {
                        // Too many at once for the system to name: some log was written.
                        event.kind() == OVERFLOW || name.toString().endsWith(".jsonl") -> written = true
                        // A new project's folder: its logs are to come.
                        event.kind() == ENTRY_CREATE && name != null && folder.resolve(name).toFile().isDirectory ->
                            watch(folder.resolve(name).toFile())
                    }
                }
                key.reset()
                if (written) onChange()
            }
        } catch (_: ClosedWatchServiceException) {
            // Closed: nothing to watch any more.
        } catch (_: InterruptedException) {
            // The app is going away.
        }
    }
    return AutoCloseable { service.close() }
}

// A desktop keeps decades of logs at the 2026 pace (about 3 MB of text a month).
actual val claudeLogArchiveLimitBytes: Long = 200L * 1024 * 1024
