package org.example.project

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import kotlin.test.Test
import kotlin.test.assertEquals
import org.example.project.ui.menuSpot

/**
 * User rule 2026-10-14: *"The right-click menu is hiding the field, even though there are spaces at the bottom. If
 * there is no space below, it must be at the top, then at the right, then at the left."* — [menuSpot], where every
 * desktop menu opens.
 */
class MenuPlacementTest {
    private val screen = IntRect(0, 0, 1000, 800)
    private val menu = IntSize(150, 300)

    @Test
    fun a_menu_opens_below_else_above_else_at_the_right_else_at_the_left_never_over_its_field() {
        // Room below: under the field, at its start.
        assertEquals(IntOffset(100, 130), menuSpot(IntRect(100, 100, 200, 130), menu, screen))
        // None below, room above.
        assertEquals(IntOffset(100, 300), menuSpot(IntRect(100, 600, 200, 630), menu, screen))
        // A screen too short for above and below: at the right, its top on the field's top.
        val short = IntRect(0, 0, 1000, 400)
        assertEquals(IntOffset(200, 100), menuSpot(IntRect(100, 200, 200, 230), menu, short))
        // …and at the left where the right has no room.
        assertEquals(IntOffset(750, 100), menuSpot(IntRect(900, 200, 1000, 230), menu, short))
        // Along its side it slides onto the screen: a field at the right edge has its menu below it, moved left.
        assertEquals(IntOffset(850, 130), menuSpot(IntRect(950, 100, 1000, 130), menu, screen))
        // A right-click is a point: below and right of it.
        assertEquals(IntOffset(400, 300), menuSpot(IntRect(400, 300, 400, 300), menu, screen))
    }
}
