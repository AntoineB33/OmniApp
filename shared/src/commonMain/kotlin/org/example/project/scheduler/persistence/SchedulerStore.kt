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
    /**
     * PRD §6: the name of the [org.example.project.scheduler.state.HistoryWindow] the unit was committed in,
     * or null when the shell named none (or the row was carried up from a pre-v12 DB, whose column is NULL).
     *
     * It sits AFTER [deltaJson] deliberately, mirroring the column order: the `delta` blob lives in overflow
     * pages, so every column a SAVE must read has to precede it — and a save never reads this one. The unit
     * is immutable once committed, so its window takes no part in the digest that decides row reuse; only
     * the full load reads it, and that reads the delta anyway.
     */
    val window: String? = null,
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

/**
 * A history category's Ctrl+Z/Y cursor, mirrored as a row in the `history_pointer` table — and one more row,
 * [WHOLE_PIN_ROW], holding the whole app's pin (`SchedulerHistories.wholePin`, a `deviceSeq`, hence a Long).
 */
@Serializable
data class HistoryPointerRow(
    val category: String,
    val pointer: Long,
)

/** The `history_pointer` row of the whole app's pin: no category's name, so an older build ignores it. */
const val WHOLE_PIN_ROW: String = "WholePin"

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
