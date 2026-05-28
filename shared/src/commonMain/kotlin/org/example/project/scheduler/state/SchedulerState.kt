package org.example.project.scheduler.state

import org.example.project.scheduler.model.Cell
import org.example.project.scheduler.model.CellId
import org.example.project.scheduler.model.CellList
import org.example.project.scheduler.model.CellListId
import org.example.project.scheduler.model.Task
import org.example.project.scheduler.model.TaskId

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

data class SchedulerState(
    val rootListId: CellListId,
    val lists: Map<CellListId, CellList>,
    val cells: Map<CellId, Cell>,
    val tasks: Map<TaskId, Task>,
    val expanded: Set<CellId>,
    val selection: SchedulerSelection,
    val history: SchedulerHistory,
) {
    companion object {
        fun empty(): SchedulerState {
            val rootListId = CellListId("list/root")
            val placeholderCell = Cell(
                id = CellId("cell/root/0"),
                parentListId = rootListId,
                taskId = null,
            )
            val rootList = CellList(
                id = rootListId,
                parentCellId = null,
                cellIds = listOf(placeholderCell.id),
            )
            return SchedulerState(
                rootListId = rootListId,
                lists = mapOf(rootListId to rootList),
                cells = mapOf(placeholderCell.id to placeholderCell),
                tasks = emptyMap(),
                expanded = emptySet(),
                selection = SchedulerSelection(),
                history = SchedulerHistory(),
            )
        }
    }
}

