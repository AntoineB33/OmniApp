package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.WellKnownIds
import org.example.project.scheduler.persistence.SchedulerStateCodec
import org.example.project.scheduler.persistence.SchedulerStore
import org.example.project.scheduler.state.AppWindow
import org.example.project.scheduler.state.CellEditMode
import org.example.project.scheduler.state.HistoryCategory
import org.example.project.scheduler.state.EditExitNavigation
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerSelection
import org.example.project.scheduler.state.SchedulerState
import org.example.project.scheduler.state.SelectionNavigate
import org.example.project.scheduler.ui.TaskSchedulerViewModel
import org.example.project.time.AppClock

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
    fun history_units_carry_a_label_for_the_history_manager() {
        // PRD §5/§6 History Manager: each committed unit records a human-readable label, which the window
        // lists per category. Setting a cell title lands a "Set title" unit in the Main ("the rest") stack.
        var s = SchedulerState.empty()
        val cellId = s.lists[s.rootListId]!!.cellIds.first()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(cellId, "Daily"))

        val main = s.histories.forCategory(HistoryCategory.Main)
        assertEquals("Set title", main.units.last().delta.label)
        assertEquals(main.units.lastIndex, main.pointer) // newest unit is the current pointer

        // PRD §5/§6: the unit also exposes all its data — here, the cell's title change — which the
        // history window lists under the label.
        val details = main.units.last().delta.details
        assertTrue(details.any { it.contains("Daily") }, "details should describe the title change: $details")
    }

    @Test
    fun history_units_stamp_time_and_use_chrono_id_only_for_ties() {
        // PRD §6: each unit records the wall-clock instant of the change; chronoId stays 0 unless an
        // already-recorded unit shares that exact timestamp, in which case it is the next index (1, 2, …).
        val controllable = object : AppClock {
            var now = 1_000L
            override fun nowMillis(): Long = now
        }
        val previous = SchedulerReducer.clock
        SchedulerReducer.clock = controllable
        try {
            var s = SchedulerState.empty()
            val cellId = s.lists[s.rootListId]!!.cellIds.first()

            // Two changes recorded at the same instant → same time, chronoId 0 then 1.
            s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(cellId, "A"))
            s = SchedulerReducer.reduce(s, SchedulerIntent.ToggleExpand(cellId))
            var units = s.histories.forCategory(HistoryCategory.Main).units
            assertEquals(1_000L, units[0].timeMillis)
            assertEquals(0L, units[0].chronoId)
            assertEquals(1_000L, units[1].timeMillis)
            assertEquals(1L, units[1].chronoId) // tie with the unit at the same instant

            // A change at a new instant → chronoId back to 0 (no tie).
            controllable.now = 2_000L
            s = SchedulerReducer.reduce(s, SchedulerIntent.ToggleExpand(cellId))
            units = s.histories.forCategory(HistoryCategory.Main).units
            assertEquals(2_000L, units.last().timeMillis)
            assertEquals(0L, units.last().chronoId)
        } finally {
            SchedulerReducer.clock = previous
        }
    }

    @Test
    fun focusing_a_floating_window_clears_selection_exits_edit_and_records_a_window_nav_unit() {
        // PRD §7: clicking a floating window forcibly exits tree Edit Mode, makes the tree selection
        // disappear, moves focus, and records a (non-undoable) WindowNav history unit.
        var s = SchedulerState.empty()
        val cellId = s.lists[s.rootListId]!!.cellIds.first()
        s = SchedulerReducer.reduce(
            s,
            SchedulerIntent.ClickCell(cellId = cellId, ctrl = false, shift = false, visibleOrder = emptyList()),
        )
        s = SchedulerReducer.reduce(s, SchedulerIntent.BeginEdit(cellId, "x"))
        assertNotNull(s.selection.main)
        assertNotNull(s.editSession)

        s = SchedulerReducer.reduce(s, SchedulerIntent.FocusWindow(AppWindow.Calendar))
        assertEquals(AppWindow.Calendar, s.focusedWindow)
        assertNull(s.editSession) // Edit Mode left
        assertNull(s.selection.main) // selection disappeared
        assertTrue(s.selection.selected.isEmpty())
        val nav = s.histories.windowNav
        assertEquals(1, nav.units.size)
        assertEquals("Focus Calendar", nav.units.last().delta.label)

        // The WindowNav unit is not walked by any undo/redo command, so Ctrl+Z does not revert focus.
        val undone = SchedulerReducer.reduce(s, SchedulerIntent.Undo)
        assertEquals(AppWindow.Calendar, undone.focusedWindow)
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
        assertEquals(-1, s.histories.main.pointer)

        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(cellId, "Second"))
        assertEquals(0, s.histories.main.pointer)
        assertEquals(1, s.histories.main.units.size)
        assertEquals("Second", s.tasks[s.cells[cellId]!!.taskId!!]!!.title)
    }

    @Test
    fun history_units_are_capped_at_1000_dropping_the_oldest() {
        var s = SchedulerState.empty()
        val cellId = s.lists[s.rootListId]!!.cellIds.first()
        // Each ToggleExpand commits one Main-category history unit.
        repeat(1005) { s = SchedulerReducer.reduce(s, SchedulerIntent.ToggleExpand(cellId)) }
        // PRD §5: the list is capped — oldest units dropped — and the pointer stays on the newest.
        assertEquals(1000, s.histories.main.units.size)
        assertEquals(999, s.histories.main.pointer)
    }

    @Test
    fun selection_history_is_independent_from_content_history() {
        var s = seedThreeTasks()
        val visible = SchedulerDomain.selectableVisibleOrder(s)

        s = SchedulerReducer.reduce(
            s,
            SchedulerIntent.ClickCell(cellId = visible[0], ctrl = false, shift = false, visibleOrder = visible),
        )
        s = SchedulerReducer.reduce(s, SchedulerIntent.NavigateSelection(SelectionNavigate.Next))
        assertEquals(visible[1], s.selection.main)

        val treeAfterSelection = s.captureTree()
        val mainUnits = s.histories.main.units.size

        // Alt+Left undoes selection only — the content tree must be untouched (PRD §5).
        s = SchedulerReducer.reduce(s, SchedulerIntent.UndoSelection)
        assertEquals(visible[0], s.selection.main)
        assertEquals(treeAfterSelection, s.captureTree())
        assertEquals(mainUnits, s.histories.main.units.size)

        // Alt+Right redoes the selection step.
        s = SchedulerReducer.reduce(s, SchedulerIntent.RedoSelection)
        assertEquals(visible[1], s.selection.main)
        assertEquals(treeAfterSelection, s.captureTree())
    }

    @Test
    fun content_undo_does_not_disturb_selection_history() {
        var s = SchedulerState.empty()
        val cell = s.lists[s.rootListId]!!.cellIds.first()

        s = SchedulerReducer.reduce(
            s,
            SchedulerIntent.ClickCell(cellId = cell, ctrl = false, shift = false, visibleOrder = emptyList()),
        )
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(cell, "Hello"))
        assertEquals(cell, s.selection.main)

        // Ctrl+Z reverts the content change but leaves the selection in place (separate stacks).
        s = SchedulerReducer.reduce(s, SchedulerIntent.Undo)
        assertEquals(null, s.cells[cell]!!.taskId)
        assertEquals(cell, s.selection.main)
    }

    @Test
    fun edit_session_collapses_into_a_single_content_history_unit() {
        var s = SchedulerState.empty()
        val cell = s.lists[s.rootListId]!!.cellIds.first()
        val mainBefore = s.histories.main.units.size

        s = SchedulerReducer.reduce(s, SchedulerIntent.BeginEdit(cell))
        s = SchedulerReducer.reduce(s, SchedulerIntent.UpdateEditText("H"))
        s = SchedulerReducer.reduce(s, SchedulerIntent.UpdateEditText("He"))
        s = SchedulerReducer.reduce(s, SchedulerIntent.UpdateEditText("Hello"))

        // Keystrokes accumulate in the ephemeral Edit Mode stack; the content stack is untouched.
        assertTrue(s.histories.edit.units.isNotEmpty())
        assertEquals(mainBefore, s.histories.main.units.size)

        s = SchedulerReducer.reduce(s, SchedulerIntent.ExitEdit(EditExitNavigation.Stay))

        // On exit the whole session collapses into one content unit and the Edit stack is cleared.
        assertEquals(null, s.editSession)
        assertTrue(s.histories.edit.units.isEmpty())
        assertEquals(mainBefore + 1, s.histories.main.units.size)
        assertEquals("Hello", s.tasks[s.cells[cell]!!.taskId!!]!!.title)

        // A single Ctrl+Z undoes the entire edit.
        s = SchedulerReducer.reduce(s, SchedulerIntent.Undo)
        assertEquals(null, s.cells[cell]!!.taskId)
    }

    @Test
    fun priority_splits_evenly_among_populated_top_level_cells() {
        val s = seedThreeTasks()
        val root = s.lists[s.rootListId]!!.cellIds
        val a = s.cells[root[0]]!!.taskId!!
        val b = s.cells[root[1]]!!.taskId!!
        val c = s.cells[root[2]]!!.taskId!!

        val p = SchedulerDomain.absoluteTaskPriorities(s)
        assertEquals(1.0 / 3, p[a]!!, 1e-9)
        assertEquals(1.0 / 3, p[b]!!, 1e-9)
        assertEquals(1.0 / 3, p[c]!!, 1e-9)
        // The trailing empty placeholder carries no priority; the populated cells sum to 100%.
        assertEquals(1.0, p[a]!! + p[b]!! + p[c]!!, 1e-9)
    }

    @Test
    fun emptied_sibling_cell_drops_out_of_the_priority_split() {
        // Regression: "deleting" a task clears its cell title but the cell keeps its taskId and the
        // task lingers blank. Such a cell must not count toward the priority divisor, otherwise two
        // siblings stay at 50% each after one is emptied.
        val s = seedThreeTasks()
        val root = s.lists[s.rootListId]!!.cellIds
        val aCell = root[0]
        val a = s.cells[aCell]!!.taskId!!
        val bCell = root[1]
        val b = s.cells[bCell]!!.taskId!!
        val cCell = root[2]
        val c = s.cells[cCell]!!.taskId!!

        // Empty B and C, leaving A the only real task.
        var deleted = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(bCell, ""))
        deleted = SchedulerReducer.reduce(deleted, SchedulerIntent.SetCellTitle(cCell, ""))

        val p = SchedulerDomain.absoluteTaskPriorities(deleted)
        assertEquals(1.0, p[a]!!, 1e-9) // A now holds the full 100%
        assertNull(p[b]) // emptied cells carry no priority and show no percentage
        assertNull(p[c])
    }

    @Test
    fun priority_distributes_down_nested_sublists() {
        var s = SchedulerState.empty()
        val parentCell = s.lists[s.rootListId]!!.cellIds.first()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(parentCell, "Parent"))
        val parentTask = s.cells[parentCell]!!.taskId!!
        s = SchedulerReducer.reduce(s, SchedulerIntent.ToggleExpand(parentCell))
        val childList = s.tasks[parentTask]!!.childListId!!
        val firstChild = s.lists[childList]!!.cellIds.first()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(firstChild, "C1"))
        val c1 = s.cells[firstChild]!!.taskId!!
        val secondChild = s.lists[childList]!!.cellIds.last()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(secondChild, "C2"))
        val c2 = s.cells[secondChild]!!.taskId!!

        val p = SchedulerDomain.absoluteTaskPriorities(s)
        // Parent is the only populated top-level cell → it inherits the root's full 100%.
        assertEquals(1.0, p[parentTask]!!, 1e-9)
        // Two populated children split the parent's 100% evenly.
        assertEquals(0.5, p[c1]!!, 1e-9)
        assertEquals(0.5, p[c2]!!, 1e-9)
    }

    @Test
    fun priority_weight_changes_split_and_is_undoable() {
        var s = seedThreeTasks()
        val root = s.lists[s.rootListId]!!.cellIds
        val first = root[0]
        val a = s.cells[first]!!.taskId!!
        val b = s.cells[root[1]]!!.taskId!!
        val c = s.cells[root[2]]!!.taskId!!

        // Default single-column weights (all 1) → even thirds.
        assertEquals(listOf(1.0), s.cells[first]!!.priorityWeights)
        assertEquals(1.0 / 3, SchedulerDomain.absoluteTaskPriorities(s)[a]!!, 1e-9)

        // Set the first cell's column-0 value to 2 → 2/(2+1+1) = 50%, others 25% each.
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetPriorityWeight(first, 0, 2.0))
        assertEquals(2.0, s.cells[first]!!.priorityWeights[0], 1e-9)
        val weighted = SchedulerDomain.absoluteTaskPriorities(s)
        assertEquals(0.5, weighted[a]!!, 1e-9)
        assertEquals(0.25, weighted[b]!!, 1e-9)
        assertEquals(0.25, weighted[c]!!, 1e-9)

        // A negative value is clamped to 0 (0 is allowed); the cell then takes 0% priority.
        val zeroed = SchedulerReducer.reduce(s, SchedulerIntent.SetPriorityWeight(first, 0, -5.0))
        assertEquals(0.0, zeroed.cells[first]!!.priorityWeights[0], 1e-9)
        assertEquals(0.0, SchedulerDomain.absoluteTaskPriorities(zeroed)[a]!!, 1e-9)

        // Weight change is an undoable content delta (PRD §6).
        s = SchedulerReducer.reduce(s, SchedulerIntent.Undo)
        assertEquals(1.0, s.cells[first]!!.priorityWeights.getOrElse(0) { 1.0 }, 1e-9)
        assertEquals(1.0 / 3, SchedulerDomain.absoluteTaskPriorities(s)[a]!!, 1e-9)
    }

    @Test
    fun column_absolute_weights_follow_remaining_fraction_rule() {
        // header[n] * (1 - Σ preceding absolute weights): [0.5, 1.0] → [0.5, 0.5].
        assertEquals(listOf(0.5, 0.5), SchedulerDomain.columnAbsoluteWeights(listOf(0.5, 1.0)))
        assertEquals(listOf(1.0), SchedulerDomain.columnAbsoluteWeights(listOf(1.0)))
    }

    @Test
    fun add_set_and_delete_priority_columns() {
        var s = seedThreeTasks()
        val listId = s.rootListId
        val first = s.lists[listId]!!.cellIds.first()
        // PRD §5: the default column has every field set to 1.
        assertEquals(listOf(1.0), s.lists[listId]!!.weightColumns)
        assertEquals(listOf(1.0), s.cells[first]!!.priorityWeights)

        // PRD §5: an added column defaults every field (header + cells) to 0.
        s = SchedulerReducer.reduce(s, SchedulerIntent.AddPriorityColumn(listId))
        assertEquals(listOf(1.0, 0.0), s.lists[listId]!!.weightColumns)
        assertEquals(listOf(1.0, 0.0), s.cells[first]!!.priorityWeights)

        s = SchedulerReducer.reduce(s, SchedulerIntent.SetPriorityColumnWeight(listId, 0, 0.5))
        assertEquals(0.5, s.lists[listId]!!.weightColumns[0], 1e-9)
        // PRD §5: a header weight is clamped to the 0..1 range.
        val overMax = SchedulerReducer.reduce(s, SchedulerIntent.SetPriorityColumnWeight(listId, 0, 5.0))
        assertEquals(1.0, overMax.lists[listId]!!.weightColumns[0], 1e-9)

        // Deleting a column shrinks both the header and the cells.
        s = SchedulerReducer.reduce(s, SchedulerIntent.DeletePriorityColumn(listId, 1))
        assertEquals(1, s.lists[listId]!!.weightColumns.size)
        assertEquals(1, s.cells[first]!!.priorityWeights.size)

        // The last remaining column cannot be deleted.
        val before = s.lists[listId]!!.weightColumns
        s = SchedulerReducer.reduce(s, SchedulerIntent.DeletePriorityColumn(listId, 0))
        assertEquals(before, s.lists[listId]!!.weightColumns)
    }

    @Test
    fun move_priority_column_reorders_headers_and_cell_values() {
        var s = seedThreeTasks()
        val listId = s.rootListId
        val first = s.lists[listId]!!.cellIds.first()
        s = SchedulerReducer.reduce(s, SchedulerIntent.AddPriorityColumn(listId))
        s = SchedulerReducer.reduce(s, SchedulerIntent.AddPriorityColumn(listId))
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetPriorityColumnWeight(listId, 0, 0.2))
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetPriorityColumnWeight(listId, 1, 0.5))
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetPriorityColumnWeight(listId, 2, 0.8))
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetPriorityWeight(first, 0, 10.0))
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetPriorityWeight(first, 1, 20.0))
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetPriorityWeight(first, 2, 30.0))

        // Move the first column to the end (insertion index 3 across all columns).
        s = SchedulerReducer.reduce(s, SchedulerIntent.MovePriorityColumn(listId, from = 0, to = 3))
        assertEquals(listOf(0.5, 0.8, 0.2), s.lists[listId]!!.weightColumns)
        assertEquals(listOf(20.0, 30.0, 10.0), s.cells[first]!!.priorityWeights)
    }

    @Test
    fun reset_priority_column_restores_defaults() {
        var s = seedThreeTasks()
        val listId = s.rootListId
        val first = s.lists[listId]!!.cellIds.first()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetPriorityWeight(first, 0, 9.0))
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetPriorityColumnWeight(listId, 0, 0.3))

        s = SchedulerReducer.reduce(s, SchedulerIntent.ResetPriorityColumn(listId, 0))
        // Column 0's default is 1 for both the header and every cell.
        assertEquals(1.0, s.lists[listId]!!.weightColumns[0], 1e-9)
        assertEquals(1.0, s.cells[first]!!.priorityWeights[0], 1e-9)
    }

    @Test
    fun mirrored_task_priority_sums_across_occurrences() {
        var s = SchedulerState.empty()
        val branchA = s.lists[s.rootListId]!!.cellIds.first()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(branchA, "Shared"))
        val sharedTask = s.cells[branchA]!!.taskId!!

        val branchB = s.lists[s.rootListId]!!.cellIds.last()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(branchB, "Branch B"))
        val branchBTask = s.cells[branchB]!!.taskId!!
        s = SchedulerReducer.reduce(s, SchedulerIntent.ToggleExpand(branchB))
        val branchBChild = s.lists[s.tasks[branchBTask]!!.childListId!!]!!.cellIds.first()
        s = SchedulerReducer.reduce(s, SchedulerIntent.AssignTaskId(branchBChild, sharedTask))

        val p = SchedulerDomain.absoluteTaskPriorities(s)
        // Top level holds two populated cells → 50% each.
        assertEquals(0.5, p[branchBTask]!!, 1e-9)
        // "Shared" is both a top-level cell (50%) and Branch B's only child (50% of 50%·1 = 50%),
        // so its absolute priority sums to 100% across the two occurrences.
        assertEquals(1.0, p[sharedTask]!!, 1e-9)
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
    fun eligible_assign_task_ids_hide_shared_descendant_parents_set() {
        // PRD §4 Filtering — the "parents set" / shared-descendant rule. With a→c and b→c (c shared by
        // both a and b), a cell under b must not offer a: assigning a there would make b a parent of a,
        // placing c twice inside b's sub-tree. An unrelated task stays assignable, and so do recurrences
        // at root (no ancestor), which is why c can sit under both a and b in the first place.
        var s = SchedulerState.empty()

        // a → c
        val aCell = s.lists[s.rootListId]!!.cellIds.first()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(aCell, "a"))
        val aTask = s.cells[aCell]!!.taskId!!
        s = SchedulerReducer.reduce(s, SchedulerIntent.ToggleExpand(aCell))
        val aChildList = s.tasks[aTask]!!.childListId!!
        val aChild = s.lists[aChildList]!!.cellIds.first()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(aChild, "c"))
        val cTask = s.cells[aChild]!!.taskId!!

        // b at root; an unrelated task z at root too.
        val bCell = s.lists[s.rootListId]!!.cellIds.last()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(bCell, "b"))
        val bTask = s.cells[bCell]!!.taskId!!
        val zCell = s.lists[s.rootListId]!!.cellIds.last()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(zCell, "z"))
        val zTask = s.cells[zCell]!!.taskId!!

        // b → c : title a temp child (which appends a trailing empty), then reassign it to the shared c.
        s = SchedulerReducer.reduce(s, SchedulerIntent.ToggleExpand(bCell))
        val bChildList = s.tasks[bTask]!!.childListId!!
        val bChild = s.lists[bChildList]!!.cellIds.first()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(bChild, "tmp"))
        s = SchedulerReducer.reduce(s, SchedulerIntent.AssignTaskId(bChild, cTask))

        val emptyUnderB = s.lists[bChildList]!!.cellIds.last()
        assertEquals(null, s.cells[emptyUnderB]!!.taskId)

        // a shares descendant c with b's sub-tree → excluded; z is unrelated → eligible.
        assertFalse(aTask in SchedulerDomain.eligibleAssignTaskIds(s, emptyUnderB, "a"))
        assertTrue(zTask in SchedulerDomain.eligibleAssignTaskIds(s, emptyUnderB, "z"))
        assertFalse(SchedulerDomain.canAssignTaskId(s, emptyUnderB, aTask))
        assertTrue(SchedulerDomain.canAssignTaskId(s, emptyUnderB, zTask))
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
    fun picking_existing_task_discards_scheduled_draft_no_duplicate_title() {
        // Regression: typing a title creates a "New task" draft; a scheduling tick can give that draft a
        // calendar panel. Picking an existing same-title task must fully discard the draft (panels too),
        // not leave a stray second task with the same title kept alive by its panel.
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
        // Typing "g" now defaults to reusing the existing nested "g"; explicitly choosing "New task"
        // forces a fresh draft titled "g" while the existing same-title task stays pickable in the menu.
        s = SchedulerReducer.reduce(s, SchedulerIntent.BeginEdit(cellId = betaCell, initialText = "g"))
        s = SchedulerReducer.reduce(s, SchedulerIntent.SelectCreateAssignTask)
        val draftId = s.editSession!!.newTaskDraftId!!
        // A scheduling run while the draft exists (as in the app's debounced tick) gives it a panel.
        s = SchedulerReducer.reduce(s, SchedulerIntent.RefreshSchedule(nowMillis = 0L))
        assertTrue(s.panels.any { it.taskId == draftId }, "test premise: the draft must get a panel")

        s = SchedulerReducer.reduce(s, SchedulerIntent.PickTaskFromMenu(nestedGId))

        assertEquals(nestedGId, s.cells[betaCell]!!.taskId, "cell reuses the picked task")
        assertEquals(
            listOf(nestedGId),
            s.tasks.values.filter { it.title == "g" }.map { it.id },
            "only the picked task should be titled 'g'; the abandoned draft must be gone",
        )
        assertTrue(s.tasks[draftId] == null, "the abandoned draft task must be purged")
        assertFalse(s.panels.any { it.taskId == draftId }, "the abandoned draft's panels must be dropped")
    }

    @Test
    fun changing_text_in_edit_mode_discards_the_previous_task_and_its_auto_panel() {
        // PRD §4: editing a cell and changing its text to a new task spins up a fresh draft and leaves the
        // cell's previous task behind. That previous task, kept alive only by an ephemeral auto panel (a
        // scheduling tick) with no cell, is an editing leftover — it must be purged along with its auto
        // panel, not linger as a ghost task/calendar block.
        var s = SchedulerState.empty()
        val cellId = s.lists[s.rootListId]!!.cellIds.first()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(cellId, "Alpha"))
        val originalTaskId = s.cells[cellId]!!.taskId!!
        // A scheduling tick gives "Alpha" an auto panel (the only thing that would otherwise keep it alive).
        s = SchedulerReducer.reduce(s, SchedulerIntent.RefreshSchedule(nowMillis = 0L))
        assertTrue(s.panels.any { it.taskId == originalTaskId }, "test premise: Alpha must get an auto panel")

        s = SchedulerReducer.reduce(s, SchedulerIntent.BeginEdit(cellId))
        s = SchedulerReducer.reduce(s, SchedulerIntent.UpdateEditText("Alphabet"))

        val newTaskId = s.cells[cellId]!!.taskId!!
        assertNotEquals(originalTaskId, newTaskId, "the cell points at a fresh task, not the old one")
        assertNull(s.tasks[originalTaskId], "the abandoned previous task must be purged from memory")
        assertFalse(
            s.panels.any { it.taskId == originalTaskId },
            "the abandoned previous task's auto panel must be dropped",
        )
    }

    @Test
    fun typing_exact_existing_title_defaults_to_reusing_that_task() {
        // PRD §4 default selection: the first eligible existing task is selected by default, so typing a
        // title that exactly matches a task in another branch reuses it instead of creating a new task.
        var s = SchedulerState.empty()
        val alphaCell = s.lists[s.rootListId]!!.cellIds.first()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(alphaCell, "Alpha"))
        val alphaId = s.cells[alphaCell]!!.taskId!!
        s = SchedulerReducer.reduce(s, SchedulerIntent.ToggleExpand(alphaCell))
        val nestedCell = s.lists[s.tasks[alphaId]!!.childListId!!]!!.cellIds.first()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(nestedCell, "g"))
        val nestedGId = s.cells[nestedCell]!!.taskId!!

        // Type "g" into an empty root cell: it defaults to reusing the existing "g", with no new task.
        val betaCell = s.lists[s.rootListId]!!.cellIds.last()
        s = SchedulerReducer.reduce(s, SchedulerIntent.BeginEdit(cellId = betaCell, initialText = "g"))
        assertEquals(nestedGId, s.editSession!!.selectedAssignTaskId)
        assertEquals(null, s.editSession!!.newTaskDraftId)
        assertEquals(nestedGId, s.cells[betaCell]!!.taskId)
        assertEquals(
            listOf(nestedGId),
            s.tasks.values.filter { it.title == "g" }.map { it.id },
            "typing an existing title must reuse it, not create a second 'g' task",
        )
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
    fun reenter_edit_selects_current_task_in_change_menu() {
        // PRD §4: re-entering Edit Mode on an assigned cell makes the current task the *default
        // selection*, so it must be listed (and highlightable) — only the in-progress "New task" draft
        // is hidden. (Excluding the current task here was the cause of "the picked task didn't go
        // purple": with it removed, changeTaskMenuSelectedIndex could not match it and returned -1.)
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

        // The UI builds the menu excluding only the "New task" draft.
        val entries =
            SchedulerDomain.changeTaskMenuEntries(
                s,
                cellId,
                session.draftText,
                excludeTaskId = session.newTaskDraftId,
            )
        // "New task" plus the current task "g"; "g" is the selected (purple) element.
        assertEquals(2, entries.size)
        assertEquals("New task", entries[0].label)
        assertEquals(taskId, entries[1].taskId)
        val selectedIndex =
            SchedulerDomain.changeTaskMenuSelectedIndex(entries, session.selectedAssignTaskId)
        assertEquals(taskId, entries[selectedIndex].taskId)
    }

    @Test
    fun picking_a_task_from_the_change_menu_marks_it_selected() {
        // Regression: after picking an existing task from the Change Task menu it must show as selected
        // (highlighted), not vanish. The menu excludes only the "New task" draft — never the picked task.
        var s = SchedulerState.empty()
        val alphaCell = s.lists[s.rootListId]!!.cellIds.first()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(alphaCell, "Alpha"))
        val alphaId = s.cells[alphaCell]!!.taskId!!
        s = SchedulerReducer.reduce(s, SchedulerIntent.ToggleExpand(alphaCell))
        val alphaListId = s.tasks[alphaId]!!.childListId!!
        val nestedCell = s.lists[alphaListId]!!.cellIds.first()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(nestedCell, "g"))
        val nestedGId = s.cells[nestedCell]!!.taskId!!

        // Edit a different (root) cell, typing "g": the first eligible existing task ("g") is now the
        // default selection (PRD §4), reused immediately — not "New task".
        val betaCell = s.lists[s.rootListId]!!.cellIds.last()
        s = SchedulerReducer.reduce(s, SchedulerIntent.BeginEdit(cellId = betaCell, initialText = "g"))
        assertEquals(nestedGId, s.editSession!!.selectedAssignTaskId)

        // Pick the existing nested "g" from the menu.
        s = SchedulerReducer.reduce(s, SchedulerIntent.PickTaskFromMenu(nestedGId))
        val session = s.editSession!!
        assertEquals(nestedGId, session.selectedAssignTaskId)

        val entries =
            SchedulerDomain.changeTaskMenuEntries(
                s,
                betaCell,
                session.draftText,
                excludeTaskId = session.newTaskDraftId,
            )
        assertTrue(nestedGId in entries.mapNotNull { it.taskId }, "picked task must stay listed")
        val selectedIndex =
            SchedulerDomain.changeTaskMenuSelectedIndex(entries, session.selectedAssignTaskId)
        assertEquals(nestedGId, entries[selectedIndex].taskId, "picked task must be the selected (purple) entry")
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
                // Mirror the UI, which excludes only the in-progress "New task" draft (a reused existing
                // task stays listed so it can render as the selected entry).
                excludeTaskId = s.editSession!!.newTaskDraftId,
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
                // Mirror the UI, which excludes only the in-progress "New task" draft (a reused existing
                // task stays listed so it can render as the selected entry).
                excludeTaskId = s.editSession!!.newTaskDraftId,
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
    fun moving_cell_below_trailing_placeholder_restores_bottom_empty_cell() {
        // PRD §4 Empty cells: dropping a task *below* the trailing empty cell must drop that now-middle
        // empty and re-create a fresh placeholder at the bottom — the empty cell never rests above a
        // populated one.
        var s = SchedulerState.empty()
        val root = s.rootListId
        fun setTitleAt(index: Int, title: String) {
            val id = s.lists[root]!!.cellIds[index]
            s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(id, title))
        }
        setTitleAt(0, "A")
        setTitleAt(1, "B")

        val before = s.lists[root]!!.cellIds
        assertEquals(3, before.size) // [A, B, <empty placeholder>]
        val bCell = before[1]
        val placeholder = before[2]
        assertTrue(SchedulerDomain.isTextuallyEmptyCell(s, placeholder))

        val visible = SchedulerDomain.selectableVisibleOrder(s)
        s = SchedulerReducer.reduce(
            s,
            SchedulerIntent.ClickCell(cellId = bCell, ctrl = false, shift = false, visibleOrder = visible),
        )
        s = SchedulerReducer.reduce(
            s,
            SchedulerIntent.MoveSelectedCells(targetCellId = placeholder, insertBefore = false),
        )

        val after = s.lists[root]!!.cellIds
        assertEquals(3, after.size, "the middle empty is dropped and one fresh placeholder is appended")
        assertEquals("A", s.tasks[s.cells[after[0]]!!.taskId]!!.title)
        assertEquals("B", s.tasks[s.cells[after[1]]!!.taskId]!!.title)
        assertFalse(SchedulerDomain.isTextuallyEmptyCell(s, after[1]), "B must not rest at the bottom")
        assertTrue(SchedulerDomain.isTextuallyEmptyCell(s, after.last()), "list must end with an empty cell")
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
        val historyBefore = s.histories.main.units.size
        s = SchedulerReducer.reduce(
            s,
            SchedulerIntent.MoveSelectedCells(targetCellId = child, insertBefore = true),
        )

        // PRD constraint 2: a cell cannot move into its own sub-tree (would create a cycle).
        assertEquals(listsBefore, s.lists)
        assertTrue(parent in s.lists[root]!!.cellIds)
        assertEquals(historyBefore, s.histories.main.units.size)
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
        // PRD §4: tree lines, then the section separator, then the min-time appendix (one per task).
        val sep = SchedulerDomain.COPY_SECTION_SEPARATOR
        assertEquals(listOf("A", "B", sep, "A\t45", "B\t45"), s.clipboard)

        s = SchedulerReducer.reduce(
            s,
            SchedulerIntent.ClickCell(cellId = visible[2], ctrl = false, shift = false, visibleOrder = visible),
        )
        s = SchedulerReducer.reduce(s, SchedulerIntent.CopySelection)
        assertEquals(listOf("C", sep, "C\t45"), s.clipboard)
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
        s = SchedulerReducer.reduce(s, SchedulerIntent.PasteTree("X\nY\nZ"))

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
        s = SchedulerReducer.reduce(s, SchedulerIntent.PasteTree("One\nTwo\nThree"))

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
        s = SchedulerReducer.reduce(s, SchedulerIntent.PasteTree("One\nTwo"))
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
            s = SchedulerReducer.reduce(s, SchedulerIntent.PasteTree("$title\n$title"))
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
    fun copy_serializes_selected_cell_subtree_as_indented_text() {
        // Build A with a child A1 (which has A1a), and a sibling B; then copy [A, B].
        var s = SchedulerState.empty()
        val root = s.rootListId
        val cA = s.lists[root]!!.cellIds[0]
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(cA, "A"))
        val cB = s.lists[root]!!.cellIds[1]
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(cB, "B"))
        val aChildList = s.tasks[s.cells[cA]!!.taskId!!]!!.childListId!!
        val cA1 = s.lists[aChildList]!!.cellIds[0]
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(cA1, "A1"))
        val a1ChildList = s.tasks[s.cells[cA1]!!.taskId!!]!!.childListId!!
        val cA1a = s.lists[a1ChildList]!!.cellIds[0]
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(cA1a, "A1a"))

        val text = SchedulerDomain.copyTreeText(
            s,
            org.example.project.scheduler.state.SchedulerSelection(main = cA, selected = setOf(cA, cB)),
        )
        // Tree section (unchanged for all-default weights) + separator + min-time appendix (PRD §4).
        val sep = SchedulerDomain.COPY_SECTION_SEPARATOR
        assertEquals("A\n\tA1\n\t\tA1a\nB\n$sep\nA\t45\nA1\t45\nA1a\t45\nB\t45", text)
    }

    @Test
    fun paste_rebuilds_the_serialized_subtree_under_the_target_cell() {
        var s = seedThreeTasks()
        val visible = SchedulerDomain.selectableVisibleOrder(s)
        val target = visible[0]
        s = SchedulerReducer.reduce(
            s,
            SchedulerIntent.ClickCell(cellId = target, ctrl = false, shift = false, visibleOrder = visible),
        )
        s = SchedulerReducer.reduce(s, SchedulerIntent.PasteTree("P\n\tC1\n\tC2"))

        // Target becomes "P"; its child list holds the populated children C1 and C2.
        assertEquals("P", s.tasks[s.cells[target]!!.taskId!!]!!.title)
        val childListId = s.tasks[s.cells[target]!!.taskId!!]!!.childListId!!
        val childTitles = s.lists[childListId]!!.cellIds
            .mapNotNull { s.cells[it]!!.taskId?.let { id -> s.tasks[id]!!.title } }
            .filter { it.isNotBlank() }
        assertEquals(listOf("C1", "C2"), childTitles)
    }

    @Test
    fun paste_rejects_text_that_is_not_the_tree_format() {
        // An indentation jump of more than one level → invalid → null → no paste.
        assertEquals(null, SchedulerDomain.parseTreeText("A\n\t\tBadJump"))
        // A real tab inside content (e.g. a Google-Sheets row) → not our format.
        assertEquals(null, SchedulerDomain.parseTreeText("left\tright"))
        assertEquals(null, SchedulerDomain.parseTreeText(""))

        var s = seedThreeTasks()
        val visible = SchedulerDomain.selectableVisibleOrder(s)
        s = SchedulerReducer.reduce(
            s,
            SchedulerIntent.ClickCell(cellId = visible[0], ctrl = false, shift = false, visibleOrder = visible),
        )
        val before = s.lists[s.rootListId]!!.cellIds.size
        s = SchedulerReducer.reduce(s, SchedulerIntent.PasteTree("A\n\t\tBadJump"))
        assertEquals(before, s.lists[s.rootListId]!!.cellIds.size) // unchanged
    }

    @Test
    fun copy_paste_round_trips_titles_with_tabs_and_newlines() {
        // A title containing a newline (Ctrl+Enter) and a tab must survive serialization.
        var s = SchedulerState.empty()
        val c = s.lists[s.rootListId]!!.cellIds[0]
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(c, "line1\nline2\tx"))
        val text = SchedulerDomain.copyTreeText(
            s,
            org.example.project.scheduler.state.SchedulerSelection(main = c, selected = setOf(c)),
        )
        val nodes = SchedulerDomain.parseTreeText(text)
        assertEquals(listOf("line1\nline2\tx"), nodes!!.map { it.title })
    }

    // ----- PRD §4 copy includes weight-table values + trailing min-time appendix --------------

    @Test
    fun copy_includes_priority_weight_values_and_column_headers() {
        // Parent P with a 2-column weight table; child C1 has a non-default value row.
        var s = SchedulerState.empty()
        val cP = s.lists[s.rootListId]!!.cellIds[0]
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(cP, "P"))
        val pTask = s.cells[cP]!!.taskId!!
        val childList = s.tasks[pTask]!!.childListId!!
        val cC1 = s.lists[childList]!!.cellIds[0]
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(cC1, "C1"))
        // Give the child sub-list a second column (header 0.0 by default), set its header to 0.3, and
        // give C1 the value row [2.0, 5.0].
        s = SchedulerReducer.reduce(s, SchedulerIntent.AddPriorityColumn(childList))
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetPriorityColumnWeight(childList, 1, 0.3))
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetPriorityWeight(cC1, 0, 2.0))
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetPriorityWeight(cC1, 1, 5.0))

        val text = SchedulerDomain.copyTreeText(
            s,
            org.example.project.scheduler.state.SchedulerSelection(main = cP, selected = setOf(cP)),
        )
        val lines = text.split('\n')
        // P parents the sub-list whose header is [1.0, 0.3] → emitted as an `h=` field on P's line.
        assertEquals("P\th=1.0,0.3", lines[0])
        // C1's own value row [2.0, 5.0] → emitted as a `w=` field.
        assertEquals("\tC1\tw=2.0,5.0", lines[1])
    }

    @Test
    fun copy_appends_minimum_time_of_each_task_at_the_end() {
        // P (min 30) with child C1 (min 90); the appendix lists both, after the separator (PRD §4).
        var s = SchedulerState.empty()
        val cP = s.lists[s.rootListId]!!.cellIds[0]
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(cP, "P"))
        val pTask = s.cells[cP]!!.taskId!!
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetTaskMinimumTime(pTask, 30))
        val childList = s.tasks[pTask]!!.childListId!!
        val cC1 = s.lists[childList]!!.cellIds[0]
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(cC1, "C1"))
        val c1Task = s.cells[cC1]!!.taskId!!
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetTaskMinimumTime(c1Task, 90))

        val text = SchedulerDomain.copyTreeText(
            s,
            org.example.project.scheduler.state.SchedulerSelection(main = cP, selected = setOf(cP)),
        )
        val sep = SchedulerDomain.COPY_SECTION_SEPARATOR
        val appendix = text.substringAfter("$sep\n")
        assertEquals("P\t30\nC1\t90", appendix)
    }

    @Test
    fun copy_paste_round_trips_weights_columns_and_minimum_times() {
        // Build P{min30, child-header [1.0,0.3]} → C1{min90, row [2.0,5.0]}, copy it, paste into a fresh
        // tree, and verify every PRD §4 value survived the round-trip.
        var s = SchedulerState.empty()
        val cP = s.lists[s.rootListId]!!.cellIds[0]
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(cP, "P"))
        val pTask = s.cells[cP]!!.taskId!!
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetTaskMinimumTime(pTask, 30))
        val childList = s.tasks[pTask]!!.childListId!!
        val cC1 = s.lists[childList]!!.cellIds[0]
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(cC1, "C1"))
        val c1Task = s.cells[cC1]!!.taskId!!
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetTaskMinimumTime(c1Task, 90))
        s = SchedulerReducer.reduce(s, SchedulerIntent.AddPriorityColumn(childList))
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetPriorityColumnWeight(childList, 1, 0.3))
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetPriorityWeight(cC1, 0, 2.0))
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetPriorityWeight(cC1, 1, 5.0))

        val text = SchedulerDomain.copyTreeText(
            s,
            org.example.project.scheduler.state.SchedulerSelection(main = cP, selected = setOf(cP)),
        )

        // Paste into a fresh tree's first (empty) cell.
        var dst = SchedulerState.empty()
        val target = dst.lists[dst.rootListId]!!.cellIds[0]
        dst = SchedulerReducer.reduce(
            dst,
            SchedulerIntent.ClickCell(
                cellId = target,
                ctrl = false,
                shift = false,
                visibleOrder = SchedulerDomain.selectableVisibleOrder(dst),
            ),
        )
        dst = SchedulerReducer.reduce(dst, SchedulerIntent.PasteTree(text))

        // The pasted P: min time + child column header restored.
        val newP = dst.cells[target]!!.taskId!!
        assertEquals("P", dst.tasks[newP]!!.title)
        assertEquals(30, dst.tasks[newP]!!.minimumMinutes)
        val newChildList = dst.tasks[newP]!!.childListId!!
        assertEquals(listOf(1.0, 0.3), dst.lists[newChildList]!!.weightColumns)
        // The pasted C1: value row + min time restored.
        val newC1 = dst.lists[newChildList]!!.cellIds.first { dst.cells[it]!!.taskId != null }
        assertEquals(listOf(2.0, 5.0), dst.cells[newC1]!!.priorityWeights)
        assertEquals(90, dst.tasks[dst.cells[newC1]!!.taskId!!]!!.minimumMinutes)
    }

    @Test
    fun select_all_visible_selects_every_visible_cell() {
        val s0 = seedThreeTasks()
        val s = SchedulerReducer.reduce(s0, SchedulerIntent.SelectAllVisibleCells)
        val visible = SchedulerDomain.selectableVisibleOrder(s)
        assertEquals(visible.toSet(), s.selection.selected)
        assertEquals(visible.last(), s.selection.main)
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
