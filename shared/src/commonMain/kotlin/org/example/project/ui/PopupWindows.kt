package org.example.project.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned

/**
 * ## The two sorts of pop-up window
 *
 * **Sort 1 — a window.** It opens on the top layer and then behaves like every other window: whatever the
 * user focuses next stacks on top of it, and it stays open until it is closed. These are the lateral-menu
 * windows, managed by `App`'s `windowStack`; there is exactly ONE of each.
 *
 * **Sort 2 — a transient pop-up.** It opens on the top layer and **disappears the moment anything else
 * takes focus**. These are the pop-ups that are about ONE object — a task, a cell, a sub-list, a calendar
 * block, a history unit. That is the whole test: a pop-up that could have several instances open at once if
 * it stacked is a sort-2 pop-up, because "the edit window of task A" and "the edit window of task B" are two
 * different windows and the user only ever means the one they just asked for.
 *
 * Consequences, and the reason this lives in one file rather than at each call site:
 *
 * - **At most one sort-2 pop-up is open at a time.** [TransientPopupHost.open] dismisses the others, so the
 *   invariant holds by construction instead of by every opener remembering to close its predecessor.
 * - **The press that dismisses still does its normal job.** A sort-2 pop-up is NOT modal: it draws no scrim
 *   and blocks nothing, so clicking the calendar both closes the pop-up and focuses the calendar. (The
 *   pre-existing full-screen scrims cost a second click for that, and made "disappears when something else
 *   is in focus" unobservable — the scrim ate the press that would have focused the something else.)
 * - **Dismissal discards.** A sort-2 pop-up holding a half-typed edit loses it, exactly as clicking its old
 *   scrim did. That is the price of the sort, not an oversight.
 */
class TransientPopupHost {
    private class Entry(val onDismiss: () -> Unit) {
        /** Window-space bounds of the pop-up's card; a press inside them is a press "in" the pop-up. */
        var bounds: Rect? = null
    }

    private val entries = LinkedHashMap<Any, Entry>()

    /** Registers a newly opened pop-up, dismissing every sort-2 pop-up already open (see the class doc). */
    fun open(key: Any, onDismiss: () -> Unit) {
        dismissAll()
        entries[key] = Entry(onDismiss)
    }

    /** Forgets a pop-up that left the composition. Never calls its `onDismiss` — it is already gone. */
    fun close(key: Any) {
        entries.remove(key)
    }

    fun setBounds(key: Any, bounds: Rect) {
        entries[key]?.bounds = bounds
    }

    /**
     * A press landed at [windowPos]. Every open pop-up it did NOT land inside is dismissed — that press is
     * the user focusing something else. The press itself is neither consumed nor altered.
     */
    fun onPress(windowPos: Offset) {
        if (entries.isEmpty()) return
        for ((key, entry) in entries.entries.toList()) {
            if (entry.bounds?.contains(windowPos) != true) {
                entries.remove(key)
                entry.onDismiss()
            }
        }
    }

    private fun dismissAll() {
        if (entries.isEmpty()) return
        for ((key, entry) in entries.entries.toList()) {
            entries.remove(key)
            entry.onDismiss()
        }
    }
}

val LocalTransientPopupHost = staticCompositionLocalOf<TransientPopupHost?> { null }

/**
 * The app-root observer that turns a press into a dismissal. It watches the **Initial** pass without
 * consuming anything, so it is an ancestor of every window and every pop-up alike and the press still
 * reaches whatever it was aimed at. Presses inside a menu (`DropdownMenu`/`Popup` draw in their own layer)
 * never reach it, which is what keeps a pop-up's own menus from closing it.
 */
@Composable
fun Modifier.transientPopupDismissRoot(host: TransientPopupHost): Modifier {
    var coords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    return this
        .onGloballyPositioned { coords = it }
        .pointerInput(host) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.type != PointerEventType.Press) continue
                    val position = event.changes.firstOrNull()?.position ?: continue
                    val window = coords?.localToWindow(position) ?: continue
                    host.onPress(window)
                }
            }
        }
}

/**
 * The full-screen layer a sort-2 pop-up centres its card in. Deliberately inert — no scrim, no pointer
 * input — so the app behind it stays live and the dismissing press gets through (see the class doc above).
 */
@Composable
fun TransientPopupLayer(
    modifier: Modifier = Modifier,
    contentAlignment: Alignment = Alignment.Center,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = contentAlignment, content = content)
}

/**
 * Applied to a sort-2 pop-up's card. It registers the pop-up with the host (which closes any other one),
 * keeps its window-space bounds published so a press inside it is never read as a press elsewhere, and
 * swallows the taps that do land inside.
 */
@Composable
fun Modifier.transientPopupCard(onDismiss: () -> Unit): Modifier {
    val host = LocalTransientPopupHost.current
    val key = remember { Any() }
    val latestDismiss by rememberUpdatedState(onDismiss)
    DisposableEffect(host, key) {
        host?.open(key) { latestDismiss() }
        onDispose { host?.close(key) }
    }
    return this
        .onGloballyPositioned { host?.setBounds(key, it.boundsInWindow()) }
        .pointerInput(Unit) { detectTapGestures { } }
}
