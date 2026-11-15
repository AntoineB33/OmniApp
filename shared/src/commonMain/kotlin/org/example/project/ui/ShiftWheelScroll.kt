package org.example.project.ui

import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * How far a section narrower than its content ([keepsWidthAbove]) is scrolled sideways: [offset] px of the content hidden
 * on the left, between 0 and [max] (what the section cuts; 0 while all of it shows). User rule 2026-11-15: a Search
 * window's result list narrowed until its scrollbar is cut off is scrolled sideways by Shift+wheel
 * ([shiftWheelScrolls]). Widened again, the offset is clamped back, so nothing is left hidden on the left needlessly.
 */
internal class SideCut {
    var offset by mutableIntStateOf(0)
        private set
    var max by mutableIntStateOf(0)
        private set
    /** The width shown, for the length of a notch. */
    var shown by mutableIntStateOf(0)
        private set

    val isCut: Boolean get() = max > 0

    /** Told by the layout each time it measures. */
    fun measured(shown: Int, laidOut: Int) {
        this.shown = shown
        max = (laidOut - shown).coerceAtLeast(0)
        if (offset > max) offset = max
    }

    /** Scrolls by [px] (positive = towards the right); returns how far it went. */
    fun scrollBy(px: Float): Float {
        val next = (offset + px.roundToInt()).coerceIn(0, max)
        val moved = next - offset
        offset = next
        return moved.toFloat()
    }
}

/**
 * User rule 2026-11-15: **Shift+wheel over a Search window's result list scrolls it** — sideways while the list is cut
 * by a narrow section ([sideways] [SideCut.isCut]: its scrollbar is then in the hidden part), vertically otherwise.
 * Compose Desktop sends a Shift+wheel notch as a horizontal delta, which a vertical-only list ignores.
 *
 * Decided per input event: a Scroll whose delta is horizontal only (Shift+wheel, or a tilted wheel / sideways swipe,
 * which move only a cut list), and that nothing under the pointer took (read in the Main pass, after the rows: a row
 * part that scrolls sideways keeps it for itself). A notch moves a twentieth of the length shown, as a plain notch does
 * on Windows (Compose's `WindowsWinUIConfig`).
 */
internal fun Modifier.shiftWheelScrolls(vertical: ScrollableState, sideways: SideCut? = null): Modifier = composed {
    val scope = rememberCoroutineScope()
    pointerInput(vertical, sideways) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Main)
                if (event.type != PointerEventType.Scroll) continue
                if (event.changes.any { it.isConsumed }) continue
                val dx = event.changes.fold(0f) { sum, change -> sum + change.scrollDelta.x }
                val dy = event.changes.fold(0f) { sum, change -> sum + change.scrollDelta.y }
                if (dx == 0f || dy != 0f) continue
                when {
                    sideways != null && sideways.isCut -> {
                        event.changes.forEach { it.consume() }
                        sideways.scrollBy(dx * sideways.shown / NOTCHES_PER_LENGTH)
                    }
                    event.keyboardModifiers.isShiftPressed -> {
                        event.changes.forEach { it.consume() }
                        val px = dx * size.height / NOTCHES_PER_LENGTH
                        scope.launch { vertical.scrollBy(px) }
                    }
                }
            }
        }
    }
}

private const val NOTCHES_PER_LENGTH = 20f
