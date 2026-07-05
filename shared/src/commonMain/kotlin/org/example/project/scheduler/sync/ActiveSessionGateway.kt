package org.example.project.scheduler.sync

import org.example.project.scheduler.model.TaskTimeRange
import org.example.project.scheduler.persistence.ActiveSessionRecord

/**
 * PRD §15 server-derived pauses: the seam the [org.example.project.scheduler.engine.SchedulerEngine] uses to
 * publish the exact intervals THIS device was active (app running + signed in + screen unlocked), and to pull
 * the account-wide **pauses** the server deduces from every device's active intervals (the windows when NO
 * device was active). The derivation runs server-side (the `derive_pauses` RPC); the client only reports its
 * own activity and reads back the result. Implemented by [SchedulerSyncEngine] (which owns the Supabase
 * session); `null`/dormant when sync is disabled or signed out. Injectable so the engine can be tested with a
 * fake.
 */
interface ActiveSessionGateway {
    /** Whether a signed-in session is available (session calls are no-ops / unknown otherwise). */
    val signedIn: Boolean

    /** The stable id of THIS install — tags the active-session rows this device records. */
    val deviceId: String

    /** Upserts this device's active-session [records] into the remote table. Best-effort; swallows transport errors. */
    suspend fun pushActiveSessions(records: List<ActiveSessionRecord>)

    /**
     * The account-wide pauses the server derives over `[sinceMillis, untilMillis]` (complement of the union of
     * every device's active intervals), or `null` on a transport failure (so a caller can retry rather than
     * mistake a network blip for "no pauses"). Returns an empty list when signed out.
     */
    suspend fun fetchDerivedPauses(sinceMillis: Long, untilMillis: Long): List<TaskTimeRange>?
}
