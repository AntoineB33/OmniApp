package org.example.project.scheduler.state

import org.example.project.scheduler.model.CellId
import org.example.project.scheduler.model.TaskId

enum class CellEditMode {
    ChangeTask,
    Rename,
}

data class SchedulerEditSession(
    val cellId: CellId,
    val draftText: String,
    val mode: CellEditMode = CellEditMode.ChangeTask,
    /** `null` = the draft creates/selects a new task (top menu row). */
    val selectedAssignTaskId: TaskId? = null,
    val treeBefore: TreeSnapshot,
)
