package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.CellId
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState

/**
 * PRD §8 **"go to task tree"** — the calendar task panel's jump back to the cell the task lives in.
 *
 * What is pinned here is the lookup and what the reveal then does with it: the FIRST occurrence is the
 * tree's own reading order (not "some cell holding the task"), a mirrored task answers with the path that
 * reaches its first row, a task no cell holds answers `null` (which is what raises the error message), and
 * the pair feeds [SchedulerIntent.RevealCell] — the find bar's primitive — which expands the way in and
 * selects the row.
 */
class GoToTaskTreeTest {

    private fun r(state: SchedulerState, intent: SchedulerIntent) = SchedulerReducer.reduce(state, intent)

    /** The trailing empty placeholder of the root list. */
    private fun freeRootCell(state: SchedulerState): CellId =
        state.lists[state.rootListId]!!.cellIds.last()

    /** The trailing empty placeholder under [cellId]. */
    private fun freeChildCell(state: SchedulerState, cellId: CellId): CellId {
        val taskId = state.cells[cellId]!!.taskId!!
        return state.lists[state.tasks[taskId]!!.childListId!!]!!.cellIds.last()
    }

    private fun cellWithTitle(state: SchedulerState, title: String): CellId =
        state.cells.values.first { cell ->
            cell.taskId?.let { state.tasks[it]?.title } == title
        }.id

    private fun taskWithTitle(state: SchedulerState, title: String): TaskId =
        state.tasks.values.first { it.title == title }.id

    /**
     * ```
     * Apple            <- a
     *   Pie            <- deep, under Apple
     * Banana
     * ```
     * Nothing is expanded: the lookup must see the whole tree, exactly as the find bar's walk does.
     */
    private fun tree(): SchedulerState {
        var s = SchedulerState.empty()
        val a = freeRootCell(s)
        s = r(s, SchedulerIntent.SetCellTitle(a, "Apple"))
        s = r(s, SchedulerIntent.SetCellTitle(freeChildCell(s, a), "Pie"))
        s = r(s, SchedulerIntent.SetCellTitle(freeRootCell(s), "Banana"))
        return s.copy(expanded = emptySet())
    }

    @Test
    fun first_occurrence_is_depth_first_so_a_collapsed_child_comes_before_a_later_root_row() {
        val s = tree()
        val pie = SchedulerDomain.firstTaskOccurrence(s, taskWithTitle(s, "Pie"))
        assertEquals(cellWithTitle(s, "Pie"), pie?.cellId)
        // The row is reached THROUGH Apple, and that chain is what the reveal expands.
        assertEquals(listOf(cellWithTitle(s, "Apple")), pie?.ancestors)

        val apple = SchedulerDomain.firstTaskOccurrence(s, taskWithTitle(s, "Apple"))
        assertEquals(cellWithTitle(s, "Apple"), apple?.cellId)
        assertEquals(emptyList(), apple?.ancestors)
    }

    @Test
    fun a_mirrored_task_answers_with_its_first_row_not_the_last_cell_pointing_at_it() {
        var s = tree()
        val pieTask = taskWithTitle(s, "Pie")
        val firstCell = cellWithTitle(s, "Pie")
        // A second cell on the same task, under Banana: the same sub-list under another parent.
        val mirror = freeChildCell(s, cellWithTitle(s, "Banana"))
        s = r(s, SchedulerIntent.AssignTaskId(mirror, pieTask))
        assertTrue(s.cells[mirror]?.taskId == pieTask)

        val found = SchedulerDomain.firstTaskOccurrence(s, pieTask)
        assertEquals(firstCell, found?.cellId)
        assertEquals(listOf(cellWithTitle(s, "Apple")), found?.ancestors)
    }

    @Test
    fun a_task_no_cell_holds_is_not_in_the_tree() {
        var s = tree()
        val pieTask = taskWithTitle(s, "Pie")
        // PRD §4 deletion: the blank title is what deletes. The task object survives (a panel may still
        // name it), but no cell shows it any more — which is the case the error message exists for.
        s = r(s, SchedulerIntent.SetCellTitle(cellWithTitle(s, "Pie"), ""))
        assertNull(SchedulerDomain.firstTaskOccurrence(s, pieTask))
        // A task id the account never minted is the same answer, not a crash.
        assertNull(SchedulerDomain.firstTaskOccurrence(s, TaskId("task/user/9999")))
    }

    @Test
    fun revealing_the_occurrence_expands_the_way_in_and_selects_the_row() {
        val s = tree()
        val appleCell = cellWithTitle(s, "Apple")
        val found = SchedulerDomain.firstTaskOccurrence(s, taskWithTitle(s, "Pie"))!!
        assertTrue(appleCell !in s.expanded)

        val after = r(s, SchedulerIntent.RevealCell(found.cellId, found.ancestors))
        assertTrue(appleCell in after.expanded)
        assertEquals(found.cellId, after.selection.main)
        // The row highlights in the occurrence the jump navigated to.
        assertEquals(appleCell, after.selection.renderVia)
    }
}
