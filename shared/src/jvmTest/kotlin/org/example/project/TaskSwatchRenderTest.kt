package org.example.project

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertTrue
import org.example.project.ui.TaskSheetExpandArrow
import org.jetbrains.skia.Bitmap

/**
 * User rule 2026-10-03: **the colour under a task cell's expansion arrow is outlined in the opposite colour**, as a
 * task panel is on the calendar ([org.example.project.ui.TaskPalette.foreground]). Read off the rendered pixels: the
 * swatch's edge row is the contrast colour, its inside the task's own.
 */
class TaskSwatchRenderTest {

    /** The luminance (0..255) of the swatch's top edge and of its inside, for a task of [color] on a grey sheet. */
    private fun edgeAndInside(color: Color): Pair<Int, Int> {
        val scene =
            ImageComposeScene(width = 40, height = 40, density = Density(1f)) {
                MaterialTheme {
                    Box(Modifier.fillMaxSize().background(Color.Gray).padding(10.dp)) {
                        TaskSheetExpandArrow(hasChildren = false, expanded = false, onToggle = {}, background = color)
                    }
                }
            }
        try {
            val bitmap = Bitmap.makeFromImage(scene.render())
            fun luminance(x: Int, y: Int) = (bitmap.getColor(x, y) shr 8) and 0xFF
            // The 20 dp swatch sits at (10, 10): its top edge row is y = 10, its inside the middle.
            return luminance(20, 10) to luminance(20, 20)
        } finally {
            scene.close()
        }
    }

    @Test
    fun a_pale_task_colour_is_outlined_in_black() {
        val (edge, inside) = edgeAndInside(Color.White)
        assertTrue(inside > 240, "the inside is the task's own colour: $inside")
        assertTrue(edge < 60, "the edge is the colour of highest contrast with it: $edge")
    }

    @Test
    fun a_dark_task_colour_is_outlined_in_white() {
        val (edge, inside) = edgeAndInside(Color.Black)
        assertTrue(inside < 15, "the inside is the task's own colour: $inside")
        assertTrue(edge > 195, "the edge is the colour of highest contrast with it: $edge")
    }
}
