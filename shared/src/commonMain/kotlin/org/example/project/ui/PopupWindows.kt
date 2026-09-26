package org.example.project.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp

/**
 * ## What leaves on an outside press, and what does not
 *
 * `docs/invariants/popups.md`. There is now **one sort of window** — it opens on the top layer, wears the
 * frame in `WindowFrame.kt`, and stays until it is closed. A press somewhere else never takes a window
 * away: half-typed edits are not thrown out by a click aimed at something else, and a window put on screen
 * is a window the user can still see after looking at what is behind it.
 *
 * The exception, and the whole subject of this file, is a **menu**: a right-click contextual menu or a
 * drop-down. A menu is not a window — it is a question the app is asking about one cell, one percentage,
 * one id row — and it closes on the first press outside it. That press still does its normal job (PRD §13:
 * right-click one cell, click another, and the second cell is selected in the same gesture), which is why
 * dismissal is decided by ONE observer at the app root ([transientMenuDismissRoot]) that watches the
 * **Initial** pass without consuming, and never by a per-menu outside-press handler.
 *
 * A press inside a `DropdownMenu`/`Popup` draws in its own layer and never reaches that observer, which is
 * exactly what makes "any press the observer sees" mean "a press outside the menu" — so a menu publishes no
 * bounds. It does have to be made **non-focusable** (`PopupProperties(focusable = false)`): a focusable
 * `DropdownMenu` consumes the outside press for its own `onDismissRequest`, and that is the press PRD §13
 * needs to go on and select the next cell. Wherever a menu is given `focusable = false`, the registration
 * here must come with it — a non-focusable menu that no one registered never closes at all.
 */
class TransientMenuHost {
    private val entries = LinkedHashMap<Any, () -> Unit>()

    /**
     * Whether a menu is open at all — **observable**, because the task tree reads it.
     *
     * A menu standing over a cell is what the user is answering, so the tree behind it goes **deaf** while
     * it is up (`TaskTreeView`'s `keyboardOwned`, together with [WindowFrameHost.keyboardClaimed]). PRD §4's
     * "type a letter on the selected cell to start renaming it" would otherwise fire behind the menu.
     */
    var anyOpen: Boolean by mutableStateOf(false)
        private set

    /** Registers a newly opened menu, closing any menu already open — a second one is never wanted. */
    fun open(key: Any, onDismiss: () -> Unit) {
        dismissMenus()
        entries[key] = onDismiss
        anyOpen = true
    }

    /**
     * The fields in an **edit mode that leaves on the first press outside them** ([leaveOnOutsidePress]), each
     * with its bounds in the window. Not menus: opening a menu does not end one (the field's own right-click menu
     * would otherwise end the edit it was opened on), a press INSIDE the field keeps it, and they do not make the
     * tree deaf — the field holding the caret already owns the keyboard.
     */
    private val editors = LinkedHashMap<Any, Pair<() -> Rect?, () -> Unit>>()

    fun openEditor(key: Any, bounds: () -> Rect?, onLeave: () -> Unit) {
        editors[key] = bounds to onLeave
    }

    fun closeEditor(key: Any) {
        editors.remove(key)
    }

    /** Forgets a menu that left the composition. Never calls its `onDismiss` — it is already gone. */
    fun close(key: Any) {
        entries.remove(key)
        anyOpen = entries.isNotEmpty()
    }

    /**
     * A press landed somewhere the observer can see it, i.e. outside every open menu. They all close, and so
     * does every edit whose field [windowPosition] (in the window; null = unknown) is not in; the press itself is
     * neither consumed nor altered.
     */
    fun onPress(windowPosition: Offset? = null) {
        for ((key, editor) in editors.entries.toList()) {
            val bounds = editor.first()
            if (windowPosition != null && bounds != null && bounds.contains(windowPosition)) continue
            editors.remove(key)
            editor.second()
        }
        dismissMenus()
    }

    private fun dismissMenus() {
        if (entries.isEmpty()) return
        for ((key, onDismiss) in entries.entries.toList()) {
            entries.remove(key)
            onDismiss()
        }
        anyOpen = false
    }
}

val LocalTransientMenuHost = staticCompositionLocalOf<TransientMenuHost?> { null }

/**
 * The app-root observer that turns a press into a menu dismissal. It watches the **Initial** pass without
 * consuming anything, so it is an ancestor of every window and every menu alike and the press still reaches
 * whatever it was aimed at. Presses inside a menu (`DropdownMenu`/`Popup` draw in their own layer) never
 * reach it, which is what keeps a window's own menus from closing on their own first click.
 */
@Composable
fun Modifier.transientMenuDismissRoot(host: TransientMenuHost): Modifier {
    // Where the observer stands, so a press can be told in window coordinates — what an editor's bounds are in.
    val coordinates = remember { arrayOfNulls<LayoutCoordinates>(1) }
    return this
        .onGloballyPositioned { coordinates[0] = it }
        .pointerInput(host) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.type != PointerEventType.Press) continue
                    val at = event.changes.firstOrNull()?.position
                    host.onPress(at?.let { position -> coordinates[0]?.takeIf { it.isAttached }?.localToWindow(position) })
                }
            }
        }
}

/**
 * An **edit mode that leaves on the first press outside the field** — [active] while it is on, [onLeave] ending
 * it. A text field alone does not: a press on something that takes no focus (a bare stretch of a window, the
 * calendar, the tree's background) leaves the caret where it was, and the edit with it. Decided by the same ONE
 * root observer as the menus ([transientMenuDismissRoot]); the press still does whatever it was aimed at, and a
 * press inside the field (moving the caret) keeps the edit.
 */
@Composable
fun Modifier.leaveOnOutsidePress(active: Boolean, onLeave: () -> Unit): Modifier {
    val host = LocalTransientMenuHost.current
    val key = remember { Any() }
    val bounds = remember { arrayOfNulls<Rect>(1) }
    val latestLeave by rememberUpdatedState(onLeave)
    DisposableEffect(host, key, active) {
        if (active) host?.openEditor(key, { bounds[0] }) { latestLeave() }
        onDispose { host?.closeEditor(key) }
    }
    return this.onGloballyPositioned { bounds[0] = it.boundsInWindow() }
}

/**
 * Registers a **right-click contextual menu** (or a drop-down) with the host, so the first press outside it
 * closes it — see the class doc for why this is the only thing in the app that still works that way, and
 * why the menu's own `Popup` must be `focusable = false`.
 */
@Composable
fun transientMenuDismissal(open: Boolean, onDismiss: () -> Unit) {
    val host = LocalTransientMenuHost.current
    val key = remember { Any() }
    val latestDismiss by rememberUpdatedState(onDismiss)
    DisposableEffect(host, key, open) {
        if (open) host?.open(key) { latestDismiss() }
        onDispose { host?.close(key) }
    }
}

/**
 * The press handler of the field a **drop-down hangs from**: a click opens the menu, and a click while it is
 * open closes it. A plain `clickable { open = true }` cannot say the second half — the field is outside the
 * menu, so [transientMenuDismissRoot] has already closed it on the press (Initial pass, ancestor first), and
 * the click then opens it straight back. So the field reads whether the menu was open **as last composed**
 * when the press lands, which the root's dismissal cannot have changed yet, and the click honours that.
 */
@Composable
fun Modifier.menuToggleClickable(open: Boolean, onOpenChange: (Boolean) -> Unit): Modifier {
    val composedOpen by rememberUpdatedState(open)
    val openAtPress = remember { booleanArrayOf(false) }
    return this
        .pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.type == PointerEventType.Press) openAtPress[0] = composedOpen
                }
            }
        }
        .clickable {
            // `open` too, for a click that is not a press (the keyboard's), which no dismissal preceded.
            val wasOpen = openAtPress[0] || open
            openAtPress[0] = false
            onOpenChange(!wasOpen)
        }
}

/**
 * The full-screen layer a per-object window is centred in. Deliberately inert — no scrim, no pointer input
 * — so the app behind it stays live and a press aimed at something else still reaches it.
 *
 * It takes the window's frame [id] because it is that window's **outermost** element, and `zIndex` only
 * orders a node among its own siblings: the frame inside this layer can say what it likes about the
 * stacking order, the app compares the LAYER against the other windows ([windowStackZ]). A layer that
 * carried no z is what pinned every per-object window above the whole stack.
 */
@Composable
fun TransientPopupLayer(
    id: String,
    modifier: Modifier = Modifier,
    contentAlignment: Alignment = Alignment.Center,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier.fillMaxSize().windowStackZ(id),
        contentAlignment = contentAlignment,
        content = content,
    )
}

/**
 * The one way the app says something back to a gesture it could not carry out — the calendar's "go to task
 * tree" on a panel whose task no cell holds (PRD §8), and the reducer's refusal of an edit that would have
 * broken a category rule (PRD §5).
 *
 * A window like any other, and closed like any other: by its **OK** button or its head's ✕. It used to
 * leave on the next press anywhere, which meant a notice could be gone before it had been read. It is the
 * one window that cannot be **reduced** — a notice filed in the bottom bar is a notice nobody reads.
 */
@Composable
fun MessagePopup(message: String, onDismiss: () -> Unit, id: String = "Notice") {
    val frame = rememberWindowFrameState(id)
    TransientPopupLayer(frame.id) {
        AppWindowFrame(
            title = "Notice",
            state = frame,
            onClose = onDismiss,
            defaultWidth = 420.dp,
            defaultHeight = 200.dp,
            canMinimize = false,
            claimsKeyboard = true,
            modifier = Modifier.align(Alignment.Center),
        ) {
            Column(
                Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.End,
            ) {
                Text(text = message, style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = onDismiss) { Text("OK") }
            }
        }
    }
}
