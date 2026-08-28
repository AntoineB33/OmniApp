package org.example.project.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties

/** The gap between a control and the bubble under it, so the pointer never sits on the bubble itself. */
private val HINT_GAP = 6.dp

/**
 * PRD §7: the info bubble a control shows while the pointer rests on it, naming the **chord that fires the
 * same action**.
 *
 * A shortcut is invisible from where the user is sitting: the lateral menu's "Look away now" says nothing
 * about `Ctrl+Shift+Alt+E`, and the three system-wide chords are rebindable, so even a user who once read the
 * keyboard-shortcuts window may be looking at a button whose chord has since moved. The bubble is that
 * window's answer brought next to the control.
 *
 * Two rules, and both are what keeps it from becoming a second source of truth:
 *
 *  * **[chord] is always a live lookup, never a constant spelling of a rebindable chord.** For the three
 *    system-wide shortcuts that is `GlobalShortcutBindings.chordOf(state.shortcutBindings, …)` — the one
 *    lookup the window, the receipt notification and the diagnostics all go through. Printing
 *    `GlobalShortcut.defaultChord` here would advertise a chord the app is not listening for.
 *  * **A control with no chord passes null**, and then this is a plain [Box]: no hover node, nothing drawn.
 *
 * The bubble is a [Popup] placed *below* the control with a gap, so it never lands under the pointer that
 * summoned it — a bubble the cursor can reach steals the hover, hides itself, and flickers (the calendar's
 * own hover bubble is built out of [onPointerEventCompat] for the same reason). It carries no pointer input
 * and no focus of its own, so the press that follows the hover still does the button's ordinary job.
 *
 * Hover is a pointer notion: on a touch-only device Enter/Exit never fire and no bubble is ever shown, which
 * is the correct no-op — those platforms report `GlobalHotkeyClaim.Unsupported` anyway.
 */
@Composable
fun ShortcutHint(
    chord: String?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    var hovered by remember { mutableStateOf(false) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    Box(
        modifier = modifier
            .then(
                if (chord == null) {
                    Modifier
                } else {
                    Modifier
                        .onSizeChanged { size = it }
                        // Observed at the Main pass without consuming: the button's own click, and any drag
                        // gesture an ancestor runs, are untouched.
                        .onPointerEventCompat(PointerEventType.Enter) { hovered = true }
                        .onPointerEventCompat(PointerEventType.Exit) { hovered = false }
                },
            ),
    ) {
        content()
        if (chord != null && hovered) {
            val gapPx = with(LocalDensity.current) { HINT_GAP.roundToPx() }
            Popup(
                alignment = Alignment.TopStart,
                // TopStart puts the bubble's own top-left on the control's; the height pushes it clear.
                offset = IntOffset(0, size.height + gapPx),
                properties = PopupProperties(focusable = false),
            ) {
                Text(
                    text = chord,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                    modifier = Modifier
                        .shadow(4.dp, RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.inverseSurface, RoundedCornerShape(4.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
    }
}
