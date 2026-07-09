package org.example.project.scheduler.persistence

/**
 * PRD §15 device-active sessions: one interval this device was **active** — the app was running, signed in,
 * and the screen was unlocked/interactive — from [startMillis] to [endMillis] (epoch millis). [deviceId] is
 * the install that recorded it and [updatedAtMillis] is when the row was last extended/written (a live
 * session's `endMillis` is advanced by a heartbeat, so an unclean shutdown is bounded by the last beat).
 *
 * This is authoritative device-activity history (an input the server needs to deduce the account-wide
 * pauses — the windows when NO device was active — not derivable from other local state), so it is synced
 * across the account, per-row and OUTSIDE [PersistedSnapshot]: it never enters the Undo/Redo history and one
 * device's rows are never clobbered by another's whole-document snapshot. Same separation rationale as
 * [SleepGapRecord] / window_placement / sync_meta.
 */
data class ActiveSessionRecord(
    val deviceId: String,
    val startMillis: Long,
    val endMillis: Long,
    val updatedAtMillis: Long,
)

/**
 * Optional capability of a platform store: durable storage for [ActiveSessionRecord]s, keyed by
 * `(deviceId, startMillis)`. Implemented by the SQLite-backed store; stores without it (e.g. web's
 * localStorage) keep sessions in-memory only. Detected with `store as? ActiveSessionStore`, the same
 * pattern as [DeviceSleepGapStore] / [SyncMetaStore] / [WindowPlacementStore].
 */
interface ActiveSessionStore {
    /** All persisted active sessions, oldest first. Empty on a first run / fresh DB. */
    fun loadActiveSessions(): List<ActiveSessionRecord>

    /** Upserts the given sessions (a repeat of the same `(deviceId, startMillis)` replaces its row). */
    fun saveActiveSessions(records: List<ActiveSessionRecord>)

    /**
     * Deletes every session recorded under [deviceId]. Used by the startup heal that purges the legacy
     * `remote-activity` adoption rows (presumed — not observed — activity an older build wrote; see
     * [org.example.project.scheduler.engine.SchedulerEngine.REMOTE_ACTIVITY_DEVICE_ID]).
     */
    fun deleteActiveSessionsForDevice(deviceId: String)
}
