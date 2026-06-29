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
class SqlDelightSchedulerStore(private val database: SchedulerDatabase) :
    SchedulerStore, SyncMetaStore, WindowPlacementStore, DeviceSleepGapStore {
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

    override fun loadSyncMeta(): SyncMeta? =
        queries.selectSyncMeta().executeAsOneOrNull()?.let { row ->
            SyncMeta(
                deviceId = row.device_id,
                lastKnownRevision = row.last_known_revision,
                dirty = row.dirty != 0L,
                accessToken = row.access_token,
                refreshToken = row.refresh_token,
                userId = row.user_id,
                email = row.email,
            )
        }

    override fun saveSyncMeta(meta: SyncMeta) {
        queries.upsertSyncMeta(
            device_id = meta.deviceId,
            last_known_revision = meta.lastKnownRevision,
            dirty = if (meta.dirty) 1L else 0L,
            access_token = meta.accessToken,
            refresh_token = meta.refreshToken,
            user_id = meta.userId,
            email = meta.email,
        )
    }

    override fun loadPlacements(): Map<String, WindowPlacement> =
        queries.selectAllPlacements().executeAsList().associate { row ->
            row.window_id to
                WindowPlacement(
                    x = row.x.toFloat(),
                    y = row.y.toFloat(),
                    width = row.width.toFloat(),
                    height = row.height.toFloat(),
                    visible = row.visible != 0L,
                )
        }

    override fun savePlacement(windowId: String, placement: WindowPlacement) {
        queries.upsertPlacement(
            window_id = windowId,
            x = placement.x.toDouble(),
            y = placement.y.toDouble(),
            width = placement.width.toDouble(),
            height = placement.height.toDouble(),
            visible = if (placement.visible) 1L else 0L,
        )
    }

    override fun loadSleepGaps(): List<SleepGapRecord> =
        queries.selectAllSleepGaps().executeAsList().map { row ->
            SleepGapRecord(
                deviceId = row.device_id,
                startMillis = row.sleep_start,
                endMillis = row.sleep_end,
                recordedAtMillis = row.recorded_at,
            )
        }

    override fun saveSleepGaps(records: List<SleepGapRecord>) {
        if (records.isEmpty()) return
        database.transaction {
            records.forEach { gap ->
                queries.upsertSleepGap(
                    device_id = gap.deviceId,
                    sleep_start = gap.startMillis,
                    sleep_end = gap.endMillis,
                    recorded_at = gap.recordedAtMillis,
                )
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
