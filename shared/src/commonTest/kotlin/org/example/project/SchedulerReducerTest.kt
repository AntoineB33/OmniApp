package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.WellKnownIds
import org.example.project.scheduler.persistence.SchedulerStateCodec
import org.example.project.scheduler.persistence.SchedulerStore
import org.example.project.scheduler.state.CellEditMode
import org.example.project.scheduler.state.EditExitNavigation
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerSelection
import org.example.project.scheduler.state.SchedulerState
import org.example.project.scheduler.state.SelectionNavigate
import org.example.project.scheduler.ui.TaskSchedulerViewModel

class SchedulerReducerTest {
    @Test
    fun empty_db_initializes_root_and_main_tasks() {
        val s = SchedulerState.empty()
        assertEquals(WellKnownIds.MAIN_LIST, s.rootListId)
        assertNotNull(s.tasks[WellKnownIds.ROOT_TASK])
        assertNotNull(s.tasks[WellKnownIds.MAIN_TASK])
        assertEquals("root", s.tasks[WellKnownIds.ROOT_TASK]!!.title)
        assertEquals("main", s.tasks[WellKnownIds.MAIN_TASK]!!.title)
        assertEquals(listOf(WellKnownIds.MAIN_TASK), s.tasks[WellKnownIds.ROOT_TASK]!!.childTaskIds)
        assertEquals(listOf(WellKnownIds.MAIN_TASK), s.titleToTaskIds["main"])
        assertEquals(1, s.lists[s.rootListId]!!.cellIds.size)
    }

    @Test
    fun new_task_is_linked_under_main_in_task_tree() {
        var s = SchedulerState.empty()
        val cellId = s.lists[s.rootListId]!!.cellIds.first()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(cellId, "Daily"))

        val taskId = s.cells[cellId]!!.taskId!!
        assertTrue(taskId in s.tasks[WellKnownIds.MAIN_TASK]!!.childTaskIds)
    }

    @Test
    fun click_sets_main_and_clears_multi() {
        var s = seedThreeTasks()
        val visible = SchedulerDomain.selectableVisibleOrder(s)

        s = SchedulerReducer.reduce(
            s,
            SchedulerIntent.ClickCell(cellId = visible[0], ctrl = false, shift = false, visibleOrder = visible),
        )
        assertEquals(visible[0], s.selection.main)
        assertTrue(s.selection.selected.isEmpty())

        s = SchedulerReducer.reduce(
            s,
            SchedulerIntent.ClickCell(cellId = visible[2], ctrl = true, shift = false, visibleOrder = visible),
        )
        assertEquals(visible[2], s.selection.main)
        assertTrue(s.selection.selected.contains(visible[2]))

        s = SchedulerReducer.reduce(
            s,
            SchedulerIntent.ClickCell(cellId = visible[1], ctrl = false, shift = false, visibleOrder = visible),
        )
        assertEquals(visible[1], s.selection.main)
        assertTrue(s.selection.selected.isEmpty())
    }

    @Test
    fun ctrl_click_includes_prior_main_in_multi_selection() {
        var s = seedThreeTasks()
        val visible = SchedulerDomain.selectableVisibleOrder(s)

        s = SchedulerReducer.reduce(
            s,
            SchedulerIntent.ClickCell(cellId = visible[0], ctrl = false, shift = false, visibleOrder = visible),
        )
        s = SchedulerReducer.reduce(
            s,
            SchedulerIntent.ClickCell(cellId = visible[2], ctrl = true, shift = false, visibleOrder = visible),
        )

        assertEquals(visible[2], s.selection.main)
        assertEquals(setOf(visible[0], visible[2]), s.selection.selected)
        assertEquals(null, s.selection.rangeAnchor)
    }

    @Test
    fun shift_click_selects_range_in_visible_order() {
        var s = seedThreeTasks()
        val visible = SchedulerDomain.selectableVisibleOrder(s)

        s = SchedulerReducer.reduce(
            s,
            SchedulerIntent.ClickCell(cellId = visible[0], ctrl = false, shift = false, visibleOrder = visible),
        )
        s = SchedulerReducer.reduce(
            s,
            SchedulerIntent.ClickCell(cellId = visible[2], ctrl = false, shift = true, visibleOrder = visible),
        )

        assertEquals(visible[2], s.selection.main)
        assertEquals(setOf(visible[0], visible[1], visible[2]), s.selection.selected)
    }

    @Test
    fun ctrl_shift_click_expands_selection_like_shift_click() {
        var s = seedThreeTasks()
        val visible = SchedulerDomain.selectableVisibleOrder(s)

        s = SchedulerReducer.reduce(
            s,
            SchedulerIntent.ClickCell(cellId = visible[0], ctrl = false, shift = false, visibleOrder = visible),
        )
        s = SchedulerReducer.reduce(
            s,
            SchedulerIntent.ClickCell(cellId = visible[2], ctrl = true, shift = true, visibleOrder = visible),
        )

        assertEquals(visible[2], s.selection.main)
        assertEquals(setOf(visible[0], visible[1], visible[2]), s.selection.selected)
    }

    @Test
    fun drag_select_spans_visible_range_from_anchor() {
        var s = seedThreeTasks()
        val visible = SchedulerDomain.selectableVisibleOrder(s)
        val anchor = visible[0]
        val hover = visible[2]

        s = SchedulerReducer.reduce(
            s,
            SchedulerIntent.ClickCell(cellId = anchor, ctrl = false, shift = false, visibleOrder = visible),
        )
        s = SchedulerReducer.reduce(
            s,
            SchedulerIntent.DragSelectCells(
                anchorCellId = anchor,
                hoverCellId = hover,
                visibleOrder = visible,
            ),
        )

        assertEquals(anchor, s.selection.main)
        assertEquals(setOf(visible[0], visible[1], visible[2]), s.selection.selected)
    }

    @Test
    fun clear_selection_clears_main_and_multi_and_exits_edit() {
        var s = seedThreeTasks()
        val visible = SchedulerDomain.selectableVisibleOrder(s)
        val cell = visible[1]

        s = SchedulerReducer.reduce(
            s,
            SchedulerIntent.ClickCell(cellId = cell, ctrl = false, shift = false, visibleOrder = visible),
        )
        s = SchedulerReducer.reduce(s, SchedulerIntent.BeginEdit(cell))
        assertNotNull(s.editSession)

        s = SchedulerReducer.reduce(s, SchedulerIntent.ClearSelection)

        assertEquals(null, s.selection.main)
        assertTrue(s.selection.selected.isEmpty())
        assertEquals(null, s.editSession)
    }

    @Test
    fun exit_edit_enter_moves_selection_down() {
        var s = seedThreeTasks()
        val visible = SchedulerDomain.selectableVisibleOrder(s)
        val top = visible[0]
        val below = visible[1]

        s = SchedulerReducer.reduce(s, SchedulerIntent.BeginEdit(top))
        s = SchedulerReducer.reduce(s, SchedulerIntent.ExitEdit(EditExitNavigation.Down))

        assertEquals(null, s.editSession)
        assertEquals(below, s.selection.main)
        assertTrue(s.selection.selected.isEmpty())
        // The moved selection must actually render its highlight: render-via must resolve for the
        // new cell, not stay pinned to the cell we exited (which would hide the highlight).
        assertEquals(null, s.selection.renderVia)
        assertTrue(SchedulerDomain.shouldShowSelectionHighlight(s.selection, below, localRenderVia = null))
    }

    @Test
    fun exit_edit_escape_stays_on_edited_cell_and_renders_highlight() {
        var s = seedThreeTasks()
        val visible = SchedulerDomain.selectableVisibleOrder(s)
        val top = visible[0]

        s = SchedulerReducer.reduce(s, SchedulerIntent.BeginEdit(top))
        s = SchedulerReducer.reduce(s, SchedulerIntent.ExitEdit(EditExitNavigation.Stay))

        assertEquals(null, s.editSession)
        assertEquals(top, s.selection.main)
        assertTrue(s.selection.selected.isEmpty())
        assertEquals(null, s.selection.renderVia)
        assertTrue(SchedulerDomain.shouldShowSelectionHighlight(s.selection, top, localRenderVia = null))
    }

    @Test
    fun reclick_after_edit_on_lone_root_cell_keeps_cell_selectable() {
        // Repro: fresh DB → click → double-click (BeginEdit) → Enter (ExitEdit) → click again.
        // The lone root cell must stay highlightable; renderVia must never become the cell itself.
        var s = SchedulerState.empty()
        val cell = s.lists[s.rootListId]!!.cellIds.first()
        val visible = SchedulerDomain.selectableVisibleOrder(s)

        s = SchedulerReducer.reduce(
            s,
            SchedulerIntent.ClickCell(cellId = cell, ctrl = false, shift = false, visibleOrder = visible),
        )
        s = SchedulerReducer.reduce(s, SchedulerIntent.BeginEdit(cell))
        s = SchedulerReducer.reduce(s, SchedulerIntent.ExitEdit(EditExitNavigation.Down))

        // No cell below, so selection stays on the edited cell with a clean (null) renderVia.
        assertEquals(cell, s.selection.main)
        assertEquals(null, s.selection.renderVia)
        assertTrue(SchedulerDomain.shouldShowSelectionHighlight(s.selection, cell, localRenderVia = null))

        s = SchedulerReducer.reduce(
            s,
            SchedulerIntent.ClickCell(cellId = cell, ctrl = false, shift = false, visibleOrder = visible),
        )
        assertEquals(cell, s.selection.main)
        assertEquals(null, s.selection.renderVia)
        assertTrue(SchedulerDomain.shouldShowSelectionHighlight(s.selection, cell, localRenderVia = null))
    }

    @Test
    fun exit_edit_shift_enter_moves_selection_up() {
        var s = seedThreeTasks()
        val visible = SchedulerDomain.selectableVisibleOrder(s)
        val middle = visible[1]
        val above = visible[0]

        s = SchedulerReducer.reduce(
            s,
            SchedulerIntent.ClickCell(cellId = middle, ctrl = false, shift = false, visibleOrder = visible),
        )
        s = SchedulerReducer.reduce(s, SchedulerIntent.BeginEdit(middle))
        s = SchedulerReducer.reduce(s, SchedulerIntent.ExitEdit(EditExitNavigation.Up))

        assertEquals(above, s.selection.main)
    }

    @Test
    fun exit_edit_tab_expands_and_selects_first_child() {
        var s = seedThreeTasks()
        val parent = s.lists[s.rootListId]!!.cellIds.first()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(parent, "Parent"))
        val childListId = s.tasks[s.cells[parent]!!.taskId!!]!!.childListId!!
        val child = s.lists[childListId]!!.cellIds.first()

        s = SchedulerReducer.reduce(s, SchedulerIntent.BeginEdit(parent))
        assertFalse(parent in s.expanded)

        s = SchedulerReducer.reduce(s, SchedulerIntent.ExitEdit(EditExitNavigation.TabToChild))

        assertTrue(parent in s.expanded)
        assertEquals(child, s.selection.main)
        assertEquals(null, s.editSession)
    }

    @Test
    fun collapsed_subtree_excluded_from_visible_order() {
        var s = seedThreeTasks()
        val rootCell = s.lists[s.rootListId]!!.cellIds.first()
        s = SchedulerReducer.reduce(s, SchedulerIntent.ToggleExpand(rootCell))
        val childListId = s.tasks[s.cells[rootCell]!!.taskId!!]!!.childListId!!
        val childId = s.lists[childListId]!!.cellIds.first()

        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(childId, "Child"))
        s = SchedulerReducer.reduce(s, SchedulerIntent.ToggleExpand(rootCell))

        val visible = SchedulerDomain.visibleCellOrder(s)
        assertFalse(visible.contains(childId))
    }

    @Test
    fun toggle_expand_is_undoable() {
        var s = SchedulerState.empty()
        val cellId = s.lists[s.rootListId]!!.cellIds.first()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(cellId, "Parent"))
        val childListId = s.tasks[s.cells[cellId]!!.taskId!!]!!.childListId!!
        val child = s.lists[childListId]!!.cellIds.first()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(child, "Child"))

        s = SchedulerReducer.reduce(s, SchedulerIntent.ToggleExpand(cellId))
        assertTrue(s.expanded.contains(cellId))

        s = SchedulerReducer.reduce(s, SchedulerIntent.Undo)
        assertFalse(s.expanded.contains(cellId))

        s = SchedulerReducer.reduce(s, SchedulerIntent.Redo)
        assertTrue(s.expanded.contains(cellId))
    }

    @Test
    fun populated_cell_with_auto_expanded_sublist_shows_expansion_arrow() {
        var s = SchedulerState.empty()
        val cellId = s.lists[s.rootListId]!!.cellIds.first()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(cellId, "Parent"))

        assertTrue(SchedulerDomain.hasExpandableSubTree(s, cellId))
    }

    @Test
    fun emptying_cell_clears_expansion_arrow_state() {
        var s = SchedulerState.empty()
        val cellId = s.lists[s.rootListId]!!.cellIds.first()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(cellId, "Parent"))

        assertTrue(SchedulerDomain.hasExpandableSubTree(s, cellId))

        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(cellId, ""))

        assertFalse(SchedulerDomain.hasExpandableSubTree(s, cellId))
    }

    @Test
    fun set_cell_title_creates_task_and_placeholder() {
        var s = SchedulerState.empty()
        val cellId = s.lists[s.rootListId]!!.cellIds.first()

        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(cellId, "Buy milk"))

        val cell = s.cells[cellId]!!
        assertNotNull(cell.taskId)
        assertEquals("Buy milk", s.tasks[cell.taskId]!!.title)
        assertEquals(listOf(cellId), s.tasks[cell.taskId]!!.occurrences)
        assertEquals(2, s.lists[s.rootListId]!!.cellIds.size)
        assertEquals(listOf(cell.taskId), s.titleToTaskIds["Buy milk"])
    }

    @Test
    fun bottom_cell_text_auto_expands_with_hidden_sublist_and_placeholder() {
        var s = SchedulerState.empty()
        val cellId = s.lists[s.rootListId]!!.cellIds.first()

        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(cellId, "Parent"))

        val task = s.tasks[s.cells[cellId]!!.taskId!!]!!
        assertNotNull(task.childListId)
        assertFalse(s.expanded.contains(cellId))
        val subList = s.lists[task.childListId]!!
        assertEquals(1, subList.cellIds.size)
        assertEquals(null, s.cells[subList.cellIds.first()]!!.taskId)
        assertEquals(2, s.lists[s.rootListId]!!.cellIds.size)
    }

    @Test
    fun emptying_cell_above_trailing_placeholder_removes_placeholder() {
        var s = SchedulerState.empty()
        val cellId = s.lists[s.rootListId]!!.cellIds.first()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(cellId, "Parent"))
        assertEquals(2, s.lists[s.rootListId]!!.cellIds.size)

        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(cellId, ""))

        val rootCells = s.lists[s.rootListId]!!.cellIds
        assertEquals(1, rootCells.size)
        assertEquals(cellId, rootCells.single())

        // The cleanup is part of the title-mutation delta, so it round-trips with undo.
        s = SchedulerReducer.reduce(s, SchedulerIntent.Undo)
        assertEquals(2, s.lists[s.rootListId]!!.cellIds.size)
    }

    @Test
    fun emptying_edited_cell_in_edit_mode_removes_trailing_placeholder() {
        var s = SchedulerState.empty()
        val cellId = s.lists[s.rootListId]!!.cellIds.first()
        s = SchedulerReducer.reduce(s, SchedulerIntent.BeginEdit(cellId, initialText = "Parent"))
        assertEquals(2, s.lists[s.rootListId]!!.cellIds.size)

        s = SchedulerReducer.reduce(s, SchedulerIntent.UpdateEditText(""))

        val rootCells = s.lists[s.rootListId]!!.cellIds
        assertEquals(1, rootCells.size)
        assertEquals(cellId, rootCells.single())
    }

    @Test
    fun emptying_does_not_remove_absolute_bottom_cell() {
        var s = SchedulerState.empty()
        val cellId = s.lists[s.rootListId]!!.cellIds.first()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(cellId, "Parent"))
        val trailing = s.lists[s.rootListId]!!.cellIds.last()

        // Emptying the trailing (already empty) bottom cell must keep it: it is the
        // absolute bottom cell of the list (PRD §4 Post-Edit Cleanup).
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(trailing, ""))
        assertTrue(trailing in s.lists[s.rootListId]!!.cellIds)
        assertEquals(2, s.lists[s.rootListId]!!.cellIds.size)
    }

    @Test
    fun exiting_edit_removes_empty_middle_cell_but_keeps_bottom_placeholder() {
        // Build [A, B, C, placeholder] in the root list.
        var s = SchedulerState.empty()
        val root = s.rootListId
        val c0 = s.lists[root]!!.cellIds.first()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(c0, "A"))
        val c1 = s.lists[root]!!.cellIds[1]
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(c1, "B"))
        val c2 = s.lists[root]!!.cellIds[2]
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(c2, "C"))
        assertEquals(4, s.lists[root]!!.cellIds.size)

        // Edit the middle cell B and empty it. The trailing placeholder is not directly
        // below B, so the in-edit cleanup leaves B empty in the middle of the list.
        s = SchedulerReducer.reduce(s, SchedulerIntent.BeginEdit(c1))
        s = SchedulerReducer.reduce(s, SchedulerIntent.UpdateEditText(""))
        assertTrue(c1 in s.lists[root]!!.cellIds)
        assertEquals(4, s.lists[root]!!.cellIds.size)

        // Exit Edit Mode by clicking another cell (PRD §4 "Forced Exit").
        s =
            SchedulerReducer.reduce(
                s,
                SchedulerIntent.ClickCell(cellId = c0, ctrl = false, shift = false, visibleOrder = emptyList()),
            )

        // PRD §4 Post-Edit Cleanup: the empty middle cell is gone; the bottom placeholder stays.
        assertEquals(null, s.editSession)
        assertFalse(c1 in s.lists[root]!!.cellIds)
        assertFalse(s.cells.containsKey(c1))
        assertEquals(3, s.lists[root]!!.cellIds.size)
        assertEquals(null, s.cells[s.lists[root]!!.cellIds.last()]!!.taskId)

        // The cleanup is an undoable delta.
        s = SchedulerReducer.reduce(s, SchedulerIntent.Undo)
        assertTrue(c1 in s.lists[root]!!.cellIds)
        assertEquals(4, s.lists[root]!!.cellIds.size)
    }

    @Test
    fun exiting_edit_keeps_absolute_bottom_cell_of_sublist() {
        // Parent with an expanded sublist whose only child is the empty bottom placeholder.
        var s = SchedulerState.empty()
        val root = s.rootListId
        val parent = s.lists[root]!!.cellIds.first()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(parent, "Parent"))
        s = SchedulerReducer.reduce(s, SchedulerIntent.ToggleExpand(parent))
        val childListId = s.tasks[s.cells[parent]!!.taskId!!]!!.childListId!!
        val bottomChild = s.lists[childListId]!!.cellIds.last()
        assertEquals(null, s.cells[bottomChild]!!.taskId)

        // Edit the empty bottom child, then exit without typing anything.
        s = SchedulerReducer.reduce(s, SchedulerIntent.BeginEdit(bottomChild))
        s =
            SchedulerReducer.reduce(
                s,
                SchedulerIntent.ClickCell(cellId = parent, ctrl = false, shift = false, visibleOrder = emptyList()),
            )

        // The absolute bottom cell of the sublist must be preserved (PRD §4 Post-Edit Cleanup).
        assertEquals(null, s.editSession)
        assertTrue(bottomChild in s.lists[childListId]!!.cellIds)
    }

    @Test
    fun rename_updates_title_index_and_shared_task_title() {
        var s = SchedulerState.empty()
        val first = s.lists[s.rootListId]!!.cellIds.first()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(first, "Shared"))
        val taskId = s.cells[first]!!.taskId!!

        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(first, "Renamed"))

        assertEquals("Renamed", s.tasks[taskId]!!.title)
        assertEquals(listOf(taskId), s.titleToTaskIds["Renamed"])
        assertFalse(s.titleToTaskIds.containsKey("Shared"))
    }

    @Test
    fun set_cell_title_is_undoable_and_redoable() {
        var s = SchedulerState.empty()
        val cellId = s.lists[s.rootListId]!!.cellIds.first()

        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(cellId, "Task A"))
        val taskId = s.cells[cellId]!!.taskId!!

        s = SchedulerReducer.reduce(s, SchedulerIntent.Undo)
        assertEquals(null, s.cells[cellId]!!.taskId)
        assertFalse(s.tasks.containsKey(taskId))

        s = SchedulerReducer.reduce(s, SchedulerIntent.Redo)
        assertEquals(taskId, s.cells[cellId]!!.taskId)
        assertEquals("Task A", s.tasks[taskId]!!.title)
    }

    @Test
    fun new_mutation_after_undo_orphans_redo_history() {
        var s = SchedulerState.empty()
        val cellId = s.lists[s.rootListId]!!.cellIds.first()

        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(cellId, "First"))
        s = SchedulerReducer.reduce(s, SchedulerIntent.Undo)
        assertEquals(-1, s.history.pointer)

        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(cellId, "Second"))
        assertEquals(0, s.history.pointer)
        assertEquals(1, s.history.units.size)
        assertEquals("Second", s.tasks[s.cells[cellId]!!.taskId!!]!!.title)
    }

    @Test
    fun assign_task_id_rejected_when_duplicate_in_same_list() {
        var s = seedThreeTasks()
        val listId = s.rootListId
        val ids = s.lists[listId]!!.cellIds
        val first = ids[0]
        val second = ids[1]
        val duplicateId = s.cells[first]!!.taskId!!

        val before = s.cells[second]!!.taskId
        s = SchedulerReducer.reduce(s, SchedulerIntent.AssignTaskId(second, duplicateId))

        assertEquals(before, s.cells[second]!!.taskId)
        assertFalse(s.cells[second]!!.taskId == duplicateId)
    }

    @Test
    fun assign_task_id_purges_task_when_last_cell_reassigned() {
        var s = SchedulerState.empty()
        val parentCell = s.lists[s.rootListId]!!.cellIds.first()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(parentCell, "Parent"))
        s = SchedulerReducer.reduce(s, SchedulerIntent.ToggleExpand(parentCell))
        val childListId = s.tasks[s.cells[parentCell]!!.taskId!!]!!.childListId!!
        val child = s.lists[childListId]!!.cellIds.first()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(child, "Temp"))
        val tempId = s.cells[child]!!.taskId!!

        val keeperCell = s.lists[s.rootListId]!!.cellIds.last()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(keeperCell, "Keeper"))
        val keeperId = s.cells[keeperCell]!!.taskId!!

        s = SchedulerReducer.reduce(s, SchedulerIntent.AssignTaskId(child, keeperId))

        assertEquals(keeperId, s.cells[child]!!.taskId)
        assertFalse(s.tasks.containsKey(tempId))
    }

    @Test
    fun occurrences_sorted_by_shortest_cell_path() {
        var s = SchedulerState.empty()
        val shallow = s.lists[s.rootListId]!!.cellIds.first()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(shallow, "Shared"))
        val sharedId = s.cells[shallow]!!.taskId!!

        val branch = s.lists[s.rootListId]!!.cellIds.last()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(branch, "Branch"))
        s = SchedulerReducer.reduce(s, SchedulerIntent.ToggleExpand(branch))
        val deepCell = s.lists[s.tasks[s.cells[branch]!!.taskId!!]!!.childListId!!]!!.cellIds.first()
        s = SchedulerReducer.reduce(s, SchedulerIntent.AssignTaskId(deepCell, sharedId))

        val depths = s.tasks[sharedId]!!.occurrences.map { SchedulerDomain.cellTreeDepth(s, it) }
        assertEquals(depths, depths.sorted())
        assertTrue(depths.first() < depths.last())
    }

    @Test
    fun rename_on_one_cell_updates_all_cells_sharing_task_id() {
        var s = SchedulerState.empty()
        val branchA = s.lists[s.rootListId]!!.cellIds.first()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(branchA, "Shared"))
        val sharedTaskId = s.cells[branchA]!!.taskId!!

        val branchB = s.lists[s.rootListId]!!.cellIds.last()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(branchB, "Branch B"))
        s = SchedulerReducer.reduce(s, SchedulerIntent.ToggleExpand(branchB))
        val branchBChild = s.lists[s.tasks[s.cells[branchB]!!.taskId!!]!!.childListId!!]!!.cellIds.first()
        s = SchedulerReducer.reduce(s, SchedulerIntent.AssignTaskId(branchBChild, sharedTaskId))

        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(branchA, "Renamed"))

        assertEquals("Renamed", s.tasks[sharedTaskId]!!.title)
        assertEquals("Renamed", s.tasks[s.cells[branchBChild]!!.taskId!!]!!.title)
    }

    @Test
    fun viewport_does_not_render_main_cell() {
        val s = SchedulerState.empty()
        val rootCells = s.lists[s.rootListId]!!.cellIds
        assertTrue(rootCells.none { s.cells[it]?.taskId == WellKnownIds.MAIN_TASK })
        assertTrue(SchedulerDomain.isSelectableCell(s, rootCells.first()))
    }

    @Test
    fun constraint_same_list_duplicate_task_id_is_detected() {
        var s = seedThreeTasks()
        val listId = s.rootListId
        val ids = s.lists[listId]!!.cellIds
        val first = ids[0]
        val second = ids[1]
        val duplicateId = s.cells[first]!!.taskId!!

        assertFalse(SchedulerDomain.canAssignTaskId(s, second, duplicateId))
    }

    @Test
    fun constraint_ancestor_task_id_is_detected() {
        var s = SchedulerState.empty()
        val parentId = s.lists[s.rootListId]!!.cellIds.first()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(parentId, "Parent"))
        s = SchedulerReducer.reduce(s, SchedulerIntent.ToggleExpand(parentId))
        val parentTaskId = s.cells[parentId]!!.taskId!!
        val childListId = s.tasks[parentTaskId]!!.childListId!!
        val childCellId = s.lists[childListId]!!.cellIds.first()

        assertFalse(SchedulerDomain.canAssignTaskId(s, childCellId, parentTaskId))
    }

    @Test
    fun multiple_task_ids_can_share_same_title() {
        var s = SchedulerState.empty()
        val first = s.lists[s.rootListId]!!.cellIds.first()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(first, "Youtube"))
        val firstTask = s.cells[first]!!.taskId!!

        val second = s.lists[s.rootListId]!!.cellIds.last()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(second, "Youtube"))
        val secondTask = s.cells[second]!!.taskId!!

        assertFalse(firstTask == secondTask)
        assertEquals(setOf(firstTask, secondTask), s.titleToTaskIds["Youtube"]!!.toSet())
    }

    @Test
    fun shared_task_id_uses_same_child_list_in_different_lists() {
        var s = SchedulerState.empty()
        val branchA = s.lists[s.rootListId]!!.cellIds.first()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(branchA, "Branch A"))
        val sharedTaskId = s.cells[branchA]!!.taskId!!
        val sharedChildListId = s.tasks[sharedTaskId]!!.childListId!!

        val branchB = s.lists[s.rootListId]!!.cellIds.last()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(branchB, "Branch B"))
        s = SchedulerReducer.reduce(s, SchedulerIntent.ToggleExpand(branchB))
        val branchBChild = s.lists[s.tasks[s.cells[branchB]!!.taskId!!]!!.childListId!!]!!.cellIds.first()

        s = SchedulerReducer.reduce(s, SchedulerIntent.AssignTaskId(branchBChild, sharedTaskId))

        assertEquals(sharedChildListId, s.tasks[sharedTaskId]!!.childListId)
        assertEquals(sharedTaskId, s.cells[branchBChild]!!.taskId)
    }

    @Test
    fun click_one_shared_task_occurrence_does_not_highlight_sibling_occurrence() {
        var s = SchedulerState.empty()
        val branchA = s.lists[s.rootListId]!!.cellIds.first()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(branchA, "Shared"))
        val sharedTaskId = s.cells[branchA]!!.taskId!!

        val branchB = s.lists[s.rootListId]!!.cellIds.last()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(branchB, "Branch B"))
        s = SchedulerReducer.reduce(s, SchedulerIntent.ToggleExpand(branchB))
        val branchBChild =
            s.lists[s.tasks[s.cells[branchB]!!.taskId!!]!!.childListId!!]!!.cellIds.first()
        s = SchedulerReducer.reduce(s, SchedulerIntent.AssignTaskId(branchBChild, sharedTaskId))

        val visible = SchedulerDomain.selectableVisibleOrder(s)
        s = SchedulerReducer.reduce(
            s,
            SchedulerIntent.ClickCell(
                cellId = branchA,
                ctrl = false,
                shift = false,
                visibleOrder = visible,
                renderVia = null,
            ),
        )
        assertEquals(branchA, s.selection.main)
        assertEquals(null, s.selection.renderVia)
        assertTrue(SchedulerDomain.shouldShowSelectionHighlight(s.selection, branchA, localRenderVia = null))
        assertFalse(SchedulerDomain.shouldShowSelectionHighlight(s.selection, branchBChild, localRenderVia = branchB))

        s = SchedulerReducer.reduce(
            s,
            SchedulerIntent.ClickCell(
                cellId = branchBChild,
                ctrl = false,
                shift = false,
                visibleOrder = visible,
                renderVia = branchB,
            ),
        )
        assertEquals(branchBChild, s.selection.main)
        assertEquals(branchB, s.selection.renderVia)
        assertFalse(SchedulerDomain.shouldShowSelectionHighlight(s.selection, branchA, localRenderVia = null))
        assertTrue(SchedulerDomain.shouldShowSelectionHighlight(s.selection, branchBChild, localRenderVia = branchB))
    }

    @Test
    fun eligible_assign_task_ids_hide_ancestors_and_siblings() {
        var s = SchedulerState.empty()
        // Ancestor and sibling share the exact title "dup" (PRD Constraint 3), so the
        // query "dup" matches both and meaningfully exercises the ancestor/sibling
        // filtering (PRD §4 Filtering) under exact-title matching.
        val parent = s.lists[s.rootListId]!!.cellIds.first()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(parent, "dup"))
        val parentTask = s.cells[parent]!!.taskId!!
        s = SchedulerReducer.reduce(s, SchedulerIntent.ToggleExpand(parent))

        val childListId = s.tasks[parentTask]!!.childListId!!
        val firstChild = s.lists[childListId]!!.cellIds.first()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(firstChild, "dup"))
        val firstChildTask = s.cells[firstChild]!!.taskId!!

        val emptySlot = s.lists[childListId]!!.cellIds.last()
        val eligible = SchedulerDomain.eligibleAssignTaskIds(s, emptySlot, "dup")
        assertFalse(parentTask in eligible)
        assertFalse(firstChildTask in eligible)
    }

    @Test
    fun eligible_assign_task_ids_sorted_by_path_length_then_label() {
        var s = SchedulerState.empty()
        // Two distinct taskIds share the exact title "Task" at different depths
        // (PRD Constraint 3). Exact-title matching surfaces both candidates.
        val alphaCell = s.lists[s.rootListId]!!.cellIds.first()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(alphaCell, "Task"))
        val alphaId = s.cells[alphaCell]!!.taskId!!
        s = SchedulerReducer.reduce(s, SchedulerIntent.ToggleExpand(alphaCell))
        val alphaListId = s.tasks[alphaId]!!.childListId!!
        val nestedCell = s.lists[alphaListId]!!.cellIds.first()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(nestedCell, "Task"))
        val nestedId = s.cells[nestedCell]!!.taskId!!

        val betaCell = s.lists[s.rootListId]!!.cellIds.last()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(betaCell, "Beta"))
        s = SchedulerReducer.reduce(s, SchedulerIntent.ToggleExpand(betaCell))
        val betaListId = s.tasks[s.cells[betaCell]!!.taskId!!]!!.childListId!!

        // Query "Task" matches both candidates; the shallower one must sort before
        // the deeper one per PRD §4 Sorting.
        val emptyInSublist = s.lists[betaListId]!!.cellIds.last()
        val eligible = SchedulerDomain.eligibleAssignTaskIds(s, emptyInSublist, "Task")
        val alphaIndex = eligible.indexOf(alphaId)
        val nestedIndex = eligible.indexOf(nestedId)
        assertTrue(alphaIndex >= 0 && nestedIndex >= 0)
        assertTrue(alphaIndex < nestedIndex)
    }

    @Test
    fun title_suggestions_sorted_by_similarity_then_alphabetical() {
        var s = SchedulerState.empty()
        val first = s.lists[s.rootListId]!!.cellIds.first()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(first, "Cook pasta"))
        val second = s.lists[s.rootListId]!!.cellIds.last()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(second, "Cook rice"))

        val suggestions = SchedulerDomain.titleSuggestions(s, "Cook")
        assertEquals(listOf("Cook pasta", "Cook rice"), suggestions)
    }

    @Test
    fun title_suggestions_exclude_exact_same_title() {
        var s = SchedulerState.empty()
        val first = s.lists[s.rootListId]!!.cellIds.first()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(first, "Cook pasta"))
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(s.lists[s.rootListId]!!.cellIds.last(), "Cook pasta deluxe"))

        val suggestions = SchedulerDomain.titleSuggestions(s, "Cook pasta")
        assertFalse("Cook pasta" in suggestions)
        assertEquals(listOf("Cook pasta deluxe"), suggestions)
    }

    @Test
    fun change_task_menu_selects_create_new_when_draft_text_changes() {
        var s = SchedulerState.empty()
        val cellId = s.lists[s.rootListId]!!.cellIds.first()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(cellId, "Alpha"))
        val originalTaskId = s.cells[cellId]!!.taskId!!
        s = SchedulerReducer.reduce(s, SchedulerIntent.BeginEdit(cellId))
        assertEquals(originalTaskId, s.editSession!!.selectedAssignTaskId)

        s = SchedulerReducer.reduce(s, SchedulerIntent.UpdateEditText("Alphabet"))
        assertEquals(null, s.editSession!!.selectedAssignTaskId)
        assertNotNull(s.editSession!!.newTaskDraftId)
        assertFalse(s.cells[cellId]!!.taskId == originalTaskId)
        assertEquals("Alphabet", s.tasks[s.cells[cellId]!!.taskId!!]!!.title)
    }

    @Test
    fun change_task_menu_first_entry_is_new_task() {
        var s = SchedulerState.empty()
        val cellId = s.lists[s.rootListId]!!.cellIds.first()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(cellId, "Alpha"))
        val entries = SchedulerDomain.changeTaskMenuEntries(s, cellId, "Alpha")
        assertEquals(null, entries.first().taskId)
        assertEquals("New task", entries.first().label)
    }

    @Test
    fun select_new_task_after_existing_creates_fresh_task_id() {
        var s = SchedulerState.empty()
        val alphaCell = s.lists[s.rootListId]!!.cellIds.first()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(alphaCell, "Alpha"))
        val alphaId = s.cells[alphaCell]!!.taskId!!
        s = SchedulerReducer.reduce(s, SchedulerIntent.ToggleExpand(alphaCell))
        val alphaListId = s.tasks[alphaId]!!.childListId!!
        val nestedCell = s.lists[alphaListId]!!.cellIds.first()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(nestedCell, "Nested"))

        val betaCell = s.lists[s.rootListId]!!.cellIds.last()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(betaCell, "Beta"))
        val betaId = s.cells[betaCell]!!.taskId!!

        s = SchedulerReducer.reduce(s, SchedulerIntent.BeginEdit(nestedCell))
        s = SchedulerReducer.reduce(s, SchedulerIntent.PickTaskFromMenu(betaId))
        val assignedOnPick = s.cells[nestedCell]!!.taskId!!
        assertEquals(betaId, assignedOnPick)

        s = SchedulerReducer.reduce(s, SchedulerIntent.SelectCreateAssignTask)
        val afterNewTask = s.cells[nestedCell]!!.taskId!!
        assertFalse(afterNewTask == betaId)
        assertEquals(null, s.editSession!!.selectedAssignTaskId)
        assertEquals(afterNewTask, s.editSession!!.newTaskDraftId)
    }

    @Test
    fun change_task_menu_selects_picked_task() {
        var s = SchedulerState.empty()
        val alphaCell = s.lists[s.rootListId]!!.cellIds.first()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(alphaCell, "Alpha"))
        s = SchedulerReducer.reduce(s, SchedulerIntent.ToggleExpand(alphaCell))
        val alphaListId = s.tasks[s.cells[alphaCell]!!.taskId!!]!!.childListId!!
        val nestedCell = s.lists[alphaListId]!!.cellIds.first()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(nestedCell, "Nested"))

        val betaCell = s.lists[s.rootListId]!!.cellIds.last()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(betaCell, "Beta"))
        val betaId = s.cells[betaCell]!!.taskId!!

        s = SchedulerReducer.reduce(s, SchedulerIntent.BeginEdit(nestedCell))
        s = SchedulerReducer.reduce(s, SchedulerIntent.PickTaskFromMenu(betaId))
        assertEquals(betaId, s.editSession!!.selectedAssignTaskId)
        assertEquals("Beta", s.editSession!!.draftText)
        assertEquals(betaId, s.cells[nestedCell]!!.taskId)
    }

    @Test
    fun begin_edit_opens_session_with_default_change_task_mode() {
        var s = SchedulerState.empty()
        val cellId = s.lists[s.rootListId]!!.cellIds.first()
        s =
            SchedulerReducer.reduce(
                s,
                SchedulerIntent.BeginEdit(cellId = cellId, initialText = "x"),
            )
        assertNotNull(s.editSession)
        assertEquals(cellId, s.editSession!!.cellId)
        assertEquals("x", s.editSession!!.draftText)
        assertEquals(CellEditMode.ChangeTask, s.editSession!!.mode)
    }

    @Test
    fun reenter_edit_does_not_duplicate_current_task_in_change_menu() {
        var s = SchedulerState.empty()
        val cellId = s.lists[s.rootListId]!!.cellIds.first()
        s =
            SchedulerReducer.reduce(
                s,
                SchedulerIntent.BeginEdit(cellId = cellId, initialText = "g"),
            )
        val taskId = s.cells[cellId]!!.taskId!!
        s = s.copy(editSession = null)

        s = SchedulerReducer.reduce(s, SchedulerIntent.BeginEdit(cellId))
        val session = s.editSession!!
        assertEquals(taskId, session.selectedAssignTaskId)
        assertEquals("g", session.draftText)

        val entries =
            SchedulerDomain.changeTaskMenuEntries(
                s,
                cellId,
                session.draftText,
                excludeTaskId = session.selectedAssignTaskId,
            )
        assertEquals(1, entries.size)
        assertEquals("New task", entries.single().label)
    }

    @Test
    fun second_cell_matching_sibling_title_hides_unassignable_task_from_menu() {
        var s = SchedulerState.empty()
        val firstCell = s.lists[s.rootListId]!!.cellIds.first()
        s =
            SchedulerReducer.reduce(
                s,
                SchedulerIntent.BeginEdit(cellId = firstCell, initialText = "g"),
            )
        val firstTaskId = s.cells[firstCell]!!.taskId!!
        s = s.copy(editSession = null)

        val secondCell = s.lists[s.rootListId]!!.cellIds.last()
        assertFalse(firstCell == secondCell)
        s =
            SchedulerReducer.reduce(
                s,
                SchedulerIntent.BeginEdit(cellId = secondCell, initialText = "g"),
            )
        val session = s.editSession!!

        // The only candidate ("g") is a sibling in the same list, so it is hidden (PRD §4).
        // Only the "New task" row remains, which keeps the menu collapsed.
        val entries =
            SchedulerDomain.changeTaskMenuEntries(
                s,
                secondCell,
                session.draftText,
                excludeTaskId = session.newTaskDraftId,
            )
        assertEquals(1, entries.size)
        assertEquals("New task", entries.single().label)
        assertFalse(firstTaskId in entries.mapNotNull { it.taskId })
    }

    @Test
    fun reentering_empty_second_cell_keeps_task_menu_collapsed() {
        var s = SchedulerState.empty()
        val firstCell = s.lists[s.rootListId]!!.cellIds.first()
        s =
            SchedulerReducer.reduce(
                s,
                SchedulerIntent.BeginEdit(cellId = firstCell, initialText = "g"),
            )
        s = s.copy(editSession = null)

        val secondCell = s.lists[s.rootListId]!!.cellIds.last()
        s = SchedulerReducer.reduce(s, SchedulerIntent.BeginEdit(secondCell))
        val session = s.editSession!!
        assertEquals("", session.draftText)

        val entries =
            SchedulerDomain.changeTaskMenuEntries(
                s,
                secondCell,
                session.draftText,
                excludeTaskId = session.selectedAssignTaskId ?: session.newTaskDraftId,
            )
        assertEquals(1, entries.size)
        assertEquals("New task", entries.single().label)
    }

    @Test
    fun typing_matching_title_shows_task_menu_when_task_is_in_other_branch() {
        var s = SchedulerState.empty()
        val alphaCell = s.lists[s.rootListId]!!.cellIds.first()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(alphaCell, "Alpha"))
        val alphaId = s.cells[alphaCell]!!.taskId!!
        s = SchedulerReducer.reduce(s, SchedulerIntent.ToggleExpand(alphaCell))
        val alphaListId = s.tasks[alphaId]!!.childListId!!
        val nestedCell = s.lists[alphaListId]!!.cellIds.first()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(nestedCell, "g"))
        val nestedGId = s.cells[nestedCell]!!.taskId!!

        val betaCell = s.lists[s.rootListId]!!.cellIds.last()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(betaCell, "Beta"))
        s = s.copy(editSession = null)

        s =
            SchedulerReducer.reduce(
                s,
                SchedulerIntent.BeginEdit(cellId = betaCell, initialText = "g"),
            )
        val session = s.editSession!!
        val entries =
            SchedulerDomain.changeTaskMenuEntries(
                s,
                betaCell,
                session.draftText,
                excludeTaskId = session.newTaskDraftId,
            )
        assertTrue(entries.size > 1, "Expected task menu entries, got: $entries")
        assertEquals(null, entries.first().taskId)
        assertTrue(nestedGId in entries.mapNotNull { it.taskId })
    }

    @Test
    fun typing_to_begin_edit_does_not_duplicate_new_task_in_change_menu() {
        var s = SchedulerState.empty()
        val cellId = s.lists[s.rootListId]!!.cellIds.first()
        s =
            SchedulerReducer.reduce(
                s,
                SchedulerIntent.BeginEdit(cellId = cellId, initialText = "h"),
            )
        val session = s.editSession!!
        assertEquals(null, session.selectedAssignTaskId)
        assertNotNull(session.newTaskDraftId)
        assertEquals("h", s.tasks[session.newTaskDraftId!!]!!.title)

        val entries =
            SchedulerDomain.changeTaskMenuEntries(
                s,
                cellId,
                session.draftText,
                excludeTaskId = session.newTaskDraftId,
            )
        assertEquals(1, entries.size)
        assertEquals(null, entries.single().taskId)
        assertEquals("New task", entries.single().label)
    }

    @Test
    fun cancel_edit_restores_tree_before_session() {
        var s = SchedulerState.empty()
        val cellId = s.lists[s.rootListId]!!.cellIds.first()
        val treeBefore = s.captureTree()
        s =
            SchedulerReducer.reduce(
                s,
                SchedulerIntent.BeginEdit(cellId = cellId, initialText = "Draft"),
            )
        assertNotNull(s.cells[cellId]!!.taskId)
        s = SchedulerReducer.reduce(s, SchedulerIntent.CancelEdit)
        assertEquals(null, s.editSession)
        assertEquals(treeBefore.tasks, s.tasks)
        assertEquals(treeBefore.cells[cellId]?.taskId, s.cells[cellId]?.taskId)
    }

    @Test
    fun click_other_cell_ends_edit_session() {
        var s = seedThreeTasks()
        val visible = SchedulerDomain.selectableVisibleOrder(s)
        s =
            SchedulerReducer.reduce(
                s,
                SchedulerIntent.BeginEdit(cellId = visible[0], initialText = "A"),
            )
        assertNotNull(s.editSession)
        s =
            SchedulerReducer.reduce(
                s,
                SchedulerIntent.ClickCell(
                    cellId = visible[1],
                    ctrl = false,
                    shift = false,
                    visibleOrder = visible,
                ),
            )
        assertEquals(null, s.editSession)
    }

    @Test
    fun assign_task_id_is_undoable() {
        var s = SchedulerState.empty()
        val a = s.lists[s.rootListId]!!.cellIds.first()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(a, "A"))
        val taskA = s.cells[a]!!.taskId!!

        val b = s.lists[s.rootListId]!!.cellIds.last()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(b, "B"))
        s = SchedulerReducer.reduce(s, SchedulerIntent.ToggleExpand(b))
        val child = s.lists[s.tasks[s.cells[b]!!.taskId!!]!!.childListId!!]!!.cellIds.first()

        s = SchedulerReducer.reduce(s, SchedulerIntent.AssignTaskId(child, taskA))
        assertEquals(taskA, s.cells[child]!!.taskId)

        s = SchedulerReducer.reduce(s, SchedulerIntent.Undo)
        assertEquals(null, s.cells[child]!!.taskId)
        assertEquals(listOf(a), s.tasks[taskA]!!.occurrences)

        s = SchedulerReducer.reduce(s, SchedulerIntent.Redo)
        assertEquals(taskA, s.cells[child]!!.taskId)
    }

    @Test
    fun empty_change_task_menu_matches_no_existing_tasks() {
        var s = SchedulerState.empty()
        val cellId = s.lists[s.rootListId]!!.cellIds.first()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(cellId, "yu"))

        // An empty query yields no existing-task matches at all (PRD §4): with no text
        // typed there is nothing to "match", so only the implicit "New task" row exists.
        val emptySlot = s.lists[s.rootListId]!!.cellIds.last()
        assertTrue(SchedulerDomain.eligibleAssignTaskIds(s, emptySlot, "").isEmpty())
    }

    @Test
    fun entering_empty_child_of_sibling_does_not_list_other_branch_task() {
        // Anomaly repro: first cell "yu", second cell "g", then edit the empty child of "g".
        var s = SchedulerState.empty()
        val firstCell = s.lists[s.rootListId]!!.cellIds.first()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(firstCell, "yu"))
        val yuTaskId = s.cells[firstCell]!!.taskId!!

        val secondCell = s.lists[s.rootListId]!!.cellIds[1]
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(secondCell, "g"))
        val gTaskId = s.cells[secondCell]!!.taskId!!

        val gChildListId = s.tasks[gTaskId]!!.childListId!!
        val gChild = s.lists[gChildListId]!!.cellIds.first()

        s = SchedulerReducer.reduce(s, SchedulerIntent.BeginEdit(gChild))
        val session = s.editSession!!
        assertEquals("", session.draftText)

        val entries =
            SchedulerDomain.changeTaskMenuEntries(
                s,
                gChild,
                session.draftText,
                excludeTaskId = session.selectedAssignTaskId ?: session.newTaskDraftId,
            )
        // Only "New task" — "yu" must NOT appear even though it is otherwise assignable.
        assertEquals(1, entries.size)
        assertEquals("New task", entries.single().label)
        assertFalse(yuTaskId in entries.mapNotNull { it.taskId })
        assertTrue(SchedulerDomain.canAssignTaskId(s, gChild, yuTaskId))
    }

    @Test
    fun typing_in_empty_child_keeps_task_menu_collapsed_and_shows_title_suggestion() {
        // Anomaly repro continued: typing "m" must surface the "main" title suggestion
        // and must NOT surface any existing-task row.
        var s = SchedulerState.empty()
        val firstCell = s.lists[s.rootListId]!!.cellIds.first()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(firstCell, "yu"))
        val secondCell = s.lists[s.rootListId]!!.cellIds[1]
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(secondCell, "g"))
        val gTaskId = s.cells[secondCell]!!.taskId!!
        val gChild = s.lists[s.tasks[gTaskId]!!.childListId!!]!!.cellIds.first()

        s = SchedulerReducer.reduce(s, SchedulerIntent.BeginEdit(gChild))
        s = SchedulerReducer.reduce(s, SchedulerIntent.UpdateEditText("m"))
        val session = s.editSession!!
        assertEquals("m", session.draftText)

        val entries =
            SchedulerDomain.changeTaskMenuEntries(
                s,
                gChild,
                session.draftText,
                excludeTaskId = session.selectedAssignTaskId ?: session.newTaskDraftId,
            )
        assertEquals(1, entries.size)
        assertEquals("New task", entries.single().label)

        val suggestions = SchedulerDomain.titleSuggestions(s, "m")
        assertEquals(listOf("main"), suggestions)
    }

    @Test
    fun partial_title_keeps_task_menu_collapsed_until_exact_match() {
        // Anomaly repro: root list is "yu" and "g". Editing g's empty child and typing
        // "y" must NOT surface "yu" (partial match). The "yu" row may appear only once
        // the text equals the title exactly (PRD §4 — exact-title matching).
        var s = SchedulerState.empty()
        val firstCell = s.lists[s.rootListId]!!.cellIds.first()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(firstCell, "yu"))
        val yuTaskId = s.cells[firstCell]!!.taskId!!

        val secondCell = s.lists[s.rootListId]!!.cellIds[1]
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(secondCell, "g"))
        val gTaskId = s.cells[secondCell]!!.taskId!!
        val gChild = s.lists[s.tasks[gTaskId]!!.childListId!!]!!.cellIds.first()

        // "yu" is assignable to g's child (not a sibling, not an ancestor).
        assertTrue(SchedulerDomain.canAssignTaskId(s, gChild, yuTaskId))

        s = SchedulerReducer.reduce(s, SchedulerIntent.BeginEdit(gChild))

        // Typing the partial "y": the menu must stay collapsed (only "New task").
        s = SchedulerReducer.reduce(s, SchedulerIntent.UpdateEditText("y"))
        val partialEntries =
            SchedulerDomain.changeTaskMenuEntries(
                s,
                gChild,
                s.editSession!!.draftText,
                excludeTaskId = s.editSession!!.selectedAssignTaskId ?: s.editSession!!.newTaskDraftId,
            )
        assertEquals(1, partialEntries.size)
        assertEquals("New task", partialEntries.single().label)
        assertFalse(yuTaskId in partialEntries.mapNotNull { it.taskId })

        // Typing the full "yu": the "yu" row now appears alongside "New task".
        s = SchedulerReducer.reduce(s, SchedulerIntent.UpdateEditText("yu"))
        val exactEntries =
            SchedulerDomain.changeTaskMenuEntries(
                s,
                gChild,
                s.editSession!!.draftText,
                excludeTaskId = s.editSession!!.selectedAssignTaskId ?: s.editSession!!.newTaskDraftId,
            )
        assertEquals(2, exactEntries.size)
        assertEquals("New task", exactEntries.first().label)
        assertTrue(yuTaskId in exactEntries.mapNotNull { it.taskId })
    }

    @Test
    fun title_suggestions_on_empty_db_list_root_and_main() {
        // Entering Edit Mode on a fresh DB with an empty draft must surface the existing
        // titles ("root", "main"), sorted alphabetically (PRD §4 Menu 2).
        val s = SchedulerState.empty()
        assertEquals(listOf("main", "root"), SchedulerDomain.titleSuggestions(s, ""))
    }

    @Test
    fun title_suggestions_for_empty_input_list_all_titles() {
        var s = SchedulerState.empty()
        val cellId = s.lists[s.rootListId]!!.cellIds.first()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(cellId, "Alpha"))

        assertEquals(listOf("Alpha", "main", "root"), SchedulerDomain.titleSuggestions(s, ""))
    }

    @Test
    fun title_suggestions_show_single_match() {
        // Single suggestion must still be returned (no "only one element" rule for Menu 2).
        val s = SchedulerState.empty()
        assertEquals(listOf("main"), SchedulerDomain.titleSuggestions(s, "m"))
        assertEquals(listOf("main"), SchedulerDomain.titleSuggestions(s, "ai"))
    }

    @Test
    fun double_clicking_existing_cell_still_lists_other_matching_tasks() {
        // Regression guard: non-empty draft (e.g. re-editing an existing title) must
        // still surface other matching tasks in another branch.
        var s = SchedulerState.empty()
        val alphaCell = s.lists[s.rootListId]!!.cellIds.first()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(alphaCell, "Report"))
        val alphaId = s.cells[alphaCell]!!.taskId!!
        s = SchedulerReducer.reduce(s, SchedulerIntent.ToggleExpand(alphaCell))
        val alphaChild = s.lists[s.tasks[alphaId]!!.childListId!!]!!.cellIds.first()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(alphaChild, "Report"))
        val nestedReportId = s.cells[alphaChild]!!.taskId!!

        val betaCell = s.lists[s.rootListId]!!.cellIds.last()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(betaCell, "Beta"))
        s = s.copy(editSession = null)

        s = SchedulerReducer.reduce(s, SchedulerIntent.BeginEdit(betaCell, initialText = "Report"))
        val session = s.editSession!!
        val entries =
            SchedulerDomain.changeTaskMenuEntries(
                s,
                betaCell,
                session.draftText,
                excludeTaskId = session.newTaskDraftId,
            )
        assertTrue(entries.size > 1)
        assertTrue(nestedReportId in entries.mapNotNull { it.taskId })
    }

    @Test
    fun persistence_round_trips_durable_tree_state() {
        var s = SchedulerState.empty()
        val cellId = s.lists[s.rootListId]!!.cellIds.first()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(cellId, "Persisted"))
        s = SchedulerReducer.reduce(s, SchedulerIntent.ToggleExpand(cellId))

        val restored = SchedulerStateCodec.decode(SchedulerStateCodec.encode(s))!!

        assertEquals(s.cells, restored.cells)
        assertEquals(s.lists, restored.lists)
        assertEquals(s.tasks, restored.tasks)
        assertEquals(s.expanded, restored.expanded)
        assertEquals(s.selection, restored.selection)
        assertEquals(s.titleToTaskIds, restored.titleToTaskIds)
        assertEquals(s.nextTaskCounter, restored.nextTaskCounter)
    }

    @Test
    fun switching_from_rename_to_change_task_reverts_shared_titles() {
        var s = SchedulerState.empty()
        val branchA = s.lists[s.rootListId]!!.cellIds.first()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(branchA, "Shared"))
        val sharedTaskId = s.cells[branchA]!!.taskId!!

        val branchB = s.lists[s.rootListId]!!.cellIds.last()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(branchB, "Branch B"))
        s = SchedulerReducer.reduce(s, SchedulerIntent.ToggleExpand(branchB))
        val branchBChild = s.lists[s.tasks[s.cells[branchB]!!.taskId!!]!!.childListId!!]!!.cellIds.first()
        s = SchedulerReducer.reduce(s, SchedulerIntent.AssignTaskId(branchBChild, sharedTaskId))

        s = SchedulerReducer.reduce(s, SchedulerIntent.BeginEdit(branchA))
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetEditMode(CellEditMode.Rename))
        s = SchedulerReducer.reduce(s, SchedulerIntent.UpdateEditText("Renamed draft"))

        assertEquals("Renamed draft", s.tasks[sharedTaskId]!!.title)

        s = SchedulerReducer.reduce(s, SchedulerIntent.SetEditMode(CellEditMode.ChangeTask))

        assertEquals("Shared", s.tasks[sharedTaskId]!!.title)
        assertEquals("Renamed draft", s.editSession!!.draftText)
    }

    @Test
    fun begin_edit_pins_to_clicked_occurrence_of_a_mirrored_cell() {
        // Anomaly: two expanded cells share one taskId, so the same child cell is mirrored
        // under both. Entering edit on the child via one parent must NOT mark the mirrored
        // copy under the other parent as editing. The edit session records the occurrence
        // (renderVia) just like the selection does.
        var s = SchedulerState.empty()
        val branchA = s.lists[s.rootListId]!!.cellIds.first()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(branchA, "Shared"))
        val sharedTaskId = s.cells[branchA]!!.taskId!!
        val sharedChild = s.lists[s.tasks[sharedTaskId]!!.childListId!!]!!.cellIds.first()

        val branchB = s.lists[s.rootListId]!!.cellIds[1]
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(branchB, "Branch B"))
        s = SchedulerReducer.reduce(s, SchedulerIntent.ToggleExpand(branchB))
        val branchBChild = s.lists[s.tasks[s.cells[branchB]!!.taskId!!]!!.childListId!!]!!.cellIds.first()
        s = SchedulerReducer.reduce(s, SchedulerIntent.AssignTaskId(branchBChild, sharedTaskId))

        // Both branchA and branchBChild now carry sharedTaskId; expand both so sharedChild
        // is rendered under each (renderVia = branchA and renderVia = branchBChild).
        s = SchedulerReducer.reduce(s, SchedulerIntent.ToggleExpand(branchA))
        s = SchedulerReducer.reduce(s, SchedulerIntent.ToggleExpand(branchBChild))

        // Select the mirrored child via the branchBChild occurrence, then edit it.
        val visible = SchedulerDomain.selectableVisibleOrder(s)
        s =
            SchedulerReducer.reduce(
                s,
                SchedulerIntent.ClickCell(
                    cellId = sharedChild,
                    ctrl = false,
                    shift = false,
                    visibleOrder = visible,
                    renderVia = branchBChild,
                ),
            )
        s = SchedulerReducer.reduce(s, SchedulerIntent.BeginEdit(sharedChild))

        // The edit session pins to the branchBChild occurrence — the UI shows the editor only
        // where the local renderVia matches, leaving the branchA copy untouched.
        assertEquals(sharedChild, s.editSession!!.cellId)
        assertEquals(branchBChild, s.editSession!!.renderVia)
        assertTrue(
            SchedulerDomain.isInVisualSubtree(s, sharedChild, branchA),
            "sharedChild is also mirrored under branchA",
        )
        assertNotEquals(branchA, s.editSession!!.renderVia)
    }

    @Test
    fun load_cancels_interrupted_edit_session() {
        var s = SchedulerState.empty()
        val cellId = s.lists[s.rootListId]!!.cellIds.first()
        s =
            SchedulerReducer.reduce(
                s,
                SchedulerIntent.BeginEdit(cellId = cellId, initialText = "Draft"),
            )
        val encoded = SchedulerStateCodec.encode(s)
        val decoded = SchedulerStateCodec.decode(encoded)!!
        assertNotNull(decoded.editSession)

        val loaded = TaskSchedulerViewModel.loadInitialState(store = null, initial = decoded)
        assertEquals(null, loaded.editSession)
        assertEquals(null, loaded.cells[cellId]!!.taskId)
    }

    @Test
    fun viewmodel_reloads_persisted_state_from_store() {
        val store = InMemoryStore()

        val first = TaskSchedulerViewModel(store = store)
        val rootList = first.state.value.rootListId
        val cellId = first.state.value.lists[rootList]!!.cellIds.first()
        first.dispatch(SchedulerIntent.SetCellTitle(cellId, "Remembered"))
        assertNotNull(store.payload)

        // A fresh ViewModel (simulating an app restart) must rehydrate from the store.
        val second = TaskSchedulerViewModel(store = store)
        val taskId = second.state.value.cells[cellId]!!.taskId!!
        assertEquals("Remembered", second.state.value.tasks[taskId]!!.title)
    }

    private class InMemoryStore : SchedulerStore {
        var payload: String? = null

        override fun load(): String? = payload

        override fun save(data: String) {
            payload = data
        }
    }

    @Test
    fun click_on_selected_cell_in_range_preserves_multi_selection() {
        var s = seedThreeTasks()
        val visible = SchedulerDomain.selectableVisibleOrder(s)

        s = SchedulerReducer.reduce(
            s,
            SchedulerIntent.ClickCell(cellId = visible[0], ctrl = false, shift = false, visibleOrder = visible),
        )
        s = SchedulerReducer.reduce(
            s,
            SchedulerIntent.ClickCell(cellId = visible[2], ctrl = false, shift = true, visibleOrder = visible),
        )
        s = SchedulerReducer.reduce(
            s,
            SchedulerIntent.ClickCell(cellId = visible[1], ctrl = false, shift = false, visibleOrder = visible),
        )

        assertEquals(visible[1], s.selection.main)
        assertEquals(setOf(visible[0], visible[1], visible[2]), s.selection.selected)
    }

    @Test
    fun stale_deferred_single_click_does_not_resteal_main_selection() {
        var s = seedThreeTasks()
        val visible = SchedulerDomain.selectableVisibleOrder(s)

        // Click cell A (its gesture's deferred forceClearMulti reset is still "pending").
        s = SchedulerReducer.reduce(
            s,
            SchedulerIntent.ClickCell(cellId = visible[0], ctrl = false, shift = false, visibleOrder = visible),
        )
        // User clicks cell B before A's double-tap timer expires: main moves to B.
        s = SchedulerReducer.reduce(
            s,
            SchedulerIntent.ClickCell(cellId = visible[1], ctrl = false, shift = false, visibleOrder = visible),
        )
        // A's stale timer now fires its deferred reset — it must NOT steal main back to A.
        s = SchedulerReducer.reduce(
            s,
            SchedulerIntent.ClickCell(
                cellId = visible[0],
                ctrl = false,
                shift = false,
                visibleOrder = visible,
                forceClearMulti = true,
            ),
        )

        assertEquals(visible[1], s.selection.main)
    }

    @Test
    fun double_click_non_move_clears_multi_selection() {
        var s = seedThreeTasks()
        val visible = SchedulerDomain.selectableVisibleOrder(s)

        s = SchedulerReducer.reduce(
            s,
            SchedulerIntent.ClickCell(cellId = visible[0], ctrl = false, shift = false, visibleOrder = visible),
        )
        s = SchedulerReducer.reduce(
            s,
            SchedulerIntent.ClickCell(cellId = visible[2], ctrl = false, shift = true, visibleOrder = visible),
        )
        // The gesture's first press dispatches a non-collapsing click that keeps the range intact
        // and moves main onto the clicked cell, before the deferred forceClearMulti reset arrives.
        s = SchedulerReducer.reduce(
            s,
            SchedulerIntent.ClickCell(cellId = visible[1], ctrl = false, shift = false, visibleOrder = visible),
        )
        s = SchedulerReducer.reduce(
            s,
            SchedulerIntent.ClickCell(
                cellId = visible[1],
                ctrl = false,
                shift = false,
                visibleOrder = visible,
                forceClearMulti = true,
            ),
        )

        assertEquals(visible[1], s.selection.main)
        assertTrue(s.selection.selected.isEmpty())
    }

    @Test
    fun return_or_delete_empties_all_selected_cells_when_not_editing() {
        var s = seedThreeTasks()
        val visible = SchedulerDomain.selectableVisibleOrder(s)

        s = SchedulerReducer.reduce(
            s,
            SchedulerIntent.ClickCell(cellId = visible[0], ctrl = false, shift = false, visibleOrder = visible),
        )
        s = SchedulerReducer.reduce(
            s,
            SchedulerIntent.ClickCell(cellId = visible[1], ctrl = false, shift = true, visibleOrder = visible),
        )
        s = SchedulerReducer.reduce(s, SchedulerIntent.EmptySelectedCells)

        // Emptied non-bottom cells are removed; only the trailing placeholder stays empty.
        assertFalse(visible[0] in s.cells)
        assertFalse(visible[1] in s.cells)
        assertTrue(visible[2] in s.cells)
        val rootCells = s.lists[s.rootListId]!!.cellIds
        assertEquals(2, rootCells.size)
        assertEquals(visible[2], rootCells.first())
        assertEquals(null, s.cells[rootCells.last()]!!.taskId)
    }

    @Test
    fun empty_selected_first_cell_removes_it_and_keeps_bottom_placeholder() {
        var s = seedThreeTasks()
        val visible = SchedulerDomain.selectableVisibleOrder(s)
        val first = visible[0]
        val second = visible[1]

        s = SchedulerReducer.reduce(
            s,
            SchedulerIntent.ClickCell(cellId = first, ctrl = false, shift = false, visibleOrder = visible),
        )
        s = SchedulerReducer.reduce(s, SchedulerIntent.EmptySelectedCells)

        assertFalse(first in s.cells)
        val rootCells = s.lists[s.rootListId]!!.cellIds
        assertEquals(3, rootCells.size)
        assertEquals(second, rootCells.first())
        assertEquals(null, s.cells[rootCells.last()]!!.taskId)
        assertEquals(second, s.selection.main)

        s = SchedulerReducer.reduce(s, SchedulerIntent.Undo)
        assertTrue(first in s.cells)
        assertEquals(4, s.lists[s.rootListId]!!.cellIds.size)
        assertEquals(first, s.selection.main)
    }

    @Test
    fun empty_selected_does_nothing_during_edit_mode() {
        var s = seedThreeTasks()
        val visible = SchedulerDomain.selectableVisibleOrder(s)
        val cell = visible[0]

        s = SchedulerReducer.reduce(
            s,
            SchedulerIntent.ClickCell(cellId = cell, ctrl = false, shift = false, visibleOrder = visible),
        )
        s = SchedulerReducer.reduce(s, SchedulerIntent.BeginEdit(cell))
        val titleBefore = s.tasks[s.cells[cell]!!.taskId!!]!!.title

        s = SchedulerReducer.reduce(s, SchedulerIntent.EmptySelectedCells)

        assertEquals(titleBefore, s.tasks[s.cells[cell]!!.taskId!!]!!.title)
    }

    @Test
    fun move_selected_cells_reorders_within_list() {
        var s = seedThreeTasks()
        val listId = s.rootListId
        val visible = SchedulerDomain.selectableVisibleOrder(s)

        s = SchedulerReducer.reduce(
            s,
            SchedulerIntent.ClickCell(cellId = visible[0], ctrl = false, shift = false, visibleOrder = visible),
        )
        s = SchedulerReducer.reduce(
            s,
            SchedulerIntent.ClickCell(cellId = visible[1], ctrl = false, shift = true, visibleOrder = visible),
        )
        s = SchedulerReducer.reduce(
            s,
            SchedulerIntent.MoveSelectedCells(targetCellId = visible[2], insertBefore = false),
        )

        val listCells = s.lists[listId]!!.cellIds
        assertEquals(visible[2], listCells[0])
        assertEquals(visible[0], listCells[1])
        assertEquals(visible[1], listCells[2])
    }

    @Test
    fun move_selected_cell_into_sibling_sublist_relocates_across_layers() {
        var s = seedThreeTasks()
        val root = s.rootListId
        val a = s.lists[root]!!.cellIds[0]
        val b = s.lists[root]!!.cellIds[1]
        val bTask = s.cells[b]!!.taskId!!
        val aTask = s.cells[a]!!.taskId!!

        s = SchedulerReducer.reduce(s, SchedulerIntent.ToggleExpand(a))
        val aChildList = s.tasks[aTask]!!.childListId!!
        val aChild = s.lists[aChildList]!!.cellIds.first()

        val visible = SchedulerDomain.selectableVisibleOrder(s)
        s = SchedulerReducer.reduce(
            s,
            SchedulerIntent.ClickCell(cellId = b, ctrl = false, shift = false, visibleOrder = visible),
        )
        s = SchedulerReducer.reduce(
            s,
            SchedulerIntent.MoveSelectedCells(targetCellId = aChild, insertBefore = true),
        )

        // B left the root list and now lives in A's child list (a different layer of the tree).
        assertFalse(b in s.lists[root]!!.cellIds)
        assertTrue(b in s.lists[aChildList]!!.cellIds)
        assertEquals(aChildList, s.cells[b]!!.parentListId)
        // The task tree is relinked: B is now a child of A, no longer of main.
        assertTrue(bTask in s.tasks[aTask]!!.childTaskIds)
        assertFalse(bTask in s.tasks[WellKnownIds.MAIN_TASK]!!.childTaskIds)

        // The cross-list move round-trips through undo.
        s = SchedulerReducer.reduce(s, SchedulerIntent.Undo)
        assertTrue(b in s.lists[root]!!.cellIds)
        assertEquals(root, s.cells[b]!!.parentListId)
    }

    @Test
    fun move_into_mirrored_subtree_links_task_under_shared_parent() {
        var s = SchedulerState.empty()
        val root = s.rootListId
        val branchA = s.lists[root]!!.cellIds.first()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(branchA, "Shared"))
        val sharedTaskId = s.cells[branchA]!!.taskId!!
        val sharedChildList = s.tasks[sharedTaskId]!!.childListId!!

        val branchB = s.lists[root]!!.cellIds.last()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(branchB, "Branch B"))
        s = SchedulerReducer.reduce(s, SchedulerIntent.ToggleExpand(branchB))
        val branchBChild =
            s.lists[s.tasks[s.cells[branchB]!!.taskId!!]!!.childListId!!]!!.cellIds.first()
        // branchBChild now mirrors the same sub-tree as branchA (shares its taskId/childList).
        s = SchedulerReducer.reduce(s, SchedulerIntent.AssignTaskId(branchBChild, sharedTaskId))

        // A movable cell at the root.
        val movable = s.lists[root]!!.cellIds.last()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(movable, "Movable"))
        val movableTask = s.cells[movable]!!.taskId!!
        val placeholderInShared = s.lists[sharedChildList]!!.cellIds.first()

        val visible = SchedulerDomain.selectableVisibleOrder(s)
        s = SchedulerReducer.reduce(
            s,
            SchedulerIntent.ClickCell(cellId = movable, ctrl = false, shift = false, visibleOrder = visible),
        )
        s = SchedulerReducer.reduce(
            s,
            SchedulerIntent.MoveSelectedCells(targetCellId = placeholderInShared, insertBefore = true),
        )

        // Inserted into the shared child list → mirrored under every occurrence of the shared task.
        assertTrue(movable in s.lists[sharedChildList]!!.cellIds)
        assertEquals(sharedChildList, s.cells[movable]!!.parentListId)
        assertTrue(movableTask in s.tasks[sharedTaskId]!!.childTaskIds)
    }

    @Test
    fun move_into_own_subtree_is_rejected() {
        var s = SchedulerState.empty()
        val root = s.rootListId
        val parent = s.lists[root]!!.cellIds.first()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(parent, "Parent"))
        s = SchedulerReducer.reduce(s, SchedulerIntent.ToggleExpand(parent))
        val childList = s.tasks[s.cells[parent]!!.taskId!!]!!.childListId!!
        val child = s.lists[childList]!!.cellIds.first()

        val visible = SchedulerDomain.selectableVisibleOrder(s)
        s = SchedulerReducer.reduce(
            s,
            SchedulerIntent.ClickCell(cellId = parent, ctrl = false, shift = false, visibleOrder = visible),
        )
        val listsBefore = s.lists
        val historyBefore = s.history.units.size
        s = SchedulerReducer.reduce(
            s,
            SchedulerIntent.MoveSelectedCells(targetCellId = child, insertBefore = true),
        )

        // PRD constraint 2: a cell cannot move into its own sub-tree (would create a cycle).
        assertEquals(listsBefore, s.lists)
        assertTrue(parent in s.lists[root]!!.cellIds)
        assertEquals(historyBefore, s.history.units.size)
    }

    @Test
    fun sequential_selection_detects_contiguous_block_in_same_list() {
        var s = seedThreeTasks()
        val visible = SchedulerDomain.selectableVisibleOrder(s)

        s = SchedulerReducer.reduce(
            s,
            SchedulerIntent.ClickCell(cellId = visible[0], ctrl = false, shift = false, visibleOrder = visible),
        )
        s = SchedulerReducer.reduce(
            s,
            SchedulerIntent.ClickCell(cellId = visible[1], ctrl = false, shift = true, visibleOrder = visible),
        )
        assertTrue(SchedulerDomain.isSequentialSelectionInSameList(s, s.selection))

        val listCells = s.lists[s.rootListId]!!.cellIds
        val nonContiguous =
            SchedulerSelection(main = listCells[2], selected = setOf(listCells[0], listCells[2]))
        assertFalse(SchedulerDomain.isSequentialSelectionInSameList(s, nonContiguous))
    }

    @Test
    fun shift_arrow_selects_range_in_visible_order() {
        var s = seedThreeTasks()
        val visible = SchedulerDomain.selectableVisibleOrder(s)

        s = SchedulerReducer.reduce(
            s,
            SchedulerIntent.ClickCell(cellId = visible[0], ctrl = false, shift = false, visibleOrder = visible),
        )
        s = SchedulerReducer.reduce(
            s,
            SchedulerIntent.NavigateSelection(direction = SelectionNavigate.Next, shift = true),
        )

        assertEquals(visible[1], s.selection.main)
        assertEquals(setOf(visible[0], visible[1]), s.selection.selected)
        assertEquals(visible[0], s.selection.rangeAnchor)

        s = SchedulerReducer.reduce(
            s,
            SchedulerIntent.NavigateSelection(direction = SelectionNavigate.Next, shift = true),
        )

        assertEquals(visible[2], s.selection.main)
        assertEquals(setOf(visible[0], visible[1], visible[2]), s.selection.selected)
        assertEquals(visible[0], s.selection.rangeAnchor)
    }

    @Test
    fun shift_arrow_resets_disjoint_ctrl_multi_select() {
        var s = seedThreeTasks()
        val visible = SchedulerDomain.selectableVisibleOrder(s)

        s = SchedulerReducer.reduce(
            s,
            SchedulerIntent.ClickCell(cellId = visible[0], ctrl = false, shift = false, visibleOrder = visible),
        )
        s = SchedulerReducer.reduce(
            s,
            SchedulerIntent.ClickCell(cellId = visible[2], ctrl = true, shift = false, visibleOrder = visible),
        )
        assertEquals(2, s.selection.selected.size)
        assertEquals(null, s.selection.rangeAnchor)

        s = SchedulerReducer.reduce(
            s,
            SchedulerIntent.NavigateSelection(direction = SelectionNavigate.Previous, shift = true),
        )

        assertEquals(visible[1], s.selection.main)
        assertEquals(setOf(visible[1], visible[2]), s.selection.selected)
        assertEquals(visible[2], s.selection.rangeAnchor)
    }

    @Test
    fun shift_click_sets_range_anchor_for_shift_arrow_extension() {
        var s = seedThreeTasks()
        val visible = SchedulerDomain.selectableVisibleOrder(s)

        s = SchedulerReducer.reduce(
            s,
            SchedulerIntent.ClickCell(cellId = visible[0], ctrl = false, shift = false, visibleOrder = visible),
        )
        s = SchedulerReducer.reduce(
            s,
            SchedulerIntent.ClickCell(cellId = visible[1], ctrl = false, shift = true, visibleOrder = visible),
        )
        assertEquals(visible[0], s.selection.rangeAnchor)

        s = SchedulerReducer.reduce(
            s,
            SchedulerIntent.NavigateSelection(direction = SelectionNavigate.Next, shift = true),
        )

        assertEquals(setOf(visible[0], visible[1], visible[2]), s.selection.selected)
        assertEquals(visible[0], s.selection.rangeAnchor)
        assertEquals(visible[2], s.selection.main)
    }

    @Test
    fun arrow_navigation_moves_main_and_clears_multi_selection() {
        var s = seedThreeTasks()
        val visible = SchedulerDomain.selectableVisibleOrder(s)

        s = SchedulerReducer.reduce(
            s,
            SchedulerIntent.ClickCell(cellId = visible[0], ctrl = false, shift = false, visibleOrder = visible),
        )
        s = SchedulerReducer.reduce(
            s,
            SchedulerIntent.ClickCell(cellId = visible[2], ctrl = false, shift = true, visibleOrder = visible),
        )
        assertEquals(visible[2], s.selection.main)
        assertEquals(3, s.selection.selected.size)

        s = SchedulerReducer.reduce(s, SchedulerIntent.NavigateSelection(SelectionNavigate.Next))

        assertTrue(s.selection.selected.isEmpty())
        assertEquals(visible[3], s.selection.main)
    }

    @Test
    fun arrow_down_follows_the_displayed_occurrence_of_a_mirrored_cell() {
        // Anomaly: a cell whose task is shared by two expanded parents is rendered twice, so the
        // same cellId appears at two positions in the visible order. "Down" must step relative to
        // the occurrence actually selected, not the first copy in the list.
        var s = SchedulerState.empty()
        val branchA = s.lists[s.rootListId]!!.cellIds.first()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(branchA, "Shared"))
        val sharedTaskId = s.cells[branchA]!!.taskId!!
        val sharedChild = s.lists[s.tasks[sharedTaskId]!!.childListId!!]!!.cellIds.first()

        // Titling branchA appended a trailing root cell — title it as the second branch.
        val branchB = s.lists[s.rootListId]!!.cellIds[1]
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(branchB, "Branch B"))
        s = SchedulerReducer.reduce(s, SchedulerIntent.ToggleExpand(branchB))
        val bList = s.tasks[s.cells[branchB]!!.taskId!!]!!.childListId!!
        val branchBChild = s.lists[bList]!!.cellIds[0]
        // Give branchBChild a sibling below it ("B2"), then make it share branchA's task.
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(branchBChild, "BC"))
        val bSibling = s.lists[bList]!!.cellIds[1]
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(bSibling, "B2"))
        s = SchedulerReducer.reduce(s, SchedulerIntent.AssignTaskId(branchBChild, sharedTaskId))

        // Expand both occurrences so sharedChild is mirrored under branchA and branchBChild.
        s = SchedulerReducer.reduce(s, SchedulerIntent.ToggleExpand(branchA))
        s = SchedulerReducer.reduce(s, SchedulerIntent.ToggleExpand(branchBChild))

        // Sanity: sharedChild is displayed twice — once under each parent occurrence.
        val occurrences = SchedulerDomain.selectableVisibleOccurrences(s)
        assertEquals(
            listOf(branchA, branchBChild),
            occurrences.filter { it.cellId == sharedChild }.map { it.renderVia },
        )

        // Select the second occurrence (rendered under branchBChild) and press Down.
        val visible = SchedulerDomain.selectableVisibleOrder(s)
        s =
            SchedulerReducer.reduce(
                s,
                SchedulerIntent.ClickCell(
                    cellId = sharedChild,
                    ctrl = false,
                    shift = false,
                    visibleOrder = visible,
                    renderVia = branchBChild,
                ),
            )
        assertEquals(sharedChild, s.selection.main)
        assertEquals(branchBChild, s.selection.renderVia)

        s = SchedulerReducer.reduce(s, SchedulerIntent.NavigateSelection(SelectionNavigate.Next))

        // Down lands on the row shown beneath that occurrence — "B2" under branchB — and NOT on
        // branchB (the row beneath the *first* occurrence, which the first-occurrence bug picked).
        assertEquals(bSibling, s.selection.main)
        assertEquals(branchB, s.selection.renderVia)
        assertNotEquals(branchB, s.selection.main)
    }

    @Test
    fun arrow_navigation_ignored_during_edit_mode() {
        var s = seedThreeTasks()
        val visible = SchedulerDomain.selectableVisibleOrder(s)
        val cell = visible[0]

        s = SchedulerReducer.reduce(
            s,
            SchedulerIntent.ClickCell(cellId = cell, ctrl = false, shift = false, visibleOrder = visible),
        )
        s = SchedulerReducer.reduce(s, SchedulerIntent.BeginEdit(cell))
        s = SchedulerReducer.reduce(s, SchedulerIntent.NavigateSelection(SelectionNavigate.Next))

        assertEquals(cell, s.selection.main)
    }

    @Test
    fun enter_cycles_main_selection_among_selected_cells() {
        var s = seedThreeTasks()
        val visible = SchedulerDomain.selectableVisibleOrder(s)

        s = SchedulerReducer.reduce(
            s,
            SchedulerIntent.ClickCell(cellId = visible[0], ctrl = false, shift = false, visibleOrder = visible),
        )
        s = SchedulerReducer.reduce(
            s,
            SchedulerIntent.ClickCell(cellId = visible[2], ctrl = false, shift = true, visibleOrder = visible),
        )
        assertEquals(visible[2], s.selection.main)

        s = SchedulerReducer.reduce(s, SchedulerIntent.CycleMainSelection(forward = true))
        assertEquals(visible[0], s.selection.main)
        assertEquals(setOf(visible[0], visible[1], visible[2]), s.selection.selected)

        s = SchedulerReducer.reduce(s, SchedulerIntent.CycleMainSelection(forward = false))
        assertEquals(visible[2], s.selection.main)
    }

    @Test
    fun tab_on_populated_cell_expands_and_selects_first_child() {
        var s = SchedulerState.empty()
        val parent = s.lists[s.rootListId]!!.cellIds.first()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(parent, "Parent"))
        val childListId = s.tasks[s.cells[parent]!!.taskId!!]!!.childListId!!
        val child = s.lists[childListId]!!.cellIds.first()

        s = SchedulerReducer.reduce(
            s,
            SchedulerIntent.ClickCell(cellId = parent, ctrl = false, shift = false, visibleOrder = emptyList()),
        )
        s = SchedulerReducer.reduce(s, SchedulerIntent.SelectFirstChild)

        assertTrue(parent in s.expanded)
        assertEquals(child, s.selection.main)
        assertTrue(s.selection.selected.isEmpty())
    }

    @Test
    fun copy_selection_uses_contiguous_block_or_main_only() {
        var s = seedThreeTasks()
        val visible = SchedulerDomain.selectableVisibleOrder(s)

        s = SchedulerReducer.reduce(
            s,
            SchedulerIntent.ClickCell(cellId = visible[0], ctrl = false, shift = false, visibleOrder = visible),
        )
        s = SchedulerReducer.reduce(
            s,
            SchedulerIntent.ClickCell(cellId = visible[1], ctrl = false, shift = true, visibleOrder = visible),
        )
        s = SchedulerReducer.reduce(s, SchedulerIntent.CopySelection)
        assertEquals(listOf("A", "B"), s.clipboard)

        s = SchedulerReducer.reduce(
            s,
            SchedulerIntent.ClickCell(cellId = visible[2], ctrl = false, shift = false, visibleOrder = visible),
        )
        s = SchedulerReducer.reduce(s, SchedulerIntent.CopySelection)
        assertEquals(listOf("C"), s.clipboard)
    }

    @Test
    fun paste_titles_inserts_cells_below_main_selection() {
        var s = seedThreeTasks()
        val visible = SchedulerDomain.selectableVisibleOrder(s)
        val target = visible[0]

        s = SchedulerReducer.reduce(
            s,
            SchedulerIntent.ClickCell(cellId = target, ctrl = false, shift = false, visibleOrder = visible),
        )
        s = SchedulerReducer.reduce(s, SchedulerIntent.PasteTitles(listOf("X", "Y", "Z")))

        val listCells = s.lists[s.rootListId]!!.cellIds
        assertEquals("X", s.tasks[s.cells[target]!!.taskId!!]!!.title)
        assertEquals("Y", s.tasks[s.cells[listCells[1]]!!.taskId!!]!!.title)
        assertEquals("Z", s.tasks[s.cells[listCells[2]]!!.taskId!!]!!.title)
    }

    @Test
    fun paste_titles_gives_each_populated_cell_an_expansion_arrow() {
        var s = SchedulerState.empty()
        val target = s.lists[s.rootListId]!!.cellIds.first()

        s = SchedulerReducer.reduce(
            s,
            SchedulerIntent.ClickCell(
                cellId = target,
                ctrl = false,
                shift = false,
                visibleOrder = SchedulerDomain.selectableVisibleOrder(s),
            ),
        )
        s = SchedulerReducer.reduce(s, SchedulerIntent.PasteTitles(listOf("One", "Two", "Three")))

        val populatedIds = s.lists[s.rootListId]!!.cellIds.filter { cellId ->
            !SchedulerDomain.isTextuallyEmptyCell(s, cellId)
        }
        assertEquals(3, populatedIds.size)
        populatedIds.forEach { cellId ->
            assertTrue(
                SchedulerDomain.hasExpandableSubTree(s, cellId),
                "Expected expansion arrow for pasted cell $cellId",
            )
        }
    }

    @Test
    fun paste_is_undoable() {
        var s = seedThreeTasks()
        val visible = SchedulerDomain.selectableVisibleOrder(s)
        val target = visible[0]

        s = SchedulerReducer.reduce(
            s,
            SchedulerIntent.ClickCell(cellId = target, ctrl = false, shift = false, visibleOrder = visible),
        )
        val beforeCount = s.lists[s.rootListId]!!.cellIds.size
        s = SchedulerReducer.reduce(s, SchedulerIntent.PasteTitles(listOf("One", "Two")))
        assertTrue(s.lists[s.rootListId]!!.cellIds.size > beforeCount)

        s = SchedulerReducer.reduce(s, SchedulerIntent.Undo)
        assertEquals(beforeCount, s.lists[s.rootListId]!!.cellIds.size)
        assertEquals("A", s.tasks[s.cells[target]!!.taskId!!]!!.title)
    }

    @Test
    fun repeated_paste_of_same_title_keeps_unique_cell_ids_and_isolated_selection() {
        var s = SchedulerState.empty()
        val title = "Sheet cell"

        repeat(5) {
            val visible = SchedulerDomain.selectableVisibleOrder(s)
            val target = visible.last { SchedulerDomain.isTextuallyEmptyCell(s, it) }
            s = SchedulerReducer.reduce(
                s,
                SchedulerIntent.ClickCell(cellId = target, ctrl = false, shift = false, visibleOrder = visible),
            )
            s = SchedulerReducer.reduce(s, SchedulerIntent.PasteTitles(listOf(title, title)))
        }

        val listIds = s.lists[s.rootListId]!!.cellIds
        assertEquals(listIds.size, listIds.toSet().size)

        val populated = listIds.filter { !SchedulerDomain.isTextuallyEmptyCell(s, it) }
        populated.forEach { cellId ->
            s = SchedulerReducer.reduce(
                s,
                SchedulerIntent.ClickCell(
                    cellId = cellId,
                    ctrl = false,
                    shift = false,
                    visibleOrder = SchedulerDomain.selectableVisibleOrder(s),
                ),
            )
            assertEquals(cellId, s.selection.main)
            assertTrue(s.selection.selected.isEmpty())
        }
    }

    @Test
    fun parse_clipboard_text_supports_newlines_and_sheet_columns() {
        assertEquals(listOf("a", "b"), SchedulerDomain.parseClipboardText("a\nb"))
        assertEquals(listOf("left"), SchedulerDomain.parseClipboardText("left\tright"))
    }

    private fun seedThreeTasks(): SchedulerState {
        var s = SchedulerState.empty()
        val root = s.rootListId

        fun setTitleAt(index: Int, title: String) {
            val id = s.lists[root]!!.cellIds[index]
            s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(id, title))
        }

        setTitleAt(0, "A")
        setTitleAt(1, "B")
        setTitleAt(2, "C")

        return s
    }
}
