package org.example.project

import kotlin.test.Test
import kotlin.test.assertTrue
import org.example.project.ui.PANEL_CURSOR_BOX
import org.example.project.ui.PanelResizeEdge
import org.example.project.ui.panelEdgeCursorImage
import java.awt.image.BufferedImage

/**
 * PRD §8: the four drawn resize cursors — a double arrow along the axis the drag moves in, with **a line
 * perpendicular to it on the side of the panel that will be resized**.
 *
 * The shape is the whole promise the user gets before pressing, and the OS offers no cursor that carries
 * it, so the glyph is rendered here and asserted here: the bar on the named side, the arrow double-ended
 * along the right axis, and the four never collapsing into each other.
 */
class PanelResizeCursorTest {

    private val size = PANEL_CURSOR_BOX

    private fun image(edge: PanelResizeEdge): BufferedImage = panelEdgeCursorImage(edge, size)

    private fun inked(img: BufferedImage, x: Int, y: Int): Boolean = (img.getRGB(x, y) ushr 24) != 0

    /** How many inked pixels the given row (or, transposed, column) holds. */
    private fun rowInk(img: BufferedImage, i: Int, transposed: Boolean): Int =
        (0 until size).count { j -> if (transposed) inked(img, i, j) else inked(img, j, i) }

    /** The index of the widest inked run across the axis — the perpendicular bar, the widest thing drawn. */
    private fun barIndex(img: BufferedImage, transposed: Boolean): Int =
        (0 until size).maxByOrNull { rowInk(img, it, transposed) }!!

    @Test
    fun theBarSitsOnTheSideOfThePanelThatWillBeResized() {
        val half = size / 2
        // Vertical edges: the bar is a horizontal line, so it is a ROW (transposed = false).
        assertTrue(barIndex(image(PanelResizeEdge.Top), transposed = false) < half, "Top bar is above")
        assertTrue(barIndex(image(PanelResizeEdge.Bottom), transposed = false) > half, "Bottom bar is below")
        // Horizontal edges: the bar is a vertical line, so it is a COLUMN (transposed = true).
        assertTrue(barIndex(image(PanelResizeEdge.Left), transposed = true) < half, "Left bar is to the left")
        assertTrue(barIndex(image(PanelResizeEdge.Right), transposed = true) > half, "Right bar is to the right")
    }

    @Test
    fun eachGlyphIsADoubleArrowAlongItsOwnAxis() {
        for (edge in PanelResizeEdge.entries) {
            val img = image(edge)
            val transposed = edge == PanelResizeEdge.Left || edge == PanelResizeEdge.Right
            val bar = barIndex(img, transposed)
            // The arrow hangs off the bar: walk away from it, and the profile must pinch in at the shaft
            // and swell again at the far arrowhead's base — that swell is the SECOND head, the thing that
            // makes it a resize arrow instead of a "push this way" one.
            val away = if (bar < size / 2) (bar + 1 until size) else (0 until bar).reversed()
            val profile = away.map { rowInk(img, it, transposed) }
            val nearHead = profile.take(profile.size / 2).max()
            val shaft = profile.drop(profile.size / 4).take(profile.size / 4).min()
            val farHead = profile.drop(profile.size / 2).max()
            assertTrue(nearHead > shaft, "$edge: no head next to the bar ($nearHead vs shaft $shaft)")
            assertTrue(farHead > shaft, "$edge: no head at the far end ($farHead vs shaft $shaft)")
        }
    }

    @Test
    fun theFourShapesAreDistinct() {
        val glyphs = PanelResizeEdge.entries.map { edge ->
            edge to (0 until size).flatMap { y -> (0 until size).map { x -> inked(image(edge), x, y) } }
        }
        for (i in glyphs.indices) {
            for (j in i + 1 until glyphs.size) {
                assertTrue(
                    glyphs[i].second != glyphs[j].second,
                    "${glyphs[i].first} and ${glyphs[j].first} draw the same shape",
                )
            }
        }
    }

    @Test
    fun theGlyphScalesToWhateverSizeTheOsAsksFor() {
        // The OS picks the cursor size (getBestCursorSize); the authored 32-unit box is only a coordinate
        // system. A glyph drawn at another size must still put its bar on the same side.
        for (px in listOf(16, 48, 64)) {
            val img = panelEdgeCursorImage(PanelResizeEdge.Left, px)
            val widest = (0 until px).maxByOrNull { x -> (0 until px).count { y -> inked(img, x, y) } }!!
            assertTrue(widest < px / 2, "Left bar drifted off its side at ${px}px (column $widest)")
        }
    }
}
