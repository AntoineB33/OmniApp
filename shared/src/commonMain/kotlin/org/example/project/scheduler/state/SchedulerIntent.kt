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
    /** Commit and exit edit mode, leaving the selection on the edited cell. */
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
     * PRD §13 Schedule Unit "Save": replace a task's schedule unit with [entries] (empty clears it).
     * Recorded as a content delta so it is part of the Undo/Redo history (PRD §6). The caller (the edit
     * window) only enables Save when [SchedulerDomain.canSaveScheduleUnit] holds, but the reducer also
     * defends against an over-budget sum so it can never persist an invalid unit.
     */
    data class SetScheduleUnit(
        val taskId: TaskId,
        val entries: List<org.example.project.scheduler.model.ScheduleUnitEntry>,
    ) : SchedulerIntent

    /**
     * PRD §14 Reminders: replace the whole reminders list with [entries] (rows are edited live in the
     * floating window) and regenerate the reminder calendar tags anchored at [todayStartMillis] (local
     * midnight of today, supplied by the caller which knows the time zone). Persisted but not part of the
     * tree Undo/Redo history (see [SchedulerState.chores]); a reminder's checked state survives the
     * regeneration.
     */
    data class SetChores(
        val entries: List<org.example.project.scheduler.model.ChoreEntry>,
        val todayStartMillis: Long = 0L,
    ) : SchedulerIntent

    /**
     * PRD §14 Reminders "checking off": mark the reminder tag [panelId] as [checked] (done) or un-check it.
     * Recorded as a Calendar History Unit (undoable while the calendar is focused), like any other panel
     * change. A no-op when [panelId] is not a reminder tag or already in the requested state.
     */
    data class SetReminderChecked(
        val panelId: String,
        val checked: Boolean,
    ) : SchedulerIntent

    /**
     * PRD §15 Side tasks: replace the side-task list — used at launch to seed each side task's
     * `lastRestMillis` from the OS sleep history (the last device rest ≥ its duration). Session state,
     * not undoable.
     */
    data class SetSideTasks(
        val sideTasks: List<org.example.project.scheduler.model.SideTask>,
    ) : SchedulerIntent

    /**
     * PRD §9 calculation event: regenerate the schedule against [nowMillis] — advance past any
     * completed panel, then refill the non-pinned panels out to +168h ([SchedulerDomain.fillSchedule]).
     * Dispatched by the debounced tree-change event and the deferred calendar timer. Gated by PRD §7:
     * a no-op while [SchedulerState.automaticSchedule] is off (the event waits for it to turn on). The
     * refill is committed as a Calendar History Unit (PRD §9); the record side effects are not undoable.
     */
    data class RefreshSchedule(
        val nowMillis: Long,
    ) : SchedulerIntent

    /**
     * PRD §9: the frequent tick — advance the schedule to [nowMillis] without refilling. Records the
     * elapsed period of any completed auto panel (and cuts the current one if its task was deleted or
     * gained a child), so the calendar stays truthful even while §7 auto-scheduling is off. Touches
     * only the history-excluded record / panel-progress state, so it is not undoable.
     */
    data class AdvanceSchedule(
        val nowMillis: Long,
    ) : SchedulerIntent

    /** PRD §7 Automatic Schedule Switch: enable/disable auto-scheduling. Persisted; not undoable. */
    data class SetAutomaticSchedule(
        val enabled: Boolean,
    ) : SchedulerIntent

    /**
     * PRD §12 Device sleep: the device was asleep for `[sleepStartEpochMillis, sleepEndEpochMillis]`
     * (detected on wake as a tick gap far larger than the cadence), so the user was NOT doing the
     * scheduled task during it. Cuts the in-progress scheduled period at the sleep start (recording
     * only the pre-sleep work) and clears the schedule; the following [RefreshSchedule] (at wake time)
     * re-picks a task starting after the sleep, leaving the sleep window as a hole in the calendar
     * panel. Not undoable (touches the history-excluded schedule/record only).
     */
    data class ReportDeviceSleep(
        val sleepStartEpochMillis: Long,
        val sleepEndEpochMillis: Long,
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

    /** PRD §3: Ctrl+A selects every visible (selectable) cell. */
    data object SelectAllVisibleCells : SchedulerIntent

    /** PRD §4 Copy: serialize the selected cells' subtrees to the (system) clipboard. */
    data object CopySelection : SchedulerIntent

    /**
     * PRD §4 Paste: rebuild the tree structure serialized in [text] at the single selected cell — a
     * no-op unless [text] is in the app's tab-indented format.
     */
    data class PasteTree(val text: String) : SchedulerIntent

    /**
     * PRD §8 Manual add / edit window "save": add a user-authored panel (`auto = false`) with the
     * given task/title/bounds. [taskId] is null for a calendar-only "New task" (does NOT create a tree
     * task). [pinned] reflects the edit-window pin toggle. Recorded as a calendar delta.
     */
    data class AddTaskPanel(
        val taskId: TaskId?,
        val title: String,
        val startEpochMillis: Long,
        val endEpochMillis: Long,
        val pinned: Boolean,
    ) : SchedulerIntent

    /**
     * PRD §8 edit window / drag / resize commit: replace a panel's task/title/bounds and [pinned] state
     * (the edit-window pin toggle). Editing turns an auto panel into a user-authored one (re-id'd into
     * the persistent `panel/{n}` namespace). [taskId] is null for a calendar-only "New task". Recorded
     * as a calendar delta.
     */
    data class UpdateTaskPanel(
        val id: String,
        val taskId: TaskId?,
        val title: String,
        val startEpochMillis: Long,
        val endEpochMillis: Long,
        val pinned: Boolean,
        /** PRD §8 Overlap Mode: keep the raw (possibly overlapping) bounds and seed the panel to 1/n. */
        val allowOverlap: Boolean = false,
    ) : SchedulerIntent

    /**
     * PRD §8 manual drag (move): drop a panel so its start is near [desiredStartEpochMillis], keeping
     * its duration. The reducer snaps it to avoid overlaps (sticking to a group's end, jumping before
     * the group past its midpoint) and shrinks it to fit a too-narrow gap. Dispatched once, on release,
     * so the whole drag is a single history unit.
     */
    data class MoveTaskPanel(
        val id: String,
        val desiredStartEpochMillis: Long,
    ) : SchedulerIntent

    /**
     * PRD §8 extend/shorten: grab a panel's start or end edge and drag it to [valueEpochMillis]. The
     * reducer clamps it so it cannot cross a neighbouring panel (or invert below a minimum length).
     */
    data class ResizeTaskPanel(
        val id: String,
        val edge: CalendarEdge,
        val valueEpochMillis: Long,
    ) : SchedulerIntent

    /**
     * PRD §8 task contextual menu ("Remove"): delete a panel. Recorded as a calendar delta so it can
     * be undone while the calendar is focused.
     */
    data class RemoveTaskPanel(
        val id: String,
    ) : SchedulerIntent

    /**
     * PRD §8 "Remove" on a *merged* calendar block (consecutive same-task panels shown as one): delete
     * every backing panel at once, as a single undoable calendar delta.
     */
    data class RemoveTaskPanels(
        val ids: List<String>,
    ) : SchedulerIntent

    /**
     * PRD §8 edit / drag / resize commit on a *merged* calendar block (consecutive same-task panels
     * shown as one): drop all of [removeIds] and lay down a single user-authored panel with the given
     * task/title/bounds/[pinned] — so interacting with the merged block treats the whole visible span as
     * one decision. The result is normalized with the same-task merge on commit. Recorded as one delta.
     */
    data class ReplaceTaskPanels(
        val removeIds: List<String>,
        val taskId: TaskId?,
        val title: String,
        val startEpochMillis: Long,
        val endEpochMillis: Long,
        val pinned: Boolean,
        /** PRD §8 Overlap Mode: keep the raw (possibly overlapping) bounds and seed the panel to 1/n. */
        val allowOverlap: Boolean = false,
    ) : SchedulerIntent

    /**
     * PRD §8 Overlap Mode: set the horizontal [TaskPanel.layoutWeight] of one or more panels (by id) —
     * dispatched when the user drags a vertical edge between two overlapping panels to re-divide their
     * shared width. Recorded as a calendar delta so it is undoable.
     */
    data class SetPanelWeights(val weights: Map<String, Double>) : SchedulerIntent

    /**
     * PRD §8 task contextual menu ("Remove") on an auto task-record block: drop the
     * `[startEpochMillis, endEpochMillis]` period from [taskId]'s record. The record lives outside the
     * Undo/Redo history (PRD §8), so this is a side effect, not an undoable delta.
     */
    data class RemoveRecordPeriod(
        val taskId: TaskId,
        val startEpochMillis: Long,
        val endEpochMillis: Long,
    ) : SchedulerIntent

    /**
     * PRD §8 (uniform blocks): convert an auto task-record period into a user-authored panel. Removes
     * the `[recordStartEpochMillis, recordEndEpochMillis]` range from [recordTaskId]'s record and adds
     * a panel with the given task/title/bounds and [pinned] state.
     */
    data class PinRecordAsPanel(
        val recordTaskId: TaskId,
        val recordStartEpochMillis: Long,
        val recordEndEpochMillis: Long,
        val taskId: TaskId?,
        val title: String,
        val startEpochMillis: Long,
        val endEpochMillis: Long,
        val pinned: Boolean,
        /** PRD §8 Overlap Mode: keep the raw (possibly overlapping) bounds and seed the panel to 1/n. */
        val allowOverlap: Boolean = false,
    ) : SchedulerIntent

    /**
     * PRD §5: mark the calendar focused/unfocused so Ctrl+Z/Y route to (or away from) the calendar
     * history. Not undoable.
     */
    data class SetCalendarFocus(val focused: Boolean) : SchedulerIntent

    /**
     * PRD §8 Overlap Mode: toggle "allow overlap" for the next calendar move/resize (pressing `O` while
     * the calendar is focused). Transient — not undoable.
     */
    data object ToggleCalendarOverlap : SchedulerIntent

    /** Ctrl+Z / Ctrl+Y — undo/redo the content history (Edit Mode while editing, else "the rest"). */
    data object Undo : SchedulerIntent
    data object Redo : SchedulerIntent

    /** Alt+Left / Alt+Right — undo/redo the independent selection-state history (PRD §5). */
    data object UndoSelection : SchedulerIntent
    data object RedoSelection : SchedulerIntent
}

