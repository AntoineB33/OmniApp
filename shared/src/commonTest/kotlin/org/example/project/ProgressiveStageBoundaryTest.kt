package org.example.project

import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.PlanTask
import org.example.project.scheduler.domain.RestrictivePeriod
import org.example.project.scheduler.domain.ScheduleFill
import org.example.project.scheduler.model.TaskId

/**
 * `docs/invariants/scheduler.md` § *Progressive Calculation*: **a stage searches past what it materializes.**
 *
 * An extension keeps everything an earlier stage published as definitive, so a stage's last runs must not be bent by
 * where that stage happened to stop. The fill searches one decision window past its horizon and emits only up to it:
 * nothing is emitted beyond the horizon, and what lies just beyond it — here a stretch one task may not run in —
 * shapes the runs placed before it, exactly as it would inside one long fill.
 */
class ProgressiveStageBoundaryTest {
    private val MIN = 60_000L
    private val HOUR = 60 * MIN
    private val NOW = 1_788_343_200_000L

    private val tasks = listOf(PlanTask(TaskId("A"), 0.5, 20 * MIN), PlanTask(TaskId("B"), 0.5, 20 * MIN))

    private fun fill(periods: List<RestrictivePeriod>, horizon: Long, searchUntil: Long?): List<ScheduleFill.Placement> =
        ScheduleFill.run(
            ScheduleFill.Input(
                startMillis = NOW,
                horizonMillis = horizon,
                lookbackMillis = 0L,
                ruleState = tasks,
                periods = periods,
                blocks = emptyList(),
                history = emptyList(),
                searchUntilMillis = searchUntil,
            ),
        ).placements

    @Test
    fun nothing_is_emitted_past_the_horizon() {
        val horizon = NOW + 2 * HOUR
        val placements = fill(emptyList(), horizon, searchUntil = horizon + 2 * HOUR)
        assertTrue(placements.isNotEmpty())
        assertTrue(placements.all { it.endMillis <= horizon }, "placements past the horizon: ${placements.map { it.endMillis - NOW }}")
        assertTrue(placements.maxOf { it.endMillis } == horizon, "the stage reaches its horizon, no idling at its end")
    }

    @Test
    fun what_lies_just_past_the_horizon_shapes_the_runs_before_it() {
        val horizon = NOW + 2 * HOUR
        // B may not run for the hour right after the stage ends: the compensation reaches back before it, and the
        // runs at the stage's end have to leave A for later.
        val ban = listOf(RestrictivePeriod(horizon, horizon + HOUR, "stage-test-ban", "ban"))
        // A kind's default resilience is 0 for everybody, so A is told it is unaffected and B that it is refused.
        val bannedTasks = tasks.map { it.copy(resilience = mapOf("stage-test-ban" to if (it.id.value == "B") 0.0 else 1.0)) }
        fun fillWith(periods: List<RestrictivePeriod>, searchUntil: Long?) =
            ScheduleFill.run(
                ScheduleFill.Input(
                    startMillis = NOW,
                    horizonMillis = horizon,
                    lookbackMillis = 0L,
                    ruleState = bannedTasks,
                    periods = periods,
                    blocks = emptyList(),
                    history = emptyList(),
                    searchUntilMillis = searchUntil,
                ),
            ).placements.map { Triple(it.taskId.value, it.startMillis - NOW, it.endMillis - NOW) }

        val blind = fillWith(ban, searchUntil = null)
        val seeing = fillWith(ban, searchUntil = horizon + 2 * HOUR)
        assertNotEquals(blind, seeing, "a stage that searched no further than its horizon could not see the ban")
    }
}
