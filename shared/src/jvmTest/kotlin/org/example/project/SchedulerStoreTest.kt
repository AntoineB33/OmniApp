package org.example.project

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import org.example.project.scheduler.model.CellId
import org.example.project.scheduler.persistence.SchedulerStateCodec
import org.example.project.scheduler.persistence.SqlDelightSchedulerStore
import org.example.project.scheduler.persistence.SyncMeta
import org.example.project.scheduler.persistence.WindowPlacement
import org.example.project.scheduler.persistence.migrateLegacyJsonPayload
import org.example.project.scheduler.persistence.db.SchedulerDatabase
import org.example.project.scheduler.state.AppWindow
import org.example.project.scheduler.state.FocusDelta
import org.example.project.scheduler.state.HistoryUnit
import org.example.project.scheduler.state.NoOpDelta
import org.example.project.scheduler.state.SchedulerHistories
import org.example.project.scheduler.state.SchedulerHistory
import org.example.project.scheduler.state.SchedulerState
import org.example.project.scheduler.state.ToggleExpandDelta
import java.io.File
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SchedulerStoreTest {
    private fun newStore(): SqlDelightSchedulerStore {
        val driver =
            JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), SchedulerDatabase.Schema)
        return SqlDelightSchedulerStore(SchedulerDatabase(driver))
    }

    /** An empty state plus a multi-category history (Main with two units, Selection with one). */
    private fun stateWithHistory(): SchedulerState {
        val histories =
            SchedulerHistories(
                main =
                    SchedulerHistory(
                        pointer = 1,
                        units =
                            listOf(
                                HistoryUnit(timeMillis = 100, chronoId = 0, delta = ToggleExpandDelta(CellId("c1"))),
                                HistoryUnit(
                                    timeMillis = 200,
                                    chronoId = 1,
                                    delta = FocusDelta(AppWindow.Tree, AppWindow.Calendar),
                                    debugTainted = true,
                                ),
                            ),
                    ),
                selection =
                    SchedulerHistory(pointer = 0, units = listOf(HistoryUnit(timeMillis = 300, delta = NoOpDelta))),
            )
        return SchedulerState.empty().copy(histories = histories)
    }

    @Test
    fun load_on_empty_db_returns_null() {
        assertNull(newStore().load())
    }

    @Test
    fun round_trips_state_and_per_row_history() {
        val store = newStore()
        val state = stateWithHistory()

        store.save(SchedulerStateCodec.encodeSnapshot(state))
        val snapshot = store.load()!!

        // One row per History Unit across all categories (2 Main + 1 Selection).
        assertEquals(3, snapshot.history.size)
        val loaded = SchedulerStateCodec.decodeSnapshot(snapshot)!!
        assertEquals(state.histories, loaded.histories)
        assertEquals(state.rootListId, loaded.rootListId)
    }

    @Test
    fun redo_branch_truncation_removes_stale_rows() {
        val store = newStore()
        store.save(SchedulerStateCodec.encodeSnapshot(stateWithHistory()))
        assertEquals(3, store.load()!!.history.size)

        // Shrink the Main history (as a discarded redo branch would), then re-save.
        val shrunk =
            stateWithHistory().let { s ->
                s.copy(histories = s.histories.withCategory(
                    org.example.project.scheduler.state.HistoryCategory.Main,
                    SchedulerHistory(pointer = 0, units = s.histories.main.units.take(1)),
                ))
            }
        store.save(SchedulerStateCodec.encodeSnapshot(shrunk))

        val reloaded = store.load()!!
        assertEquals(2, reloaded.history.size) // 1 Main + 1 Selection; the stale Main row is gone
        assertEquals(shrunk.histories, SchedulerStateCodec.decodeSnapshot(reloaded)!!.histories)
    }

    @Test
    fun migrates_legacy_json_blob_once() {
        val store = newStore()
        val legacyJson = SchedulerStateCodec.encode(stateWithHistory())

        assertTrue(migrateLegacyJsonPayload(store, legacyJson))
        val loaded = SchedulerStateCodec.decodeSnapshot(store.load()!!)!!
        assertEquals(stateWithHistory().histories, loaded.histories)

        // Second call is a no-op because the store already holds data.
        assertEquals(false, migrateLegacyJsonPayload(store, legacyJson))
    }

    @Test
    fun sync_meta_round_trips() {
        val store = newStore()
        assertNull(store.loadSyncMeta())

        val meta =
            SyncMeta(
                deviceId = "device-1",
                lastKnownRevision = 7,
                dirty = true,
                accessToken = "at",
                refreshToken = "rt",
                userId = "user-1",
                email = "a@b.c",
            )
        store.saveSyncMeta(meta)
        assertEquals(meta, store.loadSyncMeta())

        // Upsert: a second save replaces the single row rather than adding one.
        store.saveSyncMeta(meta.copy(lastKnownRevision = 8, dirty = false))
        assertEquals(8, store.loadSyncMeta()!!.lastKnownRevision)
        assertEquals(false, store.loadSyncMeta()!!.dirty)
    }

    /**
     * Persisted-DB compatibility (CLAUDE.md): a DB written by the *previous* schema (v1: app_state +
     * history_unit + history_pointer, no sync_meta) must still load after the sync feature ships, with the
     * 1.sqm migration adding sync_meta on open. Reproduces the on-disk v1 shape, then opens it with the
     * current schema and asserts old data survives and sync_meta is usable.
     */
    @Test
    fun upgrades_pre_sync_v1_db_and_preserves_data() {
        val dbFile = File.createTempFile("scheduler-v1", ".db").also { it.delete() }
        try {
            val payload = SchedulerStateCodec.encodeSnapshot(stateWithHistory()).statePayload
            // Build the v1 schema by hand (no SchedulerDatabase.Schema), exactly as the old build wrote it.
            val url = "jdbc:sqlite:${dbFile.absolutePath}"
            val raw = JdbcSqliteDriver(url, Properties())
            raw.execute(null, "CREATE TABLE app_state (id INTEGER NOT NULL PRIMARY KEY, payload TEXT NOT NULL)", 0)
            raw.execute(
                null,
                "CREATE TABLE history_unit (category TEXT NOT NULL, ordinal INTEGER NOT NULL, " +
                    "time_millis INTEGER NOT NULL, chrono_id INTEGER NOT NULL, debug_tainted INTEGER NOT NULL, " +
                    "delta TEXT NOT NULL, PRIMARY KEY (category, ordinal))",
                0,
            )
            raw.execute(
                null,
                "CREATE TABLE history_pointer (category TEXT NOT NULL PRIMARY KEY, pointer INTEGER NOT NULL)",
                0,
            )
            raw.execute(null, "INSERT INTO app_state(id, payload) VALUES (0, ?)", 1) {
                bindString(0, payload)
            }
            raw.execute(null, "PRAGMA user_version = 1", 0)
            raw.close()

            // Open with the current schema: the v1 -> v2 migration must run (adding sync_meta) without
            // dropping the app_state row.
            val driver = JdbcSqliteDriver(url, Properties(), SchedulerDatabase.Schema)
            val store = SqlDelightSchedulerStore(SchedulerDatabase(driver))

            val loaded = store.load()
            assertEquals(payload, loaded!!.statePayload)
            // The new table exists and works (no row yet for an upgraded DB).
            assertNull(store.loadSyncMeta())
            store.saveSyncMeta(SyncMeta(deviceId = "d"))
            assertEquals("d", store.loadSyncMeta()!!.deviceId)
            driver.close()
        } finally {
            dbFile.delete()
        }
    }

    @Test
    fun window_placements_round_trip() {
        val store = newStore()
        assertTrue(store.loadPlacements().isEmpty())

        store.savePlacement("Calendar", WindowPlacement(x = 12f, y = -34f, visible = true))
        store.savePlacement("Sleep", WindowPlacement(x = 5f, y = 6f, width = 320f, height = 0f, visible = false))

        val placements = store.loadPlacements()
        assertEquals(WindowPlacement(x = 12f, y = -34f, visible = true), placements["Calendar"])
        assertEquals(WindowPlacement(x = 5f, y = 6f, width = 320f, height = 0f, visible = false), placements["Sleep"])

        // Upsert: a second save for the same window replaces its single row rather than adding one.
        store.savePlacement("Calendar", WindowPlacement(x = 99f, y = 0f, visible = false))
        assertEquals(WindowPlacement(x = 99f, y = 0f, visible = false), store.loadPlacements()["Calendar"])
        assertEquals(2, store.loadPlacements().size)
    }

    /**
     * Window geometry is LOCAL-ONLY and orthogonal to the synced state: saving a placement must not change
     * the [org.example.project.scheduler.persistence.PersistedSnapshot] the store loads (and thus syncs).
     */
    @Test
    fun window_placements_do_not_affect_persisted_snapshot() {
        val store = newStore()
        store.save(SchedulerStateCodec.encodeSnapshot(stateWithHistory()))
        val before = store.load()!!

        store.savePlacement("Calendar", WindowPlacement(x = 1f, y = 2f, visible = true))

        assertEquals(before, store.load()!!)
    }

    /**
     * Persisted-DB compatibility (CLAUDE.md): a DB written by the *previous* schema (v2: app_state +
     * history_unit + history_pointer + sync_meta, no window_placement) must still load after this feature
     * ships, with the 2.sqm migration adding window_placement on open. Reproduces the on-disk v2 shape, then
     * opens it with the current schema and asserts old data survives and window placements are usable.
     */
    @Test
    fun upgrades_pre_window_placement_v2_db_and_preserves_data() {
        val dbFile = File.createTempFile("scheduler-v2", ".db").also { it.delete() }
        try {
            val payload = SchedulerStateCodec.encodeSnapshot(stateWithHistory()).statePayload
            val url = "jdbc:sqlite:${dbFile.absolutePath}"
            val raw = JdbcSqliteDriver(url, Properties())
            raw.execute(null, "CREATE TABLE app_state (id INTEGER NOT NULL PRIMARY KEY, payload TEXT NOT NULL)", 0)
            raw.execute(
                null,
                "CREATE TABLE history_unit (category TEXT NOT NULL, ordinal INTEGER NOT NULL, " +
                    "time_millis INTEGER NOT NULL, chrono_id INTEGER NOT NULL, debug_tainted INTEGER NOT NULL, " +
                    "delta TEXT NOT NULL, PRIMARY KEY (category, ordinal))",
                0,
            )
            raw.execute(
                null,
                "CREATE TABLE history_pointer (category TEXT NOT NULL PRIMARY KEY, pointer INTEGER NOT NULL)",
                0,
            )
            raw.execute(
                null,
                "CREATE TABLE sync_meta (id INTEGER NOT NULL PRIMARY KEY, device_id TEXT NOT NULL, " +
                    "last_known_revision INTEGER NOT NULL DEFAULT 0, dirty INTEGER NOT NULL DEFAULT 0, " +
                    "access_token TEXT, refresh_token TEXT, user_id TEXT, email TEXT)",
                0,
            )
            raw.execute(null, "INSERT INTO app_state(id, payload) VALUES (0, ?)", 1) {
                bindString(0, payload)
            }
            raw.execute(null, "PRAGMA user_version = 2", 0)
            raw.close()

            // Open with the current schema: the v2 -> v3 migration must run (adding window_placement) without
            // dropping the app_state row.
            val driver = JdbcSqliteDriver(url, Properties(), SchedulerDatabase.Schema)
            val store = SqlDelightSchedulerStore(SchedulerDatabase(driver))

            assertEquals(payload, store.load()!!.statePayload)
            // The new table exists and works (no placement yet for an upgraded DB).
            assertTrue(store.loadPlacements().isEmpty())
            store.savePlacement("Calendar", WindowPlacement(x = 7f, y = 8f, visible = true))
            assertEquals(WindowPlacement(x = 7f, y = 8f, visible = true), store.loadPlacements()["Calendar"])
            driver.close()
        } finally {
            dbFile.delete()
        }
    }

    @Test
    fun file_backed_db_persists_across_reopen() {
        val dbFile = File.createTempFile("scheduler-test", ".db").also { it.delete() }
        try {
            // First open creates the schema, then writes.
            val firstDriver =
                JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}", Properties(), SchedulerDatabase.Schema)
            SqlDelightSchedulerStore(SchedulerDatabase(firstDriver))
                .save(SchedulerStateCodec.encodeSnapshot(stateWithHistory()))
            firstDriver.close()

            // Reopen the existing file: the schema overload must NOT fail on already-created tables,
            // and the previously written data must be readable.
            val secondDriver =
                JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}", Properties(), SchedulerDatabase.Schema)
            val reloaded = SqlDelightSchedulerStore(SchedulerDatabase(secondDriver)).load()!!
            assertEquals(3, reloaded.history.size)
            assertEquals(stateWithHistory().histories, SchedulerStateCodec.decodeSnapshot(reloaded)!!.histories)
            secondDriver.close()
        } finally {
            dbFile.delete()
        }
    }
}
