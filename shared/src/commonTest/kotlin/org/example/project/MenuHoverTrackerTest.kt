package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
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
        // Anomaly 2026-11-15: a press on the category's row (in the tree's pop-up) pinned the branch; the keyboard going
        // from the branch's window to the tree's closed the tree too. Gone to an ancestor's window: that one stays.
        treeLeft = 0
        branchLeft = 0
        val treeWindow = Any()
        tree.windows += treeWindow
        branch.windowFocused()
        branch.pin()
        branch.windowLeft { treeWindow in it }
        assertEquals(0 to 1, treeLeft to branchLeft)
        assertTrue(tree.pinned, "the tree pinned by the same press")
        tree.unpin()
    }

    /**
     * Anomaly 2026-11-21: *"I go to down arrow, then in 'Currently on'. In this window A I click on the type selector field,
     * then I click again on A, and A disappears…"* — the type field's drop-down reports to A's tracker; the press back in A
     * closes the drop-down, the keyboard going to A's own window. A stays, pinned, and so does the tree it hangs from.
     */
    @Test
    fun the_keyboard_coming_back_to_the_pop_up_from_its_own_drop_down_closes_nothing() = runTest {
        var treeLeft = 0
        var branchLeft = 0
        val tree = MenuHoverTracker(this) { treeLeft++ }
        val branch = MenuHoverTracker(this, parent = tree) { branchLeft++ }
        val branchWindow = Any()
        val dropDownWindow = Any()
        branch.windows += branchWindow
        branch.windows += dropDownWindow
        branch.windowFocused()
        branch.pin()
        // The drop-down's window, left for A's own.
        branch.windowLeft { branchWindow in it }
        assertEquals(0 to 0, treeLeft to branchLeft)
        assertTrue(branch.pinned && tree.pinned)
        // Left for a window of no pop-up of the tree: everything closes, as ever.
        branch.windowLeft { false }
        assertEquals(1 to 1, treeLeft to branchLeft)
    }
}
