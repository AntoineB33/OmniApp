package org.example.project.ui

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize

/**
 * **Where a menu of [size] opens beside what it is about, [anchor]** (a field, a row, or the point of a right-click),
 * inside [area] (the work area of the screen it opens on) — user rule 2026-10-14: *"The right-click menu is hiding
 * the field, even though there are spaces at the bottom. If there is no space below, it must be at the top, then at
 * the right, then at the left."* The first side, in the order of [sides], the WHOLE menu fits on:
 *  - below: its top on the anchor's bottom, its start on the anchor's start;
 *  - above: its bottom on the anchor's top;
 *  - at the right: its start on the anchor's end, its top on the anchor's top;
 *  - at the left: its end on the anchor's start.
 * Along the side it opens on, it slides to stay on the screen (a field near the right edge has its menu below it,
 * moved left), which never puts it over the anchor.
 *
 * User rule 2026-11-15: *"I'd like the pop-up windows to avoid hiding the pip"* — [avoid] are other applications'
 * windows the menu keeps off (Firefox's picture-in-picture). **They choose among the four spots, never add one**
 * (anomaly the same day: *"the pop-up appears far away to avoid the pip"* — a spot covering the video was slid along
 * its side past it):
 *  1. the first side, in order, whose spot holds the whole menu clear of them;
 *  2. else **the menu is made shorter** (anomaly the same day: *"the pop-up window hides the pip. It must find the best
 *     position (left then bottom then top then right). If the position choice still hides the pip, the size is
 *     adjusted."*): the first side, in order, where it fits whole on the screen and a shorter menu — its edge on the
 *     anchor's side kept, the far edge brought in to the video — is at least [minHeight] tall ([MenuPlace.height]);
 *  3. else they are not asked about any more: the anchor comes before them.
 *
 * Where no side has room for the whole menu, it goes on the side with the most room for its size (user rule
 * 2026-11-15: *"depending on the available space, then on its size"*), pushed back onto the screen.
 */
internal fun menuPlace(
    anchor: IntRect,
    size: IntSize,
    area: IntRect,
    sides: List<MenuSide> = MenuSide.DEFAULT,
    avoid: List<IntRect> = emptyList(),
    /** The shortest a menu is made to keep off [avoid]; at most its own height. */
    minHeight: Int = MIN_SHORTENED_HEIGHT,
): MenuPlace {
    fun slideX(x: Int) = x.coerceIn(area.left, maxOf(area.left, area.right - size.width))
    fun slideY(y: Int) = y.coerceIn(area.top, maxOf(area.top, area.bottom - size.height))
    fun clear(spot: IntOffset): Boolean {
        val at = IntRect(spot.x, spot.y, spot.x + size.width, spot.y + size.height)
        return avoid.none { it.overlaps(at) }
    }
    fun spotOn(side: MenuSide): IntOffset? =
        when (side) {
            MenuSide.Below -> if (anchor.bottom + size.height <= area.bottom) IntOffset(slideX(anchor.left), maxOf(anchor.bottom, area.top)) else null
            MenuSide.Above -> if (anchor.top - size.height >= area.top) IntOffset(slideX(anchor.left), minOf(anchor.top - size.height, area.bottom - size.height)) else null
            MenuSide.Right -> if (anchor.right + size.width <= area.right) IntOffset(maxOf(anchor.right, area.left), slideY(anchor.top)) else null
            MenuSide.Left -> if (anchor.left - size.width >= area.left) IntOffset(minOf(anchor.left - size.width, area.right - size.width), slideY(anchor.top)) else null
        }
    // The menu at [spot] made shorter to keep off [avoid]: its top kept (its bottom, above the anchor), the other edge
    // brought in to the nearest video across its width. Null where a video covers the kept edge, or it gets too short.
    fun shortened(side: MenuSide, spot: IntOffset): MenuPlace? {
        val left = spot.x
        val right = spot.x + size.width
        var top = spot.y
        var bottom = spot.y + size.height
        val across = avoid.filter { it.left < right && it.right > left }
        if (side == MenuSide.Above) {
            for (v in across.sortedByDescending { it.bottom }) if (v.top < bottom && v.bottom > top) {
                if (v.bottom >= bottom) return null
                top = maxOf(top, v.bottom)
            }
        } else {
            for (v in across.sortedBy { it.top }) if (v.top < bottom && v.bottom > top) {
                if (v.top <= top) return null
                bottom = minOf(bottom, v.top)
            }
        }
        val height = bottom - top
        return if (height >= minOf(minHeight, size.height)) MenuPlace(IntOffset(left, top), height) else null
    }
    if (avoid.isNotEmpty()) {
        for (side in sides) spotOn(side)?.takeIf { clear(it) }?.let { return MenuPlace(it, size.height) }
        for (side in sides) spotOn(side)?.let { spot -> shortened(side, spot)?.let { return it } }
    }
    for (side in sides) spotOn(side)?.let { return MenuPlace(it, size.height) }
    // No side holds it whole: the side with the most room for its size, the menu pushed back onto the screen.
    val room = sides.maxByOrNull { side ->
        when (side) {
            MenuSide.Below -> (area.bottom - anchor.bottom).toFloat() / size.height.coerceAtLeast(1)
            MenuSide.Above -> (anchor.top - area.top).toFloat() / size.height.coerceAtLeast(1)
            MenuSide.Right -> (area.right - anchor.right).toFloat() / size.width.coerceAtLeast(1)
            MenuSide.Left -> (anchor.left - area.left).toFloat() / size.width.coerceAtLeast(1)
        }
    } ?: MenuSide.Below
    val at = when (room) {
        MenuSide.Below -> IntOffset(slideX(anchor.left), slideY(anchor.bottom))
        MenuSide.Above -> IntOffset(slideX(anchor.left), slideY(anchor.top - size.height))
        MenuSide.Right -> IntOffset(slideX(anchor.right), slideY(anchor.top))
        MenuSide.Left -> IntOffset(slideX(anchor.left - size.width), slideY(anchor.top))
    }
    return MenuPlace(at, size.height)
}

/** Where a menu stands ([menuPlace]) and how tall it is there: its own height, or less to keep off a video. */
internal data class MenuPlace(val offset: IntOffset, val height: Int)

/** The shortest a menu is made to keep off a picture-in-picture ([menuPlace]): a bar and a few rows. */
internal const val MIN_SHORTENED_HEIGHT: Int = 160

/** [menuPlace]'s spot alone, for a caller that cannot make its menu shorter (the task picker). */
internal fun menuSpot(
    anchor: IntRect,
    size: IntSize,
    area: IntRect,
    sides: List<MenuSide> = MenuSide.DEFAULT,
    avoid: List<IntRect> = emptyList(),
): IntOffset = menuPlace(anchor, size, area, sides, avoid, minHeight = Int.MAX_VALUE).offset

/**
 * Anomaly 2026-11-11: **an open menu whose content changes size stays where it is** while it still fits on the screen
 * ([area]) and stays off its [anchor]. Placed again, a menu pushed up to fit slid back down as a search typed in it
 * shrank its list: the field left the pointer, which was then over nothing, and the configurations tree vanished
 * ([MenuHoverTracker]). Where it no longer fits, would cover its anchor, or covers a window it keeps off ([avoid]), it
 * is placed again ([menuSpot]).
 */
internal fun keepsSpot(current: IntOffset, size: IntSize, anchor: IntRect, area: IntRect, avoid: List<IntRect> = emptyList()): Boolean {
    val at = IntRect(current.x, current.y, current.x + size.width, current.y + size.height)
    val onScreen = at.left >= area.left && at.top >= area.top && at.right <= area.right && at.bottom <= area.bottom
    // Nor while it covers a window it keeps off ([menuSpot]'s avoid): the picture-in-picture moved onto it.
    return onScreen && !at.overlaps(anchor) && avoid.none { it.overlaps(at) }
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

        /**
         * User rule 2026-11-15, a pop-up opened from a field or a button (a click, a hover, a key — not a right-click):
         * *"A pop-up window opened from a field/button must adjust its position on four choices"*, in the order *"left
         * then bottom then top then right"* (the same day, superseding right, left, bottom, top). A right-click's menu keeps [DEFAULT]; a call site that names its sides ([SUBMENU]) keeps them.
         */
        val POPUP: List<MenuSide> = listOf(Left, Below, Above, Right)
    }
}

/**
 * The sides the menus opened inside it try ([MenuSide]), read by the desktop's menus as they open; where a platform
 * draws material's menu, material places it. A menu's own content is given back the default.
 */
val LocalMenuSides = androidx.compose.runtime.compositionLocalOf { MenuSide.DEFAULT }
