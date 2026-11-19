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
 * Where no side has room for the whole menu, it goes on the side with the most room for its size (user rule
 * 2026-11-15: *"depending on the available space, then on its size"*), pushed back onto the screen.
 *
 * User rule 2026-11-16: *"When a row makes a window appear, it must never hide the list of rows the caller is in. The
 * positions below and above are only possible when it doesn't hide."* Given the [list] the anchor is a row of (its shown
 * bounds), a side at the left or the right stands off the LIST's edge, at the row's height; below or above, off the
 * list's bottom or top. [area] is the app's window since the same day (*"No window must appear outside the app"*).
 */
internal fun menuSpot(
    anchor: IntRect,
    size: IntSize,
    area: IntRect,
    sides: List<MenuSide> = MenuSide.DEFAULT,
    list: IntRect? = null,
): IntOffset {
    fun anchorFor(side: MenuSide): IntRect = when {
        list == null -> anchor
        side == MenuSide.Left || side == MenuSide.Right || side == MenuSide.LeftTop || side == MenuSide.RightTop ->
            IntRect(minOf(anchor.left, list.left), anchor.top, maxOf(anchor.right, list.right), anchor.bottom)
        else -> IntRect(minOf(anchor.left, list.left), minOf(anchor.top, list.top), maxOf(anchor.right, list.right), maxOf(anchor.bottom, list.bottom))
    }
    return spotOff({ anchorFor(it) }, size, area, sides)
}

private fun spotOff(
    anchorOf: (MenuSide) -> IntRect,
    size: IntSize,
    area: IntRect,
    sides: List<MenuSide>,
): IntOffset {
    fun slideX(x: Int) = x.coerceIn(area.left, maxOf(area.left, area.right - size.width))
    fun slideY(y: Int) = y.coerceIn(area.top, maxOf(area.top, area.bottom - size.height))
    fun whole(x: Int, y: Int): IntOffset? =
        if (x >= area.left && y >= area.top && x + size.width <= area.right && y + size.height <= area.bottom) IntOffset(x, y) else null
    fun on(side: MenuSide): IntOffset? {
        val anchor = anchorOf(side)
        return when (side) {
            MenuSide.Below -> if (anchor.bottom + size.height <= area.bottom) IntOffset(slideX(anchor.left), maxOf(anchor.bottom, area.top)) else null
            MenuSide.Above -> if (anchor.top - size.height >= area.top) IntOffset(slideX(anchor.left), minOf(anchor.top - size.height, area.bottom - size.height)) else null
            MenuSide.Right -> if (anchor.right + size.width <= area.right) IntOffset(maxOf(anchor.right, area.left), slideY(anchor.top)) else null
            MenuSide.Left -> if (anchor.left - size.width >= area.left) IntOffset(minOf(anchor.left - size.width, area.right - size.width), slideY(anchor.top)) else null
            // A pop-up's four spots hold it whole where they stand, unslid — sliding is only the fallback below.
            MenuSide.LeftTop -> whole(anchor.left - size.width, anchor.top)
            MenuSide.AboveEnd -> whole(anchor.right - size.width, anchor.top - size.height)
            MenuSide.BelowEnd -> whole(anchor.right - size.width, anchor.bottom)
            MenuSide.RightTop -> whole(anchor.right, anchor.top)
        }
    }
    for (side in sides) on(side)?.let { return it }
    // No side holds it whole: the side with the most room for its size, the menu pushed back onto the screen.
    // A pop-up's side is weighed on both axes: the room off the anchor for its depth, and the screen along the side for
    // its length (it may slide along that side, never off the anchor).
    fun fit(room: Int, need: Int) = minOf(1f, room.toFloat() / need.coerceAtLeast(1))
    val room = sides.maxByOrNull { side ->
        val anchor = anchorOf(side)
        when (side) {
            MenuSide.Below -> (area.bottom - anchor.bottom).toFloat() / size.height.coerceAtLeast(1)
            MenuSide.Above -> (anchor.top - area.top).toFloat() / size.height.coerceAtLeast(1)
            MenuSide.Right -> (area.right - anchor.right).toFloat() / size.width.coerceAtLeast(1)
            MenuSide.Left -> (anchor.left - area.left).toFloat() / size.width.coerceAtLeast(1)
            MenuSide.BelowEnd -> fit(area.bottom - anchor.bottom, size.height) * fit(area.width, size.width)
            MenuSide.AboveEnd -> fit(anchor.top - area.top, size.height) * fit(area.width, size.width)
            MenuSide.RightTop -> fit(area.right - anchor.right, size.width) * fit(area.height, size.height)
            MenuSide.LeftTop -> fit(anchor.left - area.left, size.width) * fit(area.height, size.height)
        }
    } ?: MenuSide.Below
    val anchor = anchorOf(room)
    // Along the side it slides as little as it must from its spot: the anchor at the top of a vertical side, at the
    // right end of a horizontal one.
    return when (room) {
        MenuSide.Below -> IntOffset(slideX(anchor.left), slideY(anchor.bottom))
        MenuSide.Above -> IntOffset(slideX(anchor.left), slideY(anchor.top - size.height))
        MenuSide.Right -> IntOffset(slideX(anchor.right), slideY(anchor.top))
        MenuSide.Left -> IntOffset(slideX(anchor.left - size.width), slideY(anchor.top))
        MenuSide.BelowEnd -> IntOffset(slideX(anchor.right - size.width), slideY(anchor.bottom))
        MenuSide.AboveEnd -> IntOffset(slideX(anchor.right - size.width), slideY(anchor.top - size.height))
        MenuSide.RightTop -> IntOffset(slideX(anchor.right), slideY(anchor.top))
        MenuSide.LeftTop -> IntOffset(slideX(anchor.left - size.width), slideY(anchor.top))
    }
}

/**
 * Anomaly 2026-11-11: **an open menu whose content changes size stays where it is** while it still fits on the screen
 * ([area]) and stays off its [anchor]. Placed again, a menu pushed up to fit slid back down as a search typed in it
 * shrank its list: the field left the pointer, which was then over nothing, and the configurations tree vanished
 * ([MenuHoverTracker]). Where it no longer fits, or would cover its anchor, it is placed again ([menuSpot]).
 */
internal fun keepsSpot(
    current: IntOffset,
    size: IntSize,
    anchor: IntRect,
    area: IntRect,
    list: IntRect? = null,
    /** User rule 2026-11-19: a pop-up (not a right-click's menu) stays only where it still touches its caller ([stuckTo]). */
    stuck: Boolean = false,
): Boolean {
    val at = IntRect(current.x, current.y, current.x + size.width, current.y + size.height)
    val onScreen = at.left >= area.left && at.top >= area.top && at.right <= area.right && at.bottom <= area.bottom
    return onScreen && !at.overlaps(anchor) && (list == null || !at.overlaps(list)) && (!stuck || stuckTo(at, anchor, list))
}

/** A side of its anchor a menu opens on ([menuSpot]), tried in the order a menu is given. */
enum class MenuSide {
    Below, Above, Right, Left,

    /**
     * A pop-up's four spots (user rule 2026-11-15, [POPUP]): named by the pop-up's side stuck to the anchor and where on
     * that side the anchor is. Each holds the pop-up whole where it stands or not at all — no slide before the fallback.
     */
    LeftTop, AboveEnd, BelowEnd, RightTop;

    companion object {
        /** Every menu's (user rule 2026-10-14): below, then above, then at the right, then at the left. */
        val DEFAULT: List<MenuSide> = listOf(Below, Above, Right, Left)

        /**
         * User rule 2026-11-04, the configurations tree's: a category's children open at its left, else at its right, else
         * below, else above — over its grandparents' pop-ups if that is where there is room.
         */
        val SUBMENU: List<MenuSide> = listOf(Left, Right, Below, Above)

        /**
         * User rule 2026-11-15 (second of the day), a pop-up opened from a field or a button (a click, a hover, a key —
         * not a right-click): *"First it checks if there is enough space when choosing the right side to the top, then
         * bottom side to the right, then top side to the right, then left side to the top."* The pop-up's right side
         * on the anchor, the anchor at its top (at the anchor's left, hanging down); its bottom side, the anchor at its
         * right end (above, reaching left); its top side, the same (below, reaching left); its left side, the anchor at
         * its top (at the anchor's right). Supersedes *"left then bottom then top then right"* of the same day. Where
         * none holds it whole, the side with the most room on both axes, slid along it. A right-click's menu keeps
         * [DEFAULT]; a call site that names its sides ([SUBMENU]) keeps them.
         */
        val POPUP: List<MenuSide> = listOf(LeftTop, AboveEnd, BelowEnd, RightTop)
    }
}

/**
 * The sides the menus opened inside it try ([MenuSide]), read by the desktop's menus as they open; where a platform
 * draws material's menu, material places it. A menu's own content is given back the default.
 */
val LocalMenuSides = androidx.compose.runtime.compositionLocalOf { MenuSide.DEFAULT }

/**
 * User rule 2026-11-16: **the list of rows a pop-up's caller stands in**, in the window's pixels as shown (cut where its
 * section cuts it) — what a pop-up opened from a row must not hide ([menuSpot]'s `list`). Given by a list (a Search
 * window's result list, a column of the configurations tree); null outside one.
 */
val LocalCallerList = androidx.compose.runtime.compositionLocalOf<(() -> androidx.compose.ui.geometry.Rect?)?> { null }

/**
 * User rule 2026-11-19: *"Make sure every window that appears by hovering over something, the caller, is always stuck to the
 * caller."* **Whether a pop-up at [at] still touches what it hangs from**: one of its sides on one of [anchor]'s, or — a row
 * of a [list] — on the list's edge at the row's height, or its bottom or top ([menuSpot]'s anchors), the two overlapping
 * along that side. A pixel of rounding is no gap.
 */
internal fun stuckTo(at: IntRect, anchor: IntRect, list: IntRect? = null): Boolean {
    fun near(a: Int, b: Int) = kotlin.math.abs(a - b) <= 1
    fun touches(r: IntRect): Boolean {
        val acrossY = at.top < r.bottom && r.top < at.bottom || (r.height == 0 && at.top <= r.top && r.top <= at.bottom)
        val acrossX = at.left < r.right && r.left < at.right || (r.width == 0 && at.left <= r.left && r.left <= at.right)
        return (acrossY && (near(at.right, r.left) || near(at.left, r.right))) || (acrossX && (near(at.bottom, r.top) || near(at.top, r.bottom)))
    }
    if (touches(anchor)) return true
    list ?: return false
    val row = IntRect(minOf(anchor.left, list.left), anchor.top, maxOf(anchor.right, list.right), anchor.bottom)
    val whole = IntRect(minOf(anchor.left, list.left), minOf(anchor.top, list.top), maxOf(anchor.right, list.right), maxOf(anchor.bottom, list.bottom))
    return touches(row) || touches(whole)
}

/** The side of a pop-up furthest from what it hangs from ([farSideOf]): where the ▾ of its search bar stands. */
enum class FarSide { Left, Right }

/**
 * User rule 2026-11-19: the down arrow of a window that appears stands on its left or its right side, **whichever is further
 * from the caller** — the side of [popup] further from the middle of its [anchor]: a pop-up at its caller's left reads
 * Left, at its right Right; below or above, the side further from the caller's middle. (The same rule for the hover arrow
 * of a row was asked and cancelled the same day: that arrow stays at the row's end.)
 */
fun farSideOf(popup: IntRect, anchor: IntRect): FarSide {
    val middle = (anchor.left + anchor.right) / 2
    return if (kotlin.math.abs(popup.left - middle) > kotlin.math.abs(popup.right - middle)) FarSide.Left else FarSide.Right
}

/**
 * User rule 2026-11-19: **the far side of the pop-up a search bar stands in** ([farSideOf]), given by the pop-up as it is
 * placed; null where it is not known (outside a pop-up, or a platform drawing material's menu): the ▾ then at the bar's right.
 */
val LocalPopupFarSide = androidx.compose.runtime.compositionLocalOf<FarSide?> { null }

/**
 * The sides the ▾'s own tree tries from a bar whose ▾ stands at [far]: on that side first, away from the caller, then a
 * pop-up's own order ([MenuSide.POPUP]).
 */
fun arrowTreeSides(far: FarSide?): List<MenuSide> =
    when (far) {
        FarSide.Right -> listOf(MenuSide.RightTop) + MenuSide.POPUP.filter { it != MenuSide.RightTop }
        FarSide.Left, null -> MenuSide.POPUP
    }
