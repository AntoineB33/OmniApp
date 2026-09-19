package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.DynamicPeriods
import org.example.project.scheduler.domain.PeriodKinds
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.TaskPanel
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState

/**
 * `docs/scheduler_requirements.md` § *$now line$ 3 modes* (**modes 2 & 3**: *"$now line$ must be covered by the
 * period 'no on-screen task'"*) and § *No idling*: **the away cover picks the run the line starts in, and never
 * cuts it.**
 *
 * The cover was `[now, now + 1)`: a one-millisecond window, whose edge the search decides at like any other. So
 * the resilient task got exactly one millisecond, an on-screen task the rest — and since time passing never
 * re-plans, the away line (and the mode-2 sweep after a device sleep) then walked over on-screen work the display
 * clips and the bank refuses, leaving idle a stretch where the resilient task could have run. The cover is now
 * the line's own instant, `[now, now]`, read as a rule on the first run only.
 */
class AwayCoverFirstRunTest {

    private val MIN = 60_000L
    private val HOUR = 60 * MIN
    private val NOW = 1_700_000_000_000L

    /** An on-screen task and an off-screen one (resilient to "no on-screen task"), no screen breaks. */
    private fun account(): SchedulerState {
        var s = SchedulerState.empty()
        listOf("Screen", "Walk").forEachIndexed { i, title ->
            val row = s.lists[s.rootListId]!!.cellIds.let { it.getOrNull(i) ?: it.last() }
            s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(row, title))
        }
        val walk = s.tasks.values.single { it.title == "Walk" }
        return s.copy(tasks = s.tasks + (walk.id to walk.withResilience(PeriodKinds.NO_SCREEN, 1.0)))
    }

    private fun fill(state: SchedulerState, mode: Int) =
        SchedulerDomain.fillSchedule(state, NOW, horizonMillis = NOW + 3 * HOUR, tpMode = mode)

    private fun atLine(panels: List<TaskPanel>): TaskPanel =
        panels.single { it.auto && it.taskId != null && it.startEpochMillis <= NOW && NOW < it.endEpochMillis }

    @Test
    fun in_both_away_modes_the_line_starts_in_a_full_run_of_the_resilient_task() {
        for (mode in listOf(DynamicPeriods.MODE_AWAY, DynamicPeriods.MODE_ON_BREAK)) {
            val panels = fill(account(), mode)
            val first = atLine(panels)
            assertEquals("Walk", first.title, "mode $mode: the run at the line must be resilient to no screen")
            assertTrue(
                first.endEpochMillis - NOW >= MIN,
                "mode $mode: the resilient run must not be cut one millisecond past the line " +
                    "(it ends ${first.endEpochMillis - NOW} ms after it)",
            )
        }
    }

    @Test
    fun the_cover_restricts_nothing_but_the_first_run() {
        // Past the first run the plan is the ordinary one: the on-screen task is still scheduled — the cover is an
        // instant, not a period that turns work away.
        val panels = fill(account(), DynamicPeriods.MODE_AWAY)
        assertTrue(
            panels.any { it.auto && it.title == "Screen" && it.startEpochMillis > NOW },
            "the on-screen task must still be planned after the first run: ${panels.map { it.title }}",
        )
    }

    @Test
    fun with_no_resilient_task_the_line_is_not_left_idle() {
        // "…or no task if none have such resilience": a no-task of zero length. The line still starts in a task.
        val s = account().let { s -> s.copy(tasks = s.tasks.mapValues { (_, t) -> t.withResilience(PeriodKinds.NO_SCREEN, 0.0) }) }
        val first = atLine(fill(s, DynamicPeriods.MODE_AWAY))
        assertTrue(first.endEpochMillis - NOW >= MIN, "the line must start in a real run, not a sliver: $first")
    }
}
