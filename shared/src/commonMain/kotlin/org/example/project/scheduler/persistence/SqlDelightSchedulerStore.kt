package org.example.project.scheduler.persistence

import org.example.project.scheduler.persistence.db.SchedulerDatabase

/**
 * SQLite-backed [SchedulerStore] (PRD §5 Persistence) over a SQLDelight [SchedulerDatabase].
 *
 * The non-history state is one row in `app_state`; the Undo/Redo history is stored per-unit, one row
 * per [HistoryRow] in `history_unit`, plus one [HistoryPointerRow] per category in `history_pointer`.
 * [save] replaces the whole history in a single transaction so a redo branch that was discarded in
 * memory (the units after the pointer) leaves no stale rows behind.
 *
 * **All three are ACCOUNT-SCOPED** (schema v9): the app is always connected to an account — a credential-less
 * GUEST account when the user never signed in or signed out — and each account keeps its own partition,
 * keyed by the Supabase user id in [activeAccountId] (read from `sync_meta.user_id` on every call, so a
 * sign-in/sign-out simply swaps which partition is live). Signing in to another account therefore never
 * deletes the data of the account being left; it stops being the active partition and comes back untouched
 * when that account signs in again. [UNCLAIMED_ACCOUNT_ID] is the partition of a device that has no account
 * yet (a fresh install still offline, or a pre-v9 DB migrated while signed out); the first guest account
 * adopts it via [adoptUnclaimedAccountData].
 */
class SqlDelightSchedulerStore(private val database: SchedulerDatabase) :
    SchedulerStore, SyncMetaStore, WindowPlacementStore, DeviceSleepGapStore, ActiveSessionStore,
    SleepScanCheckpointStore {
    private val queries = database.schedulerQueries

    /** The account whose partition [load]/[save] read and write: the signed-in user, else "unclaimed". */
    private fun activeAccountId(): String =
        queries.selectSyncMeta().executeAsOneOrNull()?.user_id ?: UNCLAIMED_ACCOUNT_ID

    override fun load(): PersistedSnapshot? {
        val account = activeAccountId()
        val payload = queries.selectAppState(account).executeAsOneOrNull() ?: return null
        val history =
            queries.selectAllHistory(account).executeAsList().map { row ->
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
            queries.selectAllPointers(account).executeAsList().map { row ->
                HistoryPointerRow(category = row.category, pointer = row.pointer.toInt())
            }
        return PersistedSnapshot(payload, history, pointers)
    }

    override fun save(snapshot: PersistedSnapshot) {
        val account = activeAccountId()
        database.transaction {
            queries.upsertAppState(account_id = account, payload = snapshot.statePayload)
            queries.deleteAllHistory(account)
            snapshot.history.forEach { row ->
                queries.insertHistoryUnit(
                    account_id = account,
                    category = row.category,
                    ordinal = row.ordinal.toLong(),
                    time_millis = row.timeMillis,
                    chrono_id = row.chronoId,
                    debug_tainted = if (row.debugTainted) 1L else 0L,
                    delta = row.deltaJson,
                )
            }
            queries.deleteAllPointers(account)
            snapshot.pointers.forEach { row ->
                queries.insertPointer(account_id = account, category = row.category, pointer = row.pointer.toLong())
            }
        }
    }

    override fun loadSyncMeta(): SyncMeta? =
        queries.selectSyncMeta().executeAsOneOrNull()?.let { row ->
            val account = queries.selectAccountSync(row.user_id ?: UNCLAIMED_ACCOUNT_ID).executeAsOneOrNull()
            SyncMeta(
                deviceId = row.device_id,
                lastKnownRevision = account?.last_known_revision ?: 0L,
                dirty = account?.dirty?.let { it != 0L } ?: false,
                accessToken = row.access_token,
                refreshToken = row.refresh_token,
                userId = row.user_id,
                email = row.email,
                acknowledgedLogoutAtMillis = account?.acknowledged_logout_at,
                baseSnapshot = account?.base_payload,
            )
        }

    /**
     * Writes the device-level fields always, and the per-account ones under the account in [meta].
     *
     * A save that CHANGES the active account (a sign-in, a guest creation, a sign-out) writes no account
     * row: the values in hand describe the account being LEFT, and copying them onto the account being
     * entered would hand it a foreign `revision` baseline — under whole-document LWW that silently pushes
     * this device's data over that account's remote snapshot (or ignores it). The account being entered
     * keeps whatever this device already recorded for it (defaults, when it is new here), which the very
     * next [loadSyncMeta] returns.
     */
    override fun saveSyncMeta(meta: SyncMeta) {
        val previousAccount = queries.selectSyncMeta().executeAsOneOrNull()?.user_id
        database.transaction {
            queries.upsertSyncMeta(
                device_id = meta.deviceId,
                access_token = meta.accessToken,
                refresh_token = meta.refreshToken,
                user_id = meta.userId,
                email = meta.email,
            )
            if (meta.userId != null && meta.userId == previousAccount) {
                queries.upsertAccountSync(
                    account_id = meta.userId,
                    last_known_revision = meta.lastKnownRevision,
                    dirty = if (meta.dirty) 1L else 0L,
                    acknowledged_logout_at = meta.acknowledgedLogoutAtMillis,
                    base_payload = meta.baseSnapshot,
                )
            }
        }
    }

    override fun adoptUnclaimedAccountData(accountId: String) {
        if (accountId.isEmpty() || accountId == UNCLAIMED_ACCOUNT_ID) return
        database.transaction {
            // Only when the account has no partition of its own — never merge two partitions together.
            if (queries.selectAppState(accountId).executeAsOneOrNull() != null) return@transaction
            if (queries.selectAppState(UNCLAIMED_ACCOUNT_ID).executeAsOneOrNull() == null) return@transaction
            queries.reassignAppState(accountId, UNCLAIMED_ACCOUNT_ID)
            queries.reassignHistory(accountId, UNCLAIMED_ACCOUNT_ID)
            queries.reassignPointers(accountId, UNCLAIMED_ACCOUNT_ID)
        }
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

    override fun loadActiveSessions(): List<ActiveSessionRecord> =
        queries.selectAllActiveSessions().executeAsList().map { row ->
            ActiveSessionRecord(
                deviceId = row.device_id,
                startMillis = row.start_ms,
                endMillis = row.end_ms,
                updatedAtMillis = row.updated_at,
                kind = row.kind,
            )
        }

    override fun deleteActiveSessionsForDevice(deviceId: String) {
        queries.deleteActiveSessionsByDevice(deviceId)
    }

    override fun saveActiveSessions(records: List<ActiveSessionRecord>) {
        if (records.isEmpty()) return
        database.transaction {
            records.forEach { session ->
                queries.upsertActiveSession(
                    device_id = session.deviceId,
                    start_ms = session.startMillis,
                    end_ms = session.endMillis,
                    updated_at = session.updatedAtMillis,
                    kind = session.kind,
                )
            }
        }
    }

    override fun loadSleepScanCheckpoint(): Long? =
        queries.selectSleepScanCheckpoint().executeAsOneOrNull()

    override fun saveSleepScanCheckpoint(scannedThroughMillis: Long) {
        queries.upsertSleepScanCheckpoint(scannedThroughMillis)
    }

    companion object {
        /**
         * Partition key for scheduler data written while the device had no account at all — a first launch
         * that could not reach Supabase to create its guest account, or a pre-v9 DB migrated while signed
         * out. The first guest account created on the device adopts it ([adoptUnclaimedAccountData]).
         */
        const val UNCLAIMED_ACCOUNT_ID: String = ""
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
