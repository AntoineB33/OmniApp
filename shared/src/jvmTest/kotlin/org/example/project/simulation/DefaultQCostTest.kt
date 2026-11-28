package org.example.project.simulation

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.DynamicPeriods
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.domain.SearchBudget
import org.example.project.scheduler.domain.SearchReport
import org.example.project.scheduler.engine.pacedStepSearchMillis
import org.example.project.scheduler.state.DEFAULT_PLAN_RESOURCES
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer

/**
 * `docs/scheduler/scheduler_requirements.md`, the "allocated resources quantity" hyperparameter `q` (user rule 2026-11-21: *"I
 * don't want my fans to make a noise for hours with the default q value and a task tree that is not particularly
 * complex"*). A run is about a thousand ten-minute steps to `now + 168 h`, started again at every change of the rules:
 * what one step's search costs at the default `q` is what the run costs a thousand times.
 *
 * Until 2026-11-21 every step spent its whole grant (`q` of the pace, ~1.75 s at 0.25) — and SCIP, started with a few
 * milliseconds left, ran most of a second past it — so a 12-task tree cost 31 min of wall time and 45 min of CPU per run,
 * for a plan that scored the same as one with no search at all.
 */
class DefaultQCostTest {
    private val hooks = listOf(
        SchedulerReducer.tpMode, SchedulerReducer.noScreenEvidence, SchedulerReducer.liveRestGap,
        SchedulerReducer.frozenScreenBreaks, SchedulerReducer.planAbandoned, SchedulerReducer.scheduleHorizonEndMillis,
        SchedulerReducer.planSearchSink,
    )

    @AfterTest
    @Suppress("UNCHECKED_CAST")
    fun restore() {
        SchedulerReducer.tpMode = hooks[0] as () -> Int
        SchedulerReducer.noScreenEvidence = hooks[1] as () -> List<org.example.project.scheduler.model.TaskTimeRange>
        SchedulerReducer.liveRestGap = hooks[2] as () -> SchedulerDomain.LiveRest?
        SchedulerReducer.frozenScreenBreaks = hooks[3] as () -> org.example.project.scheduler.domain.FrozenScreenBreaks?
        SchedulerReducer.planAbandoned = hooks[4] as (Long) -> Boolean
        SchedulerReducer.scheduleHorizonEndMillis = hooks[5] as (Long) -> Long
        SchedulerReducer.planSearchSink = hooks[6] as (SearchReport, Double?) -> Unit
    }

    @Test
    fun at_the_default_q_a_step_whose_search_finds_nothing_gives_most_of_its_grant_back() {
        SchedulerReducer.tpMode = { DynamicPeriods.MODE_AT_SCREEN }
        SchedulerReducer.noScreenEvidence = { emptyList() }
        SchedulerReducer.liveRestGap = { null }
        SchedulerReducer.frozenScreenBreaks = { null }
        SchedulerReducer.planAbandoned = { false }
        // The engine's goal: now + 168 h.
        SchedulerReducer.scheduleHorizonEndMillis = { it + SchedulerDomain.SCHEDULE_HORIZON_MILLIS }
        val reports = ArrayList<SearchReport>()
        SchedulerReducer.planSearchSink = { report, _ -> reports += report }

        val scenario = ScheduleSimulation.Scenario(seed = 7, tasks = 12, kinds = 2, days = 9, periodsPerWeek = 4, prePlacedPerWeek = 2)
        var s = ScheduleSimulation.build(scenario)
        kotlin.test.assertEquals(DEFAULT_PLAN_RESOURCES, s.planResources)
        val now = ScheduleSimulation.T0 + 9 * ScheduleSimulation.HOUR
        val step = 10 * ScheduleSimulation.MIN
        // A device whose own passes cost ~20 ms a step (this one's, measured): the grant is ~1.75 s.
        val grant = pacedStepSearchMillis(step, 8.0, s.planResources)
        assertTrue(grant > 1_500L, "the grant itself is unchanged: $grant ms")
        // The first step re-plans, and pays OR-Tools' first window — which teaches the solver what a window costs here.
        s = SchedulerReducer.reduce(s, SchedulerIntent.RefreshSchedule(now, now + step, grant))
        s = SchedulerReducer.reduce(s, SchedulerIntent.ExtendSchedule(now, now + 2 * step, grant))
        reports.clear()
        var reached = now + 2 * step
        repeat(6) {
            reached += step
            s = SchedulerReducer.reduce(s, SchedulerIntent.ExtendSchedule(now, reached, grant))
        }
        val searched = reports.filter { it.exhausted }
        assertTrue(searched.isNotEmpty(), "the steps searched")
        // 2026-11-21: ~90 ms a step (was ~2 s). A generous bound, so a slow machine does not fail it.
        val worst = searched.maxOf { it.searchMillis }
        assertTrue(worst < grant / 3, "a step searched $worst ms of its $grant ms finding nothing better")
    }

    @Test
    fun the_patience_runs_from_the_last_gain_and_is_shared_by_every_pass() {
        // q = 1: all the time the pace allows, gains or not.
        val full = SearchBudget.paced(400L, 1.0)
        Thread.sleep(120)
        assertFalse(full.expired(), "q = 1 has no patience")

        // q = 0.5 of a 400 ms grant: 100 ms without a gain.
        val half = SearchBudget.paced(400L, 0.5)
        half.progressed()
        val shared = half.share(10_000L)
        Thread.sleep(60)
        shared.progressed() // a pass found something better: the patience runs again, for the whole search
        Thread.sleep(60)
        assertFalse(half.expired(), "a gain 60 ms ago, patience 100 ms")
        assertTrue(half.remainingMillis() <= 100L)
        Thread.sleep(80)
        assertTrue(half.expired(), "140 ms without a gain")
        assertTrue(shared.expired())
        assertTrue(half.remainingMillis() == 0L)

        // q = 0 and a NaN search nothing more than their grant says; no grant is no search.
        assertTrue(SearchBudget.paced(0L, 0.25).expired())
        assertTrue(SearchBudget.paced(400L, Double.NaN).expired(), "NaN: patience 0")
    }
}
