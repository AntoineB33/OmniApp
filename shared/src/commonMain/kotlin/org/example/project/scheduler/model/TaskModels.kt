package org.example.project.scheduler.model

import kotlin.jvm.JvmInline

/** PRD §10: a task's minimum time defaults to 45 minutes. */
const val DEFAULT_MINIMUM_MINUTES: Int = 45

data class Task(
    val id: TaskId,
    val title: String,
    val childTaskIds: List<TaskId> = emptyList(),
    /** Cells referencing this task, ordered by shortest path (maintained on mutation). */
    val occurrences: List<CellId> = emptyList(),
    /** Shared sublist for all cells pointing at this task (mirrored sub-trees). */
    val childListId: CellListId? = null,
    /**
     * PRD §10 Minimum time for a task: the smallest duration (in minutes) the scheduler may allocate
     * to this task. Stored on the task object; edited via the per-row min-time input field. Defaults
     * to [DEFAULT_MINIMUM_MINUTES] (45 min) so a freshly created task already has a usable slot.
     */
    val minimumMinutes: Int = DEFAULT_MINIMUM_MINUTES,
    /**
     * PRD §8/§9 Task record: the periods during which the user did this task, used by the calendar
     * (visualization) and the scheduler (time-weighted percentage). Per PRD §8 this is intentionally
     * excluded from the Undo/Redo history state (see [org.example.project.scheduler.state.SchedulerState]).
     */
    val record: List<TaskTimeRange> = emptyList(),
)

/**
 * PRD §9 Task record entry: a single period `[start, end]` the user spent on a task, as epoch
 * milliseconds (stored as primitives so it serializes without a custom time serializer).
 */
data class TaskTimeRange(
    val startEpochMillis: Long,
    val endEpochMillis: Long,
)

/**
 * PRD §8 Manual calendar entry: a period the user placed or edited directly on the calendar — via
 * the right-click "add a task" action, the double-click edit window, or by dragging/resizing a block.
 * Unlike a [Task.record] (auto-logged done periods, history-excluded) or the auto [ScheduledTask],
 * it is user-authored and *does* live in the Undo/Redo history (the "manual calendar record edition"
 * delta, PRD §5). [taskId] is null for a calendar-only "New task" — creating one here intentionally
 * does NOT create a task in the tree (PRD §8); [title] is the shown label in either case. A
 * manually-placed entry whose start is still in the future constrains the auto-scheduler so the next
 * computed task does not overlap it (PRD §10 New Task).
 */
data class ManualCalendarEntry(
    val id: String,
    val taskId: TaskId?,
    val title: String,
    val startEpochMillis: Long,
    val endEpochMillis: Long,
)

/**
 * PRD §9 "the task to do now": the scheduler's current allocation — which task to do and until when.
 * The deadline is the start plus the task's minimum time (PRD §10, used here as the allocated
 * duration). Persisted so a restart can tell whether there is still a task to do at this moment, but
 * — like the task record — kept outside the Undo/Redo history.
 */
data class ScheduledTask(
    val taskId: TaskId,
    val startEpochMillis: Long,
    val deadlineEpochMillis: Long,
)

/**
 * A UI cell that points to a [taskId] (or null for placeholders/empty cells).
 * Multiple cells can point to the same [taskId] across different parents/lists.
 */
data class Cell(
    val id: CellId,
    val parentListId: CellListId,
    val taskId: TaskId?,
    /**
     * PRD §5 Priority assignment: this cell's value in each of its sub-list's weight columns
     * (index-aligned with [CellList.weightColumns]). A missing/short entry is treated as 1.
     * Values may be any number ≥ 0 (0 is allowed); default 1.
     */
    val priorityWeights: List<Double> = listOf(1.0),
)

@JvmInline
value class CellListId(val value: String)

data class CellList(
    val id: CellListId,
    val parentCellId: CellId?, // null for the list rendered in the viewport (main's children)
    val cellIds: List<CellId>,
    /**
     * PRD §5 priority weight table: the nominal header weight of each priority column for this
     * sub-list. The absolute weight of column n is `header[n] * (1 - Σ preceding absolute weights)`.
     * Defaults to a single column of weight 1 (equivalent to a plain weighted split).
     */
    val weightColumns: List<Double> = listOf(1.0),
)
