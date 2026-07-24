package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.example.project.scheduler.sync.DeviceHeartbeatPublisher
import org.example.project.scheduler.sync.PauseCueGateway
import org.example.project.scheduler.sync.PresenceState

/**
 * PRD §15 / migration 20260724000000 — the client half of the pause-cue timing model:
 *
 *  * while the screen is on, this device writes its presence row every **`t_a`** (10 s by default);
 *  * `t_a` is **server-owned** — the tick RPC returns the account's current value and the loop adopts it, so
 *    changing it over HTTP re-paces every device within one tick;
 *  * on screen-off the tick **stops** and the app calls the Edge Function once, so the cue is armed at the lock
 *    instant rather than waiting for the `t_b` cron to notice the missing beats.
 */
class PresenceTickTest {
    private val active = PresenceState(deviceId = "d1", kind = "desktop", nextBreakEndMillis = null)

    /** Records the calls the publisher makes; [tickSeconds] is what the server replies with. */
    private class FakeGateway(var tickSeconds: Int? = null) : PauseCueGateway {
        var beats = 0
        var screenOffReports = 0

        override val signedIn: Boolean = true
        override val deviceId: String = "d1"
        override val realtimeUrl: String = ""
        override val realtimeApiKey: String = ""

        override fun realtimeAuth(): Pair<String, String>? = null

        override suspend fun refreshRealtimeAuth() = Unit

        override suspend fun claimLastPhone() = Unit

        override suspend fun registerPushToken(kind: String, platform: String, token: String) = Unit

        override suspend fun publishPresence(state: PresenceState): Int? {
            beats++
            return tickSeconds
        }

        override suspend fun notifyScreenOff() {
            screenOffReports++
        }

        override suspend fun publishAccountState(sleeping: Boolean, wakeAtMillis: Long?) = Unit
    }

    @Test
    fun the_tick_beats_every_t_a_while_active_and_defaults_to_ten_seconds() = runTest {
        // No `app_config` row (the RPC returns nothing): the documented 10 s default paces the loop.
        assertEquals(10, DeviceHeartbeatPublisher.DEFAULT_TICK_SECONDS)
        val gateway = FakeGateway()
        val publisher = DeviceHeartbeatPublisher(backgroundScope, gateway).also { it.start() }

        advanceTimeBy(60_000)
        assertEquals(0, gateway.beats, "a device that is not active never beats")

        publisher.setPresence(active)
        runCurrent()
        assertEquals(1, gateway.beats, "the first beat goes out immediately")

        advanceTimeBy(30_001)
        assertEquals(4, gateway.beats, "three more beats in 30 s at t_a = 10 s")
    }

    @Test
    fun a_server_side_t_a_change_re_paces_the_tick() = runTest {
        // The account's `t_a` was changed over HTTP to 60 s; the reply to the very first beat carries it.
        val gateway = FakeGateway(tickSeconds = 60)
        val publisher = DeviceHeartbeatPublisher(backgroundScope, gateway).also { it.start() }
        publisher.setPresence(active)
        runCurrent()
        assertEquals(1, gateway.beats)

        advanceTimeBy(30_000)
        assertEquals(1, gateway.beats, "the 10 s default was replaced by the server's 60 s before the first wait")
        advanceTimeBy(30_001)
        assertEquals(2, gateway.beats)
    }

    @Test
    fun an_out_of_range_t_a_is_ignored_so_a_bad_row_cannot_wedge_or_spin_the_tick() = runTest {
        val gateway = FakeGateway(tickSeconds = 0) // below the server's own CHECK bound
        val publisher = DeviceHeartbeatPublisher(backgroundScope, gateway).also { it.start() }
        publisher.setPresence(active)
        runCurrent()

        advanceTimeBy(10_001)
        assertEquals(2, gateway.beats, "still the 10 s default — not a hot loop")
    }

    @Test
    fun screen_off_stops_the_tick_and_reports_once_to_the_edge_function() = runTest {
        val gateway = FakeGateway()
        val publisher = DeviceHeartbeatPublisher(backgroundScope, gateway).also { it.start() }
        publisher.setPresence(active)
        runCurrent()
        advanceTimeBy(20_001)
        val beatsWhileActive = gateway.beats
        assertTrue(beatsWhileActive >= 3)

        publisher.setPresence(null) // screen off
        runCurrent()
        assertEquals(1, gateway.screenOffReports)

        advanceTimeBy(120_000)
        assertEquals(beatsWhileActive, gateway.beats, "the t_a tick stops while the screen is off")
        assertEquals(1, gateway.screenOffReports, "and the Edge Function is called exactly once")

        // Coming back on resumes the tick (which re-arms the next idle episode server-side).
        publisher.setPresence(active)
        runCurrent()
        assertEquals(beatsWhileActive + 1, gateway.beats)
    }

    @Test
    fun a_device_that_never_became_active_reports_no_screen_off() = runTest {
        val gateway = FakeGateway()
        val publisher = DeviceHeartbeatPublisher(backgroundScope, gateway).also { it.start() }
        // Signed out / never unlocked: there is no presence row on the server, so there is nothing to evaluate.
        publisher.setPresence(null)
        runCurrent()
        assertEquals(0, gateway.screenOffReports)
        assertEquals(0, gateway.beats)
    }
}
