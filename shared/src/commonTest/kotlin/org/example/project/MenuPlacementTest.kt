package org.example.project

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.example.project.ui.MenuSide
import org.example.project.ui.keepsSpot
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
    /**
     * Anomaly 2026-11-11: a letter typed in the search field of a configuration pop-up shrank its list, and the pop-up,
     * placed again, slid down from under the pointer (y 177 → 285, the pointer at 230): the tree vanished. An open menu
     * that changes size stays where it is while it fits on the screen and keeps off its anchor ([keepsSpot]).
     */
    @Test
    fun an_open_menu_that_changes_size_stays_where_it_is_while_it_may() {
        val row = IntRect(703, 400, 903, 430)
        val shown = IntOffset(503, 177)
        assertTrue(keepsSpot(shown, IntSize(200, 457), row, screen), "shrunk: it stays")
        assertTrue(keepsSpot(shown, IntSize(200, 600), row, screen), "grown, still on the screen: it stays")
        assertFalse(keepsSpot(shown, IntSize(200, 700), row, screen), "off the screen's bottom: placed again")
        assertFalse(keepsSpot(shown, IntSize(250, 300), row, screen), "over its anchor: placed again")
    }

    /**
     * User rule 2026-11-15: *"A pop-up window opened from a field/button must adjust its position on four choices (right,
     * left, bottom, top), depending on the available space, then on its size"*.
     */
    @Test
    fun a_pop_up_of_a_field_opens_at_its_right_else_left_else_below_else_above() {
        val popup = MenuSide.POPUP
        val field = IntRect(400, 300, 500, 330)
        assertEquals(IntOffset(500, 300), menuSpot(field, menu, screen, popup), "at its right, tops aligned")
        assertEquals(IntOffset(750, 300), menuSpot(IntRect(900, 300, 1000, 330), menu, screen, popup), "no room at the right: at its left")
        assertEquals(IntOffset(0, 330), menuSpot(IntRect(0, 300, 1000, 330), menu, screen, popup), "no room at either side: below")
        assertEquals(IntOffset(0, 300), menuSpot(IntRect(0, 600, 1000, 630), menu, screen, popup), "nor below: above")
    }

    /** No side holds it whole: the side with the most room for its size, pushed onto the screen — never off it. */
    @Test
    fun where_no_side_holds_it_it_takes_the_side_with_the_most_room() {
        val tall = IntSize(150, 700)
        // A wide field across the screen's middle: 270 above, 470 below — below, pushed up to stay whole.
        val spot = menuSpot(IntRect(0, 300, 1000, 330), tall, screen, MenuSide.POPUP)
        assertEquals(IntOffset(0, 100), spot)
        // Near the bottom, above has the more room: placed there, held on the screen's top.
        assertEquals(IntOffset(0, 0), menuSpot(IntRect(0, 700, 1000, 730), tall, screen, MenuSide.POPUP))
    }

    /**
     * User rule 2026-11-15: *"I'd like the pop-up windows to avoid hiding the pip as the user moves the app or the pip."*
     * A spot that covers the picture-in-picture slides along its side past it; a side with no clear spot is passed over;
     * where no side is clear, the anchor comes first.
     */
    @Test
    fun a_menu_keeps_off_the_picture_in_picture() {
        val field = IntRect(100, 100, 200, 130)
        // Below the field, the video stands where the menu would: slid right, past it.
        val pip = IntRect(50, 200, 300, 400)
        assertEquals(IntOffset(300, 130), menuSpot(field, menu, screen, MenuSide.DEFAULT, listOf(pip)))
        // A video across the whole width below: no clear spot below, nor above (no room) — at the right, slid down past it.
        val band = IntRect(0, 150, 1000, 400)
        assertEquals(IntOffset(200, 400), menuSpot(field, menu, screen, MenuSide.DEFAULT, listOf(band)))
        // Nothing clear anywhere: placed as if there were no video.
        val all = IntRect(0, 0, 1000, 800)
        assertEquals(menuSpot(field, menu, screen), menuSpot(field, menu, screen, MenuSide.DEFAULT, listOf(all)))
        // A menu shown where the video has come is placed again.
        assertFalse(keepsSpot(IntOffset(100, 130), menu, field, screen, listOf(pip)))
    }
}
