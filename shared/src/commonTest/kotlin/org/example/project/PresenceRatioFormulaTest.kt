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
import org.example.project.scheduler.domain.EvolutionTarget
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
     * User rule 2026-11-20: ONE default evolution — every task under the first tree's root, relative to that root, always,
     * its formula `priority` (each task's priority in the tree, by the weights). Derived, never stored; stored by an edit,
     * it changes no share and is coherent, the tasks inside others included; a formula changed there takes effect.
     */
    @Test
    fun one_default_evolution_gives_every_task_of_the_first_tree_its_priority_in_it() {
        var s = account()
        // A deeper level: "A1" and "A2" under "A".
        for (title in listOf("A1", "A2")) {
            val a = s.tasks.values.first { it.title == "A" }
            s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(s.lists[a.childListId!!]!!.cellIds.last(), title))
        }
        val p = id(s, "P")
        val only = PresenceEvolutions.shown(s).single()
        assertEquals(PresenceEvolutions.DEFAULT_ID, only.id)
        assertEquals(setOf("A", "B", "C", "A1", "A2"), only.taskIds.map { s.tasks.getValue(it).title }.toSet(), "the first tree's tasks, not Q")
        assertEquals(p, only.relativeTask, "relative to the first tree's root")
        assertEquals("priority", only.formula)
        assertTrue(only.periods.isEmpty(), "the whole timeline")
        assertTrue(s.presenceEvolutions.isEmpty(), "derived, never stored")
        assertTrue(PresenceEvolutions.isDefault(only, s))
        assertEquals(listOf(only.id), PresenceEvolutions.involving(s, listOf(id(s, "A1"))).map { it.id })
        assertEquals(1, SearchDomain.results(s, setOf(SearchDomain.Kind.PresenceEvolution), "").size, "one row")

        // Stored (an edit writes it under its id): every share as the weights give it, and coherent.
        val before = listOf("A", "B", "C", "A1", "A2", "P", "Q").associateWith { share(s, it) }
        val stored = with(s, only)
        assertTrue(PresenceEvolutions.defaults(stored).none { it.id == only.id }, "the stored one holds in its place")
        for ((title, v) in before) assertEquals(v, share(stored, title), 1e-9, "$title unchanged")
        assertTrue(FormulaCoherence.problems(stored, utc).isEmpty(), "coherent: ${FormulaCoherence.problems(stored, utc)}")

        // Its formula changed, it takes effect: twice every priority asks more than P has.
        val doubled = SchedulerReducer.reduce(stored, SchedulerIntent.SetPresenceEvolutions(listOf(only.copy(formula = "2 * priority"))))
        assertTrue(FormulaCoherence.problems(doubled, utc).isNotEmpty(), "twice every priority is over 100 % of P")
    }

    /** User rule 2026-11-20: "Add a delete button" — a stored evolution goes; a default one is marked gone, and stays gone. */
    @Test
    fun an_evolution_is_deleted_and_a_default_one_stays_deleted() {
        val s0 = account()
        val s = with(s0, evo("e1", listOf(id(s0, "A")), "0.3"))
        val withoutE1 = SchedulerReducer.reduce(s, SchedulerIntent.SetPresenceEvolutions(PresenceEvolutions.deleted(s, listOf("e1"))))
        assertTrue(withoutE1.presenceEvolutions.isEmpty())
        val noDefault = SchedulerReducer.reduce(s0, SchedulerIntent.SetPresenceEvolutions(PresenceEvolutions.deleted(s0, listOf(PresenceEvolutions.DEFAULT_ID))))
        assertTrue(PresenceEvolutions.shown(noDefault).isEmpty(), "the default presence evolution is gone from the list")
        assertEquals(1, PresenceEvolutions.shown(noDefault, EvolutionTarget.Minimum).size, "the minimum one stays")
        assertTrue(FormulaCoherence.problems(noDefault, utc).isEmpty())
        for (title in listOf("A", "B", "C", "Q")) assertEquals(share(s0, title), share(noDefault, title), 1e-12, "$title unchanged")
        // Survives the store.
        assertTrue(PresenceEvolutions.shown(SchedulerStateCodec.decode(SchedulerStateCodec.encode(noDefault))!!).isEmpty())
        // One undo brings it back.
        assertEquals(1, PresenceEvolutions.shown(SchedulerReducer.reduce(noDefault, SchedulerIntent.Undo)).size)
    }

    /**
     * User rule 2026-11-20: *"The minimum time target evolution action should be the same thing"* — minimum length target
     * evolutions: one default (every task of the first tree, `minimum`, changing nothing), stored ones read by the
     * scheduler, two at once on a task incoherent; an older build's minimum formula lifted into one.
     */
    @Test
    fun the_minimum_length_target_is_evolutions_too() {
        val s0 = account()
        val a = id(s0, "A")
        val minimumDefault = PresenceEvolutions.shown(s0, EvolutionTarget.Minimum).single()
        assertEquals(PresenceEvolutions.MINIMUM_DEFAULT_ID, minimumDefault.id)
        assertEquals("minimum", minimumDefault.formula)
        assertEquals(setOf("A", "B", "C"), minimumDefault.taskIds.map { s0.tasks.getValue(it).title }.toSet())
        assertEquals(1, PresenceEvolutions.shown(s0).size, "the presence list holds the presence default alone")
        val taskA = s0.tasks.getValue(a)
        // Stored as it is: no change.
        val stored = with(s0, minimumDefault)
        assertEquals(taskA.minimumMinutes.toDouble(), SchedulerDomain.minimumMinutesAt(stored, taskA, 0L))
        assertTrue(FormulaCoherence.problems(stored, utc).isEmpty())
        // A stored one of A: what the scheduler reads.
        val doubled = with(s0, PresenceEvolution("m1", listOf(a), formula = "minimum * 2", target = EvolutionTarget.Minimum))
        assertEquals(taskA.minimumMinutes * 2.0, SchedulerDomain.minimumMinutesAt(doubled, taskA, 0L))
        assertEquals(listOf("m1", PresenceEvolutions.MINIMUM_DEFAULT_ID), PresenceEvolutions.involving(doubled, listOf(a), EvolutionTarget.Minimum).map { it.id })
        assertTrue(FormulaCoherence.problems(doubled, utc).isEmpty())
        // Two at once on A: incoherent.
        val twice = with(doubled, PresenceEvolution("m2", listOf(a), formula = "30", target = EvolutionTarget.Minimum))
        assertTrue(FormulaCoherence.problems(twice, utc).any { "minimum length target" in it.message }, "${FormulaCoherence.problems(twice, utc)}")
        // Persisted-DB compatibility: an older build's minimum formula on a task is lifted into an evolution of it…
        val older = SchedulerStateCodec.encode(s0.copy(tasks = s0.tasks + (a to taskA.copy(minimumFormula = "ramp(10, 20, 30, 60)"))))
        val lifted = SchedulerStateCodec.decode(older)!!
        val e = lifted.presenceEvolutions.single()
        assertEquals(EvolutionTarget.Minimum, e.target)
        assertEquals(listOf(a), e.taskIds)
        assertEquals("", lifted.tasks.getValue(a).minimumFormula)
        assertEquals(45.0, SchedulerDomain.minimumMinutesAt(lifted, lifted.tasks.getValue(a), 15 * DAY))
        // …and an evolution written before the target existed is a presence ratio target's.
        val presence = SchedulerStateCodec.encode(with(s0, evo("e1", listOf(a), "0.3")))
        val withoutTarget = presence.replace(",\"target\":\"Presence\"", "")
        assertFalse("\"target\"" in withoutTarget)
        assertEquals(EvolutionTarget.Presence, SchedulerStateCodec.decode(withoutTarget)!!.presenceEvolutions.single().target)
    }

    @Test
    fun priority_is_read_in_a_presence_formula_alone() {
        assertNull(TimeFormula.problem("priority * 2", utc, target = EvolutionTarget.Presence))
        assertEquals(0.5, TimeFormula.parse("priority * 2", utc, target = EvolutionTarget.Presence)!!.at(0L, 0.25)!!, 1e-12)
        assertTrue(TimeFormula.parse("priority", utc, target = EvolutionTarget.Presence)!!.readsTaskValue)
        assertTrue(TimeFormula.problem("priority", utc) != null, "not in a weight formula")
        assertTrue(TimeFormula.problem("priority", utc, target = EvolutionTarget.Minimum) != null, "not in a minimum formula")
        assertNull(TimeFormula.problem("minimum + 10", utc, target = EvolutionTarget.Minimum))
        assertTrue(TimeFormula.problem("minimum", utc, target = EvolutionTarget.Presence) != null, "not in a presence formula")
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
        // The default evolution is relative to P, the first tree's root: P is involved in it too.
        assertEquals(setOf("byDistance", "byTask", PresenceEvolutions.DEFAULT_ID), involved)
        // A sort of the window, and the tasks it reads, both configurations its ▾ lists in "Currently on".
        val config = SearchDomain.evolutionsFieldConfig(listOf(id(s, "P")))
        val on = SearchDomain.activeSettings(config)
        // Anomaly 2026-11-20: the evolutions' own "Sort by" — where "involvement" is — not "Sort results" (every row's).
        assertTrue(SearchDomain.Setting.PresenceEvolutionSort in on, "the sort is on: $on")
        assertTrue(SearchDomain.Setting.SortResults !in on, "no sort of every row: $on")
        assertTrue(SearchDomain.Setting.InvolvementOf in on, "its tasks are on: $on")
        // …the ▾'s "Currently on", as its tree holds it.
        val currently = SearchDomain.configurationTree(config, config).filterIsInstance<SearchDomain.ConfigurationCategory>().single { it.label == "Currently on" }
        val listed = currently.children.filterIsInstance<SearchDomain.ConfigurationLeaf>().map { it.setting }
        assertTrue(SearchDomain.Setting.PresenceEvolutionSort in listed && SearchDomain.Setting.InvolvementOf in listed, "Currently on: $listed")
        assertTrue("Relevance:" in SearchDomain.settingDescription(SearchDomain.Setting.PresenceEvolutionSort))
        assertTrue("Involvement:" in SearchDomain.settingDescription(SearchDomain.Setting.PresenceEvolutionSort))
        val all = SearchDomain.results(s, config.kinds, "")
        val led = SearchDomain.results(s, config.kinds, "", filters = config.filters, sorts = config.sorts).map { SearchDomain.keyOf(it) }
        val key = { e: String -> SearchDomain.Kind.PresenceEvolution.name + "/" + e }
        assertEquals(key("own"), led.first())
        assertEquals(setOf(key("byDistance"), key("byTask"), key(PresenceEvolutions.DEFAULT_ID)), led.subList(1, 4).toSet())
        assertEquals(all.size, led.size, "every evolution still listed")
        assertTrue(led.indexOf(key("rest")) > 3)
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
        assertEquals(listOf("e1", PresenceEvolutions.DEFAULT_ID), PresenceEvolutions.involving(s, listOf(a)).map { it.id })
        // The one default is named after the tree ("every task of “P”: …"): "A:" finds A's own evolution.
        assertEquals(setOf("e1"), SearchDomain.itemResults(s, SearchDomain.Kind.PresenceEvolution, "A:").map { it.id }.toSet())
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
