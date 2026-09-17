package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.PanelPins
import org.example.project.scheduler.model.RulePlacement
import org.example.project.scheduler.model.ScheduleCycle
import org.example.project.scheduler.model.SleepSchedule
import org.example.project.scheduler.model.TaskPanel
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState
import org.example.project.scheduler.sync.PeerCapability
import org.example.project.scheduler.sync.PeerCycle
import org.example.project.scheduler.sync.PeerMessage
import org.example.project.scheduler.sync.RealtimePhoenix

/**
 * `docs/invariants/scheduler.md` § *One device plans*: what travels between the account's devices, and what a device
 * does with the rules it receives.
 */
class SchedulerPeerProtocolTest {

    private val MIN = 60_000L
    private val HOUR = 60 * MIN
    private val DAY = 24 * HOUR
    private val NOW = 1_788_343_200_000L

    @Test
    fun every_message_survives_the_wire() {
        val capability = PeerCapability("desk", present = true, kind = 2, planHoursPerSecond = 12.5, displayedEndMillis = 42L)
        val messages =
            listOf(
                PeerMessage.Probe("desk", "desk-1", 7, capability),
                PeerMessage.Reply("phone", "desk-1", capability.copy(deviceId = "phone", displayedEndMillis = null)),
                PeerMessage.Leader("desk", "desk-1", 7, "desk", 99L),
                PeerMessage.Rules(
                    "desk", "desk-1", 7, NOW, NOW + HOUR, 3,
                    listOf(PeerMessage.placementOf(org.example.project.scheduler.model.TaskId("t/1"), NOW, NOW + 15 * MIN, null, emptyList())),
                    PeerCycle(NOW, listOf(org.example.project.scheduler.sync.PeerCycleRun("t/1", 900_000.0, "t/2")), 1, 2, exact = false),
                ),
                PeerMessage.RulesRequest("phone", 7),
            )
        for (m in messages) assertEquals(m, PeerMessage.decode(PeerMessage.encode(m)))
        assertNull(PeerMessage.decode("""{"type":"from_a_newer_build","from":"x"}"""), "an unknown message is ignored")
    }

    @Test
    fun a_broadcast_frame_is_read_back_only_on_its_own_topic() {
        val topic = RealtimePhoenix.schedulerBroadcastTopic("user-1")
        val text = PeerMessage.encode(PeerMessage.RulesRequest("phone", 7))
        val sent = RealtimePhoenix.broadcastFrame(topic, joinRef = 2, ref = 5, message = text)
        // What Realtime delivers to the other sockets: the same payload, re-enveloped.
        val received = """{"event":"broadcast","payload":${sent.substringAfter("\"payload\":").substringBefore(",\"ref\"")},"ref":null,"topic":"$topic"}"""
        assertEquals(text, RealtimePhoenix.broadcastMessage(received, topic))
        assertNull(RealtimePhoenix.broadcastMessage(received, RealtimePhoenix.schedulerBroadcastTopic("user-2")))
        assertTrue(RealtimePhoenix.broadcastJoinFrame(topic, "jwt", 2).contains("\"private\":true"))
        assertEquals(true, RealtimePhoenix.joinReplyStatus("""{"event":"phx_reply","payload":{"status":"ok"},"ref":"2","topic":"$topic"}""", topic))
        assertEquals(false, RealtimePhoenix.joinReplyStatus("""{"event":"phx_reply","payload":{"status":"error"},"ref":"2","topic":"$topic"}""", topic))
        assertNull(RealtimePhoenix.joinReplyStatus("""{"event":"phx_reply","payload":{"status":"ok"},"ref":"1","topic":"realtime:db:x"}""", topic))
    }

    private fun account(): SchedulerState {
        var s = SchedulerState.empty()
        listOf("A" to 30, "B" to 15, "C" to 15).forEachIndexed { i, (name, _) ->
            s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(s.lists[s.rootListId]!!.cellIds[i], name))
        }
        for ((name, minutes) in listOf("A" to 30, "B" to 15, "C" to 15)) {
            s = SchedulerReducer.reduce(s, SchedulerIntent.SetTaskMinimumTime(s.tasks.keys.first { s.tasks[it]!!.title == name }, minutes))
        }
        return s.copy(sleep = SleepSchedule(), screenBreaks = SchedulerDomain.DEFAULT_SCREEN_BREAKS)
    }

    private fun leaderPlan(state: SchedulerState, horizon: Long): Pair<List<TaskPanel>, ScheduleCycle?> {
        var cycle: ScheduleCycle? = null
        val panels = SchedulerDomain.fillSchedule(state, NOW, horizonMillis = horizon, cycleSink = { cycle = it })
        return panels to cycle
    }

    private fun placements(panels: List<TaskPanel>): List<RulePlacement> =
        panels.filter { it.auto && it.taskId != null }
            .map { RulePlacement(it.taskId!!, it.startEpochMillis, it.endEpochMillis, it.alternativeTaskId, it.alternativeSpans) }

    private fun runs(panels: List<TaskPanel>) =
        panels.filter { it.auto && it.taskId != null }.sortedBy { it.startEpochMillis }
            .map { Triple(it.taskId, it.startEpochMillis, it.endEpochMillis) to it.alternativeTaskId }

    @Test
    fun a_follower_lays_exactly_the_leaders_runs_and_keeps_its_cycle() {
        val s = account()
        val (leader, cycle) = leaderPlan(s, NOW + 2 * DAY)
        CalendarHorizonFixture.show(DAY)
        try {
            val adopted =
                SchedulerReducer.reduce(
                    s, SchedulerIntent.AdoptScheduleRules(NOW, placements(leader), cycle, NOW + 2 * DAY),
                )
            assertEquals(runs(leader), runs(adopted.panels), "the same environment: the same runs, alternatives included")
            assertEquals(cycle, adopted.scheduleCycle)
        } finally {
            CalendarHorizonFixture.close()
        }
    }

    @Test
    fun a_follower_never_gives_a_task_a_stretch_nobody_may_run_in_on_it() {
        // The follower has a period the leader did not see: the leader's runs are carried through it, not over it.
        val s = account()
        val (leader, _) = leaderPlan(s, NOW + DAY)
        val blockStart = NOW + 3 * HOUR
        val period =
            TaskPanel("panel/sleep-in", null, "Inactivity", blockStart, blockStart + HOUR, pinned = false, auto = false,
                inactivity = true, pins = PanelPins(existence = true))
        val follower = s.copy(panels = listOf(period))
        val adopted =
            SchedulerReducer.reduce(follower, SchedulerIntent.AdoptScheduleRules(NOW, placements(leader), null, NOW + DAY))
        assertTrue(
            adopted.panels.none { it.auto && it.taskId != null && it.startEpochMillis < period.endEpochMillis && it.endEpochMillis > period.startEpochMillis },
            "a run was laid over the follower's own period",
        )
        assertTrue(adopted.panels.any { it.auto && it.startEpochMillis >= period.endEpochMillis }, "the rules go on after it")
    }

    @Test
    fun a_peer_clock_a_little_ahead_does_not_leave_the_line_idle() {
        val s = account()
        val (leader, _) = leaderPlan(s, NOW + 2 * HOUR)
        val late = placements(leader).map { it.copy(startMillis = maxOf(it.startMillis, NOW + 20_000)) }.filter { it.endMillis > it.startMillis }
        val adopted = SchedulerReducer.reduce(s, SchedulerIntent.AdoptScheduleRules(NOW, late, null, NOW + 2 * HOUR))
        assertTrue(adopted.panels.any { it.auto && it.taskId != null && it.startEpochMillis <= NOW && it.endEpochMillis > NOW })
    }
}
