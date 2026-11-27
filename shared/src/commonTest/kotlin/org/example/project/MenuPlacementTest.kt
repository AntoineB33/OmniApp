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
import org.example.project.ui.keptInPlace
import org.example.project.ui.menuFit
import org.example.project.ui.menuSpot
import org.example.project.ui.stuckTo
import org.example.project.ui.reStuck
import org.example.project.ui.shownRow
import org.example.project.ui.FarSide
import org.example.project.ui.arrowTreeSides
import org.example.project.ui.farSideOf

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
        // A list across the whole width and short, no room at the row's sides: against the row all the same, below it
        // (user rule 2026-11-21: stuck to the row, hiding as few other rows as it can) — no longer off the list's bottom.
        val strip = IntRect(0, 100, 1000, 300)
        val stripRow = IntRect(20, 150, 980, 180)
        val at = menuSpot(stripRow, menu, screen, popup, strip)
        assertEquals(180, at.y, "right below the row: $at")
        assertTrue(stuckTo(IntRect(at.x, at.y, at.x + menu.width, at.y + menu.height), stripRow, strip))
        // Shown on its row, it is placed again; beside the list, it stays.
        assertFalse(keepsSpot(IntOffset(400, 380), menu, row, screen, list), "over the row: placed again")
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

    /**
     * User rule 2026-11-21: *"Make sure every pop-up window that appears from a caller is stuck to the caller. Here, the
     * caller is the row, so if it is the last row the window can appear on the left, the right or the bottom."*
     */
    @Test
    fun a_rows_pop_up_is_stuck_to_the_row_below_it_only_from_the_last_row() {
        val sub = MenuSide.SUBMENU
        val pop = IntSize(300, 300)
        fun rect(at: IntOffset) = IntRect(at.x, at.y, at.x + pop.width, at.y + pop.height)
        // A menu's list, its rows laid wider than it (their end out of view): 400..640 shown, the rows 400..900.
        val list = IntRect(400, 500, 640, 560)
        val first = IntRect(400, 500, 900, 530)
        val last = IntRect(400, 530, 900, 560)
        // Room at the left: there, against the row, across its height.
        val left = menuSpot(last, pop, screen, sub, list)
        // User rule 2026-11-21 (*"prioritize appearing to the left and to the top"*): reaching UP from the row, its bottom on
        // the row's bottom.
        assertEquals(IntOffset(100, 260), left, "at the left of the last row, reaching up, the row within its height")
        assertTrue(stuckTo(rect(left), last, list))
        // No room at the left, a row in the middle (above would hide the row over it): at the right of the row AS SHOWN —
        // the list's edge, not the row's far end out of view.
        val tight = IntRect(150, 0, 1000, 800)
        val shownList = IntRect(160, 470, 400, 560)
        val wideRow = IntRect(160, 500, 700, 530)
        val right = menuSpot(wideRow, pop, tight, sub, shownList)
        assertEquals(400, right.x, "at the list's right edge, not at 700: $right")
        // From the FIRST row, above it hides nothing: there, before the right (the top first, user rule 2026-11-21).
        val fromTop = menuSpot(wideRow, pop, tight, sub, IntRect(160, 500, 400, 560))
        assertEquals(200, fromTop.y, "above the first row: $fromTop")
        assertTrue(stuckTo(rect(right), wideRow, shownList))
        // Neither side has room: below — from the LAST row only, against it.
        val narrow = IntRect(300, 0, 750, 900)
        val below = menuSpot(IntRect(320, 530, 730, 560), pop, narrow, sub, IntRect(320, 500, 730, 560))
        assertEquals(560, below.y, "below the last row: $below")
        assertTrue(stuckTo(rect(below), IntRect(320, 530, 730, 560), IntRect(320, 500, 730, 560)))
        // From the first row, below the list would stand away from it: not there (above, touching it, is).
        val fromFirst = menuSpot(IntRect(320, 500, 730, 530), pop, narrow, sub, IntRect(320, 500, 730, 560))
        assertTrue(stuckTo(rect(fromFirst), IntRect(320, 500, 730, 530), IntRect(320, 500, 730, 560)), "from the first row: $fromFirst")
        // Below the list from a row that is not the last is not stuck to that row.
        assertFalse(stuckTo(IntRect(400, 560, 700, 860), first, list), "below the list, under another row")
        // Same day: *"Sometimes the window appears on the caller. When the caller is a row, the window that appears must
        // hide as little as possible the other rows."* A long list, a row in its middle, no room at either side: against
        // the row, on the side hiding fewer rows (here above: 3 rows of it, against 5 below) — never on the row.
        val longList = IntRect(320, 200, 730, 500)
        val middle = IntRect(320, 290, 730, 320)
        val spot = menuSpot(middle, IntSize(300, 150), narrow, sub, longList)
        assertEquals(IntOffset(320, 140), spot, "above the row: 3 rows hidden rather than 5")
        assertFalse(IntRect(spot.x, spot.y, spot.x + 300, spot.y + 150).overlaps(shownRow(middle, longList)), "not on the row")
    }

    /**
     * User rule 2026-11-21: *"It must avoid as much as possible to hide the rows in the same section as the caller, even if
     * it means reducing the size of the pop-up window. It must prioritize appearing to the left and to the top."*
     */
    @Test
    fun a_pop_up_is_made_smaller_rather_than_hide_the_rows_of_its_caller() {
        val sub = MenuSide.SUBMENU
        // A list in the middle of the app's window, a row in its middle: 260 px at its left, 250 at its right, the pop-up
        // 400 wide. Whole, it could only stand over rows; made 260 wide, it stands at the row's left and hides none.
        val app = IntRect(0, 0, 1000, 800)
        val list = IntRect(260, 100, 750, 700)
        val row = IntRect(260, 400, 750, 430)
        val fit = menuFit(row, IntSize(400, 300), app, sub, list)
        assertEquals(IntSize(260, 300), fit.size, "as wide as the room at the left: $fit")
        assertEquals(IntOffset(0, 130), fit.at, "at the row's left, reaching up from it")
        val shown = IntRect(fit.at.x, fit.at.y, fit.at.x + fit.size.width, fit.at.y + fit.size.height)
        assertFalse(shown.overlaps(list), "no row hidden")
        assertTrue(stuckTo(shown, row, list), "stuck to its row")
        // Room for the whole pop-up at the left: its own size.
        assertEquals(IntSize(200, 300), menuFit(row, IntSize(200, 300), app, sub, list).size)
        // Too little room on every side that hides nothing: its own size, hiding the fewest rows.
        val tight = IntRect(150, 0, 900, 800)
        val cramped = menuFit(row, IntSize(400, 300), tight, sub, list)
        assertEquals(IntSize(400, 300), cramped.size, "never below the least size: $cramped")
        // A button with no side holding its pop-up whole: shrunk, against it, never over it.
        val button = IntRect(100, 380, 900, 420)
        val pop = menuFit(button, IntSize(500, 500), app)
        val popRect = IntRect(pop.at.x, pop.at.y, pop.at.x + pop.size.width, pop.at.y + pop.size.height)
        assertFalse(popRect.overlaps(button), "not over its button: $pop")
        assertTrue(stuckTo(popRect, button), "stuck to its button: $pop")
        assertEquals(IntSize(500, 380), pop.size, "above it (the top first), as tall as the room: $pop")
    }

    /**
     * User rule 2026-11-27: *"Make sure the window appears only stuck to the caller. Make sure of that in the whole app."*
     * Wherever its caller stands, whatever its size and sides, a pop-up — a right-click's menu too — touches its caller and
     * is never over it, as long as one side of it has any room.
     */
    @Test
    fun a_pop_up_is_always_stuck_to_its_caller() {
        val random = kotlin.random.Random(27)
        val app = IntRect(0, 0, 1000, 800)
        val sideSets = listOf(MenuSide.DEFAULT, MenuSide.SUBMENU, MenuSide.POPUP)
        repeat(4000) { i ->
            val list = if (i % 2 == 0) null else {
                val l = random.nextInt(0, 700); val t = random.nextInt(0, 500)
                IntRect(l, t, random.nextInt(l + 50, 1001), random.nextInt(t + 60, 801))
            }
            val anchor = if (list != null) {
                val top = random.nextInt(list.top, list.bottom - 20)
                IntRect(list.left + random.nextInt(0, 20), top, list.right, top + 20)
            } else {
                val l = random.nextInt(0, 990); val t = random.nextInt(0, 790)
                // A point (a right-click), or a field.
                if (i % 4 == 0) IntRect(l, t, l, t) else IntRect(l, t, minOf(1000, l + random.nextInt(1, 600)), minOf(800, t + random.nextInt(1, 60)))
            }
            val size = IntSize(random.nextInt(20, 1100), random.nextInt(20, 900))
            val sides = sideSets[i % sideSets.size]
            val fit = menuFit(anchor, size, app, sides, list)
            val caller = if (list == null) anchor else shownRow(anchor, list)
            val room = maxOf(caller.left - app.left, app.right - caller.right) > 0 || maxOf(caller.top - app.top, app.bottom - caller.bottom) > 0
            if (!room) return@repeat
            val shown = IntRect(fit.at.x, fit.at.y, fit.at.x + fit.size.width, fit.at.y + fit.size.height)
            assertTrue(stuckTo(shown, anchor, list), "stuck: anchor=$anchor list=$list size=$size sides=$sides -> $fit")
            assertFalse(shown.overlaps(caller), "not over its caller: anchor=$anchor list=$list size=$size -> $fit")
            assertTrue(fit.size.width <= size.width && fit.size.height <= size.height, "never larger than it wants: $fit")
        }
    }

    /**
     * User rule 2026-11-19: the down arrow of a window that appears stands on its left or right side, whichever is further
     * from the caller, and what it opens tries that side first.
     */
    @Test
    fun the_down_arrow_of_an_appearing_window_stands_on_its_side_furthest_from_the_caller() {
        val caller = IntRect(500, 100, 520, 120)
        assertEquals(FarSide.Left, farSideOf(IntRect(300, 100, 500, 400), caller), "at its caller's left")
        assertEquals(FarSide.Right, farSideOf(IntRect(520, 100, 720, 400), caller), "at its right")
        assertEquals(FarSide.Left, farSideOf(IntRect(320, 120, 515, 400), caller), "below, reaching left")
        assertEquals(MenuSide.RightTop, arrowTreeSides(FarSide.Right).first())
        assertEquals(MenuSide.POPUP, arrowTreeSides(FarSide.Left))
        assertEquals(MenuSide.POPUP.toSet(), arrowTreeSides(FarSide.Right).toSet())
    }

    /**
     * Anomaly 2026-11-19: *"sometimes I click on the down arrow, hovers over the newly open window which rapidly closes"*. A
     * pop-up at its caller's left that grew narrower is put back by its right edge — the one the pointer crossed — not
     * placed afresh, which could move it from under the pointer.
     */
    @Test
    fun a_pop_up_whose_size_changed_stays_against_its_caller_by_the_edge_the_pointer_crossed() {
        val arrow = IntRect(700, 100, 720, 120)
        val shown = IntOffset(400, 100) // 300 wide: its right edge on the arrow's left
        val narrower = reStuck(shown, IntSize(250, 400), arrow, screen)
        assertEquals(IntOffset(450, 100), narrower, "its right edge kept on the arrow")
        assertEquals(IntOffset(720, 100), reStuck(IntOffset(720, 100), IntSize(250, 400), arrow, screen), "at its right: unmoved")
        val above = reStuck(IntOffset(600, 0), IntSize(200, 80), IntRect(600, 200, 700, 230), screen)
        assertEquals(IntOffset(600, 120), above, "above: its bottom edge kept on the caller's top")
        assertEquals(null, reStuck(IntOffset(400, 100), IntSize(250, 900), arrow, screen), "no longer on the screen: placed afresh")
    }

    /**
     * Anomaly 2026-11-27: *"I click on one check box and the window moves. The window must not move, even if the caller
     * doesn't stick anymore to the new window, which must be verified only when the window opens."* Opened, a pop-up whose
     * content changes size stays where it stands — its caller gone from beside it or not — cut only where the app ends.
     */
    @Test
    fun an_opened_pop_up_keeps_its_place_whatever_its_size_becomes() {
        val at = IntOffset(400, 100)
        assertEquals(IntSize(250, 400), keptInPlace(at, IntSize(250, 400), screen), "it fits: its own size, where it is")
        assertEquals(IntSize(600, 700), keptInPlace(at, IntSize(900, 900), screen), "too big: cut by the app's right and bottom, never moved")
        assertEquals(IntSize(1, 1), keptInPlace(IntOffset(1000, 800), IntSize(50, 50), screen), "never below a pixel")
    }
}
