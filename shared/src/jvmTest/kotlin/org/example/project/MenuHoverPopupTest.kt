package org.example.project

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.example.project.ui.ActionMenu
import org.example.project.ui.MenuAction
import org.example.project.ui.transientMenuDismissRoot

/**
 * User rule 2026-11-21: *"Right-clicking in the calendar must open a search window with options such like 'add' or
 * 'edit'. Those two options open when hovered over windows to precise what to add or edit."* — a menu entry with a
 * [MenuAction.hoverPopup] shows it while hovered ([org.example.project.ui.HoverPopupRow]), the pop-up goes once the
 * pointer has left the entry and it, and a click on the entry keeps it (pinned) whatever the pointer does next.
 */
@OptIn(ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
class MenuHoverPopupTest {
    private val popupText = "What to add here"

    @Test
    fun an_entry_shows_its_pop_up_while_hovered_and_keeps_it_once_clicked() {
        var clicked = 0
        val menus = org.example.project.ui.TransientMenuHost()
        val scene = onEdt {
            ImageComposeScene(1000, 700, density = Density(1f)) {
                CompositionLocalProvider(org.example.project.ui.LocalTransientMenuHost provides menus) {
                    Box(Modifier.fillMaxSize().transientMenuDismissRoot(menus)) {
                        ActionMenu(
                            "Test", open = true, onDismiss = {},
                            actions = listOf(
                                MenuAction("add…", hoverPopup = { Text(popupText) }) { clicked++ },
                                MenuAction("other") {},
                            ),
                        )
                    }
                }
            }
        }
        var time = 0L
        fun frames(n: Int) = repeat(n) { onEdt { time += 16L; scene.render(time * 1_000_000L) } }
        fun texts(): List<String> = onEdt {
            scene.semanticsOwners.flatMap { it.getAllSemanticsNodes(mergingEnabled = false) }
                .flatMap { n -> n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }
        }
        fun centerOf(label: String): Offset = onEdt {
            scene.semanticsOwners.flatMap { it.getAllSemanticsNodes(mergingEnabled = false) }
                .last { n -> n.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true }
                .boundsInWindow.center
        }
        fun moveTo(at: Offset) = onEdt { time += 200L; scene.sendPointerEvent(PointerEventType.Move, at, timeMillis = time) }
        try {
            frames(6)
            assertFalse(popupText in texts(), "nothing shown before the hover")
            // User rule 2026-11-21: *"When a row shows a window when hovered over, there must be an arrow indicating it."*
            // One arrow: on "add…", which opens a pop-up, and not on "other", on the same line as "add…", before it.
            val arrows = onEdt {
                scene.semanticsOwners.flatMap { it.getAllSemanticsNodes(mergingEnabled = false) }
                    .filter { n -> n.config.getOrNull(SemanticsProperties.Text)?.any { it.text == "◂" } == true }
                    .map { it.boundsInWindow }
            }
            assertEquals(1, arrows.size, "one arrow, on the entry with a pop-up: $arrows")
            assertTrue(kotlin.math.abs(arrows.single().center.y - centerOf("add…").y) < 4f, "the arrow is on the add… row: $arrows")

            moveTo(centerOf("add…"))
            frames(6)
            assertTrue(popupText in texts(), "the hover shows the entry's pop-up")

            // Off the entry and its pop-up, onto empty space: past the crossing's grace, the pop-up is gone.
            val away = Offset(950f, 30f)
            moveTo(away)
            frames(4)
            Thread.sleep(org.example.project.ui.MenuHoverTracker.GRACE_MILLIS * 3)
            frames(6)
            assertFalse(popupText in texts(), "the pointer gone from the entry and its pop-up, the pop-up goes")

            // A click on the entry opens it pinned — a finger's way to it, a finger making no hover: the pointer leaving
            // no longer closes it. (Pressed and released before a frame draws the hover's pop-up: drawn in the scene, as
            // the tests draw menus, a focusable pop-up would take the press; on the desktop it is a window of its own.)
            val add = centerOf("add…")
            onEdt {
                time += 50L
                scene.sendPointerEvent(PointerEventType.Press, add, timeMillis = time, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
                time += 40L
                scene.sendPointerEvent(PointerEventType.Release, add, timeMillis = time, button = PointerButton.Primary)
            }
            frames(4)
            assertTrue(popupText in texts(), "the click shows the pop-up")
            assertEquals(1, clicked, "the entry's own action runs too, as any entry's")
            moveTo(away)
            frames(4)
            Thread.sleep(org.example.project.ui.MenuHoverTracker.GRACE_MILLIS * 3)
            frames(6)
            assertTrue(popupText in texts(), "clicked, the pop-up stays when the pointer leaves")
        } finally {
            onEdt { scene.close() }
        }
    }
}
