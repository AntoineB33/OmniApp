package org.example.project

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.example.project.ui.MenuSide
import org.example.project.ui.MenuPlace
import org.example.project.ui.keepsSpot
import org.example.project.ui.menuPlace
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
     * User rule 2026-11-15: *"A pop-up window opened from a field/button must adjust its position on four choices"*, in
     * the order *"left then bottom then top then right"*.
     */
    @Test
    fun a_pop_up_of_a_field_opens_at_its_left_else_below_else_above_else_at_its_right() {
        val popup = MenuSide.POPUP
        assertEquals(IntOffset(250, 300), menuSpot(IntRect(400, 300, 500, 330), menu, screen, popup), "at its left, tops aligned")
        assertEquals(IntOffset(50, 330), menuSpot(IntRect(50, 300, 150, 330), menu, screen, popup), "no room at the left: below")
        assertEquals(IntOffset(0, 300), menuSpot(IntRect(0, 600, 1000, 630), menu, screen, popup), "nor below: above")
        val short = IntRect(0, 0, 1000, 400)
        assertEquals(IntOffset(150, 100), menuSpot(IntRect(50, 150, 150, 180), menu, short, popup), "nor above: at its right")
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
     * The video chooses among the four spots and never adds one (anomaly the same day: the pop-up of a ▾ appeared far
     * away, slid along its side past the video), and where every spot covers it, the pop-up is made SHORTER (anomaly the
     * same day: *"If the position choice still hides the pip, the size is adjusted."*).
     */
    @Test
    fun a_menu_keeps_off_the_picture_in_picture_by_its_four_spots_and_its_height_alone() {
        val popup = MenuSide.POPUP
        val field = IntRect(400, 300, 500, 330)
        // The four spots, each as a rectangle: [menuPlace] answers one of them, or one made shorter on its anchor's side.
        val spots = listOf(MenuSide.Left, MenuSide.Below, MenuSide.Above, MenuSide.Right).associateWith { side ->
            val at = menuSpot(field, menu, screen, listOf(side))
            IntRect(at.x, at.y, at.x + menu.width, at.y + menu.height)
        }
        fun MenuPlace.rect() = IntRect(offset.x, offset.y, offset.x + menu.width, offset.y + height)
        // The video at its left: below.
        val left = IntRect(200, 350, 390, 500)
        assertEquals(MenuPlace(IntOffset(400, 330), 300), menuPlace(field, menu, screen, popup, listOf(left)))
        // A video anywhere: one of the four spots, whole or shorter on its anchor's side — never one slid past it.
        for (x in 0..900 step 50) for (y in 0..700 step 50) {
            val pip = IntRect(x, y, x + 200, y + 120)
            val placed = menuPlace(field, menu, screen, popup, listOf(pip))
            val r = placed.rect()
            val within = spots.any { (side, s) ->
                r.left == s.left && r.right == s.right && r.top >= s.top && r.bottom <= s.bottom &&
                    (if (side == MenuSide.Above) r.bottom == s.bottom else r.top == s.top)
            }
            assertTrue(within, "a video at ($x, $y) put the pop-up at $r, none of the four spots")
            if (placed.height < menu.height) assertFalse(r.overlaps(pip), "made shorter and still over the video at ($x, $y)")
        }
        // Every spot covers the videos: the first side in order made shorter, its top kept, its bottom on the video.
        val low = IntRect(0, 500, 1000, 800)
        val high = IntRect(0, 0, 1000, 150)
        assertEquals(MenuPlace(IntOffset(250, 300), 200), menuPlace(field, menu, screen, popup, listOf(low, high)))
        // Shorter than a bar and a few rows on every side: placed as if there were no video, whole.
        val lower = IntRect(0, 420, 1000, 800)
        assertEquals(MenuPlace(IntOffset(250, 300), 300), menuPlace(field, menu, screen, popup, listOf(lower, high)))
        // A menu shown where the video has come is placed again.
        assertFalse(keepsSpot(IntOffset(250, 300), menu, field, screen, listOf(left)))
    }
}
