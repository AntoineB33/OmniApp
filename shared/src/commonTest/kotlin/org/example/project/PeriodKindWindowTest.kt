package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.PeriodKinds
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.state.HistoryCategory
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState

/**
 * `side-dev/README.md` § *Restrictive Period*: the **period edit window** — one KIND of restrictive period,
 * and every task's resilience to it.
 *
 * The window itself is Compose, so what is pinned here is everything it is a readout of: which tasks it
 * lists ([SchedulerDomain.periodKindTaskRows]), what its bulk field shows for a selection
 * ([SchedulerDomain.commonResilience]), and what its writes do
 * ([SchedulerIntent.SetPeriodResilience] — one gesture, one history unit).
 */
class PeriodKindWindowTest {

    /** Three sibling tasks, "Alpha" / "Beta" / "Gamma", in the root list. */
    private fun stateWithThreeTasks(): Triple<SchedulerState, List<TaskId>, String> {
        var s = SchedulerState.empty()
        // Naming the last cell of a list appends a fresh empty one, so the row to fill is read each time.
        for (title in listOf("Gamma", "Alpha", "Beta")) {
            val cell = s.lists[s.rootListId]!!.cellIds.first { s.cells[it]?.taskId == null }
            s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(cell, title))
        }
        val ids = listOf("Alpha", "Beta", "Gamma").map { title ->
            s.tasks.keys.first { s.tasks[it]!!.title == title }
        }
        s = SchedulerReducer.reduce(s, SchedulerIntent.AddPeriodKind("deep focus"))
        return Triple(s, ids, "deep focus")
    }

    // ----- which tasks the window lists ---------------------------------------------------------

    @Test
    fun the_window_lists_every_task_at_its_value_for_the_kind_ordered_by_title() {
        val (s0, ids, kind) = stateWithThreeTasks()
        val (alpha, beta, _) = Triple(ids[0], ids[1], ids[2])
        val s = SchedulerReducer.reduce(s0, SchedulerIntent.SetTaskResilience(beta, kind, 0.5))
        val rows = SchedulerDomain.periodKindTaskRows(s, kind)
        assertEquals(listOf("Alpha", "Beta", "Gamma"), rows.map { it.title })
        // A task never told about the kind is at that kind's default — 0 for one the user defined.
        assertEquals(0.0, rows.first { it.taskId == alpha }.resilience)
        assertEquals(0.5, rows.first { it.taskId == beta }.resilience)
    }

    @Test
    fun the_window_lists_the_schedulable_leaves_only() {
        // A resilience says where a task may be PLACED, and a parent task is a grouping the scheduler never
        // places (which is why the task edit window shows it no resilience section either). Offering it a
        // value would be offering to write a number nothing reads.
        val (s0, ids, kind) = stateWithThreeTasks()
        val (alpha, beta, _) = Triple(ids[0], ids[1], ids[2])
        val s = s0.copy(tasks = s0.tasks + (alpha to s0.tasks[alpha]!!.copy(childTaskIds = listOf(beta))))
        assertFalse(SchedulerDomain.isLeafTask(s, alpha))
        assertEquals(listOf("Beta", "Gamma"), SchedulerDomain.periodKindTaskRows(s, kind).map { it.title })
    }

    @Test
    fun the_window_reads_the_built_in_kinds_too() {
        // "on screen" is not a flag: it is a 0 against "no on-screen task", so the window for that kind is
        // exactly the list of which tasks need a screen.
        val (s0, ids, _) = stateWithThreeTasks()
        val rows = SchedulerDomain.periodKindTaskRows(s0, PeriodKinds.NO_SCREEN)
        assertTrue(rows.all { it.resilience == 0.0 }, "a new task is on screen")
        val s = SchedulerReducer.reduce(s0, SchedulerIntent.SetTaskResilience(ids[0], PeriodKinds.NO_SCREEN, 1.0))
        val after = SchedulerDomain.periodKindTaskRows(s, PeriodKinds.NO_SCREEN)
        assertEquals(1.0, after.first { it.taskId == ids[0] }.resilience)
    }

    // ----- the bulk field's value ---------------------------------------------------------------

    @Test
    fun the_bulk_field_shows_the_value_the_selection_shares() {
        val (s0, ids, kind) = stateWithThreeTasks()
        val s = SchedulerReducer.reduce(
            s0,
            SchedulerIntent.SetPeriodResilience(listOf(ids[0], ids[1]), kind, 0.25),
        )
        val rows = SchedulerDomain.periodKindTaskRows(s, kind)
        assertEquals(0.25, SchedulerDomain.commonResilience(rows, setOf(ids[0], ids[1])))
    }

    @Test
    fun the_bulk_field_is_blank_when_the_selection_disagrees() {
        // `null` is the window's blank field, and it is a real answer rather than a missing one: the field
        // stays empty until the user types the value that ends the disagreement.
        val (s0, ids, kind) = stateWithThreeTasks()
        val s = SchedulerReducer.reduce(s0, SchedulerIntent.SetTaskResilience(ids[0], kind, 0.25))
        val rows = SchedulerDomain.periodKindTaskRows(s, kind)
        assertNull(SchedulerDomain.commonResilience(rows, setOf(ids[0], ids[1])))
        // …and with nothing selected there is no field at all, which is the same answer by another route.
        assertNull(SchedulerDomain.commonResilience(rows, emptySet()))
        // One task selected always agrees with itself.
        assertEquals(0.25, SchedulerDomain.commonResilience(rows, setOf(ids[0])))
    }

    // ----- the window's write -------------------------------------------------------------------

    @Test
    fun one_bulk_write_is_one_history_unit_however_many_tasks_it_moves() {
        // Checking a block of tasks and typing one percentage is ONE gesture, so it is one Undo/Redo unit.
        val (s0, ids, kind) = stateWithThreeTasks()
        val before = s0.histories.forCategory(HistoryCategory.Main).units.size
        val s = SchedulerReducer.reduce(s0, SchedulerIntent.SetPeriodResilience(ids, kind, 0.5))
        assertEquals(1, s.histories.forCategory(HistoryCategory.Main).units.size - before)
        assertTrue(ids.all { s.tasks[it]!!.resilienceFor(kind) == 0.5 })
        val undone = SchedulerReducer.reduce(s, SchedulerIntent.Undo)
        assertTrue(undone.tasks.values.none { kind in it.resilience }, "one Ctrl+Z puts all of them back")
        val redone = SchedulerReducer.reduce(undone, SchedulerIntent.Redo)
        assertTrue(ids.all { redone.tasks[it]!!.resilienceFor(kind) == 0.5 })
    }

    @Test
    fun a_bulk_write_that_moves_nobody_records_no_history_unit() {
        val (s0, ids, kind) = stateWithThreeTasks()
        // Every task is already at this kind's default of 0.
        val s = SchedulerReducer.reduce(s0, SchedulerIntent.SetPeriodResilience(ids, kind, 0.0))
        assertEquals(s0.histories, s.histories)
    }

    @Test
    fun a_bulk_write_keeps_the_overrides_only_rule_and_the_clamp() {
        val (s0, ids, kind) = stateWithThreeTasks()
        var s = SchedulerReducer.reduce(s0, SchedulerIntent.SetPeriodResilience(ids, kind, 4.0))
        assertTrue(ids.all { s.tasks[it]!!.resilienceFor(kind) == 1.0 }, "healed to the nearest bound")
        // Back to the kind's own default ⇒ the override is dropped, not stored as a zero.
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetPeriodResilience(ids, kind, 0.0))
        assertTrue(ids.all { kind !in s.tasks[it]!!.resilience })
        assertTrue(ids.all { s.tasks[it]!!.resilienceFor(kind) == 0.0 })
    }

    @Test
    fun the_rows_own_field_is_the_same_write_with_a_one_element_list() {
        // One write path, not two — the row's percentage and the bulk field raise the same intent.
        val (s0, ids, kind) = stateWithThreeTasks()
        val s = SchedulerReducer.reduce(s0, SchedulerIntent.SetPeriodResilience(listOf(ids[1]), kind, 0.75))
        assertEquals(0.75, s.tasks[ids[1]]!!.resilienceFor(kind))
        assertEquals(0.0, s.tasks[ids[0]]!!.resilienceFor(kind))
    }

    // ----- deleting the period ------------------------------------------------------------------

    @Test
    fun the_windows_delete_takes_the_kind_and_every_value_for_it() {
        // The window's Delete is [SchedulerIntent.RemovePeriodKind] — the one place a period is deleted,
        // because it is the one place a period is an object in its own right.
        val (s0, ids, kind) = stateWithThreeTasks()
        var s = SchedulerReducer.reduce(s0, SchedulerIntent.SetPeriodResilience(ids, kind, 0.5))
        s = SchedulerReducer.reduce(s, SchedulerIntent.RemovePeriodKind(kind))
        assertEquals(emptyList(), s.periodKinds)
        assertTrue(ids.all { kind !in s.tasks[it]!!.resilience })
    }
}
