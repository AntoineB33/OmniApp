package org.example.project.scheduler.state

import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.Cell
import org.example.project.scheduler.model.CellId
import org.example.project.scheduler.model.CellList
import org.example.project.scheduler.model.CellListId
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
enum class HistoryCategory { Edit, Selection, Calendar, Main }

data class SchedulerHistories(
    val edit: SchedulerHistory = SchedulerHistory(),
    val selection: SchedulerHistory = SchedulerHistory(),
    val calendar: SchedulerHistory = SchedulerHistory(),
    val main: SchedulerHistory = SchedulerHistory(),
) {
    fun forCategory(category: HistoryCategory): SchedulerHistory =
        when (category) {
            HistoryCategory.Edit -> edit
            HistoryCategory.Selection -> selection
            HistoryCategory.Calendar -> calendar
            HistoryCategory.Main -> main
        }

    fun withCategory(category: HistoryCategory, history: SchedulerHistory): SchedulerHistories =
        when (category) {
            HistoryCategory.Edit -> copy(edit = history)
            HistoryCategory.Selection -> copy(selection = history)
            HistoryCategory.Calendar -> copy(calendar = history)
            HistoryCategory.Main -> copy(main = history)
        }
}

data class HistoryUnit(
    val chronoId: Long,
    val delta: Delta,
)

sealed interface Delta {
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
     * [TaskPanel]). Persisted user/scheduler data that lives outside [TreeSnapshot]: panel-list
     * changes (manual edits and each scheduling run, PRD §9) go through the [HistoryCategory.Calendar]
     * stack, not the tree snapshot.
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
     * PRD §5 whether the calendar window currently has focus, which routes Ctrl+Z/Y to the calendar
     * history (and away from it when unfocused). Transient session state, not persisted.
     */
    val calendarFocused: Boolean = false,
) {
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
