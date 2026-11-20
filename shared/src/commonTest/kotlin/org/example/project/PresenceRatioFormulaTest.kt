package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.datetime.TimeZone
import org.example.project.scheduler.domain.ElementTree
import org.example.project.scheduler.domain.EvolutionPeriod
import org.example.project.scheduler.domain.FormulaCoherence
import org.example.project.scheduler.domain.PresenceEvolution
import org.example.project.scheduler.domain.PresenceEvolutions
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.domain.SearchDomain
import org.example.project.scheduler.domain.TimeFormula
import org.example.project.scheduler.domain.TimeFormula.Period
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.persistence.SchedulerStateCodec
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState

/**
 * User rule 2026-11-19: a **presence ratio target evolution** is an element — the tasks that get it, what it is relative to
 * (a task above them, or each one's ancestor at a parent distance), its time periods, its formula, its categories — edited
 * from the rows of a task's "Presence ratio target" action. Incoherent, a message says so and both schedulers do nothing.
 */
class PresenceRatioFormulaTest {
    private val utc = TimeZone.UTC
    private val DAY = 86_400_000L

    /** "P" holding "A", "B", "C"; and "Q" beside "P", both under the global root. All weights 1. */
    private fun account(): SchedulerState {
        var s = SchedulerState.empty().copy(sleep = null)
        fun freeRoot() = s.lists[s.rootListId]!!.cellIds.last()
        fun freeChild(t: TaskId) = s.lists[s.tasks[t]!!.childListId!!]!!.cellIds.last()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(freeRoot(), "P"))
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(freeRoot(), "Q"))
        for (title in listOf("A", "B", "C")) s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(freeChild(id(s, "P")), title))
        return s
    }

    private fun id(s: SchedulerState, title: String) = s.tasks.values.first { it.title == title }.id

    private fun with(s: SchedulerState, vararg es: PresenceEvolution) = SchedulerReducer.reduce(s, SchedulerIntent.SetPresenceEvolutions(s.presenceEvolutions + es))

    private fun evo(id: String, tasks: List<TaskId>, formula: String, relative: TaskId? = null, distance: Int = 1, periods: List<EvolutionPeriod> = emptyList()) =
        PresenceEvolution(id, tasks, relative, distance, periods, formula)

    private fun share(s: SchedulerState, title: String, at: Long = 0L) = SchedulerDomain.absoluteTaskPriorities(s, at).getValue(id(s, title))

    /**
     * User rule 2026-11-20: every task under the first task tree's root has a default evolution — constant, relative to the
     * global root, its value the task's absolute priority — derived (never stored), listed for the task, and leaving the
     * schedulers' shares as the weights give them. Edited, it is written under its own id and replaces the default.
     */
    @Test
    fun every_task_of_the_first_tree_has_a_default_evolution_its_absolute_priority() {
        val s = account()
        val defaults = PresenceEvolutions.defaults(s)
        assertEquals(setOf("A", "B", "C"), defaults.flatMap { it.taskIds }.map { t -> s.tasks[t]!!.title }.toSet(), "the first tree's tasks, not Q's")
        assertTrue(s.presenceEvolutions.isEmpty(), "derived, never stored")
        val a = id(s, "A")
        val ofA = PresenceEvolutions.involving(s, listOf(a)).single()
        assertEquals(PresenceEvolutions.defaultIdOf(a), ofA.id)
        assertEquals(org.example.project.scheduler.model.WellKnownIds.ROOT_TASK, ofA.relativeTask)
        assertTrue(ofA.periods.isEmpty(), "the whole timeline")
        assertEquals(share(s, "A"), TimeFormula.parse(ofA.formula, utc)!!.at(0L)!!, 1e-9)
        assertTrue(PresenceEvolutions.isDefault(ofA, s))
        assertTrue(SearchDomain.results(s, setOf(SearchDomain.Kind.PresenceEvolution), "").any { SearchDomain.keyOf(it).endsWith(ofA.id) })

        // The weights move it: A's weight doubled.
        val doubled = SchedulerReducer.reduce(s, SchedulerIntent.SetCellWeightFormula(s.cells.values.first { it.taskId == a }.id, "2"))
        assertNotEquals(ofA.formula, PresenceEvolutions.involving(doubled, listOf(a)).single().formula)

        // Edited: written under its id, the default gone, its value now the schedulers'.
        val edited = with(s, ofA.copy(formula = "0.3"))
        assertEquals(listOf(ofA.id), edited.presenceEvolutions.map { it.id })
        assertFalse(PresenceEvolutions.isDefault(PresenceEvolutions.involving(edited, listOf(a)).single(), edited))
        assertEquals(0.3, share(edited, "A"), 1e-9)
        assertTrue(FormulaCoherence.problems(edited, utc).isEmpty())
    }

    /**
     * User rule 2026-11-20: the field of P's actions lists the evolutions given to P first, then those P is otherwise
     * involved in (what they are relative to), then the rest.
     */
    @Test
    fun the_evolutions_of_the_field_s_tasks_lead_then_those_they_are_involved_in() {
        val s0 = account()
        val root = org.example.project.scheduler.model.WellKnownIds.ROOT_TASK
        val s = with(
            s0,
            evo("rest", listOf(id(s0, "Q")), "0.3"),
            evo("byDistance", listOf(id(s0, "A")), "0.3"),
            evo("byTask", listOf(id(s0, "B")), "0.3", relative = id(s0, "P")),
            evo("own", listOf(id(s0, "P")), "0.5", relative = root),
        )
        val (own, involved) = PresenceEvolutions.involvementGroups(s, listOf(id(s, "P")))
        assertEquals(setOf("own"), own)
        assertEquals(setOf("byDistance", "byTask"), involved)
        val rows = SearchDomain.results(s, setOf(SearchDomain.Kind.PresenceEvolution), "")
        val key = { e: String -> SearchDomain.Kind.PresenceEvolution.name + "/" + e }
        val led = SearchDomain.leadingFirst(rows, listOf(own.mapTo(HashSet(), key), involved.mapTo(HashSet(), key))).map { SearchDomain.keyOf(it) }
        assertEquals(key("own"), led.first())
        assertEquals(setOf(key("byDistance"), key("byTask")), led.subList(1, 3).toSet())
        assertEquals(rows.size, led.size, "every evolution still listed")
        assertTrue(led.indexOf(key("rest")) > 2)
    }

    @Test
    fun a_default_formula_is_a_plain_decimal() {
        assertEquals("0.25", PresenceEvolutions.constantText(0.25))
        assertEquals("0.0001", PresenceEvolutions.constantText(1e-4))
        assertEquals("1", PresenceEvolutions.constantText(1.0))
        assertEquals("0", PresenceEvolutions.constantText(0.0))
        assertEquals(1e-4, TimeFormula.parse(PresenceEvolutions.constantText(1e-4), utc)!!.at(0L)!!, 1e-15)
    }

    @Test
    fun periods_are_a_formula_and_read_back() {
        val periods = listOf(Period("1970-01-11", "1970-01-21", "0.5"), Period("1970-01-21", "", "ramp(20, 30, 0.5, 0.2)"))
        val text = TimeFormula.FormulaPeriods.build(periods)
        assertEquals(periods, TimeFormula.FormulaPeriods.read(text))
        val f = TimeFormula.parse(text, utc)!!
        assertNull(f.at(5 * DAY))
        assertEquals(0.5, f.at(15 * DAY))
        assertEquals(0.35, f.at(25 * DAY)!!, 1e-12)
    }

    @Test
    fun a_ratio_of_the_parent_and_the_free_siblings_share_the_rest() {
        val s0 = account()
        val p = share(s0, "P")
        val s = with(s0, evo("e1", listOf(id(s0, "A")), "0.5"))
        assertEquals(p * 0.5, share(s, "A"), 1e-12)
        assertEquals(p * 0.25, share(s, "B"), 1e-12)
        assertEquals(p * 0.25, share(s, "C"), 1e-12)
        assertEquals(p, share(s, "P"), 1e-12, "the parent keeps its share")
        assertTrue(FormulaCoherence.problems(s, utc).isEmpty())
    }

    @Test
    fun relative_to_an_ancestor_further_up_by_task_or_by_parent_distance() {
        val s0 = account()
        val tree = org.example.project.scheduler.model.WellKnownIds.ROOT_TASK
        val byTask = with(s0, evo("e1", listOf(id(s0, "A")), "0.6", relative = tree))
        val byDistance = with(s0, evo("e1", listOf(id(s0, "A")), "0.6", distance = 2))
        val whole = 1.0
        assertEquals(whole * 0.6, share(byTask, "A"), 1e-12)
        assertEquals(share(byTask, "A"), share(byDistance, "A"), 1e-12)
        // The rest of the tree's share goes to what is free under it, by the weights: Q, B and C.
        assertEquals(whole * 0.4, share(byTask, "Q") + share(byTask, "B") + share(byTask, "C"), 1e-12)
    }

    @Test
    fun outside_its_periods_the_weights_decide() {
        val s0 = account()
        val s = with(s0, evo("e1", listOf(id(s0, "A")), "0.6", periods = listOf(EvolutionPeriod(10 * DAY, 20 * DAY))))
        assertEquals(share(s0, "A", 5 * DAY), share(s, "A", 5 * DAY), 1e-12)
        assertEquals(share(s0, "P", 15 * DAY) * 0.6, share(s, "A", 15 * DAY), 1e-12)
        assertEquals(listOf(10 * DAY, 20 * DAY), SchedulerDomain.formulaBreakpoints(s))
    }

    @Test
    fun over_100_percent_at_some_point_is_incoherent_and_both_schedulers_do_nothing() {
        val s0 = account()
        val s = with(s0, evo("e1", listOf(id(s0, "A")), "step(10, 0.3, 0.7)"), evo("e2", listOf(id(s0, "B")), "0.4"))
        val problems = FormulaCoherence.problems(s, utc)
        assertEquals(1, problems.size, problems.toString())
        assertTrue("110" in problems.single().message && "1970-01-11" in problems.single().message, problems.single().message)
        assertTrue(SchedulerDomain.fillSchedule(s, 0L, utc, horizonMillis = DAY).none { it.auto })
        assertTrue(SchedulerDomain.fillSchedule(s, 0L, utc, horizonMillis = DAY, instant = true).none { it.auto })
        assertFalse(SchedulerDomain.firstSecondsGapFillable(s, 0L))

        val fixed = SchedulerReducer.reduce(s, SchedulerIntent.SetPresenceEvolutions(s.presenceEvolutions.map { if (it.id == "e2") it.copy(formula = "0.3") else it }))
        assertTrue(FormulaCoherence.problems(fixed, utc).isEmpty())
        assertTrue(SchedulerDomain.fillSchedule(fixed, 0L, utc, horizonMillis = DAY).any { it.auto })
    }

    @Test
    fun the_other_incoherences() {
        val s0 = account()
        val (a, b, c, q) = listOf("A", "B", "C", "Q").map { id(s0, it) }
        fun said(vararg es: PresenceEvolution) = FormulaCoherence.problems(with(s0, *es), utc).joinToString(" | ") { it.message }
        assertTrue("no other task" in said(evo("e1", listOf(a, b, c), "0.3")))
        assertTrue("between 0 and 100" in said(evo("e1", listOf(a), "1.5")))
        assertTrue("is not above" in said(evo("e1", listOf(a), "0.2", relative = q)))
        assertTrue("levels above" in said(evo("e1", listOf(a), "0.2", distance = 9)))
        assertTrue("Two evolutions" in said(evo("e1", listOf(a), "0.2"), evo("e2", listOf(a), "0.3")))
        assertTrue("does not read" in said(evo("e1", listOf(a), "ramp(1")))
    }

    @Test
    fun the_rows_read_the_tree_and_the_categories_are_links() {
        val s0 = account()
        val (a, b) = listOf("A", "B").map { id(s0, it) }
        val above = PresenceEvolutions.commonAncestors(s0, listOf(a, b))
        assertTrue(id(s0, "P") in above && id(s0, "Q") !in above)
        val s = with(s0, evo("e1", listOf(a), "0.3"), evo("e2", listOf(id(s0, "Q")), "0.3", relative = null))
        // A's stored evolution, then its default (user rule 2026-11-20); not Q's.
        assertEquals(listOf("e1", PresenceEvolutions.defaultIdOf(a)), PresenceEvolutions.involving(s, listOf(a)).map { it.id })
        assertEquals(setOf("e1", PresenceEvolutions.defaultIdOf(a)), SearchDomain.itemResults(s, SearchDomain.Kind.PresenceEvolution, "A:").map { it.id }.toSet())
        // A category is carried as any element carries one.
        var withCategory = SchedulerReducer.reduce(s, SchedulerIntent.CreateCategory("Health"))
        val category = ElementTree.categoryKey(withCategory.categories.single { it.title == "Health" }.id)
        val key = SearchDomain.Kind.PresenceEvolution.name + "/e1"
        ElementTree.parentIntents(withCategory, listOf(key), category, on = true).forEach { withCategory = SchedulerReducer.reduce(withCategory, it) }
        assertTrue(category in ElementTree.parentsOf(withCategory, key))
    }

    @Test
    fun an_evolution_is_a_rule_one_undo_and_survives_the_store_and_older_payloads() {
        val s0 = account()
        val e = evo("e1", listOf(id(s0, "A")), "ramp(10, 20, 0.2, 0.5)", periods = listOf(EvolutionPeriod(null, 30 * DAY)))
        val s = with(s0, e)
        assertNotEquals(SchedulerDomain.schedulingSignature(s0), SchedulerDomain.schedulingSignature(s))
        assertEquals(emptyList(), SchedulerReducer.reduce(s, SchedulerIntent.Undo).presenceEvolutions)
        assertEquals(listOf(e), SchedulerStateCodec.decode(SchedulerStateCodec.encode(s))!!.presenceEvolutions)
        // Persisted-DB compatibility: a payload before the field has none…
        val older = SchedulerStateCodec.encode(s0)
        assertFalse("presenceEvolutions" in older)
        assertTrue(SchedulerStateCodec.decode(older)!!.presenceEvolutions.isEmpty())
        // …and one the morning build wrote, a presence formula on a cell, is lifted into an evolution relative to the parent.
        val cell = s0.cells.values.first { it.taskId == id(s0, "A") }
        val morning = SchedulerStateCodec.encode(s0.copy(cells = s0.cells + (cell.id to cell.copy(presenceFormula = "0.5"))))
        val lifted = SchedulerStateCodec.decode(morning)!!
        assertEquals(listOf(PresenceEvolution("presence-cell-" + cell.id.value, listOf(id(s0, "A")), id(s0, "P"), formula = "0.5")), lifted.presenceEvolutions)
        assertTrue(lifted.cells.values.all { it.presenceFormula.isEmpty() })
    }
}
