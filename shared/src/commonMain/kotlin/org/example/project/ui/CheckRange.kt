package org.example.project.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput

/**
 * **Shift+click on a check box of a vertical list sets every box from the last one clicked to it** — the rule of
 * every list of check boxes in the app (a drop-down's, a window's, the Search window's rows). The range takes the
 * state the clicked box is given, so checking one box and shift-clicking another checks everything between the
 * two, and the same gesture from an unchecked box unchecks a range.
 *
 * The one funnel: a list keeps a [CheckRangeState], marks its container with [checkRangeShift] (which reads Shift
 * off the press), and asks [CheckRangeState.press] which boxes a click sets. Never a second reading of Shift per list.
 */
object CheckRange {
    /**
     * The boxes a click on [clicked] sets, in [order]: the range from [anchor] to it both included when [shift] is
     * held and both are in the list, else [clicked] alone.
     */
    fun <K> keysToSet(order: List<K>, anchor: K?, clicked: K, shift: Boolean): List<K> {
        if (!shift || anchor == null) return listOf(clicked)
        val from = order.indexOf(anchor)
        val to = order.indexOf(clicked)
        if (from < 0 || to < 0) return listOf(clicked)
        return order.subList(minOf(from, to), maxOf(from, to) + 1)
    }
}

/** One list's range: the last box clicked (the anchor), and whether Shift was held on the press being handled. */
class CheckRangeState<K> {
    var anchor: K? = null
        private set
    internal var shiftAtPress: Boolean = false

    /** The boxes this click sets — see [CheckRange.keysToSet]; [clicked] becomes the anchor. */
    fun press(order: List<K>, clicked: K): List<K> {
        val keys = CheckRange.keysToSet(order, anchor, clicked, shiftAtPress)
        anchor = clicked
        shiftAtPress = false
        return keys
    }

    /** [press] for a list whose state is a set of checked keys. */
    fun toggle(order: List<K>, checked: Set<K>, clicked: K): Set<K> {
        val keys = press(order, clicked)
        return if (clicked in checked) checked - keys.toSet() else checked + keys
    }
}

@Composable
fun <K> rememberCheckRange(): CheckRangeState<K> = remember { CheckRangeState() }

/**
 * Put on the container of a list's boxes: records whether Shift is held on each press, on the Initial pass and
 * without consuming, so the box's own click that follows reads it. A click with no press (the keyboard's) finds
 * the flag cleared by the last [CheckRangeState.press], so it sets one box.
 */
fun Modifier.checkRangeShift(state: CheckRangeState<*>): Modifier =
    this.pointerInput(state) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.type == PointerEventType.Press) state.shiftAtPress = event.keyboardModifiers.isShiftPressed
            }
        }
    }
