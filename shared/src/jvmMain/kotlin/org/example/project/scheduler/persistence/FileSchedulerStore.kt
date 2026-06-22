package org.example.project.scheduler.persistence

import java.io.File

/**
 * JVM/desktop persistence: JSON file under the user's home directory (PRD §5).
 */
class FileSchedulerStore(private val file: File) : SchedulerStore {
    override fun load(): String? = if (file.exists()) file.readText() else null

    override fun save(data: String) {
        file.parentFile?.mkdirs()
        file.writeText(data)
    }

    companion object {
        fun default(): FileSchedulerStore {
            // Allow dev scripts to redirect state to an isolated directory
            // (e.g. dev-reset uses a throwaway dir so it never wipes the
            // real DB that dev-restart preserves). Falls back to ~/.omniapp.
            val override = System.getProperty("omniapp.stateDir")?.takeIf { it.isNotBlank() }
            val dir = if (override != null) File(override) else File(System.getProperty("user.home"), ".omniapp")
            return FileSchedulerStore(File(dir, "scheduler-state.json"))
        }
    }
}

actual fun createDefaultSchedulerStore(): SchedulerStore? = FileSchedulerStore.default()
