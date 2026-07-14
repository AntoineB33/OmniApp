package org.example.project.scheduler.sync

/**
 * PRD §15 pause-end voice cue delivery (Realtime-presence + external-listener model): the seam the
 * [org.example.project.scheduler.engine.SchedulerEngine] and the app use to keep the server reachable for the
 * cue — become the account's last-logged-in phone and register this device's push token. Implemented by
 * [SchedulerSyncEngine] (which owns the Supabase session); `null`/dormant when sync is disabled or signed out.
 * Injectable so the engine can be tested with a fake.
 *
 * The cue's *timing* decision moved off the client entirely: the external listener watches Realtime Presence
 * and invokes the pause-cue Edge Function when the account goes inactive, so the client no longer publishes or
 * reads a next-cue instant. The phone only receives the push ([SchedulerEngine.onPauseCuePush]) and schedules
 * the OS-local cue itself.
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
     * Phone startup / foreground: becomes the account's last-logged-in phone (the device the listener pushes).
     * The `account_last_phone` change fires the DB trigger that cancels the *previous* phone's pending cue, so
     * only one phone ever speaks. Best-effort.
     */
    suspend fun claimLastPhone()

    /** Registers this device's [token] (`platform` = `"fcm"`/`"apns"`) so the Edge Function can reach it. */
    suspend fun registerPushToken(kind: String, platform: String, token: String)

    /**
     * Mirrors the account's Sleep/Work mode to the `account_state` table so the external Realtime listener can
     * suppress the pause-end cue while the user is deliberately away. [wakeAtMillis] is the scheduled wake
     * instant while sleeping (null when working). Best-effort; a no-op while signed out.
     */
    suspend fun publishAccountState(sleeping: Boolean, wakeAtMillis: Long?)
}
