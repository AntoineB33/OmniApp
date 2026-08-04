package org.example.project.scheduler.domain

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.time.Instant
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import org.example.project.scheduler.model.CellId
import org.example.project.scheduler.model.CellListId
import org.example.project.scheduler.model.ChoreEntry
import org.example.project.scheduler.model.DEFAULT_MINIMUM_MINUTES
import org.example.project.scheduler.model.ScheduleUnitEntry
import org.example.project.scheduler.model.ScreenBreak
import org.example.project.scheduler.model.SleepSchedule
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
     * [taskId] together with every task in its sub-tree, walked **structurally** through the shared child
     * lists (childListId → list cells → their taskId) rather than the denormalized [Task.childTaskIds],
     * which can be stale (see [isLeafTask]). Used by the Change Task filter so the "shared descendant"
     * check reflects the live tree. The `result.add` guard makes it safe against any existing cycles.
     *
     * [excludeCellId] is skipped during the walk: while a cell is in Edit Mode it is tentatively assigned
     * the candidate task, so it momentarily sits inside its own ancestors' sub-trees. Ignoring it keeps
     * the candidate from colliding with itself (its current tentative content must not constrain it).
     */
    private fun structuralSubtreeTaskIds(
        state: SchedulerState,
        taskId: TaskId,
        excludeCellId: CellId? = null,
    ): Set<TaskId> {
        val result = mutableSetOf<TaskId>()
        val stack = ArrayDeque(listOf(taskId))
        while (stack.isNotEmpty()) {
            val id = stack.removeLast()
            if (!result.add(id)) continue
            val childListId = state.tasks[id]?.childListId ?: continue
            val list = state.lists[childListId] ?: continue
            list.cellIds.forEach { cellId ->
                if (cellId != excludeCellId) state.cells[cellId]?.taskId?.let(stack::addLast)
            }
        }
        return result
    }

    /**
     * PRD §4 Filtering: the set of task sub-trees a candidate must not collide with when assigned to
     * [cellId] — the union of the structural sub-trees of all of the cell's non-root ancestors (the
     * "parents set"), with [cellId] itself ignored. Assigning a candidate whose own sub-tree shares any
     * task with this scope would place that shared task twice inside a single non-root sub-tree. Example:
     * with a→c and b→c, editing a cell under b yields scope {b, c}; the candidate a (sub-tree {a, c})
     * collides on c, so b cannot be made a parent of a. Root-level cells have no ancestors, so the same
     * task may freely recur there.
     */
    private fun assignCollisionScope(state: SchedulerState, cellId: CellId): Set<TaskId> =
        ancestorTaskIds(state, cellId).flatMapTo(mutableSetOf()) {
            structuralSubtreeTaskIds(state, it, excludeCellId = cellId)
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

    // The §9 pick is the cyclic proportional-share model ([fillSchedule] / [PlanWalk]); the §8 manual-add
    // pick is [manualAddTaskId]. The EDF-era helpers `edfPeriodMillis` / `nextTask` were deleted with that
    // fill — they scored a static period `T = m / p`, which no longer predicts anything the scheduler does.

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
     * Subtract [regions] from each of [ranges], returning the surviving sub-ranges (sorted per input range,
     * split where a region carves out a middle piece, dropped where a region fully covers it). Pure and used
     * to keep the §15 "Inactivity" bands from overlapping the §17 "Sleep" bands — a sleep window is already
     * labelled Sleep, so the pause underneath it is not also drawn as Inactivity. Zero-length remnants are
     * dropped.
     */
    fun subtractRegions(ranges: List<TaskTimeRange>, regions: List<TaskTimeRange>): List<TaskTimeRange> {
        if (regions.isEmpty()) return ranges.filter { it.endEpochMillis > it.startEpochMillis }
        val cuts = mergeOccupied(regions)
        val result = mutableListOf<TaskTimeRange>()
        for (range in ranges) {
            var cursor = range.startEpochMillis
            val end = range.endEpochMillis
            for (cut in cuts) {
                if (cut.endEpochMillis <= cursor) continue
                if (cut.startEpochMillis >= end) break
                if (cut.startEpochMillis > cursor) result += TaskTimeRange(cursor, cut.startEpochMillis)
                cursor = maxOf(cursor, cut.endEpochMillis)
                if (cursor >= end) break
            }
            if (cursor < end) result += TaskTimeRange(cursor, end)
        }
        return result.filter { it.endEpochMillis > it.startEpochMillis }
    }

    /**
     * The overlap of [ranges] with [regions] — every sub-span present in both. Built from [subtractRegions]:
     * `ranges \ (ranges \ regions)` = `ranges ∩ regions`. Used to find the parts of the scheduled sleep
     * windows that turned out to be no-screen/inactive (PRD §9 "a scheduled sleep window found to be a
     * no-screen period is a past sleep period"). Zero-length remnants are dropped.
     */
    fun intersectRegions(ranges: List<TaskTimeRange>, regions: List<TaskTimeRange>): List<TaskTimeRange> =
        subtractRegions(ranges, subtractRegions(ranges, regions))

    /**
     * PRD §15/§17: carve the display "Sleep" bands where the device/account was demonstrably ACTIVE — the
     * user kept working through a scheduled sleep window, so that slice never happened as sleep and must show
     * as a gap. Each panel in [sleepPanels] is split by [activeRegions] into its surviving asleep sub-pieces
     * (a piece's id is suffixed so the pieces stay distinct); a panel fully covered by activity drops out.
     *
     * This is **display-only**: the scheduler's obstacle math still treats the whole window as sleep (no task
     * is planned into it) — carving reflects what the past turned out to be, it does not re-plan. It is also
     * **conservative**: only KNOWN activity punches a gap, so absent any activity evidence (e.g. every future
     * window, or a past night with no session data) the band stays solid.
     */
    fun carveSleepPanels(sleepPanels: List<TaskPanel>, activeRegions: List<TaskTimeRange>): List<TaskPanel> {
        if (activeRegions.isEmpty()) return sleepPanels
        return sleepPanels.flatMap { panel ->
            subtractRegions(listOf(TaskTimeRange(panel.startEpochMillis, panel.endEpochMillis)), activeRegions)
                .mapIndexed { index, piece ->
                    panel.copy(
                        id = if (index == 0) panel.id else "${panel.id}#$index",
                        startEpochMillis = piece.startEpochMillis,
                        endEpochMillis = piece.endEpochMillis,
                    )
                }
        }
    }

    /**
     * PRD §15: the account-wide pauses for DISPLAY — the [derived] gaps plus the live tail of the pause this
     * device observed locally but no derive has covered yet (derives only run at the sync moments). The tail
     * starts at [inactiveSinceMillis] (the last session finalize — the walk-away instant) and grows with the
     * now-line while the device stays inactive, so the "Inactivity" band renders live behind an advancing
     * now-line (a real walk-away, or the debug pause-leap racing the clock) instead of popping in whole at
     * the next derive. Once the user is back ([activeSinceMillis] non-null) the tail is capped at the
     * reopened session's start — it then holds the just-ended pause until a derive re-covers it, so the band
     * never flickers out between the return and the derive.
     *
     * Display-only and a local PRESUMPTION (this device cannot see a peer's activity between derives — the
     * same bounded staleness the active-session push accepts, ARCHITECTURE.md §8): the next derive replaces
     * it with the account-wide answer, shrinking it over any peer activity. It must never advance the
     * **stored** [ScreenBreak.lastRestMillis] or any persisted/synced state (the forward-only
     * [advanceRestsForward] merge would make a wrong this-device-only advance permanent). The sanctioned
     * derived use is the [screenBreaksForPlacement] placement overlay, which folds the same live gap into
     * screen-break **placement** without touching the stored value.
     */
    fun displayInactivityGaps(
        derived: List<TaskTimeRange>,
        inactiveSinceMillis: Long?,
        activeSinceMillis: Long?,
        nowMillis: Long,
    ): List<TaskTimeRange> {
        val tailStart = inactiveSinceMillis ?: return derived
        val tailEnd = activeSinceMillis ?: nowMillis
        if (tailStart >= tailEnd) return derived
        return mergeOccupied(derived + TaskTimeRange(tailStart, tailEnd))
    }

    /**
     * The start instant that a derived Inactivity/No-screen band should render as `∞` (open-ended into the
     * past), or null when none is. A derived band is open-started when **nothing precedes it** — no activity
     * session, task record, or user-authored/materialized panel begins strictly before it — so the inactivity
     * genuinely extends indefinitely back (the derive window's back edge is an arbitrary display floor, not a
     * real boundary). Only the earliest band can be open-started; [earliestEvidenceMillis] is the minimum
     * start instant across all such evidence (null when the account has none — a freshly-emptied DB, where the
     * whole rendered past is one open-ended inactivity band).
     */
    fun derivedBandsOpenStart(gaps: List<TaskTimeRange>, earliestEvidenceMillis: Long?): Long? {
        val earliest = gaps.minByOrNull { it.startEpochMillis } ?: return null
        return if (earliestEvidenceMillis == null || earliestEvidenceMillis >= earliest.startEpochMillis) {
            earliest.startEpochMillis
        } else {
            null
        }
    }

    /**
     * PRD §15 device-sleep gaps: the epoch-millis instant from which the launch backfill should scan this
     * device's OS sleep/wake log. It resumes from the last "scanned through" [checkpointMillis] so an
     * already-examined stretch of the log isn't re-read on every launch; but it never starts earlier than the
     * floor [nowMillis] − [horizonMillis] (the 3-day backfill horizon). A null checkpoint (first run ever) or
     * one older than the floor (the device was offline a long time) both clamp to the floor — only the last
     * few days can still reseed a pose (poses recur ≤2h apart), so re-reading further back is pointless.
     */
    fun sleepScanFloor(nowMillis: Long, checkpointMillis: Long?, horizonMillis: Long): Long {
        val floor = nowMillis - horizonMillis
        return if (checkpointMillis == null || checkpointMillis < floor) floor else checkpointMillis
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
     * PRD §15 (screen breaks hidden): when the calendar hides screen breaks, two same-task panels separated only
     * by a screen-break gap should read as one continuous block. Set [bridgeGaps] = true and the gap between
     * consecutive same-task/same-pin **auto** panels is treated as touching regardless of its width — purely cosmetic
     * (the panels stay separate in state, so the real spanning time is unchanged). This is correct because in
     * the forward fill a same-task run is only ever broken by a screen-break pause (a different task or a pinned
     * panel sits in the gap as its own block and so breaks the run on its own); deciding it structurally —
     * rather than matching the live screen-break projection, which is recomputed at the current `now` while the
     * gaps come from the last schedule — avoids a flicker as `now` advances (the two were drifting apart). A
     * different/pinned block between the two panels still breaks the run because it is a separate block in the
     * sorted input. With [bridgeGaps] = false this is the original touch-or-overlap grouping.
     */
    fun groupSameTaskPanelsForDisplay(
        panels: List<TaskPanel>,
        bridgeGaps: Boolean = false,
        // A bridged gap is not closed when one of these sleep windows sits *entirely within* it — the
        // panels straddle the night and the always-visible sleep block must cut the run. A screen-break gap
        // *inside* a sleep window (work scheduled through the night) still bridges, so hiding screen breaks
        // doesn't leave a hole in the plan during sleep.
        sleepRegions: List<TaskTimeRange> = emptyList(),
    ): List<List<TaskPanel>> {
        if (panels.isEmpty()) return emptyList()
        val sorted = panels.sortedBy { it.startEpochMillis }
        val groups = mutableListOf<MutableList<TaskPanel>>()
        for (panel in sorted) {
            val group = groups.lastOrNull()
            val head = group?.first()
            val frontier = group?.maxOf { it.endEpochMillis } ?: Long.MIN_VALUE
            // The run is cut only when a whole sleep window sits *inside* the gap — i.e. the two panels
            // straddle the night (work up to bedtime, resuming at wake) with the always-visible "Sleep"
            // band between them. A screen-break gap that falls *within* a sleep window (continuous
            // through-the-night work split by a pose cue) does NOT contain a whole sleep region, so it
            // still bridges — otherwise hiding screen breaks would leave a hole in the plan during sleep.
            val sleepStraddled =
                sleepRegions.any { it.startEpochMillis >= frontier && it.endEpochMillis <= panel.startEpochMillis }
            val mergeable = head != null &&
                panel.taskId != null && head.taskId == panel.taskId &&
                head.pinned == panel.pinned &&
                // Touching/overlapping panels always group. A *gap* is only bridged for the forward
                // auto-fill's screen-break splits, which produce auto panels — so the bridge is restricted to
                // auto panels. User-placed (pinned/manual) entries are deliberate distinct blocks: two
                // pinned same-task panels days apart must NOT fuse into one block spanning the gap.
                (panel.startEpochMillis <= frontier ||
                    (bridgeGaps && !sleepStraddled && head.auto && panel.auto))
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

    /**
     * PRD §9 Scheduling: the FURTHEST ahead of `now` the auto fill will ever materialize panels into
     * `state.panels` (168 hours). This is the **ceiling** of the horizon, not a fixed target — the horizon
     * actually used is [scheduleHorizonEndMillis], which follows the week the calendar is DISPLAYING. A week
     * further out than this ceiling is never materialized into the state at all: it is filled asynchronously
     * for display only (see [fillSchedule]'s `horizonMillis`) and dropped when the user navigates away.
     */
    const val SCHEDULE_HORIZON_MILLIS: Long = 168L * 60 * 60 * 1000

    /**
     * The **ceiling** on how far back [fillSchedule] reads the already-placed past (one week — the same span
     * as the horizon ceiling). The span it actually reads is derived from the plan's own scale: one period
     * for the virtual-clock seed and one influence reach for the field ([SchedulerPlanner.maxReachMillis]),
     * past which an exclusion is felt no more. This ceiling only stops a pathological tree (a leaf with a
     * near-zero share has a huge period) from making the fill cost total history — CLAUDE.md: hot-path
     * derivations scale with the screen, not with the whole record.
     */
    const val SCHEDULE_PAST_LOOKBACK_MILLIS: Long = 168L * 60 * 60 * 1000

    /**
     * PRD §9 Scheduling: the **floor** of the schedule horizon (24 hours) — how far the plan is materialized
     * when nothing further out is being displayed (the calendar window closed, or showing a week that ends
     * sooner than this).
     *
     * The plan is not only a calendar drawing: the engine reads it headlessly for the §11/§13 task-switch
     * notifications, the §15 wind-down cue and the schedule-unit deadlines, so the horizon can never collapse
     * to zero. One day is the smallest span that keeps every one of those correct across a phone left in the
     * background overnight, while costing 1/7 of what the old unconditional 168 h fill cost.
     */
    const val MIN_SCHEDULE_HORIZON_MILLIS: Long = 24L * 60 * 60 * 1000

    /**
     * PRD §9 Scheduling: the instant the auto fill materializes the work plan out to — **the horizon follows
     * what is displayed**, so the app never computes days the user is not looking at.
     *
     * [displayedEndMillis] is the end of the week the calendar window is currently showing (null when no
     * calendar is open), clamped into `[now + MIN_SCHEDULE_HORIZON_MILLIS, now + SCHEDULE_HORIZON_MILLIS]`:
     * - staying on the current week fills only to the end of THAT week (on a Sunday, ~24 h — not 168 h);
     * - the floor keeps the headless engine correct with the calendar closed (see
     *   [MIN_SCHEDULE_HORIZON_MILLIS]);
     * - the ceiling keeps a far week out of the persisted state — a week past it is filled for display only,
     *   off the UI thread, and never retained (`App.kt`'s far-week `LaunchedEffect`).
     */
    fun scheduleHorizonEndMillis(nowMillis: Long, displayedEndMillis: Long?): Long =
        (displayedEndMillis ?: (nowMillis + MIN_SCHEDULE_HORIZON_MILLIS))
            .coerceIn(nowMillis + MIN_SCHEDULE_HORIZON_MILLIS, nowMillis + SCHEDULE_HORIZON_MILLIS)

    /**
     * PRD §9 calculation event #1: how far the materialized schedule may fall short of the horizon in force
     * before the rolling-horizon refill is due (1 hour).
     *
     * The margin is what makes the trigger *satisfiable at all*. A fill materializes out to exactly the
     * horizon, so the coverage it produces can never EXCEED it: without slack, "refill once coverage drops
     * below the horizon" is already true the instant the fill finishes (the clock has moved on by then), and
     * since the refill rewrites `panels` — which is what the engine watches to re-evaluate the trigger — it
     * re-fires forever. See [horizonRefillDueMillis].
     */
    const val HORIZON_REFILL_MARGIN_MILLIS: Long = 60L * 60 * 1000

    /**
     * PRD §9 calculation event #1 (rolling horizon): the instant the auto fill is due to be re-run, i.e. when
     * the schedule materialized in [panels] has less than `(horizonEndMillis - nowMillis) -
     * HORIZON_REFILL_MARGIN_MILLIS` of coverage left ahead of [nowMillis].
     *
     * [horizonEndMillis] is the horizon in force ([scheduleHorizonEndMillis]) — the caller passes the one it
     * fills with, so a schedule that already reaches the end of the displayed week is *not* considered short
     * just because it stops before `now + 168h`. That is the whole point: sitting on the current week must not
     * keep re-filling the days after it.
     *
     * Coverage is measured by [firstFreeMoment] — the end of the contiguous chain of panels covering `now`.
     * Because a fill reaches at most the horizon, this instant is in the FUTURE right after a successful fill
     * (that is the property [org.example.project.scheduler.engine.SchedulerEngine] relies on to stop polling)
     * and in the past whenever the schedule genuinely fell short — a gap opened by an edit, a horizon that has
     * rolled, or the user navigating to a week further out than the fill covers.
     */
    fun horizonRefillDueMillis(
        panels: List<TaskPanel>,
        nowMillis: Long,
        horizonEndMillis: Long = nowMillis + SCHEDULE_HORIZON_MILLIS,
    ): Long =
        firstFreeMoment(panels, nowMillis) - (horizonEndMillis - nowMillis) + HORIZON_REFILL_MARGIN_MILLIS

    /**
     * PRD §15: shortest account-wide pause worth drawing as an "Inactivity" band (90 s). Below this a
     * derived pause is noise, not a real away-from-every-device period — it is finer than the activity
     * heartbeat's own resolution (matches the engine's `DEVICE_SLEEP_THRESHOLD_MILLIS`, the gap length that
     * counts as a real device suspension). The reference case: a freshly-opened account whose first session
     * starts a few seconds after the §17 scheduled wake leaves a sub-minute sliver between the "Sleep" band
     * (ending at the scheduled wake) and the first activity, which would otherwise render as a tiny
     * "20-second pause right after sleep". Applied to the *displayed* bands only — pose seeding still folds in
     * every pause (a 20-s away IS a valid look-away rest).
     */
    const val MIN_INACTIVITY_BAND_MILLIS: Long = 90L * 1000

    // ----- PRD §15 Screen breaks -----------------------------------------------------------------

    /**
     * PRD §15: the stable [ScreenBreak.key]s. The two *break types* the server knows about — `break_config` rows,
     * the value a device publishes in its presence row, and the `break_kind` the cue push carries (migration
     * 20260724000000). The look-away has a key for symmetry only: it is served locally and never cued remotely.
     */
    const val LOOK_AWAY_KEY: String = "look_away"
    const val FIVE_MIN_BREAK_KEY: String = "5min_break"
    const val FIFTEEN_MIN_BREAK_KEY: String = "15min_break"

    /**
     * PRD §15: the hardcoded set of screen breaks — periodic activities placed on the calendar with a real
     * spanning time. The §9 fill weaves them in without letting them reduce the surrounding task's minimum.
     */
    val DEFAULT_SCREEN_BREAKS: List<ScreenBreak> = listOf(
        // The 20-20-20 micro-break: after a ≥20-second pause, the next look-away is due 20 min later.
        ScreenBreak("look 20 feet away", intervalMillis = 20L * 60_000, durationMillis = 20L * 1_000, key = LOOK_AWAY_KEY),
        // The rest poses: after a pause of at least their length, the next one is due an interval later. The
        // 5-min pose merges up into the 15-min pose when their windows would overlap (PRD §15). Their [key]s are
        // the two break types the server configures (`break_config`) and the phone cue names.
        ScreenBreak(
            "take a 5min pose and blink hard",
            intervalMillis = 60L * 60_000,
            durationMillis = 5L * 60_000,
            restBreak = true,
            key = FIVE_MIN_BREAK_KEY,
        ),
        ScreenBreak(
            "take a 15min pose",
            intervalMillis = 2L * 60L * 60_000,
            durationMillis = 15L * 60_000,
            restBreak = true,
            key = FIFTEEN_MIN_BREAK_KEY,
        ),
    )

    /**
     * The screen breaks to actually seed into the running app — [DEFAULT_SCREEN_BREAKS] in production, or with the
     * **5-min break** retimed to [org.example.project.DebugFlags.breakDurationMillisOverride] /
     * [org.example.project.DebugFlags.breakIntervalMillisOverride] under the debug fast-break override (so the
     * pause-cue voice message can be tested on real phones in seconds; see the flags). Only the shorter
     * rest-break pose (the "5-min break") is retimed — the 15-min pose and the 20-20-20 look-away keep their
     * production timings. Kept separate from [DEFAULT_SCREEN_BREAKS] so the scheduler tests keep asserting the
     * exact production timings. A no-op when no override is set, so production callers get the unchanged list.
     */
    fun effectiveDefaultScreenBreaks(): List<ScreenBreak> {
        if (!org.example.project.DebugFlags.fastBreakOverrideActive) return DEFAULT_SCREEN_BREAKS
        // The "5-min break" is the shorter-duration rest-break pose; leave every other screen break untouched.
        val fiveMinPose = DEFAULT_SCREEN_BREAKS.filter { it.restBreak }.minByOrNull { it.durationMillis }
        return DEFAULT_SCREEN_BREAKS.map { side ->
            if (side === fiveMinPose) {
                side.copy(
                    intervalMillis = org.example.project.DebugFlags.breakIntervalMillisOverride ?: side.intervalMillis,
                    durationMillis = org.example.project.DebugFlags.breakDurationMillisOverride ?: side.durationMillis,
                    pauseThresholdMillis = org.example.project.DebugFlags.breakPauseThresholdMillisOverride ?: side.pauseThresholdMillis,
                )
            } else {
                side
            }
        }
    }

    /** A screen break is schedulable when it has a positive interval, a positive duration, and a title. */
    private fun isValidScreenBreak(side: ScreenBreak): Boolean =
        side.intervalMillis > 0 && side.durationMillis > 0 && side.title.isNotBlank()

    // ----- Sleep schedule -----------------------------------------------------------------------

    /** The production-default sleep schedule: wake 07:30, no drift, 8h30 in bed (so bedtime 23:00). */
    val DEFAULT_SLEEP: SleepSchedule = SleepSchedule()

    /** Wind-down before bed in which no task is scheduled (the sleep obstacle extends this much earlier). */
    const val NO_TASK_BEFORE_BED_MILLIS: Long = 60L * MILLIS_PER_MINUTE

    /**
     * The wake time (minutes since local midnight) for the local day [dateEpochDay], after applying the
     * 15-min-per-2-days drift toward [SleepSchedule.goalWakeMinutes]. Returns the plain
     * [SleepSchedule.wakeMinutes] when there is no anchor or it is already at the goal. Pure.
     */
    fun effectiveWakeMinutes(sleep: SleepSchedule, dateEpochDay: Long): Int {
        val anchor = sleep.anchorEpochDay ?: return sleep.wakeMinutes
        if (sleep.goalWakeMinutes == sleep.wakeMinutes) return sleep.wakeMinutes
        val steps = ((dateEpochDay - anchor) / 2).coerceAtLeast(0)
        val maxShift = abs(sleep.goalWakeMinutes - sleep.wakeMinutes).toLong()
        val shift = (steps * 15).coerceIn(0, maxShift).toInt()
        val direction = if (sleep.goalWakeMinutes > sleep.wakeMinutes) 1 else -1
        return sleep.wakeMinutes + direction * shift
    }

    /**
     * The next scheduled wake instant strictly after [nowMillis] — the first local day's
     * `startOfDay + effectiveWakeMinutes` that is still in the future. Used by the Sleep/Work toggle to decide
     * how long a "Sleep" press keeps the account in sleeping mode (the button auto-resets to "Sleep" once this
     * instant passes). Falls back to `now + 24h` when [sleep] is null (no schedule to derive a wake time from).
     */
    fun nextWakeInstantMillis(sleep: SleepSchedule?, nowMillis: Long, timeZone: TimeZone): Long {
        if (sleep == null) return nowMillis + 24L * 60 * MILLIS_PER_MINUTE
        var date = Instant.fromEpochMilliseconds(nowMillis).toLocalDateTime(timeZone).date
        repeat(2) {
            val wakeMillis =
                date.atStartOfDayIn(timeZone).toEpochMilliseconds() +
                    effectiveWakeMinutes(sleep, date.toEpochDays().toLong()).toLong() * MILLIS_PER_MINUTE
            if (wakeMillis > nowMillis) return wakeMillis
            date = date.plus(DatePeriod(days = 1))
        }
        // Both today's and tomorrow's wake already passed relative to `now` (only possible at a day boundary
        // corner); one more day is always in the future.
        return date.atStartOfDayIn(timeZone).toEpochMilliseconds() +
            effectiveWakeMinutes(sleep, date.toEpochDays().toLong()).toLong() * MILLIS_PER_MINUTE
    }

    /**
     * The nightly sleep windows `[wake(day) − duration, wake(day))` (one per local day whose window
     * intersects `[fromMillis, toMillis)`) as obstacle panels (`sleep = true`, null taskId, "Sleep").
     * The wake time per day follows [effectiveWakeMinutes] so the window drifts with the goal. Empty when
     * [sleep] is null or has a non-positive duration.
     */
    fun sleepPanels(
        sleep: SleepSchedule?,
        fromMillis: Long,
        toMillis: Long,
        timeZone: TimeZone,
    ): List<TaskPanel> {
        if (sleep == null || sleep.sleepDurationMinutes <= 0 || toMillis <= fromMillis) return emptyList()
        val durationMillis = sleep.sleepDurationMinutes.toLong() * MILLIS_PER_MINUTE
        val fromDate = Instant.fromEpochMilliseconds(fromMillis).toLocalDateTime(timeZone).date
        val toDate = Instant.fromEpochMilliseconds(toMillis).toLocalDateTime(timeZone).date
        val result = mutableListOf<TaskPanel>()
        // A window is indexed by its wake day; the window starts the previous evening, so begin one day
        // early to catch a window already in progress at [fromMillis].
        var date = fromDate.minus(DatePeriod(days = 1))
        val lastDate = toDate.plus(DatePeriod(days = 1))
        while (date <= lastDate) {
            val epochDay = date.toEpochDays().toLong()
            val wakeMillis =
                date.atStartOfDayIn(timeZone).toEpochMilliseconds() +
                    effectiveWakeMinutes(sleep, epochDay).toLong() * MILLIS_PER_MINUTE
            val sleepStart = wakeMillis - durationMillis
            if (wakeMillis > fromMillis && sleepStart < toMillis) {
                result.add(
                    TaskPanel(
                        id = "sleep/$epochDay",
                        taskId = null,
                        title = "Sleep",
                        startEpochMillis = sleepStart,
                        endEpochMillis = wakeMillis,
                        sleep = true,
                    ),
                )
            }
            date = date.plus(DatePeriod(days = 1))
        }
        return result
    }

    /** The sleep windows from [sleepPanels] as occupied time ranges, for scheduler obstacle math. */
    fun sleepRegions(sleep: SleepSchedule?, fromMillis: Long, toMillis: Long, timeZone: TimeZone): List<TaskTimeRange> =
        sleepPanels(sleep, fromMillis, toMillis, timeZone).map { TaskTimeRange(it.startEpochMillis, it.endEpochMillis) }

    /**
     * PRD §15: the next-occurrence start for [side] at [nowMillis], beginning from its due time
     * `lastRest + interval` (an interval after the last qualifying pause **ended**).
     *
     * How a *past* due time is handled depends on whether the app can tell the pause was taken:
     * - A **rest pose** ([ScreenBreak.restBreak], the 5-/15-min poses) is detectable from device sleep, so an
     *   un-taken one is clamped forward to `now` and waits at the now-line until the user actually rests
     *   (which updates `lastRestMillis`). A never-rested pose (`lastRestMillis == 0`) is due immediately.
     * - The **look-away cadence** (non-rest) is NOT detectable — the app can't know whether the user looked
     *   away — so a fully-elapsed occurrence is *assumed done* and the cadence advances along its fixed grid
     *   (anchored at `lastRest`, stepped by `interval`) to the next slot still live at `now`. It must NOT
     *   slide to the now-line: `lastRestMillis` never updates for it, so clamping to `now` would re-place it
     *   at `now` every tick and make the look-away voice cue repeat indefinitely.
     */
    fun screenBreakNextStart(side: ScreenBreak, nowMillis: Long): Long {
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
     * the look-away never pins to the now-line, so callers gate this on [ScreenBreak.restBreak].
     */
    fun isScreenBreakOverdue(side: ScreenBreak, nowMillis: Long): Boolean =
        side.lastRestMillis + side.intervalMillis <= nowMillis

    /**
     * PRD §15: which **rest poses** ([ScreenBreak.restBreak], the 5-/15-min breaks) the now-line has reached at
     * [nowMillis] and should be **notified** — each mapped to its stable DUE instant `lastRest + interval`.
     *
     * This is the pure mathematical rule behind the rest-pose notification, deliberately a function of only
     * `now` vs each task's due — **no drawn panels, no now-inside-a-window sampling**. Both of those are
     * heartbeat-fragile under an accelerated / time-link-re-anchoring clock: a large sim jump leaps clean over
     * a pose window (a sampled tick never lands inside it) and the drawn panel is not regenerated over the
     * fast-moving now-line at the crossing instant, so the reach is silently skipped. `now >= due` is instead
     * a **level** condition — once the now-line passes `due` it stays true however far the clock jumped, so a
     * reach can never be missed — and `due` is fixed while the break is unserved (it only steps forward when a
     * pause advances `lastRestMillis`), which is why the caller can dedupe on it to fire exactly once per
     * break and stay silent as an overdue pose slides along the now-line ([screenBreakNextStart] = `maxOf(due,
     * now)`).
     *
     * The **5↔15 merge** is honored without panels: when both poses are reached at the now-line the longer
     * pose absorbs the shorter, so a reached pose is omitted if a strictly longer-duration rest pose is also
     * reached (only the longer pose is announced). Invalid rows are skipped.
     *
     * A pose with **no known rest anchor** (`lastRestMillis == 0`) is deliberately NOT announced: its due
     * `0 + interval` is a 1970 sentinel, not an instant derived from the rules, so firing on it would violate
     * the "keys on a fixed rules-derived boundary" contract. This is the pre-seed transient every load starts
     * in — `DEFAULT_SCREEN_BREAKS` seed at `lastRestMillis == 0` and the startup derive anchors them a moment
     * later (a fresh account's whole past is inactivity, so every pose is served at startup ⇒ anchor ≈ now;
     * a returning account's poses anchor to their real last rest). Announcing on the sentinel before that
     * seed lands is the "a freshly-emptied account spoke *take a 15min pose* the instant it opened" bug: the
     * cue sweep sampled the poses in the ~90 ms window between seeding `DEFAULT_SCREEN_BREAKS` and the first
     * `pauses refreshed [local, startup]` derive. Once anchored, the real due (`anchor + interval`) drives
     * the cue normally, so the only effect is suppressing the un-anchored sentinel fire.
     */
    fun reachedRestPoseDueByTitle(screenBreaks: List<ScreenBreak>, nowMillis: Long): Map<String, Long> {
        val reached = screenBreaks
            .filter {
                it.restBreak && isValidScreenBreak(it) &&
                    it.lastRestMillis > 0 && it.lastRestMillis + it.intervalMillis <= nowMillis
            }
        return reached
            .filter { task -> reached.none { it.durationMillis > task.durationMillis } }
            .associate { it.title to it.lastRestMillis + it.intervalMillis }
    }

    /**
     * PRD §15: fold device-sleep [gaps] (each `start..end` epoch millis) into each screen break's last-rest time.
     * A pause of length L counts as having taken every screen break whose duration ≤ L (a long pause satisfies the
     * shorter poses too), so a task's [ScreenBreak.lastRestMillis] advances to the **latest** qualifying gap's end.
     *
     * This is the batch form of the per-gap rule in `reduceReportDeviceSleep`, and the one that closes the
     * cross-device divergence: a device-sleep gap is authoritative account-wide evidence that the user paused
     * (no device was active), so a peer's synced gap must seed the rest poses here even though **this** device
     * never slept — e.g. an Android phone (which can't read its own OS sleep log) inherits the desktop's rests
     * and stops showing a rest pose pinned to the now-line that the desktop doesn't have. It only advances
     * `lastRestMillis`; it does NOT append work records or carve panels — that belongs to the device that was
     * actually asleep when it recorded the gap.
     */
    fun seedScreenBreaksFromGaps(screenBreaks: List<ScreenBreak>, gaps: List<TaskTimeRange>): List<ScreenBreak> {
        if (gaps.isEmpty()) return screenBreaks
        return screenBreaks.map { side ->
            if (side.durationMillis <= 0) return@map side
            val latestRest = gaps.asSequence()
                .filter { it.endEpochMillis - it.startEpochMillis >= side.qualifyingPauseMillis }
                .map { it.endEpochMillis }
                .filter { it > side.lastRestMillis }
                .maxOrNull()
            if (latestRest != null) side.copy(lastRestMillis = latestRest) else side
        }
    }

    /**
     * PRD §15: fold freshly [seeded] rest times into the [current] screen breaks, advancing each pose's
     * [ScreenBreak.lastRestMillis] **forward only** (never backward). Rest evidence can only ever reveal a
     * more-recent rest, so a later signal never "un-rests" a pose.
     *
     * This guards the two rest-pose seeders — the device's OS sleep log and the server-derived account-wide
     * pauses — which both run off-thread against a snapshot captured when they started and can therefore land
     * out of order. Without a forward-only merge, the slow OS-log seed (built from the startup state where
     * `lastRestMillis == 0`) landing after the derived-pause seed has advanced a pose to `now` would drag it
     * back to the morning wake and re-pin the 5-/15-min pose to the now-line on a freshly-opened account.
     * Index-aligned: both lists derive from [DEFAULT_SCREEN_BREAKS].
     */
    fun advanceRestsForward(current: List<ScreenBreak>, seeded: List<ScreenBreak>): List<ScreenBreak> =
        current.mapIndexed { i, side ->
            val s = seeded.getOrNull(i)
            if (s != null && s.lastRestMillis > side.lastRestMillis) side.copy(lastRestMillis = s.lastRestMillis) else side
        }

    /**
     * PRD §15: the LIVE rest evidence — the pause this device is observing right now (or the one it just
     * finished, until a derive covers it). [gap] starts at the last session finalize (the walk-away
     * instant) and, while the device stays inactive ([ongoing]), ends at the now-line, so it grows with
     * `now` exactly like the display Inactivity tail ([displayInactivityGaps] draws the same range). Once
     * the user is back the end freezes at the reopened session's start ([ongoing] = false) and the gap
     * holds until a derive retires the tail. [ongoing] is what lets [screenBreaksForPlacement] PRESUME an
     * still-growing pause will run long enough to serve each screen break (fluid placement), while a held
     * gap only counts for what it actually contained.
     */
    data class LiveRest(val gap: TaskTimeRange, val ongoing: Boolean)

    /**
     * PRD §15: the device's pending local pause as [LiveRest] evidence — see [LiveRest]. Null when the
     * device has no pending local pause (no walk-away instant, or the range is empty/inverted).
     */
    fun liveRestGap(inactiveSinceMillis: Long?, activeSinceMillis: Long?, nowMillis: Long): LiveRest? {
        val start = inactiveSinceMillis ?: return null
        val end = activeSinceMillis ?: nowMillis
        return if (start < end) LiveRest(TaskTimeRange(start, end), ongoing = activeSinceMillis == null) else null
    }

    /**
     * PRD §15: the screen breaks as the schedule PLACEMENT must see them — with the live, still-underived rest
     * evidence [liveRest] ([liveRestGap]) folded into every screen break's anchor.
     *
     * An **ongoing** pause (the device is inactive right now) is PRESUMED to keep going until it has served
     * each screen break, so every anchor moves the moment the user walks away: the task's presumed rest end is
     * `max(gapEnd, gapStart + duration)` — a *fixed* instant (`gapStart + duration`) while the pause is
     * still younger than the task's duration, transitioning **continuously** into the *sliding* gap end
     * (= the now-line) once the pause reaches it. This is what keeps the whole projected grid fluid under
     * an accelerated leap: without the presumption, every occurrence downstream of a not-yet-served rest
     * pose is re-anchored to that pose's frozen slot ([screenBreakPanels]' pause-re-anchors-shorter-pauses
     * rule) and visibly waits for the leap's end to move. It also means the look-away's boundary can never
     * be crossed mid-pause (the "20s break stayed still and fired during the simulated pause" anomaly).
     *
     * A **held** gap (the user is back; the tail awaits a derive) counts only for what it actually
     * contained — the strict [seedScreenBreaksFromGaps] rule: length ≥ duration, anchored at the gap's end,
     * which is exactly what the derive will bank, so placement doesn't snap when it lands. A pause aborted
     * before reaching a pose's duration therefore RETRACTS that pose's presumption at the reopen instant —
     * the pose is still owed, and its occurrence returns to its stored slot.
     *
     * Placement-only overlay: the stored [ScreenBreak.lastRestMillis] is NOT advanced (the tail — and a
     * fortiori the ongoing-pause presumption — is a local guess a peer's activity or an early return can
     * falsify; [advanceRestsForward] could never take a wrong advance back). It is recomputed on every
     * fill from `now` + the engine's session state, evaporating into the derive's account-wide answer, so
     * it persists and syncs nothing (reconstructibility rule).
     */
    fun screenBreaksForPlacement(screenBreaks: List<ScreenBreak>, liveRest: LiveRest?): List<ScreenBreak> {
        if (liveRest == null) return screenBreaks
        val gap = liveRest.gap
        val restLength = gap.endEpochMillis - gap.startEpochMillis
        return screenBreaks.map { side ->
            if (side.durationMillis <= 0) return@map side
            val restEnd =
                if (liveRest.ongoing) {
                    // Presumed: the still-growing pause serves this task once it has lasted the qualifying
                    // length, then keeps re-satisfying it (sliding gap end) for as long as the user stays away.
                    maxOf(gap.endEpochMillis, gap.startEpochMillis + side.qualifyingPauseMillis)
                } else {
                    // Held: only a pause that actually reached the task's qualifying length counts.
                    if (restLength >= side.qualifyingPauseMillis) gap.endEpochMillis else return@map side
                }
            if (restEnd > side.lastRestMillis) side.copy(lastRestMillis = restEnd) else side
        }
    }

    /**
     * PRD §15 server-derived pauses: the account-wide pauses implied by every device's **active** intervals.
     * A pause is a window when NO device was active (app running + signed in + screen unlocked), so it is the
     * complement of the *union* of all devices' active intervals — computed here over `[sinceMillis, untilMillis]`.
     *
     * Each interval in [active] is clipped to the window and the overlapping/adjacent ones are merged into
     * maximal active spans; the pauses are **every** gap NOT covered by any span — leading (window start →
     * first activity: a freshly emptied account shows the whole window as a pause, a short-running device the
     * long stretch before it started), interior, AND trailing (last activity → `untilMillis` = now):
     * "inactivity unless a device reported activity". The caller keeps the trailing gap honest by freshening
     * the open session to `now` right before deriving (the engine's `freshenOpenSession` runs at every
     * refresh), so an active device's trailing gap is empty rather than a phantom sliver at the now-line; a
     * *finalized* last session leaves a genuine trailing pause, exactly as it should. (The server-side
     * `derive_pauses` gets the same property from the `closed` flag: only a fresh open session is presumed
     * active through `p_until`.)
     *
     * This is the reference the server SQL `derive_pauses` mirrors (the server is authoritative at runtime; this
     * exists so the algorithm is unit-tested and used as the offline/signed-out and RPC-unavailable fallback).
     * Pure and deterministic.
     *
     * A **zero-length** active interval is kept as a boundary *point* (the filter is `end >= start`, not `>`):
     * a session that has only just opened — e.g. the one this device opens right after waking from a sleep, or
     * right after the debug "simulate pause" carves a hole — reads as an active point that correctly bounds the
     * *preceding* pause so the band shows immediately instead of waiting for the session to grow.
     */
    fun derivePauses(active: List<TaskTimeRange>, sinceMillis: Long, untilMillis: Long): List<TaskTimeRange> {
        if (untilMillis <= sinceMillis) return emptyList()
        val clipped = active.asSequence()
            .map { TaskTimeRange(maxOf(it.startEpochMillis, sinceMillis), minOf(it.endEpochMillis, untilMillis)) }
            .filter { it.endEpochMillis >= it.startEpochMillis }
            .sortedBy { it.startEpochMillis }
            .toList()
        // No activity at all in the window: the whole window is one pause (a freshly emptied account where no
        // app was ever active).
        if (clipped.isEmpty()) return listOf(TaskTimeRange(sinceMillis, untilMillis))
        val pauses = mutableListOf<TaskTimeRange>()
        var cursor = sinceMillis
        for (span in clipped) {
            if (span.startEpochMillis > cursor) pauses += TaskTimeRange(cursor, span.startEpochMillis)
            cursor = maxOf(cursor, span.endEpochMillis)
        }
        // Trailing gap: time after the last recorded activity is a pause too (see docstring).
        if (untilMillis > cursor) pauses += TaskTimeRange(cursor, untilMillis)
        return pauses
    }

    /**
     * Safety cap on the screen-break projection loop. Far above what any real horizon holds — ~700 occurrences a
     * week at production timings — so it only ever fires on a genuinely degenerate (near-zero span)
     * configuration. The placement itself is O(n) (see [simulateScreenBreaks]), so a large count is cheap in
     * the projection loop *itself* — but every emitted occurrence becomes a panel that downstream code
     * (`fillSchedule`'s screen-break obstacle regions, the calendar merge/render) pays for, so a genuinely
     * dense cadence is bounded at the source by [DENSE_SCREEN_BREAK_INTERVAL_FLOOR_MILLIS] below.
     */
    private const val SCREEN_BREAK_PROJECTION_LIMIT: Int = 2_000_000

    /**
     * Below this interval a recurring screen break is treated as a **dense test anchor**, not a real cadence:
     * only the debug fast-break override ([org.example.project.DebugFlags.breakIntervalMillisOverride], set by
     * `account2-open-fast-break.bat`) shrinks an interval this far — production intervals are ≥20 min. A
     * sub-minute interval would otherwise place tens of thousands of occurrences across a one-week fill horizon
     * (`604800 s / 5 s ≈ 121 000`), flooding `state.panels` and the O(occurrences)-per-cursor screen-break
     * obstacle scan in [fillSchedule] until the desktop window is created-but-never-shown (the classic "the app
     * froze on launch"). Such a task is capped to [DENSE_SCREEN_BREAK_EMIT_CAP] occurrences per projection
     * (SchedulerDomain owns no debug flag, so the cap keys purely on the configured interval and stays a pure,
     * testable function). The first break still comes due within seconds, and because every reschedule projects
     * afresh from the advanced `lastRestMillis`, a fresh single break re-appears each time the previous one
     * elapses — so the fast-break test keeps working (reach a break, sleep, hear the phone cue) without the
     * flood. Production timings sit far above the floor, so a real schedule is never affected.
     *
     * This cap only ever applies to a **coupled** dense break (`qualifyingPauseMillis == durationMillis`, the
     * real-time `account2-open-fast-break.bat` shape) that legitimately grid-recurs. A **decoupled** pose
     * (`qualifyingPauseMillis > durationMillis`, the `account1-…-fast-break.bat` shape: a 5 s break due only
     * after a ≥2 h pause) never rides the grid at all — [screenBreakPanels] routes it to
     * [decoupledPoseOccurrences], which places one break an interval after each qualifying pause (the live
     * now-anchor + each scheduled sleep window). So the cap is not what bounds the decoupled case; the
     * anchor-per-pause rule is, mathematically ("one break per ≥threshold pause"), not a flood band-aid.
     */
    private const val DENSE_SCREEN_BREAK_INTERVAL_FLOOR_MILLIS: Long = 60_000L

    /** Max occurrences a dense ([DENSE_SCREEN_BREAK_INTERVAL_FLOOR_MILLIS]) screen break emits per projection. */
    private const val DENSE_SCREEN_BREAK_EMIT_CAP: Int = 1

    /**
     * PRD §15: project [screenBreaks] forward from [nowMillis] to [horizonMillis] as obstacle panels,
     * interleaving the three recurrences and resolving their overlaps. Each panel has `screenBreak = true`, a
     * null taskId, and a deterministic `side/{index}/{start}` id; invalid rows are skipped.
     *
     * [horizonMillis] defaults to the scheduling horizon (`now + [SCHEDULE_HORIZON_MILLIS]`), which is what
     * the §9 fill uses as a fixed obstacle window. The calendar display passes the **end of the focused week**
     * instead (PRD §15 "computed from now to the end of the currently focused week"), so navigating to a week
     * beyond the default horizon still shows the screen-break markers across it.
     *
     * Breaks split by whether they can **self-recur** — whether taking the drawn break is itself a qualifying
     * pause for the next one (`qualifyingPauseMillis <= durationMillis`):
     * - **Coupled** breaks (every production break, and the real-time `account2-open-fast-break.bat` shape) DO
     *   self-recur, so they ride the [simulateScreenBreaks] grid engine below.
     * - **Decoupled** poses (`qualifyingPauseMillis > durationMillis`, the `account1-…-fast-break.bat` shape: a
     *   5 s break due only after a ≥2 h pause) do NOT — a 5 s break is not a 2 h pause — so they never ride the
     *   grid. [decoupledPoseOccurrences] places them exactly an interval after each qualifying pause: the live
     *   now-anchor plus each future qualifying-pause window in [qualifyingPauseWindows] (the scheduled sleep
     *   windows), giving one break per day, 5 s after each night's wake. Passing no windows yields just the live
     *   now-anchor. This is the fix for "lots of 5-min breaks in a single day" under the account1 fast-break
     *   script.
     *
     * The grid simulation walks the coupled occurrences in time order (ties resolved toward the **longer** pause
     * so a coincident bigger pause is placed first), applying:
     * - **Recurrence:** a rest pose ([ScreenBreak.restBreak]) recurs an interval after it *ends*
     *   (`start + duration + interval`); the cadence look-away recurs an interval after it *starts*
     *   (`start + interval`).
     * - **A pause re-anchors shorter pauses:** placing any pause (overdue at `now` or future) re-anchors every
     *   *shorter* pause to `thisPauseEnd + itsInterval`, so the look-away always lands **20 min after a pose
     *   ends** ("after a ≥20-second pause, the next look-away is 20 minutes later") and never within an
     *   interval of a longer pose. (An overdue *rest pose* seeds at the now-line; the look-away instead seeds
     *   at the next live slot of its fixed grid — see [screenBreakNextStart].)
     * - **Absorption:** a (defensive) skip of any occurrence whose window still falls inside an already-placed
     *   longer pause; it advances its own clock. With the re-anchoring above a shorter pause is normally pushed
     *   clear of a longer one before it would be drawn.
     * - **5 → 15 merge:** when the 5-min pose comes due just before the 15-min pose (its window would overlap
     *   the still-future 15-min pose), it **becomes** a 15-min pose at its own start and the 15-min pose is
     *   pushed to an interval after the merged pause ends (`mergedEnd + 2 h` = 2h15 after the 5-min start).
     */
    fun screenBreakPanels(
        screenBreaks: List<ScreenBreak>,
        nowMillis: Long,
        horizonMillis: Long = nowMillis + SCHEDULE_HORIZON_MILLIS,
        qualifyingPauseWindows: List<TaskTimeRange> = emptyList(),
    ): List<TaskPanel> {
        val horizon = maxOf(horizonMillis, nowMillis)
        val valid = screenBreaks.withIndex().filter { isValidScreenBreak(it.value) }
        if (valid.isEmpty()) return emptyList()
        val (decoupled, coupled) = valid.partition { isDecoupledPose(it.value) }
        // Coupled breaks: seed each at its due time (or `now` when overdue) and run the shared grid engine.
        val gridPanels =
            if (coupled.isEmpty()) emptyList()
            else simulateScreenBreaks(screenBreaks, coupled.associate { (i, t) -> i to screenBreakNextStart(t, nowMillis) }, horizon)
        // Decoupled poses: an interval after the live now-anchor and after each future qualifying pause.
        val decoupledPanels = decoupled.flatMap { (i, t) ->
            decoupledPoseOccurrences(i, t, nowMillis, horizon, qualifyingPauseWindows, includeLiveNow = true, nowMillis)
        }
        return (gridPanels + decoupledPanels).sortedBy { it.startEpochMillis }
    }

    /** True when [side] is a rest pose whose qualifying pause exceeds its drawn length (the fast-break shape). */
    private fun isDecoupledPose(side: ScreenBreak): Boolean =
        side.restBreak && side.qualifyingPauseMillis > side.durationMillis

    /**
     * PRD §15: the occurrences of a **decoupled** rest pose ([isDecoupledPose]) over `[fromMillis, toMillis]`.
     * A decoupled pose cannot self-recur — its short drawn break is not a qualifying pause — so it never rides
     * the [simulateScreenBreaks] grid; it appears exactly `interval` after each qualifying pause:
     * - the most recent PAST pause (its anchored `lastRestMillis`, clamped forward to the now-line while
     *   overdue-unserved via [screenBreakNextStart]), included only when [includeLiveNow] — i.e. the window that
     *   actually contains the now-line ([screenBreakPanels]); a future-week window ([screenBreakPanelsInWindow])
     *   omits it, since the live anchor is not inside that week; and
     * - each FUTURE qualifying-pause window in [qualifyingPauseWindows] whose length reaches the pose's
     *   qualifying threshold — the scheduled sleep windows are the only future ≥threshold pauses on a typical
     *   schedule, so a break lands `interval` after each night's wake ("after a ≥2 h pause the next 5-min pose
     *   is `interval` later"). This is what makes the account1 fast-break calendar show exactly one 5-min break
     *   per day instead of a 5-second grid flood.
     */
    fun decoupledPoseOccurrences(
        index: Int,
        pose: ScreenBreak,
        fromMillis: Long,
        toMillis: Long,
        qualifyingPauseWindows: List<TaskTimeRange>,
        includeLiveNow: Boolean,
        nowMillis: Long,
    ): List<TaskPanel> {
        if (toMillis < fromMillis) return emptyList()
        val starts = buildList {
            if (includeLiveNow) add(screenBreakNextStart(pose, nowMillis))
            for (w in qualifyingPauseWindows) {
                if (w.endEpochMillis - w.startEpochMillis >= pose.qualifyingPauseMillis) {
                    add(w.endEpochMillis + pose.intervalMillis)
                }
            }
        }.filter { it in fromMillis..toMillis }.distinct().sorted()
        return starts.map { screenBreakPanel(index, pose.title, it, it + pose.durationMillis) }
    }

    /**
     * PRD §15: the most recent screen-break occurrence whose start is strictly before [nowMillis] (the
     * "last past screen break before the now-line"), or null when none. Reuses the same interleaving /
     * merge / re-anchor engine the forward [screenBreakPanels] uses, but seeds each task's grid at a
     * point a full longest-interval (+ longest duration) before `now` and runs only up to `now`. That
     * lookback is wide enough that any pose able to re-anchor a look-away inside the window is itself
     * placed first, so the reconstructed recent cadence matches what the forward projection would have
     * drawn. Callers test `restBreak` of the returned panel's source task to tell a 20s look-away apart
     * from a rest pose.
     */
    fun lastScreenBreakBefore(
        screenBreaks: List<ScreenBreak>,
        nowMillis: Long,
    ): TaskPanel? {
        val valid = screenBreaks.withIndex().filter { isValidScreenBreak(it.value) }
        if (valid.isEmpty()) return null
        val maxInterval = valid.maxOf { it.value.intervalMillis }
        val maxDuration = valid.maxOf { it.value.durationMillis }
        return screenBreakOccurrencesBetween(screenBreaks, nowMillis - maxInterval - maxDuration, nowMillis)
            .filter { it.startEpochMillis < nowMillis }
            .maxByOrNull { it.startEpochMillis }
    }

    /**
     * PRD §15: every screen-break occurrence whose **start** lies in `[fromMillis, toMillis]`, reconstructed
     * from the fixed grid via the same interleave / merge / re-anchor engine [screenBreakPanels] uses — but
     * seeded from the PAST (one full recurrence step at/before [fromMillis]) so it reproduces the cadence
     * inside the window **without walking the grid from `now`.**
     *
     * This is the calendar's source of screen-break markers for a week the forward [screenBreakPanels] would have
     * to project across to reach: the forward projection generates every occurrence between `now` and the
     * horizon, so drawing a week `N` weeks out costs `O(N)` occurrences — and under a shrunk 5-min-break
     * interval ([org.example.project.DebugFlags.breakIntervalMillisOverride]) that is tens of thousands of
     * them, run through the `O(n²)` placement scan in [simulateScreenBreaks], which froze the UI when the user
     * opened a distant day.
     * Reconstructing only `[fromMillis, toMillis]` keeps the cost proportional to the **visible window**,
     * independent of how far it sits from `now` (CLAUDE.md: hot-path display derivations scale with the
     * screen, not with total history).
     *
     * Each participating task is seeded a full recurrence step at/before [fromMillis] so any pose able to
     * re-anchor a look-away inside the window is itself placed first (the same widening [lastScreenBreakBefore]
     * and [screenBreakOccurrencesBetween] rely on). Unlike [screenBreakOccurrencesBetween] this applies **no**
     * dragging-pose shadow — a display window shows every occurrence the periodic grid places; the shadow is
     * a cue-ordering concern of the now-line sweep only.
     *
     * Like [screenBreakPanels], a **decoupled** pose ([isDecoupledPose]) is projected off the grid via
     * [decoupledPoseOccurrences] — here anchored only to the future qualifying-pause windows
     * ([qualifyingPauseWindows], the scheduled sleep windows) that fall in this week, since the live now-anchor
     * is not inside a future week.
     */
    fun screenBreakPanelsInWindow(
        screenBreaks: List<ScreenBreak>,
        fromMillis: Long,
        toMillis: Long,
        qualifyingPauseWindows: List<TaskTimeRange> = emptyList(),
    ): List<TaskPanel> {
        if (toMillis < fromMillis) return emptyList()
        val valid = screenBreaks.withIndex().filter { isValidScreenBreak(it.value) }
        if (valid.isEmpty()) return emptyList()
        val (decoupled, coupled) = valid.partition { isDecoupledPose(it.value) }
        val gridPanels =
            if (coupled.isEmpty()) {
                emptyList()
            } else {
                val maxInterval = coupled.maxOf { it.value.intervalMillis }
                val maxDuration = coupled.maxOf { it.value.durationMillis }
                val from = fromMillis - maxInterval - maxDuration
                val seedDue = coupled.associate { (i, t) ->
                    // A pose recurs over a (duration + interval) cycle; the look-away every interval. Seed at the
                    // grid point at/just before the widened [from] so the loop reconstructs every occurrence up to
                    // [toMillis] with the re-anchoring poses already placed.
                    val step = if (t.restBreak) t.durationMillis + t.intervalMillis else t.intervalMillis
                    val base = t.lastRestMillis + t.intervalMillis
                    i to (if (base >= from) base else base + ((from - base) / step) * step)
                }
                simulateScreenBreaks(screenBreaks, seedDue, toMillis)
                    .filter { it.startEpochMillis in fromMillis..toMillis }
            }
        val decoupledPanels = decoupled.flatMap { (i, t) ->
            decoupledPoseOccurrences(i, t, fromMillis, toMillis, qualifyingPauseWindows, includeLiveNow = false, fromMillis)
        }
        return (gridPanels + decoupledPanels).sortedBy { it.startEpochMillis }
    }

    /**
     * PRD §15: [screenBreakPanelsInWindow] minus the **dragging-pose shadow** — the **leap-safe** source for the
     * look-away cue. The forward [screenBreakPanels] seeds each task at [screenBreakNextStart], which **steps the
     * grid past any occurrence `now` has already crossed**, so a look-away the clock jumped over is simply
     * absent from `state.panels`; a panel-based cue can then never fire it — the reported "the 20s break was
     * notified *after* the 5-min pose, it should have been before": the earlier look-away was dropped from the
     * projection and only the next (re-anchored past the pose) one remained. Reconstructing over the sweep's
     * `[scanFloor, now]` window instead tiles the timeline, so no crossed boundary is clipped (mirrors the
     * level `now >= due` leap-proofing of the rest-pose cue in [reachedRestPoseDueByTitle]).
     *
     * Callers test `restBreak` of a returned panel's source task to tell a 20s look-away apart from a rest pose.
     *
     * **Dragging-pose shadow.** The static engine places an overdue rest pose at its FIXED due and re-anchors
     * the look-away to that pose's fixed end (`due + duration + interval`), emitting one look-away just past
     * the pose. But an overdue-**unserved** pose does not sit at its due — it SLIDES to the now-line
     * ([screenBreakNextStart] = `maxOf(due, now)`), and dragging there it perpetually re-anchors every shorter
     * task to `now + interval`, a slot that recedes as fast as `now` advances and that the now-line therefore
     * never crosses. So while a pose is overdue and unserved, NO look-away is a fixed crossable boundary after
     * its due; the one the static engine emits is a phantom (the reported "a 20s break fired right after the
     * 5-min pose I never took — I only accelerated time, so the pose was just dragged"). Look-aways whose
     * start is at/after the earliest overdue-unserved pose's due are dropped here (the pose's own due cue is
     * the level [reachedRestPoseDueByTitle], unaffected; a genuinely-served pose is not overdue — its
     * `lastRestMillis` advanced — so it casts no shadow and the look-away legitimately resumes 20 min after it).
     */
    fun screenBreakOccurrencesBetween(
        screenBreaks: List<ScreenBreak>,
        fromMillis: Long,
        toMillis: Long,
    ): List<TaskPanel> {
        if (toMillis < fromMillis) return emptyList()
        val valid = screenBreaks.withIndex().filter { isValidScreenBreak(it.value) }
        if (valid.isEmpty()) return emptyList()
        // The earliest overdue-unserved rest-pose due (anchored, i.e. `lastRestMillis > 0` — an un-anchored
        // pose is the pre-seed transient, not a drag). Look-aways at/after it are shadowed — see the docstring.
        val overduePoseDue = valid
            .filter { (_, t) -> t.restBreak && t.lastRestMillis > 0 && t.lastRestMillis + t.intervalMillis <= toMillis }
            .minOfOrNull { (_, t) -> t.lastRestMillis + t.intervalMillis }
        return screenBreakPanelsInWindow(screenBreaks, fromMillis, toMillis)
            .filterNot { panel ->
                // Drop a look-away shadowed by a dragging (overdue-unserved) pose — see the docstring.
                overduePoseDue != null &&
                    panel.startEpochMillis >= overduePoseDue &&
                    screenBreaks.any { !it.restBreak && it.title == panel.title }
            }
    }

    /** PRD §15: a cue boundary the now-line crossed — the atom of the engine's single ordered cue sweep. */
    enum class CueKind { LookAwayStart, RestPoseDue, WindDown }

    /**
     * A single cue crossing: fire the [kind] cue for [title] at [instant]. [endInstant] is the look-away's
     * resume moment (`start + duration`) for [CueKind.LookAwayStart], else equal to [instant].
     */
    data class CueCrossing(
        val instant: Long,
        val kind: CueKind,
        val title: String,
        val endInstant: Long,
    )

    /**
     * PRD §15 / CLAUDE.md "each fires exactly once, **in order**": the cue boundaries the clock crossed in a
     * sweep window, as a single list sorted by their true boundary [CueCrossing.instant]. This is the pure
     * core the engine's one cue sweep drives — collapsing the previously independent look-away / rest-pose /
     * wind-down coroutines, whose separate now-line collectors could fire a leap's crossings in the wrong
     * order (the reported "20s look-away announced after the 5-min pose, but its boundary was earlier").
     *
     * The three kinds are gathered by their own leap-safe rules and then merged/sorted:
     * - **Look-away starts** — [screenBreakOccurrencesBetween] over `[fromMillis, toMillis]` (not the forward
     *   projection, which drops an already-crossed occurrence). Each carries its resume instant as
     *   [CueCrossing.endInstant].
     * - **Rest-pose dues** — [reachedRestPoseDueByTitle] at [toMillis] (a **level** reach, so a jump can't
     *   skip it), keyed on the stable due; a due already in [alreadyNotifiedPoseDues] is omitted, so a pose
     *   sliding along the now-line announces once. The crossing's instant is the DUE, so it orders against a
     *   look-away by which boundary was actually earlier — even an overdue due that predates [fromMillis].
     * - **Wind-downs** — the caller's precomputed bedtime−1h instants that fall in the window.
     *
     * Staleness (real age), the screen-active gate and the once-only de-dupe stay in the engine, which owns
     * the clock and the fired-boundary memory; this function is a pure function of the schedule and window.
     */
    fun cueCrossings(
        screenBreaks: List<ScreenBreak>,
        windDownInstants: List<Long>,
        automaticSchedule: Boolean,
        alreadyNotifiedPoseDues: Map<String, Long>,
        fromMillis: Long,
        toMillis: Long,
    ): List<CueCrossing> {
        val out = mutableListOf<CueCrossing>()
        // 20s look-away starts (leap-safe reconstruction; a pose-merged/absorbed occurrence never surfaces).
        for (occ in screenBreakOccurrencesBetween(screenBreaks, fromMillis, toMillis)) {
            if (screenBreaks.any { !it.restBreak && it.title == occ.title }) {
                out += CueCrossing(occ.startEpochMillis, CueKind.LookAwayStart, occ.title, occ.endEpochMillis)
            }
        }
        // 5/15-min rest-pose dues reached at the now-line (level; the stable due is the ordering key).
        if (automaticSchedule) {
            for ((title, due) in reachedRestPoseDueByTitle(screenBreaks, toMillis)) {
                if (alreadyNotifiedPoseDues[title] != due) {
                    out += CueCrossing(due, CueKind.RestPoseDue, title, due)
                }
            }
        }
        // Wind-down (bedtime − 1h) instants that fall in the window.
        for (wd in windDownInstants) {
            if (wd in fromMillis..toMillis) out += CueCrossing(wd, CueKind.WindDown, "", wd)
        }
        return out.sortedWith(compareBy({ it.instant }, { it.kind.ordinal }))
    }

    /**
     * PRD §15 projection engine shared by [screenBreakPanels] (forward from `now`) and [lastScreenBreakBefore]
     * (recent past). Walks the [seedDue] occurrences in time order up to [horizon], resolving overlaps via
     * the merge / absorption / re-anchor rules documented on [screenBreakPanels]. [seedDue] maps each
     * participating screen-break index to the start of its first occurrence to consider.
     */
    private fun simulateScreenBreaks(
        screenBreaks: List<ScreenBreak>,
        seedDue: Map<Int, Long>,
        horizon: Long,
    ): List<TaskPanel> {
        if (seedDue.isEmpty()) return emptyList()
        // The rest poses (restBreak) absorb any shorter pause whose window they fall inside (the 5↔15 merge
        // and the look-away→pose merge below).
        val poses = seedDue.keys.filter { screenBreaks[it].restBreak }

        // Next-due start per task index; seeded by the caller.
        val due = HashMap(seedDue)

        // Dense test-anchor cap (see [DENSE_SCREEN_BREAK_INTERVAL_FLOOR_MILLIS]): a sub-minute-interval break
        // is only the debug fast-break override, which would otherwise flood the projection. Each such index
        // gets a small remaining-occurrence budget; when it runs out the index is dropped from [due] so it
        // stops recurring. Production intervals are ≥20 min, so this map is empty for a real schedule.
        val denseBudget = HashMap<Int, Int>()
        for (i in seedDue.keys) {
            if (screenBreaks[i].intervalMillis in 1L until DENSE_SCREEN_BREAK_INTERVAL_FLOOR_MILLIS) {
                denseBudget[i] = DENSE_SCREEN_BREAK_EMIT_CAP
            }
        }

        val result = mutableListOf<TaskPanel>()
        // The still-OPEN placed pauses (those whose end is still ahead of the occurrence being placed), as
        // `(end, duration)`. Occurrences are placed in non-decreasing `start` order (every `due` only ever
        // moves forward, and an absorbed pose is pulled back only to the *current* start), so a placed pause
        // covers the current `start` iff its end is still ahead of it — which is exactly this set once pruned.
        // Testing "is `start` inside an already-placed pause strictly longer than `durationMillis`" is then a
        // scan of just the open pauses (kept tiny by the merge/absorption rules) instead of the whole growing
        // result list — the O(n) transform that keeps a dense projection (a debug seconds-interval break, or a
        // far-future week filled from the now-line) linear instead of O(n²).
        val openPauses = ArrayList<Pair<Long, Long>>()
        // PRD §15: placing a pause re-anchors every *shorter* pause to `thisPauseEnd + itsInterval`, so a
        // shorter pause never lands within its own interval of a longer one that already covers it — e.g.
        // the look-away always restarts 20 min *after a pose ends* ("after a ≥20-second pause, the next
        // look-away is 20 minutes later"), rather than continuing its own grid into the gap right after the
        // pose. Applies to every placed pause (overdue at `now` or future), so the rule holds forward too.
        fun reanchorSmaller(placedEnd: Long, placedDuration: Long) {
            seedDue.keys.forEach { j ->
                val s = screenBreaks[j]
                if (s.durationMillis < placedDuration) {
                    due[j] = placedEnd + s.intervalMillis
                }
            }
        }

        var guard = 0
        while (guard++ < SCREEN_BREAK_PROJECTION_LIMIT) {
            // The earliest pending occurrence within the horizon; ties go to the longer pause.
            val nextIndex = due.entries
                .filter { it.value <= horizon }
                .minWithOrNull(
                    compareBy<Map.Entry<Int, Long>> { it.value }
                        .thenByDescending { screenBreaks[it.key].durationMillis },
                )?.key ?: break
            val task = screenBreaks[nextIndex]
            val start = due.getValue(nextIndex)
            // Drop the pauses that have already closed at/before this occurrence's start (`start` never moves
            // backward, so a closed pause can never re-open) — leaving exactly the pauses that cover `start`.
            openPauses.removeAll { it.first <= start }

            // Screen breaks are projected straight through the nightly sleep windows too — a user who works at
            // the computer during the night still needs the eye-rest / pose cues, so the cadence never pauses
            // for sleep and the markers render over the "Sleep" band (PRD §15). A device that is *actually*
            // asleep advances each task's lastRestMillis over the real device-sleep gap
            // ([seedScreenBreaksFromGaps] / reduceReportDeviceSleep), which re-anchors the poses past the gap on
            // its own — so the "don't nag me the instant I wake" case is handled by real sleep evidence, not
            // by skipping the scheduled window here.

            // Merge (PRD §15): a strictly-longer rest pose coming due within this occurrence's window absorbs
            // it — the occurrence "becomes" that pose, which then starts here (at this occurrence's start) and
            // ends its own duration later. We pull the pose's due back to `start` and re-evaluate; the loop
            // places it next (the tie-break prefers the longer pause, and re-anchoring pushes this shorter
            // occurrence past the pose's end). This generalizes the 5-min→15-min merge to the 20s look-away, so
            // a look-away that would overlap a pose never renders — or sounds — as its own occurrence. It also
            // cascades: a look-away pulled onto a 5-min pose that itself overlaps the 15-min pose ends as a
            // single 15-min pose.
            val absorbing = poses
                .filter {
                    it != nextIndex &&
                        screenBreaks[it].durationMillis > task.durationMillis &&
                        (due[it] ?: Long.MAX_VALUE) in (start + 1) until (start + task.durationMillis)
                }
                .maxByOrNull { screenBreaks[it].durationMillis }
            if (absorbing != null) {
                due[absorbing] = start
                continue
            }

            val end = start + task.durationMillis
            // `start` is inside an already-placed strictly-longer pause iff any still-open pause is longer.
            // Only *placed* pauses can cover a later occurrence (matching the old scan of `result`), so a
            // covered/skipped occurrence is never itself added to the open set.
            val covered = openPauses.any { it.second > task.durationMillis }
            if (!covered) {
                result.add(screenBreakPanel(nextIndex, task.title, start, end))
                openPauses.add(end to task.durationMillis)
            }
            // Recurrence: poses resume an interval after they end; the cadence look-away an interval after it
            // starts. Placing this pause also pushes every shorter pause to an interval after it ends.
            // (Only COUPLED breaks reach this grid — a decoupled pose is projected separately by
            // [decoupledPoseOccurrences] and never seeded here; see [screenBreakPanels].)
            val remaining = denseBudget[nextIndex]
            if (remaining != null && remaining <= 1) {
                // Dense test-anchor budget exhausted — stop recurring this index so its sub-minute interval
                // can't flood the projection (see [DENSE_SCREEN_BREAK_INTERVAL_FLOOR_MILLIS]).
                due.remove(nextIndex)
            } else {
                if (remaining != null) denseBudget[nextIndex] = remaining - 1
                due[nextIndex] = (if (task.restBreak) end else start) + task.intervalMillis
            }
            reanchorSmaller(end, task.durationMillis)
        }
        return result.sortedBy { it.startEpochMillis }
    }

    private fun screenBreakPanel(index: Int, title: String, start: Long, end: Long): TaskPanel =
        TaskPanel(
            id = "side/$index/$start",
            taskId = null,
            title = title,
            startEpochMillis = start,
            endEpochMillis = end,
            pinned = false,
            auto = false,
            screenBreak = true,
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
     * CLAUDE.md reconstructibility rule: true for a panel [fillSchedule] **regenerates** deterministically
     * from `now` + the tree + the sleep/screen-break config — the screen-break and sleep obstacle panels and the
     * non-pinned auto-fill panels. These carry no authoritative user state, so a re-derive that only moves
     * them is not a syncable change. Pinned panels (user-fixed) and reminder tags (`chore`, which carry the
     * authoritative `checked` state) are NOT regenerated and so are never treated as derived. Mirrors the
     * `kept` filter in [fillSchedule] (screenBreak/sleep always cut; everything else kept when fixed or a
     * reminder). Used by [org.example.project.scheduler.persistence.SchedulerStateCodec.syncFingerprint] to
     * exclude derived panels from the sync fingerprint, so an engine-tick reschedule that only re-derives
     * them neither marks state dirty nor pushes ("known deviation" fix).
     */
    fun isRegeneratedPanel(panel: TaskPanel): Boolean =
        panel.screenBreak || panel.sleep || (panel.auto && !panel.pinned && !panel.chore)

    /**
     * PRD §9 Scheduling: regenerate the auto schedule with the **cyclic proportional-share** rules of
     * `tests/README.md` — [SchedulerPlanner] / [PlanWalk], the Kotlin port of the reference `tests/test.py`.
     * Every **non-pinned** panel in the window `[now, horizonMillis]` is cut and replaced; the only panels
     * kept are the **fixed** ones (pinned + chore, [isSchedulerFixed]), any panel entirely **outside** the
     * window — already past (`end ≤ now`) or starting beyond the horizon — and, when this call is an
     * *extension* rather than a re-plan, the auto head already materialized ([keepExistingUntilMillis]).
     * On a full re-plan, cutting the in-progress non-pinned panel too means the current task is re-derived
     * from `now` each run, so a task added to the tree always reschedules immediately (no kept block can
     * swallow the window); the fill is deterministic, so a refill at the same instant reproduces the same
     * panels (the §9 no-op short-circuit still fires) and re-picks the same current task (notification
     * continuity, §11).
     *
     * ### This function is a *driver*, not a second copy of the rules
     * Every scheduling decision — which task, for how long, how an exclusion distorts the neighbourhood, how
     * an abnormal imbalance is forgotten — lives in [PlanWalk], the single shared implementation that
     * [SchedulerPlanner.plan] (the rule-list form of `tests/README.md`) also drives. What this function adds
     * is the mapping from OmniApp's world onto the reference's two inputs, and the materialization of concrete
     * [TaskPanel]s:
     * - **pre-placed blocks** = the user's pinned/manual panels still ahead of `now`, plus (on an extension)
     *   the kept head of the plan, plus the already-served **past** (records and past panels), which is what
     *   seeds the virtual clocks ([SchedulerPlanner.seedClocks]) and what the influence field ramps down from;
     * - **periods that accept a set of tasks** = the §9 screen zones and the §15 screen breaks. Inside a
     *   no-screen period only off-screen tasks are accepted, outside one only on-screen tasks, and inside a
     *   5/15-min screen break only the tasks marked *doable during a screen break*. The reference's rule that
     *   a task is a candidate only while its minimum fits the gap is what enforces "never when its minimum
     *   time exceeds the break's length", with no special case.
     *
     * Because a fixed block owned by another task and a period that bans a task are the *same* deprivation
     * (`tests/README.md`), both feed one influence field: a task kept out of the timeline gets a denser,
     * **bounded** presence on both sides of the exclusion, decaying exponentially with the distance. That is
     * what replaced the earlier debt-with-a-natural-bound model: a 17-hour block of A no longer buys B 17
     * hours of catch-up, it buys it a logarithmic amount of compensation spread around the block.
     *
     * PRD §15 Screen breaks: [SchedulerState.screenBreaks] are materialized as obstacle panels
     * ([screenBreakPanels]) and woven into the window as periods. They behave like a pinned obstacle with one
     * difference: when a task chunk meets a screen break, the chunk is **split** around it and the task
     * **resumes after** with its remaining work, so its minimum is never charged for the screen-break time (a
     * 45-min task crossing a 5-min screen break occupies a 50-min wall-clock span). A pinned obstacle, by
     * contrast, truncates the chunk (the minimum is cut). Screen-break panels regenerate every fill.
     *
     * PRD §9 merge: two consecutive auto panels of the same task are fused into one block
     * ([mergeSameTaskPanels]), so a sole task shows as a single continuous panel. Auto panels get
     * deterministic `auto/{i}` ids (regenerated each run, skipping ids held by kept panels).
     *
     * PRD §9 trigger: this runs only when [schedulingSignature] moves — never because time passed. The
     * `SchedulerIntent.ExtendSchedule` path merely materializes more of the same plan (see
     * [keepExistingUntilMillis]).
     */
    fun fillSchedule(
        state: SchedulerState,
        nowMillis: Long,
        // Sleep is local wall-clock, so the otherwise tz-pure fill needs a zone to place the nightly
        // sleep windows. Defaults to the system zone for production; tests pass empty/explicit sleep.
        timeZone: TimeZone = TimeZone.currentSystemDefault(),
        // The device's live ongoing/held pause, if any ([liveRestGap]). Folded into screen-break placement
        // via [screenBreaksForPlacement] so the screen-break grid moves with a pause the derives haven't
        // banked yet — placement-only; the stored lastRestMillis is untouched.
        liveRest: LiveRest? = null,
        // The instant to materialize the plan out to — **the horizon follows what is displayed**, never a
        // fixed 168h. Live callers pass [scheduleHorizonEndMillis] of the focused week (the reducer, via
        // `SchedulerReducer.scheduleHorizonEndMillis`), so staying on the current week computes only that
        // week and no later day. A DISPLAY caller viewing a week past the 168h ceiling passes that week's end
        // directly. The work is O(horizon); a distant week is meant to be filled off the UI thread (it "takes
        // time to display", never freezes), and nothing beyond the requested horizon is retained, so
        // navigating back simply refills the nearer window. The default is the ceiling, for tests and for any
        // caller that genuinely wants the maximum span.
        horizonMillis: Long = nowMillis + SCHEDULE_HORIZON_MILLIS,
        // PRD §9 / CLAUDE.md trigger rule: when non-null, this is an **extension**, not a re-plan — the auto
        // panels already materialized before this instant are KEPT (the cursor walks over them, feeding the
        // virtual clocks exactly as if it had just placed them) and only the tail past them is generated. The
        // rolling-horizon / calendar-navigation refills use it so that merely *displaying* more days never
        // rewrites the plan the user is already looking at; only a change to the scheduling rules
        // ([schedulingSignature]) re-plans from `now` (null).
        keepExistingUntilMillis: Long? = null,
    ): List<TaskPanel> {
        val horizon = maxOf(horizonMillis, nowMillis)
        // Cut every non-pinned panel in [now, horizon]; keep fixed (pinned) panels, reminder tags (PRD
        // §14 — kept on the calendar though not obstacles, see isSchedulerFixed), and any panel entirely
        // outside the window — already past (end ≤ now) or beyond the horizon (start > horizon). Screen-break
        // and schedule-DERIVED (`sleep/{day}`) sleep panels are always cut and regenerated fresh below, so
        // they never accumulate — but MATERIALIZED past-sleep panels (PRD §17, allocated id) are a recorded
        // fact and kept, like the materialized Inactivity panels.
        val kept = state.panels.filter {
            when {
                it.screenBreak -> false
                it.sleep -> !it.id.startsWith("sleep/")
                else ->
                    isSchedulerFixed(it) || it.chore || it.noScreen || it.inactivity ||
                        it.endEpochMillis <= nowMillis || it.startEpochMillis > horizon ||
                        // An EXTENSION keeps the already-materialized head of the plan (see the parameter).
                        (keepExistingUntilMillis != null && it.auto && it.startEpochMillis < keepExistingUntilMillis)
            }
        }
        // The user's sleep windows: rendered as "Sleep" bands, but NO LONGER task obstacles. The work plan
        // projects straight through them (like the screen breaks) so a user working at night still sees the
        // priority-ordered plan; those panels render tinted, under the "Sleep" band (see CalendarUi).
        val sleepPanels = sleepPanels(state.sleep, nowMillis, horizon, timeZone)
        val leaves = schedulableLeaves(state)
        // PRD §15: screen breaks materialize regardless of whether there are leaf tasks to fill around them.
        // Each one places its next occurrence at its due time (or the now-line when overdue), with the
        // 5-min↔15-min merge applied. They project straight through the sleep windows too, so the eye-rest /
        // pose cues still fire (and render over the "Sleep" band) for a user working through the night.
        // Bounded by THIS fill's [horizon], not by the fixed 168h default: a fill for a short horizon (the
        // displayed week ends tomorrow, or the calendar is closed) must not project a week of breaks it will
        // then carry in `panels`, and a DISPLAY fill for a far week must project across it (the default would
        // stop at 168h and leave the far days break-less).
        val sidePanels = screenBreakPanels(
            screenBreaksForPlacement(state.screenBreaks, liveRest),
            nowMillis,
            horizon,
            qualifyingPauseWindows = sleepPanels.map { TaskTimeRange(it.startEpochMillis, it.endEpochMillis) },
        )
        if (leaves.isEmpty()) return (kept + sidePanels + sleepPanels).sortedBy { it.startEpochMillis }

        val priorities = absoluteTaskPriorities(state)
        val keptIds = kept.mapTo(HashSet()) { it.id }
        val working = state.copy(panels = kept)
        // PRD §9 screen switches: the no-screen periods *classify* the timeline rather than obstructing it
        // — an on-screen task may only run outside them, an off-screen task only inside. Inactivity
        // periods classify nothing (they stay screen periods). Neither is an occupancy obstacle.
        val noScreenRegions =
            mergeOccupied(
                kept.filter { it.noScreen }
                    .map { TaskTimeRange(maxOf(it.startEpochMillis, nowMillis), it.endEpochMillis) }
                    .filter { it.endEpochMillis > it.startEpochMillis },
            )
        // PRD §15: the screen-break occupied regions. They are periods (only break-doable tasks are accepted
        // inside them), not occupancy obstacles: a regular chunk suspends across one and resumes after it.
        val sideRegions = mergeOccupied(sidePanels.map { TaskTimeRange(it.startEpochMillis, it.endEpochMillis) })
        val breakStarts = sideRegions.mapTo(HashSet()) { it.startEpochMillis }
        fun covers(regions: List<TaskTimeRange>, t: Long): Boolean =
            regions.any { it.startEpochMillis <= t && t < it.endEpochMillis }

        // `tests/test.py` resolves ties by (biggest share, then name); OmniApp's PRD §9 tie-break is (highest
        // absolute priority, then title). [PlanWalk.pick] takes the first candidate on a tie, so handing it
        // this order IS the tie-break.
        val tieBreak =
            compareByDescending<TaskId> { priorities[it] ?: 0.0 }.thenBy { state.tasks[it]?.title.orEmpty() }
        val ordered = leaves.sortedWith(tieBreak)
        val minimumMillisOf = ordered.associateWith { (state.tasks[it]?.minimumMinutes ?: 0).toLong() * MILLIS_PER_MINUTE }
        val planner =
            SchedulerPlanner(ordered.map { PlanTask(it, priorities[it] ?: 0.0, minimumMillisOf[it] ?: 0L) })

        // --- the periods (`tests/README.md`: "the timeline is formed of periods, each defining a set of
        // tasks it accepts"). The screen zones and the screen breaks partition [now, ∞) into maximal spans
        // with a constant accepted set; the LAST one is left open-ended, so a task banned from it is banned
        // "forever" and the field gives it a ramp before the ban and no phantom ramp after the horizon.
        val edges = buildList {
            add(nowMillis)
            for (region in noScreenRegions + sideRegions) {
                if (region.startEpochMillis in (nowMillis + 1) until horizon) add(region.startEpochMillis)
                if (region.endEpochMillis in (nowMillis + 1) until horizon) add(region.endEpochMillis)
            }
        }.distinct().sorted()
        val windows = edges.mapIndexed { i, start ->
            val onScreenHere = ordered.filter { (working.tasks[it]?.onScreen ?: true) != covers(noScreenRegions, start) }
            val accepted =
                if (covers(sideRegions, start)) {
                    onScreenHere.filter { working.tasks[it]?.doableDuringBreak == true }
                } else {
                    onScreenHere
                }
            PlanWindow(start, edges.getOrNull(i + 1), accepted.toSet())
        }
        // The accepted lists, in the tie-break order, precomputed per window: the walk asks for them at every
        // step and rebuilding them there would make the fill quadratic in the number of screen breaks.
        val accepted = windows.map { w -> ordered.filter { it in w.allowed } }

        // --- the pre-placed blocks. Ahead of the cursor: the user's fixed blocks and, on an extension, the
        // kept head of the plan — both are committed service the cursor must walk OVER. Behind it: what has
        // already been served, which seeds the virtual clocks and anchors the influence field.
        val futureBlocks =
            kept.asSequence()
                .filter { (isSchedulerFixed(it) || it.auto) && it.endEpochMillis > nowMillis && !it.chore }
                .map { PlanBlock(it.taskId, maxOf(it.startEpochMillis, nowMillis), it.endEpochMillis) }
                .filter { it.endMillis > it.startMillis }
                .sortedBy { it.startMillis }
                .toList()
        // How far back the already-placed past is read. One period is all the seed needs
        // ([SchedulerPlanner.seedClocks]); the field needs its own reach, past which an exclusion is felt no
        // more. Bounded by [SCHEDULE_PAST_LOOKBACK_MILLIS] so the fill never costs total history.
        val pastLookback =
            maxOf(planner.minPeriodMillis, planner.maxReachMillis)
                .coerceIn(MILLIS_PER_MINUTE.toDouble(), SCHEDULE_PAST_LOOKBACK_MILLIS.toDouble())
                .roundToLong()
        val pastAnchor = nowMillis - pastLookback
        val pastBlocks =
            ordered.flatMap { id ->
                // Merged per task so a record and the auto panel that banked it are not counted twice.
                mergeOccupied(pastPeriodsForTask(working, id, nowMillis))
                    .map { PlanBlock(id, maxOf(it.startEpochMillis, pastAnchor), minOf(it.endEpochMillis, nowMillis)) }
                    .filter { it.endMillis > it.startMillis }
            }.sortedBy { it.startMillis }

        planner.setField(pastBlocks + futureBlocks, windows)
        val walk = planner.walk(planner.seedClocks(pastBlocks, nowMillis))

        val generated = mutableListOf<TaskPanel>()
        var cursor = nowMillis
        var index = 0
        var idCounter = 0
        // `tests/test.py` `free_tail`: whether the last thing placed was a freely-chosen slot, and so may be
        // stretched over a crumb too short for any minimum.
        var freeTail = false
        // PRD §15: the task whose chunk is mid-placement, split across a screen break, with the work it still
        // owes. Carried across iterations so it resumes after the break rather than being re-picked mid-chunk.
        var pending: Pair<TaskId, Long>? = null
        fun nextAutoId(): String {
            while ("auto/$idCounter" in keptIds) idCounter++
            return "auto/${idCounter++}"
        }
        fun emit(taskId: TaskId, start: Long, end: Long) {
            generated += TaskPanel(
                id = nextAutoId(),
                taskId = taskId,
                title = working.tasks[taskId]?.title.orEmpty(),
                startEpochMillis = start,
                endEpochMillis = end,
                pinned = false,
                auto = true,
            )
        }
        // The windows partition the timeline and the cursor only advances, so one monotonic index answers
        // "which period are we in?" in amortized O(1) — the equivalent of [SchedulerPlanner.allowedAt] for a
        // partition, without its per-step scan.
        var windowIndex = 0
        fun windowAt(t: Long): Int {
            while (windowIndex + 1 < windows.size && windows[windowIndex + 1].startMillis <= t) windowIndex++
            return windowIndex
        }
        // Bound the loop defensively: with positive spans this can't run away, but a degenerate zero
        // span (only possible if minima are clamped to 0) would otherwise spin. The cap SCALES with the
        // horizon span (~one chunk per 30 s) so a DISPLAY fill out to a distant focused week isn't clipped
        // the way a fixed 168h-sized cap would be — bounded by the absolute [MAX_SCHEDULE_PANELS] ceiling.
        val maxPanels = ((horizon - nowMillis) / 30_000L).coerceIn(1L, MAX_SCHEDULE_PANELS.toLong()).toInt()
        // Set when the walk runs out of anything that could distort the schedule — the reference's phase-2
        // condition. With screen breaks enabled the context never freezes inside the horizon, so it stays
        // false and the whole fill is phase 1.
        var frozen = false

        // --- phase 1 (`tests/test.py`): the disturbed part of the timeline ---
        while (cursor < horizon && index < maxPanels) {
            val here = windowAt(cursor)
            val allowedHere = accepted[here]
            val period = if (allowedHere.isNotEmpty()) planner.periodOf(allowedHere) else planner.minPeriodMillis

            // A committed block cannot be moved: the cursor walks OVER it, and the walk charges the whole span
            // to its task, so committed work counts exactly like auto-placed work.
            val block = futureBlocks.firstOrNull { it.startMillis <= cursor && cursor < it.endMillis }
            if (block != null) {
                walk.serve(block.taskId, (block.endMillis - cursor).toDouble())
                walk.relax(0.0, period, allowedHere) // no forgetting here: the block is not ours
                cursor = block.endMillis
                pending = null
                freeTail = false
                continue
            }

            // The next instant the context changes: the end of this period, or the next committed block.
            val nextBlock = futureBlocks.asSequence().map { it.startMillis }.filter { it > cursor }.minOrNull()
            val limit = listOfNotNull(windows[here].endMillis, nextBlock).minOrNull()?.takeIf { it < horizon }
            val fieldEnd = planner.fieldEndMillis
            if (limit == null && (fieldEnd == null || cursor >= fieldEnd)) {
                frozen = true // nothing left to disturb → phase 2
                break
            }
            val gap = limit?.minus(cursor)
            val insideBreak = covers(sideRegions, cursor)
            if (pending != null && !insideBreak && pending.first !in allowedHere) pending = null
            val resume = if (insideBreak) null else pending
            // Inside a screen break the suspended task must not be the one that fills it (PRD §15).
            val candidates = if (insideBreak) allowedHere.filter { it != pending?.first } else allowedHere
            // `tests/test.py`: a task is a candidate only while its minimum fits the gap ahead of it. The
            // boundary that decides is the next **cutting** one — a fixed block or a screen-zone edge, and
            // inside a break the break's own end. A screen break's START is deliberately NOT one (PRD §15: it
            // only suspends a chunk, which resumes on the far side with its minimum intact), which is exactly
            // where this parts company with the reference's uniform `next_boundary`. Inside a break the gap IS
            // what remains of it, so PRD §9's "never when its minimum exceeds the break's length" needs no
            // special case.
            val nextZoneEdge =
                noScreenRegions.asSequence()
                    .flatMap { sequenceOf(it.startEpochMillis, it.endEpochMillis) }
                    .filter { it > cursor }.minOrNull()
            val breakEnd = if (insideBreak) sideRegions.first { it.endEpochMillis > cursor }.endEpochMillis else null
            val fitGap = listOfNotNull(nextBlock, nextZoneEdge, breakEnd).minOrNull()?.minus(cursor)
            val fitting = candidates.filter { fitGap == null || (minimumMillisOf[it] ?: 0L) <= fitGap }
            // `tests/test.py` steady_cycle's "no unplaceable crumb", applied to the walk: minimum times are
            // authored in whole minutes, so anything shorter than one is not a slot, it is a seam — e.g. the
            // 20-second look-away, or what is left of a chunk when a break lands a few seconds before it ends.
            val crumb = gap != null && gap < MILLIS_PER_MINUTE

            if (crumb || (resume == null && fitting.isEmpty())) {
                if (gap == null) break
                val tail = generated.lastOrNull()
                if (!insideBreak && allowedHere.isNotEmpty() && freeTail && tail?.endEpochMillis == cursor) {
                    // A crumb too short for any minimum: the previous slot stretches over it (`free_tail`)
                    // rather than leaving a sliver the calendar cannot use. A screen break is never such a
                    // crumb — PRD §9/§15 already decided nobody whose minimum exceeds it may work there, so
                    // the surrounding task must not creep into it either.
                    generated[generated.size - 1] = tail.copy(endEpochMillis = limit)
                    walk.serve(tail.taskId, gap.toDouble())
                } else {
                    // Nothing may occupy this stretch at all — a no-screen span with no off-screen task, or a
                    // screen break nobody can work through. Leave it empty; it is idle time for every clock.
                    walk.idle()
                    freeTail = false
                }
                cursor = limit
                if (!insideBreak) pending = null
                continue
            }

            val taskId = resume?.first ?: walk.pick(fitting) ?: break
            val boost = planner.boostAt(taskId, cursor)
            var need =
                resume?.second
                    ?: walk.chunkMillis(taskId, fitting, boost, period).roundToLong().coerceAtLeast(1L)
            // PRD §10: a task whose continuous effort is still running at the now-line is scheduled for the
            // REMAINDER of its minimum, not a fresh one, so the block it merges into is exactly one minimum
            // long. Only the first chunk can have such an effort behind it, and only when the walk did not
            // already decide to give the task MORE than its minimum (a catch-up must never be shortened).
            if (resume == null && cursor == nowMillis && need <= (minimumMillisOf[taskId] ?: 0L)) {
                need = (scheduledSpanMinutes(working, taskId, nowMillis) * MILLIS_PER_MINUTE).coerceAtLeast(1L)
            }
            val end = minOf(cursor + need, limit ?: Long.MAX_VALUE, horizon)
            if (end <= cursor) break
            emit(taskId, cursor, end)
            val placed = end - cursor
            // Charged at the boosted rate: time won near an exclusion is a genuinely higher local share, not
            // a debt to be taken back once the field is gone.
            walk.serve(taskId, placed.toDouble(), boost)
            walk.relax(placed.toDouble(), period, allowedHere)
            if (!insideBreak) {
                // PRD §15: only a screen break suspends a chunk. A fixed panel or a screen-zone edge
                // truncates it instead (PRD §9/§10: the minimum IS cut there).
                pending =
                    if (placed < need && need - placed >= MILLIS_PER_MINUTE &&
                        limit != null && limit in breakStarts
                    ) {
                        taskId to (need - placed)
                    } else {
                        null // the chunk is satisfied, or what is left of it is an unplaceable crumb
                    }
            }
            cursor = end
            freeTail = true
            index++
        }

        // --- phase 2 (`tests/test.py`): the context is frozen forever, so settle what is still owed and then
        // attach the analytic cycle — the "list of rules + repeat" of `tests/README.md` — unrolled out to the
        // horizon. Its shares are exact by construction, unlike the greedy's asymptotic ones. With screen
        // breaks enabled the context never freezes inside the horizon, so this simply never runs.
        if (frozen && cursor < horizon && index < maxPanels) {
            val allowedHere = accepted[windowAt(cursor)]
            if (allowedHere.isNotEmpty()) {
                val period = planner.periodOf(allowedHere)
                val settleEnd = minOf(cursor + (SchedulerPlanner.SETTLE_PERIODS * period).roundToLong(), horizon)
                while (cursor < settleEnd && index < maxPanels && walk.spread(allowedHere) > period) {
                    val name = walk.pick(allowedHere) ?: break
                    val boost = planner.boostAt(name, cursor)
                    val need = walk.chunkMillis(name, allowedHere, boost, period).roundToLong().coerceAtLeast(1L)
                    val end = minOf(cursor + need, horizon)
                    if (end <= cursor) break
                    emit(name, cursor, end)
                    walk.serve(name, (end - cursor).toDouble(), boost)
                    walk.relax((end - cursor).toDouble(), period, allowedHere)
                    cursor = end
                    index++
                }
                var cycle =
                    planner.steadyCycle(allowedHere)
                        .let { if (it.size > SchedulerPlanner.MAX_RULES) planner.coarseCycle(allowedHere) else it }
                        .filter { it.taskId != null && it.durationMillis > 0L }
                // No same-task seam with what the prefix just placed (`tests/test.py` `_tidy`).
                var rotations = cycle.size
                while (rotations-- > 0 && cycle.size > 1 && cycle.first().taskId == generated.lastOrNull()?.taskId) {
                    cycle = cycle.drop(1) + cycle.first()
                }
                var slotIndex = 0
                while (cursor < horizon && index < maxPanels && cycle.isNotEmpty()) {
                    val slot = cycle[slotIndex++ % cycle.size]
                    val end = minOf(cursor + slot.durationMillis, horizon)
                    if (end <= cursor) break
                    emit(slot.taskId!!, cursor, end)
                    cursor = end
                    index++
                }
            }
        }
        // PRD §9: two consecutive auto panels of the same task merge into one block. Screen-break and sleep
        // panels are added as-is (they split the run, so adjacent same-task pieces don't touch and stay apart).
        return (kept + sidePanels + sleepPanels + mergeSameTaskPanels(generated)).sortedBy { it.startEpochMillis }
    }

    /**
     * PRD §9 / CLAUDE.md trigger rule: **everything the plan is a function of, except `now`.**
     *
     * The scheduler is re-run only when this value changes — i.e. only when the user (or, through a pulled
     * remote snapshot, another device's user) changed something that can change the scheduling rules. Time
     * passing is deliberately NOT in it: the plan is a function from an instant to a task
     * (`tests/README.md`), so advancing the now-line only *consumes* it, and materializing more of its tail
     * is an extension ([fillSchedule]'s `keepExistingUntilMillis`), never a re-plan.
     *
     * What is in it: the tree that carries the priorities (lists, cells, weights), each schedulable leaf's
     * scheduling attributes (title, minimum time, on/off-screen, doable-during-break), the panels the fill
     * treats as input rather than output (pinned/user blocks, no-screen and inactivity periods — anything
     * [isRegeneratedPanel] says is NOT regenerated), the sleep schedule, the screen-break configuration and
     * anchors, and the PRD §7 automatic-schedule switch.
     *
     * What is deliberately NOT in it:
     * - the regenerated panels themselves — including them would make every fill re-trigger the next one;
     * - the **records**, which the schedule-advance banks continuously as auto panels elapse (CLAUDE.md's
     *   reconstructibility rule puts them on the derived side). A record edit that IS user-authored
     *   (`RemoveRecordPeriod`) therefore refills inside its own reducer instead of through this signature.
     */
    fun schedulingSignature(state: SchedulerState): Int {
        var result = if (state.automaticSchedule) 1 else 0
        result = 31 * result + state.sleep.hashCode()
        result = 31 * result + state.screenBreaks.hashCode()
        for (list in state.lists.values.sortedBy { it.id.value }) {
            result = 31 * result + list.id.value.hashCode()
            result = 31 * result + list.cellIds.hashCode()
            result = 31 * result + list.weightColumns.hashCode()
        }
        for (cell in state.cells.values.sortedBy { it.id.value }) {
            result = 31 * result + cell.id.value.hashCode()
            result = 31 * result + (cell.taskId?.value?.hashCode() ?: 0)
            result = 31 * result + cell.priorityWeights.hashCode()
        }
        for (task in state.tasks.values.sortedBy { it.id.value }) {
            result = 31 * result + task.id.value.hashCode()
            result = 31 * result + task.title.hashCode()
            result = 31 * result + task.minimumMinutes
            result = 31 * result + (if (task.onScreen) 1 else 0)
            result = 31 * result + (if (task.doableDuringBreak) 1 else 0)
        }
        for (panel in state.panels.filterNot(::isRegeneratedPanel).sortedBy { it.id }) {
            result = 31 * result + panel.id.hashCode()
            result = 31 * result + (panel.taskId?.value?.hashCode() ?: 0)
            result = 31 * result + panel.startEpochMillis.hashCode()
            result = 31 * result + panel.endEpochMillis.hashCode()
            result = 31 * result + (if (panel.noScreen) 1 else 0)
            result = 31 * result + (if (panel.inactivity) 1 else 0)
            result = 31 * result + (if (panel.pinned) 1 else 0)
        }
        return result
    }

    /**
     * Absolute ceiling on auto panels (pre-merge chunks) one fill can lay down. The live cap scales with the
     * fill horizon (~one chunk per 30 s of span — ≈ 20k over the standard 168h, as before), so a display fill
     * out to a distant focused week isn't clipped; this ceiling only guards a degenerate near-zero span.
     */
    private const val MAX_SCHEDULE_PANELS = 2_000_000

    // ----- PRD §14 Reminders scheduler --------------------------------------------------------

    /** PRD §14: reminder tags are generated this far ahead of the anchor day (a fixed 4-week horizon). */
    const val CHORE_HORIZON_DAYS: Int = 28

    private const val MILLIS_PER_DAY: Long = 24L * 60 * 60 * 1000

    /**
     * PRD §14: the day offsets (from today, day 0) on which a chore recurring every [spanDays] lands, out to
     * [horizonDays].
     *
     * - **`spanDays ≤ 0`** (blank / no recurrence): a one-off — just `[0]` (today).
     * - **`0 < spanDays < 1`**: a daily reminder — every day `[0, 1, …, horizonDays]`.
     * - **`spanDays ≥ 1`**: a day cadence. The accumulated counter is anchored at [anchorOffset] (day 0 =
     *   today; **negative** = a past completion, e.g. a checked reminder acting as the §14 tie-breaker); each
     *   subsequent iteration adds [spanDays] and the closest integer to the running sum is the chosen day, so
     *   a fractional cadence lands an exact `numerator` occurrences per `denominator`-day window without
     *   drifting (e.g. `31/21` ≈ 1.476 → 0, 1, 3, 4, 6, … = 21 days out of every 31). Occurrences that already
     *   fell in the past (a day before today) are dropped — the future tags resume the cadence from the
     *   anchor, so a weekly reminder last done on a Monday recurs on Mondays, not from today. With the default
     *   `anchorOffset = 0` (no prior completion) the counter starts at today and offset 0 is always included.
     */
    fun choreOccurrenceDayOffsets(
        spanDays: Double,
        horizonDays: Int = CHORE_HORIZON_DAYS,
        anchorOffset: Int = 0,
    ): List<Int> {
        if (spanDays <= 0.0) return listOf(0)
        if (spanDays < 1.0) return (0..horizonDays).toList()
        val offsets = mutableListOf<Int>()
        // Walk the cadence from the anchor; a negative anchor needs extra steps to first reach today.
        val maxK = horizonDays + (if (anchorOffset < 0) -anchorOffset else 0) + 2
        var k = 0
        while (k <= maxK) {
            val day = anchorOffset + (k * spanDays).roundToInt()
            k++
            if (day < 0) continue // a missed occurrence before today: not regenerated as a future tag
            if (day > horizonDays) break
            if (offsets.isEmpty() || day != offsets.last()) offsets.add(day)
        }
        return offsets
    }

    /**
     * PRD §14 "constrained in": the day offsets of a reminder of cadence [spanDays] that may only occur on
     * the days its constraining reminder occurs ([constrainingDays]). Each constraining day is taken in turn;
     * the reminder is placed there when its running due-date (advanced by [spanDays] per placement) has been
     * reached, so over time it averages its own cadence yet never lands off a constraining day. When its
     * cadence is *shorter* than the constraining reminder's it simply lands on every constraining day (capped
     * at that rate); a one-off (`spanDays ≤ 0`) lands on the first constraining day only. The phase rides the
     * constraining schedule (the first placement is the first constraining day), so the constrained reminder
     * has no independent anchor — it exists only alongside its constraint.
     */
    fun constrainedOccurrenceOffsets(
        spanDays: Double,
        constrainingDays: List<Int>,
        horizonDays: Int = CHORE_HORIZON_DAYS,
    ): List<Int> {
        val days = constrainingDays.filter { it in 0..horizonDays }.distinct().sorted()
        if (days.isEmpty()) return emptyList()
        if (spanDays <= 0.0) return listOf(days.first())
        val result = mutableListOf<Int>()
        var due = Double.NEGATIVE_INFINITY // first eligible constraining day is always placed
        for (d in days) {
            if (d >= due) {
                result.add(d)
                due = (if (due.isInfinite()) d.toDouble() else due) + spanDays
            }
        }
        return result
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
     * `start == end` (no spanning time), with deterministic `chore/{reminderId}/{offset}` ids (keyed by
     * the reminder's stable id, not the row index) so a steady regeneration reproduces them. They start un-[TaskPanel.checked]. Overlapping tags keep the default
     * layout weight, so the calendar splits their shared width evenly (PRD §14).
     *
     * [anchorMillisByReminderId] supplies, per reminder id ([ChoreEntry.id]), the epoch-millis of the most
     * recent **checked** occurrence of that reminder (PRD §14 tie-breaker). When present, the cadence is
     * anchored at that completion's day instead of today, so a reminder last done on a Monday recurs on
     * Mondays (see [choreOccurrenceDayOffsets]). Reminders with no past completion fall back to today.
     */
    fun choreScheduledPanels(
        chores: List<ChoreEntry>,
        todayStartMillis: Long,
        horizonDays: Int = CHORE_HORIZON_DAYS,
        nowMillis: Long = todayStartMillis,
        anchorMillisByReminderId: Map<String, Long> = emptyMap(),
    ): List<TaskPanel> {
        val currentTimeOfDayMinutes =
            ((nowMillis - todayStartMillis) / MILLIS_PER_MINUTE).toInt().coerceIn(0, 24 * 60 - 1)
        val result = mutableListOf<TaskPanel>()
        // A tag id needs a stable reminder id; fill any blank one defensively so direct callers need not.
        val withIds = assignReminderIds(chores)
        val byId = withIds.associateBy { it.id }
        fun anchorOffsetFor(id: String): Int =
            anchorMillisByReminderId[id]?.let { (it - todayStartMillis).floorDiv(MILLIS_PER_DAY).toInt() } ?: 0
        // PRD §14 "constrained in": the day offsets a reminder actually occupies. Unconstrained → its own
        // cadence (anchored at its last completion). Constrained → snapped onto the days its constraining
        // reminder occupies (resolved recursively; [visiting] breaks any constraint cycle, and a blank,
        // self, missing or blank-titled target falls back to the unconstrained cadence).
        fun effectiveOffsets(chore: ChoreEntry, visiting: Set<String>): List<Int> {
            val targetId = chore.constrainedToReminderId
            val target = byId[targetId]
                ?.takeIf { it.title.isNotBlank() && it.id != chore.id && it.id !in visiting }
            if (targetId.isBlank() || target == null) {
                return choreOccurrenceDayOffsets(chore.spanDays, horizonDays, anchorOffsetFor(chore.id))
            }
            val constrainingDays = effectiveOffsets(target, visiting + chore.id)
            return constrainedOccurrenceOffsets(chore.spanDays, constrainingDays, horizonDays)
        }
        withIds.forEach { chore ->
            // PRD §14: a tag is keyed by its reminder's *stable id*, not the row index, so checked tags
            // survive row reorders/detach (see [regenerateChorePanels]). Skip blank-title / id-less rows.
            if (chore.title.isBlank() || chore.id.isBlank()) return@forEach
            // PRD §14: "the defined time in the day, or the current time if not defined in the field".
            val minutes = if (chore.timeOfDayMinutes < 0) currentTimeOfDayMinutes else chore.timeOfDayMinutes
            val timeOfDay = minutes.coerceIn(0, 24 * 60 - 1) * MILLIS_PER_MINUTE
            for (offset in effectiveOffsets(chore, emptySet())) {
                val start = todayStartMillis + offset * MILLIS_PER_DAY + timeOfDay
                result.add(
                    TaskPanel(
                        id = "chore/${chore.id}/$offset",
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
     * A reminder tag the user placed by hand survives the regeneration while it is **checked or pinned**
     * (mirroring the old chore pin behaviour): a freshly generated tag whose deterministic id matches one of
     * those kept tags is dropped in its favour, so its checked/pinned state is preserved.
     */
    fun regenerateChorePanels(
        panels: List<TaskPanel>,
        chores: List<ChoreEntry>,
        todayStartMillis: Long,
        horizonDays: Int = CHORE_HORIZON_DAYS,
        nowMillis: Long = todayStartMillis,
    ): List<TaskPanel> {
        val withIds = assignReminderIds(chores)
        val nonChore = panels.filter { !it.chore }
        // PRD §14: a **checked** (a completion record) or **pinned** reminder tag is a persistent reference
        // keyed by its reminder's stable id — it survives regeneration AND row edits/reorder/detach (manual
        // "add reminder" tags and previously-checked generated tags alike). Keep them all verbatim; they are
        // the only persistent reminder references besides the manager rows.
        val kept = panels.filter { it.chore && (it.checked || it.pinned) }
        val keptIds = kept.mapTo(HashSet()) { it.id }
        // PRD §14 tie-breaker: anchor each reminder's cadence at its most recent **checked** occurrence (a
        // completion), so the recurrence lines up with past completions (a weekly reminder checked on a
        // Monday recurs on Mondays). A pinned-but-unchecked tag is a placement, not a completion — it does
        // not anchor.
        val anchorMillisByReminderId = HashMap<String, Long>()
        for (p in kept) {
            if (!p.checked) continue
            val rid = reminderIdOfChorePanel(p.id) ?: continue
            val existing = anchorMillisByReminderId[rid]
            if (existing == null || p.startEpochMillis > existing) anchorMillisByReminderId[rid] = p.startEpochMillis
        }
        // Fresh tags for the current rows, skipping occurrences already kept. Chore panels that are not
        // reproduced here (stale generated tags, or orphans whose reminder is no longer a row and is neither
        // checked nor pinned) are dropped — an id referenced by nothing ceases to exist (PRD §14 GC).
        val generated =
            choreScheduledPanels(withIds, todayStartMillis, horizonDays, nowMillis, anchorMillisByReminderId)
                .filter { it.id !in keptIds }
        return nonChore + kept + generated
    }

    /** PRD §14: id prefix marking a reminder added by hand ("add reminder"), of the form
     * `chore-manual/{reminderId}/{uniqueSuffix}`. These survive reminder regeneration (unlike generated tags). */
    const val MANUAL_REMINDER_PREFIX = "chore-manual/"

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
        // "already in the sub-list": the same task can't appear twice in the cell's own list.
        if (taskId in siblingTaskIds(state, cellId)) return false
        // "parents set": assigning a task whose sub-tree shares any task with an ancestor's sub-tree
        // would duplicate that task within a single non-root sub-tree (subsumes the ancestor/cycle case).
        val collisionScope = assignCollisionScope(state, cellId)
        if (structuralSubtreeTaskIds(state, taskId, excludeCellId = cellId).any { it in collisionScope }) {
            return false
        }
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
     * Hides tasks already in the cell's list ("sub-list") and tasks that would duplicate a task within a
     * non-root sub-tree (the "parents set" / shared-descendant rule, see [assignCollisionScope]); sorts
     * by path length, path label, child titles (PRD §4).
     */
    fun eligibleAssignTaskIds(
        state: SchedulerState,
        cellId: CellId,
        text: String,
        /** Draft task created while "New task" is selected; already represented by that menu row. */
        excludeTaskId: TaskId? = null,
    ): List<TaskId> {
        val siblings = siblingTaskIds(state, cellId)
        val collisionScope = assignCollisionScope(state, cellId)
        return matchingUserTaskIds(state, text, excludeTaskId).filter { candidate ->
            candidate !in siblings &&
                structuralSubtreeTaskIds(state, candidate, excludeCellId = cellId).none { it in collisionScope }
        }
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
    /**
     * PRD §8 calendar edit window: a task is a valid panel target only when it is a **leaf** (the calendar
     * schedules leaves, never a parent) **and still lives in the tree** (some cell points at it). The
     * latter excludes a *tombstone* — a task with no cell, kept alive only to keep its calendar panels /
     * records labelled (see [purgeOrphanTasks] and the reducer's tombstone handling). A tombstone can't be
     * scheduled (it has no cell, so [schedulableLeaves] skips it) and the user has removed it from the tree,
     * so it must not be offered back as a reusable task in the title / id menus.
     */
    private fun isCalendarPanelTarget(state: SchedulerState, taskId: TaskId): Boolean =
        isLeafTask(state, taskId) && taskHasCells(state, taskId)

    fun calendarTaskMenuEntries(
        state: SchedulerState,
        draftText: String,
        excludeTaskId: TaskId? = null,
    ): List<ChangeTaskMenuEntry> {
        val matching = matchingUserTaskIds(state, draftText, excludeTaskId).filter { isCalendarPanelTarget(state, it) }
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
            state.titleToTaskIds[title].orEmpty().any { isCalendarPanelTarget(state, it) }
        }

    /** PRD §8 calendar edit window: the leaf task to assign when a title suggestion is chosen, if any. */
    fun calendarTaskIdForTitle(state: SchedulerState, title: String): TaskId? =
        state.titleToTaskIds[title].orEmpty().firstOrNull { isCalendarPanelTarget(state, it) }

    /** PRD §14: a reminder choice for the "add a checked reminder" id menu — its stable id and title. */
    data class ReminderMenuEntry(val id: String, val title: String)

    /**
     * PRD §14: ensure every reminder has a stable, unique [ChoreEntry.id]. Blank ids (legacy entries, or a
     * row freshly added in the manager) are filled with `reminder-{n}` using the smallest free numbers,
     * leaving existing ids untouched. Run on load and whenever the reminders list is set.
     */
    fun assignReminderIds(entries: List<ChoreEntry>): List<ChoreEntry> {
        val used = entries.map { it.id }.filterTo(mutableSetOf()) { it.isNotBlank() }
        var counter = 0
        fun nextId(): String {
            while (used.contains("reminder-$counter")) counter++
            val id = "reminder-$counter"
            used.add(id)
            counter++
            return id
        }
        return entries.map { if (it.id.isBlank()) it.copy(id = nextId()) else it }
    }

    /**
     * PRD §14: the reminder id encoded in a manually-added "add a checked reminder" panel id
     * (`chore-manual/{reminderId}/{suffix}`). Blank for a brand-new (id-less) reminder → null.
     */
    private fun reminderIdOfManualPanel(panelId: String): String? =
        panelId.removePrefix(MANUAL_REMINDER_PREFIX).substringBeforeLast('/').ifBlank { null }

    /**
     * PRD §14: the **stable reminder id** of any chore tag — a manually-added `chore-manual/{reminderId}/{suffix}`
     * panel (via [reminderIdOfManualPanel]) or a generated `chore/{reminderId}/{offset}` panel — read straight
     * from the id segment (no row-index lookup, so it is stable across reorders/detach). Null when the id
     * carries no reminder id.
     */
    private fun reminderIdOfChorePanel(panelId: String): String? = when {
        panelId.startsWith(MANUAL_REMINDER_PREFIX) -> reminderIdOfManualPanel(panelId)
        panelId.startsWith("chore/") -> panelId.removePrefix("chore/").substringBefore('/').ifBlank { null }
        else -> null
    }

    /** PRD §14: the set of reminder ids that have at least one **checked** tag (a completion record). */
    fun checkedReminderIds(state: SchedulerState): Set<String> =
        state.panels.asSequence()
            .filter { it.chore && it.checked }
            .mapNotNull { reminderIdOfChorePanel(it.id) }
            .toSet()

    /**
     * PRD §14: reminder ids kept alive by a calendar tag the user placed by hand — a **checked** tag (a
     * completion) or a **pinned** tag (a placement). These are the persistent references besides the manager
     * rows: such an id survives a row being detached/removed and stays selectable in the id menus.
     */
    fun referencedReminderIds(state: SchedulerState): Set<String> =
        state.panels.asSequence()
            .filter { it.chore && (it.checked || it.pinned) }
            .mapNotNull { reminderIdOfChorePanel(it.id) }
            .toSet()

    /**
     * PRD §14: mint a stable `reminder-{n}` id that is not used by any manager reminder
     * ([SchedulerState.chores]) nor encoded in any existing manually-added "add a checked reminder" panel.
     * "Add a checked reminder" calls this when the user records a brand-new reminder (no id picked) so the
     * tag carries a real identity and shows up in the reminder id menu — a blank id is decoded as `null` by
     * [reminderIdOfManualPanel] and dropped from [allReminderEntries], so it would never be selectable.
     */
    fun freshReminderId(state: SchedulerState): String {
        val used = HashSet<String>()
        for (chore in state.chores) if (chore.id.isNotBlank()) used.add(chore.id)
        // Dodge every reminder id kept alive by a checked or pinned tag, so a brand-new reminder never
        // collides with an existing completion record or pinned placement.
        used.addAll(referencedReminderIds(state))
        var n = 0
        while (used.contains("reminder-$n")) n++
        return "reminder-$n"
    }

    /**
     * PRD §14: every **referenced** reminder identity (stable id + title) — the reminders configured in the
     * reminders manager ([SchedulerState.chores]) plus those referenced *only* by a **checked or pinned** tag
     * on the calendar (a manually-added "add reminder", or a generated tag the user checked, whose row was
     * since removed/detached). A plain unchecked, unpinned generated tag is not a reference (it regenerates
     * from its row), so it is not listed. A manager reminder wins when the same id appears in both; manager
     * reminders come first.
     */
    fun allReminderEntries(state: SchedulerState): List<ReminderMenuEntry> {
        val byId = LinkedHashMap<String, String>()
        for (chore in state.chores) {
            if (chore.id.isNotBlank() && chore.title.isNotBlank() && !byId.containsKey(chore.id)) {
                byId[chore.id] = chore.title
            }
        }
        for (panel in state.panels) {
            if (!panel.chore || !(panel.checked || panel.pinned) || panel.title.isBlank()) continue
            val rid = reminderIdOfChorePanel(panel.id) ?: continue
            if (!byId.containsKey(rid)) byId[rid] = panel.title
        }
        return byId.map { (id, title) -> ReminderMenuEntry(id, title) }
    }

    /**
     * PRD §14 reminder id menu: known reminders ([allReminderEntries]) whose title **exactly** (case-
     * insensitively) matches [draftText]. Empty text matches nothing — mirroring the task Change Task menu
     * ([matchingUserTaskIds], PRD §4): the id menu only offers reusing a reminder whose title IS the typed
     * text, so a partial draft ("y" vs "yoga") never surfaces a longer reminder and an empty field shows no
     * id menu at all. (Partial-as-you-type matches are the *title suggestion* menu's job, not the id menu's.)
     */
    fun reminderMenuEntries(state: SchedulerState, draftText: String): List<ReminderMenuEntry> {
        val q = draftText.trim()
        if (q.isEmpty()) return emptyList()
        return allReminderEntries(state).filter { it.title.equals(q, ignoreCase = true) }
    }

    /** PRD §14 reminder title-suggestion menu: distinct reminder titles matching [input]. */
    fun reminderTitleSuggestions(state: SchedulerState, input: String): List<String> {
        val q = input.trim()
        return allReminderEntries(state).map { it.title }.distinct()
            .filter { q.isBlank() || it.contains(q, ignoreCase = true) }
    }

    /** PRD §14: the reminder id of the first known reminder with this exact [title], if any. */
    fun reminderIdForTitle(state: SchedulerState, title: String): String? =
        allReminderEntries(state).firstOrNull { it.title == title }?.id

    /** PRD §14: the title of the known reminder with this [id], if any. */
    fun reminderTitleForId(state: SchedulerState, id: String): String? =
        allReminderEntries(state).firstOrNull { it.id == id }?.title

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

    /**
     * PRD §4: drop every cell, sublist, and task of a subtree *detached from the root* — the children left
     * dangling under a cell that was emptied (in this model only a titled cell owns a child sublist, so an
     * emptied parent's subtree is no longer part of the tree). Without this, those orphaned cells keep their
     * tasks alive, so a "removed" task id still surfaces in the title / id suggestion menus.
     *
     * Reachability walks the tree from the root list, descending into a cell's child sublist only when its
     * task has a **non-blank** title. Whatever it doesn't reach is detached and removed, then [purgeOrphanTasks]
     * drops the now cell-less tasks. Call only at committed edit boundaries ([evaluatePostEditCleanup] — exit
     * edit / empty selection / move), never mid-keystroke, so a transient blank while renaming a parent (which
     * the live tree shows between keystrokes) does not delete its children.
     */
    fun pruneDetachedTree(state: SchedulerState): SchedulerState {
        val reachableCells = mutableSetOf<CellId>()
        val reachableLists = mutableSetOf<CellListId>()
        val queue = ArrayDeque(listOf(state.rootListId))
        while (queue.isNotEmpty()) {
            val listId = queue.removeFirst()
            if (!reachableLists.add(listId)) continue
            val list = state.lists[listId] ?: continue
            for (cellId in list.cellIds) {
                reachableCells.add(cellId)
                val task = state.cells[cellId]?.taskId?.let { state.tasks[it] } ?: continue
                if (task.title.isNotBlank()) task.childListId?.let { queue.add(it) }
            }
        }
        // Nothing detached → only the regular orphan-task purge is needed (and the common case stays cheap).
        if (reachableCells.size == state.cells.size && reachableLists.size == state.lists.size) {
            return purgeOrphanTasks(state)
        }
        val cells = state.cells.filterKeys { it in reachableCells }
        val lists = state.lists.filterKeys { it in reachableLists }
        val tasks =
            state.tasks.mapValues { (_, task) ->
                task.copy(
                    occurrences = task.occurrences.filter { it in reachableCells },
                    childListId = task.childListId?.takeIf { it in reachableLists },
                )
            }
        val expanded = state.expanded.filterTo(mutableSetOf()) { it in reachableCells }
        return purgeOrphanTasks(state.copy(cells = cells, lists = lists, tasks = tasks, expanded = expanded))
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
     *
     * PRD §13 "copy" / "deep copy" additionally carry everything the cell's Edit window holds:
     * [noScreenDoable] (the switch — the task is done away from a screen, i.e. `!Task.onScreen`),
     * [scheduleUnit] and [text]. All three default to the *empty* value, which is also what a task the
     * clipboard said nothing about keeps, so they round-trip exactly and never need a null.
     */
    data class CopiedNode(
        val title: String,
        val children: List<CopiedNode>,
        val rowWeights: List<Double> = listOf(1.0),
        val childHeader: List<Double> = listOf(1.0),
        val minMinutes: Int? = null,
        val noScreenDoable: Boolean = false,
        val scheduleUnit: List<ScheduleUnitEntry> = emptyList(),
        val text: String = "",
    )

    /** PRD §4 default priority-weight row/column header — omitted from the serialized text. */
    private val DEFAULT_WEIGHTS: List<Double> = listOf(1.0)

    /**
     * PRD §4 separator between the tree section and the trailing min-time appendix. A lone form-feed
     * line: fields escape `\f` (see [escapeField]) so real content can never be mistaken for it.
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
        return CopiedNode(
            title = title,
            children = children,
            rowWeights = rowWeights,
            childHeader = childHeader,
            minMinutes = task?.minimumMinutes,
            noScreenDoable = task?.onScreen == false,
            scheduleUnit = task?.scheduleUnit.orEmpty(),
            text = task?.text.orEmpty(),
        )
    }

    /**
     * PRD §13 cell contextual menu "copy" / "deep copy": the cell's own task serialized to the same text
     * [copyTreeText] produces — so it pastes back (Ctrl+V) with its schedule unit, its text, its no-screen
     * switch, its minimum time and its weight row restored. [deep] keeps the subtree beneath the cell;
     * otherwise only the cell itself is copied. Empty when [cellId] holds no titled task.
     */
    fun copyCellText(state: SchedulerState, cellId: CellId, deep: Boolean): String {
        if (!isPopulated(state, cellId)) return ""
        val node = copiedSubtree(state, cellId)
        return renderCopiedNodes(listOf(if (deep) node else node.copy(children = emptyList())))
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
        return renderCopiedNodes(nodes)
    }

    /**
     * Serialize a copied forest to the clipboard text described on [copyTreeText] and [parseTreeText]:
     * the tab-indented tree, then three [COPY_SECTION_SEPARATOR]-delimited appendices keyed by task title
     * — minimum times, schedule units, task texts. The last two are always emitted (possibly empty) so a
     * section's meaning is its position; a payload that stops after the min-time appendix is the older
     * shape and still parses.
     */
    private fun renderCopiedNodes(nodes: List<CopiedNode>): String {
        val sb = StringBuilder()
        fun render(ns: List<CopiedNode>, depth: Int) {
            for (n in ns) {
                repeat(depth) { sb.append('\t') }
                sb.append(escapeField(n.title))
                if (n.rowWeights != DEFAULT_WEIGHTS) sb.append('\t').append("w=").append(formatWeights(n.rowWeights))
                if (n.children.isNotEmpty() && n.childHeader != DEFAULT_WEIGHTS) {
                    sb.append('\t').append("h=").append(formatWeights(n.childHeader))
                }
                // PRD §13 Edit window switch. Only the non-default (off-screen) value is written, so an
                // ordinary on-screen task's line is byte-for-byte what it always was.
                if (n.noScreenDoable) sb.append('\t').append("ns=1")
                sb.append('\n')
                render(n.children, depth + 1)
            }
        }
        render(nodes, 0)

        // The appendices are keyed by title and collected in first-appearance order, so a task mirrored
        // at several cells contributes once.
        val minByTitle = LinkedHashMap<String, Int>()
        val unitByTitle = LinkedHashMap<String, List<ScheduleUnitEntry>>()
        val textByTitle = LinkedHashMap<String, String>()
        fun collect(ns: List<CopiedNode>) {
            for (n in ns) {
                if (n.title !in minByTitle) minByTitle[n.title] = n.minMinutes ?: DEFAULT_MINIMUM_MINUTES
                if (n.scheduleUnit.isNotEmpty() && n.title !in unitByTitle) unitByTitle[n.title] = n.scheduleUnit
                if (n.text.isNotEmpty() && n.title !in textByTitle) textByTitle[n.title] = n.text
                collect(n.children)
            }
        }
        collect(nodes)

        // PRD §4 appendix 1: the minimum time of each distinct task.
        sb.append(COPY_SECTION_SEPARATOR).append('\n')
        sb.append(minByTitle.entries.joinToString("\n") { "${escapeField(it.key)}\t${it.value}" })
        // PRD §13 appendix 2: one line per schedule-unit step, `<task>\t<step title>\t<minutes>`, in order.
        sb.append('\n').append(COPY_SECTION_SEPARATOR).append('\n')
        sb.append(
            unitByTitle.entries.joinToString("\n") { (title, entries) ->
                entries.joinToString("\n") { "${escapeField(title)}\t${escapeField(it.title)}\t${it.spanMinutes}" }
            },
        )
        // PRD §13 appendix 3: the task text, escaped onto a single line.
        sb.append('\n').append(COPY_SECTION_SEPARATOR).append('\n')
        sb.append(textByTitle.entries.joinToString("\n") { "${escapeField(it.key)}\t${escapeField(it.value)}" })
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
        // Split on every separator: section 0 is the tree, then the min-time / schedule-unit / text
        // appendices. A payload written before the last two existed simply has fewer sections.
        val sections = ArrayList<List<String>>()
        var start = 0
        for (i in allLines.indices) {
            if (allLines[i] == COPY_SECTION_SEPARATOR) {
                sections.add(allLines.subList(start, i))
                start = i + 1
            }
        }
        sections.add(allLines.subList(start, allLines.size))
        val treeLines = sections[0]

        // Appendix 1: `<escaped title>\t<minutes>` per distinct task. A malformed line → not our format.
        val minByTitle = HashMap<String, Int>()
        for (line in sections.getOrElse(1) { emptyList() }) {
            if (line.isBlank()) continue
            val tab = line.indexOf('\t')
            if (tab < 0) return null
            val minutes = line.substring(tab + 1).toIntOrNull() ?: return null
            minByTitle[unescapeField(line.substring(0, tab))] = minutes
        }

        // Appendix 2 (PRD §13): `<escaped task>\t<escaped step title>\t<minutes>`, one line per step, in
        // order. A task with no schedule unit contributes no line and so keeps the empty default.
        val unitByTitle = HashMap<String, MutableList<ScheduleUnitEntry>>()
        for (line in sections.getOrElse(2) { emptyList() }) {
            if (line.isBlank()) continue
            val fields = line.split('\t')
            if (fields.size != 3) return null
            val span = fields[2].toIntOrNull() ?: return null
            unitByTitle.getOrPut(unescapeField(fields[0])) { ArrayList() }
                .add(ScheduleUnitEntry(unescapeField(fields[1]), span))
        }

        // Appendix 3 (PRD §13): `<escaped task>\t<escaped text>` — the text is escaped onto one line.
        val textByTitle = HashMap<String, String>()
        for (line in sections.getOrElse(3) { emptyList() }) {
            if (line.isBlank()) continue
            val tab = line.indexOf('\t')
            if (tab < 0) return null
            textByTitle[unescapeField(line.substring(0, tab))] = unescapeField(line.substring(tab + 1))
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
            var noScreenDoable = false
            for (field in fields.drop(1)) {
                when {
                    field.startsWith("w=") -> rowWeights = parseWeights(field.removePrefix("w=")) ?: return null
                    field.startsWith("h=") -> childHeader = parseWeights(field.removePrefix("h=")) ?: return null
                    field.startsWith("ns=") ->
                        noScreenDoable = when (field.removePrefix("ns=")) {
                            "1" -> true
                            "0" -> false
                            else -> return null
                        }
                    else -> return null // a real tab in content / unknown field → not our format
                }
            }
            entries.add(
                MutableCopiedNode(
                    title = unescapeField(fields[0]),
                    rowWeights = rowWeights,
                    childHeader = childHeader,
                    noScreenDoable = noScreenDoable,
                ),
            )
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
        return roots.map { it.toImmutable(minByTitle, unitByTitle, textByTitle) }
    }

    private class MutableCopiedNode(
        val title: String,
        val children: MutableList<MutableCopiedNode> = mutableListOf(),
        val rowWeights: List<Double> = listOf(1.0),
        val childHeader: List<Double> = listOf(1.0),
        val noScreenDoable: Boolean = false,
    ) {
        fun toImmutable(
            minByTitle: Map<String, Int>,
            unitByTitle: Map<String, List<ScheduleUnitEntry>>,
            textByTitle: Map<String, String>,
        ): CopiedNode =
            CopiedNode(
                title = title,
                children = children.map { it.toImmutable(minByTitle, unitByTitle, textByTitle) },
                rowWeights = rowWeights,
                childHeader = childHeader,
                minMinutes = minByTitle[title],
                noScreenDoable = noScreenDoable,
                scheduleUnit = unitByTitle[title].orEmpty(),
                text = textByTitle[title].orEmpty(),
            )
    }

    /** Escapes a tab-separated field (a title, a schedule-unit step name, a task text) onto one line. */
    private fun escapeField(s: String): String =
        buildString {
            for (c in s) when (c) {
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\t' -> append("\\t")
                '\u000C' -> append("\\f")
                else -> append(c)
            }
        }

    private fun unescapeField(s: String): String =
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
