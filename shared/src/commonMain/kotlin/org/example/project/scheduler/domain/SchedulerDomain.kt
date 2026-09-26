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
import org.example.project.perf.Perf
import org.example.project.scheduler.model.Cell
import org.example.project.scheduler.model.AlternativeSpan
import org.example.project.scheduler.model.CellId
import org.example.project.scheduler.model.CellList
import org.example.project.scheduler.model.CellListId
import org.example.project.scheduler.model.AlertSettings
import org.example.project.scheduler.model.ChoreEntry
import org.example.project.scheduler.model.DEFAULT_MINIMUM_MINUTES
import org.example.project.scheduler.model.ForcedTaskStart
import org.example.project.scheduler.model.ForcedTaskSwitch
import org.example.project.scheduler.model.PanelPins
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
import org.example.project.scheduler.state.TreeSnapshot

/**
 * PRD §6 / `docs/scheduler_requirements.md`: what one run of the scheduler read and what it answered, kept
 * apart.
 *
 * [ruleState] is the § *Rule State Definition* — the tasks with their priority percentages, minimum
 * execution times and resilience values, which is what the **user** authored. [rules] is the set of rules
 * the scheduler **returned** for it: the instructions, parameterized by the now-line and its mode, that give
 * the future ([SchedulerDomain.describeScheduleRules]).
 *
 * They are two fields and not one list because they were one list, and the History window then showed the
 * question where it said it was showing the answer.
 */
data class SchedulerRunRules(
    val ruleState: List<String>,
    val rules: List<String>,
) {
    companion object {
        val EMPTY = SchedulerRunRules(emptyList(), emptyList())
    }
}

object SchedulerDomain {
    fun isRootTask(taskId: TaskId?): Boolean = taskId == WellKnownIds.ROOT_TASK

    /**
     * The id shape [SchedulerState.allocateTaskId] mints — the only one a clipboard payload may name
     * (ADR 0012), so a pasted id can never land on the tree's own root task or on an id the counter
     * will never walk past.
     */
    fun isUserTaskId(taskId: TaskId): Boolean = USER_TASK_ID_PATTERN.matches(taskId.value)

    private val USER_TASK_ID_PATTERN = Regex("""task/user/\d+""")

    fun isSelectableCell(state: SchedulerState, cellId: CellId): Boolean {
        val cell = state.cells[cellId] ?: return false
        return !isRootTask(cell.taskId)
    }

    // ----- The root ---------------------------------------------------------------------------------
    //
    // PRD §2: the tree is drawn under ONE inert row standing for the whole of it — [WellKnownIds.ROOT_CELL],
    // a real cell of the tree pointing at [WellKnownIds.ROOT_TASK], sitting alone in
    // [WellKnownIds.ROOT_CELL_LIST] one level above [SchedulerState.rootListId]. It is a real cell and not a
    // synthetic header so that exactly one thing draws a task row, one thing decides the visible order, and
    // the expansion set answers for the root the same way it answers for every other parent: collapsing it
    // collapses the tree.
    //
    // [SchedulerState.rootListId] deliberately did NOT move onto the root cell's list. That field names the
    // list of the tree's TOP-LEVEL tasks ([WellKnownIds.ROOT_LIST]), and it is what the priority walk, the
    // colour ring, the category scopes and the path labels all mean by "the root" — re-pointing it at a list
    // holding one inert cell would have changed every one of those answers. So the root cell is reached the
    // other way round: it is the root list's `parentCellId`, which is null on a drawing that has no root row
    // and is the one thing that tells the two shapes apart.

    /**
     * The tree's **root cell**, or `null` for a drawing that has none — the PRD §4 template, whose own root
     * list is parentless.
     */
    fun rootCellId(state: SchedulerState): CellId? =
        state.lists[state.rootListId]?.parentCellId?.takeIf { it in state.cells && !isSelectableCell(state, it) }

    /**
     * The list a **drawing** of this tree starts from: the root cell's own list where there is one, and
     * [SchedulerState.rootListId] itself where there is not. Everything that decides what the user can see —
     * [visibleOccurrences] and the tree's own [org.example.project.scheduler.ui.TaskTreeView] — starts here,
     * so a collapsed root cell hides the tree from the keyboard exactly as it hides it from the eye.
     */
    fun displayRootListId(state: SchedulerState): CellListId =
        rootCellId(state)?.let { state.cells[it]?.parentListId } ?: state.rootListId

    /**
     * Installs the whole root shape — the [WellKnownIds.ROOT_TASK] titled `root`, its [WellKnownIds.ROOT_LIST]
     * of top-level cells, and the [WellKnownIds.ROOT_CELL] the tree is drawn under — on a tree written before
     * it existed, and leaves a tree that already has it exactly as it is (the stored expansion included:
     * only a freshly minted root cell is expanded, so an account that collapsed it keeps it collapsed).
     *
     * It is the ONE definition of that shape: [SchedulerState.empty] builds through it, the codec heals a
     * decoded payload with it, the sync merge repairs with it, and [SchedulerState.applyTree] carries it
     * across an undo into pre-upgrade history. It refuses any tree not rooted at [WellKnownIds.ROOT_LIST]
     * (a Search sub-tree projection is re-rooted elsewhere), and the caller that must not grow one — the §4
     * template, whose own [WellKnownIds.ROOT_LIST] is parentless and shadows the live tree's — is kept out
     * by its own call site.
     *
     * The **title is forced** rather than defaulted: the root task is not selectable, so no user gesture can
     * name it, and a payload written when it was called `main` must not keep saying so.
     */
    fun withRoot(state: SchedulerState): SchedulerState {
        if (state.rootListId != WellKnownIds.ROOT_LIST) return state
        val rootList = state.lists[WellKnownIds.ROOT_LIST] ?: return state
        val rootTask =
            Task(
                id = WellKnownIds.ROOT_TASK,
                title = ROOT_TASK_TITLE,
                childListId = WellKnownIds.ROOT_LIST,
                // Denormalized like every other parent's: the tree's top-level tasks, in reading order.
                childTaskIds = rootList.cellIds.mapNotNull { state.cells[it]?.taskId },
            )
        val healed =
            if (state.tasks[WellKnownIds.ROOT_TASK] == rootTask) {
                state
            } else {
                val tasks = state.tasks + (WellKnownIds.ROOT_TASK to rootTask)
                state.copy(tasks = tasks, titleToTaskIds = buildTitleIndex(tasks))
            }
        if (rootList.parentCellId == WellKnownIds.ROOT_CELL &&
            WellKnownIds.ROOT_CELL in healed.cells &&
            WellKnownIds.ROOT_CELL_LIST in healed.lists
        ) {
            return healed
        }
        val rootCell =
            Cell(
                id = WellKnownIds.ROOT_CELL,
                parentListId = WellKnownIds.ROOT_CELL_LIST,
                taskId = WellKnownIds.ROOT_TASK,
            )
        return healed.copy(
            lists =
                healed.lists +
                    mapOf(
                        WellKnownIds.ROOT_CELL_LIST to
                            CellList(
                                id = WellKnownIds.ROOT_CELL_LIST,
                                parentCellId = null,
                                cellIds = listOf(WellKnownIds.ROOT_CELL),
                            ),
                        WellKnownIds.ROOT_LIST to rootList.copy(parentCellId = WellKnownIds.ROOT_CELL),
                    ),
            cells = healed.cells + (WellKnownIds.ROOT_CELL to rootCell),
            // A tree arriving without a root cell has never been able to collapse one, so the row it grows
            // here is open: anything else would hide the whole tree behind an upgrade.
            expanded = healed.expanded + WellKnownIds.ROOT_CELL,
        )
    }

    /**
     * [withRoot]'s root **task** alone, for a tree that is not the live one — the PRD §4 template's
     * [TreeSnapshot] and every stored task tree's. Those carry [WellKnownIds.ROOT_TASK] too (it is what
     * `projectDefaultSubtree` roots the template at, and what its percentages are a share of), but they are
     * plain snapshots: [withRoot] never sees them, so before this they kept whatever title the payload had.
     * On the release account the template's said `main` — the pre-1.6.0 name, which the codec's id
     * migration cannot reach because a title is not an id — and every Change Task row naming a
     * template-owned task therefore read `main / …`, a path the account's tree has nowhere (2026-09-20).
     *
     * It installs the task and nothing else: a snapshot must **not** grow a root CELL. The template's root
     * list is parentless by design (`SchedulerDomain.rootCellId`), and a stored tree gets its root cell from
     * [withRoot] at the moment it is loaded ([SchedulerState.applyTreeWithRecords]).
     */
    fun withRootTask(tree: TreeSnapshot): TreeSnapshot {
        val rootList = tree.lists[WellKnownIds.ROOT_LIST] ?: return tree
        val rootTask =
            Task(
                id = WellKnownIds.ROOT_TASK,
                title = ROOT_TASK_TITLE,
                childListId = WellKnownIds.ROOT_LIST,
                childTaskIds = rootList.cellIds.mapNotNull { tree.cells[it]?.taskId },
            )
        if (tree.tasks[WellKnownIds.ROOT_TASK] == rootTask) return tree
        val tasks = tree.tasks + (WellKnownIds.ROOT_TASK to rootTask)
        return tree.copy(tasks = tasks, titleToTaskIds = buildTitleIndex(tasks))
    }

    /** The root task's title — the one name the tree's own root row, and every "root" label, print. */
    const val ROOT_TASK_TITLE: String = "root"

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
     *
     * It starts at [displayRootListId] and not at [SchedulerState.rootListId]: the root cell is a parent
     * like any other, so a collapsed one has to omit the whole tree here exactly as it does on screen —
     * otherwise the arrows would still walk rows the user cannot see. It is NOT a render-via, though
     * ([renderViaOf]): the rows under it keep the null via that says "the root viewport".
     */
    fun visibleOccurrences(
        state: SchedulerState,
        listId: CellListId = displayRootListId(state),
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
                result += visibleOccurrences(state, childListId, renderViaOf(state, cellId))
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
        listId: CellListId = displayRootListId(state),
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
     * [tasksInTree] needs the first occurrence of every task, and asking one task at a time would re-walk the
     * tree per task — O(tasks × tree) on every
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
        return state.lists[listId]?.parentCellId?.let { renderViaOf(state, it) }
    }

    /**
     * [parentCellId] as a **render-via**, or `null` when it is the PRD §2 root cell.
     *
     * A render-via names *which occurrence of a mirrored parent* a row is drawn under, and the root cell is
     * the one parent that can never be mirrored — it is the viewport's header, not a place in the tree. So
     * the tree's top-level cells keep the `null` via they had before the root row existed, which is what
     * lets all three drawings agree: the tree draws them under the root row, PRD §4's template draws them
     * at the top with no parent at all, and one selection highlights
     * correctly in every one of them.
     */
    fun renderViaOf(state: SchedulerState, parentCellId: CellId): CellId? =
        parentCellId.takeIf { isSelectableCell(state, it) }

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

    /** Parent task owning [listId] (its `childListId`); ROOT_TASK for the viewport list. */
    fun parentTaskIdOfList(state: SchedulerState, listId: CellListId): TaskId? {
        val list = state.lists[listId] ?: return null
        val parentCellId = list.parentCellId ?: return WellKnownIds.ROOT_TASK
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
     * PRD §1 *Constraint 2* / PRD §4 *Filtering*: the tasks a candidate's own sub-tree must not contain
     * when assigned to [cellId] — **the cell's ancestor path**, and nothing more. A candidate holding one of
     * its own ancestors would be its own descendant, which is the infinite mirrored cycle Constraint 2
     * exists to forbid. Root-level cells have no ancestors, so anything may be assigned there.
     *
     * It used to be the union of the ancestors' whole **sub-trees** (the "parents set"): with a→c and b→c,
     * a cell under b was refused the candidate a, because c would then sit twice inside b's sub-tree. That
     * is a fourth constraint the PRD never states — *Filtering* is "already in the same list, or in the
     * cell's ancestor path", and Constraint 1 forbids a repeat within one **list**, which two different
     * lists under one parent are not. Recurring under many parents is mirroring, the thing the tree is for
     * (Constraint 3), and on a real account that rule hid the id menu for **a third** of the (empty cell,
     * existing title) pairs in the tree — the user types a title that exists and gets no id row at all.
     *
     * It was also the second answer to a question the tree already answered: [canMoveTaskIntoList] asks
     * exactly this one (the target's ancestors against the moving task's descendants), so the very layout
     * the menu refused could be built by dragging the cell there instead. One rule, one funnel.
     */
    private fun assignCollisionScope(state: SchedulerState, cellId: CellId): Set<TaskId> =
        ancestorTaskIds(state, cellId)

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
            if (taskId == WellKnownIds.ROOT_TASK) return 1.0
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

        // The root task is left OUT of the answer. It is what every percentage is a share *of* — [absolute]
        // returns 1.0 for it so the walk terminates — not a row that holds a share of its own, and it only
        // reaches [cellsByTask] at all because the PRD §2 root cell points at it. Returning it would put a
        // second 1.0 beside the tasks that divide that 1.0 up, so anything summing this map (the category
        // rules' solve, the tests that assert the leaves fill the tree) would count
        // the whole tree twice.
        return cellsByTask.keys.asSequence().filterNot(::isRootTask).associateWith { absolute(it) }
    }

    // ----- The tasks the tree holds ------------------------------------------------------------

    /**
     * Every task the tree actually holds: the **populated** cells' tasks, counted off `state.cells` as
     * [absoluteTaskPriorities] and [RelativePriority.occurrenceChains] count them. A task "deleted" by blanking
     * its title (PRD §4) is not held even while its records keep it alive, and a *detached parent* — titled, but
     * with no cell pointing at it — is not held either.
     *
     * **"Not in the tree" is [firstTaskOccurrences], not "has no cell",** and the two differ for a reason that is
     * easy to miss: a detached parent keeps its whole **sub-tree** alive (that is what assigning its id back
     * restores), so the tasks inside it still have cells — cells the tree cannot display anywhere, since nothing
     * reachable from the root descends into that sub-list. Membership is therefore this walk, so the answer is
     * exactly the set of tasks whose "go to task" (PRD §4) / "go to task tree" (PRD §8) has somewhere to go.
     */
    fun tasksInTree(state: SchedulerState): Set<TaskId> {
        val inTree = firstTaskOccurrences(state)
        val held = LinkedHashSet<TaskId>()
        for (cell in state.cells.values) {
            val taskId = cell.taskId ?: continue
            if (taskId in inTree && isPopulatedCell(state, cell.id)) held += taskId
        }
        return held
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
     * The rows are the **schedulable leaves** of [tasksInTree], and only those: a resilience says where a
     * task may be *placed*, and a parent task is a grouping the scheduler never places (the task edit window
     * shows it no resilience section for the same reason). Offering a parent a value would be offering to
     * write a number nothing reads.
     */
    fun periodKindTaskRows(state: SchedulerState, kind: String): List<PeriodKindTaskRow> =
        tasksInTree(state)
            .filter { isLeafTask(state, it) }
            .map { taskId ->
                PeriodKindTaskRow(
                    taskId = taskId,
                    title = state.tasks[taskId]?.title.orEmpty(),
                    resilience = state.tasks[taskId]?.resilienceFor(kind)
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
     * **The decision boundaries inside a task-tree transition** — the trigger the engine watches.
     *
     * `docs/scheduler_requirements.md` § *Rule State Evolution*: the rule state applied is the one at the
     * now-line, so a plan made at `x` holds `R(x)` for its whole continuation, and a decision the line has not
     * reached yet may be taken with another rule state by the time it is. So inside a transition the plan is
     * re-made each time the line reaches the start of a run the plan placed: every decision the frozen past
     * records is then taken with the rule state at its own instant, which is what makes two transitions with the
     * same slope the same schedule while they overlap. It is boundary-driven, not a tick: the key changes only
     * when the line crosses a run's start, and re-planning there keeps that start (the run's elapsed head is the
     * frozen past), so a re-plan never moves the key it was triggered by.
     *
     * 0 whenever no tree is dated, and constant outside a transition except when the bracketing keyframe
     * changes — the feature-off case must never dispatch anything.
     */
    fun taskTreeBlendDecisionKey(state: SchedulerState, nowMillis: Long): Long {
        val blend = taskTreeBlendAt(state, nowMillis) ?: return 0L
        val result = 31L * blend.from.id.value.hashCode() + blend.to.id.value.hashCode()
        if (blend.isSingle) return result
        val decided = state.panels.asSequence()
            .filter { it.auto && it.taskId != null && it.startEpochMillis <= nowMillis }
            .maxOfOrNull { it.startEpochMillis } ?: 0L
        return 31L * result + decided
    }

    /** The next run start the plan placed after [nowMillis] — where [taskTreeBlendDecisionKey] next moves. */
    fun nextDecisionMillis(state: SchedulerState, nowMillis: Long): Long? =
        state.panels.asSequence()
            .filter { it.auto && it.taskId != null && it.startEpochMillis > nowMillis }
            .minOfOrNull { it.startEpochMillis }

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
     * PRD §4/§8: whether the **timeline still holds** [taskId] — a banked record of a period the user spent
     * on it ([Task.record], §9's bank) or a block the user placed for it by hand ([isUserPlaced]).
     *
     * The question the Change Task menu asks before offering "New task" at all ([changeTaskMenuEntries]):
     * picking that row points the cell at a fresh id and leaves this one behind, which is worth offering
     * exactly when the id has a past to be left behind with — and it is the same history [purgeOrphanTasks]
     * keeps the abandoned id alive on, which is what brings it back as a row of that very menu.
     *
     * The scheduler's own reading of a task's past ([pastPeriodsForTask]) clips to an instant and counts
     * every panel; this one takes no `nowMillis` on purpose. It sits on the per-keystroke edit path, where a
     * `now` argument would re-walk the whole menu on every display resample (`display-hot-path.md`) — and
     * the AUTO panels it would then count are the schedule's own future, which all but every scheduled leaf
     * has, so the menu would be back to showing always. A user-placed block still ahead of the now-line
     * counts, and is the one place the two readings differ.
     */
    fun taskHasTimelineHistory(state: SchedulerState, taskId: TaskId): Boolean =
        state.tasks[taskId]?.record?.isNotEmpty() == true ||
            state.panels.any { it.taskId == taskId && isUserPlaced(it) }

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
        if (isRootTask(taskId)) return false
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
     * tasks still in the tree. Empty placeholders, the root task, tasks no longer pointed at by any
     * cell (kept only for their record, PRD §4/§8), and **blank-titled tasks** (a cell emptied to "delete"
     * the task keeps its id and lingers while panels/records still point at it) are all excluded.
     */
    fun schedulableLeaves(state: SchedulerState): List<TaskId> =
        state.tasks.keys.filter {
            !isRootTask(it) && taskHasCells(state, it) && isLeafTask(state, it) &&
                state.tasks[it]?.title?.isNotBlank() == true
        }

    // The §9 pick is the best-score continuation ([fillSchedule] / [ScheduleFill]); the §8 manual-add
    // pick is [manualAddTaskId]. The EDF-era helpers `edfPeriodMillis` / `nextTask` were deleted with that
    // fill — they scored a static period `T = m / p`, which no longer predicts anything the scheduler does.

    private const val MILLIS_PER_MINUTE: Long = 60_000L

    /**
     * How many instructions [describeScheduleRules] spells before it says how many more there are. The rule
     * list is finite by construction but its LENGTH follows the horizon, and this text is held in RAM for the
     * last several runs (see [SchedulerRunRules]).
     */
    private const val MAX_DESCRIBED_RULES: Int = 500

    // ----- PRD §8 manual calendar entries -----------------------------------------------------

    /** PRD §8: a manually dragged/resized calendar block never collapses below this length. */
    const val MIN_MANUAL_ENTRY_MILLIS: Long = 60_000L

    /**
     * PRD §7 **the switch entry**: how long the block both switch chords lay at the now-line is —
     * **epsilon**, and deliberately not a length anybody chose.
     *
     * The entry's whole job is to say *this task, from here*; **how long** is the scheduler's answer and must
     * not be pre-empted by the press. A second is the smallest span that is unambiguously a period and not a
     * rounding artefact, and far below anything the calendar can draw or the score can be distorted by (it
     * is served time like any other, so a span this size moves no lag measurably).
     *
     * What then makes the panel a *usable* length is the fill, not this: the request the same press records
     * ([org.example.project.scheduler.model.ForcedTaskStart]) makes the task the first run after the seed, and
     * the seed and that run are ONE panel on the score's clock, so criterion 2 (`docs/scheduler_score.md`)
     * charges its shortfall until it reaches the task's minimum — the soft *Minimum Execution Time* goal.
     */
    const val SWITCH_ENTRY_MILLIS: Long = 1_000L

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
     * first in alphabetic order wins). Excludes the root task, tasks no longer in the tree,
     * blank-titled (emptied) tasks, and non-leaf tasks (the calendar schedules only leaves, PRD §8).
     * Returns null when there is no real task to add.
     */
    fun manualAddTaskId(state: SchedulerState): TaskId? {
        val absolute = absoluteTaskPriorities(state)
        val candidates =
            state.tasks.keys.filter {
                !isRootTask(it) && taskHasCells(state, it) && isLeafTask(state, it) &&
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
     * `docs/scheduler_requirements.md` § *$now line$ 3 modes* + PRD §8: **the stretches the user DECLARED this
     * device away from** — the closed episodes plus the open one growing with the now-line, the same shape
     * [displayInactivityGaps] gives the live pause.
     *
     * "I'm away" is the user saying *this device's screen is not in use*, which is the sentence a LOCK says.
     * The OS cannot see it — the machine stays unlocked — so it has to reach that device's layer some other
     * way, and this is that way: it is unioned into the layer's ASSERTED regions ([layerRegions]) and into the
     * no-screen reading built out of them ([observedNoScreenRegions]), never into its evidence. Both halves of
     * that matter. It has to reach them, or the calendar contradicts the mode: mode 3 is *"at least one device
     * with the button on and every other one locked"*, so a declared-away stretch on an otherwise locked
     * account IS a stretch carrying both layers — a no-screen period — and the hatch has to show it. And it
     * has to arrive as an assertion, or the seam filter ([MIN_INACTIVITY_BAND_MILLIS]) would silently drop a
     * declaration shorter than a minute, hatching nothing over a period the mode was 3 for.
     *
     * Runtime state, like the flag itself ([org.example.project.scheduler.engine.SchedulerEngine.userAway]):
     * never persisted and never synced, so a restart forgets the episodes and the layer falls back to whatever
     * the OS history says. A PEER's declaration needs no equivalent — no channel carries a peer's lock history
     * either, so that device's layer is already hatched across the whole window ("a device that cannot be
     * asked was locked").
     */
    fun declaredAwayRegions(
        closedSpans: List<TaskTimeRange>,
        awaySinceMillis: Long?,
        nowMillis: Long,
    ): List<TaskTimeRange> {
        val open =
            awaySinceMillis?.takeIf { it < nowMillis }?.let { listOf(TaskTimeRange(it, nowMillis)) } ?: emptyList()
        val all = (closedSpans + open).filter { it.endEpochMillis > it.startEpochMillis }
        return if (all.isEmpty()) emptyList() else mergeOccupied(all)
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
                // The alternative schedule is named for every position of the line: the fused panel keeps the
                // head's answer and every later one where it changes.
                val inForce = keep.alternativeSpans.lastOrNull()?.taskId ?: keep.alternativeTaskId
                val joined =
                    if (panel.startEpochMillis >= keep.endEpochMillis && panel.auto && keep.auto) {
                        val head =
                            if (panel.alternativeTaskId == inForce) emptyList()
                            else listOf(AlternativeSpan(panel.startEpochMillis, panel.alternativeTaskId))
                        keep.alternativeSpans + head + panel.alternativeSpans
                    } else {
                        keep.alternativeSpans
                    }
                result[into] = keep.copy(
                    endEpochMillis = maxOf(keep.endEpochMillis, panel.endEpochMillis),
                    auto = keep.auto && panel.auto,
                    alternativeSpans = joined,
                )
                changed = true
            } else {
                result.add(panel)
            }
        }
        return if (changed) result else panels
    }

    /**
     * PRD §8: **two overlapping "No screen" periods are ONE period — their union.** A no-screen period is
     * not an object that owns a slice of the timeline the way a task panel does; it is the statement *no
     * screen was in use here*, and two overlapping statements of it say one thing. So they must never be
     * drawn as two blocks splitting the day column's width between them (`overlapLayout`) — that is the
     * shape for two panels genuinely competing for the same hours, which these are not: the scheduler
     * already reads them merged (`mergeOccupied`, `noScreenRangesFor`) and so does the layer assertion.
     *
     * Fuses every **strictly overlapping** run of panels **of one layer-asserting kind** into one panel
     * spanning the run; the rest of [panels] is returned untouched, in place. Two periods that merely **abut** are left
     * alone: they already draw full-width and each is still an object the user can remove on its own.
     * Within a run the survivor is the panel named by [keepId] if it is there — the one the user just laid,
     * moved or resized, which the caller goes on to apply the override rule for — else the run's earliest;
     * it keeps its id, its pins and its layout weight, and only its bounds grow.
     *
     * Identity-stable: returns [panels] itself when there is nothing to fuse, which is the common case.
     *
     * The one funnel for the rule: [org.example.project.scheduler.state.SchedulerReducer]'s
     * `resolveScreenOverrides` runs it at every point a period is laid or moved, and
     * [org.example.project.scheduler.persistence.SchedulerStateCodec] runs it on decode, so a state an
     * older build wrote with overlapping periods in it is healed rather than surfaced (CLAUDE.md).
     */
    fun unifyNoScreenPeriods(panels: List<TaskPanel>, keepId: String? = null): List<TaskPanel> {
        // Per KIND, not per flag: the rule is about a period that states a LAYER, and there are three such
        // kinds ([PeriodKinds.isLayerKind]). Two overlapping "no computer unlocked" periods say one thing
        // for exactly the reason two "No screen" ones do. Kinds are never fused ACROSS — a computer-only
        // statement and a both-screens one are different statements, the same reason a no-screen period never
        // fuses with an inactivity one. By NAME, not by what a kind carries: a `before bed` period carries a
        // no-screen period ([PeriodKindConfig.impliedKinds]) but is not one, so it keeps its own bounds.
        var result = panels
        for (kind in panels.mapNotNull { it.restrictiveKind.ifBlank { null } }.distinct()) {
            if (!PeriodKinds.isLayerKind(kind)) continue
            result = unifyPeriodsOfKind(result, kind, keepId)
        }
        return result
    }

    private fun unifyPeriodsOfKind(panels: List<TaskPanel>, kind: String, keepId: String?): List<TaskPanel> {
        val periods = panels.filter { it.restrictiveKind == kind }
        if (periods.size < 2) return panels
        val runs = mutableListOf<MutableList<TaskPanel>>()
        for (panel in periods.sortedBy { it.startEpochMillis }) {
            // Strictly `<`: sorted by start, so a run's frontier is its widest end so far and a panel that
            // only touches it (start == frontier) opens a new run.
            val run = runs.lastOrNull()
            if (run != null && panel.startEpochMillis < run.maxOf { it.endEpochMillis }) {
                run.add(panel)
            } else {
                runs.add(mutableListOf(panel))
            }
        }
        if (runs.size == periods.size) return panels
        val fused = HashMap<String, TaskPanel>()
        val absorbed = HashSet<String>()
        for (run in runs) {
            if (run.size < 2) continue
            val keeper = run.firstOrNull { it.id == keepId } ?: run.first()
            fused[keeper.id] =
                keeper.copy(
                    startEpochMillis = run.minOf { it.startEpochMillis },
                    endEpochMillis = run.maxOf { it.endEpochMillis },
                )
            run.forEach { if (it.id != keeper.id) absorbed.add(it.id) }
        }
        return panels.mapNotNull { fused[it.id] ?: it.takeIf { p -> p.id !in absorbed } }
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
     * A calendar scrolled further out than this is still computed to its goal, but asynchronously and for
     * display only (see [fillSchedule]'s `horizonMillis` and `App.kt`'s far-week `LaunchedEffect`), never
     * materialized into `state.panels`.
     */
    const val SCHEDULE_HORIZON_MILLIS: Long = 168L * 60 * 60 * 1000

    /**
     * `docs/scheduler_requirements.md` § *Progressive Calculation*: how far the first stage of a progressive fill
     * reaches; every next stage reaches twice as far, up to $t_{goal}$ (`SchedulerEngine.dispatchProgressivePlan`, and
     * the in-reducer re-plans that answer a press).
     */
    const val PROGRESSIVE_FIRST_STAGE_MILLIS: Long = 60L * 60 * 1_000

    /**
     * The search time a re-plan made inside a reducer to answer a press may spend reaching the best score. Short: the
     * press waits for it on the thread that dispatched it.
     */
    const val INLINE_REPLAN_SEARCH_MILLIS: Long = 250

    /**
     * How far back [fillSchedule] reads the already-placed past, in **windows of `Theta`** — the whole rule lives
     * in [ScheduleFill.pastLookbackMillis], which is what turns these three constants into an instant. The frozen
     * past is replayed over that span to give every task's lag at the line, and a deprivation inside it still
     * raises a target share after it.
     *
     * Four windows: what replaying from a cutoff loses is the lag standing there, damped by `e^(-d/tau_i)`, so at
     * `4*Theta` of SCHEDULABLE time under 2 % of it survives. A flat 168 h of WALL time is what shipped, and it
     * zeroed the lag of every task rare enough for `tau_i` to exceed it — a three-day pre-placed block ending nine
     * days ago simply did not count as served.
     */
    const val SCHEDULE_PAST_LOOKBACK_WINDOWS: Double = 4.0

    /**
     * The floor under the past lookback (one week — what shipped as the flat ceiling, and the same span as the
     * horizon ceiling). Every ordinary account lands here: its `Theta` is hours, so four windows of it are far
     * shorter, and a week costs nothing to replay.
     */
    const val SCHEDULE_PAST_LOOKBACK_FLOOR_MILLIS: Long = 168L * 60 * 60 * 1000

    /**
     * The ceiling over it (90 days), and the **one approximation the backward side still carries**: a leaf at a
     * near-zero share has an enormous `tau`, and without this a fill would cost O(total history) — CLAUDE.md:
     * hot-path derivations scale with the screen, not with the whole record. A task whose window reaches past it
     * is rarer than once a quarter, and its lag is clamped exactly as it was before.
     */
    const val SCHEDULE_PAST_LOOKBACK_CAP_MILLIS: Long = 90L * 24 * 60 * 60 * 1000

    /**
     * PRD §15 display only: the floor under how far the calendar PROJECTS its forward bands (screen breaks,
     * sleep) past the now-line (24 hours). It is not a scheduling horizon — the plan's goal is
     * [scheduleGoalEndMillis], whose floor is [SCHEDULE_GOAL_FLOOR_MILLIS].
     */
    const val MIN_SCHEDULE_HORIZON_MILLIS: Long = 24L * 60 * 60 * 1000

    /**
     * `docs/scheduler_requirements.md` § *Progressive Calculation*: the **floor under $t_{goal}$** (10 minutes).
     * With no calendar open — or one showing nothing later than this — the scheduler only has to know what
     * comes in the next ten minutes: that is all the headless §11/§13 task cue, the §17 wind-down cue and the
     * schedule-unit deadlines read ahead of the line. The §15 break windows the server is told are asked of the
     * recurrence bars directly, never of `state.panels`, so they do not depend on it.
     */
    const val SCHEDULE_GOAL_FLOOR_MILLIS: Long = 10L * 60 * 1000

    /**
     * The **Monday** the week holding [date] starts on. The app is Monday-first everywhere — the side menu's
     * month rail and the calendar's `weekAnchorDay` (which delegates here) — and "which day a week starts on"
     * is exactly the kind of rule that must exist once.
     */
    fun weekStartDate(date: LocalDate): LocalDate =
        date.minus(DatePeriod(days = date.dayOfWeek.isoDayNumber - 1))

    /**
     * `docs/scheduler_requirements.md` § *Progressive Calculation*: **the end of the CURRENT WEEK**, one of the
     * three instants $t_{goal}$ is the latest of ([scheduleGoalEndMillis]). Midnight opening the next Monday,
     * because the app is Monday-first everywhere ([weekStartDate]).
     *
     * It is a WALL-CLOCK week, so it needs the zone the calendar draws in — and it is why $t_{goal}$ is not a
     * pure function of `now` and the scroll: the week the user is living in is a term of the goal whether or
     * not a calendar is open to show it.
     */
    fun currentWeekEndMillis(nowMillis: Long, timeZone: TimeZone): Long =
        weekStartDate(Instant.fromEpochMilliseconds(nowMillis).toLocalDateTime(timeZone).date)
            .plus(DatePeriod(days = 7))
            .atStartOfDayIn(timeZone)
            .toEpochMilliseconds()

    /**
     * `docs/scheduler_requirements.md` § *Progressive Calculation*: **$t_{goal}$ — the instant the scheduler
     * may stop at.** *"The scheduler can have a time $t goal$ such as when definitive schedule is found for
     * any t < $t goal$ the scheduler can stop."*
     *
     * It is **the LATEST of three instants** (user rule, 2026-09-18): the **end of the current week**
     * ([currentWeekEndMillis]), the **last displayed time in the calendar** ([displayedEndMillis] — the
     * EXCLUSIVE end of the displayed day span, what `App.kt` publishes through
     * [org.example.project.scheduler.engine.SchedulerEngine.setCalendarHorizon]), and **`now + `**
     * [SCHEDULE_GOAL_FLOOR_MILLIS]. The scheduler may stop once the schedule is definitive up to all three.
     *
     * The week term holds WITH NO CALENDAR OPEN (it replaces the 2026-09-16 rule, under which a closed
     * calendar asked for ten minutes and nothing more): the week the user is living in is what the plan is
     * for, and opening the calendar on it must not be what makes it exist. A grid scrolled into the past adds
     * nothing — it shows no time the plan has to reach — and one scrolled forward adds its own end.
     *
     * The floor ROLLS with the line, so a plan that has just reached it is short again a millisecond later.
     * That is why a fill is not aimed at the goal itself but at [scheduleHorizonEndMillis], which carries
     * another floor's worth of slack, and why [horizonRefillDueMillis] answers against the goal: the two
     * together extend the plan once per [SCHEDULE_GOAL_FLOOR_MILLIS] rather than at every tick.
     *
     * The other two ways the scheduler may stop are not here, because neither is an instant: the set of rules
     * growing too heavy ([SCHEDULE_HORIZON_MILLIS], applied by [scheduleHorizonEndMillis]) and the calculation
     * time limit (`SchedulerEngine.PLAN_CALCULATION_LIMIT_MILLIS`, which stops a fill wherever it has reached).
     */
    fun scheduleGoalEndMillis(
        nowMillis: Long,
        displayedEndMillis: Long?,
        timeZone: TimeZone = TimeZone.currentSystemDefault(),
    ): Long =
        maxOf(
            displayedEndMillis ?: nowMillis,
            currentWeekEndMillis(nowMillis, timeZone),
            nowMillis + SCHEDULE_GOAL_FLOOR_MILLIS,
        )

    /**
     * PRD §9 Scheduling: the instant a fill materializes the work plan out to — [scheduleGoalEndMillis] with
     * two adjustments:
     * - a goal past `now + `[SCHEDULE_HORIZON_MILLIS] is capped there — *"the set of rules is too heavy"*, the
     *   second of the requirement's stopping conditions. Only a calendar scrolled out can reach it: the week
     *   term is at most a week away by construction. Past the cap the far week is computed for display only,
     *   off the UI thread, and never retained (`App.kt`'s far-week `LaunchedEffect`);
     * - the rolling floor is DOUBLED, so a fill made at the floor is not due again until the line has moved a
     *   whole [SCHEDULE_GOAL_FLOOR_MILLIS] ([horizonRefillDueMillis]).
     */
    fun scheduleHorizonEndMillis(
        nowMillis: Long,
        displayedEndMillis: Long?,
        timeZone: TimeZone = TimeZone.currentSystemDefault(),
    ): Long =
        maxOf(
            minOf(
                maxOf(displayedEndMillis ?: nowMillis, currentWeekEndMillis(nowMillis, timeZone)),
                nowMillis + SCHEDULE_HORIZON_MILLIS,
            ),
            nowMillis + 2 * SCHEDULE_GOAL_FLOOR_MILLIS,
        )

    /**
     * `docs/scheduler_requirements.md` § *Progressive Calculation*: **the DEFINITIVE-SCHEDULE FRONT**, the
     * instant $t_1$ the requirement's guarantee reaches — *"for all the next set of rules the scheduler will
     * return until it is done, they will all indicate the same schedule rules for any t < t_1"*.
     *
     * It is [scheduleHorizonEndMillis], and that is the whole of the rule: the front is exactly how far a fill
     * MATERIALIZES into `state.panels`. Everything below it has been published by a progressive stage, and an
     * extension keeps what a stage published (`docs/invariants/scheduler.md` § *Progressive Calculation*), so
     * the schedule there is settled until a rule change. Past it the scheduler has returned nothing: the plan
     * `App.kt` draws there is the far-week fill, computed off the UI thread for DISPLAY, never retained, and
     * recomputed from scratch the next time that week is looked at.
     *
     * The same instant answers three questions, and they are the same question: how far the derived inactivity
     * bands run (`App.kt` — past the front there is no answer yet to give), where the far-week display fill
     * takes over, and which task panels are PROVISIONAL ([isProvisionalPanel]).
     */
    fun definitiveScheduleFrontMillis(
        nowMillis: Long,
        displayedEndMillis: Long?,
        timeZone: TimeZone = TimeZone.currentSystemDefault(),
    ): Long = scheduleHorizonEndMillis(nowMillis, displayedEndMillis, timeZone)

    /**
     * `docs/scheduler_requirements.md` § *Progressive Calculation*: whether [panel] is part of a schedule the
     * scheduler has **not made definitive**, i.e. one a later set of rules may still contradict — an AUTO panel
     * reaching past [definitiveFrontMillis] ([definitiveScheduleFrontMillis]).
     *
     * Two halves, both needed. *Auto*: only the fill's own picks are the scheduler's answer — a pinned or
     * hand-drawn panel is § *Starting timeline* input, as fixed past the front as before it, and so is a
     * materialized record of what happened. *Past the front*: a panel whose END is beyond it is not covered by
     * the guarantee, including one straddling it — the guarantee is over `t < t_1`, so a run whose length the
     * front did not bound is unsettled as a whole.
     */
    fun isProvisionalPanel(panel: TaskPanel, definitiveFrontMillis: Long): Boolean =
        panel.auto && panel.endEpochMillis > definitiveFrontMillis

    /**
     * PRD §9 calculation event #1: how far a CAPPED calendar goal (one past [SCHEDULE_HORIZON_MILLIS]) may
     * fall short before it is extended (1 hour). The cap is `now + 168 h` and rolls, so without this slack the
     * plan capped there would be short again the instant its fill finished and re-fire forever — the
     * self-retriggering shape that kept the release app's window from ever presenting (2026-07-28).
     */
    const val HORIZON_REFILL_MARGIN_MILLIS: Long = 60L * 60 * 1000

    /**
     * PRD §9 calculation event #1 (rolling horizon): the instant the plan materialized in [panels] stops
     * covering what $t_{goal}$ requires — the engine extends it (to [scheduleHorizonEndMillis]) once `now`
     * reaches this instant. A value at or before [nowMillis] means "due now".
     *
     * Coverage is [firstFreeMoment] — the end of the contiguous chain of panels covering `now`. It is due:
     * - when the coverage has only [SCHEDULE_GOAL_FLOOR_MILLIS] left ahead of the line — a fill reaches twice
     *   that, so this turns true once per floor, never right after the fill;
     * - when $t_{goal}$ asks for more than the coverage reaches — the calendar showing further out, or the END
     *   OF THE CURRENT WEEK, which is a term of the goal whether or not a calendar is open ([currentWeekEndMillis])
     *   and which steps forward on its own at every rollover. Due at once while the shortfall is inside
     *   `now + `[SCHEDULE_HORIZON_MILLIS]` − `[HORIZON_REFILL_MARGIN_MILLIS], and past that cap once the line has
     *   moved one margin on.
     *
     * A goal the coverage already reaches is NOT due (the comparison is strict), so a plan that has reached the
     * instant the requirement lets the scheduler stop at does not keep re-filling it.
     */
    fun horizonRefillDueMillis(
        panels: List<TaskPanel>,
        nowMillis: Long,
        displayedEndMillis: Long?,
        timeZone: TimeZone = TimeZone.currentSystemDefault(),
    ): Long {
        val coverage = firstFreeMoment(panels, nowMillis)
        val floorDue = coverage - SCHEDULE_GOAL_FLOOR_MILLIS
        val target = maxOf(displayedEndMillis ?: nowMillis, currentWeekEndMillis(nowMillis, timeZone))
        val goalDue =
            if (target > coverage) coverage - (SCHEDULE_HORIZON_MILLIS - HORIZON_REFILL_MARGIN_MILLIS)
            else Long.MAX_VALUE
        return minOf(floorDue, goalDue)
    }

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
    /** The §17 wind-down band's title — [PeriodKinds.periodTitle]'s answer for the kind, never a second one. */
    val BEFORE_BED_PANEL_TITLE: String = PeriodKinds.periodTitle(PeriodKinds.BEFORE_BED)

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
     * no legacy flag — it is not an `inactivity` period wearing a different name — and the calendar draws it
     * as the empty orange-outlined box every period a repeating rule lays is drawn as.
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
     * period of [PeriodKinds.INACTIVITY] behind (and up to) the now-line — and the bars then do the rest by
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
            kind = PeriodKinds.INACTIVITY,
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
     * The kind is [PeriodKinds.NO_SCREEN] and not [PeriodKinds.INACTIVITY], because that is exactly what the
     * evidence says: nobody was at a SCREEN. An off-screen task may legitimately have run there (§9 exempts
     * one from the record ban for that very reason), and the README's clause is *"covered by the period 'no
     * on-screen task' **without any task**"* — so the stretch is a rest on an account whose tasks are all
     * on-screen, and correctly is not one where somebody could have been working through it.
     *
     * Nothing here is stored, drawn or synced: it is derived evidence handed to a placement, like the live
     * pause beside it.
     */
    /**
     * `side-dev/README.md` § *Restrictive Period* + the period edit window: **the COMPANION periods [periods]
     * carry** — for every period, one period of each kind the account says is *"always present when this period
     * is present"* ([PeriodKindConfig.impliedKinds], transitive), over the same span. By default that is the
     * no-screen period every `sleep` window and every `before bed` hour carries (PRD §17,
     * [PeriodKinds.defaultStyle]).
     *
     * Plus one the account cannot switch off, because it is the layers' own definition rather than a
     * companion: **where a period asserting the computer's layer overlaps one asserting the phone's, the
     * stretch is a [PeriodKinds.NO_SCREEN] period** ([assertedNoScreenRanges] reads the same intersection).
     *
     * Per kind, the spans a period of that very kind already covers are SUBTRACTED, and overlapping companions
     * are merged, because the plan multiplies the resiliences of every covering period
     * ([PeriodKinds.multiplier]): handing it the same stretch twice would square a task's resilience to the kind
     * and silently halve the share of anybody sitting between 0 and 1. A `0` and a `1` would not have noticed,
     * which is exactly why this is written down.
     *
     * An implication, never a panel: nothing is laid, so there is nothing to drift, edit or sync. **Whoever
     * builds periods outside `state.panels` must hand them in here** (the fill's `dynamicBase`, the calendar's
     * projected wind-down hours) — mapping a period by hand drops its companions.
     */
    fun companionPeriods(periods: List<RestrictivePeriod>, config: PeriodKindConfig): List<RestrictivePeriod> {
        val implied = LinkedHashMap<String, MutableList<TaskTimeRange>>()
        val computer = ArrayList<TaskTimeRange>()
        val phone = ArrayList<TaskTimeRange>()
        for (period in periods) {
            if (period.kind.isEmpty() || period.endMillis <= period.startMillis) continue
            val span = TaskTimeRange(period.startMillis, period.endMillis)
            val kinds = config.kindsOf(period.kind)
            for (kind in kinds) if (kind != period.kind) implied.getOrPut(kind) { ArrayList() } += span
            if (PeriodKinds.NO_COMPUTER_UNLOCKED in kinds) computer += span
            if (PeriodKinds.NO_PHONE_UNLOCKED in kinds) phone += span
        }
        if (computer.isNotEmpty() && phone.isNotEmpty()) {
            val both = intersectRegions(mergeOccupied(computer), mergeOccupied(phone))
            if (both.isNotEmpty()) implied.getOrPut(PeriodKinds.NO_SCREEN) { ArrayList() } += both
        }
        if (implied.isEmpty()) return emptyList()
        return implied.flatMap { (kind, spans) ->
            val explicit =
                periods.filter { it.kind == kind }.map { TaskTimeRange(it.startMillis, it.endMillis) }
            subtractRegions(mergeOccupied(spans), explicit).map {
                RestrictivePeriod(
                    startMillis = it.startEpochMillis,
                    endMillis = it.endEpochMillis,
                    kind = kind,
                    label = PeriodKinds.periodTitle(kind),
                )
            }
        }
    }

    /**
     * `docs/scheduler_requirements.md` § *$now line$ 3 modes*, **mode 1**: *"$now line$ must not be covered by
     * the period 'no on-screen task'. This means that if it reaches one of those periods, the passing of the
     * $now line$ line creates task panels not covered by the period."* — **the spans those periods give up at
     * the line**, and the ONE place the clause is read for the fill.
     *
     * Mode 1 is *a device of the account is unlocked*, so the user is demonstrably at a screen; a period that
     * says nobody is ([PeriodKindConfig.isOrImpliesNoScreen]) cannot be covering the line, and what it gives up is
     * everything from the line to its own end. Two halves, and both are the requirements':
     * - the span reaches **forward to the period's end**, not one millisecond, because the plan has to be
     *   computed over the retracted timeline — the rules it returns are *"task A from 00:40 to $now line$,
     *   until 01:25"*, and naming 01:25 means searching past the line. Only the stretch the line has actually
     *   SWEPT is materialized (`fillScheduleUninstrumented` clips the placements back to `now + 1`), so the
     *   band ahead of the line is untouched and a lock at 01:00 flips the mode with nothing to undo;
     * - it is empty in **modes 2 and 3**, whose clause is the opposite one — the line must BE covered — which
     *   is why `DynamicPeriods.awayCover` exists and why nothing here may fire in them.
     *
     * This is PRD §17's *"carved by activity"* rule, **for the scheduler and not only for the band**. That
     * carve ([carveSleepPanels]) shipped display-only, in as many words, so a night worked through showed the
     * Sleep band retracting to the now-line while the fill went on treating the whole window as an obstacle:
     * the line sat in a stretch with no band and no task at all, which is § *No idling* and mode 1 broken
     * together (account 3, 00:43 on 2026-09-18, awake since 00:40 inside a 23:15→07:45 window). It is the same
     * shape as the dragged pose fixed 2026-09-05 ([isDraggedScreenBreak]) — a period that in mode 1 cannot be
     * happening still obstructing the fill — and it is answered the same way.
     */
    fun retractedAtLineSpans(
        periods: List<RestrictivePeriod>,
        nowMillis: Long,
        tpMode: Int,
        config: PeriodKindConfig,
    ): List<TaskTimeRange> {
        if (tpMode != DynamicPeriods.MODE_AT_SCREEN) return emptyList()
        return mergeOccupied(
            periods.filter { retractsAtLine(it.kind, config) && it.covers(nowMillis) && it.endMillis > nowMillis }
                .map { TaskTimeRange(nowMillis, it.endMillis) },
        )
    }

    /**
     * Whether a period of [kind] **gives its remainder up at a mode-1 line** — the one predicate
     * [retractedAtLineSpans] and [retractAtLine] are both written against, so the span a period contributes
     * and the span it loses can never be two different sets.
     *
     * Two kinds do, and for the two halves of the same requirement:
     * - a **[PeriodKinds.NO_SCREEN]** period is the one the clause names in as many words (*"$now line$ must
     *   not be covered by the period 'no on-screen task'"*), the companion one a §17 window or a wind-down hour
     *   carries included ([companionPeriods]) — which is how an ON-SCREEN task becomes placeable at a
     *   line the user is demonstrably sitting at;
     * - a period **nobody may ever be let through** (`!`[PeriodKinds.isResilienceEditable]) and that carries a
     *   no-screen period ([PeriodKindConfig.isOrImpliesNoScreen]): `sleep` by default, and `inactivity` too if
     *   the account makes "no screen" its companion. There is no other way for the clause to hold inside one — no
     *   resilience can be written against it, so if the window itself stayed the line would go on being
     *   covered by a period admitting nobody and no task could appear however awake the user is.
     *
     * **`before bed` keeps its own hour**, and that is the same test answering the other way rather than an
     * exception: its resilience IS editable, so PRD §17's *"a task the user gives a value above 0 works
     * through the wind-down"* is the sanctioned way anything runs there, and § *No idling*'s own clause
     * (*"not covered by restrictive periods which would PREVENT ANY TASK from being scheduled"*) is satisfied
     * while nobody has been given one. Retracting it too would quietly delete the wind-down, the hour the user
     * is meant to stop working in being exactly an hour they are at a screen for. Only the no-screen period it
     * implies lifts, which is what lets a task the user DID let through be an on-screen one.
     *
     * By default `inactivity` and every kind the ACCOUNT defined are outside both clauses — they say the
     * timeline is empty, not that nobody is at a screen ([PeriodKindConfig.isOrImpliesNoScreen]) — so a
     * restriction the user drew is never retracted out from under them. An editable kind the account gives a
     * "no screen" companion keeps its own span for the same reason `before bed` does; only the companion lifts.
     */
    private fun retractsAtLine(kind: String, config: PeriodKindConfig): Boolean =
        kind == PeriodKinds.NO_SCREEN ||
            (config.isOrImpliesNoScreen(kind) && !PeriodKinds.isResilienceEditable(kind))

    /**
     * [retractedAtLineSpans] applied: the periods that **give their remainder up** ([retractsAtLine], where
     * the whole rule and its reasons live) with those spans taken out of them. Every other period is returned
     * untouched — including the `before bed` hour whose own implied no-screen period contributed a span.
     */
    fun retractAtLine(
        periods: List<RestrictivePeriod>,
        retracted: List<TaskTimeRange>,
        config: PeriodKindConfig,
    ): List<RestrictivePeriod> {
        if (retracted.isEmpty()) return periods
        return periods.flatMap { period ->
            if (!retractsAtLine(period.kind, config)) {
                listOf(period)
            } else {
                subtractRegions(listOf(TaskTimeRange(period.startMillis, period.endMillis)), retracted)
                    .map { period.copy(startMillis = it.startEpochMillis, endMillis = it.endEpochMillis) }
            }
        }
    }

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
        config: PeriodKindConfig,
        liveRest: LiveRest? = null,
        noScreenEvidence: List<TaskTimeRange> = emptyList(),
    ): List<RestrictivePeriod> =
        restrictivePeriodsOf(panels, config) +
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
     * break is being taken*. It no longer changes where the three dynamic periods GO — the requirements state
     * modes 2 and 3 in one clause, so both cover the line and neither drags
     * ([DynamicPeriods.lineIsCoveredAt]). What it decides is whether a break is ANNOUNCED
     * ([DynamicPeriods.breaksAreNotifiedAt]): a locked machine with no declaration behind it has nobody to
     * tell, while mode 3 is the account saying a break is being taken and is the mode the server cues the end
     * of one from.
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
     * How long the DISPLAY may wait before it is re-derived — **the next instant the set of rules says the
     * picture changes SHAPE.**
     *
     * `docs/scheduler_requirements.md` says the scheduler returns *a set of rules*, and everything the
     * calendar draws is read out of that set. So the display is a **piecewise affine** function of the
     * now-line, and nothing about it is a reason to recompute on a timer:
     *
     *  • **Nothing in the past is a function of the line.** The past is frozen; a break the app conducted,
     *    a period the user drew and a banked record are facts, and only an EVENT (a "look away now", an edit)
     *    changes one.
     *  • **Nothing in the future is either, between two boundaries.** A panel says what happens from t1 to
     *    t2; the line crossing t1 is what changes the picture, and t1 is a number the rules already gave us.
     *  • **What does follow the line follows it AFFINELY** — a pose the line drags in mode 1 sits at
     *    `(t_p, t_p + d]`, the panel behind it grows at the line, a live band ends at it — so its motion is
     *    known from one reading, and the calendar draws it continuously without asking again (ADR 0009).
     *
     * Hence the terms. [bounds] is every FIXED instant the derived model is built out of, and the smallest one
     * still ahead of the line is a boundary: the model cannot change shape before it (plus the next local
     * midnight, which is the one boundary no panel carries — the day rollover). [lineOffsets] is every bound
     * that FOLLOWS the line, as its offset from it: such a bound changes the model's shape where it MEETS a
     * fixed one (`bound − offset`, e.g. a dragged pose reaching the next period) and where it crosses a
     * midnight into another day's column — both instants known now, so both are boundaries too.
     *
     * [millisPerPixel] survives for one case only: a FIXED bound sitting on the line (within
     * [NOW_LINE_ANCHOR_SLACK]). That is a pin whose motion was not read — the two readings of the derivation
     * straddled a boundary — so it is re-derived at the display's own resolution until the next reading
     * recovers its motion, exactly as every pin was before the motion was read at all.
     *
     * The answer is in the caller's own time base (sim millis under acceleration) and is at least 1; the
     * caller clamps it to a real-time floor and to a ceiling that bounds any mistake this makes.
     */
    fun displayResampleDelayMillis(
        bounds: List<Long>,
        nowMillis: Long,
        tz: TimeZone,
        millisPerPixel: Long,
        lineOffsets: List<Long> = emptyList(),
    ): Long {
        val nextMidnight = nextLocalMidnightAfter(nowMillis, tz)
        var next = nextMidnight
        var pinned = false
        for (bound in bounds) {
            // A bound sitting ON the line is a PIN, never a boundary ahead of it — and the distinction is
            // load-bearing, not bookkeeping: a dragged pose's own start is `t_p + 1`, so counting it as a
            // boundary would answer "one millisecond" and turn the sleep into a busy loop measuring a band
            // that has not moved.
            if (bound >= nowMillis - NOW_LINE_ANCHOR_SLACK && bound <= nowMillis + NOW_LINE_ANCHOR_SLACK) {
                pinned = true
            } else if (bound > nowMillis && bound < next) {
                next = bound
            }
        }
        for (offset in lineOffsets.distinct()) {
            // Where this moving bound meets each fixed one. Two moving bounds never meet: they share a slope.
            for (bound in bounds) {
                val meet = bound - offset
                if (meet > nowMillis && meet < next) next = meet
            }
            // ...and where it crosses into the next day's column, which draws it on another row.
            val position = nowMillis + offset
            val crossing = nowMillis + (nextLocalMidnightAfter(position, tz) - position)
            if (crossing > nowMillis && crossing < next) next = crossing
        }
        val toBoundary = next - nowMillis
        val delay = if (pinned) minOf(toBoundary, millisPerPixel) else toBoundary
        return maxOf(1L, delay)
    }

    private fun nextLocalMidnightAfter(millis: Long, tz: TimeZone): Long =
        Instant.fromEpochMilliseconds(millis)
            .toLocalDateTime(tz)
            .date
            .plus(DatePeriod(days = 1))
            .atStartOfDayIn(tz)
            .toEpochMilliseconds()

    /**
     * How far either side of the now-line a derived bound still counts as sitting ON it — see
     * [displayResampleDelayMillis].
     *
     * It is not slop: a period the line drags is the half-open `(t_p, t_p + d]`, so its start is literally
     * `t_p + 1` ([DynamicPeriods.Instance.coveredFromMillis]), and a "taken" break is drawn to `t_p - 1`. Two
     * milliseconds is those two cases and nothing else — a bound a whole frame away from the line is a
     * boundary ahead of it, not a pin on it.
     */
    const val NOW_LINE_ANCHOR_SLACK: Long = 2

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
        return mergeOccupied(layerEvidence(lockedIntervals, sinceMillis, untilMillis) + assertedRegions)
    }

    /**
     * The EVIDENCE half of one layer: that device kind'''s lock history clipped to the asked window, with the
     * sub-minute slivers dropped (the seam rule, [MIN_INACTIVITY_BAND_MILLIS]). Its own function because two
     * readings must agree on it — the hatch [layerRegions] draws, the seam rule the bank applies, and which
     * part of that hatch [declaredLayerRegions] dots.
     */
    private fun layerEvidence(
        lockedIntervals: List<TaskTimeRange>,
        sinceMillis: Long,
        untilMillis: Long,
    ): List<TaskTimeRange> =
        lockedIntervals
            .map {
                TaskTimeRange(
                    maxOf(it.startEpochMillis, sinceMillis),
                    minOf(it.endEpochMillis, untilMillis),
                )
            }
            .filter { it.endEpochMillis - it.startEpochMillis >= MIN_INACTIVITY_BAND_MILLIS }

    /**
     * PRD §8 + `docs/scheduler_requirements.md` § *$now line$ 3 modes*: which sub-stretches of one layer's
     * [regions] the calendar draws **DOTTED** rather than solid — *"the oblique lines must be dotted if at
     * least one of the corresponding devices was unlocked but the I'm away button was clicked"*, and
     * *"when the user adds a no-screen period on a past time period where some computers were unlocked, the
     * oblique lines for the no computer unlocked restrictive period must be dotted there"* — for the
     * computer's slope and the phone's alike.
     *
     * **A hatch says *no device of this kind was unlocked*, and the dots say that sentence is the USER'S
     * WORD against the machine's.** Two things put a hatch somewhere the OS log contradicts, and they are
     * one rule and not two:
     *
     * - the **"I'm away" button** ([declaredAwayRegions]): the machine **stays unlocked** while it is on —
     *   that is the whole reason the button exists — so a device of this layer's kind really was sitting
     *   there unlocked;
     * - a **period the user DREW** asserting this layer ([assertedLayerRanges]) over a stretch already
     *   elapsed: the user is stating what the past was, and where the lock history disagrees, the hatch over
     *   it is that statement rather than a reading.
     *
     * Solid = nothing of the kind was unlocked. Dotted = one was, and the user said otherwise. The dots
     * change nothing else: same slope, same span, same bubble section.
     *
     * **The blue outline is not this answer in another guise**, which is why both exist. An outline says WHO
     * PUT THIS HERE and belongs to a PERIOD — the away button lays none at all, and a drawn no-screen period
     * wears one whether or not a device was unlocked inside it. The dots say the statement is CONTRADICTED.
     * So a declared-away stretch has dots and no outline; a no-screen period over a locked night has an
     * outline and no dots; one over an evening at the keyboard has both, and each mark answers its own
     * question. (The dots were deleted on 2026-09-12 for being the outline twice and restored the same day.)
     *
     * So the answer is [declaredRegions] MINUS this kind's lock evidence, intersected with the band actually
     * drawn:
     *
     * - the lock evidence wins wherever it overlaps — the button was pressed and the machine then locked or
     *   went to standby, or the drawn period covers hours the machine really was asleep for, and over that
     *   slice nothing of the kind was unlocked, so the hatch is a reading again. It is the SAME evidence
     *   [layerRegions] draws (same clipping, same sub-minute seam filter), or a standby flicker too short to
     *   hatch would still break a dotted band into pieces;
     * - **only the OBSERVED window can contradict anything**, so a declaration is clipped to
     *   `[sinceMillis, untilMillis]` (the caller's now-line) before the evidence is taken out of it. Nothing
     *   ahead of that line has been watched, so a period drawn over the future is not yet a claim about
     *   anything and draws solid — one straddling the line dots only its elapsed half;
     * - an ASSERTED region does NOT win. A sleep window or a screen break is a promise about every screen,
     *   and a promise cannot un-unlock the machine. Only lock EVIDENCE takes a slice back;
     * - and the app's own promises are not in [declaredRegions] either: a projected sleep window or a screen
     *   break is nobody's statement about what happened, and it wears the orange or grey outline that says
     *   so. The hand's statements are the away spells and the drawn periods;
     * - [lockedIntervals] `null` — "no device of this kind could be asked", hence assumed locked throughout
     *   ([layerRegions]) — dots nothing, for the same reason: nothing was read, so nothing is contradicted.
     *   That is every PEER layer's case.
     */
    fun declaredLayerRegions(
        regions: List<TaskTimeRange>,
        declaredRegions: List<TaskTimeRange>,
        lockedIntervals: List<TaskTimeRange>?,
        sinceMillis: Long,
        untilMillis: Long,
    ): List<TaskTimeRange> {
        if (regions.isEmpty() || declaredRegions.isEmpty() || untilMillis <= sinceMillis) return emptyList()
        // Assumed-locked leaves nothing unlocked to dot (see the docstring's last bullet).
        val locked = lockedIntervals ?: return emptyList()
        // Only what has been WATCHED can be contradicted: a period drawn ahead of the now-line makes no
        // claim about an observation yet, so it is no more dotted than an ordinary locked stretch is.
        val declaredAndObserved =
            intersectRegions(
                mergeOccupied(declaredRegions),
                listOf(TaskTimeRange(sinceMillis, untilMillis)),
            )
        if (declaredAndObserved.isEmpty()) return emptyList()
        val unlockedAndDeclared =
            subtractRegions(declaredAndObserved, layerEvidence(locked, sinceMillis, untilMillis))
        return intersectRegions(regions, unlockedAndDeclared)
    }

    /**
     * PRD §8: **the stretches a PERIOD asserts [layer] over** — the hand-drawn half of a layer, as opposed to
     * the OS lock history [layerEvidence] reads.
     *
     * One reading, off the panel's KIND and its companions ([PeriodKindConfig.assertedLayers]), so the places
     * that care cannot disagree: the hatch the calendar paints, the no-screen stretch [assertedNoScreenRanges]
     * takes out of two of these, and the record bank. Each one-sided kind appears in its own answer; a "No
     * screen" period appears in neither unless the account made the layers its companions.
     */
    fun assertedLayerRanges(
        panels: List<TaskPanel>,
        layer: ActivityLayer,
        config: PeriodKindConfig,
    ): List<TaskTimeRange> =
        mergeOccupied(
            panels.filter { it.restrictiveKind.isNotEmpty() && layer in config.assertedLayers(it.restrictiveKind) }
                .map { TaskTimeRange(it.startEpochMillis, it.endEpochMillis) },
        )

    /**
     * PRD §8: **the no-screen stretches the user has DRAWN** — every period that is or carries a "no screen"
     * period ([PeriodKindConfig.isOrImpliesNoScreen]: a "No screen" period, a `sleep` window, a `before bed`
     * hour, or any kind the account gave that companion), plus where a period asserting the computer's layer
     * and one asserting the phone's overlap.
     *
     * The second half is the definition the whole app reads no-screen time by — "a no-screen period is where
     * BOTH layers fall" ([observedNoScreenRegions] takes the same intersection over the two layers' *evidence*)
     * — so the two one-sided kinds reach the record bank through the rule "no screen" already has instead of
     * growing one of their own. It is the same set [companionPeriods] hands the scheduler, read as spans.
     */
    fun assertedNoScreenRanges(panels: List<TaskPanel>, config: PeriodKindConfig): List<TaskTimeRange> {
        val stated =
            panels.filter { it.restrictiveKind.isNotEmpty() && config.isOrImpliesNoScreen(it.restrictiveKind) }
                .map { TaskTimeRange(it.startEpochMillis, it.endEpochMillis) }
        val computer = assertedLayerRanges(panels, ActivityLayer.NoComputerUnlocked, config)
        val phone =
            if (computer.isEmpty()) emptyList()
            else assertedLayerRanges(panels, ActivityLayer.NoPhoneUnlocked, config)
        val both = if (phone.isEmpty()) emptyList() else intersectRegions(computer, phone)
        if (stated.isEmpty() && both.isEmpty()) return emptyList()
        return mergeOccupied(stated + both)
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
     * [computerAway] / [phoneAway] are the ONE exception, and they are not an assertion in that sense: they
     * are the stretches the USER said they were away from a device of that kind for ([declaredAwayRegions]),
     * which is a statement that nobody was at that screen — exactly what a lock reports, and exactly what this
     * function is asking. The rules' promises are left out because a break is not time the user was absent
     * for; a declaration IS. They ride the asserted slot so the seam filter cannot drop a short one, and each
     * belongs to its own layer: an away press on the computer says nothing about the phone.
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
        computerAway: List<TaskTimeRange> = emptyList(),
        phoneAway: List<TaskTimeRange> = emptyList(),
    ): List<TaskTimeRange> {
        if (untilMillis <= sinceMillis) return emptyList()
        val computer = layerRegions(computerLocked, computerAway, sinceMillis, untilMillis)
        val phone = layerRegions(phoneLocked, phoneAway, sinceMillis, untilMillis)
        return intersectRegions(computer, phone)
    }

    /**
     * PRD §8: the stretches the calendar draws as an **inactivity period it derived** — the timeline minus
     * everything already drawn over it ([coveredRegions]: the task panels, the §17 sleep bands, and the
     * user's own hand-added periods).
     *
     * The rule it implements is the user's: **the timeline is fully accounted for** — every stretch is either
     * a task panel or a restrictive period — and the ONE place that can fail to be true is past the instant
     * the scheduler has a definitive schedule for, where there is no answer yet to give. So the window is
     * `[sinceMillis, untilMillis]` with the far end at the **definitive-schedule front**, not at the now-line:
     * the future's empty stretches are as much "nothing is placed here" as the past's, they are simply
     * derived from the plan instead of from what happened.
     *
     * It stays DERIVED on both sides, and that is deliberate (ADR 0002): a band is recomputed every pass and
     * nothing about it is persisted or synced. `materializePastInactivity` wrote exactly this into
     * `state.panels` and was deleted for it — it grew without bound (218 panels on the release account) and
     * it turned an observation into a statement refusing the off-screen tasks too. **Editing one is what
     * materializes it** instead, in the period editor's Save, which is the only moment the user has actually
     * said something about the stretch.
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
     * [mode] is the requirements' `t_p` mode. At the screen ([DynamicPeriods.MODE_AT_SCREEN]) the now-line may
     * not be covered by a POSE, so a pose it has reached is pushed ahead of it and goes on being pushed; in
     * either AWAY mode it must be covered ([DynamicPeriods.lineIsCoveredAt]), so nothing is dragged and the
     * gap back to the last period's end is covered by a "no on-screen task" period the resilient tasks may
     * still fill.
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
    fun restrictivePeriodsOf(panels: List<TaskPanel>, config: PeriodKindConfig): List<RestrictivePeriod> {
        val own = panels.mapNotNull { panel ->
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
        // Each arrives WITH the companion periods its kind carries ([companionPeriods]).
        return own + companionPeriods(own, config)
    }

    /**
     * PRD §17: **the sleep windows and the wind-down hours over `[fromMillis, toMillis)`, as periods** — each
     * WITH the companion periods its kind carries ([companionPeriods]; by default a no-screen period over both).
     * For a caller projecting the §17 schedule past what `state.panels` holds (the calendar's visible span may
     * run past the fill's horizon): the grid the calendar draws and the one the fill places must see the same
     * rest, and a window mapped to a period by hand drops its companions.
     */
    fun projectedSleepPeriods(
        state: SchedulerState,
        fromMillis: Long,
        toMillis: Long,
        timeZone: TimeZone,
    ): List<RestrictivePeriod> {
        val own =
            sleepRegions(state.sleep, fromMillis, toMillis, timeZone).map {
                RestrictivePeriod(it.startEpochMillis, it.endEpochMillis, PeriodKinds.SLEEP, SLEEP_PANEL_TITLE)
            } +
                beforeBedPanels(state.sleep, fromMillis, toMillis, timeZone).map {
                    RestrictivePeriod(it.startEpochMillis, it.endEpochMillis, it.restrictiveKind, it.title)
                }
        return own + companionPeriods(own, state.periodKindConfig)
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
     * The suffix a dynamic period's panel id carries when the now-line is **DRAGGING** it — the half-open
     * `(t_p, t_p + d]` that mode 1 pushes ahead of the line ([DynamicPeriods.Instance.openStart]).
     *
     * It is in the id and not in a field of its own because a dynamic period's panel is DERIVED: every fill
     * cuts the three and regenerates them ([fillSchedule]'s `kept` filter), so nothing persisted ever has to
     * carry it, and every reader already has the id in hand. Read it through [isDraggedScreenBreak].
     */
    const val DRAGGED_BREAK_ID_SUFFIX: String = "/dragged"

    /**
     * Whether [panel] is one of the three dynamic periods that the now-line is **dragging**, rather than one
     * simply placed where the recurrence bars put it.
     *
     * The distinction matters for exactly one reason, and it is a rule of the plan rather than of the
     * drawing: **a dragged period never happens.** Mode 1 says `t_p` may not be covered by one of the three,
     * so a pose the line reached is pushed to `(t_p, t_p + d]` and goes on being pushed at every position of
     * the line — no instant of the timeline is ever inside it, and `docs/scheduler_requirements.md` says what
     * the line does instead in as many words: it *"would continuously delay that period (while creating task
     * panels in its passing)"*. So [fillSchedule] draws it and does not plan around it. Everything else — the
     * calendar, the cue sweep, the recurrence bars themselves — treats it exactly like any other placement.
     */
    fun isDraggedScreenBreak(panel: TaskPanel): Boolean =
        panel.screenBreak && panel.id.endsWith(DRAGGED_BREAK_ID_SUFFIX)

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
                        // `openStart` IS the drag: the line pushed this period ahead of itself, so it is the
                        // one period in the window no instant of the timeline will ever be inside. The chain
                        // merge keeps the flag of the chain it collapses, so a look-away absorbed into a
                        // dragged pose comes back as the dragged pose and not as two answers.
                        dragged = inst.openStart,
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
     * three are [PeriodKinds.INACTIVITY], as the README says in as many words.
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
     * really was one — the stretch was crossed in mode 2 or mode 3 (no device unlocked), where the line is
     * covered by the break instead of dragging it, so the break elapses and stays drawn where it happened.
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
     * The break's start is fixed, but the plan under it does not follow it: the plan is materialized by
     * [fillSchedule], which by CLAUDE.md's trigger rule runs on a **rule change**, not on time passing — and
     * every tick after that the marker slides forward over auto panels the fill placed past it. That is the
     * sliding-period case the reference answers with its dynamic rule list (`MovingWindow`): between
     * breakpoints the plan is *affine* in the period's position, so a display can follow the period without
     * re-scheduling. Here the period is pinned to the plan's own origin (the now-line), which is that rule's
     * simplest regime — the disturbed slot is the one the cursor is in, and nothing else changes shape.
     *
     * For a period the line is **DRAGGING** the panels underneath are there on purpose
     * ([isDraggedScreenBreak]): a dragged pose never happens, so the fill plans straight through it and this
     * clip is the whole of what makes it read as a period on screen. That is also why the clip may only ever
     * reach FORWARD. Every refusing region begins at or after the now-line, so a panel straddling it keeps its
     * **elapsed** head — and for a dragged pose that head is the requirements' *"creating task panels in its
     * passing"*: the drag recedes as the line advances and reveals the plan it was drawn over. A clip that
     * reached behind the line would put the empty stretch straight back (§ *No idling*, 2026-09-05).
     *
     * Only [isRegeneratedPanel] panels are cut: a pinned/manual block and a chore are pre-placed blocks in the
     * reference's sense and cannot be moved by a period. The cut is a hole, never a rewrite of the past. Each
     * resumed piece takes a distinct id so two display blocks never share one.
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
     * `docs/scheduler_requirements.md` § *$now line$ 3 modes* (**mode 1**): the work plan as it must be
     * **displayed** while the line sits inside a period that has given its remainder up
     * ([retractedAtLineSpans]) — a §17 sleep window the user is still awake in, or the no-screen period a
     * wind-down hour implies.
     *
     * The requirement is *"the PASSING of the $now line$ line creates task panels not covered by the period"*,
     * and a passing reaches exactly as far as the line has gone. So the plan is searched AND materialized
     * across the whole retracted span — it has to name which task holds and until when (*"task A from 00:40 to
     * $now line$, until 01:25"*), and the fill runs at a rule change rather than on time passing (CLAUDE.md),
     * so a plan stopping at the line would leave the stretch between two fills with no panel to be swept into
     * — and what is still AHEAD of the line is cut here instead. The user will still go to bed: the Sleep band
     * ahead stays whole, and a lock at 01:00 flips the mode with nothing to undo.
     *
     * **Forward only**, for the same reason [clipPlanForPinnedScreenBreak] is: the elapsed head of a
     * straddling panel is the requirements' own *"task panels in its passing"*, and a cut reaching behind the
     * line would put the empty stretch straight back (§ *No idling*). Behind the line the band itself is
     * already gone — PRD §17's *"carved by activity"* rule ([carveSleepPanels]) opens exactly the hole this
     * work fills, off the same account activity that makes the mode 1.
     *
     * Only [isRegeneratedPanel] panels are cut, and never a restrictive period: a pinned or manual block is a
     * pre-placed block no period may move, and a period is what the cut is made OF.
     *
     * [periodPanels] is what is still DRAWN AS A BAND ahead of the line — the §17 sleep windows — and that is
     * the whole of what may be hidden behind: a wind-down hour's implied no-screen period has no band of its
     * own, so a task the user let through §17's *"a value above 0"* goes on being drawn through the hour. The
     * filter here ([retractsAtLine], inside [retractedAtLineSpans]) says the same thing from the other end.
     */
    fun clipPlanForRetractedPeriod(
        panels: List<TaskPanel>,
        periodPanels: List<TaskPanel>,
        nowMillis: Long,
        tpMode: Int,
        config: PeriodKindConfig,
    ): List<TaskPanel> {
        val retracted =
            retractedAtLineSpans(
                periodPanels.mapNotNull { panel ->
                    val kind = panel.restrictiveKind
                    if (kind.isEmpty()) null
                    else RestrictivePeriod(panel.startEpochMillis, panel.endEpochMillis, kind, panel.title)
                },
                nowMillis,
                tpMode,
                config,
            )
        val cuts =
            retracted.mapNotNull { span ->
                val from = maxOf(span.startEpochMillis, nowMillis + 1L)
                if (span.endEpochMillis > from) TaskTimeRange(from, span.endEpochMillis) else null
            }
        if (cuts.isEmpty()) return panels
        return panels.flatMap { panel ->
            when {
                !isRegeneratedPanel(panel) || panel.isRestrictivePeriod -> listOf(panel)
                panel.endEpochMillis <= nowMillis -> listOf(panel)
                else -> panel.minus(cuts)
            }
        }
    }

    /**
     * Whether the restrictive period [band] admits [task] at all — the README's resilience, read at a panel.
     *
     * There is nothing special about a screen break here any more. `side-dev/README.md` gives all three
     * dynamic periods the kind [PeriodKinds.INACTIVITY], whose default resilience is `0`, so a break admits
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

    /**
     * PRD §14/§15: a cue boundary the now-line crossed — the atom of the engine's single ordered cue sweep.
     *
     * [ReminderDue] is here rather than on the alarms' sweep on purpose: a reminder is **not** armed with the
     * OS (the one alarm slot a device has belongs to the alarms and the timers, `alarms-and-timers.md`), so it
     * is announced by the running app like the break cues are, and in the same boundary order as them.
     */
    enum class CueKind { LookAwayStart, RestPoseDue, ReminderDue, WindDown }

    /**
     * A single cue crossing: fire the [kind] cue for [title] at [instant]. [endInstant] is the look-away's
     * resume moment (`start + duration`) for [CueKind.LookAwayStart], else equal to [instant].
     *
     * [sourceId] names the thing that is due where the [title] cannot: a [CueKind.ReminderDue] carries its
     * **tag's panel id**, which is how the engine finds the reminder's own alert settings (PRD §11) and how it
     * de-dupes — the id is stable across a regeneration of the tags while the instant of a reminder with no
     * time of day is not. Empty for the break cues, which are named by their title.
     */
    data class CueCrossing(
        val instant: Long,
        val kind: CueKind,
        val title: String,
        val endInstant: Long,
        val sourceId: String = "",
    )

    /**
     * PRD §14: the **reminder tags** the now-line crossed in `(fromMillis, toMillis]`, as cue crossings.
     *
     * The occurrence source is the tag itself — the zero-duration panel the calendar draws, laid by
     * [choreScheduledPanels] and kept by the regeneration — rather than a second reading of the reminders'
     * recurrence arithmetic. A reminder's placement is not a formula per local day the way an alarm's is: it
     * is dispersed, constrained by another reminder, anchored on the last completion, and a manually placed
     * tag is a placement too. Announcing off a second derivation of that is how the app would come to say a
     * reminder is due at an instant the calendar does not draw it at.
     *
     * A **checked** tag is a completion, so it is not announced: the user has already done the thing. An
     * overdue unchecked one rides the now-line on the calendar but keeps the instant it was *for*, which is
     * the boundary that gets crossed exactly once.
     */
    fun reminderCueOccurrencesBetween(
        tags: List<TaskPanel>,
        fromMillis: Long,
        toMillis: Long,
    ): List<CueCrossing> =
        tags.asSequence()
            .filter { it.chore && !it.checked }
            .filter { it.startEpochMillis > fromMillis && it.startEpochMillis <= toMillis }
            .map {
                CueCrossing(
                    instant = it.startEpochMillis,
                    kind = CueKind.ReminderDue,
                    title = it.title,
                    endInstant = it.startEpochMillis,
                    sourceId = it.id,
                )
            }
            .toList()

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
     * **In `t_p` mode 2 a screen break is not announced at all** ([DynamicPeriods.breaksAreNotifiedAt]) — the
     * user's rule, and the whole of what tells mode 2 from mode 3. Every screen of the account is locked and
     * nobody has said they are taking a break, so there is neither anybody to tell nor a break being taken.
     * The break is still PLACED and still drawn: this drops the crossing, it does not move the period. It is
     * dropped rather than swallowed downstream so nothing marks it announced — a mode that flips back to 1
     * inside the same scan window announces what it crossed. The **wind-down** is not a screen break and is
     * unaffected.
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
        /**
         * PRD §14: the reminder tags to announce off ([reminderCueOccurrencesBetween]) — the calendar's own,
         * normally `state.panels`. Empty asks about no reminders, which is what every caller that has none
         * wants to say.
         */
        reminderTags: List<TaskPanel> = emptyList(),
        basePeriods: List<RestrictivePeriod> = emptyList(),
        blocks: List<PlanBlock> = emptyList(),
        tasks: List<PlanTask> = emptyList(),
        /** The `t_p` mode the line is in — the at-line run's half of the reading above is a function of it. */
        mode: Int = DynamicPeriods.MODE_AT_SCREEN,
    ): List<CueCrossing> {
        val out = mutableListOf<CueCrossing>()
        val byTitle = screenBreaks.associateBy { it.title }
        // The window's right edge IS the now-line here: the sweep asks about `[scanFloor, now]`.
        val announcedBreaks =
            if (DynamicPeriods.breaksAreNotifiedAt(mode)) {
                screenBreakCueOccurrencesBetween(
                    screenBreaks, fromMillis, toMillis, toMillis, basePeriods, blocks, tasks, mode,
                )
            } else {
                emptyList()
            }
        for (panel in announcedBreaks) {
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
        // PRD §14: the reminder tags due in the window — announced in the same ordered sweep as the breaks,
        // so a reminder and a screen break crossed by one leap are said in the order they were due.
        out += reminderCueOccurrencesBetween(reminderTags, fromMillis, toMillis)
        // Wind-down (bedtime - 1h) instants that fall in the window.
        for (wd in windDownInstants) {
            if (wd in fromMillis..toMillis) out += CueCrossing(wd, CueKind.WindDown, "", wd)
        }
        return out.sortedWith(compareBy({ it.instant }, { it.kind.ordinal }))
    }

    /**
     * PRD §15: **what the user is free to go on doing when a screen break ENDS** — the stretch the break runs
     * out into, when that stretch is one the break did not begin in.
     *
     * A break the user is told about is a break they have to come back from, so the one thing worth adding to
     * that sentence is that they do not: the break ends inside a period no on-screen work happens in anyway,
     * and it started outside it. Two things can say so and they are the two named cases —
     * [UserPeriod] (a restrictive period the user drew themselves) and [BeforeBed] (§17's wind-down hour).
     */
    enum class ScreenBreakFollowOn {
        /** A restrictive period the user drew — the break runs out into it. */
        UserPeriod,

        /** §17's `before bed` hour ([PeriodKinds.BEFORE_BED]) — the break runs out into the wind-down. */
        BeforeBed,
    }

    /**
     * The [ScreenBreakFollowOn] for a break occupying `[startMillis, endMillis]`, or `null` where there is
     * nothing to add.
     *
     * The rule, exactly: the break's START is covered by no qualifying period and its END is
     * (half-open coverage `start <= t < end`, so a period beginning at the break's own end instant IS the
     * stretch that follows it — the case this exists for). A break wholly inside such a period says nothing:
     * the user was already off the screen when it began.
     *
     * What qualifies is what the calendar already draws as a period the user is away from the screen for —
     * a period the USER drew (never a derived one) and the §17 wind-down hour. The three dynamic periods are
     * excluded because a break is not the freedom that follows a break, and a §17 **sleep window** because the
     * wind-down hour always precedes one: a break reaching a sleep window started inside `before bed` and is
     * already refused by the first clause. Where both qualify at the end instant the user's own period is
     * named first, being the one they asked for.
     */
    fun screenBreakFollowOn(
        panels: List<TaskPanel>,
        startMillis: Long,
        endMillis: Long,
    ): ScreenBreakFollowOn? {
        fun coveringAt(instant: Long): List<ScreenBreakFollowOn> =
            panels.mapNotNull { panel ->
                val kind = followOnKindOf(panel) ?: return@mapNotNull null
                if (instant >= panel.startEpochMillis && instant < panel.endEpochMillis) kind else null
            }
        if (coveringAt(startMillis).isNotEmpty()) return null
        val ending = coveringAt(endMillis)
        return when {
            ScreenBreakFollowOn.UserPeriod in ending -> ScreenBreakFollowOn.UserPeriod
            ScreenBreakFollowOn.BeforeBed in ending -> ScreenBreakFollowOn.BeforeBed
            else -> null
        }
    }

    /** Which side of [screenBreakFollowOn]'s qualifying set this panel is on, or `null` for neither. */
    private fun followOnKindOf(panel: TaskPanel): ScreenBreakFollowOn? = when {
        !panel.isRestrictivePeriod -> null
        // A dynamic period, taken or conducted, is a break — never the stretch a break runs out into.
        panel.screenBreak || panel.conductedBreak -> null
        panel.id.startsWith(BEFORE_BED_PANEL_ID_PREFIX) -> ScreenBreakFollowOn.BeforeBed
        // Derived §17 sleep, and the recorded sessions it materializes, are not what this announces (above).
        panel.sleep -> null
        else -> ScreenBreakFollowOn.UserPeriod
    }

    /**
     * PRD §15: the BODY of a screen break's start notification — the break's [title], plus what the break
     * runs out into where [screenBreakFollowOn] has something to say.
     *
     * One function, so the automatic cue sweep and the manual "Look away now" cannot word one break two ways.
     */
    fun screenBreakStartNotificationMessage(
        panels: List<TaskPanel>,
        title: String,
        startMillis: Long,
        endMillis: Long,
    ): String = when (screenBreakFollowOn(panels, startMillis, endMillis)) {
        ScreenBreakFollowOn.UserPeriod -> "$title — followed by a no screen period"
        ScreenBreakFollowOn.BeforeBed -> "$title — followed by the hour before bed"
        null -> title
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
     *    there and only there, because mode 3 is a mode in which nothing drags a pose, so where the bars put
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

    private fun screenBreakPanel(
        index: Int,
        title: String,
        start: Long,
        end: Long,
        dragged: Boolean = false,
    ): TaskPanel =
        TaskPanel(
            id = "side/$index/$start" + if (dragged) DRAGGED_BREAK_ID_SUFFIX else "",
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

    /**
     * PRD §11: the panel covering [nowMillis] (pinned or auto) — what to notify as the current task.
     *
     * **Asked with the MODE, because that is what the schedule is parameterized by.**
     * `docs/scheduler_requirements.md` § *$now line$ 3 modes*: *"Mode 2 & 3: $now line$ must be covered by
     * the period 'no on-screen task'"* — a constraint on the line at EVERY instant it is in one of those
     * modes, not only at the instant the last fill happened to run. The fill expresses it as
     * [DynamicPeriods.awayCover], zero wide at the line it was built for
     * (`[now, now]`: it decides the run the line starts in, [ScheduleFill.firstAmong]); the line then walks on without re-planning (CLAUDE.md: time passing never
     * re-plans), straight into the on-screen task the plan put after it. Reading the stored panel alone is
     * therefore reading an answer computed for a different `t_p` — and the app **announced "Task to do now"
     * in the middle of a declared-away spell** (account 3, 15:08:40 on 2026-09-12, away since 14:54:15).
     *
     * So the mode is applied HERE, where the question is asked, rather than by trying to keep a stored plan
     * continuously true: in either away mode an ON-SCREEN task is not scheduled at the line, whatever a plan
     * built under mode 1 says. Who survives is the resilience and nothing else ([Task.onScreen] — a `0`
     * against [PeriodKinds.NO_SCREEN]), the same predicate [clipPanelsForObservedNoScreen] cuts the display
     * with and [clipRecordsForObservedNoScreen] refuses to bank a record with. Those two shipped and this one
     * did not, which is why the calendar drew no task across the stretch, the bank stored none, and the app
     * still spoke one.
     *
     * A period, a screen break, a reminder tag or a panel of no task at all is not work and is returned
     * unchanged — the mode says nothing about them.
     */
    fun currentPanel(
        state: SchedulerState,
        nowMillis: Long,
        tpMode: Int = DynamicPeriods.MODE_AT_SCREEN,
    ): TaskPanel? {
        val panel = panelAt(state.panels, nowMillis) ?: return null
        if (!DynamicPeriods.lineIsCoveredAt(tpMode)) return panel
        val task = panel.taskId?.let { state.tasks[it] } ?: return panel
        return if (task.onScreen) null else panel
    }

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
            isWorkPanel(it) && it.startEpochMillis <= nowMillis && nowMillis < it.endEpochMillis
        }?.taskId

    /**
     * PRD §7 / `side-dev/README.md` § *Restrictive Period*: **the kinds of every restrictive period that
     * actually restricts [nowMillis]** — what the timeline is inside right now, read off the panels the
     * calendar draws.
     *
     * The display's own reading of the question `fillSchedule` asks per window: a period is a start, an end
     * and a KIND ([TaskPanel.restrictiveKind] — never the four legacy flags, so a kind with no flag of its
     * own counts too), and everything a task may or may not do there follows from its resilience to those
     * kinds. Bounded by the panel list and asked at ONE instant, so it is cheap enough for a surface that
     * re-asks it whenever the now-line crosses a boundary.
     *
     * **A period the line is DRAGGING is not one of them** ([isDraggedScreenBreak],
     * `docs/invariants/screen-breaks.md`) — the same drop `fillSchedule` makes when it builds its
     * `restrictions`, and for the same reason: mode 1 pushes an owed pose ahead of the line at every position
     * of the line, so no instant of the timeline is ever inside it and it obstructs nothing. It is drawn, it
     * is cued and it still says a break is owed; what it is not is a restriction on the task running now.
     * Asking this without the drop is how the §7 picker came to paint every task red while the line was
     * dragging a 15-minute pose: the panel is materialized as `[t_p + 1, t_p + d + 1)` at the instant of the
     * fill, and the line then sweeps into it long before the next re-plan pushes it forward again.
     *
     * One thing it deliberately does NOT see: mode 2's `no on-screen task` cover
     * ([DynamicPeriods.awayCover]), which is an environment period the fill builds for itself and never a
     * panel. That is a restriction this answer misses while the user is AWAY — which is a state the chord
     * that asks this question is not struck in.
     */
    fun restrictiveKindsAt(state: SchedulerState, nowMillis: Long): Set<String> =
        state.panels.asSequence()
            .filter { it.startEpochMillis <= nowMillis && nowMillis < it.endEpochMillis }
            .filterNot { isDraggedScreenBreak(it) }
            .map { it.restrictiveKind }
            .filter { it.isNotEmpty() }
            .toSet()

    /**
     * A task's **resilience multiplier** inside [kinds] — `1` where nothing restricts it, `0` where it is
     * forbidden, and a fraction where its share is merely scaled. [PeriodKinds.multiplier] is the whole of
     * it; this overload exists only so a caller asking it of many tasks reads the kinds once.
     */
    fun taskResilienceIn(state: SchedulerState, taskId: TaskId, kinds: Set<String>): Double =
        PeriodKinds.multiplier(state.tasks[taskId]?.resilience.orEmpty(), kinds)

    /**
     * A task's resilience multiplier **at the now-line** — [taskResilienceIn] over [restrictiveKindsAt].
     *
     * This is exactly the multiplier the score reads there ([ScoreModel]'s `μ_i`), which is
     * why the §7 task picker can colour its rows with it: a `0` row is a task the plan cannot place at this
     * instant however it is asked, and a fractional one is a task whose share is being scaled down while the
     * period lasts.
     */
    fun taskResilienceAt(state: SchedulerState, taskId: TaskId, nowMillis: Long): Double =
        taskResilienceIn(state, taskId, restrictiveKindsAt(state, nowMillis))

    /**
     * Whether [panel] stands for **real work on a task** — the one reading of that question.
     *
     * Everything else the calendar draws is not a task: a §14 reminder tag, and every **restrictive period**
     * — a screen break, a sleep band, a wind-down hour, a grey or no-screen period, one of a kind the account
     * defined (none of which carry a [TaskPanel.taskId] anyway). Asked through [TaskPanel.isRestrictivePeriod],
     * the single reading of a panel's kind, rather than through the four legacy flags: spelling those out said
     * the same thing for the four kinds that have one and nothing at all for a kind that has not. Both
     * questions the now-line asks about tasks go through it — what it is on right now ([taskAtNowLine]) and
     * what it was on before ([taskPickerEntries]) — so the two can never disagree about what counts.
     */
    private fun isWorkPanel(panel: TaskPanel): Boolean =
        panel.taskId != null && !panel.chore && !panel.isRestrictivePeriod

    /**
     * PRD §7 **"Switch task"**: [switch] if the refusal it records is still **outstanding** at [nowMillis],
     * else null — the value [fillSchedule] hands the search as `refusedFirst`, so the refused task is not the
     * one that starts here.
     *
     * A refusal is outstanding until some **other** task has actually been served past the instant it was made.
     * That is what granting it means, and reading it off [past] (the same recorded history the frozen past
     * is replayed from) is what keeps CLAUDE.md's resume contract: a chain of re-plans over the refusal reaches
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
     * history the frozen past is replayed from — is what keeps CLAUDE.md's resume contract. A marker stamped
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

    // ----- PRD §7 the task picker -------------------------------------------------------------

    /**
     * One row of the PRD §7 **task picker** — the menu the "Choose the task to do now" chord opens at the
     * pointer ([org.example.project.scheduler.platform.GlobalShortcut.PickTask]).
     *
     * [label] is the row's own [changeTaskMenuLabel] — the task's shortest path in the tree, as every other
     * identity menu in the app labels a task, because two tasks may share a title and the user is choosing
     * between them by sight. [lastTouchedMillis] is the instant the now-line was last **on** this task, or
     * null for a task it has never been on; the list is sorted by it and the UI prints it.
     */
    data class TaskPickEntry(
        val taskId: TaskId,
        val label: String,
        val lastTouchedMillis: Long?,
    )

    /**
     * PRD §7 the task picker's list: every task the plan can be made to start
     * ([org.example.project.scheduler.state.SchedulerIntent.ForceTaskStart]), **most recently touched by the now-line first**.
     *
     * "Touched by the now-line" is read off the recorded past and nothing else — the task panels that stand
     * for real work ([isWorkPanel]) and the records banked from them — so a row's rank is a fact about what
     * the user has actually been doing, never about the plan's intentions ahead of the line. A panel
     * straddling the line counts at the line, not at its end: the future half has not happened.
     *
     * **The task the now-line is on right now is left out.** The list is what to switch *to*; leaving it in
     * would put it first (it is being touched at this very instant) and make Enter straight after the chord
     * re-ask for the task the user is trying to leave. With it gone the first row is the task worked before
     * this one, so chord-then-Enter means "back to what I was doing".
     *
     * Tasks the now-line has never been on are kept, after the touched ones, in the identity menus' own
     * order ([taskIdMenuSort]: shortest path first) — a task that has never run is exactly the one a user
     * may want to start, and the search field below the list is a *second* way to find one, not the only
     * one.
     *
     * O(records + panels + tasks), and asked **on a press** rather than on a tick — nothing here is on the
     * display hot path (`docs/invariants/display-hot-path.md`); the result is remembered for as long as the
     * menu stands.
     */
    fun taskPickerEntries(state: SchedulerState, nowMillis: Long): List<TaskPickEntry> {
        val current = taskAtNowLine(state, nowMillis)
        val eligible = schedulableLeaves(state).filterTo(HashSet()) { it != current }
        if (eligible.isEmpty()) return emptyList()

        val lastTouched = HashMap<TaskId, Long>(eligible.size)
        fun touch(taskId: TaskId, startMillis: Long, endMillis: Long) {
            if (taskId !in eligible || startMillis >= nowMillis) return
            val at = minOf(endMillis, nowMillis)
            val best = lastTouched[taskId]
            if (best == null || at > best) lastTouched[taskId] = at
        }
        // The two halves [pastPeriodsForTask] reads, in one pass over each: a task's banked records, and the
        // panels the schedule laid down (which is where a period the user is in the MIDDLE of comes from).
        for ((taskId, task) in state.tasks) {
            if (taskId !in eligible) continue
            for (record in task.record) touch(taskId, record.startEpochMillis, record.endEpochMillis)
        }
        for (panel in state.panels) {
            val taskId = panel.taskId ?: continue
            if (isWorkPanel(panel)) touch(taskId, panel.startEpochMillis, panel.endEpochMillis)
        }

        val paths = shortestTaskTreePaths(state)
        val neverTouchedOrder = eligible.sortedWith(taskIdMenuSort(state, paths))
        val rank = neverTouchedOrder.withIndex().associate { (index, taskId) -> taskId to index }
        return neverTouchedOrder
            .sortedWith(
                // Touched before untouched; then the later instant first; then the identity menus' order,
                // which is what keeps the untouched tail (and any two tasks last touched in the same
                // millisecond) in a stable, explicable order rather than the hash map's.
                compareBy<TaskId> { if (lastTouched.containsKey(it)) 0 else 1 }
                    .thenByDescending { lastTouched[it] ?: Long.MIN_VALUE }
                    .thenBy { rank[it] ?: 0 },
            )
            .map { taskId ->
                TaskPickEntry(
                    taskId = taskId,
                    label = changeTaskMenuLabel(state, taskId, paths),
                    lastTouchedMillis = lastTouched[taskId],
                )
            }
    }

    /**
     * PRD §7 the task picker's **id menu** — the "Tasks" rows under its search field: the placeable tasks
     * whose title IS what is typed, exactly as a cell's Change Task menu reads an exact title match
     * ([matchingUserTaskIds]), labelled and ordered the same way.
     *
     * There is deliberately **no "New task" row**, for the reason a weight table's optional row has none: a
     * brand-new task has no cell and no place in the tree, so nothing could be started — the picker names a
     * task that exists, or it names nothing.
     */
    fun taskPickerIdentityRows(state: SchedulerState, draftText: String): List<ChangeTaskMenuEntry> {
        val paths = shortestTaskTreePaths(state)
        return matchingUserTaskIds(state, draftText.trim(), paths)
            .filter { isPlaceableTask(state, it) }
            .map { ChangeTaskMenuEntry(taskId = it, label = changeTaskMenuLabel(state, it, paths)) }
    }

    /**
     * PRD §7 the task picker: **what Enter takes**, and the one place that rule is written.
     *
     * Two states, because the menu has two ways of naming a task and only one Enter:
     *  - the search field **empty** — the highlighted row of the list, which starts on the first row, so the
     *    chord followed by Enter is "back to the task I was on before this one";
     *  - the field **holding text** — the task that text NAMES, which is the first row of the field's own id
     *    menu ([taskPickerIdentityRows]): the same row the user can see, so Enter and a click agree. Text
     *    naming no task commits nothing (a title *suggestion* only fills the field, here as everywhere
     *    else — picking one is not choosing a task).
     */
    fun taskPickerCommit(
        state: SchedulerState,
        entries: List<TaskPickEntry>,
        draftText: String,
        highlighted: Int,
    ): TaskId? =
        if (draftText.isBlank()) {
            entries.getOrNull(highlighted)?.taskId
        } else {
            taskPickerIdentityRows(state, draftText).firstOrNull()?.taskId
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
    /** The longest a "task to do now" notification's path line runs before its start is dropped. */
    const val TASK_NOTIFICATION_PATH_MAX_CHARS: Int = 48

    fun taskSwitchNotificationMessage(
        state: SchedulerState,
        taskId: TaskId,
        startMillis: Long,
        formatDeadline: (Long) -> String,
    ): String? {
        val title = state.tasks[taskId]?.title?.takeIf { it.isNotBlank() } ?: return null
        // Where the task sits: its shortest path below the tree's root (a top-level task has none), the start
        // dropped when it runs long — the end, next to the task, is the part that tells two of a name apart.
        val path =
            SearchDomain.shortestPathsInAnyTree(state)[taskId]?.drop(1)?.takeIf { it.isNotEmpty() }
                ?.let { SearchDomain.shortenedPathLabel(it, TASK_NOTIFICATION_PATH_MAX_CHARS) }
        val head = if (path == null) title else "$title\n$path"
        val unit = state.tasks[taskId]?.scheduleUnit.orEmpty()
        if (unit.isEmpty()) return head
        val lines =
            scheduleUnitDeadlines(unit, startMillis).joinToString("\n") { (stepTitle, deadline) ->
                "• $stepTitle — ${formatDeadline(deadline)}"
            }
        return "$head\n$lines"
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
     * PRD §8: **the panel the USER put there** — the one question the blue outline and the pin box on the
     * calendar answer, and the one reading of it.
     *
     * It is the complement of the two things the app lays down itself, so it is written as that complement
     * rather than as a list of what qualifies: a panel is the user's exactly when it is neither
     * scheduler-generated ([isRegeneratedPanel] — the auto fill's picks and the three period families a fill
     * re-lays wholesale: the screen breaks, the derived sleep windows and the §17 wind-down hours) nor a §14
     * reminder tag ([TaskPanel.chore], which carries its own check box and is drawn as a tag, not a panel).
     * Everything left is something the user placed: a task panel added from the menu, a scheduler panel the
     * user has since dragged or resized, and every restrictive period drawn by hand.
     *
     * It says **who placed it**, never whether the scheduler is bound by it — that is [isSchedulerFixed] for
     * a task panel and the period's own kind for a period.
     */
    fun isUserPlaced(panel: TaskPanel): Boolean =
        !panel.auto && !panel.chore && !isRegeneratedPanel(panel)

    /**
     * PRD §8: **what a block's outline says — WHERE THE USER SAID IT.** The one reading behind every outline
     * the calendar draws, so the drawing cannot answer it differently from a test.
     *
     * The user's own statement of the rule, and it is a rule about **which surface** the thing was stated on
     * rather than about who benefits from it: *"things placed by rules defined in windows accessible via the
     * left side menu of the app (Sleep schedule, Alarm) are outlined in ORANGE; the ones that are placed or
     * moved through a right-click menu in the calendar are outlined in BLUE."* Both surfaces are the user's
     * hand, which is why "who placed it" could not tell them apart — and reading it as "the user's" is what
     * put every daily alarm in blue.
     */
    enum class PanelOutline {
        /**
         * Nothing placed it: the §9 fill's own task panels, and the DERIVED bands (a past "Inactivity"
         * stretch, a layer region). A derived Inactivity band is drawn as **nothing but its title** — there
         * is no hand to name and nothing was stated, so there is nothing to outline.
         */
        None,

        /**
         * Stated **on the calendar**: added from its right-click menu, or an existing block the user has
         * since dragged or resized there ([isUserPlaced]) — plus the §14 reminder tag, which is added the
         * same way ([reminderTagOutline]). Drawn BLUE.
         */
        User,

        /**
         * Stated in a **window off the left menu**, as a rule the app then applies wherever it falls: the
         * §17 sleep schedule's windows and the wind-down hours it implies, and the §18 Alarms window's
         * alarms and timers ([ringOutline]). Drawn ORANGE.
         */
        Pattern,

        /**
         * One of the three DYNAMIC restrictive periods (`side-dev/README.md` § *3 Dynamic Restrictive
         * Period*) — the 20 s look-away and the two rest poses, whether projected by the recurrence bars
         * ([TaskPanel.screenBreak]) or conducted and recorded ([TaskPanel.conductedBreak]). Drawn GREY.
         */
        Dynamic,
    }

    /**
     * PRD §8: **the outline [panel] wears**, asked once.
     *
     * **Three ways a period gets onto the timeline, and the colour names which one.** The DYNAMIC periods are
     * asked for first because they are the one family that is neither the user's hand nor a standing rule:
     * the recurrence bars place them against the timeline itself, and a break the app CONDUCTED is one of the
     * three that really happened — it is `auto = false` and so would otherwise read as something the user
     * drew. After that the two remaining answers are complements: a block the USER placed is blue whatever it
     * is (a task panel, a period of any kind), and what is left over is orange exactly when it is a
     * restrictive period — because the only periods the app lays by itself are the ones a repeating rule puts
     * there (the §17 sleep windows and the wind-down hours measured back from them). So there is no list of
     * families to keep in step with [isRegeneratedPanel]: add a fill-laid period family tomorrow and it is
     * orange for the same reason these are.
     *
     * A derived band ([derivedInactivityBands], a layer region) has no panel behind it to ask about, and that
     * is the same answer by the same route: nobody placed it, so it carries no outline — an "Inactivity"
     * stretch the past left uncovered is drawn as its title and nothing else.
     *
     * The two families that are neither a panel nor derived have their own answers beside this one, and for
     * the same reason a derived band has none — there is no [TaskPanel] to read: [ringOutline] for a §18
     * alarm/timer ring (orange, a rule off the left menu) and [reminderTagOutline] for a §14 tag (blue,
     * added from the calendar's own menu).
     */
    fun panelOutline(panel: TaskPanel): PanelOutline = when {
        panel.screenBreak || panel.conductedBreak -> PanelOutline.Dynamic
        isUserPlaced(panel) -> PanelOutline.User
        panel.isRestrictivePeriod -> PanelOutline.Pattern
        else -> PanelOutline.None
    }

    /**
     * PRD §8/§18: **the outline an alarm's or a timer's ring wears — ORANGE**, the same as a §17 sleep
     * window's, and for the identical reason: it is a rule the user stated in a window off the LEFT MENU,
     * which the app then applies wherever the rule falls. A daily alarm rings on days the user never
     * looked at, exactly as the sleep schedule lays a window on every one of them.
     *
     * It is asked here rather than at the drawing site because a ring has no [TaskPanel] behind it for
     * [panelOutline] to read — it is an INSTANT, drawn as a fixed-height marker — and that absence is what
     * made it the easy one to get wrong: on 2026-09-12 the ring was given the blue border under "the whole
     * added period/panel/reminder/alarm must be outlined in blue", read as *the user added it*. Every daily
     * alarm then drew as something placed on the calendar, which is the one thing an alarm can never be:
     * **the calendar's own menu cannot add an alarm or a timer at all** — it only EDITS one, by opening the
     * §18 window that owns it. There is no blue case to distinguish, so this takes no argument.
     */
    fun ringOutline(): PanelOutline = PanelOutline.Pattern

    /**
     * PRD §8/§14: **the outline a reminder tag wears — BLUE.** A reminder is added from the calendar's own
     * right-click menu ("add…" → reminder) and edited from its "edit…" chooser, so it is on the calendar
     * side of the rule, unlike the ring beside it.
     *
     * Its own answer for the same reason as [ringOutline]'s — a tag is a chip at an instant with its own
     * check box, not a panel, so [isUserPlaced] deliberately excludes it ([TaskPanel.chore]) and
     * [panelOutline] would say [PanelOutline.None]. The case that makes the outline load-bearing rather
     * than decorative is a **checked** tag: its fill goes muted, and the border is then the only thing left
     * saying whose it is.
     */
    fun reminderTagOutline(): PanelOutline = PanelOutline.User

    /**
     * PRD §8: the pins a block carries once the user has **put it where it is** — dragged it or dragged one
     * of its edges on the grid.
     *
     * Placing a block by hand IS the existence pin. The gesture says *this occurrence, here*, and a panel the
     * scheduler is still free to wipe cannot say that: dragging one of the fill's own panels used to hand the
     * reducer the panel's own (empty) pins, so the panel became user-authored and unpinned — which is
     * precisely the shape [fillSchedule] deletes, and the next re-plan silently undid the drag. The pin the
     * gesture sets is the same one the edit window's first switch holds, and the window is where the user says
     * otherwise; the calendar's own pin box is the third way to the same field.
     *
     * The other three pins are left exactly as they were: a drag is a statement about existence, not about
     * whether the position, the span or a distance is fixed from now on.
     */
    fun pinsAfterHandPlacement(pins: PanelPins): PanelPins =
        if (pins.existence) pins else pins.copy(existence = true)

    /**
     * PRD §9 Scheduling: regenerate the auto schedule — **the continuation with the best score**
     * (`docs/scheduler_score.md`), or as close to it as the budget allows.
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
     * Every scheduling decision — which task, for how long — is the score's ([ScoreModel]) and its search's
     * ([ScheduleOptimizer], [ScheduleImprover]), reached through [ScheduleFill]. What this function adds is the
     * mapping from OmniApp's world onto the score's inputs, and the materialization of concrete [TaskPanel]s:
     * - **pre-placed tasks** = the user's pinned/manual panels, past and future;
     * - **the frozen past** = the already-served records and past panels (plus, on an extension, the kept head of
     *   the plan), replayed on the schedulable clock to give every task's lag and the run in progress at the line;
     * - **restrictive periods** = every panel that names a kind ([TaskPanel.restrictiveKind]), the §15 screen
     *   breaks included. A period is a start, an end and a **kind**, and who may run inside it is each task's
     *   **resilience** to that kind — a multiplier in `[0, 1]` on its priority percentage, `0` forbidding it
     *   outright ([PeriodKinds]). Overlapping periods multiply, so the strictest still forbids. A stretch nobody
     *   may run in is not on the score's schedulable clock at all, so it neither separates a panel nor creates any
     *   compensation — which is why a look-away, recurring every 20 minutes forever, does not distort the plan.
     *
     * A fixed block owned by another task and a period that bans a task are the *same* deprivation: the task kept
     * out gets a raised target share on both sides of it, decaying exponentially with the schedulable distance and
     * bounded whatever the length of the exclusion — a 17-hour block of A buys B a bounded compensation, not 17
     * hours of catch-up.
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
        // fixed 168h. Live callers pass [scheduleHorizonEndMillis] of the displayed span (the reducer, via
        // `SchedulerReducer.scheduleHorizonEndMillis`), so a calendar showing today computes only up to its
        // end, and a closed one twenty minutes. A DISPLAY caller viewing a week past the 168h ceiling passes that week's end
        // directly. The work is O(horizon); a distant week is meant to be filled off the UI thread (it "takes
        // time to display", never freezes), and nothing beyond the requested horizon is retained, so
        // navigating back simply refills the nearer window. The default is the ceiling, for tests and for any
        // caller that genuinely wants the maximum span.
        horizonMillis: Long = nowMillis + SCHEDULE_HORIZON_MILLIS,
        // PRD §9 / CLAUDE.md trigger rule: when non-null, this is an **extension**, not a re-plan — the auto
        // panels already materialized before this instant are KEPT (replayed as served time, exactly as if the
        // fill had just placed them) and only the tail past them is generated. The
        // rolling-horizon / calendar-navigation refills use it so that merely *displaying* more days never
        // rewrites the plan the user is already looking at; only a change to the scheduling rules
        // ([schedulingSignature]) re-plans from `now` (null).
        keepExistingUntilMillis: Long? = null,
        // `side-dev/README.md` § *$t_p$ 3 modes*: which mode the now-line is in — mode 1 while a device of the
        // account is unlocked, mode 2 otherwise ([tpMode] / [anyDeviceUnlockedAt]). It decides where the three
        // dynamic periods sit relative to the line and nothing else. The engine injects it through
        // `SchedulerReducer.tpMode`; the default is mode 1, which is what a shell with no device signal
        // (tests, a headless host that cannot read a lock) should assume — somebody is at a screen.
        tpMode: Int = DynamicPeriods.MODE_AT_SCREEN,
        // PRD §6/§9: where this fill reports what it read and what it ANSWERED — the two halves
        // `docs/scheduler_requirements.md` names separately and which must never be confused for each other
        // ([SchedulerRunRules]). Only the two plan reductions in `SchedulerReducer` pass one — the display
        // fills leave it null, and nothing is described then, so this costs a null check on the hot path.
        rulesSink: ((SchedulerRunRules) -> Unit)? = null,
        // `docs/scheduler_score.md` § *The rules repeat*: where the fill reports the repeating part of the rules it
        // returned (null: they do not repeat). An EXTENSION of a state holding one unrolls it where it still holds.
        cycleSink: ((org.example.project.scheduler.model.ScheduleCycle?) -> Unit)? = null,
        // `docs/invariants/scheduler.md` § *One device plans*: the runs another device of the account placed for these
        // rules, laid instead of searched (and [adoptedCycle], the repeating part past them). Null: search here.
        adoptedPlacements: List<org.example.project.scheduler.model.RulePlacement>? = null,
        adoptedCycle: org.example.project.scheduler.model.ScheduleCycle? = null,
        // `docs/scheduler_score.md` § *Degradation*: the wall time this fill may spend past its step-bounded passes —
        // the exhaustive search that certifies the best continuation, and the platform's solver. NONE for every fill
        // that answers a display or a press.
        searchBudget: SearchBudget = SearchBudget.NONE,
        // Other devices' continuations for these rules, to compete on the score with this fill's own search
        // (`docs/invariants/scheduler.md` § *One device plans*). The plan this re-plan replaces always competes.
        extraSeeds: List<List<org.example.project.scheduler.model.RulePlacement>> = emptyList(),
        // What the extra passes did, and the score of the searched continuation (null: nothing was searched).
        searchSink: ((SearchReport, Double?) -> Unit)? = null,
    ): List<TaskPanel> {
        var ruleState: List<String> = emptyList()
        // Only the fill itself is measured: describing it is a diagnostic the two plan reductions ask for,
        // and folding its cost into `scheduler.fillSchedule` would misreport what a re-plan costs a user
        // (CLAUDE.md — the section is how the re-plan cost is checked rather than assumed).
        val panels =
            Perf.measure("scheduler.fillSchedule") {
                fillScheduleUninstrumented(
                    state, nowMillis, timeZone, liveRest, noScreenEvidence, horizonMillis,
                    keepExistingUntilMillis, tpMode, if (rulesSink == null) null else ({ ruleState = it }), cycleSink,
                    adoptedPlacements, adoptedCycle, searchBudget, extraSeeds, searchSink,
                )
            }
        // The returned set of rules is read off what the fill RETURNED, here, rather than collected inside
        // it: the fill has several exits and a rule list assembled at one of them would be a second, partial
        // reading of the same answer (CLAUDE.md *one rule, one funnel*).
        rulesSink?.invoke(
            SchedulerRunRules(
                ruleState = ruleState,
                rules = describeScheduleRules(panels, nowMillis, tpMode),
            ),
        )
        return panels
    }

    /**
     * PRD §6: one line of the **rule state**, spelled for a human — `docs/scheduler_requirements.md` § *Rule
     * State Definition*: *"the set of tasks and their associated priority percentages, minimum execution time
     * and resilience values"*. A task with no resilience override is spelled "on screen only", which is what
     * an empty map means (see [PlanTask.resilience]).
     *
     * **This is the scheduler's INPUT, not its answer.** The rule state is what the user authored; the set of
     * rules is what the scheduler returned for it ([describeScheduleRules]). The History window shows the two
     * as two sections precisely because reading one for the other is the confusion this split exists to end.
     *
     * Written out here — beside the [PlanTask] it describes — rather than in the UI, where it would be a
     * second spelling of the same thing.
     */
    fun describePlanRule(task: PlanTask, title: String): String {
        val name = title.ifBlank { task.id.value }
        val share = ((task.priority * 1000.0).roundToLong() / 10.0)
        val minimum = task.minimumMillis / MILLIS_PER_MINUTE
        val resilience =
            if (task.resilience.isEmpty()) "on screen only"
            else task.resilience.entries.sortedBy { it.key }.joinToString(", ") { (kind, value) ->
                "$kind ${((value * 1000.0).roundToLong() / 10.0)}%"
            }
        return "$name — priority $share%, minimum $minimum min, resilience: $resilience"
    }

    /**
     * PRD §6 / `docs/scheduler_requirements.md`: **the set of rules the scheduler RETURNED**, spelled for a
     * human — one line per instruction, and nothing else.
     *
     * The requirements are explicit about what this is and what it is not. *"The scheduler returns a set of
     * rules that define the task schedule for a given timeline"*, and those rules are *"parameterized by
     * $now line$ and $now line$ mode"* — so a rule is an **instruction**: from here to there, run this task,
     * and (§ *Alternative Schedules*) run that one instead if this one cannot be run now. The tasks with
     * their priority percentages, minimums and resilience values are the **rule state** the scheduler read
     * ([describePlanRule]) — the question, not the answer.
     *
     * The offsets are written **relative to the now-line** rather than as wall-clock instants, because that
     * is what the parameterization means: one list, read at another position of the line, names another
     * schedule. The line's mode is stated once, at the top, for the same reason — the placement of the three
     * dynamic periods is a function of it ([DynamicPeriods]), so a rule list that did not say which mode it
     * was drawn at would not answer for any.
     *
     * What is a rule here: the runs the search chose ([TaskPanel.auto]) and the dynamic restrictive periods it
     * placed ([TaskPanel.screenBreak]) — the two things this fill *decides*. A pre-placed block, a user-drawn
     * period, a sleep window and a reminder tag are the *starting timeline* the requirements name: input the
     * rules were computed against, already listed in the rule state or authored by hand, and repeating them
     * here would make the answer indistinguishable from the question again. Only the future is listed: the
     * past is frozen (§ *frozen past*), so it is no longer something the rules say.
     *
     * Capped at [MAX_DESCRIBED_RULES]: this is a RAM-only diagnostic held for the last
     * [org.example.project.scheduler.state.SchedulerRunEntry.MAX_ENTRIES] runs, and a week of horizon at a
     * short minimum time is a few thousand instructions per run.
     */
    fun describeScheduleRules(panels: List<TaskPanel>, nowMillis: Long, tpMode: Int): List<String> {
        val instructions =
            panels.asSequence()
                .filter { it.endEpochMillis > nowMillis }
                .filter { (it.auto && !it.chore) || it.screenBreak }
                .sortedWith(compareBy({ it.startEpochMillis }, { it.endEpochMillis }))
                .toList()
        val head =
            "now-line mode $tpMode — ${DynamicPeriods.modeLabel(tpMode)}; offsets are from the now-line"
        // The alternative is named by id; the titles are on the panels, so they are collected once rather
        // than searched for per rule.
        val titles = panels.mapNotNull { p -> p.taskId?.let { it to p.title } }.filter { it.second.isNotBlank() }.toMap()
        val body =
            instructions.take(MAX_DESCRIBED_RULES).map { panel ->
                val span =
                    "${formatRuleOffset(panel.startEpochMillis - nowMillis)} → " +
                        formatRuleOffset(panel.endEpochMillis - nowMillis)
                if (panel.screenBreak) {
                    "$span  restrict [${panel.restrictiveKind}] ${panel.title.ifBlank { "period" }}"
                } else {
                    val name = panel.title.ifBlank { panel.taskId?.value ?: "(nobody)" }
                    val alternative =
                        panel.alternativeTaskId?.let { id -> "  else ${titles[id] ?: id.value}" }.orEmpty()
                    "$span  run $name$alternative"
                }
            }
        val overflow = instructions.size - body.size
        return listOf(head) + body + if (overflow > 0) listOf("… $overflow more rules") else emptyList()
    }

    /**
     * An offset from the now-line, `±h:mm:ss`. Seconds are shown because one of the three dynamic periods is
     * twenty seconds long, and a rule list that rounded it away would say the period was empty.
     */
    private fun formatRuleOffset(millis: Long): String {
        val sign = if (millis < 0) "-" else "+"
        val total = abs(millis) / 1000
        val minutes = (total % 3600) / 60
        val seconds = total % 60
        return "$sign${total / 3600}:${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
    }

    /**
     * [fillSchedule]'s body. Split out only so the fill can be timed as one section without a `return@measure`
     * on every one of its exits — CLAUDE.md's rule is that a re-plan happens on a rule change and never on a
     * tick, and this section is how that is checked rather than assumed.
     */
    private fun fillScheduleUninstrumented(
        state: SchedulerState,
        nowMillis: Long,
        timeZone: TimeZone,
        liveRest: LiveRest?,
        noScreenEvidence: List<TaskTimeRange>,
        horizonMillis: Long,
        keepExistingUntilMillis: Long?,
        tpMode: Int,
        ruleStateSink: ((List<String>) -> Unit)? = null,
        cycleSink: ((org.example.project.scheduler.model.ScheduleCycle?) -> Unit)? = null,
        adoptedPlacements: List<org.example.project.scheduler.model.RulePlacement>? = null,
        adoptedCycle: org.example.project.scheduler.model.ScheduleCycle? = null,
        searchBudget: SearchBudget = SearchBudget.NONE,
        extraSeeds: List<List<org.example.project.scheduler.model.RulePlacement>> = emptyList(),
        searchSink: ((SearchReport, Double?) -> Unit)? = null,
    ): List<TaskPanel> {
        val horizon = maxOf(horizonMillis, nowMillis)
        // Cut every non-pinned panel in [now, horizon]; keep fixed (pinned) panels, reminder tags (PRD
        // §14 — kept on the calendar though not obstacles, see isSchedulerFixed), and any panel entirely
        // outside the window — already past (end ≤ now) or, if the fill does not own it, beyond the horizon
        // (start > horizon; an auto panel there is the previous plan's and is cut too). Screen-break
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
                        panel.endEpochMillis <= nowMillis ||
                        // Beyond the horizon only what the fill does NOT own survives. An AUTO panel there is
                        // the previous plan's, laid under rules this fill may have replaced: kept, it sat in
                        // the state as the old answer, and when it happened to abut the new tail the next
                        // extension read it as materialized and kept it as definitive ([firstFreeMoment]).
                        // With the ten-minute goal floor a fill that stops short of an older plan is routine.
                        (panel.startEpochMillis > horizon && !panel.auto) ||
                        // An EXTENSION keeps the already-materialized head of the plan (see the parameter).
                        (keepExistingUntilMillis != null && panel.auto && panel.startEpochMillis < keepExistingUntilMillis)
            }
            when {
                survives -> panel
                // `side-dev/README.md` § *frozen past*: **"the schedule at t < $now line$ never changes as
                // $now line$ increases."** A task panel the line is standing IN is cut by the branch above
                // and the plan is regenerated from `now` — so without this its ELAPSED HEAD, work the app has
                // already told the user it was doing, silently disappears from the timeline on every re-plan
                // (and, because [pastPeriodsForTask] reads these same panels, from the frozen past the lags are
                // replayed from). It is not banked as a record either:
                // [org.example.project.scheduler.state.SchedulerReducer]'s advance banks a panel only once it
                // has wholly elapsed, precisely so an in-progress one stays a panel.
                //
                // So the head is KEPT, truncated at the line, and the tail alone is re-planned. **Whoever
                // placed the panel**: the branch above cuts an auto panel the fill owns and, exactly as
                // deliberately, a panel the user has just UNPINNED (PRD §8 — unpinning is what makes the
                // scheduler stop seeing it). The elapsed half of the second is the same frozen past as the
                // first's, and reading only `auto` here deleted it, which is the frozen-past rule breaking on
                // the one gesture that was asking the scheduler to re-plan.
                //
                // The kept head is an ordinary AUTO panel from here on, however it started: the next advance
                // banks it like any other, [mergeSameTaskPanels] fuses it back with the new panel when the
                // re-plan picks the same task again (it must carry the same `auto`/`pinned` to fuse), it is
                // behind the line so it is served history, never a pre-placed block, and it is no longer something the
                // user placed — so the calendar stops drawing it as one ([isUserPlaced]).
                !panel.chore && panel.taskId != null &&
                    panel.startEpochMillis < nowMillis && panel.endEpochMillis > nowMillis -> {
                    elapsedHeadIds += panel.id
                    panel.copy(endEpochMillis = nowMillis, auto = true)
                }

                else -> null
            }
        }
        // The user's sleep windows. PRD §8: a sleep window IS an inactivity period — one labelled "Sleep" —
        // so, like every grey period, it is a period accepting NOBODY (see [blockedRegions] below). It is
        // still not an occupancy *obstacle*: a chunk crossing one suspends and resumes on the far side.
        // The search looks one decision window past the horizon (`docs/invariants/scheduler.md` § *Progressive
        // Calculation*), so the environment is built that far; what is emitted still stops at the horizon.
        val searchEnd = horizon + ScheduleOptimizer.searchMarginMillis(
            state.tasks.values.map { PlanTask(it.id, 0.0, it.minimumMinutes.toLong() * MILLIS_PER_MINUTE) },
        )
        val envSleepPanels = sleepPanels(state.sleep, nowMillis, searchEnd, timeZone)
        val sleepPanels = envSleepPanels.filter { it.startEpochMillis < horizon }
        // PRD §17 wind-down: **the hour before bed is covered by the period "before bed"**. It is an ordinary
        // restrictive period of its own kind ([PeriodKinds.BEFORE_BED]) — the hour stays empty because every
        // task's default resilience to that kind is `0`, and a task given a value above zero works through it.
        // Derived from the same schedule the sleep windows are, and regenerated with them.
        val envBeforeBedPanels = beforeBedPanels(state.sleep, nowMillis, searchEnd, timeZone)
        val beforeBedPanels = envBeforeBedPanels.filter { it.startEpochMillis < horizon }
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

        // `docs/scheduler_score.md` § *Ties*: (highest absolute priority, then title). The search takes the first
        // candidate on a tie, so handing it this order IS the tie-break.
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

        // PRD §6: hand the RULE STATE this fill read to whoever asked for it, spelled with the titles it
        // already has. What the fill answers with it is described from the returned panels, in [fillSchedule].
        ruleStateSink?.invoke(planTasks.map { describePlanRule(it, working.tasks[it.id]?.title.orEmpty()) })
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
        val standingPeriodPanels = kept.filter { it.isRestrictivePeriod } + envSleepPanels + envBeforeBedPanels
        val standingOwn =
            standingPeriodPanels.mapNotNull { panel ->
                val kind = panel.restrictiveKind
                if (kind.isEmpty()) null
                else RestrictivePeriod(panel.startEpochMillis, panel.endEpochMillis, kind, panel.title)
            }
        val dynamicBase =
            standingOwn +
                // The wind-down hours are laid by THIS fill, not kept, so they are handed in beside `kept`:
                // each one's companion no-screen period (PRD §17) is what makes the hour a rest to the bars.
                companionPeriods(standingOwn, state.periodKindConfig) + listOfNotNull(liveRestPeriod(liveRest)) +
                observedNoScreenPeriods(noScreenEvidence)
        val dynamicBlocks =
            kept.asSequence()
                .filter { it.taskId != null && !it.chore && !it.isRestrictivePeriod }
                .map { PlanBlock(it.taskId, it.startEpochMillis, it.endEpochMillis) }
                .filter { it.endMillis > it.startMillis }
                .toList()
        val envSidePanels =
            screenBreakPanels(
                screenBreaks = state.screenBreaks,
                nowMillis = nowMillis,
                horizonMillis = searchEnd,
                basePeriods = dynamicBase,
                blocks = dynamicBlocks,
                tasks = planTasks,
                mode = tpMode,
            )
        val sidePanels = envSidePanels.filter { it.startEpochMillis < horizon }
        // `docs/scheduler_requirements.md` § *$now line$ 3 modes*, **mode 1**, and § *No idling*: **a period
        // the line is DRAGGING obstructs nothing** ([isDraggedScreenBreak]).
        //
        // Mode 1 says `t_p` may not be covered by one of the three, so a pose the line reached is pushed to
        // `(t_p, t_p + d]` and goes on being pushed at every position of the line — no instant of the
        // timeline is ever inside it. The requirements say what happens instead in as many words: the line
        // *"would continuously delay that period (while creating task panels in its passing)"*. Planning
        // around it as though it were a fixed block is planning around something that cannot happen, and it
        // broke both of those rules at once. The half-open form leaves exactly the single millisecond
        // `[t_p, t_p + 1)` free, so that was the whole of what the fill could place; every later re-plan
        // regenerated the pose at the NEW line and left the entire stretch the line had swept since the pose
        // fell due with no panel at all. The calendar then drew it as a derived "Inactivity" band while a
        // device was unlocked and tasks were free to run — § *No idling*, reported on account 3 — and `d` of
        // the horizon went on a block that recedes.
        //
        // So the dragged instances are dropped from the environment the plan is built over. They are still
        // DRAWN, still cued and still re-anchor the recurrence bars (which is where they belong: the drag is
        // a statement about a break being OWED, not about the timeline being blocked); only [restrictions]
        // loses them, so the plan runs straight through and the passing line leaves task panels behind it. A
        // pose the user actually takes is a different object — a break the app CONDUCTED
        // (`RecordConductedBreak`), a pre-placed period nothing drags — so nothing is lost by refusing to
        // obstruct on one that never happened.
        //
        // **Neither away mode drags at all**, so neither has anything to drop here: the requirements state
        // modes 2 and 3 in one clause and the line is covered in both, which means the pose elapses under the
        // line and really happens. The filter is written as a mode-1 test rather than as "drop the dragged
        // ones" so it stays true if a later mode ever drags again.
        val obstructingSidePanels =
            if (tpMode == DynamicPeriods.MODE_AT_SCREEN) envSidePanels.filterNot { isDraggedScreenBreak(it) }
            else envSidePanels
        // `side-dev/README.md` § *$t_p$ 3 modes*, **mode 2**: *"$now line$ must be covered by the period 'no
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
                    envSidePanels.mapNotNull { panel ->
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

        // --- the RESTRICTIVE PERIODS (`docs/scheduler_requirements.md` § *Restrictive Period*), past AND future:
        // the score measures the frozen past against them and reads a deprivation's influence on both sides of it.
        //
        // Every restriction on the timeline is one object — a start, an end and a KIND — and every task's behaviour
        // inside one is its own resilience to that kind ([Task.resilience]). A stretch nobody may run in is simply
        // not on the score's schedulable clock, which is what makes a break or a night suspend a run rather than
        // cut it (PRD §15/§17) without a rule of its own.
        val periodPanels =
            kept.filter { it.isRestrictivePeriod } + envSleepPanels + envBeforeBedPanels + obstructingSidePanels
        val periodOwn =
            periodPanels.map { panel ->
                RestrictivePeriod(panel.startEpochMillis, panel.endEpochMillis, panel.restrictiveKind, panel.title)
            }
        val standingRestrictions =
            (
                periodOwn +
                    // The companions each period's kind carries, and — the layers' own definition — a
                    // computer-layer period overlapping a phone-layer one IS a no-screen period. Taken in the one
                    // place ([companionPeriods]).
                    companionPeriods(periodOwn, state.periodKindConfig)
                ).filter { it.kind.isNotEmpty() && it.endMillis > it.startMillis }
        // `docs/scheduler_requirements.md` § *$now line$ 3 modes*, **mode 1**: the line is at a screen, so a
        // period saying nobody is does not cover it — it gives up everything from the line to its own end
        // ([retractedAtLineSpans] / [retractAtLine], where the whole rule and its reasons live). The plan is
        // searched over the retracted timeline so it can name which task holds and until when; only the
        // stretch the line has SWEPT is materialized ([retractedSpans] again, at the placements below), which
        // is what leaves the band ahead of the line exactly as it was.
        val retractedSpans =
            retractedAtLineSpans(standingRestrictions, nowMillis, tpMode, state.periodKindConfig)
        val restrictions =
            retractAtLine(standingRestrictions, retractedSpans, state.periodKindConfig) +
                // The away modes' cover. It is the one period whose end is CLOSED — the README covers `t_p` itself —
                // and it is ZERO wide, `[now, now]`: what runs AT the line must be resilient to "no screen", and
                // nothing more ([ScheduleFill.firstAmong]). It was `[now, now + 1)`, a one-millisecond window the
                // search decides at the edge of: the resilient task got that millisecond and an on-screen task the
                // rest, which the away line then swept without drawing or banking anything (§ *No idling*).
                listOfNotNull(
                    awayCover?.let { RestrictivePeriod(nowMillis, nowMillis, it.kind, it.label, closedEnd = true) },
                )

        // --- where placement starts. An EXTENSION keeps the head already materialized: it is part of the
        // continuation the rules gave, so it is replayed as served time, never re-planned and never treated as a
        // pre-placed task (a pre-placed task deprives the others; the scheduler's own plan does not).
        val keptHead =
            if (keepExistingUntilMillis == null) emptyList()
            else kept.filter {
                it.auto && !it.pinned && !it.chore && it.taskId != null && it.id !in elapsedHeadIds &&
                    it.endEpochMillis > nowMillis && it.startEpochMillis < keepExistingUntilMillis &&
                    it.startEpochMillis <= horizon && !it.isRestrictivePeriod
            }
        val startMillis = maxOf(nowMillis, keptHead.maxOfOrNull { it.endEpochMillis } ?: nowMillis).coerceAtMost(horizon)
        // `docs/scheduler_requirements.md` § *Priority, Granularity and Compensation*: *"the timeline is infinite
        // forward and backward"*. How far back is not a wall-time constant — it is four of the longest task window
        // `Theta`, measured on the schedulable clock, so a task rare enough to need a month of it gets one
        // ([ScheduleFill.pastLookbackMillis] holds the whole rule and its reasons).
        val scoreFrom = nowMillis - ScheduleFill.pastLookbackMillis(
            tasks = planTasks,
            periods = restrictions,
            nowMillis = nowMillis,
            windows = SCHEDULE_PAST_LOOKBACK_WINDOWS,
            floorMillis = SCHEDULE_PAST_LOOKBACK_FLOOR_MILLIS,
            capMillis = SCHEDULE_PAST_LOOKBACK_CAP_MILLIS,
        )

        // --- the pre-placed tasks: the user's pinned panels, past and future.
        val pinnedBlocks =
            kept.asSequence()
                .filter { isSchedulerFixed(it) && !it.chore && !it.isRestrictivePeriod }
                .filter { it.endEpochMillis > scoreFrom && it.startEpochMillis < searchEnd }
                .map { PlanBlock(it.taskId, it.startEpochMillis, it.endEpochMillis) }
                .filter { it.endMillis > it.startMillis }
                .sortedBy { it.startMillis }
                .toList()

        // --- the frozen past: what every task has been served, merged per task so a record and the auto panel
        // that banked it count once, plus the kept head of an extension.
        val pastBlocks =
            ordered.flatMap { id ->
                mergeOccupied(pastPeriodsForTask(working, id, nowMillis))
                    .map { PlanBlock(id, maxOf(it.startEpochMillis, scoreFrom), minOf(it.endEpochMillis, nowMillis)) }
                    .filter { it.endMillis > it.startMillis }
            }
        val history =
            pastBlocks + keptHead
                .map { PlanBlock(it.taskId, maxOf(it.startEpochMillis, nowMillis), minOf(it.endEpochMillis, startMillis)) }
                .filter { it.endMillis > it.startMillis }

        // PRD §7 "Switch task" and PRD §13 "start this task now" are the README's alternative schedule put to use:
        // the refused task may not be the first run, the requested one must be. Both are about the line itself, so
        // an extension (which starts past the kept head) carries neither.
        val refusedHere = liveForcedSwitchTask(state.forcedSwitch, pastBlocks, nowMillis)
        val forcedStartTask = liveForcedStartTask(state.forcedStart, pastBlocks, nowMillis)

        // --- the rule state at the line, `R(now)` — exact inside a task-tree transition (the minimum in millis,
        // not rounded to a minute), and held for the whole continuation.
        val inTransition = taskTreeBlendAt(state, nowMillis)?.isSingle == false
        // The rule state MOVES over this fill when the transitions (first keyframe to last) overlap what it searches —
        // at a transition's very first instant the blend still reads as a single tree, and from the next millisecond
        // it does not.
        val dated = if (inTransition) emptyList() else datedTaskTrees(state)
        val moving = inTransition ||
            (dated.size >= 2 && (dated.first().dateMillis ?: Long.MAX_VALUE) < searchEnd && (dated.last().dateMillis ?: Long.MIN_VALUE) > nowMillis)
        val timeline = if (moving) RuleStateTimeline(state) else null
        val ruleState = if (inTransition) timeline!!.planTasksAt(nowMillis) else planTasks

        // `docs/scheduler_score.md` § *Degradation*: the plan this re-plan replaces competes with the search, re-scored
        // under the rules in force now — so a re-plan never returns a continuation worse than the one on screen. An
        // extension keeps its head and has nothing of its own to compete with.
        val previousPlan =
            if (keepExistingUntilMillis != null || adoptedPlacements != null) emptyList()
            else state.panels.asSequence()
                .filter { it.auto && !it.pinned && !it.chore && it.taskId != null && !it.isRestrictivePeriod && it.endEpochMillis > nowMillis }
                .sortedBy { it.startEpochMillis }
                .map { org.example.project.scheduler.model.RulePlacement(it.taskId!!, it.startEpochMillis, it.endEpochMillis, it.alternativeTaskId, it.alternativeSpans) }
                .toList()
        val seeds = (listOf(previousPlan) + extraSeeds).filter { it.isNotEmpty() }

        val filled =
            ScheduleFill.run(
                ScheduleFill.Input(
                    startMillis = startMillis,
                    horizonMillis = horizon,
                    lookbackMillis = startMillis - scoreFrom,
                    ruleState = ruleState,
                    periods = restrictions,
                    blocks = pinnedBlocks,
                    history = history,
                    forcedFirst = forcedStartTask.takeIf { startMillis == nowMillis },
                    refusedFirst = refusedHere.takeIf { startMillis == nowMillis },
                    cycle = adoptedCycle ?: state.scheduleCycle.takeIf { keepExistingUntilMillis != null },
                    adopted = adoptedPlacements,
                    // The limit on how far the rules are searched is the materialization ceiling: past it they repeat.
                    repeatBeyondMillis = nowMillis + SCHEDULE_HORIZON_MILLIS,
                    searchUntilMillis = searchEnd,
                    seeds = seeds,
                    budget = searchBudget,
                    ruleStateAt = timeline?.let { t -> { x: Long -> t.planTasksAt(x) } },
                ),
            )
        cycleSink?.invoke(filled.cycle)
        searchSink?.invoke(filled.report, filled.cost)
        val placements = filled.placements

        var idCounter = 0
        fun nextAutoId(): String {
            while ("auto/$idCounter" in keptIds) idCounter++
            return "auto/${idCounter++}"
        }
        // The plan IS materialized across a retracted period ([retractedAtLineSpans]) and hidden ahead of the
        // line on the DISPLAY side ([clipPlanForRetractedPeriod]), exactly as the plan under a pinned screen
        // break is ([clipPlanForPinnedScreenBreak]). Clipping it here instead leaves nothing for the line to
        // sweep INTO: the fill runs at a rule change and not on time passing (CLAUDE.md), so between two fills
        // the line would advance over a stretch no panel was ever laid in and the calendar would draw the
        // §17 carve's hole as a growing Inactivity band — the very anomaly this answers, moved three minutes
        // to the right.
        val generated =
            placements.map { p ->
                TaskPanel(
                    id = nextAutoId(),
                    taskId = p.taskId,
                    title = working.tasks[p.taskId]?.title.orEmpty(),
                    startEpochMillis = p.startMillis,
                    endEpochMillis = p.endMillis,
                    pinned = false,
                    auto = true,
                    alternativeTaskId = p.alternative,
                    alternativeSpans = p.alternativeSpans,
                )
            }
        // PRD §9: two consecutive auto panels of the same task merge into one block. Screen-break and sleep
        // panels are added as-is (they split the run, so adjacent same-task pieces don't touch and stay apart).
        return (
            kept.filterNot { it.id in elapsedHeadIds } + sidePanels + sleepPanels + beforeBedPanels +
                mergeSameTaskPanels(kept.filter { it.id in elapsedHeadIds } + generated)
            ).sortedBy { it.startEpochMillis }
    }

    /**
     * The rule state `R(x)` along the task-tree timeline, as the score reads it: every leaf of the two keyframes
     * around `x`, its priority, minimum execution time and resilience moving at a constant rate between them
     * (`docs/scheduler_requirements.md` § *Rule State Evolution*) — the minimum in millis, not rounded to a
     * minute. The keyframes' own readings are computed once per keyframe, since a transition asks at every
     * decision.
     */
    private class RuleStateTimeline(val state: SchedulerState) {
        private val priorities = HashMap<TaskTreeId, Map<TaskId, Double>>()
        private val leaves = HashMap<TaskTreeId, List<TaskId>>()

        private fun prioritiesOf(entry: TaskTreeEntry) = priorities.getOrPut(entry.id) { taskTreePriorities(state, entry) }

        private fun leavesOf(entry: TaskTreeEntry) =
            leaves.getOrPut(entry.id) { schedulableLeaves(state.applyTreeWithRecords(entry.tree)) }

        fun titleOf(id: TaskId): String =
            state.tasks[id]?.title?.takeIf { it.isNotBlank() }
                ?: state.taskTrees.firstNotNullOfOrNull { it.tree.tasks[id]?.title?.takeIf { t -> t.isNotBlank() } }
                ?: ""

        fun planTasksAt(x: Long): List<PlanTask> {
            val blend = taskTreeBlendAt(state, x) ?: return emptyList()
            val f = if (blend.isSingle) 0.0 else blend.fraction.coerceIn(0.0, 1.0)
            val pa = prioritiesOf(blend.from)
            val pb = if (blend.isSingle) pa else prioritiesOf(blend.to)
            val ids = if (blend.isSingle) leavesOf(blend.from) else (leavesOf(blend.from) + leavesOf(blend.to)).distinct()
            val ta = blend.from.tree.tasks
            val tb = blend.to.tree.tasks
            val tasks = ids.map { id ->
                // A task only one keyframe holds keeps that side's minimum and resilience; only its percentage fades.
                val a = ta[id] ?: tb[id] ?: state.tasks[id]
                val b = tb[id] ?: a
                val minA = (a?.minimumMinutes ?: DEFAULT_MINIMUM_MINUTES).toDouble() * MILLIS_PER_MINUTE
                val minB = (b?.minimumMinutes ?: DEFAULT_MINIMUM_MINUTES).toDouble() * MILLIS_PER_MINUTE
                val ra = a?.resilience.orEmpty()
                val rb = b?.resilience.orEmpty()
                PlanTask(
                    id = id,
                    priority = (1.0 - f) * (pa[id] ?: 0.0) + f * (pb[id] ?: 0.0),
                    minimumMillis = (minA + (minB - minA) * f).roundToLong(),
                    resilience = (ra.keys + rb.keys).associateWith { kind ->
                        val x0 = PeriodKinds.resilienceFor(ra, kind)
                        val x1 = PeriodKinds.resilienceFor(rb, kind)
                        PeriodKinds.clamp(x0 + (x1 - x0) * f)
                    },
                )
            }
            return tasks.sortedWith(compareByDescending<PlanTask> { it.priority }.thenBy { titleOf(it.id) })
        }
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
        val rules = panels.filter { it.auto && it.taskId != null && (it.alternativeTaskId != null || it.alternativeSpans.isNotEmpty()) }
            .sortedBy { it.startEpochMillis }
        rules.firstOrNull { it.startEpochMillis <= millis && millis < it.endEpochMillis }
            ?.let { return it.alternativeAt(millis) }
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
     * blend, which the signature cannot express — see [taskTreeBlendDecisionKey].
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
            // The panel's KIND, not the two legacy flags: a period of `before bed` or of a kind the account
            // defined restricts the plan exactly as the two named ones do, and read off the flags it was
            // indistinguishable from a task panel — so re-kinding one re-plans nothing.
            result = 31 * result + panel.restrictiveKind.hashCode()
            result = 31 * result + (if (panel.pinned) 1 else 0)
        }
        // The period edit window's companion sets: a period that starts or stops carrying a kind restricts the
        // plan differently. Only the companions — a period's DRAWING is paint, and re-planning on it would be
        // a re-plan of unchanged rules.
        for ((kind, style) in state.periodKindStyles.entries.sortedBy { it.key }) {
            if (style.companions == PeriodKinds.defaultStyle(kind).companions) continue
            result = 31 * result + kind.hashCode()
            result = 31 * result + style.companions.sorted().hashCode()
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
            // A TITLE'S TEXT IS NOT A RULE — only two things about it are, and both are here.
            //
            // The first is whether it is BLANK: a blank title deletes (`docs/invariants/task-tree.md`), so the
            // task leaves the schedulable set. The second is the ORDER titles put the tasks in, which is the
            // tie-break `docs/scheduler_score.md` § *Ties* names — "higher priority first, then title" — and it
            // is hashed once for the whole tree below rather than per task, because only the RELATIVE order is
            // read. Hashing the text itself made every keystroke of a rename a rule change, so renaming a task
            // re-planned the account once per letter for a plan that could not come out any different.
            result = 31 * result + if (task.title.isBlank()) 1 else 0
            result = 31 * result + task.minimumMinutes
            // `side-dev/README.md`: a resilience IS a scheduling rule — changing one changes the plan, so it
            // belongs in the signature (CLAUDE.md: anything new that wants to re-plan belongs here).
            for ((kind, value) in task.resilience.entries.sortedBy { it.key }) {
                result = 31 * result + kind.hashCode()
                result = 31 * result + value.hashCode()
            }
        }
        // `docs/scheduler_score.md` § *Ties*: the tasks IN TITLE ORDER, which is the whole of what the fill
        // takes from the text (it sorts by priority, then title, and hands the search that order). A rename
        // that does not move a task past another one cannot change the plan, and so must not re-plan.
        for (task in tasks.values.sortedWith(compareBy({ it.title }, { it.id.value }))) {
            result = 31 * result + task.id.value.hashCode()
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

    /**
     * PRD §5 the weight table's **default row**, read to this list's column count — the value row a task
     * arrives in [list]'s table with ([org.example.project.scheduler.model.CellList.defaultWeights]).
     *
     * The ONE place that row is read, so the table that draws it and the reducer that seeds from it can
     * never disagree about what it says. A row shorter than the column count (every payload written before
     * the row existed, and any list whose columns grew elsewhere) reads as [defaultWeightAt] past its end,
     * which is exactly what a new task used to get.
     */
    internal fun defaultWeightRow(list: org.example.project.scheduler.model.CellList): List<Double> =
        List(list.weightColumns.size.coerceAtLeast(1)) { c ->
            list.defaultWeights.getOrElse(c) { defaultWeightAt(c) }
        }

    fun parentTaskId(state: SchedulerState, cellId: CellId): TaskId? {
        val cell = state.cells[cellId] ?: return null
        val list = state.lists[cell.parentListId] ?: return null
        if (list.parentCellId == null) return WellKnownIds.ROOT_TASK
        return state.cells[list.parentCellId]?.taskId
    }

    /**
     * The tasks of the cells [cellId] hangs under — PRD §1 *Constraint 2*'s "ancestor cells", and the set
     * the assign / move collision rules are read against.
     *
     * **The root cell is not one of them.** It stands for the whole tree rather than being a parent inside
     * it, so its sub-tree IS the tree: counting it would make every task an ancestor of every cell, and
     * `assignCollisionScope` would read that as "everything collides" — no task could be assigned to any
     * cell any more. The walk therefore stops at the first non-selectable parent, which is exactly where it
     * used to stop when the top list had no parent cell at all.
     */
    fun ancestorTaskIds(state: SchedulerState, cellId: CellId): Set<TaskId> {
        val ancestors = mutableSetOf<TaskId>()
        var listId = state.cells[cellId]?.parentListId ?: return ancestors
        while (true) {
            val list = state.lists[listId] ?: break
            val parentCellId = list.parentCellId ?: break
            if (!isSelectableCell(state, parentCellId)) break
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
        if (isRootTask(taskId)) return false
        // "already in the sub-list": the same task can't appear twice in the cell's own list.
        if (taskId in siblingTaskIds(state, cellId)) return false
        // Constraint 2: a candidate whose own sub-tree holds one of the cell's ancestors would become its
        // own descendant — the infinite mirrored cycle. Read against the ancestor PATH ([assignCollisionScope]),
        // which is the same question [canMoveTaskIntoList] asks of a drop.
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
        val prefix = listOf(WellKnownIds.ROOT_TASK)
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

    /**
     * PRD §4: the **name of the root** a path is read against — the root task's own title, except on the §4
     * template's projection, where it is the template.
     *
     * A path says *which* of the tasks of that title this one is, so it is only an answer if the user can
     * tell where it starts. The template window draws a projection rooted at the template
     * ([SchedulerState.isDefaultSubtreeProjection]), and a template-owned task is named from that drawing —
     * so with the root's own title it read exactly like a path through the account's tree, and sent the user
     * looking for `planning / write good prompt` in a tree that has no such row (2026-09-20, account 3).
     */
    const val DEFAULT_SUBTREE_ROOT_LABEL: String = "Default sub-tree"

    private fun taskPathLabel(state: SchedulerState, path: List<TaskId>): String =
        path.mapNotNull { taskId ->
            if (taskId == WellKnownIds.ROOT_TASK && state.isDefaultSubtreeProjection) {
                DEFAULT_SUBTREE_ROOT_LABEL
            } else {
                state.tasks[taskId]?.title
            }
        }.joinToString(" / ")

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
     * PRD §4 *Presentation*: what an id row wears when **the tree does not hold its task at all** — a
     * tombstone kept alive by its calendar records, a detached parent, a task stranded inside one. It is a
     * real row (assigning the id brings the task, and whatever sub-tree it owns, back where the cell is), so
     * it is marked rather than dropped — exactly as its "go to task" is greyed rather than hidden.
     */
    const val DEAD_TASK_ROW_PREFIX = "[dead] "

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
            .filter { !isRootTask(it) }
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
     * which is the same predicate [tasksInTree] uses for membership and the same one "go to task" is
     * greyed on — one answer, not three. It is wider than `taskHasCells`, deliberately: a task stranded
     * inside a *detached parent's* sub-tree still has a cell, but there is no path in the tree to name it
     * by, so it is named by what it holds exactly as the detached parent above it is. A task with nothing
     * under it falls back to its own title, so no row is ever blank.
     *
     * Such a row is also marked [DEAD_TASK_ROW_PREFIX]. The absence of a path was already the whole of the
     * statement — and it is a statement no one reads, because a row saying `planning` and a row saying
     * `root / planning` differ by something the eye takes for a shorter path, not for "this one is not in
     * your tree" (2026-09-23: the user took a row for a dead task precisely because they had no way to tell
     * a live one from one). It is the same rows whose "go to task" is greyed, and they sort last.
     */
    private fun changeTaskMenuLabel(
        state: SchedulerState,
        taskId: TaskId,
        paths: Map<TaskId, List<TaskId>>,
    ): String {
        val childLabel = childTitlesLabel(state, taskId)
        val path = paths[taskId]
        if (path == null) {
            return DEAD_TASK_ROW_PREFIX + childLabel.ifEmpty { state.tasks[taskId]?.title.orEmpty() }
        }
        val pathLabel = taskPathLabel(state, path)
        return if (childLabel.isNotEmpty()) "$pathLabel ($childLabel)" else pathLabel
    }

    /**
     * Task IDs eligible for "Change Task" on [cellId] while editing [text].
     * PRD §4 *Filtering* — "impossible IDs (already in the same list, or in the cell's ancestor path) are
     * hidden": tasks already in the cell's list ("sub-list") and tasks whose own sub-tree holds one of the
     * cell's ancestors (see [assignCollisionScope]) are dropped; sorts by path length, path label, child
     * titles (PRD §4). A task already recurring elsewhere under the same ancestor IS offered — that is
     * mirroring (Constraint 3), not a collision.
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
     * PRD §5: the tasks a sub-list's **priority-weight table** may take as an **optional row** while its add
     * row shows [text] — the identity menu of that row, exactly as [eligibleAssignTaskIds] is the identity
     * menu of a cell of the tree.
     *
     * The question a weight table asks is not the one a cell asks, so this is not that filter with a
     * different argument: an optional row is a task **somewhere under this list's own parent task** whose
     * share of that sub-tree the table is being asked to state. So the predicate is the one
     * `SetPriorityWeightTableRow` itself enforces — a live occurrence chain under the parent
     * ([RelativePriorityDomain.optionalTaskPath]) — asked here so the menu can never offer a row the intent
     * would then refuse. A task already in the table is left out either way: a **member** cell has its own
     * row with its own weight, and an existing **optional** row is already that row.
     *
     * [replacing] is the task the row being edited names TODAY, and it is exempt from "already in the
     * table" — it is the answer the row is already giving, so it has to stay a live one: the menu offers it
     * back (picking it is a no-op the reducer drops), and the row keeps ITS COLOUR while its own title sits
     * in the field. Without the exemption a row went colourless the instant its editor opened.
     *
     * Title matching, ordering and the exclusion of the root task are [eligibleAssignTaskIds]' own, so
     * the two menus read the same way (an exact title match, shortest path first).
     */
    fun eligibleWeightTableTaskIds(
        state: SchedulerState,
        listId: CellListId,
        text: String,
        replacing: TaskId? = null,
    ): List<TaskId> {
        val list = state.lists[listId] ?: return emptyList()
        val parentTask = parentTaskIdOfList(state, listId) ?: return emptyList()
        val members = list.cellIds.mapNotNull { state.cells[it]?.taskId }.toSet()
        return matchingUserTaskIds(state, text, shortestTaskTreePaths(state)).filter { candidate ->
            candidate != parentTask &&
                (
                    candidate == replacing ||
                        (candidate !in members && candidate !in list.optionalTaskIds)
                    ) &&
                RelativePriorityDomain.optionalTaskPath(state, listId, candidate).isNotEmpty()
        }
    }

    /**
     * All rows in the Change Task menu — "New task" first when there is a menu at all, **empty when there
     * is none** (PRD §4 *Appearance*). Impossible IDs (same list / ancestor path) are hidden, per PRD §4
     * Filtering.
     *
     * **The menu answers "which of the tasks with this title is this cell", so it appears only when that
     * question has more than one answer** — a *rival*: some other task the typed text names that this cell
     * could take. The cell's own **unchanged** task is not one: it is the answer already given, and a menu
     * whose only row was the path the user is looking straight at read as a task the tree no longer holds
     * (2026-09-23, account 3 — the row was the edited cell's own "writing", and its `(planning)` child
     * titles made it look like a second, dead one).
     *
     * **Unchanged** is the whole of it, and it is why this reads the edit session's own `treeBefore`
     * rather than just `state.cells[cellId]`: the hidden row is the one the cell arrived with **and** still
     * holds. Every other id the cell passes through mid-session is a choice the user made or the default
     * selection made for them, and it has to stay visible and selectable:
     *  - typing an existing title into an empty cell **reuses** that task (PRD §4 *Default selection*), and
     *    its row is the only thing that says so — and the only way back to a *new* id of the same title,
     *    which PRD §4 *Creation* exists to give (the release tree holds five tasks called "planning");
     *  - a row picked from this very menu must stay listed to render **selected** (purple), or
     *    [changeTaskMenuSelectedIndex] cannot match it and the menu would vanish under the click;
     *  - and **picking "New task" brings the abandoned id straight back as a row** with no rule of its own:
     *    the cell now points at the draft (excluded by [excludeTaskId]) so the previous task is unchanged no
     *    longer, and it is still there to be listed because [purgeOrphanTasks] keeps a task its timeline
     *    holds — the same predicate that opened the menu.
     *
     * The one thing left to offer when nothing rivals it is the **"New task"** row, and that is worth a menu
     * exactly when the unchanged id has a past to be abandoned ([taskHasTimelineHistory]): picking it
     * re-points the cell at a fresh task and leaves this one holding its records. So, with no rival:
     *  - the unchanged task has timeline history ⇒ the menu is the lone "New task" row (nothing is selected —
     *    [changeTaskMenuSelectedIndex] finds no row for the current task, which is the truth: the cell's task
     *    is not among the choices);
     *  - it has none ⇒ no menu at all, because minting a fresh id under the same title would change nothing
     *    a user could see.
     *
     * [namingSource] is the state the rows are **named from** when the tree being drawn is not the account's
     * own — `TaskTreeView`'s `colorSource` by another name, and for the same kind of reason.
     *
     * A row's path answers "**which** of the tasks with this title is this one", which is a fact about where
     * the task lives in the ACCOUNT, not about the tree this window happens to draw. Both projections re-root
     * the state: PRD §7's Search sub-trees root at a task's own sub-list and PRD §4's template shadows
     * [WellKnownIds.ROOT_LIST] with its own root, so read off the drawing every live task is pathless and
     * falls back to [childTitlesLabel] or its bare title — on the release account, **sixty-odd rows all
     * reading "planning"**, which is exactly the flattening the path exists to prevent (the same symptom the
     * stale `Task.childTaskIds` walk caused), and the row bound to the user's own "writing" task was named
     * "main / planning / writing" after its place in the TEMPLATE rather than "root / long term / socialize /
     * english / writing", where it lives.
     *
     * So a task the naming source's tree holds is named **exactly as that tree's own menu names it** — its
     * path AND the titles along it read from there, or the shared root cell would be titled by whichever tree
     * is drawn. Everything else is named from the drawn tree, which is where a template-owned task lives.
     * What is *offered* and what is *filtered out* stay questions about the drawn tree: the cell being edited
     * is one of its cells, and its siblings and ancestors are the ones the user can see.
     *
     * Two whole-tree walks, and only in a window drawing a projection; the account's own tree passes no
     * [namingSource] and still walks once (ADR 0009).
     */
    fun changeTaskMenuEntries(
        state: SchedulerState,
        cellId: CellId,
        draftText: String,
        excludeTaskId: TaskId? = null,
        namingSource: SchedulerState = state,
    ): List<ChangeTaskMenuEntry> {
        // One walk of the tree for the whole menu — the sort and every row's label read it (ADR 0009).
        val drawnPaths = shortestTaskTreePaths(state)
        val sourcePaths = if (namingSource === state) drawnPaths else shortestTaskTreePaths(namingSource)
        // The sort's first key is "has a path at all", so the merge is what keeps the rows in PRD §4's
        // order (shortest first) instead of dropping every live task into the pathless tail.
        val paths = if (namingSource === state) drawnPaths else drawnPaths + sourcePaths
        val matching = eligibleAssignTaskIds(state, cellId, draftText, paths, excludeTaskId)
        // PRD §4 *Appearance* — see above. Both halves are read off the DRAWN tree, like everything else the
        // menu offers or hides: it is that tree's cell the user is editing, and its own session (the §4
        // template window and the Search window each carry theirs).
        val sessionEntryTaskId =
            state.editSession
                ?.takeIf { it.cellId == cellId }
                ?.treeBefore?.cells?.get(cellId)?.taskId
        val unchangedTaskId = state.cells[cellId]?.taskId?.takeIf { it == sessionEntryTaskId }
        val rivals = matching.filter { it != unchangedTaskId }
        val rows =
            when {
                // A rival exists: every match is listed, the cell's own task included — that row is what
                // renders selected (purple), and dropping it would leave the menu unable to say which of
                // the identical-looking paths the cell is on.
                rivals.isNotEmpty() -> matching
                unchangedTaskId != null && taskHasTimelineHistory(state, unchangedTaskId) -> emptyList()
                else -> return emptyList()
            }
        return buildList {
            add(ChangeTaskMenuEntry(taskId = null, label = "New task"))
            for (taskId in rows) {
                add(
                    ChangeTaskMenuEntry(
                        taskId = taskId,
                        label =
                            if (taskId in sourcePaths) changeTaskMenuLabel(namingSource, taskId, sourcePaths)
                            else changeTaskMenuLabel(state, taskId, drawnPaths),
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
     * A task the app may **place at an instant**: a **leaf** (the scheduler places leaves, never a parent —
     * a parent is a grouping) that **still lives in the tree** (some cell points at it). That second half
     * excludes a *tombstone* — a task with no cell, kept alive only to keep its calendar panels / records
     * labelled (see [purgeOrphanTasks] and the reducer's tombstone handling). A tombstone can't be scheduled
     * (it has no cell, so [schedulableLeaves] skips it) and the user has removed it from the tree, so it
     * must not be offered back as a reusable task in the title / id menus.
     *
     * Asked by the two surfaces that name a task to put somewhere — PRD §8's calendar block editor and PRD
     * §7's task picker ([taskPickerEntries]) — and it is deliberately the same predicate
     * `SchedulerReducer.reduceForceTaskStart` enforces, so neither menu can offer a task the intent behind
     * it would then refuse.
     */
    fun isPlaceableTask(state: SchedulerState, taskId: TaskId): Boolean =
        isLeafTask(state, taskId) && taskHasCells(state, taskId)

    fun calendarTaskMenuEntries(
        state: SchedulerState,
        draftText: String,
        excludeTaskId: TaskId? = null,
    ): List<ChangeTaskMenuEntry> {
        val paths = shortestTaskTreePaths(state)
        val matching = matchingUserTaskIds(state, draftText, paths, excludeTaskId).filter { isPlaceableTask(state, it) }
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
     * Title suggestions restricted to titles that name at least one **placeable** task ([isPlaceableTask]) —
     * a parent task is never somewhere the app can put the now-line. Same ordering as [titleSuggestions].
     * The PRD §8 calendar block editor and the PRD §7 task picker both ask it.
     */
    fun placeableTaskTitleSuggestions(state: SchedulerState, input: String): List<String> =
        titleSuggestions(state, input).filter { title ->
            state.titleToTaskIds[title].orEmpty().any { isPlaceableTask(state, it) }
        }

    /** The placeable task a chosen title suggestion designates, if any — the calendar editor's and the
     * picker's one answer to "which task is this title". */
    fun placeableTaskIdForTitle(state: SchedulerState, title: String): TaskId? =
        state.titleToTaskIds[title].orEmpty().firstOrNull { isPlaceableTask(state, it) }

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
    /**
     * PRD §11/§14: **how the reminder behind a tag announces itself** — the row's own [AlertSettings].
     *
     * A tag whose reminder is not in [chores] still alerts, with [AlertSettings.REMINDER]: that is a
     * manually placed "add a reminder" tag whose id never became a manager row, or a row struck off while its
     * checked/pinned tag stayed. Falling silent there would be the app quietly dropping a reminder the user
     * placed by hand, which is the one tag they were most deliberate about.
     */
    fun alertForReminderTag(chores: List<ChoreEntry>, tagId: String): AlertSettings {
        val reminderId = reminderIdOfChorePanel(tagId) ?: return AlertSettings.REMINDER
        return chores.firstOrNull { it.id == reminderId }?.alert ?: AlertSettings.REMINDER
    }

    fun reminderIdOfChorePanel(panelId: String): String? = when {
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

    /**
     * **How a task is NAMED, wherever one is named.** The one answer to "what do I print for this task?",
     * so a blank title cannot read as `(untitled)` on one surface and as nothing at all on the next.
     *
     * A blank title is not an anomaly: emptying a cell *deletes* by blanking its task's title
     * (`docs/invariants/task-tree.md`), and a blanked task stays alive for as long as a panel or a record
     * still points at it — so every surface that can name a task can be handed one of these.
     *
     * The companion rule is that a named task is **drawn in its own colour**
     * ([org.example.project.ui.TaskTitleLabel], ADR 0013): the string and the tint are asked together at
     * every site, which is why they are two functions and not two conventions.
     */
    fun taskTitleLabel(title: String?): String = title.orEmpty().ifBlank { UNTITLED_LABEL }

    /**
     * The same answer for a task the state holds. The tree's root is the one task with a name of its own
     * ([ROOT_LABEL]) — it stands for the whole tree and the user never wrote its title.
     */
    fun taskTitleLabel(state: SchedulerState, taskId: TaskId?): String =
        when (taskId) {
            null -> UNTITLED_LABEL
            WellKnownIds.ROOT_TASK -> ROOT_LABEL
            else -> taskTitleLabel(state.tasks[taskId]?.title)
        }

    /** The tree's own placeholder for a task with no title. */
    const val UNTITLED_LABEL: String = "(untitled)"

    /** What the root is called where it is named at all — the relative-priority and category scope menus. */
    const val ROOT_LABEL: String = "root"

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
                setOf(WellKnownIds.ROOT_TASK) +
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
        // [WellKnownIds.ROOT_LIST] is seeded as well as [SchedulerState.rootListId]: every tree in the
        // account is rooted there (SchedulerState.empty, withTaskTreeLoaded, projectDefaultSubtree), so for
        // all of them this is the same list twice. It matters for the ONE projection that re-roots the state
        // elsewhere — an expanded Search row's sub-tree
        // ([org.example.project.scheduler.state.projectSearchSubtree]), rooted at a task's own sub-list: a
        // cell of the real root is reachable from neither that root nor a detached parent, and without this
        // seed the first edit boundary in that window would prune it out of the tree.
        // [WellKnownIds.ROOT_CELL_LIST] joins them for the same reason: the root cell is reachable from
        // neither [SchedulerState.rootListId] (it sits ABOVE it) nor a detached parent, so without this seed
        // the first edit boundary would prune the row the whole tree is drawn under — and, in a Search
        // sub-tree, would prune it out of the live tree.
        val queue =
            ArrayDeque(
                listOf(state.rootListId, WellKnownIds.ROOT_LIST, WellKnownIds.ROOT_CELL_LIST) + detachedRoots,
            )
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

    /**
     * PRD §7 *Search*: [taskId] renamed to [title] wherever the account holds it — the live tree and every
     * stored task tree carrying that id (a task id is one task across trees). A **task-level** rename, for the
     * one surface that renames a task it may have no cell for (a task cut from the tree and kept by the timeline,
     * or one only a stored tree holds). The title index of each tree it touches is rebuilt with it. The receiver
     * itself when nothing changes; the caller refuses a blank title (a blank title is how a CELL deletes).
     */
    fun withTaskRenamed(state: SchedulerState, taskId: TaskId, title: String): SchedulerState {
        var result = state
        result.tasks[taskId]?.takeIf { it.title != title }?.let { task ->
            val tasks = result.tasks + (taskId to task.copy(title = title))
            result = result.copy(tasks = tasks, titleToTaskIds = buildTitleIndex(tasks))
        }
        val trees =
            result.taskTrees.map { entry ->
                // The active entry's snapshot is stale by design: the live fields above ARE that tree.
                if (entry.id == result.activeTaskTreeId) return@map entry
                val task = entry.tree.tasks[taskId]?.takeIf { it.title != title } ?: return@map entry
                val tasks = entry.tree.tasks + (taskId to task.copy(title = title))
                entry.copy(tree = entry.tree.copy(tasks = tasks, titleToTaskIds = buildTitleIndex(tasks)))
            }
        if (trees != result.taskTrees) result = result.copy(taskTrees = trees)
        return result
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
        /**
         * PRD §13 **"copy task id"**: this node is a bare **reference** to a task — the id and nothing else,
         * the shape `Ctrl+C` and the cell menu write ([TASK_ID_REFERENCE_PREFIX]). It says nothing about a
         * title, a field or a child, so the only thing a paste can make of it is a **mirror**: an id naming
         * no live titled task, or one the target cell cannot hold, is a no-op rather than a task rebuilt
         * under the blank title that deletes (PRD §4).
         */
        val reference: Boolean = false,
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
     * PRD §4/§13 **"copy task id (ctrl c)"**: the whole of that clipboard shape — this prefix and the task
     * id, one line per task, and nothing else. It is a sentence no other application writes, which is the
     * point: a paste can tell it apart from text the user copied elsewhere, so the chord and the menu entry
     * can hand a cell an identity with no window and no confirmation. A title that happens to read like one
     * is escaped ([escapeTitleField]), exactly as a title that reads like an attribute line is.
     */
    const val TASK_ID_REFERENCE_PREFIX: String = "OmniApp task id: "

    /**
     * PRD §13 "deep copy": the depth a fresh account starts at, and the value the window's **reset** button
     * returns to. A depth of 1 is the cell alone (what the menu's plain "copy" takes), 2 is the cell and its
     * children. The live value is [SchedulerState.deepCopyMaxDepth] — one number for the whole account, which
     * the deep-copy window edits and §4's Ctrl+X then copies by without asking.
     */
    const val DEEP_COPY_DEFAULT_DEPTH: Int = 20

    /** The deep-copy depth the window (and therefore the account setting) accepts. */
    val DEEP_COPY_DEPTH_RANGE: IntRange = 1..999

    /**
     * PRD §4 `Ctrl+X`: the whole sub-tree, however deep it runs. The account's
     * [SchedulerState.deepCopyMaxDepth] is the **deep-copy window's** number — the chord asks nobody and
     * cuts nothing off. (`Ctrl+C` no longer copies a sub-tree at all: see [taskIdReferenceText].)
     */
    const val FULL_SUBTREE_DEPTH: Int = Int.MAX_VALUE

    /**
     * PRD §13 deep-copy window: **what** a copy carries, beside how deep it goes. Three switches, one
     * account-wide answer each ([SchedulerState.copyIncludeIds], [SchedulerState.copyPriorityTables],
     * [SchedulerState.copyIncludeText]) — the window edits them and every copy in the app then obeys them,
     * exactly as the depth setting already worked. Editing them in the window and finding `Ctrl+X` still
     * carrying what they turned off is the drift the one-answer-per-account rule exists to prevent. They say
     * what a copy of a TASK carries, so "copy task id" ([taskIdReferenceText]) obeys none of them.
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
     * PRD §5: `- category: <title>`, one line per category the task carries. One line each rather than a
     * comma-separated list, so a title holding a comma needs no second escaping rule.
     */
    private const val ATTR_CATEGORY: String = "category"

    /**
     * `side-dev/README.md` § *Restrictive Period*: one line per resilience override, `- resilience to <kind>:
     * <n> %`. The kind is spelled out because it is what a person reading the clipboard needs; the value is a
     * percentage because that is what the multiplier means — 0 % is forbidden, 100 % is unaffected.
     *
     * It is written in the kind's own name and READ through [PeriodKinds.migrateStoredKind], so a clipboard
     * cut before the 2026-09-12 rename still pastes onto the kind it meant.
     */
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
     * PRD §13 cell contextual menu "deep copy": the cells' tasks serialized to the same text
     * [copyTreeText] produces — so they paste back (Ctrl+V) with their schedule unit, their text, their
     * no-screen switch, their minimum time and their weight row restored.
     *
     * [maxDepth] is how many levels are taken, each cell itself counting as the first: 1 is the cells alone
     * (what the menu's "copy" took before it became "copy task id"), the deep-copy window's own number is
     * anything above, and [FULL_SUBTREE_DEPTH] is §4's Ctrl+X. Empty when nothing in [cellIds] holds a titled task, or when [maxDepth] is below 1 (there is
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
     * PRD §4/§13 **"copy task id"** — the cell menu's entry and `Ctrl+C`, which are one gesture: [cellIds]'
     * tasks as the bare [TASK_ID_REFERENCE_PREFIX] reference lines [parseTreeText] reads back. One line per
     * DISTINCT task, in the cells' own order, so a block that mirrors one task twice names it once. Empty
     * when nothing in [cellIds] holds a task the app itself minted — the copy is then a no-op.
     *
     * The deep-copy window's three switches deliberately do **not** apply: they say what a copy of a *task*
     * carries, and this copy is the identity alone (`copyIncludeIds` off would leave nothing to write).
     */
    fun taskIdReferenceText(state: SchedulerState, cellIds: List<CellId>): String =
        cellIds.filter { isPopulated(state, it) }
            .mapNotNull { state.cells[it]?.taskId }
            .filter { isUserTaskId(it) }
            .distinct()
            .joinToString("\n") { TASK_ID_REFERENCE_PREFIX + it.value }

    /**
     * PRD §13: the cells a right-click's "copy task id" / "deep copy" acts on. Right-clicking **inside** a
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
     * PRD §4 Cut: the selected cells' subtrees serialized to the app's clipboard text (see
     * [renderCopiedNodes] for the shape). Uses the consecutive selection block when there is one, otherwise
     * the main selection. Empty when nothing populated is selected.
     *
     * Ctrl+X takes the **entire** sub-tree and asks nothing: [maxDepth] defaults to [FULL_SUBTREE_DEPTH].
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
     * the main selection alone. The id copy, the cut and the menu all need the same list, so they read it
     * from here.
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
        // PRD §4/§13 "copy task id" first: a payload of bare id references is not a tree at all, and its
        // nodes carry no title the tree parse below could build one from. The shape is decided BEFORE it is
        // read, so a reference naming an id the app never mints is a no-op — never a task titled after the
        // reference line, which is what falling through to the title-tree parse would have made of it.
        if (isTaskIdReferenceText(lines)) return parseTaskIdReferences(lines)
        // A form-feed section line is the pre-1.6.0 shape (tree + title-keyed appendices). Nothing writes
        // it any more, but a clipboard filled by an older build must still paste.
        if (lines.any { it == COPY_SECTION_SEPARATOR }) return parseLegacyTreeText(lines)
        return parseReadableTreeText(lines)
    }

    /**
     * PRD §4/§13: is [lines] the **"copy task id"** shape at all — every non-blank line a
     * [TASK_ID_REFERENCE_PREFIX] line, and at least one of them? Asked before the ids are read, so that a
     * payload the app plainly wrote is answered by [parseTaskIdReferences] alone and never falls through to
     * the title-tree parse below (which would turn a malformed one into a task *titled* after it).
     */
    private fun isTaskIdReferenceText(lines: List<String>): Boolean {
        val nonBlank = lines.map { it.trim() }.filter { it.isNotEmpty() }
        return nonBlank.isNotEmpty() && nonBlank.all { it.startsWith(TASK_ID_REFERENCE_PREFIX) }
    }

    /**
     * PRD §4/§13 **"copy task id"**: one bare reference node per line — and null on an id [isUserTaskId]
     * refuses, which keeps a paste from ever pointing a cell at the tree's own `task/root`.
     */
    private fun parseTaskIdReferences(lines: List<String>): List<CopiedNode>? =
        lines.map { it.trim() }.filter { it.isNotEmpty() }.map { line ->
            val taskId = TaskId(line.removePrefix(TASK_ID_REFERENCE_PREFIX).trim())
            if (!isUserTaskId(taskId)) return null
            CopiedNode(title = "", children = emptyList(), taskId = taskId, reference = true)
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
            // must stay a no-op rather than build a task under the id the tree reserves (the root).
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
                // Read through [PeriodKinds.migrateStoredKind], exactly as a stored payload's resilience map
                // is: a clipboard written before the 2026-09-12 rename spells the kind `no on-screen task`,
                // and left un-migrated that pastes as a USER-DEFINED kind of that name — whose default is 0,
                // so the `0 %` that made the task on-screen is read as redundant and DROPPED, and the task
                // comes back off-screen. The one reading of a stored kind name, here too.
                val kind = PeriodKinds.migrateStoredKind(name.removePrefix("$ATTR_RESILIENCE "))
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
        // A title that reads like an attribute line, like the summary header, or like a "copy task id"
        // reference is escaped — the three shapes the parser would otherwise read instead of a title.
        val ambiguous =
            escaped.startsWith(ATTR_MARKER) ||
                escaped == COPIED_TASKS_SECTION_HEADER ||
                escaped.startsWith(TASK_ID_REFERENCE_PREFIX)
        return if (ambiguous) "\\$escaped" else escaped
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
