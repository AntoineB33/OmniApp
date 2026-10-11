package org.example.project

import androidx.compose.ui.input.key.Key
import java.awt.event.KeyEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.example.project.ui.TabWalk

/**
 * Anomaly 2026-10-11: *"I type ctrl + 9/3, with or without num lk, but it doesn't do anything."* The window bar's
 * chords were `Key.PageDown` / `Key.PageUp` — the keys of the block above the arrows. The numeric pad's 3 and 9 are
 * Page Down and Page Up too, but the desktop gives them another [Key]: the same code at the pad's LOCATION with Num
 * Lock off, the digits' with it on. Built here as the desktop builds them, which is why this is a desktop test.
 */
class TabWalkNumpadTest {
    private fun move(key: Key, shift: Boolean = false) = TabWalk.moveFor(key, keyDown = true, ctrlOrMeta = true, shift = shift, alt = false)

    @Test
    fun the_numeric_pads_3_and_9_walk_the_bar_with_num_lock_off_and_on() {
        // Num Lock off: Page Down / Page Up, sent from the pad.
        val padPageDown = Key(KeyEvent.VK_PAGE_DOWN, KeyEvent.KEY_LOCATION_NUMPAD)
        val padPageUp = Key(KeyEvent.VK_PAGE_UP, KeyEvent.KEY_LOCATION_NUMPAD)
        assertEquals(false, padPageDown == Key.PageDown, "the pad's key is another Key: equality alone never saw it")
        assertEquals(TabWalk.Move.BarNext, move(padPageDown))
        assertEquals(TabWalk.Move.BarPrevious, move(padPageUp))
        // Num Lock on: the digits of the pad.
        assertEquals(TabWalk.Move.BarNext, move(Key(KeyEvent.VK_NUMPAD3, KeyEvent.KEY_LOCATION_NUMPAD)))
        assertEquals(TabWalk.Move.BarPrevious, move(Key(KeyEvent.VK_NUMPAD9, KeyEvent.KEY_LOCATION_NUMPAD)))
        // The block above the arrows, as before.
        assertEquals(TabWalk.Move.BarNext, move(Key.PageDown))
        assertEquals(TabWalk.Move.BarPrevious, move(Key.PageUp))
        // The digits of the top row are not the pad's: Ctrl + 3 and Ctrl + 9 stay free.
        assertNull(move(Key.Three))
        assertNull(move(Key.Nine))
        assertNull(move(Key(KeyEvent.VK_NUMPAD4, KeyEvent.KEY_LOCATION_NUMPAD)))
    }
}
