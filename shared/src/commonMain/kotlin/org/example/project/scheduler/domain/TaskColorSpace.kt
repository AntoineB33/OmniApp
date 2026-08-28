package org.example.project.scheduler.domain

import kotlin.math.floor
import org.example.project.scheduler.model.CellListId
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.state.SchedulerState

/**
 * **The task colours.** Every task in the tree is given its own colour, and the colours are not arbitrary:
 * they are a partition of one **colour space** — the hue circle — so a task's colour says both *that it is
 * not any other task* and *where in the tree it lives*.
 *
 * The rule, in three lines:
 *
 * 1. **The tasks with an empty sub-tree come first, and they are spread as far apart as they can be.** They
 *    are the tasks the user actually works on and they are the many, so they own the circle: `n` of them
 *    take the `n` hues `i/n`, which is exactly the arrangement maximising the smallest distance between any
 *    two of them. Their ORDER around the circle is the tree's own depth-first order, which is what makes the
 *    other half of the rule true — **the closer two tasks are in the tree, the closer their colours**: a
 *    branch is a contiguous run of the circle, so it reads as one family of shades.
 * 2. **Then every other task takes what is left, as far from all the others as it can get.** Taken one at a
 *    time, most constrained first, each parent lands at the point of its own sub-tree's arc that is furthest
 *    from every colour already given out — the middle of the widest gap it can reach. So a parent is never
 *    the colour of one of its own children (the collision the old *average of my own arc* rule had by
 *    construction), and it still sits among its descendants rather than across the circle from them.
 * 3. **Where several answers are equally good, the one closest to the PREVIOUS answer wins.** The circle has
 *    no privileged origin and a gap has two equally distant halves, so ties are the normal case, not the
 *    exception; broken arbitrarily they would repaint the whole tree on every edit. Both the ring's rotation
 *    and each parent's pick are settled by "which of the best answers moves the least from where it was".
 *    That is what [previous] is for, and it is why the caller feeds each answer back into the next call —
 *    see `org.example.project.ui.TaskHueMemo`, which also carries the debounce.
 *
 * Two things follow from the tree's own shape and are deliberate:
 *
 * - **The walk visits each LIST once, and a colour belongs to the TASK.** A sub-list belongs to the task id
 *   (CLAUDE.md *A sub-list belongs to the task id, not to the cell*), so a mirrored task is one list under
 *   many parents; re-walking it per occurrence is exponential, and would also leave the task with as many
 *   colours as it has cells — while the calendar panel, which knows only the task, could pick none of them.
 *   So the **first** occurrence reached names the task and walks its sub-tree, and a later one adds nothing.
 *   The visited set doubles as the cycle guard.
 * - **Only populated cells take part** ([SchedulerDomain.isPopulatedCell]) — an empty placeholder row and a
 *   blank-titled (deleted) task are not tasks the user has, so they take no colour and no room.
 *
 * Pure and free of any notion of what a colour looks like: it answers with the **hue fraction** in `[0, 1)`
 * and a depth, so it can be unit-tested as arithmetic. Turning that into something to paint with is the UI's
 * business — see `org.example.project.ui.TaskPalette`.
 */
object TaskColorSpace {

    /**
     * Where in the colour space one task sits: its [hue] fraction in `[0, 1)`, and the [depth] of the row
     * that put it there (0 for a root-list row).
     *
     * The depth is **not** what tells two tasks apart any more — the placement does that, and a parent is
     * now deliberately kept off every hue its sub-tree already holds. It is carried because a branch that
     * darkens a step per level reads as the family it is, and because a leaf and the parent placed beside it
     * are, by design, neighbouring hues. The palette spends it on lightness alone, leaving the hue partition
     * untouched.
     */
    data class TaskHue(val hue: Double, val depth: Int)

    /**
     * Where every task the tree colours sits in the colour space.
     *
     * Keyed by [TaskId] because a colour belongs to the task, not to the cell — see the class note.
     *
     * [previous] is the answer this one should stay close to: wherever the rule leaves several equally good
     * placements, the one nearest to where that task already was is taken. An empty map asks for the
     * canonical answer (the ring anchored at hue 0), which is what a cold start gets. Feeding an answer back
     * into itself is a fixed point: `hues(state, hues(state, p)) == hues(state, p)`.
     */
    fun hues(
        state: SchedulerState,
        previous: Map<TaskId, TaskHue> = emptyMap(),
    ): Map<TaskId, TaskHue> {
        val tree = walk(state)
        if (tree.order.isEmpty()) return emptyMap()

        // The ring: the childless tasks, in the order the walk met them. A tree with none at all is a cycle
        // the tree's own constraints forbid; everything rides the ring there, so something is still coloured.
        val ring = tree.order.filter { tree.children[it].isNullOrEmpty() }.ifEmpty { tree.order }
        val ringIndex = ring.withIndex().associate { (i, taskId) -> taskId to i }
        val n = ring.size
        val offset = chooseRotation(ring, n, previous)

        val hues = LinkedHashMap<TaskId, TaskHue>()
        val placed = ArrayList<Double>(tree.order.size)
        for ((i, taskId) in ring.withIndex()) {
            val hue = wrap(offset + i.toDouble() / n)
            hues[taskId] = TaskHue(hue, tree.depth.getValue(taskId))
            placed.add(hue)
        }
        placed.sort()

        // Everything else, most constrained first: a task whose sub-tree covers a narrow arc has the least
        // room to move, so it picks before the ones that can go anywhere. The walk's own order breaks the
        // remaining ties, so the sequence is a function of the tree and nothing else.
        val arcs = SubtreeArcs(tree, ringIndex, n, offset)
        val walkRank = tree.order.withIndex().associate { (i, taskId) -> taskId to i }
        val rest =
            tree.order
                .filter { it !in hues }
                .map { it to arcs.of(it) }
                .sortedWith(compareBy({ it.second.length }, { walkRank.getValue(it.first) }))
        for ((taskId, arc) in rest) {
            val hue = placeFurthest(arc, placed, previous[taskId]?.hue)
            hues[taskId] = TaskHue(hue, tree.depth.getValue(taskId))
            insertSorted(placed, hue)
        }
        return hues
    }

    // ---------------------------------------------------------------------------------------------------
    // The tree, walked once
    // ---------------------------------------------------------------------------------------------------

    /** The one traversal: every populated task, met once, with the depth and the children it was met with. */
    private class Tree(
        val order: List<TaskId>,
        val depth: Map<TaskId, Int>,
        val children: Map<TaskId, List<TaskId>>,
    )

    private fun walk(state: SchedulerState): Tree {
        val order = ArrayList<TaskId>()
        val depth = HashMap<TaskId, Int>()
        val children = HashMap<TaskId, List<TaskId>>()

        fun populatedTasksOf(listId: CellListId?): List<TaskId> =
            listId?.let { state.lists[it] }?.cellIds.orEmpty().mapNotNull { cellId ->
                state.cells[cellId]?.taskId?.takeIf { SchedulerDomain.isPopulatedCell(state, cellId) }
            }

        fun visit(taskId: TaskId, at: Int) {
            // A task reached twice is a mirror: it keeps the place its first occurrence gave it, and its
            // sub-list is not walked again.
            if (taskId in depth) return
            depth[taskId] = at
            order.add(taskId)
            val kids = populatedTasksOf(state.tasks[taskId]?.childListId)
            children[taskId] = kids
            kids.forEach { visit(it, at + 1) }
        }

        populatedTasksOf(state.rootListId).forEach { visit(it, 0) }
        return Tree(order, depth, children)
    }

    // ---------------------------------------------------------------------------------------------------
    // A sub-tree's arc — "where in the circle this branch lives"
    // ---------------------------------------------------------------------------------------------------

    /** A stretch of the circle: everything from [start], going forward, for [length] (1.0 = all of it). */
    private data class Arc(val start: Double, val length: Double)

    /**
     * The arc a task's sub-tree occupies: the **smallest** stretch of the circle holding every ring member
     * below it, widened by half a ring step at each end so the branch owns the ground halfway to its
     * neighbours. That half-step is what gives the parent of a single leaf somewhere to go — without it its
     * arc would be that leaf's own point and the two would have to share a colour.
     *
     * Measured in ring indices, so the covering arc is exact integer arithmetic and the rotation is applied
     * once, at the end. Memoised per task, which is what keeps a mirrored sub-tree to one walk.
     */
    private class SubtreeArcs(
        private val tree: Tree,
        private val ringIndex: Map<TaskId, Int>,
        private val n: Int,
        private val offset: Double,
    ) {
        private val memo = HashMap<TaskId, List<Int>>()
        private val walking = HashSet<TaskId>()

        private fun indices(taskId: TaskId): List<Int> {
            memo[taskId]?.let { return it }
            if (!walking.add(taskId)) return emptyList()
            val own = ringIndex[taskId]
            val result =
                if (own != null) listOf(own)
                else tree.children[taskId].orEmpty().flatMap { indices(it) }.distinct().sorted()
            walking.remove(taskId)
            memo[taskId] = result
            return result
        }

        fun of(taskId: TaskId): Arc {
            val idx = indices(taskId)
            if (idx.isEmpty()) return Arc(0.0, 1.0)
            // The widest gap between consecutive members is the part of the circle the branch does NOT
            // occupy; the arc is everything else. Ties take the first gap, so the answer is deterministic.
            var widestAt = 0
            var widestGap = -1
            for (j in idx.indices) {
                val gap =
                    if (idx.size == 1) {
                        n
                    } else {
                        val raw = (idx[(j + 1) % idx.size] - idx[j] + n) % n
                        if (raw == 0) n else raw
                    }
                if (gap > widestGap) {
                    widestGap = gap
                    widestAt = j
                }
            }
            val startIndex = idx[(widestAt + 1) % idx.size]
            val half = 0.5 / n
            val length = ((n - widestGap).toDouble() / n + 2 * half).coerceAtMost(1.0)
            return Arc(wrap(offset + startIndex.toDouble() / n - half), length)
        }
    }

    // ---------------------------------------------------------------------------------------------------
    // Placement
    // ---------------------------------------------------------------------------------------------------

    /**
     * The point of [arc] furthest from every hue in [placed] — and, among the points that tie for that, the
     * one nearest [prior] (this task's colour in the previous answer).
     *
     * The distance to the nearest taken hue is piecewise linear along the arc, so its maxima are exactly the
     * **midpoints of the gaps** between consecutive taken hues, plus the arc's own two ends. Enumerating
     * those is the whole search — there is nothing else to try, and no need to sample.
     */
    private fun placeFurthest(arc: Arc, placed: List<Double>, prior: Double?): Double {
        if (placed.isEmpty()) return arc.start
        val candidates = ArrayList<Pair<Double, Double>>(placed.size + 2)
        for (j in placed.indices) {
            val from = placed[j]
            val gap = if (placed.size == 1) 1.0 else wrapPositive(placed[(j + 1) % placed.size] - from)
            val mid = wrap(from + gap / 2)
            if (inArc(mid, arc)) candidates.add(mid to gap / 2)
        }
        val end = wrap(arc.start + arc.length)
        candidates.add(arc.start to nearest(arc.start, placed))
        candidates.add(end to nearest(end, placed))

        val best = candidates.maxOf { it.second }
        return candidates
            .filter { it.second >= best - EPSILON }
            .minWith(
                compareBy(
                    { prior?.let { p -> circularDistance(it.first, p) } ?: 0.0 },
                    { it.first },
                ),
            )
            .first
    }

    /**
     * The rotation to hang the ring on. Every rotation spreads the ring equally well, so the circle has no
     * origin of its own and the choice is free — and that freedom is exactly what would repaint the whole
     * tree on every edit if it were not spent on staying put. It goes to the rotation that moves the ring's
     * members least from where [previous] had them; with nothing to remember, hue 0.
     *
     * A best rotation always puts at least one member exactly back where it was, so the offsets that do that
     * are the only ones worth trying.
     */
    private fun chooseRotation(ring: List<TaskId>, n: Int, previous: Map<TaskId, TaskHue>): Double {
        val anchors =
            ring.withIndex().mapNotNull { (i, taskId) ->
                previous[taskId]?.let { wrap(it.hue - i.toDouble() / n) }
            }
        if (anchors.isEmpty()) return 0.0
        var bestOffset = 0.0
        var bestCost = Double.MAX_VALUE
        for (candidate in (anchors + 0.0).sorted()) {
            var cost = 0.0
            for ((i, taskId) in ring.withIndex()) {
                val was = previous[taskId]?.hue ?: continue
                cost += circularDistance(wrap(candidate + i.toDouble() / n), was)
            }
            if (cost < bestCost - EPSILON) {
                bestCost = cost
                bestOffset = candidate
            }
        }
        return bestOffset
    }

    // ---------------------------------------------------------------------------------------------------
    // Circle arithmetic
    // ---------------------------------------------------------------------------------------------------

    private const val EPSILON = 1e-9

    private fun wrap(x: Double): Double = x - floor(x)

    private fun wrapPositive(x: Double): Double = wrap(x).let { if (it <= 0.0) 1.0 else it }

    private fun circularDistance(a: Double, b: Double): Double {
        val d = wrap(a - b)
        return if (d > 0.5) 1.0 - d else d
    }

    private fun nearest(x: Double, placed: List<Double>): Double = placed.minOf { circularDistance(x, it) }

    private fun inArc(x: Double, arc: Arc): Boolean =
        arc.length >= 1.0 - EPSILON || wrap(x - arc.start) <= arc.length + EPSILON

    private fun insertSorted(placed: MutableList<Double>, hue: Double) {
        val at = placed.indexOfFirst { it > hue }
        if (at < 0) placed.add(hue) else placed.add(at, hue)
    }
}
