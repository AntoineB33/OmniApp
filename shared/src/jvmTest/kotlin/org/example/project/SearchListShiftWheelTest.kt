package org.example.project

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.example.project.ui.GivenRow
import org.example.project.ui.LATERAL_MENU_SEARCH
import org.example.project.ui.LateralMenu
import org.example.project.ui.MenuItemRows

/**
 * User rule 2026-11-15: Shift+scroll scrolls a Search window's result list vertically. Compose Desktop sends a
 * Shift+wheel notch as a horizontal delta; the list moves down by it as by a plain notch, and up for the other way.
 * Without Shift, a horizontal delta (a sideways swipe) leaves it where it is.
 */
@OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class)
class SearchListShiftWheelTest {
    private val keys = (0 until 60).map { "row$it" }
    private val tops = HashMap<String, Float>()
    private var time = 0L

    private fun scene() = ImageComposeScene(width = 300, height = 600, density = Density(1f)) {
        MaterialTheme {
            LateralMenu(
                page = OmniPage.TaskScheduler,
                onPageSelected = {},
                config = LATERAL_MENU_SEARCH,
                items = { _ ->
                    MenuItemRows(
                        rows = keys.map { GivenRow(it, it) },
                        row = { key, _ ->
                            Box(
                                Modifier.fillMaxWidth().height(40.dp)
                                    .onGloballyPositioned { tops[key] = it.boundsInRoot().top },
                            ) { Text(key) }
                        },
                        onActivate = {},
                        onArrange = { _, _, _ -> },
                        onGroup = { _, _ -> },
                    )
                },
            )
        }
    }

    private fun frames(scene: ImageComposeScene) = onEdt { repeat(6) { time += 16_000_000L; scene.render(time) } }

    private fun wheel(scene: ImageComposeScene, delta: Offset, shift: Boolean) = onEdt {
        val at = Offset(100f, 300f)
        scene.sendPointerEvent(PointerEventType.Move, at)
        scene.sendPointerEvent(
            PointerEventType.Scroll, at, scrollDelta = delta,
            keyboardModifiers = PointerKeyboardModifiers(isShiftPressed = shift),
        )
    }

    @Test
    fun shiftWheelScrollsTheListVertically() {
        val scene = scene()
        try {
            frames(scene)
            val start = tops.getValue("row5")

            wheel(scene, Offset(3f, 0f), shift = false)
            frames(scene)
            assertEquals(start, tops.getValue("row5"), "a sideways swipe without Shift leaves the list")

            wheel(scene, Offset(3f, 0f), shift = true)
            frames(scene)
            val down = tops.getValue("row5")
            assertTrue(down < start - 1f, "Shift+wheel down scrolls the list down: row5 $start -> $down")

            wheel(scene, Offset(-3f, 0f), shift = true)
            frames(scene)
            assertEquals(start, tops.getValue("row5"), 0.5f, "Shift+wheel up scrolls it back")
        } finally {
            onEdt { scene.close() }
        }
    }
}
