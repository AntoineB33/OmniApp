package org.example.project

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.example.project.scheduler.engine.SchedulerEngine
import org.example.project.scheduler.ui.TaskSchedulerViewModel
import org.example.project.time.AppClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * ARCHITECTURE.md §8 sync moment #5 (device inactivity): when the heartbeat's device check finds the device
 * went inactive — the screen turned off, or the process was suspended and this wake-up beat spots the gap —
 * the open active session is finalized, and that finalized interval is authoritative state peers cannot
 * re-derive (without it a peer over-presumes this device active up to its own now-line). So the engine must
 * request a unified sync moment right then, not merely let the finalize ride the next of the other four
 * moments. The trigger fires exactly ONCE per active→inactive transition, and never while a session stays
 * closed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeviceInactivitySyncMomentTest {
    private val fixedClock = object : AppClock {
        override fun nowMillis(): Long = 1_000_000L
    }

    @Test
    fun a_finalized_session_requests_one_sync_moment_per_transition() {
        val dispatcher = StandardTestDispatcher()
        runTest(dispatcher) {
            val scope = CoroutineScope(dispatcher)
            val vm = TaskSchedulerViewModel(saveDispatcher = dispatcher)
            var syncRequests = 0
            val engine = SchedulerEngine(
                vm = vm,
                clock = fixedClock,
                scope = scope,
                requestSyncMoment = { syncRequests++ },
            )

            // Active screen: a session opens. Opening never syncs (a peer re-derives an open session).
            engine.heartbeatSampleForTest(active = true, suspended = false)
            assertNotNull(engine.activeSince.value, "an active heartbeat opens a session")
            assertEquals(0, syncRequests, "opening/extending a session must not request a sync")

            // Extend it: still no sync.
            engine.heartbeatSampleForTest(active = true, suspended = false)
            assertEquals(0, syncRequests, "extending the open session must not request a sync")

            // Screen off: the session finalizes — this is the device-inactivity moment.
            engine.heartbeatSampleForTest(active = false, suspended = false)
            assertNull(engine.activeSince.value, "an inactive heartbeat finalizes the session")
            assertNotNull(engine.inactiveSince.value, "the live-inactivity tail starts at the finalize")
            assertEquals(1, syncRequests, "the finalize requests exactly one sync moment")

            // Still inactive: no open session to finalize, so no re-fire (a screen that stays off is silent).
            engine.heartbeatSampleForTest(active = false, suspended = false)
            engine.heartbeatSampleForTest(active = false, suspended = true)
            assertEquals(1, syncRequests, "a device that stays inactive does not re-request a sync")

            scope.cancel()
        }
    }

    @Test
    fun a_wake_from_suspension_finalizes_and_syncs_even_when_active_again() {
        val dispatcher = StandardTestDispatcher()
        runTest(dispatcher) {
            val scope = CoroutineScope(dispatcher)
            val vm = TaskSchedulerViewModel(saveDispatcher = dispatcher)
            var syncRequests = 0
            val engine = SchedulerEngine(
                vm = vm,
                clock = fixedClock,
                scope = scope,
                requestSyncMoment = { syncRequests++ },
            )

            engine.heartbeatSampleForTest(active = true, suspended = false)
            assertEquals(0, syncRequests)

            // The wake-up beat after a suspension: the pre-sleep session is finalized (spotting the gap) and,
            // the user having returned, a fresh session opens in the same sample — the finalize still syncs.
            engine.heartbeatSampleForTest(active = true, suspended = true)
            assertNotNull(engine.activeSince.value, "a new session opens after the wake-up gap")
            assertEquals(1, syncRequests, "the finalize of the pre-suspension session requests a sync moment")

            scope.cancel()
        }
    }
}
