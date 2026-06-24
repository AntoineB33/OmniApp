package org.example.project

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import org.example.project.scheduler.model.CellId
import org.example.project.scheduler.persistence.SchedulerStateCodec
import org.example.project.scheduler.persistence.SqlDelightSchedulerStore
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
