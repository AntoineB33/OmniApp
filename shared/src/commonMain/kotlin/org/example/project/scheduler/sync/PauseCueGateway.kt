package org.example.project.scheduler.sync

/**
 * PRD §15 pause-end voice cue delivery (pg_cron + `device_heartbeat` model): the seam the
 * [org.example.project.scheduler.engine.SchedulerEngine] and the app use to keep the server reachable for the
 * cue — write this device's activity heartbeat, become the account's last-logged-in phone, and register this
 * device's push token. Implemented by [SchedulerSyncEngine] (which owns the Supabase session); `null`/dormant
 * when sync is disabled or signed out. Injectable so the engine can be tested with a fake.
 *
 * The cue's *timing* decision stays off the client, and there are **two** Edge Functions for it, one per way a
 * pause is detected (migration 20260729000000):
 *  * **e1**, `pause-cue` — the CLEAN LOCK. This device reports its own screen-off ([notifyScreenOff]) and e1
 *    decides whether a data payload is owed, timing the cue at `now() + break_length` — on this path the
 *    request *is* the walk-away, so there is nothing to estimate.
 *  * **e2**, `pause-cue-cron` — the DIRTY KILL, where no such report is ever sent. Every active device writes
 *    its presence row every `t_a`; the `t_b` cron (`tick_pause_cues()`) spots an account whose newest beat is
 *    older than `2·t_a` and owes an overdue break, and hands it to e2, which claims + computes + pushes with
 *    the estimated walk-away `t2 = max(beat_at) + t_a/2`.
 * The phone only receives the push ([SchedulerEngine.onPauseCuePush]) and schedules the OS-local cue itself.
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
     * reconcile may not have run recently enough to have refreshed it). Serialized with the reconcile
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
     * The **`t_a` tick** (migrations 20260724000000 → 20260728000000): one `publish_presence` RPC that upserts
     * this device's presence row — `{ account, device, server-stamped time of upsert }` and nothing else — and,
     * in the same call, the account's `data_payload_sent` row back to `false`. Runs from the moment the device
     * is signed in *and* unlocked, and repeats every `t_a`. The break instants travel separately
     * ([publishNextBreak]).
     *
     * Returns the account's current `t_a` in **seconds** so [DeviceHeartbeatPublisher] re-paces itself when it
     * is changed over HTTP (null ⇒ keep the current pacing — signed out, or the call failed). Best-effort.
     */
    suspend fun publishPresence(state: PresenceState): Int?

    /**
     * PRD §15: writes **when both screen breaks next come due** to the account's `device_break` row
     * (`publish_next_break` RPC, migrations 20260726000000 + 20260728000000). Called **only when the app
     * calculates a different pair**, so it costs nothing in steady state — and, unlike the old per-beat
     * piggyback, the server's copy is current the instant the schedule changes rather than up to one
     * active-session beat later. Throws on failure so the caller can retry.
     */
    suspend fun publishNextBreak(state: NextBreakState)

    /**
     * PRD §15 clean screen-off: this device's screen just went off, so it stopped its `t_a` tick — tell **e1**
     * (the `pause-cue` Edge Function) directly, so it can evaluate the account NOW rather than waiting for the
     * next `t_b` cron tick to notice the missing beats. e1 excludes this device from the liveness check and
     * times the cue from the instant this call lands — the lock event, i.e. the walk-away itself. A no-op while
     * signed out; best-effort — when this call never happens (the app was killed outright) the presence row
     * simply goes stale and the cron's own function, **e2** (`pause-cue-cron`), delivers the same cue later,
     * timed from the estimated midpoint of the tick interval the device vanished in.
     */
    suspend fun notifyScreenOff()

    /**
     * Emits after every reconcile (any trigger, any outcome) — the only moment peer active-session rows can
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
