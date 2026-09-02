package org.example.project

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import org.example.project.scheduler.model.CellId
import org.example.project.scheduler.persistence.SchedulerStateCodec
import org.example.project.scheduler.persistence.SqlDelightSchedulerStore
import org.example.project.scheduler.persistence.db.SchedulerDatabase
import org.example.project.scheduler.state.HistoryUnit
import org.example.project.scheduler.state.SchedulerHistories
import org.example.project.scheduler.state.SchedulerHistory
import org.example.project.scheduler.state.SchedulerState
import org.example.project.scheduler.state.ToggleExpandDelta
import java.io.File
import java.sql.DriverManager
import java.util.Properties
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Adding a History Unit must cost ONE INSERT.
 *
 * `save()` used to delete every `history_unit` row of the account and re-insert the whole list, because the
 * row key was the unit's dense index and the cap evicts from the FRONT — one new unit renumbered all 1000.
 * On the release account that was ~70 MB of delta text rewritten on every 400 ms save debounce, i.e. on
 * every burst of typing.
 *
 * These tests assert the rewrite is gone the only way that cannot be faked: a **sentinel** is written
 * straight into a stored row's `delta` behind the store's back, and must still be there after the next
 * save. A row that was re-inserted would have lost it.
 */
class SchedulerHistoryWriteTest {
    private val dbFile: File = File.createTempFile("scheduler-history", ".db").also { it.delete() }
    private val url: String = "jdbc:sqlite:${dbFile.absolutePath}"
    private var driver: JdbcSqliteDriver? = null

    @AfterTest
    fun cleanUp() {
        driver?.close()
        dbFile.delete()
    }

    private fun openStore(): SqlDelightSchedulerStore {
        val opened = JdbcSqliteDriver(url, Properties(), SchedulerDatabase.Schema)
        driver = opened
        return SqlDelightSchedulerStore(SchedulerDatabase(opened))
    }

    private fun unit(timeMillis: Long, cell: String) =
        HistoryUnit(timeMillis = timeMillis, delta = ToggleExpandDelta(CellId(cell)))

    private fun stateWith(units: List<HistoryUnit>): SchedulerState =
        SchedulerState.empty()
            .copy(
                histories =
                    SchedulerHistories(main = SchedulerHistory(pointer = units.lastIndex, units = units)),
            )

    private fun save(store: SqlDelightSchedulerStore, units: List<HistoryUnit>) {
        store.save(SchedulerStateCodec.encodeSnapshot(stateWith(units)))
    }

    /** `seq -> delta` of every Main row, read straight from the table. */
    private fun storedMainRows(): Map<Long, String> {
        val rows = LinkedHashMap<Long, String>()
        DriverManager.getConnection(url, Properties()).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT seq, delta FROM history_unit WHERE category = 'Main' ORDER BY seq",
                ).use { result ->
                    while (result.next()) rows[result.getLong(1)] = result.getString(2)
                }
            }
        }
        return rows
    }

    /** Writes [text] into one stored row's delta behind the store's back, as a rewrite detector. */
    private fun stampSentinel(seq: Long, text: String) {
        DriverManager.getConnection(url, Properties()).use { connection ->
            connection.createStatement().use {
                it.executeUpdate("UPDATE history_unit SET delta = '$text' WHERE category = 'Main' AND seq = $seq")
            }
        }
    }

    @Test
    fun appending_a_unit_inserts_one_row_and_rewrites_none() {
        val store = openStore()
        val first = listOf(unit(100, "a"), unit(200, "b"), unit(300, "c"))
        save(store, first)
        assertEquals(listOf(0L, 1L, 2L), storedMainRows().keys.toList(), "seqs are allocated in order")

        stampSentinel(seq = 1, text = "SENTINEL-B")

        save(store, first + unit(400, "d"))

        val after = storedMainRows()
        assertEquals(listOf(0L, 1L, 2L, 3L), after.keys.toList(), "exactly one row was added")
        assertEquals("SENTINEL-B", after[1], "an untouched unit's row must not be rewritten")
        assertTrue(after.getValue(3).contains("\"d\""), "the appended unit's delta is the one written")
    }

    /**
     * The cap evicts from the FRONT. That is the case a dense-index key could not survive — every surviving
     * unit's index shifts — and the one the stable `seq` exists for.
     */
    @Test
    fun evicting_the_oldest_unit_keeps_every_survivor_s_row() {
        val store = openStore()
        val units = listOf(unit(100, "a"), unit(200, "b"), unit(300, "c"))
        save(store, units)
        stampSentinel(seq = 2, text = "SENTINEL-C")

        // What the cap does: drop the head, append a new unit.
        save(store, units.drop(1) + unit(400, "d"))

        val after = storedMainRows()
        assertEquals(listOf(1L, 2L, 3L), after.keys.toList(), "the evicted head is gone, the rest keep their seqs")
        assertEquals("SENTINEL-C", after[2], "a survivor's row must not be rewritten by the eviction")
    }

    /** PRD §5 branching: a new mutation after an undo discards the redo tail, which must leave no rows. */
    @Test
    fun a_redo_branch_deletes_the_orphaned_tail() {
        val store = openStore()
        val kept = listOf(unit(100, "a"), unit(200, "b"))
        save(store, kept + unit(300, "orphan"))
        stampSentinel(seq = 0, text = "SENTINEL-A")

        save(store, kept + unit(400, "branch"))

        val after = storedMainRows()
        assertEquals(listOf(0L, 1L, 3L), after.keys.toList(), "the orphan's row is deleted, the new unit appended")
        assertEquals("SENTINEL-A", after[0], "the kept prefix must not be rewritten")
        assertTrue(after.getValue(3).contains("branch"), "the branch unit's delta is the one written")
    }

    /**
     * A unit's identity is its digest, not its position — so a unit whose delta CHANGED at a position it
     * already occupied must be rewritten, never silently kept. (The reducer never does this; the guard is
     * what makes the reuse decision safe rather than merely fast.)
     */
    @Test
    fun a_different_delta_at_the_same_position_is_rewritten() {
        val store = openStore()
        val same = unit(100, "a")
        save(store, listOf(same, unit(200, "b")))

        // Same timestamp and tie-break index as the stored second unit, different delta.
        save(store, listOf(same, unit(200, "different")))

        val reloaded = SchedulerStateCodec.decodeSnapshot(store.load()!!)!!
        assertEquals(
            listOf(ToggleExpandDelta(CellId("a")), ToggleExpandDelta(CellId("different"))),
            reloaded.histories.main.units.map { it.delta },
        )
    }

    /** Whatever the seqs end up being, the loaded list is dense, ordered, and indexes the pointer. */
    @Test
    fun the_loaded_list_is_dense_and_ordered_however_the_seqs_fall() {
        val store = openStore()
        save(store, listOf(unit(100, "a"), unit(200, "b"), unit(300, "c")))
        save(store, listOf(unit(200, "b"), unit(300, "c"), unit(400, "d"))) // evict head + append
        save(store, listOf(unit(200, "b"), unit(300, "c"), unit(500, "e"))) // branch off the tail

        val snapshot = store.load()!!
        val mainRows = snapshot.history.filter { it.category == "Main" }
        assertEquals(listOf(0, 1, 2), mainRows.map { it.ordinal }, "ordinals are the dense index, not the seq")

        val reloaded = SchedulerStateCodec.decodeSnapshot(snapshot)!!
        assertEquals(
            listOf(ToggleExpandDelta(CellId("b")), ToggleExpandDelta(CellId("c")), ToggleExpandDelta(CellId("e"))),
            reloaded.histories.main.units.map { it.delta },
        )
        assertEquals(2, reloaded.histories.main.pointer)
    }

    /**
     * The other half of "adding a unit is cheap": `encodeSnapshot` must not re-serialize the history it has
     * already serialized. It runs on every save AND again for every `syncFingerprint`, so a mature account
     * was encoding tens of MB of JSON twice per keystroke debounce before the store was even called.
     */
    @Test
    fun a_unit_s_delta_is_serialized_once_and_reused() {
        val units = listOf(unit(100, "a"), unit(200, "b"))
        val state = stateWith(units)

        val first = SchedulerStateCodec.encodeSnapshot(state)
        val second = SchedulerStateCodec.encodeSnapshot(state)

        assertNotNull(units[0].encodedDelta, "the unit keeps the JSON it was encoded to")
        repeat(units.size) { index ->
            assertTrue(
                first.history[index].deltaJson === second.history[index].deltaJson,
                "the second encode must hand back the SAME string, not an equal one",
            )
        }
    }

    /** A unit loaded from the store arrives with its memo filled, so a launch re-serializes nothing. */
    @Test
    fun a_loaded_unit_arrives_already_encoded() {
        val store = openStore()
        save(store, listOf(unit(100, "a")))

        val reloaded = SchedulerStateCodec.decodeSnapshot(store.load()!!)!!
        val loadedUnit = reloaded.histories.main.units.single()
        assertNotNull(loadedUnit.encodedDelta, "the row's own delta text is kept as the memo")
        assertTrue(
            SchedulerStateCodec.encodeSnapshot(reloaded).history.single().deltaJson ===
                loadedUnit.encodedDelta!!.json,
            "encoding a loaded state must reuse the text it was loaded from",
        )
    }

    /**
     * Persisted-DB compatibility (CLAUDE.md): a DB written by the previous schema (v10 — `history_unit`
     * keyed by the dense `ordinal`, no digest columns) must still load, and the first save must reuse its
     * rows rather than rewrite them. Reproduces the on-disk v10 shape, then opens it with the current schema.
     */
    @Test
    fun upgrades_a_v10_db_and_reuses_its_history_rows() {
        val units = listOf(unit(100, "a"), unit(200, "b"), unit(300, "c"))
        val snapshot = SchedulerStateCodec.encodeSnapshot(stateWith(units))
        writeV10Database(snapshot.statePayload, snapshot.history)

        val store = openStore()

        // The migration kept the data, and the dense ordinals became a valid seq sequence.
        val loaded = store.load()!!
        assertEquals(snapshot.statePayload, loaded.statePayload)
        assertEquals(
            units.map { it.delta },
            SchedulerStateCodec.decodeSnapshot(loaded)!!.histories.main.units.map { it.delta },
        )
        assertEquals(listOf(0L, 1L, 2L), storedMainRows().keys.toList())
        assertNull(storedDeltaHash(seq = 0), "a carried-up row has no digest yet")

        // A carried-up row matches on its remaining columns, so the append reuses it rather than rewriting…
        stampSentinel(seq = 1, text = "SENTINEL-B")
        save(store, units + unit(400, "d"))

        val after = storedMainRows()
        assertEquals(listOf(0L, 1L, 2L, 3L), after.keys.toList())
        assertEquals("SENTINEL-B", after[1], "an upgraded row must be reused, not rewritten")
        // …and it is healed in passing, so the next save aligns on the full identity.
        assertNotNull(storedDeltaHash(seq = 0), "the reused row was given the digest it lacked")
    }

    private fun storedDeltaHash(seq: Long): Long? =
        DriverManager.getConnection(url, Properties()).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT delta_hash FROM history_unit WHERE category = 'Main' AND seq = $seq",
                ).use { result ->
                    if (!result.next()) null else result.getLong(1).takeIf { !result.wasNull() }
                }
            }
        }

    /** The v10 on-disk shape, exactly as the previous build wrote it (no `seq`, no digest columns). */
    private fun writeV10Database(payload: String, history: List<org.example.project.scheduler.persistence.HistoryRow>) {
        DriverManager.getConnection(url, Properties()).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE TABLE app_state (account_id TEXT NOT NULL PRIMARY KEY, payload TEXT NOT NULL)")
                statement.execute(
                    "CREATE TABLE history_unit (account_id TEXT NOT NULL, category TEXT NOT NULL, " +
                        "ordinal INTEGER NOT NULL, time_millis INTEGER NOT NULL, chrono_id INTEGER NOT NULL, " +
                        "debug_tainted INTEGER NOT NULL, delta TEXT NOT NULL, " +
                        "PRIMARY KEY (account_id, category, ordinal))",
                )
                statement.execute(
                    "CREATE TABLE history_pointer (account_id TEXT NOT NULL, category TEXT NOT NULL, " +
                        "pointer INTEGER NOT NULL, PRIMARY KEY (account_id, category))",
                )
                statement.execute(
                    "CREATE TABLE sync_meta (id INTEGER NOT NULL PRIMARY KEY, device_id TEXT NOT NULL, " +
                        "access_token TEXT, refresh_token TEXT, user_id TEXT, email TEXT)",
                )
                statement.execute(
                    "CREATE TABLE account_sync (account_id TEXT NOT NULL PRIMARY KEY, " +
                        "last_known_revision INTEGER NOT NULL DEFAULT 0, dirty INTEGER NOT NULL DEFAULT 0, " +
                        "acknowledged_logout_at INTEGER, base_payload TEXT)",
                )
                statement.execute(
                    "CREATE TABLE window_placement (window_id TEXT NOT NULL PRIMARY KEY, x REAL NOT NULL, " +
                        "y REAL NOT NULL, width REAL NOT NULL DEFAULT 0, height REAL NOT NULL DEFAULT 0, " +
                        "visible INTEGER NOT NULL)",
                )
                statement.execute(
                    "CREATE TABLE device_sleep_gap (device_id TEXT NOT NULL, sleep_start INTEGER NOT NULL, " +
                        "sleep_end INTEGER NOT NULL, recorded_at INTEGER NOT NULL, " +
                        "PRIMARY KEY (device_id, sleep_start))",
                )
                statement.execute(
                    "CREATE TABLE device_active_session (device_id TEXT NOT NULL, start_ms INTEGER NOT NULL, " +
                        "end_ms INTEGER NOT NULL, updated_at INTEGER NOT NULL, kind TEXT NOT NULL DEFAULT '', " +
                        "PRIMARY KEY (device_id, start_ms))",
                )
                statement.execute(
                    "CREATE TABLE sleep_scan_checkpoint (id INTEGER NOT NULL PRIMARY KEY, " +
                        "scanned_through INTEGER NOT NULL)",
                )
            }
            connection.prepareStatement("INSERT INTO app_state(account_id, payload) VALUES ('', ?)").use {
                it.setString(1, payload)
                it.executeUpdate()
            }
            connection.prepareStatement(
                "INSERT INTO history_unit(account_id, category, ordinal, time_millis, chrono_id, " +
                    "debug_tainted, delta) VALUES ('', ?, ?, ?, ?, ?, ?)",
            ).use { insert ->
                history.forEach { row ->
                    insert.setString(1, row.category)
                    insert.setLong(2, row.ordinal.toLong())
                    insert.setLong(3, row.timeMillis)
                    insert.setLong(4, row.chronoId)
                    insert.setLong(5, if (row.debugTainted) 1L else 0L)
                    insert.setString(6, row.deltaJson)
                    insert.executeUpdate()
                }
            }
            connection.createStatement().use { it.execute("PRAGMA user_version = 10") }
        }
    }
}
