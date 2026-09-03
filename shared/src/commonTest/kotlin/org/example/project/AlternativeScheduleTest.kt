package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.model.TaskPanel
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState

/**
 * `side-dev/README.md` § *Alternative Schedules*:
 *
 * > The returned set of rules must also give for every $now line$ the task that must be scheduled if the task
 * > scheduled by the scheduler can't be scheduled now. When it happens, a program would simply read the
 * > rules, set this new task starting at $now line$, and run the scheduler again with this new schedule.
 *
 * Both halves are pinned here. **Naming it**: every rule the fill makes carries the alternative beside the
 * pick ([TaskPanel.alternativeTaskId]), so the answer can be *read* out of the returned rules at any position
 * of the line ([SchedulerDomain.alternativeTaskAt]) — the port of `side-dev/scheduler.py`'s `Placement.alt`
 * and `Scheduler.alternative_at`, and the same invariant `tests_displayer.py`'s `unnamed_alternatives`
 * enforces there. **Using it**: PRD §7 "Switch task" is the README's own re-run — refuse the scheduled task
 * at the line and the schedule that comes back really does start the named alternative there.
 */
class AlternativeScheduleTest {

    private val MIN = 60_000L
    private val T0 = 1_700_000_000_000L

    /**
     * Three equal-priority siblings, so "who instead?" is a real choice and not the only other task.
     *
     * [breaks] carries the production screen-break configuration, which is what decides **which of the fill's
     * two phases answers**: the three dynamic periods disturb the timeline forever, so the walk never freezes
     * and everything is placed by phase 1 (the port of `side-dev/scheduler.py`'s `Walk.run`). Without them
     * the context freezes before phase 1 places anything and the analytic cycle writes the whole horizon —
     * a rule of the returned set either way, so both are asked here.
     */
    private fun stateWithThreeTasks(breaks: Boolean = true): Pair<SchedulerState, List<TaskId>> {
        var s = SchedulerState.empty()
        val root = s.rootListId
        for ((i, title) in listOf("A", "B", "C").withIndex()) {
            s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(s.lists[root]!!.cellIds[i], title))
        }
        if (breaks) s = s.copy(screenBreaks = SchedulerDomain.DEFAULT_SCREEN_BREAKS)
        val ids = listOf("A", "B", "C").map { t -> s.tasks.keys.first { s.tasks[it]!!.title == t } }
        return s to ids
    }

    private fun filled(state: SchedulerState, hours: Long = 12): List<TaskPanel> =
        SchedulerDomain.fillSchedule(state, T0, horizonMillis = T0 + hours * 60 * MIN)

    private fun rules(panels: List<TaskPanel>) =
        panels.filter { it.auto && it.taskId != null }.sortedBy { it.startEpochMillis }

    // ----- every rule names one ---------------------------------------------------------------

    @Test
    fun every_rule_the_scheduler_makes_names_an_alternative() {
        // `tests_displayer.py` `unnamed_alternatives`: a rule that names nobody is only allowed where there
        // is nobody to name. Here every task has the same (default) resilience to every kind, so a stretch
        // either admits all three or admits none and holds no rule at all.
        val (state, _) = stateWithThreeTasks()
        val made = rules(filled(state))
        assertTrue(made.isNotEmpty(), "the fill placed nothing to check")
        val unnamed = made.filter { it.alternativeTaskId == null }
        assertTrue(unnamed.isEmpty(), "rules naming no alternative at ${unnamed.map { it.startEpochMillis - T0 }}")
    }

    @Test
    fun the_alternative_is_never_the_task_it_replaces() {
        // "The same task again" would be no answer at all — `side-dev/scheduler.py` `Walk._alternative`.
        val (state, _) = stateWithThreeTasks()
        for (rule in rules(filled(state))) assertNotEquals(rule.taskId, rule.alternativeTaskId)
    }

    @Test
    fun the_analytic_cycle_names_them_too() {
        // The undisturbed timeline: the walk freezes before phase 1 places anything and the whole horizon is
        // written by the steady cycle. A rule is a rule — it names who runs from here instead as well.
        val (state, _) = stateWithThreeTasks(breaks = false)
        val made = rules(filled(state))
        assertTrue(made.isNotEmpty())
        assertTrue(made.all { it.alternativeTaskId != null && it.alternativeTaskId != it.taskId })
    }

    @Test
    fun a_sole_candidate_names_nobody() {
        // The exemption, and it is the absence of a choice rather than a missing answer.
        var s = SchedulerState.empty()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(s.lists[s.rootListId]!!.cellIds[0], "Solo"))
        val made = rules(filled(s))
        assertTrue(made.isNotEmpty())
        assertTrue(made.all { it.alternativeTaskId == null })
    }

    // ----- reading it at the line -------------------------------------------------------------

    @Test
    fun the_rules_answer_at_every_position_of_the_now_line() {
        // "for every $now line$": the readout answers wherever the line is put down, not only where a rule
        // happens to start — and it never answers with the task it would be replacing.
        val (state, _) = stateWithThreeTasks()
        val panels = filled(state)
        val last = rules(panels).last().endEpochMillis
        var probe = T0
        var answered = 0
        while (probe < last) {
            val alt = SchedulerDomain.alternativeTaskAt(panels, probe)
            assertNotNull(alt, "no alternative at +${(probe - T0) / MIN} min")
            val scheduled = SchedulerDomain.taskAtNowLine(state.copy(panels = panels), probe)
            if (scheduled != null) assertNotEquals(scheduled, alt)
            answered++
            probe += 7 * MIN
        }
        assertTrue(answered > 10)
    }

    @Test
    fun a_line_standing_in_emptiness_is_answered_by_the_next_rule() {
        // The line in mode 1 sits at the edge of the period it drags, so the instant itself is often inside a
        // stretch nobody may run in. `Scheduler.alternative_at`'s second pass: the question is then about the
        // next thing scheduled.
        val (_, ids) = stateWithThreeTasks()
        val a = ids[0]
        val b = ids[1]
        val ahead = TaskPanel("auto/0", a, "A", T0 + 30 * MIN, T0 + 60 * MIN, auto = true, alternativeTaskId = b)
        assertEquals(b, SchedulerDomain.alternativeTaskAt(listOf(ahead), T0))
        assertNull(SchedulerDomain.alternativeTaskAt(listOf(ahead), T0 + 90 * MIN))
    }

    // ----- the README's own use of it ---------------------------------------------------------

    @Test
    fun refusing_the_scheduled_task_really_does_start_the_named_alternative() {
        // "a program would simply read the rules, set this new task starting at $now line$, and run the
        // scheduler again": in OmniApp that program is PRD §7's "Switch task". What comes back must be the
        // task the rules named, or the answer they gave was not the schedule's own.
        val (s0, _) = stateWithThreeTasks()
        val state = s0.copy(panels = filled(s0))
        val scheduled = SchedulerDomain.taskAtNowLine(state, T0)
        assertNotNull(scheduled)
        val alternative = SchedulerDomain.alternativeTaskAt(state.panels, T0)
        assertNotNull(alternative)
        assertNotEquals(scheduled, alternative)

        val switched = SchedulerReducer.reduce(state, SchedulerIntent.ForceTaskSwitch(T0))
        assertEquals(alternative, SchedulerDomain.taskAtNowLine(switched, T0))
    }

    @Test
    fun the_re_run_names_an_alternative_of_its_own() {
        // "this alternative schedule doesn't say what happens next if this alternative task is chosen" — so
        // the re-run is an ordinary schedule, answering the question again from where it now stands.
        val (s0, _) = stateWithThreeTasks()
        val state = s0.copy(panels = filled(s0))
        val switched = SchedulerReducer.reduce(state, SchedulerIntent.ForceTaskSwitch(T0))
        val alt = SchedulerDomain.alternativeTaskAt(switched.panels, T0)
        assertNotNull(alt)
        assertNotEquals(SchedulerDomain.taskAtNowLine(switched, T0), alt)
    }

    // ----- the answer belongs to the run's start -----------------------------------------------

    @Test
    fun a_merged_run_keeps_the_alternative_named_at_its_start() {
        // `side-dev/scheduler.py`'s `coalesce`: the alternative kept is the one named at the run's START,
        // which is the answer a single uninterrupted plan would have given.
        val (_, ids) = stateWithThreeTasks()
        val a = ids[0]
        val b = ids[1]
        val c = ids[2]
        val head = TaskPanel("auto/0", a, "A", T0, T0 + 30 * MIN, auto = true, alternativeTaskId = b)
        val tail = TaskPanel("auto/1", a, "A", T0 + 30 * MIN, T0 + 60 * MIN, auto = true, alternativeTaskId = c)
        val merged = SchedulerDomain.mergeSameTaskPanels(listOf(head, tail))
        assertEquals(1, merged.size)
        assertEquals(T0 + 60 * MIN, merged[0].endEpochMillis)
        assertEquals(b, merged[0].alternativeTaskId)
    }
}
