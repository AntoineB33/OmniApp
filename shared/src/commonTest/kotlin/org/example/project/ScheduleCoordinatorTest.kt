package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.example.project.scheduler.engine.ScheduleCoordinator
import org.example.project.scheduler.sync.PeerCapability
import org.example.project.scheduler.sync.PeerMessage
import org.example.project.scheduler.sync.SchedulerPeerChannel

/**
 * `docs/invariants/scheduler.md` § *One device plans*: the election, over an in-memory bus standing in for the
 * account's broadcast channel. Every device runs the real [ScheduleCoordinator]; what is asserted is who planned,
 * who took whose rules in, and that no device is ever left without a plan.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ScheduleCoordinatorTest {

    private val window = ScheduleCoordinator.PROBE_WINDOW_MILLIS
    private val deadline = ScheduleCoordinator.RULES_DEADLINE_MILLIS

    private class Bus {
        val peers = ArrayList<Peer>()
        val sent = ArrayList<PeerMessage>()
    }

    private class Peer(val bus: Bus) : SchedulerPeerChannel {
        override val connected = MutableStateFlow(true)
        val inbox = MutableSharedFlow<PeerMessage>(extraBufferCapacity = 256)
        override val messages: SharedFlow<PeerMessage> = inbox

        override fun send(message: PeerMessage) {
            if (!connected.value) return
            bus.sent += message
            for (other in bus.peers) if (other !== this && other.connected.value) other.inbox.tryEmit(message)
        }
    }

    private inner class Device(
        val id: String,
        val test: TestScope,
        scope: CoroutineScope,
        bus: Bus,
        val kind: Int,
        var present: Boolean = true,
        signature: Int = 1,
        var displayedEnd: Long? = null,
        /** An elected device that publishes its plan (false: a leader that died before it could). */
        var publishes: Boolean = true,
    ) {
        val peer = Peer(bus).also { bus.peers += it }
        val signature = MutableStateFlow(signature)
        val plans = ArrayList<ScheduleCoordinator.Lead?>()
        val adopted = ArrayList<PeerMessage.Rules>()
        private var elections = 0
        private var stamp = 0L

        fun rules(election: String): PeerMessage.Rules =
            PeerMessage.Rules(id, election, signature.value, test.testScheduler.currentTime + stamp++, 0L, 0, emptyList())

        val coordinator: ScheduleCoordinator =
            ScheduleCoordinator(
                scope = scope,
                deviceId = id,
                channel = peer,
                capability = { PeerCapability(id, present, kind, 0.0, displayedEnd) },
                signature = this.signature,
                planLocally = { lead ->
                    plans += lead
                    if (lead != null && publishes) coordinatorRef().publish(rules(lead.election))
                },
                adopt = { adopted += it },
                currentRules = { election -> rules(election) },
                newElectionId = { "$id-${elections++}" },
                elapsed = { test.testScheduler.currentTime },
            ).also { it.start() }

        private fun coordinatorRef() = coordinator
    }

    private val desktop = PeerCapability.kindRank(org.example.project.scheduler.platform.DeviceKind.Desktop)
    private val phone = PeerCapability.kindRank(org.example.project.scheduler.platform.DeviceKind.Phone)

    @Test
    fun without_the_channel_a_device_plans_for_itself_at_once() = runTest {
        val bus = Bus()
        val a = Device("phone", this, backgroundScope, bus, phone)
        a.peer.connected.value = false
        runCurrent()
        a.coordinator.requestPlan()
        assertEquals(listOf<ScheduleCoordinator.Lead?>(null), a.plans)
        assertTrue(bus.sent.isEmpty())
    }

    @Test
    fun the_desktop_plans_for_the_phone_and_the_phone_takes_its_rules() = runTest {
        val bus = Bus()
        val p = Device("phone", this, backgroundScope, bus, phone)
        val d = Device("desktop", this, backgroundScope, bus, desktop)
        runCurrent()
        p.coordinator.requestPlan()
        runCurrent()
        advanceTimeBy(window + 1)
        runCurrent()
        assertEquals(1, d.plans.size, "the desktop is elected")
        assertNotNull(d.plans.single())
        assertTrue(p.plans.isEmpty(), "the phone does not search")
        assertEquals("desktop", p.adopted.single().from)

        // The desktop's own watcher asks for the same rules once the edit reaches it: no second plan, no second
        // election.
        d.coordinator.requestPlan()
        runCurrent()
        advanceTimeBy(deadline + window)
        runCurrent()
        assertEquals(1, d.plans.size)
        assertTrue(p.plans.isEmpty(), "rules arrived, so the deadline never fires")
        assertEquals(1, bus.sent.count { it is PeerMessage.Probe })
    }

    @Test
    fun a_locked_device_is_not_elected() = runTest {
        val bus = Bus()
        val p = Device("phone", this, backgroundScope, bus, phone)
        Device("desktop", this, backgroundScope, bus, desktop, present = false)
        runCurrent()
        p.coordinator.requestPlan()
        runCurrent()
        advanceTimeBy(window + 1)
        runCurrent()
        assertEquals(1, p.plans.size)
        assertNotNull(p.plans.single(), "the phone, the only device in use, leads")
        assertTrue(bus.sent.none { it is PeerMessage.Reply }, "a locked device does not answer")
    }

    @Test
    fun a_leader_that_never_answers_costs_one_deadline() = runTest {
        val bus = Bus()
        val p = Device("phone", this, backgroundScope, bus, phone)
        val d = Device("desktop", this, backgroundScope, bus, desktop, publishes = false)
        runCurrent()
        p.coordinator.requestPlan()
        runCurrent()
        advanceTimeBy(window + 1)
        runCurrent()
        assertEquals(1, d.plans.size)
        assertTrue(p.plans.isEmpty(), "still waiting for the elected desktop")
        advanceTimeBy(deadline)
        runCurrent()
        assertEquals(listOf<ScheduleCoordinator.Lead?>(null), p.plans, "no rules in time: the phone plans for itself")
    }

    @Test
    fun two_devices_asking_at_once_hold_one_election() = runTest {
        val bus = Bus()
        val a = Device("laptop", this, backgroundScope, bus, desktop)
        val b = Device("tower", this, backgroundScope, bus, desktop)
        runCurrent()
        a.coordinator.requestPlan()
        b.coordinator.requestPlan()
        runCurrent()
        advanceTimeBy(window + 1)
        runCurrent()
        advanceTimeBy(deadline + window)
        runCurrent()
        val leads = a.plans.filterNotNull() + b.plans.filterNotNull()
        assertEquals(1, leads.size, "exactly one device planned: a=${a.plans} b=${b.plans}")
        assertEquals(1, bus.sent.count { it is PeerMessage.Leader })
        assertTrue(a.plans.none { it == null } && b.plans.none { it == null }, "nobody fell back to a local plan")
        assertEquals(1, a.adopted.size + b.adopted.size, "the other took the leader's rules in")
    }

    @Test
    fun rules_heard_before_the_state_caught_up_are_taken_when_it_does() = runTest {
        val bus = Bus()
        val d = Device("desktop", this, backgroundScope, bus, desktop, signature = 2)
        val p = Device("phone", this, backgroundScope, bus, phone, signature = 1)
        runCurrent()
        d.coordinator.requestPlan()
        runCurrent()
        advanceTimeBy(window + 1)
        runCurrent()
        assertTrue(p.adopted.isEmpty(), "the phone has not synced the change yet")
        p.signature.value = 2
        runCurrent()
        assertEquals(2, p.adopted.single().signature)
    }

    @Test
    fun an_elected_device_whose_state_lags_plans_once_it_catches_up() = runTest {
        val bus = Bus()
        val p = Device("phone", this, backgroundScope, bus, phone, signature = 2)
        val d = Device("desktop", this, backgroundScope, bus, desktop, signature = 1)
        runCurrent()
        p.coordinator.requestPlan()
        runCurrent()
        advanceTimeBy(window + 1)
        runCurrent()
        assertTrue(d.plans.isEmpty(), "elected, but its own state is not at those rules yet")
        d.signature.value = 2
        runCurrent()
        assertEquals(2, d.plans.single()!!.signature)
        assertEquals(1, p.adopted.size)
    }

    @Test
    fun older_rules_never_replace_newer_ones() = runTest {
        val bus = Bus()
        val p = Device("phone", this, backgroundScope, bus, phone)
        val sender = Peer(bus)
        bus.peers += sender
        runCurrent()
        sender.send(PeerMessage.Rules("desktop", "e", 1, nowMillis = 2_000, horizonMillis = 0, stage = 0, placements = emptyList()))
        sender.send(PeerMessage.Rules("desktop", "e", 1, nowMillis = 2_000, horizonMillis = 0, stage = 1, placements = emptyList()))
        sender.send(PeerMessage.Rules("desktop", "e", 1, nowMillis = 1_000, horizonMillis = 0, stage = 5, placements = emptyList()))
        runCurrent()
        assertEquals(listOf(0, 1), p.adopted.map { it.stage })
    }

    @Test
    fun the_leader_plans_to_the_furthest_calendar_any_candidate_shows() = runTest {
        val bus = Bus()
        val p = Device("phone", this, backgroundScope, bus, phone, displayedEnd = 5_000_000)
        val d = Device("desktop", this, backgroundScope, bus, desktop, displayedEnd = 1_000_000)
        runCurrent()
        p.coordinator.requestPlan()
        runCurrent()
        advanceTimeBy(window + 1)
        runCurrent()
        assertEquals(5_000_000, d.plans.single()!!.displayedEndMillis)
    }

    @Test
    fun a_device_that_becomes_present_gets_the_rules_from_the_last_leader() = runTest {
        val bus = Bus()
        val d = Device("desktop", this, backgroundScope, bus, desktop)
        runCurrent()
        d.coordinator.requestPlan()
        runCurrent()
        advanceTimeBy(window + 1)
        runCurrent()
        // A phone that was offline through the whole election comes online and is unlocked.
        val p = Device("phone", this, backgroundScope, bus, phone)
        runCurrent()
        // Its channel coming up is already an announcement: the last leader hands over the rules in force.
        assertEquals("desktop", p.adopted.single().from)
        // Being unlocked asks again, and the answer is still the last leader's.
        p.coordinator.onPresent()
        runCurrent()
        assertTrue(p.adopted.all { it.from == "desktop" })
        assertNull(p.plans.firstOrNull())
    }

    @Test
    fun the_ranking_is_the_same_on_every_device() {
        val candidates =
            listOf(
                PeerCapability("b-desktop", present = true, kind = desktop, planHoursPerSecond = 40.0),
                PeerCapability("a-desktop", present = true, kind = desktop, planHoursPerSecond = 45.0),
                PeerCapability("fast-phone", present = true, kind = phone, planHoursPerSecond = 500.0),
                PeerCapability("locked-desktop", present = false, kind = desktop, planHoursPerSecond = 900.0),
            )
        // In use first, then the kind, then the speed by powers of two (40 and 45 are one bucket), then the id.
        assertEquals("a-desktop", candidates.minWith(PeerCapability.rank).deviceId)
        assertEquals("a-desktop", candidates.reversed().minWith(PeerCapability.rank).deviceId)
    }
}
