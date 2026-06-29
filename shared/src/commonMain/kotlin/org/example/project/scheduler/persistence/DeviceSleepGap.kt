package org.example.project.scheduler.persistence

/**
 * PRD §15: one exact device-sleep interval — the user was away/paused from [startMillis] to [endMillis]
 * (epoch millis), as read from the OS on wake. [deviceId] is the install that recorded it and [recordedAtMillis]
 * is when the row was written (used to age out / decide "apply once" without re-walking the schedule).
 *
 * This is authoritative device-sleep history (an input to the schedule, not derivable from other state), so
 * it is synced across the account — but per-row and OUTSIDE [PersistedSnapshot], so it never enters the
 * Undo/Redo history and one device's rows are never clobbered by another's whole-document snapshot.
 */
data class SleepGapRecord(
    val deviceId: String,
    val startMillis: Long,
    val endMillis: Long,
    val recordedAtMillis: Long,
)

/**
 * Optional capability of a platform store: durable storage for [SleepGapRecord]s, keyed by
 * `(deviceId, startMillis)`. Implemented by the SQLite-backed store; stores without it (e.g. web's
 * localStorage) keep gaps in-memory only. Detected with `store as? DeviceSleepGapStore`, the same pattern
 * as [SyncMetaStore] / [WindowPlacementStore].
 */
interface DeviceSleepGapStore {
    /** All persisted sleep gaps, oldest first. Empty on a first run / fresh DB. */
    fun loadSleepGaps(): List<SleepGapRecord>

    /** Upserts the given gaps (a repeat of the same `(deviceId, startMillis)` replaces its row). */
    fun saveSleepGaps(records: List<SleepGapRecord>)
}
