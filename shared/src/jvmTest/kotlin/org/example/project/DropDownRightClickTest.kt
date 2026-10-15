package org.example.project

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import org.example.project.scheduler.ui.contextMenuModifier
import org.example.project.ui.CheckBoxDropDown

/**
 * Anomaly 2026-10-15: *"I right-clicked on the element type drop-down menu in the Search window, and it opened a menu
 * as if I right-clicked on the element in the result list."* The type list opens OVER the result rows; a right-click
 * on one of its options checks that option alone — and must reach nothing under the list.
 */
@OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class)
class DropDownRightClickTest {

    private class Probe {
        var checked by mutableStateOf(setOf(0, 1, 2))
        var deployed by mutableStateOf(false)
        var rowMenus = 0
    }

    @Test
    fun a_right_click_on_an_option_picks_it_alone_and_opens_no_menu_under_the_list() {
        val probe = Probe()
        val scene =
            ImageComposeScene(width = 500, height = 700, density = Density(1f)) {
                MaterialTheme {
                    Column(Modifier.fillMaxSize()) {
                        CheckBoxDropDown(
                            options = listOf(0, 1, 2, 3, 4),
                            checked = probe.checked,
                            face = "kinds",
                            label = { "Kind number $it" },
                            onChange = { probe.checked = it },
                            deploy = !probe.deployed,
                            onDeployed = { probe.deployed = true },
                            soloOnRightClick = true,
                        )
                        // The result list under it: a row whose right-click opens its menu.
                        Box(Modifier.fillMaxSize().then(contextMenuModifier(enabled = true) { probe.rowMenus++ }))
                    }
                }
            }
        try {
            var time = 0L
            fun frames() = repeat(20) { time += 16_000_000L; scene.render(time) }
            frames()
            // "Select all", then the options, 48 px each under the field: the middle of "Kind number 1".
            val at = Offset(60f, 30f + 8f + 48f * 2 + 24f)
            val secondary = PointerButtons(isSecondaryPressed = true)
            scene.sendPointerEvent(PointerEventType.Move, at)
            scene.sendPointerEvent(PointerEventType.Press, at, buttons = secondary, button = PointerButton.Secondary)
            frames()
            // The press alone picks nothing and leaves the list open: on the desktop the list is a window of its own,
            // and closed under a button still held it let the rest of the click reach the row beneath.
            assertEquals(setOf(0, 1, 2), probe.checked, "nothing picked before the release")
            scene.sendPointerEvent(PointerEventType.Release, at, buttons = PointerButtons(), button = PointerButton.Secondary)
            frames()
            assertEquals(setOf(1), probe.checked, "the option right-clicked, alone")
            assertEquals(0, probe.rowMenus, "no row under the list opened its menu")
            // A right-click on the closed field opens nothing either.
            scene.sendPointerEvent(PointerEventType.Move, Offset(60f, 12f))
            scene.sendPointerEvent(PointerEventType.Press, Offset(60f, 12f), buttons = secondary, button = PointerButton.Secondary)
            scene.sendPointerEvent(PointerEventType.Release, Offset(60f, 12f), buttons = PointerButtons(), button = PointerButton.Secondary)
            frames()
            assertFalse(probe.rowMenus > 0, "the field is no row")
        } finally {
            scene.close()
        }
    }
}
