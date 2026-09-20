package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.example.project.ui.WindowFrameHost
import org.example.project.ui.WindowFrameState

/**
 * `docs/invariants/popups.md`: the registry every framed window is in, read three times — to draw the bar of
 * REDUCED windows along the bottom of the app, to answer "does the task tree still own the keyboard?", and
 * to say what is drawn over what.
 */
class WindowFrameHostTest {

    private fun register(
        host: WindowFrameHost,
        id: String,
        claimsKeyboard: Boolean = false,
        onClose: () -> Unit = {},
    ): WindowFrameState {
        val state = WindowFrameState(id)
        host.register(WindowFrameHost.Registration(id, id, state, claimsKeyboard, onClose))
        return state
    }

    @Test
    fun `only reduced windows are in the bar, in the order they were opened`() {
        val host = WindowFrameHost()
        val a = register(host, "A")
        register(host, "B")
        val c = register(host, "C")
        assertFalse(host.hasMinimized)

        c.minimize()
        a.minimize()

        assertTrue(host.hasMinimized)
        assertEquals(listOf("A", "C"), host.minimizedWindows.map { it.id })
    }

    @Test
    fun `restoring takes a window out of the bar`() {
        val host = WindowFrameHost()
        val a = register(host, "A")
        a.minimize()
        assertEquals(listOf("A"), host.minimizedWindows.map { it.id })

        a.restore()

        assertFalse(host.hasMinimized)
    }

    @Test
    fun `a chip's cross closes the window it stands for`() {
        val host = WindowFrameHost()
        var closed = 0
        val a = register(host, "A") { closed++ }
        a.minimize()

        host.minimizedWindows.single().onClose()

        assertEquals(1, closed)
    }

    @Test
    fun `a window that leaves the composition leaves the bar with it`() {
        val host = WindowFrameHost()
        val a = register(host, "A")
        a.minimize()

        host.unregister("A")

        assertFalse(host.hasMinimized)
        assertTrue(host.registrations.isEmpty())
    }

    @Test
    fun `re-registering the same id replaces its row rather than doubling it`() {
        // The frame re-registers whenever its title changes; a second row under one id would put the same
        // window in the bar twice.
        val host = WindowFrameHost()
        register(host, "A")
        register(host, "A")
        assertEquals(1, host.registrations.size)
    }


    // ----- the stacking order -------------------------------------------------------------------------
    //
    // ONE order for every window of the app. A window about one object used to be pinned above the whole
    // of it on a fixed `zIndex(100f)` — right only while such a window left on the next press. Now that
    // nothing leaves, the priority-weight table has to be able to go under the window the user moved to.

    @Test
    fun `a window opens on top of the ones already there`() {
        val host = WindowFrameHost()
        register(host, "Calendar")
        register(host, "Categories")

        register(host, "PriorityWeights")

        assertEquals(listOf("Calendar", "Categories", "PriorityWeights"), host.stackOrder)
        assertTrue(host.zOf("PriorityWeights") > host.zOf("Categories"))
    }

    @Test
    fun `pressing in a window puts it over the per-object window that was on top`() {
        val host = WindowFrameHost()
        register(host, "Categories")
        register(host, "PriorityWeights")
        assertTrue(host.zOf("PriorityWeights") > host.zOf("Categories"))

        host.focus("Categories")

        assertEquals(listOf("PriorityWeights", "Categories"), host.stackOrder)
        assertTrue(host.zOf("Categories") > host.zOf("PriorityWeights"))
    }

    @Test
    fun `raising the window already on top changes nothing`() {
        val host = WindowFrameHost()
        register(host, "Calendar")
        register(host, "TaskEdit")

        host.raise("TaskEdit")

        assertEquals(listOf("Calendar", "TaskEdit"), host.stackOrder)
    }

    @Test
    fun `a window not in the stack yet reads as the top`() {
        // Its `register` runs after the composition that first draws it; reading as the BOTTOM would draw
        // a window that has just opened under its neighbours for one frame.
        val host = WindowFrameHost()
        register(host, "Calendar")
        register(host, "Categories")

        assertTrue(host.zOf("TaskEdit") > host.zOf("Categories"))
    }

    // ----- a PAIR: a window and the companion window it opens beside itself ---------------------------
    //
    // Both are drawn in ONE wrapper `Box`, and `zIndex` only orders a node among its own siblings — so the
    // companion's own z never leaves that Box and the WRAPPER is what stands among the app's windows.

    @Test
    fun `a pair stands where its topmost half does, so a press in the companion brings it forward`() {
        // The History window and its row-info window. Pressing in the info window raised the info window
        // and nothing came forward: the pair was drawn at the History window's z, which was still under
        // the window the user had moved to.
        val host = WindowFrameHost()
        register(host, "History")
        register(host, "HistoryEntryInfo", claimsKeyboard = true)
        register(host, "Calendar")
        assertTrue(host.zOf("Calendar") > host.zOf("History", "HistoryEntryInfo"))

        host.focus("HistoryEntryInfo")

        assertTrue(host.zOf("History", "HistoryEntryInfo") > host.zOf("Calendar"))
        // …and the focus stays with the half the press landed in, which is what keeps its keyboard.
        assertTrue(host.keyboardClaimed)
    }

    @Test
    fun `a pair with no companion open reads as the window itself`() {
        // Never the closed companion's id: an id that is not in the stack reads as the TOP, so naming it
        // while it is shut would pin the pair over every other window.
        val host = WindowFrameHost()
        register(host, "History")
        register(host, "Calendar")

        assertEquals(host.zOf("History"), host.zOf("History", null))
        assertTrue(host.zOf("Calendar") > host.zOf("History", null))
    }

    @Test
    fun `a closed window leaves the stack`() {
        val host = WindowFrameHost()
        register(host, "Calendar")
        register(host, "TaskEdit")

        host.unregister("TaskEdit")

        assertEquals(listOf("Calendar"), host.stackOrder)
    }

    @Test
    fun `the front window is the top of the whole stack, per-object windows included`() {
        // `App`'s lateral-menu button closes the window it names only when that window is the FRONT one.
        // Reading "the topmost lateral-menu window" instead had the Alarms button close the Alarms window
        // while the priority-weight table stood over it — the one gesture that has to bring Alarms back.
        val host = WindowFrameHost()
        register(host, "Alarms")
        register(host, "PriorityWeights")

        assertEquals("PriorityWeights", host.frontId)

        // …and pressing the Alarms button raises it, which is what makes the NEXT press on that button
        // close the window, exactly as it does with no per-object window in the way.
        host.focus("Alarms")

        assertEquals("Alarms", host.frontId)
    }

    @Test
    fun `nothing is in front when every window has been closed`() {
        val host = WindowFrameHost()
        register(host, "Alarms")
        host.unregister("Alarms")
        assertNull(host.frontId)
    }

    @Test
    fun `the bar keeps the order windows were opened in however they are raised`() {
        // The two orders are deliberately separate: a chip must not jump along the bar because its window
        // was raised.
        val host = WindowFrameHost()
        val a = register(host, "A")
        val b = register(host, "B")
        a.minimize()
        b.minimize()

        host.focus("B")
        host.focus("A")

        assertEquals(listOf("A", "B"), host.minimizedWindows.map { it.id })
        assertEquals(listOf("B", "A"), host.stackOrder)
    }

    // ----- the keyboard -------------------------------------------------------------------------------
    //
    // `TaskTreeView`'s `keyboardOwned`. Since a window no longer leaves on an outside press, this follows
    // the FOCUS: "a window is open" would hold the keyboard for as long as the window stood there, and the
    // tree could never be typed in again without closing it.

    @Test
    fun `a focused window that answers keystrokes takes the keyboard off the tree`() {
        val host = WindowFrameHost()
        register(host, "TaskEdit", claimsKeyboard = true)
        assertFalse(host.keyboardClaimed)

        host.focus("TaskEdit")

        assertTrue(host.keyboardClaimed)
    }

    @Test
    fun `pressing in the tree hands the keyboard straight back`() {
        val host = WindowFrameHost()
        register(host, "TaskEdit", claimsKeyboard = true)
        host.focus("TaskEdit")

        host.blur()

        assertFalse(host.keyboardClaimed)
        // …and the window is still open. That is the point: it no longer leaves because a press landed
        // somewhere else.
        assertEquals(listOf("TaskEdit"), host.registrations.map { it.id })
    }

    @Test
    fun `a focused window that does not answer keystrokes leaves the tree the keyboard`() {
        val host = WindowFrameHost()
        register(host, "Calendar", claimsKeyboard = false)
        host.focus("Calendar")
        assertFalse(host.keyboardClaimed)
    }

    @Test
    fun `focusing another window takes the keyboard with it`() {
        val host = WindowFrameHost()
        register(host, "TaskEdit", claimsKeyboard = true)
        register(host, "Calendar", claimsKeyboard = false)
        host.focus("TaskEdit")
        assertTrue(host.keyboardClaimed)

        host.focus("Calendar")

        assertFalse(host.keyboardClaimed)
    }

    /**
     * The History window's "info" button pressed on the row whose information window is already open: the
     * press landed in the History window (which took the focus and the top), and the row it asks for changes no
     * state — so the host has to be told to bring the window back, reduced or not. The reduce bar's chip asks
     * the same way: restoring and RAISING alone left a window that answers keystrokes back on screen with the
     * tree still holding the keyboard.
     */
    @Test
    fun `presenting an open window restores it, raises it and focuses it`() {
        val host = WindowFrameHost()
        val info = register(host, "HistoryEntryInfo", claimsKeyboard = true)
        register(host, "History")
        info.minimize()
        host.focus("History")
        assertEquals("History", host.frontId)

        host.present("HistoryEntryInfo")

        assertFalse(info.minimized)
        assertEquals("HistoryEntryInfo", host.frontId)
        assertEquals("HistoryEntryInfo", host.focusedId)
        assertTrue(host.keyboardClaimed)
    }

    @Test
    fun `presenting a window before it registers leaves it on top once it does`() {
        val host = WindowFrameHost()
        register(host, "History")

        host.present("HistoryEntryInfo")
        register(host, "HistoryEntryInfo")

        assertEquals(listOf("History", "HistoryEntryInfo"), host.stackOrder)
        assertEquals("HistoryEntryInfo", host.focusedId)
    }

    @Test
    fun `closing the focused window releases the keyboard`() {
        val host = WindowFrameHost()
        register(host, "TaskEdit", claimsKeyboard = true)
        host.focus("TaskEdit")

        host.unregister("TaskEdit")

        assertFalse(host.keyboardClaimed)
    }
}
