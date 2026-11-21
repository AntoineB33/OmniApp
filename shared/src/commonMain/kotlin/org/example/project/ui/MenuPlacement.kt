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
    if (list == null) return spotOff({ anchor }, size, area, sides)
    // User rule 2026-11-21: *"Make sure every pop-up window that appears from a caller is stuck to the caller. Here, the
    // caller is the row, so if it is the last row the window can appear on the left, the right or the bottom."* The row as
    // the list SHOWS it ([shownRow]); below only from the last row shown, above only from the first — anywhere else, off
    // the list's bottom or top stood away from the row. Off the row's sides, it stays across the row ([acrossRow]).
    //
    // Same day: *"Sometimes the window appears on the caller. When the caller is a row, the window that appears must hide
    // as little as possible the other rows."* Every spot against the row that holds the pop-up whole is a candidate — at
    // its left or right (hiding no row), below or above it (hiding the rows there, none from the last or the first row) —
    // and the one hiding the least of the list wins, the sides' order breaking ties. Off the list's bottom or top from a
    // row in the middle stood away from it, and no spot at all slid the pop-up back over the row.
    val row = shownRow(anchor, list)
    fun hides(at: IntOffset): Long {
        val w = minOf(at.x + size.width, list.right) - maxOf(at.x, list.left)
        val h = minOf(at.y + size.height, list.bottom) - maxOf(at.y, list.top)
        return if (w <= 0 || h <= 0) 0L else w.toLong() * h
    }
    return spotOff({ row }, size, area, sides, acrossRow = true, pick = { spots -> spots.minByOrNull(::hides) })
}

/** Where a pop-up stands and the size it is shown at ([menuFit]): its own, or less where that keeps rows in view. */
internal data class MenuFit(val at: IntOffset, val size: IntSize)

/**
 * The least a pop-up is shrunk to rather than hide a row or leave its caller ([menuFit]), in screen units: smaller, it
 * would show too little to be used, and it covers rows at its own size instead.
 */
internal const val POPUP_MIN_WIDTH = 240
internal const val POPUP_MIN_HEIGHT = 160

/**
 * User rule 2026-11-21: *"Make sure the pop-up window is stuck to the caller (a row or a button). Its position and sizes
 * must be adjusted for the best. It must avoid as much as possible to hide the rows in the same section as the caller,
 * even if it means reducing the size of the pop-up window. It must prioritize appearing to the left and to the top."*
 * **Where a pop-up (not a right-click's menu) stands, and at what size**, in this order:
 *  1. [menuSpot]'s spot, where it holds the pop-up WHOLE, stuck to its caller and hiding no row of its [list];
 *  2. else the pop-up made smaller, on a side of its caller where it hides no row: at the left or the right of the row
 *     as the list shows it, above the first row, below the last; of a field or a button, any side. The spot keeping the
 *     most of it wins, [sides]' order (left and top first) breaking ties. Never below [POPUP_MIN_WIDTH] x
 *     [POPUP_MIN_HEIGHT] (or its own size, when smaller). Beside its caller it reaches up first, above or below it left;
 *  3. else [menuSpot]'s spot at its own size: the one hiding the fewest rows.
 */
internal fun menuFit(
    anchor: IntRect,
    size: IntSize,
    area: IntRect,
    sides: List<MenuSide> = MenuSide.POPUP,
    list: IntRect? = null,
): MenuFit {
    val spot = menuSpot(anchor, size, area, sides, list)
    val caller = if (list == null) anchor else shownRow(anchor, list)
    fun rect(at: IntOffset, s: IntSize) = IntRect(at.x, at.y, at.x + s.width, at.y + s.height)
    fun inArea(r: IntRect) = r.left >= area.left && r.top >= area.top && r.right <= area.right && r.bottom <= area.bottom
    fun hidesNone(r: IntRect) = list == null || !r.overlaps(list)
    val shown = rect(spot, size)
    if (inArea(shown) && !shown.overlaps(caller) && hidesNone(shown) && stuckTo(shown, anchor, list)) return MenuFit(spot, size)
    // Above or below a row only from the first or the last: elsewhere the rows between would be hidden, or the pop-up
    // would stand away from its row.
    val first = list == null || caller.top <= list.top + 1
    val last = list == null || caller.bottom >= list.bottom - 1
    val minW = minOf(size.width, POPUP_MIN_WIDTH)
    val minH = minOf(size.height, POPUP_MIN_HEIGHT)
    // Along a side, inside the room and across the caller (its start at or before the caller's, its end at or after).
    fun along(preferred: Int, lo: Int, hi: Int, length: Int, callerStart: Int, callerEnd: Int): Int =
        preferred.coerceIn(lo, maxOf(lo, hi - length))
            .coerceIn(minOf(callerEnd - length, callerStart), maxOf(callerEnd - length, callerStart))
    val shrunk = sides.distinct().mapNotNull { side ->
        val left = side == MenuSide.Left || side == MenuSide.LeftTop
        val horizontal = left || side == MenuSide.Right || side == MenuSide.RightTop
        val above = side == MenuSide.Above || side == MenuSide.AboveEnd
        if (!horizontal && (if (above) !first else !last)) return@mapNotNull null
        val w = minOf(size.width, if (!horizontal) area.width else if (left) caller.left - area.left else area.right - caller.right)
        val h = minOf(size.height, if (horizontal) area.height else if (above) caller.top - area.top else area.bottom - caller.bottom)
        if (w < minW || h < minH) return@mapNotNull null
        val at =
            if (horizontal) {
                IntOffset(if (left) caller.left - w else caller.right, along(caller.bottom - h, area.top, area.bottom, h, caller.top, caller.bottom))
            } else {
                IntOffset(along(caller.right - w, area.left, area.right, w, caller.left, caller.right), if (above) caller.top - h else caller.bottom)
            }
        val r = rect(at, IntSize(w, h))
        if (inArea(r) && !r.overlaps(caller) && hidesNone(r)) MenuFit(at, IntSize(w, h)) else null
    }
    // The most of the pop-up kept; the sides' order breaks ties (maxBy keeps the first of equals).
    shrunk.maxByOrNull { it.size.width.toLong() * it.size.height }?.let { return it }
    return MenuFit(spot, size)
}

/**
 * User rule 2026-11-21: **the row a pop-up hangs from, as its [list] shows it** — the list's width at the row's height. A
 * row may be laid out wider than the list that shows it (a menu's rows are, their end out of view): its sides are the
 * list's edges, never the row's own far end.
 */
internal fun shownRow(anchor: IntRect, list: IntRect): IntRect = IntRect(list.left, anchor.top, list.right, anchor.bottom)


private fun spotOff(
    anchorOf: (MenuSide) -> IntRect,
    size: IntSize,
    area: IntRect,
    sides: List<MenuSide>,
    /**
     * A row's pop-up (user rule 2026-11-21): sliding along the side it stands on, it never leaves the row — at its left or
     * right it keeps the row's height within its own, below or above the row's width within its own ([acrossRow]).
     */
    acrossRow: Boolean = false,
    /** Which of the spots that hold it whole, in the order of [sides], it takes: the first by default. */
    pick: (List<IntOffset>) -> IntOffset? = { it.firstOrNull() },
): IntOffset {
    fun slideX(x: Int): Int {
        val slid = x.coerceIn(area.left, maxOf(area.left, area.right - size.width))
        if (!acrossRow) return slid
        val row = anchorOf(MenuSide.Left)
        // Its left at or before the row's, its right at or after the row's (one narrower than the row: inside it).
        return slid.coerceIn(minOf(row.right - size.width, row.left), maxOf(row.right - size.width, row.left))
    }
    fun slideY(y: Int): Int {
        val slid = y.coerceIn(area.top, maxOf(area.top, area.bottom - size.height))
        if (!acrossRow) return slid
        val row = anchorOf(MenuSide.Left)
        // Its top at or above the row's, its bottom at or below the row's (one that is not as tall as the row: inside it).
        val lo = minOf(row.bottom - size.height, row.top)
        val hi = maxOf(row.bottom - size.height, row.top)
        return slid.coerceIn(lo, hi)
    }
    // User rule 2026-11-21: *"It must prioritize appearing to the left and to the top"* (a picture-in-picture video in the
    // screen's bottom right corner): a row's pop-up beside its row reaches up from it, its bottom on the row's bottom,
    // sliding down only where the top of the room stops it.
    fun sideTop(anchor: IntRect): Int = if (acrossRow) anchor.bottom - size.height else anchor.top
    fun whole(x: Int, y: Int): IntOffset? =
        if (x >= area.left && y >= area.top && x + size.width <= area.right && y + size.height <= area.bottom) IntOffset(x, y) else null
    fun on(side: MenuSide): IntOffset? {
        val anchor = anchorOf(side)
        return when (side) {
            MenuSide.Below -> if (anchor.bottom + size.height <= area.bottom) IntOffset(slideX(anchor.left), maxOf(anchor.bottom, area.top)) else null
            MenuSide.Above -> if (anchor.top - size.height >= area.top) IntOffset(slideX(anchor.left), minOf(anchor.top - size.height, area.bottom - size.height)) else null
            MenuSide.Right -> if (anchor.right + size.width <= area.right) IntOffset(maxOf(anchor.right, area.left), slideY(sideTop(anchor))) else null
            MenuSide.Left -> if (anchor.left - size.width >= area.left) IntOffset(minOf(anchor.left - size.width, area.right - size.width), slideY(sideTop(anchor))) else null
            // A pop-up's four spots hold it whole where they stand, unslid — sliding is only the fallback below.
            MenuSide.LeftTop -> whole(anchor.left - size.width, anchor.top)
            MenuSide.AboveEnd -> whole(anchor.right - size.width, anchor.top - size.height)
            MenuSide.BelowEnd -> whole(anchor.right - size.width, anchor.bottom)
            MenuSide.RightTop -> whole(anchor.right, anchor.top)
        }
    }
    pick(sides.mapNotNull(::on))?.let { return it }
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
    // A row's pop-up (user rule 2026-11-21): never on the row as the list shows it — over other rows only where [menuSpot]
    // found no spot that hides fewer, which a pop-up stuck to the row keeps.
    val caller = if (list == null) anchor else shownRow(anchor, list)
    return onScreen && !at.overlaps(caller) && (!stuck || stuckTo(at, anchor, list))
}

/**
 * Anomaly 2026-11-19 (*"sometimes I click on the down arrow, hovers over the newly open window which rapidly closes"*): **a
 * pop-up whose size changed, put back against its caller by the edge that touched it** — the edge the pointer crosses
 * coming from the caller — the rest of it growing or shrinking away. Placed afresh ([menuSpot]) it could move from under the
 * pointer, and a hover pop-up the pointer has left closes ([MenuHoverTracker]). [current] is where it stands, [size] its new
 * size. Null where that spot does not hold it (off the screen, over its caller or its list): it is then placed afresh.
 */
internal fun reStuck(current: IntOffset, size: IntSize, anchor: IntRect, area: IntRect, list: IntRect? = null): IntOffset? {
    val row = list?.let { shownRow(anchor, it) } ?: anchor
    val at =
        when {
            // At its caller's left: its right edge back on the caller's left edge.
            current.x < row.left && current.y < row.bottom && current.y + size.height > row.top -> IntOffset(row.left - size.width, current.y)
            // Above it: its bottom edge back on the caller's top edge.
            current.y < row.top -> IntOffset(current.x, row.top - size.height)
            // At its right, or below: the touching edge (its left, its top) has not moved.
            else -> current
        }
    return at.takeIf { keepsSpot(it, size, anchor, area, list, stuck = true) }
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
         * below, else above — over its grandparents' pop-ups if that is where there is room. User rule 2026-11-21: *"It must
         * prioritize appearing to the left and to the top"* — at its left, else above, else at its right, else below (a row's
         * pop-up still takes the spot hiding the fewest rows first, [menuFit]).
         */
        val SUBMENU: List<MenuSide> = listOf(Left, Above, Right, Below)

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
 * caller."* **Whether a pop-up at [at] still touches what it hangs from**: one of its sides on one of [anchor]'s — a row of a
 * [list]: of the row as the list shows it ([shownRow], user rule 2026-11-21) — the two overlapping along that side. A pixel of
 * rounding is no gap.
 */
internal fun stuckTo(at: IntRect, anchor: IntRect, list: IntRect? = null): Boolean {
    fun near(a: Int, b: Int) = kotlin.math.abs(a - b) <= 1
    fun touches(r: IntRect): Boolean {
        val acrossY = at.top < r.bottom && r.top < at.bottom || (r.height == 0 && at.top <= r.top && r.top <= at.bottom)
        val acrossX = at.left < r.right && r.left < at.right || (r.width == 0 && at.left <= r.left && r.left <= at.right)
        return (acrossY && (near(at.right, r.left) || near(at.left, r.right))) || (acrossX && (near(at.bottom, r.top) || near(at.top, r.bottom)))
    }
    // User rule 2026-11-21: a row of a list is the row as the list shows it ([shownRow]) — its own far end may be out of
    // view, and the list's bottom or top is the row's only for the last or the first row shown.
    return touches(if (list == null) anchor else shownRow(anchor, list))
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
