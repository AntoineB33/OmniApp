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
 * pushed back onto the screen. [sides] is the order the sides are tried in ([MenuSide.DEFAULT], the rule above).
 */
internal fun menuSpot(anchor: IntRect, size: IntSize, area: IntRect, sides: List<MenuSide> = MenuSide.DEFAULT): IntOffset {
    fun slideX(x: Int) = x.coerceIn(area.left, maxOf(area.left, area.right - size.width))
    fun slideY(y: Int) = y.coerceIn(area.top, maxOf(area.top, area.bottom - size.height))
    for (side in sides) {
        when (side) {
            MenuSide.Below -> if (anchor.bottom + size.height <= area.bottom) return IntOffset(slideX(anchor.left), maxOf(anchor.bottom, area.top))
            MenuSide.Above -> if (anchor.top - size.height >= area.top) return IntOffset(slideX(anchor.left), minOf(anchor.top - size.height, area.bottom - size.height))
            MenuSide.Right -> if (anchor.right + size.width <= area.right) return IntOffset(maxOf(anchor.right, area.left), slideY(anchor.top))
            MenuSide.Left -> if (anchor.left - size.width >= area.left) return IntOffset(minOf(anchor.left - size.width, area.right - size.width), slideY(anchor.top))
        }
    }
    return IntOffset(slideX(anchor.left), slideY(anchor.bottom))
}

/** A side of its anchor a menu opens on ([menuSpot]), tried in the order a menu is given. */
enum class MenuSide {
    Below, Above, Right, Left;

    companion object {
        /** Every menu's (user rule 2026-10-14): below, then above, then at the right, then at the left. */
        val DEFAULT: List<MenuSide> = listOf(Below, Above, Right, Left)

        /**
         * User rule 2026-11-04, the configurations tree's: a category's children open at its left, else at its right, else
         * below, else above — over its grandparents' pop-ups if that is where there is room.
         */
        val SUBMENU: List<MenuSide> = listOf(Left, Right, Below, Above)
    }
}

/**
 * The sides the menus opened inside it try ([MenuSide]), read by the desktop's menus as they open; where a platform
 * draws material's menu, material places it. A menu's own content is given back the default.
 */
val LocalMenuSides = androidx.compose.runtime.compositionLocalOf { MenuSide.DEFAULT }
