package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState

/**
 * `docs/scheduler_requirements.md` § *Rule state input evolution* (rewritten by the user 2026-10-04), driven since
 * 2026-11-16 by each weight's own formula of the instant (user rule: *"the evolution of its presence ratio target … with
 * any mathematical formula"*, `TimeFormula`) — what the dated task trees drove until then, `TaskTreeTimelineTest`'s
 * scheduling half carried over: every stretch is placed under the rule state in force THERE.
 */
class RuleStateEvolutionTest {
    private val minute = 60_000L
    private val hour = 60 * minute
    private val t0 = 1_700_000_000_000L

    /** [millis] as a formula's `t` (days). */
    private fun d(millis: Long) = (millis / 86_400_000.0).toString()

    /** [titles] at the top, each place's weight given by [weights] (title → formula; absent: constant 1). */
    private fun account(titles: List<String>, weights: Map<String, String>): SchedulerState {
        var s = SchedulerState.empty().copy(sleep = null)
        for (title in titles) s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(s.lists[s.rootListId]!!.cellIds.last(), title))
        for ((title, formula) in weights) {
            val cell = s.cells.values.first { s.tasks[it.taskId]?.title == title }.id
            s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellWeightFormula(cell, formula))
        }
        return s
    }

    /** A discrete switch at [ts]: A throughout, B before it, C from it ([afterSwitch] false: B throughout, never C). */
    private fun switchScenario(ts: Long, afterSwitch: Boolean = true): SchedulerState =
        account(
            listOf("A", "B", "C"),
            if (afterSwitch) mapOf("B" to "step(${d(ts)}, 1, 0)", "C" to "step(${d(ts)}, 0, 1)") else mapOf("C" to "0"),
        )

    private fun runs(state: SchedulerState, now: Long, horizon: Long) =
        SchedulerDomain.fillSchedule(state, now, horizonMillis = now + horizon)
            .filter { it.taskId != null && !it.isRestrictivePeriod && it.auto }
            .sortedBy { it.startEpochMillis }

    @Test
    fun a_switch_ahead_is_planned_under_the_rule_state_in_force_on_each_side_of_it() {
        val ts = t0 + 6 * hour
        val plan = runs(switchScenario(ts), t0, 12 * hour)
        val before = plan.filter { it.startEpochMillis < ts }
        val after = plan.filter { it.endEpochMillis > ts }
        assertTrue(before.isNotEmpty() && after.isNotEmpty(), "the fixture places runs on both sides")
        assertTrue(before.all { it.title != "C" && it.endEpochMillis <= ts }, "nothing of what follows before the switch: $before")
        assertTrue(after.all { it.title != "B" && it.startEpochMillis >= ts }, "nothing of what precedes after it: $after")
        // The new rule state catches up on the frozen past: C, never served, leads from the switch.
        assertEquals("C", after.first().title)
    }

    @Test
    fun before_a_switch_the_plan_is_the_one_the_old_rule_state_alone_would_give() {
        val ts = t0 + 6 * hour
        val withSwitch = runs(switchScenario(ts), t0, 12 * hour).filter { it.startEpochMillis < ts }
        val alone = runs(switchScenario(ts, afterSwitch = false), t0, 12 * hour)
            .filter { it.startEpochMillis < ts }
            .map { it.copy(endEpochMillis = minOf(it.endEpochMillis, ts)) }
        assertEquals(
            alone.map { Triple(it.title, it.startEpochMillis, it.endEpochMillis) },
            withSwitch.map { Triple(it.title, it.startEpochMillis, it.endEpochMillis) },
        )
    }

    @Test
    fun the_far_side_of_a_switch_is_definitive_before_the_line_reaches_it() {
        val ts = t0 + 6 * hour
        val s = switchScenario(ts)
        val early = runs(s, t0, 24 * hour)
        val frozen = s.copy(panels = early.filter { it.startEpochMillis < ts }.map { it.copy(endEpochMillis = minOf(it.endEpochMillis, ts)) })
        val atSwitch = runs(frozen, ts, 18 * hour).filter { it.startEpochMillis >= ts }
        val window = ts + 6 * hour
        assertEquals(
            atSwitch.filter { it.startEpochMillis < window }.map { Triple(it.title, it.startEpochMillis, minOf(it.endEpochMillis, window)) },
            early.filter { it.startEpochMillis >= ts && it.startEpochMillis < window }
                .map { Triple(it.title, it.startEpochMillis, minOf(it.endEpochMillis, window)) },
        )
    }

    /** B holds everything at [t0]; A's share rises to [endPercent] over [spanMinutes], at the same slope whatever the span. */
    private fun rampScenario(endPercent: Int, spanMinutes: Long): SchedulerState {
        val end = endPercent / 100.0
        val t1 = t0 + spanMinutes * minute
        return account(
            listOf("A", "B"),
            mapOf("A" to "ramp(${d(t0)}, ${d(t1)}, 0, $end)", "B" to "ramp(${d(t0)}, ${d(t1)}, 1, ${1.0 - end})"),
        )
    }

    @Test
    fun inside_a_transition_every_run_is_decided_under_the_rule_state_at_its_own_instant() {
        val s = rampScenario(endPercent = 100, spanMinutes = 600)
        val early = runs(s, t0, 6 * hour)
        assertTrue(early.size >= 3, "the fixture places several runs")
        for (k in 1..2) {
            val at = early[k].startEpochMillis
            val frozen = s.copy(panels = early.filter { it.startEpochMillis < at })
            val first = runs(frozen, at, 6 * hour).first { it.startEpochMillis >= at }
            assertEquals(early[k].title, first.title, "run $k (at ${(at - t0) / minute} min) is the rule state's own decision")
        }
    }

    @Test
    fun a_run_the_moving_rule_state_turns_against_ends_where_it_does() {
        val s = rampScenario(endPercent = 100, spanMinutes = 600)
        val horizon = 6 * hour
        val panels = SchedulerDomain.fillSchedule(s, t0, horizonMillis = t0 + horizon)
            .filter { it.taskId != null && !it.isRestrictivePeriod }
            .sortedBy { it.startEpochMillis }
        val first = panels.first()
        assertEquals("B", first.title, "B leads while it holds nearly all the priority")
        assertTrue(first.endEpochMillis < t0 + horizon, "B's first run ends inside the stage")
        assertEquals(first.endEpochMillis, panels[1].startEpochMillis, "the next run starts on the instant the first one ends")
        val cut = first.endEpochMillis
        val atCut = SchedulerDomain.fillSchedule(s.copy(panels = listOf(first)), cut, horizonMillis = cut + horizon)
            .filter { it.taskId != null && !it.isRestrictivePeriod && it.startEpochMillis >= cut }
            .minBy { it.startEpochMillis }
        assertEquals("A", atCut.title, "the rule state at the cut prefers A")
    }

    /**
     * The README's worked example: *"Task A goes from 0 to 100% priority from t1 to t2=t1+10min"* and *"from 0 to 50% …
     * t2=t1+5min"* give *"the same schedule and alternative schedule up to t1+5min"*. Same slope, same rule state.
     */
    @Test
    fun the_same_slope_gives_the_same_rule_state_and_the_same_schedule_while_they_overlap() {
        val slow = rampScenario(endPercent = 100, spanMinutes = 10)
        val fast = rampScenario(endPercent = 50, spanMinutes = 5)
        fun idOf(s: SchedulerState, title: String) = s.tasks.values.first { it.title == title }.id
        for (tenth in 0..50) {
            val now = t0 + tenth * minute / 10
            val slowP = SchedulerDomain.absoluteTaskPriorities(slow, now)
            val fastP = SchedulerDomain.absoluteTaskPriorities(fast, now)
            assertEquals(0.1 * tenth / 10.0, slowP.getValue(idOf(slow, "A")), 1e-9, "slow A at ${tenth / 10.0} min")
            assertEquals(slowP.getValue(idOf(slow, "A")), fastP.getValue(idOf(fast, "A")), 1e-9, "A at ${tenth / 10.0} min")
        }
        fun planAt(state: SchedulerState, now: Long, until: Long): List<String> {
            val panels = SchedulerDomain.fillSchedule(state, now, horizonMillis = now + 6 * hour)
                .filter { it.taskId != null && it.startEpochMillis < until }
                .sortedBy { it.startEpochMillis }
            return panels.map { "${it.title}@${it.startEpochMillis}-${minOf(it.endEpochMillis, until)}" } +
                (now until until step minute).map { t -> "alt@$t=${SchedulerDomain.alternativeTaskAt(panels, t)?.let { id -> state.tasks[id]?.title }}" }
        }
        for (halfMinute in 0..9) {
            val now = t0 + halfMinute * minute / 2
            assertEquals(planAt(slow, now, t0 + 5 * minute), planAt(fast, now, t0 + 5 * minute), "the plan at ${halfMinute / 2.0} min past t1")
        }
        assertTrue(planAt(slow, t0 + 9 * minute, t0 + 6 * hour) != planAt(fast, t0 + 9 * minute, t0 + 6 * hour), "the guarantee is about the shared slope")
    }
}
