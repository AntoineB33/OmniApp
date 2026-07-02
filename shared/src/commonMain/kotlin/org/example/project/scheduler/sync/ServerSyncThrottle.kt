package org.example.project.scheduler.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Spaces out **server** pushes so a local change reaches the remote DB at most once per [intervalMillis]
 * (ARCHITECTURE.md §8). This is separate from the small *local-save* debounce: the SQLite write still
 * coalesces per keystroke, but the network push that mirrors it to Supabase is rate-limited here.
 *
 * Semantics (a leading-edge throttle with a trailing flush):
 * - **Idle → immediate.** When no push happened in the last interval, [request] fires one right away, so a
 *   change arriving ≥ [intervalMillis] after the previous push is sent immediately.
 * - **Burst → one deferred push.** Requests during the cooldown do not each push; they mark a pending flush
 *   that fires once when the interval elapses, then re-arm the cooldown. All changes made in the same
 *   event-loop turn therefore ride the same push (the caller dispatches [request] after the turn settles).
 *
 * Not thread-safe: drive it from a single dispatcher (the owning ViewModel's save scope). [push] is launched
 * on [scope]; if it can overlap with a following push, the callee must serialize (the sync engine's reconcile
 * mutex does).
 */
class ServerSyncThrottle(
    private val scope: CoroutineScope,
    private val intervalMillis: Long,
    private val push: suspend () -> Unit,
) {
    // Non-null while inside the post-push cooldown; null means "idle → the next request pushes immediately".
    private var cooldownJob: Job? = null
    private var pending = false

    /** Registers that the local state changed and should be pushed, subject to the once-per-interval rule. */
    fun request() {
        if (cooldownJob == null) fire() else pending = true
    }

    private fun fire() {
        pending = false
        scope.launch { push() }
        cooldownJob =
            scope.launch {
                delay(intervalMillis)
                cooldownJob = null
                if (pending) fire()
            }
    }
}
