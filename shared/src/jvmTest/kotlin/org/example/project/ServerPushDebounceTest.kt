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
 * ARCHITECTURE.md §8: the user-change sync moment is a **10-second leading-scheduled throttle** — the first
 * change of a burst schedules one push 10 s later, and a change made while that push is already waiting is
 * absorbed (it does NOT restart the countdown), so the push is timed from the FIRST change and a never-quiet
 * user is still synced every 10 s. Never per change and never immediate. (The five sync moments are login,
 * the sync button, this throttle, the deferred pause-cue burst, and the device-inactivity detection.)
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
    fun a_burst_coalesces_into_one_push_timed_from_the_first_change() {
        val dispatcher = StandardTestDispatcher()
        runTest(dispatcher) {
            val scope = CoroutineScope(dispatcher)
            var pushes = 0
            val debounce = ServerPushDebounce(scope, delayMs) { pushes++ }

            // Edits every 4 s: only the FIRST starts a countdown; the rest are absorbed while it is pending
            // (they do NOT restart it), so the single push is timed from the first change, not the last.
            debounce.request()
            advanceTimeBy(4_000); runCurrent()
            debounce.request()
            advanceTimeBy(4_000); runCurrent()
            debounce.request()
            runCurrent()
            assertEquals(0, pushes, "changes inside the throttle window must not push")

            // The single push lands exactly [delayMs] after the FIRST change (8 s already elapsed above).
            advanceTimeBy(delayMs - 8_000 - 1)
            runCurrent()
            assertEquals(0, pushes, "not before [delayMs] since the first change elapsed")
            advanceTimeBy(1)
            runCurrent()
            assertEquals(1, pushes, "the burst collapses into one push, 10 s after the FIRST change")

            // Quiet afterward: no further push (the absorbed requests are not re-sent).
            advanceTimeBy(delayMs * 2)
            runCurrent()
            assertEquals(1, pushes, "absorbed requests are carried by the one push, never re-sent")

            scope.cancel()
        }
    }

    @Test
    fun a_request_while_a_push_is_waiting_does_not_restart_the_countdown() {
        val dispatcher = StandardTestDispatcher()
        runTest(dispatcher) {
            val scope = CoroutineScope(dispatcher)
            var pushes = 0
            val debounce = ServerPushDebounce(scope, delayMs) { pushes++ }

            debounce.request()
            advanceTimeBy(delayMs - 1); runCurrent()
            // A second request 1 ms before the push would have RESET a trailing debounce; here it is absorbed.
            debounce.request()
            advanceTimeBy(1); runCurrent()
            assertEquals(1, pushes, "the pending push still fires on the original schedule, unmoved")

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
