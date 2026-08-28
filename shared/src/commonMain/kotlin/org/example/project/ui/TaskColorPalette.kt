package org.example.project.ui

import androidx.compose.ui.graphics.Color
import org.example.project.scheduler.domain.TaskColorSpace
import org.example.project.scheduler.model.TaskId

/**
 * **What a task's colour looks like.** [TaskColorSpace] answers with a hue fraction and nothing else; this is
 * the one place that turns one into something to paint with, so the tree's cell and the calendar's panel can
 * never disagree about what colour a task is.
 *
 * Two readings of the same hue, because the two surfaces ask different things of it:
 *
 * - [sheet] is a **task-tree cell background**: a pale tint, light enough that the row's ordinary dark text,
 *   its percentage, its guide-lines and its 1 dp grid border all stay legible on top of it. A tree of a
 *   hundred saturated rows is unreadable; a tree of a hundred tints reads as the branches it is.
 * - [accent] is a **calendar panel's colour** — used exactly as `CalColors.event` (the single Google-blue the
 *   panels were drawn in) was: at 30 % as the fill, at full strength for the 1 dp border and the title. So it
 *   is deliberately as heavy as that blue was, and the blue remains the colour of a panel whose task the tree
 *   gives no colour.
 *
 * Saturation is fixed, so where a task sits in the tree is essentially the only thing that varies. Lightness
 * carries one extra thing: **the depth**, a step darker per level. It is no longer what tells two tasks
 * apart — [TaskColorSpace] now places a parent off every hue its own sub-tree holds, so the hue alone always
 * separates them — but a parent and the leaf it was placed beside are *neighbouring* hues by design, and a
 * step of lightness is what makes that a branch reading as a family of shades rather than two rows the eye
 * has to measure. The step is small and stops after [DEPTH_STEPS] levels, so a deep tree never runs out of
 * contrast against the row's text.
 */
internal object TaskPalette {

    /** Pale tint for a task-tree cell's background. */
    fun sheet(hue: TaskColorSpace.TaskHue): Color =
        Color.hsl(degrees(hue.hue), SHEET_SATURATION, SHEET_LIGHTNESS - step(hue.depth) * SHEET_DEPTH_STEP)

    /** Full-strength colour for a calendar panel's fill, border and title. */
    fun accent(hue: TaskColorSpace.TaskHue): Color =
        Color.hsl(degrees(hue.hue), ACCENT_SATURATION, ACCENT_LIGHTNESS - step(hue.depth) * ACCENT_DEPTH_STEP)

    /**
     * Every task's cell tint, for the task tree. Takes the solved hues rather than the state: solving is
     * [TaskHueMemo]'s business (it holds the previous answer the tie-breaks are settled against, and the
     * debounce), and going through it is what keeps this reading and the calendar's identical.
     */
    fun sheetColors(hues: Map<TaskId, TaskColorSpace.TaskHue>): Map<TaskId, Color> =
        hues.mapValues { (_, hue) -> sheet(hue) }

    /** Every task's panel colour, for the calendar. Same hues, same source — see [sheetColors]. */
    fun accentColors(hues: Map<TaskId, TaskColorSpace.TaskHue>): Map<TaskId, Color> =
        hues.mapValues { (_, hue) -> accent(hue) }

    private fun step(depth: Int): Float = depth.coerceIn(0, DEPTH_STEPS).toFloat()

    /**
     * The hue fraction as the degrees `Color.hsl` wants. A fraction is in `[0, 1)` by construction, but a
     * task sitting at the very end of the circle must still land strictly below 360° — the two are the same
     * colour, and the conversion rejects the upper bound.
     */
    private fun degrees(hue: Double): Float {
        val wrapped = hue - kotlin.math.floor(hue)
        return (wrapped * 360.0).toFloat().coerceIn(0f, 359.999f)
    }

    private const val SHEET_SATURATION = 0.70f
    private const val SHEET_LIGHTNESS = 0.90f
    private const val SHEET_DEPTH_STEP = 0.035f
    private const val ACCENT_SATURATION = 0.62f
    private const val ACCENT_LIGHTNESS = 0.50f
    private const val ACCENT_DEPTH_STEP = 0.025f

    /** Levels the depth step keeps darkening for; deeper rows all share the last one. */
    private const val DEPTH_STEPS = 4
}
