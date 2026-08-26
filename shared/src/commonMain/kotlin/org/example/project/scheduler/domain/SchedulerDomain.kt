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
import org.example.project.scheduler.model.DefaultSubtreeNode
import org.example.project.scheduler.model.DEFAULT_MINIMUM_MINUTES
import org.example.project.scheduler.model.ForcedTaskStart
import org.example.project.scheduler.model.ForcedTaskSwitch
import org.example.project.scheduler.model.ScheduleUnitEntry
import org.example.project.scheduler.model.ScreenBreak
import org.example.project.scheduler.model.ScreenBreakPeriod
import org.example.project.scheduler.platform.DeviceKind
import org.example.project.scheduler.model.SleepSchedule
import org.example.project.scheduler.model.Task
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.model.TaskPanel
import org.example.project.scheduler.model.TaskTimeRange
import org.example.project.scheduler.model.TaskTreeId
import org.example.project.scheduler.model.WellKnownIds
import org.example.project.scheduler.state.CalendarEdge
import org.example.project.scheduler.state.SchedulerSelection
import org.example.project.scheduler.state.SchedulerState
import org.example.project.scheduler.state.TaskTreeEntry

object SchedulerDomain {
    fun isMainTask(taskId: TaskId?): Boolean = taskId == WellKnownIds.MAIN_TASK

    fun isRootTask(taskId: TaskId?): Boolean = taskId == WellKnownIds.ROOT_TASK

    /**
     * The id shape [SchedulerState.allocateTaskId] mints — the only one a clipboard payload may name
     * (ADR 0012), so a pasted id can never land on the tree's own root/main tasks or on an id the counter
     * will never walk past.
     */
    fun isUserTaskId(taskId: TaskId): Boolean = USER_TASK_ID_PATTERN.matches(taskId.value)

    private val USER_TASK_ID_PATTERN = Regex("""task/user/\d+""")

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
    internal fun isPopulatedCell(state: SchedulerState, cellId: CellId): Boolean {
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

    // ----- The task list ("All tasks") ---------------------------------------------------------

    /**
     * PRD §7 lateral menu ("All tasks"): the two figures that window may order its rows by.
     *
     * Both are readouts of the tree the user is editing, never of the scheduler's blended view — see
     * [TaskListEntry].
     */
    enum class TaskListSort {
        /** How many cells of the tree point at the task (a mirrored task counts once per cell). */
        Occurrences,

        /** The task's absolute priority share — the same number its rows show in the tree. */
        Priority,
    }

    /**
     * One row of the "All tasks" window: a task of the **live** tree, with the two figures it is sorted by.
     *
     * [priority] is [absoluteTaskPriorities], not [blendedTaskPriorities], for the same reason the tree's
     * own percentage column is: this window is a readout of the arrangement on screen, which is what the
     * user is editing — not of the keyframe blend the scheduler happens to be following right now.
     */
    data class TaskListEntry(
        val taskId: TaskId,
        val title: String,
        val occurrences: Int,
        val priority: Double,
    )

    /**
     * Every task the tree actually holds, ordered by [sort] — [descending] puts the largest figure at the
     * top (the window's "top to bottom"), otherwise the smallest leads ("bottom to top").
     *
     * The rows are the **populated** cells' tasks, counted off `state.cells` exactly as
     * [absoluteTaskPriorities] and [RelativePriority.occurrenceChains] count them, so the two columns can
     * never disagree about what an occurrence is. Consequences worth knowing: a task "deleted" by blanking
     * its title (PRD §4) is gone from the list even while its records keep it alive, and a *detached
     * parent* — titled, but with no cell pointing at it — is not listed either, since it is not in the tree.
     *
     * Ties fall back to the title and then the id so the order is total: without that, the many tasks
     * sharing 0 % (or one occurrence) would be free to shuffle between recompositions.
     */
    fun taskListEntries(
        state: SchedulerState,
        sort: TaskListSort = TaskListSort.Priority,
        descending: Boolean = true,
    ): List<TaskListEntry> {
        val priorities = absoluteTaskPriorities(state)
        val counts = HashMap<TaskId, Int>()
        for (cell in state.cells.values) {
            val taskId = cell.taskId ?: continue
            if (!isPopulatedCell(state, cell.id)) continue
            counts[taskId] = (counts[taskId] ?: 0) + 1
        }
        val entries = counts.map { (taskId, count) ->
            TaskListEntry(
                taskId = taskId,
                title = state.tasks[taskId]?.title.orEmpty(),
                occurrences = count,
                priority = priorities[taskId] ?: 0.0,
            )
        }
        val byFigure = when (sort) {
            TaskListSort.Occurrences ->
                if (descending) compareByDescending<TaskListEntry> { it.occurrences }
                else compareBy { it.occurrences }
            TaskListSort.Priority ->
                if (descending) compareByDescending<TaskListEntry> { it.priority }
                else compareBy { it.priority }
        }
        return entries.sortedWith(
            byFigure.thenBy { it.title.lowercase() }.thenBy { it.taskId.value },
        )
    }

    // ----- The task-tree timeline ("All task trees") -------------------------------------------

    /**
     * Where `now` sits on the task-tree timeline: between the keyframe [from] and the keyframe [to],
     * [fraction] of the way across (0 at [from]'s date, 1 at [to]'s).
     *
     * Outside the dated range the nearest keyframe holds, which is expressed as `from === to` with a
     * fraction of 0 — a *degenerate* blend, not a missing one. That distinction matters: it still means
     * "the scheduler follows the dated trees", it just happens to be following exactly one of them.
     */
    data class TaskTreeBlend(
        val from: TaskTreeEntry,
        val to: TaskTreeEntry,
        val fraction: Double,
    ) {
        /** True while `now` sits on a keyframe / outside the dated range, so nothing is being interpolated. */
        val isSingle: Boolean get() = from.id == to.id
    }

    /**
     * The trees on the timeline, in date order — the keyframes the scheduler blends between.
     *
     * The **active** tree's stored snapshot is stale by design (the live [SchedulerState] fields are the
     * truth for it, see [TaskTreeEntry]), so the state is flushed first: a keyframe that happens to be the
     * tree on screen must contribute what the user has actually got, not what it looked like when it was
     * last selected. Ties on the same instant fall back to the id, so the order is total and stable.
     */
    fun datedTaskTrees(state: SchedulerState): List<TaskTreeEntry> =
        state.withActiveTaskTreeFlushed().taskTrees
            .filter { it.dateMillis != null }
            .sortedWith(compareBy({ it.dateMillis }, { it.id.value }))

    /**
     * The blend in force at [nowMillis], or `null` when **no** tree is dated — in which case there is no
     * timeline at all and every priority question falls back to the live tree, exactly as before this
     * feature existed. That null is the "feature is off" signal every caller below keys on.
     */
    fun taskTreeBlendAt(state: SchedulerState, nowMillis: Long): TaskTreeBlend? {
        val dated = datedTaskTrees(state)
        if (dated.isEmpty()) return null
        val first = dated.first()
        if (nowMillis <= (first.dateMillis ?: 0L)) return TaskTreeBlend(first, first, 0.0)
        val last = dated.last()
        if (nowMillis >= (last.dateMillis ?: 0L)) return TaskTreeBlend(last, last, 0.0)
        for (i in 0 until dated.size - 1) {
            val a = dated[i]
            val b = dated[i + 1]
            val ta = a.dateMillis ?: continue
            val tb = b.dateMillis ?: continue
            if (nowMillis < ta || nowMillis > tb) continue
            // Two keyframes on the same instant have no span to cross: the earlier one (by the id
            // tie-break above) governs, rather than dividing by zero.
            val span = tb - ta
            return if (span <= 0L) TaskTreeBlend(a, a, 0.0)
            else TaskTreeBlend(a, b, (nowMillis - ta).toDouble() / span.toDouble())
        }
        return TaskTreeBlend(last, last, 0.0)
    }

    /** The absolute priorities the task tree [entry] defines on its own, as if it were the live tree. */
    fun taskTreePriorities(state: SchedulerState, entry: TaskTreeEntry): Map<TaskId, Double> =
        absoluteTaskPriorities(state.applyTreeWithRecords(entry.tree))

    /**
     * **The priorities the scheduler actually follows at [nowMillis]** — the whole point of the timeline.
     *
     * Between two keyframes each task's absolute priority moves linearly from the share its tree gives it
     * to the share the next tree gives it, so the plan transforms evenly and continuously from one
     * arrangement into the other rather than snapping over on the date. A task that exists in only one of
     * the two trees is treated as **0% in the other**, which is what makes a task fade out (or in) over the
     * transition instead of appearing at full share the instant its tree becomes current.
     *
     * Note this is deliberately NOT the priority the tree on screen shows: the displayed tree keeps
     * reporting its own [absoluteTaskPriorities], because that is the arrangement the user is editing. With
     * no dated tree at all, the two are the same thing.
     */
    fun blendedTaskPriorities(state: SchedulerState, nowMillis: Long): Map<TaskId, Double> {
        val blend = taskTreeBlendAt(state, nowMillis) ?: return absoluteTaskPriorities(state)
        val from = taskTreePriorities(state, blend.from)
        if (blend.isSingle) return from
        val to = taskTreePriorities(state, blend.to)
        val f = blend.fraction.coerceIn(0.0, 1.0)
        return (from.keys + to.keys).associateWith { id ->
            (1.0 - f) * (from[id] ?: 0.0) + f * (to[id] ?: 0.0)
        }
    }

    /**
     * The tasks the scheduler may place at [nowMillis]: the **union** of the two keyframes' schedulable
     * leaves. A task living only in the tree being transitioned *to* is schedulable as soon as its blended
     * share leaves zero — without that, a continuous priority transformation would still hand out a
     * discontinuous plan, since the task could not be placed until its tree became the live one.
     */
    fun blendedSchedulableLeaves(state: SchedulerState, nowMillis: Long): List<TaskId> {
        val blend = taskTreeBlendAt(state, nowMillis) ?: return schedulableLeaves(state)
        val from = schedulableLeaves(state.applyTreeWithRecords(blend.from.tree))
        if (blend.isSingle) return from
        return (from + schedulableLeaves(state.applyTreeWithRecords(blend.to.tree))).distinct()
    }

    /**
     * The task attributes the fill reads — title, minimum time, screen flags, records — widened to cover
     * the union above, since a leaf drawn from the other keyframe has no entry in the live `tasks` map and
     * would otherwise schedule as a nameless, zero-minimum task.
     *
     * The **live** map wins wherever a task exists in both: it is the freshest copy (a stored snapshot is
     * only as current as the last flush) and its records are the ones the advance has been banking. With
     * one exception — a **blank-titled tombstone**. Emptying a cell to delete a task leaves its id behind
     * with a blank title (kept alive by its panels/records, PRD §4/§8), so a task deleted from the live
     * tree but still alive in a keyframe would take its name from the tombstone and schedule as
     * "(untitled)". A tombstone carries no title precisely because it is *not* a task any more here, so the
     * keyframe's copy is the better answer.
     */
    fun blendedTaskAttributes(state: SchedulerState, nowMillis: Long): Map<TaskId, Task> {
        val blend = taskTreeBlendAt(state, nowMillis) ?: return state.tasks
        val merged = LinkedHashMap(state.tasks)
        for (entry in listOf(blend.from, blend.to)) {
            for ((id, task) in entry.tree.tasks) {
                val live = merged[id]
                if (live == null || (live.title.isBlank() && task.title.isNotBlank())) merged[id] = task
            }
        }
        return merged
    }

    /**
     * How finely the blend is quantized for re-planning purposes: the transition is cut into this many
     * steps, and the schedule is re-planned when the cursor crosses one.
     *
     * The plan is a step function of a continuously moving quantity, and something has to choose the step.
     * 100 bounds the priority error at 1% (a share moves by at most the whole gap between the two trees
     * across the transition, so by at most 1/100th of it per step) — below anything the fill's minimum-time
     * granularity can express — while keeping re-plans rare: a two-month transition re-plans about every
     * 14 hours, a one-day transition about every 14 minutes.
     */
    const val TASK_TREE_BLEND_STEPS: Int = 100

    /**
     * The quantized blend cursor at [nowMillis] — the trigger the engine watches. It changes only when the
     * bracketing pair changes or the interpolation crosses a step, so it is a *coarse* function of time
     * rather than a continuous one, and 0 whenever no tree is dated (the feature-off case, which must never
     * dispatch anything).
     */
    fun taskTreeBlendStep(state: SchedulerState, nowMillis: Long): Int {
        val blend = taskTreeBlendAt(state, nowMillis) ?: return 0
        var result = blend.from.id.value.hashCode()
        result = 31 * result + blend.to.id.value.hashCode()
        result = 31 * result + (blend.fraction.coerceIn(0.0, 1.0) * TASK_TREE_BLEND_STEPS).toInt()
        return result
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
     * PRD §4: a **detached parent** — a titled task no cell points at anymore that still owns a populated
     * sub-list. Re-pointing a cell at another task id does NOT delete the task it left: the sub-tree belongs
     * to the *task id*, not to the cell, so the task survives cell-less with its children and the Change Task
     * menu keeps offering it (with no path to show, it is labelled by its child titles — PRD §4
     * *Presentation*). Assigning that id back to any cell brings the whole sub-tree back, exactly as it
     * already does for a task that kept a second occurrence elsewhere.
     *
     * The escape hatch is the **title**: emptying a cell (PRD §4 *Deletion*) blanks its task's title, and a
     * blank-titled task is never detached-parent, so "delete" still deletes the sub-tree. That is also what
     * keeps a peer's deletion sticking through [org.example.project.scheduler.sync.SnapshotMerge] — the
     * merged task is either gone or blank-titled, never a retained parent.
     */
    fun isDetachedParentTask(state: SchedulerState, taskId: TaskId): Boolean =
        isDetachedParentTask(state, taskId, taskIdsWithCells(state))

    /** All task ids some cell points at — [taskHasCells] for every task at once, in one pass. */
    private fun taskIdsWithCells(state: SchedulerState): Set<TaskId> =
        state.cells.values.mapNotNullTo(HashSet()) { it.taskId }

    /**
     * [isDetachedParentTask] against a [taskIdsWithCells] set built once — the GC passes below ask it of every
     * task, and scanning the cells per task would make an ordinary edit O(tasks × cells).
     */
    private fun isDetachedParentTask(
        state: SchedulerState,
        taskId: TaskId,
        taskIdsWithCells: Set<TaskId>,
    ): Boolean {
        if (isRootTask(taskId) || isMainTask(taskId)) return false
        if (taskId in taskIdsWithCells) return false
        val task = state.tasks[taskId] ?: return false
        if (task.title.isBlank()) return false
        val list = task.childListId?.let { state.lists[it] } ?: return false
        return list.cellIds.any { state.cells[it]?.taskId != null }
    }

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
     * PRD §8 "∞" period bound: the instant a hand-added no-screen / inactivity period **open into the past**
     * begins at (1900-01-01T00:00Z), and [OPEN_FUTURE_MILLIS] the one an open-ended period runs to
     * (2200-01-01T00:00Z).
     *
     * A real instant rather than `Long.MIN_VALUE`/`MAX_VALUE` on purpose: every consumer of a panel's bounds
     * does ordinary arithmetic on them (`end - start`, `start + MIN_MANUAL_ENTRY_MILLIS`, the day clipping),
     * and a saturating sentinel would overflow the first of those. These two are far enough outside any
     * calendar the user can reach that the period covers "everything", and near enough that no sum overflows.
     * They are only ever *recognized* by [isOpenPast] / [isOpenFuture] — which is what makes the bubble and the
     * period editor print "∞" instead of a wall-clock time.
     */
    const val OPEN_PAST_MILLIS: Long = -2_208_988_800_000L

    /** PRD §8 "∞" period bound, the future side. See [OPEN_PAST_MILLIS]. */
    const val OPEN_FUTURE_MILLIS: Long = 7_258_118_400_000L

    /** PRD §8: true when [millis] is the open-into-the-past "∞" bound (or beyond it). */
    fun isOpenPast(millis: Long): Boolean = millis <= OPEN_PAST_MILLIS

    /** PRD §8: true when [millis] is the open-into-the-future "∞" bound (or beyond it). */
    fun isOpenFuture(millis: Long): Boolean = millis >= OPEN_FUTURE_MILLIS

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
     * PRD §15: the head of a [ScreenBreakPeriod.ClosedMinuteThenBreakDoable] break during which the period
     * accepts **no task at all** — the minute the user needs to actually get off the screen before anything
     * else may be scheduled. Clamped to the break's own length by the caller, so a debug-shortened pose is
     * closed end to end.
     *
     * Only the **5-minute** pose has one. The 20-second look-away is [ScreenBreakPeriod.Closed] (closed end to
     * end, so the head is moot) and the 15-minute pose is [ScreenBreakPeriod.OffScreenOnly] — fifteen minutes
     * is long enough that getting off the screen is not a step worth reserving a period for. See [fillSchedule].
     */
    const val SCREEN_BREAK_CLOSED_HEAD_MILLIS: Long = MILLIS_PER_MINUTE

    /**
     * PRD §15: how long a real no-screen pause must last to serve the 20-second look-away — its
     * [ScreenBreak.pauseThresholdMillis]. Deliberately **15 minutes**, the 15-min pose's own length, so the
     * longer pose serves the look-away through the ordinary "a pause at least this long anchors it" rule
     * ([seedScreenBreaksFromGaps]) and needs no rule of its own. The two SHORTER rests that also serve it are
     * not pauses at all and are handled where they happen: the look-away's own conducted occurrence
     * ([serveElapsedScreenBreaks]) and a 5-min pose break ([pastScreenBreaksFromPauses] → [serveShorterBreaks]).
     *
     * Before this the threshold was 0 — i.e. "as long as the break itself", a mere 20 seconds — which made
     * every brief away-from-the-desk moment restart the 20-minute clock.
     */
    const val LOOK_AWAY_QUALIFYING_PAUSE_MILLIS: Long = 15L * MILLIS_PER_MINUTE

    /**
     * PRD §15: where a screen break's **closed head** ends and its **open period** begins — the instant from
     * which the break stops accepting nobody and starts accepting the tasks that need no screen. `null` when
     * the break is closed end to end and so has no open part at all (the 20-second look-away, and any break
     * shortened by the debug knobs to no more than its own closed head).
     *
     * One function for the two readers that must never disagree about the shape of a break: the §9 fill,
     * which turns it into the periods it hands the walk ([fillSchedule]), and the calendar, which draws the
     * open part **hollow** so the off-screen work the break accepts is visible inside it rather than covered
     * by a solid band (`App.mergePanelsForDisplay`). The three shapes are `side-dev` test 11's periods: closed
     * end to end, a closed [SCREEN_BREAK_CLOSED_HEAD_MILLIS] then a break-doable tail, or open throughout.
     */
    fun screenBreakOpenStartMillis(shape: ScreenBreakPeriod, startMillis: Long, endMillis: Long): Long? {
        if (endMillis <= startMillis) return null
        val opens =
            when (shape) {
                ScreenBreakPeriod.Closed -> endMillis
                // Clamped to the break's own length, so a debug-retimed pose shorter than the head is closed
                // end to end rather than opening before it starts.
                ScreenBreakPeriod.ClosedMinuteThenBreakDoable ->
                    minOf(startMillis + SCREEN_BREAK_CLOSED_HEAD_MILLIS, endMillis)
                ScreenBreakPeriod.OffScreenOnly -> startMillis
            }
        return if (opens < endMillis) opens else null
    }

    /**
     * [screenBreakOpenStartMillis] for a materialized screen-break [panel], whose shape is looked up by title
     * among [screenBreaks] (the same match [fillSchedule] makes — a break's title is what its panels carry).
     * An unknown title is treated as closed end to end, which is the conservative reading: an unrecognized
     * band is drawn solid and accepts nobody.
     */
    fun screenBreakOpenStartMillis(screenBreaks: List<ScreenBreak>, panel: TaskPanel): Long? {
        val shape = screenBreaks.firstOrNull { it.title == panel.title }?.shape ?: return null
        return screenBreakOpenStartMillis(shape, panel.startEpochMillis, panel.endEpochMillis)
    }

    /**
     * PRD §15: the hardcoded set of screen breaks — periodic activities placed on the calendar with a real
     * spanning time. The §9 fill weaves them in without letting them reduce the surrounding task's minimum.
     */
    val DEFAULT_SCREEN_BREAKS: List<ScreenBreak> = listOf(
        // The 20-20-20 micro-break: after a rest that SERVED it, the next look-away is due 20 min later. Three
        // things serve it (see [serveElapsedScreenBreaks] / [pastScreenBreaksFromPauses]): a look-away break
        // that happened, a 5-/15-min pose break that happened, or a real no-screen pause of at least
        // [LOOK_AWAY_QUALIFYING_PAUSE_MILLIS]. That threshold is the 15-min pose's own length, so the longer
        // pose serves the look-away through the plain pause rule and needs no special case.
        ScreenBreak(
            "look 20 feet away",
            intervalMillis = 20L * 60_000,
            durationMillis = 20L * 1_000,
            pauseThresholdMillis = LOOK_AWAY_QUALIFYING_PAUSE_MILLIS,
            key = LOOK_AWAY_KEY,
            // `side-dev` test 11's sliding 20 s window: a period that accepts nothing.
            shape = ScreenBreakPeriod.Closed,
        ),
        // The rest poses: after a pause of at least their length, the next one is due an interval later. The
        // 5-min pose merges up into the 15-min pose when their windows would overlap (PRD §15). Their [key]s are
        // the two break types the server configures (`break_config`) and the phone cue names.
        ScreenBreak(
            "take a 5min pose and blink hard",
            intervalMillis = 60L * 60_000,
            durationMillis = 5L * 60_000,
            restBreak = true,
            key = FIVE_MIN_BREAK_KEY,
            // `side-dev` test 11's five-minute stretch, verbatim: "1min: nothing" then "4min: only A".
            shape = ScreenBreakPeriod.ClosedMinuteThenBreakDoable,
        ),
        ScreenBreak(
            "take a 15min pose",
            intervalMillis = 2L * 60L * 60_000,
            durationMillis = 15L * 60_000,
            restBreak = true,
            key = FIFTEEN_MIN_BREAK_KEY,
            // NOT a longer copy of the 5-minute pose: a plain fifteen-minute period accepting every task that
            // needs no screen. No closed head (a quarter of an hour is not a stretch one has to be eased into)
            // and no *doable during a break* gate (it is real off-screen working time, not a pose to fill).
            shape = ScreenBreakPeriod.OffScreenOnly,
        ),
    )

    /**
     * The screen breaks to actually seed into the running app — [DEFAULT_SCREEN_BREAKS] in production, or with
     * breaks retimed by the debug fast-break override (so the pause-cue voice message can be tested on real
     * phones in seconds; see [org.example.project.DebugFlags.screenBreakOverrides]).
     *
     * **Any of the three breaks may be retimed, independently**, matched by [ScreenBreak.key] — the stable
     * identifier, not the title or the duration, both of which move under these very knobs. A break with no
     * override entry, and each `null` field of an entry, keeps its production rule. Kept separate from
     * [DEFAULT_SCREEN_BREAKS] so the scheduler tests keep asserting the exact production timings; a no-op when
     * nothing is overridden, so production callers get the unchanged list back.
     */
    fun effectiveDefaultScreenBreaks(): List<ScreenBreak> {
        val overrides = org.example.project.DebugFlags.screenBreakOverrides
        if (overrides.isEmpty()) return DEFAULT_SCREEN_BREAKS
        return DEFAULT_SCREEN_BREAKS.map { side ->
            val override = overrides[side.key] ?: return@map side
            side.copy(
                intervalMillis = override.intervalMillis ?: side.intervalMillis,
                durationMillis = override.durationMillis ?: side.durationMillis,
                pauseThresholdMillis = override.pauseThresholdMillis ?: side.pauseThresholdMillis,
            )
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
     * `lastRest + interval` (an interval after the last qualifying pause **ended**), clamped forward to the
     * now-line once that due has passed without the pause being taken.
     *
     * **Every** screen break — the 5-/15-min rest poses and the 20-second look-away alike — slides this way,
     * because a break the now-line has reached and that nobody took is *still owed*: it is a period accepting
     * no task that **moves to the right** while a device stays unlocked, exactly as the reference's sliding
     * period does (`side-dev/scheduler_logic.py` tests 10–11, `MovingWindow`). What releases it is a real pause: a device
     * going inactive advances the anchor through [screenBreaksForPlacement] (an ongoing pause is presumed to
     * serve every break) and, once derived, through [advanceRestsForward] — so the break moves off the now-line
     * the moment the user actually stops, and not before. A never-rested break (`lastRestMillis == 0`) is due
     * immediately, which is the pre-seed transient every load starts in.
     *
     * The look-away used instead to *step its fixed grid* past an elapsed occurrence, on the grounds that the
     * app cannot tell whether the user looked away and that a sliding start would re-fire its voice cue every
     * tick. The first half was the wrong reading of "not detectable" — an untaken break is owed, not done — and
     * the second is handled where it belongs: the cue keys on the **fixed due**, never on the drawn start
     * ([reachedScreenBreakDueByTitle], the same level `now >= due` rule the poses use). See CLAUDE.md's
     * "the boundary a trigger keys on must be a fixed instant".
     */
    fun screenBreakNextStart(side: ScreenBreak, nowMillis: Long): Long =
        maxOf(side.lastRestMillis + side.intervalMillis, nowMillis)

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
        val poses = screenBreaks.filter { it.restBreak }.mapTo(HashSet()) { it.title }
        return reachedScreenBreakDueByTitle(screenBreaks, nowMillis).filterKeys { it in poses }
    }

    /**
     * PRD §15: [reachedRestPoseDueByTitle] widened to **every** screen break, the 20-second look-away included
     * — each reached break mapped to its stable DUE instant `lastRest + interval`.
     *
     * The look-away belongs here now that it *slides* like a pose ([screenBreakNextStart]): CLAUDE.md's rule is
     * that a sliding break's trigger must key on the fixed DUE and never on the drawn start, which rides the
     * now-line and would re-fire the cue every frame. Everything else is the pose rule unchanged — a **level**
     * `now >= due` so no clock leap can skip it, the `lastRestMillis > 0` gate that keeps a never-anchored
     * break's 1970 sentinel from firing, and the **longest-reached-wins** shadow.
     *
     * Two shadows keep the reached set honest, and they are different rules:
     * - among the **rest poses**, the longest reached one absorbs the shorter (the 5↔15 merge — resting 15
     *   minutes discharges a 5-minute pose due at the same instant);
     * - a **look-away** whose due is at or after the earliest reached pose's due is dropped (the *dragging-pose
     *   shadow*). An owed pose sits at the now-line and, dragging there, re-anchors every shorter break to a
     *   slot that recedes as fast as `now` — so no look-away after that pose's due is a boundary the now-line
     *   can ever cross, and announcing one is the phantom "a 20s break fired right after the 5-min pose I
     *   never took". A look-away due strictly *before* the pose's is a real earlier boundary and is kept, which
     *   is what lets the sweep fire the two in their true order.
     */
    fun reachedScreenBreakDueByTitle(screenBreaks: List<ScreenBreak>, nowMillis: Long): Map<String, Long> {
        fun due(side: ScreenBreak) = side.lastRestMillis + side.intervalMillis
        val reached = screenBreaks
            .filter { isValidScreenBreak(it) && it.lastRestMillis > 0 && due(it) <= nowMillis }
        val (poses, others) = reached.partition { it.restBreak }
        val keptPoses = poses.filter { task -> poses.none { it.durationMillis > task.durationMillis } }
        val earliestPoseDue = poses.minOfOrNull { due(it) }
        val keptOthers = others.filter { earliestPoseDue == null || due(it) < earliestPoseDue }
        return (keptPoses + keptOthers).associate { it.title to due(it) }
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
     * PRD §15: a screen break that **happened** — the object the "what serves a break" rules are written
     * against. [range] is the span it occupied, so its `end` is the rest instant a shorter break anchors to.
     */
    data class PastScreenBreak(val title: String, val key: String, val range: TaskTimeRange)

    /**
     * PRD §15: advance every **non-rest** break (the 20-second look-away) whose own occurrence has fully
     * elapsed at [nowMillis] — *a look-away break that happened serves the look-away.*
     *
     * This is what makes the look-away recur. Its anchor otherwise only ever moved on a detected pause, and
     * nothing detects a look-away: the app announces it, waits [ScreenBreak.durationMillis] and says "resume
     * your work", but no device locked, so `lastRestMillis` never advanced, the due stayed fixed, and the cue —
     * which keys on that due and de-dupes on it ([reachedScreenBreakDueByTitle]) — fired **once per session and
     * never again** (the reported "I got look away, then resume your work, then nothing afterward"). The break
     * also stayed owed forever, pinning its no-task period to the now-line ([screenBreakNextStart]).
     *
     * This is NOT the old "assume a break the grid stepped past was taken". The distinction is that the app
     * *conducted* this one: the occurrence is the app's own announced 20 seconds, and only its full length
     * elapsing counts. The rest **poses** are deliberately excluded — a 5-/15-minute stop IS detectable, so a
     * pose stays owed and keeps sliding until a real pause serves it ([pastScreenBreaksFromPauses]).
     *
     * Arithmetic, not iterative: occurrence `n` of a break anchored at `L` ends at `L + n·(duration +
     * interval)`, so the latest elapsed one is a single division — a clock leap over a hundred cycles costs the
     * same as one. Unanchored breaks (`lastRestMillis == 0`, the pre-seed transient) are left alone, exactly as
     * the cue rule leaves their 1970 sentinel alone.
     *
     * **Stopped by the dragging-pose shadow**, which is what keeps this rule and the cue rule the same rule. An
     * owed rest pose sits at the now-line and re-anchors every shorter break to a slot that recedes with `now`,
     * so from that pose's due onward no look-away is drawn or announced ([reachedScreenBreakDueByTitle]) — and
     * a look-away nobody announced was not conducted and must not count. The anchor therefore advances only
     * through occurrences that came due before the earliest owed pose's due, and then freezes: the look-away
     * stays owed until the pause that serves the pose serves it too ([serveShorterBreaks], the longer rest
     * discharging the shorter). Without this the anchor would tick on through a long owed-pose stretch and the
     * calendar would later draw a row of past look-aways that never happened.
     */
    fun serveElapsedScreenBreaks(screenBreaks: List<ScreenBreak>, nowMillis: Long): List<ScreenBreak> {
        // The earliest pose the now-line has reached and nothing has served: from its due on, every shorter
        // break is being dragged rather than taken.
        val owedPoseDue = screenBreaks
            .filter { it.restBreak && isValidScreenBreak(it) && it.lastRestMillis > 0L }
            .map { it.lastRestMillis + it.intervalMillis }
            .filter { it <= nowMillis }
            .minOrNull()
        return screenBreaks.map { side ->
            if (side.restBreak || !isValidScreenBreak(side) || side.lastRestMillis <= 0L) return@map side
            val cycle = side.durationMillis + side.intervalMillis
            // An occurrence counts if it came due before the shadow fell, so the ceiling is the shadow's due
            // plus this break's own length (the span of the last occurrence it could have announced).
            val ceiling = if (owedPoseDue == null) nowMillis else minOf(nowMillis, owedPoseDue + side.durationMillis)
            val cycles = (ceiling - side.lastRestMillis) / cycle
            if (cycles < 1L) side else side.copy(lastRestMillis = side.lastRestMillis + cycles * cycle)
        }
    }

    /**
     * PRD §15: the **rest-pose breaks that happened** — a past no-screen [pauses] period long enough to be the
     * pose the now-line was *dragging* when it began.
     *
     * A pose slides along the now-line while owed and unserved ([screenBreakNextStart]), so "the user took it"
     * is not a projection but an observation: the account went off-screen for at least the dragged pose's own
     * length. That period **is** that break — drawn as one, and (being a longer rest than a look-away) it
     * serves the look-away too ([serveShorterBreaks]), which is why a 5-min pose discharges the 20-20-20 clock
     * even though 5 minutes is under the look-away's own 15-minute pause threshold.
     *
     * **Which pose was being dragged** is [reachedScreenBreakDueByTitle] evaluated at the pause's start — the
     * same level `now >= due` rule the cue uses, carrying the same **5↔15 merge**: when both poses were owed,
     * what sat on the now-line was the 15-minute one, so the period has to reach *fifteen* minutes to be a
     * break at all, and it is then a past 15-min break rather than a 5-min one. Evaluate against the anchors
     * as they stood BEFORE this pause was folded in ([seedScreenBreaksFromGaps] moves them to its end), or the
     * pose reads as already served and nothing is recognized.
     */
    fun pastScreenBreaksFromPauses(
        screenBreaks: List<ScreenBreak>,
        pauses: List<TaskTimeRange>,
    ): List<PastScreenBreak> {
        if (pauses.isEmpty()) return emptyList()
        val byTitle = screenBreaks.associateBy { it.title }
        return pauses.mapNotNull { pause ->
            // The pose on the now-line when the pause began: the reached ones, longest-absorbs-shorter.
            val dragged = reachedScreenBreakDueByTitle(screenBreaks, pause.startEpochMillis).keys
                .mapNotNull { byTitle[it] }
                .filter { it.restBreak }
                .maxByOrNull { it.durationMillis }
                ?: return@mapNotNull null
            val length = pause.endEpochMillis - pause.startEpochMillis
            if (length < dragged.durationMillis) return@mapNotNull null
            PastScreenBreak(
                dragged.title,
                dragged.key,
                TaskTimeRange(pause.startEpochMillis, pause.startEpochMillis + dragged.durationMillis),
            )
        }
    }

    /**
     * PRD §15: fold the breaks that HAPPENED ([pastScreenBreaks]) into the anchors of every **shorter** break —
     * the same "a pause re-anchors every shorter pause" rule the projection grid applies when it places one
     * ([screenBreakPanels]), here applied to a break observed in the past. A 5-min pose break therefore serves
     * the 20-second look-away; a look-away break serves nothing (nothing is shorter).
     *
     * Forward-only, like every other rest evidence: an anchor already past the break's end is left alone.
     */
    fun serveShorterBreaks(
        screenBreaks: List<ScreenBreak>,
        pastScreenBreaks: List<PastScreenBreak>,
    ): List<ScreenBreak> {
        if (pastScreenBreaks.isEmpty()) return screenBreaks
        val byTitle = screenBreaks.associateBy { it.title }
        return screenBreaks.map { side ->
            val servedAt = pastScreenBreaks
                .filter { (byTitle[it.title]?.durationMillis ?: 0L) > side.durationMillis }
                .maxOfOrNull { it.range.endEpochMillis }
            if (servedAt != null && servedAt > side.lastRestMillis) side.copy(lastRestMillis = servedAt) else side
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
     * PRD §8 calendar layers: the two decorative layers the calendar draws over the timeline — the oblique-line
     * pattern for "no computer was unlocked" and the opposite-slope one for "no phone was unlocked". A stretch
     * carrying BOTH is exactly a **no-screen period** (the user's rule: no computer and no phone unlocked at the
     * same time), which is what §9 places the off-screen tasks in and what §15 counts as a pause.
     *
     * Each layer is read from the DEVICE's own history — its OS lock/unlock record, or its sleep/awake record
     * where the platform exposes no other (see `deviceLockedIntervals`) — never from the app's own activity
     * heartbeats, which only say when the app happened to be running. See [layerRegions].
     */
    enum class ActivityLayer(
        /** What the layer says, shown on hover / in the phone's contextual menu. */
        val calendarLabel: String,
    ) {
        NoComputerUnlocked("No computer unlocked"),
        NoPhoneUnlocked("No phone unlocked"),
    }

    /** PRD §8: which layer a device of [kind] speaks for — everything that is not a phone is a computer. */
    fun layerForDeviceKind(kind: DeviceKind): ActivityLayer =
        if (kind == DeviceKind.Phone) ActivityLayer.NoPhoneUnlocked else ActivityLayer.NoComputerUnlocked

    /**
     * PRD §8: the regions the calendar hatches for one [ActivityLayer].
     *
     * [lockedIntervals] is the OS lock/standby history of that layer's device kind over
     * `[sinceMillis, untilMillis]` (see `deviceLockedIntervals`), or **null when no device of that kind could
     * tell** — and null is the load-bearing case: a device whose history is unavailable is assumed to have
     * been LOCKED throughout, so running on a computer with no phone on the account hatches the whole asked
     * past with the phone layer (the user's own example: "if I run the app on a computer and the data of the
     * phone is not available because it is the first time I run the app, then it is considered that the phone
     * was always locked in the past"). This is the SAME default as the account-wide pause derivation
     * ([derivePauses], "no screen unless a device reported activity"): a device nobody can vouch for was not
     * being used.
     *
     * Null and an EMPTY list stay different answers, which is why the seam is nullable: an empty list is the
     * OS answering "this device was never locked over that window" and draws nothing at all. The window is
     * `[sinceMillis, untilMillis]` — never beyond the now-line the caller passes as [untilMillis], because
     * nothing ahead of it has been observed and only [assertedRegions] speak for the future.
     *
     * [assertedRegions] are the stretches the RULES promise nobody is unlocked in — the §17 sleep windows
     * ahead of the now-line, the §15 screen breaks, and the user's own no-screen periods. They hold whether
     * or not any history is available, so a device that cannot tell still shows those.
     *
     * Sub-minute slivers are dropped from the EVIDENCE (the seam rule, [MIN_INACTIVITY_BAND_MILLIS]): a
     * machine that dips in and out of standby for seconds would otherwise draw hairlines of hatch all day.
     * They are never dropped from [assertedRegions] — a 20-second look-away is a real claim and keeps its
     * hatch however short it is.
     */
    fun layerRegions(
        lockedIntervals: List<TaskTimeRange>?,
        assertedRegions: List<TaskTimeRange>,
        sinceMillis: Long,
        untilMillis: Long,
    ): List<TaskTimeRange> {
        // "Cannot be asked" ⇒ locked for the whole asked window. Not seam-filtered and not clipped further:
        // it is one span by construction. A degenerate window (nothing elapsed yet) asserts nothing.
        if (lockedIntervals == null) {
            val unaskable =
                if (untilMillis > sinceMillis) listOf(TaskTimeRange(sinceMillis, untilMillis)) else emptyList()
            return mergeOccupied(unaskable + assertedRegions)
        }
        val evidence =
            lockedIntervals
                .map {
                    TaskTimeRange(
                        maxOf(it.startEpochMillis, sinceMillis),
                        minOf(it.endEpochMillis, untilMillis),
                    )
                }
                .filter { it.endEpochMillis - it.startEpochMillis >= MIN_INACTIVITY_BAND_MILLIS }
        return mergeOccupied(evidence + assertedRegions)
    }

    /**
     * PRD §8/§9: the past stretches that were OBSERVED to be no-screen periods — the intersection of the two
     * layers' evidence halves, i.e. the times neither a computer nor a phone was unlocked.
     *
     * This is the same identity [layerRegions] draws ("a stretch carrying BOTH layers is a no-screen period"),
     * read for the SCHEDULER rather than for the calendar: §9's "assume nothing happened" rule must not bank an
     * on-screen task's record over a stretch the devices say nobody was at a screen for. Before this existed,
     * that rule keyed on hand-drawn "No screen" PANELS alone, so on an account where the user had never drawn
     * one it never fired at all — the app banked work straight through a machine the OS reported asleep.
     *
     * Only the EVIDENCE halves intersect here; [layerRegions]' asserted regions (sleep windows, screen breaks,
     * the user's own no-screen periods) are deliberately left out. A screen break SUSPENDS the chunk it lands
     * in rather than cutting it (PRD §15), so folding the assertions in would silently stop recording across
     * every break — a different rule from the one this answers. The hand-drawn periods reach the bank by their
     * own route (the reducer unions them in).
     *
     * [computerLocked] / [phoneLocked] are the OS lock/standby histories of the two device kinds over
     * `[sinceMillis, untilMillis]`, each **null when no device of that kind could tell** — and null carries the
     * same assumed-LOCKED meaning it has in [layerRegions], which is what makes a phone-less account's whole
     * past turn on the computer's history alone. Both null ⇒ the whole window, matching [derivePauses]' own
     * "no screen unless a device reported activity" default.
     */
    fun observedNoScreenRegions(
        computerLocked: List<TaskTimeRange>?,
        phoneLocked: List<TaskTimeRange>?,
        sinceMillis: Long,
        untilMillis: Long,
    ): List<TaskTimeRange> {
        if (untilMillis <= sinceMillis) return emptyList()
        val computer = layerRegions(computerLocked, emptyList(), sinceMillis, untilMillis)
        val phone = layerRegions(phoneLocked, emptyList(), sinceMillis, untilMillis)
        return intersectRegions(computer, phone)
    }

    /**
     * PRD §8: the past stretches the calendar draws as a derived GREY "Inactivity" band — the elapsed timeline
     * minus everything already drawn over it ([coveredRegions]: the task panels, the §17 sleep bands, and the
     * user's own hand-added inactivity panels). The rule it implements is that the past is fully accounted
     * for: every elapsed stretch is either a task panel or a grey period labelled "Inactivity" or "Sleep".
     *
     * A no-screen period is deliberately NOT part of [coveredRegions]: it is not a task panel and it is not
     * grey — it is the period carrying both "nobody unlocked" layers — so a past no-screen stretch with no
     * work in it is idle time and reads as one. Neither is a screen break: its band draws over whatever is
     * underneath, and a break nothing was scheduled in is idle time too.
     *
     * Sub-minute remnants are dropped ([MIN_INACTIVITY_BAND_MILLIS]): the seam between two adjacent panels is
     * not a pause, and drawing it would litter the day with slivers.
     */
    fun derivedInactivityBands(
        coveredRegions: List<TaskTimeRange>,
        sinceMillis: Long,
        untilMillis: Long,
    ): List<TaskTimeRange> {
        if (untilMillis <= sinceMillis) return emptyList()
        return subtractRegions(listOf(TaskTimeRange(sinceMillis, untilMillis)), mergeOccupied(coveredRegions))
            .filter { it.endEpochMillis - it.startEpochMillis >= MIN_INACTIVITY_BAND_MILLIS }
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
     * re-anchor a look-away inside the window is itself placed first (the same widening
     * [screenBreakOccurrencesBetween] relies on). Unlike [screenBreakOccurrencesBetween] this applies **no**
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
                    // Every break recurs over a (duration + interval) cycle — an interval after it ends. Seed at
                    // the grid point at/just before the widened [from] so the loop reconstructs every occurrence
                    // up to [toMillis] with the re-anchoring poses already placed.
                    val step = t.durationMillis + t.intervalMillis
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
     * PRD §15: the screen breaks that were **taken** in `[fromMillis, toMillis]` — the calendar's past-side
     * markers, so a break stays drawn where it happened instead of vanishing the instant the now-line passes it.
     *
     * Read off the ANCHORS, not off the projection grid, because only the anchor knows what happened. Each
     * break's [ScreenBreak.lastRestMillis] is exactly the end of the last rest that served it — so `[anchor −
     * duration, anchor]` is a break that was really taken, and everything after the anchor is the pending
     * occurrence, which slides to the now-line while owed ([screenBreakNextStart]) and belongs to the FORWARD
     * projection. (Drawn by both, an owed break would appear twice at once: at its fixed due and at the
     * now-line.) The two kinds differ in how far back the evidence reaches, and it is the same asymmetry as
     * everywhere else in §15 — **only a break the app CONDUCTED is drawn in the past**:
     * - a **look-away** is conducted: the app announces it, waits its full [ScreenBreak.durationMillis] and
     *   says "resume your work", and its anchor steps one whole `duration + interval` cycle per break that
     *   elapsed WHOLLY ([serveElapsedScreenBreaks]), so walking the anchor back reproduces exactly the
     *   occurrences that happened. One that started and did NOT finish — the manual "Look away now"
     *   superseding the run in progress ([org.example.project.scheduler.engine.SchedulerEngine.restartLookAway]),
     *   or the app stopping mid-break — never moved the anchor, so it is simply erased;
     * - a **rest pose (5-/15-min) draws NOTHING in the past**. Nothing about a pose ever happens in the app:
     *   it is only ever RECOGNIZED after the fact, from an observed pause ([pastScreenBreaksFromPauses]). That
     *   pause is already on the calendar as what it really was — the two device layers, the no-screen period,
     *   the derived Inactivity band — so stamping a pose band over it would restate one fact as a second
     *   object, and assert the break's nominal 5-/15-min shape in place of the pause's real extent. (It also
     *   made the anchor's own meaning visible as a lie: an anchor seeded from a long sleep drew a tidy 5-min
     *   pose at the end of the night.)
     *
     * Deliberately NOT the [simulateScreenBreaks] grid: these are markers of what happened, so they need no
     * merge/absorption/re-anchor interleaving (those rules place FUTURE occurrences that do not overlap), and
     * seeding that walk in the past would reconstruct occurrences no anchor vouches for. Cost is bounded by the
     * window, not by history (CLAUDE.md), and an unanchored break (`lastRestMillis == 0`) draws nothing.
     */
    fun takenScreenBreakPanels(
        screenBreaks: List<ScreenBreak>,
        fromMillis: Long,
        toMillis: Long,
    ): List<TaskPanel> {
        if (toMillis < fromMillis) return emptyList()
        val out = mutableListOf<TaskPanel>()
        for ((index, side) in screenBreaks.withIndex()) {
            if (!isValidScreenBreak(side) || side.lastRestMillis <= 0L) continue
            // A pose is never conducted, only recognized from a pause the calendar already draws as itself, so
            // it leaves no past marker at all (see the docstring).
            if (side.restBreak) continue
            // The look-away chains backward a cycle at a time. Skip straight to the first occurrence that can
            // fall in the window, so the cost is the window's own span and not its distance from the anchor.
            val cycle = side.durationMillis + side.intervalMillis
            val overshoot = side.lastRestMillis - side.durationMillis - toMillis
            val skip = if (overshoot > 0) (overshoot + cycle - 1) / cycle else 0L
            var end = side.lastRestMillis - skip * cycle
            var guard = 0
            while (end >= fromMillis && guard++ < SCREEN_BREAK_PROJECTION_LIMIT) {
                val start = end - side.durationMillis
                if (start in fromMillis..toMillis) out += screenBreakPanel(index, side.title, start, end)
                end -= cycle
            }
        }
        return out.sortedBy { it.startEpochMillis }
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

    /**
     * PRD §15 / `side-dev/scheduler_logic.py` tests 10–11: the work plan as it must be **displayed** while a screen break
     * sits on the now-line — with the auto panels cut out of what the break REFUSES, so a break the now-line
     * has reached really is the period it says it is for as long as it slides.
     *
     * Refuses, not covers: a break is a period, and only the 20-second look-away is a period that accepts no
     * task. The 5-minute pose's closed head and the part of any break the task at hand is not accepted in are
     * cut; a pose's **open** period keeps the off-screen work it accepts, which is the part the calendar draws
     * hollow ([screenBreakOpenStartMillis]). Cutting the whole span instead would state on screen that the
     * scheduler may not use a period it is in fact filling.
     *
     * A break that is owed slides right with the now-line ([screenBreakNextStart]) while the plan under it does
     * not: the plan is materialized by [fillSchedule], which by CLAUDE.md's trigger rule runs on a **rule
     * change**, not on time passing. So the fill correctly leaves the break's span empty at the instant it
     * runs, and every tick after that the marker slides forward over auto panels the fill placed past it. That
     * is the sliding-period case the reference answers with its dynamic rule list (`MovingWindow`): between
     * breakpoints the plan is *affine* in the period's position, so a display can follow the period without
     * re-scheduling. Here the period is pinned to the plan's own origin (the now-line), which is that rule's
     * simplest regime — the disturbed slot is the one the cursor is in, and nothing else changes shape.
     *
     * Only [isRegeneratedPanel] panels are cut: a pinned/manual block and a chore are pre-placed blocks in the
     * reference's sense and cannot be moved by a period. Every refusing region begins at or after the now-line,
     * so a panel straddling it keeps its **elapsed** head (that time really was worked) and resumes past the
     * refusal — the cut is a hole, never a rewrite of the past. Each resumed piece takes a distinct id so two
     * display blocks never share one.
     */
    fun clipPlanForPinnedScreenBreak(
        panels: List<TaskPanel>,
        breakPanels: List<TaskPanel>,
        nowMillis: Long,
        // PRD §15: the break definitions the [breakPanels] were projected from, and the task attributes, so a
        // pose's OPEN period keeps the work it accepts instead of being cut like its closed head. Left empty
        // (tests, and any caller with no configuration in hand) every break reads as closed end to end, which
        // is the conservative answer: the whole span is cut, exactly as before.
        screenBreaks: List<ScreenBreak> = emptyList(),
        tasks: Map<TaskId, Task> = emptyMap(),
    ): List<TaskPanel> {
        // How far the break covering the now-line reaches. Walked transitively, because the 5↔15 merge and a
        // look-away re-anchored onto a pose's edge can leave two touching markers: the plan resumes past the
        // last of them, not inside the seam.
        var end = nowMillis
        while (true) {
            val next = breakPanels
                .filter { it.startEpochMillis <= end && it.endEpochMillis > end }
                .maxOfOrNull { it.endEpochMillis } ?: break
            if (next <= end) break
            end = next
        }
        if (end <= nowMillis) return panels
        val chain = breakPanels.filter { it.endEpochMillis > nowMillis && it.startEpochMillis < end }
        // What the chain refuses THIS task: every closed head, plus every open period whose own accepted set
        // this task is not in. An off-screen task a pose accepts is not cut at all — the break slid over work
        // it is happy to have there, and the calendar draws that part hollow for exactly that reason.
        fun refusedRegions(taskId: TaskId?): List<TaskTimeRange> {
            val task = taskId?.let { tasks[it] }
            val out = mutableListOf<TaskTimeRange>()
            for (band in chain) {
                val from = maxOf(band.startEpochMillis, nowMillis)
                val to = minOf(band.endEpochMillis, end)
                if (to <= from) continue
                val opens = screenBreakOpenStartMillis(screenBreaks, band)?.coerceIn(from, to) ?: to
                if (opens > from) out += TaskTimeRange(from, opens)
                if (to > opens && !breakPeriodAccepts(screenBreaks, band, task)) out += TaskTimeRange(opens, to)
            }
            return mergeOccupied(out)
        }
        return panels.flatMap { panel ->
            when {
                // Fixed blocks and the bands are not the plan; a period cannot move them.
                !isRegeneratedPanel(panel) || panel.screenBreak || panel.sleep -> listOf(panel)
                // Wholly past, or already past the break: untouched.
                panel.endEpochMillis <= nowMillis || panel.startEpochMillis >= end -> listOf(panel)
                // Every refusing region starts at/after the now-line, so a straddling panel keeps its elapsed
                // head (that time really was worked) and what survives resumes under a distinct id.
                else -> panel.minus(refusedRegions(panel.taskId))
            }
        }
    }

    /** Whether the period [band] is (per its [ScreenBreak.shape]) open to [task] — see [fillSchedule]. */
    private fun breakPeriodAccepts(screenBreaks: List<ScreenBreak>, band: TaskPanel, task: Task?): Boolean {
        if (task == null || task.onScreen) return false
        return when (screenBreaks.firstOrNull { it.title == band.title }?.shape) {
            ScreenBreakPeriod.ClosedMinuteThenBreakDoable -> task.doableDuringBreak
            ScreenBreakPeriod.OffScreenOnly -> true
            else -> false
        }
    }

    /**
     * This panel with [regions] (merged and sorted) cut out of it. The first surviving piece keeps the panel's
     * id and each later one takes a distinct `/resume` id, so two display blocks can never share one.
     */
    private fun TaskPanel.minus(regions: List<TaskTimeRange>): List<TaskPanel> {
        if (regions.isEmpty()) return listOf(this)
        return subtractRegions(listOf(TaskTimeRange(startEpochMillis, endEpochMillis)), regions)
            .mapIndexed { index, part -> piece(part.startEpochMillis, part.endEpochMillis, index) }
    }

    private fun TaskPanel.piece(start: Long, stop: Long, index: Int): TaskPanel =
        copy(
            id = if (index == 0) id else id + "/resume" + (if (index == 1) "" else index.toString()),
            startEpochMillis = start,
            endEpochMillis = stop,
        )

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
     * - **Screen-break dues** — [reachedScreenBreakDueByTitle] at [toMillis] (a **level** reach, so a jump
     *   can't skip it), keyed on each break's stable due. This is now the source for BOTH the 5-/15-min poses
     *   and the 20-second look-away: every break slides to the now-line while owed ([screenBreakNextStart]),
     *   so none of them has a drawn start that is a fixed crossable boundary any more. A pose due already in
     *   [alreadyNotifiedPoseDues] is omitted, so a break sliding along the now-line announces once; a
     *   look-away carries its resume instant (`due + duration`) as [CueCrossing.endInstant]. The crossing's
     *   instant is the DUE, so the kinds order against each other by which boundary was actually earlier —
     *   even an overdue pose due that predates [fromMillis]. A **look-away** due is additionally required to
     *   fall in the window: unlike a pose it has no de-dupe memory here, and its due is fixed while the break
     *   is owed, so an unbounded reach would re-offer the same crossing on every later sweep.
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
        val byTitle = screenBreaks.associateBy { it.title }
        // Every screen break is now keyed on its fixed DUE (`lastRest + interval`), the look-away included:
        // it slides to the now-line while owed ([screenBreakNextStart]), so the drawn start is no longer a
        // crossable boundary and only the due is. The level reach is leap-proof, and the longest-reached-wins
        // shadow inside [reachedScreenBreakDueByTitle] subsumes what the dragging-pose shadow used to do.
        for ((title, due) in reachedScreenBreakDueByTitle(screenBreaks, toMillis)) {
            val side = byTitle[title] ?: continue
            if (side.restBreak) {
                // 5/15-min rest-pose dues reached at the now-line (the stable due is the ordering key).
                if (automaticSchedule && alreadyNotifiedPoseDues[title] != due) {
                    out += CueCrossing(due, CueKind.RestPoseDue, title, due)
                }
            } else if (due >= fromMillis) {
                // The 20s look-away: announced at its due, with the resume cue an occurrence-length later.
                // Bounded by the sweep window because the look-away has no de-dupe memory of its own (the
                // engine keys on the instant, and a due stays FIXED while the break is owed, so it would be
                // re-offered on every later sweep). Consecutive scans tile the timeline
                // (`BoundarySweep.scanFloorMillis`), so bounding it here cannot drop a crossing.
                out += CueCrossing(due, CueKind.LookAwayStart, title, due + side.durationMillis)
            }
        }
        // Wind-down (bedtime − 1h) instants that fall in the window.
        for (wd in windDownInstants) {
            if (wd in fromMillis..toMillis) out += CueCrossing(wd, CueKind.WindDown, "", wd)
        }
        return out.sortedWith(compareBy({ it.instant }, { it.kind.ordinal }))
    }

    /**
     * PRD §15 projection engine shared by [screenBreakPanels] (forward from `now`) and
     * [screenBreakOccurrencesBetween] / [screenBreakPanelsInWindow] (an arbitrary window, past included).
     * Walks the [seedDue] occurrences in time order up to [horizon], resolving overlaps via
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
            // Recurrence: EVERY break resumes an interval after it ENDS — the same arithmetic the anchor uses
            // (`lastRest` is the serving rest's end, the next due `lastRest + interval`), so the drawn grid and
            // the cue's due can never drift apart. The look-away used to recur an interval after it *started*,
            // which left its drawn cadence 20 s ahead of the anchor now that a look-away break serves itself
            // ([serveElapsedScreenBreaks]). Placing this pause also pushes every shorter pause to an interval
            // after it ends. (Only COUPLED breaks reach this grid — a decoupled pose is projected separately by
            // [decoupledPoseOccurrences] and never seeded here; see [screenBreakPanels].)
            val remaining = denseBudget[nextIndex]
            if (remaining != null && remaining <= 1) {
                // Dense test-anchor budget exhausted — stop recurring this index so its sub-minute interval
                // can't flood the projection (see [DENSE_SCREEN_BREAK_INTERVAL_FLOOR_MILLIS]).
                due.remove(nextIndex)
            } else {
                if (remaining != null) denseBudget[nextIndex] = remaining - 1
                due[nextIndex] = end + task.intervalMillis
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

    /**
     * PRD §7 **"Switch task"**: the task the now-line is actually **on** — the panel covering [nowMillis]
     * that stands for real work, or null when there is none.
     *
     * Everything else the calendar draws across the now-line is not a task and so is nothing to switch away
     * from: a screen break, a sleep band, a grey inactivity or a no-screen period (all of which carry no
     * [TaskPanel.taskId] anyway) and a zero-duration §14 reminder tag. Unlike [currentPanel] this is not "the
     * first panel here" but "the task here", so a task panel drawn under one of those overlays is still found.
     */
    fun taskAtNowLine(state: SchedulerState, nowMillis: Long): TaskId? =
        state.panels.firstOrNull {
            it.taskId != null && !it.chore && !it.screenBreak && !it.sleep && !it.noScreen && !it.inactivity &&
                it.startEpochMillis <= nowMillis && nowMillis < it.endEpochMillis
        }?.taskId

    /**
     * PRD §7 **"Switch task"**: [switch] if the refusal it records is still **outstanding** at [nowMillis],
     * else null — the value [fillSchedule] hands the walk as its `last`, so the refused task is not the one
     * that starts here.
     *
     * A refusal is outstanding until some **other** task has actually been served past the instant it was made.
     * That is what granting it means, and reading it off [past] (the same recorded history the virtual clocks
     * are seeded from) is what keeps CLAUDE.md's resume contract: a chain of re-plans over the refusal reaches
     * the same schedule as one long plan, because each of them asks the history the same question rather than
     * carrying a flag the next one cannot reconstruct. A marker stamped in the future (a peer's clock ahead of
     * ours) is not yet live.
     */
    internal fun liveForcedSwitchTask(
        switch: ForcedTaskSwitch?,
        past: List<PlanBlock>,
        nowMillis: Long,
    ): TaskId? {
        if (switch == null || switch.atMillis > nowMillis) return null
        val granted = past.any { it.taskId != null && it.taskId != switch.taskId && it.endMillis > switch.atMillis }
        return if (granted) null else switch.taskId
    }

    /**
     * PRD §13 **"start this task now"**: [start] if the request it records is still **outstanding** at
     * [nowMillis], else null — the task [fillSchedule] puts in the first slot it places.
     *
     * The liveness rule is [liveForcedSwitchTask]'s, unchanged: a marker is outstanding until some **other**
     * task has actually been served past the instant it was made. For a refusal that means "the plan started
     * something else, as asked"; for a request it means "the plan has moved on from the task I asked for" —
     * the same event ends both. While the named task is still the one running since [ForcedTaskStart.atMillis]
     * the request is unanswered, so a re-plan in between (a rule change, the hourly staleness refresh) keeps
     * the user on it instead of quietly picking somebody else. Reading it off [past] — the same recorded
     * history the virtual clocks are seeded from — is what keeps CLAUDE.md's resume contract. A marker stamped
     * in the future (a peer's clock ahead of ours) is not yet live.
     */
    internal fun liveForcedStartTask(
        start: ForcedTaskStart?,
        past: List<PlanBlock>,
        nowMillis: Long,
    ): TaskId? {
        if (start == null || start.atMillis > nowMillis) return null
        val answered = past.any { it.taskId != null && it.taskId != start.taskId && it.endMillis > start.atMillis }
        return if (answered) null else start.taskId
    }

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
     * `side-dev/README.md` — [SchedulerPlanner] / [PlanWalk], the Kotlin port of the reference `side-dev/scheduler_logic.py`.
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
     * [SchedulerPlanner.plan] (the rule-list form of `side-dev/README.md`) also drives. What this function adds
     * is the mapping from OmniApp's world onto the reference's two inputs, and the materialization of concrete
     * [TaskPanel]s:
     * - **pre-placed blocks** = the user's pinned/manual panels still ahead of `now`, plus (on an extension)
     *   the kept head of the plan, plus the already-served **past** (records and past panels), which is what
     *   seeds the virtual clocks ([SchedulerPlanner.replayClocks]). Only the blocks still AHEAD feed the influence field — the past is history, not a blockage to compensate around;
     * - **periods that accept a set of tasks** = the §9 screen zones and the §15 screen breaks. Inside a
     *   no-screen period only off-screen tasks are accepted, outside one only on-screen tasks. A screen
     *   break is a period too — which one is the break's own [ScreenBreak.shape], and the three are exactly
     *   the periods of `side-dev`'s **test 11**: the 20-second look-away accepts **nobody** over its whole
     *   length; the 5-minute pose is a closed **first minute** ([SCREEN_BREAK_CLOSED_HEAD_MILLIS]) then a tail
     *   accepting the tasks that need **no screen** and are marked *doable during a screen break* (PRD §8: the
     *   second flag implies the first); the 15-minute pose is one open period accepting every task that needs
     *   no screen. The reference's rule that a task is a candidate only while its minimum fits the gap is what
     *   enforces "never when its minimum time exceeds what is left of the break", with no special case. A
     *   period accepting nobody excludes everyone equally, so per `side-dev/README.md` it creates **no**
     *   influence field — which is exactly why a look-away, which recurs every 20 minutes forever, does not
     *   distort the plan around each of its occurrences (a pose's open period does, and should).
     *
     * Because a fixed block owned by another task and a period that bans a task are the *same* deprivation
     * (`side-dev/README.md`), both feed one influence field: a task kept out of the timeline gets a denser,
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
     * PRD §9 trigger: this runs when [schedulingSignature] moves, plus once an hour if nothing moved it —
     * a staleness bound, not a tick (every re-plan re-arms it, so an edited account never reaches it). The
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
        // The user's sleep windows. PRD §8: a sleep window IS an inactivity period — one labelled "Sleep" —
        // so, like every grey period, it is a period accepting NOBODY (see [blockedRegions] below). It is
        // still not an occupancy *obstacle*: a chunk crossing one suspends and resumes on the far side.
        val sleepPanels = sleepPanels(state.sleep, nowMillis, horizon, timeZone)
        // The task-tree timeline: while `now` sits between two dated trees the scheduler follows the two
        // trees' BLENDED priorities over the UNION of their leaves, not the live tree's own — so the plan
        // transforms continuously from one arrangement into the next. With no dated tree these collapse to
        // `schedulableLeaves(state)` / `state.tasks`, i.e. exactly the pre-timeline behaviour.
        val leaves = blendedSchedulableLeaves(state, nowMillis)
        // PRD §15: screen breaks materialize regardless of whether there are leaf tasks to fill around them.
        // Each one places its next occurrence at its due time (or the now-line when overdue), with the
        // 5-min↔15-min merge applied. They project straight through the sleep windows too, so the eye-rest /
        // pose cues still fire (and render over the "Sleep" band) for a user working through the night.
        // Bounded by THIS fill's [horizon], not by the fixed 168h default: a fill for a short horizon (the
        // displayed week ends tomorrow, or the calendar is closed) must not project a week of breaks it will
        // then carry in `panels`, and a DISPLAY fill for a far week must project across it (the default would
        // stop at 168h and leave the far days break-less).
        val placementBreaks = screenBreaksForPlacement(state.screenBreaks, liveRest)
        val sidePanels = screenBreakPanels(
            placementBreaks,
            nowMillis,
            horizon,
            qualifyingPauseWindows = sleepPanels.map { TaskTimeRange(it.startEpochMillis, it.endEpochMillis) },
        )
        if (leaves.isEmpty()) return (kept + sidePanels + sleepPanels).sortedBy { it.startEpochMillis }

        val priorities = blendedTaskPriorities(state, nowMillis)
        val keptIds = kept.mapTo(HashSet()) { it.id }
        // Everything below reads task attributes through `working`, which carries the widened map: a leaf
        // that lives only in the other keyframe still gets its title (so its panels are not nameless), its
        // minimum time, its screen flags and its records.
        val working = state.copy(panels = kept, tasks = blendedTaskAttributes(state, nowMillis))
        // PRD §8: an INACTIVITY period is the grey one — a stretch where the scheduler places NOTHING at
        // all, stated as a period whose accepted set is empty. Three things are such a period and they are
        // deliberately one concept: the user's hand-added inactivity periods, the §17 sleep windows (an
        // inactivity period labelled "Sleep") and the closed heads of the screen breaks (handled below with
        // the rest of each break's shape, since a break's head and tail are one panel). Excluding EVERYBODY
        // equally, they create no influence field — the reference model's "interval belonging to nobody",
        // which the walk steps over rather than ending a run on.
        val blockedRegions =
            mergeOccupied(
                (kept.filter { it.inactivity } + sleepPanels)
                    .map { TaskTimeRange(maxOf(it.startEpochMillis, nowMillis), it.endEpochMillis) }
                    .filter { it.endEpochMillis > it.startEpochMillis },
            )
        // PRD §9 screen switches: the no-screen periods *classify* the timeline rather than obstructing it
        // — an on-screen task may only run outside them, an off-screen task only inside. A no-screen period
        // is not grey (it accepts the off-screen tasks); it is the period carrying both "nobody unlocked"
        // layers. Neither it nor an inactivity period is an occupancy obstacle.
        val noScreenRegions =
            mergeOccupied(
                kept.filter { it.noScreen }
                    .map { TaskTimeRange(maxOf(it.startEpochMillis, nowMillis), it.endEpochMillis) }
                    .filter { it.endEpochMillis > it.startEpochMillis },
            )
        // PRD §15: the screen-break occupied regions. They are periods (the accepted set is decided by
        // [breakClosedRegions] / [breakDoableRegions] / [breakOffScreenRegions] below), not occupancy
        // obstacles: a regular chunk suspends across one and resumes after it.
        val sideRegions = mergeOccupied(sidePanels.map { TaskTimeRange(it.startEpochMillis, it.endEpochMillis) })
        // The regions that SUSPEND a chunk instead of cutting it: the §15 screen breaks and the §8 grey
        // periods (a hand-added inactivity period, a §17 sleep window). PRD §17 says it in as many words — a
        // task that meets a sleep window is "split and resumes at wake, not charged for the sleep time, like
        // a screen break" — and it is the same rule for the same reason: the stretch belongs to NOBODY, so it
        // costs the run nothing but time. A screen-zone edge is the other kind of boundary: somebody else may
        // run past it, so it ends the run and PRD §9/§10 cut the minimum there.
        val suspendRegions = mergeOccupied(sideRegions + blockedRegions)
        val suspendStarts = suspendRegions.mapTo(HashSet()) { it.startEpochMillis }
        // PRD §15: a screen break is a period, and *which* tasks it accepts is the break's own declared shape
        // ([ScreenBreakPeriod]) — the periods of `side-dev`'s test 11:
        //   - the 20-second look-away accepts **nobody**, end to end;
        //   - the 5-minute pose is a closed opening minute (the eyes have to actually leave the screen) then a
        //     tail accepting the tasks that need no screen and are marked doable during a break;
        //   - the 15-minute pose is one open period accepting every task that needs no screen.
        // The split is what the reference model wants: a period accepting nobody excludes everyone equally, so
        // it creates **no** influence field (`side-dev/README.md`) — a look-away must not distort the plan
        // around itself, whereas an open pose tail legitimately does, because it hands the tasks it accepts
        // time nobody else gets.
        val shapeByTitle = placementBreaks.associate { it.title to it.shape }
        val breakClosed = mutableListOf<TaskTimeRange>()
        val breakDoableOpen = mutableListOf<TaskTimeRange>()
        val offScreenOpen = mutableListOf<TaskTimeRange>()
        for (panel in sidePanels) {
            val start = panel.startEpochMillis
            val end = panel.endEpochMillis
            if (end <= start) continue
            // A debug-shortened break no longer than the closed head is closed end to end, whatever its shape.
            // [screenBreakOpenStartMillis] is the single reading of that shape — the calendar draws the open
            // part hollow off the very same function, so what is shown and what is scheduled cannot diverge.
            val shape = shapeByTitle[panel.title] ?: ScreenBreakPeriod.Closed
            val opens = screenBreakOpenStartMillis(shape, start, end) ?: end
            if (opens > start) breakClosed += TaskTimeRange(start, opens)
            if (end > opens) {
                val tail = TaskTimeRange(opens, end)
                if (shape == ScreenBreakPeriod.OffScreenOnly) offScreenOpen += tail else breakDoableOpen += tail
            }
        }
        val breakClosedRegions = mergeOccupied(breakClosed)
        val breakDoableRegions = mergeOccupied(breakDoableOpen)
        val breakOffScreenRegions = mergeOccupied(offScreenOpen)
        fun covers(regions: List<TaskTimeRange>, t: Long): Boolean =
            regions.any { it.startEpochMillis <= t && t < it.endEpochMillis }

        // `side-dev/scheduler_logic.py` resolves ties by (biggest share, then name); OmniApp's PRD §9 tie-break is (highest
        // absolute priority, then title). [PlanWalk.pick] takes the first candidate on a tie, so handing it
        // this order IS the tie-break.
        val tieBreak =
            compareByDescending<TaskId> { priorities[it] ?: 0.0 }.thenBy { working.tasks[it]?.title.orEmpty() }
        val ordered = leaves.sortedWith(tieBreak)
        val minimumMillisOf = ordered.associateWith { (working.tasks[it]?.minimumMinutes ?: 0).toLong() * MILLIS_PER_MINUTE }
        val planner =
            SchedulerPlanner(ordered.map { PlanTask(it, priorities[it] ?: 0.0, minimumMillisOf[it] ?: 0L) })

        // --- the periods (`side-dev/README.md`: "the timeline is formed of periods, each defining a set of
        // tasks it accepts"). The screen zones and the screen breaks partition [now, ∞) into maximal spans
        // with a constant accepted set; the LAST one is left open-ended, so a task banned from it is banned
        // "forever" and the field gives it a ramp before the ban and no phantom ramp after the horizon.
        val edges = buildList {
            add(nowMillis)
            for (region in
                blockedRegions + noScreenRegions + breakClosedRegions + breakDoableRegions + breakOffScreenRegions
            ) {
                if (region.startEpochMillis in (nowMillis + 1) until horizon) add(region.startEpochMillis)
                if (region.endEpochMillis in (nowMillis + 1) until horizon) add(region.endEpochMillis)
            }
        }.distinct().sorted()
        // PRD §8/§15: a task doable during a screen break must also be one that needs no screen (the
        // invariant the edit window and the reducer enforce), so this is the accepted set of a pose's open
        // tail — stated as a conjunction here rather than assumed, so a payload that predates the invariant
        // cannot smuggle an on-screen task into a break.
        val breakDoable =
            ordered.filter { working.tasks[it]?.onScreen == false && working.tasks[it]?.doableDuringBreak == true }
        // PRD §15: the 15-minute pose's accepted set — every task that needs no screen, break-doable or not.
        val offScreenTasks = ordered.filter { working.tasks[it]?.onScreen == false }
        val windows = edges.mapIndexed { i, start ->
            val accepted =
                when {
                    // PRD §8: a grey period (inactivity, sleep) and a break's closed head both accept nobody.
                    covers(blockedRegions, start) -> emptyList()
                    covers(breakClosedRegions, start) -> emptyList()
                    covers(breakDoableRegions, start) -> breakDoable
                    covers(breakOffScreenRegions, start) -> offScreenTasks
                    // Outside every break the §9 screen switch classifies: an on-screen task only outside a
                    // no-screen period, an off-screen task only inside one.
                    else -> ordered.filter { (working.tasks[it]?.onScreen ?: true) != covers(noScreenRegions, start) }
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
        // ([SchedulerPlanner.replayClocks]); the field needs its own reach, past which an exclusion is felt no
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

        // `side-dev/scheduler_logic.py` `plan`: only obstacles still AHEAD bend the plan — what already
        // happened is history, not a blockage the timeline has to be compensated around. The past reaches the
        // walk through the CLOCKS, replayed the way the walk writes them (the forgetting included), which is
        // what makes an extension continue the plan it is extending instead of re-deriving a different one.
        planner.setField(futureBlocks, windows)
        val lookbackWant = 2.0 * planner.minPeriodMillis
        val replayStart =
            maxOf(planner.lookbackStart(windows, nowMillis, lookbackWant), pastAnchor)
                .coerceAtMost(nowMillis)
        val walk = planner.walk(planner.replayClocks(pastBlocks, windows, nowMillis, replayStart))
        // `_last_run`, NOT `_head`: a task that stopped and was not replaced never took a second turn, so the
        // walk must not refuse the very task the timeline left off with (see [SchedulerPlanner.lastRun]).
        //
        // PRD §7 "Switch task": an outstanding refusal ([liveForcedSwitchTask]) takes that seat instead. The
        // user asked for something else to start here, and "the task the timeline just left off with" is
        // already the walk's word for a task it must not pick now — so the refusal needs no rule of its own,
        // and inherits the right escape: a task nothing can replace still runs rather than the period being
        // left empty. It costs the refused task nothing else — its clock is untouched, and from the second
        // slot on it is an ordinary candidate again.
        walk.setLast(
            liveForcedSwitchTask(state.forcedSwitch, pastBlocks, nowMillis)
                ?: planner.lastRun(pastBlocks, nowMillis),
        )

        // PRD §13 "start this task now": the mirror image of the refusal above — the user named the task the
        // plan must place, so it takes the FIRST slot this fill picks rather than being kept out of it. It is
        // consumed there and nowhere else: the walk is charged for the slot exactly as if it had chosen it,
        // and everything after is the ordinary walk. A named task the current period will not have (a break,
        // a no-screen zone, a minimum that does not fit before the next cutting edge) simply loses its turn
        // here — the marker is not honoured by hunting for a later window, since "now" is the whole of what
        // was asked; the request then dies the ordinary way, as soon as another task has been served past it.
        var forcedStartTask = liveForcedStartTask(state.forcedStart, pastBlocks, nowMillis)

        val generated = mutableListOf<TaskPanel>()
        var cursor = nowMillis
        var index = 0
        var idCounter = 0
        // `side-dev/scheduler_logic.py` `free_tail`: whether the last thing placed was a freely-chosen slot, and so may be
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

        // --- phase 1 (`side-dev/scheduler_logic.py`): the disturbed part of the timeline ---
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
            val insideSuspend = insideBreak || covers(blockedRegions, cursor)
            if (pending != null && !insideSuspend && pending.first !in allowedHere) pending = null
            val resume = if (insideSuspend) null else pending
            // Inside a screen break the suspended task must not be the one that fills it (PRD §15).
            val candidates = if (insideSuspend) allowedHere.filter { it != pending?.first } else allowedHere
            // `side-dev/scheduler_logic.py` `_fits_from`: a task is a candidate only while its minimum fits
            // the room ahead of it, and that room COUNTS THE INSTANTS IT MAY ACTUALLY RUN — an interval nobody
            // may run in only suspends a run, so it is stepped over. The boundary that decides is therefore the
            // next **cutting** one — a fixed block or a screen-zone edge, and inside a break the break's own
            // end. A screen break's START is deliberately NOT one (PRD §15: it only suspends a chunk, which
            // resumes on the far side with its minimum intact). For a break's CLOSED parts that is the
            // reference's own rule; where this parts company with it is a pose's OPEN tail, which the reference
            // would treat as somebody else's return (`_clears`) and PRD §15 wants filled by the off-screen
            // tasks while the on-screen chunk waits. Inside a break the gap IS what remains of it, so PRD §9's
            // "never when its minimum exceeds the break's length" needs no special case.
            val nextZoneEdge =
                noScreenRegions.asSequence()
                    .flatMap { sequenceOf(it.startEpochMillis, it.endEpochMillis) }
                    .filter { it > cursor }.minOrNull()
            val breakEnd =
                if (insideSuspend) suspendRegions.first { it.endEpochMillis > cursor }.endEpochMillis else null
            val fitGap = listOfNotNull(nextBlock, nextZoneEdge, breakEnd).minOrNull()?.minus(cursor)
            val fitting = candidates.filter { fitGap == null || (minimumMillisOf[it] ?: 0L) <= fitGap }
            // `side-dev/scheduler_logic.py` steady_cycle's "no unplaceable crumb", applied to the walk: minimum times are
            // authored in whole minutes, so anything shorter than one is not a slot, it is a seam — e.g. the
            // 20-second look-away, or what is left of a chunk when a break lands a few seconds before it ends.
            val crumb = gap != null && gap < MILLIS_PER_MINUTE

            if (crumb || (resume == null && fitting.isEmpty())) {
                if (gap == null) break
                val tail = generated.lastOrNull()
                if (!insideSuspend && allowedHere.isNotEmpty() && freeTail && tail?.endEpochMillis == cursor) {
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
                if (!insideSuspend) pending = null
                continue
            }

            // PRD §13 "start this task now" — asked once, at the first slot the fill actually places.
            // A suspended chunk is never preempted (PRD §15 — it is mid-placement, not a fresh pick); at the
            // FIRST pick, which is the only one this can be, there is none.
            val forcedHere = forcedStartTask?.takeIf { resume == null && it in fitting }
            forcedStartTask = null
            val taskId = forcedHere ?: resume?.first ?: walk.pick(fitting) ?: break
            val boost = planner.boostAt(taskId, cursor)
            var need =
                resume?.second
                    ?: walk.chunkMillis(taskId, fitting, boost).roundToLong().coerceAtLeast(1L)
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
            if (!insideSuspend) {
                // PRD §15: only a screen break suspends a chunk. A fixed panel or a screen-zone edge
                // truncates it instead (PRD §9/§10: the minimum IS cut there).
                pending =
                    if (placed < need && need - placed >= MILLIS_PER_MINUTE &&
                        limit != null && limit in suspendStarts
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

        // --- phase 2 (`side-dev/scheduler_logic.py`): the context is frozen forever, so settle what is still owed and then
        // attach the analytic cycle — the "list of rules + repeat" of `side-dev/README.md` — unrolled out to the
        // horizon. Its shares are exact by construction, unlike the greedy's asymptotic ones. With screen
        // breaks enabled the context never freezes inside the horizon, so this simply never runs.
        if (frozen && cursor < horizon && index < maxPanels) {
            val allowedHere = accepted[windowAt(cursor)]
            if (allowedHere.isNotEmpty()) {
                val period = planner.periodOf(allowedHere)
                // PRD §13 "start this task now": the request is answered wherever the fill's first slot falls,
                // and on a timeline nothing disturbs (no screen breaks, no fixed blocks) that is HERE — phase 1
                // freezes before placing anything. Same act as there: the named task is emitted and charged
                // like any other pick, so the settle below and the cycle after it (phased off the walk) go on
                // from exactly the state the walk would have been in had it chosen the task itself.
                forcedStartTask?.takeIf { it in allowedHere }?.let { forced ->
                    val boost = planner.boostAt(forced, cursor)
                    val need = walk.chunkMillis(forced, allowedHere, boost).roundToLong().coerceAtLeast(1L)
                    val end = minOf(cursor + need, horizon)
                    if (end > cursor) {
                        emit(forced, cursor, end)
                        walk.serve(forced, (end - cursor).toDouble(), boost)
                        walk.relax((end - cursor).toDouble(), period, allowedHere)
                        cursor = end
                        index++
                    }
                }
                forcedStartTask = null
                val settleEnd = minOf(cursor + (SchedulerPlanner.SETTLE_PERIODS * period).roundToLong(), horizon)
                while (cursor < settleEnd && index < maxPanels && walk.spread(allowedHere) > period) {
                    val name = walk.pick(allowedHere) ?: break
                    val boost = planner.boostAt(name, cursor)
                    val need = walk.chunkMillis(name, allowedHere, boost).roundToLong().coerceAtLeast(1L)
                    val end = minOf(cursor + need, horizon)
                    if (end <= cursor) break
                    emit(name, cursor, end)
                    walk.serve(name, (end - cursor).toDouble(), boost)
                    walk.relax((end - cursor).toDouble(), period, allowedHere)
                    cursor = end
                    index++
                }
                // `side-dev/scheduler_logic.py` `_phase`: the cycle is attached in the phase the walk would have gone on
                // with. A cycle built from a blank slate always opens with the same task, so opening there
                // after a prefix that left another one starved hands the first task two slots in a row — one
                // block of twice the minimum, the coarse scale the model exists to avoid.
                val cycle =
                    planner.phaseCycle(
                        planner.steadyCycle(allowedHere)
                            .let { if (it.size > SchedulerPlanner.MAX_RULES) planner.coarseCycle(allowedHere) else it }
                            .filter { it.taskId != null && it.durationMillis > 0L },
                        walk,
                        allowedHere,
                    )
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
     * (`side-dev/README.md`), so advancing the now-line only *consumes* it, and materializing more of its tail
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
     *
     * The **dated** task trees are in it too, content and date alike — they are keyframes the fill reads
     * directly ([blendedTaskPriorities]), so editing one that is not on screen changes the plan exactly as
     * editing the live tree does. Undated trees are not: nothing reads them until they are selected, at
     * which point they *are* the live tree. Note this still leaves the plan a function of `now` through the
     * blend cursor, which is the one thing the signature cannot express — see [taskTreeBlendStep].
     */
    fun schedulingSignature(state: SchedulerState): Int {
        var result = if (state.automaticSchedule) 1 else 0
        result = 31 * result + state.sleep.hashCode()
        result = 31 * result + state.screenBreaks.hashCode()
        result = treeSignature(result, state.lists, state.cells, state.tasks)
        for (entry in datedTaskTrees(state)) {
            result = 31 * result + entry.id.value.hashCode()
            result = 31 * result + (entry.dateMillis?.hashCode() ?: 0)
            result = treeSignature(result, entry.tree.lists, entry.tree.cells, entry.tree.tasks)
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
     * The part of [schedulingSignature] that reads one tree — the priority-carrying structure (lists,
     * cells, weights) plus each task's scheduling attributes. Shared by the live tree and by every dated
     * task tree, so a keyframe is watched on exactly the same fields as the tree on screen.
     */
    private fun treeSignature(
        seed: Int,
        lists: Map<CellListId, org.example.project.scheduler.model.CellList>,
        cells: Map<CellId, org.example.project.scheduler.model.Cell>,
        tasks: Map<TaskId, Task>,
    ): Int {
        var result = seed
        for (list in lists.values.sortedBy { it.id.value }) {
            result = 31 * result + list.id.value.hashCode()
            result = 31 * result + list.cellIds.hashCode()
            result = 31 * result + list.weightColumns.hashCode()
        }
        for (cell in cells.values.sortedBy { it.id.value }) {
            result = 31 * result + cell.id.value.hashCode()
            result = 31 * result + (cell.taskId?.value?.hashCode() ?: 0)
            result = 31 * result + cell.priorityWeights.hashCode()
        }
        for (task in tasks.values.sortedBy { it.id.value }) {
            result = 31 * result + task.id.value.hashCode()
            result = 31 * result + task.title.hashCode()
            result = 31 * result + task.minimumMinutes
            result = 31 * result + (if (task.onScreen) 1 else 0)
            result = 31 * result + (if (task.doableDuringBreak) 1 else 0)
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
    internal fun defaultWeightAt(column: Int): Double = if (column == 0) 1.0 else 0.0

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

    /**
     * The titles under [taskId], read from its shared child list — the same structural source of truth
     * [isLeafTask] uses, since the denormalized [Task.childTaskIds] only tracks freshly-typed children and
     * goes stale. It is what names a task no cell points at in the Change Task menu (PRD §4), so it has to
     * hold for a sub-tree that arrived by paste or by a move as well as one that was typed.
     */
    fun childTitlesLabel(state: SchedulerState, taskId: TaskId): String {
        val task = state.tasks[taskId] ?: return ""
        val childList = task.childListId?.let { state.lists[it] }
        val titles =
            // No sub-list at all (a payload from before every titled task got one) is the only case with
            // nothing structural to read; a sub-list that is merely EMPTY means a leaf, and must read as one.
            if (childList == null) {
                task.childTaskIds.mapNotNull { state.tasks[it]?.title }
            } else {
                childList.cellIds.mapNotNull { cellId -> state.cells[cellId]?.taskId?.let { state.tasks[it]?.title } }
            }
        return titles.filter { it.isNotBlank() }.distinct().sorted().joinToString(", ")
    }

    data class ChangeTaskMenuEntry(
        /** `null` = "New task" row (creates a new [TaskId] when selected or while typing). */
        val taskId: TaskId?,
        val label: String,
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

    /**
     * PRD §4 *Presentation*: a menu row shows the task's shortest path in the tree — "**or a list of child
     * titles if no cells point to it**". A task no cell points at (a detached parent, [isDetachedParentTask],
     * or a tombstone kept for its records) has no place in the tree to name, and [shortestTaskTreePath] would
     * still report one off the denormalized [Task.childTaskIds], so name it by what it holds instead. A
     * cell-less task with nothing under it falls back to its own title, so no row is ever blank.
     */
    private fun changeTaskMenuLabel(state: SchedulerState, taskId: TaskId): String {
        val childLabel = childTitlesLabel(state, taskId)
        if (!taskHasCells(state, taskId)) {
            return childLabel.ifEmpty { state.tasks[taskId]?.title.orEmpty() }
        }
        val pathLabel = taskPathLabel(state, taskId)
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
     * PRD §4 **Default sub-tree** (the lateral-menu window, §7): the identity menu of one template node.
     *
     * A leading **"New id"** row — the node mints a brand-new task every time the template is applied — then
     * every existing user task whose title is exactly [draftText] and that still lives in the tree. It is the
     * calendar's menu shape ([calendarTaskMenuEntries]) rather than the cell's: a template node has no cell,
     * so there is no sibling list and no ancestor path to forbid; and unlike the calendar it does NOT require
     * a leaf, because binding to a parent is exactly how a node brings a whole existing sub-tree along.
     * Tombstones (tasks kept alive only by their records/panels) are excluded, like everywhere else.
     *
     * Picking a row here is what turns the node's switch **off**; picking "New id" turns it back on — see
     * [org.example.project.scheduler.model.DefaultSubtreeNode].
     */
    fun defaultSubtreeTaskMenuEntries(
        state: SchedulerState,
        draftText: String,
    ): List<ChangeTaskMenuEntry> {
        val matching = matchingUserTaskIds(state, draftText).filter { taskHasCells(state, it) }
        return buildList {
            add(ChangeTaskMenuEntry(taskId = null, label = DEFAULT_SUBTREE_NEW_ID_LABEL))
            for (taskId in matching) {
                add(ChangeTaskMenuEntry(taskId = taskId, label = changeTaskMenuLabel(state, taskId)))
            }
        }
    }

    /** The label of the "new id" row of a default-sub-tree node's identity menu (PRD §4). */
    const val DEFAULT_SUBTREE_NEW_ID_LABEL: String = "New id"

    /**
     * PRD §4: the template with everything that could never be grafted removed — **the blank title is what
     * deletes**, here as in the tree itself, so a blank-titled node goes and takes its children with it (they
     * have no cell to hang under). The editor keeps a trailing empty row per list only while its window is
     * open, exactly as the tree keeps the bottom cell of a sub-list; nothing else may hold one.
     *
     * Applied by the reducer on [org.example.project.scheduler.state.SchedulerIntent.SetDefaultSubtree] and
     * again on decode, so what is stored, synced, and grafted is always this form. It is safe against a title
     * being retyped through empty: the editor pushes its own copy back on the next keystroke.
     */
    fun normalizeDefaultSubtree(nodes: List<DefaultSubtreeNode>): List<DefaultSubtreeNode> =
        nodes.mapNotNull { node ->
            if (node.title.isBlank()) null else node.copy(children = normalizeDefaultSubtree(node.children))
        }

    /**
     * One row of a read-only rendering of a task's sub-tree: its title and the same for its children.
     * A plain title tree — nothing here identifies a cell, because this is a *preview* of what a sub-list
     * holds, not a slice of the tree the user can edit.
     */
    data class TaskOutlineNode(val title: String, val children: List<TaskOutlineNode>)

    /**
     * PRD §4: what a **default-sub-tree row bound to an existing task brings along** — that task's OWN
     * sub-tree. A sub-list belongs to the task id, not to the cell, so the template has no say in what
     * appears under such a row; the window draws this beneath it (greyed, uneditable) instead of the
     * template children it keeps but cannot apply.
     *
     * Blank-titled cells are skipped (a tombstone or the trailing empty cell of a sub-list is not something
     * the graft will produce). Depth is capped at [TASK_OUTLINE_MAX_DEPTH]: mirroring means the same task can
     * appear under many parents, and a preview must not be able to walk further than the user can read.
     */
    fun taskSubtreeOutline(
        state: SchedulerState,
        taskId: TaskId,
        depth: Int = 0,
    ): List<TaskOutlineNode> {
        if (depth >= TASK_OUTLINE_MAX_DEPTH) return emptyList()
        val listId = state.tasks[taskId]?.childListId ?: return emptyList()
        val list = state.lists[listId] ?: return emptyList()
        return list.cellIds.mapNotNull { cellId ->
            val childTaskId = state.cells[cellId]?.taskId ?: return@mapNotNull null
            val title = state.tasks[childTaskId]?.title.orEmpty()
            if (title.isBlank()) return@mapNotNull null
            TaskOutlineNode(title, taskSubtreeOutline(state, childTaskId, depth + 1))
        }
    }

    /** How deep [taskSubtreeOutline] descends before it stops. */
    private const val TASK_OUTLINE_MAX_DEPTH = 12

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

    /**
     * PRD §14 reminder title-suggestion menu: distinct reminder titles matching [input]. The title that IS
     * [input] is left out, exactly as the task menu's [titleSuggestions] does — picking it would only retype
     * what is already there. (The task-tree selector deliberately keeps it, see [taskTreeTitleSuggestions].)
     */
    fun reminderTitleSuggestions(state: SchedulerState, input: String): List<String> {
        val q = input.trim()
        return allReminderEntries(state).map { it.title }.distinct()
            .filter { it != input }
            .filter { q.isBlank() || it.contains(q, ignoreCase = true) }
    }

    /** PRD §14: the reminder id of the first known reminder with this exact [title], if any. */
    fun reminderIdForTitle(state: SchedulerState, title: String): String? =
        allReminderEntries(state).firstOrNull { it.title == title }?.id

    /** PRD §14: the title of the known reminder with this [id], if any. */
    fun reminderTitleForId(state: SchedulerState, id: String): String? =
        allReminderEntries(state).firstOrNull { it.id == id }?.title

    /**
     * The name a task tree carries by default: `tree-YYYY-MM-DD` for the local day [nowMillis] falls in.
     * It is both what a fresh account's first tree is titled (see
     * [org.example.project.scheduler.ui.TaskSchedulerViewModel.prepareLoadedState]) and the row the selector's
     * identity menu always leads with — one string for both, so on the day a tree was seeded that row *is*
     * that tree instead of offering to create a second one under the same name.
     */
    fun defaultTaskTreeTitle(
        nowMillis: Long,
        timeZone: TimeZone = TimeZone.currentSystemDefault(),
    ): String = "tree-" + Instant.fromEpochMilliseconds(nowMillis).toLocalDateTime(timeZone).date

    /**
     * One row of the task-tree selector's identity menu. [id] is the tree the row acts on, which is null both
     * on the "New task tree" row and on a [Kind.Today] row for a day that has no tree yet — so what a row
     * *means* is carried by [kind], never by `id == null`.
     */
    data class TaskTreeMenuEntry(
        val id: TaskTreeId?,
        val label: String,
        val kind: Kind = Kind.Existing,
    ) {
        enum class Kind {
            /** `tree-<today>`: opens that tree, or creates it when the day has none yet. */
            Today,

            /** "New task tree": creates one named after whatever is typed. */
            New,

            /** An existing tree whose title is similar to what is typed: opens it. */
            Existing,
        }
    }

    /**
     * Rows of the task-tree selector's identity menu — which the UI shows in *Change task tree* mode only, so
     * every row here acts on a tree (the [taskTreeTitleSuggestions] menu below it only fills the field):
     *  1. **`tree-<today>`** ([todayTitle], from [defaultTaskTreeTitle]) — always, whatever is typed and even
     *     on an empty field. That is the point of the dated default name: today's tree is one click away,
     *     opened when it exists and created only when it does not.
     *  2. **"New task tree"** — creates one under the typed name.
     *  3. Every *other* tree whose title is **similar** to the typed text (containment, most similar first),
     *     an empty field listing them all. Unlike the cell's Change Task menu this is deliberately not
     *     restricted to an exact match: it is how a tree gets opened, and requiring the full title first
     *     would make the menu useless for browsing.
     */
    fun taskTreeMenuEntries(
        state: SchedulerState,
        draftText: String,
        todayTitle: String,
    ): List<TaskTreeMenuEntry> {
        val q = draftText.trim()
        val today = state.taskTrees.firstOrNull { it.title.equals(todayTitle, ignoreCase = true) }
        return buildList {
            add(TaskTreeMenuEntry(id = today?.id, label = todayTitle, kind = TaskTreeMenuEntry.Kind.Today))
            add(TaskTreeMenuEntry(id = null, label = "New task tree", kind = TaskTreeMenuEntry.Kind.New))
            state.taskTrees
                .filter { it.id != today?.id && it.title.isNotBlank() }
                .filter { q.isEmpty() || it.title.contains(q, ignoreCase = true) }
                // Two stable sorts rather than one comparator: most similar first, ties alphabetical.
                .sortedBy { it.title }
                .sortedByDescending { titleSimilarity(it.title, q) }
                .forEach {
                    add(TaskTreeMenuEntry(id = it.id, label = it.title, kind = TaskTreeMenuEntry.Kind.Existing))
                }
        }
    }

    /**
     * Task-tree title suggestions (the selector's second menu), mirroring [titleSuggestions]: every tree
     * title containing [input], most similar first. An **empty** input lists them all, which is how the user
     * browses the existing trees.
     *
     * One deliberate difference from [titleSuggestions] / [reminderTitleSuggestions], which drop the title
     * that IS the typed text: here it is **kept** (and the UI highlights it). This menu is the browse list of
     * every tree, so hiding one because its name happens to be typed in full would take a tree off the list;
     * the highlighted row is also what Enter commits to, which is worth showing.
     */
    fun taskTreeTitleSuggestions(state: SchedulerState, input: String): List<String> {
        val q = input.trim()
        return state.taskTrees
            .map { it.title }
            .filter { it.isNotBlank() }
            .distinct()
            .filter { q.isEmpty() || it.contains(q, ignoreCase = true) }
            .sortedWith(compareByDescending<String> { titleSimilarity(it, q) }.thenBy { it })
    }

    /** The first task tree whose title is exactly [title], if any (what Enter in the selector commits to). */
    fun taskTreeIdForTitle(state: SchedulerState, title: String): TaskTreeId? {
        val q = title.trim()
        if (q.isEmpty()) return null
        return state.taskTrees.firstOrNull { it.title.equals(q, ignoreCase = true) }?.id
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
        // PRD §4: a childless task that loses all its cell pointers is purged *unless* it has a task
        // record (§8) — such tasks linger only to keep showing their recorded periods in the calendar.
        // A task referenced by a calendar panel is also kept, so a scheduled task deleted from the tree
        // survives until the next refresh cuts and records its in-progress period (§9).
        // A **detached parent** ([isDetachedParentTask]) is kept too: its sub-tree is real user data that
        // belongs to the task id, so re-assigning that id restores it.
        val withCells = taskIdsWithCells(state)
        val referenced =
            withCells +
                setOf(WellKnownIds.ROOT_TASK, WellKnownIds.MAIN_TASK) +
                state.tasks.filterValues { it.record.isNotEmpty() }.keys +
                state.panels.mapNotNull { it.taskId } +
                state.tasks.keys.filter { isDetachedParentTask(state, it, withCells) }
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
        // PRD §4: the root list, plus the sub-list of every **detached parent** — a titled task whose last
        // cell was re-pointed at another id ([isDetachedParentTask]). Its sub-tree is not reachable from the
        // root anymore, but it is not detached *from its task* either, so it is kept alive to come back with
        // the id. A cell emptied to delete it blanks the title, which is exactly what excludes it here.
        val withCells = taskIdsWithCells(state)
        val detachedRoots =
            state.tasks.keys.mapNotNull { taskId ->
                state.tasks[taskId]?.childListId?.takeIf { isDetachedParentTask(state, taskId, withCells) }
            }
        val queue = ArrayDeque(listOf(state.rootListId) + detachedRoots)
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
     * parents — together, the weight **table** of every sub-list the copy walks. With the deep-copy
     * window's "priority tables" switch off, [rowWeights] instead holds the single share the table
     * produced (`[0.375]` for 37.5 % of its sub-list) and [childHeader] stays the default one column, so
     * the paste rebuilds those percentages without the table that encoded them. [minMinutes] is the PRD §10 minimum time of this node's task (null when the clipboard text
     * carried no min-time appendix entry for it, e.g. a plain title tree, so paste keeps the default).
     *
     * [taskId] is the copied cell's own task **identity**, so pasting back into the tree lands on that very
     * task rather than on a duplicate of it (ADR 0012). Null when the clipboard text carried no id line —
     * a plain title tree, or a pre-1.6.0 payload — and paste then mints a fresh task as it always did.
     *
     * PRD §13 "copy" / "deep copy" additionally carry everything the cell's Edit window holds:
     * [noScreenDoable] (the switch — the task is done away from a screen, i.e. `!Task.onScreen`),
     * [scheduleUnit] and [text]. All three default to the *empty* value, which is also what a task the
     * clipboard said nothing about keeps, so they round-trip exactly and never need a null.
     */
    data class CopiedNode(
        val title: String,
        val children: List<CopiedNode>,
        val taskId: TaskId? = null,
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
     * PRD §4 separator between the tree section and the trailing title-keyed appendices, as builds before
     * 1.6.0 wrote them. A lone form-feed line. Nothing emits it any more — the clipboard text is now the
     * readable shape below — but [parseTreeText] still reads it, so text copied by an older build pastes.
     */
    const val COPY_SECTION_SEPARATOR: String = "\u000C"

    /**
     * PRD §13 "deep copy": the depth a fresh account starts at, and the value the window's **reset** button
     * returns to. A depth of 1 is the cell alone (what the menu's plain "copy" takes), 2 is the cell and its
     * children. The live value is [SchedulerState.deepCopyMaxDepth] — one number for the whole account, which
     * the deep-copy window edits and §4's Ctrl+C / Ctrl+X then copy by without asking.
     */
    const val DEEP_COPY_DEFAULT_DEPTH: Int = 20

    /** The deep-copy depth the window (and therefore the account setting) accepts. */
    val DEEP_COPY_DEPTH_RANGE: IntRange = 1..999

    /**
     * PRD §4 `Ctrl+C` / `Ctrl+X`: the whole sub-tree, however deep it runs. The account's
     * [SchedulerState.deepCopyMaxDepth] is the **deep-copy window's** number — the chord asks nobody and
     * cuts nothing off.
     */
    const val FULL_SUBTREE_DEPTH: Int = Int.MAX_VALUE

    /**
     * PRD §13 deep-copy window: **what** a copy carries, beside how deep it goes. Three switches, one
     * account-wide answer each ([SchedulerState.copyIncludeIds], [SchedulerState.copyPriorityTables],
     * [SchedulerState.copyIncludeText]) — the window edits them and every copy in the app then obeys them,
     * exactly as the depth setting already worked. Editing them in the window and finding `Ctrl+C` still
     * carrying what they turned off is the drift the one-answer-per-account rule exists to prevent.
     *
     * - [includeIds] off ⇒ no `- id:` line. The copy is then indistinguishable from text the app did not
     *   write, which is the point: it pastes back as **new** tasks (and, PRD §7, seeds the default sub-tree
     *   under the leaves it mints), never as a mirror or a restore of the tasks it came from.
     * - [priorityTables] off ⇒ the sub-list weight **tables** (the cell's weight row and the header of the
     *   sub-list it parents) are replaced by the one number they exist to produce: the cell's **percentage
     *   of its own sub-list**. Pasting that back reproduces the percentages — a single weight column whose
     *   values are the shares — but not the table that happened to encode them.
     * - [includeText] off ⇒ no `- text:` block. Everything else the edit window holds still travels.
     */
    data class CopyOptions(
        val includeIds: Boolean = true,
        val priorityTables: Boolean = true,
        val includeText: Boolean = true,
    ) {
        companion object {
            /** The account's answers (what every copy uses unless a caller says otherwise). */
            fun from(state: SchedulerState): CopyOptions =
                CopyOptions(
                    includeIds = state.copyIncludeIds,
                    priorityTables = state.copyPriorityTables,
                    includeText = state.copyIncludeText,
                )
        }
    }

    /**
     * An attribute line's marker. Everything indented one level under a title line and starting with it
     * describes that task; anything else at that indent is a child task. A title that really starts with
     * `- ` is escaped (`\- `), so a task can never be read as one of its own attributes.
     */
    private const val ATTR_MARKER: String = "- "

    // The attribute names. They are prose on purpose — the clipboard text is meant to be read by a human —
    // and this is their only copy: the parser matches against these same constants.
    private const val ATTR_ID: String = "id"
    private const val ATTR_MIN_TIME: String = "minimum time"
    private const val ATTR_NO_SCREEN: String = "can be done during a no-screen period"
    private const val ATTR_WEIGHTS: String = "priority weights"
    private const val ATTR_COLUMNS: String = "sub-list weight columns"
    private const val ATTR_SHARE: String = "priority in its sub-list"
    private const val ATTR_UNIT: String = "schedule unit"
    private const val ATTR_TEXT: String = "text"

    /** True when the cell points at a task with a non-blank title (a real, copyable cell). */
    private fun isPopulated(state: SchedulerState, cellId: CellId): Boolean {
        val taskId = state.cells[cellId]?.taskId ?: return false
        return state.tasks[taskId]?.title?.isNotBlank() == true
    }

    /**
     * Build the copied subtree rooted at [cellId] from the task's shared child list (populated cells).
     * [remainingDepth] counts the levels still to take, this node included — so 1 stops here.
     *
     * [options] is what the copy carries (PRD §13): the id and the text are simply dropped when their
     * switch is off, and with [CopyOptions.priorityTables] off the weight table is replaced by the single
     * number it produces — the cell's **share of its own sub-list**, stored as its one weight so that
     * pasting the forest back reproduces exactly those percentages.
     */
    private fun copiedSubtree(
        state: SchedulerState,
        cellId: CellId,
        remainingDepth: Int,
        options: CopyOptions,
    ): CopiedNode {
        val cell = state.cells[cellId]
        val taskId = cell?.taskId
        val task = taskId?.let { state.tasks[it] }
        val title = task?.title.orEmpty()
        val childList = task?.childListId?.let { state.lists[it] }
        val rowWeights =
            if (options.priorityTables) cell?.priorityWeights ?: DEFAULT_WEIGHTS
            else listOf(roundedShare(RelativePriorityDomain.cellShare(state, cellId)))
        // Without the tables there is no header to carry: the shares above ARE the single column.
        val childHeader = if (options.priorityTables) childList?.weightColumns ?: DEFAULT_WEIGHTS else DEFAULT_WEIGHTS
        val children =
            if (remainingDepth <= 1) emptyList()
            else childList?.cellIds.orEmpty()
                .filter { isPopulated(state, it) }
                .map { copiedSubtree(state, it, remainingDepth - 1, options) }
        return CopiedNode(
            title = title,
            children = children,
            taskId = taskId.takeIf { options.includeIds },
            rowWeights = rowWeights,
            childHeader = childHeader,
            minMinutes = task?.minimumMinutes,
            noScreenDoable = task?.onScreen == false,
            scheduleUnit = task?.scheduleUnit.orEmpty(),
            text = if (options.includeText) task?.text.orEmpty() else "",
        )
    }

    /**
     * PRD §13 cell contextual menu "copy" / "deep copy": the cells' tasks serialized to the same text
     * [copyTreeText] produces — so they paste back (Ctrl+V) with their schedule unit, their text, their
     * no-screen switch, their minimum time and their weight row restored.
     *
     * [maxDepth] is how many levels are taken, each cell itself counting as the first: 1 is "copy" (the
     * cells alone), the deep-copy window's own number is anything above, and [FULL_SUBTREE_DEPTH] is §4's
     * Ctrl+C. Empty when nothing in [cellIds] holds a titled task, or when [maxDepth] is below 1 (there is
     * then nothing to copy).
     *
     * [options] defaults to the account's three switches — what the deep-copy window last asked for.
     */
    fun copyCellsText(
        state: SchedulerState,
        cellIds: List<CellId>,
        maxDepth: Int = 1,
        options: CopyOptions = CopyOptions.from(state),
    ): String {
        if (maxDepth < 1) return ""
        val nodes = cellIds.filter { isPopulated(state, it) }.map { copiedSubtree(state, it, maxDepth, options) }
        if (nodes.isEmpty()) return ""
        return renderCopiedNodes(nodes, options)
    }

    /**
     * PRD §13: the cells a right-click's "copy" / "deep copy" acts on. Right-clicking **inside** a
     * multi-selection copies the whole block — the very block §4's Ctrl+C takes, so the menu and the chord
     * never disagree — while a right-click on a cell outside the selection copies that cell alone.
     */
    fun contextMenuCopyTargets(
        state: SchedulerState,
        selection: SchedulerSelection,
        cellId: CellId,
    ): List<CellId> {
        if (!isInActiveSelection(selection, cellId)) return listOf(cellId)
        val block = orderedActiveSelectionInList(state, selection)?.second.orEmpty()
        return if (cellId in block) block else listOf(cellId)
    }

    /**
     * PRD §13 deep-copy window: the titles along ONE path down to the deepest level a copy of [maxDepth]
     * levels would reach — the deepest branch under whichever of [cellIds] reaches furthest, cut to
     * [maxDepth] entries (that cell's own title being the first). Shorter when the sub-tree runs out first,
     * empty when none of them holds a titled task. The window prints it so the number reads as a real place
     * in the tree, and taking the deepest of the copied cells keeps it the path the depth actually bites on.
     */
    fun deepCopyPathTitles(state: SchedulerState, cellIds: List<CellId>, maxDepth: Int): List<String> {
        if (maxDepth < 1) return emptyList()
        val path = ArrayList<String>()
        // A mirrored task cannot contain itself, but the walk is guarded anyway: a cycle here would be an
        // unbounded descent on the UI thread, and the window redraws it on every keystroke.
        val seen = HashSet<CellId>()
        var current: CellId? =
            cellIds
                .filter { isPopulated(state, it) }
                .maxByOrNull { subtreeHeight(state, it, maxDepth, seen) }
        while (current != null && path.size < maxDepth && seen.add(current)) {
            val task = state.cells[current]?.taskId?.let { state.tasks[it] } ?: break
            path.add(task.title)
            val children =
                task.childListId?.let { state.lists[it] }?.cellIds.orEmpty()
                    .filter { isPopulated(state, it) && it !in seen }
            // The deepest branch — measured over the whole depth asked for, not just the levels
            // still to show, so raising the number EXTENDS the path instead of switching branches.
            current = children.maxByOrNull { subtreeHeight(state, it, maxDepth, seen) }
        }
        return path
    }

    /** Height of the populated sub-tree at [cellId] (a leaf is 1), never counted beyond [limit]. */
    private fun subtreeHeight(state: SchedulerState, cellId: CellId, limit: Int, seen: Set<CellId>): Int {
        if (limit <= 1) return 1
        val task = state.cells[cellId]?.taskId?.let { state.tasks[it] } ?: return 1
        val children =
            task.childListId?.let { state.lists[it] }?.cellIds.orEmpty()
                .filter { isPopulated(state, it) && it !in seen }
        if (children.isEmpty()) return 1
        return 1 + children.maxOf { subtreeHeight(state, it, limit - 1, seen) }
    }

    private fun formatWeights(weights: List<Double>): String = weights.joinToString(", ") { it.toString() }

    /**
     * A cell's share of its sub-list, rounded to the two decimals of a percentage the clipboard prints
     * (PRD §13, "priority tables off"). Rounding here rather than at the render keeps the copy and the
     * paste at the same number: the text says what the node holds, so a second round trip changes nothing.
     */
    private fun roundedShare(share: Double): Double = (share * 10_000.0).roundToInt() / 10_000.0

    /** `0.375` → `37.5`, with no trailing `.0` — the percentage as a person would write it. */
    private fun formatSharePercent(share: Double): String {
        val percent = (share * 10_000.0).roundToInt() / 100.0
        val whole = percent.toLong()
        return if (percent == whole.toDouble()) whole.toString() else percent.toString()
    }

    /** `37.5 %` (or `37.5`) → `0.375`; null when it is not a number, so the paste is a no-op. */
    private fun parseSharePercent(value: String): Double? {
        val percent = value.removeSuffix("%").trim().toDoubleOrNull() ?: return null
        if (percent < 0.0) return null
        return roundedShare(percent / 100.0)
    }

    /**
     * PRD §4 Copy: the selected cells' subtrees serialized to the app's clipboard text (see
     * [renderCopiedNodes] for the shape). Uses the consecutive selection block when there is one, otherwise
     * the main selection. Empty when nothing populated is selected.
     *
     * Ctrl+C copies the **entire** sub-tree and asks nothing: [maxDepth] defaults to [FULL_SUBTREE_DEPTH].
     * The account's [SchedulerState.deepCopyMaxDepth] belongs to the deep-copy window — the chord is the
     * gesture for "all of it", and the window is the one that cuts a copy short. *What* each node carries
     * is still the account's ([CopyOptions.from]), so the window's three switches govern the chord too.
     */
    fun copyTreeText(
        state: SchedulerState,
        selection: SchedulerSelection,
        maxDepth: Int = FULL_SUBTREE_DEPTH,
        options: CopyOptions = CopyOptions.from(state),
    ): String = copyCellsText(state, copyTreeTargets(state, selection), maxDepth, options)

    /**
     * The cells §4's Ctrl+C / Ctrl+X act on: the consecutive selection block when there is one, otherwise
     * the main selection alone. The cut needs the same list the copy took, so both read it from here.
     */
    fun copyTreeTargets(state: SchedulerState, selection: SchedulerSelection): List<CellId> =
        orderedActiveSelectionInList(state, selection)?.second
            ?: selection.main?.let { listOf(it) }.orEmpty()

    /**
     * Serialize a copied forest to the clipboard text [parseTreeText] reads back. **It has to be readable**
     * (PRD §4/§13): what lands in the clipboard is the user's copy of their task as much as it is the app's,
     * so every field is a named line in prose rather than a packed token, and a multi-line task text is
     * carried as its own indented block instead of an escaped one-liner.
     *
     * A tab-indented title line per task, then — one level deeper — one `- <name>: <value>` line per thing
     * the cell holds (its task id, its minimum time, the edit window's screen switch, its priority-weight row, the
     * weight columns of the sub-list it parents), the schedule unit as one `- <step>: <n> min` line per
     * step a level deeper still, and the task text verbatim under `- text:`. The task's children follow at
     * the title line's own indent + 1, after its attributes.
     *
     * Only the id and the minimum time are always written; everything else is omitted at its default value,
     * so an ordinary task stays a title and two lines. [options] drops what the deep-copy window's switches
     * turned off, and swaps the weight table for its percentage.
     */
    private fun renderCopiedNodes(nodes: List<CopiedNode>, options: CopyOptions = CopyOptions()): String {
        val sb = StringBuilder()
        fun line(depth: Int, content: String) {
            repeat(depth) { sb.append('\t') }
            sb.append(content).append('\n')
        }
        fun attribute(depth: Int, name: String, value: String) = line(depth, "$ATTR_MARKER$name: $value")
        fun render(ns: List<CopiedNode>, depth: Int) {
            for (n in ns) {
                line(depth, escapeTitleField(n.title))
                val d = depth + 1
                // The identity first: pasting back into the tree lands on this very task (ADR 0012).
                n.taskId?.let { attribute(d, ATTR_ID, it.value) }
                attribute(d, ATTR_MIN_TIME, "${n.minMinutes ?: DEFAULT_MINIMUM_MINUTES} min")
                // PRD §13 edit-window switch. Only the non-default (off-screen) value is written, so an
                // ordinary on-screen task says nothing about a screen — exactly as its window reads.
                if (n.noScreenDoable) attribute(d, ATTR_NO_SCREEN, "yes")
                // PRD §13: the whole weight table of every sub-list travels — this cell's value row, and
                // the weight columns of the sub-list it parents — unless the window's switch asked for the
                // percentage those two produce instead, which is written on every node (it IS the copy).
                if (options.priorityTables) {
                    if (n.rowWeights != DEFAULT_WEIGHTS) attribute(d, ATTR_WEIGHTS, formatWeights(n.rowWeights))
                    if (n.children.isNotEmpty() && n.childHeader != DEFAULT_WEIGHTS) {
                        attribute(d, ATTR_COLUMNS, formatWeights(n.childHeader))
                    }
                } else {
                    attribute(d, ATTR_SHARE, "${formatSharePercent(n.rowWeights.firstOrNull() ?: 1.0)} %")
                }
                if (n.scheduleUnit.isNotEmpty()) {
                    line(d, "$ATTR_MARKER$ATTR_UNIT:")
                    for (step in n.scheduleUnit) {
                        line(d + 1, "$ATTR_MARKER${escapeField(step.title)}: ${step.spanMinutes} min")
                    }
                }
                if (n.text.isNotEmpty()) {
                    line(d, "$ATTR_MARKER$ATTR_TEXT:")
                    // Verbatim, one level deeper: an empty line keeps the indent so it stays part of the
                    // block. This is the whole point of the format — the note reads as the note.
                    for (textLine in n.text.split('\n')) line(d + 1, textLine)
                }
                render(n.children, depth + 1)
            }
        }
        render(nodes, 0)
        return sb.toString()
    }

    private fun parseWeights(csv: String): List<Double>? {
        if (csv.isBlank()) return null
        val result = ArrayList<Double>()
        for (part in csv.split(',')) result.add(part.trim().toDoubleOrNull() ?: return null)
        return result
    }

    /** How many leading tabs [line] carries — its depth in the serialized tree. */
    private fun indentOf(line: String): Int {
        var i = 0
        while (i < line.length && line[i] == '\t') i++
        return i
    }

    /**
     * PRD §4 Paste: parse the app's clipboard text (see [renderCopiedNodes]) into a forest carrying the
     * priority weight values, the per-task minimum times and everything the edit window holds — or null
     * when [text] is not in that format (an unknown attribute, an unparseable value, a real tab inside a
     * title, an indentation jump of more than one level, or nothing populated). The strictness is what
     * makes paste a no-op for arbitrary clipboard text. A plain tab-indented title tree still parses —
     * weights default and min-times stay null, so paste leaves them at their defaults.
     */
    fun parseTreeText(text: String): List<CopiedNode>? {
        if (text.isBlank()) return null
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        // A form-feed section line is the pre-1.6.0 shape (tree + title-keyed appendices). Nothing writes
        // it any more, but a clipboard filled by an older build must still paste.
        if (lines.any { it == COPY_SECTION_SEPARATOR }) return parseLegacyTreeText(lines)
        return parseReadableTreeText(lines)
    }

    private fun parseReadableTreeText(lines: List<String>): List<CopiedNode>? {
        val entries = ArrayList<MutableCopiedNode>()
        val depths = ArrayList<Int>()
        var i = 0
        while (i < lines.size) {
            val depth = indentOf(lines[i])
            val rest = lines[i].substring(depth)
            if (rest.isBlank()) {
                i++
                continue
            }
            // An attribute with no task above it, or a real tab inside a title (a spreadsheet row pasted
            // in) → not our format, so paste stays a no-op.
            if (rest.startsWith(ATTR_MARKER) || rest.contains('\t')) return null
            val node = MutableCopiedNode(title = unescapeField(rest))
            entries.add(node)
            depths.add(depth)
            i++
            // Everything marked and indented one level under the title describes THIS task.
            while (i < lines.size) {
                val d = indentOf(lines[i])
                val body = lines[i].substring(d)
                if (body.isBlank()) {
                    i++
                    continue
                }
                if (d != depth + 1 || !body.startsWith(ATTR_MARKER)) break
                i++
                val field = body.removePrefix(ATTR_MARKER)
                when (field) {
                    "$ATTR_UNIT:" -> {
                        // One `- <step>: <n> min` line per step, in order, a level deeper.
                        while (i < lines.size) {
                            val sd = indentOf(lines[i])
                            val step = lines[i].substring(sd)
                            if (step.isBlank()) {
                                i++
                                continue
                            }
                            if (sd != depth + 2 || !step.startsWith(ATTR_MARKER)) break
                            node.scheduleUnit.add(parseUnitStep(step.removePrefix(ATTR_MARKER)) ?: return null)
                            i++
                        }
                    }
                    "$ATTR_TEXT:" -> {
                        // The verbatim block: every line indented deeper than the marker, that indent
                        // stripped. A child task sits one level *shallower*, so it is never swallowed.
                        val textLines = ArrayList<String>()
                        while (i < lines.size && indentOf(lines[i]) >= depth + 2) {
                            textLines.add(lines[i].substring(depth + 2))
                            i++
                        }
                        node.text = textLines.joinToString("\n")
                    }
                    else -> if (!applyAttribute(node, field)) return null
                }
            }
        }
        return assembleForest(entries, depths)
    }

    /** Apply one `<name>: <value>` attribute to [node]; false when it is not one we write. */
    private fun applyAttribute(node: MutableCopiedNode, field: String): Boolean {
        val colon = field.indexOf(':')
        if (colon < 0) return false
        val name = field.substring(0, colon)
        val value = field.substring(colon + 1).trim()
        when (name) {
            // Only the shape the app itself mints: anything else is not our clipboard text, and paste
            // must stay a no-op rather than build a task under an id the tree reserves (root/main).
            ATTR_ID -> node.taskId = TaskId(value).takeIf { isUserTaskId(it) } ?: return false
            ATTR_MIN_TIME -> node.minMinutes = value.removeSuffix(" min").trim().toIntOrNull() ?: return false
            ATTR_NO_SCREEN -> node.noScreenDoable = when (value) {
                "yes" -> true
                "no" -> false
                else -> return false
            }
            ATTR_WEIGHTS -> node.rowWeights = parseWeights(value) ?: return false
            ATTR_COLUMNS -> node.childHeader = parseWeights(value) ?: return false
            // The percentage form of the two above (the deep-copy window's "priority tables" switch, off).
            // It lands as the node's single weight, so a sub-list of shares rebuilds those very shares.
            ATTR_SHARE -> node.rowWeights = listOf(parseSharePercent(value) ?: return false)
            else -> return false
        }
        return true
    }

    /** `<step title>: <n> min` — the title is taken up to the LAST colon, so a title may hold one. */
    private fun parseUnitStep(field: String): ScheduleUnitEntry? {
        if (!field.endsWith(" min")) return null
        val head = field.dropLast(" min".length)
        val colon = head.lastIndexOf(':')
        if (colon < 0) return null
        val span = head.substring(colon + 1).trim().toIntOrNull() ?: return null
        return ScheduleUnitEntry(unescapeField(head.substring(0, colon)), span)
    }

    /** Nest a flat list of nodes by their indent; null on a jump of more than one level, or on nothing. */
    private fun assembleForest(entries: List<MutableCopiedNode>, depths: List<Int>): List<CopiedNode>? {
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
        return roots.map { it.toImmutable() }
    }

    /**
     * The pre-1.6.0 clipboard shape: a tab-indented tree whose lines carry `w=` / `h=` / `ns=` fields, then
     * [COPY_SECTION_SEPARATOR]-delimited appendices keyed by task title (minimum times, schedule units,
     * texts). Kept read-only so a clipboard filled by an older build still pastes; nothing writes it.
     */
    private fun parseLegacyTreeText(allLines: List<String>): List<CopiedNode>? {
        val sections = ArrayList<List<String>>()
        var start = 0
        for (i in allLines.indices) {
            if (allLines[i] == COPY_SECTION_SEPARATOR) {
                sections.add(allLines.subList(start, i))
                start = i + 1
            }
        }
        sections.add(allLines.subList(start, allLines.size))

        // Appendix 1: `<escaped title>\t<minutes>` per distinct task. A malformed line → not our format.
        val minByTitle = HashMap<String, Int>()
        for (line in sections.getOrElse(1) { emptyList() }) {
            if (line.isBlank()) continue
            val tab = line.indexOf('\t')
            if (tab < 0) return null
            val minutes = line.substring(tab + 1).toIntOrNull() ?: return null
            minByTitle[unescapeField(line.substring(0, tab))] = minutes
        }

        // Appendix 2: `<escaped task>\t<escaped step title>\t<minutes>`, one line per step, in order.
        val unitByTitle = HashMap<String, MutableList<ScheduleUnitEntry>>()
        for (line in sections.getOrElse(2) { emptyList() }) {
            if (line.isBlank()) continue
            val fields = line.split('\t')
            if (fields.size != 3) return null
            val span = fields[2].toIntOrNull() ?: return null
            unitByTitle.getOrPut(unescapeField(fields[0])) { ArrayList() }
                .add(ScheduleUnitEntry(unescapeField(fields[1]), span))
        }

        // Appendix 3: `<escaped task>\t<escaped text>` — the text escaped onto one line.
        val textByTitle = HashMap<String, String>()
        for (line in sections.getOrElse(3) { emptyList() }) {
            if (line.isBlank()) continue
            val tab = line.indexOf('\t')
            if (tab < 0) return null
            textByTitle[unescapeField(line.substring(0, tab))] = unescapeField(line.substring(tab + 1))
        }

        val entries = ArrayList<MutableCopiedNode>()
        val depths = ArrayList<Int>()
        for (line in sections[0]) {
            val depth = indentOf(line)
            val rest = line.substring(depth)
            if (rest.isBlank()) continue
            val fields = rest.split('\t')
            val node = MutableCopiedNode(title = unescapeField(fields[0]))
            for (field in fields.drop(1)) {
                when {
                    field.startsWith("w=") -> node.rowWeights = parseWeights(field.removePrefix("w=")) ?: return null
                    field.startsWith("h=") -> node.childHeader = parseWeights(field.removePrefix("h=")) ?: return null
                    field.startsWith("ns=") ->
                        node.noScreenDoable = when (field.removePrefix("ns=")) {
                            "1" -> true
                            "0" -> false
                            else -> return null
                        }
                    else -> return null // a real tab in content / unknown field → not our format
                }
            }
            node.minMinutes = minByTitle[node.title]
            node.scheduleUnit.addAll(unitByTitle[node.title].orEmpty())
            node.text = textByTitle[node.title].orEmpty()
            entries.add(node)
            depths.add(depth)
        }
        return assembleForest(entries, depths)
    }

    private class MutableCopiedNode(
        val title: String,
        val children: MutableList<MutableCopiedNode> = mutableListOf(),
        var rowWeights: List<Double> = listOf(1.0),
        var childHeader: List<Double> = listOf(1.0),
        var noScreenDoable: Boolean = false,
        var taskId: TaskId? = null,
        var minMinutes: Int? = null,
        val scheduleUnit: MutableList<ScheduleUnitEntry> = mutableListOf(),
        var text: String = "",
    ) {
        fun toImmutable(): CopiedNode =
            CopiedNode(
                title = title,
                children = children.map { it.toImmutable() },
                taskId = taskId,
                rowWeights = rowWeights,
                childHeader = childHeader,
                minMinutes = minMinutes,
                noScreenDoable = noScreenDoable,
                scheduleUnit = scheduleUnit.toList(),
                text = text,
            )
    }

    /** Escapes a title onto one line, and past a leading [ATTR_MARKER] that would read as an attribute. */
    private fun escapeTitleField(s: String): String {
        val escaped = escapeField(s)
        return if (escaped.startsWith(ATTR_MARKER)) "\\$escaped" else escaped
    }

    /** Escapes a tab-separated field (a title, a schedule-unit step name) onto one line. */
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
