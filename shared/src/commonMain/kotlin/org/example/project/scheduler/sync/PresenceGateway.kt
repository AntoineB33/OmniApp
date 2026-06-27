package org.example.project.scheduler.sync

import org.example.project.scheduler.platform.DeviceKind

/**
 * PRD §15 cross-device presence: the seam the [org.example.project.scheduler.engine.SchedulerEngine] uses to
 * publish this device's "active screen" heartbeat and to ask whether any **other** device on the account
 * currently has an active screen. Implemented by [SchedulerSyncEngine] (which owns the Supabase session);
 * `null`/dormant when sync is disabled or signed out. Injectable so the engine can be tested with a fake.
 */
interface PresenceGateway {
    /** Whether a signed-in session is available (presence calls are no-ops otherwise). */
    val signedIn: Boolean

    /** Upserts this device's presence row (heartbeat): its [kind] and whether its [screenActive] now. */
    suspend fun publishPresence(kind: DeviceKind, screenActive: Boolean)

    /**
     * Whether any device **other than this one** on the account has a fresh (newer than [staleMillis])
     * heartbeat reporting an active screen. A device that slept/crashed without posting `screen_active=false`
     * ages out via the freshness check, so it cannot read as active forever. `false` when signed out/offline.
     */
    suspend fun activePeersExist(staleMillis: Long): Boolean
}
