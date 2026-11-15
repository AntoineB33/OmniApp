package org.example.project.ui

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import kotlin.math.abs

/**
 * User rule 2026-11-15: *"I want every window to adjust, and not just to the pip but to anything above the app, when a
 * window in the app opens, it must adjust its position and size. If the user drags something over the app, it doesn't
 * adjust in live, only when the window in the app opens or stops being minimized in the system tray of the app."*
 *
 * **The other applications' windows standing over the app, in pixels of the app window's content** — the coordinates
 * `positionInWindow` speaks. Asked as a window of the app opens or comes back from the reduce bar
 * ([WindowFrameState.avoidCovering]), never watched. Given by the desktop's `main` (Windows: `WindowsAboveApp`);
 * nothing elsewhere, where no other application's window stands over the app's.
 */
val LocalCoveringWindows = staticCompositionLocalOf<(() -> List<Rect>)?> { null }

/**
 * **Where a window [window] goes inside [area] to be clear of [covering]**, or null where it stays as it is.
 *  1. Clear already, and whole inside [area]: it stays (null).
 *  2. Else the nearest place for it at its size, inside [area] and clear — tried against each covering window's edges
 *     and the area's: beside one of them, or against the area's side.
 *  3. Else the largest clear room in [area] ([freeRooms]): it is made to fit there, as big as it was where it can,
 *     never under [minSize], placed in that room as near as it can be to where it was.
 *  4. Else, nothing clear is big enough: it stays (null) — the window over the app comes second to a usable window.
 * Only what covers [area] counts.
 */
internal fun fitClear(window: Rect, area: Rect, covering: List<Rect>, minSize: Size): Rect? {
    val obstacles = covering.mapNotNull { it.intersectOrNull(area) }
    fun clear(r: Rect) = obstacles.none { it.overlapsStrictly(r) }
    fun inside(r: Rect) = r.left >= area.left - EPS && r.top >= area.top - EPS && r.right <= area.right + EPS && r.bottom <= area.bottom + EPS
    if (obstacles.isEmpty() || (clear(window) && inside(window))) return null
    val w = window.width
    val h = window.height
    // 2. At its size: every left and top an edge of the area or of a window over it allows, nearest first.
    if (w <= area.width && h <= area.height) {
        val xs = (listOf(window.left, area.left, area.right - w) + obstacles.flatMap { listOf(it.right, it.left - w) })
            .map { it.coerceIn(area.left, area.right - w) }.distinct()
        val ys = (listOf(window.top, area.top, area.bottom - h) + obstacles.flatMap { listOf(it.bottom, it.top - h) })
            .map { it.coerceIn(area.top, area.bottom - h) }.distinct()
        val best = xs.flatMap { x -> ys.map { y -> Rect(x, y, x + w, y + h) } }
            .filter { clear(it) }
            .minByOrNull { abs(it.left - window.left) + abs(it.top - window.top) }
        if (best != null) return best
    }
    // 3. Made to fit the largest clear room.
    val room = freeRooms(area, obstacles)
        .map { it to Size(minOf(w, it.width), minOf(h, it.height)) }
        .filter { (_, s) -> s.width >= minOf(minSize.width, w) - EPS && s.height >= minOf(minSize.height, h) - EPS }
        .maxWithOrNull(compareBy<Pair<Rect, Size>> { (_, s) -> s.width * s.height }.thenBy { (r, _) -> -distance(r, window) })
        ?: return null
    val (free, size) = room
    val x = window.left.coerceIn(free.left, free.right - size.width)
    val y = window.top.coerceIn(free.top, free.bottom - size.height)
    return Rect(x, y, x + size.width, y + size.height)
}

/**
 * The rooms of [area] that no one of [obstacles] covers, each as large as it can be across both axes: every rectangle
 * whose four edges are edges of the area or of an obstacle, clear of all of them, and none inside another. A few
 * windows over the app make a few dozen candidates.
 */
internal fun freeRooms(area: Rect, obstacles: List<Rect>): List<Rect> {
    val xs = (listOf(area.left, area.right) + obstacles.flatMap { listOf(it.left, it.right) })
        .filter { it >= area.left && it <= area.right }.distinct().sorted()
    val ys = (listOf(area.top, area.bottom) + obstacles.flatMap { listOf(it.top, it.bottom) })
        .filter { it >= area.top && it <= area.bottom }.distinct().sorted()
    val clear = ArrayList<Rect>()
    for (i in xs.indices) for (j in i + 1 until xs.size) for (k in ys.indices) for (l in k + 1 until ys.size) {
        val r = Rect(xs[i], ys[k], xs[j], ys[l])
        if (obstacles.none { it.overlapsStrictly(r) }) clear += r
    }
    return clear.filter { r -> clear.none { o -> o != r && o.contains(r) } }
}

private const val EPS = 0.5f

private fun Rect.overlapsStrictly(other: Rect): Boolean =
    left < other.right - EPS && other.left < right - EPS && top < other.bottom - EPS && other.top < bottom - EPS

private fun Rect.intersectOrNull(other: Rect): Rect? {
    val r = intersect(other)
    return r.takeIf { it.width > EPS && it.height > EPS }
}

private fun Rect.contains(other: Rect): Boolean =
    left <= other.left && top <= other.top && right >= other.right && bottom >= other.bottom

private fun distance(room: Rect, window: Rect): Float {
    val dx = maxOf(room.left - window.left, 0f, window.left - (room.right - minOf(window.width, room.width)))
    val dy = maxOf(room.top - window.top, 0f, window.top - (room.bottom - minOf(window.height, room.height)))
    return dx + dy
}
