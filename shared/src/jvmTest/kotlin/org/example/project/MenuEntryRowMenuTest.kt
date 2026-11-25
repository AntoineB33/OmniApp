package org.example.project

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.example.project.scheduler.ui.COPY_ID_LABEL
import org.example.project.ui.ActionMenu
import org.example.project.ui.MenuAction
import org.example.project.ui.transientMenuDismissRoot

/**
 * Anomaly 2026-11-25: *"when I right-click a row, then right-click a row in the right-click menu R, it doesn't open a
 * right-click menu as well"* — an entry of a menu is a row like any: its right-click opens the one row menu, whose "add"
 * puts it in R's own added elements. It does not run the entry.
 */
@OptIn(ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
class MenuEntryRowMenuTest {
    @Test
    fun a_menu_entrys_right_click_opens_the_row_menu_whose_add_adds_it_to_the_menus_list() {
        var acted = 0
        val menus = org.example.project.ui.TransientMenuHost()
        val scene = onEdt {
            ImageComposeScene(1000, 800, density = Density(1f)) {
                CompositionLocalProvider(org.example.project.ui.LocalTransientMenuHost provides menus) {
                    Box(Modifier.fillMaxSize().transientMenuDismissRoot(menus)) {
                        var open by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(true) }
                        ActionMenu(
                            "R",
                            open = open,
                            onDismiss = { open = false },
                            actions = listOf(MenuAction("alpha") { acted++ }, MenuAction("beta") { acted++ }),
                        )
                    }
                }
            }
        }
        var time = 0L
        fun frames(n: Int) = repeat(n) { onEdt { time += 16L; scene.render(time * 1_000_000L) } }
        fun nodes(): List<SemanticsNode> = onEdt { scene.semanticsOwners.flatMap { it.getAllSemanticsNodes(mergingEnabled = false) } }
        fun texts(): List<String> = nodes().mapNotNull { n -> n.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text } }
        fun find(text: String): Offset =
            nodes().last { n -> n.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true }.boundsInWindow.center
        fun press(at: Offset, secondary: Boolean) {
            onEdt {
                time += 1_000L
                scene.sendPointerEvent(PointerEventType.Move, at, timeMillis = time)
                scene.sendPointerEvent(
                    PointerEventType.Press, at, timeMillis = time,
                    buttons = if (secondary) PointerButtons(isSecondaryPressed = true) else PointerButtons(isPrimaryPressed = true),
                    button = if (secondary) PointerButton.Secondary else PointerButton.Primary,
                )
                scene.sendPointerEvent(PointerEventType.Release, at, timeMillis = time + 30, button = if (secondary) PointerButton.Secondary else PointerButton.Primary)
            }
            frames(6)
        }
        try {
            frames(6)
            press(find("beta"), secondary = true)
            assertEquals(0, acted, "a right-click does not run the entry")
            assertTrue(COPY_ID_LABEL in texts(), "the row menu is open: ${texts()}")
            assertTrue("add" in texts(), "with its add: ${texts()}")
            press(find("add"), secondary = false)
            assertEquals(0, acted, "adding runs nothing")
            // Added to R's own list: its row in R is outlined as an added element's is ([org.example.project.ui.addedOutline]).
            val outlined = nodes().filter { it.config.getOrNull(org.example.project.ui.AddedOutlined) == true }
            assertEquals(1, outlined.size, "one entry outlined: ${texts()}")
            val beta = nodes().last { n -> n.config.getOrNull(SemanticsProperties.Text)?.any { it.text == "beta" } == true }
            assertTrue(outlined.single().boundsInWindow.contains(beta.boundsInWindow.center), "it is beta")
        } finally {
            onEdt { scene.close() }
        }
    }
}
