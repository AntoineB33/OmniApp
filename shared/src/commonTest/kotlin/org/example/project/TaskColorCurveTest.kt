package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.TaskColorCurve

/**
 * User rule 2026-10-01: *"only at more than 256·256·256 tasks to schedule there will be tasks with the same background
 * color"*, and what is drawn on a task's colour is written in the colour of highest WCAG contrast with it.
 */
class TaskColorCurveTest {

    @Test
    fun the_curve_visits_every_24_bit_colour_once_each_step_one_channel_by_one() {
        val seen = BooleanArray(TaskColorCurve.CUBE.toInt())
        var previous = -1
        for (step in 0 until TaskColorCurve.CUBE) {
            val rgb = TaskColorCurve.rgbAt(step)
            assertTrue(!seen[rgb], "colour ${rgb.toString(16)} visited twice (step $step)")
            seen[rgb] = true
            if (previous >= 0) {
                val dr = kotlin.math.abs(((rgb shr 16) and 0xFF) - ((previous shr 16) and 0xFF))
                val dg = kotlin.math.abs(((rgb shr 8) and 0xFF) - ((previous shr 8) and 0xFF))
                val db = kotlin.math.abs((rgb and 0xFF) - (previous and 0xFF))
                if (dr + dg + db != 1) throw AssertionError("step $step is not one channel by one: $dr $dg $db")
            }
            previous = rgb
        }
    }

    @Test
    fun n_tasks_spread_around_the_circle_are_n_colours_up_to_the_whole_cube() {
        for (n in listOf(1, 2, 3, 7, 100, 4_096, 1_000_003)) {
            for (rotation in listOf(0.0, 0.123456789, 0.999)) {
                val colours = HashSet<Int>()
                for (i in 0 until n) colours += TaskColorCurve.rgbOf(i.toDouble() / n + rotation)
                assertEquals(n, colours.size, "n=$n rotation=$rotation")
            }
        }
    }

    @Test
    fun the_foreground_is_black_or_white_whichever_contrasts_more() {
        assertEquals(TaskColorCurve.BLACK, TaskColorCurve.bestForeground(0xFFFFFF))
        assertEquals(TaskColorCurve.WHITE, TaskColorCurve.bestForeground(0x000000))
        assertEquals(21.0, TaskColorCurve.contrastRatio(0x000000, 0xFFFFFF), 1e-9)
        // Every colour of the curve gets the better of the two, and never less than the WCAG minimum for large text.
        var worst = 21.0
        for (step in 0 until TaskColorCurve.CUBE step 997) {
            val bg = TaskColorCurve.rgbAt(step)
            val fg = TaskColorCurve.bestForeground(bg)
            val other = if (fg == TaskColorCurve.BLACK) TaskColorCurve.WHITE else TaskColorCurve.BLACK
            assertTrue(TaskColorCurve.contrastRatio(bg, fg) >= TaskColorCurve.contrastRatio(bg, other))
            worst = minOf(worst, TaskColorCurve.contrastRatio(bg, fg))
        }
        assertTrue(worst >= 4.58, "the worst background still reads at $worst:1")
    }

    @Test
    fun a_real_tree_paints_every_task_to_schedule_in_its_own_colour() {
        // 600 tasks to schedule: the old pale tint (one saturation, one lightness) had ~216 distinct 24-bit colours.
        var s = org.example.project.scheduler.state.SchedulerState.empty()
        repeat(600) { i ->
            val free = s.lists[s.rootListId]!!.cellIds.last()
            s = org.example.project.scheduler.state.SchedulerReducer.reduce(
                s, org.example.project.scheduler.state.SchedulerIntent.SetCellTitle(free, "Task $i"),
            )
        }
        val hues = org.example.project.scheduler.domain.TaskColorSpace.hues(s)
        val colours = org.example.project.ui.TaskPalette.sheetColors(hues)
        val leaves = colours.filterKeys { org.example.project.scheduler.domain.SchedulerDomain.isPlaceableTask(s, it) }
        assertEquals(600, leaves.size)
        assertEquals(600, leaves.values.toSet().size, "every task to schedule its own colour")
        // The tree and the calendar read the same colour, and what is written on it is black or white.
        assertEquals(colours, org.example.project.ui.TaskPalette.accentColors(hues))
        assertTrue(colours.values.all { org.example.project.ui.TaskPalette.foreground(it).let { fg -> fg == androidx.compose.ui.graphics.Color.Black || fg == androidx.compose.ui.graphics.Color.White } })
    }
}
