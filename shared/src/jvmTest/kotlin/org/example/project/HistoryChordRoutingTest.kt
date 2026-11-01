package org.example.project

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.unit.Density
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.example.project.ui.undoRedoIntentFor

/**
 * User rule 2026-11-01: the routing the anomaly went through, on real key events. `App`'s root takes a history chord on
 * the PREVIEW pass, before the focused element, only while the next change was made outside every window; with nothing
 * focused no modifier of the composition sees a key at all, which is what `AppKeyFallback` is for and what `hasFocus`
 * tells it.
 */
@OptIn(InternalComposeUiApi::class)
class HistoryChordRoutingTest {
    private class Probe {
        var appWide by mutableStateOf(false)
        var taken by mutableStateOf(0)
        var seen by mutableStateOf(0)
        var held by mutableStateOf(false)
        var text by mutableStateOf("ab")
    }

    private fun scene(probe: Probe, focusField: Boolean) =
        ImageComposeScene(width = 300, height = 200, density = Density(1f)) {
            Box(
                Modifier
                    .fillMaxSize()
                    .onFocusChanged { probe.held = it.hasFocus }
                    .onPreviewKeyEvent { event ->
                        val chord = undoRedoIntentFor(event)
                        if (chord != null) probe.seen++
                        if (chord != null && probe.appWide) {
                            probe.taken++
                            true
                        } else {
                            false
                        }
                    },
            ) {
                val requester = remember { FocusRequester() }
                LaunchedEffect(Unit) { if (focusField) requester.requestFocus() }
                BasicTextField(probe.text, { probe.text = it }, Modifier.focusRequester(requester))
            }
        }

    private fun ImageComposeScene.ctrlZ() {
        sendKeyEvent(KeyEvent(key = Key.Z, type = KeyEventType.KeyDown, isCtrlPressed = true))
        sendKeyEvent(KeyEvent(key = Key.Z, type = KeyEventType.KeyUp, isCtrlPressed = true))
    }

    @Test
    fun the_root_takes_the_chord_before_a_focused_text_field_while_the_next_change_is_app_wide() {
        val probe = Probe()
        val scene = scene(probe, focusField = true)
        try {
            scene.render(0)
            scene.render(16_000_000)
            assertTrue(probe.held, "the field holds the keyboard")
            scene.ctrlZ()
            scene.render(32_000_000)
            assertEquals(1, probe.seen, "the root sees the chord first")
            assertEquals(0, probe.taken, "and leaves a change of the window to the field")
            probe.appWide = true
            scene.ctrlZ()
            scene.render(48_000_000)
            assertEquals(1, probe.taken)
        } finally {
            scene.close()
        }
    }

    @Test
    fun with_nothing_focused_no_modifier_sees_the_chord_and_has_focus_says_so() {
        val probe = Probe()
        val scene = scene(probe, focusField = false)
        try {
            scene.render(0)
            scene.render(16_000_000)
            assertFalse(probe.held)
            scene.ctrlZ()
            scene.render(32_000_000)
            assertEquals(0, probe.seen, "this is why the desktop window hands such a key to AppKeyFallback")
        } finally {
            scene.close()
        }
    }
}
