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
 * PRD §15 cross-device activity, presence-table model: what a device publishes about **itself** while it is
 * active (screen on / unlocked, signed in; on Android also app-foreground).
 *
 * Since migration 20260726000000 this is *identity only* — the presence row is `{ user_id, device_id }` plus a
 * server-stamped `beat_at` and the `data_payload_sent` claim flag. Its one job is "this account was alive at
 * time T"; the break the account is waiting on rides [NextBreakState] on its own, event-driven cadence.
 */
data class PresenceState(val deviceId: String)

/**
 * PRD §15: **when each of the two screen breaks next comes due** — the whole content of the account's
 * `device_break` row, written **only when the app (re)calculates them into a different pair** (migrations
 * 20260726000000 + 20260728000000). A device sitting at its desk publishes nothing.
 *
 * Both instants are the poses' mathematical DUE times (`lastRest + interval`) on the REAL wall clock, **not**
 * their drawn starts. That distinction is what makes an event-driven write possible at all: an overdue pose's
 * drawn start is clamped to the now-line and so changes at every sample, whereas the due instant moves only when
 * the pose is served or reconfigured. The server's overdue gate reads them as "was this break already due at the
 * last moment we saw this account?" and lets the longest overdue one govern.
 *
 * The row is account-keyed, not device-keyed: both instants derive from the synced screen-break config, so every
 * device of the account computes the same pair. Null means "no such pose configured, or none anchored yet" — an
 * unanchored pose must NOT be published, or its 1970 sentinel due would read as permanently overdue and earn a
 * spurious cue on a freshly emptied account.
 *
 * The row is deliberately NEVER cleared on screen-off: the last value published while active is exactly the
 * state the user walked away in, which is what the cue is judged on.
 */
data class NextBreakState(
    val fiveMinDueMillis: Long?,
    val fifteenMinDueMillis: Long?,
)

/**
 * The seam the [org.example.project.scheduler.engine.SchedulerEngine] uses to publish this device's state.
 * Implemented by [DeviceHeartbeatPublisher]; a no-op fake in tests.
 */
interface RealtimePresence {
    /** Activity: a non-null state starts/keeps the `t_a` tick, `null` stops it (screen off / signed out). */
    fun setPresence(state: PresenceState?)

    /**
     * The two screen breaks' due instants. Idempotent per value — re-publishing an unchanged pair costs no
     * request. Call it only while the device is active; it is never cleared (see [NextBreakState]).
     */
    fun setNextBreak(state: NextBreakState)
}

/**
 * The **`t_a` tick** plus the event-driven break write — the client half of the pause-cue timing model
 * (migrations 20260724000000 + 20260726000000). Two independent cadences, matching two different rates of
 * change:
 *
 *  * **presence**, every `t_a` from the moment the device is signed in *and* unlocked: `publish_presence(
 *    device_id)`, which upserts `{account, device, server-stamped time}` into the presence table **and** re-arms
 *    the account's `data_payload_sent` row to `false`, atomically. When no row of the account has been refreshed
 *    within `2·t_a`, the server times the pause-end cue from `t2 = <row time> + t_a/2` plus the waiting break's
 *    length. The payload carries nothing but the device id, so the loop is **not** restarted by a break change —
 *    a burst of schedule edits cannot turn the tick into a request storm.
 *  * **the two breaks**, only on change: `publish_next_break(due5, due15)`. Because it is written the instant
 *    the app recalculates them, the server's copy is never up-to-one-beat stale — the race that let a device go
 *    dark within 30 s of a schedule edit and be judged on the pre-edit break (and that the clean screen-off path
 *    hit *by construction*, since it calls e1 without a final beat) is closed. A failed write is retried with
 *    backoff until it lands or a newer value supersedes it, so the two paths' freshness guarantees are
 *    equivalent.
 *
 * `t_a` is **server-owned**: [PauseCueGateway.publishPresence] returns the account's current value (default
 * [DEFAULT_TICK_SECONDS]) and this loop adopts it on the next pass, so changing it over HTTP re-paces every
 * device within one tick. [beatMillisOverride] pins it instead (tests).
 *
 * On the inactive transition the tick stops and **e1** (`pause-cue`) is called once — the clean screen-off
 * short-circuit — so the cue is armed at the lock instant *and timed from it* (`now() + break_length`; this
 * request is the walk-away, so nothing is estimated). A dirty kill makes no such call: there the row simply
 * goes stale and the `t_b` cron hands the account to the OTHER function, **e2** (`pause-cue-cron`), which
 * delivers the same cue later, timed from `max(beat_at) + t_a/2`.
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
    private val nextBreak = MutableStateFlow<BreakRequest?>(null)
    private var job: Job? = null
    private var breakJob: Job? = null

    /**
     * Bumped on every inactive→active transition, and carried in the dedupe key below so that transition
     * force-republishes the break even when its value is unchanged.
     *
     * Needed because the server may have dropped this device's break row without the client knowing: a device is
     * associated to exactly ONE account (PRD §15), so signing into another account deletes the previous
     * account's presence + break pair server-side (migration 20260727000000). Coming back to the first account
     * would otherwise leave it with a heartbeat but no break — and a break that is only written on CHANGE would
     * not be rewritten until the pose next moved, so the account could never be owed a cue. An activation is an
     * event, not a cadence, so this costs a handful of writes a day and leaves the steady state at zero.
     */
    private var activation = 0

    // The dedupe key: the window itself plus the activation it was published under.
    private data class BreakRequest(val state: NextBreakState, val activation: Int)
    // The server-published `t_a`, adopted from the last successful beat. Starts at the documented default.
    private var beatMillis: Long = beatMillisOverride ?: DEFAULT_TICK_SECONDS * 1000L
    // Whether this device was ever active in this run — a device that never ticked has nothing to report off.
    private var everActive = false

    override fun setPresence(state: PresenceState?) {
        // Going active again starts a new publication epoch (see [activation]); the caller re-asserts the break
        // in the same pass, and the changed key makes that re-assertion actually go out.
        if (desired.value == null && state != null) activation++
        desired.value = state
    }

    // The StateFlow dedupes by value, so an unchanged window never reaches the collector below — that is the
    // whole "zero writes in steady state" guarantee, and it holds only because [NextBreakState] carries the
    // FIXED due instant rather than the now-line-riding drawn start.
    override fun setNextBreak(state: NextBreakState) {
        nextBreak.value = BreakRequest(state, activation)
    }

    /** Starts the publish loops (idempotent). */
    fun start() {
        if (job == null) job = scope.launch { run() }
        if (breakJob == null) breakJob = scope.launch { runBreakWrites() }
    }

    private suspend fun run() {
        desired.collectLatest { state ->
            if (state == null) {
                // Screen off / signed out: stop ticking and hand the account to e1 right away. Skipped when this
                // device never ticked (nothing on the server to go stale, so nothing to evaluate). The break row
                // is deliberately left as it is — it holds the state the user walked away in.
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

    // The event-driven half. [collectLatest] cancels an in-flight retry the moment a newer window arrives, so a
    // device that is offline while its pose changes twice ends up publishing only the second (correct) one.
    private suspend fun runBreakWrites() {
        nextBreak.collectLatest { request ->
            val state = request?.state ?: return@collectLatest
            var backoff = BREAK_RETRY_MIN_MILLIS
            while (coroutineContext.isActive) {
                val failure = runCatching { gateway.publishNextBreak(state) }.exceptionOrNull()
                if (failure == null) {
                    Diagnostics.log(
                        "next breaks published: 5min_due=${state.fiveMinDueMillis} " +
                            "15min_due=${state.fifteenMinDueMillis}",
                    )
                    return@collectLatest
                }
                // Retried rather than dropped: unlike a beat, nothing later re-sends this value on its own, so a
                // lost write would leave the server judging the cue on a stale break until the NEXT change.
                Diagnostics.log("next-break write failed, retrying in ${backoff}ms: ${failure.message}")
                delay(backoff)
                backoff = minOf(backoff * 2, BREAK_RETRY_MAX_MILLIS)
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

        // Retry cadence for a failed break write: fast enough to land while the user is still at the desk,
        // capped so an offline device does not poll a dead network.
        internal const val BREAK_RETRY_MIN_MILLIS = 2_000L
        internal const val BREAK_RETRY_MAX_MILLIS = 60_000L
    }
}
