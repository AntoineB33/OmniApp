package org.example.project

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import kotlin.test.Test
import kotlin.test.assertEquals
import org.example.project.ui.ActionMenu
import org.example.project.ui.MenuAction
import org.example.project.ui.transientMenuDismissRoot

/**
 * Anomaly 2026-11-15 (7): *"under Parents, click in 'add a parent', right-click, select show the header, and the window
 * disappears"* — a menu entry acted on the PRESS: on the desktop the menu is a window of its own, gone under a button
 * still held, and the rest of the click reached the "add a parent" pop-up under it, which picked a parent and closed.
 * An entry acts on the release, of either button ([org.example.project.ui.SearchWindow]'s `pickOnRelease`).
 */
@OptIn(ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
class MenuActsOnReleaseTest {
    @Test
    fun a_menu_entry_acts_on_the_release_not_on_the_press() {
        var acted = 0
        val menus = org.example.project.ui.TransientMenuHost()
        val scene = onEdt {
            ImageComposeScene(800, 600, density = Density(1f)) {
                CompositionLocalProvider(org.example.project.ui.LocalTransientMenuHost provides menus) {
                    Box(Modifier.fillMaxSize().transientMenuDismissRoot(menus)) {
                        // As most callers do: the entry's own handler closes the menu.
                        var open by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(true) }
                        ActionMenu("Test", open = open, onDismiss = { open = false }, actions = listOf(MenuAction("Show the header") { acted++; open = false }))
                    }
                }
            }
        }
        var time = 0L
        fun frames(n: Int) = repeat(n) { onEdt { time += 16L; scene.render(time * 1_000_000L) } }
        try {
            frames(6)
            val at = onEdt {
                scene.semanticsOwners.flatMap { it.getAllSemanticsNodes(mergingEnabled = false) }
                    .last { n -> n.config.getOrNull(SemanticsProperties.Text)?.any { it.text == "Show the header" } == true }
                    .boundsInWindow.center
            }
            onEdt {
                time += 1_000L
                scene.sendPointerEvent(PointerEventType.Move, at, timeMillis = time)
                scene.sendPointerEvent(PointerEventType.Press, at, timeMillis = time, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
            }
            frames(4)
            assertEquals(0, acted, "nothing on the press: the button is still held")
            onEdt { scene.sendPointerEvent(PointerEventType.Release, at, timeMillis = time + 40, button = PointerButton.Primary) }
            frames(4)
            assertEquals(1, acted, "the release acts, once")
            // User rule 2026-11-15: *"Every menu stay open after you choose an entry"* — whatever its caller does on the pick.
            val stillShown = onEdt {
                scene.semanticsOwners.flatMap { it.getAllSemanticsNodes(mergingEnabled = false) }
                    .any { n -> n.config.getOrNull(SemanticsProperties.Text)?.any { it.text == "Show the header" } == true }
            }
            kotlin.test.assertTrue(stillShown, "the menu stays open after its entry")
        } finally {
            onEdt { scene.close() }
        }
    }
}
