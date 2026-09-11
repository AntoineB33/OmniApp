package org.example.project.ui

import androidx.compose.ui.input.pointer.PointerIcon

/**
 * PRD §8 extend/shorten: the standard "grab this edge to resize" mouse cursor shown when hovering a task
 * panel's top/bottom edge. On desktop this is the OS vertical-resize cursor; other platforms fall back to
 * a crosshair (no OS resize cursor concept).
 */
expect fun verticalResizePointerIcon(): PointerIcon

/**
 * PRD §8 Overlap Mode: the "grab this edge to re-divide the shared width" cursor, shown on the vertical
 * boundary between two panels sharing a column's width (a [WeightHandle]). The horizontal counterpart of
 * [verticalResizePointerIcon] — a width edge is dragged sideways, so it must not read as a time edge.
 */
expect fun horizontalResizePointerIcon(): PointerIcon

/** PRD §8: which side of a calendar panel a grab strip resizes — the four sides, each its own shape. */
enum class PanelResizeEdge {
    /** The panel's true start edge: dragging it moves the start time. */
    Top,

    /** The panel's true end edge: dragging it moves the end time. */
    Bottom,

    /** The panel's left width edge: dragging it hands width to the neighbour on its left (Overlap Mode). */
    Left,

    /** The panel's right width edge: dragging it hands width to the neighbour on its right (Overlap Mode). */
    Right,
}

/**
 * PRD §8: the cursor for a calendar panel's resize grab strip — a double arrow along the axis the drag
 * moves in, with **a line perpendicular to it drawn on the side that will be resized**.
 *
 * Four distinct shapes, not two: the bar is what says *which* edge the press will take, so a top strip and
 * a bottom strip can never be confused, and the two halves of a shared-width [WeightHandle] each name the
 * neighbour they lie on (its right edge on the left half, its left edge on the right half).
 *
 * Distinct from [verticalResizePointerIcon] / [horizontalResizePointerIcon], which stay the plain OS
 * double arrows: those mark a **window frame** edge ([WindowFrame]), where there is no second panel on the
 * other side of the line and nothing for the bar to point at.
 *
 * Only desktop has real cursors; every other platform falls back to a crosshair.
 */
expect fun panelResizePointerIcon(edge: PanelResizeEdge): PointerIcon
