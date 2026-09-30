package org.example.project

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.DynamicPeriods
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.domain.SchedulerRunRules
import org.example.project.scheduler.model.TaskPanel
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerRunEntry
import org.example.project.scheduler.state.SchedulerState

/**
 * `docs/scheduler_requirements.md` keeps two things apart that the History window had run together:
 *
 * > **Rule State Definition:** A rule state is the set of tasks and their associated priority percentages,
 * > minimum execution time and resilience values for every periods at a given moment in time.
 *
 * versus
 *
 * > The scheduler returns a **set of rules** that define the task schedule for a given timeline … some of the
 * > rules returned by the scheduler are **parameterized by** two variables … $now line$ … and the $now line$
 * > mode.
 *
 * The first is the question the **user** wrote; the second is the scheduler's **answer**. The History
 * window's scheduler rows showed the first under the name of the second, so a run's "set of rules" listed
 * priorities and resiliences and said nothing at all about what would be run when.
 *
 * These tests pin the split: what each of the two lists may contain, that the answer is a list of
 * INSTRUCTIONS reproducing the schedule the fill returned, that it is written against the now-line and the
 * mode it was drawn at (change either and the answer changes), and that a recorded run carries both halves
 * plus the two parameters.
 */
class SchedulerRuleSetTest {

    private val MIN = 60_000L
    private val T0 = 1_700_000_000_000L

    /** Three siblings, so a rule has somebody to name as its alternative, plus the production breaks. */
    private fun threeTasks(breaks: Boolean = true): SchedulerState {
        var s = SchedulerState.empty()
        val root = s.rootListId
        for ((i, title) in listOf("A", "B", "C").withIndex()) {
            s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(s.lists[root]!!.cellIds[i], title))
        }
        return if (breaks) s.copy(screenBreaks = SchedulerDomain.DEFAULT_SCREEN_BREAKS) else s
    }

    private fun run(
        state: SchedulerState,
        mode: Int = DynamicPeriods.MODE_AT_SCREEN,
        hours: Long = 8,
    ): Pair<List<TaskPanel>, SchedulerRunRules> {
        var reported = SchedulerRunRules.EMPTY
        val panels =
            SchedulerDomain.fillSchedule(
                state,
                T0,
                horizonMillis = T0 + hours * 60 * MIN,
                tpMode = mode,
                rulesSink = { reported = it },
            )
        return panels to reported
    }

    // ----- the two lists are two different things -------------------------------------------------

    @Test
    fun the_rule_state_is_the_users_question_and_the_set_of_rules_is_the_schedulers_answer() {
        val (_, reported) = run(threeTasks())

        // The rule state: one line per schedulable task, and every line says what the requirements say a
        // rule state holds — a priority percentage, a minimum execution time and resilience values.
        assertEquals(3, reported.ruleState.size, reported.ruleState.toString())
        for (line in reported.ruleState) {
            assertTrue("priority" in line && "minimum" in line && "resilience" in line, line)
        }

        // The set of rules: none of that. This is the anomaly the split exists to end — a "set of rules"
        // that spelled the user's priorities was showing the question in the place reserved for the answer.
        assertTrue(reported.rules.isNotEmpty(), "the scheduler returned no rule at all")
        for (line in reported.rules.drop(1)) {
            assertFalse("priority" in line, "a returned rule spells a priority share: $line")
            assertFalse("resilience" in line, "a returned rule spells a resilience: $line")
            assertTrue(
                line.startsWith("+") || line.startsWith("-") || line.startsWith("…"),
                "a returned rule is not an instruction over the timeline: $line",
            )
        }
    }

    // ----- the answer is the schedule, read from the now-line -------------------------------------

    @Test
    fun the_returned_rules_are_the_instructions_that_give_the_future() {
        val (panels, reported) = run(threeTasks())

        // Every pick the fill made ahead of the line has its instruction, at the offset it falls at — the
        // rules are written FROM the now-line, which is what "parameterized by $now line$" means.
        val picks = panels.filter { it.auto && it.taskId != null && it.endEpochMillis > T0 }
        assertTrue(picks.isNotEmpty(), "the fill placed nothing to describe")
        for (panel in picks) {
            val minutes = (panel.startEpochMillis - T0) / MIN
            val stamp = "+${minutes / 60}:${(minutes % 60).toString().padStart(2, '0')}:"
            assertTrue(
                reported.rules.any { it.startsWith(stamp) && "run ${panel.title}" in it },
                "no rule for ${panel.title} at $stamp in ${reported.rules}",
            )
        }
        // …and the three dynamic periods, whose placement is the other half of what the line parameterizes.
        val breaks = panels.filter { it.screenBreak && it.endEpochMillis > T0 }
        assertEquals(
            breaks.size,
            reported.rules.count { "restrict [" in it },
            "the dynamic periods the fill placed are not all in the returned rules",
        )
        // Nothing behind the line: the past is frozen, so it is no longer something the rules say.
        assertTrue(
            reported.rules.drop(1).none { it.startsWith("-") },
            "a returned rule reaches behind the now-line: ${reported.rules}",
        )
    }

    @Test
    fun the_answer_names_the_now_line_mode_it_was_drawn_at() {
        // The requirements: the rules are parameterized by the mode as well as by the line, and the three
        // dynamic periods sit differently in each. A list that did not say which mode it holds for would
        // hold for none, so the mode is stated on the list itself.
        val state = threeTasks()
        val atScreen = run(state, DynamicPeriods.MODE_AT_SCREEN).second.rules
        val onBreak = run(state, DynamicPeriods.MODE_ON_BREAK).second.rules

        assertTrue(atScreen.first().startsWith("now-line mode 1"), atScreen.first())
        assertTrue(onBreak.first().startsWith("now-line mode 3"), onBreak.first())
        assertTrue(DynamicPeriods.modeLabel(DynamicPeriods.MODE_AT_SCREEN) in atScreen.first())
        // Mode 1 may not be covered by one of the three, mode 3 is: the two modes really do return two
        // different sets of rules for one rule state, which is the whole reason the parameter is recorded.
        assertTrue(atScreen != onBreak, "the mode changed nothing in the returned rules")
    }

    @Test
    fun the_instructions_are_written_against_the_line_so_one_list_names_two_schedules() {
        // Nothing about the task tree changed; only the line moved. That is exactly the case the
        // parameterization is for: the rule state is the same question, the instruction list is the same
        // answer, and the two SCHEDULES it names are 37 minutes apart because each list is read from its
        // own line. A list written in absolute instants could not do that.
        val state = threeTasks()
        val laterLine = T0 + 37 * MIN
        var later = SchedulerRunRules.EMPTY
        val laterPanels =
            SchedulerDomain.fillSchedule(
                state,
                laterLine,
                horizonMillis = laterLine + 8 * 60 * MIN,
                rulesSink = { later = it },
            )
        val (herePanels, here) = run(state)

        assertEquals(here.ruleState, later.ruleState, "the rule state is not a function of the now-line")
        // Both lists open at the line itself — nothing makes leaving it to nobody score lower here, so the first
        // instruction is at +0:00:00.
        assertTrue(here.rules[1].startsWith("+0:00:00"), here.rules[1])
        assertTrue(later.rules[1].startsWith("+0:00:00"), later.rules[1])
        // …and the schedules they name are the two different futures the two lines have.
        val hereFirst = herePanels.filter { it.auto && it.taskId != null }.minOf { it.startEpochMillis }
        val laterFirst = laterPanels.filter { it.auto && it.taskId != null }.minOf { it.startEpochMillis }
        assertEquals(37 * MIN, laterFirst - hereFirst, "the schedule did not move with the now-line")
    }

    @Test
    fun an_empty_tree_returns_a_rule_state_of_nothing_and_no_instruction() {
        // The two lists fail independently: no schedulable task is an empty QUESTION, and the answer to it
        // is the mode header and nothing else. Neither may be spelled with the other's placeholder.
        val (_, reported) = run(SchedulerState.empty().copy(screenBreaks = emptyList()))
        assertEquals(emptyList(), reported.ruleState)
        assertEquals(1, reported.rules.size, reported.rules.toString())
        assertTrue(reported.rules.single().startsWith("now-line mode"))
    }

    // ----- what a recorded run carries ------------------------------------------------------------

    @AfterTest
    fun resetReducerSeams() {
        SchedulerReducer.recordSchedulerRun = {}
        SchedulerReducer.tpMode = { DynamicPeriods.MODE_AT_SCREEN }
    }

    @Test
    fun a_recorded_run_carries_both_halves_and_the_two_parameters() {
        val recorded = mutableListOf<SchedulerRunEntry>()
        SchedulerReducer.recordSchedulerRun = { recorded += it }
        SchedulerReducer.tpMode = { DynamicPeriods.MODE_AWAY }

        SchedulerReducer.reduce(threeTasks(), SchedulerIntent.RefreshSchedule(T0))

        val entry = recorded.single()
        assertEquals(SchedulerRunEntry.Kind.Replan, entry.kind)
        // The parameters the answer is written against — without them the instruction list names no schedule.
        assertEquals(T0, entry.nowMillis)
        assertEquals(DynamicPeriods.MODE_AWAY, entry.tpMode)
        assertEquals(3, entry.ruleState.size)
        assertTrue(entry.rules.first().startsWith("now-line mode 2"), entry.rules.first())
        // The information window's two sections are two different texts; that is the anomaly's regression.
        assertTrue(entry.ruleState != entry.rules)
    }
}
