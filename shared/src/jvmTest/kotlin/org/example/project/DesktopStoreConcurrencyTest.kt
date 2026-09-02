package org.example.project

import org.example.project.scheduler.persistence.SyncMeta
import org.example.project.scheduler.persistence.SyncMetaStore
import org.example.project.scheduler.persistence.createDefaultSchedulerStore
import java.io.File
import java.sql.DriverManager
import java.util.Properties
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.AfterTest
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The desktop store is a MULTI-CONNECTION SQLite client of its own file — SQLDelight's file-backed
 * `JdbcSqliteDriver` opens one connection per thread — so the two pragmas that decide what happens when two
 * of them write at once are load-bearing, not tuning. With the driver defaults (`journal_mode=delete`,
 * `busy_timeout=3000`) a save that outlives 3 s made every other connection's write THROW
 * `[SQLITE_BUSY] The database file is locked`; unguarded save sites let that escape and the packaged
 * launcher showed it as a fatal "Error" box, i.e. the app read as crashing. Both halves are pinned here.
 */
class DesktopStoreConcurrencyTest {
    private val previousStateDir: String? = System.getProperty("omniapp.stateDir")

    @AfterTest
    fun restoreStateDir() {
        if (previousStateDir == null) System.clearProperty("omniapp.stateDir")
        else System.setProperty("omniapp.stateDir", previousStateDir)
    }

    private fun newStateDir(name: String): File =
        File(System.getProperty("java.io.tmpdir"), "omniapp-test-$name-${System.nanoTime()}").also {
            it.mkdirs()
            it.deleteOnExit()
            System.setProperty("omniapp.stateDir", it.absolutePath)
        }

    /**
     * `journal_mode` is a PERSISTENT property of the file, so a second, plainly-configured connection is
     * enough to prove the app's own driver set it — which is what stops a writer from locking out every
     * reader for the length of its transaction.
     */
    @Test
    fun `the desktop database is opened in WAL mode`() {
        val dir = newStateDir("wal")
        assertTrue(createDefaultSchedulerStore() != null, "the desktop store should be created")

        val dbFile = File(dir, "scheduler-state.db")
        DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}", Properties()).use { probe ->
            probe.createStatement().use { statement ->
                statement.executeQuery("PRAGMA journal_mode").use { rows ->
                    rows.next()
                    assertEquals("wal", rows.getString(1).lowercase())
                }
            }
        }
    }

    /**
     * WAL still admits only ONE writer at a time, so the busy timeout is what turns losing that race into a
     * wait rather than an exception. A rival holds the write lock for longer than the driver's 3 s default
     * and the store must still get its write through — with the defaults this call threw at 3 s.
     */
    @Test
    fun `a store write waits out a concurrent writer instead of throwing SQLITE_BUSY`() {
        val dir = newStateDir("busy")
        val store = createDefaultSchedulerStore() as SyncMetaStore

        val dbFile = File(dir, "scheduler-state.db")
        val lockHeld = CountDownLatch(1)
        val releaseLock = CountDownLatch(1)
        var rivalFailure: Throwable? = null
        val rival =
            Thread {
                runCatching {
                    DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}", Properties())
                        .use { connection ->
                            connection.createStatement().use {
                                it.execute("CREATE TABLE IF NOT EXISTS busy_probe(x INTEGER)")
                            }
                            // An actual write inside a transaction is what takes the write lock and holds it
                            // until the rollback below — WAL admits exactly one writer at a time.
                            connection.autoCommit = false
                            connection.createStatement().use { it.execute("INSERT INTO busy_probe VALUES(1)") }
                            lockHeld.countDown()
                            releaseLock.await(30, TimeUnit.SECONDS)
                            connection.rollback()
                        }
                }.onFailure { rivalFailure = it }
                lockHeld.countDown()
            }
        rival.start()
        try {
            assertTrue(lockHeld.await(10, TimeUnit.SECONDS), "the rival writer should have started")
            assertNull(rivalFailure, "the rival writer should have taken the lock: $rivalFailure")
            // Longer than the 3 s default the crash was reported against, short enough to keep the test quick.
            val rivalHoldMillis = 4_500L
            Thread { Thread.sleep(rivalHoldMillis); releaseLock.countDown() }.start()

            val startedAt = System.currentTimeMillis()
            store.saveSyncMeta(SyncMeta(deviceId = "device-under-test"))
            val waited = System.currentTimeMillis() - startedAt

            assertTrue(
                waited >= 3_000,
                "the write should have WAITED on the rival, not raced past it (waited ${waited} ms)",
            )
            assertEquals("device-under-test", store.loadSyncMeta()?.deviceId)
        } finally {
            releaseLock.countDown()
            rival.join(30_000)
        }
    }
}
