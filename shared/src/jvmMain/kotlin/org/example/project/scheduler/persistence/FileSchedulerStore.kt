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
    // Redirect state to an isolated directory so the release, debug, and reset runs each use a separate DB.
    // Priority: the `-Domniapp.stateDir` property (dev scripts pass it via Gradle -P) → the
    // `OMNIAPP_STATE_DIR` env var (the packaged release launcher sets it, since a shortcut/.bat can set an
    // env var but not a JVM -D flag) → the default ~/.omniapp.
    val override =
        (System.getProperty("omniapp.stateDir")?.takeIf { it.isNotBlank() }
            ?: System.getenv("OMNIAPP_STATE_DIR")?.takeIf { it.isNotBlank() })
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
