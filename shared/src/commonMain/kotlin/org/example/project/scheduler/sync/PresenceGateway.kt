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

    /** Upserts this device's presence row (a pose-window beacon): its [kind] and whether its [screenActive] now. */
    suspend fun publishPresence(kind: DeviceKind, screenActive: Boolean)

    /**
     * Whether any device **other than this one** on the account has a fresh (newer than [staleMillis]) presence
     * row reporting an active screen. A device that slept/crashed without posting a fresh beacon ages out via
     * the freshness check, so it cannot read as active forever.
     *
     * Returns `false` when signed out (a definitive "no peer"), and **`null` on a transport failure** (the
     * answer is unknown) so the caller can retry within its own budget rather than mistaking a network blip
     * for "no peer active".
     */
    suspend fun activePeersExistOrNull(staleMillis: Long): Boolean?
}
