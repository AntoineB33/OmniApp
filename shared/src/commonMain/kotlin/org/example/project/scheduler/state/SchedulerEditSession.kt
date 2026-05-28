package org.example.project.scheduler.state

import org.example.project.scheduler.model.CellId

enum class CellEditMode {
    ChangeTask,
    Rename,
}

data class SchedulerEditSession(
    val cellId: CellId,
    val draftText: String,
    val mode: CellEditMode = CellEditMode.ChangeTask,
    val treeBefore: TreeSnapshot,
)
