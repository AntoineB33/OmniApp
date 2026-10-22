package org.example.project

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDate
import org.example.project.ui.CalendarFloatingWindow

/**
 * The calendar window's two sections, resized by the line between them as the task tree window's are
 * ([TaskTreeSectionsTest]). Anomaly 2026-10-21: *"the lines move slower than the mouse"* — the line must move exactly
 * as far as the pointer — and *"the line must be draggable even if its section got minimized"*: a drag out of a
 * retracted section brings it back at the width the drag gives it.
 */
class CalendarSectionsTest {
    private val width = 1400
    private val day = LocalDate(2026, 10, 21)

    private class Seen {
        val widths = ArrayList<Float>()
        var hidden: List<Boolean>? = null
    }

    private fun drag(initiallyHidden: List<Boolean>?, x: Float, dx: Float): Seen {
        val seen = Seen()
        ImageComposeScene(width, 700, density = Density(1f)) {
            var kept by remember { mutableStateOf<Float?>(null) }
            var hidden by remember { mutableStateOf(initiallyHidden) }
            CalendarFloatingWindow(
                selectedDate = day,
                today = day,
                nowMillis = 1_792_000_000_000L,
                onDismiss = {},
                configurationWidthDp = kept,
                onConfigurationWidthChange = { kept = it; seen.widths += it },
                sectionsHidden = hidden,
                onSectionsHiddenChange = { hidden = it; seen.hidden = it },
                initialSize = Size(width.toFloat(), 700f),
            )
        }.use { scene ->
            scene.render()
            scene.sendPointerEvent(PointerEventType.Move, Offset(x, 400f))
            scene.sendPointerEvent(PointerEventType.Press, Offset(x, 400f))
            scene.render()
            // Small steps, as a mouse sends them.
            val steps = 40
            for (step in 1..steps) {
                scene.sendPointerEvent(PointerEventType.Move, Offset(x + dx * step / steps, 400f))
                scene.render()
            }
            scene.sendPointerEvent(PointerEventType.Release, Offset(x + dx, 400f))
            scene.render()
        }
        return seen
    }

    /** The first of [xs] that grabs the line: the drag there tells a width. */
    private fun dragFirstHit(hidden: List<Boolean>?, xs: IntProgression, dx: Float): Seen =
        xs.asSequence().map { drag(hidden, it.toFloat(), dx) }.first { it.widths.isNotEmpty() }

    @Test
    fun the_line_follows_the_pointer_exactly() {
        // The configuration is 220 wide at the right; the line just left of it.
        val near = width - 220
        val left = dragFirstHit(null, (near - 20)..(near + 4) step 2, -80f)
        assertEquals(300f, left.widths.last(), 0.5f, "dragged 80 left, the configuration widens by 80")
        assertEquals(1, left.widths.size, "told once, at the end of the drag")
        assertEquals(140f, dragFirstHit(null, (near - 20)..(near + 4) step 2, 80f).widths.last(), 0.5f, "dragged 80 right")
    }

    @Test
    fun a_drag_out_of_the_retracted_grid_brings_it_back() {
        val seen = dragFirstHit(listOf(true, false), 4..90 step 3, 300f)
        assertEquals(listOf(false, false), seen.hidden, "the grid is back")
        assertTrue(seen.widths.last() in (width - 400f)..(width - 250f), "at the width the drag gave it: ${seen.widths.last()}")
    }

    @Test
    fun a_drag_out_of_the_retracted_configuration_brings_it_back() {
        val seen = dragFirstHit(listOf(false, true), (width - 4) downTo (width - 90) step 3, -200f)
        assertEquals(listOf(false, false), seen.hidden, "the configuration is back")
        assertTrue(seen.widths.last() in 150f..250f, "about as wide as dragged: ${seen.widths.last()}")
    }
}
