package org.example.project.scheduler.domain

import kotlin.math.roundToInt
import org.example.project.scheduler.model.CellId
import org.example.project.scheduler.model.CellListId
import org.example.project.scheduler.model.ChoreEntry
import org.example.project.scheduler.model.DEFAULT_MINIMUM_MINUTES
import org.example.project.scheduler.model.ScheduleUnitEntry
import org.example.project.scheduler.model.SideTask
import org.example.project.scheduler.model.Task
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.model.TaskPanel
import org.example.project.scheduler.model.TaskTimeRange
import org.example.project.scheduler.model.WellKnownIds
import org.example.project.scheduler.state.CalendarEdge
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
    /**
     * Whether [cellId] holds a real task for priority purposes: it points at a task whose title is not
     * blank. An empty placeholder (no `taskId`) holds none; so does a cell whose task was "deleted" by
     * clearing its title — the cell keeps its id and the task lingers blank (kept alive by its
     * panels/records), but it must not count toward a sub-list's priority divisor nor show a percentage.
     */
    private fun isPopulatedCell(state: SchedulerState, cellId: CellId): Boolean {
        val taskId = state.cells[cellId]?.taskId ?: return false
        return state.tasks[taskId]?.title?.isNotBlank() == true
    }

    fun absoluteTaskPriorities(state: SchedulerState): Map<TaskId, Double> {
        val cellsByTask = HashMap<TaskId, MutableList<CellId>>()
        for (cell in state.cells.values) {
            val taskId = cell.taskId ?: continue
            if (!isPopulatedCell(state, cell.id)) continue
            cellsByTask.getOrPut(taskId) { mutableListOf() }.add(cell.id)
        }

        // Per-list cache of (column absolute weights, per-column populated sums).
        val listCache = HashMap<CellListId, Pair<List<Double>, List<Double>>>()
        fun listInfo(listId: CellListId): Pair<List<Double>, List<Double>> =
            listCache.getOrPut(listId) {
                val list = state.lists[listId]
                val absW = columnAbsoluteWeights(list?.weightColumns ?: listOf(1.0))
                val populated =
                    list?.cellIds?.filter { isPopulatedCell(state, it) }.orEmpty()
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

    // ----- PRD §9 Scheduler -------------------------------------------------------------------

    /**
     * The task's *done* periods at [nowMillis] for scheduling purposes: its recorded sessions plus
     * any manual calendar entries assigned to it (PRD §8 uniform blocks — a manually-placed block in
     * the past counts as time spent, exactly like a record). Each period is clipped to end at
     * [nowMillis] and only periods that started before `now` are kept, so a future/ongoing block
     * contributes only its elapsed part. This is what makes a task that was over-served via manual
     * past blocks no longer be re-picked (PRD §9).
     */
    fun pastPeriodsForTask(state: SchedulerState, taskId: TaskId, nowMillis: Long): List<TaskTimeRange> {
        val recorded = state.tasks[taskId]?.record.orEmpty().asSequence()
        val panels = state.panels.asSequence()
            .filter { it.taskId == taskId }
            .map { TaskTimeRange(it.startEpochMillis, it.endEpochMillis) }
        return (recorded + panels)
            .filter { it.startEpochMillis < nowMillis }
            .map { TaskTimeRange(it.startEpochMillis, minOf(it.endEpochMillis, nowMillis)) }
            .toList()
    }

    /** Whether any cell in the tree currently points at [taskId] (i.e. the task is still in the tree). */
    fun taskHasCells(state: SchedulerState, taskId: TaskId): Boolean =
        state.cells.values.any { it.taskId == taskId }

    /**
     * PRD §9: a task is *schedulable* only when it has no child task — the scheduler picks the leaves
     * of the tree (a parent task is just a grouping; its actual work lives in its children).
     *
     * "Has a child task" is decided structurally: does the task's shared child list hold any populated
     * cell? This is the source of truth — note every *titled* task is given a `childListId` (with an
     * empty placeholder) on creation, so `childListId != null` does NOT mean it has children, and the
     * denormalized [Task.childTaskIds] is only updated for freshly-typed children (not for every way a
     * child can appear), so it can be stale. The [Task.childTaskIds] check is kept as a fast path.
     */
    fun isLeafTask(state: SchedulerState, taskId: TaskId): Boolean {
        val task = state.tasks[taskId] ?: return true
        if (task.childTaskIds.isNotEmpty()) return false
        val childListId = task.childListId ?: return true
        val list = state.lists[childListId] ?: return true
        return list.cellIds.none { state.cells[it]?.taskId != null }
    }

    /**
     * PRD §9: the *schedulable leaf* tasks — leaves of the tree ([isLeafTask]) that are real, titled
     * tasks still in the tree. Empty placeholders, the root/main tasks, tasks no longer pointed at by any
     * cell (kept only for their record, PRD §4/§8), and **blank-titled tasks** (a cell emptied to "delete"
     * the task keeps its id and lingers while panels/records still point at it) are all excluded.
     */
    fun schedulableLeaves(state: SchedulerState): List<TaskId> =
        state.tasks.keys.filter {
            !isRootTask(it) && !isMainTask(it) && taskHasCells(state, it) && isLeafTask(state, it) &&
                state.tasks[it]?.title?.isNotBlank() == true
        }

    /**
     * PRD §9 EDF: the period `T = m / p` (millis) of a leaf task — its minimum time `m` divided by its
     * absolute priority percentage `p`. The task releases an `m`-long job every `T`, so its utilization
     * `m / T = p` is exactly its priority share. A zero-priority task has an infinite period (it is only
     * ever scheduled as a last resort when nothing else is due, PRD §9 "satisfy the priorities").
     */
    fun edfPeriodMillis(minimumMinutes: Int, priority: Double): Double {
        if (priority <= 0.0) return Double.POSITIVE_INFINITY
        return (minimumMinutes.toLong() * MILLIS_PER_MINUTE).toDouble() / priority
    }

    /**
     * PRD §9 task choice (Earliest Deadline First): the leaf task the EDF fill picks *first* at [nowMillis]
     * — the one with the earliest initial deadline, i.e. the shortest period `T = m / p` ([edfPeriodMillis]).
     * Ties (equal periods, e.g. equal minimum + equal priority) break by **higher priority, then
     * alphabetically** (matching §8 manual add and the §9 tie order). Returns null when there is no real
     * leaf task. This is the convenience point-query; the full window fill ([fillSchedule]) tracks each
     * task's deadline across the simulation.
     */
    fun nextTask(
        state: SchedulerState,
        @Suppress("UNUSED_PARAMETER") nowMillis: Long,
    ): TaskId? {
        val absolute = absoluteTaskPriorities(state)
        val leaves = schedulableLeaves(state)
        if (leaves.isEmpty()) return null
        return leaves.minWithOrNull(
            compareBy<TaskId> { edfPeriodMillis(state.tasks[it]?.minimumMinutes ?: 0, absolute[it] ?: 0.0) }
                .thenByDescending { absolute[it] ?: 0.0 }
                .thenBy { state.tasks[it]?.title.orEmpty() },
        )
    }

    private const val MILLIS_PER_MINUTE: Long = 60_000L

    // ----- PRD §8 manual calendar entries -----------------------------------------------------

    /** PRD §8: a manually dragged/resized calendar block never collapses below this length. */
    const val MIN_MANUAL_ENTRY_MILLIS: Long = 60_000L

    /**
     * PRD §8 Manual add: the task chosen by the calendar's right-click "add a task" action — the one
     * with the biggest absolute priority percentage, breaking ties alphabetically by title (the
     * first in alphabetic order wins). Excludes the root/main tasks, tasks no longer in the tree,
     * blank-titled (emptied) tasks, and non-leaf tasks (the calendar schedules only leaves, PRD §8).
     * Returns null when there is no real task to add.
     */
    fun manualAddTaskId(state: SchedulerState): TaskId? {
        val absolute = absoluteTaskPriorities(state)
        val candidates =
            state.tasks.keys.filter {
                !isRootTask(it) && !isMainTask(it) && taskHasCells(state, it) && isLeafTask(state, it) &&
                    state.tasks[it]?.title?.isNotBlank() == true
            }
        if (candidates.isEmpty()) return null
        // minWith over (priority desc, title asc): the minimum is the highest priority, and on a tie
        // the alphabetically-first title.
        return candidates.minWith(
            compareByDescending<TaskId> { absolute[it] ?: 0.0 }
                .thenBy { state.tasks[it]?.title.orEmpty() },
        )
    }

    /**
     * PRD §9: a panel the §9 auto fill must treat as a fixed obstacle — a user-pinned panel. Reminder
     * tags (PRD §14, [TaskPanel.chore]) are explicitly NOT obstacles: they have no spanning time, so the
     * auto fill flows straight through them (they are kept across a fill, but never block or shorten it).
     */
    fun isSchedulerFixed(panel: TaskPanel): Boolean = panel.pinned

    /**
     * PRD §10 New Task: the earliest **fixed** panel (pinned or chore, [isSchedulerFixed]) that starts
     * strictly after [cursor], or null when none. A freshly scheduled auto panel is reduced so it ends no
     * later than this, so it never overlaps a fixed panel (only fixed panels constrain the auto fill).
     */
    fun nextPinnedStartAfter(panels: List<TaskPanel>, cursor: Long): Long? =
        panels.asSequence()
            .filter { isSchedulerFixed(it) && it.startEpochMillis > cursor }
            .minOfOrNull { it.startEpochMillis }

    /** Merge [ranges] into sorted, disjoint occupied blocks; touching or overlapping ranges fuse. */
    fun mergeOccupied(ranges: List<TaskTimeRange>): List<TaskTimeRange> {
        if (ranges.isEmpty()) return emptyList()
        val sorted = ranges.sortedBy { it.startEpochMillis }
        val merged = mutableListOf(sorted.first())
        for (range in sorted.drop(1)) {
            val last = merged.last()
            if (range.startEpochMillis <= last.endEpochMillis) {
                // Touching/overlapping → extend the current block (PRD §8 "consecutive tasks" group).
                merged[merged.lastIndex] =
                    last.copy(endEpochMillis = maxOf(last.endEpochMillis, range.endEpochMillis))
            } else {
                merged.add(range)
            }
        }
        return merged
    }

    /**
     * PRD §8 "Two task panels with the same task are automatically merged unless one is pinned and the
     * other not pinned": fuse touching/overlapping panels that share a (non-null) [TaskPanel.taskId]
     * **and** the same [TaskPanel.pinned] flag into one panel spanning both. A null taskId (a calendar-
     * only "New task") is never "the same task" as anything, so it is left alone. The surviving panel
     * keeps the earlier one's id/title/pin and stays `auto` only when both fused panels were auto (a
     * user-authored panel makes the result user-authored). Panels whose pin state differs are never
     * fused, so a pinned and a non-pinned panel of the same task can sit side by side. Returns the
     * input unchanged (same order, same instance content) when nothing merges.
     */
    fun mergeSameTaskPanels(panels: List<TaskPanel>): List<TaskPanel> {
        if (panels.size < 2) return panels
        val sorted = panels.sortedBy { it.startEpochMillis }
        val result = mutableListOf<TaskPanel>()
        var changed = false
        for (panel in sorted) {
            // Fuse into an already-kept panel of the same task + pin that this one touches/overlaps.
            // Sorted by start, so a different-task panel in between leaves a gap (no overlap, PRD §8)
            // and breaks the adjacency, preventing a merge across it.
            val into = result.indexOfLast {
                it.taskId != null && it.taskId == panel.taskId && it.pinned == panel.pinned &&
                    panel.startEpochMillis <= it.endEpochMillis
            }
            if (into >= 0) {
                val keep = result[into]
                result[into] = keep.copy(
                    endEpochMillis = maxOf(keep.endEpochMillis, panel.endEpochMillis),
                    auto = keep.auto && panel.auto,
                )
                changed = true
            } else {
                result.add(panel)
            }
        }
        return if (changed) result else panels
    }

    /**
     * PRD §8 same-task merge (display grouping): the runs the calendar shows as single blocks. Walking
     * the [panels] in start order, consecutive panels of the same (non-null) task with the same
     * [TaskPanel.pinned] flag that touch or overlap are grouped together; a different task, a pin-state
     * change, a null taskId, or a gap starts a new group. Unlike [mergeSameTaskPanels] this keeps the
     * individual panels (so callers can still act on each backing panel) rather than fusing them — the
     * UI fuses each returned run into one block while the stored panels stay separate.
     *
     * PRD §15 (side tasks hidden): when the calendar hides side tasks, two same-task panels separated only
     * by a side-task gap should read as one continuous block. Set [bridgeGaps] = true and the gap between
     * consecutive same-task/same-pin panels is treated as touching regardless of its width — purely cosmetic
     * (the panels stay separate in state, so the real spanning time is unchanged). This is correct because in
     * the forward fill a same-task run is only ever broken by a side-task pause (a different task or a pinned
     * panel sits in the gap as its own block and so breaks the run on its own); deciding it structurally —
     * rather than matching the live side-task projection, which is recomputed at the current `now` while the
     * gaps come from the last schedule — avoids a flicker as `now` advances (the two were drifting apart). A
     * different/pinned block between the two panels still breaks the run because it is a separate block in the
     * sorted input. With [bridgeGaps] = false this is the original touch-or-overlap grouping.
     */
    fun groupSameTaskPanelsForDisplay(
        panels: List<TaskPanel>,
        bridgeGaps: Boolean = false,
    ): List<List<TaskPanel>> {
        if (panels.isEmpty()) return emptyList()
        val sorted = panels.sortedBy { it.startEpochMillis }
        val groups = mutableListOf<MutableList<TaskPanel>>()
        for (panel in sorted) {
            val group = groups.lastOrNull()
            val head = group?.first()
            val frontier = group?.maxOf { it.endEpochMillis } ?: Long.MIN_VALUE
            val mergeable = head != null &&
                panel.taskId != null && head.taskId == panel.taskId &&
                head.pinned == panel.pinned &&
                (panel.startEpochMillis <= frontier || bridgeGaps)
            if (mergeable) group!!.add(panel) else groups.add(mutableListOf(panel))
        }
        return groups
    }

    /**
     * PRD §8 Manual drag (move): where a block of [duration] dropped near [desiredStart] settles
     * given the [others] already on the calendar, never overlapping them:
     *  - in free space it sits exactly at [desiredStart];
     *  - over a group of consecutive entries it sticks to the group's end, unless the drag's centre is
     *    nearer the group's start than its end, in which case it jumps before the group;
     *  - if the gap it lands in is narrower than [duration] it shrinks to fit (the caller keeps the
     *    original [duration] to restore it in a wider gap, PRD §8 "remembers its original size").
     */
    fun placeDraggedEntry(
        others: List<TaskTimeRange>,
        desiredStart: Long,
        duration: Long,
    ): TaskTimeRange {
        val blocks = mergeOccupied(others)
        val desiredEnd = desiredStart + duration
        val hit = blocks.firstOrNull { it.startEpochMillis < desiredEnd && desiredStart < it.endEpochMillis }
            ?: return TaskTimeRange(desiredStart, desiredEnd)

        val mid = (hit.startEpochMillis + hit.endEpochMillis) / 2
        val dragCentre = desiredStart + duration / 2
        return if (dragCentre < mid) {
            // Before the group, shrinking to the gap left of it.
            val prevEnd = blocks.filter { it.endEpochMillis <= hit.startEpochMillis }
                .maxOfOrNull { it.endEpochMillis } ?: Long.MIN_VALUE
            val end = hit.startEpochMillis
            val start = maxOf(end - duration, prevEnd)
            TaskTimeRange(start, end)
        } else {
            // After the group, shrinking to the gap right of it.
            val nextStart = blocks.filter { it.startEpochMillis >= hit.endEpochMillis }
                .minOfOrNull { it.startEpochMillis } ?: Long.MAX_VALUE
            val start = hit.endEpochMillis
            val end = minOf(start + duration, nextStart)
            TaskTimeRange(start, end)
        }
    }

    /**
     * PRD §8 extend/shorten: the new bounds when the [edge] of [entry] is dragged to [value], clamped
     * so the edge cannot cross a neighbouring entry in [others] ("it can't be dragged any further")
     * nor shrink the block below [MIN_MANUAL_ENTRY_MILLIS].
     */
    fun clampResize(
        others: List<TaskTimeRange>,
        entry: TaskTimeRange,
        edge: CalendarEdge,
        value: Long,
        minLength: Long = MIN_MANUAL_ENTRY_MILLIS,
    ): TaskTimeRange =
        when (edge) {
            CalendarEdge.Start -> {
                val floor = others.filter { it.endEpochMillis <= entry.startEpochMillis }
                    .maxOfOrNull { it.endEpochMillis } ?: Long.MIN_VALUE
                val start = value.coerceIn(floor, entry.endEpochMillis - minLength)
                entry.copy(startEpochMillis = start)
            }
            CalendarEdge.End -> {
                val ceil = others.filter { it.startEpochMillis >= entry.endEpochMillis }
                    .minOfOrNull { it.startEpochMillis } ?: Long.MAX_VALUE
                val end = value.coerceIn(entry.startEpochMillis + minLength, ceil)
                entry.copy(endEpochMillis = end)
            }
        }

    /**
     * PRD §8 Overlap Mode default split: the horizontal weight to give a panel just dropped over
     * `[start, end)` so it ends up occupying `1/n` of the shared width while the [others] keep their
     * existing ratios — `n = 1 + (the number of others it overlaps)`. With a dropped panel of weight `w`
     * against others summing to `S` over `k = n - 1` panels, `w / (w + S) = 1/n` solves to `w = S / k`.
     * Returns 1.0 when it overlaps nothing (so a non-overlapping drop stays full width).
     */
    fun seedOverlapWeight(others: List<TaskPanel>, start: Long, end: Long): Double {
        val overlapping = others.filter { it.startEpochMillis < end && start < it.endEpochMillis }
        if (overlapping.isEmpty()) return 1.0
        return overlapping.sumOf { it.layoutWeight } / overlapping.size
    }

    /** PRD §10: recorded sessions less than this many minutes apart count as one continuous effort. */
    const val SESSION_GAP_MINUTES: Int = 10

    /**
     * PRD §10: minutes of the task's most recent *continuous* effort at [nowMillis] — walking back
     * from `now`, summing recorded sessions while each successive gap (the `now → latest session`
     * gap included) stays under [SESSION_GAP_MINUTES]. Returns 0 once a ≥10-minute gap breaks the
     * streak, so an effort that ended a while ago doesn't shorten the next allocation.
     */
    fun recentContiguousRecordMinutes(record: List<TaskTimeRange>, nowMillis: Long): Long {
        if (record.isEmpty()) return 0
        val gapMillis = SESSION_GAP_MINUTES * MILLIS_PER_MINUTE
        var accumulatedMillis = 0L
        // The start of the more-recent neighbour already counted (or `now` for the latest session).
        var nextBoundary = nowMillis
        for (range in record.sortedByDescending { it.endEpochMillis }) {
            if (nextBoundary - range.endEpochMillis >= gapMillis) break
            accumulatedMillis += (range.endEpochMillis - range.startEpochMillis).coerceAtLeast(0)
            nextBoundary = range.startEpochMillis
        }
        return accumulatedMillis / MILLIS_PER_MINUTE
    }

    /**
     * PRD §10: how long to schedule [task] for at [nowMillis] — its minimum time minus the time it
     * has already been done in the current continuous effort ([recentContiguousRecordMinutes]). Once
     * that effort has met or exceeded the minimum (remainder ≤ 0) the task is scheduled for a fresh
     * full minimum instead — so e.g. a sole task keeps extending by a full period each time, rather
     * than collapsing to a zero-length slot when the just-completed period exactly equals the
     * minimum. Minimum time defaults to 45 minutes.
     */
    fun scheduledSpanMinutes(task: Task, nowMillis: Long): Long {
        val minimum = task.minimumMinutes.toLong()
        val span = minimum - recentContiguousRecordMinutes(task.record, nowMillis)
        return if (span <= 0) minimum else span
    }

    /**
     * PRD §8/§10 state-aware overload of [scheduledSpanMinutes]: the continuous-effort credit counts
     * the task's manual calendar entries in the past too (via [pastPeriodsForTask]), not just its
     * record — so a manually-placed block that just ended shortens the next allocation exactly like a
     * record would. Falls back to a fresh full minimum once the effort already met it.
     */
    fun scheduledSpanMinutes(state: SchedulerState, taskId: TaskId, nowMillis: Long): Long {
        val task = state.tasks[taskId] ?: return 0
        val minimum = task.minimumMinutes.toLong()
        val span = minimum - recentContiguousRecordMinutes(pastPeriodsForTask(state, taskId, nowMillis), nowMillis)
        return if (span <= 0) minimum else span
    }

    /** PRD §9 Scheduling: the auto fill materializes panels out to this far ahead of `now` (168 hours). */
    const val SCHEDULE_HORIZON_MILLIS: Long = 168L * 60 * 60 * 1000

    // ----- PRD §15 Side tasks -----------------------------------------------------------------

    /**
     * PRD §15: the hardcoded set of side tasks — periodic activities placed on the calendar with a real
     * spanning time. The §9 fill weaves them in without letting them reduce the surrounding task's minimum.
     */
    val DEFAULT_SIDE_TASKS: List<SideTask> = listOf(
        // The 20-20-20 micro-break: after a ≥20-second pause, the next look-away is due 20 min later.
        SideTask("look 20 feet away", intervalMillis = 20L * 60_000, durationMillis = 20L * 1_000),
        // The rest poses: after a pause of at least their length, the next one is due an interval later. The
        // 5-min pose merges up into the 15-min pose when their windows would overlap (PRD §15).
        SideTask("take a 5min pose and blink hard", intervalMillis = 60L * 60_000, durationMillis = 5L * 60_000, restBreak = true),
        SideTask("take a 15min pose", intervalMillis = 2L * 60L * 60_000, durationMillis = 15L * 60_000, restBreak = true),
    )

    /** A side task is schedulable when it has a positive interval, a positive duration, and a title. */
    private fun isValidSideTask(side: SideTask): Boolean =
        side.intervalMillis > 0 && side.durationMillis > 0 && side.title.isNotBlank()

    /**
     * PRD §15: the next-occurrence start for [side] at [nowMillis], beginning from its due time
     * `lastRest + interval` (an interval after the last qualifying pause **ended**).
     *
     * How a *past* due time is handled depends on whether the app can tell the pause was taken:
     * - A **rest pose** ([SideTask.restBreak], the 5-/15-min poses) is detectable from device sleep, so an
     *   un-taken one is clamped forward to `now` and waits at the now-line until the user actually rests
     *   (which updates `lastRestMillis`). A never-rested pose (`lastRestMillis == 0`) is due immediately.
     * - The **look-away cadence** (non-rest) is NOT detectable — the app can't know whether the user looked
     *   away — so a fully-elapsed occurrence is *assumed done* and the cadence advances along its fixed grid
     *   (anchored at `lastRest`, stepped by `interval`) to the next slot still live at `now`. It must NOT
     *   slide to the now-line: `lastRestMillis` never updates for it, so clamping to `now` would re-place it
     *   at `now` every tick and make the look-away voice cue repeat indefinitely.
     */
    fun sideTaskNextStart(side: SideTask, nowMillis: Long): Long {
        val due = side.lastRestMillis + side.intervalMillis
        if (side.restBreak) return maxOf(due, nowMillis)
        // The current occurrence still covers `now` (or is future) → keep it; otherwise step the grid forward
        // to the first slot whose window has not already elapsed before `now`.
        if (due + side.durationMillis > nowMillis) return due
        val elapsed = nowMillis - side.durationMillis - due
        val steps = elapsed / side.intervalMillis + 1
        return due + steps * side.intervalMillis
    }

    /**
     * PRD §15: true when [side]'s due time `lastRest + interval` has passed at [nowMillis]. For a rest pose
     * this means it sits at the now-line (and is what drives the per-tick refill that keeps it tracking `now`);
     * the look-away never pins to the now-line, so callers gate this on [SideTask.restBreak].
     */
    fun isSideTaskOverdue(side: SideTask, nowMillis: Long): Boolean =
        side.lastRestMillis + side.intervalMillis <= nowMillis

    /** Safety cap on the side-task projection loop (far above the ~700 occurrences a week-long horizon holds). */
    private const val SIDE_TASK_PROJECTION_LIMIT: Int = 200_000

    /**
     * PRD §15: project [sideTasks] forward from [nowMillis] to [horizonMillis] as obstacle panels,
     * interleaving the three recurrences and resolving their overlaps. Each panel has `sideTask = true`, a
     * null taskId, and a deterministic `side/{index}/{start}` id; invalid rows are skipped.
     *
     * [horizonMillis] defaults to the scheduling horizon (`now + [SCHEDULE_HORIZON_MILLIS]`), which is what
     * the §9 fill uses as a fixed obstacle window. The calendar display passes the **end of the focused week**
     * instead (PRD §15 "computed from now to the end of the currently focused week"), so navigating to a week
     * beyond the default horizon still shows the side-task markers across it.
     *
     * The simulation walks occurrences in time order (ties resolved toward the **longer** pause so a
     * coincident bigger pause is placed first), applying:
     * - **Recurrence:** a rest pose ([SideTask.restBreak]) recurs an interval after it *ends*
     *   (`start + duration + interval`); the cadence look-away recurs an interval after it *starts*
     *   (`start + interval`).
     * - **A pause re-anchors shorter pauses:** placing any pause (overdue at `now` or future) re-anchors every
     *   *shorter* pause to `thisPauseEnd + itsInterval`, so the look-away always lands **20 min after a pose
     *   ends** ("after a ≥20-second pause, the next look-away is 20 minutes later") and never within an
     *   interval of a longer pose. (An overdue *rest pose* seeds at the now-line; the look-away instead seeds
     *   at the next live slot of its fixed grid — see [sideTaskNextStart].)
     * - **Absorption:** a (defensive) skip of any occurrence whose window still falls inside an already-placed
     *   longer pause; it advances its own clock. With the re-anchoring above a shorter pause is normally pushed
     *   clear of a longer one before it would be drawn.
     * - **5 → 15 merge:** when the 5-min pose comes due just before the 15-min pose (its window would overlap
     *   the still-future 15-min pose), it **becomes** a 15-min pose at its own start and the 15-min pose is
     *   pushed to an interval after the merged pause ends (`mergedEnd + 2 h` = 2h15 after the 5-min start).
     */
    fun sideTaskPanels(
        sideTasks: List<SideTask>,
        nowMillis: Long,
        horizonMillis: Long = nowMillis + SCHEDULE_HORIZON_MILLIS,
    ): List<TaskPanel> {
        val horizon = maxOf(horizonMillis, nowMillis)
        val valid = sideTasks.withIndex().filter { isValidSideTask(it.value) }
        if (valid.isEmpty()) return emptyList()
        // The two rest poses (restBreak), shortest first, drive the 5↔15 merge.
        val poses = valid.filter { it.value.restBreak }.sortedBy { it.value.durationMillis }
        val shorterIndex = poses.firstOrNull()?.index
        val longerEntry = poses.lastOrNull()?.takeIf { it.index != shorterIndex }
        val longerIndex = longerEntry?.index
        val longerPose = longerEntry?.value

        // Next-due start per task index; seeded at each task's due time (or `now` when overdue).
        val due = HashMap<Int, Long>(valid.size)
        valid.forEach { (i, t) -> due[i] = sideTaskNextStart(t, nowMillis) }

        val result = mutableListOf<TaskPanel>()
        // True when [start] falls inside an already-placed pause strictly longer than [durationMillis].
        fun coveredByLonger(start: Long, durationMillis: Long): Boolean =
            result.any { p ->
                (p.endEpochMillis - p.startEpochMillis) > durationMillis &&
                    p.startEpochMillis <= start && start < p.endEpochMillis
            }
        // PRD §15: placing a pause re-anchors every *shorter* pause to `thisPauseEnd + itsInterval`, so a
        // shorter pause never lands within its own interval of a longer one that already covers it — e.g.
        // the look-away always restarts 20 min *after a pose ends* ("after a ≥20-second pause, the next
        // look-away is 20 minutes later"), rather than continuing its own grid into the gap right after the
        // pose. Applies to every placed pause (overdue at `now` or future), so the rule holds forward too.
        fun reanchorSmaller(placedEnd: Long, placedDuration: Long) {
            valid.forEach { (j, s) ->
                if (s.durationMillis < placedDuration) {
                    due[j] = placedEnd + s.intervalMillis
                }
            }
        }

        var guard = 0
        while (guard++ < SIDE_TASK_PROJECTION_LIMIT) {
            // The earliest pending occurrence within the horizon; ties go to the longer pause.
            val nextIndex = due.entries
                .filter { it.value <= horizon }
                .minWithOrNull(
                    compareBy<Map.Entry<Int, Long>> { it.value }
                        .thenByDescending { sideTasks[it.key].durationMillis },
                )?.key ?: break
            val task = sideTasks[nextIndex]
            val start = due.getValue(nextIndex)

            // 5 → 15 merge: the 5-min pose's window would overlap the still-future 15-min pose.
            if (longerPose != null && longerIndex != null && nextIndex == shorterIndex) {
                val longerDue = due[longerIndex] ?: Long.MAX_VALUE
                if (longerDue in (start + 1) until (start + task.durationMillis)) {
                    val mergedEnd = start + longerPose.durationMillis
                    if (!coveredByLonger(start, longerPose.durationMillis)) {
                        result.add(sideTaskPanel(longerIndex, longerPose.title, start, mergedEnd))
                    }
                    due[nextIndex] = mergedEnd + task.intervalMillis
                    due[longerIndex] = mergedEnd + longerPose.intervalMillis
                    reanchorSmaller(mergedEnd, longerPose.durationMillis)
                    continue
                }
            }

            val end = start + task.durationMillis
            if (!coveredByLonger(start, task.durationMillis)) {
                result.add(sideTaskPanel(nextIndex, task.title, start, end))
            }
            // Recurrence: poses resume an interval after they end; the cadence look-away an interval after it
            // starts. Placing this pause also pushes every shorter pause to an interval after it ends.
            due[nextIndex] = (if (task.restBreak) end else start) + task.intervalMillis
            reanchorSmaller(end, task.durationMillis)
        }
        return result.sortedBy { it.startEpochMillis }
    }

    private fun sideTaskPanel(index: Int, title: String, start: Long, end: Long): TaskPanel =
        TaskPanel(
            id = "side/$index/$start",
            taskId = null,
            title = title,
            startEpochMillis = start,
            endEpochMillis = end,
            pinned = false,
            auto = false,
            sideTask = true,
        )

    /** The panel whose `[start, end)` contains [nowMillis] (the "task to do now"), or null. */
    fun panelAt(panels: List<TaskPanel>, nowMillis: Long): TaskPanel? =
        panels.firstOrNull { it.startEpochMillis <= nowMillis && nowMillis < it.endEpochMillis }

    /** PRD §11: the panel covering [nowMillis] (pinned or auto) — what to notify as the current task. */
    fun currentPanel(state: SchedulerState, nowMillis: Long): TaskPanel? =
        panelAt(state.panels, nowMillis)

    // ----- §13 Schedule Unit ------------------------------------------------------------------

    /** PRD §13: total spanning time (minutes) of a schedule unit's entries. */
    fun scheduleUnitSumMinutes(entries: List<ScheduleUnitEntry>): Int =
        entries.sumOf { it.spanMinutes }

    /**
     * PRD §13 "Save button is not clickable" rule: the edit window may be saved only when the sum of
     * the schedule unit's spanning times does **not exceed** the task's minimum time. An empty unit
     * (the user cleared every step) is always saveable — it simply removes the schedule unit.
     */
    fun canSaveScheduleUnit(entries: List<ScheduleUnitEntry>, minimumMinutes: Int): Boolean =
        scheduleUnitSumMinutes(entries) <= minimumMinutes

    /**
     * PRD §13 Notification: the deadline of each schedule unit element, as `(title, deadlineEpochMillis)`
     * pairs. Walking the entries in order, each deadline is [startMillis] plus the running sum of this
     * entry's span and every preceding one (so the last entry's deadline is the task's end if the spans
     * fill the slot). Empty when the task has no schedule unit.
     */
    fun scheduleUnitDeadlines(
        entries: List<ScheduleUnitEntry>,
        startMillis: Long,
    ): List<Pair<String, Long>> {
        var cursor = startMillis
        return entries.map { entry ->
            cursor += entry.spanMinutes.toLong() * 60_000L
            entry.title to cursor
        }
    }

    /**
     * PRD §11/§13 Notification body for "the task to do now". Names the [taskId] (its title) and, when
     * the task carries a schedule unit (PRD §13), appends each element's deadline computed from
     * [startMillis] via [formatDeadline]. Returns null when the task is missing or blank-titled (nothing
     * worth notifying about). [formatDeadline] turns an epoch-millis deadline into a human label.
     */
    fun taskSwitchNotificationMessage(
        state: SchedulerState,
        taskId: TaskId,
        startMillis: Long,
        formatDeadline: (Long) -> String,
    ): String? {
        val title = state.tasks[taskId]?.title?.takeIf { it.isNotBlank() } ?: return null
        val unit = state.tasks[taskId]?.scheduleUnit.orEmpty()
        if (unit.isEmpty()) return title
        val lines =
            scheduleUnitDeadlines(unit, startMillis).joinToString("\n") { (stepTitle, deadline) ->
                "• $stepTitle — ${formatDeadline(deadline)}"
            }
        return "$title\n$lines"
    }

    /**
     * PRD §9 "the first point in time there is no scheduled task": walking forward from [nowMillis]
     * over the contiguous chain of [panels] that cover it, the first instant left uncovered. With only
     * the kept (fixed) panels this is where the auto fill must resume — past a pinned/chore panel that
     * currently covers `now`.
     */
    fun firstFreeMoment(panels: List<TaskPanel>, nowMillis: Long): Long {
        var cursor = nowMillis
        while (true) {
            val covering = panelAt(panels, cursor) ?: break
            if (covering.endEpochMillis <= cursor) break // guard against a zero/negative-length panel
            cursor = covering.endEpochMillis
        }
        return cursor
    }

    /**
     * PRD §9 Scheduling: regenerate the auto schedule with an Earliest-Deadline-First simulation. Every
     * **non-pinned** panel in the window `[now, now + 168h]` is cut and replaced; the only panels kept
     * are the **fixed** ones (pinned + chore, [isSchedulerFixed]) and any panel entirely **outside** the
     * window — already past (`end ≤ now`) or starting beyond the horizon (`start > now + 168h`). Cutting
     * the in-progress non-pinned panel too means the current task is re-derived from `now` each run, so a
     * task added to the tree always reschedules immediately (no kept block can swallow the window); the
     * fill is deterministic, so a refill at the same instant reproduces the same panels (the §9 no-op
     * short-circuit still fires) and re-picks the same current task (notification continuity, §11).
     *
     * EDF: each schedulable leaf ([schedulableLeaves]) releases an `mᵢ`-long job every `Tᵢ = mᵢ / pᵢ`
     * ([edfPeriodMillis]), so its long-run utilization `mᵢ/Tᵢ = pᵢ` is its priority share. Walking a cursor
     * from [firstFreeMoment] to the horizon, the task with the earliest current deadline runs next (ties →
     * higher priority → alphabetical); its deadline then advances by `Tᵢ`. Because the leaves' priorities
     * sum to 1, total utilization is 1 and the window packs with no idle gaps. A chunk is shortened so it
     * never overlaps the next fixed panel (PRD §10); the cursor skips over a fixed panel it lands inside.
     *
     * PRD §9 "the deficits/excess from the already-placed panels before `now` … influence the chosen
     * result" (example 1): each leaf's **first** deadline is seeded from its *committed* service before
     * `now` — its records and the kept fixed/past panels ([pastPeriodsForTask] over [working], so the
     * soon-to-be-cut auto panels never feed back and flip-flop the pick, §11 continuity). A task served
     * right up to `now` is seeded one full period out (`lastEnd + Tᵢ`); one never served (or last served
     * long ago) is due at `start`. The seed is clamped to never start *before* `start`, so an over-served
     * task only changes who runs *first* — it never buys the others catch-up time. Hence A served heavily
     * before `now` plus a fresh B (both 50 %, 45 min) yields B, A, B, A: the excess is not balanced, it
     * only sets the starting phase.
     *
     * PRD §9 merge: two consecutive auto panels of the same task are fused into one block
     * ([mergeSameTaskPanels]), so a sole task shows as a single continuous panel. Auto panels get
     * deterministic `auto/{i}` ids (regenerated each run, skipping ids held by kept panels).
     *
     * PRD §15 Side tasks: [SchedulerState.sideTasks] are materialized as fixed obstacle panels
     * ([sideTaskPanels]) and woven into the window. They behave like a pinned obstacle (the fill flows
     * around them) with one difference: when a task chunk meets a side task, the chunk is **split** around
     * it and the task **resumes after** with its remaining work, so its minimum is never charged for the
     * side-task time (a 45-min task crossing a 5-min side task occupies a 50-min wall-clock span). A pinned
     * obstacle, by contrast, truncates the chunk (the minimum is cut). Side panels regenerate every fill.
     */
    fun fillSchedule(
        state: SchedulerState,
        nowMillis: Long,
    ): List<TaskPanel> {
        val horizon = nowMillis + SCHEDULE_HORIZON_MILLIS
        // Cut every non-pinned panel in [now, horizon]; keep fixed (pinned) panels, reminder tags (PRD
        // §14 — kept on the calendar though not obstacles, see isSchedulerFixed), and any panel entirely
        // outside the window — already past (end ≤ now) or beyond the horizon (start > horizon). Side-task
        // panels (PRD §15) are always cut and regenerated fresh below, so they never accumulate.
        val kept = state.panels.filter {
            !it.sideTask &&
                (isSchedulerFixed(it) || it.chore || it.endEpochMillis <= nowMillis || it.startEpochMillis > horizon)
        }
        val leaves = schedulableLeaves(state)
        // PRD §15: side tasks materialize regardless of whether there are leaf tasks to fill around them.
        // Each one places its next occurrence at its due time (or the now-line when overdue), with the
        // 5-min↔15-min merge applied (see [sideTaskPanels]).
        val sidePanels = sideTaskPanels(state.sideTasks, nowMillis)
        if (leaves.isEmpty()) return (kept + sidePanels).sortedBy { it.startEpochMillis }

        val priorities = absoluteTaskPriorities(state)
        val keptIds = kept.mapTo(HashSet()) { it.id }
        var working = state.copy(panels = kept)
        val start = firstFreeMoment(kept, nowMillis)

        // PRD §15: the side-task occupied regions (merged, since side tasks can coincide) the regular fill
        // must skip over. The regular task resumes after each region without its minimum being charged.
        val sideRegions = mergeOccupied(sidePanels.map { TaskTimeRange(it.startEpochMillis, it.endEpochMillis) })
        fun sideRegionCovering(t: Long): TaskTimeRange? =
            sideRegions.firstOrNull { it.startEpochMillis <= t && t < it.endEpochMillis }
        fun nextSideRegionStart(t: Long): Long? =
            sideRegions.asSequence().map { it.startEpochMillis }.filter { it > t }.minOrNull()

        // EDF state: each leaf's recurrence period Tᵢ and its current deadline (Double millis so an
        // infinite-period zero-priority task is representable and always sorts last).
        val period = HashMap<TaskId, Double>(leaves.size)
        val deadline = HashMap<TaskId, Double>(leaves.size)
        for (t in leaves) {
            val p = edfPeriodMillis(state.tasks[t]?.minimumMinutes ?: 0, priorities[t] ?: 0.0)
            period[t] = p
            // PRD §9 example 1: seed the first deadline from committed pre-now service (records + kept
            // fixed/past panels in `working`, not the auto panels being regenerated). A task served up to
            // now is one period out (`lastEnd + p`); a never-served task defaults to `start - p` so it is
            // due at `start`. Clamping at `start` keeps an over-served task from forcing catch-up for the
            // others — the excess only sets who goes first, it is not balanced away.
            deadline[t] =
                if (p.isInfinite()) {
                    p
                } else {
                    val lastEnd =
                        pastPeriodsForTask(working, t, nowMillis).maxOfOrNull { it.endEpochMillis }?.toDouble()
                            ?: (start.toDouble() - p)
                    maxOf(start.toDouble(), lastEnd + p)
                }
        }
        val tieBreak =
            compareByDescending<TaskId> { priorities[it] ?: 0.0 }.thenBy { state.tasks[it]?.title.orEmpty() }

        val generated = mutableListOf<TaskPanel>()
        var cursor = start
        var index = 0
        var idCounter = 0
        fun nextAutoId(): String {
            while ("auto/$idCounter" in keptIds) idCounter++
            return "auto/${idCounter++}"
        }
        // PRD §15: the task whose minimum chunk is mid-placement, split across a side task, with the work
        // it still owes. Carried across iterations so it resumes after the side region rather than the EDF
        // re-picking and its deadline only advances once the whole chunk (or a pinned-truncated one) lands.
        var pending: Pair<TaskId, Long>? = null
        fun advance(t: TaskId) {
            val p = period[t] ?: Double.POSITIVE_INFINITY
            if (!p.isInfinite()) deadline[t] = (deadline[t] ?: start.toDouble()) + p
        }
        // Bound the loop defensively: with positive spans this can't run away, but a degenerate zero
        // span (only possible if minima are clamped to 0) would otherwise spin.
        while (cursor < horizon && index < MAX_SCHEDULE_PANELS) {
            val pinnedCovering = kept.firstOrNull {
                isSchedulerFixed(it) && it.startEpochMillis <= cursor && cursor < it.endEpochMillis
            }
            if (pinnedCovering != null) {
                // A pinned obstacle cuts the minimum (PRD §9/§10): abandon any pending chunk's remainder.
                cursor = pinnedCovering.endEpochMillis
                pending = null
                continue
            }
            // PRD §15: skip a side-task region (it never charges the surrounding task) — the pending chunk
            // resumes on the far side, its remaining work intact.
            val sideCovering = sideRegionCovering(cursor)
            if (sideCovering != null) {
                cursor = sideCovering.endEpochMillis
                continue
            }

            // Continue the interrupted task, else Earliest Deadline First (ties → priority → title).
            val taskId =
                pending?.first
                    ?: leaves.minWithOrNull(
                        compareBy<TaskId> { deadline[it] ?: Double.POSITIVE_INFINITY }.then(tieBreak),
                    ) ?: break
            val task = working.tasks[taskId] ?: break
            val need = pending?.second ?: (scheduledSpanMinutes(working, taskId, cursor) * MILLIS_PER_MINUTE)
            if (need <= 0) break
            // This piece runs until the chunk is satisfied, the next side region, the next pinned panel, or
            // the horizon — whichever comes first.
            val nextSide = nextSideRegionStart(cursor) ?: Long.MAX_VALUE
            val nextPinned = nextPinnedStartAfter(kept, cursor) ?: Long.MAX_VALUE
            val boundary = minOf(nextSide, nextPinned, horizon)
            val pieceEnd = minOf(cursor + need, boundary)
            if (pieceEnd <= cursor) break
            generated += TaskPanel(
                id = nextAutoId(),
                taskId = taskId,
                title = task.title,
                startEpochMillis = cursor,
                endEpochMillis = pieceEnd,
                pinned = false,
                auto = true,
            )
            working = working.copy(panels = kept + generated)
            val placed = pieceEnd - cursor
            cursor = pieceEnd
            when {
                placed >= need -> { advance(taskId); pending = null } // chunk complete → next EDF release
                boundary == nextPinned && nextPinned <= nextSide -> {
                    // Truncated by a pinned obstacle: the minimum IS cut here (PRD §9/§10), chunk ends.
                    advance(taskId); pending = null
                }
                else -> pending = taskId to (need - placed) // interrupted by a side task → resume after it
            }
            index++
        }
        // PRD §9: two consecutive auto panels of the same task merge into one block. Side-task panels (PRD
        // §15) are added as-is (they split the run, so adjacent same-task pieces don't touch and stay apart).
        return (kept + sidePanels + mergeSameTaskPanels(generated)).sortedBy { it.startEpochMillis }
    }

    /** Safety cap on auto panels (pre-merge chunks) one fill can lay down (≈ 168h / a 1-minute minimum). */
    private const val MAX_SCHEDULE_PANELS = 20_000

    // ----- PRD §14 Reminders scheduler --------------------------------------------------------

    /** PRD §14: reminder tags are generated this far ahead of the anchor day (a fixed 4-week horizon). */
    const val CHORE_HORIZON_DAYS: Int = 28

    private const val MILLIS_PER_DAY: Long = 24L * 60 * 60 * 1000

    /**
     * PRD §14: the day offsets (from the anchor, day 0 = today) on which a chore recurring every
     * [spanDays] lands, out to [horizonDays].
     *
     * - **`spanDays ≤ 0`** (blank / no recurrence): a one-off — just `[0]` (today).
     * - **`0 < spanDays < 1`**: a daily reminder — every day `[0, 1, …, horizonDays]`.
     * - **`spanDays ≥ 1`**: a day cadence. The accumulated counter starts at today (offset 0 is always
     *   included); each subsequent iteration adds [spanDays] and the closest integer to the running sum is
     *   the chosen day, so a fractional cadence lands an exact `numerator` occurrences per `denominator`-day
     *   window without drifting (e.g. `31/21` ≈ 1.476 → 0, 1, 3, 4, 6, … = 21 days out of every 31).
     */
    fun choreOccurrenceDayOffsets(spanDays: Double, horizonDays: Int = CHORE_HORIZON_DAYS): List<Int> {
        if (spanDays <= 0.0) return listOf(0)
        if (spanDays < 1.0) return (0..horizonDays).toList()
        val offsets = mutableListOf(0)
        var k = 1
        while (k <= horizonDays + 1) {
            val day = (k * spanDays).roundToInt()
            if (day > horizonDays) break
            if (day != offsets.last()) offsets.add(day)
            k++
        }
        return offsets
    }

    /**
     * PRD §14: evaluate the reminders "Days" field, which accepts an arithmetic **formula** (e.g. `31/21`,
     * `1/2`, `7*2`) as well as a plain number — supporting `+ - * /`, parentheses, unary signs and decimals.
     * Returns the numeric result (the recurrence in days, see [choreOccurrenceDayOffsets]), or null when the
     * text is blank / malformed / not finite (e.g. a division by zero) — which the caller treats as 0 (a
     * one-off). Whitespace is ignored; `,` should be normalised to `.` by the caller before parsing.
     */
    fun evaluateDayFormula(text: String): Double? = DayFormulaParser(text).parse()

    /** Recursive-descent evaluator for [evaluateDayFormula]: `expr = term (+|-) term`, `term = factor (*|/) factor`. */
    private class DayFormulaParser(private val s: String) {
        private var pos = 0

        fun parse(): Double? {
            val value = expr() ?: return null
            skipWs()
            if (pos != s.length) return null // trailing garbage → invalid
            return value.takeIf { it.isFinite() }
        }

        private fun skipWs() { while (pos < s.length && s[pos].isWhitespace()) pos++ }

        private fun expr(): Double? {
            var value = term() ?: return null
            while (true) {
                skipWs()
                when (s.getOrNull(pos)) {
                    '+' -> { pos++; value += term() ?: return null }
                    '-' -> { pos++; value -= term() ?: return null }
                    else -> return value
                }
            }
        }

        private fun term(): Double? {
            var value = factor() ?: return null
            while (true) {
                skipWs()
                when (s.getOrNull(pos)) {
                    '*' -> { pos++; value *= factor() ?: return null }
                    '/' -> { pos++; value /= factor() ?: return null }
                    else -> return value
                }
            }
        }

        private fun factor(): Double? {
            skipWs()
            when (s.getOrNull(pos)) {
                '+' -> { pos++; return factor() }
                '-' -> { pos++; return factor()?.let { -it } }
                '(' -> {
                    pos++
                    val value = expr() ?: return null
                    skipWs()
                    if (s.getOrNull(pos) != ')') return null
                    pos++
                    return value
                }
                else -> return number()
            }
        }

        private fun number(): Double? {
            skipWs()
            val start = pos
            while (pos < s.length && (s[pos].isDigit() || s[pos] == '.')) pos++
            return if (pos == start) null else s.substring(start, pos).toDoubleOrNull()
        }
    }

    /** PRD §14: a reminder is a calendar panel ([TaskPanel.chore]) — a zero-duration, checkable tag. */
    fun isReminder(panel: TaskPanel): Boolean = panel.chore

    /**
     * PRD §14 reminder scheduler: turn [chores] into **zero-duration calendar tags** anchored at
     * [todayStartMillis] (local midnight of "today", supplied by the caller which knows the time zone).
     * Each reminder is placed at its [ChoreEntry.timeOfDayMinutes] on every [choreOccurrenceDayOffsets] day
     * out to [CHORE_HORIZON_DAYS]. A reminder whose time-of-day is **not defined** (negative) is placed at
     * the **current time** instead — the time-of-day of [nowMillis] (defaults to midnight when omitted).
     * Only **blank-titled** reminders are skipped; a reminder with no recurrence (`spanDays ≤ 0`) is a
     * **one-off** placed today only, so entering just a title creates a single reminder; a sub-day cadence
     * (`0 < spanDays < 1`) recurs every day. Tags carry [TaskPanel.chore] = true, a null taskId, and
     * `start == end` (no spanning time), with deterministic `chore/{index}/{offset}` ids so a steady
     * regeneration reproduces them. They start un-[TaskPanel.checked]. Overlapping tags keep the default
     * layout weight, so the calendar splits their shared width evenly (PRD §14).
     */
    fun choreScheduledPanels(
        chores: List<ChoreEntry>,
        todayStartMillis: Long,
        horizonDays: Int = CHORE_HORIZON_DAYS,
        nowMillis: Long = todayStartMillis,
    ): List<TaskPanel> {
        val currentTimeOfDayMinutes =
            ((nowMillis - todayStartMillis) / MILLIS_PER_MINUTE).toInt().coerceIn(0, 24 * 60 - 1)
        val result = mutableListOf<TaskPanel>()
        chores.forEachIndexed { index, chore ->
            if (chore.title.isBlank()) return@forEachIndexed
            // PRD §14: "the defined time in the day, or the current time if not defined in the field".
            val minutes = if (chore.timeOfDayMinutes < 0) currentTimeOfDayMinutes else chore.timeOfDayMinutes
            val timeOfDay = minutes.coerceIn(0, 24 * 60 - 1) * MILLIS_PER_MINUTE
            for (offset in choreOccurrenceDayOffsets(chore.spanDays, horizonDays)) {
                val start = todayStartMillis + offset * MILLIS_PER_DAY + timeOfDay
                result.add(
                    TaskPanel(
                        id = "chore/$index/$offset",
                        taskId = null,
                        title = chore.title,
                        startEpochMillis = start,
                        endEpochMillis = start, // zero duration: a reminder is a tag, not a panel.
                        pinned = false,
                        auto = false,
                        chore = true,
                    ),
                )
            }
        }
        return result
    }

    /**
     * PRD §14 "the calendar updates each time the reminders manager changes": rebuild the reminder tags in
     * [panels] from [chores] (anchored at [todayStartMillis]), leaving all non-reminder panels untouched.
     * A reminder's **checked state survives** the regeneration (mirroring the old chore pin behaviour): a
     * freshly generated tag whose deterministic id matches one the user had already checked stays checked.
     */
    fun regenerateChorePanels(
        panels: List<TaskPanel>,
        chores: List<ChoreEntry>,
        todayStartMillis: Long,
        horizonDays: Int = CHORE_HORIZON_DAYS,
        nowMillis: Long = todayStartMillis,
    ): List<TaskPanel> {
        val checkedIds = panels.asSequence().filter { it.chore && it.checked }.mapTo(HashSet()) { it.id }
        val nonChore = panels.filter { !it.chore }
        val generated =
            choreScheduledPanels(chores, todayStartMillis, horizonDays, nowMillis)
                .map { if (it.id in checkedIds) it.copy(checked = true) else it }
        return nonChore + generated
    }

    /**
     * PRD §14 "accumulation when missed": the reminder tags that are *overdue* at [nowMillis] — reminders
     * ([TaskPanel.chore]) whose scheduled time has passed (`start ≤ now`) and that the user has not yet
     * checked off. These leave their original slot and accumulate on the calendar's now-line; a checked
     * reminder is done and drops out. Returned in scheduled-time order (oldest first) so the now-line
     * stack reads chronologically.
     */
    fun overdueReminders(panels: List<TaskPanel>, nowMillis: Long): List<TaskPanel> =
        panels.filter { it.chore && !it.checked && it.startEpochMillis <= nowMillis }
            .sortedBy { it.startEpochMillis }

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
        val populated = list.cellIds.filter { isPopulatedCell(state, it) }
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

    /**
     * PRD §8 calendar edit window — the same two-menu structure as the tree's Change Task menu, but
     * without a cell: there is no sibling/ancestor list to forbid, so it offers "New task" (first
     * row) plus every existing user task whose title exactly matches [draftText]. [excludeTaskId] is
     * the task already represented by the current draft/selection. This lets the calendar window
     * create a task (taskId left null) or reuse an existing one, exactly like Edit Mode in the tree.
     */
    fun calendarTaskMenuEntries(
        state: SchedulerState,
        draftText: String,
        excludeTaskId: TaskId? = null,
    ): List<ChangeTaskMenuEntry> {
        // PRD §8 calendar edit window: only leaf tasks (no child tasks) are offered — the calendar
        // schedules leaves, so a parent task is never a valid panel target.
        val matching = matchingUserTaskIds(state, draftText, excludeTaskId).filter { isLeafTask(state, it) }
        return buildList {
            add(ChangeTaskMenuEntry(taskId = null, label = "New task"))
            for (taskId in matching) {
                add(ChangeTaskMenuEntry(taskId = taskId, label = changeTaskMenuLabel(state, taskId)))
            }
        }
    }

    /**
     * PRD §8 calendar edit window default selection: unlike the tree (where "New task" is the default),
     * the calendar pre-selects the **first actual task** of the menu — so a panel reuses an existing
     * leaf task by default and only creates a new one when the user explicitly picks the "New task" row.
     * Returns that task's id, or null when the menu offers no real task (only "New task").
     */
    fun calendarDefaultMenuTaskId(entries: List<ChangeTaskMenuEntry>): TaskId? =
        entries.firstOrNull { it.taskId != null }?.taskId

    /**
     * PRD §8 calendar edit window: title suggestions restricted to titles that have at least one leaf
     * task (a parent task is never a valid panel target). Same ordering as [titleSuggestions].
     */
    fun calendarTitleSuggestions(state: SchedulerState, input: String): List<String> =
        titleSuggestions(state, input).filter { title ->
            state.titleToTaskIds[title].orEmpty().any { isLeafTask(state, it) }
        }

    /** PRD §8 calendar edit window: the leaf task to assign when a title suggestion is chosen, if any. */
    fun calendarTaskIdForTitle(state: SchedulerState, title: String): TaskId? =
        state.titleToTaskIds[title].orEmpty().firstOrNull { isLeafTask(state, it) }

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
        // PRD §4: a childless task that loses all its cell pointers is purged *unless* it has a task
        // record (§8) — such tasks linger only to keep showing their recorded periods in the calendar.
        // A task referenced by a calendar panel is also kept, so a scheduled task deleted from the tree
        // survives until the next refresh cuts and records its in-progress period (§9).
        val referenced =
            state.cells.values.mapNotNull { it.taskId }.toSet() +
                setOf(WellKnownIds.ROOT_TASK, WellKnownIds.MAIN_TASK) +
                state.tasks.filterValues { it.record.isNotEmpty() }.keys +
                state.panels.mapNotNull { it.taskId }
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

    // ----- PRD §4 tree copy / paste serialization --------------------------------------------

    /**
     * A copied cell: its [title], the (populated) subtree beneath it, plus the PRD §4 priority-weight
     * table values needed to reproduce it — [rowWeights] is this cell's per-column value row (aligned to
     * its parent sub-list's columns) and [childHeader] is the weight-column header of the sub-list it
     * parents. [minMinutes] is the PRD §10 minimum time of this node's task (null when the clipboard text
     * carried no min-time appendix entry for it, e.g. a plain title tree, so paste keeps the default).
     */
    data class CopiedNode(
        val title: String,
        val children: List<CopiedNode>,
        val rowWeights: List<Double> = listOf(1.0),
        val childHeader: List<Double> = listOf(1.0),
        val minMinutes: Int? = null,
    )

    /** PRD §4 default priority-weight row/column header — omitted from the serialized text. */
    private val DEFAULT_WEIGHTS: List<Double> = listOf(1.0)

    /**
     * PRD §4 separator between the tree section and the trailing min-time appendix. A lone form-feed
     * line: titles escape `\f` (see [escapeTitle]) so a real title can never be mistaken for it.
     */
    const val COPY_SECTION_SEPARATOR: String = "\u000C"

    /** True when the cell points at a task with a non-blank title (a real, copyable cell). */
    private fun isPopulated(state: SchedulerState, cellId: CellId): Boolean {
        val taskId = state.cells[cellId]?.taskId ?: return false
        return state.tasks[taskId]?.title?.isNotBlank() == true
    }

    /** Build the copied subtree rooted at [cellId] from the task's shared child list (populated cells). */
    private fun copiedSubtree(state: SchedulerState, cellId: CellId): CopiedNode {
        val cell = state.cells[cellId]
        val taskId = cell?.taskId
        val task = taskId?.let { state.tasks[it] }
        val title = task?.title.orEmpty()
        val rowWeights = cell?.priorityWeights ?: DEFAULT_WEIGHTS
        val childList = task?.childListId?.let { state.lists[it] }
        val childHeader = childList?.weightColumns ?: DEFAULT_WEIGHTS
        val children =
            childList?.cellIds.orEmpty()
                .filter { isPopulated(state, it) }
                .map { copiedSubtree(state, it) }
        return CopiedNode(title, children, rowWeights, childHeader, task?.minimumMinutes)
    }

    private fun formatWeights(weights: List<Double>): String = weights.joinToString(",") { it.toString() }

    /**
     * PRD §4 Copy: the selected cells' subtrees serialized to the app's tab-indented text. Each line is
     * `<depth tabs><escaped title>` optionally followed by tab-separated fields carrying the priority
     * weight table values — `w=<csv>` for the cell's own weight row and `h=<csv>` for the header of the
     * sub-list it parents (both omitted when they are the default single column of 1). Then a
     * [COPY_SECTION_SEPARATOR] line and, at the end, the minimum time of each distinct task in the copied
     * tree as `<escaped title>\t<minutes>` lines (PRD §4). Uses the consecutive selection block when there
     * is one, otherwise the main selection. Empty when nothing populated is selected.
     */
    fun copyTreeText(state: SchedulerState, selection: SchedulerSelection): String {
        val roots =
            orderedActiveSelectionInList(state, selection)?.second
                ?: selection.main?.let { listOf(it) }.orEmpty()
        val nodes = roots.filter { isPopulated(state, it) }.map { copiedSubtree(state, it) }
        if (nodes.isEmpty()) return ""
        val sb = StringBuilder()
        fun render(ns: List<CopiedNode>, depth: Int) {
            for (n in ns) {
                repeat(depth) { sb.append('\t') }
                sb.append(escapeTitle(n.title))
                if (n.rowWeights != DEFAULT_WEIGHTS) sb.append('\t').append("w=").append(formatWeights(n.rowWeights))
                if (n.children.isNotEmpty() && n.childHeader != DEFAULT_WEIGHTS) {
                    sb.append('\t').append("h=").append(formatWeights(n.childHeader))
                }
                sb.append('\n')
                render(n.children, depth + 1)
            }
        }
        render(nodes, 0)
        // PRD §4 trailing appendix: the minimum time of each distinct task (first-appearance order).
        val minByTitle = LinkedHashMap<String, Int>()
        fun collectMins(ns: List<CopiedNode>) {
            for (n in ns) {
                if (n.title !in minByTitle) minByTitle[n.title] = n.minMinutes ?: DEFAULT_MINIMUM_MINUTES
                collectMins(n.children)
            }
        }
        collectMins(nodes)
        sb.append(COPY_SECTION_SEPARATOR).append('\n')
        sb.append(minByTitle.entries.joinToString("\n") { "${escapeTitle(it.key)}\t${it.value}" })
        return sb.toString()
    }

    private fun parseWeights(csv: String): List<Double>? {
        if (csv.isEmpty()) return null
        val result = ArrayList<Double>()
        for (part in csv.split(',')) result.add(part.toDoubleOrNull() ?: return null)
        return result
    }

    /**
     * PRD §4 Paste: parse the app's serialized text (see [copyTreeText]) into a forest carrying the
     * priority weight values and per-task minimum times, or null when [text] is not in that format (an
     * unknown line field, an unparseable weight/min-time, an indentation jump of more than one level, or
     * nothing populated). The strictness is what makes paste a no-op for arbitrary clipboard text. A plain
     * tab-indented title tree (no weight fields, no appendix) still parses — weights default and min-times
     * stay null so paste leaves them at their defaults.
     */
    fun parseTreeText(text: String): List<CopiedNode>? {
        if (text.isBlank()) return null
        val allLines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val sepIndex = allLines.indexOf(COPY_SECTION_SEPARATOR)
        val treeLines = if (sepIndex >= 0) allLines.subList(0, sepIndex) else allLines
        val appendixLines = if (sepIndex >= 0) allLines.subList(sepIndex + 1, allLines.size) else emptyList()

        // Appendix: `<escaped title>\t<minutes>` per distinct task. A malformed line → not our format.
        val minByTitle = HashMap<String, Int>()
        for (line in appendixLines) {
            if (line.isBlank()) continue
            val tab = line.indexOf('\t')
            if (tab < 0) return null
            val minutes = line.substring(tab + 1).toIntOrNull() ?: return null
            minByTitle[unescapeTitle(line.substring(0, tab))] = minutes
        }

        val entries = ArrayList<MutableCopiedNode>()
        val depths = ArrayList<Int>()
        for (line in treeLines) {
            var depth = 0
            while (depth < line.length && line[depth] == '\t') depth++
            val rest = line.substring(depth)
            if (rest.isBlank()) continue
            val fields = rest.split('\t')
            var rowWeights = DEFAULT_WEIGHTS
            var childHeader = DEFAULT_WEIGHTS
            for (field in fields.drop(1)) {
                when {
                    field.startsWith("w=") -> rowWeights = parseWeights(field.removePrefix("w=")) ?: return null
                    field.startsWith("h=") -> childHeader = parseWeights(field.removePrefix("h=")) ?: return null
                    else -> return null // a real tab in content / unknown field → not our format
                }
            }
            entries.add(MutableCopiedNode(unescapeTitle(fields[0]), rowWeights = rowWeights, childHeader = childHeader))
            depths.add(depth)
        }
        if (entries.isEmpty()) return null

        val roots = ArrayList<MutableCopiedNode>()
        val ancestors = ArrayList<MutableCopiedNode>() // ancestors[d] = current node at depth d
        for (i in entries.indices) {
            val depth = depths[i]
            if (depth > ancestors.size) return null // indentation jumped more than one level
            val node = entries[i]
            if (depth == 0) roots.add(node) else ancestors[depth - 1].children.add(node)
            while (ancestors.size > depth) ancestors.removeAt(ancestors.size - 1)
            ancestors.add(node)
        }
        return roots.map { it.toImmutable(minByTitle) }
    }

    private class MutableCopiedNode(
        val title: String,
        val children: MutableList<MutableCopiedNode> = mutableListOf(),
        val rowWeights: List<Double> = listOf(1.0),
        val childHeader: List<Double> = listOf(1.0),
    ) {
        fun toImmutable(minByTitle: Map<String, Int>): CopiedNode =
            CopiedNode(title, children.map { it.toImmutable(minByTitle) }, rowWeights, childHeader, minByTitle[title])
    }

    private fun escapeTitle(s: String): String =
        buildString {
            for (c in s) when (c) {
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\t' -> append("\\t")
                '\u000C' -> append("\\f")
                else -> append(c)
            }
        }

    private fun unescapeTitle(s: String): String =
        buildString {
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '\\' && i + 1 < s.length) {
                    when (s[i + 1]) {
                        'n' -> append('\n')
                        't' -> append('\t')
                        'f' -> append('\u000C')
                        '\\' -> append('\\')
                        else -> append(s[i + 1])
                    }
                    i += 2
                } else {
                    append(c)
                    i++
                }
            }
        }
}
