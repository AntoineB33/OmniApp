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
 * User rule 2026-11-18: *"The deep search is worse than the instant schedule. Fix it."* — **time is left to nobody only
 * where no task may run** ([org.example.project.scheduler.domain.ScoreModel.choicesAt]), as the instant schedule never
 * leaves it. Until then nobody was a decision the score priced, and the search took it wherever every candidate would
 * overshoot its own small lag by its minimum: 5 % of the simulations' time. A fill still reports what it leaves
 * ([ScheduleFill.Result.idle]), which is now only time no task may run in.
 */
class IdleDecisionFillTest {
    private val MIN = 60_000L
    private val HOUR = 60 * MIN
    private val NOW = 1_700_000_000_000L
    private val BAN = "idle-test-ban"

    /**
     * T2 and T1 are turned away for half an hour every three hours, where only T0 may run, and T0 has a pre-placed
     * hour. Running T1 into a ban's eve cuts a panel short of its minimum, and running T0 early serves it past its share —
     * where the score once left that stretch to nobody.
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
    fun nobody_is_scheduled_where_a_task_may_run() {
        val result = fill()
        assertTrue(result.idle.isEmpty(), "time left to nobody where some task may run: ${result.idle.map { (it.startEpochMillis - NOW) / MIN }}")
        // Placements and the pre-placed hour tile the whole horizon: every instant is decided or pre-placed.
        val covered =
            (result.placements.map { it.startMillis to it.endMillis } + listOf(NOW + 7 * HOUR to NOW + 8 * HOUR)).sortedBy { it.first }
        var at = NOW
        for ((a, b) in covered) {
            assertEquals(at, a, "nothing between ${(at - NOW) / MIN} and ${(a - NOW) / MIN} min")
            at = b
        }
        assertEquals(NOW + 24 * HOUR, at)
    }
}
