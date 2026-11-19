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
import org.example.project.ui.stuckTo

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
     * User rule 2026-11-15 (second of the day): *"right side to the top, then bottom side to the right, then top side to
     * the right, then left side to the top"* — each spot holds the pop-up whole, unslid, or is passed over.
     */
    @Test
    fun a_pop_up_of_a_field_opens_at_its_left_else_above_else_below_else_at_its_right() {
        val popup = MenuSide.POPUP
        val field = IntRect(400, 400, 500, 430)
        assertEquals(IntOffset(250, 400), menuSpot(field, menu, screen, popup), "at its left, hanging from its top")
        // Too low to hang down at the left: above, its bottom-right corner on the field's top-right.
        assertEquals(IntOffset(350, 300), menuSpot(IntRect(400, 600, 500, 630), menu, screen, popup), "above, reaching left")
        // No room at the left nor above: below, reaching left.
        assertEquals(IntOffset(0, 130), menuSpot(IntRect(50, 100, 150, 130), menu, screen, popup), "below, reaching left")
        // A field at the screen's left edge, its right end too short for below/above: at its right.
        assertEquals(IntOffset(100, 100), menuSpot(IntRect(0, 100, 100, 130), menu, IntRect(0, 0, 1000, 400), popup), "at its right")
    }

    /** No spot holds it whole: the side with the most room on both axes, slid along it — never off the screen. */
    @Test
    fun where_no_side_holds_it_it_takes_the_side_with_the_most_room() {
        val tall = IntSize(150, 700)
        // A field at the screen's middle: neither left nor right holds 700 hanging from y 300; above and below have
        // less room than the side columns, which hold the whole width — at the left, slid up as little as it must.
        assertEquals(IntOffset(350, 100), menuSpot(IntRect(500, 300, 600, 330), tall, screen, MenuSide.POPUP))
        // A field across the whole width: only above and below; below has the more room, slid up to stay whole.
        assertEquals(IntOffset(850, 100), menuSpot(IntRect(0, 300, 1000, 330), tall, screen, MenuSide.POPUP))
        assertEquals(IntOffset(850, 0), menuSpot(IntRect(0, 700, 1000, 730), tall, screen, MenuSide.POPUP))
    }

    /**
     * User rule 2026-11-16: *"When a row makes a window appear, it must never hide the list of rows the caller is in. The
     * positions below and above are only possible when it doesn't hide."*
     */
    @Test
    fun a_pop_up_of_a_row_never_hides_the_list_the_row_is_in() {
        val popup = MenuSide.POPUP
        val list = IntRect(300, 100, 700, 700)
        val row = IntRect(320, 400, 680, 430)
        // Room at the list's left: off its left edge, at the row's height — not at the row's left, over the list.
        assertEquals(IntOffset(150, 400), menuSpot(row, menu, screen, popup, list), "off the list's left edge")
        // No room at the left (the list starts at the edge), nor above or below it: off its right edge.
        val wide = IntRect(0, 100, 700, 700)
        assertEquals(IntOffset(700, 400), menuSpot(IntRect(20, 400, 680, 430), menu, screen, popup, wide), "off the list's right edge")
        // A list across the whole width and short: below the LIST, not below the row.
        val strip = IntRect(0, 100, 1000, 300)
        val at = menuSpot(IntRect(20, 150, 980, 180), menu, screen, popup, strip)
        assertTrue(at.y >= strip.bottom || at.y + menu.height <= strip.top, "above or below the list alone: $at")
        // Shown where it hides the list, it is placed again.
        assertFalse(keepsSpot(IntOffset(400, 430), menu, row, screen, list), "over the list: placed again")
        assertTrue(keepsSpot(IntOffset(150, 400), menu, row, screen, list), "off the list: it stays")
    }

    /**
     * User rule 2026-11-19: *"Make sure every window that appears by hovering over something, the caller, is always stuck to
     * the caller."* A pop-up whose size changed stays only where it still touches its caller ([stuckTo]): at its left, a
     * narrower one left a gap between it and the row, and is placed again.
     */
    @Test
    fun a_pop_up_stays_only_where_it_still_touches_its_caller() {
        val row = IntRect(703, 400, 903, 430)
        val shown = IntOffset(503, 177)
        assertTrue(keepsSpot(shown, IntSize(200, 457), row, screen, stuck = true), "shrunk in height, against the row still: it stays")
        assertFalse(keepsSpot(shown, IntSize(150, 457), row, screen, stuck = true), "narrower: a gap off the row, placed again")
        assertFalse(keepsSpot(IntOffset(503, 500), IntSize(200, 100), row, screen, stuck = true), "below the row's height: not against it")
        assertTrue(stuckTo(IntRect(703, 430, 853, 730), row), "below it")
        // A row of a list: against the list's edge, at the row's height.
        val list = IntRect(600, 100, 950, 700)
        assertTrue(stuckTo(IntRect(450, 400, 600, 700), row, list))
        assertFalse(stuckTo(IntRect(440, 400, 590, 700), row, list))
        // Placed again, it is.
        val placed = menuSpot(row, IntSize(150, 300), screen, MenuSide.SUBMENU)
        assertTrue(stuckTo(IntRect(placed.x, placed.y, placed.x + 150, placed.y + 300), row))
    }
}
