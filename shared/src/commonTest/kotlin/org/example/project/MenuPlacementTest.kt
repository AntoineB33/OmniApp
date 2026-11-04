package org.example.project

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import kotlin.test.Test
import kotlin.test.assertEquals
import org.example.project.ui.MenuSide
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

    /** User rule 2026-11-01: *"the menu must always adapt its position for the available space"*. */
    @Test
    fun a_menu_at_the_pointer_stays_whole_on_the_screen_at_every_edge_and_corner() {
        fun at(x: Int, y: Int) = menuSpot(IntRect(x, y, x, y), menu, screen)
        assertEquals(IntOffset(850, 300), at(990, 300), "the right edge: moved left")
        assertEquals(IntOffset(400, 490), at(400, 790), "the bottom edge: above")
        assertEquals(IntOffset(850, 490), at(990, 790), "the bottom right corner")
        assertEquals(IntOffset(0, 0), at(0, 0), "the top left corner")
        for (x in listOf(0, 1, 500, 849, 850, 999, 1000)) {
            for (y in listOf(0, 1, 400, 499, 500, 799, 800)) {
                val spot = at(x, y)
                val inside = spot.x >= 0 && spot.y >= 0 && spot.x + menu.width <= 1000 && spot.y + menu.height <= 800
                assertEquals(true, inside, "a click at ($x, $y) put the menu at $spot")
            }
        }
    }
    /**
     * User rule 2026-11-04, the configurations tree: *"The new pop-up window appears on the left, or if not enough space on
     * the right, then at the bottom, then at the top."*
     */
    @Test
    fun a_categorys_children_open_at_its_left_else_its_right_else_below_else_above() {
        val sub = MenuSide.SUBMENU
        val row = IntRect(400, 100, 600, 130)
        assertEquals(IntOffset(250, 100), menuSpot(row, menu, screen, sub), "at its left, tops aligned")
        assertEquals(IntOffset(200, 100), menuSpot(IntRect(50, 100, 200, 130), menu, screen, sub), "no room at the left: at its right")
        assertEquals(IntOffset(0, 130), menuSpot(IntRect(0, 100, 1000, 130), menu, screen, sub), "no room at either side: below")
        assertEquals(IntOffset(0, 300), menuSpot(IntRect(0, 600, 1000, 630), menu, screen, sub), "nor below: above")
    }
}
