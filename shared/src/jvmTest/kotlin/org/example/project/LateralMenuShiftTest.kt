package org.example.project

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import org.example.project.ui.LateralMenu

/**
 * Anomaly 2026-11-01: *"when right-clicking on the left-side menu but not on a button, all the buttons shift down a little
 * bit except the feature page selector"*. The menu's right-click menu is a child of nothing while it is shut, and of the
 * column while it is open: the column's gap, given to it, moved all that stands under it.
 */
@OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class)
class LateralMenuShiftTest {
    @Test
    fun a_right_click_beside_the_buttons_moves_none_of_them() {
        var y = -1f
        val scene =
            ImageComposeScene(width = 300, height = 500, density = Density(1f)) {
                MaterialTheme {
                    LateralMenu(
                        page = OmniPage.TaskScheduler,
                        onPageSelected = {},
                        items = {
                            Box(Modifier.fillMaxWidth().height(40.dp).onGloballyPositioned { y = it.positionInRoot().y })
                        },
                    )
                }
            }
        try {
            var time = 0L
            fun frames() = repeat(8) { time += 16_000_000L; scene.render(time) }
            frames()
            val before = y
            assertEquals(true, before > 0f, "the item is laid out")
            val empty = Offset(90f, 400f)
            scene.sendPointerEvent(PointerEventType.Move, empty)
            scene.sendPointerEvent(PointerEventType.Press, empty, buttons = PointerButtons(isSecondaryPressed = true), button = PointerButton.Secondary)
            frames()
            scene.sendPointerEvent(PointerEventType.Release, empty, buttons = PointerButtons(), button = PointerButton.Secondary)
            frames()
            assertEquals(before, y, "the buttons stay where they are")
        } finally {
            scene.close()
        }
    }
}
