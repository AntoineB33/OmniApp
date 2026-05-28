package org.example.project.scheduler.domain

import org.example.project.scheduler.model.CellId
import org.example.project.scheduler.model.CellListId
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.model.WellKnownIds
import org.example.project.scheduler.state.SchedulerState

object SchedulerDomain {
    fun isMainTask(taskId: TaskId?): Boolean = taskId == WellKnownIds.MAIN_TASK

    fun isSelectableCell(state: SchedulerState, cellId: CellId): Boolean {
        val cell = state.cells[cellId] ?: return false
        return !isMainTask(cell.taskId)
    }

    /**
     * Depth-first visible cell order starting at [listId].
     * Collapsed cells (not in [SchedulerState.expanded]) omit their subtree.
     */
    fun visibleCellOrder(
        state: SchedulerState,
        listId: CellListId = state.rootListId,
    ): List<CellId> {
        val list = state.lists[listId] ?: return emptyList()
        val result = mutableListOf<CellId>()
        for (cellId in list.cellIds) {
            result += cellId
            val cell = state.cells[cellId] ?: continue
            val task = cell.taskId?.let { state.tasks[it] } ?: continue
            val childListId = task.childListId ?: continue
            if (cellId in state.expanded) {
                result += visibleCellOrder(state, childListId)
            }
        }
        return result
    }

    fun selectableVisibleOrder(state: SchedulerState): List<CellId> =
        visibleCellOrder(state).filter { isSelectableCell(state, it) }

    fun ancestorTaskIds(state: SchedulerState, cellId: CellId): Set<TaskId> {
        val ancestors = mutableSetOf<TaskId>()
        var listId = state.cells[cellId]?.parentListId ?: return ancestors
        while (true) {
            val list = state.lists[listId] ?: break
            val parentCellId = list.parentCellId ?: break
            val parentCell = state.cells[parentCellId] ?: break
            parentCell.taskId?.let { ancestors += it }
            listId = parentCell.parentListId
        }
        return ancestors
    }

    fun siblingTaskIds(state: SchedulerState, cellId: CellId): Set<TaskId> {
        val listId = state.cells[cellId]?.parentListId ?: return emptySet()
        val list = state.lists[listId] ?: return emptySet()
        return list.cellIds
            .filter { it != cellId }
            .mapNotNull { state.cells[it]?.taskId }
            .toSet()
    }

    fun canAssignTaskId(state: SchedulerState, cellId: CellId, taskId: TaskId): Boolean {
        if (!isSelectableCell(state, cellId)) return false
        if (taskId in siblingTaskIds(state, cellId)) return false
        if (taskId in ancestorTaskIds(state, cellId)) return false
        return true
    }

    fun addTitleMapping(
        titleToTaskIds: Map<String, List<TaskId>>,
        title: String,
        taskId: TaskId,
    ): Map<String, List<TaskId>> {
        val updated = (titleToTaskIds[title].orEmpty() + taskId).distinct()
        return titleToTaskIds + (title to updated)
    }

    fun removeTitleMapping(
        titleToTaskIds: Map<String, List<TaskId>>,
        title: String,
        taskId: TaskId,
    ): Map<String, List<TaskId>> {
        val remaining = titleToTaskIds[title].orEmpty() - taskId
        return if (remaining.isEmpty()) titleToTaskIds - title else titleToTaskIds + (title to remaining)
    }

    fun purgeOrphanTasks(state: SchedulerState): SchedulerState {
        val referenced = state.cells.values.mapNotNull { it.taskId }.toSet() +
            setOf(WellKnownIds.ROOT_TASK, WellKnownIds.MAIN_TASK)
        val tasks = state.tasks.filterKeys { it in referenced }
        val titleToTaskIds = buildTitleIndex(tasks)
        return state.copy(tasks = tasks, titleToTaskIds = titleToTaskIds)
    }

    fun buildTitleIndex(tasks: Map<TaskId, org.example.project.scheduler.model.Task>): Map<String, List<TaskId>> {
        val byTitle = mutableMapOf<String, MutableList<TaskId>>()
        for (task in tasks.values) {
            byTitle.getOrPut(task.title) { mutableListOf() }.add(task.id)
        }
        return byTitle.mapValues { (_, ids) -> ids.distinct() }
    }
}
