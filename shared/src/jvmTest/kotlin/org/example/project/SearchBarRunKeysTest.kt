package org.example.project

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.example.project.ui.typesInBar

/**
 * User rule 2026-11-20 (`SearchBarPinTest`): the bar's run of keystrokes goes on while the user only changes its text; any
 * other key — the caret moved, an undo, Enter — is "something else", and the text it left is a stop of the bar's pin.
 */
class SearchBarRunKeysTest {
    @OptIn(androidx.compose.ui.InternalComposeUiApi::class)
    private fun k(key: Key, char: Int = 0, ctrl: Boolean = false) =
        KeyEvent(key = key, type = KeyEventType.KeyDown, codePoint = char, isCtrlPressed = ctrl)

    @Test
    fun only_a_key_that_changes_the_text_keeps_the_run_going() {
        assertTrue(typesInBar(k(Key.A, 'a'.code)))
        assertTrue(typesInBar(k(Key.Backspace)))
        assertTrue(typesInBar(k(Key.V, 'v'.code, ctrl = true)), "a paste changes the text")
        assertTrue(typesInBar(k(Key.ShiftLeft)), "a modifier alone does nothing yet")
        assertFalse(typesInBar(k(Key.DirectionLeft)), "moving the caret is something else")
        assertFalse(typesInBar(k(Key.Z, 'z'.code, ctrl = true)), "an undo is something else")
        assertFalse(typesInBar(k(Key.Enter, 13)))
    }
}
