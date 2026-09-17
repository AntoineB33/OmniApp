package org.example.project

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import org.example.project.ui.timelineSpan
import org.jetbrains.skia.Bitmap

/**
 * ADR 0009 § *Everything that follows the line moves continuously*: **the screen's pixels are the only
 * rounding** — measured on the rendered pixels, not argued.
 *
 * A calendar element spanning hours 2..5 of a column 10 px per hour tall is RENDERED headlessly, black on
 * white, and one pixel column is read back as coverage (how much of each pixel row the element paints). Its
 * edges are then where the coverage says they are: the total is the element's height and the partial rows are
 * the fractions. That is exactly what "placed between pixels" means on a discrete grid.
 */
class TimelineSpanRenderTest {

    private val width = 20
    private val height = 120

    /** Per pixel row: the fraction of it the element covers, read at the middle of the column. */
    private fun coverage(topFollows: Boolean, bottomFollows: Boolean, driftHours: Double): DoubleArray {
        val scene =
            ImageComposeScene(width = width, height = height, density = Density(1f)) {
                Box(Modifier.fillMaxSize().background(Color.White)) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .timelineSpan(10.dp, 2f, 5f, topFollows, bottomFollows, { driftHours })
                            .background(Color.Black),
                    )
                }
            }
        try {
            val bitmap = Bitmap.makeFromImage(scene.render())
            return DoubleArray(height) { y -> 1.0 - ((bitmap.getColor(width / 2, y) shr 16) and 0xFF) / 255.0 }
        } finally {
            scene.close()
        }
    }

    private fun DoubleArray.total() = sum()

    private fun DoubleArray.top(): Double {
        val first = indexOfFirst { it > 0.01 }
        return first + (1.0 - this[first])
    }

    private fun DoubleArray.bottom(): Double {
        val last = indexOfLast { it > 0.01 }
        return last + this[last]
    }

    @Test
    fun an_element_with_no_edge_on_the_line_is_whole_pixel_geometry() {
        val still = coverage(topFollows = false, bottomFollows = false, driftHours = 0.035)
        assertEquals(20.0, still.top(), 0.02)
        assertEquals(50.0, still.bottom(), 0.02)
    }

    @Test
    fun a_dragged_pose_glides_rigidly_between_pixels() {
        // 0.035 h at 10 px/h is 0.35 px: both edges move by exactly that, and the height does not change.
        val moved = coverage(topFollows = true, bottomFollows = true, driftHours = 0.035)
        assertEquals(20.35, moved.top(), 0.03, "the top edge lands between pixels")
        assertEquals(50.35, moved.bottom(), 0.03, "the bottom edge lands between pixels")
        assertEquals(30.0, moved.total(), 0.05, "a rigid motion keeps the height")
    }

    @Test
    fun the_panel_growing_behind_the_line_stretches_by_a_fraction_of_a_pixel() {
        val grown = coverage(topFollows = false, bottomFollows = true, driftHours = 0.062)
        assertEquals(20.0, grown.top(), 0.03, "the fixed top stays on its pixel row")
        assertEquals(50.62, grown.bottom(), 0.03, "the moving bottom lands between pixels")
    }

    @Test
    fun the_panel_resuming_after_the_line_shrinks_from_its_top() {
        val shrunk = coverage(topFollows = true, bottomFollows = false, driftHours = 0.081)
        assertEquals(20.81, shrunk.top(), 0.03)
        assertEquals(50.0, shrunk.bottom(), 0.03)
    }

    @Test
    fun the_motion_is_continuous_across_a_pixel_boundary() {
        // Sweep the drift through a whole pixel and more: the drawn top must advance by the drift every time,
        // never hold still and jump.
        var previous = Double.NaN
        for (step in 0..12) {
            val drift = step * 0.01 // 0.1 px per step
            val top = coverage(topFollows = true, bottomFollows = true, driftHours = drift).top()
            assertEquals(20.0 + step * 0.1, top, 0.03, "drift ${step * 0.1} px")
            if (!previous.isNaN()) assertEquals(0.1, top - previous, 0.05)
            previous = top
        }
    }
}
