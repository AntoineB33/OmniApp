package org.example.project.ui

import androidx.compose.ui.input.pointer.PointerIcon
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Cursor
import java.awt.Point
import java.awt.RenderingHints
import java.awt.Shape
import java.awt.Toolkit
import java.awt.geom.AffineTransform
import java.awt.geom.Path2D
import java.awt.geom.Rectangle2D
import java.awt.image.BufferedImage

/** Desktop: the OS north/south resize cursor — the standard "grab the edge" shape (PRD §8). */
actual fun verticalResizePointerIcon(): PointerIcon = PointerIcon(Cursor(Cursor.N_RESIZE_CURSOR))

/** Desktop: the OS east/west resize cursor — the shared-width edge is dragged sideways (PRD §8). */
actual fun horizontalResizePointerIcon(): PointerIcon = PointerIcon(Cursor(Cursor.E_RESIZE_CURSOR))

/**
 * Desktop: a drawn cursor per side (PRD §8) — the OS offers only the plain double arrows, which cannot say
 * WHICH of the two edges on an axis the press will take, so the four shapes are rendered here.
 *
 * Built once and cached: a cursor is an OS handle, and this is read from a hover path that recomposes with
 * every zoom and every pointer move (`docs/invariants/display-hot-path.md`).
 */
private val panelEdgeCursors: Map<PanelResizeEdge, PointerIcon> by lazy {
    PanelResizeEdge.entries.associateWith { PointerIcon(buildPanelEdgeCursor(it)) }
}

actual fun panelResizePointerIcon(edge: PanelResizeEdge): PointerIcon = panelEdgeCursors.getValue(edge)

/** The side the OS double arrow falls back to when custom cursors are unavailable (headless, X11 stubs). */
private fun fallbackCursor(edge: PanelResizeEdge): Cursor = Cursor(
    when (edge) {
        PanelResizeEdge.Top -> Cursor.N_RESIZE_CURSOR
        PanelResizeEdge.Bottom -> Cursor.S_RESIZE_CURSOR
        PanelResizeEdge.Left -> Cursor.W_RESIZE_CURSOR
        PanelResizeEdge.Right -> Cursor.E_RESIZE_CURSOR
    },
)

private fun buildPanelEdgeCursor(edge: PanelResizeEdge): Cursor = runCatching {
    val toolkit = Toolkit.getDefaultToolkit()
    val best = toolkit.getBestCursorSize(PANEL_CURSOR_BOX, PANEL_CURSOR_BOX)
    val size = minOf(best.width, best.height)
    if (size <= 0) return@runCatching fallbackCursor(edge)
    val image = panelEdgeCursorImage(edge, size)
    // The hot spot is the glyph's centre: the bar then lands ON the edge the pointer is hovering, because
    // a grab strip is measured INWARDS from that edge (RESIZE_EDGE_DP / WEIGHT_HANDLE_WIDTH).
    toolkit.createCustomCursor(image, Point(size / 2, size / 2), "omniapp-panel-resize-$edge")
}.getOrElse { fallbackCursor(edge) }

/** The coordinate box every glyph below is authored in; scaled to whatever size the OS asks for. */
internal const val PANEL_CURSOR_BOX = 32

/**
 * The drawn glyph for one panel edge: a double arrow along the resize axis with **a perpendicular bar on
 * the side that will be resized**.
 *
 * Authored once in the canonical [PanelResizeEdge.Top] orientation (bar at the top, arrow hanging below it)
 * and rotated/mirrored about the centre for the other three — one shape, four placements, so the four can
 * never drift apart. `internal` so a test can assert the bar really lands on the named side.
 */
internal fun panelEdgeCursorImage(edge: PanelResizeEdge, size: Int): BufferedImage {
    val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
    val g = image.createGraphics()
    try {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        val scale = size.toFloat() / PANEL_CURSOR_BOX
        val place = AffineTransform.getScaleInstance(scale.toDouble(), scale.toDouble())
        place.concatenate(orientation(edge))
        val shapes = canonicalGlyph().map { place.createTransformedShape(it) }
        // Outline first, then fill: a white halo under the black ink is what keeps the shape readable on a
        // dark task panel and on the pale grid alike.
        g.color = Color.WHITE
        g.stroke = BasicStroke(3f * scale, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        shapes.forEach { g.draw(it) }
        g.color = Color.BLACK
        shapes.forEach { g.fill(it) }
    } finally {
        g.dispose()
    }
    return image
}

/** Maps the canonical (bar-at-top) glyph onto each edge, about the box's centre. */
private fun orientation(edge: PanelResizeEdge): AffineTransform {
    val c = PANEL_CURSOR_BOX / 2.0
    return when (edge) {
        PanelResizeEdge.Top -> AffineTransform()
        // Mirror vertically: the bar drops to the bottom, the arrow rises above it.
        PanelResizeEdge.Bottom -> AffineTransform(1.0, 0.0, 0.0, -1.0, 0.0, PANEL_CURSOR_BOX.toDouble())
        // A quarter turn each way: "up" becomes "left" / "right", carrying the bar with it.
        PanelResizeEdge.Left -> AffineTransform.getRotateInstance(-Math.PI / 2, c, c)
        PanelResizeEdge.Right -> AffineTransform.getRotateInstance(Math.PI / 2, c, c)
    }
}

/** The Top glyph in the 32-unit box: bar across y≈3–6, double arrow from y=9 down to y=30 at x=16. */
private fun canonicalGlyph(): List<Shape> = listOf(
    Rectangle2D.Float(4f, 3f, 24f, 3f),
    Rectangle2D.Float(14.5f, 14f, 3f, 10f),
    triangle(16f, 9f, 10.5f, 15f, 21.5f, 15f),
    triangle(16f, 30f, 10.5f, 24f, 21.5f, 24f),
)

private fun triangle(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float): Shape =
    Path2D.Float().apply {
        moveTo(x1, y1)
        lineTo(x2, y2)
        lineTo(x3, y3)
        closePath()
    }
