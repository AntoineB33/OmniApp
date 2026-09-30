package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.PlanBlock
import org.example.project.scheduler.domain.PlanTask
import org.example.project.scheduler.domain.RestrictivePeriod
import org.example.project.scheduler.domain.ScheduleFill
import org.example.project.scheduler.model.TaskId

/**
 * `docs/scheduler_requirements.md` requires no task anywhere: **time the rules leave to nobody is a decision**, priced
 * by the score like any other. A fill places it as nothing and reports it ([ScheduleFill.Result.idle]) — the one
 * reading the check at the line ([org.example.project.scheduler.domain.SchedulerDomain.planMismatchAtLine]) tells a
 * hole the plan chose from a hole a moved break left with.
 */
class IdleDecisionFillTest {
    private val MIN = 60_000L
    private val HOUR = 60 * MIN
    private val NOW = 1_700_000_000_000L
    private val BAN = "idle-test-ban"

    /**
     * T2 and T1 are turned away for half an hour every three hours, where only T0 may run, and T0 has a pre-placed
     * hour. The plan leaves a stretch before one of the bans to nobody: running T1 into it cuts a panel far short of
     * its minimum, and running T0 early serves it far past its share.
     */
    private fun fill(): ScheduleFill.Result {
        val tasks = listOf(
            PlanTask(TaskId("T2"), 3.0, 45 * MIN, mapOf(BAN to 0.0)),
            PlanTask(TaskId("T1"), 2.0, 30 * MIN, mapOf(BAN to 0.0)),
            PlanTask(TaskId("T0"), 1.0, 15 * MIN, mapOf(BAN to 1.0)),
        )
        val bans = (0 until 8).map { k ->
            RestrictivePeriod(NOW + k * 3 * HOUR + 2 * HOUR, NOW + k * 3 * HOUR + 2 * HOUR + 30 * MIN, BAN)
        }
        return ScheduleFill.run(
            ScheduleFill.Input(
                startMillis = NOW,
                horizonMillis = NOW + 24 * HOUR,
                lookbackMillis = 0L,
                ruleState = tasks,
                periods = bans,
                blocks = listOf(PlanBlock(TaskId("T0"), NOW + 7 * HOUR, NOW + 8 * HOUR)),
                history = emptyList(),
            ),
        )
    }

    @Test
    fun time_left_to_nobody_is_placed_as_nothing_and_reported() {
        val result = fill()
        assertTrue(result.idle.isNotEmpty(), "this case leaves time to nobody: ${result.placements.map { it.taskId.value to (it.startMillis - NOW) / MIN }}")
        for (hole in result.idle) {
            assertTrue(
                result.placements.none { it.startMillis < hole.endEpochMillis && hole.startEpochMillis < it.endMillis },
                "a placement inside the reported hole $hole",
            )
        }
        // Placements, holes and the pre-placed hour tile the whole horizon: every instant is either decided or pre-placed.
        val covered =
            (result.placements.map { it.startMillis to it.endMillis } +
                result.idle.map { it.startEpochMillis to it.endEpochMillis } +
                listOf(NOW + 7 * HOUR to NOW + 8 * HOUR)).sortedBy { it.first }
        var at = NOW
        for ((a, b) in covered) {
            assertEquals(at, a, "nothing between ${(at - NOW) / MIN} and ${(a - NOW) / MIN} min")
            at = b
        }
        assertEquals(NOW + 24 * HOUR, at)
    }
}
