package org.example.project.scheduler.state

import org.example.project.scheduler.model.CellId
import org.example.project.scheduler.model.TaskId

enum class CellEditMode {
    ChangeTask,
    Rename,
}

data class SchedulerEditSession(
    val cellId: CellId,
    /**
     * Parent occurrence the edited cell is rendered under (mirrors [SchedulerSelection.renderVia]).
     * Disambiguates which copy of a cell mirrored under multiple same-task parents is being
     * edited, so only that occurrence shows the edit UI. `null` = the root-viewport occurrence.
     */
    val renderVia: CellId? = null,
    val draftText: String,
    val mode: CellEditMode = CellEditMode.ChangeTask,
    /** `null` = "New task" menu row selected. */
    val selectedAssignTaskId: TaskId? = null,
    /** The task the last keystroke made while "New task" is selected. The next one makes another (user rule 2026-11-03). */
    val newTaskDraftId: TaskId? = null,
    /** Every task this session made, in order: what Escape and Ctrl+Z give the time on them back from ([EditDrafts]). */
    val draftTaskIds: List<TaskId> = emptyList(),
    val treeBefore: TreeSnapshot,
    /** Snapshot taken when entering Rename; used to revert shared titles when leaving Rename. */
    val renameTreeBefore: TreeSnapshot? = null,
) {
    /** What undoing this session takes back, or null where it made no task. */
    val drafts: EditDrafts?
        get() = draftTaskIds.takeIf { it.isNotEmpty() }?.let { EditDrafts(it.toSet(), treeBefore.cells[cellId]?.taskId) }
}

/**
 * User rule 2026-11-03: the tasks a cell's Edit Mode typed through, and the task it held before them. Each keeps the time
 * the now-line spent on it, so the calendar reads A, AB, ABC; Escape, or Ctrl+Z after Enter, rewrites that as [base] alone
 * ([withTimeGivenBack]). A null [base]: the cell held nothing, and the time goes with the tasks.
 */
data class EditDrafts(val drafts: Set<TaskId>, val base: TaskId?)
