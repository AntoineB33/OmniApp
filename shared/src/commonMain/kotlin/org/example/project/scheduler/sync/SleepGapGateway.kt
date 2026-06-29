package org.example.project.scheduler.sync

import org.example.project.scheduler.persistence.SleepGapRecord

/**
 * PRD §15 device-sleep gaps: the seam the [org.example.project.scheduler.engine.SchedulerEngine] uses to
 * publish the exact pause intervals THIS device recorded on wake, and to pull every device's gaps so the
 * local DB (and calendar) reflects the exact pauses other devices observed. Implemented by
 * [SchedulerSyncEngine] (which owns the Supabase session); `null`/dormant when sync is disabled or signed
 * out. Injectable so the engine can be tested with a fake.
 */
interface SleepGapGateway {
    /** Whether a signed-in session is available (gap calls are no-ops / unknown otherwise). */
    val signedIn: Boolean

    /** The stable id of THIS install — tags the gaps this device records so peers can attribute them. */
    val deviceId: String

    /** Upserts this device's [records] into the remote gaps table. Best-effort; swallows transport errors. */
    suspend fun pushSleepGaps(records: List<SleepGapRecord>)

    /**
     * Every device's gap rows for this account, or `null` on a transport failure (so a caller can retry
     * rather than mistake a network blip for "no gaps"). Returns an empty list when signed out.
     */
    suspend fun fetchSleepGaps(): List<SleepGapRecord>?
}
