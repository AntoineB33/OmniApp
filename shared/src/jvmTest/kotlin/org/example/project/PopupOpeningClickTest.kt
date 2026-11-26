package org.example.project

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Density
import kotlin.test.Test
import kotlin.test.assertEquals
import org.example.project.scheduler.ui.contextMenuModifier
import org.example.project.ui.deafToTheOpeningClick

/**
 * Anomaly 2026-11-26: *"Right-clicking the row Action: Minimum time / Contexts and states opens two windows"* — the row's
 * menu, a pop-up Search window opening under the pointer while the right button is still held, got that held button as a
 * press of its own (Compose Desktop's, no AWT press: a `[popup]` trace showed none), and its background opened its own
 * menu ("Show the header", …). A pop-up answers no press until the click that opened it is over.
 */
@OptIn(ExperimentalComposeUiApi::class)
class PopupOpeningClickTest {
    private class Popup {
        var menus = 0
        /** The presses that reached the pop-up's inside unconsumed. */
        var pressesInside = 0
        var time = 0L
        val scene = onEdt {
            ImageComposeScene(200, 200, density = Density(1f)) {
                Box(
                    Modifier.fillMaxSize().deafToTheOpeningClick()
                        .then(contextMenuModifier(enabled = true, key = "bg") { menus++ })
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    if (event.type == PointerEventType.Press && event.changes.none { it.isConsumed }) pressesInside++
                                }
                            }
                        },
                )
            }
        }
        val at = Offset(100f, 100f)

        fun frames() = repeat(3) { onEdt { time += 16L; scene.render(time * 1_000_000L) } }

        fun send(type: PointerEventType, buttons: PointerButtons?, button: PointerButton?, pointer: PointerType = PointerType.Mouse) = onEdt {
            time += 50L
            scene.sendPointerEvent(type, at, timeMillis = time, type = pointer, buttons = buttons, button = button)
        }

        fun close() = onEdt { scene.close() }
    }

    private val right = PointerButtons(isSecondaryPressed = true)

    @Test
    fun the_held_button_of_the_click_that_opened_it_opens_nothing_and_the_next_right_click_does() {
        val popup = Popup()
        try {
            popup.frames()
            // The opening click, still held as the pop-up appears: its first events carry the button down.
            popup.send(PointerEventType.Enter, right, null)
            popup.send(PointerEventType.Press, right, PointerButton.Secondary)
            popup.frames()
            assertEquals(0, popup.menus, "the opening click's held button is not a right-click of the pop-up's")
            popup.send(PointerEventType.Release, PointerButtons(), PointerButton.Secondary)
            popup.send(PointerEventType.Move, PointerButtons(), null)
            popup.send(PointerEventType.Press, right, PointerButton.Secondary)
            popup.frames()
            assertEquals(1, popup.menus, "a right-click of its own, once the opening click is over")
        } finally {
            popup.close()
        }
    }

    @Test
    fun a_pop_up_reached_with_no_button_down_answers_its_first_right_click() {
        val popup = Popup()
        try {
            popup.frames()
            popup.send(PointerEventType.Move, PointerButtons(), null)
            popup.send(PointerEventType.Press, right, PointerButton.Secondary)
            popup.frames()
            assertEquals(1, popup.menus)
        } finally {
            popup.close()
        }
    }

    @Test
    fun a_finger_has_no_hover_its_first_press_is_its_own() {
        val popup = Popup()
        try {
            popup.frames()
            popup.send(PointerEventType.Press, PointerButtons(isPrimaryPressed = true), null, PointerType.Touch)
            popup.frames()
            assertEquals(1, popup.pressesInside, "the finger's first press reaches the pop-up's inside")
        } finally {
            popup.close()
        }
    }
}
