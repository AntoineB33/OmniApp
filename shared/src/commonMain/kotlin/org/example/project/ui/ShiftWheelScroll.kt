package org.example.project.ui

import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.launch

/**
 * User rule 2026-11-15: **Shift+wheel scrolls a vertical list vertically** (the Search window's result list). Compose
 * Desktop turns a Shift+wheel notch into a horizontal delta, which a vertical-only list ignores, so the wheel did nothing.
 *
 * Decided per input event: a Scroll with Shift held whose delta is horizontal only, and that nothing under the pointer
 * took (read in the Main pass, after the rows: a row part that scrolls sideways keeps Shift+wheel for itself). A notch
 * moves the list as far as a plain notch does on Windows — a twentieth of the list's height (Compose's
 * `WindowsWinUIConfig`).
 */
internal fun Modifier.shiftWheelScrollsVertically(state: ScrollableState): Modifier = composed {
    val scope = rememberCoroutineScope()
    pointerInput(state) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Main)
                if (event.type != PointerEventType.Scroll || !event.keyboardModifiers.isShiftPressed) continue
                if (event.changes.any { it.isConsumed }) continue
                val dx = event.changes.fold(0f) { sum, change -> sum + change.scrollDelta.x }
                val dy = event.changes.fold(0f) { sum, change -> sum + change.scrollDelta.y }
                if (dx == 0f || dy != 0f) continue
                event.changes.forEach { it.consume() }
                val px = dx * size.height / SHIFT_WHEEL_NOTCHES_PER_HEIGHT
                scope.launch { state.scrollBy(px) }
            }
        }
    }
}

private const val SHIFT_WHEEL_NOTCHES_PER_HEIGHT = 20f
