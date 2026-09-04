package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.example.project.scheduler.sync.DeviceHeartbeatPublisher
import org.example.project.scheduler.sync.NextBreakState
import org.example.project.scheduler.model.TaskTimeRange
import org.example.project.scheduler.sync.PauseCueGateway
import org.example.project.scheduler.sync.PresenceState

/**
 * PRD §15 / migrations 20260724000000 + 20260726000000 — the client half of the pause-cue timing model, which
 * runs on **two cadences**:
 *
 *  * from the moment the device is signed in and unlocked it writes its presence row every **`t_a`** (10 s by
 *    default), and that row is `{ account, device, time of upsert }` — the same call re-arms the account's
 *    `data_payload_sent` row to `false`;
 *  * `t_a` is **server-owned** — the tick RPC returns the account's current value and the loop adopts it, so
 *    changing it over HTTP re-paces every device within one tick;
 *  * the two breaks' due instants are written **only when they change** — never on the beat, and retried until
 *    they land;
 *  * on screen-off the tick **stops** and the app calls the Edge Function once, so the cue is armed at the lock
 *    instant rather than waiting for the `t_b` cron to notice the missing beats.
 */
class PresenceTickTest {
    private val active = PresenceState(deviceId = "d1")

    private fun breakState(fiveMinDue: Long, fifteenMinDue: Long = 900_000L) =
        NextBreakState(fiveMinDueMillis = fiveMinDue, fifteenMinDueMillis = fifteenMinDue)

    /** Records the calls the publisher makes; [tickSeconds] is what the server replies with. */
    private class FakeGateway(var tickSeconds: Int? = null) : PauseCueGateway {
        var beats = 0
        var screenOffReports = 0
        val breakWrites = mutableListOf<NextBreakState>()

        /** When > 0, the next this-many break writes throw (a device that is offline / the RPC 500s). */
        var failBreakWrites = 0

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

        override suspend fun publishNextBreak(state: NextBreakState) {
            if (failBreakWrites > 0) {
                failBreakWrites--
                throw IllegalStateException("offline")
            }
            breakWrites += state
        }

        override suspend fun notifyScreenOff() {
            screenOffReports++
        }

        override suspend fun publishAccountState(sleeping: Boolean, wakeAtMillis: Long?) = Unit

        /** Every away-flag call: `null` is a read, a value is a write. Replies with the last written value. */
        val awayCalls = mutableListOf<Boolean?>()
        var accountAway = false

        override suspend fun syncDeviceAway(away: Boolean?): Boolean {
            awayCalls += away
            if (away != null) accountAway = away
            return accountAway
        }

        override suspend fun fetchAwaySpans(fromMillis: Long, toMillis: Long): List<TaskTimeRange> = emptyList()
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
    fun the_next_break_is_written_once_on_change_and_never_on_the_beat() = runTest {
        // The whole point of migration 20260726000000: an idle-but-present device makes ZERO break writes, so
        // the beat cannot be the thing carrying the break — and a break change reaches the server at once
        // instead of waiting up to one active-session beat (30 s) for the next piggyback.
        val gateway = FakeGateway()
        val publisher = DeviceHeartbeatPublisher(backgroundScope, gateway).also { it.start() }
        publisher.setPresence(active)
        publisher.setNextBreak(breakState(fiveMinDue = 5_000L))
        runCurrent()
        assertEquals(1, gateway.breakWrites.size, "the first window is published immediately")

        // Ten minutes of beating with an unchanged window: sixty beats, still one break write.
        advanceTimeBy(600_000)
        assertTrue(gateway.beats >= 60, "the presence tick kept running")
        publisher.setNextBreak(breakState(fiveMinDue = 5_000L)) // re-published, identical value
        runCurrent()
        assertEquals(1, gateway.breakWrites.size, "an unchanged window costs no request")

        // The pose is served / the schedule is edited → exactly one more write, right away.
        publisher.setNextBreak(breakState(fiveMinDue = 9_000L, fifteenMinDue = 4_000_000L))
        runCurrent()
        assertEquals(2, gateway.breakWrites.size)
        assertEquals(9_000L, gateway.breakWrites.last().fiveMinDueMillis)
        assertEquals(4_000_000L, gateway.breakWrites.last().fifteenMinDueMillis)
    }

    @Test
    fun a_failed_break_write_is_retried_until_it_lands() = runTest {
        // Unlike a beat, nothing re-sends this value on its own: a dropped write would leave the server judging
        // the cue on a stale break until the NEXT change, which may be hours away.
        val gateway = FakeGateway().apply { failBreakWrites = 2 }
        val publisher = DeviceHeartbeatPublisher(backgroundScope, gateway).also { it.start() }
        publisher.setPresence(active)
        publisher.setNextBreak(breakState(fiveMinDue = 5_000L))
        runCurrent()
        assertEquals(0, gateway.breakWrites.size, "the first attempt failed")

        advanceTimeBy(DeviceHeartbeatPublisher.BREAK_RETRY_MIN_MILLIS * 4)
        assertEquals(1, gateway.breakWrites.size, "it landed on a retry")
        assertEquals(5_000L, gateway.breakWrites.single().fiveMinDueMillis)

        // And the retry stops once it succeeds — it does not turn into a poll.
        advanceTimeBy(600_000)
        assertEquals(1, gateway.breakWrites.size)
    }

    @Test
    fun a_newer_window_supersedes_a_pending_retry() = runTest {
        // Offline while the pose changes twice: only the second (correct) window must reach the server.
        val gateway = FakeGateway().apply { failBreakWrites = 1 }
        val publisher = DeviceHeartbeatPublisher(backgroundScope, gateway).also { it.start() }
        publisher.setPresence(active)
        publisher.setNextBreak(breakState(fiveMinDue = 5_000L))
        runCurrent()
        publisher.setNextBreak(breakState(fiveMinDue = 9_000L))
        advanceTimeBy(DeviceHeartbeatPublisher.BREAK_RETRY_MIN_MILLIS * 4)

        assertEquals(listOf(9_000L), gateway.breakWrites.map { it.fiveMinDueMillis })
    }

    @Test
    fun going_active_again_re_publishes_the_break_even_when_it_is_unchanged() = runTest {
        // PRD §15 device↔account exclusivity: a device belongs to ONE account, so signing into another account
        // deletes this account's presence + break pair server-side (migration 20260727000000). Coming back must
        // restore BOTH — and a break written only on change would otherwise not be rewritten until the pose next
        // moved, leaving the account with a heartbeat but no break and so never owed a cue.
        val gateway = FakeGateway()
        val publisher = DeviceHeartbeatPublisher(backgroundScope, gateway).also { it.start() }
        publisher.setPresence(active)
        publisher.setNextBreak(breakState(fiveMinDue = 5_000L))
        runCurrent()
        assertEquals(1, gateway.breakWrites.size)

        publisher.setPresence(null) // lock / sign out
        runCurrent()
        publisher.setPresence(active) // unlock / sign back in — the engine re-asserts in the same pass
        publisher.setNextBreak(breakState(fiveMinDue = 5_000L)) // identical window
        runCurrent()
        assertEquals(2, gateway.breakWrites.size, "the re-assertion goes out despite the unchanged window")

        // …and it is still one write per activation, not a new cadence.
        advanceTimeBy(600_000)
        publisher.setNextBreak(breakState(fiveMinDue = 5_000L))
        runCurrent()
        assertEquals(2, gateway.breakWrites.size)
    }

    @Test
    fun the_break_row_survives_screen_off_so_the_server_judges_what_the_user_walked_away_in() = runTest {
        val gateway = FakeGateway()
        val publisher = DeviceHeartbeatPublisher(backgroundScope, gateway).also { it.start() }
        publisher.setPresence(active)
        publisher.setNextBreak(breakState(fiveMinDue = 5_000L))
        runCurrent()
        publisher.setPresence(null) // screen off
        advanceTimeBy(600_000)

        // No clearing write: the last window published while active IS the state to judge the cue on.
        assertEquals(1, gateway.breakWrites.size)
        assertEquals(1, gateway.screenOffReports)
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
