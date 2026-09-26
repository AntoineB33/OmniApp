package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.example.project.ui.TransientMenuHost

/**
 * `docs/invariants/popups.md`: a right-click contextual MENU (or a drop-down) is the one thing in the app
 * that still leaves on a press somewhere else. A **window** never does — that half is
 * [WindowFrameStateTest]'s and [WindowFrameHostTest]'s.
 */
class TransientMenuHostTest {

    @Test
    fun `a press anywhere the observer sees closes the menu`() {
        // The observer never sees a press INSIDE the menu — a DropdownMenu draws its own Popup, in its own
        // layer — so every press that reaches it is by construction a press outside.
        val host = TransientMenuHost()
        var dismissed = 0
        host.open(Any()) { dismissed++ }
        assertTrue(host.anyOpen)

        host.onPress()

        assertEquals(1, dismissed)
        assertFalse(host.anyOpen)
    }

    @Test
    fun `a closed menu is closed once, however many presses follow`() {
        val host = TransientMenuHost()
        var dismissed = 0
        host.open(Any()) { dismissed++ }

        host.onPress()
        host.onPress()

        assertEquals(1, dismissed)
    }

    @Test
    fun `right-clicking a second cell replaces the menu instead of stacking one`() {
        // PRD §13: the right-click that opens menu B reaches the app-root observer FIRST (Initial pass) and
        // closes menu A; registering B afterwards must not leave two menus believing they are open.
        val host = TransientMenuHost()
        var dismissedA = 0
        val keyA = Any()
        host.open(keyA) { dismissedA++ }
        host.onPress()
        assertEquals(1, dismissedA)

        host.open(Any()) {}
        assertTrue(host.anyOpen)
        // …and the menu that was dismissed is not dismissed a second time when it leaves the composition.
        host.close(keyA)
        assertEquals(1, dismissedA)
        assertTrue(host.anyOpen)
    }

    @Test
    fun `opening one menu closes the one already open`() {
        val host = TransientMenuHost()
        var dismissedA = 0
        var dismissedB = 0
        host.open(Any()) { dismissedA++ }
        host.open(Any()) { dismissedB++ }

        assertEquals(1, dismissedA)
        assertEquals(0, dismissedB)
        assertTrue(host.anyOpen)
    }

    @Test
    fun `closing a menu that left the composition never fires its dismissal`() {
        // Picking an entry removes the menu itself; the host must not call back into a gone composable.
        val host = TransientMenuHost()
        var dismissed = 0
        val key = Any()
        host.open(key) { dismissed++ }

        host.close(key)
        host.onPress()

        assertEquals(0, dismissed)
    }

    @Test
    fun `a press with nothing open is free`() {
        val host = TransientMenuHost()
        host.onPress()
        assertFalse(host.anyOpen)
    }

    // ----- `anyOpen`: what the TASK TREE reads to know it does not own the keyboard -------------------
    //
    // `TaskTreeView`'s `keyboardOwned`. Every way a menu can leave has to keep the flag in step, or the
    // tree stays deaf with nothing on screen.

    @Test
    fun `anyOpen follows a menu opening and leaving the composition`() {
        val host = TransientMenuHost()
        assertFalse(host.anyOpen)

        val key = Any()
        host.open(key) {}
        assertTrue(host.anyOpen)

        host.close(key)
        assertFalse(host.anyOpen)
    }

    @Test
    fun `anyOpen stays true when one menu replaces another`() {
        val host = TransientMenuHost()
        host.open(Any()) {}
        host.open(Any()) {}
        // At most one is open at a time — but one still is, so the tree must stay deaf.
        assertTrue(host.anyOpen)
    }

    @Test
    fun `an edit that leaves on an outside press ends there and survives a press inside it or a menu`() {
        // The timer's countdown field in edit mode (2026-09-26): a press on something that takes no focus left the
        // caret, and the held countdown, where they were.
        val host = TransientMenuHost()
        val field = androidx.compose.ui.geometry.Rect(100f, 100f, 160f, 140f)
        var left = 0
        host.openEditor(Any(), { field }) { left++ }

        host.onPress(androidx.compose.ui.geometry.Offset(120f, 120f))
        assertEquals(0, left, "a press inside the field moves the caret; it stays in edit mode")
        host.open(Any()) {}
        assertEquals(0, left, "its own right-click menu opening does not end it")
        assertTrue(host.anyOpen, "the menu is open")

        host.onPress(androidx.compose.ui.geometry.Offset(400f, 20f))
        assertEquals(1, left)
        host.onPress(androidx.compose.ui.geometry.Offset(400f, 20f))
        assertEquals(1, left, "it leaves once")
    }
}
