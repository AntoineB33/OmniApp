package org.example.project.scheduler.model

data class Task(
    val id: TaskId,
    val title: String,
    val childTaskIds: List<TaskId> = emptyList(),
    /** Cells referencing this task, ordered by shortest path (maintained on mutation). */
    val occurrences: List<CellId> = emptyList(),
    /** Shared sublist for all cells pointing at this task (mirrored sub-trees). */
    val childListId: CellListId? = null,
)

/**
 * A UI cell that points to a [taskId] (or null for placeholders/empty cells).
 * Multiple cells can point to the same [taskId] across different parents/lists.
 */
data class Cell(
    val id: CellId,
    val parentListId: CellListId,
    val taskId: TaskId?,
)

@JvmInline
value class CellListId(val value: String)

data class CellList(
    val id: CellListId,
    val parentCellId: CellId?, // null for the list rendered in the viewport (main's children)
    val cellIds: List<CellId>,
)
