package org.example.project.scheduler.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * PRD §15 / ARCHITECTURE.md §8 (requirements #4/#5): pushes the account's next pause-end cue instant to the
 * server at the **last responsible moment** instead of on every change, so an idle session never chatters.
 *
 * Two instants drive it:
 * - **d1** = the instant the server currently holds (what we last pushed — [lastPushed]).
 * - **d2** = this device's current prediction ([onPrediction], the next rest-pose end; always non-null in
 *   practice — every app state has an upcoming pause).
 *
 * The push fires at `min(d1, d2) − margin`:
 * - **d2 > d1 → [cancelMarginMillis] (2 s).** The server still holds the *earlier* d1, so the last phone has a
 *   stale, too-early alarm that **must be cancelled**; the upsert's DB trigger → edge push needs ~1 s to reach
 *   the phone plus ~1 s of slack before d1 would have fired.
 * - **otherwise → [publishMarginMillis] (1 s).** Nothing stale to cancel — the cue is moving earlier, or this
 *   is the first publish (d1 unknown) — so one propagation margin suffices.
 * - **d2 == d1 → no push.** Steady state: the server is already correct.
 *
 * Each distinct prediction re-arms a single timer (cancelling the prior one). After a push, d1 := d2, so the
 * next authoritative change is the only thing that re-arms it.
 *
 * The wait is computed against [nowMillis] and slept with coroutine [delay]; in production both are real wall
 * time (the sync clock is [org.example.project.time.SystemAppClock]). Under accelerated debug time simulation
 * the two diverge, so the deferred push is best-effort there — acceptable, as phone cue delivery is not a
 * sim-mode scenario. Not thread-safe: drive from a single dispatcher (the engine's scope).
 */
class PauseCuePushScheduler(
    private val scope: CoroutineScope,
    private val nowMillis: () -> Long,
    private val cancelMarginMillis: Long,
    private val publishMarginMillis: Long,
    private val push: suspend (dueAtMillis: Long) -> Unit,
) {
    private var lastPushed: Long? = null
    private var job: Job? = null

    /** Seeds d1 from the instant the server currently holds (best-effort, at startup). */
    fun seed(serverDueAtMillis: Long?) {
        lastPushed = serverDueAtMillis
    }

    /** The local prediction changed to [d2] (the next pause-end instant). Re-arms the deferred push. */
    fun onPrediction(d2: Long) {
        job?.cancel()
        val d1 = lastPushed
        if (d1 == d2) return // steady state: the server already holds the correct instant
        val target = if (d1 != null) minOf(d1, d2) else d2
        val margin = if (d1 != null && d2 > d1) cancelMarginMillis else publishMarginMillis
        job =
            scope.launch {
                val wait = target - margin - nowMillis()
                if (wait > 0) delay(wait)
                push(d2)
                lastPushed = d2
            }
    }
}
