package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.example.project.ui.OutsidePressEditor
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
        val editor = OutsidePressEditor().apply { parts[Any()] = WINDOW to field }
        host.openEditor(editor, editor) { left++ }

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

    @Test
    fun `a task cell's Edit Mode is inside its field AND its menus, and leaves on a press anywhere else`() {
        // PRD §4 Forced Exit (2026-09-30): the menus are drawn under the row, apart from the field, so the edit is
        // two parts — a press in the suggestions must keep it, a press beside the tree or in another window ends it.
        val host = TransientMenuHost()
        val field = androidx.compose.ui.geometry.Rect(100f, 100f, 300f, 120f)
        val menus = androidx.compose.ui.geometry.Rect(100f, 120f, 400f, 260f)
        val editor = OutsidePressEditor().apply {
            parts[Any()] = WINDOW to field
            parts[Any()] = WINDOW to menus
        }
        var left = 0
        host.openEditor(editor, editor) { left++ }

        host.onPress(androidx.compose.ui.geometry.Offset(150f, 110f))
        host.onPress(androidx.compose.ui.geometry.Offset(350f, 200f))
        assertEquals(0, left, "a press in the field or in its menus keeps Edit Mode")

        host.onPress(androidx.compose.ui.geometry.Offset(150f, 300f))
        assertEquals(1, left, "a press below the menus — another cell, the calendar — ends it")
    }

    /**
     * Anomaly 2026-11-15: *"the selected rows sometimes stay selected even when clicking elsewhere"* — a part drawn in a
     * pop-up's window has bounds in THAT window: a press in the app's window at the same numbers is outside it, and a
     * press elsewhere in the pop-up's own window leaves it too.
     */
    @Test
    fun `a part is inside only for a press in its own window`() {
        val host = TransientMenuHost()
        val popup = Any()
        val rows = androidx.compose.ui.geometry.Rect(0f, 0f, 200f, 100f)
        var left = 0
        val editor = OutsidePressEditor().apply { parts[Any()] = popup to rows }
        host.openEditor(editor, editor) { left++ }
        host.onPressInPopup(androidx.compose.ui.geometry.Offset(50f, 50f), popup)
        assertEquals(0, left, "a press on the rows, in the pop-up")
        host.onPress(androidx.compose.ui.geometry.Offset(50f, 50f), WINDOW)
        assertEquals(1, left, "the same numbers in the app's window are elsewhere")
        host.openEditor(editor, editor) { left++ }
        host.onPressInPopup(androidx.compose.ui.geometry.Offset(50f, 300f), Any())
        assertEquals(1, left, "a press in ANOTHER pop-up is no press elsewhere in this one")
        host.onPressInPopup(androidx.compose.ui.geometry.Offset(50f, 300f), popup)
        assertEquals(2, left, "a press elsewhere in its own pop-up")
    }

    @Test
    fun `an edit whose parts are not laid out yet leaves on any press`() {
        // No bounds known is not "inside": the old single-rect form left on a null rect, and so does this.
        val host = TransientMenuHost()
        val editor = OutsidePressEditor()
        var left = 0
        host.openEditor(editor, editor) { left++ }

        host.onPress(androidx.compose.ui.geometry.Offset(10f, 10f))
        assertEquals(1, left)
    }

    /**
     * Anomaly 2026-11-11: a press in a pop-up of its own layer (the "▾" configurations pop-up) never reaches the root
     * observer, so the pop-up reports it ([org.example.project.ui.dismissesMenusOnPress]): the background menu opened in
     * it closes, and the cell's Edit Mode the pop-up was opened from does not end.
     */
    @Test
    fun `a press in a pop-up closes the menus and leaves the edits`() {
        val host = TransientMenuHost()
        var menuClosed = 0
        host.open(Any()) { menuClosed++ }
        val editor = OutsidePressEditor()
        var left = 0
        host.openEditor(editor, editor) { left++ }

        host.onPressInPopup()
        assertEquals(1, menuClosed, "the menu closed")
        assertFalse(host.anyOpen)
        assertEquals(0, left, "the edit the pop-up belongs to goes on")
    }

    private companion object {
        /** The app window's root, as the parts and the root observer name it. */
        val WINDOW = Any()
    }
}
