package org.example.project

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.domain.PeriodKinds
import org.example.project.scheduler.domain.PlanTask
import org.example.project.scheduler.state.HistorySource
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerRunEntry
import org.example.project.scheduler.state.SchedulerState
import org.example.project.ui.FilteredHistoryEntry
import org.example.project.ui.HistoryFilterConfig
import org.example.project.ui.filteredHistoryUnits

/**
 * PRD §6/§9: the History window's **scheduler engine** source.
 *
 * A re-plan is not a History Unit — PRD §9 is explicit that a schedule is derived from the current state, so
 * nothing undoes it — but it is the app deciding something, and the one thing about it the user cannot read
 * anywhere else is what the scheduler was asked and what it answered. These pin that the two plan reductions
 * report a run, that the run carries **both** halves — the § *Rule State Definition* it read and the set of
 * rules it returned ([SchedulerRuleSetTest] owns the split itself) — and that a rule-state line says what
 * `docs/scheduler_requirements.md` says a rule state holds (a share, a minimum, a resilience).
 */
class SchedulerRunLogTest {
    private val previousSink = SchedulerReducer.recordSchedulerRun
    private val runs = mutableListOf<SchedulerRunEntry>()

    @AfterTest
    fun restore() {
        SchedulerReducer.recordSchedulerRun = previousSink
    }

    private fun collect() {
        SchedulerReducer.recordSchedulerRun = { runs.add(it) }
    }

    @Test
    fun a_re_plan_reports_the_rule_state_it_read_and_the_rules_it_returned() {
        collect()
        var s = SchedulerState.empty()
        val cellId = s.lists[s.rootListId]!!.cellIds.first()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(cellId, "Deep work"))
        runs.clear()

        SchedulerReducer.reduce(s, SchedulerIntent.RefreshSchedule(0L))

        val run = runs.single()
        assertEquals(SchedulerRunEntry.Kind.Replan, run.kind)
        assertTrue(
            run.ruleState.any { it.contains("Deep work") },
            "the run must carry the rule state of every schedulable task: ${run.ruleState}",
        )
        assertTrue(
            run.rules.any { it.contains("run Deep work") },
            "the run must carry the instructions it returned: ${run.rules}",
        )
    }

    @Test
    fun a_re_plan_given_the_time_reaches_and_reports_the_certified_best() {
        // `docs/scheduler_requirements.md` § *Strict requirements*: "if the best possible score is reachable within
        // the required time and acceptable computer power, it must be reached" — on a case this small the
        // exhaustive search finishes inside the time a stage is given, and the run says the result is the best.
        collect()
        var s = SchedulerState.empty()
        val cells = s.lists[s.rootListId]!!.cellIds
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(cells[0], "Deep work"))
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(s.lists[s.rootListId]!!.cellIds[1], "Email"))
        runs.clear()

        SchedulerReducer.reduce(s, SchedulerIntent.RefreshSchedule(0L, horizonCapMillis = 30 * 60_000L, searchMillis = 20_000))

        val run = runs.single()
        assertTrue(run.search?.certified == true, "the search finished and certified the plan: ${run.search}")
        assertTrue(run.score != null)
        // Without the time, nothing past the step-bounded passes runs, and nothing is claimed.
        runs.clear()
        SchedulerReducer.reduce(s, SchedulerIntent.RefreshSchedule(0L, horizonCapMillis = 30 * 60_000L))
        assertEquals(false, runs.single().search?.certified)
    }

    @Test
    fun a_horizon_extension_is_reported_as_the_other_event() {
        // PRD §9: growing the horizon is NOT a change to the scheduling rules, and the row has to say so —
        // otherwise a user reading the log cannot tell a re-plan from a materialization of the same plan.
        collect()
        var s = SchedulerState.empty()
        val cellId = s.lists[s.rootListId]!!.cellIds.first()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(cellId, "Deep work"))
        runs.clear()

        SchedulerReducer.reduce(s, SchedulerIntent.ExtendSchedule(0L))

        assertEquals(SchedulerRunEntry.Kind.Extension, runs.single().kind)
    }

    @Test
    fun a_rule_state_line_spells_the_share_the_minimum_and_the_resilience() {
        // `docs/scheduler_requirements.md` § *Rule State Definition*, and resilience is "the ONE thing that
        // says where a task may run and at what share", so a line that omitted it would not be the rule
        // state the scheduler read.
        val plain =
            SchedulerDomain.describePlanRule(
                PlanTask(id = TaskId("task/1"), priority = 0.5, minimumMillis = 45 * 60_000L),
                title = "Deep work",
            )
        assertTrue(plain.contains("Deep work"), plain)
        assertTrue(plain.contains("50.0%"), plain)
        assertTrue(plain.contains("45 min"), plain)
        assertTrue(plain.contains("on screen only"), plain)

        val resilient =
            SchedulerDomain.describePlanRule(
                PlanTask(
                    id = TaskId("task/2"),
                    priority = 0.25,
                    minimumMillis = 0L,
                    resilience = mapOf(PeriodKinds.NO_SCREEN to 1.0),
                ),
                title = "Walk",
            )
        assertTrue(resilient.contains(PeriodKinds.NO_SCREEN), resilient)
        assertTrue(resilient.contains("100.0%"), resilient)
    }

    @Test
    fun the_runs_reach_the_history_window_under_the_scheduler_engine_source() {
        val entry =
            SchedulerRunEntry(
                timeMillis = 4_000,
                kind = SchedulerRunEntry.Kind.Replan,
                horizonMillis = 9_000,
                panelCount = 2,
                ruleState = listOf("Deep work - priority 50.0%, minimum 45 min, resilience: on screen only"),
                rules = listOf("+0:00:00 -> +0:45:00  run Deep work"),
            )
        val rows =
            filteredHistoryUnits(
                SchedulerState.empty().histories,
                HistoryFilterConfig(filterBySource = true, source = HistorySource.SchedulerEngine),
                schedulerRuns = listOf(entry),
            )
        assertEquals(entry, (rows.single() as FilteredHistoryEntry.SchedulerRun).entry)

        // ...and only under that source: the window field lists History Units, and a run is not one.
        assertTrue(
            filteredHistoryUnits(
                SchedulerState.empty().histories,
                HistoryFilterConfig(),
                schedulerRuns = listOf(entry),
            ).isEmpty(),
        )
    }
}
