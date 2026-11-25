package org.example.project

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.SearchDomain
import org.example.project.scheduler.state.SchedulerState
import org.example.project.ui.DetachedAs
import org.example.project.ui.DetachedDrag
import org.example.project.ui.EMBEDDED_SEARCH_SPLITS
import org.example.project.ui.LocalPopupChrome
import org.example.project.ui.LocalPopupDetacher
import org.example.project.ui.OPTION_HANDLERS
import org.example.project.ui.OPTION_OPENERS
import org.example.project.ui.PopupChrome
import org.example.project.ui.PopupDetacher
import org.example.project.ui.SearchOptions
import org.example.project.ui.SearchWindow

/**
 * Anomaly 2026-11-25: *"I right-clicked on a md row, which opened window B, then right-clicked on the background of B to
 * select show header, but the header doesn't have the buttons to maximize or save the window for example, and when I
 * drag it it disappear. This means that the app is not unified enough."* — **every Search window drawn in no frame wears a
 * window's head**, whatever draws it: a menu's list (its entries given), a pop-up, a Search window inside another. Its
 * buttons make it a Search window of the app — a menu's carrying the entries it was given.
 */
@OptIn(ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
class EveryPopupHeadTest {
    private class Asked(val config: SearchDomain.Config, val given: SearchOptions?, val how: DetachedAs)

    private fun check(popup: Boolean, options: SearchOptions?) {
        val asked = ArrayList<Asked>()
        val detacher = PopupDetacher(
            canDetach = { false },
            detach = { _, _, _, _ -> DetachedDrag({}, {}) },
            detachSearch = { config, _, how, _, _ -> asked += Asked(config, null, how); DetachedDrag({}, {}) },
            detachGiven = { config, given, _, how, _, _ -> asked += Asked(config, given.value, how); DetachedDrag({}, {}) },
        )
        var dismissed = 0
        val scene = onEdt {
            ImageComposeScene(900, 700, density = Density(1f)) {
                CompositionLocalProvider(LocalPopupDetacher provides detacher, LocalPopupChrome provides if (popup) PopupChrome() else null) {
                    Box(Modifier.fillMaxSize()) {
                        SearchWindow(
                            state = SchedulerState.empty(),
                            openers = OPTION_OPENERS,
                            onStartTaskNow = {},
                            onGoToTaskTree = { _, _ -> },
                            onDeepCopyCell = {},
                            actionHandlers = OPTION_HANDLERS,
                            onIntent = {},
                            onDismiss = { dismissed++ },
                            config = if (options != null) SearchDomain.OPTION_SEARCH else SearchDomain.Config(),
                            onConfigChange = {},
                            // Its header shown, as its background menu's "Show the header" does.
                            splits = EMBEDDED_SEARCH_SPLITS.copy(headerRemoved = false),
                            embedded = true,
                            options = options,
                            modifier = Modifier.size(600.dp, 400.dp),
                        )
                    }
                }
            }
        }
        var time = 0L
        fun frames() = repeat(6) { onEdt { time += 16L; scene.render(time * 1_000_000L) } }
        fun nodes(): List<SemanticsNode> = onEdt { scene.semanticsOwners.flatMap { it.getAllSemanticsNodes(mergingEnabled = false) } }
        fun texts(): List<String> = nodes().mapNotNull { n -> n.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text } }
        try {
            frames()
            for (glyph in listOf("☆", "⧉", "↔", "↕", "—", "▢")) assertTrue(glyph in texts(), "the head has $glyph: ${texts()}")
            val at: Offset = nodes().first { n -> n.config.getOrNull(SemanticsProperties.Text)?.any { it.text == "▢" } == true }.boundsInWindow.center
            onEdt {
                time += 1_000L
                scene.sendPointerEvent(PointerEventType.Move, at, timeMillis = time)
                scene.sendPointerEvent(PointerEventType.Press, at, timeMillis = time, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
                scene.sendPointerEvent(PointerEventType.Release, at, timeMillis = time + 30, button = PointerButton.Primary)
            }
            frames()
            assertEquals(1, asked.size, "maximize made it a window of the app")
            assertEquals(DetachedAs.Maximized, asked.single().how)
            assertTrue(asked.single().given === options, "a menu's window lists the entries it was given")
            assertEquals(1, dismissed, "and it is closed where it stood")
        } finally {
            onEdt { scene.close() }
        }
    }

    @Test
    fun a_menus_list_wears_a_windows_head_and_becomes_a_window_with_its_entries() =
        check(popup = true, options = SearchOptions(options = listOf("0" to "alpha", "1" to "beta"), checked = null, onClick = {}))

    @Test
    fun a_pop_up_search_window_wears_a_windows_head() = check(popup = true, options = null)

    @Test
    fun a_search_window_inside_another_wears_a_windows_head() = check(popup = false, options = null)
}
