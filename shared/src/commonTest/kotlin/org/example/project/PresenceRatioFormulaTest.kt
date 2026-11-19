package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.datetime.TimeZone
import org.example.project.scheduler.domain.FormulaCoherence
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.domain.TimeFormula
import org.example.project.scheduler.domain.TimeFormula.Period
import org.example.project.scheduler.persistence.SchedulerStateCodec
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState

/**
 * User rule 2026-11-19: in the action section of a task element, the formula of the evolution of the presence ratio target
 * (or the minimum length) — over the tasks selected, relative to a task element, by time periods; incoherent, a message
 * says so and both schedulers do nothing until it is resolved.
 */
class PresenceRatioFormulaTest {
    private val utc = TimeZone.UTC
    private val DAY = 86_400_000L

    /** "A", "B" and "C" at the top, constant weights 1. */
    private fun account(): SchedulerState {
        var s = SchedulerState.empty()
        for (title in listOf("A", "B", "C")) {
            s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(s.lists[s.rootListId]!!.cellIds.last(), title))
        }
        return s.copy(sleep = null)
    }

    private fun id(s: SchedulerState, title: String) = s.tasks.values.first { it.title == title }.id

    private fun place(s: SchedulerState, title: String) = s.cells.values.first { it.taskId == id(s, title) }.id

    private fun withPresence(s: SchedulerState, vararg pairs: Pair<String, String>): SchedulerState =
        pairs.fold(s) { acc, (title, formula) -> SchedulerReducer.reduce(acc, SchedulerIntent.SetCellsPresenceFormula(listOf(place(acc, title)), formula)) }

    @Test
    fun periods_are_a_formula_and_read_back_into_the_editor() {
        val periods = listOf(Period("1970-01-11", "1970-01-21", "0.5"), Period("1970-01-21", "", "ramp(20, 30, 0.5, 0.2)"))
        val text = TimeFormula.FormulaPeriods.build(periods)
        assertEquals(periods, TimeFormula.FormulaPeriods.read(text))
        val f = TimeFormula.parse(text, utc)!!
        assertNull(f.at(5 * DAY), "outside every period: no value, the weights decide")
        assertEquals(0.5, f.at(15 * DAY))
        assertEquals(0.35, f.at(25 * DAY)!!, 1e-12)
        assertEquals(listOf(10 * DAY, 20 * DAY, 30 * DAY), f.breakpoints)
        assertEquals("0.3", TimeFormula.FormulaPeriods.build(listOf(Period("", "", "0.3"))), "one open period is the formula itself")
        assertEquals(listOf(Period("", "", "ramp(1, 2, 3, 4)")), TimeFormula.FormulaPeriods.read("ramp(1, 2, 3, 4)"))
    }

    @Test
    fun a_ratio_is_the_share_of_its_parent_and_the_free_siblings_share_the_rest() {
        val s = withPresence(account(), "A" to "0.5")
        val p = SchedulerDomain.absoluteTaskPriorities(s, 0L)
        assertEquals(0.5, p.getValue(id(s, "A")), 1e-12)
        assertEquals(0.25, p.getValue(id(s, "B")), 1e-12)
        assertEquals(0.25, p.getValue(id(s, "C")), 1e-12)
        assertTrue(FormulaCoherence.problems(s, utc).isEmpty())
    }

    @Test
    fun outside_its_periods_the_weights_decide() {
        val text = TimeFormula.FormulaPeriods.build(listOf(Period("1970-01-11", "1970-01-21", "0.6")))
        val s = withPresence(account(), "A" to text)
        assertEquals(1.0 / 3.0, SchedulerDomain.absoluteTaskPriorities(s, 5 * DAY).getValue(id(s, "A")), 1e-12)
        assertEquals(0.6, SchedulerDomain.absoluteTaskPriorities(s, 15 * DAY).getValue(id(s, "A")), 1e-12)
    }

    @Test
    fun a_total_over_100_percent_at_some_point_is_incoherent_and_both_schedulers_do_nothing() {
        // Each alone is fine; from day 10, A rises to 0.7 while B holds 0.4: 110 %.
        val s = withPresence(account(), "A" to "step(10, 0.3, 0.7)", "B" to "0.4")
        val problems = FormulaCoherence.problems(s, utc)
        assertEquals(1, problems.size, problems.toString())
        assertTrue("110" in problems.single().message && "1970-01-11" in problems.single().message, problems.single().message)
        assertFalse(FormulaCoherence.isCoherent(s))
        val start = 0L
        val deep = SchedulerDomain.fillSchedule(s, start, utc, horizonMillis = start + DAY).filter { it.auto }
        val instant = SchedulerDomain.fillSchedule(s, start, utc, horizonMillis = start + DAY, instant = true).filter { it.auto }
        assertTrue(deep.isEmpty() && instant.isEmpty(), "incoherent: nothing placed")
        assertFalse(SchedulerDomain.firstSecondsGapFillable(s, start))

        // Resolved: the plan comes back.
        val fixed = withPresence(s, "B" to "0.3")
        assertTrue(FormulaCoherence.problems(fixed, utc).isEmpty())
        assertTrue(SchedulerDomain.fillSchedule(fixed, start, utc, horizonMillis = start + DAY).any { it.auto })
    }

    @Test
    fun under_100_percent_with_nobody_to_take_the_rest_is_incoherent() {
        val s = withPresence(account(), "A" to "0.3", "B" to "0.3", "C" to "0.3")
        assertTrue(FormulaCoherence.problems(s, utc).single().message.contains("no other task"))
        assertTrue(FormulaCoherence.problems(withPresence(s, "C" to "0.4"), utc).isEmpty())
    }

    @Test
    fun a_ratio_out_of_range_overlapping_periods_and_a_negative_minimum_are_incoherent() {
        assertTrue(FormulaCoherence.problems(withPresence(account(), "A" to "1.5"), utc).any { "between 0 and 100" in it.message })
        val overlapping = TimeFormula.FormulaPeriods.build(listOf(Period("1970-01-11", "1970-01-21", "0.2"), Period("1970-01-15", "", "0.1")))
        assertTrue(FormulaCoherence.problems(withPresence(account(), "A" to overlapping), utc).any { "overlap" in it.message })
        val s = account()
        val negative = SchedulerReducer.reduce(s, SchedulerIntent.SetTasksMinimumFormula(listOf(id(s, "A")), "ramp(10, 20, 30, -10)"))
        assertTrue(FormulaCoherence.problems(negative, utc).any { "below 0" in it.message })
    }

    @Test
    fun the_editor_targets_the_places_under_the_element_chosen() {
        val s = account()
        val ids = listOf(id(s, "A"), id(s, "B"))
        val parents = FormulaCoherence.parentsOf(s, ids)
        assertEquals(1, parents.size)
        assertEquals(listOf(place(s, "A"), place(s, "B")).sortedBy { it.value }, FormulaCoherence.placesUnder(s, ids, parents.single()))
    }

    @Test
    fun a_ratio_is_a_rule_one_undo_and_survives_the_store_and_an_older_payload() {
        val s = account()
        val text = "ramp(10, 20, 0.2, 0.5)"
        val with = withPresence(s, "A" to text)
        assertNotEquals(SchedulerDomain.schedulingSignature(s), SchedulerDomain.schedulingSignature(with))
        assertEquals(with, SchedulerReducer.reduce(with, SchedulerIntent.SetCellsPresenceFormula(listOf(place(with, "A")), "ramp(1")), "refused")
        assertEquals("", SchedulerReducer.reduce(with, SchedulerIntent.Undo).cells.getValue(place(s, "A")).presenceFormula)
        assertEquals(text, SchedulerStateCodec.decode(SchedulerStateCodec.encode(with))!!.cells.getValue(place(s, "A")).presenceFormula)
        // Persisted-DB compatibility: a payload written before the field has none and loads with none.
        val older = SchedulerStateCodec.encode(s)
        assertFalse("presenceFormula" in older)
        assertTrue(SchedulerStateCodec.decode(older)!!.cells.values.all { it.presenceFormula.isEmpty() })
    }
}
