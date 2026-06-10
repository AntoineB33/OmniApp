package org.example.project.scheduler.domain

import kotlin.math.roundToInt
import org.example.project.scheduler.model.CellId
import org.example.project.scheduler.model.CellListId
import org.example.project.scheduler.model.ChoreEntry
import org.example.project.scheduler.model.DEFAULT_MINIMUM_MINUTES
import org.example.project.scheduler.model.ScheduleUnitEntry
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

    /**
     * PRD §9 "working time": the past moments where **at least one task was scheduled/done** — the
     * merged union of every task's record plus every panel (auto/user/pinned), clipped to [nowMillis].
     * Used as the denominator of a task's recent share ([taskRecentShare]) so idle gaps don't dilute it.
     */
    fun workingPeriods(state: SchedulerState, nowMillis: Long): List<TaskTimeRange> {
        val recorded = state.tasks.values.asSequence().flatMap { it.record.asSequence() }
        val panels = state.panels.asSequence().map { TaskTimeRange(it.startEpochMillis, it.endEpochMillis) }
        val clipped = (recorded + panels)
            .filter { it.startEpochMillis < nowMillis }
            .map { TaskTimeRange(it.startEpochMillis, minOf(it.endEpochMillis, nowMillis)) }
            .toList()
        return mergeOccupied(clipped)
    }

    /** Total length of [periods] overlapping the half-open window `[from, to)`. */
    private fun spanWithin(periods: List<TaskTimeRange>, from: Long, to: Long): Long {
        if (to <= from) return 0L
        var sum = 0L
        for (p in periods) {
            val lo = maxOf(p.startEpochMillis, from)
            val hi = minOf(p.endEpochMillis, to)
            if (hi > lo) sum += hi - lo
        }
        return sum
    }

    /**
     * PRD §9 task choice fraction `f` for leaf task [taskId] at instant `t` = [nowMillis]: its minimum
     * time `m` as a fraction of the working time over `[t1, t]`.
     *
     * Walking back from `t` over the task's [pastPeriodsForTask], `t1` is the closest past instant at which
     * the task has accumulated exactly its minimum time `m` (PRD §10); `f = m / workingTime(t1, t)`, the
     * share of recent working time that was this task (idle gaps excluded — see [workingPeriods]).
     *
     * PRD §9 "If t1 doesn't exist, f is 0": when the task has been done **less than its minimum** in its
     * whole history (or never), no past window holds a full `m` of it, so `f = 0` — an under-served task.
     * A task is chosen by [nextTask] when this `f` is at most its absolute priority percentage. [working]
     * defaults to a fresh computation but is supplied by [nextTask] so it is computed once per instant.
     */
    fun taskRecentShare(
        state: SchedulerState,
        taskId: TaskId,
        nowMillis: Long,
        working: List<TaskTimeRange> = workingPeriods(state, nowMillis),
    ): Double {
        val minMillis = (state.tasks[takId]?.minimumMinutes ?: 0).toLong() * MILLIS_PER_MINUTE
        if (minMillis <= 0L) return 0.0s
        val periods = pastPeriodsForTask(state, taskId, nowMillis).sortedByDescending { it.endEpochMillis }
        // Walk back accumulating the task's own time until the running total reaches minMillis: that
        // instant is t1. If the task's whole history is shorter than its minimum, t1 doesn't exist.
        var acc = 0L
        var t1 = nowMillis
        for (p in periods) {
            val dur = (p.endEpochMillis - p.startEpochMillis).coerceAtLeast(0L)
            if (acc + dur >= minMillis) {
                t1 = p.endEpochMillis - (minMillis - acc)
                acc = minMillis
                break
            }
            acc += dur
        }
        if (acc < minMillis) return 0.0 // PRD §9: t1 doesn't exist → f = 0
        val workTime = spanWithin(working, t1, nowMillis)
        return if (workTime > 0L) minMillis.toDouble() / workTime.toDouble() else 0.0
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
     * PRD §9 task choice: iterate the *leaf* tasks at [nowMillis] **from highest to lowest absolute
     * priority percentage** (ties alphabetical, matching §8 manual add) and return the first whose recent
     * share `f` ([taskRecentShare]) is its absolute priority percentage `p` **or lower** (`f ≤ p`) — the
     * highest-priority task that is not currently over-served. Empty placeholders, the root/main tasks,
     * tasks with child tasks (PRD §9), tasks no longer in the tree (kept only for their record, PRD §4/§8),
     * and **blank-titled tasks** (a cell emptied to "delete" the task keeps its id and lingers while
     * panels/records still point at it) are excluded. Returns null when there are no real leaf tasks. If
     * every task is over-served (`f > p` for all — a degenerate, near-unreachable state), the highest-
     * priority task is returned so the fill still progresses. (Minimum time, PRD §10, constrains the
     * allocated duration, not which task is chosen.)
     */
    fun nextTask(
        state: SchedulerState,
        nowMillis: Long,
    ): TaskId? {
        val absolute = absoluteTaskPriorities(state)
        val working = workingPeriods(state, nowMillis)
        val candidates =
            state.tasks.keys.filter {
                !isRootTask(it) && !isMainTask(it) && taskHasCells(state, it) && isLeafTask(state, it) &&
                    state.tasks[it]?.title?.isNotBlank() == true
            }
        if (candidates.isEmpty()) return null
        // Highest priority first, ties broken alphabetically (PRD §9 "highest to lowest absolute priority").
        val ordered =
            candidates.sortedWith(
                compareByDescending<TaskId> { absolute[it] ?: 0.0 }
                    .thenBy { state.tasks[it]?.title.orEmpty() },
            )
        // The correct task is the first whose recent share f is its priority p or lower (PRD §9).
        return ordered.firstOrNull { taskRecentShare(state, it, nowMillis, working) <= (absolute[it] ?: 0.0) }
            ?: ordered.first()
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
     * PRD §9/§14: a panel the §9 auto fill must treat as a fixed obstacle — a user-pinned panel or a
     * chore panel (a chore panel "behaves like a pinned task panel in relation to the task scheduler").
     */
    fun isSchedulerFixed(panel: TaskPanel): Boolean = panel.pinned || panel.chore

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
     */
    fun groupSameTaskPanelsForDisplay(panels: List<TaskPanel>): List<List<TaskPanel>> {
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
                panel.startEpochMillis <= frontier
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

    /** PRD §9 Scheduling: the auto fill materializes panels out to this far ahead of `now` (24 hours). */
    const val SCHEDULE_HORIZON_MILLIS: Long = 24L * 60 * 60 * 1000

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
     * the kept panels (pinned + the current in-progress one) this is where the auto fill must resume.
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
     * PRD §9 Scheduling: regenerate the auto schedule. Keeps every **pinned** panel, the single panel
     * currently covering [nowMillis], and every panel entirely **outside** the scheduling window —
     * past (`end ≤ now`) or strictly beyond the horizon (`start > now + 24h`). Scheduling only
     * regenerates the window `[firstFree, now+24h]`, so a non-pinned user panel the user dragged before
     * `now` or added more than 24h out is left untouched. Drops the other (in-window, non-pinned)
     * panels and refills the window from [firstFreeMoment] out past
     * `now + ` [SCHEDULE_HORIZON_MILLIS] with a contiguous chain of auto panels. Each panel's task is
     * chosen by [nextTask] **at that panel's start time** (PRD §9 "task choice"); because each panel
     * just laid down counts as a past period for the next iteration (via [pastPeriodsForTask], clipped
     * to the moving cursor), the chain rotates across tasks by priority. A panel is shortened so it
     * never overlaps the next pinned panel (PRD §10).
     *
     * PRD §9 trailing edge: when `now + 24h` falls inside a pre-existing non-pinned panel that extends
     * past it, that panel's beyond-horizon extension is removed **unless** the new last task is the SAME
     * task — in which case it is preserved by extending the new fill's last panel to that panel's end,
     * keeping the trailing edge unchanged (e.g. honouring a manual stretch). For a DIFFERENT last task
     * the new fill runs its own span past the horizon, replacing the old extension. Auto panels get
     * deterministic `auto/{i}` ids (regenerated each run); pinned/user panels keep their `panel/{n}` ids.
     */
    fun fillSchedule(
        state: SchedulerState,
        nowMillis: Long,
    ): List<TaskPanel> {
        val current = panelAt(state.panels, nowMillis)
        val horizon = nowMillis + SCHEDULE_HORIZON_MILLIS
        // Scheduling regenerates ONLY the window [firstFree, now+24h]. Keep pinned panels, the
        // in-progress one, and any panel entirely OUTSIDE the window — past (end ≤ now) or STRICTLY
        // beyond the horizon (start > now+24h) — so a user panel there (incl. non-pinned) is never
        // wiped (a panel dragged before `now`, or added >24h out, survives). The `> horizon` is strict
        // so a panel starting exactly at the horizon (e.g. a preserved `auto/tail`, below) is handled
        // by the trailing-edge logic, not double-kept here.
        val keptRaw = state.panels.filter {
            isSchedulerFixed(it) || it === current || it.endEpochMillis <= nowMillis || it.startEpochMillis > horizon
        }
        // The kept in-progress auto panel keeps its old `auto/i` id, which the freshly numbered
        // generated panels (also `auto/i`, from 0) would otherwise reuse. The calendar keys its overlap
        // layout by panel id (a panel's `entryId`), so two different-task panels sharing one id render
        // as a single stretched block (the next task drawn over the in-progress one). Normalize the kept
        // current auto panel to a stable `auto/0` and number the generated panels around the kept ids
        // (so a steady-state refill still reproduces identical ids — the no-op `filled == panels` check).
        val kept =
            keptRaw.map { if (it === current && it.auto && !it.pinned) it.copy(id = "auto/0") else it }
        val keptIds = kept.mapTo(HashSet()) { it.id }
        var working = state.copy(panels = kept)
        val generated = mutableListOf<TaskPanel>()
        var cursor = firstFreeMoment(kept, nowMillis)
        var index = 0
        var idCounter = 0
        fun nextAutoId(): String {
            while ("auto/$idCounter" in keptIds) idCounter++
            return "auto/${idCounter++}"
        }
        // Bound the loop defensively: with positive spans this can't run away, but a degenerate zero
        // span (only possible if minima are clamped to 0) would otherwise spin.
        while (cursor < horizon && index < MAX_SCHEDULE_PANELS) {
            val pinnedCovering = kept.firstOrNull {
                isSchedulerFixed(it) && it.startEpochMillis <= cursor && cursor < it.endEpochMillis
            }
            if (pinnedCovering != null) {
                cursor = pinnedCovering.endEpochMillis
                continue
            }
            val taskId = nextTask(working, cursor) ?: break
            val task = working.tasks[taskId] ?: break
            var end = cursor + scheduledSpanMinutes(working, taskId, cursor) * MILLIS_PER_MINUTE
            nextPinnedStartAfter(kept, cursor)?.let { end = minOf(end, it) }
            if (end <= cursor) break
            val panel = TaskPanel(
                id = nextAutoId(),
                taskId = taskId,
                title = task.title,
                startEpochMillis = cursor,
                endEpochMillis = end,
                pinned = false,
                auto = true,
            )
            generated += panel
            // Fold the just-placed panel into the working state so it counts as a past period at the
            // next cursor (PRD §9 task choice rotates by priority).
            working = working.copy(panels = kept + generated)
            cursor = end
            index++
        }

        // PRD §9 trailing edge: if `now+24h` was inside a pre-existing (non-pinned) panel that extends
        // further, its beyond-horizon extension is removed UNLESS the new last panel is the SAME task —
        // then it is preserved by extending the new last panel to keep that trailing edge unchanged
        // (e.g. honouring a manual stretch of the trailing panel). A DIFFERENT last task lets the new
        // fill replace it (its own span runs past the horizon). The straddler is read from the original
        // panels (it is in-window, so it was dropped from `kept` and regenerated).
        val straddler = state.panels.firstOrNull {
            !isSchedulerFixed(it) && it !== current &&
                it.startEpochMillis <= horizon && horizon < it.endEpochMillis
        }
        val lastGen = generated.lastOrNull()
        if (straddler != null && lastGen != null && lastGen.taskId == straddler.taskId) {
            generated[generated.lastIndex] = lastGen.copy(endEpochMillis = straddler.endEpochMillis)
        }
        // NB: auto panels are deliberately NOT run through [mergeSameTaskPanels] here. Each auto panel is
        // a distinct scheduling *session*; the reschedule keeps only the in-progress one (the panel
        // covering `now`) and refills the rest. Fusing a sole task's consecutive sessions into one block
        // would make that block span the whole horizon, so the kept "current" panel would swallow the
        // entire window and a newly added task could never be scheduled (firstFreeMoment → now+24h).
        // Same-task merging is a property of persistent user/pinned panels, applied on commit (PRD §8).
        return (kept + generated).sortedBy { it.startEpochMillis }
    }

    /** Safety cap on the number of auto panels one fill can lay down (≈ 24h / shortest sane span). */
    private const val MAX_SCHEDULE_PANELS = 2_000

    // ----- PRD §14 Chores Manager scheduler ---------------------------------------------------

    /** PRD §14: each chore occurrence is a fixed 5-minute panel. */
    const val CHORE_PANEL_MILLIS: Long = 5L * 60_000L

    /** PRD §14: chore panels are generated this far ahead of the anchor day (a fixed 4-week horizon). */
    const val CHORE_HORIZON_DAYS: Int = 28

    private const val MILLIS_PER_DAY: Long = 24L * 60 * 60 * 1000

    /**
     * PRD §14: the day offsets (from the anchor, day 0 = today) on which a chore recurring every
     * [spanDays] lands, out to [horizonDays]. The accumulated counter starts at today (offset 0 is always
     * included); each subsequent iteration adds [spanDays] and the closest integer to the running sum is
     * the chosen day, so a fractional cadence does not drift (e.g. 2.5 → 0, 2/3, 5, 8, 10, …, ties to
     * even per [roundToInt]). Returns just `[0]` when [spanDays] is not a valid cadence (≤ 1, per §14).
     */
    fun choreOccurrenceDayOffsets(spanDays: Double, horizonDays: Int = CHORE_HORIZON_DAYS): List<Int> {
        val offsets = mutableListOf(0)
        if (spanDays <= 1.0) return offsets
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
     * PRD §14 chore scheduler: turn [chores] into recurring 5-minute calendar panels anchored at
     * [todayStartMillis] (local midnight of "today", supplied by the caller which knows the time zone).
     * Each chore is placed at its [ChoreEntry.timeOfDayMinutes] on every [choreOccurrenceDayOffsets] day
     * out to [CHORE_HORIZON_DAYS]. Blank-titled chores and non-cadences (spanDays ≤ 1) are skipped.
     * Panels carry [TaskPanel.chore] = true and a null taskId, with deterministic `chore/{index}/{offset}`
     * ids so a steady regeneration reproduces them. Overlapping chores keep the default layout weight, so
     * the calendar splits their shared width evenly (PRD §14).
     */
    fun choreScheduledPanels(
        chores: List<ChoreEntry>,
        todayStartMillis: Long,
        horizonDays: Int = CHORE_HORIZON_DAYS,
    ): List<TaskPanel> {
        val result = mutableListOf<TaskPanel>()
        chores.forEachIndexed { index, chore ->
            if (chore.title.isBlank() || chore.spanDays <= 1.0) return@forEachIndexed
            val timeOfDay = chore.timeOfDayMinutes.coerceIn(0, 24 * 60 - 1) * MILLIS_PER_MINUTE
            for (offset in choreOccurrenceDayOffsets(chore.spanDays, horizonDays)) {
                val start = todayStartMillis + offset * MILLIS_PER_DAY + timeOfDay
                result.add(
                    TaskPanel(
                        id = "chore/$index/$offset",
                        taskId = null,
                        title = chore.title,
                        startEpochMillis = start,
                        endEpochMillis = start + CHORE_PANEL_MILLIS,
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
     * PRD §14 "the calendar updates each time the chores manager window changes": rebuild the chore
     * panels in [panels] from [chores] (anchored at [todayStartMillis]), keeping every chore panel the
     * user pinned (the chore pin system: pinned chore panels survive a regeneration) and leaving all
     * non-chore panels untouched. A freshly generated panel whose id collides with a kept pinned one is
     * dropped in favour of the pinned panel.
     */
    fun regenerateChorePanels(
        panels: List<TaskPanel>,
        chores: List<ChoreEntry>,
        todayStartMillis: Long,
        horizonDays: Int = CHORE_HORIZON_DAYS,
    ): List<TaskPanel> {
        val keptPinnedChores = panels.filter { it.chore && it.pinned }
        val keptIds = keptPinnedChores.mapTo(HashSet()) { it.id }
        val nonChore = panels.filter { !it.chore }
        val generated = choreScheduledPanels(chores, todayStartMillis, horizonDays).filter { it.id !in keptIds }
        return nonChore + keptPinnedChores + generated
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
