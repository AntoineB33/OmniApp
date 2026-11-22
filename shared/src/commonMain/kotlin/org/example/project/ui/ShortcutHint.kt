package org.example.project.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlin.math.roundToInt

/**
 * **Where every hover bubble of the app is drawn: this far below the pointer, at its x** — the calendar's
 * (`CalendarTitleBubble`) and the info bubble ([InfoHint]). Below it, so the pointer never sits on the bubble itself.
 */
internal val BUBBLE_BELOW_POINTER = 16.dp

/**
 * PRD §7: the info bubble a control shows while the pointer rests on it, naming the **chord that fires the
 * same action**.
 *
 * A shortcut is invisible from where the user is sitting: the lateral menu's "Look away now" says nothing
 * about `Ctrl+Shift+Alt+E`, and the system-wide chords are rebindable, so even a user who once read the
 * keyboard-shortcuts window may be looking at a button whose chord has since moved. The bubble is that
 * window's answer brought next to the control.
 *
 * Two rules, and both are what keeps it from becoming a second source of truth:
 *
 *  * **[chord] is always a live lookup, never a constant spelling of a rebindable chord.** For the
 *    system-wide shortcuts that is `GlobalShortcutBindings.chordOf(state.shortcutBindings, …)` — the one
 *    lookup the window, the receipt notification and the diagnostics all go through. Printing
 *    `GlobalShortcut.defaultChord` here would advertise a chord the app is not listening for.
 *  * **A control with no chord passes null**, and then this is a plain [Box]: no hover node, nothing drawn.
 *
 * The bubble is a [Popup] **at the pointer, following it while it moves** (user rule 2026-11-20: *"The bubble info must
 * appear only at the position of the mouse, even if it is moving."*), [BUBBLE_BELOW_POINTER] under it — so it never
 * lands under the pointer that summoned it: a bubble the cursor can reach steals the hover, hides itself, and flickers
 * (the calendar's own hover bubble is built out of [onPointerEventCompat] for the same reason). It was hung under the
 * control's left edge, far from a pointer at the other end of a wide row. It carries no pointer input and no focus of
 * its own, so the press that follows the hover still does the button's ordinary job.
 *
 * Hover is a pointer notion: on a touch-only device Enter/Exit never fire and no bubble is ever shown, which
 * is the correct no-op — those platforms report `GlobalHotkeyClaim.Unsupported` anyway.
 */
@Composable
fun ShortcutHint(
    chord: String?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) = InfoHint(text = chord, modifier = modifier, content = content)

/**
 * The info bubble itself — [ShortcutHint] is this with a chord for text; a control that needs to explain
 * *what it is* rather than which chord fires it (the default sub-tree's per-row switch) passes that
 * explanation. One bubble, drawn in one place, under the same rules: at the pointer, no pointer input, no focus, and a
 * plain [Box] when [text] is null.
 */
@Composable
fun InfoHint(
    text: String?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    // The pointer, in this element's own coordinates, while it is over it; null while it is not.
    var pointer by remember { mutableStateOf<Offset?>(null) }
    // Where the element stands in its window, last seen: a move of it under a resting pointer drops the bubble.
    val placedAt = remember { arrayOfNulls<Offset>(1) }
    Box(
        modifier = modifier
            .then(
                if (text == null) {
                    Modifier
                } else {
                    Modifier
                        // Anomaly 2026-11-22 (*"the info bubble sometimes gets stuck when I vertically scroll on the
                        // list of rows"*): a list scrolled under a resting pointer carries the element, and its bubble
                        // with it, up under the pointer; the bubble then takes the pointer, the element never hears its
                        // Exit, and the bubble stays. **The element moved, the bubble goes** — the next Enter or Move
                        // shows the bubble of whatever is under the pointer then, at the pointer.
                        .onGloballyPositioned { coordinates ->
                            val now = coordinates.positionInWindow()
                            val was = placedAt[0]
                            placedAt[0] = now
                            if (was != null && was != now) pointer = null
                        }
                        // Observed at the Main pass without consuming: the button's own click, and any drag
                        // gesture an ancestor runs, are untouched.
                        .onPointerEventCompat(PointerEventType.Enter) { pointer = it.changes.first().position }
                        .onPointerEventCompat(PointerEventType.Move) { pointer = it.changes.first().position }
                        .onPointerEventCompat(PointerEventType.Exit) { pointer = null }
                        // The wheel turning under the pointer moves what it rests on: the bubble goes at once.
                        .onPointerEventCompat(PointerEventType.Scroll) { pointer = null }
                },
            ),
    ) {
        content()
        val at = pointer
        if (text != null && at != null) {
            val belowPx = with(LocalDensity.current) { BUBBLE_BELOW_POINTER.roundToPx() }
            Popup(
                alignment = Alignment.TopStart,
                // TopStart puts the bubble's own top-left on the element's: moved to the pointer, and under it.
                offset = IntOffset(at.x.roundToInt(), at.y.roundToInt() + belowPx),
                properties = PopupProperties(focusable = false),
            ) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                    modifier = Modifier
                        .widthIn(max = 320.dp)
                        .shadow(4.dp, RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.inverseSurface, RoundedCornerShape(4.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
    }
}
