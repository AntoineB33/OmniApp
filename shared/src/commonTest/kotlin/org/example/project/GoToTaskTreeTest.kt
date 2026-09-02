package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.CellId
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.state.CellEditMode
import org.example.project.scheduler.state.EditExitNavigation
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

    /**
     * PRD §4: Edit Mode's **Tasks** (id) menu offers a right-click "go to task", greyed when the row's task
     * is nowhere in the tree — and that case is REACHABLE, which is the whole reason the entry is greyed
     * rather than dropped. A **detached parent** is the everyday one: a titled task whose last cell was
     * re-pointed elsewhere. It is still an ordinary id row (that is how its sub-tree is brought back), and
     * it has no cell to go to.
     */
    @Test
    fun an_id_row_the_menu_offers_can_still_have_nowhere_to_go() {
        var s = SchedulerState.empty()
        val parentCell = s.lists[s.rootListId]!!.cellIds.first()
        s = r(s, SchedulerIntent.SetCellTitle(parentCell, "abilities"))
        val abilities = s.cells[parentCell]!!.taskId!!
        s = r(s, SchedulerIntent.ToggleExpand(parentCell))
        val subListId = s.tasks[abilities]!!.childListId!!
        s = r(s, SchedulerIntent.SetCellTitle(s.lists[subListId]!!.cellIds.first(), "drawing"))
        // Re-point the cell at a brand-new task: "abilities" keeps its sub-tree but loses its last cell.
        s = r(s, SchedulerIntent.BeginEdit(parentCell))
        s = r(s, SchedulerIntent.SetEditMode(CellEditMode.ChangeTask))
        s = r(s, SchedulerIntent.SelectCreateAssignTask)
        s = r(s, SchedulerIntent.ExitEdit(EditExitNavigation.Stay))

        // The menu still offers it — the row is real, and picking it is how the sub-tree comes back...
        assertTrue(
            SchedulerDomain.changeTaskMenuEntries(s, parentCell, "abilities").any { it.taskId == abilities },
        )
        // ...but there is no cell showing it, so "go to task" has no destination and is drawn greyed.
        assertNull(SchedulerDomain.firstTaskOccurrence(s, abilities))
    }

    /**
     * PRD §4 *Presentation*: a menu row is named by the task's **shortest path in the tree**, and the path
     * is walked over the CELLS, not over the denormalized `Task.childTaskIds`. That field only tracks
     * freshly-typed children, so a task that arrived any other way — moved, pasted, or assigned to a second
     * cell — used to fall back to naming itself, and a menu of same-titled tasks became a column of
     * identical rows (64 of them, all "planning", on the release account).
     */
    @Test
    fun a_menu_row_is_named_by_its_path_through_the_cells() {
        var s = tree()
        val pieTask = taskWithTitle(s, "Pie")
        // Mirror "Pie" under "Banana" by ASSIGNING the id — the route that never touches childTaskIds.
        val bananaCell = cellWithTitle(s, "Banana")
        s = r(s, SchedulerIntent.ToggleExpand(bananaCell))
        val bananaList = s.tasks[s.cells[bananaCell]!!.taskId!!]!!.childListId!!
        s = r(s, SchedulerIntent.AssignTaskId(s.lists[bananaList]!!.cellIds.first(), pieTask))

        // Both parents hold it; the shortest path is the one the BFS reaches first, and it is a real path.
        assertEquals("root / main / Apple / Pie", SchedulerDomain.taskPathLabel(s, pieTask))
        assertEquals("root / main / Apple", SchedulerDomain.taskPathLabel(s, taskWithTitle(s, "Apple")))
    }

    /**
     * PRD §4 *Sorting*: a task the tree does not hold has no path to be short, so it goes LAST — never
     * first, which is what a nominal length of 1 did. Those are exactly the rows whose "go to task" is
     * greyed, so leading with them put the one unusable answer under the user's cursor.
     */
    @Test
    fun rows_the_tree_does_not_hold_sort_after_the_ones_it_does() {
        var s = tree()
        // Detach "Apple" (it keeps its sub-list, so it survives cell-less) and give a root cell its title.
        val appleCell = cellWithTitle(s, "Apple")
        s = r(s, SchedulerIntent.BeginEdit(appleCell))
        s = r(s, SchedulerIntent.SetEditMode(CellEditMode.ChangeTask))
        s = r(s, SchedulerIntent.SelectCreateAssignTask)
        s = r(s, SchedulerIntent.ExitEdit(EditExitNavigation.Stay))

        // Ask from a cell OUTSIDE the root list, or the live "Apple" would be filtered out as a sibling.
        val bananaCell = cellWithTitle(s, "Banana")
        s = r(s, SchedulerIntent.ToggleExpand(bananaCell))
        val target = freeChildCell(s, bananaCell)
        val rows = SchedulerDomain.changeTaskMenuEntries(s, target, "Apple").drop(1) // past "New task"
        val occurrences = SchedulerDomain.firstTaskOccurrences(s)
        val inTree = rows.map { it.taskId != null && occurrences[it.taskId!!] != null }
        assertTrue(rows.size >= 2, "both the live Apple and the detached one must be offered: $rows")
        // Every in-tree row precedes every out-of-tree one.
        assertEquals(inTree.sortedByDescending { it }, inTree, "not-in-the-tree rows must come last: $rows")
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
