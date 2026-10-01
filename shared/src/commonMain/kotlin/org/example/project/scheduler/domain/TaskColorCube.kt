package org.example.project.scheduler.domain

import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import org.example.project.scheduler.model.TaskId

/**
 * **Where every task's colour is in the sRGB cube** (user rule 2026-10-01): *"only at more than 256·256·256 tasks
 * there will be tasks with the same background color. In the color space (a cube), the program first places the
 * schedulable tasks as far away from each other as possible in the cube, then the not schedulable tasks. Another
 * optimization (that never prevails on the first one) is that the more two tasks are close in the task tree, the
 * more they are close in the cube."*
 *
 * [TaskColorSpace] still answers the ORDER and the STABILITY: the tasks to schedule (the childless ones, the ring) at
 * `i/n` round a circle in the tree's depth-first order, the others in their sub-tree's stretch of it, every tie settled
 * against the previous answer. This turns those places into colours, in three classes, each placed only where the one
 * before it is settled:
 *
 * 1. **The tasks to schedule, as far apart as the cube allows**: a `k·k·k` lattice spanning the whole cube, `k` the
 *    least with `k³ ≥` their number — the regular arrangement whose smallest distance, `255/(k−1)`, is the largest a
 *    lattice holding them all can have. They are laid on it along a continuous path through the lattice
 *    ([snakeAxes]: each step one neighbour) in the circle's order, so tree neighbours are lattice neighbours — the
 *    secondary rule, paid for with the order only, never with the spacing.
 * 2. **Then the others, as far from the first as can be**: the lattice's CELL CENTRES (`(k−1)³`), the points furthest
 *    from every lattice point (`√3/2` of its step), themselves a lattice of that step. Each lands at the free centre
 *    nearest its own place on the circle along the centres' own path, which is where its sub-tree's colours are.
 * 3. **Whatever is left** (more of them than centres, or a centre that rounds onto a used colour once the step is
 *    under 2): the free colour nearest its place along the curve through all `256³` colours ([TaskColorCurve]).
 *
 * Every colour handed out is checked against those already given, so **no two tasks share a colour while there are
 * at most `256³` of them** — the third class can always find a free one.
 */
object TaskColorCube {
    private const val CUBE: Long = TaskColorCurve.CUBE

    /** Every task's colour, `0xRRGGBB`, from where [TaskColorSpace] put it ([hues], in its placement order). */
    fun colors(hues: Map<TaskId, TaskColorSpace.TaskHue>): Map<TaskId, Int> {
        if (hues.isEmpty()) return emptyMap()
        val used = HashSet<Int>(hues.size * 2)
        val out = LinkedHashMap<TaskId, Int>(hues.size * 2)
        val leaves = hues.filterValues { it.leaf }
        val k = latticeSide(leaves.size)

        // 1. The tasks to schedule, on the lattice.
        val lattice = BooleanArray(cubeOf(k))
        for ((taskId, hue) in leaves) {
            val free = nearestFree(indexOf(hue.hue, lattice.size), lattice.size) { !lattice[it] } ?: continue
            lattice[free] = true
            val rgb = latticeRgb(snakeAxes(free, k), k)
            if (used.add(rgb)) out[taskId] = rgb
        }

        // 2. The others, on the cell centres; 3. anything left, anywhere free.
        val centreSide = k - 1
        val centres = BooleanArray(cubeOf(centreSide))
        for ((taskId, hue) in hues) {
            if (taskId in out) continue
            val atCentre =
                if (centres.isEmpty()) null
                else nearestFree(indexOf(hue.hue, centres.size), centres.size) { i ->
                    !centres[i] && centreRgb(snakeAxes(i, centreSide), k) !in used
                }
            if (atCentre != null) {
                centres[atCentre] = true
                val rgb = centreRgb(snakeAxes(atCentre, centreSide), k)
                used += rgb
                out[taskId] = rgb
                continue
            }
            val anywhere =
                nearestFreeLong(indexOfLong(hue.hue, CUBE), CUBE) { TaskColorCurve.rgbAt(it) !in used } ?: continue
            val rgb = TaskColorCurve.rgbAt(anywhere)
            used += rgb
            out[taskId] = rgb
        }
        return out
    }

    /** The least `k` with `k³ ≥ n` (at least 1), capped at 256: the lattice holding [n] tasks with the widest step. */
    fun latticeSide(n: Int): Int {
        var k = maxOf(1, kotlin.math.cbrt(n.toDouble()).toInt() - 1)
        while (k.toLong() * k * k < n) k++
        return k.coerceAtMost(256)
    }

    private fun cubeOf(side: Int): Int = if (side <= 0) 0 else side * side * side

    /** A lattice point's colour: each axis `i` at `round(i·255/(k−1))`, the lone point of a 1-lattice at mid-grey. */
    private fun latticeRgb(axes: IntArray, k: Int): Int {
        fun at(i: Int) = if (k <= 1) 128 else (i * 255.0 / (k - 1)).roundToInt()
        return (at(axes[0]) shl 16) or (at(axes[1]) shl 8) or at(axes[2])
    }

    /** A cell centre's colour: each axis half a step past lattice point `i`. */
    private fun centreRgb(axes: IntArray, k: Int): Int {
        fun at(i: Int) = ((i + 0.5) * 255.0 / (k - 1)).roundToInt()
        return (at(axes[0]) shl 16) or (at(axes[1]) shl 8) or at(axes[2])
    }

    /**
     * The [t]-th point of a `side³` grid along a path that moves to a neighbour at every step: layers along one axis,
     * rows back and forth inside each, alternating direction from row to row and layer to layer.
     */
    fun snakeAxes(t: Int, side: Int): IntArray {
        val layer = t / (side * side)
        val inLayer = t % (side * side)
        val rowInLayer = inLayer / side
        val col = inLayer % side
        val row = if (layer % 2 == 0) rowInLayer else side - 1 - rowInLayer
        val rowSeq = layer * side + rowInLayer
        val x = if (rowSeq % 2 == 0) col else side - 1 - col
        return intArrayOf(x, row, layer)
    }

    /** Where [hue] falls among [size] steps: rounded, so the `i/n` of a ring exactly as large as the grid hit `i`. */
    private fun indexOf(hue: Double, size: Int): Int {
        val wrapped = hue - floor(hue)
        return ((wrapped * size).roundToLong() % size).toInt()
    }

    private fun indexOfLong(hue: Double, size: Long): Long {
        val wrapped = hue - floor(hue)
        return (wrapped * size).roundToLong() % size
    }

    /** The free index nearest [from] round a cycle of [size] (ties forward), or null when none is free. */
    private inline fun nearestFree(from: Int, size: Int, free: (Int) -> Boolean): Int? {
        for (d in 0..size / 2) {
            val ahead = (from + d) % size
            if (free(ahead)) return ahead
            val behind = ((from - d) % size + size) % size
            if (free(behind)) return behind
        }
        return null
    }

    private inline fun nearestFreeLong(from: Long, size: Long, free: (Long) -> Boolean): Long? {
        var d = 0L
        while (d <= size / 2) {
            val ahead = (from + d) % size
            if (free(ahead)) return ahead
            val behind = ((from - d) % size + size) % size
            if (free(behind)) return behind
            d++
        }
        return null
    }
}
