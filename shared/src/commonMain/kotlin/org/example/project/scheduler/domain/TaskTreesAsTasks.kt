package org.example.project.scheduler.domain

import org.example.project.scheduler.model.Cell
import org.example.project.scheduler.model.CellId
import org.example.project.scheduler.model.CellList
import org.example.project.scheduler.model.CellListId
import org.example.project.scheduler.model.Task
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.model.WellKnownIds
import org.example.project.scheduler.state.SchedulerState
import org.example.project.scheduler.state.TaskTreeEntry

/**
 * User rule 2026-11-16: *"The type 'task tree' is redundant, since there already are task elements. … the default db must
 * have a task element, acting as the root where all different task trees diverge, and a second task element, acting as
 * the root of a task tree, child of the first task element."* **A task tree IS a task directly under the global root**
 * ([WellKnownIds.ROOT_TASK]); its tasks are the ones under it. There is one structure, and nothing stands beside it.
 *
 * [of] brings an account written with the stored task trees ([SchedulerState.taskTrees]) into that one structure, on
 * decode (`SchedulerStateCodec`), so a payload joined from synced rows heals the same way:
 *  - the OPEN tree (the live fields) goes under a task named after it, whose priority share is the whole of the root's;
 *  - every other stored tree goes under a task of its own, beside it, with a priority weight of **0** — it took no part
 *    in the plan, and still takes none until the user gives it a share. Its tasks are COPIES under ids of their own:
 *    a task shared with the live tree may hold other children there, and a task has one child list. A copy of a task
 *    the live tree holds leaves its completed-work record to the live one, which is where that work is counted.
 *
 * Every id it makes is a function of the tree's id and the old id, never of a counter: two devices migrating the same
 * rows write the same rows. Nothing is deleted.
 */
object TaskTreesAsTasks {
    /** Whether [state] still holds stored task trees for [of] to bring in. */
    fun pending(state: SchedulerState): Boolean = state.taskTrees.isNotEmpty() || state.activeTaskTreeId != null

    fun of(state: SchedulerState): SchedulerState {
        if (!pending(state)) return state
        val active = state.taskTrees.firstOrNull { it.id == state.activeTaskTreeId }
        var out = state.copy(taskTrees = emptyList(), activeTaskTreeId = null, taskLinks = emptyList())
        if (out.rootListId != WellKnownIds.ROOT_LIST || WellKnownIds.ROOT_LIST !in out.lists) return out
        // The open tree: what the live tree holds at its top goes under a task named after it.
        val liveKey = active?.id?.value ?: "live"
        if (active != null || topTaskIds(out).isNotEmpty()) {
            out = wrapped(out, key = liveKey, title = active?.title?.ifBlank { null } ?: "tree", expand = true)
        }
        // The others, beside it, at no share of the plan.
        for (entry in state.taskTrees) {
            if (entry.id == state.activeTaskTreeId) continue
            out = grafted(out, entry, liveTasks = state.tasks.keys)
        }
        return SchedulerDomain.withRoot(out.copy(titleToTaskIds = SchedulerDomain.buildTitleIndex(out.tasks)))
    }

    /**
     * A fresh account: the root with ONE tree under it, named [title] (its first start, `prepareLoadedState`). Whatever
     * the root's list already holds goes under it. An account whose root already holds a task is left as it is: every
     * task at the top is a tree's root.
     */
    fun seeded(state: SchedulerState, title: String): SchedulerState {
        if (state.rootListId != WellKnownIds.ROOT_LIST || WellKnownIds.ROOT_LIST !in state.lists) return state
        if (topTaskIds(state).isNotEmpty()) return state
        val out = wrapped(state, key = "first", title = title, expand = true)
        return SchedulerDomain.withRoot(out.copy(titleToTaskIds = SchedulerDomain.buildTitleIndex(out.tasks)))
    }

    private fun taskIdOf(key: String) = TaskId("task/tree/$key")

    private fun cellIdOf(key: String) = CellId("cell/tree/$key")

    private fun listIdOf(key: String) = CellListId("list/tree/$key")

    /** The empty row at the end of the root's list, once the trees stand in it. */
    private val ROOT_END = CellId("cell/tree/end")

    private fun topTaskIds(state: SchedulerState): List<TaskId> =
        state.lists[state.rootListId]?.cellIds.orEmpty().mapNotNull { state.cells[it]?.taskId }
            .filter { state.tasks[it]?.title?.isNotEmpty() == true }

    /** The root's list, holding [cell] before its empty row (one is made where it has none). */
    private fun rootWith(state: SchedulerState, rootList: CellList, cell: CellId): Pair<SchedulerState, CellList> {
        val cells = rootList.cellIds.filter { it != ROOT_END }
        val endCell = state.cells[ROOT_END] ?: Cell(ROOT_END, WellKnownIds.ROOT_LIST, taskId = null)
        return state.copy(cells = state.cells + (ROOT_END to endCell)) to rootList.copy(cellIds = cells + cell + ROOT_END)
    }

    /** The live tree's top under a new task [title] at the root, the root's list keeping only the trees. */
    private fun wrapped(state: SchedulerState, key: String, title: String, expand: Boolean): SchedulerState {
        val rootList = state.lists.getValue(WellKnownIds.ROOT_LIST)
        val task = taskIdOf(key)
        val cell = cellIdOf(key)
        val list = listIdOf(key)
        if (task in state.tasks) return state
        val moved = rootList.cellIds.filter { it != ROOT_END }
        val inner = CellList(
            id = list,
            parentCellId = cell,
            cellIds = moved,
            weightColumns = rootList.weightColumns,
            defaultWeights = rootList.defaultWeights,
            optionalTaskIds = rootList.optionalTaskIds,
            optionalTaskValues = rootList.optionalTaskValues,
        )
        val cells = state.cells.toMutableMap()
        for (id in moved) cells[id]?.let { cells[id] = it.copy(parentListId = list) }
        cells[cell] = Cell(cell, WellKnownIds.ROOT_LIST, task, priorityWeights = listOf(1.0))
        val top = moved.mapNotNull { cells[it]?.taskId }
        val tree = Task(id = task, title = title, childTaskIds = top, occurrences = listOf(cell), childListId = list)
        // The root's list starts afresh: its weight table went with what it held.
        val emptied = CellList(id = WellKnownIds.ROOT_LIST, parentCellId = rootList.parentCellId, cellIds = emptyList())
        val (withEnd, root) = rootWith(state.copy(cells = cells), emptied, cell)
        return withEnd.copy(
            lists = withEnd.lists + (list to inner) + (WellKnownIds.ROOT_LIST to root),
            tasks = withEnd.tasks + (task to tree),
            expanded = if (expand) withEnd.expanded + cell else withEnd.expanded,
        )
    }

    /** [entry]'s tree under a task of its own at the root, beside the others, its ids its own. */
    private fun grafted(state: SchedulerState, entry: TaskTreeEntry, liveTasks: Set<TaskId>): SchedulerState {
        val key = entry.id.value
        val task = taskIdOf(key)
        val cell = cellIdOf(key)
        val list = listIdOf(key)
        if (task in state.tasks) return state
        val snapshot = SchedulerDomain.withRootTask(entry.tree)
        val prefix = "tree/$key/"
        fun t(id: TaskId) = if (id == WellKnownIds.ROOT_TASK) task else TaskId("task/" + prefix + id.value)
        fun c(id: CellId) = CellId("cell/" + prefix + id.value)
        fun l(id: CellListId) = if (id == WellKnownIds.ROOT_LIST) list else CellListId("list/" + prefix + id.value)
        val reached = snapshot.cells.values.filter { it.taskId != WellKnownIds.ROOT_TASK }
        val cells = reached.associate { old -> c(old.id) to old.copy(id = c(old.id), parentListId = l(old.parentListId), taskId = old.taskId?.let(::t)) }
        val lists =
            snapshot.lists.values.associate { old ->
                l(old.id) to old.copy(
                    id = l(old.id),
                    parentCellId = if (old.id == WellKnownIds.ROOT_LIST) cell else old.parentCellId?.let(::c),
                    cellIds = old.cellIds.filter { id -> snapshot.cells[id]?.taskId != WellKnownIds.ROOT_TASK }.map(::c),
                    optionalTaskIds = old.optionalTaskIds.mapTo(LinkedHashSet(), ::t),
                    optionalTaskValues = old.optionalTaskValues.mapKeys { (id, _) -> t(id) },
                )
            }
        val tasks =
            snapshot.tasks.values.filter { it.id != WellKnownIds.ROOT_TASK }.associate { old ->
                t(old.id) to old.copy(
                    id = t(old.id),
                    childTaskIds = old.childTaskIds.map(::t),
                    occurrences = old.occurrences.map(::c),
                    childListId = old.childListId?.let(::l),
                    fulfilment = old.fulfilment.mapKeys { (id, _) -> t(id) },
                    // The live task counts the work it shares with this copy.
                    record = if (old.id in liveTasks) emptyList() else old.record,
                    lastTreePath = emptyList(),
                )
            }
        val top = lists[list]?.cellIds.orEmpty().mapNotNull { cells[it]?.taskId }
        val tree = Task(id = task, title = entry.title.ifBlank { "tree" }, childTaskIds = top, occurrences = listOf(cell), childListId = list)
        val treeCell = Cell(cell, WellKnownIds.ROOT_LIST, task, priorityWeights = listOf(0.0))
        val (withEnd, root) = rootWith(state.copy(cells = state.cells + cells + (cell to treeCell)), state.lists.getValue(WellKnownIds.ROOT_LIST), cell)
        return withEnd.copy(
            lists = withEnd.lists + lists + (WellKnownIds.ROOT_LIST to root),
            tasks = withEnd.tasks + tasks + (task to tree),
            expanded = withEnd.expanded + entry.expanded.map(::c).filter { it in cells },
        )
    }
}
