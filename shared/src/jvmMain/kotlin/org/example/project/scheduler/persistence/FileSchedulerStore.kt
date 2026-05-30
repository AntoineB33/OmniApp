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
            val dir = File(System.getProperty("user.home"), ".omniapp")
            return FileSchedulerStore(File(dir, "scheduler-state.json"))
        }
    }
}

actual fun createDefaultSchedulerStore(): SchedulerStore? = FileSchedulerStore.default()
