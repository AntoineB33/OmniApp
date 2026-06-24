package org.example.project.scheduler.persistence

import org.example.project.scheduler.persistence.db.SchedulerDatabase

/**
 * SQLite-backed [SchedulerStore] (PRD §5 Persistence) over a SQLDelight [SchedulerDatabase].
 *
 * The non-history state is one row in `app_state`; the Undo/Redo history is stored per-unit, one row
 * per [HistoryRow] in `history_unit`, plus one [HistoryPointerRow] per category in `history_pointer`.
 * [save] replaces the whole history in a single transaction so a redo branch that was discarded in
 * memory (the units after the pointer) leaves no stale rows behind.
 */
class SqlDelightSchedulerStore(private val database: SchedulerDatabase) : SchedulerStore {
    private val queries = database.schedulerQueries

    override fun load(): PersistedSnapshot? {
        val payload = queries.selectAppState().executeAsOneOrNull() ?: return null
        val history =
            queries.selectAllHistory().executeAsList().map { row ->
                HistoryRow(
                    category = row.category,
                    ordinal = row.ordinal.toInt(),
                    timeMillis = row.time_millis,
                    chronoId = row.chrono_id,
                    debugTainted = row.debug_tainted != 0L,
                    deltaJson = row.delta,
                )
            }
        val pointers =
            queries.selectAllPointers().executeAsList().map { row ->
                HistoryPointerRow(category = row.category, pointer = row.pointer.toInt())
            }
        return PersistedSnapshot(payload, history, pointers)
    }

    override fun save(snapshot: PersistedSnapshot) {
        database.transaction {
            queries.upsertAppState(snapshot.statePayload)
            queries.deleteAllHistory()
            snapshot.history.forEach { row ->
                queries.insertHistoryUnit(
                    category = row.category,
                    ordinal = row.ordinal.toLong(),
                    time_millis = row.timeMillis,
                    chrono_id = row.chronoId,
                    debug_tainted = if (row.debugTainted) 1L else 0L,
                    delta = row.deltaJson,
                )
            }
            queries.deleteAllPointers()
            snapshot.pointers.forEach { row ->
                queries.insertPointer(category = row.category, pointer = row.pointer.toLong())
            }
        }
    }
}

/**
 * One-time migration from the legacy whole-state JSON blob (PRD §5, pre-SQLite) into [store]. Returns
 * true if a payload was migrated. No-op when [legacyJson] is null/blank or [store] already holds data,
 * so it is safe to call on every startup.
 */
fun migrateLegacyJsonPayload(store: SchedulerStore, legacyJson: String?): Boolean {
    if (legacyJson.isNullOrBlank()) return false
    if (store.load() != null) return false
    val state = SchedulerStateCodec.decode(legacyJson) ?: return false
    store.save(SchedulerStateCodec.encodeSnapshot(state))
    return true
}
