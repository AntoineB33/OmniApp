package org.example.project

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.unit.Density
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.example.project.ui.OutlinedTextField

/**
 * User rule 2026-10-05: **pressing Enter in a field ends its edit** — the app's one text field
 * (`ui/EnterLeavesField.kt`) gives up the focus. Driven through real key events on a rendered field.
 */
@OptIn(InternalComposeUiApi::class)
class EnterLeavesFieldTest {

    private class Probe {
        var focused by mutableStateOf(false)
        var text by mutableStateOf("20:3")
        var ownEnters by mutableStateOf(0)
    }

    private fun scene(probe: Probe, singleLine: Boolean, ownEnter: Boolean = false) =
        ImageComposeScene(width = 300, height = 200, density = Density(1f)) {
            MaterialTheme {
                Column {
                    val requester = remember { FocusRequester() }
                    LaunchedEffect(Unit) { requester.requestFocus() }
                    OutlinedTextField(
                        value = probe.text,
                        onValueChange = { probe.text = it },
                        singleLine = singleLine,
                        modifier = Modifier
                            .focusRequester(requester)
                            .onFocusChanged { probe.focused = it.isFocused }
                            // A field that gives Enter a meaning of its own (the Search bar, a picker).
                            .onPreviewKeyEvent { event ->
                                if (ownEnter && event.key == Key.Enter) {
                                    probe.ownEnters++
                                    true
                                } else {
                                    false
                                }
                            },
                    )
                }
            }
        }

    private fun ImageComposeScene.press(key: Key, shift: Boolean = false) {
        sendKeyEvent(KeyEvent(key = key, type = KeyEventType.KeyDown, isShiftPressed = shift))
        sendKeyEvent(KeyEvent(key = key, type = KeyEventType.KeyUp, isShiftPressed = shift))
        render(0)
    }

    @Test
    fun enter_ends_the_edit_of_a_single_line_field() {
        val probe = Probe()
        val scene = scene(probe, singleLine = true)
        try {
            scene.render(0)
            assertTrue(probe.focused, "the field is being edited")
            scene.press(Key.Enter, shift = true)
            assertTrue(probe.focused, "a chord is not Enter")
            scene.press(Key.Enter)
            assertFalse(probe.focused, "Enter left it")
            assertEquals("20:3", probe.text, "and typed nothing")
        } finally {
            scene.close()
        }
    }

    @Test
    fun the_keypads_enter_does_too() {
        val probe = Probe()
        val scene = scene(probe, singleLine = true)
        try {
            scene.render(0)
            scene.press(Key.NumPadEnter)
            assertFalse(probe.focused)
        } finally {
            scene.close()
        }
    }

    @Test
    fun a_field_with_an_enter_of_its_own_answers_first_and_a_field_of_several_lines_keeps_it() {
        val own = Probe()
        val first = scene(own, singleLine = true, ownEnter = true)
        try {
            first.render(0)
            first.press(Key.Enter)
            assertTrue(own.focused, "its own Enter is not an exit")
            assertTrue(own.ownEnters > 0)
        } finally {
            first.close()
        }
        val lines = Probe()
        val second = scene(lines, singleLine = false)
        try {
            second.render(0)
            second.press(Key.Enter)
            assertTrue(lines.focused, "Enter is a new line there")
        } finally {
            second.close()
        }
    }
}
