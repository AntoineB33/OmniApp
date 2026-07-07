package org.example.project.scheduler.sync

/**
 * PRD §15 / ARCHITECTURE.md §8 pause-end voice cue delivery: the seam the
 * [org.example.project.scheduler.engine.SchedulerEngine] uses to drive the server half of the cue push — write
 * the account's next pause-end instant, become the account's last-logged-in phone, and register this device's
 * push token. Implemented by [SchedulerSyncEngine] (which owns the Supabase session); `null`/dormant when sync
 * is disabled or signed out. Injectable so the engine can be tested with a fake.
 *
 * This is transport only: the engine decides *when* the next-cue instant changes and whether this device is a
 * phone; the actual OS-scheduled local cue (AlarmManager / UNUserNotificationCenter) is the platform half.
 */
interface PauseCueGateway {
    /** Whether a signed-in session is available (all calls are no-ops otherwise). */
    val signedIn: Boolean

    /** The stable id of THIS install — recorded as the cue schedule's origin and the last-phone claimant. */
    val deviceId: String

    /**
     * Writes the account's next pause-end cue instant with origin = this device. The cron pushes the last phone
     * ~1 min before [dueAtMillis], **unless** this device *is* the last phone (origin == last phone) — then it
     * has already scheduled the cue locally, so the server stays silent. Best-effort; swallows transport errors.
     */
    suspend fun publishPauseCueSchedule(dueAtMillis: Long)

    /**
     * Reads the account's currently-stored next pause-end instant (epoch millis), or null if no row is set or
     * the read fails. Used to seed **d1** at startup so the deferred push ([PauseCuePushScheduler]) compares
     * this device's prediction against what the server already holds instead of pushing unconditionally.
     */
    suspend fun fetchPauseCueSchedule(): Long?

    /**
     * Phone startup: becomes the account's last-logged-in phone. The `account_last_phone` change fires the DB
     * trigger that pushes `cancel` to the *previous* phone, so only one phone ever speaks. Best-effort.
     */
    suspend fun claimLastPhone()

    /** Registers this device's [token] (`platform` = `"fcm"`/`"apns"`) so the Edge Function can reach it. */
    suspend fun registerPushToken(kind: String, platform: String, token: String)
}
