package org.example.project.ui

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * PRD §4: the one rule for "this key press is text" — what opens Edit Mode on a selected task cell or a selected
 * Search task row. 2026-09-30: the Search window had its own copy, which let a bare Shift (held for a shift-click
 * on a check box) through as U+FFFF and renamed the task to a square.
 */
class PrintableCharTest {
    // The event is built the only way Compose lets a test build one without a window.
    @OptIn(InternalComposeUiApi::class)
    private fun pressed(key: Key, codePoint: Int, ctrl: Boolean = false, alt: Boolean = false, shift: Boolean = false) =
        KeyEvent(key, KeyEventType.KeyDown, codePoint, ctrl, false, alt, shift)

    @Test
    fun `a bare Shift is not text, though desktop reports it as U+FFFF`() {
        assertNull(pressed(Key.ShiftLeft, 0xFFFF, shift = true).printableChar())
        assertNull(pressed(Key.ShiftRight, 0xFFFF, shift = true).printableChar())
    }

    @Test
    fun `U+FFFF is not text whatever key reports it`() {
        assertNull(pressed(Key.Unknown, 0xFFFF).printableChar())
    }

    @Test
    fun `a letter is text, and so is a shifted one`() {
        assertEquals("a", pressed(Key.A, 'a'.code).printableChar())
        assertEquals("A", pressed(Key.A, 'A'.code, shift = true).printableChar())
    }

    @Test
    fun `Ctrl and the other modifier keys are not text`() {
        assertNull(pressed(Key.C, 'c'.code, ctrl = true).printableChar())
        assertNull(pressed(Key.CtrlLeft, 0xFFFF, ctrl = true).printableChar())
        assertNull(pressed(Key.AltLeft, 0xFFFF, alt = true).printableChar())
    }
}
