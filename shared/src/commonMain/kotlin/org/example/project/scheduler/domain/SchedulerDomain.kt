package org.example.project.scheduler.domain

import org.example.project.scheduler.model.CellId
import org.example.project.scheduler.model.CellListId
import org.example.project.scheduler.model.Task
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.model.WellKnownIds
import org.example.project.scheduler.state.SchedulerSelection
import org.example.project.scheduler.state.SchedulerState

object SchedulerDomain {
    fun isMainTask(taskId: TaskId?): Boolean = taskId == WellKnownIds.MAIN_TASK

    fun isRootTask(taskId: TaskId?): Boolean = taskId == WellKnownIds.ROOT_TASK

    fun isSelectableCell(state: SchedulerState, cellId: CellId): Boolean {
        val cell = state.cells[cellId] ?: return false
        return !isMainTask(cell.taskId) && !isRootTask(cell.taskId)
    }

    /**
     * Depth-first visible cell order starting at [listId].
     * Collapsed cells (not in [SchedulerState.expanded]) omit their subtree.
     */
    fun visibleCellOrder(
        state: SchedulerState,
        listId: CellListId = state.rootListId,
    ): List<CellId> {
        val list = state.lists[listId] ?: return emptyList()
        val result = mutableListOf<CellId>()
        for (cellId in list.cellIds) {
            result += cellId
            val cell = state.cells[cellId] ?: continue
            val task = cell.taskId?.let { state.tasks[it] } ?: continue
            val childListId = task.childListId ?: continue
            if (cellId in state.expanded) {
                result += visibleCellOrder(state, childListId)
            }
        }
        return result
    }

    fun selectableVisibleOrder(state: SchedulerState): List<CellId> =
        visibleCellOrder(state).filter { isSelectableCell(state, it) }

    /** Cells highlighted for selection actions (PRD §3). */
    fun activeSelectionCells(selection: SchedulerSelection): Set<CellId> {
        val multi = selection.selected
        return if (multi.isNotEmpty()) multi else setOfNotNull(selection.main)
    }

    fun isInActiveSelection(selection: SchedulerSelection, cellId: CellId): Boolean =
        cellId == selection.main || cellId in selection.selected

    /**
     * True when every cell in the active selection shares one parent list and occupies
     * a contiguous block of indices (PRD §3 Double Click & Drag).
     */
    fun isSequentialSelectionInSameList(state: SchedulerState, selection: SchedulerSelection): Boolean {
        val cellIds = activeSelectionCells(selection).filter { isSelectableCell(state, it) }
        if (cellIds.isEmpty()) return false
        val parentListId = state.cells[cellIds.first()]?.parentListId ?: return false
        if (cellIds.any { state.cells[it]?.parentListId != parentListId }) return false
        val list = state.lists[parentListId] ?: return false
        val indices = cellIds.map { list.cellIds.indexOf(it) }.sorted()
        if (indices.any { it < 0 }) return false
        return indices == (indices.first()..indices.last()).toList()
    }

    /** Multi-cell contiguous selection that can be drag-moved (PRD §3). */
    fun canDragMoveSelection(state: SchedulerState, selection: SchedulerSelection): Boolean {
        val cellIds = activeSelectionCells(selection).filter { isSelectableCell(state, it) }
        return cellIds.size >= 2 && isSequentialSelectionInSameList(state, selection)
    }

    /** Active selection in list order, or `null` when not sequential in one list. */
    fun orderedActiveSelectionInList(
        state: SchedulerState,
        selection: SchedulerSelection,
    ): Pair<CellListId, List<CellId>>? {
        if (!isSequentialSelectionInSameList(state, selection)) return null
        val cellIds = activeSelectionCells(selection).filter { isSelectableCell(state, it) }
        val parentListId = state.cells[cellIds.first()]!!.parentListId
        val list = state.lists[parentListId] ?: return null
        val ordered = list.cellIds.filter { it in cellIds }
        return parentListId to ordered
    }

    /**
     * Index in [listCellIds] (after removing [moving]) where the block should be inserted
     * relative to [targetCellId].
     */
    fun moveInsertIndex(
        listCellIds: List<CellId>,
        moving: Set<CellId>,
        targetCellId: CellId,
        insertBefore: Boolean,
    ): Int {
        val without = listCellIds.filter { it !in moving }
        val targetIdx = without.indexOf(targetCellId)
        if (targetIdx < 0) return without.size
        return if (insertBefore) targetIdx else targetIdx + 1
    }

    fun applyMoveCellsInList(
        state: SchedulerState,
        listId: CellListId,
        movingOrdered: List<CellId>,
        insertIndex: Int,
    ): SchedulerState {
        val list = state.lists[listId] ?: return state
        val moving = movingOrdered.toSet()
        val without = list.cellIds.filter { it !in moving }
        val clamped = insertIndex.coerceIn(0, without.size)
        val newIds = without.toMutableList()
        newIds.addAll(clamped, movingOrdered)
        val lists = state.lists + (listId to list.copy(cellIds = newIds))
        return state.copy(lists = lists)
    }

    /** Visible selectable cells from [fromCellId] through [toCellId] (inclusive). */
    fun visibleSelectionRange(
        visibleOrder: List<CellId>,
        fromCellId: CellId,
        toCellId: CellId,
    ): Set<CellId> {
        val a = visibleOrder.indexOf(fromCellId)
        val b = visibleOrder.indexOf(toCellId)
        if (a == -1 || b == -1) return setOf(fromCellId)
        val (from, to) = if (a <= b) a to b else b to a
        return visibleOrder.subList(from, to + 1).toSet()
    }

    fun neighborSelectableCell(
        state: SchedulerState,
        cellId: CellId,
        direction: Int,
    ): CellId? {
        val order = selectableVisibleOrder(state)
        val index = order.indexOf(cellId)
        if (index == -1) return null
        val nextIndex = index + direction
        if (nextIndex !in order.indices) return null
        return order[nextIndex]
    }

    fun firstSelectableChild(state: SchedulerState, cellId: CellId): CellId? {
        val cell = state.cells[cellId] ?: return null
        val taskId = cell.taskId ?: return null
        val childListId = state.tasks[taskId]?.childListId ?: return null
        return state.lists[childListId]
            ?.cellIds
            ?.firstOrNull { isSelectableCell(state, it) }
    }

    fun cellTreeDepth(state: SchedulerState, cellId: CellId): Int {
        var depth = 0
        var listId = state.cells[cellId]?.parentListId ?: return 0
        while (true) {
            val list = state.lists[listId] ?: break
            val parentCellId = list.parentCellId ?: break
            depth++
            val parentCell = state.cells[parentCellId] ?: break
            listId = parentCell.parentListId
        }
        return depth
    }

    fun sortOccurrences(state: SchedulerState, occurrences: List<CellId>): List<CellId> =
        occurrences.distinct().sortedWith(
            compareBy({ cellTreeDepth(state, it) }, { it.value }),
        )

    fun parentTaskId(state: SchedulerState, cellId: CellId): TaskId? {
        val cell = state.cells[cellId] ?: return null
        val list = state.lists[cell.parentListId] ?: return null
        if (list.parentCellId == null) return WellKnownIds.MAIN_TASK
        return state.cells[list.parentCellId]?.taskId
    }

    fun ancestorTaskIds(state: SchedulerState, cellId: CellId): Set<TaskId> {
        val ancestors = mutableSetOf<TaskId>()
        var listId = state.cells[cellId]?.parentListId ?: return ancestors
        while (true) {
            val list = state.lists[listId] ?: break
            val parentCellId = list.parentCellId ?: break
            val parentCell = state.cells[parentCellId] ?: break
            parentCell.taskId?.let { ancestors += it }
            listId = parentCell.parentListId
        }
        return ancestors
    }

    fun siblingTaskIds(state: SchedulerState, cellId: CellId): Set<TaskId> {
        val listId = state.cells[cellId]?.parentListId ?: return emptySet()
        val list = state.lists[listId] ?: return emptySet()
        return list.cellIds
            .filter { it != cellId }
            .mapNotNull { state.cells[it]?.taskId }
            .toSet()
    }

    fun canAssignTaskId(state: SchedulerState, cellId: CellId, taskId: TaskId): Boolean {
        if (!isSelectableCell(state, cellId)) return false
        if (isRootTask(taskId) || isMainTask(taskId)) return false
        if (taskId in siblingTaskIds(state, cellId)) return false
        if (taskId in ancestorTaskIds(state, cellId)) return false
        return true
    }

    /** Shortest path from root through [Task.childTaskIds] links (BFS). */
    fun shortestTaskTreePath(state: SchedulerState, taskId: TaskId): List<TaskId> {
        data class Node(val id: TaskId, val path: List<TaskId>)
        val queue = ArrayDeque(listOf(Node(WellKnownIds.ROOT_TASK, listOf(WellKnownIds.ROOT_TASK))))
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node.id == taskId) return node.path
            val task = state.tasks[node.id] ?: continue
            for (child in task.childTaskIds) {
                queue.add(Node(child, node.path + child))
            }
        }
        return listOf(taskId)
    }

    fun taskPathLabel(state: SchedulerState, taskId: TaskId): String =
        shortestTaskTreePath(state, taskId)
            .mapNotNull { state.tasks[it]?.title }
            .joinToString(" / ")

    fun childTitlesLabel(state: SchedulerState, taskId: TaskId): String {
        val task = state.tasks[taskId] ?: return ""
        return task.childTaskIds
            .mapNotNull { state.tasks[it]?.title }
            .sorted()
            .joinToString(", ")
    }

    data class ChangeTaskMenuEntry(
        /** `null` = "New task" row (creates a new [TaskId] when selected or while typing). */
        val taskId: TaskId?,
        val label: String,
        /** Always `true`: impossible IDs are now hidden from the menu (PRD §4 Filtering). */
        val assignable: Boolean = true,
    )

    private fun matchingUserTaskIds(
        state: SchedulerState,
        text: String,
        excludeTaskId: TaskId? = null,
    ): List<TaskId> =
        state.tasks.keys
            .filter { !isRootTask(it) && !isMainTask(it) }
            .filter { it != excludeTaskId }
            .filter { task ->
                val title = state.tasks[task]?.title.orEmpty()
                // Exact (case-insensitive) title match only: the Change Task menu offers
                // reusing an existing task whose title IS the typed text. A partial match
                // such as "y" against "yu" must NOT surface "yu" (PRD §4); the row only
                // appears once the text equals the title exactly. Empty text matches nothing.
                text.isNotEmpty() && title.equals(text, ignoreCase = true)
            }
            .sortedWith(taskIdMenuSort(state))

    private fun taskIdMenuSort(state: SchedulerState) =
        compareBy<TaskId>(
            { shortestTaskTreePath(state, it).size },
            { taskPathLabel(state, it) },
            { childTitlesLabel(state, it) },
        )

    private fun changeTaskMenuLabel(state: SchedulerState, taskId: TaskId): String {
        val pathLabel = taskPathLabel(state, taskId)
        val childLabel = childTitlesLabel(state, taskId)
        return if (childLabel.isNotEmpty()) "$pathLabel ($childLabel)" else pathLabel
    }

    /**
     * Task IDs eligible for "Change Task" on [cellId] while editing [text].
     * Filters ancestors/siblings; sorts by path length, path label, child titles (PRD §4).
     */
    fun eligibleAssignTaskIds(
        state: SchedulerState,
        cellId: CellId,
        text: String,
        /** Draft task created while "New task" is selected; already represented by that menu row. */
        excludeTaskId: TaskId? = null,
    ): List<TaskId> {
        val forbidden = siblingTaskIds(state, cellId) + ancestorTaskIds(state, cellId)
        return matchingUserTaskIds(state, text, excludeTaskId).filter { it !in forbidden }
    }

    /**
     * All rows in the Change Task menu; first row is always "New task" (PRD §4).
     * Impossible IDs (same list / ancestor path) are hidden, per PRD §4 Filtering.
     */
    fun changeTaskMenuEntries(
        state: SchedulerState,
        cellId: CellId,
        draftText: String,
        excludeTaskId: TaskId? = null,
    ): List<ChangeTaskMenuEntry> {
        val matching = eligibleAssignTaskIds(state, cellId, draftText, excludeTaskId)
        return buildList {
            add(ChangeTaskMenuEntry(taskId = null, label = "New task"))
            for (taskId in matching) {
                add(
                    ChangeTaskMenuEntry(
                        taskId = taskId,
                        label = changeTaskMenuLabel(state, taskId),
                    ),
                )
            }
        }
    }

    fun changeTaskMenuSelectedIndex(
        entries: List<ChangeTaskMenuEntry>,
        selectedAssignTaskId: TaskId?,
    ): Int {
        if (entries.isEmpty()) return -1
        if (selectedAssignTaskId == null) return 0
        return entries.indexOfFirst { it.taskId == selectedAssignTaskId }
    }

    fun titleSimilarity(title: String, input: String): Int {
        if (input.isEmpty()) return 0
        return when {
            title.equals(input, ignoreCase = true) -> 3
            title.startsWith(input, ignoreCase = true) -> 2
            title.contains(input, ignoreCase = true) -> 1
            else -> 0
        }
    }

    fun totalOccurrencesForTitle(state: SchedulerState, title: String): Int =
        state.titleToTaskIds[title]
            .orEmpty()
            .sumOf { state.tasks[it]?.occurrences?.size ?: 0 }

    /**
     * Title suggestions for edit mode (PRD §4 Menu 2).
     * Sort: similarity → alphabetical → taskId count → total occurrence count.
     */
    fun titleSuggestions(state: SchedulerState, input: String): List<String> =
        state.titleToTaskIds.keys
            .filter { it != input }
            .filter { input.isEmpty() || it.contains(input, ignoreCase = true) }
            .sortedWith(
                compareByDescending<String> { titleSimilarity(it, input) }
                    .thenBy { it }
                    .thenBy { state.titleToTaskIds[it]?.size ?: 0 }
                    .thenBy { totalOccurrencesForTitle(state, it) },
            )

    fun linkChildUnderParent(
        tasks: Map<TaskId, Task>,
        parentId: TaskId,
        childId: TaskId,
    ): Map<TaskId, Task> {
        val parent = tasks[parentId] ?: return tasks
        if (childId in parent.childTaskIds) return tasks
        return tasks + (parentId to parent.copy(childTaskIds = parent.childTaskIds + childId))
    }

    fun addTitleMapping(
        titleToTaskIds: Map<String, List<TaskId>>,
        title: String,
        taskId: TaskId,
    ): Map<String, List<TaskId>> {
        val updated = (titleToTaskIds[title].orEmpty() + taskId).distinct()
        return titleToTaskIds + (title to updated)
    }

    fun removeTitleMapping(
        titleToTaskIds: Map<String, List<TaskId>>,
        title: String,
        taskId: TaskId,
    ): Map<String, List<TaskId>> {
        val remaining = titleToTaskIds[title].orEmpty() - taskId
        return if (remaining.isEmpty()) titleToTaskIds - title else titleToTaskIds + (title to remaining)
    }

    fun purgeOrphanTasks(state: SchedulerState): SchedulerState {
        val referenced =
            state.cells.values.mapNotNull { it.taskId }.toSet() +
                setOf(WellKnownIds.ROOT_TASK, WellKnownIds.MAIN_TASK)
        val tasks =
            state.tasks
                .filterKeys { it in referenced }
                .mapValues { (_, task) ->
                    task.copy(childTaskIds = task.childTaskIds.filter { it in referenced })
                }
        val titleToTaskIds = buildTitleIndex(tasks)
        return state.copy(tasks = tasks, titleToTaskIds = titleToTaskIds)
    }

    fun buildTitleIndex(tasks: Map<TaskId, Task>): Map<String, List<TaskId>> {
        val byTitle = mutableMapOf<String, MutableList<TaskId>>()
        for (task in tasks.values) {
            byTitle.getOrPut(task.title) { mutableListOf() }.add(task.id)
        }
        return byTitle.mapValues { (_, ids) -> ids.distinct() }
    }
}
