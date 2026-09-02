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
        // The rows come back in `seq` order, and `seq` is a stable allocation, not a position - so the DENSE
        // [HistoryRow.ordinal] the rest of the app speaks in (and that a `history_pointer` indexes) is the
        // row's index within its own category here, never the `seq` itself.
        val history =
            queries.selectAllHistory(account).executeAsList()
                .groupBy { it.category }
                .flatMap { (category, categoryRows) ->
                    categoryRows.mapIndexed { index, row ->
                        HistoryRow(
                            category = category,
                            ordinal = index,
                            timeMillis = row.time_millis,
                            chronoId = row.chrono_id,
                            debugTainted = row.debug_tainted != 0L,
                            deltaJson = row.delta,
                        )
                    }
                }
        val pointers =
            queries.selectAllPointers(account).executeAsList().map { row ->
                HistoryPointerRow(category = row.category, pointer = row.pointer.toInt())
            }
        return PersistedSnapshot(payload, history, pointers)
    }

    /**
     * Writes [snapshot], touching only the history rows that actually changed.
     *
     * **Adding a History Unit is one INSERT.** It used to be a `DELETE` of every row plus a re-INSERT of the
     * whole list, because the row key was the unit's dense index and the cap evicts from the FRONT - so one
     * new unit renumbered all 1000 and nothing could be reused. On the release account that was ~70 MB of
     * delta text rewritten on every 400 ms save debounce, i.e. on every burst of typing, and it is what put
     * the write transaction past the SQLite busy timeout (see ADR 0007).
     *
     * The rewrite is gone in three steps, and each is load-bearing:
     *
     * 1. The row's key is a **stable `seq`**, allocated once when the unit is first written and never
     *    renumbered. [HistoryRow.ordinal] stays the dense index the rest of the app speaks in; [load]
     *    derives it back from the seq order.
     * 2. What the incoming list is diffed against is the **digest** (`selectHistoryDigests`) - every column
     *    but `delta`. Reading the deltas back to compare them would reload exactly the bytes this exists to
     *    stop writing.
     * 3. The incoming side carries its own digest for free ([HistoryRow.deltaHash], memoized on the unit by
     *    [SchedulerStateCodec]), so a save hashes only the delta it is about to insert.
     *
     * The alignment relies on nothing but the identity in that digest: the incoming list is matched against
     * the stored one from its first unit onwards, whatever falls outside the matched run is deleted, and
     * whatever the match did not cover is appended above the highest seq. That covers the three things that
     * can happen to a history list - an append, a redo branch discarding the tail, the cap evicting the head
     * - and it degrades safely, because a run that fails to align is simply rewritten.
     */
    override fun save(snapshot: PersistedSnapshot) {
        val account = activeAccountId()
        database.transaction {
            queries.upsertAppState(account_id = account, payload = snapshot.statePayload)
            saveHistory(account, snapshot.history)
            queries.deleteAllPointers(account)
            snapshot.pointers.forEach { row ->
                queries.insertPointer(account_id = account, category = row.category, pointer = row.pointer.toLong())
            }
        }
    }

    /** One stored history row as the diff sees it: its key and its identity, never its delta. */
    private class StoredUnit(
        val seq: Long,
        val timeMillis: Long,
        val chronoId: Long,
        val debugTainted: Boolean,
        val deltaLength: Long,
        val deltaHash: Long?,
    )

    /** Reconciles every category's stored rows with [rows]. Call inside a transaction. */
    private fun saveHistory(account: String, rows: List<HistoryRow>) {
        val stored =
            queries.selectHistoryDigests(account).executeAsList()
                .groupBy({ it.category }) { row ->
                    StoredUnit(
                        seq = row.seq,
                        timeMillis = row.time_millis,
                        chronoId = row.chrono_id,
                        debugTainted = row.debug_tainted != 0L,
                        deltaLength = row.delta_length,
                        deltaHash = row.delta_hash,
                    )
                }
        val incoming = rows.groupBy { it.category }.mapValues { (_, list) -> list.sortedBy { it.ordinal } }
        for (category in stored.keys + incoming.keys) {
            saveHistoryCategory(account, category, stored[category].orEmpty(), incoming[category].orEmpty())
        }
    }

    private fun saveHistoryCategory(
        account: String,
        category: String,
        stored: List<StoredUnit>,
        incoming: List<HistoryRow>,
    ) {
        // Where the incoming list picks up in the stored one. Anything before it was evicted by the cap;
        // `stored.size` (no match at all, an emptied history included) means nothing may be reused.
        val start =
            incoming.firstOrNull()
                ?.let { first -> stored.indexOfFirst { it matches first } }
                ?.takeIf { it >= 0 }
                ?: stored.size
        var matched = 0
        while (start + matched < stored.size && matched < incoming.size &&
            stored[start + matched] matches incoming[matched]
        ) {
            matched++
        }

        // Everything outside the matched run goes: the head the cap evicted before it, the redo branch
        // discarded after it. A `seq` is never reused, so the appends below can only land above what is kept.
        stored.forEachIndexed { index, unit ->
            if (index < start || index >= start + matched) {
                queries.deleteHistoryUnit(account_id = account, category = category, seq = unit.seq)
            }
        }

        // A row a pre-v11 DB carried up matched on its remaining columns; give it the digest it lacked so the
        // next save can align on the full identity. Two integers - its delta is not touched.
        for (index in 0 until matched) {
            val unit = stored[start + index]
            if (unit.deltaHash != null && unit.deltaLength >= 0) continue
            val row = incoming[index]
            queries.healHistoryUnitDigest(
                delta_length = row.deltaJson.length.toLong(),
                delta_hash = row.digestHash(),
                account_id = account,
                category = category,
                seq = unit.seq,
            )
        }

        var nextSeq = (stored.maxOfOrNull { it.seq } ?: -1L) + 1
        for (index in matched until incoming.size) {
            val row = incoming[index]
            queries.insertHistoryUnit(
                account_id = account,
                category = category,
                seq = nextSeq++,
                time_millis = row.timeMillis,
                chrono_id = row.chronoId,
                debug_tainted = if (row.debugTainted) 1L else 0L,
                delta = row.deltaJson,
                delta_length = row.deltaJson.length.toLong(),
                delta_hash = row.digestHash(),
            )
        }
    }

    /**
     * Whether the stored row still holds this very unit.
     *
     * An unknown stored length/hash (a row a pre-v11 DB carried up) is not a mismatch - it is simply not
     * evidence, so the remaining columns decide and the row is healed above. A wrong `false` here costs one
     * rewritten row; a wrong `true` would keep a stale delta, which is why the timestamp, the tie-break
     * index, the taint flag, the length AND the hash all have to agree (see [HistoryDigest]).
     */
    private infix fun StoredUnit.matches(row: HistoryRow): Boolean =
        timeMillis == row.timeMillis &&
            chronoId == row.chronoId &&
            debugTainted == row.debugTainted &&
            (deltaLength < 0 || deltaLength == row.deltaJson.length.toLong()) &&
            (deltaHash == null || deltaHash == row.digestHash())

    /** [HistoryRow.deltaHash] if the codec carried it, else computed here. */
    private fun HistoryRow.digestHash(): Long =
        deltaHash.takeIf { it != HistoryDigest.UNKNOWN_HASH } ?: HistoryDigest.hash(deltaJson)

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
