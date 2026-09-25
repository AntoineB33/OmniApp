package org.example.project

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.CellId
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.model.WellKnownIds
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState
import org.example.project.scheduler.state.projectDefaultSubtree

/**
 * PRD §2: the tree is drawn under ONE inert row standing for the whole of it — a real cell of the tree
 * ([WellKnownIds.ROOT_CELL]) pointing at [WellKnownIds.ROOT_TASK], sitting one level above the tree's
 * top-level list. It is a real cell and not a synthetic header so that one thing draws a task row, one
 * thing decides the visible order, and the expansion set answers for it as it does for every other parent.
 *
 * Being a real cell is exactly what makes it dangerous: every walk that climbs to the top of the tree now
 * finds one more level whose sub-tree is *the whole tree*. The tests below pin the three answers that must
 * NOT change because of it.
 */
class RootCellTest {

    /** "Book" (with a child "Chapter") and "Notes" at the top level. */
    private fun tree(): Pair<SchedulerState, TaskId> {
        var s = SchedulerState.empty()
        val bookCell = s.lists[s.rootListId]!!.cellIds[0]
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(bookCell, "Book"))
        val notesCell = s.lists[s.rootListId]!!.cellIds[1]
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(notesCell, "Notes"))
        val book = s.cells[bookCell]!!.taskId!!
        val bookList = s.tasks[book]!!.childListId!!
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(s.lists[bookList]!!.cellIds[0], "Chapter"))
        return s to book
    }

    // ----- the row itself -------------------------------------------------------------------------

    @Test
    fun the_tree_is_drawn_under_the_root_row() {
        val (s, _) = tree()
        val order = SchedulerDomain.visibleCellOrder(s)
        assertEquals(WellKnownIds.ROOT_CELL, order.first(), "the root row is drawn first")
        assertTrue(order.size > 1, "and the tree under it")
    }

    /** PRD §3: it cannot be selected, edited or reached with the keyboard. */
    @Test
    fun the_root_row_is_never_selectable() {
        val (s, _) = tree()
        assertFalse(SchedulerDomain.isSelectableCell(s, WellKnownIds.ROOT_CELL))
        assertFalse(WellKnownIds.ROOT_CELL in SchedulerDomain.selectableVisibleOrder(s))

        // Neither a click nor Ctrl+A can put it in the selection.
        val clicked =
            SchedulerReducer.reduce(
                s,
                SchedulerIntent.ClickCell(
                    cellId = WellKnownIds.ROOT_CELL,
                    ctrl = false,
                    shift = false,
                    visibleOrder = SchedulerDomain.selectableVisibleOrder(s),
                ),
            )
        assertNull(clicked.selection.main)
        val all = SchedulerReducer.reduce(s, SchedulerIntent.SelectAllVisibleCells)
        assertFalse(WellKnownIds.ROOT_CELL in all.selection.selected)
    }

    /** Collapsing it collapses the tree — the row stays, everything under it goes. */
    @Test
    fun collapsing_the_root_row_hides_the_whole_tree() {
        val (s0, _) = tree()
        val s = SchedulerReducer.reduce(s0, SchedulerIntent.ToggleExpand(WellKnownIds.ROOT_CELL))
        assertEquals(listOf(WellKnownIds.ROOT_CELL), SchedulerDomain.visibleCellOrder(s))
        // The keyboard sees exactly what the eye does: nothing left to walk.
        assertTrue(SchedulerDomain.selectableVisibleOrder(s).isEmpty())
    }

    // ----- what must NOT change because of it -----------------------------------------------------

    /**
     * The regression the root cell all but caused. `assignCollisionScope` refuses an id whose sub-tree
     * shares a task with an ANCESTOR's sub-tree; the root cell's sub-tree is the whole tree, so counting it
     * as an ancestor made every assignment collide with everything and no cell could be pointed at any task
     * ever again. [SchedulerDomain.ancestorTaskIds] stops at it for that reason.
     */
    @Test
    fun the_root_row_is_not_an_ancestor_so_a_task_can_still_be_assigned() {
        var (s, book) = tree()
        val chapter = s.tasks.values.first { it.title == "Chapter" }.id
        val notesCell = s.cells.values.first { s.tasks[it.taskId]?.title == "Notes" }.id
        val notesList = s.tasks[s.cells[notesCell]!!.taskId!!]!!.childListId!!
        val target = s.lists[notesList]!!.cellIds.first()

        assertTrue(SchedulerDomain.canAssignTaskId(s, target, chapter), "mirroring must stay possible")
        assertFalse(WellKnownIds.ROOT_TASK in SchedulerDomain.ancestorTaskIds(s, target))

        s = SchedulerReducer.reduce(s, SchedulerIntent.AssignTaskId(target, chapter))
        assertEquals(chapter, s.cells[target]!!.taskId)

        // The root task itself is still not assignable to anything.
        assertFalse(SchedulerDomain.canAssignTaskId(s, target, WellKnownIds.ROOT_TASK))
        assertFalse(SchedulerDomain.canAssignTaskId(s, notesCell, book))
    }

    /**
     * The root task is what every percentage is a share *of*, not a row holding a share. It answers 1.0 to
     * the walk so the recursion terminates, but it must stay out of the map — otherwise a second 1.0 sits
     * beside the tasks dividing that 1.0 up, and everything summing the map counts the tree twice.
     */
    @Test
    fun the_root_task_is_not_a_row_of_the_priority_table() {
        val (s, _) = tree()
        val priorities = SchedulerDomain.absoluteTaskPriorities(s)
        assertFalse(WellKnownIds.ROOT_TASK in priorities)

        val leaves = SchedulerDomain.schedulableLeaves(s)
        assertFalse(WellKnownIds.ROOT_TASK in leaves)
        val total = leaves.sumOf { priorities[it] ?: 0.0 }
        assertTrue(abs(1.0 - total) < 1e-9, "the leaves still fill the tree, but was $total")
    }

    /**
     * A render-via names *which occurrence of a mirrored parent* a row is drawn under, and the root cell is
     * the one parent that can never be mirrored. So the tree's top-level rows keep the null via they had
     * before it existed — which is what lets the tree and PRD §4's template all highlight one selection the
     * same way.
     */
    @Test
    fun the_root_row_is_not_a_render_via() {
        val (s, _) = tree()
        val topLevel = s.lists[s.rootListId]!!.cellIds.first()
        assertNull(SchedulerDomain.renderViaOf(s, WellKnownIds.ROOT_CELL))
        assertNull(SchedulerDomain.resolveSelectionRenderVia(s, topLevel))
        assertNull(
            SchedulerDomain.visibleOccurrences(s).first { it.cellId == topLevel }.renderVia,
        )

        val clicked =
            SchedulerReducer.reduce(
                s,
                SchedulerIntent.ClickCell(
                    cellId = topLevel,
                    ctrl = false,
                    shift = false,
                    visibleOrder = SchedulerDomain.selectableVisibleOrder(s),
                ),
            )
        assertNull(clicked.selection.renderVia)
        assertTrue(
            SchedulerDomain.shouldShowSelectionHighlight(clicked.selection, topLevel, localRenderVia = null),
        )
    }

    // ----- the drawing that has no root row -------------------------------------------------------

    /**
     * PRD §4's template is re-rooted at its own parentless list, so it grows no root row —
     * [SchedulerDomain.displayRootListId] falls back to its own root.
     */
    @Test
    fun the_template_draws_no_root_row() {
        val (s, _) = tree()

        val template = s.projectDefaultSubtree()
        assertNull(SchedulerDomain.rootCellId(template))
        assertEquals(template.rootListId, SchedulerDomain.displayRootListId(template))
        assertFalse(WellKnownIds.ROOT_CELL in SchedulerDomain.visibleCellOrder(template))
    }

    // ----- surviving a whole-tree swap ------------------------------------------------------------

    /**
     * `applyTree` replaces the cells, lists and tasks wholesale, so an undo into a history unit written
     * before the root existed is the one moment a healed state can go back to an unhealed tree. It must
     * come back with the root, or the account opens on a tree with no rows.
     */
    @Test
    fun a_whole_tree_swap_from_before_the_root_existed_keeps_it() {
        val (s, _) = tree()
        val stripped =
            s.captureTree().let { snapshot ->
                snapshot.copy(
                    cells = snapshot.cells - WellKnownIds.ROOT_CELL,
                    lists =
                        (snapshot.lists - WellKnownIds.ROOT_CELL_LIST).mapValues { (id, list) ->
                            if (id == WellKnownIds.ROOT_LIST) list.copy(parentCellId = null) else list
                        },
                    tasks = snapshot.tasks - WellKnownIds.ROOT_TASK,
                )
            }

        val applied = s.applyTree(stripped)
        assertEquals(WellKnownIds.ROOT_CELL, SchedulerDomain.rootCellId(applied))
        val root = assertNotNull(applied.tasks[WellKnownIds.ROOT_TASK])
        assertEquals("root", root.title)
        assertEquals(WellKnownIds.ROOT_LIST, root.childListId)
        assertEquals(WellKnownIds.ROOT_CELL, SchedulerDomain.visibleCellOrder(applied).first())
        // The tree it swapped in is still all there under the row.
        assertTrue(applied.tasks.values.any { it.title == "Book" })
    }

    /** Installing the root is idempotent — the funnel runs on every load, merge and tree swap. */
    @Test
    fun healing_a_state_that_already_has_a_root_changes_nothing() {
        val (s, _) = tree()
        assertEquals(s, SchedulerDomain.withRoot(s))
    }

    /** A collapsed root row stays collapsed across a heal: only a freshly minted one is expanded. */
    @Test
    fun healing_never_re_expands_a_collapsed_root_row() {
        val (s0, _) = tree()
        val collapsed = SchedulerReducer.reduce(s0, SchedulerIntent.ToggleExpand(WellKnownIds.ROOT_CELL))
        assertFalse(WellKnownIds.ROOT_CELL in SchedulerDomain.withRoot(collapsed).expanded)
    }

    /** The tree's own root list is not the list a drawing starts from — the two must stay distinct. */
    @Test
    fun the_root_list_and_the_list_holding_the_root_row_are_different_lists() {
        val (s, _) = tree()
        assertEquals(WellKnownIds.ROOT_LIST, s.rootListId)
        assertEquals(WellKnownIds.ROOT_CELL_LIST, SchedulerDomain.displayRootListId(s))
        assertFalse(WellKnownIds.ROOT_CELL in s.lists[WellKnownIds.ROOT_LIST]!!.cellIds)
        assertEquals(listOf(WellKnownIds.ROOT_CELL), s.lists[WellKnownIds.ROOT_CELL_LIST]!!.cellIds)
    }

    /** A stray reference: nothing in the tree may still point at the retired ids. */
    @Test
    fun no_part_of_a_fresh_account_names_main() {
        val (s, _) = tree()
        assertNull(s.tasks[TaskId("task/main")])
        assertNull(s.titleToTaskIds["main"])
        assertFalse(s.lists.keys.any { it.value == "list/main" })
        assertFalse(s.cells.values.any { it.taskId?.value == "task/main" })
        assertFalse(CellId("cell/main/0") in s.cells)
    }
}
