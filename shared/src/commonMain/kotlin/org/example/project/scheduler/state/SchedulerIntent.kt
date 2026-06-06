package org.example.project.scheduler.state

import org.example.project.scheduler.model.CellId
import org.example.project.scheduler.model.CellListId
import org.example.project.scheduler.model.TaskId

/** PRD §8 extend/shorten: which edge of a calendar block the user grabbed. */
enum class CalendarEdge { Start, End }

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

    /** PRD §10: set a task's minimum time in minutes (clamped to ≥ 0); recorded as a content delta. */
    data class SetTaskMinimumTime(
        val taskId: TaskId,
        val minutes: Int,
    ) : SchedulerIntent

    /**
     * PRD §9: recompute "the task to do now" against [nowMillis]. Dispatched on app start and on a
     * timer; a no-op while the current allocation's deadline is still in the future. Not undoable.
     */
    data class RefreshSchedule(
        val nowMillis: Long,
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

    /**
     * PRD §8 Manual add: place a calendar entry at [startEpochMillis] for the highest-absolute-priority
     * task (alphabetical tie-break), spanning that task's minimum time. Recorded as a calendar delta.
     */
    data class AddManualCalendarEntry(
        val startEpochMillis: Long,
    ) : SchedulerIntent

    /**
     * PRD §8 edit window: replace an entry's task/title and start/end times. [taskId] is null for a
     * calendar-only "New task". Recorded as a calendar delta.
     */
    data class UpdateManualCalendarEntry(
        val id: String,
        val taskId: TaskId?,
        val title: String,
        val startEpochMillis: Long,
        val endEpochMillis: Long,
    ) : SchedulerIntent

    /**
     * PRD §8 manual drag (move): drop an entry so its start is near [desiredStartEpochMillis], keeping
     * its duration. The reducer snaps it to avoid overlaps (sticking to a group's end, jumping before
     * the group past its midpoint) and shrinks it to fit a too-narrow gap. Dispatched once, on release,
     * so the whole drag is a single history unit.
     */
    data class MoveManualCalendarEntry(
        val id: String,
        val desiredStartEpochMillis: Long,
    ) : SchedulerIntent

    /**
     * PRD §8 extend/shorten: grab an entry's start or end edge and drag it to [valueEpochMillis]. The
     * reducer clamps it so it cannot cross a neighbouring entry (or invert below a minimum length).
     */
    data class ResizeManualCalendarEntry(
        val id: String,
        val edge: CalendarEdge,
        val valueEpochMillis: Long,
    ) : SchedulerIntent

    /**
     * PRD §5: mark the calendar focused/unfocused so Ctrl+Z/Y route to (or away from) the calendar
     * history. Not undoable.
     */
    data class SetCalendarFocus(val focused: Boolean) : SchedulerIntent

    /** Ctrl+Z / Ctrl+Y — undo/redo the content history (Edit Mode while editing, else "the rest"). */
    data object Undo : SchedulerIntent
    data object Redo : SchedulerIntent

    /** Alt+Left / Alt+Right — undo/redo the independent selection-state history (PRD §5). */
    data object UndoSelection : SchedulerIntent
    data object RedoSelection : SchedulerIntent
}

