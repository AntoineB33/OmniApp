package org.example.project.scheduler.sync

/**
 * PRD §15 pause-end voice cue delivery (pg_cron + `device_heartbeat` model): the seam the
 * [org.example.project.scheduler.engine.SchedulerEngine] and the app use to keep the server reachable for the
 * cue — write this device's activity heartbeat, become the account's last-logged-in phone, and register this
 * device's push token. Implemented by [SchedulerSyncEngine] (which owns the Supabase session); `null`/dormant
 * when sync is disabled or signed out. Injectable so the engine can be tested with a fake.
 *
 * The cue's *timing* decision stays off the client: every active device upserts a `device_heartbeat` row, and
 * the server's `tick_pause_cues()` cron (migration 20260723000000) invokes the pause-cue Edge Function when the
 * account goes idle and is not sleeping. The phone only receives the push ([SchedulerEngine.onPauseCuePush]) and
 * schedules the OS-local cue itself. (This replaced the external Realtime-presence listener.)
 */
interface PauseCueGateway {
    /** Whether a signed-in session is available (all calls are no-ops otherwise). */
    val signedIn: Boolean

    /** The stable id of THIS install — recorded as the last-phone claimant and the push-token owner. */
    val deviceId: String

    /** The Supabase Realtime WebSocket endpoint + public anon key, for the presence publisher's connection. */
    val realtimeUrl: String
    val realtimeApiKey: String

    /** (userId, accessToken) for the Realtime presence join, or null while signed out. */
    fun realtimeAuth(): Pair<String, String>?

    /**
     * Forces a session access-token refresh (used when the Realtime join is rejected for an expired JWT — the
     * button-only sync model means the token isn't otherwise refreshed on launch). Serialized with the reconcile
     * refresh so the single-use refresh token isn't double-spent. Best-effort; after it, [realtimeAuth] returns
     * the fresh token.
     */
    suspend fun refreshRealtimeAuth()

    /**
     * Phone startup / foreground: becomes the account's last-logged-in phone. (The pause cue now fans out to
     * every registered phone via `device_id:'*'`, so this is informational — a record of the most recent phone;
     * the per-device eligibility gate is what keeps a device already back at a screen silent.) Best-effort.
     */
    suspend fun claimLastPhone()

    /** Registers this device's [token] (`platform` = `"fcm"`/`"apns"`) so the Edge Function can reach it. */
    suspend fun registerPushToken(kind: String, platform: String, token: String)

    /**
     * Upserts this device's `device_heartbeat` row (migration 20260723000000). While the device is active
     * [DeviceHeartbeatPublisher] calls this on a fixed interval with `closed = false` (the server stamps
     * `beat_at`, the liveness signal); on the inactive transition it calls it once with `closed = true`
     * (carrying the last-known break window) — the explicit "device locked" signal the cron detects next tick.
     * Best-effort; a no-op while signed out.
     */
    suspend fun publishHeartbeat(state: PresenceState, closed: Boolean)

    /**
     * Emits after every Sync-button reconcile (any outcome) — the only moment peer active-session rows can
     * land in the local store. The engine collects this to re-derive the Inactivity bands / refresh the
     * calendar's device-set data right after a sync. Null on gateways without a reconcile loop (tests).
     */
    val syncMoments: kotlinx.coroutines.flow.SharedFlow<Unit>? get() = null

    /**
     * Mirrors the account's Sleep/Work mode to the `account_state` table so the server's `tick_pause_cues()`
     * cron can suppress the pause-end cue while the user is deliberately away. [wakeAtMillis] is the scheduled
     * wake instant while sleeping (null when working). Best-effort; a no-op while signed out.
     */
    suspend fun publishAccountState(sleeping: Boolean, wakeAtMillis: Long?)
}
