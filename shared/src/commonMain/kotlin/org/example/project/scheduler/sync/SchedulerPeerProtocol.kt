package org.example.project.scheduler.sync

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.example.project.scheduler.model.AlternativeSpan
import org.example.project.scheduler.model.CycleRun
import org.example.project.scheduler.model.ScheduleCycle
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.platform.DeviceKind

/**
 * `docs/invariants/scheduler.md` § *One device plans*: the account's devices talking to each other about WHO runs
 * the scheduler, and what it returned. A broadcast channel private to the account (`realtime:scheduler:<userId>`,
 * migration 20260916000000); nothing on it is stored anywhere, and a device that misses a message is covered by the
 * deadline in [org.example.project.scheduler.engine.ScheduleCoordinator], never by a replay.
 *
 * Implemented over the one live Realtime WebSocket ([RealtimeSnapshotSubscriber]); a fake bus in tests.
 */
interface SchedulerPeerChannel {
    /** True while the channel is joined, so a message sent now reaches the account's other connected devices. */
    val connected: StateFlow<Boolean>

    /** Messages from the account's OTHER devices (a device never hears its own). */
    val messages: SharedFlow<PeerMessage>

    /** Best effort: a message sent while disconnected is dropped. */
    fun send(message: PeerMessage)
}

/**
 * What a device is worth as the one that plans. Ranked by [rank]: a device somebody is using first (the election is
 * between the UNLOCKED devices of the account), then the stronger kind of device, then the faster one it measured
 * on its own fills, then the device id — so every device ranks the same candidates the same way.
 */
@Serializable
data class PeerCapability(
    val deviceId: String,
    val present: Boolean,
    /** [kindRank] of the device. */
    val kind: Int,
    /** Hours of plan this device's own fills produced per second of work; 0 until it has measured one. */
    val planHoursPerSecond: Double,
    /** The exclusive end of the span this device's calendar shows, or null when it shows none. */
    val displayedEndMillis: Long? = null,
) {
    companion object {
        fun kindRank(kind: DeviceKind): Int =
            when (kind) {
                DeviceKind.Desktop -> 2
                DeviceKind.Other -> 1
                DeviceKind.Phone -> 0
            }

        /**
         * The measured speed, bucketed by powers of two: two devices a few percent apart are the same speed, so a
         * noisy measurement never swaps the leader between two elections.
         */
        fun speedBucket(hoursPerSecond: Double): Int =
            if (hoursPerSecond <= 0.0) Int.MIN_VALUE else kotlin.math.floor(kotlin.math.log2(hoursPerSecond)).toInt()

        /** Best candidate first. */
        val rank: Comparator<PeerCapability> =
            compareByDescending<PeerCapability> { it.present }
                .thenByDescending { it.kind }
                .thenByDescending { speedBucket(it.planHoursPerSecond) }
                .thenBy { it.deviceId }
    }
}

/** One run the leader placed, on the wall clock — [ScheduleFill.Placement] on the wire. */
@Serializable
data class PeerPlacement(
    val task: String,
    val start: Long,
    val end: Long,
    val alternative: String? = null,
    /** `[fromMillis, task-or-null]` pairs: where the alternative changes inside the run. */
    val spans: List<PeerAlternativeSpan> = emptyList(),
)

@Serializable
data class PeerAlternativeSpan(val from: Long, val task: String? = null)

@Serializable
data class PeerCycleRun(val task: String, val length: Double, val alternative: String? = null)

@Serializable
data class PeerCycle(
    val anchor: Long,
    val runs: List<PeerCycleRun>,
    val ruleStateHash: Int,
    val environmentHash: Int,
    val exact: Boolean,
) {
    fun toModel(): ScheduleCycle =
        ScheduleCycle(anchor, runs.map { CycleRun(TaskId(it.task), it.length, it.alternative?.let(::TaskId)) }, ruleStateHash, environmentHash, exact)

    companion object {
        fun of(cycle: ScheduleCycle): PeerCycle =
            PeerCycle(
                cycle.anchorMillis,
                cycle.runs.map { PeerCycleRun(it.taskId.value, it.lengthMillis, it.alternativeTaskId?.value) },
                cycle.ruleStateHash,
                cycle.environmentHash,
                cycle.exact,
            )
    }
}

@Serializable
sealed class PeerMessage {
    abstract val from: String

    /** "The rules must be re-planned for [signature]: who is here?" */
    @Serializable
    @SerialName("probe")
    data class Probe(override val from: String, val election: String, val signature: Int, val capability: PeerCapability) :
        PeerMessage()

    /** A present device answering [election]. */
    @Serializable
    @SerialName("reply")
    data class Reply(override val from: String, val election: String, val capability: PeerCapability) : PeerMessage()

    /** [election]'s result: [leader] plans [signature], out to at least the furthest [displayedEndMillis] shown. */
    @Serializable
    @SerialName("leader")
    data class Leader(
        override val from: String,
        val election: String,
        val signature: Int,
        val leader: String,
        val displayedEndMillis: Long? = null,
    ) : PeerMessage()

    /**
     * A set of rules the leader returned for [signature]: the runs it placed from [nowMillis] to [horizonMillis],
     * and the repeating part past them. Sent after every progressive stage; each one contains the previous.
     */
    @Serializable
    @SerialName("rules")
    data class Rules(
        override val from: String,
        val election: String,
        val signature: Int,
        val nowMillis: Long,
        val horizonMillis: Long,
        val stage: Int,
        val placements: List<PeerPlacement>,
        val cycle: PeerCycle? = null,
    ) : PeerMessage()

    /** A device that has just become present asks the last leader for the rules in force for [signature]. */
    @Serializable
    @SerialName("rules_request")
    data class RulesRequest(override val from: String, val signature: Int) : PeerMessage()

    companion object {
        private val json = Json { ignoreUnknownKeys = true; classDiscriminator = "type"; encodeDefaults = false }

        fun encode(message: PeerMessage): String = json.encodeToString(serializer(), message)

        /** Null for anything this build does not understand — a newer peer's message is ignored, never fatal. */
        fun decode(text: String): PeerMessage? = runCatching { json.decodeFromString(serializer(), text) }.getOrNull()

        fun placementOf(taskId: TaskId, start: Long, end: Long, alternative: TaskId?, spans: List<AlternativeSpan>): PeerPlacement =
            PeerPlacement(taskId.value, start, end, alternative?.value, spans.map { PeerAlternativeSpan(it.fromMillis, it.taskId?.value) })
    }
}
