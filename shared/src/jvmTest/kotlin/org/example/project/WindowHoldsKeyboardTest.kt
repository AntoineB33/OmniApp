package org.example.project

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import org.example.project.ui.AppWindowFrame
import org.example.project.ui.LocalWindowFrameHost
import org.example.project.ui.TabWalk
import org.example.project.ui.WindowFrameHost
import org.example.project.ui.WindowFrameState

/**
 * Anomaly 2026-10-15: *"Ctrl + W doesn't seem to work for the 'unfocused notif' window"*. The app's chords are answered
 * on the root's preview pass, which a key only reaches through a node holding the keyboard; a window with no key
 * handlers of its own (every Search window) held none, so once the calendar holding it closed, the focus went to the
 * notif window and the keys went nowhere. Every window now holds the keyboard when it is focused.
 */
@OptIn(InternalComposeUiApi::class)
class WindowHoldsKeyboardTest {

    @Test
    fun ctrl_w_reaches_the_app_in_a_focused_window_that_has_no_key_handler_of_its_own() {
        val host = WindowFrameHost()
        val closed = mutableListOf<String>()
        var open by mutableStateOf(setOf("Calendar", "Search#2"))
        val scene =
            ImageComposeScene(width = 900, height = 700, density = Density(1f)) {
                MaterialTheme {
                    CompositionLocalProvider(LocalWindowFrameHost provides host) {
                        Box(
                            Modifier.fillMaxSize().onPreviewKeyEvent { event ->
                                val move = TabWalk.moveFor(
                                    event.key, event.type == KeyEventType.KeyDown, event.isCtrlPressed || event.isMetaPressed,
                                    shift = false, alt = false,
                                )
                                if (move == TabWalk.Move.CloseWindow) host.closeFocused() else false
                            },
                        ) {
                            for (id in open) {
                                androidx.compose.runtime.key(id) {
                                    AppWindowFrame(
                                        title = id,
                                        state = androidx.compose.runtime.remember { WindowFrameState(id) },
                                        // As the app's close: noted now, the window gone at the next frame — never a
                                        // state written from inside the key's own dispatch.
                                        onClose = {
                                            closed += id
                                            host.focusHeirOf(id)?.let(host::focus)
                                        },
                                        defaultWidth = 400.dp,
                                        defaultHeight = 300.dp,
                                    ) { Text("nothing to type in") }
                                }
                            }
                        }
                    }
                }
            }
        try {
            var time = 0L
            fun frames() = repeat(6) { time += 16_000_000L; scene.render(time) }
            fun ctrlW() {
                scene.sendKeyEvent(KeyEvent(key = Key.W, type = KeyEventType.KeyDown, isCtrlPressed = true))
                scene.sendKeyEvent(KeyEvent(key = Key.W, type = KeyEventType.KeyUp, isCtrlPressed = true))
                open = open - closed.toSet()
                frames()
            }
            frames()
            host.focus("Calendar")
            frames()
            ctrlW()
            assertEquals(listOf("Calendar"), closed)
            // The focus went on to the notif window, which holds no field: Ctrl + W closes it too.
            frames()
            assertEquals("Search#2", host.focusedId)
            ctrlW()
            assertEquals(listOf("Calendar", "Search#2"), closed)
        } finally {
            scene.close()
        }
    }
}
