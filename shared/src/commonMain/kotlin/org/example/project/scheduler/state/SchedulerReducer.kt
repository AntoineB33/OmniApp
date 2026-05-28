package org.example.project.scheduler.state

import org.example.project.scheduler.model.CellId
import org.example.project.scheduler.model.Cell
import org.example.project.scheduler.model.Task
import org.example.project.scheduler.model.TaskId

object SchedulerReducer {
    fun reduce(state: SchedulerState, intent: SchedulerIntent): SchedulerState {
        return when (intent) {
            is SchedulerIntent.ClickCell -> reduceClick(state, intent)
            is SchedulerIntent.ToggleExpand -> commitDelta(state, ToggleExpandDelta(intent.cellId))
            is SchedulerIntent.SetCellTitle -> commitDelta(state, SetCellTitleDelta(intent.cellId, intent.title))
            SchedulerIntent.Undo -> undo(state)
            SchedulerIntent.Redo -> redo(state)
        }
    }

    private fun reduceClick(state: SchedulerState, intent: SchedulerIntent.ClickCell): SchedulerState {
        val currentMain = state.selection.main
        val newSelection =
            when {
                intent.shift && currentMain != null -> {
                    val ids = intent.visibleOrder
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
                    val newSelected =
                        if (state.selection.selected.contains(intent.cellId)) state.selection.selected - intent.cellId
                        else state.selection.selected + intent.cellId
                    SchedulerSelection(main = intent.cellId, selected = newSelected)
                }
                else -> SchedulerSelection(main = intent.cellId, selected = emptySet())
            }

        return commitDelta(
            state,
            SetSelectionDelta(
                before = state.selection,
                after = newSelection,
            )
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
            )
        )
    }
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
        val expanded =
            if (state.expanded.contains(cellId)) state.expanded - cellId
            else state.expanded + cellId
        return state.copy(expanded = expanded)
    }
}

private data class SetCellTitleDelta(
    val cellId: CellId,
    val title: String,
) : Delta {
    override fun undo(state: SchedulerState): SchedulerState {
        // v0.1.0: keep undo minimal (selection/expand are fully reversible; edits are not yet delta-reversible)
        return state
    }

    override fun redo(state: SchedulerState): SchedulerState {
        val cell = state.cells[cellId] ?: return state
        val newTaskId = cell.taskId ?: TaskId("task/${state.tasks.size + 1}")

        val tasks = state.tasks.toMutableMap()
        val oldTask = tasks[newTaskId]
        tasks[newTaskId] = (oldTask ?: Task(id = newTaskId, title = title)).copy(title = title)

        val cells = state.cells.toMutableMap()
        cells[cellId] = cell.copy(taskId = newTaskId)

        // Ensure a placeholder exists at bottom of the list.
        val list = state.lists[cell.parentListId] ?: return state
        val lastId = list.cellIds.lastOrNull()
        val needsPlaceholder = lastId == null || cells[lastId]?.taskId != null
        val newList =
            if (needsPlaceholder) {
                val placeholder = Cell(
                    id = CellId("${list.id.value}/${list.cellIds.size}"),
                    parentListId = list.id,
                    taskId = null,
                )
                cells[placeholder.id] = placeholder
                list.copy(cellIds = list.cellIds + placeholder.id)
            } else {
                list
            }

        val lists = state.lists.toMutableMap()
        lists[newList.id] = newList

        return state.copy(
            tasks = tasks,
            cells = cells,
            lists = lists,
        )
    }
}

