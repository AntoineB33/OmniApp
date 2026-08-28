package org.example.project

import org.example.project.scheduler.domain.PeriodKinds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.ScreenBreak
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.model.TaskPanel
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState

/**
 * `side-dev` **test 13** in OmniApp's own terms: the break grid *and* the sliding percentages, together.
 *
 * Test 12 is the environment — a timeline the three screen-break periods arrive on one after another — and
 * test 13 adds the one thing the requirement names: the priorities themselves slide from one arrangement to
 * another, and "at t_p the scheduler is done to satisfy the priorities that are at exactly t_p". OmniApp's
 * two halves of that are the §15 screen breaks (periods, one shape each) and the §9 task-tree timeline (dated
 * trees as keyframes, linearly blended at the now-line). Each half is pinned on its own — the break shapes
 * against the reference in `SchedulerPlanTest`, the blend in `TaskTreeTimelineTest` — and these are what says
 * the two hold **at the same time**, which is the whole of what test 13 asks:
 *
 *  - the plan a fill from `now` produces satisfies the percentages *at* `now`, breaks and all;
 *  - and the breaks stay periods while it does: nothing that needs a screen is ever placed in one, and the
 *    off-screen work only ever appears in the part of a break that accepts it.
 */
class BreaksAndSlidingPrioritiesTest {

    private val MIN = 60_000L
    private val HOUR = 60 * MIN
    private val DAY = 24 * HOUR
    private val T0 = 1_700_000_000_000L

    /**
     * Three tasks and two dated keyframes: "before" (at [T0]) weights the screen work toward Focus, "after"
     * (a day later) hands that weight to Admin. Stretch — the only task needing no screen — keeps its share
     * throughout, so what slides is which of the two screen tasks the timeline is for.
     */
    private fun keyframes(): SchedulerState {
        var s = SchedulerState.empty()
        val titles = listOf("Focus", "Admin", "Stretch")
        titles.forEachIndexed { i, title ->
            // Titling the last empty cell grows the list, so the row to write is looked up each time.
            val row = s.lists[s.rootListId]!!.cellIds.let { it.getOrNull(i) ?: it.last() }
            s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(row, title))
        }
        val cells = s.lists[s.rootListId]!!.cellIds
        val ids = titles.associateWith { title -> s.tasks.keys.first { s.tasks[it]!!.title == title } }
        // Stretch is the break's own kind of work: no screen needed, doable during a break, and short enough
        // that its minimum fits inside a pose (a 45-min minimum could never be started in one).
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetTaskMinimumTime(ids.getValue("Stretch"), 3))
        s = SchedulerReducer.reduce(
            s,
            SchedulerIntent.SetTaskResilience(ids.getValue("Stretch"), PeriodKinds.NO_SCREEN, 1.0),
        )
        // The "before" arrangement: Focus 3, Admin 1, Stretch 1 (60/20/20).
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetPriorityWeight(cells[0], 0, 3.0))
        s = SchedulerReducer.reduce(s, SchedulerIntent.CreateTaskTree("before"))
        // A copy to diverge from — every edit below lands in "after", which is now the live tree.
        s = SchedulerReducer.reduce(s, SchedulerIntent.CreateTaskTree("after"))
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetPriorityWeight(cells[0], 0, 1.0))
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetPriorityWeight(cells[1], 0, 3.0))
        fun dateOf(title: String, millis: Long): SchedulerState =
            SchedulerReducer.reduce(
                s,
                SchedulerIntent.SetTaskTreeDate(s.taskTrees.first { it.title == title }.id, millis),
            )
        s = dateOf("before", T0)
        s = dateOf("after", T0 + DAY)
        return s.copy(screenBreaks = breaksAnchoredAt(T0))
    }

    /**
     * The three production screen breaks. There is nothing to anchor any more: `side-dev/README.md`'s
     * recurrence bars read the rest stretches out of the timeline the placement is asked about.
     */
    private fun breaksAnchoredAt(@Suppress("UNUSED_PARAMETER") at: Long): List<ScreenBreak> =
        SchedulerDomain.DEFAULT_SCREEN_BREAKS

    private fun taskId(state: SchedulerState, title: String): TaskId =
        state.tasks.entries.firstOrNull { it.value.title == title }?.key
            ?: state.taskTrees.firstNotNullOf { entry ->
                entry.tree.tasks.entries.firstOrNull { it.value.title == title }?.key
            }

    private fun servedMillis(panels: List<TaskPanel>, id: TaskId): Long =
        panels.filter { it.auto && it.taskId == id }.sumOf { it.endEpochMillis - it.startEpochMillis }

    // ----- the plan satisfies the percentages of the position it is planned from ------------------

    @Test
    fun the_plan_follows_the_percentages_of_exactly_the_position_it_is_planned_from() {
        val s = keyframes()
        val focus = taskId(s, "Focus")
        val admin = taskId(s, "Admin")
        // Sanity on the two ends the blend runs between, so a failure below is about the SCHEDULE and not
        // about the fixture's arrangement.
        assertEquals(0.6, SchedulerDomain.blendedTaskPriorities(s, T0)[focus]!!, 1e-9)
        assertEquals(0.6, SchedulerDomain.blendedTaskPriorities(s, T0 + DAY)[admin]!!, 1e-9)

        fun ratioAt(now: Long): Double {
            val panels = SchedulerDomain.fillSchedule(s, now, horizonMillis = now + 2 * DAY)
            val f = servedMillis(panels, focus)
            val a = servedMillis(panels, admin)
            assertTrue(f > 0 && a > 0, "both screen tasks must be placed at $now (focus=$f admin=$a)")
            return f.toDouble() / a.toDouble()
        }
        // A plan made at t_p satisfies the percentages AT t_p and holds them across its own reach, so the
        // ratio a fill comes out with tracks the blend position it was made from: 3:1 for Focus at the
        // start, even at the midpoint, and 1:3 by the far keyframe.
        val start = ratioAt(T0)
        val middle = ratioAt(T0 + DAY / 2)
        val end = ratioAt(T0 + DAY)
        assertTrue(start > middle, "the transition must move the plan: $start then $middle")
        assertTrue(middle > end, "the transition must keep moving it: $middle then $end")
        assertTrue(start > 1.5, "Focus owns 60% against Admin's 20% at the near keyframe, got $start")
        assertTrue(middle in 0.7..1.4, "the two are 40/40 halfway through the transition, got $middle")
        assertTrue(end < 0.7, "Admin owns 60% against Focus's 20% at the far keyframe, got $end")
    }

    @Test
    fun the_percentages_slide_evenly_rather_than_snapping_over_on_the_date() {
        // The reference's "the result is of the same type, a single list of rules, simply at t_p the
        // scheduler is done to satisfy the priorities that are at exactly t_p" — read across positions, that
        // is a plan that transforms EVENLY, never one that holds still and then jumps on the keyframe's date.
        val s = keyframes()
        val focus = taskId(s, "Focus")
        val shares =
            (0..4).map { step ->
                val now = T0 + step * DAY / 4
                val panels = SchedulerDomain.fillSchedule(s, now, horizonMillis = now + 2 * DAY)
                val total = panels.filter { it.auto }.sumOf { it.endEpochMillis - it.startEpochMillis }
                servedMillis(panels, focus).toDouble() / total
            }
        assertTrue(shares.first() > shares.last(), "Focus must lose share across the transition: $shares")
        // No single step may carry the whole move: the transition is spread over the span, not banked on a date.
        val move = shares.first() - shares.last()
        val steps = shares.zipWithNext { a, b -> a - b }
        assertTrue(steps.all { it < move * 0.75 }, "one step carried almost the whole transition: $shares")
    }

    // ----- and the breaks are still periods while it happens --------------------------------------

    @Test
    fun a_screen_break_stays_a_period_while_the_percentages_slide() {
        val s = keyframes()
        val stretch = taskId(s, "Stretch")
        val now = T0 + DAY / 2 // mid-transition: both arrangements are in force at once
        val panels = SchedulerDomain.fillSchedule(s, now, horizonMillis = now + DAY)
        val bands = panels.filter { it.screenBreak }
        assertTrue(bands.isNotEmpty(), "the break grid must be materialized")
        val work = panels.filter { it.auto }
        fun overlaps(p: TaskPanel, b: TaskPanel) =
            p.startEpochMillis < b.endEpochMillis && p.endEpochMillis > b.startEpochMillis

        for (p in work.filter { it.taskId != stretch }) {
            assertTrue(
                bands.none { overlaps(p, it) },
                "a task needing a screen was placed inside a break: ${p.title} at ${p.startEpochMillis}",
            )
        }
        // `side-dev/README.md`: a task's resilience is what decides where it may run, and this one declares
        // none to "no task allowed" — so it is kept out of every break exactly like the others, and runs
        // freely everywhere else. (It used to be CONFINED to the breaks and the no-screen periods; a period
        // can only multiply what it covers, so the model has no way to say that any more.)
        val stretchPanels = work.filter { it.taskId == stretch }
        assertTrue(stretchPanels.isNotEmpty(), "the task must still be scheduled")
        for (p in stretchPanels) {
            assertTrue(
                bands.none { overlaps(p, it) },
                "no task without a resilience to \"no task allowed\" may be inside a break",
            )
        }
    }

    @Test
    fun the_dated_trees_and_the_breaks_are_both_scheduling_inputs() {
        // CLAUDE.md trigger rule: the plan is re-run when [schedulingSignature] moves. Test 13's two moving
        // parts are a keyframe's contents and the break grid, so an edit to either must re-plan — otherwise
        // "the priorities of exactly this position" would be whatever the last unrelated edit left behind.
        val s = keyframes()
        val base = SchedulerDomain.schedulingSignature(s)
        val retimed = s.copy(screenBreaks = s.screenBreaks.map { it.copy(intervalMillis = it.intervalMillis * 2) })
        assertTrue(base != SchedulerDomain.schedulingSignature(retimed), "the breaks are a scheduling input")
        val moved =
            s.copy(taskTrees = s.taskTrees.map { if (it.title == "before") it.copy(dateMillis = T0 - DAY) else it })
        assertTrue(base != SchedulerDomain.schedulingSignature(moved), "a keyframe's DATE is a scheduling input")
    }
}
