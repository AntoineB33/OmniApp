package org.example.project.scheduler.persistence

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import org.example.project.scheduler.persistence.db.SchedulerDatabase
import java.io.File
import java.util.Properties

/**
 * The connection pragmas the desktop database is opened with, and why each one is load-bearing.
 *
 * **A file-backed [JdbcSqliteDriver] opens ONE CONNECTION PER THREAD** (SQLDelight's
 * `ThreadedConnectionManager`; only an in-memory URL gets a single shared connection). So the app is a
 * multi-connection SQLite client of its own database: the debounced `save` runs on a `Dispatchers.Default`
 * thread, `applyRemoteSnapshot`'s save on whichever thread the reconcile landed on, `flush()` on the UI
 * thread at close, and the engine's `saveActiveSessions` / `saveSleepGaps` / `saveSyncMeta` /
 * `saveCheckpoint` on their own. Every one of those is a separate connection contending for the file, and
 * the driver's defaults are not built for that:
 *
 * - **`journal_mode` defaults to `delete`**, where a writer takes an EXCLUSIVE lock on the whole file for
 *   the length of its transaction and every other connection — readers included — is turned away.
 * - **`busy_timeout` defaults to 3 s** (sqlite-jdbc's `SQLiteConfig`), after which the loser does not wait,
 *   it THROWS `[SQLITE_BUSY] The database file is locked`. Unguarded save sites let that escape and the
 *   packaged launcher shows it as a fatal "Error" box, so a lock contention reads to the user as a crash.
 *
 * `SchedulerStore.save` rewrites the whole Undo/Redo history (delete-all + re-insert) alongside the state
 * blob, which on a mature account is tens of MB in one transaction and comfortably outlives 3 s — so the
 * two defaults together turn an ordinary concurrent write into a crash, and do so MORE often the longer the
 * account has been used. WAL is the fix that matches the access pattern (readers never block the writer and
 * the writer never blocks readers, so only two concurrent WRITES ever wait at all), the timeout is what
 * makes that wait a wait instead of an exception, and `synchronous=NORMAL` is the sanctioned WAL companion
 * (a commit no longer fsyncs; only a checkpoint does).
 *
 * The values ride the [Properties] handed to the driver, so every per-thread connection it opens is
 * configured identically — there is no second place a connection is made.
 */
private const val BUSY_TIMEOUT_MILLIS = 30_000

private fun connectionProperties(): Properties =
    Properties().apply {
        setProperty("journal_mode", "WAL")
        setProperty("busy_timeout", BUSY_TIMEOUT_MILLIS.toString())
        setProperty("synchronous", "NORMAL")
    }

/**
 * JVM/desktop persistence: a SQLite database under the user's home directory (PRD §5).
 *
 * The [JdbcSqliteDriver] schema overload creates the tables on first run and migrates them on a
 * version bump (driven by `PRAGMA user_version`), so no manual `Schema.create` guard is needed. It runs
 * that create/migrate as a WRITE transaction at construction, so it is itself subject to
 * [connectionProperties]' busy timeout — a launcher that starts the new instance before the old one's
 * handle is gone would otherwise fail here, at startup, before there is any app to report it.
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
        JdbcSqliteDriver(
            "jdbc:sqlite:${dbFile.absolutePath}",
            connectionProperties(),
            SchedulerDatabase.Schema,
        )
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
