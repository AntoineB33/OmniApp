package org.example.project.scheduler.domain

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.time.Instant
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import org.example.project.scheduler.model.CellId
import org.example.project.scheduler.model.CellListId
import org.example.project.scheduler.model.ChoreEntry
import org.example.project.scheduler.model.DEFAULT_MINIMUM_MINUTES
import org.example.project.scheduler.model.ForcedTaskStart
import org.example.project.scheduler.model.ForcedTaskSwitch
import org.example.project.scheduler.model.ScheduleUnitEntry
import org.example.project.scheduler.model.ScreenBreak
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

    /**
     * One cell showing a task, together with the [ancestors] chain the tree descends through to reach it
     * (outermost first, the cell itself excluded) — exactly the pair
     * [org.example.project.scheduler.state.SchedulerIntent.RevealCell] takes.
     */
    data class TaskOccurrence(val cellId: CellId, val ancestors: List<CellId>)

    /**
     * PRD §8 "go to task tree": the **first** cell showing [taskId], or `null` when no cell does.
     *
     * "First" is the tree's own reading order — the first row the user would see once the ancestors are
     * expanded — so the walk is depth-first and **visits each LIST once**, the same walk (and the same
     * reason) as [org.example.project.scheduler.domain.TaskTreeSearch.matches]: a sub-list belongs to the
     * task id, so a mirrored sub-tree is ONE list under many parents and re-entering it per occurrence is
     * exponential. The two are kept apart only because a search hit is a range inside a title and this is a
     * task; neither walks anything the other does not.
     *
     * `null` is a real answer, not an error path: a panel outlives the cell that laid it (panels are not
     * per-tree, §7), so it may name a **detached parent**, a task blanked by §4's deletion, or a task
     * another named task tree owns.
     */
    fun firstTaskOccurrence(state: SchedulerState, taskId: TaskId): TaskOccurrence? {
        val visitedLists = mutableSetOf<CellListId>()

        fun walk(listId: CellListId, ancestors: List<CellId>): TaskOccurrence? {
            if (!visitedLists.add(listId)) return null
            val list = state.lists[listId] ?: return null
            for (cellId in list.cellIds) {
                val cell = state.cells[cellId] ?: continue
                val cellTaskId = cell.taskId ?: continue
                // A blank-titled cell is PRD §4's deleted one — an empty placeholder, drawn with no title
                // and no expand arrow. It is not a row to go to, and nothing under it could be brought on
                // screen anyway (the reveal will not expand it), so the walk neither matches nor descends
                // there. That is what makes a panel naming a blanked task answer "not in the tree", which
                // is exactly what it is.
                if (isTextuallyEmptyCell(state, cellId)) continue
                if (cellTaskId == taskId && isSelectableCell(state, cellId)) {
                    return TaskOccurrence(cellId, ancestors)
                }
                val childListId = state.tasks[cellTaskId]?.childListId ?: continue
                walk(childListId, ancestors + cellId)?.let { return it }
            }
            return null
        }

        return walk(state.rootListId, emptyList())
    }

    /**
     * [firstTaskOccurrence] for **every** task at once, in one walk.
     *
     * PRD §7's "All tasks" window needs the first occurrence of each of its rows to draw them as the tree's
     * own cells, and asking one task at a time would re-walk the tree per task — O(tasks × tree) on every
     * recomposition, which is exactly the display cost ADR 0009 forbids. Same walk, same rules (depth-first,
     * each LIST visited once, a blank-titled cell neither matched nor descended into), so the two can never
     * disagree about which cell "the first occurrence" is.
     */
    fun firstTaskOccurrences(state: SchedulerState): Map<TaskId, TaskOccurrence> {
        val found = LinkedHashMap<TaskId, TaskOccurrence>()
        val visitedLists = mutableSetOf<CellListId>()

        fun walk(listId: CellListId, ancestors: List<CellId>) {
            if (!visitedLists.add(listId)) return
            val list = state.lists[listId] ?: return
            for (cellId in list.cellIds) {
                val cell = state.cells[cellId] ?: continue
                val cellTaskId = cell.taskId ?: continue
                if (isTextuallyEmptyCell(state, cellId)) continue
                if (cellTaskId !in found && isSelectableCell(state, cellId)) {
                    found[cellTaskId] = TaskOccurrence(cellId, ancestors)
                }
                val childListId = state.tasks[cellTaskId]?.childListId ?: continue
                walk(childListId, ancestors + cellId)
            }
        }

        walk(state.rootListId, emptyList())
        return found
    }

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
     * PRD §7 lateral menu ("All tasks"): the three figures that window may order its rows by.
     *
     * All three are readouts of the tree the user is editing, never of the scheduler's blended view — see
     * [TaskListEntry].
     */
    enum class TaskListSort {
        /** How many cells of the tree point at the task (a mirrored task counts once per cell). */
        Occurrences,

        /** The task's absolute priority share — the same number its rows show in the tree. */
        Priority,

        /**
         * How alike the task's title is to another task's — [TitleSimilarity], the answer to "what have I
         * written down twice?". Unlike the two above it is not a property of the task at all but of the
         * task *against the rest of the list*, which is why it is the one figure that is computed only when
         * it is asked for.
         */
        Similarity,
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
        /**
         * How alike this title is to the other listed tasks' — `null` unless [TaskListSort.Similarity] asked
         * for it, since it costs a pass over every pair of tasks and nothing else on this path reads it.
         */
        val similarity: TitleSimilarity? = null,
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
     * **"Not in the tree" is [firstTaskOccurrences], not "has no cell",** and the two are different for a
     * reason that is easy to miss: a detached parent keeps its whole **sub-tree** alive (that is what
     * assigning its id back restores), so the tasks inside it still have cells — cells the tree cannot
     * display anywhere, since nothing reachable from the root descends into that sub-list. Counting "has a
     * populated cell" listed those, and [org.example.project.ui.TaskListWindow] then dropped them again when
     * it asked this same walk for their row cells — a row silently missing from a window whose own sort had
     * already counted it. Membership is therefore this walk, so the list is exactly the set of tasks whose
     * "go to task" (PRD §4) / "go to task tree" (PRD §8) has somewhere to go.
     *
     * Deliberately NOT filtered: the occurrence **count** of a listed task still counts every populated cell
     * pointing at it, unreachable ones included, because that is the occurrence the percentage is divided
     * over. A task with one row in the tree and one stranded cell reads as 2 occurrences, and changing that
     * here would make the two columns disagree.
     *
     * Ties fall back to the title and then the id so the order is total: without that, the many tasks
     * sharing 0 % (or one occurrence) would be free to shuffle between recompositions. That final fallback
     * is deliberately **not** reversed with [descending], so flipping the direction never re-shuffles a
     * block sharing one figure.
     *
     * [TaskListSort.Similarity] is the one figure that is a fact about the *list* rather than about a task,
     * so it is measured here and only here — and only when it is the sort asked for, because it costs a pass
     * over every pair of titles (ADR 0009). Its order is the PRD's: the **best** score first, and among
     * tasks sharing one best score, the one that **reaches it against the most other tasks**.
     */
    fun taskListEntries(
        state: SchedulerState,
        sort: TaskListSort = TaskListSort.Priority,
        descending: Boolean = true,
    ): List<TaskListEntry> {
        val priorities = absoluteTaskPriorities(state)
        // "In the tree" is ONE predicate, and it is this walk — the same one "go to task" / "go to task
        // tree" asks, so a task has a row here exactly when the tree has somewhere to take you. Membership
        // only: the COUNT below still counts every populated cell off `state.cells`, because that is the
        // occurrence [absoluteTaskPriorities] and [RelativePriority.occurrenceChains] charge, and the
        // percentage column has to keep agreeing with it.
        val inTree = firstTaskOccurrences(state)
        val counts = HashMap<TaskId, Int>()
        for (cell in state.cells.values) {
            val taskId = cell.taskId ?: continue
            if (!isPopulatedCell(state, cell.id)) continue
            if (taskId !in inTree) continue
            counts[taskId] = (counts[taskId] ?: 0) + 1
        }
        val titles = counts.keys.associateWith { state.tasks[it]?.title.orEmpty() }
        val similarities =
            if (sort == TaskListSort.Similarity) TitleSimilarity.of(titles) else emptyMap()
        val entries = counts.map { (taskId, count) ->
            TaskListEntry(
                taskId = taskId,
                title = titles[taskId].orEmpty(),
                occurrences = count,
                priority = priorities[taskId] ?: 0.0,
                similarity = similarities[taskId],
            )
        }
        val byFigure = when (sort) {
            TaskListSort.Occurrences ->
                if (descending) compareByDescending<TaskListEntry> { it.occurrences }
                else compareBy { it.occurrences }
            TaskListSort.Priority ->
                if (descending) compareByDescending<TaskListEntry> { it.priority }
                else compareBy { it.priority }
            // Two figures, in the PRD's order: the best score, then how many tasks reach that same best.
            // Both follow the direction toggle — the tie-break is part of the figure, not part of the
            // alphabetical fallback below.
            TaskListSort.Similarity ->
                if (descending) {
                    compareByDescending<TaskListEntry> { it.similarity?.best ?: 0 }
                        .thenByDescending { it.similarity?.matches ?: 0 }
                } else {
                    compareBy<TaskListEntry> { it.similarity?.best ?: 0 }
                        .thenBy { it.similarity?.matches ?: 0 }
                }
        }
        return entries.sortedWith(
            byFigure.thenBy { it.title.lowercase() }.thenBy { it.taskId.value },
        )
    }

    // ----- The period edit window (a kind of restrictive period, and who may work through it) ----

    /**
     * One row of the **period edit window**: a task, and its resilience to the kind that window is about.
     *
     * The window is the other half of the task edit window's resilience section, read the other way round —
     * that section is *one task, every kind*, this is *one kind, every task*. Both read
     * [PeriodKinds.resilienceFor], so neither can invent a value the other disagrees with.
     */
    data class PeriodKindTaskRow(
        val taskId: TaskId,
        val title: String,
        val resilience: Double,
    )

    /**
     * Every task the period edit window lists for [kind], ordered by title (then id, so the order is total).
     *
     * The rows are the **schedulable leaves** of [taskListEntries], and only those: a resilience says where a
     * task may be *placed*, and a parent task is a grouping the scheduler never places (the task edit window
     * shows it no resilience section for the same reason). Offering a parent a value would be offering to
     * write a number nothing reads.
     */
    fun periodKindTaskRows(state: SchedulerState, kind: String): List<PeriodKindTaskRow> =
        taskListEntries(state)
            .filter { isLeafTask(state, it.taskId) }
            .map { entry ->
                PeriodKindTaskRow(
                    taskId = entry.taskId,
                    title = entry.title,
                    resilience = state.tasks[entry.taskId]?.resilienceFor(kind)
                        ?: PeriodKinds.defaultResilience(kind),
                )
            }
            .sortedWith(compareBy({ it.title.lowercase() }, { it.taskId.value }))

    /**
     * The value the period edit window's bulk field shows for [selected]: the resilience they **all** share,
     * or `null` where they do not agree — which the window draws as a blank field, exactly as asked.
     *
     * `null` for an empty selection too, which is the same answer by another route: with nothing selected
     * there is no field at all.
     */
    fun commonResilience(rows: List<PeriodKindTaskRow>, selected: Set<TaskId>): Double? {
        var common: Double? = null
        for (row in rows) {
            if (row.taskId !in selected) continue
            if (common == null) common = row.resilience
            else if (common != row.resilience) return null
        }
        return common
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
        // `side-dev/README.md` § *Rule State Definition*: the rule state is *"the set of tasks and their
        // associated priority percentages, **minimum execution time and resilience values**"*, and § *Rule
        // State Evolution* says the whole of it *"transforms evenly from the first state to the second one"*.
        // So the two numeric facts a keyframe holds about a task travel with the percentage, and neither is
        // read off the live tree while a blend is in force.
        //
        // Only these two: everything else on a [Task] — its title, its records, its schedule unit — is not a
        // rule-state quantity and has no midpoint, so the merge above still answers for it.
        val from = blend.from.tree.tasks
        val to = blend.to.tree.tasks
        val f = blend.fraction.coerceIn(0.0, 1.0)
        for (id in from.keys + to.keys) {
            val base = merged[id] ?: continue
            // A task the OTHER keyframe does not hold is not half-defined there: the identity is what carries
            // across, so only its percentage fades (to 0%) and the side that HAS it states its minimum and its
            // resilience throughout — `side-dev/scheduler.py`'s `RuleStates.at`, *"its minimum is taken from
            // the side that has it"*.
            val a = from[id] ?: to[id] ?: continue
            val b = to[id] ?: a
            val minimum = a.minimumMinutes + (b.minimumMinutes - a.minimumMinutes) * f
            // A resilience is read through [PeriodKinds.resilienceFor], so a kind ABSENT from one side is at
            // that kind's default there and not at zero — blending the raw maps would silently drag every
            // untouched kind towards 0. A kind neither side mentions is at one default on both sides, so it
            // interpolates to itself and needs no override.
            val kinds = a.resilience.keys + b.resilience.keys
            val resilience =
                if (kinds.isEmpty()) a.resilience
                else kinds.associateWith { kind ->
                    val ra = PeriodKinds.resilienceFor(a.resilience, kind)
                    val rb = PeriodKinds.resilienceFor(b.resilience, kind)
                    PeriodKinds.clamp(ra + (rb - ra) * f)
                }
            merged[id] = base.copy(minimumMinutes = minimum.roundToInt(), resilience = resilience)
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
     * any persisted/synced state. The sanctioned derived use is [liveRestPeriod], which hands the same live
     * gap to the recurrence bars as the restrictive period it is, so the placement moves with a pause the
     * derives have not banked yet — and stores nothing.
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
     * `docs/scheduler_requirements.md` § *Progressive Calculation*: **the ceiling on how far a CALENDAR-driven
     * $t_{goal}$ may pull the materialized fill** (168 hours). It is not the goal, and it is not a target — the
     * goal is [scheduleGoalEndMillis], and [scheduleHorizonEndMillis] is what the fill honours.
     *
     * The current week's own goal is NEVER clipped by it (a Monday's goal is the end of the following Monday,
     * eight days out): the headless engine must always hold the goal it would compute with no calendar open.
     * What this bounds is the other half of the max — a week the user has scrolled to. Beyond it that week is
     * still computed to the goal, but asynchronously and for display only (see [fillSchedule]'s `horizonMillis`
     * and `App.kt`'s far-week `LaunchedEffect`), never materialized into `state.panels`.
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
     * PRD §9 Scheduling: the **floor** under the goal (24 hours) — how far the plan is materialized when the
     * goal itself would somehow fall short of a day.
     *
     * The plan is not only a calendar drawing: the engine reads it headlessly for the §11/§13 task-switch
     * notifications, the §15 wind-down cue and the schedule-unit deadlines, so the horizon can never collapse
     * to zero. [scheduleGoalEndMillis] is already at least a day out for every position of the clock inside a
     * week (a Sunday 23:59 goal is the end of the following Tuesday); this only guards the arithmetic against
     * a DST-shortened week, and is what a caller with no time zone at all falls back on.
     */
    const val MIN_SCHEDULE_HORIZON_MILLIS: Long = 24L * 60 * 60 * 1000

    /**
     * The **Monday** the week holding [date] starts on. The app is Monday-first everywhere — the side menu's
     * month rail, the calendar's `weekAnchorDay` (which delegates here) and, now, $t_{goal}$ — and "which day
     * a week starts on" is exactly the kind of rule that must exist once.
     */
    fun weekStartDate(date: LocalDate): LocalDate =
        date.minus(DatePeriod(days = date.dayOfWeek.isoDayNumber - 1))

    /**
     * **The end of the first day of the week AFTER the week holding [date]** — the CURRENT-WEEK half of
     * $t_{goal}$.
     *
     * The week after starts on `weekStart(date) + 7 days`; the END of that first day is the midnight closing
     * it, i.e. `weekStart(date) + 8 days` at 00:00. Computed through the calendar, never as `+ 8 × 24 h`, so a
     * DST change inside the span does not move it off midnight.
     */
    fun endOfFirstDayOfNextWeekMillis(date: LocalDate, timeZone: TimeZone): Long =
        weekStartDate(date).plus(DatePeriod(days = 8)).atStartOfDayIn(timeZone).toEpochMilliseconds()

    /**
     * **The end of the day AFTER [date]** — the CALENDAR half of $t_{goal}$, given the last day the grid
     * shows: the first day that does not appear is the one after it, and the goal is the midnight closing
     * that day, i.e. `date + 2 days` at 00:00. Through the calendar, never `+ 48 h`, for the DST reason above.
     */
    fun endOfDayAfterMillis(date: LocalDate, timeZone: TimeZone): Long =
        date.plus(DatePeriod(days = 2)).atStartOfDayIn(timeZone).toEpochMilliseconds()

    /**
     * `docs/scheduler_requirements.md` § *Progressive Calculation*: **$t_{goal}$ — the instant the scheduler
     * may stop at.** *"The scheduler can have a time $t goal$ such as when definitive schedule is found for
     * any t < $t goal$ the scheduler can stop."*
     *
     * It is `max(` end of **the first day that does not appear in the calendar** `,` end of the first day of
     * the week after **the current week** `)`.
     *
     * So the calendar half is **one day past the bottom of the grid**, and it moves with the SCROLL rather
     * than with the week the scroll happens to be in: open the calendar on the current week and scroll down
     * far enough to see the Monday of the next week, and the goal becomes the end of that next week's
     * Tuesday.
     *
     * Three things it is, and each one is load-bearing:
     * - **It is a MAX, so the calendar can only ever push it further out.** Scrolling back — or closing the
     *   calendar altogether ([displayedEndMillis] `null`) — never shortens the schedule below what the current
     *   week asks for, which is what the headless §11/§13/§15 paths read.
     * - **The current-week half is an ABSOLUTE staircase.** It holds still for a whole week and then steps a
     *   week forward, so a schedule that has reached it stays complete instead of falling a millisecond short
     *   on every tick — the rolling `now + 24 h` horizon it replaces made [horizonRefillDueMillis] true again
     *   the instant a fill finished, which is the self-retriggering shape HORIZON_REFILL_MARGIN_MILLIS exists
     *   to damp. The calendar half moves only when the user scrolls, which is an event, not a tick.
     * - **"The first day that does not appear" is read off the LAST day displayed**, so a scroll that reveals
     *   one more day extends the goal by exactly that day — which is what makes "everything on screen is
     *   scheduled, and a day past it" true however the grid is scrolled or zoomed.
     *
     * [displayedEndMillis] is the EXCLUSIVE end of the displayed day span (what `App.kt` publishes through
     * [org.example.project.scheduler.engine.SchedulerEngine.setCalendarHorizon]), so the last day displayed is
     * the one holding `displayedEndMillis - 1`.
     */
    fun scheduleGoalEndMillis(
        nowMillis: Long,
        displayedEndMillis: Long?,
        timeZone: TimeZone = TimeZone.currentSystemDefault(),
    ): Long {
        fun dateOf(millis: Long): LocalDate =
            Instant.fromEpochMilliseconds(millis).toLocalDateTime(timeZone).date

        val current = endOfFirstDayOfNextWeekMillis(dateOf(nowMillis), timeZone)
        // A grid scrolled entirely into the past shows no day the plan has to reach, so it is clamped to the
        // now-line — where its answer (the end of tomorrow) is never above the current week's own.
        val shown =
            displayedEndMillis?.let { endOfDayAfterMillis(dateOf(maxOf(it - 1, nowMillis)), timeZone) }
                ?: current
        return maxOf(current, shown)
    }

    /**
     * PRD §9 Scheduling: the instant the auto fill materializes the work plan out to — **$t_{goal}$**
     * ([scheduleGoalEndMillis]), with the one bound that keeps a far week out of the persisted state.
     *
     * The goal of the CURRENT week is always honoured, whatever the calendar is showing; a goal the calendar
     * has pushed past `now + `[SCHEDULE_HORIZON_MILLIS] is capped here and computed for display only, off the
     * UI thread, and never retained (`App.kt`'s far-week `LaunchedEffect`). The [MIN_SCHEDULE_HORIZON_MILLIS]
     * floor is the arithmetic guard described on that constant.
     */
    fun scheduleHorizonEndMillis(
        nowMillis: Long,
        displayedEndMillis: Long?,
        timeZone: TimeZone = TimeZone.currentSystemDefault(),
    ): Long {
        val currentWeekGoal = scheduleGoalEndMillis(nowMillis, null, timeZone)
        val goal = scheduleGoalEndMillis(nowMillis, displayedEndMillis, timeZone)
        return maxOf(currentWeekGoal, minOf(goal, nowMillis + SCHEDULE_HORIZON_MILLIS))
            .coerceAtLeast(nowMillis + MIN_SCHEDULE_HORIZON_MILLIS)
    }

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
     * fills with, so a schedule that already reaches $t_{goal}$ is *not* considered short just because it
     * stops before `now + 168h`. That is the whole point: a plan that has reached the instant the requirement
     * lets the scheduler stop at must not keep re-filling the days after it. Since the goal is an ABSOLUTE
     * staircase this stays false for a whole week and then turns true once, when the week rolls.
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
        // The 20-20-20 micro-break. Its interval IS the README's own bar: after ANY dynamic period, no 20 s
        // period for 20 minutes (and a >=15-min rest stretch bars it for the same 20 minutes). Nothing has to
        // "serve" it — [DynamicPeriods] reads the rest stretches out of the timeline it is asked about.
        ScreenBreak(
            "look 20 feet away",
            intervalMillis = 20L * 60_000,
            durationMillis = 20L * 1_000,
            key = LOOK_AWAY_KEY,
            // `side-dev` test 11's sliding 20 s window: a period that accepts nothing.
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
            )
        }
    }

    /** A screen break is schedulable when it has a positive interval, a positive duration, and a title. */
    private fun isValidScreenBreak(side: ScreenBreak): Boolean =
        side.intervalMillis > 0 && side.durationMillis > 0 && side.title.isNotBlank()

    // ----- Sleep schedule -----------------------------------------------------------------------

    /** The production-default sleep schedule: wake 07:30, no drift, 8h30 in bed (so bedtime 23:00). */
    val DEFAULT_SLEEP: SleepSchedule = SleepSchedule()

    /**
     * PRD §17 wind-down: **the hour before bed**, which is covered by a period of
     * [PeriodKinds.BEFORE_BED] ([beforeBedPanels]) — not by an extension of the sleep obstacle, which is what
     * it used to be. The length of the period, and nothing else; who may run inside it is each task's own
     * resilience to that kind, exactly as for every other restrictive period.
     */
    const val BEFORE_BED_MILLIS: Long = 60L * MILLIS_PER_MINUTE

    /** The title the §17 wind-down periods carry, so a caller can build the same period the fill builds. */
    const val BEFORE_BED_PANEL_TITLE: String = "Before bed"

    /**
     * The id prefix of a DERIVED §17 wind-down panel (`before-bed/{wake day}`) — the one place the fill's
     * `kept` filter and [isRegeneratedPanel] recognise it, exactly as `sleep/{day}` names the derived sleep
     * windows. It carries no authoritative state: it is re-derived from the sleep schedule on every fill.
     */
    const val BEFORE_BED_PANEL_ID_PREFIX: String = "before-bed/"

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
     * PRD §17 **the hour before bed, as the period it is**: `[bedtime − [BEFORE_BED_MILLIS], bedtime)` for
     * every §17 sleep window intersecting `[fromMillis, toMillis)`, laid as a restrictive period of
     * [PeriodKinds.BEFORE_BED].
     *
     * Derived from [sleepPanels], never from a second reading of the schedule, so the wind-down cannot drift
     * away from the bedtime it is measured back from — the wake time drifts toward its goal
     * ([effectiveWakeMinutes]) and the hour drifts with it. The window is asked one [BEFORE_BED_MILLIS] wider
     * on the right, because a sleep window that starts just past [toMillis] still has an hour inside it.
     *
     * **This is the whole of the rule.** No task is scheduled here because every task's resilience to the
     * kind defaults to `0` ([PeriodKinds.defaultResilience]); a task the user hands a value above zero works
     * through the wind-down, and there is nothing else anywhere that says so. The panel carries the KIND and
     * no legacy flag — it is not an `inactivity` period wearing a different name — and the calendar paints it
     * as the grey band every "the scheduler places nothing here" stretch is painted as.
     */
    fun beforeBedPanels(
        sleep: SleepSchedule?,
        fromMillis: Long,
        toMillis: Long,
        timeZone: TimeZone,
    ): List<TaskPanel> {
        if (toMillis <= fromMillis) return emptyList()
        return sleepPanels(sleep, fromMillis, toMillis + BEFORE_BED_MILLIS, timeZone).mapNotNull { window ->
            val start = window.startEpochMillis - BEFORE_BED_MILLIS
            val end = window.startEpochMillis
            if (end <= fromMillis || start >= toMillis) {
                null
            } else {
                TaskPanel(
                    id = BEFORE_BED_PANEL_ID_PREFIX + window.id.removePrefix("sleep/"),
                    taskId = null,
                    title = BEFORE_BED_PANEL_TITLE,
                    startEpochMillis = start,
                    endEpochMillis = end,
                    periodKind = PeriodKinds.BEFORE_BED,
                )
            }
        }
    }

    /** The §17 wind-down hours from [beforeBedPanels] as occupied time ranges. */
    fun beforeBedRegions(
        sleep: SleepSchedule?,
        fromMillis: Long,
        toMillis: Long,
        timeZone: TimeZone,
    ): List<TaskTimeRange> =
        beforeBedPanels(sleep, fromMillis, toMillis, timeZone)
            .map { TaskTimeRange(it.startEpochMillis, it.endEpochMillis) }

    /**
     * PRD §15: a screen break that **happened** — the object the "what serves a break" rules are written
     * against. [range] is the span it occupied, so its `end` is the rest instant a shorter break anchors to.
     */
    data class PastScreenBreak(val title: String, val key: String, val range: TaskTimeRange)

    /**
     * PRD §15: the LIVE rest evidence — the pause this device is observing right now (or the one it just
     * finished, until a derive covers it). [gap] starts at the last session finalize (the walk-away
     * instant) and, while the device stays inactive ([ongoing]), ends at the now-line, so it grows with
     * `now` exactly like the display Inactivity tail ([displayInactivityGaps] draws the same range). Once
     * the user is back the end freezes at the reopened session's start ([ongoing] = false) and the gap
     * holds until a derive retires the tail. [ongoing] says whether the gap is still growing, which is what
     * [liveRestPeriod] hands the bars: an ongoing pause reaches them as a period running to the now-line, a
     * held one only as what it actually contained.
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
     * `side-dev/README.md`: the live pause as a **restrictive period**, which is the only shape the
     * recurrence bars know how to read.
     *
     * A pause the user is in the middle of is a stretch of the timeline nobody is working in, so it is a
     * period of [PeriodKinds.NO_TASK] behind (and up to) the now-line — and the bars then do the rest by
     * themselves: it is a *rest stretch*, so a pause of five minutes bars the 5-min period for an hour and
     * one of a quarter of an hour bars the 15-min period for two. That replaces the old placement overlay,
     * which folded the gap into every break's stored rest anchor and re-derived the grid from there.
     *
     * An **ongoing** pause is drawn to the now-line, which is what makes the grid move with the user as they
     * stay away rather than freezing at the instant they left.
     */
    fun liveRestPeriod(liveRest: LiveRest?): RestrictivePeriod? {
        val gap = liveRest?.gap ?: return null
        if (gap.endEpochMillis <= gap.startEpochMillis) return null
        return RestrictivePeriod(
            startMillis = gap.startEpochMillis,
            endMillis = gap.endEpochMillis,
            kind = PeriodKinds.NO_TASK,
            label = "Inactivity",
            // An ONGOING pause is drawn to the now-line and the line is inside it — the user has not come
            // back yet. So it covers its end, which is what makes `t_p` genuinely covered while the device is
            // locked, and therefore what makes mode 2's own rule hold wherever the app has live evidence for
            // it (`DynamicPeriods.awayCover` then has no gap left to cover). A pause that has ENDED stops at
            // the instant the user returned, exclusive, like every other period.
            closedEnd = liveRest.ongoing,
        )
    }

    /**
     * `side-dev/README.md` § *3 Dynamic Restrictive Period*: **what the devices OBSERVED, as restrictive
     * periods** — the third of the three ways a pause reaches the recurrence bars, and the one that was
     * missing.
     *
     * A rest stretch is read out of the timeline itself (ADR 0003: there is no stored `lastRest` any more),
     * so a pause has to BE on the timeline for the bars to see it at all. Two routes put it there and both
     * are narrow: a period the user drew by hand, and the pause **this device is living through right now**
     * ([liveRestPeriod], off `inactiveSince`/`activeSince`). Neither covers a pause that has simply *ended* —
     * a derive retires the live tail, and a restart clears it outright — so a quarter of an hour away from
     * every device left no mark on the placement whatsoever. The bars went on counting from the last
     * *recorded* break, which is why a 5-minute pose fell due well inside the hour the README bars it in
     * (observed 2026-08-29: both layers locked 12:15–12:28, a 5-min pose owed at 12:40 instead of 13:28).
     *
     * [regions] is [observedNoScreenRegions] — both calendar layers' OS lock/standby evidence intersected —
     * so the placement, the §9 record bank and the calendar's panel clipping are all answering the same
     * reading of "nobody was at a screen here" and cannot drift apart.
     *
     * The kind is [PeriodKinds.NO_SCREEN] and not [PeriodKinds.NO_TASK], because that is exactly what the
     * evidence says: nobody was at a SCREEN. An off-screen task may legitimately have run there (§9 exempts
     * one from the record ban for that very reason), and the README's clause is *"covered by the period 'no
     * on-screen task' **without any task**"* — so the stretch is a rest on an account whose tasks are all
     * on-screen, and correctly is not one where somebody could have been working through it.
     *
     * Nothing here is stored, drawn or synced: it is derived evidence handed to a placement, like the live
     * pause beside it.
     */
    fun observedNoScreenPeriods(regions: List<TaskTimeRange>): List<RestrictivePeriod> =
        regions.mapNotNull { region ->
            if (region.endEpochMillis <= region.startEpochMillis) {
                null
            } else {
                RestrictivePeriod(
                    startMillis = region.startEpochMillis,
                    endMillis = region.endEpochMillis,
                    kind = PeriodKinds.NO_SCREEN,
                    label = "Inactivity",
                )
            }
        }

    /**
     * `side-dev/README.md` § *3 Dynamic Restrictive Period*: **the environment the three are placed over**,
     * assembled once.
     *
     * The bars are a walk over the timeline, so two callers handed two different timelines get two different
     * grids — and the app would then announce a break at an instant the calendar does not draw one at, which
     * is the drift the whole due/place split exists to remove. So there is one funnel: the standing periods a
     * set of panels holds ([restrictivePeriodsOf]), the live pause ([liveRestPeriod]) and what the devices
     * observed ([observedNoScreenPeriods]). The cue sweep, the pause cue's published due and the calendar's
     * display all ask through here; [fillSchedule] builds the same three parts out of the panels it is
     * keeping, plus the §17 sleep windows it is about to place.
     */
    fun dynamicPeriodBase(
        panels: List<TaskPanel>,
        liveRest: LiveRest? = null,
        noScreenEvidence: List<TaskTimeRange> = emptyList(),
    ): List<RestrictivePeriod> =
        restrictivePeriodsOf(panels) +
            listOfNotNull(liveRestPeriod(liveRest)) +
            observedNoScreenPeriods(noScreenEvidence)

    /**
     * `side-dev/README.md` § *$t_p$ 3 modes* — **which mode the line is in**, and the one place it is decided.
     *
     * The rule is the user's, and it is two questions asked in order:
     *  * **mode 1** while ANY device of the account is unlocked — somebody is at a screen;
     *  * otherwise **mode 3** if the user has pressed **"I'm away"**, and **mode 2** if they have not.
     *
     * The second question is what mode 3 adds, and it is the difference between *no screen is in use* and *a
     * break is being taken*. A locked machine says only the first: the user may be reading at their desk, or
     * the screen may have locked itself while they thought. So mode 2 goes on pushing a pose ahead of the line
     * exactly as mode 1 does — what makes that pose go away there is the ordinary bar rule, a locked stretch
     * being a rest stretch — while mode 3 lets the pose elapse under the line, because the user said so.
     *
     * It is not the Sleep/Work toggle (which is a statement about the night, not about a screen). The "I'm
     * away" button reaches BOTH halves: it declares its own device idle, which is how it reaches
     * [anyDeviceUnlockedAt] like a lock does, and it is [awayDeclared] here — which is why pressing it on the
     * one device of a single-device account lands in mode 3 rather than mode 2, and why pressing it while a
     * phone is still unlocked leaves the account in mode 1. An unlock clears it
     * (`SchedulerEngine.noteScreenSignal`), so the mode comes back on its own.
     *
     * **[awayDeclared] is the ACCOUNT's answer, not this device's flag**: *at least one device with the button
     * on*. That is the spec's own wording, and it is why the flag is published and read back
     * (`PauseCueGateway.syncDeviceAway`) instead of staying local — a peer that is merely locked has to reach
     * the same mode as the device the button was pressed on, or the two place the dynamic periods differently.
     * It arrives with the same reconcile-bounded staleness as that peer's activity does for the other half.
     */
    /*
     * Note: the mode is a fact about the DEVICES, so it says nothing about the 20 s look-away. That one is
     * never dragged in any mode (`DynamicPeriods.dragsAtLine`), which is the same thing as saying the line is
     * in mode 3 for the twenty seconds it takes to cross one.
     */
    fun tpMode(anyDeviceUnlocked: Boolean, awayDeclared: Boolean = false): Int =
        when {
            anyDeviceUnlocked -> DynamicPeriods.MODE_AT_SCREEN
            awayDeclared -> DynamicPeriods.MODE_ON_BREAK
            else -> DynamicPeriods.MODE_AWAY
        }

    /**
     * `side-dev/README.md` § *$now line$*: **the coarsest step the line may take without skipping a slot** —
     * the smallest minimum execution time the rules hold at the line, or `null` when no task has one.
     *
     * The line "moves continuously forward in time", so a caller that asks for a distant position is asking
     * for a JOURNEY, not a landing: it is walked there a step at a time
     * ([org.example.project.scheduler.engine.SchedulerEngine] sweeps it, `ProgressiveSchedule.advanceTo`
     * commits it). This is the granularity of that walk, and it is the reference's own
     * (`side-dev/scheduler.py`'s `Walk._sweep_step`): the finest thing the walk can place is one task's
     * minimum, so a line that never skips a whole minimum never skips a placement it should have entered.
     * Stepping on the placement edges instead is the tempting alternative and it is wrong — it never freezes a
     * partial slot, and it is ENTERING a placement, not landing on its edge, that settles the picks after it.
     *
     * An ordinary tick — a frame, a second — is far inside the first step and so costs exactly one commit:
     * nothing is spent except where a caller really does ask the line to cover ground (a wake from device
     * sleep, a debug time leap).
     */
    fun sweepStepMillis(state: SchedulerState, nowMillis: Long): Long? =
        planTasksOf(state, nowMillis).asSequence().map { it.minimumMillis }.filter { it > 0L }.minOrNull()

    /**
     * Is any device of the account unlocked at [nowMillis]? — the input [tpMode] is a function of.
     *
     * It is read off the account-wide pause the calendar already draws ([displayInactivityGaps]): the derived
     * gaps, which are the complement of every device's active intervals (this device's own rows plus the
     * peers' the last reconcile pulled), plus the live tail of the pause this device is observing right now.
     * The now-line being inside one of those IS "no device is unlocked" — so the mode and the Inactivity band
     * can never say two different things, which is the property worth having: what the user sees is the mode.
     *
     * The right edge is inclusive here, unlike everywhere else. An ongoing pause's tail ends AT the now-line
     * ([displayInactivityGaps] grows it with `now`), so a half-open test would report the device unlocked at
     * the one instant the question is being asked about.
     *
     * The peers reach this with reconcile-bounded staleness, and the live tail is a local presumption that a
     * derive later shrinks over any peer activity — the same bound [displayInactivityGaps] documents for the
     * band itself. A host that cannot report a lock at all (a non-Windows JVM, iOS) simply never opens a tail,
     * so it stays in mode 1 unless the user says otherwise with the "I'm away" button.
     */
    fun anyDeviceUnlockedAt(
        inactivityGaps: List<TaskTimeRange>,
        inactiveSinceMillis: Long?,
        activeSinceMillis: Long?,
        nowMillis: Long,
    ): Boolean =
        displayInactivityGaps(inactivityGaps, inactiveSinceMillis, activeSinceMillis, nowMillis)
            .none { it.startEpochMillis <= nowMillis && nowMillis <= it.endEpochMillis }

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
     * `side-dev/README.md` § *3 Dynamic Restrictive Period*: **where the 20 s, the 5 min and the 15 min
     * periods fall** over `[nowMillis, horizonMillis]`.
     *
     * The whole rule is [DynamicPeriods] — the three recurrence bars, the rest stretches that trigger them,
     * the emptiness that absorbs a period, the two `t_p` modes and the chain merge. What used to live here —
     * a per-break `lastRest` anchor, a grid simulation, the 5-to-15 merge, the "a pause re-anchors shorter
     * pauses" rule, the decoupled-pose special case — is gone, because every one of those was a way of
     * saying "a rest bars the breaks that follow it", which is what the bars say **once**.
     *
     * That also makes the anchors DERIVED rather than stored (CLAUDE.md's authoritative-vs-derived rule):
     * the placement reads the rest stretches out of the timeline itself. So the past reaches it through
     * [basePeriods] — the recorded inactivity, no-screen and sleep spans behind the now-line — and the walk
     * starts one [DYNAMIC_PLACEMENT_LOOKBACK_MILLIS] before `now` so a pause that has just happened bars the
     * breaks it should. Instances ending at or before the now-line are dropped: the past is frozen and is
     * drawn from what really happened ([takenScreenBreakPanels]), never re-derived.
     *
     * [mode] is the README's `t_p` mode, and it lands on a control the app already has: the "I'm away"
     * toggle. At the screen ([DynamicPeriods.MODE_AT_SCREEN]) the now-line may not be covered by a POSE, so a
     * pose it has reached is pushed ahead of it and goes on being pushed; away ([DynamicPeriods.MODE_AWAY]) it
     * must be covered, so the gap back to the last period's end is covered by a "no on-screen task" period the
     * resilient tasks may still fill.
     *
     * The 20 s look-away is dragged by neither mode (`DynamicPeriods.dragsAtLine`): it is assumed taken as it
     * falls due, so it stays where the bars put it and the line crosses it.
     */
    fun screenBreakPanels(
        screenBreaks: List<ScreenBreak>,
        nowMillis: Long,
        horizonMillis: Long = nowMillis + SCHEDULE_HORIZON_MILLIS,
        basePeriods: List<RestrictivePeriod> = emptyList(),
        blocks: List<PlanBlock> = emptyList(),
        tasks: List<PlanTask> = emptyList(),
        mode: Int = DynamicPeriods.MODE_AT_SCREEN,
    ): List<TaskPanel> =
        dynamicPeriodPanels(
            screenBreaks = screenBreaks,
            fromMillis = nowMillis,
            toMillis = maxOf(horizonMillis, nowMillis),
            tpMillis = nowMillis,
            basePeriods = basePeriods,
            blocks = blocks,
            tasks = tasks,
            mode = mode,
            anchorMillis = nowMillis,
            // `nowMillis` IS $t_p$ here, so both modes apply. (The other caller that says so is
            // [takenScreenBreakPanels]: the elapsed window is behind the same line.)
            atLine = true,
        )

    /** The title the §17 sleep windows carry, so a caller can build the same period the fill builds. */
    const val SLEEP_PANEL_TITLE: String = "Sleep"

    /**
     * `side-dev/README.md` § *Restrictive Period*: **the restrictive periods a set of panels holds** — every
     * panel that is one ([TaskPanel.isRestrictivePeriod]), read through the single reading of its kind
     * ([TaskPanel.restrictiveKind]).
     *
     * One function so the fill, the display and the recurrence bars cannot disagree about what a panel
     * restricts. Nothing is clipped here: the bars deliberately look BEHIND the now-line, because a rest that
     * has just happened bars the breaks that follow it.
     */
    fun restrictivePeriodsOf(panels: List<TaskPanel>): List<RestrictivePeriod> =
        panels.mapNotNull { panel ->
            // A materialized DYNAMIC period is not an input to its own placement. The recurrence bars derive
            // the three from the standing environment, so feeding last fill's output back in would make each
            // of them a blocked stretch that absorbs the next one — the grid would walk away from itself on
            // every pass. They are derived; only what the user drew and what §17 schedules are the timeline.
            if (panel.screenBreak) return@mapNotNull null
            val kind = panel.restrictiveKind
            if (kind.isEmpty() || panel.endEpochMillis <= panel.startEpochMillis) null
            else
                RestrictivePeriod(
                    panel.startEpochMillis,
                    panel.endEpochMillis,
                    kind,
                    panel.title,
                    // A break the app CONDUCTED is a dynamic restrictive period that really happened, so it
                    // fires the README's first bar (no 20 s period for twenty minutes after it) exactly as a
                    // placed occurrence does. Nothing else about the panel says so - it is a recorded
                    // `no task allowed` span like any other.
                    dynamic = panel.conductedBreak,
                )
        }

    /**
     * The schedulable leaves of [state] as the plan layer sees them at [nowMillis] — priority, minimum time
     * and, above all, the **resilience map** that says where each of them may run and at what share.
     *
     * The same list [fillSchedule] builds, exposed because the recurrence bars need it too: whether a stretch
     * is a REST (the README's "without any task") is a question about the tasks, not about the period.
     */
    fun planTasksOf(state: SchedulerState, nowMillis: Long): List<PlanTask> {
        val leaves = blendedSchedulableLeaves(state, nowMillis)
        if (leaves.isEmpty()) return emptyList()
        val priorities = blendedTaskPriorities(state, nowMillis)
        val attributes = blendedTaskAttributes(state, nowMillis)
        return leaves.map { id ->
            PlanTask(
                id = id,
                priority = priorities[id] ?: 0.0,
                minimumMillis = (attributes[id]?.minimumMinutes ?: 0).toLong() * MILLIS_PER_MINUTE,
                resilience = attributes[id]?.resilience.orEmpty(),
            )
        }
    }

    /**
     * `side-dev/README.md`: **$t_{pstart}$, the constant the timeline starts at** — and it has to be a
     * constant, not a distance behind whatever window is being asked about.
     *
     * The recurrence bars are a walk from the origin forward, so the grid they produce is a function OF that
     * origin: two questions asked with origins ten minutes apart get two different grids. The calendar asks
     * about the visible span, the cue sweep asks about its scan window and the fill asks from the now-line —
     * so a relative lookback would have the app announce a break at an instant the calendar does not draw one
     * at, which is exactly the drift this model exists to remove.
     *
     * So the origin is quantized to the **start of the UTC day before** an ANCHOR every caller shares — the
     * now-line. Not the window's own left edge: the fill asks from the now-line, the cue sweep from its scan
     * floor ten minutes behind it and the calendar from the visible span's start, and quantizing each of
     * those separately puts them in different days whenever one straddles a midnight, which is precisely
     * when the two grids would part company. Anchored on the line they all already have, every question
     * asked at one instant gets one answer, and the walk still sees a full day of history — far past the
     * longest bar the README states (two hours).
     *
     * "The timeline starts rested" is then a statement about a day boundary rather than about an arbitrary
     * instant, and in practice the night that sits there is a rest stretch anyway, so the environment would
     * re-anchor the bars at about that point whatever origin was chosen.
     */
    fun dynamicPlacementOriginMillis(anchorMillis: Long): Long {
        val day = 24L * 60L * 60L * 1000L
        val floor = (anchorMillis.floorDiv(day)) * day
        return floor - day
    }

    /**
     * How far behind a window the dynamic placement is guaranteed to see. Kept as a named bound because the
     * bars need at least the longest of them (two hours) of history to be in force at the window's left edge;
     * [dynamicPlacementOriginMillis] always provides more.
     */
    const val DYNAMIC_PLACEMENT_LOOKBACK_MILLIS: Long = 4L * 60L * 60L * 1000L

    /**
     * `side-dev/README.md`: the three dynamic periods over an arbitrary window — the engine behind both
     * [screenBreakPanels] (the window containing the now-line) and [screenBreakPanelsInWindow] (a week the
     * user has navigated to).
     *
     * The placement always starts one [DYNAMIC_PLACEMENT_LOOKBACK_MILLIS] before [fromMillis] and runs to
     * [toMillis], then keeps what lands in the window: the bars are a recurrence, so a period near the left
     * edge is only correct if the ones before it were placed too.
     */
    fun dynamicPeriodPanels(
        screenBreaks: List<ScreenBreak>,
        fromMillis: Long,
        toMillis: Long,
        tpMillis: Long,
        basePeriods: List<RestrictivePeriod> = emptyList(),
        blocks: List<PlanBlock> = emptyList(),
        tasks: List<PlanTask> = emptyList(),
        mode: Int = DynamicPeriods.MODE_AT_SCREEN,
        /** The now-line, which fixes the grid's origin; see [dynamicPlacementOriginMillis]. */
        anchorMillis: Long = tpMillis,
        /**
         * Whether [tpMillis] really is the **$t_p$ line** — the present, which has swept continuously from
         * $t_{pstart}$ up to here — rather than the left edge of some window being asked about.
         *
         * Only the line drags and only the line is covered, so this is what turns the two `t_p` modes on. It
         * is false for every question of the form "where do the bars put a break over this span": the cue
         * sweep's dues, the pause cue's next break, a week the calendar has navigated to. Those all want the
         * bars' own answer, which is what the drag is defined *against*.
         */
        atLine: Boolean = false,
    ): List<TaskPanel> {
        if (toMillis <= fromMillis) return emptyList()
        val specs = dynamicPeriodSpecs(screenBreaks)
        if (specs.isEmpty()) return emptyList()
        val start = dynamicPlacementOriginMillis(anchorMillis)
        val base = DynamicPeriods.Base(basePeriods, blocks, tasks)
        val titleOfLabel = dynamicPeriodTitles(screenBreaks)
        val indexOfTitle = screenBreaks.withIndex().associate { (i, side) -> side.title to i }
        // What the line has SWEPT, which is the whole of mode 1: from the placement origin up to the line.
        // The line moves CONTINUOUSLY, so there is no instant below it that it did not stand on, and the
        // sweep gets no floor of its own — it starts where the timeline does. Every POSE whose slot falls
        // in that stretch was therefore reached by the line and
        // is therefore pushed ahead of it, and the chain merge then collapses the ones that pile up into the
        // longest, exactly as the README says. (The 20 s look-away is never dragged —
        // `DynamicPeriods.dragsAtLine` — so the sweep says nothing about it: it is assumed taken as it falls
        // due, and the line simply crosses it.) It is bounded, and that is not an accident: the drag re-anchors
        // each bar at the line, so at most one occurrence per bar can be swept and the merge leaves ONE period
        // owed at the now-line, never four hours of them.
        //
        // A caller that is not the line ([atLine] false) sweeps nothing: `sweepFrom = tp` makes the drag's
        // condition (`sweepFrom <= slot < tp`) unsatisfiable, so it gets the bars' undragged answer.
        //
        // The walk runs one millisecond PAST the window and the filter is inclusive at the right edge: the
        // cue sweep asks about `[floor, now]` and the boundary it is looking for is the one at `now` itself.
        // Excluded, a break would be announced one sweep late — or, at a sweep that then self-delays past it,
        // never.
        val placed =
            DynamicPeriods.instances(
                base, specs, start, toMillis + 1, tpMillis, mode,
                sweepFromMillis = if (atLine) start else tpMillis,
            )
        val breaks =
            placed
                // The half-open `(t_p, t_p + duration]` of a dragged period, realized in the app's discrete
                // millisecond time (see [DynamicPeriods.Instance.coveredFromMillis]) — so the instant `t_p`
                // itself is genuinely left free, which is the whole of mode 1's rule.
                .filter { it.coveredUntilMillis > fromMillis && it.coveredFromMillis <= toMillis }
                .map { inst ->
                    val title = titleOfLabel[inst.spec.label] ?: inst.spec.label
                    screenBreakPanel(
                        indexOfTitle[title] ?: 0, title, inst.coveredFromMillis, inst.coveredUntilMillis,
                    )
                }
        // Mode 2's cover is deliberately NOT here. It is a period the SCHEDULER reads and the calendar must
        // not draw (a synthetic "Away" band shipped once and was reverted), and this function's answer is the
        // panel list — what the calendar draws and what `state.panels` carries. [fillSchedule] builds the
        // cover for its own environment through [DynamicPeriods.awayCover] instead.
        return breaks.sortedBy { it.startEpochMillis }
    }

    /**
     * The three the README names, read off the account's [ScreenBreak] list: a label (its role among the
     * three), how long it lasts, and its own recurrence bar — which is exactly [ScreenBreak.intervalMillis],
     * already 20 min / 1 h / 2 h in [DEFAULT_SCREEN_BREAKS]. The **kind** is not read from anywhere: all
     * three are [PeriodKinds.NO_TASK], as the README says in as many words.
     *
     * The labels the bars key on are positional, not textual — the shortest of the three is the README's
     * "20s", the longest its "15min" — so the debug fast-break override (which retimes the durations and
     * nothing else) keeps working, and so does an account whose breaks were retitled.
     */
    fun dynamicPeriodSpecs(screenBreaks: List<ScreenBreak>): List<DynamicPeriods.Spec> {
        val valid = screenBreaks.filter { isValidScreenBreak(it) }
        if (valid.isEmpty()) return emptyList()
        return valid.sortedBy { it.durationMillis }.mapIndexed { i, side ->
            DynamicPeriods.Spec(
                label = DYNAMIC_BAR_LABELS.getOrNull(i) ?: side.title,
                durationMillis = side.durationMillis,
                cadenceMillis = side.intervalMillis,
            )
        }
    }

    /** Which of the account's breaks each bar label stands for — the inverse of [dynamicPeriodSpecs]. */
    private fun dynamicPeriodTitles(screenBreaks: List<ScreenBreak>): Map<String, String> {
        val valid = screenBreaks.filter { isValidScreenBreak(it) }.sortedBy { it.durationMillis }
        return valid.mapIndexed { i, side -> (DYNAMIC_BAR_LABELS.getOrNull(i) ?: side.title) to side.title }.toMap()
    }

    /** The README's three, shortest first — the roles the recurrence bars are written against. */
    private val DYNAMIC_BAR_LABELS: List<String> =
        listOf(DynamicPeriods.LABEL_20S, DynamicPeriods.LABEL_5MIN, DynamicPeriods.LABEL_15MIN)

    /**
     * `side-dev/README.md`: the three dynamic periods over a window the now-line is NOT in — a week the user
     * has navigated to. Same rules, same engine; the line is taken to be at the window's start, so nothing
     * there is being dragged. That is not the line jumping — the line cannot jump — it is asking the bars
     * where the periods FALL over a window the line is nowhere near.
     */
    fun screenBreakPanelsInWindow(
        screenBreaks: List<ScreenBreak>,
        fromMillis: Long,
        toMillis: Long,
        basePeriods: List<RestrictivePeriod> = emptyList(),
        blocks: List<PlanBlock> = emptyList(),
        tasks: List<PlanTask> = emptyList(),
        anchorMillis: Long = fromMillis,
    ): List<TaskPanel> =
        dynamicPeriodPanels(
            screenBreaks = screenBreaks,
            fromMillis = fromMillis,
            toMillis = toMillis,
            tpMillis = fromMillis,
            basePeriods = basePeriods,
            blocks = blocks,
            tasks = tasks,
            anchorMillis = anchorMillis,
        )

    /**
     * `side-dev/README.md`: the dynamic periods that fall in `[fromMillis, toMillis]` **behind the now-line**
     * — the calendar's past-side markers, so a break stays drawn where it happened instead of vanishing the
     * instant the now-line passes it.
     *
     * There is nothing special left to do here. The three are placed by the recurrence bars over the
     * environment, and the environment behind the now-line is the recorded one, so *the past placement is
     * simply the placement* — the same function, asked about a window that has already gone by, at the line
     * ([dynamicPeriodPanels]' `atLine`) because that is where the two modes are read from.
     *
     * Which is why a stretch the line crossed in **mode 1** holds no POSE: a pose the line reached was pushed
     * ahead of it and never happened, and "the passing of the $t_p$ line creates task panels not covered by
     * the period" is the README's own account of what is drawn there instead. A pose shows in the past when it
     * really was one — the stretch was crossed in mode 2 (no device unlocked).
     *
     * A **20 s look-away always shows**, in either mode. It is never dragged (`DynamicPeriods.dragsAtLine`):
     * the app assumes the user looked away as it fell due, so the line crossed it and it is a fact of the past
     * like any other. The same is true of one the app CONDUCTED and recorded (`RecordConductedBreak`), which is
     * a pre-placed period and so is never dragged by anything either — and which re-anchors the 20 s bar, so
     * pressing "Look away now" less than twenty minutes after a crossed one moves what the bars draw there.
     */
    fun takenScreenBreakPanels(
        screenBreaks: List<ScreenBreak>,
        fromMillis: Long,
        toMillis: Long,
        basePeriods: List<RestrictivePeriod> = emptyList(),
        blocks: List<PlanBlock> = emptyList(),
        tasks: List<PlanTask> = emptyList(),
        anchorMillis: Long = toMillis,
        /** The now-line. The elapsed window is behind it, so the line's own rules decide what happened in it. */
        tpMillis: Long = toMillis,
        mode: Int = DynamicPeriods.MODE_AT_SCREEN,
    ): List<TaskPanel> =
        dynamicPeriodPanels(
            screenBreaks = screenBreaks,
            fromMillis = fromMillis,
            toMillis = toMillis,
            tpMillis = tpMillis,
            basePeriods = basePeriods,
            blocks = blocks,
            tasks = tasks,
            mode = mode,
            anchorMillis = anchorMillis,
            atLine = true,
        )

    /**
     * `side-dev/README.md`: the dynamic periods whose start falls in `[fromMillis, toMillis]` — the boundary
     * instants the cue sweep announces.
     *
     * These are the **dues** — where the recurrence bars put each of the three, with nothing dragged
     * ([dynamicPeriodPanels]' `atLine` is false here). That is deliberately not always where the period ends
     * up sitting: in mode 1 the line pushes a POSE it reaches ahead of itself, and a start that moves with
     * the line is never crossed, so it is no boundary at all and a sweep keyed on it would announce a break at
     * every scan for as long as one is owed.
     *
     * The due is the boundary, and it is the right one: the instant the line reaches a slot is the instant the
     * break falls due, which is exactly when the app should say so. It is a fixed instant derived from the
     * rules, crossed once. What is *drawn* from there on is the owed pose sliding at the line
     * ([takenScreenBreakPanels] / [screenBreakPanels]) — the same placement, asked with the line in it.
     *
     * It is the right reading for a POSE and the wrong one for the **20 s look-away**, which is never dragged
     * — ask [screenBreakCueOccurrencesBetween] for the cue boundaries rather than this directly.
     */
    fun screenBreakOccurrencesBetween(
        screenBreaks: List<ScreenBreak>,
        fromMillis: Long,
        toMillis: Long,
        basePeriods: List<RestrictivePeriod> = emptyList(),
        blocks: List<PlanBlock> = emptyList(),
        tasks: List<PlanTask> = emptyList(),
        /** The now-line. Defaults to the window's right edge, which is what the cue sweep's own is. */
        anchorMillis: Long = toMillis,
    ): List<TaskPanel> =
        dynamicPeriodPanels(
            screenBreaks = screenBreaks,
            fromMillis = fromMillis,
            toMillis = toMillis,
            tpMillis = fromMillis,
            basePeriods = basePeriods,
            blocks = blocks,
            tasks = tasks,
            anchorMillis = anchorMillis,
        ).filter { it.startEpochMillis in fromMillis..toMillis }

    /**
     * `side-dev/README.md`: **the boundary instants the app announces a break at** — the one reading the cue
     * sweep and its self-delay share, and the answer to "which run is this break's start crossable in".
     *
     * There are two runs of the recurrence bars and they are not the same sequence, because the drag
     * re-anchors the bar it fires on. Each of the three is read from the run its own rules make crossable:
     *
     *  * a **POSE is dragged** (mode 1 pushes an owed one onto the line), so where it sits rides the now-line
     *    and is never crossed. Its cue keys on its **due** — [screenBreakOccurrencesBetween], nothing dragged
     *    — which is a fixed instant crossed once.
     *  * the **20 s look-away is never dragged** (`DynamicPeriods.dragsAtLine`), so its start is already a
     *    fixed instant in the run that is actually happening. Its cue therefore keys on the **at-line** run —
     *    the very placement the calendar draws and [fillSchedule] obstructs on.
     *
     * Reading the look-away off the undragged run instead is what shipped until 2026-09-04, and it drifted in
     * BOTH directions, because an owed pose is a dynamic period in one run and not in the other: the
     * undragged run places the pose and so bars the 20 s for twenty minutes after it, while the at-line run
     * drags that pose to the line and leaves the bar where the environment put it. So the calendar drew a
     * look-away the app had never announced (account 3, 12:54 on 2026-09-04, with a 15-min pose owed since
     * 12:51 — the last cue logged), and the sweep would announce one at an instant the calendar never draws
     * one at as soon as the dragged pose lands on the line and bars the 20 s ahead of it. That is exactly the
     * drift the due/place split exists to remove; it is removed by asking each break the question its own
     * placement rule can answer, not by asking both of them one question.
     *
     * [nowMillis] is the now-line — the grid's shared anchor ([dynamicPlacementOriginMillis]) and the `t_p`
     * the at-line run is read at. The window may sit behind it (the sweep's scan window) or ahead of it (the
     * self-delay's search).
     */
    fun screenBreakCueOccurrencesBetween(
        screenBreaks: List<ScreenBreak>,
        fromMillis: Long,
        toMillis: Long,
        nowMillis: Long,
        basePeriods: List<RestrictivePeriod> = emptyList(),
        blocks: List<PlanBlock> = emptyList(),
        tasks: List<PlanTask> = emptyList(),
        mode: Int = DynamicPeriods.MODE_AT_SCREEN,
    ): List<TaskPanel> {
        // Both runs are asked with the WHOLE list of breaks and the answer is selected from them afterwards.
        // Dropping the look-away from the due run (or the poses from the at-line one) would be a different
        // walk, not a filtered one: the chain merge collapses a look-away that touches a pose into the pose,
        // so a spec removed from the list moves the starts of the ones left behind.
        val dragged = screenBreaks.filter { it.restBreak }.mapTo(HashSet()) { it.title }
        val poseDues =
            screenBreakOccurrencesBetween(
                screenBreaks = screenBreaks,
                fromMillis = fromMillis,
                toMillis = toMillis,
                basePeriods = basePeriods,
                blocks = blocks,
                tasks = tasks,
                anchorMillis = nowMillis,
            ).filter { it.title in dragged }
        val lookAwayStarts =
            dynamicPeriodPanels(
                screenBreaks = screenBreaks,
                fromMillis = fromMillis,
                toMillis = toMillis,
                tpMillis = nowMillis,
                basePeriods = basePeriods,
                blocks = blocks,
                tasks = tasks,
                mode = mode,
                anchorMillis = nowMillis,
                atLine = true,
            ).filter { it.title !in dragged && it.startEpochMillis in fromMillis..toMillis }
        return (poseDues + lookAwayStarts).sortedBy { it.startEpochMillis }
    }

    /**
     * PRD §15 / `side-dev/scheduler_logic.py` tests 10–11: the work plan as it must be **displayed** while a screen break
     * sits on the now-line — with the auto panels cut out of what the break REFUSES, so a break the now-line
     * has reached really is the period it says it is for as long as it slides.
     *
     * Refuses, not covers: a break is a period of the kind `no task allowed`, so what it cuts is decided per
     * TASK — is this one's resilience to that kind zero? ([periodAccepts]). A task that has been given a
     * non-zero one keeps its panel through the break; cutting the whole span would state on screen that the
     * scheduler may not use a period it is in fact filling. (This was a per-SHAPE reading until 2026-08-28: a
     * closed head cut, an open tail left alone. There are no shapes now — ADR 0003.)
     *
     * The break's start is fixed, but the plan under it does not follow it: the plan is materialized by [fillSchedule], which by CLAUDE.md's trigger rule runs on a **rule
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
        //
        // The walk is seeded one millisecond PAST the line, not at it. `side-dev/README.md`'s mode 1 says the
        // instant $t_p$ itself must stay free, so the period the line drags is the half-open
        // `(t_p, t_p + duration]` — in the app's discrete time `[t_p + 1, …)`
        // ([DynamicPeriods.Instance.coveredFromMillis]). Seeded at $t_p$ the walk asks for a band with
        // `start <= t_p` and the dragged one starts at `t_p + 1`, so it found nothing: in mode 1 — every
        // moment a device of the account is unlocked, i.e. the ordinary state of the app — this whole
        // function returned the plan untouched and the parked look-away was drawn straight over the task
        // panel the fill had placed under it.
        var end = nowMillis
        var cursor = nowMillis + 1
        while (true) {
            val next = breakPanels
                .filter { it.startEpochMillis <= cursor && it.endEpochMillis > cursor }
                .maxOfOrNull { it.endEpochMillis } ?: break
            if (next <= end) break
            end = next
            cursor = next
        }
        if (end <= nowMillis) return panels
        val chain = breakPanels.filter { it.endEpochMillis > nowMillis && it.startEpochMillis < end }
        // What the chain refuses THIS task: `side-dev/README.md`'s resilience, and nothing else. A dynamic
        // period has no shape any more — it is one span of "no task allowed" end to end — so the question is
        // no longer "which part of the break is open" but the plain one every kind is asked: is this task's
        // resilience to that kind above zero? A task that IS resilient to it is not cut at all, and the
        // calendar draws that part hollow for exactly that reason.
        fun refusedRegions(taskId: TaskId?): List<TaskTimeRange> {
            val task = taskId?.let { tasks[it] }
            val out = mutableListOf<TaskTimeRange>()
            for (band in chain) {
                val from = maxOf(band.startEpochMillis, nowMillis)
                val to = minOf(band.endEpochMillis, end)
                if (to <= from) continue
                if (!periodAccepts(band, task)) out += TaskTimeRange(from, to)
            }
            return mergeOccupied(out)
        }
        return panels.flatMap { panel ->
            when {
                // Fixed blocks and the bands are not the plan; a period cannot move them. A restrictive
                // period is never cut at all — it is what the cut is made of (the §17 wind-down included).
                !isRegeneratedPanel(panel) || panel.isRestrictivePeriod -> listOf(panel)
                // Wholly past, or already past the break: untouched.
                panel.endEpochMillis <= nowMillis || panel.startEpochMillis >= end -> listOf(panel)
                // Every refusing region starts at/after the now-line, so a straddling panel keeps its elapsed
                // head (that time really was worked) and what survives resumes under a distinct id.
                else -> panel.minus(refusedRegions(panel.taskId))
            }
        }
    }

    /**
     * Whether the restrictive period [band] admits [task] at all — the README's resilience, read at a panel.
     *
     * There is nothing special about a screen break here any more. `side-dev/README.md` gives all three
     * dynamic periods the kind [PeriodKinds.NO_TASK], whose default resilience is `0`, so a break admits
     * nobody unless a task has deliberately been given a non-zero resilience to "no task allowed" — which is
     * the same sentence, and the same code path, as any other kind.
     */
    private fun periodAccepts(band: TaskPanel, task: Task?): Boolean {
        if (task == null) return false
        val kind = band.restrictiveKind
        if (kind.isEmpty()) return false
        return task.resilienceFor(kind) > 0.0
    }

    /**
     * PRD §8/§9: the panels with every ON-SCREEN task's work cut out of [noScreenRegions] — the stretches the
     * devices OBSERVED nobody at a screen for ([observedNoScreenRegions]).
     *
     * **A stretch carrying both calendar layers IS a "no on-screen task" period** (ADR 0002 pins that
     * identity), and a no-screen period overrides the on-screen task panels it covers — the same rule a
     * hand-drawn "No screen" panel already follows. Only that half of the rule was implemented: §9 refused to
     * BANK a record over an observed no-screen stretch, but the panel that record would have come from went on
     * being drawn straight across the hatch. So the calendar showed an on-screen task running on a machine the
     * OS reported asleep, which is the thing the bank rule exists to deny.
     *
     * Who is cut is the resilience and nothing else: a task is on-screen exactly when
     * [org.example.project.scheduler.model.Task.onScreen] — a `0` against [PeriodKinds.NO_SCREEN]. §9 lets an
     * off-screen task run in a no-screen period, so its panel is true there and is left alone; so is every
     * restrictive period (a period is not work) and every panel of no task at all.
     *
     * Display-side, like [clipPlanForPinnedScreenBreak] and [carveSleepPanels]: the regions are the past
     * (`[floor, now]`), the fill only ever places ahead of the now-line, and what the devices report is not a
     * user edit — nothing here belongs in the stored plan. Whatever the cut vacates is then idle time and the
     * calendar draws it as a derived "Inactivity" band, exactly as any other uncovered past stretch.
     */
    fun clipRecordsForObservedNoScreen(
        records: List<TaskTimeRange>,
        task: Task?,
        noScreenRegions: List<TaskTimeRange>,
    ): List<TaskTimeRange> {
        if (noScreenRegions.isEmpty()) return records
        val regions = mergeOccupied(noScreenRegions)
        if (regions.isEmpty()) return records
        if (task == null || !task.onScreen) return records
        return records.flatMap { record ->
            subtractRegions(listOf(TaskTimeRange(record.startEpochMillis, record.endEpochMillis)), regions)
        }
    }

    fun clipPanelsForObservedNoScreen(
        panels: List<TaskPanel>,
        tasks: Map<TaskId, Task>,
        noScreenRegions: List<TaskTimeRange>,
    ): List<TaskPanel> {
        if (noScreenRegions.isEmpty()) return panels
        val regions = mergeOccupied(noScreenRegions)
        if (regions.isEmpty()) return panels
        return panels.flatMap { panel ->
            val task = panel.taskId?.let { tasks[it] }
            if (task == null || panel.isRestrictivePeriod || !task.onScreen) listOf(panel)
            else panel.minus(regions)
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
     * core the engine's one cue sweep drives.
     *
     * **Every break keys on the START of the run its own placement rule makes crossable**
     * ([screenBreakCueOccurrencesBetween]): a pose on its undragged **due**, the 20 s look-away on the
     * **at-line** run the calendar draws. Both are fixed instants crossed once, and the look-away's is the
     * same instant the calendar draws it at — which is what asking one run for both had stopped being true.
     *
     * A pose start already in [alreadyNotifiedPoseDues] is omitted, so a sweep that revisits a window
     * announces once; a look-away carries its resume instant (start + duration) as [CueCrossing.endInstant].
     *
     * Staleness (real age), the screen-active gate and the once-only de-dupe stay in the engine, which owns
     * the clock and the fired-boundary memory; this is a pure function of the schedule and the window.
     */
    fun cueCrossings(
        screenBreaks: List<ScreenBreak>,
        windDownInstants: List<Long>,
        automaticSchedule: Boolean,
        alreadyNotifiedPoseDues: Map<String, Long>,
        fromMillis: Long,
        toMillis: Long,
        basePeriods: List<RestrictivePeriod> = emptyList(),
        blocks: List<PlanBlock> = emptyList(),
        tasks: List<PlanTask> = emptyList(),
        /** The `t_p` mode the line is in — the at-line run's half of the reading above is a function of it. */
        mode: Int = DynamicPeriods.MODE_AT_SCREEN,
    ): List<CueCrossing> {
        val out = mutableListOf<CueCrossing>()
        val byTitle = screenBreaks.associateBy { it.title }
        // The window's right edge IS the now-line here: the sweep asks about `[scanFloor, now]`.
        for (panel in screenBreakCueOccurrencesBetween(
            screenBreaks, fromMillis, toMillis, toMillis, basePeriods, blocks, tasks, mode,
        )) {
            val side = byTitle[panel.title] ?: continue
            val start = panel.startEpochMillis
            if (side.restBreak) {
                if (automaticSchedule && alreadyNotifiedPoseDues[panel.title] != start) {
                    out += CueCrossing(start, CueKind.RestPoseDue, panel.title, start)
                }
            } else {
                out += CueCrossing(start, CueKind.LookAwayStart, panel.title, panel.endEpochMillis)
            }
        }
        // Wind-down (bedtime - 1h) instants that fall in the window.
        for (wd in windDownInstants) {
            if (wd in fromMillis..toMillis) out += CueCrossing(wd, CueKind.WindDown, "", wd)
        }
        return out.sortedWith(compareBy({ it.instant }, { it.kind.ordinal }))
    }

    /**
     * `side-dev/README.md`: **when [title]'s dynamic period next begins** at or after [nowMillis], or null
     * within the search window. The one reading of "the next break", shared by the calendar, the cue sweep and
     * the pause-cue publication — all three ask the placement rather than an anchor, so the three cannot
     * disagree about when a break happens.
     */
    fun nextScreenBreakStartMillis(
        screenBreaks: List<ScreenBreak>,
        title: String,
        nowMillis: Long,
        basePeriods: List<RestrictivePeriod> = emptyList(),
        blocks: List<PlanBlock> = emptyList(),
        tasks: List<PlanTask> = emptyList(),
    ): Long? =
        dynamicPeriodPanels(
            screenBreaks = screenBreaks,
            fromMillis = nowMillis,
            toMillis = nowMillis + NEXT_BREAK_SEARCH_MILLIS,
            tpMillis = nowMillis,
            basePeriods = basePeriods,
            blocks = blocks,
            tasks = tasks,
            anchorMillis = nowMillis,
        ).firstOrNull { it.title == title && it.startEpochMillis >= nowMillis }?.startEpochMillis

    /**
     * How far ahead [nextScreenBreakStartMillis] and [poseWindowsBetween] look. A day is far past every bar the
     * README states (the longest is two hours), so a break that is not found inside it is one the environment
     * has suspended indefinitely — a night, a hand-drawn inactivity period without end — and has no next start
     * to name.
     */
    const val NEXT_BREAK_SEARCH_MILLIS: Long = 24L * 60L * 60L * 1000L

    /** One placed pose: `[startMillis, endMillis)`, named by its [ScreenBreak.key]. */
    data class PoseWindow(val key: String, val startMillis: Long, val endMillis: Long)

    /**
     * `docs/scheduler_requirements.md` § *$now line$ 3 modes*: **THE SET OF RULES the scheduler returns for the
     * two poses** — where every 5- and 15-minute dynamic restrictive period falls over the next
     * [NEXT_BREAK_SEARCH_MILLIS], as the recurrence bars place them with **nothing dragged**.
     *
     * This is the one query the server's whole copy of the schedule comes out of, and both readings of it are
     * taken here so they cannot name different breaks:
     *  * the **windows** themselves, which the mode-3 evaluation compares the now-line against — legitimate
     *    there and only there, because mode 3 is the mode in which nothing drags a pose, so where the bars put
     *    one IS where it happens;
     *  * the **two dues**, which are the first window of each kind (`SchedulerEngine.restPoseDueMillisByKey`),
     *    and which the walk-away gate asks a question about the PAST with.
     *
     * It is the undragged run (`atLine = false`), like every other question that is not about the line itself.
     * A window straddling [nowMillis] is kept — the line may be inside a break right now, which is precisely
     * what the server is being asked — while one wholly elapsed is not.
     *
     * The 20 s look-away is excluded ([ScreenBreak.restBreak]): it is assumed taken as it falls due, so it is
     * never cued, and its 20-minute cadence would rewrite the published set for an answer nothing reads.
     */
    fun poseWindowsBetween(
        screenBreaks: List<ScreenBreak>,
        nowMillis: Long,
        basePeriods: List<RestrictivePeriod> = emptyList(),
        blocks: List<PlanBlock> = emptyList(),
        tasks: List<PlanTask> = emptyList(),
    ): List<PoseWindow> {
        val keyOfTitle =
            screenBreaks.filter {
                it.restBreak && it.intervalMillis > 0 && it.durationMillis > 0 &&
                    it.title.isNotBlank() && it.key.isNotBlank()
            }.associate { it.title to it.key }
        if (keyOfTitle.isEmpty()) return emptyList()
        return dynamicPeriodPanels(
            screenBreaks = screenBreaks,
            fromMillis = nowMillis,
            toMillis = nowMillis + NEXT_BREAK_SEARCH_MILLIS,
            tpMillis = nowMillis,
            basePeriods = basePeriods,
            blocks = blocks,
            tasks = tasks,
            anchorMillis = nowMillis,
        )
            .mapNotNull { panel ->
                val key = keyOfTitle[panel.title] ?: return@mapNotNull null
                if (panel.endEpochMillis <= nowMillis) return@mapNotNull null
                PoseWindow(key, panel.startEpochMillis, panel.endEpochMillis)
            }
            .sortedBy { it.startMillis }
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
     * from `now` + the tree + the sleep/screen-break config — the screen-break, sleep-obstacle and §17
     * wind-down ("before bed") panels and the
     * non-pinned auto-fill panels. These carry no authoritative user state, so a re-derive that only moves
     * them is not a syncable change. Pinned panels (user-fixed) and reminder tags (`chore`, which carry the
     * authoritative `checked` state) are NOT regenerated and so are never treated as derived. Mirrors the
     * `kept` filter in [fillSchedule] (screenBreak / derived sleep / `before-bed/{day}` always cut; everything
     * else kept when fixed or a reminder). Used by
     * [org.example.project.scheduler.persistence.SchedulerStateCodec.syncFingerprint] to
     * exclude derived panels from the sync fingerprint, so an engine-tick reschedule that only re-derives
     * them neither marks state dirty nor pushes ("known deviation" fix).
     */
    fun isRegeneratedPanel(panel: TaskPanel): Boolean =
        panel.screenBreak || panel.sleep || panel.id.startsWith(BEFORE_BED_PANEL_ID_PREFIX) ||
            (panel.auto && !panel.pinned && !panel.chore)

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
     * - **restrictive periods** = every panel that names a kind ([TaskPanel.restrictiveKind]), the §15 screen
     *   breaks included. A period is a start, an end and a **kind**, and who may run inside it is each task's
     *   **resilience** to that kind — a multiplier in `[0, 1]` on its priority percentage, `0` forbidding it
     *   outright ([PeriodKinds]). Overlapping periods multiply, so the strictest still forbids. All three
     *   screen breaks carry `no task allowed` end to end, so a task works through one exactly when it has
     *   been given a non-zero resilience to that kind. The reference's rule that a task is a candidate only
     *   while its minimum fits the gap is what enforces "never when its minimum time exceeds what is left of
     *   the break", with no special case. A period that refuses everybody deprives everyone equally, so per
     *   `side-dev/README.md` it creates **no** influence field — which is exactly why a look-away, which
     *   recurs every 20 minutes forever, does not distort the plan around each of its occurrences.
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
        // The device's live ongoing/held pause, if any ([liveRestGap]). Handed to the recurrence bars as the
        // restrictive period it is ([liveRestPeriod]), so the grid moves with a pause the derives have not
        // banked yet — and nothing is stored.
        liveRest: LiveRest? = null,
        // What the DEVICES observed about whether anybody was at a screen ([observedNoScreenRegions], through
        // `SchedulerReducer.noScreenEvidence`). Handed to the recurrence bars as the restrictive periods it is
        // ([observedNoScreenPeriods]), so a pause the app was not watching from the inside — one that ended, one
        // a derive has retired, anything at all before a restart — still bars the breaks the README says it does.
        noScreenEvidence: List<TaskTimeRange> = emptyList(),
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
        // `side-dev/README.md` § *$t_p$ 2 modes*: which mode the now-line is in — mode 1 while a device of the
        // account is unlocked, mode 2 otherwise ([tpMode] / [anyDeviceUnlockedAt]). It decides where the three
        // dynamic periods sit relative to the line and nothing else. The engine injects it through
        // `SchedulerReducer.tpMode`; the default is mode 1, which is what a shell with no device signal
        // (tests, a headless host that cannot read a lock) should assume — somebody is at a screen.
        tpMode: Int = DynamicPeriods.MODE_AT_SCREEN,
    ): List<TaskPanel> {
        val horizon = maxOf(horizonMillis, nowMillis)
        // Cut every non-pinned panel in [now, horizon]; keep fixed (pinned) panels, reminder tags (PRD
        // §14 — kept on the calendar though not obstacles, see isSchedulerFixed), and any panel entirely
        // outside the window — already past (end ≤ now) or beyond the horizon (start > horizon). Screen-break
        // and schedule-DERIVED (`sleep/{day}`) sleep panels are always cut and regenerated fresh below, so
        // they never accumulate — but MATERIALIZED past-sleep panels (PRD §17, allocated id) are a recorded
        // fact and kept, like the materialized Inactivity panels. The §17 wind-down periods
        // (`before-bed/{day}`) are derived from those same windows and are cut and regenerated with them.
        // The ids of the elapsed heads kept below — they are ordinary auto panels, so PRD §9's "two
        // consecutive auto panels of the same task are one block" has to see them beside the generated tail.
        val elapsedHeadIds = HashSet<String>()
        val kept = state.panels.mapNotNull { panel ->
            val survives = when {
                panel.screenBreak -> false
                panel.sleep -> !panel.id.startsWith("sleep/")
                panel.id.startsWith(BEFORE_BED_PANEL_ID_PREFIX) -> false
                else ->
                    // `side-dev/README.md`: EVERY restrictive period is kept, whatever its kind — the two
                    // built-in ones and the account's own alike, read through the single [TaskPanel.restrictiveKind].
                    isSchedulerFixed(panel) || panel.chore || panel.isRestrictivePeriod ||
                        panel.endEpochMillis <= nowMillis || panel.startEpochMillis > horizon ||
                        // An EXTENSION keeps the already-materialized head of the plan (see the parameter).
                        (keepExistingUntilMillis != null && panel.auto && panel.startEpochMillis < keepExistingUntilMillis)
            }
            when {
                survives -> panel
                // `side-dev/README.md` § *frozen past*: **"the schedule at t < $now line$ never changes as
                // $now line$ increases."** An auto panel the line is standing IN is cut by the branch above
                // and the plan is regenerated from `now` — so without this its ELAPSED HEAD, work the app has
                // already told the user it was doing, silently disappears from the timeline on every re-plan
                // (and, because [pastPeriodsForTask] reads these same panels, from the clock replay that seeds
                // the walk, which is the resume contract going with it). It is not banked as a record either:
                // [org.example.project.scheduler.state.SchedulerReducer]'s advance banks a panel only once it
                // has wholly elapsed, precisely so an in-progress one stays a panel.
                //
                // So the head is KEPT, truncated at the line, and the tail alone is re-planned. It stays an
                // ordinary auto panel: the next advance banks it like any other, [mergeSameTaskPanels] fuses
                // it back with the new panel when the re-plan picks the same task again, and it is behind the
                // line so it is never a [futureBlocks] obstacle.
                panel.auto && !panel.chore && panel.taskId != null &&
                    panel.startEpochMillis < nowMillis && panel.endEpochMillis > nowMillis -> {
                    elapsedHeadIds += panel.id
                    panel.copy(endEpochMillis = nowMillis)
                }

                else -> null
            }
        }
        // The user's sleep windows. PRD §8: a sleep window IS an inactivity period — one labelled "Sleep" —
        // so, like every grey period, it is a period accepting NOBODY (see [blockedRegions] below). It is
        // still not an occupancy *obstacle*: a chunk crossing one suspends and resumes on the far side.
        val sleepPanels = sleepPanels(state.sleep, nowMillis, horizon, timeZone)
        // PRD §17 wind-down: **the hour before bed is covered by the period "before bed"**. It is an ordinary
        // restrictive period of its own kind ([PeriodKinds.BEFORE_BED]) — the hour stays empty because every
        // task's default resilience to that kind is `0`, and a task given a value above zero works through it.
        // Derived from the same schedule the sleep windows are, and regenerated with them.
        val beforeBedPanels = beforeBedPanels(state.sleep, nowMillis, horizon, timeZone)
        // The task-tree timeline: while `now` sits between two dated trees the scheduler follows the two
        // trees' BLENDED priorities over the UNION of their leaves, not the live tree's own — so the plan
        // transforms continuously from one arrangement into the next. With no dated tree these collapse to
        // `schedulableLeaves(state)` / `state.tasks`, i.e. exactly the pre-timeline behaviour.
        val leaves = blendedSchedulableLeaves(state, nowMillis)
        val priorities = blendedTaskPriorities(state, nowMillis)
        val keptIds = kept.mapTo(HashSet()) { it.id }
        // Everything below reads task attributes through `working`, which carries the widened map: a leaf
        // that lives only in the other keyframe still gets its title (so its panels are not nameless), its
        // minimum time, its screen flags and its records.
        val working = state.copy(panels = kept, tasks = blendedTaskAttributes(state, nowMillis))

        // `side-dev/scheduler.py` resolves ties by (biggest share, then name); OmniApp's PRD §9 tie-break is
        // (highest absolute priority, then title). [PlanWalk.pick] takes the first candidate on a tie, so
        // handing it this order IS the tie-break.
        val tieBreak =
            compareByDescending<TaskId> { priorities[it] ?: 0.0 }.thenBy { working.tasks[it]?.title.orEmpty() }
        val ordered = leaves.sortedWith(tieBreak)
        val minimumMillisOf = ordered.associateWith { (working.tasks[it]?.minimumMinutes ?: 0).toLong() * MILLIS_PER_MINUTE }
        val planTasks =
            ordered.map {
                PlanTask(
                    id = it,
                    priority = priorities[it] ?: 0.0,
                    minimumMillis = minimumMillisOf[it] ?: 0L,
                    // `side-dev/README.md`: the ONE thing that says where a task may run and at what share.
                    resilience = working.tasks[it]?.resilience.orEmpty(),
                )
            }
        val planner = SchedulerPlanner(planTasks)
        // --- `side-dev/README.md` § *3 Dynamic Restrictive Period*: where the three fall.
        //
        // They are placed by the recurrence bars ([DynamicPeriods]) over the environment they interrupt, so
        // the environment has to be built first: the standing restrictive periods (the user's own, the §17
        // sleep windows) reaching back one lookback behind the now-line, the live pause and what the devices
        // OBSERVED behind the line, and the pre-placed task blocks — a pre-placed task IS a task, so an hour of
        // it is not a rest and bars nothing. It is [dynamicPeriodBase]'s three parts, built out of the panels
        // this fill is keeping rather than out of `state.panels`.
        //
        // Bounded by THIS fill's [horizon], not by the fixed 168h default: a fill for a short horizon must
        // not project a week of breaks it will then carry in `panels`, and a DISPLAY fill for a far week
        // must project across it.
        val dynamicBase =
            (kept.filter { it.isRestrictivePeriod } + sleepPanels + beforeBedPanels).mapNotNull { panel ->
                val kind = panel.restrictiveKind
                if (kind.isEmpty()) null
                else RestrictivePeriod(panel.startEpochMillis, panel.endEpochMillis, kind, panel.title)
            } + listOfNotNull(liveRestPeriod(liveRest)) + observedNoScreenPeriods(noScreenEvidence)
        val dynamicBlocks =
            kept.asSequence()
                .filter { it.taskId != null && !it.chore && !it.isRestrictivePeriod }
                .map { PlanBlock(it.taskId, it.startEpochMillis, it.endEpochMillis) }
                .filter { it.endMillis > it.startMillis }
                .toList()
        val sidePanels =
            screenBreakPanels(
                screenBreaks = state.screenBreaks,
                nowMillis = nowMillis,
                horizonMillis = horizon,
                basePeriods = dynamicBase,
                blocks = dynamicBlocks,
                tasks = planTasks,
                mode = tpMode,
            )
        // `side-dev/README.md` § *$t_p$ 2 modes*, **mode 2**: *"$now line$ must be covered by the period 'no
        // on-screen task'"*, and its own consequence example — *"the gap between the end of the 15min period
        // and $t_p$ is covered by a period 'no on-screen task', filled with tasks that have a non-zero
        // resilience to the kind 'no on-screen task', or no task if none have such resilience"*.
        //
        // It is an **environment period, never a panel**. Emitting it as one is what shipped and was reverted
        // (2026-08-31): the calendar drew a synthetic "Away" band the user did not want, and the revert took
        // the scheduling effect away with the band — mode 2's own rule then reached nothing at all, and the
        // fill went on placing an on-screen task AT the line while no device of the account was unlocked.
        // Built here rather than in [dynamicPeriodPanels] for exactly that reason: what the fill reads and
        // what the calendar draws are two different lists, and only the first of them wants this.
        //
        // Where the app already has evidence the line is covered — this device's ongoing pause
        // ([liveRestPeriod], `closedEnd`), a standing no-screen period, a dynamic period the line is inside —
        // [DynamicPeriods.awayCover] finds nothing left to do and answers null.
        val awayCover =
            DynamicPeriods.awayCover(
                base = DynamicPeriods.Base(dynamicBase, dynamicBlocks, planTasks),
                placed =
                    sidePanels.mapNotNull { panel ->
                        val kind = panel.restrictiveKind
                        if (kind.isEmpty()) null
                        else RestrictivePeriod(panel.startEpochMillis, panel.endEpochMillis, kind, panel.title)
                    },
                tpMillis = nowMillis,
                mode = tpMode,
            )
        if (leaves.isEmpty()) {
            return (kept + sidePanels + sleepPanels + beforeBedPanels).sortedBy { it.startEpochMillis }
        }

        // --- the RESTRICTIVE PERIODS (`side-dev/README.md` § *Restrictive Period*).
        //
        // Every restriction on the timeline is one object now — a start, an end and a KIND — and every task's
        // behaviour inside one is its own resilience to that kind ([Task.resilience]). So there is no longer a
        // list of accepted sets to assemble per sort of band: the grey regions, the no-screen zones and the
        // three dynamic periods all become periods of a kind, and the walk asks [PeriodKinds.multiplier].
        val periodPanels = kept.filter { it.isRestrictivePeriod } + sleepPanels + beforeBedPanels + sidePanels
        val restrictions =
            periodPanels.mapNotNull { panel ->
                val kind = panel.restrictiveKind
                if (kind.isEmpty()) return@mapNotNull null
                val from = maxOf(panel.startEpochMillis, nowMillis)
                val to = panel.endEpochMillis
                if (to <= from) null else RestrictivePeriod(from, to, kind, panel.title)
            } +
                // Mode 2's cover, clipped into the half-open form the rest of the fill measures in. It is the
                // one period whose end is CLOSED — the README covers `t_p` itself — so in discrete time it
                // reaches `now + 1`, where every other period clipped to the line would collapse to nothing
                // and be dropped. That single millisecond IS the rule: what runs at the line must be resilient
                // to "no on-screen task". Everything the cover holds behind the line is already the frozen
                // past, which the fill does not place.
                listOfNotNull(
                    awayCover?.let {
                        RestrictivePeriod(nowMillis, nowMillis + 1L, it.kind, it.label)
                    },
                )
        // The two regions the *display* and the record bank still ask about by name. They are derived from
        // the periods rather than collected separately, so a user-defined kind that happens to refuse
        // everybody behaves exactly like a hand-drawn inactivity period.
        //
        // PRD §17's "before bed" joins the grey ones here for the one reason grey is collected at all: a
        // stretch nobody may run in SUSPENDS a chunk rather than cutting it, and is stepped over by "does the
        // minimum fit?" (CLAUDE.md). The wind-down hour is exactly that — its default resilience turns every
        // task away — so a run that meets it resumes after the night with its minimum intact, like one that
        // meets a sleep window or a screen break. A task deliberately given a resilience to the kind is still
        // placed inside it, which is the same escape a break already has.
        val blockedRegions =
            mergeOccupied(
                restrictions.filter { it.kind == PeriodKinds.NO_TASK || it.kind == PeriodKinds.BEFORE_BED }
                    .map { TaskTimeRange(it.startMillis, it.endMillis) },
            )
        val noScreenRegions =
            mergeOccupied(
                restrictions.filter { it.kind == PeriodKinds.NO_SCREEN }
                    .map { TaskTimeRange(it.startMillis, it.endMillis) },
            )
        // PRD §15: the screen-break regions — now ordinary [PeriodKinds.NO_TASK] periods, kept as their own
        // list only because a break SUSPENDS a chunk rather than cutting it.
        val sideRegions = mergeOccupied(sidePanels.map { TaskTimeRange(it.startEpochMillis, it.endEpochMillis) })
        // The regions that SUSPEND a chunk instead of cutting it: the §15 screen breaks and the §8 grey
        // periods (a hand-added inactivity period, a §17 sleep window). PRD §17 says it in as many words — a
        // task that meets a sleep window is "split and resumes at wake, not charged for the sleep time, like
        // a screen break" — and it is the same rule for the same reason: the stretch belongs to NOBODY, so it
        // costs the run nothing but time. A screen-zone edge is the other kind of boundary: somebody else may
        // run past it, so it ends the run and PRD §9/§10 cut the minimum there.
        val suspendRegions = mergeOccupied(sideRegions + blockedRegions)
        val suspendStarts = suspendRegions.mapTo(HashSet()) { it.startEpochMillis }
        fun covers(regions: List<TaskTimeRange>, t: Long): Boolean =
            regions.any { it.startEpochMillis <= t && t < it.endEpochMillis }


        // The periods cut [now, ∞) at every edge into maximal spans of a constant KIND SET; the LAST one is
        // left open-ended, so a task banned from it is banned "forever" and the field gives it a ramp before
        // the ban and no phantom ramp after the horizon.
        val edges = buildList {
            add(nowMillis)
            for (r in restrictions) {
                if (r.startMillis in (nowMillis + 1) until horizon) add(r.startMillis)
                if (r.endMillis in (nowMillis + 1) until horizon) add(r.endMillis)
            }
        }.distinct().sorted()
        val windows = edges.mapIndexed { i, start ->
            val kinds = restrictions.filterTo(HashSet()) { it.covers(start) }.mapTo(HashSet()) { it.kind }
            PlanWindow.of(start, edges.getOrNull(i + 1), kinds, planTasks)
        }
        // Per window: the effective weights, who may run there, and their locally renormalized shares. The
        // walk asks for all three at every step and rebuilding them there would make the fill quadratic in
        // the number of periods.
        val weightsPer = windows.map { w -> planner.weightsAt(emptyList(), listOf(w), w.startMillis) }
        val accepted =
            windows.mapIndexed { i, w ->
                // A zero-priority task is absent from the share model, so it is a candidate only where
                // nothing with a real share is (the app's documented deviation from the reference).
                planner.candidatesAt(weightsPer[i], planner.permittedAt(emptyList(), listOf(w), w.startMillis))
                    .let { permitted -> ordered.filter { it in permitted } }
            }
        val localShares = weightsPer.mapIndexed { i, w -> planner.localSharesOf(w, accepted[i]) }

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
        val refusedHere = liveForcedSwitchTask(state.forcedSwitch, pastBlocks, nowMillis)
        val runningAtLine = planner.lastRun(pastBlocks, nowMillis)
        walk.setLast(refusedHere ?: runningAtLine)

        // PRD §13 "start this task now": the mirror image of the refusal above — the user named the task the
        // plan must place, so it takes the FIRST slot this fill picks rather than being kept out of it. It is
        // consumed there and nowhere else: the walk is charged for the slot exactly as if it had chosen it,
        // and everything after is the ordinary walk. A named task the current period will not have (a break,
        // a no-screen zone, a minimum that does not fit before the next cutting edge) simply loses its turn
        // here — the marker is not honoured by hunting for a later window, since "now" is the whole of what
        // was asked; the request then dies the ordinary way, as soon as another task has been served past it.
        var forcedStartTask = liveForcedStartTask(state.forcedStart, pastBlocks, nowMillis)

        // `side-dev/scheduler.py` `Walk.run`: *"if head is not None and head[1] < minimum[head[0]] → pending"*
        // — **the chunk the timeline is in the MIDDLE of resumes; it is not re-picked.** So `last` never gets
        // to refuse it and the never-twice-in-a-row rule does not fire on a run that never ended, and the
        // block the user is looking at ends up exactly one minimum long instead of one minimum PLUS whatever
        // had already elapsed.
        //
        // This could not be seeded while the straddling panel's elapsed head was being thrown away (see the
        // `kept` filter above): with nothing behind the line there was no run in progress to find, and PRD
        // §10's continuous-effort credit shortened the fresh pick after the fact instead. That credit still
        // answers for an effort the records alone carry; here it is simply never reached, so the two cannot
        // both apply to one slot.
        //
        // [SchedulerPlanner.headRun] is the run at the end of the past and is not required to reach the line,
        // so it is qualified by [SchedulerPlanner.lastRun], which is: a run that stopped an hour ago is
        // history, not a chunk to resume. Both §7's refusal and §13's request are the user overriding what
        // the plan would do at the line, so either of them drops the resume — otherwise the press would be
        // silently swallowed by a chunk that happened to be unfinished.
        val resumedHead =
            planner.headRun(pastBlocks, nowMillis)
                ?.takeIf { (id, _) ->
                    id == runningAtLine && id != refusedHere &&
                        (forcedStartTask == null || forcedStartTask == id)
                }
                ?.let { (id, served) ->
                    val owed = (minimumMillisOf[id] ?: 0L) - served
                    // Under a minute is the fill's own unplaceable crumb, not a chunk worth resuming.
                    if (owed >= MILLIS_PER_MINUTE) id to owed else null
                }

        val generated = mutableListOf<TaskPanel>()
        var cursor = nowMillis
        var index = 0
        var idCounter = 0
        // PRD §15: the task whose chunk is mid-placement, split across a screen break, with the work it still
        // owes. Carried across iterations so it resumes after the break rather than being re-picked mid-chunk.
        var pending: Pair<TaskId, Long>? = resumedHead
        fun nextAutoId(): String {
            while ("auto/$idCounter" in keptIds) idCounter++
            return "auto/${idCounter++}"
        }
        // `side-dev/README.md` § *Alternative Schedules*: every rule the scheduler makes also names **who runs
        // from here instead**, and a rule of this fill is a panel — so the alternative is emitted with it,
        // exactly as `side-dev/scheduler.py`'s `Walk._emit` carries `alt` beside the placement and as
        // [SchedulerPlanner.runRange] hands it to its [SchedulerPlanner.PlacementCollector]. Keeping the two
        // drivers in step (CLAUDE.md) means this driver names it too.
        fun emit(taskId: TaskId, start: Long, end: Long, alternative: TaskId?) {
            generated += TaskPanel(
                id = nextAutoId(),
                taskId = taskId,
                title = working.tasks[taskId]?.title.orEmpty(),
                startEpochMillis = start,
                endEpochMillis = end,
                pinned = false,
                auto = true,
                alternativeTaskId = alternative,
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
            // `side-dev/scheduler.py`: the percentages the walk races on here are the EFFECTIVE ones — each
            // task's share after its resilience to the kinds covering this span, renormalized over whoever is
            // left. A task half-resilient to a period in force runs there, just less.
            val weightsHere = weightsPer[here]
            val sharesHere = localShares[here]
            val period =
                if (allowedHere.isNotEmpty()) planner.periodOf(allowedHere, sharesHere) else planner.minPeriodMillis

            // A committed block cannot be moved: the cursor walks OVER it, and the walk charges the whole span
            // to its task, so committed work counts exactly like auto-placed work.
            val block = futureBlocks.firstOrNull { it.startMillis <= cursor && cursor < it.endMillis }
            if (block != null) {
                walk.serveWeighted(
                    block.taskId,
                    (block.endMillis - cursor).toDouble(),
                    block.taskId?.let { weightsHere[it] ?: planner.share[it] ?: 0.0 } ?: 0.0,
                )
                walk.relax(0.0, period, allowedHere) // no forgetting here: the block is not ours
                cursor = block.endMillis
                pending = null
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
            val insideBreak = covers(sideRegions, cursor)
            val insideSuspend = insideBreak || covers(blockedRegions, cursor)
            if (pending != null && !insideSuspend && pending.first !in allowedHere) pending = null
            val resume = if (insideSuspend) null else pending
            // `side-dev/README.md`: who may run inside a period is its KIND and the task's resilience to it,
            // and nothing else. The suspended task used to be excluded from its own break here — a rule that
            // made sense while a break's accepted set was a shape ("off-screen work only", "break-doable
            // only") and the task at hand was in it for the wrong reason. It cannot be right now: a task is
            // in `allowedHere` inside a break only if it has DELIBERATELY been given a non-zero resilience to
            // "no task allowed", which is precisely the statement "I can do this during a break".
            val candidates = allowedHere

            // `side-dev/README.md` § *No idling*: **"Anywhere that is not covered by restrictive periods which
            // would prevent any task from being scheduled, the scheduler must schedule a task, for any $now
            // line$ and $now line$ mode."** So the ONLY thing that empties a stretch is that nobody may run in
            // it — the reference's `if not cand: emit(IDLE)`, and exactly what [SchedulerPlanner.runRange]
            // does. There is no second reason, and there must not be one: the minimum execution time is a
            // **soft** optimization goal (§ *Soft Minimum Execution Time* — *"another optimization goal"*),
            // and a soft goal may not create idle time a hard constraint forbids.
            //
            // What used to be here were two extra reasons to idle, and both were divergences from
            // [SchedulerPlanner.runRange] that CLAUDE.md's "keep the two drivers in step" did not sanction:
            // a `_fits_from` filter that dropped every task whose minimum did not fit the room ahead, and a
            // sub-minute `crumb` rule (with a `free_tail` stretch beside it). Both cited `scheduler_logic.py`,
            // a reference file that no longer exists — the current `side-dev/scheduler.py` has neither, and
            // clips the chunk at the next environment bound instead. Measured before the change: a 45-minute
            // task and a pinned block twenty minutes out left `[now, now + 20 min)` empty with nothing
            // restricting it at all.
            //
            // The soft goal is still served everywhere it can be: [PlanWalk.chunkMillis] floors a chunk at the
            // task's minimum, so a slot is short only where the timeline itself is.
            if (candidates.isEmpty()) {
                // Nothing may occupy this stretch at all — a grey period, or a break nobody is resilient to.
                // Idle time for every clock, and the run in progress is SUSPENDED rather than ended (the
                // reference leaves `pending` standing here for exactly that reason).
                if (limit == null) break
                walk.idle()
                cursor = limit
                continue
            }

            // PRD §13 "start this task now" — asked once, at the first slot the fill actually places.
            // A suspended chunk is never preempted (PRD §15 — it is mid-placement, not a fresh pick); at the
            // FIRST pick, which is the only one this can be, there is none.
            val forcedHere = forcedStartTask?.takeIf { resume == null && it in candidates }
            forcedStartTask = null
            val taskId = forcedHere ?: resume?.first ?: walk.pick(candidates, shares = sharesHere) ?: break
            val boost = planner.boostAt(taskId, cursor)
            var need =
                resume?.second
                    ?: walk.chunkMillis(taskId, candidates, boost, shares = sharesHere).roundToLong().coerceAtLeast(1L)
            // PRD §10: a task whose continuous effort is still running at the now-line is scheduled for the
            // REMAINDER of its minimum, not a fresh one, so the block it merges into is exactly one minimum
            // long. Only the first chunk can have such an effort behind it, and only when the walk did not
            // already decide to give the task MORE than its minimum (a catch-up must never be shortened).
            if (resume == null && cursor == nowMillis && need <= (minimumMillisOf[taskId] ?: 0L)) {
                need = (scheduledSpanMinutes(working, taskId, nowMillis) * MILLIS_PER_MINUTE).coerceAtLeast(1L)
            }
            val end =
                minOf(
                    SchedulerPlanner.advance(cursor, need, horizon),
                    limit ?: Long.MAX_VALUE,
                )
            if (end <= cursor) break
            // `side-dev/scheduler.py`: `alt = self._alternative(v, cand, name, p_local)` — read BEFORE the
            // clocks are charged, so the alternative answers "who instead?" at the same instant, and against
            // the same claims, as the pick it stands in for.
            emit(taskId, cursor, end, walk.alternative(candidates, taskId, sharesHere))
            val placed = end - cursor
            // `side-dev/scheduler.py`: `v[name] += served / w[name]` — charged against the task's EFFECTIVE
            // weight, its percentage after resilience, and at the PLAIN rate. The field lengthens the slot
            // ([boost] above); it does not also discount what the slot costs, or the compensation would be
            // paid twice over — which is the overcompensation the README rules out.
            walk.serveWeighted(taskId, placed.toDouble(), weightsHere[taskId] ?: planner.share[taskId] ?: 0.0)
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
            index++
        }

        // --- phase 2 (`side-dev/scheduler_logic.py`): the context is frozen forever, so settle what is still owed and then
        // attach the analytic cycle — the "list of rules + repeat" of `side-dev/README.md` — unrolled out to the
        // horizon. Its shares are exact by construction, unlike the greedy's asymptotic ones. With screen
        // breaks enabled the context never freezes inside the horizon, so this simply never runs.
        if (frozen && cursor < horizon && index < maxPanels) {
            val hereIndex = windowAt(cursor)
            val allowedHere = accepted[hereIndex]
            val weightsHere = weightsPer[hereIndex]
            val sharesHere = localShares[hereIndex]
            if (allowedHere.isNotEmpty()) {
                val period = planner.periodOf(allowedHere, sharesHere)
                // `side-dev/scheduler.py` `Walk.run`'s `pending`, reaching phase 2 for the same reason §13's
                // request does just below: a timeline nothing disturbs freezes before phase 1 places anything,
                // and the chunk the line is in the MIDDLE of must not be dropped there. Charged like any other
                // slot, so the settle and the cycle after it go on from the state the walk would have been in.
                pending?.takeIf { it.first in allowedHere }?.let { (id, owed) ->
                    val end = SchedulerPlanner.advance(cursor, owed, horizon)
                    if (end > cursor) {
                        emit(id, cursor, end, walk.alternative(allowedHere, id, sharesHere))
                        walk.serveWeighted(id, (end - cursor).toDouble(), weightsHere[id] ?: 0.0)
                        walk.relax((end - cursor).toDouble(), period, allowedHere)
                        cursor = end
                        index++
                        // A §13 request naming the resumed task has just been answered by the resume itself.
                        if (forcedStartTask == id) forcedStartTask = null
                    }
                }
                pending = null
                // PRD §13 "start this task now": the request is answered wherever the fill's first slot falls,
                // and on a timeline nothing disturbs (no screen breaks, no fixed blocks) that is HERE — phase 1
                // freezes before placing anything. Same act as there: the named task is emitted and charged
                // like any other pick, so the settle below and the cycle after it (phased off the walk) go on
                // from exactly the state the walk would have been in had it chosen the task itself.
                forcedStartTask?.takeIf { it in allowedHere }?.let { forced ->
                    val boost = planner.boostAt(forced, cursor)
                    val need =
                        walk.chunkMillis(forced, allowedHere, boost, shares = sharesHere)
                            .roundToLong().coerceAtLeast(1L)
                    val end = SchedulerPlanner.advance(cursor, need, horizon)
                    if (end > cursor) {
                        emit(forced, cursor, end, walk.alternative(allowedHere, forced, sharesHere))
                        walk.serveWeighted(forced, (end - cursor).toDouble(), weightsHere[forced] ?: 0.0)
                        walk.relax((end - cursor).toDouble(), period, allowedHere)
                        cursor = end
                        index++
                    }
                }
                forcedStartTask = null
                val settleEnd =
                    SchedulerPlanner.advance(
                        cursor, (SchedulerPlanner.SETTLE_PERIODS * period).roundToLong(), horizon,
                    )
                while (cursor < settleEnd && index < maxPanels && walk.spread(allowedHere) > period) {
                    val name = walk.pick(allowedHere, shares = sharesHere) ?: break
                    val boost = planner.boostAt(name, cursor)
                    val need =
                        walk.chunkMillis(name, allowedHere, boost, shares = sharesHere)
                            .roundToLong().coerceAtLeast(1L)
                    val end = SchedulerPlanner.advance(cursor, need, horizon)
                    if (end <= cursor) break
                    emit(name, cursor, end, walk.alternative(allowedHere, name, sharesHere))
                    walk.serveWeighted(name, (end - cursor).toDouble(), weightsHere[name] ?: 0.0)
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
                        // `side-dev/README.md`: the steady state under a standing restrictive period is the
                        // WEIGHTED one — a task half-resilient to a period in force keeps half its percentage
                        // there, so the cycle the plan converges on is built on the effective shares.
                        planner.steadyCycle(allowedHere, sharesHere)
                            .let {
                                if (it.size > SchedulerPlanner.MAX_RULES) planner.coarseCycle(allowedHere, sharesHere)
                                else it
                            }
                            .filter { it.taskId != null && it.durationMillis > 0L },
                        walk,
                        allowedHere,
                    )
                // `side-dev/README.md` § *Alternative Schedules* under the analytic cycle: the cycle is a fixed
                // rotation the walk has converged on, so "who runs from here instead" is simply **the next
                // task the rotation reaches** — the same task the greedy's claims would name, said once for
                // the whole repeat instead of recomputed per slot (the walk is not advanced here). Null where
                // the rotation holds one task only, which is the cycle's way of saying there is nobody else.
                fun cycleAlternative(at: Int): TaskId? {
                    for (step in 1 until cycle.size) {
                        val other = cycle[(at + step) % cycle.size].taskId
                        if (other != null && other != cycle[at % cycle.size].taskId) return other
                    }
                    return null
                }
                var slotIndex = 0
                while (cursor < horizon && index < maxPanels && cycle.isNotEmpty()) {
                    val at = slotIndex++ % cycle.size
                    val slot = cycle[at]
                    val end = SchedulerPlanner.advance(cursor, slot.durationMillis, horizon)
                    if (end <= cursor) break
                    emit(slot.taskId!!, cursor, end, cycleAlternative(at))
                    cursor = end
                    index++
                }
            }
        }
        // PRD §9: two consecutive auto panels of the same task merge into one block. Screen-break and sleep
        // panels are added as-is (they split the run, so adjacent same-task pieces don't touch and stay apart).
        return (
            kept.filterNot { it.id in elapsedHeadIds } + sidePanels + sleepPanels + beforeBedPanels +
                mergeSameTaskPanels(kept.filter { it.id in elapsedHeadIds } + generated)
            ).sortedBy { it.startEpochMillis }
    }

    /**
     * `side-dev/README.md` § *Alternative Schedules* **at the now-line**: *"the task that must be scheduled if
     * the task scheduled by the scheduler can't be scheduled now"*, read out of the rules the fill returned.
     *
     * `side-dev/scheduler.py`'s `Scheduler.alternative_at`, over the panel list this app's scheduler answers
     * with. The line in mode 1 sits at the very edge of the period it is dragging, so the instant
     * [millis] itself is often inside a stretch nobody may run in: the question is then about the **next**
     * thing scheduled, not about the emptiness the line is standing in. Hence the two passes — the rule
     * covering the line first, then the first rule ahead of it.
     *
     * Null where the rules name nobody: an empty timeline, or a stretch only one task was allowed in. The
     * README's own use of the answer is [org.example.project.scheduler.model.ForcedTaskSwitch] (PRD §7
     * "Switch task"): refuse the scheduled task at [millis] and the re-plan starts this one there.
     */
    fun alternativeTaskAt(panels: List<TaskPanel>, millis: Long): TaskId? {
        val rules = panels.filter { it.auto && it.taskId != null && it.alternativeTaskId != null }
            .sortedBy { it.startEpochMillis }
        rules.firstOrNull { it.startEpochMillis <= millis && millis < it.endEpochMillis }
            ?.let { return it.alternativeTaskId }
        return rules.firstOrNull { it.endEpochMillis > millis }?.alternativeTaskId
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
            // `side-dev/README.md`: a resilience IS a scheduling rule — changing one changes the plan, so it
            // belongs in the signature (CLAUDE.md: anything new that wants to re-plan belongs here).
            for ((kind, value) in task.resilience.entries.sortedBy { it.key }) {
                result = 31 * result + kind.hashCode()
                result = 31 * result + value.hashCode()
            }
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

    /**
     * PRD §4 *Presentation*: the shortest path down to every task at once — what a Change Task menu row is
     * **named** by, and what its rows are sorted by.
     *
     * **The walk is the CELL/LIST tree, not [Task.childTaskIds].** That denormalized field only tracks
     * freshly-typed children and goes stale (the reason [childTitlesLabel] reads the structure instead), so
     * a BFS over it dies almost at once on a mature account and every row falls back to naming itself: on
     * the release account, 153 of the 163 tasks that ARE in the tree came out as a bare title, which turned
     * a menu of 64 tasks all called "planning" into 64 identical rows — the thing the path exists to tell
     * apart. It also flattened the menu's first sort key to the constant 1.
     *
     * Same walk, same rules as [firstTaskOccurrences]: a blank-titled cell is PRD §4's deleted one, so it is
     * neither named nor descended into, and each LIST is entered once (a sub-list belongs to the task id, so
     * a mirrored sub-tree is one list under many parents). It is **breadth-first**, which is what makes the
     * first path reached the SHORTEST one — a mirrored task is named by its shallowest occurrence, where
     * [firstTaskOccurrences] answers with its first in reading order. The two agree about what is *in* the
     * tree and are deliberately allowed to differ about which occurrence to name.
     *
     * A task with no entry here is not in the tree at all — a detached parent, a task stranded inside one's
     * sub-tree, a tombstone kept for its records — and [changeTaskMenuLabel] names those by what they hold.
     */
    fun shortestTaskTreePaths(state: SchedulerState): Map<TaskId, List<TaskId>> {
        // The two well-known tasks above the root list, so a row reads "root / main / …" exactly as it did
        // when the path was walked through the task links. [taskPathLabel] drops whichever is absent.
        val prefix = listOf(WellKnownIds.ROOT_TASK, WellKnownIds.MAIN_TASK)
        val shortest = HashMap<TaskId, List<TaskId>>()
        val visitedLists = mutableSetOf(state.rootListId)
        var frontier = listOf(state.rootListId to prefix)
        while (frontier.isNotEmpty()) {
            val next = mutableListOf<Pair<CellListId, List<TaskId>>>()
            for ((listId, path) in frontier) {
                val list = state.lists[listId] ?: continue
                for (cellId in list.cellIds) {
                    val taskId = state.cells[cellId]?.taskId ?: continue
                    if (isTextuallyEmptyCell(state, cellId)) continue
                    val here = path + taskId
                    if (isSelectableCell(state, cellId) && taskId !in shortest) shortest[taskId] = here
                    val childListId = state.tasks[taskId]?.childListId ?: continue
                    if (visitedLists.add(childListId)) next.add(childListId to here)
                }
            }
            frontier = next
        }
        return shortest
    }

    /**
     * [shortestTaskTreePaths] for one task, falling back to the task alone when the tree does not hold it.
     *
     * Prefer the map wherever more than one task is asked about: this walks the whole tree per call, and the
     * menu asks it once per row **and** once per comparison while sorting.
     */
    fun shortestTaskTreePath(state: SchedulerState, taskId: TaskId): List<TaskId> =
        shortestTaskTreePaths(state)[taskId] ?: listOf(taskId)

    fun taskPathLabel(state: SchedulerState, taskId: TaskId): String =
        taskPathLabel(state, shortestTaskTreePath(state, taskId))

    private fun taskPathLabel(state: SchedulerState, path: List<TaskId>): String =
        path.mapNotNull { state.tasks[it]?.title }.joinToString(" / ")

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

    /**
     * [paths] is [shortestTaskTreePaths], taken as an argument rather than recomputed: it is a walk of the
     * whole tree, and the sort below asks it once per COMPARISON. Every entry point that builds a menu
     * computes it once and hands it down.
     */
    private fun matchingUserTaskIds(
        state: SchedulerState,
        text: String,
        paths: Map<TaskId, List<TaskId>>,
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
            .sortedWith(taskIdMenuSort(state, paths))

    /**
     * PRD §4 *Sorting*: shortest path first, then alphabetically by that path, then by child titles — with
     * every task the tree does NOT hold pushed to the END, ahead of nothing.
     *
     * That last clause is the one thing the PRD's three keys cannot answer, because such a task has no path
     * to be short: it is a detached parent, or a task stranded inside one, and the row exists only as the
     * way to pull it back. Ranking it by a nominal length of 1 put it FIRST — so on the release account the
     * four unreachable "planning" tasks led a menu of 64, which is where the user clicks, and every one of
     * them answers "go to task" with a greyed entry. The rows that are actually in the tree come first.
     */
    private fun taskIdMenuSort(state: SchedulerState, paths: Map<TaskId, List<TaskId>>) =
        compareBy<TaskId>(
            { if (paths.containsKey(it)) 0 else 1 },
            { (paths[it] ?: listOf(it)).size },
            { taskPathLabel(state, paths[it] ?: listOf(it)) },
            { childTitlesLabel(state, it) },
        )

    /**
     * PRD §4 *Presentation*: a menu row shows the task's shortest path in the tree — "**or a list of child
     * titles if no cells point to it**".
     *
     * "No cells point to it" is read as **not in the tree** ([shortestTaskTreePaths] has no path for it),
     * which is the same predicate [taskListEntries] uses for membership and the same one "go to task" is
     * greyed on — one answer, not three. It is wider than `taskHasCells`, deliberately: a task stranded
     * inside a *detached parent's* sub-tree still has a cell, but there is no path in the tree to name it
     * by, so it is named by what it holds exactly as the detached parent above it is. A task with nothing
     * under it falls back to its own title, so no row is ever blank.
     */
    private fun changeTaskMenuLabel(
        state: SchedulerState,
        taskId: TaskId,
        paths: Map<TaskId, List<TaskId>>,
    ): String {
        val childLabel = childTitlesLabel(state, taskId)
        val path = paths[taskId]
        if (path == null) {
            return childLabel.ifEmpty { state.tasks[taskId]?.title.orEmpty() }
        }
        val pathLabel = taskPathLabel(state, path)
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
    ): List<TaskId> = eligibleAssignTaskIds(state, cellId, text, shortestTaskTreePaths(state), excludeTaskId)

    private fun eligibleAssignTaskIds(
        state: SchedulerState,
        cellId: CellId,
        text: String,
        paths: Map<TaskId, List<TaskId>>,
        excludeTaskId: TaskId?,
    ): List<TaskId> {
        val siblings = siblingTaskIds(state, cellId)
        val collisionScope = assignCollisionScope(state, cellId)
        return matchingUserTaskIds(state, text, paths, excludeTaskId).filter { candidate ->
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
        // One walk of the tree for the whole menu — the sort and every row's label read it (ADR 0009).
        val paths = shortestTaskTreePaths(state)
        val matching = eligibleAssignTaskIds(state, cellId, draftText, paths, excludeTaskId)
        return buildList {
            add(ChangeTaskMenuEntry(taskId = null, label = "New task"))
            for (taskId in matching) {
                add(
                    ChangeTaskMenuEntry(
                        taskId = taskId,
                        label = changeTaskMenuLabel(state, taskId, paths),
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
        val paths = shortestTaskTreePaths(state)
        val matching = matchingUserTaskIds(state, draftText, paths, excludeTaskId).filter { isCalendarPanelTarget(state, it) }
        return buildList {
            add(ChangeTaskMenuEntry(taskId = null, label = "New task"))
            for (taskId in matching) {
                add(ChangeTaskMenuEntry(taskId = taskId, label = changeTaskMenuLabel(state, taskId, paths)))
            }
        }
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
        // [WellKnownIds.MAIN_LIST] is seeded as well as [SchedulerState.rootListId]: every tree in the
        // account is rooted there (SchedulerState.empty, withTaskTreeLoaded, projectDefaultSubtree), so for
        // all of them this is the same list twice. It matters for the ONE projection that re-roots the state
        // elsewhere — PRD §7's "All tasks" window
        // ([org.example.project.scheduler.state.projectTaskList]), whose synthetic root holds one cell per
        // task: a real root cell that is not the first occurrence of its task (nor an empty placeholder) is
        // reachable from neither that root nor a detached parent, and without this seed the first edit
        // boundary in that window would prune it out of the tree.
        val queue = ArrayDeque(listOf(state.rootListId, WellKnownIds.MAIN_LIST) + detachedRoots)
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
     * [resilience] (`side-dev/README.md`'s per-kind multipliers — what the two screen switches used to be),
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
        /** `side-dev/README.md`: the task's resilience overrides, kind by kind. Empty = every default. */
        val resilience: Map<String, Double> = emptyMap(),
        /**
         * PRD §5: the **titles** of the categories the task carries. Titles rather than ids because the
         * clipboard is text a person reads, and because a category is attached BY NAME everywhere else in
         * the app — a paste therefore lands on the category of that name where the account already has one
         * and mints it where it has not, which is exactly what typing the name into the row's field does.
         */
        val categories: List<String> = emptyList(),
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
    const val COPIED_TASKS_SECTION_HEADER: String = "Copied tasks:"

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
        val includePriorityPercentages: Boolean = true,
        val includeMinimumTime: Boolean = true,
        val excludeTitle: String = "",
    ) {
        companion object {
            /** The account's answers (what every copy uses unless a caller says otherwise). */
            fun from(state: SchedulerState): CopyOptions =
                CopyOptions(
                    includeIds = state.copyIncludeIds,
                    priorityTables = state.copyPriorityTables,
                    includeText = state.copyIncludeText,
                    includePriorityPercentages = state.copyIncludePriorityPercentages,
                    includeMinimumTime = state.copyIncludeMinimumTime,
                    excludeTitle = state.copyExcludeTitle,
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
    /** The pre-resilience switch. Still READ so an older clipboard pastes; nothing writes it. */
    private const val ATTR_NO_SCREEN: String = "can be done during a no-screen period"

    /**
     * `side-dev/README.md` § *Restrictive Period*: one line per resilience override, `- resilience to <kind>:
     * <n> %`. The kind is spelled out because it is what a person reading the clipboard needs; the value is a
     * percentage because that is what the multiplier means — 0 % is forbidden, 100 % is unaffected.
     */
    /**
     * PRD §5: `- category: <title>`, one line per category the task carries. One line each rather than a
     * comma-separated list, so a title holding a comma needs no second escaping rule.
     */
    private const val ATTR_CATEGORY: String = "category"
    private const val ATTR_RESILIENCE: String = "resilience to"
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
    ): CopiedNode? {
        val cell = state.cells[cellId]
        val taskId = cell?.taskId
        val task = taskId?.let { state.tasks[it] }
        val title = task?.title.orEmpty()
        if (options.excludeTitle.isNotBlank() && title == options.excludeTitle) return null
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
                .mapNotNull { copiedSubtree(state, it, remainingDepth - 1, options) }
        return CopiedNode(
            title = title,
            children = children,
            taskId = taskId.takeIf { options.includeIds },
            rowWeights = rowWeights,
            childHeader = childHeader,
            minMinutes = task?.minimumMinutes,
            resilience = task?.resilience.orEmpty(),
            categories = task?.categoryIds.orEmpty().mapNotNull { state.categoryById(it)?.title },
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
        val nodes = cellIds.filter { isPopulated(state, it) }
            .mapNotNull { copiedSubtree(state, it, maxDepth, options) }
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
    private fun renderTaskSummary(nodes: List<CopiedNode>, options: CopyOptions): List<String> {
        val seen = LinkedHashSet<String>()
        val lines = ArrayList<String>()

        fun addIfNeeded(node: CopiedNode) {
            val key = node.taskId?.value ?: node.title
            if (!seen.add(key)) return
            val fields = ArrayList<String>()
            if (options.includeMinimumTime) {
                fields += "minimum time: ${node.minMinutes ?: DEFAULT_MINIMUM_MINUTES} min"
            }
            if (options.includeIds && node.taskId != null) {
                fields += "id: ${node.taskId.value}"
            }
            if (options.includeText && node.text.isNotBlank()) {
                fields += "text: ${escapeField(node.text)}"
            }
            if (fields.isEmpty()) return
            lines += "- ${escapeTitleField(node.title)}: ${fields.joinToString(", ")}"
        }

        fun walk(ns: List<CopiedNode>) {
            for (n in ns) {
                addIfNeeded(n)
                walk(n.children)
            }
        }

        walk(nodes)
        return lines
    }

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
                if (options.includeMinimumTime) {
                    attribute(d, ATTR_MIN_TIME, "${n.minMinutes ?: DEFAULT_MINIMUM_MINUTES} min")
                }
                // `side-dev/README.md`: one line per resilience that differs from what a FRESH task carries
                // ([Task.DEFAULT_RESILIENCE]), sorted so a copy is stable. An ordinary on-screen task
                // therefore says nothing about a screen — exactly as its edit window reads — and a kind the
                // destination account has never heard of is harmless because the paste path starts from those
                // same defaults.
                for (kind in (n.resilience.keys + Task.DEFAULT_RESILIENCE.keys).sorted()) {
                    val value = PeriodKinds.resilienceFor(n.resilience, kind)
                    if (value == PeriodKinds.resilienceFor(Task.DEFAULT_RESILIENCE, kind)) continue
                    attribute(d, "$ATTR_RESILIENCE $kind", "${formatSharePercent(value)} %")
                }
                // PRD §5: the categories the task carries, by name and in the order it carries them.
                for (name in n.categories) attribute(d, ATTR_CATEGORY, escapeField(name))
                // PRD §13: the whole weight table of every sub-list travels — this cell's value row, and
                // the weight columns of the sub-list it parents — unless the window's switch asked for the
                // percentage those two produce instead, which is written on every node (it IS the copy).
                if (options.priorityTables) {
                    if (n.rowWeights != DEFAULT_WEIGHTS) attribute(d, ATTR_WEIGHTS, formatWeights(n.rowWeights))
                    if (n.children.isNotEmpty() && n.childHeader != DEFAULT_WEIGHTS) {
                        attribute(d, ATTR_COLUMNS, formatWeights(n.childHeader))
                    }
                } else if (options.includePriorityPercentages) {
                    attribute(d, ATTR_SHARE, "${formatSharePercent(n.rowWeights.firstOrNull() ?: 1.0)} %")
                }
                // Task text is intentionally not part of the tree payload anymore. It is listed once in the
                // human-facing summary below the tree, and the parser still accepts legacy clipboard text
                // blocks for backward compatibility.
                if (n.scheduleUnit.isNotEmpty()) {
                    line(d, "$ATTR_MARKER$ATTR_UNIT:")
                    for (step in n.scheduleUnit) {
                        line(d + 1, "$ATTR_MARKER${escapeField(step.title)}: ${step.spanMinutes} min")
                    }
                }
                render(n.children, depth + 1)
            }
        }
        render(nodes, 0)
        val summary = renderTaskSummary(nodes, options)
        if (summary.isNotEmpty()) {
            sb.append('\n')
            sb.append(COPIED_TASKS_SECTION_HEADER).append('\n')
            for (line in summary) {
                sb.append(line).append('\n')
            }
        }
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
        val headerAt = lines.indexOfLast { it.trim() == COPIED_TASKS_SECTION_HEADER }
        val effectiveLines = if (headerAt >= 0) lines.take(headerAt) else lines
        val entries = ArrayList<MutableCopiedNode>()
        val depths = ArrayList<Int>()
        var i = 0
        while (i < effectiveLines.size) {
            val depth = indentOf(effectiveLines[i])
            val rest = effectiveLines[i].substring(depth)
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
            while (i < effectiveLines.size) {
                val d = indentOf(effectiveLines[i])
                val body = effectiveLines[i].substring(d)
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
                        while (i < effectiveLines.size) {
                            val sd = indentOf(effectiveLines[i])
                            val step = effectiveLines[i].substring(sd)
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
                        // The verbatim block: in the modern format the block is indented deeper than the
                        // marker, but older 1.6-era clipboard payloads kept the text at the same indent as
                        // the attribute itself. Keep accepting both: the block ends before the next task or
                        // attribute line, while blank lines stay part of the text.
                        val textLines = ArrayList<String>()
                        while (i < effectiveLines.size) {
                            val nextDepth = indentOf(effectiveLines[i])
                            val nextBody = effectiveLines[i].substring(nextDepth)
                            if (nextBody.isBlank()) {
                                textLines.add("")
                                i++
                                continue
                            }
                            if (nextDepth <= depth || (nextDepth == depth + 1 && nextBody.startsWith(ATTR_MARKER))) break
                            textLines.add(if (nextDepth >= depth + 2) nextBody else nextBody)
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
            // The pre-resilience switch, read-only: "can be done during a no-screen period" is exactly a
            // resilience of 1 to "no on-screen task" — i.e. NO override, since 1 is that kind's default —
            // and "no" is the 0 an on-screen task carries.
            ATTR_NO_SCREEN -> when (value) {
                "yes" -> node.resilience.remove(PeriodKinds.NO_SCREEN)
                "no" -> node.resilience[PeriodKinds.NO_SCREEN] = 0.0
                else -> return false
            }
            ATTR_CATEGORY -> unescapeField(value).trim().takeIf { it.isNotEmpty() }?.let(node.categories::add)
            ATTR_WEIGHTS -> node.rowWeights = parseWeights(value) ?: return false
            ATTR_COLUMNS -> node.childHeader = parseWeights(value) ?: return false
            // The percentage form of the two above (the deep-copy window's "priority tables" switch, off).
            // It lands as the node's single weight, so a sub-list of shares rebuilds those very shares.
            ATTR_SHARE -> node.rowWeights = listOf(parseSharePercent(value) ?: return false)
            else -> {
                // `- resilience to <kind>: <n> %`. The kind is part of the attribute NAME, so it is matched
                // by prefix rather than by equality — the one attribute of the format whose name varies.
                if (!name.startsWith("$ATTR_RESILIENCE ")) return false
                val kind = PeriodKinds.normalize(name.removePrefix("$ATTR_RESILIENCE "))
                if (kind.isEmpty()) return false
                val parsed = parseSharePercent(value) ?: return false
                val clamped = PeriodKinds.clamp(parsed)
                if (clamped == PeriodKinds.defaultResilience(kind)) node.resilience.remove(kind)
                else node.resilience[kind] = clamped
            }
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
                    // The pre-1.6.0 no-screen flag: `1` = the task needs no screen, which under the
                    // resilience model is simply NO override against "no on-screen task" (its default is 1).
                    field.startsWith("ns=") ->
                        when (field.removePrefix("ns=")) {
                            "1" -> node.resilience.remove(PeriodKinds.NO_SCREEN)
                            "0" -> node.resilience[PeriodKinds.NO_SCREEN] = 0.0
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
        val resilience: MutableMap<String, Double> = Task.DEFAULT_RESILIENCE.toMutableMap(),
        val categories: MutableList<String> = mutableListOf(),
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
                resilience = resilience.toMap(),
                categories = categories.toList(),
                scheduleUnit = scheduleUnit.toList(),
                text = text,
            )
    }

    /** Escapes a title onto one line. A user-written title must never be mistaken for a copied-task
     * attribute, a field entry or the summary footer marker that follows the tree payload.
     */
    private fun escapeTitleField(s: String): String {
        val escaped = escapeField(s)
        return if (escaped.startsWith(ATTR_MARKER) || escaped == COPIED_TASKS_SECTION_HEADER) "\\$escaped" else escaped
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
