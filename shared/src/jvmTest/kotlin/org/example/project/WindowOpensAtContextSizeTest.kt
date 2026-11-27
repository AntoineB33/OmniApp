package org.example.project

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Density
import kotlin.test.Test
import kotlin.test.assertEquals
import org.example.project.scheduler.domain.ContextDomain
import org.example.project.scheduler.domain.SearchDomain
import org.example.project.ui.AppWindowFrame
import org.example.project.ui.LocalContextDefaults
import org.example.project.ui.LocalWindowFrameHost
import org.example.project.ui.WindowFrameHost
import org.example.project.ui.WindowFrameState

/**
 * User rule 2026-11-27: **a window opens at its context's size, as the account set it, cut to its share of the room** —
 * the frame reads [ContextDomain.sizeOf] and [ContextDomain.fitted], and the size it opens at is kept as its own.
 */
@OptIn(InternalComposeUiApi::class)
class WindowOpensAtContextSizeTest {

    private fun openedSize(defaults: List<org.example.project.scheduler.domain.ContextDefault>, state: WindowFrameState = WindowFrameState("w")): Size =
        onEdt {
            val scene = ImageComposeScene(width = 800, height = 600, density = Density(1f)) {
                MaterialTheme {
                    CompositionLocalProvider(LocalWindowFrameHost provides WindowFrameHost(), LocalContextDefaults provides defaults) {
                        Box(Modifier.fillMaxSize()) {
                            AppWindowFrame(title = "w", state = state, onClose = {}, sizeContext = ContextDomain.frameContextId("Alarms")) {
                                Text("inside")
                            }
                        }
                    }
                }
            }
            try {
                var time = 0L
                repeat(4) { time += 16_000_000L; scene.render(time) }
                state.size
            } finally {
                scene.close()
            }
        }

    @Test
    fun a_window_opens_at_the_code_s_size_of_its_context() {
        assertEquals(Size(440f, 560f), openedSize(emptyList()))
    }

    @Test
    fun a_window_opens_at_the_account_s_size_cut_to_its_share_of_the_room() {
        val alarms = ContextDomain.frameContextId("Alarms")
        val config = ContextDomain.configOf(emptyList(), alarms)
        // Wider and taller than the 800 × 600 room: its width cut to half the room, its height to the whole room.
        val defaults = ContextDomain.written(emptyList(), alarms, config.copy(size = SearchDomain.WindowSize(1200, 900, 50, 100)))
        assertEquals(Size(400f, 600f), openedSize(defaults))
    }

    @Test
    fun a_window_the_user_sized_keeps_its_own_size() {
        val defaults = ContextDomain.withSize(emptyList(), ContextDomain.frameContextId("Alarms"), 300, 300)
        assertEquals(Size(500f, 350f), openedSize(defaults, WindowFrameState("w", initialSize = Size(500f, 350f))))
    }
}
