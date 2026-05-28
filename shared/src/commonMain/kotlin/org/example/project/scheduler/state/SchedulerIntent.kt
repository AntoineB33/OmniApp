package org.example.project.scheduler.state

import org.example.project.scheduler.model.CellId
import org.example.project.scheduler.model.TaskId

sealed interface SchedulerIntent {
    data class ClickCell(
        val cellId: CellId,
        val ctrl: Boolean,
        val shift: Boolean,
        val visibleOrder: List<CellId>,
    ) : SchedulerIntent

    data class ToggleExpand(val cellId: CellId) : SchedulerIntent

    data class SetCellTitle(
        val cellId: CellId,
        val title: String,
    ) : SchedulerIntent

    data class AssignTaskId(
        val cellId: CellId,
        val taskId: TaskId,
    ) : SchedulerIntent

    data object Undo : SchedulerIntent
    data object Redo : SchedulerIntent
}

