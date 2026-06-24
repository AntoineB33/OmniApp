package org.example.project.scheduler.persistence

import android.content.Context
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import org.example.project.scheduler.persistence.db.SchedulerDatabase

/** Set from [org.example.project.MainActivity] before Compose content is shown. */
object AndroidSchedulerStoreHolder {
    var context: Context? = null
}

actual fun createDefaultSchedulerStore(): SchedulerStore? {
    val ctx = AndroidSchedulerStoreHolder.context ?: return null
    // AndroidSqliteDriver creates/migrates the schema from PRAGMA user_version automatically.
    val driver = AndroidSqliteDriver(SchedulerDatabase.Schema, ctx, "scheduler-state.db")
    val store = SqlDelightSchedulerStore(SchedulerDatabase(driver))
    migrateLegacyJson(ctx, store)
    return store
}

/** Imports a pre-SQLite `scheduler-state.json` blob once, then deletes it so it is not re-imported. */
private fun migrateLegacyJson(ctx: Context, store: SchedulerStore) {
    val fileName = "scheduler-state.json"
    val legacy =
        runCatching { ctx.openFileInput(fileName).bufferedReader().use { it.readText() } }.getOrNull()
    if (migrateLegacyJsonPayload(store, legacy)) {
        runCatching { ctx.deleteFile(fileName) }
    }
}
