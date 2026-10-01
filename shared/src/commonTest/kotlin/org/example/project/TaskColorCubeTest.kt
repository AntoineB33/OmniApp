package org.example.project

import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.domain.TaskColorCube
import org.example.project.scheduler.domain.TaskColorSpace
import org.example.project.scheduler.model.CellId
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState

/**
 * User rule 2026-10-01: *"only at more than 256·256·256 tasks there will be tasks with the same background color. In
 * the color space (a cube), the program first places the schedulable tasks as far away from each other as possible in
 * the cube, then the not schedulable tasks. Another optimization (that never prevails on the first one) is that the
 * more two tasks are close in the task tree, the more they are close in the cube."*
 */
class TaskColorCubeTest {
    private fun r(state: SchedulerState, intent: SchedulerIntent) = SchedulerReducer.reduce(state, intent)

    private fun distance(a: Int, b: Int): Double {
        val dr = ((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)
        val dg = ((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)
        val db = (a and 0xFF) - (b and 0xFF)
        return sqrt((dr * dr + dg * dg + db * db).toDouble())
    }

    /** [parents] parents of [perParent] tasks each, at the top level. */
    private fun tree(parents: Int, perParent: Int): SchedulerState {
        var s = SchedulerState.empty()
        repeat(parents) { p ->
            val free = s.lists[s.rootListId]!!.cellIds.last()
            s = r(s, SchedulerIntent.SetCellTitle(free, "Parent $p"))
            val parent = s.cells[free]!!.taskId!!
            repeat(perParent) { c ->
                val list = s.tasks[parent]!!.childListId!!
                val slot: CellId = s.lists[list]!!.cellIds.last()
                s = r(s, SchedulerIntent.SetCellTitle(slot, "Task $p.$c"))
            }
        }
        return s
    }

    @Test
    fun every_task_has_its_own_colour_parents_included() {
        val s = tree(parents = 30, perParent = 20)
        val colours = TaskColorCube.colors(TaskColorSpace.hues(s))
        assertEquals(630, colours.size)
        assertEquals(630, colours.values.toSet().size)
    }

    @Test
    fun the_tasks_to_schedule_come_first_and_as_far_apart_as_a_lattice_holding_them_allows() {
        val s = tree(parents = 27, perParent = 8) // 216 = 6³ tasks to schedule
        val hues = TaskColorSpace.hues(s)
        val colours = TaskColorCube.colors(hues)
        val leaves = hues.filterValues { it.leaf }.keys
        assertEquals(216, leaves.size)
        assertTrue(leaves.all { SchedulerDomain.isPlaceableTask(s, it) })
        val step = 255.0 / (TaskColorCube.latticeSide(216) - 1)
        val leafColours = leaves.map { colours.getValue(it) }
        var closest = Double.MAX_VALUE
        for (i in leafColours.indices) for (j in i + 1 until leafColours.size) closest = minOf(closest, distance(leafColours[i], leafColours[j]))
        assertTrue(closest >= step - 1.0, "the closest two tasks to schedule are a lattice step apart: $closest vs $step")
        // The others sit at the cell centres: no closer to a task to schedule than half a cell's diagonal.
        val others = hues.keys - leaves
        for (other in others) {
            val near = leafColours.minOf { distance(it, colours.getValue(other)) }
            assertTrue(near >= step * sqrt(3.0) / 2 - 1.5, "a parent as far from every task to schedule as can be: $near")
        }
    }

    @Test
    fun tree_neighbours_are_lattice_neighbours() {
        val s = tree(parents = 8, perParent = 8) // 64 = 4³
        val hues = TaskColorSpace.hues(s)
        val colours = TaskColorCube.colors(hues)
        val step = 255.0 / (TaskColorCube.latticeSide(64) - 1)
        val ring = hues.filterValues { it.leaf }.entries.sortedBy { it.value.hue }.map { colours.getValue(it.key) }
        val steps = ring.zipWithNext { a, b -> distance(a, b) }
        assertTrue(steps.all { it <= step + 1.0 }, "each task's next one round the tree is a lattice neighbour")
    }

    @Test
    fun the_path_through_the_lattice_steps_to_a_neighbour_every_time() {
        for (side in listOf(1, 2, 3, 6, 17)) {
            var previous: IntArray? = null
            val seen = HashSet<List<Int>>()
            for (t in 0 until side * side * side) {
                val axes = TaskColorCube.snakeAxes(t, side)
                assertTrue(seen.add(axes.toList()))
                previous?.let { p -> assertEquals(1, (0..2).sumOf { kotlin.math.abs(axes[it] - p[it]) }, "side $side step $t") }
                previous = axes
            }
        }
    }

    @Test
    fun more_tasks_than_centres_still_take_a_colour_each() {
        // 8 tasks to schedule (a 2-lattice: the cube's corners) and one centre; 40 more tasks go anywhere free.
        val hues = LinkedHashMap<TaskId, TaskColorSpace.TaskHue>()
        repeat(8) { hues[TaskId("leaf/$it")] = TaskColorSpace.TaskHue(it / 8.0, 1, leaf = true) }
        repeat(40) { hues[TaskId("parent/$it")] = TaskColorSpace.TaskHue(it / 40.0, 0) }
        val colours = TaskColorCube.colors(hues)
        assertEquals(48, colours.values.toSet().size)
        val corners = (0 until 8).map { colours.getValue(TaskId("leaf/$it")) }.toSet()
        assertTrue(corners.all { c -> listOf(16, 8, 0).all { ((c shr it) and 0xFF).let { v -> v == 0 || v == 255 } } }, "the eight corners")
    }
}
