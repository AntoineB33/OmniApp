package org.example.project.scheduler.state

import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.Cell
import org.example.project.scheduler.model.CellId
import org.example.project.scheduler.model.CellList
import org.example.project.scheduler.model.CellListId
import org.example.project.scheduler.model.Task
import org.example.project.scheduler.model.TaskId

object SchedulerReducer {
    fun reduce(state: SchedulerState, intent: SchedulerIntent): SchedulerState {
        return when (intent) {
            is SchedulerIntent.ClickCell -> reduceClick(state, intent)
            is SchedulerIntent.ToggleExpand -> commitDelta(state, ToggleExpandDelta(intent.cellId))
            is SchedulerIntent.SetCellTitle -> commitDelta(state, setCellTitleDelta(state, intent.cellId, intent.title))
            is SchedulerIntent.AssignTaskId -> commitDelta(state, assignTaskIdDelta(state, intent.cellId, intent.taskId))
            SchedulerIntent.Undo -> undo(state)
            SchedulerIntent.Redo -> redo(state)
        }
    }

    private fun reduceClick(state: SchedulerState, intent: SchedulerIntent.ClickCell): SchedulerState {
        if (!SchedulerDomain.isSelectableCell(state, intent.cellId)) return state

        val visibleOrder =
            intent.visibleOrder.ifEmpty { SchedulerDomain.selectableVisibleOrder(state) }
        val currentMain = state.selection.main
        val newSelection =
            when {
                intent.shift && currentMain != null -> {
                    val ids = visibleOrder
                    val a = ids.indexOf(currentMain)
                    val b = ids.indexOf(intent.cellId)
                    if (a == -1 || b == -1) {
                        SchedulerSelection(main = intent.cellId, selected = emptySet())
                    } else {
                        val (from, to) = if (a <= b) a to b else b to a
                        SchedulerSelection(main = intent.cellId, selected = ids.subList(from, to + 1).toSet())
                    }
                }
                intent.ctrl -> {
                    val toggled =
                        if (state.selection.selected.contains(intent.cellId)) {
                            state.selection.selected - intent.cellId
                        } else {
                            state.selection.selected + intent.cellId
                        }
                    SchedulerSelection(main = intent.cellId, selected = toggled)
                }
                else -> SchedulerSelection(main = intent.cellId, selected = emptySet())
            }

        return commitDelta(
            state,
            SetSelectionDelta(
                before = state.selection,
                after = newSelection,
            ),
        )
    }

    private fun undo(state: SchedulerState): SchedulerState {
        val pointer = state.history.pointer
        if (pointer < 0) return state
        val unit = state.history.units[pointer]
        val undone = unit.delta.undo(state)
        return undone.copy(history = state.history.copy(pointer = pointer - 1))
    }

    private fun redo(state: SchedulerState): SchedulerState {
        val next = state.history.pointer + 1
        if (next >= state.history.units.size) return state
        val unit = state.history.units[next]
        val redone = unit.delta.redo(state)
        return redone.copy(history = state.history.copy(pointer = next))
    }

    private fun commitDelta(state: SchedulerState, forward: Delta): SchedulerState {
        val newState = forward.redo(state)

        val newUnits =
            if (state.history.pointer == state.history.units.lastIndex) {
                state.history.units + HistoryUnit(
                    chronoId = state.history.units.size.toLong(),
                    delta = forward,
                )
            } else {
                state.history.units.take(state.history.pointer + 1) + HistoryUnit(
                    chronoId = (state.history.pointer + 1).toLong(),
                    delta = forward,
                )
            }

        return newState.copy(
            history = state.history.copy(
                pointer = state.history.pointer + 1,
                units = newUnits,
            ),
        )
    }
}

private fun assignTaskIdDelta(
    state: SchedulerState,
    cellId: CellId,
    taskId: TaskId,
): Delta {
    val before = state.captureTree()
    val after =
        if (!SchedulerDomain.canAssignTaskId(state, cellId, taskId)) {
            state
        } else {
            applyAssignTaskId(state, cellId, taskId)
        }.captureTree()
    return TreeMutationDelta(before = before, after = after)
}

private fun applyAssignTaskId(state: SchedulerState, cellId: CellId, taskId: TaskId): SchedulerState {
    val cell = state.cells[cellId] ?: return state
    val targetTask = state.tasks[taskId] ?: return state

    var tasks = state.tasks.toMutableMap()
    val oldTaskId = cell.taskId

    if (oldTaskId != null && oldTaskId != taskId) {
        val oldTask = tasks[oldTaskId] ?: return state
        tasks[oldTaskId] = oldTask.copy(occurrences = oldTask.occurrences - cellId)
    }

    val cells = state.cells.toMutableMap()
    cells[cellId] = cell.copy(taskId = taskId)

    var working = state.copy(cells = cells, tasks = tasks)
    val mergedOccurrences = (targetTask.occurrences + cellId).distinct()
    tasks[taskId] = targetTask.copy(occurrences = SchedulerDomain.sortOccurrences(working, mergedOccurrences))
    working = working.copy(tasks = tasks)

    SchedulerDomain.parentTaskId(working, cellId)?.let { parentId ->
        working = working.copy(tasks = SchedulerDomain.linkChildUnderParent(working.tasks, parentId, taskId))
    }

    return SchedulerDomain.purgeOrphanTasks(working)
}

private fun setCellTitleDelta(
    state: SchedulerState,
    cellId: CellId,
    title: String,
): Delta {
    val before = state.captureTree()
    val after = applySetCellTitle(state, cellId, title).captureTree()
    return TreeMutationDelta(before = before, after = after)
}

private fun applySetCellTitle(state: SchedulerState, cellId: CellId, title: String): SchedulerState {
    if (!SchedulerDomain.isSelectableCell(state, cellId)) return state
    val cell = state.cells[cellId] ?: return state

    var working = state
    val list = working.lists[cell.parentListId] ?: return state

    val isNewTask = cell.taskId == null
    val (taskId, afterAllocate) =
        if (cell.taskId != null) {
            cell.taskId to working
        } else {
            val (id, next) = working.allocateTaskId()
            id to next
        }
    working = afterAllocate

    val previousTask = working.tasks[taskId]
    val previousTitle = previousTask?.title

    val tasks = working.tasks.toMutableMap()
    val task =
        (previousTask ?: Task(id = taskId, title = title)).let { existing ->
            existing.copy(
                title = title,
                occurrences = SchedulerDomain.sortOccurrences(
                    working,
                    (existing.occurrences + cellId).distinct(),
                ),
            )
        }
    tasks[taskId] = task

    if (isNewTask) {
        SchedulerDomain.parentTaskId(working, cellId)?.let { parentId ->
            val linked = SchedulerDomain.linkChildUnderParent(tasks, parentId, taskId)
            tasks.clear()
            tasks.putAll(linked)
        }
    }

    var titleToTaskIds = working.titleToTaskIds
    if (previousTitle != null && previousTitle != title) {
        titleToTaskIds = SchedulerDomain.removeTitleMapping(titleToTaskIds, previousTitle, taskId)
    }
    if (title.isNotEmpty()) {
        titleToTaskIds = SchedulerDomain.addTitleMapping(titleToTaskIds, title, taskId)
    }

    val cells = working.cells.toMutableMap()
    cells[cellId] = cell.copy(taskId = taskId)

    var lists = working.lists.toMutableMap()
    var currentList = lists[cell.parentListId] ?: return state

    if (title.isNotEmpty() && currentList.cellIds.lastOrNull() == cellId) {
        val updatedTask = tasks[taskId]!!
        if (updatedTask.childListId == null) {
            val subListId = CellListId("${taskId.value}/children")
            val subPlaceholderId = CellId("cell/${subListId.value}/0")
            val subPlaceholder =
                Cell(
                    id = subPlaceholderId,
                    parentListId = subListId,
                    taskId = null,
                )
            val subList =
                CellList(
                    id = subListId,
                    parentCellId = cellId,
                    cellIds = listOf(subPlaceholderId),
                )
            cells[subPlaceholderId] = subPlaceholder
            lists[subListId] = subList
            tasks[taskId] = updatedTask.copy(childListId = subListId)
        }

        val placeholderId = CellId("cell/${currentList.id.value}/${currentList.cellIds.size}")
        val placeholder =
            Cell(
                id = placeholderId,
                parentListId = currentList.id,
                taskId = null,
            )
        cells[placeholderId] = placeholder
        currentList = currentList.copy(cellIds = currentList.cellIds + placeholderId)
        lists[currentList.id] = currentList
    }

    var result =
        working.copy(
            cells = cells,
            lists = lists,
            tasks = tasks,
            titleToTaskIds = titleToTaskIds,
        )

    return SchedulerDomain.purgeOrphanTasks(result)
}

private data class SetSelectionDelta(
    val before: SchedulerSelection,
    val after: SchedulerSelection,
) : Delta {
    override fun undo(state: SchedulerState): SchedulerState = state.copy(selection = before)

    override fun redo(state: SchedulerState): SchedulerState = state.copy(selection = after)
}

private data class ToggleExpandDelta(
    val cellId: CellId,
) : Delta {
    override fun undo(state: SchedulerState): SchedulerState = applyToggle(state)

    override fun redo(state: SchedulerState): SchedulerState = applyToggle(state)

    private fun applyToggle(state: SchedulerState): SchedulerState {
        val cell = state.cells[cellId] ?: return state
        if (cell.taskId == null) return state
        val expanded =
            if (state.expanded.contains(cellId)) state.expanded - cellId
            else state.expanded + cellId
        return state.copy(expanded = expanded)
    }
}

private data class TreeMutationDelta(
    val before: TreeSnapshot,
    val after: TreeSnapshot,
) : Delta {
    override fun undo(state: SchedulerState): SchedulerState = state.applyTree(before)

    override fun redo(state: SchedulerState): SchedulerState = state.applyTree(after)
}
