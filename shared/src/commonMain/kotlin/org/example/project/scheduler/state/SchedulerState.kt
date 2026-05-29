package org.example.project.scheduler.state

import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.Cell
import org.example.project.scheduler.model.CellId
import org.example.project.scheduler.model.CellList
import org.example.project.scheduler.model.CellListId
import org.example.project.scheduler.model.Task
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.model.WellKnownIds

data class SchedulerSelection(
    val main: CellId? = null,
    val selected: Set<CellId> = emptySet(),
)

data class SchedulerHistory(
    val pointer: Int = -1,
    val units: List<HistoryUnit> = emptyList(),
)

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
    val history: SchedulerHistory,
    val nextTaskCounter: Int = 0,
) {
    fun captureTree(): TreeSnapshot =
        TreeSnapshot(
            cells = cells,
            lists = lists,
            tasks = tasks,
            titleToTaskIds = titleToTaskIds,
            nextTaskCounter = nextTaskCounter,
        )

    fun applyTree(snapshot: TreeSnapshot): SchedulerState =
        copy(
            cells = snapshot.cells,
            lists = snapshot.lists,
            tasks = snapshot.tasks,
            titleToTaskIds = snapshot.titleToTaskIds,
            nextTaskCounter = snapshot.nextTaskCounter,
        )

    fun allocateTaskId(): Pair<TaskId, SchedulerState> {
        val id = TaskId("task/user/$nextTaskCounter")
        return id to copy(nextTaskCounter = nextTaskCounter + 1)
    }

    companion object {
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
                history = SchedulerHistory(),
            )
        }
    }
}
