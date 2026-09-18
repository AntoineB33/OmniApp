package org.example.project.ui

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import kotlin.math.roundToInt
import org.example.project.scheduler.domain.PeriodDrawing
import org.example.project.scheduler.domain.PeriodKindConfig

/**
 * The account's period styles ([PeriodKindConfig]: companions + drawings) for whatever draws a period — the
 * calendar's period boxes, sleep bands and layer hatches. Provided once by `App` off the live state; the default
 * is every kind at its built-in style, so a preview or a test composition draws what a fresh account would.
 */
val LocalPeriodKindConfig = compositionLocalOf { PeriodKindConfig.DEFAULT }

/**
 * PRD §8: **paint [drawing] behind this node** — the one renderer of a [PeriodDrawing], used by the calendar
 * (period boxes, the sleep band, the two layer hatches) and by the period edit window's swatches, so the
 * chooser shows exactly what the calendar will draw.
 *
 * Every drawing is the same colour, the same 1 dp stroke and the same 35 % alpha, and differs from the others
 * by geometry alone — that is what lets several overlap over one stretch and still be read one by one, and what
 * keeps a task drawn through the period legible (a period has no fill at any depth).
 *
 * Drawn as ONE rectangle filled with a repeated tile rather than as a loop of lines, so the cost is the same
 * for a 20-minute box and for a night at the zoom ceiling (~150 000 px tall), and the pattern is continuous
 * across the tile seams. [dotted] breaks the strokes into dashes without touching anything else — the mark
 * of a layer the user declared against the machine's own log (see `SchedulerDomain.declaredLayerRegions`).
 */
internal fun Modifier.periodDrawing(drawing: PeriodDrawing, color: Color, dotted: Boolean = false): Modifier =
    this.drawWithCache {
        val tile = periodDrawingTile(drawing, color.copy(alpha = 0.35f), dotted, this)
        val brush = ShaderBrush(ImageShader(tile, TileMode.Repeated, TileMode.Repeated))
        onDrawBehind { drawRect(brush) }
    }

/** [periodDrawing] for every drawing in [drawings], in order. */
internal fun Modifier.periodDrawings(drawings: List<PeriodDrawing>, color: Color): Modifier =
    drawings.fold(this) { acc, drawing -> acc.periodDrawing(drawing, color) }

/** The tile's side in dp, per drawing — wide enough to leave air between the marks of two overlapping ones. */
private fun tileSizeDp(drawing: PeriodDrawing): Pair<Float, Float> =
    when (drawing) {
        PeriodDrawing.VerticalLines,
        PeriodDrawing.HorizontalLines,
        PeriodDrawing.RisingObliques,
        PeriodDrawing.FallingObliques -> 10f to 10f
        PeriodDrawing.HalfCirclesLeft,
        PeriodDrawing.HalfCirclesRight -> 16f to 14f
        PeriodDrawing.Crosses -> 16f to 16f
        PeriodDrawing.Zigzags -> 8f to 14f
    }

private fun periodDrawingTile(drawing: PeriodDrawing, color: Color, dotted: Boolean, density: Density): ImageBitmap {
    val (wDp, hDp) = tileSizeDp(drawing)
    val w = ceil(wDp * density.density).roundToInt().coerceAtLeast(2)
    val h = ceil(hDp * density.density).roundToInt().coerceAtLeast(2)
    val image = ImageBitmap(w, h)
    CanvasDrawScope().draw(density, LayoutDirection.Ltr, Canvas(image), Size(w.toFloat(), h.toFloat())) {
        drawTile(drawing, color, dotted)
    }
    return image
}

private fun DrawScope.drawTile(drawing: PeriodDrawing, color: Color, dotted: Boolean) {
    val stroke = 1.dp.toPx()
    val effect = if (dotted) PathEffect.dashPathEffect(floatArrayOf(1.5.dp.toPx(), 2.5.dp.toPx())) else null
    val w = size.width
    val h = size.height
    fun line(from: Offset, to: Offset) =
        drawLine(color, from, to, strokeWidth = stroke, pathEffect = effect, cap = StrokeCap.Butt)
    when (drawing) {
        PeriodDrawing.VerticalLines -> line(Offset(w / 2f, 0f), Offset(w / 2f, h))
        PeriodDrawing.HorizontalLines -> line(Offset(0f, h / 2f), Offset(w, h / 2f))
        // The diagonal plus its two neighbours, so the anti-aliased corners join into one continuous line.
        PeriodDrawing.RisingObliques ->
            for (dx in listOf(-w, 0f, w)) line(Offset(dx, h), Offset(dx + w, 0f))
        PeriodDrawing.FallingObliques ->
            for (dx in listOf(-w, 0f, w)) line(Offset(dx, 0f), Offset(dx + w, h))
        // `(` in the left half of the tile and `)` in the right half, so the two together read "( )" — both
        // visible, neither closing the other into a circle.
        PeriodDrawing.HalfCirclesLeft, PeriodDrawing.HalfCirclesRight -> {
            val d = h * 0.6f
            val left = drawing == PeriodDrawing.HalfCirclesLeft
            val cx = if (left) w * 0.3f else w * 0.7f
            drawArc(
                color = color,
                startAngle = if (left) 90f else 270f,
                sweepAngle = 180f,
                useCenter = false,
                topLeft = Offset(cx - d / 2f, (h - d) / 2f),
                size = Size(d, d),
                style = Stroke(width = stroke, pathEffect = effect),
            )
        }
        PeriodDrawing.Crosses -> {
            val arm = 3.dp.toPx()
            line(Offset(w / 2f - arm, h / 2f), Offset(w / 2f + arm, h / 2f))
            line(Offset(w / 2f, h / 2f - arm), Offset(w / 2f, h / 2f + arm))
        }
        // One period of the zig-zag per tile; the tile repeats sideways into a continuous line.
        PeriodDrawing.Zigzags -> {
            val top = h * 0.35f
            val bottom = h * 0.65f
            line(Offset(0f, bottom), Offset(w / 2f, top))
            line(Offset(w / 2f, top), Offset(w, bottom))
        }
    }
}
