package org.example.project.scheduler.persistence

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import org.example.project.scheduler.persistence.db.SchedulerDatabase
import java.io.File
import java.util.Properties

/**
 * JVM/desktop persistence: a SQLite database under the user's home directory (PRD §5).
 *
 * The [JdbcSqliteDriver] schema overload creates the tables on first run and migrates them on a
 * version bump (driven by `PRAGMA user_version`), so no manual `Schema.create` guard is needed.
 */
actual fun createDefaultSchedulerStore(): SchedulerStore? {
    // Allow dev scripts to redirect state to an isolated directory (e.g. dev-reset uses a throwaway
    // dir so it never wipes the real DB that dev-restart preserves). Falls back to ~/.omniapp.
    val override = System.getProperty("omniapp.stateDir")?.takeIf { it.isNotBlank() }
    val dir = if (override != null) File(override) else File(System.getProperty("user.home"), ".omniapp")
    dir.mkdirs()
    val dbFile = File(dir, "scheduler-state.db")
    val driver =
        JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}", Properties(), SchedulerDatabase.Schema)
    val store = SqlDelightSchedulerStore(SchedulerDatabase(driver))
    migrateLegacyJson(dir, store)
    return store
}

/** Imports a pre-SQLite `scheduler-state.json` blob once, then renames it so it is not re-imported. */
private fun migrateLegacyJson(dir: File, store: SchedulerStore) {
    val legacy = File(dir, "scheduler-state.json")
    if (!legacy.exists()) return
    if (migrateLegacyJsonPayload(store, runCatching { legacy.readText() }.getOrNull())) {
        legacy.renameTo(File(dir, "scheduler-state.json.migrated"))
    }
}
