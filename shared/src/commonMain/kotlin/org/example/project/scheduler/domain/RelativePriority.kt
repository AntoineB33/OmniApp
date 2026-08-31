package org.example.project.scheduler.domain

import org.example.project.scheduler.model.CellId
import org.example.project.scheduler.model.CellListId
import org.example.project.scheduler.model.RelativePriorityPinKey
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.model.WellKnownIds
import org.example.project.scheduler.state.SchedulerState
import kotlin.math.abs

/**
 * PRD §5 the **relative priority** window (right-click on a cell's absolute priority percentage).
 *
 * The absolute percentage a cell shows is the priority of its task relative to the **root**. This object
 * generalizes that to any ancestor `t_r`: the priority of a task `t` *within* `t_r`'s sub-tree, which is
 * the sum, over every occurrence of `t` under `t_r`, of the product of the **local shares** along the
 * chain of cells from just under `t_r` down to that occurrence. With `t_r == root` it reduces exactly to
 * [SchedulerDomain.absoluteTaskPriorities] — that identity is what makes the window's default view the
 * same number the tree already displays, and it is pinned by `RelativePriorityTest`.
 *
 * Editing the number scales the **percentages** of every cell the window shows by one common factor `f`
 * (the user's rule: "they all equally change"), except the cells the user has **pinned**, which hold their
 * percentage. A chain of `k` unpinned cells therefore moves by roughly `f^k`, and `f` itself is solved by
 * bisection — the priority is non-decreasing in it. Only *roughly*, because a cell cannot always take the
 * share the factor asks of it (an only child holds 100% of its parent however its weight is set), which is
 * why the solve measures the scaled tree instead of trusting the closed form.
 */
object RelativePriorityDomain {

    /** Largest share a single cell may be given; 1.0 is unreachable (it would need an infinite weight). */
    private const val MAX_SHARE = 0.999_999

    /** Convergence tolerance / iteration cap of the two numeric solves (both are 1-D and monotone). */
    private const val EPSILON = 1e-12
    private const val MAX_ITERATIONS = 200

    // ----- The tree walk ------------------------------------------------------------------------

    /**
     * The share [cellId] holds **within its own sub-list**: its blended priority weight over the sum of
     * the sub-list's column weights (PRD §5). This is the factor the parent's priority is multiplied by,
     * so a chain of shares multiplied together is the sub-tree's priority contribution.
     */
    fun cellShare(state: SchedulerState, cellId: CellId): Double {
        val cell = state.cells[cellId] ?: return 0.0
        val total = listWeightSum(state, cell.parentListId)
        if (total <= 0.0) return 0.0
        return SchedulerDomain.cellPriorityWeight(state, cellId) / total
    }

    /**
     * Σ of the populated cells' weights in [listId]. It collapses to the sum of the columns' absolute
     * weights over the columns that have a non-zero total, since each column's values sum back to its own
     * total (the same reduction [SchedulerDomain.absoluteTaskPriorities] makes).
     */
    private fun listWeightSum(state: SchedulerState, listId: CellListId): Double {
        val list = state.lists[listId] ?: return 0.0
        val absW = SchedulerDomain.columnAbsoluteWeights(list.weightColumns)
        val populated = list.cellIds.filter { SchedulerDomain.isPopulatedCell(state, it) }
        var sum = 0.0
        for (c in absW.indices) {
            val colSum = populated.sumOf { id ->
                state.cells[id]?.priorityWeights?.getOrElse(c) { SchedulerDomain.defaultWeightAt(c) } ?: 0.0
            }
            if (colSum > 0.0) sum += absW[c]
        }
        return sum
    }

    /** The cell holding [cellId]'s parent task, or null when [cellId] sits directly in the root list. */
    private fun parentCellOf(state: SchedulerState, cellId: CellId): CellId? {
        val cell = state.cells[cellId] ?: return null
        return state.lists[cell.parentListId]?.parentCellId
    }

    /** [cellId]'s ancestor cells, outermost first (the root-list cell) down to its immediate parent. */
    fun ancestorCells(state: SchedulerState, cellId: CellId): List<CellId> {
        val chain = ArrayDeque<CellId>()
        var current = parentCellOf(state, cellId)
        val guard = HashSet<CellId>()
        while (current != null && guard.add(current)) {
            chain.addFirst(current)
            current = parentCellOf(state, current)
        }
        return chain.toList()
    }

    /**
     * The `t_r` choices of the window's drop-down for the cell that was right-clicked: **root first**
     * (which makes the window show the absolute percentage the cell already displays), then this cell's
     * ancestor tasks from the root-most one down to its closest parent.
     */
    fun relativeToOptions(state: SchedulerState, cellId: CellId): List<TaskId> {
        val result = mutableListOf(WellKnownIds.MAIN_TASK)
        for (ancestor in ancestorCells(state, cellId)) {
            val taskId = state.cells[ancestor]?.taskId ?: continue
            if (taskId == WellKnownIds.MAIN_TASK || taskId in result) continue
            result.add(taskId)
        }
        return result
    }

    /**
     * One chain per occurrence of [taskId] **under** [relativeTo]: the cells from the one sitting directly
     * in [relativeTo]'s sub-list down to the occurrence itself. An occurrence outside [relativeTo]'s
     * sub-tree contributes no chain (its priority is not part of [relativeTo]'s), which is exactly why the
     * window lists one tree per chain rather than one per cell.
     *
     * Ordered like [SchedulerDomain.sortOccurrences] (shallowest first, then by id) so the window's rows
     * are stable across edits.
     */
    fun occurrenceChains(
        state: SchedulerState,
        taskId: TaskId,
        relativeTo: TaskId,
    ): List<List<CellId>> {
        if (taskId == relativeTo) return emptyList()
        val occurrences = state.cells.values
            .filter { it.taskId == taskId && SchedulerDomain.isPopulatedCell(state, it.id) }
            .map { it.id }
        val chains = mutableListOf<List<CellId>>()
        for (occurrence in SchedulerDomain.sortOccurrences(state, occurrences)) {
            val chain = ArrayDeque(listOf(occurrence))
            var current = occurrence
            var reached = false
            val guard = HashSet<CellId>()
            while (guard.add(current)) {
                val parentCell = parentCellOf(state, current)
                if (parentCell == null) {
                    // Walked out of the top of the tree: the enclosing task is the root/MAIN task.
                    reached = relativeTo == WellKnownIds.MAIN_TASK
                    break
                }
                if (state.cells[parentCell]?.taskId == relativeTo) {
                    reached = true
                    break
                }
                chain.addFirst(parentCell)
                current = parentCell
            }
            if (reached) chains.add(chain.toList())
        }
        return chains
    }

    /** The first stable path from [listId]'s parent task to an optional [taskId] row. */
    fun optionalTaskPath(state: SchedulerState, listId: CellListId, taskId: TaskId): List<CellId> {
        val parentTask = SchedulerDomain.parentTaskIdOfList(state, listId) ?: return emptyList()
        return occurrenceChains(state, taskId, parentTask).minWithOrNull(
            compareBy<List<CellId>> { it.size }.thenBy { it.joinToString("/") { cell -> cell.value } },
        ).orEmpty()
    }

    /** Scale one weight column along an optional task's path, except cells pinned for that task and parent. */
    fun scaleOptionalTaskPath(
        state: SchedulerState,
        listId: CellListId,
        taskId: TaskId,
        column: Int,
        factor: Double,
    ): SchedulerState {
        if (column < 0 || !factor.isFinite() || factor < 0.0) return state
        val path = optionalTaskPath(state, listId, taskId)
        val parentTask = SchedulerDomain.parentTaskIdOfList(state, listId) ?: return state
        val pinned = state.relativePriorityPins[RelativePriorityPinKey(taskId, parentTask)].orEmpty()
        val cells = state.cells.toMutableMap()
        for (cellId in path) {
            if (cellId in pinned) continue
            val cell = cells[cellId] ?: continue
            val weights = cell.priorityWeights.toMutableList()
            while (weights.size <= column) weights += SchedulerDomain.defaultWeightAt(weights.size)
            weights[column] *= factor
            cells[cellId] = cell.copy(priorityWeights = weights)
        }
        return state.copy(cells = cells)
    }

    /**
     * The priority of [taskId] relative to [relativeTo], as a fraction in `[0,1]`. With
     * `relativeTo == MAIN` this is the task's absolute priority percentage (PRD §5).
     */
    fun relativePriority(state: SchedulerState, taskId: TaskId, relativeTo: TaskId): Double =
        occurrenceChains(state, taskId, relativeTo).sumOf { chain -> chainProduct(state, chain) }

    private fun chainProduct(state: SchedulerState, chain: List<CellId>): Double {
        var product = 1.0
        for (cellId in chain) product *= cellShare(state, cellId)
        return product
    }

    // ----- Editing ------------------------------------------------------------------------------

    /**
     * Set the priority of [taskId] relative to [relativeTo] to [target] (a fraction in `[0,1]`), by
     * scaling the percentage of every cell of every occurrence chain by one common factor — except the
     * cells in [pinned], which keep theirs. Returns the state unchanged when nothing can move (no chain,
     * every cell pinned) or when the target is already met.
     *
     * Two cells of one sub-list can both sit on chains (two occurrences under two siblings), and then one
     * common factor is arithmetically impossible — the shares of one list sum to 1, so they cannot all grow.
     * The solve stays well defined there because it targets the achieved priority: the requested shares are
     * approached as closely as the list allows and the factor is chosen so the total still lands on [target].
     *
     * The factor is solved against the **achieved** priority rather than against `Σ f^k · product`,
     * because not every cell can actually take the share the formula asks of it: an only child holds 100%
     * of its parent whatever its weight, and no cell can reach exactly 100%. Measuring the scaled tree
     * keeps the solve exact where the cells can move and best-effort where they cannot, instead of
     * silently landing somewhere else.
     */
    fun setRelativePriority(
        state: SchedulerState,
        taskId: TaskId,
        relativeTo: TaskId,
        target: Double,
        pinned: Set<CellId>,
    ): SchedulerState {
        val chains = occurrenceChains(state, taskId, relativeTo)
        if (chains.isEmpty()) return state
        // A cell shared by two chains is scaled once; a cell with no share at all cannot be scaled into one.
        val onChains = chains.flatten().distinct().filter { cellShare(state, it) > 0.0 }
        val movable = onChains.filter { it !in pinned }
        if (movable.isEmpty()) return state
        // Grouped by sub-list: the cells of one list share a denominator, so they are solved together (a
        // chain never holds two cells of one list, but two chains may). A **pinned** cell of such a list is
        // solved too, with its CURRENT share as the target: holding a percentage while a sibling grows is
        // not "leave the weight alone", it is "raise the weight enough to keep the share".
        val byList = onChains
            .groupBy { state.cells[it]!!.parentListId }
            .filterValues { cellIds -> cellIds.any { it in movable } }

        fun scaled(factor: Double): SchedulerState {
            var result = state
            for ((listId, cellIds) in byList) {
                val targets = cellIds.associateWith {
                    val share = cellShare(state, it)
                    if (it in pinned) share else (share * factor).coerceIn(0.0, MAX_SHARE)
                }
                result = setListShares(result, listId, targets)
            }
            return result
        }

        fun valueAt(factor: Double): Double = relativePriority(scaled(factor), taskId, relativeTo)

        if (abs(valueAt(1.0) - target) <= EPSILON) return state
        // Bracket the factor: 0 shrinks every movable cell as far as it goes, and the ceiling is grown
        // until it covers the target (a share tends to its column's own ceiling, not to 1).
        var hi = 1.0
        var guard = 0
        while (valueAt(hi) < target && guard++ < 40) hi *= 2.0
        val factor = solveMonotone(lo = 0.0, hi = hi, target = target, f = ::valueAt)
        if (factor == 1.0) return state
        return scaled(factor)
    }

    /**
     * Give each cell of [targets] the share it maps to within [listId], leaving the list's other cells'
     * weights untouched. A cell's share is monotone in its own weight but the cells of one list share a
     * denominator, so the cells are solved one at a time and the pass is repeated until it settles.
     */
    private fun setListShares(
        state: SchedulerState,
        listId: CellListId,
        targets: Map<CellId, Double>,
    ): SchedulerState {
        var result = state
        repeat(MAX_ITERATIONS) {
            var worst = 0.0
            for ((cellId, target) in targets) {
                val before = cellShare(result, cellId)
                result = setCellShare(result, cellId, target)
                val after = cellShare(result, cellId)
                // A cell that cannot move at all (all-zero weights) must not keep the loop spinning.
                if (after != before || abs(after - target) <= EPSILON) {
                    worst = maxOf(worst, abs(after - target))
                }
            }
            if (worst <= EPSILON) return result
        }
        return result
    }

    /** Scale [cellId]'s weight vector so its share of its sub-list becomes [target] (siblings fixed). */
    private fun setCellShare(state: SchedulerState, cellId: CellId, target: Double): SchedulerState {
        val cell = state.cells[cellId] ?: return state
        if (cell.priorityWeights.all { it <= 0.0 }) return state

        fun shareWith(scale: Double): Double {
            val scaled = cell.copy(priorityWeights = cell.priorityWeights.map { it * scale })
            return cellShare(state.copy(cells = state.cells + (cellId to scaled)), cellId)
        }

        // Grow the bracket until it covers the target (the share tends to its column ceiling, not to 1).
        var hi = 1.0
        var guard = 0
        while (shareWith(hi) < target && guard++ < 60) hi *= 2.0
        val scale = solveMonotone(lo = 0.0, hi = hi, target = target, f = ::shareWith)
        val scaled = cell.copy(priorityWeights = cell.priorityWeights.map { it * scale })
        return state.copy(cells = state.cells + (cellId to scaled))
    }

    /** Bisection on a non-decreasing [f] over `[lo, hi]`; returns the argument whose value is [target]. */
    private fun solveMonotone(lo: Double, hi: Double, target: Double, f: (Double) -> Double): Double {
        var low = lo
        var high = hi
        if (f(low) >= target) return low
        if (f(high) <= target) return high
        repeat(MAX_ITERATIONS) {
            val mid = (low + high) / 2.0
            if (mid == low || mid == high) return mid
            if (f(mid) < target) low = mid else high = mid
        }
        return (low + high) / 2.0
    }

    /** `base^exponent` for a small non-negative integer exponent (`0^0 == 1`: a fully pinned chain is fixed). */
    private fun powInt(base: Double, exponent: Int): Double {
        var result = 1.0
        repeat(exponent) { result *= base }
        return result
    }
}
