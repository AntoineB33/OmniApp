package org.example.project.scheduler.state

import org.example.project.scheduler.model.CellId
import org.example.project.scheduler.model.CellListId
import org.example.project.scheduler.model.TaskId

enum class SelectionNavigate {
    /** Up / Left — previous visible selectable cell. */
    Previous,
    /** Down / Right — next visible selectable cell. */
    Next,
}

enum class EditExitNavigation {
    /** Enter — commit and move selection down one visible cell. */
    Down,
    /** Shift+Enter / Shift+Tab — commit and move selection up one visible cell. */
    Up,
    /** Tab — expand sublist and move selection to the first child. */
    TabToChild,
    /** Escape — commit and exit edit mode, leaving the selection on the edited cell. */
    Stay,
}

sealed interface SchedulerIntent {
    data class ClickCell(
        val cellId: CellId,
        val ctrl: Boolean,
        val shift: Boolean,
        val visibleOrder: List<CellId>,
        /** Expanded parent occurrence for this row (mirrored subtree path). */
        val renderVia: CellId? = null,
        /** Double-click on a non-movable cell clears multi-selection (PRD §3). */
        val forceClearMulti: Boolean = false,
    ) : SchedulerIntent

    data class DragSelectCells(
        val anchorCellId: CellId,
        val hoverCellId: CellId,
        val visibleOrder: List<CellId>,
        val renderVia: CellId? = null,
    ) : SchedulerIntent

    data class MoveSelectedCells(
        val targetCellId: CellId,
        val insertBefore: Boolean,
    ) : SchedulerIntent

    data object ClearSelection : SchedulerIntent

    data object EmptySelectedCells : SchedulerIntent

    data class ExitEdit(val navigation: EditExitNavigation) : SchedulerIntent

    data class ToggleExpand(val cellId: CellId) : SchedulerIntent

    data class SetCellTitle(
        val cellId: CellId,
        val title: String,
    ) : SchedulerIntent

    data class AssignTaskId(
        val cellId: CellId,
        val taskId: TaskId,
    ) : SchedulerIntent

    /** PRD §5: set a cell's value in a weight column (clamped to ≥ 0); recorded as a content delta. */
    data class SetPriorityWeight(
        val cellId: CellId,
        val column: Int,
        val value: Double,
    ) : SchedulerIntent

    /** PRD §5: set the nominal header weight of a sub-list's priority column (clamped to ≥ 0). */
    data class SetPriorityColumnWeight(
        val listId: CellListId,
        val column: Int,
        val weight: Double,
    ) : SchedulerIntent

    /** PRD §5: insert a new priority weight column at [index] (default appends to the end). */
    data class AddPriorityColumn(
        val listId: CellListId,
        val index: Int = Int.MAX_VALUE,
    ) : SchedulerIntent

    /** PRD §5: reset a column (header + every cell) to its default value ("Reset to default"). */
    data class ResetPriorityColumn(
        val listId: CellListId,
        val column: Int,
    ) : SchedulerIntent

    /** PRD §5: delete a priority weight column from a sub-list ("Delete column"). */
    data class DeletePriorityColumn(
        val listId: CellListId,
        val column: Int,
    ) : SchedulerIntent

    /** PRD §5: reorder a priority column by dragging it to a new position. */
    data class MovePriorityColumn(
        val listId: CellListId,
        val from: Int,
        val to: Int,
    ) : SchedulerIntent

    /** [initialText] non-null when entering via typing (replaces cell content with first keystroke). */
    data class BeginEdit(
        val cellId: CellId,
        val initialText: String? = null,
    ) : SchedulerIntent

    data class UpdateEditText(val text: String) : SchedulerIntent

    data class SetEditMode(val mode: CellEditMode) : SchedulerIntent

    data class PickTaskFromMenu(val taskId: TaskId) : SchedulerIntent

    data object SelectCreateAssignTask : SchedulerIntent

    data class PickTitleSuggestion(val title: String) : SchedulerIntent

    data object CancelEdit : SchedulerIntent

    data class NavigateSelection(
        val direction: SelectionNavigate,
        /** Shift+Direction expands a visible range from [SchedulerSelection.rangeAnchor] or main. */
        val shift: Boolean = false,
    ) : SchedulerIntent

    data class CycleMainSelection(val forward: Boolean) : SchedulerIntent

    /** Tab on a single selected cell: expand and focus the first child when populated. */
    data object SelectFirstChild : SchedulerIntent

    data object CopySelection : SchedulerIntent

    /** Paste [titles] at the main selection (Google Sheets uses newline-separated rows). */
    data class PasteTitles(val titles: List<String>) : SchedulerIntent

    /** Ctrl+Z / Ctrl+Y — undo/redo the content history (Edit Mode while editing, else "the rest"). */
    data object Undo : SchedulerIntent
    data object Redo : SchedulerIntent

    /** Alt+Left / Alt+Right — undo/redo the independent selection-state history (PRD §5). */
    data object UndoSelection : SchedulerIntent
    data object RedoSelection : SchedulerIntent
}

