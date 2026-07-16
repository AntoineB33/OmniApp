package org.example.project.scheduler.persistence

/**
 * PRD §15 device-active sessions: one interval a device was **active** from [startMillis] to [endMillis]
 * (epoch millis). On the desktop "active" means the app was running, signed in, and the screen
 * unlocked/interactive; on the phone it means the app was in the **foreground**, expressed as one-minute
 * leases renewed every minute (`end = now + 1 min`), so backgrounding/killing the app reads as inactive
 * within a minute. [deviceId] is the install that recorded it, [kind] is that install's device kind
 * (`"desktop"`/`"phone"`/`"other"`; `""` on rows written before the column existed) — the label the
 * calendar's "which devices were open" hover bubble shows — and [updatedAtMillis] is when the row was
 * last extended/written (a live session's `endMillis` is advanced by a heartbeat, so an unclean shutdown
 * is bounded by the last beat / lease end).
 *
 * Device activity is a physical fact — NOT reconstructible from other state — so these rows are
 * authoritative, but they ride ONLY the manual Sync button (`SchedulerSyncEngine.reconcile` pushes this
 * device's rows and pulls every peer's into the local store); nothing about them ever triggers a push on
 * its own. They stay OUTSIDE [PersistedSnapshot]: never a History Unit, merged per-row rather than
 * clobbered by another device's whole-document snapshot. Same separation rationale as [SleepGapRecord] /
 * window_placement / sync_meta.
 */
data class ActiveSessionRecord(
    val deviceId: String,
    val startMillis: Long,
    val endMillis: Long,
    val updatedAtMillis: Long,
    val kind: String = "",
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
