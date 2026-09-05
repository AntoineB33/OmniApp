package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.example.project.scheduler.domain.DynamicPeriods
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.engine.SchedulerEngine
import org.example.project.scheduler.model.TaskTimeRange
import org.example.project.scheduler.sync.NextBreakState
import org.example.project.scheduler.sync.PauseCueGateway
import org.example.project.scheduler.sync.PresenceState
import org.example.project.scheduler.state.SchedulerState
import org.example.project.scheduler.ui.TaskSchedulerViewModel
import org.example.project.time.AppClock

/**
 * `docs/scheduler_requirements.md` § *$now line$ 3 modes*: **mode 3 is an ACCOUNT-wide condition, and the "I'm
 * away" flag has to leave the device it was pressed on for that to be true.**
 *
 * *"Mode 3 happens when and only when there is at least one device where the I'm away button is clicked and all
 * the other devices are locked."* So the flag is a quantifier over the account's devices, not a property of the
 * install asking: a peer that is merely locked must reach the same mode as the machine the button was pressed
 * on, or the two place the three dynamic periods differently and the calendar disagrees with itself across
 * devices.
 *
 * The flag travels through [PauseCueGateway.syncDeviceAway], which writes this device's value and answers the
 * account's question in the same round trip. What is pinned here is that the engine publishes on every edge and
 * adopts the answer — including the answer that says a PEER is away while this device's own button is off.
 */
class AccountAwayModeTest {

    private val NOW = 1_000_000_000_000L

    /** Records every away call and replies with whatever the "account" is set to. */
    private class FakeGateway : PauseCueGateway {
        val awayCalls = mutableListOf<Boolean?>()

        /** Another device of the account has the button on — the fact this device cannot know by itself. */
        var peerAway = false
        private var ownAway = false

        override val signedIn: Boolean = true
        override val deviceId: String = "d1"
        override val realtimeUrl: String = ""
        override val realtimeApiKey: String = ""

        override fun realtimeAuth(): Pair<String, String>? = null
        override suspend fun refreshRealtimeAuth() = Unit
        override suspend fun claimLastPhone() = Unit
        override suspend fun registerPushToken(kind: String, platform: String, token: String) = Unit
        override suspend fun publishPresence(state: PresenceState): Int? = null
        override suspend fun publishNextBreak(state: NextBreakState) = Unit
        override suspend fun notifyScreenOff() = Unit
        override suspend fun publishAccountState(sleeping: Boolean, wakeAtMillis: Long?) = Unit
        override suspend fun fetchAwaySpans(fromMillis: Long, toMillis: Long): List<TaskTimeRange> = emptyList()

        // The server's own rule: write this device's flag, answer for the ACCOUNT.
        override suspend fun syncDeviceAway(away: Boolean?): Boolean {
            awayCalls += away
            if (away != null) ownAway = away
            return ownAway || peerAway
        }
    }

    private class Harness(var now: Long, val gateway: FakeGateway, var unlocked: Boolean = true) {
        val vm = TaskSchedulerViewModel(initial = SchedulerState.empty(), store = null, saveDispatcher = Dispatchers.Default)
        val engine =
            SchedulerEngine(
                vm = vm,
                clock = object : AppClock { override fun nowMillis(): Long = now },
                scope = CoroutineScope(Dispatchers.Unconfined),
                // Unlocked, like a machine the user leaves running while they walk off — the case the button
                // exists for, and the one where the lock signal alone says nothing.
                screenActive = { unlocked },
                playCue = {},
                pauseCue = gateway,
            )

        /** Open a session, then close it a minute later: the account-wide pause the mode's first half reads. */
        suspend fun goIdle() {
            engine.heartbeatSampleForTest(active = true, suspended = false)
            now += 60_000L
            engine.heartbeatSampleForTest(active = false, suspended = false)
            now += 60_000L
        }
    }

    @Test
    fun pressing_the_button_publishes_it_and_an_unlock_publishes_the_clearing() = runTest {
        val gateway = FakeGateway()
        val h = Harness(NOW, gateway)

        h.engine.setUserAway(true)
        assertEquals(listOf<Boolean?>(true), gateway.awayCalls, "the press must reach the account")
        assertTrue(h.engine.userAway.value)

        // A same-value press is a no-op, here as everywhere: no second write.
        h.engine.setUserAway(true)
        assertEquals(listOf<Boolean?>(true), gateway.awayCalls)

        h.engine.setUserAway(false)
        assertEquals(listOf<Boolean?>(true, false), gateway.awayCalls, "coming back must reach it too")
    }

    @Test
    fun a_peer_declaring_itself_away_puts_this_device_in_mode_three() = runTest {
        // The whole point of publishing the flag. This device's own button is OFF and its screen is unlocked at
        // the platform level — but it reports itself idle (the beat below), and the SERVER says some other
        // device of the account has the button on. That is the spec's condition met, so the mode is 3.
        val gateway = FakeGateway()
        val h = Harness(NOW, gateway)

        // Nothing away anywhere yet, and this device reports itself inactive: no device unlocked, nobody has
        // said they are on a break — mode 2, which is the mode this test exists to tell mode 3 from. (A session
        // has to have been open for its end to be the instant the pause derives from.)
        h.goIdle()
        assertEquals(DynamicPeriods.MODE_AWAY, h.engine.tpModeNow(h.now))

        // A peer presses "I'm away"; this device learns it at its next sync moment (or its next own edge).
        gateway.peerAway = true
        h.engine.setUserAway(true)
        h.engine.setUserAway(false)
        assertTrue(!h.engine.userAway.value, "this device's OWN button is off")

        assertEquals(
            DynamicPeriods.MODE_ON_BREAK,
            h.engine.tpModeNow(h.now),
            "some device of the account is away and none is unlocked: that is mode 3, on every device",
        )
    }

    @Test
    fun the_button_takes_effect_at_the_press_even_with_no_server_to_tell() = runTest {
        // The `or` in the engine's reading is not redundancy: the account's answer includes this device only
        // once the publish has landed, and a user pressing the button offline must still get mode 3.
        val h = Harness(NOW, gateway = FakeGateway())
        h.engine.setUserAway(true)
        h.goIdle()
        assertEquals(DynamicPeriods.MODE_ON_BREAK, h.engine.tpModeNow(h.now))
    }

    @Test
    fun the_button_records_the_stretch_it_was_on_so_the_calendar_can_hatch_it() = runTest {
        // `docs/scheduler_requirements.md` § *$now line$ 3 modes* + PRD §8: the period this device is declared
        // away for has to be COVERED BY THE TWO LAYERS on the calendar — it is a stretch where no computer and
        // no phone of the account is unlocked, which is what a no-screen period is. The OS cannot supply it:
        // the machine stays unlocked the whole time (`screenActive = { true }` here, which is the case the
        // button exists for), so the engine's own record of the button is the only source there is.
        val h = Harness(NOW, FakeGateway())
        assertTrue(h.engine.declaredAwaySpans.value.isEmpty())
        assertEquals(null, h.engine.declaredAwaySince.value)

        // A session is open first, so the press has one to finalize: pressing the button is what makes this
        // device read idle, and mode 3 needs the account to be.
        h.engine.heartbeatSampleForTest(active = true, suspended = false)
        h.now += 60_000L
        val pressed = h.now
        h.engine.setUserAway(true)
        assertEquals(pressed, h.engine.declaredAwaySince.value, "the press opens the stretch at the press")

        // While the button is on the stretch grows with the now-line and stops there — the live shape the
        // calendar draws, exactly like the Inactivity tail beside it.
        h.now += 5 * 60_000L
        assertEquals(
            listOf(TaskTimeRange(pressed, h.now)),
            SchedulerDomain.declaredAwayRegions(
                h.engine.declaredAwaySpans.value, h.engine.declaredAwaySince.value, h.now,
            ),
        )
        // …and the account is in mode 3 for the whole of it, which is the correspondence the requirement
        // states: this kind of period and mode 3 are the same set.
        assertEquals(DynamicPeriods.MODE_ON_BREAK, h.engine.tpModeNow(h.now))

        val returned = h.now
        h.engine.setUserAway(false)
        assertEquals(
            listOf(TaskTimeRange(pressed, returned)),
            h.engine.declaredAwaySpans.value,
            "the closed stretch",
        )
        assertEquals(null, h.engine.declaredAwaySince.value)
        // Back at the screen: the hatch stays where it happened and grows no further (the past is frozen).
        h.now += 60_000L
        assertEquals(
            listOf(TaskTimeRange(pressed, returned)),
            SchedulerDomain.declaredAwayRegions(
                h.engine.declaredAwaySpans.value, h.engine.declaredAwaySince.value, h.now,
            ),
        )
    }

    @Test
    fun the_unlock_that_clears_the_button_closes_the_stretch_too() = runTest {
        // The other of the flag's two edges (PRD §15: an unlock is the user visibly coming back). The record
        // must close there as well, or the layer would go on hatching a machine somebody is sitting at.
        val h = Harness(NOW, FakeGateway())
        h.engine.setUserAway(true)
        h.now += 2 * 60_000L

        h.unlocked = false // the machine locks itself while the user is away
        h.engine.onPlatformActivityChanged()
        assertTrue(h.engine.userAway.value, "a lock is not a return")
        assertEquals(NOW, h.engine.declaredAwaySince.value, "and it does not close the stretch")

        h.now += 60_000L
        val returned = h.now
        h.unlocked = true // the user comes back and unlocks
        h.engine.onPlatformActivityChanged()
        assertTrue(!h.engine.userAway.value)
        assertEquals(listOf(TaskTimeRange(NOW, returned)), h.engine.declaredAwaySpans.value)
    }
}
