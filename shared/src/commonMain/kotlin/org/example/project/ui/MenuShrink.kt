package org.example.project.ui

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.dp

/**
 * How many pixels a menu is shown SHORTER than its own height (user rule 2026-11-15: made shorter rather than over the
 * picture-in-picture, [menuPlace]) — 0 at its own height. Provided by the desktop's menu window; nothing provides it
 * elsewhere, where a menu is material's and never made shorter.
 */
val LocalMenuShortfall = compositionLocalOf { 0 }

/**
 * Anomaly 2026-11-15 (*"there are two vertical scrollbars when the height is adjusted to not hide the pip"*): **the part
 * of a menu that scrolls on its own gives up the height the menu lost**, so the menu window's own scroll has nothing to
 * scroll and only ONE bar shows. Put on that part, outside its height bound and its scroll (the ▾ tree's columns, a
 * drop-down's list). At its own height it measures as ever and remembers how tall it was; shown [LocalMenuShortfall]
 * shorter, it is that much shorter, never under a row or two.
 */
fun Modifier.shrinksInMenu(): Modifier = composed {
    val shortfall = LocalMenuShortfall.current
    val ownHeight = remember { intArrayOf(-1) }
    layout { measurable, constraints ->
        val bounded =
            if (shortfall <= 0 || ownHeight[0] < 0) {
                constraints
            } else {
                val floor = minOf(ownHeight[0], MIN_SHRUNK_PART.roundToPx())
                val target = (ownHeight[0] - shortfall).coerceAtLeast(floor)
                constraints.copy(maxHeight = target.coerceIn(constraints.minHeight, constraints.maxHeight))
            }
        val placeable = measurable.measure(bounded)
        if (shortfall <= 0) ownHeight[0] = placeable.height
        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }
}

/** The shortest a part that scrolls is made in a shortened menu ([shrinksInMenu]): a row or two. */
private val MIN_SHRUNK_PART = 72.dp
