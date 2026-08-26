package org.example.project

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.example.project.ui.TransientPopupHost

/**
 * The sort-2 pop-up rules (see [TransientPopupHost]): it opens on the top layer and leaves as soon as
 * anything else takes focus, and only ever one of them is open — which is why every pop-up that is about
 * ONE object (a task, a cell, a history unit) is this sort rather than a window.
 */
class TransientPopupHostTest {

    private val cardA = Rect(100f, 100f, 300f, 200f)
    private val cardB = Rect(400f, 100f, 600f, 200f)

    @Test
    fun `a press outside the card dismisses the pop-up`() {
        val host = TransientPopupHost()
        var dismissed = 0
        val key = Any()
        host.open(key) { dismissed++ }
        host.setBounds(key, cardA)

        host.onPress(Offset(50f, 50f))

        assertEquals(1, dismissed)
    }

    @Test
    fun `a press inside the card leaves it open`() {
        val host = TransientPopupHost()
        var dismissed = 0
        val key = Any()
        host.open(key) { dismissed++ }
        host.setBounds(key, cardA)

        host.onPress(Offset(150f, 150f))

        assertEquals(0, dismissed)
    }

    @Test
    fun `a dismissed pop-up is dismissed once, however many presses follow`() {
        val host = TransientPopupHost()
        var dismissed = 0
        val key = Any()
        host.open(key) { dismissed++ }
        host.setBounds(key, cardA)

        host.onPress(Offset(50f, 50f))
        host.onPress(Offset(60f, 60f))

        assertEquals(1, dismissed)
    }

    @Test
    fun `a pop-up that never reported bounds is dismissed by the first press`() {
        // A press arriving between open() and the first layout pass must not be read as "inside".
        val host = TransientPopupHost()
        var dismissed = 0
        host.open(Any()) { dismissed++ }

        host.onPress(Offset(150f, 150f))

        assertEquals(1, dismissed)
    }

    @Test
    fun `opening one pop-up dismisses the one already open`() {
        val host = TransientPopupHost()
        var dismissedA = 0
        var dismissedB = 0
        val keyA = Any()
        host.open(keyA) { dismissedA++ }
        host.setBounds(keyA, cardA)

        val keyB = Any()
        host.open(keyB) { dismissedB++ }
        host.setBounds(keyB, cardB)

        assertEquals(1, dismissedA)
        assertEquals(0, dismissedB)

        // …and the survivor is the new one: a press inside B keeps it, a press inside the now-closed A
        // (which is "somewhere else" as far as B is concerned) closes it.
        host.onPress(Offset(450f, 150f))
        assertEquals(0, dismissedB)
        host.onPress(Offset(150f, 150f))
        assertEquals(1, dismissedB)
    }

    @Test
    fun `closing a pop-up that left the composition never fires its dismissal`() {
        // Save/Cancel removes the pop-up itself; the host must not call back into a gone composable.
        val host = TransientPopupHost()
        var dismissed = 0
        val key = Any()
        host.open(key) { dismissed++ }
        host.setBounds(key, cardA)

        host.close(key)
        host.onPress(Offset(50f, 50f))

        assertEquals(0, dismissed)
    }

    @Test
    fun `a press with nothing open is free`() {
        val host = TransientPopupHost()
        host.onPress(Offset(50f, 50f))
        assertTrue(true)
    }
}
