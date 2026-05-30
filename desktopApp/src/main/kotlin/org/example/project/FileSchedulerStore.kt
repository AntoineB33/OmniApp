package org.example.project

import org.example.project.scheduler.persistence.SchedulerStore
import java.io.File

/**
 * Desktop (JVM) persistence: stores the scheduler tree as a JSON file on disk so the DB
 * survives across `:desktopApp:run` invocations (PRD §5 Persistence).
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
