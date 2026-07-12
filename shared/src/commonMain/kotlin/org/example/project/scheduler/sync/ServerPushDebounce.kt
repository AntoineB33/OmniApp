package org.example.project.scheduler.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Defers the **server** push of a local change by [delayMillis] (ARCHITECTURE.md §8: the 10-second
 * user-change sync moment, one of the five snapshot sync moments). This is separate from the small
 * *local-save* debounce: the SQLite write still coalesces per keystroke, while the network push that mirrors
 * an authoritative change to Supabase is deferred here.
 *
 * Semantics (a leading-scheduled throttle — **not** a trailing debounce):
 * - The first [request] with nothing already pending starts the countdown; [push] fires [delayMillis] later.
 * - A [request] made while a push is **already waiting** changes nothing — it does NOT restart the countdown
 *   (the deliberate difference from a trailing debounce). So a burst collapses into one push timed from the
 *   **first** change of the burst, and a user who never stops editing is still synced every [delayMillis]
 *   rather than being starved until they go quiet.
 * - The countdown only (re)starts when a request actually initiates a new cycle — i.e. once the previous push
 *   has run and cleared the pending job, the next request schedules a fresh one.
 * - Nothing fires without a request, and [push] is never immediate (the change waits out the delay).
 *
 * Because the push reads the current state when it fires, every edit made during the window rides that one
 * push even though the later requests were absorbed — the caller also `markDirty()`s immediately, so a change
 * is durable even if the app closes before the deferred push (the next launch's login reconcile sends it).
 *
 * Not thread-safe: drive it from a single dispatcher (the owning ViewModel's save scope). [push] is launched
 * on [scope]; if it can overlap with a following push, the callee must serialize (the sync engine's reconcile
 * mutex does).
 */
class ServerPushDebounce(
    private val scope: CoroutineScope,
    private val delayMillis: Long,
    private val push: suspend () -> Unit,
) {
    private var job: Job? = null

    /**
     * Registers that the local state changed. If a push is already waiting this is a no-op (the countdown is
     * NOT restarted); otherwise it starts the [delayMillis] countdown after which [push] fires exactly once.
     */
    fun request() {
        if (job != null) return
        job =
            scope.launch {
                delay(delayMillis)
                job = null
                push()
            }
    }

    /** Drops any pending push (e.g. sign-out's farewell reconcile already carried the change). */
    fun cancel() {
        job?.cancel()
        job = null
    }
}
