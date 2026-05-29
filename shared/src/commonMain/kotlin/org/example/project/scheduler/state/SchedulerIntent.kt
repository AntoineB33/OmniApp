package org.example.project.scheduler.state

import org.example.project.scheduler.model.CellId
import org.example.project.scheduler.model.TaskId

enum class EditExitNavigation {
    /** Enter — commit and move selection down one visible cell. */
    Down,
    /** Shift+Enter / Shift+Tab — commit and move selection up one visible cell. */
    Up,
    /** Tab — expand sublist and move selection to the first child. */
    TabToChild,
}

sealed interface SchedulerIntent {
    data class ClickCell(
        val cellId: CellId,
        val ctrl: Boolean,
        val shift: Boolean,
        val visibleOrder: List<CellId>,
        /** Double-click on a non-movable cell clears multi-selection (PRD §3). */
        val forceClearMulti: Boolean = false,
    ) : SchedulerIntent

    data class DragSelectCells(
        val anchorCellId: CellId,
        val hoverCellId: CellId,
        val visibleOrder: List<CellId>,
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

    data object Undo : SchedulerIntent
    data object Redo : SchedulerIntent
}

