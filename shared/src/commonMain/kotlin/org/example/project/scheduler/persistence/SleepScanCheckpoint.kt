package org.example.project.scheduler.persistence

/**
 * Optional capability of a platform store: a durable, LOCAL-ONLY watermark of how far this device has already
 * scanned its OS sleep/wake log for the launch backfill ([org.example.project.scheduler.engine.SchedulerEngine]
 * `backfillSleepGaps`). "Scanned through [millis]" means the log up to that epoch instant was already examined,
 * so the next launch resumes from there instead of re-reading the full 3-day horizon each time.
 *
 * This is device-local scan progress — it is NOT reconstructible from other state, but it is also NOT synced
 * (a peer's scan progress is meaningless here) and never a History Unit. It lives in its own single-row table,
 * kept separate from `sync_meta` so the sync engine's whole-row `sync_meta` writes can't clobber it. Detected
 * with `store as? SleepScanCheckpointStore`, the same pattern as [DeviceSleepGapStore] / [ActiveSessionStore];
 * a store without it (e.g. web's in-memory store) simply re-scans the full horizon each launch.
 */
interface SleepScanCheckpointStore {
    /** The epoch-millis instant the OS sleep/wake log was last scanned through, or null if never scanned. */
    fun loadSleepScanCheckpoint(): Long?

    /** Record that the OS sleep/wake log has now been scanned through [scannedThroughMillis]. */
    fun saveSleepScanCheckpoint(scannedThroughMillis: Long)
}
