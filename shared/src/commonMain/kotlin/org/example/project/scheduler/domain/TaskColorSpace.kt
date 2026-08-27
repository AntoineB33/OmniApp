package org.example.project.scheduler.domain

import org.example.project.scheduler.model.CellListId
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.state.SchedulerState

/**
 * **The task colours.** Every task in the tree is given its own colour, and the colours are not arbitrary:
 * they are a partition of one **colour space** — the hue circle — handed down the tree, so a task's colour
 * says where in the tree it lives. Siblings are neighbours on the circle; a sub-tree occupies one contiguous
 * arc, so a whole branch reads as one family of shades.
 *
 * The rule, in three lines:
 *
 * 1. the root list owns the **whole** circle, `[0, 1)`;
 * 2. a list's arc is split between its cells **in proportion to how many childless tasks each one's sub-tree
 *    holds** — not equally, so a branch that is ten leaves wide gets ten times the room of a bare leaf and
 *    every leaf in the tree ends up with roughly the same slice of the circle;
 * 3. a cell's colour is the **average** of its own arc (its midpoint), and that same arc is the colour space
 *    its sub-list then divides.
 *
 * Two things follow from the tree's own shape and are deliberate:
 *
 * - **The walk visits each LIST once, and a colour belongs to the TASK.** A sub-list belongs to the task id
 *   (CLAUDE.md *A sub-list belongs to the task id, not to the cell*), so a mirrored task is one list under
 *   many parents; re-walking it per occurrence is exponential, and would also leave the task with as many
 *   colours as it has cells — while the calendar panel, which knows only the task, could pick none of them.
 *   So the **first** occurrence reached colours the task and its sub-tree, and a later one keeps its share of
 *   its own parent's arc (it is part of that branch's width) without re-dividing it. That set of
 *   already-coloured tasks doubles as the cycle guard.
 * - **Only populated cells take part** ([SchedulerDomain.isPopulatedCell]) — an empty placeholder row and a
 *   blank-titled (deleted) task are not tasks the user has, so they neither get a colour nor consume the
 *   arc their siblings are sharing.
 *
 * Pure and free of any notion of what a colour looks like: it answers with the **hue fraction** in `[0, 1)`
 * and a depth, so it can be unit-tested as arithmetic. Turning that into something to paint with is the UI's
 * business — see `org.example.project.ui.TaskPalette`.
 */
object TaskColorSpace {

    /**
     * Where in the colour space one task sits: its [hue] fraction in `[0, 1)`, and the [depth] of the row
     * that put it there (0 for the root list).
     *
     * The depth is carried because the hue **alone does not tell every pair of tasks apart**, and cannot: a
     * parent's colour is the average of the arc its children then divide, so a child sitting in the middle
     * of that arc has the very same average. Siblings can never collide (their arcs are disjoint), so a
     * collision is always between an ANCESTOR and a DESCENDANT — which is exactly what a depth separates.
     * The palette spends it on lightness, leaving the hue partition itself untouched.
     */
    data class TaskHue(val hue: Double, val depth: Int)

    /**
     * Where every task the tree colours sits in the colour space.
     *
     * Keyed by [TaskId] because a colour belongs to the task, not to the cell — see the class note.
     */
    fun hues(state: SchedulerState): Map<TaskId, TaskHue> {
        val leaves = LeafCounter(state)
        val hues = LinkedHashMap<TaskId, TaskHue>()

        fun divide(listId: CellListId, start: Double, end: Double, depth: Int) {
            val cellIds = state.lists[listId]?.cellIds.orEmpty()
            // The width each cell is owed, and the total to measure it against. Both are read off the
            // populated cells alone, so an empty row consumes none of the arc.
            val widths =
                cellIds.mapNotNull { cellId ->
                    val taskId = state.cells[cellId]?.taskId ?: return@mapNotNull null
                    if (!SchedulerDomain.isPopulatedCell(state, cellId)) return@mapNotNull null
                    taskId to leaves.of(taskId).toDouble()
                }
            val total = widths.sumOf { it.second }
            if (total <= 0.0) return

            var cursor = start
            val span = end - start
            for ((taskId, width) in widths) {
                val from = cursor
                val to = from + span * (width / total)
                cursor = to
                // A task reached twice is a mirror: it keeps the colour its first occurrence gave it, and
                // its sub-list is not divided again. It still consumed its share of THIS arc above, which is
                // what keeps its siblings' widths proportional to the sub-trees they actually hold.
                if (taskId in hues) continue
                hues[taskId] = TaskHue((from + to) / 2.0, depth)
                state.tasks[taskId]?.childListId?.let { divide(it, from, to, depth + 1) }
            }
        }

        divide(state.rootListId, 0.0, 1.0, depth = 0)
        return hues
    }

    /**
     * How many **childless** tasks a task's sub-tree holds — its width in the colour space, and 1 for a leaf
     * (a childless task is one leaf: itself).
     *
     * Memoised per task rather than per path, which is both what makes a mirrored sub-tree cost one walk and
     * what makes the two occurrences of a mirror agree about how wide they are. A task met while it is still
     * being counted is a cycle the tree's own constraints forbid; it counts as a leaf so the walk terminates
     * rather than trusting that.
     */
    private class LeafCounter(private val state: SchedulerState) {
        private val memo = HashMap<TaskId, Int>()
        private val counting = HashSet<TaskId>()

        fun of(taskId: TaskId): Int {
            memo[taskId]?.let { return it }
            if (!counting.add(taskId)) return 1
            val childList = state.tasks[taskId]?.childListId?.let { state.lists[it] }
            val children =
                childList?.cellIds.orEmpty().mapNotNull { cellId ->
                    state.cells[cellId]?.taskId
                        ?.takeIf { SchedulerDomain.isPopulatedCell(state, cellId) }
                }
            val count = if (children.isEmpty()) 1 else children.sumOf { of(it) }
            counting.remove(taskId)
            memo[taskId] = count
            return count
        }
    }
}
