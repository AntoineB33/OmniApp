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
     * (visualization) and the scheduler (the §9 task score's recent-share term). Per PRD §8 this is intentionally
     * excluded from the Undo/Redo history state (see [org.example.project.scheduler.state.SchedulerState]).
     */
    val record: List<TaskTimeRange> = emptyList(),
    /**
     * PRD §13 Schedule Unit: an ordered list of named sub-steps (each with its own spanning time) that
     * subdivide this task's allotted slot. Empty when the task has no schedule unit. Only meaningful for
     * leaf tasks (PRD §13 exposes the "define schedule unit" menu only when the task has no child task).
     * Part of the Task Tree domain object (PRD §6), so edits go through the content Undo/Redo history.
     */
    val scheduleUnit: List<ScheduleUnitEntry> = emptyList(),
)

/**
 * PRD §13 Schedule Unit element: one sequential sub-step of a task, with a [title] and a [spanMinutes]
 * spanning time (minutes, matching [Task.minimumMinutes]). The running sum of an entry's span and all
 * preceding ones gives its deadline offset from the task's start (see
 * [org.example.project.scheduler.domain.SchedulerDomain.scheduleUnitDeadlines]).
 */
data class ScheduleUnitEntry(
    val title: String,
    val spanMinutes: Int,
)

/**
 * PRD §14 Reminders entry: one row of the standalone reminders list — a [title], its recurrence
 * [spanDays] (in days; a floating-point number > 1), and [timeOfDayMinutes] (the "time in the day" the
 * reminder is placed, as minutes since local midnight in `0..1439`). Independent of the task tree: the
 * reminders list is its own top-level state, not a per-task field. The reminder scheduler turns each
 * entry into recurring **zero-duration, checkable calendar tags** (see
 * [org.example.project.scheduler.domain.SchedulerDomain.choreScheduledPanels]) — unlike the panels they
 * replace, a reminder has no spanning time and is not an obstacle to the §9 task scheduler.
 *
 * (The `Chore*` names are retained internally for now; the user-facing concept is "Reminders".)
 */
data class ChoreEntry(
    val title: String,
    val spanDays: Double,
    val timeOfDayMinutes: Int = 0,
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
 * PRD §8/§9 task panel: one block on the calendar in the schedulable window. A panel is either
 * **auto** (scheduler-generated, [auto] = true — the §9 "task to do now" and the panels that follow
 * it out to +168h) or **user-authored** ([auto] = false — placed via the right-click "add a task"
 * action / edit window, or produced by pinning a record). Orthogonally, a panel is **pinned**
 * ([pinned] = true) when the user has locked it in the edit window: pinned panels survive a
 * reschedule and constrain it (the auto fill flows around them, PRD §9/§10), whereas non-pinned
 * panels are wiped and regenerated on every scheduling run.
 *
 * [taskId] is null for a calendar-only "New task" — creating one here intentionally does NOT create a
 * task in the tree (PRD §8); [title] is the shown label in either case. Like the task record, panels
 * are persisted user/scheduler data that lives outside the [org.example.project.scheduler.state.TreeSnapshot];
 * panel-list changes (manual edits *and* each scheduling run) go through the
 * [org.example.project.scheduler.state.HistoryCategory.Calendar] stack (PRD §5/§9).
 */
data class TaskPanel(
    val id: String,
    val taskId: TaskId?,
    val title: String,
    val startEpochMillis: Long,
    val endEpochMillis: Long,
    /** PRD §9: locked by the user (edit-window toggle); survives + constrains a reschedule. */
    val pinned: Boolean = false,
    /** True for scheduler-generated panels (the §9 auto fill); false for user-authored ones. */
    val auto: Boolean = false,
    /**
     * PRD §14 Reminders: true for a **zero-duration, checkable tag** generated by the reminder scheduler
     * (its [taskId] is null and `start == end`). A reminder has no spanning time, so — unlike the 5-minute
     * panels it replaced — it is NOT an obstacle to the §9 task scheduler ([SchedulerDomain.isSchedulerFixed]
     * excludes it; the auto fill flows straight through). It is kept across rescheduling all the same.
     */
    val chore: Boolean = false,
    /**
     * PRD §14 Reminders: whether the user has checked this reminder off (done). An unchecked reminder
     * whose scheduled time has passed is *overdue* and accumulates on the calendar's now-line
     * ([SchedulerDomain.overdueReminders]). Checking/un-checking is a Calendar History Unit (undoable).
     * Always false for non-reminder panels.
     */
    val checked: Boolean = false,
    /**
     * PRD §8 Overlap Mode: this panel's relative horizontal weight within any time slice it shares with
     * other panels. Within a slice each active panel's width is `weight / Σ weights`, so equal weights
     * give every panel `1/n`. Only meaningful where panels overlap; a panel that is alone always fills
     * the column. Default 1.0.
     */
    val layoutWeight: Double = 1.0,
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
