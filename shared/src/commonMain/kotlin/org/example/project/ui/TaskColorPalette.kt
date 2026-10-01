package org.example.project.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.example.project.scheduler.domain.TaskColorCube
import org.example.project.scheduler.domain.TaskColorCurve
import org.example.project.scheduler.domain.TaskColorSpace
import org.example.project.scheduler.model.TaskId

/**
 * **What a task's colour looks like.** [TaskColorSpace] answers where each task sits (the order, settled against the
 * previous answer); [TaskColorCube] turns all of them TOGETHER into colours of the whole sRGB cube — the tasks to
 * schedule first and furthest apart, then the others, no two the same while there are at most `256³` tasks (user rule
 * 2026-10-01). This is the one place that hands those out to paint with, so the tree's cell and the calendar's panel
 * can never disagree about what colour a task is.
 *
 * There is ONE reading of a task's colour: the colour itself, opaque, wherever the task is drawn — the tree's cell, the
 * calendar's panel, a chip naming it. A pale tint or a 30 % wash (the two readings there were) compressed the colours
 * into a corner of the cube and merged them. What is drawn ON a task's colour takes [foreground]: black or white,
 * whichever has the higher WCAG contrast with it.
 */
internal object TaskPalette {

    /**
     * **What to draw on [background]** for the most contrast it can have (WCAG 2): black or white
     * ([TaskColorCurve.bestForeground]). Every element drawn over a task's colour — a title, a percentage, an arrow, a
     * period's marking, a graduation line — takes it where it overlaps that colour.
     */
    fun foreground(background: Color): Color =
        if (TaskColorCurve.bestForeground(background.toArgb() and 0xFFFFFF) == TaskColorCurve.BLACK) Color.Black else Color.White

    /**
     * Every task's colour, for the task tree. Takes the solved hues rather than the state: solving is [TaskHueMemo]'s
     * business (it holds the previous answer the tie-breaks are settled against, and the debounce), and going
     * through it is what keeps this reading and the calendar's identical.
     */
    fun sheetColors(hues: Map<TaskId, TaskColorSpace.TaskHue>): Map<TaskId, Color> =
        TaskColorCube.colors(hues).mapValues { (_, rgb) -> Color(0xFF000000.toInt() or rgb) }

    /** Every task's colour, for the calendar. Same hues, same source, same colours — see [sheetColors]. */
    fun accentColors(hues: Map<TaskId, TaskColorSpace.TaskHue>): Map<TaskId, Color> = sheetColors(hues)
}
