package org.example.project

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.example.project.scheduler.sync.ServerSyncThrottle
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class ServerSyncThrottleTest {
    private val interval = 60_000L

    @Test
    fun idle_request_pushes_immediately() {
        val dispatcher = StandardTestDispatcher()
        runTest(dispatcher) {
            val scope = CoroutineScope(dispatcher)
            var pushes = 0
            val throttle = ServerSyncThrottle(scope, interval) { pushes++ }

            throttle.request()
            runCurrent()
            assertEquals(1, pushes, "an idle request is sent immediately (leading edge)")

            scope.cancel()
        }
    }

    @Test
    fun burst_within_interval_coalesces_into_one_deferred_push() {
        val dispatcher = StandardTestDispatcher()
        runTest(dispatcher) {
            val scope = CoroutineScope(dispatcher)
            var pushes = 0
            val throttle = ServerSyncThrottle(scope, interval) { pushes++ }

            // Three changes in the same turn: one leading push, the rest fold into a pending flush.
            throttle.request()
            throttle.request()
            throttle.request()
            runCurrent()
            assertEquals(1, pushes, "the burst does not push per change")

            // The trailing flush lands exactly one interval after the leading push.
            advanceTimeBy(interval)
            runCurrent()
            assertEquals(2, pushes, "the coalesced changes push once when the interval elapses")

            // No further changes: the next interval clears the cooldown without an extra push.
            advanceTimeBy(interval)
            runCurrent()
            assertEquals(2, pushes)

            scope.cancel()
        }
    }

    @Test
    fun change_after_a_quiet_interval_is_sent_immediately() {
        val dispatcher = StandardTestDispatcher()
        runTest(dispatcher) {
            val scope = CoroutineScope(dispatcher)
            var pushes = 0
            val throttle = ServerSyncThrottle(scope, interval) { pushes++ }

            throttle.request()
            runCurrent()
            assertEquals(1, pushes)

            // Let the cooldown lapse with nothing pending, then change again.
            advanceTimeBy(interval)
            runCurrent()
            assertEquals(1, pushes, "an idle interval fires nothing on its own")

            throttle.request()
            runCurrent()
            assertEquals(2, pushes, "a change ≥ one interval after the last push goes out immediately")

            scope.cancel()
        }
    }

    @Test
    fun a_single_change_pushes_exactly_once() {
        val dispatcher = StandardTestDispatcher()
        runTest(dispatcher) {
            val scope = CoroutineScope(dispatcher)
            var pushes = 0
            val throttle = ServerSyncThrottle(scope, interval) { pushes++ }

            throttle.request()
            advanceTimeBy(interval * 3)
            runCurrent()
            assertEquals(1, pushes, "one change is never re-sent by the trailing timer")

            scope.cancel()
        }
    }
}
