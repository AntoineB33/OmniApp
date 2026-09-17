package org.example.project.scheduler.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.example.project.scheduler.platform.Diagnostics
import org.example.project.scheduler.sync.PeerCapability
import org.example.project.scheduler.sync.PeerMessage
import org.example.project.scheduler.sync.SchedulerPeerChannel

/**
 * `docs/invariants/scheduler.md` § *One device plans*: **which of the account's devices runs the scheduler when the
 * rules must be re-planned, and how the others get its answer.**
 *
 * Every device of the account notices the same re-plan — its own rule-change watcher fires once the change has
 * synced to it — and asks [requestPlan]. Nobody plans on a timer and nobody asks who is present on one: the question
 * is asked only when a re-plan is due, which is the one moment its answer matters.
 *
 * 1. The first device to ask **probes** the account's channel ([PeerMessage.Probe]) and every device somebody is
 *    using answers with what it is worth ([PeerMessage.Reply], [PeerCapability]). Two probes for the same rules
 *    crossing: the smaller election id wins and the other device joins it.
 * 2. After [probeWindowMillis] it **ranks** the candidates ([PeerCapability.rank]) and announces the leader
 *    ([PeerMessage.Leader]), with the furthest calendar end any of them shows.
 * 3. The leader plans (once its own state has caught up with those rules) and broadcasts every progressive stage
 *    ([PeerMessage.Rules]); every other device adopts the newest rules for its own rule state.
 * 4. A device still without rules [rulesDeadlineMillis] after the announcement **plans for itself**: a leader that
 *    was killed, lost its network or was suspended costs one deadline, never a device with no plan.
 *
 * **Nobody else around, nothing sent** (`docs/invariants/server-quota.md`): a device that has heard from no other device
 * since its last probe went unanswered leads alone, without a probe, an announcement or its stages on the wire. A
 * device announces itself when somebody starts using it and whenever its channel (re)connects ([PeerMessage.RulesRequest],
 * answered with the rules in force or a [PeerMessage.Reply]), so the first re-plan after that is an election again.
 *
 * With the channel down — offline, signed out, the migration not applied — every request plans locally at once,
 * which is exactly how the app behaved before.
 *
 * Confined to [scope]: every entry point and every callback runs on it.
 */
class ScheduleCoordinator(
    private val scope: CoroutineScope,
    private val deviceId: String,
    private val channel: SchedulerPeerChannel,
    /** What this device is worth right now. */
    private val capability: () -> PeerCapability,
    /** `SchedulerDomain.schedulingSignature` of this device's state — the rules a plan answers for. */
    private val signature: StateFlow<Int>,
    /** Plan here: as the elected [Lead] (broadcast the stages), or `null` for a plan nobody else is told about. */
    private val planLocally: (Lead?) -> Unit,
    /** Take a leader's rules into this device's plan. */
    private val adopt: (PeerMessage.Rules) -> Unit,
    /** The rules this device's current plan returns, for the last election it led; null when it has none. */
    private val currentRules: (election: String) -> PeerMessage.Rules?,
    private val newElectionId: () -> String,
    /** A monotonic clock in millis (never the app clock, which a debug simulation accelerates). */
    private val elapsed: () -> Long,
    private val probeWindowMillis: Long = PROBE_WINDOW_MILLIS,
    private val rulesDeadlineMillis: Long = RULES_DEADLINE_MILLIS,
) {
    /** This device was elected for [election]; [displayedEndMillis] is the furthest calendar end to plan to. */
    data class Lead(val election: String, val signature: Int, val displayedEndMillis: Long?)

    private inner class Election(val id: String, val signature: Int, val mine: Boolean) {
        val startedAt: Long = elapsed()
        var leader: String? = null
        var displayedEndMillis: Long? = null
        var wanted = false
        var rulesSeen = false
        // This device already planned as the elected one: its own watcher asking again for the same rules is not a
        // second plan.
        var led = false
        val replies = LinkedHashMap<String, PeerCapability>()
        var decideJob: Job? = null
        var deadlineJob: Job? = null
        var leadJob: Job? = null

        fun live(): Boolean = elapsed() - startedAt < probeWindowMillis + rulesDeadlineMillis
    }

    private val elections = LinkedHashMap<String, Election>()

    // The newest rules heard for each rule state, kept so a device whose state catches up after the rules arrived
    // still adopts them; and the rules last taken in (heard or made here), which later ones must be newer than.
    private val heard = LinkedHashMap<Int, PeerMessage.Rules>()
    private var lastTaken: PeerMessage.Rules? = null
    private var lastLed: Election? = null

    private var started = false

    // The other devices heard on the channel since the last probe nobody answered: an election is held only when this is
    // not empty.
    private val peers = LinkedHashSet<String>()

    fun start() {
        if (started) return
        started = true
        scope.launch {
            channel.messages.collect {
                if (it.from == deviceId) return@collect
                peers += it.from
                onMessage(it)
            }
        }
        scope.launch {
            channel.connected.collect { up -> if (up && capability().present) channel.send(PeerMessage.RulesRequest(deviceId, signature.value)) }
        }
        scope.launch {
            signature.collect { sig ->
                val rules = heard[sig] ?: return@collect
                takeIfNewer(rules)
            }
        }
    }

    /** The rules must be re-planned: plan here, or have the elected device plan and take its answer. */
    fun requestPlan() {
        val sig = signature.value
        if (!channel.connected.value || !capability().present) {
            // Offline, or nobody is using this device: plan here, and tell nobody (`docs/invariants/server-quota.md`).
            planLocally(null)
            return
        }
        prune()
        val live = elections.values.lastOrNull { it.signature == sig && it.live() }
        if (live != null) {
            live.wanted = true
            when (val leader = live.leader) {
                null -> Unit // still deciding: [decide] or the announcement will act for this device
                deviceId -> lead(live)
                else -> if (!live.rulesSeen) armDeadline(live)
            }
            return
        }
        val election = Election(newElectionId(), sig, mine = true).also { it.wanted = true }
        elections[election.id] = election
        if (peers.isEmpty()) {
            election.leader = deviceId
            lead(election)
            return
        }
        channel.send(PeerMessage.Probe(deviceId, election.id, sig, capability()))
        election.decideJob = scope.launch {
            delay(probeWindowMillis)
            decide(election)
        }
    }

    /** This device has just become one somebody is using: ask the last leader for the rules in force. */
    fun onPresent() {
        if (channel.connected.value) channel.send(PeerMessage.RulesRequest(deviceId, signature.value))
    }

    /** The elected device publishes one stage of its plan. */
    fun publish(rules: PeerMessage.Rules) {
        lastTaken = rules
        if (channel.connected.value && peers.isNotEmpty()) channel.send(rules)
    }

    /** The rules this device's plan answers were just made HERE (a local plan): later rules must be newer. */
    fun notePlannedLocally(signature: Int, nowMillis: Long) {
        lastTaken = PeerMessage.Rules(deviceId, "", signature, nowMillis, nowMillis, Int.MAX_VALUE, emptyList())
    }

    private fun decide(election: Election) {
        if (elections[election.id] !== election || election.leader != null) return
        // Whoever did not answer is not being used: it announces itself again when it is.
        peers.retainAll(election.replies.keys)
        val candidates = election.replies.values + capability()
        val leader = candidates.minWith(PeerCapability.rank)
        election.leader = leader.deviceId
        election.displayedEndMillis = candidates.mapNotNull { it.displayedEndMillis }.maxOrNull()
        Diagnostics.log(
            "scheduler peers: ${election.id} elected ${leader.deviceId} among ${candidates.map { it.deviceId }}",
        )
        channel.send(PeerMessage.Leader(deviceId, election.id, election.signature, leader.deviceId, election.displayedEndMillis))
        announced(election)
    }

    private fun announced(election: Election) {
        if (election.leader == deviceId) {
            lead(election)
        } else if (election.wanted && !election.rulesSeen) {
            armDeadline(election)
        }
    }

    private fun lead(election: Election) {
        if (election.led) return
        lastLed = election
        val lead = Lead(election.id, election.signature, election.displayedEndMillis)
        if (signature.value == election.signature) {
            election.led = true
            planLocally(lead)
            return
        }
        // Elected before this device's own state has synced the change: plan the moment it has.
        election.leadJob?.cancel()
        election.leadJob = scope.launch {
            val caughtUp = withTimeoutOrNull(rulesDeadlineMillis) { signature.first { it == election.signature } }
            if (caughtUp != null && !election.led) {
                election.led = true
                planLocally(lead)
            }
        }
    }

    private fun armDeadline(election: Election) {
        if (election.deadlineJob?.isActive == true) return
        election.deadlineJob = scope.launch {
            delay(rulesDeadlineMillis)
            if (!election.rulesSeen && signature.value == election.signature) {
                Diagnostics.log("scheduler peers: no rules from ${election.leader} for ${election.id} — planning here")
                planLocally(null)
            }
        }
    }

    private fun onMessage(message: PeerMessage) {
        prune()
        when (message) {
            is PeerMessage.Probe -> onProbe(message)
            is PeerMessage.Reply -> elections[message.election]?.takeIf { it.mine && it.leader == null }
                ?.replies?.put(message.from, message.capability)
            is PeerMessage.Leader -> {
                val election = elections.getOrPut(message.election) { Election(message.election, message.signature, mine = false) }
                if (election.leader != null) return
                election.decideJob?.cancel()
                election.leader = message.leader
                election.displayedEndMillis = message.displayedEndMillis
                announced(election)
            }
            is PeerMessage.Rules -> onRules(message)
            is PeerMessage.RulesRequest -> {
                val rules = lastLed?.takeIf { it.signature == message.signature && signature.value == message.signature }?.let { currentRules(it.id) }
                if (rules != null) {
                    channel.send(rules)
                } else {
                    // No rules to hand over: still let the newcomer know this device is here.
                    val mine = capability()
                    if (mine.present) channel.send(PeerMessage.Reply(deviceId, "", mine))
                }
            }
        }
    }

    private fun onProbe(probe: PeerMessage.Probe) {
        val rival = elections.values.lastOrNull { it.mine && it.leader == null && it.signature == probe.signature && it.id != probe.election }
        var wanted = false
        if (rival != null) {
            // Two devices asked for the same rules at once: the smaller id is the election, the other joins it.
            if (probe.election > rival.id) return
            rival.decideJob?.cancel()
            elections.remove(rival.id)
            wanted = rival.wanted
        }
        val election = elections.getOrPut(probe.election) { Election(probe.election, probe.signature, mine = false) }
        election.wanted = election.wanted || wanted
        val mine = capability()
        if (mine.present) channel.send(PeerMessage.Reply(deviceId, probe.election, mine))
    }

    private fun onRules(rules: PeerMessage.Rules) {
        elections[rules.election]?.let { election ->
            election.rulesSeen = true
            election.deadlineJob?.cancel()
        }
        val previous = heard[rules.signature]
        if (previous == null || newer(rules, previous)) heard[rules.signature] = rules
        while (heard.size > MAX_HEARD) heard.remove(heard.keys.first())
        if (signature.value == rules.signature) takeIfNewer(rules)
    }

    private fun takeIfNewer(rules: PeerMessage.Rules) {
        val last = lastTaken
        if (last != null && last.signature == rules.signature && !newer(rules, last)) return
        lastTaken = rules
        adopt(rules)
    }

    private fun newer(a: PeerMessage.Rules, b: PeerMessage.Rules): Boolean =
        a.nowMillis > b.nowMillis || (a.nowMillis == b.nowMillis && a.stage > b.stage)

    private fun prune() {
        val it = elections.values.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (elapsed() - e.startedAt > 4 * (probeWindowMillis + rulesDeadlineMillis)) it.remove()
        }
    }

    companion object {
        /** How long the device that asks waits for the others to answer before it ranks them. */
        const val PROBE_WINDOW_MILLIS: Long = 1_000

        /**
         * How long a device waits for the elected device's first rules before planning for itself — the pace
         * `docs/scheduler_requirements.md` § *Progressive Calculation* asks for (10 minutes of definitive schedule
         * per 10 seconds).
         */
        const val RULES_DEADLINE_MILLIS: Long = 10_000

        private const val MAX_HEARD = 8
    }
}
