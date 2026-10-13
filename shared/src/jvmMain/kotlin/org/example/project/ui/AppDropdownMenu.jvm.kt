package org.example.project.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalContext
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.currentCompositeKeyHash
import androidx.compose.runtime.currentCompositionLocalContext
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeDialog
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.window.PopupProperties
import java.awt.Dialog
import java.awt.Dimension
import java.awt.EventQueue
import java.awt.KeyboardFocusManager
import java.awt.Point
import java.awt.Window as AwtWindow
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.awt.event.WindowFocusListener
import javax.swing.RootPaneContainer
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Desktop: **a menu is an OS window of its own, always on top** — over every other application's window, an
 * always-on-top one included (Firefox's picture-in-picture), with nothing else of the app raised. It is what a
 * browser's own menus are: a borderless pop-up window of the topmost band that does not take the foreground.
 *
 * Why not Compose's own way to the same thing (`compose.layers.type=WINDOW`, which makes every `Popup` such a
 * window): it was shipped on 2026-10-13 and **crashed the app at the first right-click** — in Compose 1.11.0 a layer
 * window resizes itself from inside its own draw (`WindowComposeSceneLayer.onDrawBoundsChanged` → `setSize` →
 * `SkiaLayer.reshape` → `renderImmediately` → the draw again), without end, until the native stack is gone. Hence
 * the first rule this file keeps: **the window is sized and placed from a task of its own on the event queue, never
 * from inside a layout or a draw**.
 *
 * The other two are the day's other anomalies:
 *  - **it opens WHERE material's menu opens** ([menuPlacement], material's own rule, candidate for candidate): the
 *    first version knew "under the anchor, else above it" and nothing else, which sent the calendar's menu — hung
 *    from a whole day column and moved to the pointer by its offset — off the screen, and others to the left;
 *  - **it opens in as few turns of the event queue as there are** ([MenuSession]): the anchor is known in one, the
 *    window is shown at the size the same menu had last time in the next. Going through Compose's `DialogWindow`
 *    took half a dozen, each waiting for a frame of an app window that has its own work to do.
 *
 * `-Domniapp.menuWindows=false` puts every menu back inside the app's window (material's own), to compare.
 */
@Composable
actual fun AppDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier,
    offset: DpOffset,
    properties: PopupProperties,
    atPointer: Boolean,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (!expanded) return
    // The window the menu is opened in: the one the user is acting in. Where there is none — a scene rendered with no
    // window at all, as the render tests do — the menu is material's, drawn in the scene: it must still open.
    val owner = remember { if (MENUS_ARE_WINDOWS) owningWindow() else null }
    if (owner == null) {
        androidx.compose.material3.DropdownMenu(true, onDismissRequest, modifier, offset, properties = properties, content = content)
        return
    }
    HangingMenu(owner, at = null, offset, properties.focusable, onDismissRequest, modifier, atPointer || ROW_MENUS_AT_POINTER_EVERYWHERE, content)
}

private val MENUS_ARE_WINDOWS: Boolean = System.getProperty("omniapp.menuWindows") != "false"

/**
 * A dev run's switch (`-Pomniapp.menusAtPointer=true`): every menu opens at the pointer, as the Search window's rows
 * do ([LocalRowMenusAtPointer]) — to see that rule on a window whose rows are at hand. Never set by the release.
 */
private val ROW_MENUS_AT_POINTER_EVERYWHERE: Boolean = System.getProperty("omniapp.menusAtPointer") == "true"

/** The app's window the user is acting in — the active one, else any of its frames that is on screen. */
private fun owningWindow(): AwtWindow? =
    KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow
        ?: java.awt.Frame.getFrames().firstOrNull { it.isShowing }

/**
 * A menu as a window of its own, hung from the layout it is declared in — or, with [at], opened AT that point of it
 * (pixels from its top left corner: the pointer, for a text field's own menu).
 */
@Composable
private fun HangingMenu(
    owner: AwtWindow,
    at: Offset?,
    offset: DpOffset,
    focusable: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier,
    /** Opens at the pointer, wherever the menu is declared ([AppDropdownMenu]'s `atPointer`). */
    atPointer: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val offsetPx = with(LocalDensity.current) { Offset(offset.x.roundToPx().toFloat(), offset.y.roundToPx().toFloat()) }
    val dismiss = rememberUpdatedState(onDismissRequest)
    val shownModifier = rememberUpdatedState(modifier)
    val shownContent = rememberUpdatedState(content)
    // Every composition local the menu's entries read is the one they would read inside the window.
    val locals = rememberUpdatedState(currentCompositionLocalContext)
    // The same menu, wherever it is opened from (every row of a list): what its last size is remembered under.
    val site = currentCompositeKeyHash
    val session = remember(owner) { MenuSession(owner, focusable, site, dismiss, shownModifier, shownContent, locals) }
    DisposableEffect(session) { onDispose { session.close() } }
    // What the menu hangs from: the layout it is declared in, exactly as material's menu reads it (`Popup`: a node of
    // no size, there only while the menu is open, that reports its parent's place in the window).
    Layout(
        content = {},
        modifier = Modifier.onGloballyPositioned { own ->
            own.parentCoordinates?.let { parent ->
                val origin = parent.positionInWindow()
                val pointer = if (atPointer) pointerIn(owner) else null
                session.hangFrom(
                    when {
                        pointer != null -> Rect(pointer, Size.Zero)
                        at == null -> Rect(origin, parent.size.toSize())
                        else -> Rect(origin + at, Size.Zero)
                    },
                    if (pointer != null) Offset.Zero else offsetPx,
                )
            }
        },
    ) { _, _ -> layout(0, 0) {} }
}

/**
 * **One menu, from the moment its anchor is known to the moment it is closed** — plain code on the event queue, not
 * a composable, because every composable in the way was a turn of the queue the user waited for.
 *
 * [hangFrom] is called from the layout pass that placed the anchor, so it only notes where and posts [open]; the
 * window is taken, sized, placed and shown there, in one task. Its size is the one the same menu ([site]) had last
 * time — right away, for the menus that never change — and, the first time, nothing is shown until the content has
 * been measured ([fit]): a window is never seen at a wrong size, nor moved under the eye.
 */
private class MenuSession(
    private val owner: AwtWindow,
    private val focusable: Boolean,
    private val site: Int,
    private val dismiss: State<() -> Unit>,
    private val modifier: State<Modifier>,
    private val content: State<@Composable ColumnScope.() -> Unit>,
    private val locals: State<CompositionLocalContext>,
) {
    private var anchor: Rect? = null
    private var offsetPx = Offset.Zero
    private var dialog: ComposeDialog? = null
    private var size: Dimension? = null
    private var closed = false

    private val ownerListener = object : WindowAdapter() {
        override fun windowDeactivated(event: WindowEvent) {
            if (!ours(event.oppositeWindow)) dismiss.value()
        }
    }
    private val focusListener = object : WindowFocusListener {
        override fun windowGainedFocus(event: WindowEvent) = Unit
        override fun windowLostFocus(event: WindowEvent) {
            if (focusable && !ours(event.oppositeWindow)) dismiss.value()
        }
    }

    /** A menu opened from this one is not "outside" it. */
    private fun ours(other: AwtWindow?): Boolean = generateSequence(other) { it.owner }.any { it === dialog }

    fun hangFrom(rect: Rect, offset: Offset) {
        if (rect == anchor && offset == offsetPx) return
        anchor = rect
        offsetPx = offset
        EventQueue.invokeLater {
            if (closed) return@invokeLater
            if (dialog == null) open() else place()
        }
    }

    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    private fun open() {
        val window = MenuWindows.take(owner)
        dialog = window
        window.focusableWindowState = focusable
        size = MenuWindows.lastSize[site]
        window.size = size ?: Dimension(2, 2)
        // A size not known yet: composed and measured out of sight, shown where it belongs once it is ([fit]).
        window.location = if (size == null) Point(-32000, -32000) else location()
        window.compositionLocalContext = locals.value
        window.setContent(
            // A focusable menu is material's: Escape closes it. A non-focusable one never sees a key.
            onPreviewKeyEvent = { event ->
                if (focusable && event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                    dismiss.value()
                    true
                } else {
                    false
                }
            },
        ) { MenuSurface(owner, modifier.value, content.value, ::fit) }
        // `popups.md`: a menu leaves on the first press outside it. A press in the app is the root observer's to see;
        // a press in ANOTHER application is seen here, as the window the menu hangs from losing the foreground — or,
        // for a menu that holds the keyboard, as the menu losing it.
        owner.addWindowListener(ownerListener)
        window.addWindowFocusListener(focusListener)
        window.isVisible = true
        if (focusable) {
            window.toFront()
            window.requestFocus()
        }
    }

    /** The content measured [wanted] (this window's pixels): reported from its layout, so applied in a task of its own. */
    private fun fit(wanted: IntSize) {
        EventQueue.invokeLater {
            val window = dialog ?: return@invokeLater
            val scale = window.graphicsConfiguration?.defaultTransform?.scaleX ?: 1.0
            val fitted = Dimension(ceil(wanted.width / scale).toInt().coerceAtLeast(1), ceil(wanted.height / scale).toInt().coerceAtLeast(1))
            MenuWindows.lastSize[site] = fitted
            size = fitted
            if (window.size != fitted) window.size = fitted
            place()
        }
    }

    private fun place() {
        val window = dialog ?: return
        if (size == null) return
        val at = location()
        if (window.location != at) window.location = at
    }

    private fun location(): Point = menuPlacement(owner, anchor ?: Rect.Zero, offsetPx, size ?: Dimension(0, 0))

    fun close() {
        closed = true
        val window = dialog ?: return
        dialog = null
        owner.removeWindowListener(ownerListener)
        window.removeWindowFocusListener(focusListener)
        MenuWindows.give(owner, window)
    }
}

/** What a menu's window shows: material's menu surface, measured without bounds so that it says the size it wants. */
@Composable
private fun MenuSurface(
    owner: AwtWindow,
    modifier: Modifier,
    content: @Composable ColumnScope.() -> Unit,
    onWanted: (IntSize) -> Unit,
) {
    // Never taller than the screen it is on: past that the menu scrolls, as material's does.
    val tallest = remember(owner) { (workAreaAt(ScreenPoint(owner.x, owner.y))?.height ?: 2000).dp }
    Box(Modifier.wrapContentSize(align = Alignment.TopStart, unbounded = true).onSizeChanged(onWanted)) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            contentColor = MaterialTheme.colorScheme.onSurface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.heightIn(max = tallest),
        ) {
            // Material's own menu column: the caller's modifier, the menu's padding, as wide as its widest entry.
            Column(
                modifier = modifier.padding(vertical = 8.dp).width(IntrinsicSize.Max).verticalScroll(rememberScrollState()),
                content = content,
            )
        }
    }
}

/**
 * **The menu windows are kept and used again** (anomaly 2026-10-13, the day they became windows: *"it takes a moment
 * for the menu to appear after the right-click"*). Making an OS window — its peer, its Direct3D surface — is what the
 * moment was; a menu is opened many times a minute and never two at once per window, so each window that opens menus
 * keeps ONE dialog, hidden between two menus and handed back to the next. The first is made ahead ([warm]), so the
 * first menu does not pay for it either.
 */
internal object MenuWindows {
    // Weak on the owner: a window that is gone takes its spare with it (AWT disposes of what it owns).
    private val spare = java.util.WeakHashMap<AwtWindow, ComposeDialog>()

    /** The size each menu had the last time it was open, by where it is declared — what the next one opens at. */
    val lastSize = HashMap<Int, Dimension>()

    private fun make(owner: AwtWindow): ComposeDialog =
        ComposeDialog(owner, Dialog.ModalityType.MODELESS).apply {
            isUndecorated = true
            isResizable = false
            isAlwaysOnTop = true
            type = AwtWindow.Type.POPUP
        }

    fun take(owner: AwtWindow): ComposeDialog = spare.remove(owner)?.takeIf { it.isDisplayable } ?: make(owner)

    /** The menu closed: its content is let go, and the window is kept hidden for the next one — one per owner. */
    fun give(owner: AwtWindow, dialog: ComposeDialog) {
        dialog.isVisible = false
        dialog.setContent { }
        if (owner.isDisplayable && spare[owner] == null) spare[owner] = dialog else dialog.dispose()
    }

    /** Makes [owner]'s menu window ahead of its first menu, native peer included. Never shown here. */
    fun warm(owner: AwtWindow) {
        if (!MENUS_ARE_WINDOWS || spare[owner] != null) return
        runCatching {
            spare[owner] = make(owner).apply {
                setSize(2, 2)
                addNotify()
            }
        }
    }
}

/** What the desktop's `main` calls once its window is up: see [MenuWindows.warm]. */
fun warmMenuWindows(owner: AwtWindow) = MenuWindows.warm(owner)

/**
 * **A text field's own right-click menu** (cut, copy, paste, select all) as a window of its own too — Compose draws
 * it through whatever `LocalContextMenuRepresentation` holds, which the desktop's `main` sets to this. It opens at the
 * pointer, leaves the keyboard with the field it is about, and is a menu like the others: registered with the host,
 * closed by the first press outside it ([transientMenuDismissal]).
 */
object OwnWindowContextMenuRepresentation : androidx.compose.foundation.ContextMenuRepresentation {
    @Composable
    override fun Representation(
        state: androidx.compose.foundation.ContextMenuState,
        items: () -> List<androidx.compose.foundation.ContextMenuItem>,
    ) {
        val status = state.status as? androidx.compose.foundation.ContextMenuState.Status.Open ?: return
        val close = { state.status = androidx.compose.foundation.ContextMenuState.Status.Closed }
        // Set by the desktop's `main` alone, so there is a window; without one there is nothing to open it in.
        val owner = remember { owningWindow() } ?: return
        transientMenuDismissal(open = true, onDismiss = close)
        HangingMenu(owner, at = status.rect.center, DpOffset(0.dp, 0.dp), focusable = false, onDismissRequest = close, Modifier) {
            items().forEach { item ->
                androidx.compose.material3.DropdownMenuItem(
                    text = { androidx.compose.material3.Text(item.label) },
                    onClick = {
                        close()
                        item.onClick()
                    },
                )
            }
        }
    }
}

/** Where the pointer is, in pixels of [owner]'s content — what an anchor is said in. Null where it cannot be asked. */
private fun pointerIn(owner: AwtWindow): Offset? =
    runCatching {
        val at = java.awt.MouseInfo.getPointerInfo()?.location ?: return@runCatching null
        val origin = ((owner as? RootPaneContainer)?.contentPane ?: owner).locationOnScreen
        val scale = owner.graphicsConfiguration?.defaultTransform?.scaleX ?: 1.0
        Offset(((at.x - origin.x) * scale).toFloat(), ((at.y - origin.y) * scale).toFloat())
    }.getOrNull()

/** Material's `MenuVerticalMargin`: how close to the top or the bottom of the window a menu may be placed. */
private const val MENU_VERTICAL_MARGIN_DP = 48

/**
 * **Where a menu of [size] (AWT units) opens, on the screen — material's own rule, candidate for candidate**
 * (`DropdownMenuPositionProvider`), so that a menu opens exactly where it did when it was drawn inside the window.
 * [anchor] and [offsetPx] are pixels in [owner]'s content, which is "the window" the rule fits the menu in:
 *
 *  - across: its start on the anchor's start (+ the offset); else its end on the anchor's end; else against the
 *    window's left or right edge, whichever side the anchor is on — the first that lies inside the window;
 *  - down: its top on the anchor's bottom (+ the offset); else its bottom on the anchor's top; else its middle on the
 *    anchor's top; else against the window's top or bottom — the first that lies inside the window, a margin apart.
 *
 * (The later ones down are what put the calendar's menu at the pointer: it hangs from a whole day column, with the
 * pointer's place in the column as its offset.) The answer is then kept on the screen it falls on — the one thing
 * added: a window of its own may reach past the app's, but not past the screen.
 */
private fun menuPlacement(owner: AwtWindow, anchor: Rect, offsetPx: Offset, size: Dimension): Point {
    val pane = (owner as? RootPaneContainer)?.contentPane
    val origin = runCatching { (pane ?: owner).locationOnScreen }.getOrNull() ?: owner.location
    val scale = owner.graphicsConfiguration?.defaultTransform?.scaleX ?: 1.0
    val windowWidth = ((pane?.width ?: owner.width) * scale).roundToInt()
    val windowHeight = ((pane?.height ?: owner.height) * scale).roundToInt()
    val width = (size.width * scale).roundToInt()
    val height = (size.height * scale).roundToInt()
    val left = anchor.left.roundToInt()
    val top = anchor.top.roundToInt()
    val right = anchor.right.roundToInt()
    val bottom = anchor.bottom.roundToInt()
    val dx = offsetPx.x.roundToInt()
    val dy = offsetPx.y.roundToInt()
    val margin = (MENU_VERTICAL_MARGIN_DP * scale).roundToInt()

    val across = listOf(left + dx, right - width + dx, if ((left + right) / 2 < windowWidth / 2) 0 else windowWidth - width)
    val x = across.firstOrNull { it >= 0 && it + width <= windowWidth } ?: across.last()
    val down = listOf(
        bottom + dy,
        top - height + dy,
        top - height / 2 + dy,
        if ((top + bottom) / 2 < windowHeight / 2) margin else windowHeight - height - margin,
    )
    val y = down.firstOrNull { it >= margin && it + height <= windowHeight - margin } ?: down.last()

    val at = Point(origin.x + (x / scale).roundToInt(), origin.y + (y / scale).roundToInt())
    val area = workAreaAt(ScreenPoint(at.x, at.y)) ?: workAreaAt(ScreenPoint(origin.x, origin.y)) ?: return at
    return Point(
        at.x.coerceIn(area.x, maxOf(area.x, area.x + area.width - size.width)),
        at.y.coerceIn(area.y, maxOf(area.y, area.y + area.height - size.height)),
    )
}
