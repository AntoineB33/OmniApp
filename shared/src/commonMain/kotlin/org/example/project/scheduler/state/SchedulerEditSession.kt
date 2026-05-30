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
    /** `null` = "New task" menu row selected. */
    val selectedAssignTaskId: TaskId? = null,
    /** Task allocated while "New task" is selected (reused until another row is picked). */
    val newTaskDraftId: TaskId? = null,
    val treeBefore: TreeSnapshot,
    /** Snapshot taken when entering Rename; used to revert shared titles when leaving Rename. */
    val renameTreeBefore: TreeSnapshot? = null,
)
