package org.example.project.scheduler.persistence

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * One persisted History Unit (PRD §5/§6), mirrored as a single row in the `history_unit` table.
 * [ordinal] is the unit's index within its [category]'s list; [deltaJson] is the serialized
 * [org.example.project.scheduler.state.Delta] (see [SchedulerStateCodec]).
 *
 * [ordinal] is the DENSE index, which is also what a `history_pointer` indexes. The table's own key is a
 * stable `seq` the store allocates once per unit — the two are deliberately different things, because the
 * history cap evicts from the FRONT and a dense index as the row key meant one new unit renumbered them
 * all (see [SqlDelightSchedulerStore.save]).
 */
@Serializable
data class HistoryRow(
    val category: String,
    val ordinal: Int,
    val timeMillis: Long,
    val chronoId: Long,
    val debugTainted: Boolean,
    val deltaJson: String,
) {
    /**
     * [HistoryDigest] of [deltaJson], carried from the memo on the unit this row was encoded from so the
     * store never has to hash tens of MB to decide which rows it may reuse.
     *
     * [HistoryDigest.UNKNOWN_HASH] means "not carried" — a row built anywhere but [SchedulerStateCodec],
     * or read back from a pre-v11 DB — and every reader recomputes rather than trusting it, so it is only
     * ever an optimization. `@Transient` and outside the constructor on purpose: it is derived from
     * [deltaJson], so it must not reach the wire and must take no part in equality — two snapshots that
     * differ only here are the same snapshot, which is what [SchedulerStateCodec.syncFingerprint]
     * comparisons depend on.
     */
    @Transient
    var deltaHash: Long = HistoryDigest.UNKNOWN_HASH
}

/** A history category's Ctrl+Z/Y cursor, mirrored as a row in the `history_pointer` table. */
@Serializable
data class HistoryPointerRow(
    val category: String,
    val pointer: Int,
)

/**
 * The full durable scheduler payload as the store sees it: the non-history state serialized into
 * [statePayload] (one `app_state` row) plus the Undo/Redo history spread across one [HistoryRow] per
 * unit and one [HistoryPointerRow] per category.
 */
@Serializable
data class PersistedSnapshot(
    val statePayload: String,
    val history: List<HistoryRow>,
    val pointers: List<HistoryPointerRow>,
)

/**
 * Local persistence sink for the scheduler (PRD §5 Persistence).
 *
 * Implementations supply platform-specific storage — a SQLite database on desktop/Android/iOS (one
 * `history_unit` row per unit), or a serialized blob in browser localStorage on web. The codec lives
 * in [SchedulerStateCodec]; this interface only moves the structured [PersistedSnapshot] to/from
 * durable storage.
 */
interface SchedulerStore {
    /** Returns the previously saved payload, or `null` for an empty/first-run DB. */
    fun load(): PersistedSnapshot?

    /** Persists the latest snapshot. Called (debounced) after committed mutations. */
    fun save(snapshot: PersistedSnapshot)
}
