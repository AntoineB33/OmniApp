package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.WellKnownIds
import org.example.project.scheduler.state.CellEditMode
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState

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
        var s = seedThreeTasks()
        val cellId = s.lists[s.rootListId]!!.cellIds.first()

        s = SchedulerReducer.reduce(s, SchedulerIntent.ToggleExpand(cellId))
        assertTrue(s.expanded.contains(cellId))

        s = SchedulerReducer.reduce(s, SchedulerIntent.Undo)
        assertFalse(s.expanded.contains(cellId))

        s = SchedulerReducer.reduce(s, SchedulerIntent.Redo)
        assertTrue(s.expanded.contains(cellId))
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
    fun eligible_assign_task_ids_hide_ancestors_and_siblings() {
        var s = SchedulerState.empty()
        val parent = s.lists[s.rootListId]!!.cellIds.first()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(parent, "Parent"))
        val parentTask = s.cells[parent]!!.taskId!!
        s = SchedulerReducer.reduce(s, SchedulerIntent.ToggleExpand(parent))

        val childListId = s.tasks[parentTask]!!.childListId!!
        val firstChild = s.lists[childListId]!!.cellIds.first()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(firstChild, "First"))
        val firstChildTask = s.cells[firstChild]!!.taskId!!

        val emptySlot = s.lists[childListId]!!.cellIds.last()
        val eligible = SchedulerDomain.eligibleAssignTaskIds(s, emptySlot, "")
        assertFalse(parentTask in eligible)
        assertFalse(firstChildTask in eligible)
    }

    @Test
    fun eligible_assign_task_ids_sorted_by_path_length_then_label() {
        var s = SchedulerState.empty()
        val alphaCell = s.lists[s.rootListId]!!.cellIds.first()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(alphaCell, "Alpha"))
        val alphaId = s.cells[alphaCell]!!.taskId!!
        s = SchedulerReducer.reduce(s, SchedulerIntent.ToggleExpand(alphaCell))
        val alphaListId = s.tasks[alphaId]!!.childListId!!
        val nestedCell = s.lists[alphaListId]!!.cellIds.first()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(nestedCell, "Nested"))
        val nestedId = s.cells[nestedCell]!!.taskId!!

        val betaCell = s.lists[s.rootListId]!!.cellIds.last()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(betaCell, "Beta"))
        s = SchedulerReducer.reduce(s, SchedulerIntent.ToggleExpand(betaCell))
        val betaListId = s.tasks[s.cells[betaCell]!!.taskId!!]!!.childListId!!

        val emptyInSublist = s.lists[betaListId]!!.cellIds.last()
        val eligible = SchedulerDomain.eligibleAssignTaskIds(s, emptyInSublist, "")
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
