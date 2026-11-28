package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.datetime.TimeZone
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.domain.TimeFormula
import org.example.project.scheduler.persistence.SchedulerStateCodec
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState

/**
 * User rule 2026-11-16: *"In a task element, the user can define the evolution of its presence ratio target and its
 * minimum length target with any mathematical formula. By default, when a task element is created, if it is a
 * descendant of the default task tree root, then the presence ratio target relative to any task parents are constants."*
 */
class TimeFormulaTest {
    private val utc = TimeZone.UTC
    private val DAY = 86_400_000L

    private fun at(text: String, millis: Long) = assertNotNull(TimeFormula.parse(text, utc), text).at(millis)

    @Test
    fun t_is_the_instant_in_days_and_a_date_is_one() {
        assertEquals(20_000.5, at("t", 20_000 * DAY + DAY / 2))
        assertEquals(at("date(2026-12-01)", 0L), at("t", 1_796_083_200_000L))
        assertEquals(1.0 / 24 * 8.5, at("date(1970-01-01 08:30)", 0L)!!, 1e-12)
    }

    @Test
    fun arithmetic_and_functions() {
        assertEquals(14.0, at("2 + 3 * 4", 0L))
        assertEquals(20.0, at("(2 + 3) * 4", 0L))
        assertEquals(-8.0, at("-2 ^ 3", 0L))
        assertEquals(1.0, at("min(4, 1, 7)", 0L))
        assertEquals(7.0, at("max(4, 1, 7)", 0L))
        assertEquals(5.0, at("clamp(9, 0, 5)", 0L))
        assertEquals(3.0, at("sqrt(9)", 0L))
        assertNull(at("1 / 0", 0L), "not a finite number is no value")
    }

    @Test
    fun ramp_and_step_move_between_their_dates_and_hold_outside() {
        val f = "ramp(10, 20, 3, 1)"
        assertEquals(3.0, at(f, 5 * DAY))
        assertEquals(2.0, at(f, 15 * DAY))
        assertEquals(1.0, at(f, 25 * DAY))
        assertEquals(1.0, at("step(10, 1, 2)", 9 * DAY))
        assertEquals(2.0, at("step(10, 1, 2)", 10 * DAY))
        assertEquals(listOf(10 * DAY, 20 * DAY), TimeFormula.parse(f, utc)!!.breakpoints)
    }

    @Test
    fun a_formula_without_t_is_a_constant() {
        assertTrue(TimeFormula.parse("3 * 2", utc)!!.constant)
        assertFalse(TimeFormula.parse("ramp(1, 2, 3, 4)", utc)!!.constant)
        assertFalse(TimeFormula.parse("t / 100", utc)!!.constant)
    }

    @Test
    fun what_is_wrong_is_said() {
        assertNull(TimeFormula.problem(""), "blank is no formula: the constant")
        assertNull(TimeFormula.problem("ramp(date(2026-12-01), date(2027-01-01), 3, 1)"))
        assertNotNull(TimeFormula.problem("ramp(1, 2)"))
        assertNotNull(TimeFormula.problem("x + 1"))
        assertNotNull(TimeFormula.problem("date(2026-13-01)"))
        assertNotNull(TimeFormula.problem("(1 + 2"))
        assertNull(TimeFormula.parse("2 +", utc))
    }

    // ----- in the account ---------------------------------------------------------------------------

    /** "A" and "B" at the top, A's weight sliding from 3 to 1 between day 10 and day 20. */
    private fun account(): SchedulerState {
        var s = SchedulerState.empty()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(s.lists[s.rootListId]!!.cellIds.last(), "A"))
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(s.lists[s.rootListId]!!.cellIds.last(), "B"))
        val a = s.lists[s.rootListId]!!.cellIds[0]
        return SchedulerReducer.reduce(s, SchedulerIntent.SetCellWeightFormula(a, "ramp(10, 20, 3, 1)"))
    }

    private fun id(s: SchedulerState, title: String) = s.tasks.values.first { it.title == title }.id

    @Test
    fun the_presence_follows_its_formula_at_the_instant_asked() {
        val s = account()
        assertEquals(0.75, SchedulerDomain.absoluteTaskPriorities(s, 5 * DAY).getValue(id(s, "A")), 1e-12)
        assertEquals(2.0 / 3.0, SchedulerDomain.absoluteTaskPriorities(s, 15 * DAY).getValue(id(s, "A")), 1e-12)
        assertEquals(0.5, SchedulerDomain.absoluteTaskPriorities(s, 25 * DAY).getValue(id(s, "A")), 1e-12)
    }

    @Test
    fun the_minimum_follows_its_formula() {
        var s = account()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetTasksMinimumFormula(listOf(id(s, "B")), "ramp(10, 20, 30, 60)"))
        val b = s.tasks.getValue(id(s, "B"))
        assertEquals(30.0, SchedulerDomain.minimumMinutesAt(s, b, 0L))
        assertEquals(45.0, SchedulerDomain.minimumMinutesAt(s, b, 15 * DAY))
        assertEquals(b.minimumMinutes.toDouble(), SchedulerDomain.minimumMinutesAt(s, b.copy(minimumFormula = ""), 15 * DAY), "blank: the constant")
    }

    @Test
    fun a_formula_that_does_not_read_is_refused_and_a_formula_is_one_undo() {
        val s = account()
        val a = s.lists[s.rootListId]!!.cellIds[0]
        assertEquals(s, SchedulerReducer.reduce(s, SchedulerIntent.SetCellWeightFormula(a, "ramp(1")))
        val undone = SchedulerReducer.reduce(s, SchedulerIntent.Undo)
        assertEquals("", undone.cells.getValue(a).weightFormula)
        assertEquals("ramp(10, 20, 3, 1)", SchedulerReducer.reduce(undone, SchedulerIntent.Redo).cells.getValue(a).weightFormula)
    }

    @Test
    fun a_formula_is_a_scheduling_rule_and_survives_the_store() {
        val s = account()
        val a = s.lists[s.rootListId]!!.cellIds[0]
        val plain = s.copy(cells = s.cells + (a to s.cells.getValue(a).copy(weightFormula = "")))
        assertNotEquals(SchedulerDomain.schedulingSignature(plain), SchedulerDomain.schedulingSignature(s))
        val withMinimum = SchedulerReducer.reduce(s, SchedulerIntent.SetTasksMinimumFormula(listOf(id(s, "B")), "40 + t * 0"))
        val back = SchedulerStateCodec.decode(SchedulerStateCodec.encode(withMinimum))!!
        assertEquals("ramp(10, 20, 3, 1)", back.cells.getValue(a).weightFormula)
        // User rule 2026-11-20: a minimum formula is a minimum length target evolution — written on the task, it is lifted.
        assertEquals("", back.tasks.getValue(id(s, "B")).minimumFormula)
        assertEquals("40 + t * 0", back.presenceEvolutions.single { it.taskIds == listOf(id(s, "B")) }.formula)
        // Persisted-DB compatibility: a payload written before the fields has none, and writes none until one is given.
        val older = SchedulerStateCodec.encode(SchedulerState.empty())
        assertFalse("weightFormula" in older || "minimumFormula" in older)
        assertTrue(SchedulerStateCodec.decode(older)!!.cells.values.all { it.weightFormula.isEmpty() })
    }

    /** `docs/scheduler/scheduler_requirements.md` § *Rule state input evolution*: every stretch is placed under the state in force there. */
    @Test
    fun the_plan_follows_the_formula_where_it_places() {
        val s = account().copy(sleep = null)
        val start = 10 * DAY
        val plan = SchedulerDomain.fillSchedule(s, start, utc, horizonMillis = start + 10 * DAY).filter { it.auto }
        fun share(task: String, from: Long, until: Long): Double {
            fun served(t: String) = plan.filter { it.taskId == id(s, t) }
                .sumOf { maxOf(0L, minOf(it.endEpochMillis, until) - maxOf(it.startEpochMillis, from)) }.toDouble()
            return served(task) / (served("A") + served("B"))
        }
        // A's target slides from 75 % to 50 %: it holds more of the first days than of the last.
        assertTrue(share("A", start, start + 3 * DAY) > share("A", start + 7 * DAY, start + 10 * DAY), "A's share must slide down")
    }
}
