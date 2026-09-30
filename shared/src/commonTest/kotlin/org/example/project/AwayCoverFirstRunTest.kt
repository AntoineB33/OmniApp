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
 * period 'no on-screen task'"*): **while the mode holds, the plan is the plan for a covered line —
 * from the line to the end of what is searched.**
 *
 * The cover was first `[now, now + 1)` (the resilient task got one millisecond, an on-screen task the rest), then
 * the line's own instant `[now, now]` (it chose the first run and nothing more). Either way, since time passing
 * never re-plans, the away line walked out of the first run into on-screen work the display clips and the bank
 * refuses: an away stretch left empty where a resilient task could have run. The rules are parameterized by the
 * mode, so the plan for an away mode covers every instant the mode holds, and a flip re-plans at the flip.
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
    fun while_away_the_resilient_task_fills_the_whole_plan_and_no_on_screen_task_is_placed() {
        for (mode in listOf(DynamicPeriods.MODE_AWAY, DynamicPeriods.MODE_ON_BREAK)) {
            val panels = fill(account(), mode)
            assertTrue(
                panels.none { it.auto && it.title == "Screen" && it.endEpochMillis > NOW },
                "mode $mode: no on-screen task may be planned while the line is covered: ${panels.map { it.title }}",
            )
            val walk = panels.filter { it.auto && it.title == "Walk" }
                .map { org.example.project.scheduler.model.TaskTimeRange(maxOf(it.startEpochMillis, NOW), it.endEpochMillis) }
            assertEquals(
                emptyList(),
                SchedulerDomain.subtractRegions(listOf(org.example.project.scheduler.model.TaskTimeRange(NOW, NOW + 3 * HOUR)), walk),
                "mode $mode: the resilient task fills the away plan — nothing idles where it may run",
            )
        }
    }

    @Test
    fun with_no_resilient_task_nothing_is_scheduled_while_away() {
        // "…filled with tasks that have a non-zero resilience to the kind 'no on-screen task', or no task if none
        // have such resilience" — the stretch is restricted, not idle.
        val s = account().let { s -> s.copy(tasks = s.tasks.mapValues { (_, t) -> t.withResilience(PeriodKinds.NO_SCREEN, 0.0) }) }
        val panels = fill(s, DynamicPeriods.MODE_AWAY)
        assertTrue(panels.none { it.auto && it.endEpochMillis > NOW }, "nothing may run while nobody is resilient: $panels")
    }
}
