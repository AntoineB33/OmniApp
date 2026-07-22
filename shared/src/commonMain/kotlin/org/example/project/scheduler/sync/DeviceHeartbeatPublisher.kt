package org.example.project.scheduler.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext
import org.example.project.scheduler.platform.Diagnostics

/**
 * PRD §15 cross-device activity, pg_cron + `device_heartbeat` model (migration 20260723000000): what a device
 * publishes about itself while it is **active** (app foreground, screen on / unlocked, signed in). The server's
 * `tick_pause_cues()` reads the freshest heartbeat rows to decide, when the whole account goes idle and is not
 * sleeping, when to fire the phone's pause-end cue. `null` [nextBreakEndMillis] means no upcoming ≥5-min break.
 */
data class PresenceState(
    val deviceId: String,
    val kind: String,
    val nextBreakEndMillis: Long?,
    /**
     * PRD §15 (server-side break computation): the drawn START of the next ≥5-min rest pose. A start pinned to
     * the now-line means the break is already WAITING (overdue-unserved poses slide, `maxOf(due, now)`), so the
     * server computes the true pause end as `max(idleSince, start) + len` from the instant the account actually
     * went idle — not from this (up to one beat stale) client projection.
     */
    val nextBreakStartMillis: Long? = null,
    /** PRD §15: the length of that pose (`end − start`) — also the "waiting break" length for the cue. */
    val nextBreakLenMillis: Long? = null,
)

/**
 * The seam the [org.example.project.scheduler.engine.SchedulerEngine] uses to publish this device's activity.
 * `null` clears it (the device went inactive / signed out); a non-null state (re)publishes it. Idempotent per
 * value. Implemented by [DeviceHeartbeatPublisher]; a no-op fake in tests.
 */
interface RealtimePresence {
    fun setPresence(state: PresenceState?)
}

/**
 * Writes this device's `device_heartbeat` row every [HEARTBEAT_MILLIS] while it is active, and flips the row
 * `closed = true` once when it goes inactive. This is the client half of the pause-cue timing model that
 * replaced the external Realtime-presence listener (migration 20260723000000): the server stamps `beat_at`
 * and, when no fresh non-closed heartbeat remains for an account, times the pause-end cue from the row's
 * published break window.
 *
 * Lifecycle, driven by [setPresence] (via [org.example.project.scheduler.engine.SchedulerEngine.updatePresence]):
 *  - active (non-null) → re-upsert the row on a fixed interval (the freshness signal). [collectLatest] restarts
 *    the loop when the payload changes, so a new break window is pushed at once.
 *  - inactive (null) → one `closed = true` upsert carrying the last-known window (the explicit "device locked"
 *    signal, detected on the next server tick). A clean lock usually completes this write; a dirty lock (the app
 *    was suspended first) instead lets the row's `beat_at` go stale, which the server's staleness window catches.
 *
 * All writes are best-effort (a failed heartbeat must never crash the engine); the server's staleness window is
 * the backstop when a write is missed. Every call is a no-op while signed out ([PauseCueGateway.signedIn]).
 */
class DeviceHeartbeatPublisher(
    private val scope: CoroutineScope,
    private val gateway: PauseCueGateway,
    private val beatMillis: Long = HEARTBEAT_MILLIS,
) : RealtimePresence {
    private val desired = MutableStateFlow<PresenceState?>(null)
    private var job: Job? = null
    // The last active payload, so the inactive transition can carry its break window into the closed row.
    private var lastActive: PresenceState? = null

    override fun setPresence(state: PresenceState?) {
        desired.value = state
    }

    /** Starts the publish loop (idempotent). */
    fun start() {
        if (job != null) return
        job = scope.launch { run() }
    }

    private suspend fun run() {
        desired.collectLatest { state ->
            if (state == null) {
                // Inactive: mark our row closed once (carrying the last-known window). No further writes until
                // the device is active again (a new non-null [desired] restarts this collector).
                lastActive?.let { s ->
                    runCatching { gateway.publishHeartbeat(s, closed = true) }
                        .onFailure { Diagnostics.log("heartbeat close skipped: ${it.message}") }
                }
                return@collectLatest
            }
            lastActive = state
            while (coroutineContext.isActive) {
                runCatching { gateway.publishHeartbeat(state, closed = false) }
                    .onFailure { Diagnostics.log("heartbeat skipped: ${it.message}") }
                delay(beatMillis)
            }
        }
    }

    companion object {
        // The active re-upsert cadence. The server's staleness window (~25 s) is ~2.5× this, tolerating a
        // dropped write before an active device is mistaken for idle.
        const val HEARTBEAT_MILLIS = 10_000L
    }
}
