package org.example.project

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.example.project.quota.QuotaScenario
import org.example.project.scheduler.ui.TaskSchedulerScreen
import org.example.project.scheduler.ui.TaskSchedulerViewModel
import org.example.project.ui.LocalWindowFrameHost
import org.example.project.ui.TaskTreeWindow
import org.example.project.ui.WindowFrameHost

/**
 * User rule 2026-10-20: the task tree window's two sections are resized by dragging the line between them, as the Search
 * and calendar windows' are — and (anomaly 2026-10-20, *"the separation line is still not draggable"*: the tree was
 * retracted, and the line beside a retracted section was a plain divider) a drag out of a retracted section brings it
 * back. Driven through the real window frame and a release-sized tree, with the pointer events a mouse sends.
 */
class TaskTreeSectionsTest {
    private val width = 1200

    private class Seen {
        val widths = ArrayList<Float>()
        var hidden: List<Boolean>? = null
    }

    private fun drag(initiallyHidden: List<Boolean>?, fromX: (Int) -> Float, dx: Float): Seen {
        val seen = Seen()
        ImageComposeScene(width, 700, density = Density(1f)) {
            var kept by remember { mutableStateOf<Float?>(null) }
            var hidden by remember { mutableStateOf(initiallyHidden) }
            CompositionLocalProvider(LocalWindowFrameHost provides WindowFrameHost()) {
                Box(Modifier.fillMaxSize()) {
                    TaskTreeWindow(onDismiss = {}) {
                        TaskSchedulerScreen(
                            vm = TaskSchedulerViewModel(initial = QuotaScenario.account(), store = null),
                            configurationWidthDp = kept,
                            onConfigurationWidthChange = { kept = it; seen.widths += it },
                            sectionsHidden = hidden,
                            onSectionsHiddenChange = { hidden = it; seen.hidden = it },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }.use { scene ->
            scene.render()
            val x = fromX(width)
            scene.sendPointerEvent(PointerEventType.Move, Offset(x, 300f))
            scene.sendPointerEvent(PointerEventType.Press, Offset(x, 300f))
            scene.render()
            for (step in 1..4) {
                scene.sendPointerEvent(PointerEventType.Move, Offset(x + dx * step / 4, 300f))
                scene.render()
            }
            scene.sendPointerEvent(PointerEventType.Release, Offset(x + dx, 300f))
            scene.render()
        }
        return seen
    }

    @Test
    fun the_line_between_the_sections_is_dragged_both_ways() {
        // The configuration is 320 wide at the right of the frame's 1-dp border; the 9-wide line just left of it.
        val line = { w: Int -> w - 1f - 320f - 4.5f }
        assertEquals(400f, drag(null, line, -80f).widths.last(), 0.5f, "dragged left, the configuration widens")
        assertEquals(240f, drag(null, line, 80f).widths.last(), 0.5f, "dragged right, it narrows")
    }

    @Test
    fun a_drag_out_of_the_retracted_tree_brings_it_back() {
        // The tree retracted (as the user's window was): the line stands just right of the tree's arrow, near the left.
        val seen = (8..80 step 4).asSequence().map { x -> drag(listOf(true, false), { x.toFloat() }, 300f) }.first { it.widths.isNotEmpty() }
        assertEquals(listOf(false, false), seen.hidden, "the tree is back")
        assertTrue(seen.widths.last() < width - 300f + 10f, "at the width the drag gave it: ${seen.widths.last()}")
    }

    @Test
    fun a_drag_out_of_the_retracted_configuration_brings_it_back() {
        val seen = drag(listOf(false, true), { w -> w - 1f - 4.5f - 30f }, -200f)
        // The line stands left of the configuration's arrow column; scan for it if this first try missed it.
        val hit = if (seen.widths.isNotEmpty()) seen
        else (20..90 step 4).asSequence().map { off -> drag(listOf(false, true), { w -> w - 1f - off.toFloat() }, -200f) }.first { it.widths.isNotEmpty() }
        assertEquals(listOf(false, false), hit.hidden, "the configuration is back")
        assertTrue(hit.widths.last() in 150f..250f, "about as wide as dragged: ${hit.widths.last()}")
    }
}
