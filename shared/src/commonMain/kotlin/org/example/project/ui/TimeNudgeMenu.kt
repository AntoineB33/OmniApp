package org.example.project.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.window.PopupProperties

/** What the `HH:MM` fields' right-click menu adds or removes, in minutes, in the order it lists them. */
internal val TIME_NUDGE_MINUTES: List<Int> = listOf(60, 10, 1, -1, -10, -60)

/** How the menu names a step: `+1 h`, `−10 min`. */
internal fun timeNudgeLabel(deltaMinutes: Int): String {
    val size = kotlin.math.abs(deltaMinutes)
    return (if (deltaMinutes > 0) "+" else "−") + if (size % 60 == 0) "${size / 60} h" else "$size min"
}

/** A time of day [deltaMinutes] later (earlier when negative), round the clock: 23:30 + 1 h is 00:30. */
internal fun nudgedTimeOfDay(minutes: Int, deltaMinutes: Int): Int = ((minutes + deltaMinutes) % 1440 + 1440) % 1440

/**
 * **The right-click menu of a field that holds a time as `HH:MM`** (user rule 2026-10-05): add or remove 1 h, 10 min
 * or 1 min ([TIME_NUDGE_MINUTES]). ONE menu for every such field — a quota's and "Add to the calendar"'s times, an
 * alarm's, a reminder's, the sleep schedule's — wrapped round the [field], whatever it is drawn with.
 *
 * The right-click is taken on the Initial pass and consumed: it is this menu's, never the text field's own. The menu
 * STAYS OPEN, like a timer's countdown fields' (`AlarmWindow`): "+10 min" three times is three clicks, not three
 * right-clicks. It leaves like any menu, on the first press outside it (`popups.md`). With [enabled] false (no time
 * to step from) the right-click opens nothing.
 */
@Composable
internal fun TimeNudgeMenu(enabled: Boolean = true, onNudge: (deltaMinutes: Int) -> Unit, field: @Composable () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(
        Modifier.pointerInput(enabled) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                        event.changes.forEach { it.consume() }
                        if (enabled) open = true
                    }
                }
            }
        },
    ) {
        field()
        transientMenuDismissal(open) { open = false }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, properties = PopupProperties(focusable = false)) {
            TIME_NUDGE_MINUTES.forEach { delta ->
                DropdownMenuItem(text = { Text(timeNudgeLabel(delta)) }, onClick = { onNudge(delta) })
            }
        }
    }
}
