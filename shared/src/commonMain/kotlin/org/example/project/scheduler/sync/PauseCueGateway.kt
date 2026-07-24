package org.example.project.scheduler.sync

/**
 * PRD §15 pause-end voice cue delivery (pg_cron + `device_heartbeat` model): the seam the
 * [org.example.project.scheduler.engine.SchedulerEngine] and the app use to keep the server reachable for the
 * cue — write this device's activity heartbeat, become the account's last-logged-in phone, and register this
 * device's push token. Implemented by [SchedulerSyncEngine] (which owns the Supabase session); `null`/dormant
 * when sync is disabled or signed out. Injectable so the engine can be tested with a fake.
 *
 * The cue's *timing* decision stays off the client: every active device writes its presence row every `t_a`, the
 * `t_b` cron (`tick_pause_cues()`) spots an account whose newest beat is older than `2·t_a`, and the `pause-cue`
 * Edge Function ("e1") evaluates + claims + pushes (migration 20260724000000). The phone only receives the push
 * ([SchedulerEngine.onPauseCuePush]) and schedules the OS-local cue itself.
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
     * The **`t_a` tick** (migration 20260724000000): upserts this device's row in the presence table
     * (`device_heartbeat`) via the `publish_presence` RPC while the device is unlocked + signed in. The server
     * stamps the row's time and clears its `data_payload_sent` claim flag.
     *
     * Returns the account's current `t_a` in **seconds** so [DeviceHeartbeatPublisher] re-paces itself when it
     * is changed over HTTP (null ⇒ keep the current pacing — signed out, or the call failed). Best-effort.
     */
    suspend fun publishPresence(state: PresenceState): Int?

    /**
     * PRD §15 clean screen-off: this device's screen just went off, so it stopped its `t_a` tick — tell **e1**
     * (the `pause-cue` Edge Function) directly, so it can evaluate the account NOW rather than waiting for the
     * next `t_b` cron tick to notice the missing beats. e1 excludes this device from the liveness check. A no-op
     * while signed out; best-effort (the stale presence row is the backstop when the app is killed outright).
     */
    suspend fun notifyScreenOff()

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
