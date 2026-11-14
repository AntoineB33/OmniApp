package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.example.project.ui.PopupPositions
import org.example.project.ui.MenuHoverTracker

/**
 * User rule 2026-11-04: *"When the user hovers out of the pop-up window and of its descendants, it must vanish."* —
 * [MenuHoverTracker], what the configurations tree's arrow, pop-ups and the menus opened inside them report to.
 */
class MenuHoverTrackerTest {
    private val grace = MenuHoverTracker.GRACE_MILLIS

    @Test
    fun it_vanishes_once_the_pointer_has_left_every_part() = runTest {
        var left = 0
        val tracker = MenuHoverTracker(this) { left++ }
        val arrow = Any()
        val root = Any()
        val child = Any()
        // Arrow → root → child: each crossing leaves one part before entering the next.
        tracker.enter(arrow)
        tracker.exit(arrow)
        advanceTimeBy(grace / 3)
        tracker.enter(root)
        tracker.exit(root)
        tracker.enter(child)
        advanceTimeBy(grace * 2)
        runCurrent()
        assertEquals(0, left, "held all along, a seam apart")
        // Out of the child, over nothing.
        tracker.exit(child)
        advanceTimeBy(grace - 1)
        runCurrent()
        assertEquals(0, left, "within the grace")
        advanceTimeBy(2)
        runCurrent()
        assertEquals(1, left, "vanished")
    }

    @Test
    fun a_part_over_which_the_pointer_still_is_holds_it_and_a_part_gone_counts_as_left() = runTest {
        var left = 0
        val tracker = MenuHoverTracker(this) { left++ }
        val root = Any()
        val child = Any()
        tracker.enter(root)
        tracker.enter(child)
        tracker.exit(root)
        advanceTimeBy(grace * 3)
        runCurrent()
        assertEquals(0, left, "the child still holds the pointer")
        // The child pop-up closed with the pointer on it (its parent's category changed): it is left.
        tracker.exit(child)
        advanceTimeBy(grace + 1)
        runCurrent()
        assertEquals(1, left)
        // An exit of a part never entered changes nothing.
        tracker.exit(Any())
        advanceTimeBy(grace * 3)
        runCurrent()
        assertEquals(1, left)
    }

    /**
     * Anomaly 2026-11-11: a category's child pop-up stood while the pointer rested on its parent pop-up's background.
     * The child is a branch ([MenuHoverTracker.parent]): it goes once the pointer has left its row and it, while the
     * pointer still on the parent holds the whole tree.
     */
    @Test
    fun a_branch_goes_when_the_pointer_is_back_on_its_parent_and_holds_the_parent_while_over_it() = runTest {
        var treeLeft = 0
        var branchLeft = 0
        val tree = MenuHoverTracker(this) { treeLeft++ }
        val branch = MenuHoverTracker(this, parent = tree) { branchLeft++ }
        val parentPopup = Any()
        val row = Any()
        val childPopup = Any()
        tree.enter(parentPopup)
        branch.enter(row)
        // Row → child pop-up, a seam apart: the parent pop-up's window is left meanwhile.
        branch.exit(row)
        tree.exit(parentPopup)
        advanceTimeBy(grace / 3)
        branch.enter(childPopup)
        advanceTimeBy(grace * 3)
        runCurrent()
        assertEquals(0 to 0, treeLeft to branchLeft, "the child pop-up holds both")
        // Back onto the parent pop-up's background: the child goes, the tree stays.
        branch.exit(childPopup)
        tree.enter(parentPopup)
        advanceTimeBy(grace + 1)
        runCurrent()
        assertEquals(0 to 1, treeLeft to branchLeft)
    }

    /**
     * User rule 2026-11-11: a press in a hover pop-up pins it and every pop-up it is a branch of — the pointer leaving
     * no longer closes them — until it closes some other way ([MenuHoverTracker.unpin]).
     */
    @Test
    fun a_pinned_pop_up_and_its_ancestors_stay_when_the_pointer_leaves() = runTest {
        var treeLeft = 0
        var branchLeft = 0
        val tree = MenuHoverTracker(this) { treeLeft++ }
        val branch = MenuHoverTracker(this, parent = tree) { branchLeft++ }
        val child = Any()
        branch.enter(child)
        branch.pin()
        branch.exit(child)
        advanceTimeBy(grace * 5)
        runCurrent()
        assertEquals(0 to 0, treeLeft to branchLeft, "pinned, both stay")
        // Closed some other way, then opened again: a hover pop-up again.
        branch.unpin()
        tree.unpin()
        branch.enter(child)
        branch.exit(child)
        advanceTimeBy(grace + 1)
        runCurrent()
        assertEquals(1 to 1, treeLeft to branchLeft)
    }

    /**
     * User rule 2026-11-14: while the pop-up holding the keyboard is pinned, a hover opens a pop-up only from inside it
     * (or from a fresh hover pop-up hanging from it); nothing is held back while nothing is pinned.
     */
    @Test
    fun a_hover_acts_only_inside_the_pinned_pop_up_holding_the_keyboard() = runTest {
        val tree = MenuHoverTracker(this) { }
        val branch = MenuHoverTracker(this, parent = tree) { }
        val leaf = MenuHoverTracker(this, parent = branch) { }
        val other = MenuHoverTracker(this) { }
        branch.windowFocused()
        assertTrue(MenuHoverTracker.hoverActs(null) && MenuHoverTracker.hoverActs(tree) && MenuHoverTracker.hoverActs(other), "not pinned")
        branch.pin()
        assertFalse(MenuHoverTracker.hoverActs(null), "an arrow in a window of the app")
        assertFalse(MenuHoverTracker.hoverActs(tree), "a row of the pop-up it hangs from")
        assertFalse(MenuHoverTracker.hoverActs(other))
        assertTrue(MenuHoverTracker.hoverActs(branch) && MenuHoverTracker.hoverActs(leaf), "in it, and in its descendants")
        // A hover pop-up opened from it takes the keyboard, not pinned: the pinned one above it still decides.
        leaf.windowFocused()
        assertFalse(MenuHoverTracker.hoverActs(tree))
        assertTrue(MenuHoverTracker.hoverActs(branch))
        // Closed: every hover acts again.
        leaf.unpin()
        branch.unpin()
        tree.unpin()
        assertTrue(MenuHoverTracker.hoverActs(null))
    }

    /**
     * User rule 2026-11-14: a press outside the pinned pop-up holding the keyboard — the keyboard gone to a window that is
     * none of its own nor of its descendants — closes it and every pop-up it hangs from; not pinned, its ancestors stay.
     * A walk through the positions to one of its ancestors closes what is below that ancestor alone.
     */
    @Test
    fun the_keyboard_leaving_a_pinned_pop_up_closes_it_and_its_ancestors() = runTest {
        var treeLeft = 0
        var branchLeft = 0
        val tree = MenuHoverTracker(this) { treeLeft++ }
        val branch = MenuHoverTracker(this, parent = tree) { branchLeft++ }
        branch.windowFocused()
        branch.windowLeft()
        assertEquals(0 to 0, treeLeft to branchLeft, "not pinned: the menu's own dismissal is all there is")
        branch.windowFocused()
        branch.pin()
        branch.windowLeft()
        assertEquals(1 to 1, treeLeft to branchLeft)
        assertFalse(branch.pinned || tree.pinned)
        // The press that closed it, on the button that opens it, does not open it again; any other press there does.
        assertTrue(tree.takeClosedPinned())
        assertFalse(tree.takeClosedPinned(), "asked once")
        branch.windowFocused()
        branch.pin()
        branch.windowLeft()
        tree.enter(Any())
        assertFalse(tree.takeClosedPinned(), "the pointer came back since")
        // Walked to its parent: the parent stays.
        treeLeft = 0
        branchLeft = 0
        branch.windowFocused()
        branch.pin()
        tree.takeKeyboard()
        branch.windowLeft()
        assertEquals(0 to 1, treeLeft to branchLeft)
        assertTrue(tree.pinned)
        tree.unpin()
    }

    /**
     * User rule 2026-11-11: the pop-ups the keyboard went to, and the app's window it went back to, are positions walked
     * before the app's own. Anomaly 2026-11-11: hovered in and out several times, the walk showed the pop-up once, hid
     * it, and never showed it again — two openings of one pop-up were one position, the window between them none.
     */
    @Test
    fun hovering_in_and_out_leaves_as_many_positions_as_the_walk_shows_and_hides() {
        var stamp = 0
        val positions = PopupPositions { stamp }
        val shown = mutableListOf<String>()
        // What the platform does: the pop-up taking the keyboard as it opens, the app's window as it closes.
        val ui = object {
            var open = false
            fun opened() {
                positions.focused("popup", { open }, { hide() }) { if (!open) { open = true; shown += "popup"; opened() } }
            }
            fun hide() {
                if (open) { open = false; shown += "window"; closed() }
            }
            fun closed() = positions.appFocused { hide() }
        }
        repeat(3) {
            ui.open = true
            ui.opened()
            ui.open = false
            ui.closed()
        }
        // Back: the pop-up, the window, the pop-up, the window, the pop-up, the window it was first opened from.
        repeat(6) { assertTrue(positions.back(), "step ${it + 1}") }
        assertEquals(listOf("popup", "window", "popup", "window", "popup", "window"), shown)
        assertFalse(ui.open)
        assertFalse(positions.back(), "nothing older: the app's own history is walked")
        // Forward again, as far as it went.
        shown.clear()
        repeat(6) { assertTrue(positions.forward(), "forward ${it + 1}") }
        assertEquals(listOf("popup", "window", "popup", "window", "popup", "window"), shown)
        assertFalse(positions.forward())
        // Back past the oldest into the app's own history (its stamp moves): they wait; once the app's history is
        // redone to where it stood, forward goes on through them (anomaly 2026-11-11).
        repeat(6) { positions.back() }
        assertFalse(positions.back())
        stamp++
        assertFalse(positions.forward(), "the app's history is redone first")
        stamp--
        shown.clear()
        assertTrue(positions.forward())
        assertEquals(listOf("popup"), shown)
        // A new position after the app's history moved starts them anew.
        stamp++
        ui.open = true
        ui.opened()
        assertFalse(positions.forward())
        assertTrue(positions.back(), "the window it was opened from")
        assertFalse(ui.open)
        assertFalse(positions.back())
    }

    /** A pop-up closed while the keyboard was in it, with no window position after it: back brings it back first. */
    @Test
    fun the_pop_up_the_keyboard_was_in_comes_back_first_and_one_gone_is_no_position() {
        val positions = PopupPositions { 0 }
        val open = mutableMapOf("tree" to true, "branch" to true)
        val brought = mutableListOf<String>()
        fun focus(name: String) = positions.focused(name, { open.getValue(name) }) { brought += name; open[name] = true }
        focus("tree")
        focus("branch")
        open["branch"] = false
        assertTrue(positions.back())
        assertEquals(listOf("branch"), brought)
        // Back: the tree gets the keyboard; its focus is no new position.
        assertTrue(positions.back())
        focus("tree")
        assertEquals(listOf("branch", "tree"), brought)
        // One gone from the screen is no position.
        positions.forget("tree")
        positions.forget("branch")
        assertFalse(positions.back(), "left at the app's window they hung from: the app's own history is walked")
    }

    /**
     * Anomaly 2026-11-11 (measured in diagnostics.log): three trees deep — each opened by the arrow in the bar of the
     * pop-up before — a click in the app's window, then Ctrl+Alt+<-: the two inner trees had forgotten their positions
     * as they left the screen with the pop-up they hung from, and the walk brought back older pop-ups. The outermost
     * tree's [org.example.project.ui.TreeKeeper] keeps them all, by address, and a walk back brings the trees down to the
     * pop-up walked to; the pop-ups opening on the way are no positions of their own.
     */
    @Test
    fun a_walk_back_brings_the_trees_down_to_the_deepest_pop_up_and_on_back_up() {
        val positions = PopupPositions { 0 }
        val keeper = org.example.project.ui.TreeKeeper()
        val open = mutableMapOf<List<List<String>>, List<String>>()
        fun showing(prefix: List<List<String>>) {
            open[prefix] = emptyList()
            keeper.live[prefix] = { open[prefix] }
        }
        // What a tree's pop-up does as the keyboard comes to it ([ConfigurationTreeArrow]).
        fun focus(prefix: List<List<String>>) {
            val address = prefix + listOf(emptyList<String>())
            keeper.focused(address) {
                positions.focused(keeper.tokenOf(address), { keeper.isShowing(address) }) { keeper.bringBack(address) }
            }
        }
        val first = emptyList<List<String>>()
        val second = listOf(emptyList<String>())
        val third = listOf(emptyList<String>(), emptyList())
        for (tree in listOf(first, second, third)) {
            showing(tree)
            focus(tree)
        }
        // A click in the app's window: the trees close and leave the screen; their positions stay.
        open.clear()
        positions.appFocused {}
        assertTrue(positions.back())
        assertEquals(third + listOf(emptyList()), keeper.wanted, "down to the third tree's pop-up")
        // The trees open on the way, each taking the keyboard: no position of its own — the third's is the one arrived at.
        for (tree in listOf(first, second, third)) {
            showing(tree)
            focus(tree)
        }
        assertTrue(keeper.isShowing(third + listOf(emptyList())))
        // Back again: the second, then the first — the walk was not cut off by the pop-ups opened on the way.
        assertTrue(positions.back())
        assertEquals(second + listOf(emptyList()), keeper.wanted)
        focus(second)
        assertTrue(positions.back())
        assertEquals(first + listOf(emptyList()), keeper.wanted)
    }
}
