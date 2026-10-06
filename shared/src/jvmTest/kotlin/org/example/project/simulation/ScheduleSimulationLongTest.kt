package org.example.project.simulation

import kotlin.test.Test
import kotlin.test.assertEquals
import org.example.project.simulation.ScheduleSimulation.Change
import org.example.project.simulation.ScheduleSimulation.DAY
import org.example.project.simulation.ScheduleSimulation.Scenario
import org.junit.Assume

/**
 * **The long tier of the simulations**: hundreds of tasks and dozens of period kinds, a timeline crowded with
 * periods and pre-placed panels, lived through for weeks — unchanged, with a minor rule change every week, and with a
 * major one on the way. Minutes to hours, so it is NOT in `jvmTest`: every test here is skipped unless
 * `-Domniapp.longTests=true`, which is what `./gradlew :shared:longTest` sets.
 *
 * Run it when the scheduler's answer can have changed — `docs/invariants/scheduler.md` § *Simulations* lists the
 * files — and read its output even when it passes: each scenario prints how far it is from
 * [ScheduleSimulation.GOAL], which is the number a change to the scheduler is trying to move.
 *
 * Sized by properties (`./gradlew :shared:longTest -PlongScale=0.25 -PlongSeeds=1,2,3`):
 *  - `omniapp.longTests.scale` multiplies the task and kind counts (1 = 60 / 120 / 200 tasks);
 *  - `omniapp.longTests.seeds` is the list of seeds each scenario is lived under (default: 1).
 */
class ScheduleSimulationLongTest {

    private val scale: Double = System.getProperty("omniapp.longTests.scale")?.toDoubleOrNull() ?: 1.0
    private val seeds: List<Long> =
        System.getProperty("omniapp.longTests.seeds")?.split(',')?.mapNotNull { it.trim().toLongOrNull() }?.takeIf { it.isNotEmpty() }
            ?: listOf(1L)

    private fun scaled(n: Int) = (n * scale).toInt().coerceAtLeast(2)

    private fun live(name: String, scenario: (seed: Long) -> Scenario) {
        Assume.assumeTrue("the long tier runs only under :shared:longTest", System.getProperty("omniapp.longTests") == "true")
        val failures = ArrayList<String>()
        for (seed in seeds) {
            val run = ScheduleSimulation.run(scenario(seed))
            for (epoch in run.epochs) {
                val outcome = ScheduleSimulation.measure(run, epoch.fromMillis, epoch.untilMillis)
                val report = ScheduleSimulation.describe(run, outcome, worst = 15)
                println("[$name] $report")
                val goal = ScheduleSimulation.shortOf(outcome, ScheduleSimulation.GOAL)
                println(if (goal.isEmpty()) "[$name] at the goal." else "[$name] short of the goal:\n  " + goal.joinToString("\n  "))
                ScheduleSimulation.broken(run, outcome).forEach { failures += "seed $seed: HARD CONSTRAINT: $it" }
                ScheduleSimulation.shortOf(outcome, ScheduleSimulation.toleratedFor(run, epoch)).forEach { failures += "seed $seed, +${(epoch.fromMillis - ScheduleSimulation.T0) / DAY}d: $it" }
            }
        }
        assertEquals(emptyList(), failures, "$name: worse than tolerated (the reports are printed above)")
    }

    /** Sixty tasks, fifteen kinds, two weeks, nothing changed on the way. */
    @Test
    fun sixty_tasks_two_weeks_unchanged() =
        live("60 tasks") { seed ->
            Scenario(seed, tasks = scaled(60), kinds = scaled(15), days = 14, periodsPerWeek = 25, prePlacedPerWeek = 15)
        }

    /** A hundred and twenty tasks, thirty kinds, four weeks, one task's weight and minimum redrawn every week. */
    @Test
    fun a_hundred_and_twenty_tasks_four_weeks_with_a_minor_change_every_week() =
        live("120 tasks, minor changes") { seed ->
            Scenario(
                seed, tasks = scaled(120), kinds = scaled(30), days = 28, periodsPerWeek = 40, prePlacedPerWeek = 25,
                changes = listOf(Change.OneTask(7 * DAY), Change.OneTask(14 * DAY), Change.OneTask(21 * DAY)),
            )
        }

    /** Two hundred tasks, fifty kinds, four weeks: every weight redrawn and new periods laid at day 10, a minor change at day 20. */
    @Test
    fun two_hundred_tasks_four_weeks_with_an_upheaval_on_the_way() =
        live("200 tasks, an upheaval") { seed ->
            Scenario(
                seed, tasks = scaled(200), kinds = scaled(50), days = 28, periodsPerWeek = 60, prePlacedPerWeek = 40,
                changes = listOf(Change.Upheaval(10 * DAY), Change.OneTask(20 * DAY)),
            )
        }
}
