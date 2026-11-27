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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.findRootCoordinates
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
    private val entries = LinkedHashMap<Any, Pair<PopupOpener?, () -> Unit>>()

    /**
     * Whether a menu is open at all — **observable**, because the task tree reads it.
     *
     * A menu standing over a cell is what the user is answering, so the tree behind it goes **deaf** while
     * it is up (`TaskTreeView`'s `keyboardOwned`, together with [WindowFrameHost.keyboardClaimed]). PRD §4's
     * "type a letter on the selected cell to start renaming it" would otherwise fire behind the menu.
     */
    var anyOpen: Boolean by mutableStateOf(false)
        private set

    /**
     * Registers a newly opened menu, closing any menu already open — a second one is never wanted — but those it is opened
     * from ([keep]): anomaly 2026-11-25, a row of a right-click menu right-clicked opens its own menu over it.
     */
    fun open(key: Any, opener: PopupOpener? = null, keep: Collection<Any> = emptyList(), onDismiss: () -> Unit) {
        dismissMenus(keep = keep)
        entries[key] = opener to onDismiss
        anyOpen = true
    }

    /**
     * The fields in an **edit mode that leaves on the first press outside them** ([leaveOnOutsidePress]), each
     * with its bounds in the window. Not menus: opening a menu does not end one (the field's own right-click menu
     * would otherwise end the edit it was opened on), a press INSIDE the field keeps it, and they do not make the
     * tree deaf — the field holding the caret already owns the keyboard.
     */
    private val editors = LinkedHashMap<Any, Pair<OutsidePressEditor, () -> Unit>>()

    fun openEditor(key: Any, editor: OutsidePressEditor, onLeave: () -> Unit) {
        editors[key] = editor to onLeave
    }

    /**
     * User rule 2026-11-20: told of every press and every wheel turn the app sees — in its window or in a pop-up's, the
     * two observers below — what ends a Search bar's run of keystrokes (`App`'s `searchRunEnded`).
     */
    var onActivity: () -> Unit = {}

    /** A wheel turn anywhere: no menu closes, but it is the user doing something ([onActivity]). */
    fun onScroll() = onActivity()

    /** The app window's root ([transientMenuDismissRoot]): a root other than it is a pop-up's own window. */
    internal var appRoot: Any? = null

    /**
     * Anomaly 2026-11-16 (*"there should not be two rows selected in two different windows"*): **rows selected in a pop-up's
     * window take the selection from the rows of every other window** — at the moment they are selected, which for a
     * drop-down or a row's menu is the moment it opens on its first row. A row selection ([OutsidePressEditor.rowSelection])
     * in the app's window takes nothing: the sections of one window keep their own rule (`LocalSectionTakesSelection`).
     * The rows that yield are told so ([OutsidePressEditor.onYield]): a menu opened on them still acts on them.
     */
    internal fun takeRowSelection(taker: OutsidePressEditor, root: Any) {
        val app = appRoot ?: return
        if (root === app) return
        yieldRowSelections(root, except = taker)
    }

    private fun yieldRowSelections(root: Any, except: OutsidePressEditor? = null) {
        for ((key, editor) in editors.entries.toList()) {
            val held = editor.first
            if (held === except || !held.rowSelection || held.hasPartIn(root)) continue
            editors.remove(key)
            (held.onYield ?: editor.second)()
        }
    }

    fun closeEditor(key: Any) {
        editors.remove(key)
    }

    /**
     * User rule 2026-11-22: every right-click menu closes — a walk of the positions (`Shift+Alt+←`) takes the user
     * elsewhere, and a menu is a question about where they were.
     */
    fun closeMenus() = dismissMenus()

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
    fun onPress(windowPosition: Offset? = null, root: Any? = null) {
        onActivity()
        for ((key, editor) in editors.entries.toList()) {
            if (windowPosition != null && editor.first.contains(root, windowPosition)) continue
            editors.remove(key)
            editor.second()
        }
        dismissMenus(onPress = true)
    }

    /**
     * A press in a pop-up of its own layer, which the root observer never sees ([dismissesMenusOnPress]): the menus
     * close, and no edit — the pop-up is a part of the edit it was opened from (the cell's "▾"), not outside it.
     */
    fun onPressInPopup(windowPosition: Offset? = null, root: Any? = null, keep: Collection<Any> = emptyList()) {
        onActivity()
        // Anomaly 2026-11-15 (*"the selected rows sometimes stay selected even when clicking elsewhere"*): an edit drawn
        // in THAT pop-up's window (a Search window's selected rows there) leaves on a press elsewhere in the same window.
        if (windowPosition != null && root != null) {
            for ((key, editor) in editors.entries.toList()) {
                if (!editor.first.hasPartIn(root) || editor.first.contains(root, windowPosition)) continue
                editors.remove(key)
                editor.second()
            }
            // Anomaly 2026-11-16 (*"click on the window that opens, the task element isn't deselected"*): a press in a
            // pop-up is a press somewhere else for the rows selected in another window ([takeRowSelection]).
            yieldRowSelections(root)
        }
        dismissMenus(onPress = true, keep = keep)
    }

    /**
     * [onPress]: closed by a press — which leaves alone the menu whose own control it lands on: that control's click
     * answers it ([PopupOpeners.pressAnsweredBy], anomaly 2026-11-20).
     */
    private fun dismissMenus(onPress: Boolean = false, keep: Collection<Any> = emptyList()) {
        if (entries.isEmpty()) return
        for ((key, entry) in entries.entries.toList()) {
            if (key in keep) continue
            if (onPress && PopupOpeners.pressAnsweredBy(entry.first)) continue
            entries.remove(key)
            entry.second()
        }
        anyOpen = entries.isNotEmpty()
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
        .onGloballyPositioned {
            coordinates[0] = it
            host.appRoot = it.findRootCoordinates()
        }
        .pointerInput(host) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.type == PointerEventType.Scroll) host.onScroll()
                    if (event.type != PointerEventType.Press) continue
                    val at = event.changes.firstOrNull()?.position
                    val here = coordinates[0]?.takeIf { it.isAttached }
                    host.onPress(at?.let { position -> here?.localToWindow(position) }, here?.findRootCoordinates())
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
    val editor = rememberOutsidePressEditor(active, onLeave = onLeave)
    return this.outsidePressPart(editor)
}

/**
 * The places an edit mode that leaves on an outside press counts as **inside** — its field, and anything drawn
 * apart from it that belongs to the same edit (a task cell's Edit Mode menus, drawn under the row). Each part
 * reports its own bounds ([outsidePressPart]); a press in any of them keeps the edit. What draws in a `Popup`
 * (the mode selector's drop-down, a menu row's right-click menu) needs no part: its presses never reach the
 * root observer at all.
 */
class OutsidePressEditor internal constructor() {
    /**
     * Each part's window (its root's coordinates) and bounds in it. Anomaly 2026-11-15: since every pop-up is a window
     * of its own (2026-11-13), a part drawn in a pop-up has bounds in THAT window — read against a press in the app's
     * window they matched where nothing of the edit stood, and the selected rows stayed selected.
     */
    internal val parts = LinkedHashMap<Any, Pair<Any, Rect>>()

    /** Whether [windowPosition], in the window of [root] (null: any window), is in a part. */
    internal fun contains(root: Any?, windowPosition: Offset): Boolean =
        parts.values.any { (at, bounds) -> (root == null || at === root) && bounds.contains(windowPosition) }

    internal fun hasPartIn(root: Any): Boolean = parts.values.any { it.first === root }

    /** A selection of rows, which a pop-up's selected rows take ([TransientMenuHost.takeRowSelection]). */
    internal var rowSelection: Boolean = false

    /** What the rows do when a pop-up takes their selection; null: as on a press outside. */
    internal var onYield: (() -> Unit)? = null

    /** Whether it is on, and whether its selection was claimed from the other windows since it came on. */
    internal var active: Boolean = false
    internal var claimed: Boolean = false
}

/** [leaveOnOutsidePress] for an edit drawn in more than one place: mark each place with [outsidePressPart]. */
@Composable
fun rememberOutsidePressEditor(
    active: Boolean,
    /** A selection of rows: one in a pop-up's window takes the others' ([TransientMenuHost.takeRowSelection]). */
    rowSelection: Boolean = false,
    /** What a row selection does when a pop-up takes it; null: [onLeave]. */
    onYield: (() -> Unit)? = null,
    onLeave: () -> Unit,
): OutsidePressEditor {
    val host = LocalTransientMenuHost.current
    val editor = remember { OutsidePressEditor() }
    val latestLeave by rememberUpdatedState(onLeave)
    val latestYield by rememberUpdatedState(onYield)
    editor.rowSelection = rowSelection
    editor.onYield = if (onYield == null) null else { { latestYield?.invoke() } }
    DisposableEffect(host, editor, active) {
        editor.active = active
        editor.claimed = false
        if (active) host?.openEditor(editor, editor) { latestLeave() }
        onDispose {
            editor.active = false
            host?.closeEditor(editor)
        }
    }
    return editor
}

/**
 * Anomaly 2026-11-11 (*"right-click on the background of the headless search window … then clicked on the background,
 * but it didn't close the right-click menu"*): a `Popup` draws in its own layer, so a press in it never reaches the
 * root observer ([transientMenuDismissRoot]). Put on a pop-up's content, a press in it closes the open menus as one
 * outside them does — never a menu's own press, which stays in the menu's layer. Observes the Initial pass and consumes
 * nothing, as the root does.
 */
@Composable
fun Modifier.dismissesMenusOnPress(): Modifier {
    val host = LocalTransientMenuHost.current ?: return this
    val coordinates = remember { arrayOfNulls<LayoutCoordinates>(1) }
    // Anomaly 2026-11-25: a press in a menu's own window closes the menus opened from it — never that menu, nor those it
    // was opened from ([LocalEnclosingMenus]) — exactly as a press in the app's window closes every menu over it.
    val keep by rememberUpdatedState(LocalEnclosingMenus.current)
    return this
        .onGloballyPositioned { coordinates[0] = it }
        .pointerInput(host) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.type == PointerEventType.Scroll) host.onScroll()
                    if (event.type != PointerEventType.Press) continue
                    val here = coordinates[0]?.takeIf { it.isAttached }
                    val at = event.changes.firstOrNull()?.position?.let { here?.localToWindow(it) }
                    host.onPressInPopup(at, here?.findRootCoordinates(), keep)
                }
            }
        }
}

/**
 * Anomaly 2026-11-15: **the edits a pop-up drawn here stands inside** — what a field's Search window, drawn in a layer of
 * its own ([FieldSearchPopupLayer]), counts as inside besides its own field: the task cell's Edit Mode rectangle, the
 * field's pop-up it was opened from. Each such surface provides its own on top of those around it.
 */
val LocalEnclosingEdits = androidx.compose.runtime.compositionLocalOf<List<OutsidePressEditor>> { emptyList() }

/** One place [editor] counts as inside — see [OutsidePressEditor]. */
@Composable
fun Modifier.outsidePressPart(editor: OutsidePressEditor): Modifier {
    val key = remember { Any() }
    val host = LocalTransientMenuHost.current
    DisposableEffect(editor, key) { onDispose { editor.parts.remove(key) } }
    return this.onGloballyPositioned {
        val root = it.findRootCoordinates()
        editor.parts[key] = root to it.boundsInWindow()
        // Rows selected in a pop-up's window: the other windows' rows are not any more (anomaly 2026-11-16).
        if (editor.rowSelection && editor.active && !editor.claimed) {
            editor.claimed = true
            host?.takeRowSelection(editor, root)
        }
    }
}

/**
 * [rememberOutsidePressEditor] for an edit whose edit mode **is its focus** — a search bar, the task-tree name
 * field: [focused] while it holds the caret, and a press outside it takes the focus away (which is what closes
 * whatever that field shows only while focused). What was typed stays. Mark the parts with [outsidePressPart].
 */
@Composable
fun rememberFocusOutsidePressEditor(focused: Boolean): OutsidePressEditor {
    val focusManager = LocalFocusManager.current
    return rememberOutsidePressEditor(focused) { focusManager.clearFocus() }
}

/**
 * A field (or a bar of fields) that is its own whole edit: a press outside it takes its focus away — see
 * [rememberFocusOutsidePressEditor]. `hasFocus`, so it also serves a container of fields.
 */
@Composable
fun Modifier.leaveFocusOnOutsidePress(): Modifier {
    var focused by remember { mutableStateOf(false) }
    val editor = rememberFocusOutsidePressEditor(focused)
    return this.onFocusChanged { focused = it.hasFocus }.outsidePressPart(editor)
}

/**
 * Registers a **right-click contextual menu** (or a drop-down) with the host, so the first press outside it
 * closes it — see the class doc for why this is the only thing in the app that still works that way, and
 * why the menu's own `Popup` must be `focusable = false`.
 */
@Composable
fun transientMenuDismissal(open: Boolean, onDismiss: () -> Unit): Any {
    val host = LocalTransientMenuHost.current
    val key = remember { Any() }
    val latestDismiss by rememberUpdatedState(onDismiss)
    // The control that opens it, whose click a press on it is (anomaly 2026-11-20, [PopupOpener]).
    val opener = LocalPopupOpener.current
    // The menus it is opened from: they stay open under it (anomaly 2026-11-25).
    val within = LocalEnclosingMenus.current
    DisposableEffect(host, key, open, opener, within) {
        if (open) host?.open(key, opener, within) { latestDismiss() }
        onDispose { host?.close(key) }
    }
    return key
}

/**
 * Anomaly 2026-11-25 (*"when I right-click a row, then right-click a row in the right-click menu R, it doesn't open a
 * right-click menu as well"*): **the menus the composition stands in**, outermost first — a menu opened from a row of
 * another keeps that one open ([TransientMenuHost.open]'s `keep`).
 */
val LocalEnclosingMenus = androidx.compose.runtime.compositionLocalOf<List<Any>> { emptyList() }

/**
 * The press handler of the field a **drop-down hangs from**: a click opens the menu, and a click while it is
 * open closes it. A plain `clickable { open = true }` cannot say the second half — the field is outside the
 * menu, so [transientMenuDismissRoot] has already closed it on the press (Initial pass, ancestor first), and
 * the click then opens it straight back. So the field reads whether the menu was open **as last composed**
 * when the press lands, which the root's dismissal cannot have changed yet, and the click honours that.
 */
@Composable
fun Modifier.menuToggleClickable(open: Boolean, opener: PopupOpener? = null, onOpenChange: (Boolean) -> Unit): Modifier {
    val composedOpen by rememberUpdatedState(open)
    val openAtPress = remember { booleanArrayOf(false) }
    val own = opener ?: rememberPopupOpener()
    return this
        // Anomaly 2026-11-20: the field is its menu's opener — a press on it is its click's, never the root's to close
        // the menu on, and a double click is one click ([PopupOpener]). Provide [opener] around the menu
        // ([LocalPopupOpener]) for the root observer and the menu's own window to know it.
        .popupOpener(own, open)
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
            onOpenChange(own.click(wasOpen, closes = true))
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
            sizeContext = org.example.project.scheduler.domain.ContextDomain.frameContextId("Notice"),
            canMinimize = false,
            claimsKeyboard = true,
            modifier = Modifier.align(Alignment.Center),
        ) {
            Column(
                Modifier.fillMaxWidth().weight(1f).verticalScrollWithBar(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.End,
            ) {
                Text(text = message, style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = onDismiss) { Text("OK") }
            }
        }
    }
}
