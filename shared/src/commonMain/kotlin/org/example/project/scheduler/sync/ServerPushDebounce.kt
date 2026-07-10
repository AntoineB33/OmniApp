package org.example.project.scheduler.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Defers the **server** push of a local change until the user has been quiet for [delayMillis]
 * (ARCHITECTURE.md §8: the 10-second user-change debounce, one of the four snapshot sync moments). This is
 * separate from the small *local-save* debounce: the SQLite write still coalesces per keystroke, while the
 * network push that mirrors an authoritative change to Supabase is deferred here.
 *
 * Semantics (a trailing-edge debounce):
 * - Every [request] (re)starts the countdown; [push] fires once, [delayMillis] after the **last** request.
 * - A burst of edits therefore collapses into exactly one push — the server never sees per-change traffic.
 * - Nothing fires without a request, and a request is never re-sent by a later idle interval.
 *
 * A change is durable even if the app closes before the deferred push: the caller `markDirty()`s
 * immediately, so the next launch's login reconcile sends it.
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

    /** Registers that the local state changed; the push fires [delayMillis] after the LAST call. */
    fun request() {
        job?.cancel()
        job =
            scope.launch {
                delay(delayMillis)
                job = null
                push()
            }
    }
}
