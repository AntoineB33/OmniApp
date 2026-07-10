package org.example.project

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.example.project.scheduler.sync.ServerPushDebounce
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * ARCHITECTURE.md §8: the user-change sync moment is a **10-second trailing debounce** — one push fires
 * 10 s after the LAST change of a burst, never per change and never immediately. (This replaced the old
 * leading-edge once-per-minute throttle: the four sync moments are login, the sync button, this debounce,
 * and the deferred pause-cue burst.)
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ServerPushDebounceTest {
    private val delayMs = 10_000L

    @Test
    fun a_change_pushes_once_after_the_delay_not_immediately() {
        val dispatcher = StandardTestDispatcher()
        runTest(dispatcher) {
            val scope = CoroutineScope(dispatcher)
            var pushes = 0
            val debounce = ServerPushDebounce(scope, delayMs) { pushes++ }

            debounce.request()
            runCurrent()
            assertEquals(0, pushes, "a change must not push immediately — the push waits out the debounce")

            advanceTimeBy(delayMs)
            runCurrent()
            assertEquals(1, pushes, "the push fires once the debounce elapses")

            // No further changes: nothing else ever fires.
            advanceTimeBy(delayMs * 3)
            runCurrent()
            assertEquals(1, pushes, "one change is never re-sent by a later idle interval")

            scope.cancel()
        }
    }

    @Test
    fun a_burst_coalesces_into_one_push_timed_from_the_last_change() {
        val dispatcher = StandardTestDispatcher()
        runTest(dispatcher) {
            val scope = CoroutineScope(dispatcher)
            var pushes = 0
            val debounce = ServerPushDebounce(scope, delayMs) { pushes++ }

            // Edits every 4 s: each restarts the countdown, so nothing pushes during the burst.
            debounce.request()
            advanceTimeBy(4_000); runCurrent()
            debounce.request()
            advanceTimeBy(4_000); runCurrent()
            debounce.request()
            runCurrent()
            assertEquals(0, pushes, "changes inside the debounce window must not push")

            // The single push lands exactly [delayMs] after the LAST change.
            advanceTimeBy(delayMs - 1)
            runCurrent()
            assertEquals(0, pushes, "not before the full quiet period elapsed")
            advanceTimeBy(1)
            runCurrent()
            assertEquals(1, pushes, "the burst collapses into one push, 10 s after the last change")

            scope.cancel()
        }
    }

    @Test
    fun a_change_after_a_completed_push_starts_a_fresh_debounce() {
        val dispatcher = StandardTestDispatcher()
        runTest(dispatcher) {
            val scope = CoroutineScope(dispatcher)
            var pushes = 0
            val debounce = ServerPushDebounce(scope, delayMs) { pushes++ }

            debounce.request()
            advanceTimeBy(delayMs)
            runCurrent()
            assertEquals(1, pushes)

            debounce.request()
            runCurrent()
            assertEquals(1, pushes, "the new change waits out its own debounce")
            advanceTimeBy(delayMs)
            runCurrent()
            assertEquals(2, pushes, "then pushes once")

            scope.cancel()
        }
    }
}
