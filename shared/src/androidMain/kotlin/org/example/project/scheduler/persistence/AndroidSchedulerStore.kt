package org.example.project.scheduler.persistence

import android.content.Context

private class AndroidSchedulerStore(private val context: Context) : SchedulerStore {
    private val fileName = "scheduler-state.json"

    override fun load(): String? {
        return runCatching {
            context.openFileInput(fileName).bufferedReader().use { it.readText() }
        }.getOrNull()
    }

    override fun save(data: String) {
        context.openFileOutput(fileName, Context.MODE_PRIVATE).bufferedWriter().use { it.write(data) }
    }
}

/** Set from [org.example.project.MainActivity] before Compose content is shown. */
object AndroidSchedulerStoreHolder {
    var context: Context? = null
}

actual fun createDefaultSchedulerStore(): SchedulerStore? {
    val ctx = context ?: return null
    return AndroidSchedulerStore(ctx)
}
