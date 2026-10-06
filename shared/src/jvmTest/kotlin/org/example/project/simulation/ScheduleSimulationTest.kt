package org.example.project.simulation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.example.project.simulation.ScheduleSimulation.Change
import org.example.project.simulation.ScheduleSimulation.DAY
import org.example.project.simulation.ScheduleSimulation.Scenario

/**
 * **The fast tier of the simulations** — in every `:shared:jvmTest`, so in `checkChange`: a chaotic account of ten
 * tasks lived through for a week, with and without rule changes on the way, through the app's own reducer
 * ([ScheduleSimulation]). Seconds, not minutes. The long tier ([ScheduleSimulationLongTest], hundreds of tasks over
 * weeks) is `./gradlew :shared:longTest`; `docs/invariants/scheduler.md` § *Simulations* says which changes owe it.
 *
 * What is asserted, at every stretch under one set of rules:
 *  1. **no hard constraint is broken** — no task ran in a period it has resilience 0 to, or inside another task's
 *     pre-placed panel, no two ran at once, and nothing the line had banked was rewritten afterwards;
 *  2. **the time is divided within [ScheduleSimulation.TOLERATED]** — see there for why that is not yet
 *     [ScheduleSimulation.GOAL], which a failure message always measures against too.
 */
class ScheduleSimulationTest {

    private fun check(run: ScheduleSimulation.Run) {
        for (epoch in run.epochs) {
            val outcome = ScheduleSimulation.measure(run, epoch.fromMillis, epoch.untilMillis)
            val report = ScheduleSimulation.describe(run, outcome)
            assertEquals(emptyList(), ScheduleSimulation.broken(run, outcome), "a hard constraint was broken\n$report")
            assertEquals(
                emptyList(), ScheduleSimulation.shortOf(outcome, ScheduleSimulation.toleratedFor(run, epoch)),
                "worse than what the scheduler did when this was written\n$report",
            )
            assertTrue(outcome.runs > 0 && outcome.servedMillis > 0, "nothing ran at all\n$report")
        }
    }

    @Test
    fun a_chaotic_week_breaks_no_hard_constraint_and_divides_the_time_within_tolerance() {
        for (seed in listOf(2L, 3L)) {
            check(ScheduleSimulation.run(Scenario(seed, tasks = 10, kinds = 3, days = 7, periodsPerWeek = 8, prePlacedPerWeek = 5)))
        }
    }

    @Test
    fun a_minor_then_a_major_rule_change_on_the_way_are_each_planned_from_where_the_line_is() {
        val run = ScheduleSimulation.run(
            Scenario(
                seed = 2L, tasks = 8, kinds = 3, days = 9, periodsPerWeek = 8, prePlacedPerWeek = 5,
                changes = listOf(Change.OneTask(3 * DAY), Change.Upheaval(6 * DAY)),
            ),
        )
        assertEquals(3, run.epochs.size, "three sets of rules were lived under")
        assertEquals(3, run.replans, "the first plan, and one re-plan per rule change — never one because time passed")
        check(run)
    }

    @Test
    fun a_scenario_is_its_seed() {
        val scenario = Scenario(seed = 7L, tasks = 6, kinds = 2, days = 3, periodsPerWeek = 8, prePlacedPerWeek = 5)
        fun records(run: ScheduleSimulation.Run) =
            run.finalState.tasks.values.filter { it.record.isNotEmpty() }.associate { it.title to it.record }
        val first = records(ScheduleSimulation.run(scenario))
        assertTrue(first.isNotEmpty())
        assertEquals(first, records(ScheduleSimulation.run(scenario)), "the same seed lives the same weeks")
        val other = records(ScheduleSimulation.run(scenario.copy(seed = 8L)))
        assertTrue(first != other, "another seed is another account")
    }

    /** The measure itself, on a walk whose answer is known: what the levels read must be what happened. */
    @Test
    fun the_levels_say_how_far_a_run_is_from_the_goal() {
        val run = ScheduleSimulation.run(Scenario(seed = 3L, tasks = 4, kinds = 0, days = 2, periodsPerWeek = 0, prePlacedPerWeek = 0, nightHours = 0))
        val outcome = ScheduleSimulation.measure(run, ScheduleSimulation.T0, run.scenario.endMillis)
        assertEquals(2 * DAY, outcome.schedulableMillis, "nothing is laid: every instant is somebody's")
        // With no period and no pre-placed panel, the two targets are one: the priority percentage of the two days.
        for (task in outcome.tasks) assertEquals(task.localMillis, task.nominalMillis, 1.0, task.title)
        assertEquals(1.0, outcome.tasks.sumOf { it.share }, 1e-9)
        assertEquals(outcome.tasks.sumOf { it.servedMillis }, outcome.servedMillis)
        assertTrue(outcome.idleFraction in 0.0..1.0)
        // A level nothing can meet is reported, a level everything meets is not.
        val impossible = ScheduleSimulation.Levels(idleFraction = -1.0, underService = 9.0, overService = 0.0, shortRunFraction = -1.0)
        assertTrue(ScheduleSimulation.shortOf(outcome, impossible).isNotEmpty())
        val anything = ScheduleSimulation.Levels(idleFraction = 1.0, underService = 0.0, overService = 99.0, shortRunFraction = 1.0)
        assertEquals(emptyList(), ScheduleSimulation.shortOf(outcome, anything))
    }
}
