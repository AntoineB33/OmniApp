package org.example.project.scheduler.domain

import org.example.project.scheduler.model.CellId
import org.example.project.scheduler.model.CellListId
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.state.SchedulerState

/**
 * PRD §13 the task edit window's **Paths** (user spec 2026-09-26): where a task sits in the LIVE task tree, one
 * row per cell holding it, and where it may be put as well.
 *
 * A row is a CELL, and says the path down to the list it is in — the parent task and the parent's own shortest
 * path. Removing it takes the task out of that list only (`SchedulerIntent.RemoveTaskPath`); adding puts it at
 * the bottom of a parent's list (`SchedulerIntent.AddTaskPath`), under the tree's own rules — never twice in one
 * list, never under its own sub-tree ([SchedulerDomain.canAssignTaskId], the Change Task menu's own rule).
 */
object TaskPathsDomain {
    /** One place the task sits: its [cellId], the task whose list holds it (null = the top level), and that path. */
    data class Occurrence(val cellId: CellId, val parentTaskId: TaskId?, val parentPath: List<String>) {
        /** How the row reads: the parent's path down to it, or "top level". */
        val label: String get() = if (parentPath.isEmpty()) TOP_LEVEL else SearchDomain.pathLabel(parentPath)
    }

    /** A place the task can be added under: [parentTaskId] null = the top level. */
    data class Candidate(val parentTaskId: TaskId?, val label: String)

    const val TOP_LEVEL: String = "top level"

    /** Most candidates the "Add under…" field offers at once. */
    const val MAX_CANDIDATES: Int = 8

    /**
     * The live tree walked once, breadth-first, each list once: every reachable cell with the titles from the top
     * down to its list's task, and every reachable task with its shortest such path.
     */
    private class LiveWalk(state: SchedulerState) {
        val cellPath = HashMap<CellId, List<String>>()
        val taskPath = HashMap<TaskId, List<String>>()

        init {
            val visited = hashSetOf(state.rootListId)
            var frontier = listOf(state.rootListId to emptyList<String>())
            while (frontier.isNotEmpty()) {
                val next = ArrayList<Pair<CellListId, List<String>>>()
                for ((listId, prefix) in frontier) {
                    val list = state.lists[listId] ?: continue
                    for (cellId in list.cellIds) {
                        val taskId = state.cells[cellId]?.taskId ?: continue
                        val task = state.tasks[taskId] ?: continue
                        if (task.title.isEmpty()) continue
                        cellPath[cellId] = prefix
                        if (taskId !in taskPath) taskPath[taskId] = prefix
                        val child = task.childListId ?: continue
                        if (visited.add(child)) next.add(child to prefix + task.title)
                    }
                }
                frontier = next
            }
        }
    }

    /** Every place [taskId] sits in the live tree, shortest path first. */
    fun occurrences(state: SchedulerState, taskId: TaskId): List<Occurrence> {
        val walk = LiveWalk(state)
        return state.cells.values
            .filter { it.taskId == taskId && it.id in walk.cellPath }
            .map { cell ->
                val parent = SchedulerDomain.parentTaskIdOfList(state, cell.parentListId)
                    ?.takeUnless { SchedulerDomain.isRootTask(it) }
                Occurrence(cell.id, parent, walk.cellPath.getValue(cell.id))
            }
            .sortedWith(compareBy({ it.parentPath.size }, { it.label }))
    }

    /**
     * Whether [taskId] may be added under [parentTaskId] (null = the top level): the tree's own assignment rule,
     * asked of the bottom placeholder of that list. A parent that has no list yet is given one when the path is
     * added, and is asked again then.
     */
    fun canAddUnder(state: SchedulerState, taskId: TaskId, parentTaskId: TaskId?): Boolean {
        if (parentTaskId == taskId) return false
        val listId =
            if (parentTaskId == null) state.rootListId
            else state.tasks[parentTaskId]?.childListId ?: return state.tasks[parentTaskId]?.title?.isNotEmpty() == true
        val placeholder = placeholderOf(state, listId) ?: return false
        return SchedulerDomain.canAssignTaskId(state, placeholder, taskId)
    }

    /** The empty cell at the bottom of [listId], where a task is added. */
    fun placeholderOf(state: SchedulerState, listId: CellListId): CellId? =
        state.lists[listId]?.cellIds?.lastOrNull()?.takeIf { SchedulerDomain.isTextuallyEmptyCell(state, it) }

    /**
     * The places the "Add under…" field offers for [query]: the top level, then every task of the live tree whose
     * title matches, best match first — each only when [taskId] may be added there — at most [MAX_CANDIDATES].
     */
    fun candidates(state: SchedulerState, taskId: TaskId, query: String): List<Candidate> {
        val walk = LiveWalk(state)
        val top =
            listOfNotNull(
                Candidate(null, TOP_LEVEL).takeIf {
                    SearchDomain.matchRank(TOP_LEVEL, query) != null && canAddUnder(state, taskId, null)
                },
            )
        val tasks =
            walk.taskPath.entries
                .asSequence()
                .filter { (id, _) -> id != taskId }
                .mapNotNull { (id, path) ->
                    val title = state.tasks[id]?.title ?: return@mapNotNull null
                    SearchDomain.matchRank(title, query)?.let { rank -> Triple(rank, id, path + title) }
                }
                .sortedWith(compareBy({ it.first }, { it.third.size }, { SearchDomain.pathLabel(it.third) }))
                .filter { (_, id, _) -> canAddUnder(state, taskId, id) }
                .take(MAX_CANDIDATES)
                .map { (_, id, path) -> Candidate(id, SearchDomain.pathLabel(path)) }
                .toList()
        return (top + tasks).take(MAX_CANDIDATES)
    }
}
