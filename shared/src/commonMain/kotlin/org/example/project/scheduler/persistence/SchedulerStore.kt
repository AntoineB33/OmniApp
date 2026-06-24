package org.example.project.scheduler.persistence

import kotlinx.serialization.Serializable

/**
 * One persisted History Unit (PRD §5/§6), mirrored as a single row in the `history_unit` table.
 * [ordinal] is the unit's index within its [category]'s list; [deltaJson] is the serialized
 * [org.example.project.scheduler.state.Delta] (see [SchedulerStateCodec]).
 */
@Serializable
data class HistoryRow(
    val category: String,
    val ordinal: Int,
    val timeMillis: Long,
    val chronoId: Long,
    val debugTainted: Boolean,
    val deltaJson: String,
)

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
