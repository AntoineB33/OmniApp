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

    /** True when the cell has no assigned task or its task title is blank (PRD §5). */
    fun isTextuallyEmptyCell(state: SchedulerState, cellId: CellId): Boolean {
        val cell = state.cells[cellId] ?: return false
        val taskId = cell.taskId ?: return true
        return state.tasks[taskId]?.title.isNullOrEmpty()
    }

    /**
     * True when [cellId] should show a structural expand/collapse arrow (PRD §2):
     * the cell is populated and has an initialized sublist (including auto-expansion placeholders).
     */
    fun hasExpandableSubTree(state: SchedulerState, cellId: CellId): Boolean {
        if (isTextuallyEmptyCell(state, cellId)) return false
        val cell = state.cells[cellId] ?: return false
        val taskId = cell.taskId ?: return false
        return state.tasks[taskId]?.childListId != null
    }

    /**
     * One displayed row: a [cellId] together with the parent occurrence ([renderVia]) it is
     * mirrored under. A cell whose task is assigned to several expanded parents is rendered once
     * per parent, so the same [cellId] appears in multiple occurrences with distinct [renderVia].
     * `renderVia == null` is the root-viewport occurrence. Mirrors [SchedulerSelection.renderVia].
     */
    data class VisibleOccurrence(val cellId: CellId, val renderVia: CellId?)

    /**
     * Depth-first visible order of displayed rows starting at [listId], each tagged with the
     * parent occurrence ([via]) it is rendered under. Collapsed cells omit their subtree.
     */
    fun visibleOccurrences(
        state: SchedulerState,
        listId: CellListId = state.rootListId,
        via: CellId? = null,
    ): List<VisibleOccurrence> {
        val list = state.lists[listId] ?: return emptyList()
        val result = mutableListOf<VisibleOccurrence>()
        for (cellId in list.cellIds) {
            result += VisibleOccurrence(cellId, via)
            val cell = state.cells[cellId] ?: continue
            if (isTextuallyEmptyCell(state, cellId)) continue
            val task = cell.taskId?.let { state.tasks[it] } ?: continue
            val childListId = task.childListId ?: continue
            if (cellId in state.expanded) {
                result += visibleOccurrences(state, childListId, cellId)
            }
        }
        return result
    }

    /**
     * Depth-first visible cell order starting at [listId].
     * Collapsed cells (not in [SchedulerState.expanded]) omit their subtree.
     */
    fun visibleCellOrder(
        state: SchedulerState,
        listId: CellListId = state.rootListId,
    ): List<CellId> = visibleOccurrences(state, listId).map { it.cellId }

    fun selectableVisibleOrder(state: SchedulerState): List<CellId> =
        visibleCellOrder(state).filter { isSelectableCell(state, it) }

    fun selectableVisibleOccurrences(state: SchedulerState): List<VisibleOccurrence> =
        visibleOccurrences(state).filter { isSelectableCell(state, it.cellId) }

    /** Cells highlighted for selection actions (PRD §3). */
    fun activeSelectionCells(selection: SchedulerSelection): Set<CellId> {
        val multi = selection.selected
        return if (multi.isNotEmpty()) multi else setOfNotNull(selection.main)
    }

    fun isInActiveSelection(selection: SchedulerSelection, cellId: CellId): Boolean =
        cellId == selection.main || cellId in selection.selected

    /** True when [cellId] lies in the mirrored subtree expanded under [via]. */
    fun isInVisualSubtree(state: SchedulerState, cellId: CellId, via: CellId): Boolean {
        if (cellId == via) return true
        val childListId =
            state.cells[via]?.taskId?.let { state.tasks[it]?.childListId } ?: return false
        var current: CellId? = cellId
        while (current != null) {
            val parentListId = state.cells[current]?.parentListId ?: return false
            if (parentListId == childListId) return true
            current = state.lists[parentListId]?.parentCellId
        }
        return false
    }

    fun resolveSelectionRenderVia(
        state: SchedulerState,
        cellId: CellId,
        explicitVia: CellId? = null,
        prior: SchedulerSelection? = null,
    ): CellId? {
        if (explicitVia != null) return explicitVia
        // A render-via must be a strict ancestor occurrence the cell is mirrored under; a cell
        // can never be rendered "via itself" (that would leave a root-viewport cell with a
        // non-null via and break shouldShowSelectionHighlight).
        prior?.renderVia?.let { via ->
            if (via != cellId && isInVisualSubtree(state, cellId, via)) return via
        }
        prior?.main?.let { main ->
            if (main != cellId && isInVisualSubtree(state, cellId, main)) return main
        }
        val listId = state.cells[cellId]?.parentListId ?: return null
        return state.lists[listId]?.parentCellId
    }

    fun shouldShowSelectionHighlight(
        selection: SchedulerSelection,
        cellId: CellId,
        localRenderVia: CellId?,
    ): Boolean {
        if (!isInActiveSelection(selection, cellId)) return false
        val via = selection.renderVia ?: return localRenderVia == null
        return localRenderVia == via
    }

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

    /**
     * Contiguous selection that can be drag-moved via double-click & drag (PRD §3). A single
     * selected cell qualifies — double-click & drag moves "the whole selection", which may be one
     * cell; the move vs. edit distinction comes from whether the pointer drags past the touch slop.
     */
    fun canDragMoveSelection(state: SchedulerState, selection: SchedulerSelection): Boolean =
        isSequentialSelectionInSameList(state, selection)

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

    /** Parent task owning [listId] (its `childListId`); MAIN_TASK for the viewport list. */
    fun parentTaskIdOfList(state: SchedulerState, listId: CellListId): TaskId? {
        val list = state.lists[listId] ?: return null
        val parentCellId = list.parentCellId ?: return WellKnownIds.MAIN_TASK
        return state.cells[parentCellId]?.taskId
    }

    /** [taskId] together with every task reachable through its `childTaskIds` links. */
    fun descendantTaskIds(state: SchedulerState, taskId: TaskId): Set<TaskId> {
        val result = mutableSetOf<TaskId>()
        val stack = ArrayDeque(listOf(taskId))
        while (stack.isNotEmpty()) {
            val id = stack.removeLast()
            if (!result.add(id)) continue
            state.tasks[id]?.childTaskIds?.forEach { stack.addLast(it) }
        }
        return result
    }

    /**
     * Whether [movingTaskId] (with its whole sub-tree) may be inserted into the list owning
     * [targetCellId] without breaking PRD constraints: 1 (a taskId cannot appear twice in one
     * list) or 2 (a taskId cannot equal one of its ancestors — that would create an infinite
     * mirrored cycle). [movingCells] are ignored in the duplicate check so a block move does not
     * collide with its own members. A null task (empty placeholder) is always movable.
     */
    fun canMoveTaskIntoList(
        state: SchedulerState,
        movingTaskId: TaskId?,
        targetListId: CellListId,
        targetCellId: CellId,
        movingCells: Set<CellId>,
    ): Boolean {
        if (movingTaskId == null) return true
        val list = state.lists[targetListId] ?: return false
        val existing =
            list.cellIds
                .filter { it !in movingCells }
                .mapNotNull { state.cells[it]?.taskId }
        if (movingTaskId in existing) return false
        val newAncestors = ancestorTaskIds(state, targetCellId)
        val subtree = descendantTaskIds(state, movingTaskId)
        if (newAncestors.any { it in subtree }) return false
        return true
    }

    /**
     * Move [movingOrdered] out of [sourceListId] and into [targetListId] at [insertIndex]. When the
     * two lists are the same this is a plain reorder. Cross-list moves re-point the cells'
     * `parentListId`, relink the moved tasks under the destination's parent task (and unlink the
     * ones no longer present in the source), then re-sort occurrences since depths changed.
     *
     * Because a task's `childListId` is shared by every cell pointing at it, inserting into (or
     * removing from) a list automatically mirrors the change across every expanded occurrence of
     * that list elsewhere (PRD §3 Double Click & Drag mirroring).
     */
    fun applyMoveCellsToList(
        state: SchedulerState,
        sourceListId: CellListId,
        movingOrdered: List<CellId>,
        targetListId: CellListId,
        insertIndex: Int,
    ): SchedulerState {
        if (sourceListId == targetListId) {
            return applyMoveCellsInList(state, sourceListId, movingOrdered, insertIndex)
        }
        val sourceList = state.lists[sourceListId] ?: return state
        val targetList = state.lists[targetListId] ?: return state
        val moving = movingOrdered.toSet()

        val newSource = sourceList.cellIds.filter { it !in moving }
        val clamped = insertIndex.coerceIn(0, targetList.cellIds.size)
        val newTarget = targetList.cellIds.toMutableList().also { it.addAll(clamped, movingOrdered) }

        val cells = state.cells.toMutableMap()
        for (id in movingOrdered) {
            val cell = cells[id] ?: continue
            cells[id] = cell.copy(parentListId = targetListId)
        }

        val lists =
            state.lists +
                (sourceListId to sourceList.copy(cellIds = newSource)) +
                (targetListId to targetList.copy(cellIds = newTarget))

        var working = state.copy(cells = cells, lists = lists)

        var tasks = working.tasks.toMutableMap()
        parentTaskIdOfList(working, targetListId)?.let { targetParent ->
            for (id in movingOrdered) {
                val taskId = working.cells[id]?.taskId ?: continue
                tasks = linkChildUnderParent(tasks, targetParent, taskId).toMutableMap()
            }
        }
        parentTaskIdOfList(working, sourceListId)?.let { sourceParent ->
            val remaining = newSource.mapNotNull { working.cells[it]?.taskId }.toSet()
            val removed =
                movingOrdered
                    .mapNotNull { working.cells[it]?.taskId }
                    .filter { it !in remaining }
                    .toSet()
            tasks[sourceParent]?.let { parent ->
                tasks[sourceParent] = parent.copy(childTaskIds = parent.childTaskIds - removed)
            }
        }
        working = working.copy(tasks = tasks)

        val resorted =
            working.tasks.mapValues { (_, task) ->
                task.copy(occurrences = sortOccurrences(working, task.occurrences))
            }
        return purgeOrphanTasks(working.copy(tasks = resorted))
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
    ): CellId? = neighborSelectableOccurrence(state, cellId, renderVia = null, direction)?.cellId

    /**
     * The displayed row immediately above/below the occurrence ([cellId] rendered under
     * [renderVia]). Resolving by occurrence — not just by [cellId] — is what makes "Down" land on
     * the row actually shown beneath the selected one when the same cell is mirrored under several
     * expanded parents. Falls back to the first occurrence of [cellId] when [renderVia] matches no
     * displayed row (e.g. a stale render-via).
     */
    fun neighborSelectableOccurrence(
        state: SchedulerState,
        cellId: CellId,
        renderVia: CellId?,
        direction: Int,
    ): VisibleOccurrence? {
        val order = selectableVisibleOccurrences(state)
        val exact = order.indexOfFirst { it.cellId == cellId && it.renderVia == renderVia }
        val index = if (exact >= 0) exact else order.indexOfFirst { it.cellId == cellId }
        if (index == -1) return null
        return order.getOrNull(index + direction)
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

    /**
     * PRD §5 Priority assignment: the absolute priority percentage of every task, as a fraction in
     * `[0,1]` (1.0 == 100%).
     *
     * A populated cell's priority weight blends its sub-list's weight columns (see
     * [cellPriorityWeight]); its local share is `cellWeight / Σ cellWeights of the populated cells`.
     * A task's absolute priority is the sum over all cells sharing its `taskId` (so a mirrored
     * sub-tree accumulates priority from each parent). The conceptual root holds 100%, so the MAIN
     * task — its only child — also resolves to 100% and seeds the top-down distribution. Empty
     * placeholder cells (no `taskId`) hold no priority.
     */
    fun absoluteTaskPriorities(state: SchedulerState): Map<TaskId, Double> {
        val cellsByTask = HashMap<TaskId, MutableList<CellId>>()
        for (cell in state.cells.values) {
            val taskId = cell.taskId ?: continue
            cellsByTask.getOrPut(taskId) { mutableListOf() }.add(cell.id)
        }

        // Per-list cache of (column absolute weights, per-column populated sums).
        val listCache = HashMap<CellListId, Pair<List<Double>, List<Double>>>()
        fun listInfo(listId: CellListId): Pair<List<Double>, List<Double>> =
            listCache.getOrPut(listId) {
                val list = state.lists[listId]
                val absW = columnAbsoluteWeights(list?.weightColumns ?: listOf(1.0))
                val populated =
                    list?.cellIds?.filter { state.cells[it]?.taskId != null }.orEmpty()
                val colSums =
                    absW.indices.map { c ->
                        populated.sumOf { state.cells[it]!!.priorityWeights.getOrElse(c) { defaultWeightAt(c) } }
                    }
                absW to colSums
            }

        fun cellWeight(cell: org.example.project.scheduler.model.Cell): Double {
            val (absW, colSums) = listInfo(cell.parentListId)
            var w = 0.0
            for (c in absW.indices) {
                val sum = colSums[c]
                if (sum == 0.0) continue
                w += (cell.priorityWeights.getOrElse(c) { defaultWeightAt(c) } / sum) * absW[c]
            }
            return w
        }

        // Σ of populated cells' weights in a list collapses to Σ of the columns' absolute weights
        // (over columns with a non-zero sum), since each column's values sum back to its own total.
        fun listWeightSum(listId: CellListId): Double {
            val (absW, colSums) = listInfo(listId)
            return absW.indices.sumOf { c -> if (colSums[c] > 0.0) absW[c] else 0.0 }
        }

        val memo = HashMap<TaskId, Double>()
        val visiting = HashSet<TaskId>()

        fun absolute(taskId: TaskId): Double {
            if (taskId == WellKnownIds.MAIN_TASK) return 1.0
            memo[taskId]?.let { return it }
            if (!visiting.add(taskId)) return 0.0 // cycle guard (constraints forbid real cycles)
            var sum = 0.0
            for (cellId in cellsByTask[taskId].orEmpty()) {
                val cell = state.cells[cellId] ?: continue
                val totalWeight = listWeightSum(cell.parentListId)
                if (totalWeight == 0.0) continue
                val parent = parentTaskIdOfList(state, cell.parentListId) ?: continue
                sum += absolute(parent) * (cellWeight(cell) / totalWeight)
            }
            visiting.remove(taskId)
            memo[taskId] = sum
            return sum
        }

        return cellsByTask.keys.associateWith { absolute(it) }
    }

    /**
     * PRD §5 priority weight table: the absolute weight of each column. Column n takes its nominal
     * header weight times the fraction of priority still unclaimed by the preceding columns:
     * `absolute[n] = header[n] * (1 - Σ_{k<n} absolute[k])`.
     */
    fun columnAbsoluteWeights(headers: List<Double>): List<Double> {
        val result = ArrayList<Double>(headers.size)
        var preceding = 0.0
        for (header in headers) {
            val absolute = header * (1.0 - preceding)
            result.add(absolute)
            preceding += absolute
        }
        return result
    }

    /** Blended priority weight of [cellId] across its sub-list's weight columns (PRD §5). */
    fun cellPriorityWeight(state: SchedulerState, cellId: CellId): Double {
        val cell = state.cells[cellId] ?: return 0.0
        val list = state.lists[cell.parentListId] ?: return 0.0
        val absW = columnAbsoluteWeights(list.weightColumns)
        val populated = list.cellIds.filter { state.cells[it]?.taskId != null }
        var w = 0.0
        for (c in absW.indices) {
            val colSum = populated.sumOf { state.cells[it]!!.priorityWeights.getOrElse(c) { defaultWeightAt(c) } }
            if (colSum == 0.0) continue
            w += (cell.priorityWeights.getOrElse(c) { defaultWeightAt(c) } / colSum) * absW[c]
        }
        return w
    }

    /** PRD §5: default value of a weight field by column — column 0 defaults to 1, the rest to 0. */
    private fun defaultWeightAt(column: Int): Double = if (column == 0) 1.0 else 0.0

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

    /** Titles copied from the active selection (PRD §4 Copy). */
    fun copyTitlesFromSelection(state: SchedulerState, selection: SchedulerSelection): List<String> {
        val block = orderedActiveSelectionInList(state, selection)
        val cellIds =
            if (block != null) {
                block.second
            } else {
                selection.main?.let { listOf(it) }.orEmpty()
            }
        return cellIds.map { cellId ->
            val taskId = state.cells[cellId]?.taskId
            taskId?.let { state.tasks[it]?.title }.orEmpty()
        }
    }

    /** Parse clipboard text from Google Sheets or this app (newline rows, tab columns). */
    fun parseClipboardText(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        return text
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .split('\n')
            .map { line -> line.substringBefore('\t') }
    }

    fun formatClipboardText(titles: List<String>): String = titles.joinToString("\n")
}
