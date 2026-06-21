package org.example.project.scheduler.state

import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.Cell
import org.example.project.scheduler.model.CellId
import org.example.project.scheduler.model.CellList
import org.example.project.scheduler.model.CellListId
import org.example.project.scheduler.model.ChoreEntry
import org.example.project.scheduler.model.Task
import org.example.project.scheduler.model.TaskPanel
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.model.WellKnownIds

data class SchedulerSelection(
    val main: CellId? = null,
    val selected: Set<CellId> = emptySet(),
    /** Fixed end of a Shift+Click / Shift+Arrow range; `null` for disjoint Ctrl multi-select. */
    val rangeAnchor: CellId? = null,
    /**
     * Expanded occurrence that owns the current selection path for mirrored subtrees.
     * `null` for cells rendered in the root viewport list.
     */
    val renderVia: CellId? = null,
)

data class SchedulerHistory(
    val pointer: Int = -1,
    val units: List<HistoryUnit> = emptyList(),
)

/**
 * PRD §5 History Architecture: history is split into independent categories, each with its own list
 * of units and pointer — changes made in Edit Mode, selection-state changes, calendar edits, and
 * "the rest" (tree/expansion mutations). Selection history is undone/redone separately (Alt+Left /
 * Alt+Right) from the content categories (Ctrl+Z / Ctrl+Y).
 *
 * The content categories implement PRD §5's "pointer navigates by context": Ctrl+Z/Y target [Edit]
 * while an Edit-Mode session is open (so it only touches that session's text changes, skipping every
 * other unit), [Calendar] while the calendar is focused (skipping non-calendar units — and non-focus
 * Ctrl+Z skips calendar units), and otherwise [Main].
 */
enum class HistoryCategory { Edit, Selection, Calendar, Main, WindowNav }

/**
 * PRD §7: the focus targets the user navigates between — the task tree plus the floating windows. The
 * focused window is the top layer except when the task tree is focused. Persisted with the rest of the
 * app state.
 */
enum class AppWindow { Tree, Calendar, Reminders, History }

/**
 * PRD §5/§6: every History Unit lives in one shared timeline; the categories are just how the History
 * Manager *queries and groups* that timeline into columns. [WindowNav] holds window-navigation units
 * (PRD §7) — recorded for display but, for now, not walked by any undo/redo command.
 */
data class SchedulerHistories(
    val edit: SchedulerHistory = SchedulerHistory(),
    val selection: SchedulerHistory = SchedulerHistory(),
    val calendar: SchedulerHistory = SchedulerHistory(),
    val main: SchedulerHistory = SchedulerHistory(),
    val windowNav: SchedulerHistory = SchedulerHistory(),
) {
    fun forCategory(category: HistoryCategory): SchedulerHistory =
        when (category) {
            HistoryCategory.Edit -> edit
            HistoryCategory.Selection -> selection
            HistoryCategory.Calendar -> calendar
            HistoryCategory.Main -> main
            HistoryCategory.WindowNav -> windowNav
        }

    fun withCategory(category: HistoryCategory, history: SchedulerHistory): SchedulerHistories =
        when (category) {
            HistoryCategory.Edit -> copy(edit = history)
            HistoryCategory.Selection -> copy(selection = history)
            HistoryCategory.Calendar -> copy(calendar = history)
            HistoryCategory.Main -> copy(main = history)
            HistoryCategory.WindowNav -> copy(windowNav = history)
        }
}

/**
 * PRD §6: one recorded change. [timeMillis] is the exact wall-clock instant (epoch millis) of the
 * change. [chronoId] only breaks ties between units that share the same [timeMillis]: it is **0** by
 * default and becomes 1, 2, … (in commit order) when an earlier retained unit already carries the
 * same timestamp, so truly simultaneous events still sort deterministically.
 */
data class HistoryUnit(
    val timeMillis: Long,
    val chronoId: Long = 0,
    val delta: Delta,
)

sealed interface Delta {
    /** PRD §5/§6 History Manager: a short human-readable name for this unit (shown in the history window). */
    val label: String

    /**
     * PRD §5/§6 History Manager: the human-readable specifics of this unit — one line per concrete change
     * (a renamed cell, an added/removed panel, the selection before→after, …). Derived from the unit's
     * own before/after data so the window can display all of a unit's data, not just its [label]. Empty
     * when the unit carries no meaningful per-item detail.
     */
    val details: List<String> get() = emptyList()

    fun undo(state: SchedulerState): SchedulerState
    fun redo(state: SchedulerState): SchedulerState
}

/** Tree + title index fields affected by structural / edit mutations. */
data class TreeSnapshot(
    val cells: Map<CellId, Cell>,
    val lists: Map<CellListId, CellList>,
    val tasks: Map<TaskId, Task>,
    val titleToTaskIds: Map<String, List<TaskId>>,
    val nextTaskCounter: Int,
    val nextCellCounter: Int,
)

data class SchedulerState(
    val rootListId: CellListId,
    val lists: Map<CellListId, CellList>,
    val cells: Map<CellId, Cell>,
    val tasks: Map<TaskId, Task>,
    val titleToTaskIds: Map<String, List<TaskId>>,
    val expanded: Set<CellId>,
    val selection: SchedulerSelection,
    val editSession: SchedulerEditSession? = null,
    val histories: SchedulerHistories = SchedulerHistories(),
    val nextTaskCounter: Int = 0,
    /** Monotonic suffix for `cell/{listId}/{n}` ids; avoids collisions between paste inserts and auto-expansion. */
    val nextCellCounter: Int = 1,
    /** In-memory clipboard for copy/paste (not persisted). */
    val clipboard: List<String> = emptyList(),
    /**
     * PRD §8/§9 task panels: the calendar blocks in the schedulable window — both scheduler-generated
     * (`auto`) panels and user-authored ones, with `pinned` panels surviving a reschedule (see
     * [TaskPanel]). Persisted user/scheduler data that lives outside [TreeSnapshot]: *manual* panel-list
     * edits (PRD §8) go through the [HistoryCategory.Calendar] stack, not the tree snapshot. An
     * automatic scheduling run (PRD §9) is derived from the state and is NOT recorded in any history.
     */
    val panels: List<TaskPanel> = emptyList(),
    /** Monotonic suffix for `panel/{n}` ids; never reused, so undo need not roll it back. */
    val nextPanelCounter: Int = 0,
    /**
     * PRD §7 Automatic Schedule Switch: while off, the §9 calculation events that update the schedule
     * are deferred until it is turned back on. Persisted (defaults on). Toggling it is not undoable.
     */
    val automaticSchedule: Boolean = true,
    /**
     * PRD §7 the window the user is currently focused on (the task tree or a floating window). Routes
     * Ctrl+Z/Y to that window's history (PRD §5/§6) and gates which surface catches letter typing
     * (PRD §8). Persisted with the rest of the app state.
     */
    val focusedWindow: AppWindow = AppWindow.Tree,
    /**
     * PRD §8 Overlap Mode: whether `O` has armed "allow overlap" for the next calendar move/resize.
     * Transient session state, not persisted and not undoable.
     */
    val overlapArmed: Boolean = false,
    /**
     * PRD §14 Chores Manager: the standalone list of chores (title + spanning time in days). Persisted
     * (survives sessions) but, like the panels/switch, lives outside the [TreeSnapshot] — editing it is
     * not routed through the Undo/Redo tree history.
     */
    val chores: List<ChoreEntry> = emptyList(),
    /**
     * PRD §15 Side tasks: the periodic side tasks to weave into the auto schedule. A hardcoded set in
     * production (seeded by [org.example.project.scheduler.ui.TaskSchedulerViewModel] from
     * [org.example.project.scheduler.domain.SchedulerDomain.DEFAULT_SIDE_TASKS]); empty by default so the
     * scheduler tests that assert exact schedules see no side tasks unless they opt in.
     */
    val sideTasks: List<org.example.project.scheduler.model.SideTask> = emptyList(),
    /**
     * PRD §15 Side tasks: whether the calendar window draws the side tasks. A purely cosmetic display
     * preference (persisted, not undoable) — when off, side-task blocks are hidden and two same-task panels
     * separated only by a hidden side task render as one merged block. The underlying panels and the
     * scheduling (and the side-task notifications) are unaffected; the real spanning time never changes.
     */
    val showSideTasks: Boolean = false,
    /**
     * PRD §14 Reminders: whether the calendar window draws the reminder tags. A purely cosmetic display
     * preference (persisted, not undoable) — when off, reminder tags are hidden. The underlying chores and
     * their scheduling/checked state are unaffected.
     */
    val showReminders: Boolean = true,
    /**
     * PRD §15 Side tasks (20s look-away): whether the spoken voice cue is enabled — when the look-away pause
     * is reached a voice says to look away and, at the pause's end, to resume work (in addition to the
     * notification). On by default; persisted, not undoable.
     */
    val lookAwayVoiceEnabled: Boolean = true,
) {
    /** PRD §8: the calendar catches letter typing / routes Ctrl+Z/Y only while it is the focused window. */
    val calendarFocused: Boolean get() = focusedWindow == AppWindow.Calendar

    // PRD §8: the task record is NOT part of the history state, so it is stripped from snapshots
    // (capture) and re-attached from the live tasks on restore (applyTree). Undo/Redo therefore
    // never reverts records, even though they live on the Task object.
    fun captureTree(): TreeSnapshot =
        TreeSnapshot(
            cells = cells,
            lists = lists,
            tasks = tasks.mapValues { (_, task) -> task.copy(record = emptyList()) },
            titleToTaskIds = titleToTaskIds,
            nextTaskCounter = nextTaskCounter,
            nextCellCounter = nextCellCounter,
        )

    fun applyTree(snapshot: TreeSnapshot): SchedulerState =
        copy(
            cells = snapshot.cells,
            lists = snapshot.lists,
            tasks =
                snapshot.tasks.mapValues { (id, task) ->
                    task.copy(record = tasks[id]?.record ?: emptyList())
                },
            titleToTaskIds = snapshot.titleToTaskIds,
            nextTaskCounter = snapshot.nextTaskCounter,
            nextCellCounter = snapshot.nextCellCounter,
        )

    fun allocateTaskId(): Pair<TaskId, SchedulerState> {
        val id = TaskId("task/user/$nextTaskCounter")
        return id to copy(nextTaskCounter = nextTaskCounter + 1)
    }

    fun allocateCellId(listId: CellListId): Pair<CellId, SchedulerState> {
        val id = CellId("cell/${listId.value}/$nextCellCounter")
        return id to copy(nextCellCounter = nextCellCounter + 1)
    }

    fun allocatePanelId(): Pair<String, SchedulerState> {
        val id = "panel/$nextPanelCounter"
        return id to copy(nextPanelCounter = nextPanelCounter + 1)
    }

    companion object {
        /** Ensures [nextCellCounter] stays above every numeric suffix already used in persisted cell ids. */
        fun deriveNextCellCounter(cells: Collection<CellId>): Int {
            val maxSuffix =
                cells.maxOfOrNull { id ->
                    id.value.substringAfterLast('/').toIntOrNull() ?: -1
                } ?: -1
            return maxOf(maxSuffix + 1, 1)
        }

        fun empty(): SchedulerState {
            val placeholderId = CellId("cell/main/0")
            val placeholder =
                Cell(
                    id = placeholderId,
                    parentListId = WellKnownIds.MAIN_LIST,
                    taskId = null,
                )
            val mainList =
                CellList(
                    id = WellKnownIds.MAIN_LIST,
                    parentCellId = null,
                    cellIds = listOf(placeholderId),
                )
            val mainTask =
                Task(
                    id = WellKnownIds.MAIN_TASK,
                    title = "main",
                    childListId = WellKnownIds.MAIN_LIST,
                )
            val rootTask =
                Task(
                    id = WellKnownIds.ROOT_TASK,
                    title = "root",
                    childTaskIds = listOf(WellKnownIds.MAIN_TASK),
                )
            val tasks = mapOf(WellKnownIds.ROOT_TASK to rootTask, WellKnownIds.MAIN_TASK to mainTask)
            return SchedulerState(
                rootListId = WellKnownIds.MAIN_LIST,
                lists = mapOf(WellKnownIds.MAIN_LIST to mainList),
                cells = mapOf(placeholderId to placeholder),
                tasks = tasks,
                titleToTaskIds = SchedulerDomain.buildTitleIndex(tasks),
                expanded = emptySet(),
                selection = SchedulerSelection(),
                histories = SchedulerHistories(),
            )
        }
    }
}
