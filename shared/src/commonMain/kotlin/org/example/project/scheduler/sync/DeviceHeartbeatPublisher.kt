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
 * PRD §15 cross-device activity, presence-table model (migration 20260724000000): what a device publishes about
 * itself while it is **active** (screen on / unlocked, signed in; on Android also app-foreground). The server's
 * `evaluate_pause_cue()` reads the account's presence rows to decide, when they all go stale and the account is
 * not sleeping, when to fire the phones' pause-end cue. `null` [nextBreakEndMillis] means no upcoming ≥5-min
 * break.
 */
data class PresenceState(
    val deviceId: String,
    val kind: String,
    val nextBreakEndMillis: Long?,
    /**
     * PRD §15 (server-side break computation): the drawn START of the next ≥5-min rest pose. A start pinned to
     * the now-line means the break is already WAITING (overdue-unserved poses slide, `maxOf(due, now)`), so the
     * server computes the true pause end from the instant the account actually went idle — not from this (up to
     * one beat stale) client projection.
     */
    val nextBreakStartMillis: Long? = null,
    /** PRD §15: the length of that pose (`end − start`) — also the "waiting break" length for the cue. */
    val nextBreakLenMillis: Long? = null,
    /**
     * PRD §15: WHICH of the two break types is waiting — `"5min_break"` / `"15min_break"`
     * ([org.example.project.scheduler.model.ScreenBreak.key]). The server looks its configured length and vocal
     * message up in `break_config`, so both are changeable over HTTP without touching the client.
     */
    val nextBreakKind: String? = null,
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
 * The **`t_a` tick**: writes this device's row in the presence table every `t_a` while it is active, and on the
 * inactive transition stops ticking and tells the `pause-cue` Edge Function ("e1") directly that this device's
 * screen went off. This is the client half of the pause-cue timing model (migration 20260724000000): the server
 * stamps the row's time and, when no row of the account has been refreshed within `2·t_a`, times the pause-end
 * cue from `t2 = <row time> + t_a/2` plus the waiting break's length.
 *
 * `t_a` is **server-owned**: [PauseCueGateway.publishPresence] returns the account's current value (default
 * [DEFAULT_TICK_SECONDS]) and this loop adopts it on the next pass, so changing it over HTTP re-paces every
 * device within one tick. [beatMillisOverride] pins it instead (tests).
 *
 * Lifecycle, driven by [setPresence] (via [org.example.project.scheduler.engine.SchedulerEngine.updatePresence]):
 *  - active (non-null) → re-publish the row every `t_a`. [collectLatest] restarts the loop when the payload
 *    changes, so a new break window reaches the server at once.
 *  - inactive (null) → stop ticking and call e1 once (the clean screen-off short-circuit), so the cue is armed
 *    at the lock instant. A dirty kill makes no such call — there the row simply goes stale and the `t_b` cron
 *    tick delivers the same cue, later.
 *
 * All writes are best-effort (a failed beat must never crash the engine); the server's staleness window is the
 * backstop when one is missed. Every call is a no-op while signed out ([PauseCueGateway.signedIn]).
 */
class DeviceHeartbeatPublisher(
    private val scope: CoroutineScope,
    private val gateway: PauseCueGateway,
    private val beatMillisOverride: Long? = null,
) : RealtimePresence {
    private val desired = MutableStateFlow<PresenceState?>(null)
    private var job: Job? = null
    // The server-published `t_a`, adopted from the last successful beat. Starts at the documented default.
    private var beatMillis: Long = beatMillisOverride ?: DEFAULT_TICK_SECONDS * 1000L
    // Whether this device was ever active in this run — a device that never ticked has nothing to report off.
    private var everActive = false

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
                // Screen off / signed out: stop ticking and hand the account to e1 right away. Skipped when this
                // device never ticked (nothing on the server to go stale, so nothing to evaluate).
                if (everActive) {
                    runCatching { gateway.notifyScreenOff() }
                        .onFailure { Diagnostics.log("screen-off cue evaluation skipped: ${it.message}") }
                }
                return@collectLatest
            }
            everActive = true
            while (coroutineContext.isActive) {
                runCatching { gateway.publishPresence(state) }
                    .onSuccess { tickSeconds -> if (beatMillisOverride == null) adoptTick(tickSeconds) }
                    .onFailure { Diagnostics.log("presence beat skipped: ${it.message}") }
                delay(beatMillis)
            }
        }
    }

    // Adopt the account's server-side `t_a`, ignoring an absent/insane value so a bad row can never wedge the
    // tick (or turn it into a hot loop).
    private fun adoptTick(tickSeconds: Int?) {
        val seconds = tickSeconds ?: return
        if (seconds !in MIN_TICK_SECONDS..MAX_TICK_SECONDS) return
        val millis = seconds * 1000L
        if (millis != beatMillis) {
            Diagnostics.log("presence tick t_a adopted: ${beatMillis / 1000}s → ${seconds}s")
            beatMillis = millis
        }
    }

    companion object {
        /** `t_a` when the account has no `app_config` row (or the server could not be reached yet). */
        const val DEFAULT_TICK_SECONDS = 10

        // The server's own bounds (`app_config.tick_seconds` CHECK); a value outside them is ignored.
        private const val MIN_TICK_SECONDS = 1
        private const val MAX_TICK_SECONDS = 3600
    }
}
