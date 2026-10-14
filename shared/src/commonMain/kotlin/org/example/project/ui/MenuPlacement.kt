package org.example.project.ui

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize

/**
 * **Where a menu of [size] opens beside what it is about, [anchor]** (a field, a row, or the point of a right-click),
 * inside [area] (the work area of the screen it opens on) — user rule 2026-10-14: *"The right-click menu is hiding
 * the field, even though there are spaces at the bottom. If there is no space below, it must be at the top, then at
 * the right, then at the left."* The first side the WHOLE menu fits on:
 *  - below: its top on the anchor's bottom, its start on the anchor's start;
 *  - above: its bottom on the anchor's top;
 *  - at the right: its start on the anchor's end, its top on the anchor's top;
 *  - at the left: its end on the anchor's start.
 * Along the side it opens on, it slides to stay on the screen (a field near the right edge has its menu below it,
 * moved left), which never puts it over the anchor. Only where no side has room does it cover the anchor: below,
 * pushed back onto the screen.
 */
internal fun menuSpot(anchor: IntRect, size: IntSize, area: IntRect): IntOffset {
    fun slideX(x: Int) = x.coerceIn(area.left, maxOf(area.left, area.right - size.width))
    fun slideY(y: Int) = y.coerceIn(area.top, maxOf(area.top, area.bottom - size.height))
    return when {
        anchor.bottom + size.height <= area.bottom -> IntOffset(slideX(anchor.left), maxOf(anchor.bottom, area.top))
        anchor.top - size.height >= area.top -> IntOffset(slideX(anchor.left), minOf(anchor.top - size.height, area.bottom - size.height))
        anchor.right + size.width <= area.right -> IntOffset(maxOf(anchor.right, area.left), slideY(anchor.top))
        anchor.left - size.width >= area.left -> IntOffset(minOf(anchor.left - size.width, area.right - size.width), slideY(anchor.top))
        else -> IntOffset(slideX(anchor.left), slideY(anchor.bottom))
    }
}
