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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalContext
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.currentCompositeKeyHash
import androidx.compose.runtime.currentCompositionLocalContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
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
 * Desktop: **a menu is an OS window of its own, owned by the app's window** — above the app, free of its bounds (a field
 * near the bottom of a small window still has its menu below it), and under whatever stands above the app: user rule
 * 2026-11-15, *"I want the search windows to never appear above what is supposed to be above the app (like the
 * Firefox's picture-in-picture)"*. From 2026-10-13 it was always on top, over that too.
 *
 * Why not Compose's own way to the same thing (`compose.layers.type=WINDOW`, which makes every `Popup` such a
 * window): it was shipped on 2026-10-13 and **crashed the app at the first right-click** — in Compose 1.11.0 a layer
 * window resizes itself from inside its own draw (`WindowComposeSceneLayer.onDrawBoundsChanged` → `setSize` →
 * `SkiaLayer.reshape` → `renderImmediately` → the draw again), without end, until the native stack is gone. Hence
 * the first rule this file keeps: **the window is sized and placed from a task of its own on the event queue, never
 * from inside a layout or a draw**.
 *
 * The other two are the day's other anomalies:
 *  - **it opens BESIDE what it is about, never over it** ([menuPlacement]): below, else above, else at the right,
 *    else at the left, on the screen (user rule 2026-10-14). Material's own rule, ported here on 2026-10-13, fitted
 *    the menu in the app's window and fell back to centring it on the anchor, which hid the field it was about;
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
    // A menu opened inside a menu hangs from THAT menu's window (user rule 2026-11-04, the configurations tree's columns):
    // the active window may be a sibling menu about to close, and the anchor is in the window that draws it.
    // And a menu opened in an app window hangs from THAT window, never from the active one (anomaly 2026-11-04: once the
    // configurations tree had vanished on a hover out, the active window was one of its hidden menu windows, and the
    // next tree opened from wherever that one had stood — elsewhere at every try).
    val inMenu = LocalMenuWindow.current
    val here = LocalAppWindow.current
    val owner = remember { if (MENUS_ARE_WINDOWS) inMenu ?: here ?: owningWindow() else null }
    if (owner == null) {
        // Material's menu closes itself on a press outside it: one on its opener is that opener's click to answer
        // (anomaly 2026-11-20, [PopupOpener]), as it is for a menu window's loss of the keyboard.
        val opener = LocalPopupOpener.current
        val dismiss = androidx.compose.runtime.rememberUpdatedState(onDismissRequest)
        androidx.compose.material3.DropdownMenu(
            true, { if (!PopupOpeners.pressAnsweredBy(opener)) dismiss.value() }, modifier, offset, properties = properties,
        ) {
            androidx.compose.runtime.CompositionLocalProvider(LocalPopupOpener provides null) { content() }
        }
        return
    }
    HangingMenu(owner, at = null, offset, properties.focusable, onDismissRequest, modifier, atPointer || ROW_MENUS_AT_POINTER_EVERYWHERE, content)
}

/**
 * Left of and above it, a menu is out of sight, measured before it is shown ([MenuSession]'s first open, at -32000,
 * which Windows clamps to -16384): never a place to keep.
 */
private const val OFF_SCREEN = -16000

private val MENUS_ARE_WINDOWS: Boolean = System.getProperty("omniapp.menuWindows") != "false"

/**
 * The app window a composable is drawn in — what its menus hang from — given by the desktop's `main`; null where nothing
 * gives it (a test window), and the active window is asked instead.
 */
val LocalAppWindow = androidx.compose.runtime.staticCompositionLocalOf<AwtWindow?> { null }

/** The menu window a composable is drawn in, null outside one ([MenuSurface] provides it). */
private val LocalMenuWindow = androidx.compose.runtime.staticCompositionLocalOf<AwtWindow?> { null }

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
    val sides = LocalMenuSides.current
    // User rule 2026-11-16: the list of rows the menu's caller is in, which a pop-up opened from a row must not hide.
    val callerList = rememberUpdatedState(LocalCallerList.current)
    // The control that opens it, whose click a press on it is (anomaly 2026-11-20, [PopupOpener]).
    val opener = rememberUpdatedState(LocalPopupOpener.current)
    // User rule 2026-11-15: *"Right-click must open the right-click menu even if a right-click menu was already open."*
    // A right-click outside an open menu that leaves it open — the same row's again, another row whose menu is the
    // same one, a row of the pop-up it hangs from — opens it AFRESH: a new session, at the new pointer. Before, the
    // menu stood on where the first right-click had put it, as if the second had done nothing.
    val rightPress = RightPresses.count
    val opening = remember(owner) { intArrayOf(rightPress, 0) }
    // Anomaly 2026-11-21 (*"every pop-up window that appears from a caller is stuck to the caller"*): a right-click's menu
    // hangs from the window the right press landed in. A pop-up a hover opened in a menu just after the right-click that
    // opened that menu (the pointer is often on its row already) hangs from the menu's window: it is no right-click's
    // menu, and is stuck to its row like any other pop-up.
    val openedOnRight = remember(owner) { RightPresses.justPressed() && RightPresses.window === owner }
    val current = remember(owner) { arrayOfNulls<MenuSession>(1) }
    // Anomaly 2026-11-25 (*"right-click on A which opens B > right-click on B > show header > right-click on a row in B, no
    // pop-up opens"*): the right press is looked at once it has been answered — two frames on — and the menu opens afresh
    // only if it is still open then. A right-click on ANOTHER row with a menu of its own (a row of the pop-up this menu
    // hangs from) closes this one as that one opens ([TransientMenuHost.open]): reopened at once here, as it was, the two
    // fought and neither stayed.
    var generation by remember(owner) { androidx.compose.runtime.mutableIntStateOf(0) }
    if (openedOnRight && rightPress != opening[0]) {
        androidx.compose.runtime.LaunchedEffect(rightPress) {
            androidx.compose.runtime.withFrameNanos { }
            androidx.compose.runtime.withFrameNanos { }
            if (rightPress == RightPresses.count && rightPress != opening[0]) {
                opening[0] = rightPress
                if (current[0]?.contains(RightPresses.window) != true) generation++
            }
        }
    }
    val session = remember(owner, generation) {
        MenuSession(owner, focusable, site, dismiss, shownModifier, shownContent, locals, sides, openedOnRight, callerList, opener).also { current[0] = it }
    }
    DisposableEffect(session) { onDispose { session.close() } }
    // The pointer is read ONCE, as the menu opens — where the right-click was. Read at every layout of the anchor (a
    // row's hover, a scroll, a recomposition of the list), it sent the open menu after the hand moving to it (anomaly
    // 2026-10-14: *"appears, then teleports to the new position of the mouse"*).
    val pointer = remember(session) { if (atPointer) pointerIn(owner) else null }
    // What the menu hangs from: the layout it is declared in, exactly as material's menu reads it (`Popup`: a node of
    // no size, there only while the menu is open, that reports its parent's place in the window).
    // Keyed on the session: a menu opened afresh (a second right-click) is a new node, whose place is reported again.
    androidx.compose.runtime.key(session) {
    Layout(
        content = {},
        modifier = Modifier.onGloballyPositioned { own ->
            own.parentCoordinates?.let { parent ->
                val origin = parent.positionInWindow()
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
    /** The sides of its anchor the menu tries, in order ([LocalMenuSides]). */
    callSides: List<MenuSide> = MenuSide.DEFAULT,
    /** Opened by a right-click ([RightPresses]): a right-click's menu, not a pop-up of a field or a button. */
    openedOnRight: Boolean = true,
    /** The list of rows its caller is in ([LocalCallerList]), in [owner]'s pixels. */
    private val callerList: State<(() -> Rect?)?>? = null,
    /** What opens it ([LocalPopupOpener]): a press on it is its click's to answer, never a loss of the keyboard's. */
    private val opener: State<PopupOpener?>? = null,
) {
    /**
     * Anomaly 2026-11-20 (*"when I rapidly click on a down arrow, it appears and disappears in a blink"*): **every way this
     * window hears the keyboard leave it for a window of the app goes through here** — its loss, another window active,
     * its hover pop-up's tracker told. A press on its opener is that opener's click to answer ([PopupOpeners.keyboardLeft]):
     * the keyboard is given back if the click keeps it, [act] is dropped if the click closes it, and done as ever when
     * no click comes ([PopupOpener.SAME_GESTURE], or the pointer leaving the opener).
     */
    private fun keyboardLeft(act: () -> Unit) {
        val handle = PopupOpeners.keyboardLeft(
            opener?.value,
            lost = { if (!closed) act() },
            kept = { if (!closed) dialog?.giveKeyboard() },
        ) ?: return
        javax.swing.Timer(PopupOpener.SAME_GESTURE.inWholeMilliseconds.toInt()) { PopupOpeners.expire(handle) }
            .apply { isRepeats = false }
            .start()
    }

    /**
     * User rule 2026-11-16: *"When a row makes a window appear, it must never hide the list of rows the caller is in."* A
     * pop-up of a field or a button stands off the list; a right-click's menu is a question about one row, at the pointer.
     */
    private val avoidsList: Boolean = !openedOnRight
    private fun listNow(): Rect? = if (avoidsList) callerList?.value?.invoke() else null

    /**
     * User rule 2026-11-15: a pop-up opened from a field or a button tries the right, the left, below, above
     * ([MenuSide.POPUP]); a right-click's menu keeps below, above, right, left (2026-10-14). Told by the press that
     * opened it, not by the call site: a call site naming its own sides ([MenuSide.SUBMENU]) keeps them.
     */
    private val sides: List<MenuSide> = if (callSides == MenuSide.DEFAULT && !openedOnRight) MenuSide.POPUP else callSides

    /** Whether [window] is this menu's, or a menu opened from it. */
    fun contains(window: AwtWindow?): Boolean = ours(window)

    /**
     * User rule 2026-11-15: a pop-up dragged by its head ([PopupWindowControl]) follows the pointer and is no longer
     * placed by its anchor — neither as its content changes size nor as the app moves.
     */
    private var heldByHand = false
    private var dragFrom: Point? = null
    private var dragPointerFrom: Point? = null
    private var lastPointer: Point? = null
    /** Dragged, it is made invisible over every screen (not sent off the screen): it keeps the pointer, unseen. */
    private var hiddenInPlace = false



    val control = object : PopupWindowControl {
        override fun startDrag() {
            val window = dialog ?: return
            heldByHand = true
            dragFrom = window.location
            dragPointerFrom = java.awt.MouseInfo.getPointerInfo()?.location
            lastPointer = dragPointerFrom
            // Anomaly 2026-11-25, measured on the desktop — every other way broke the drag: sent off the screen, it let the
            // window under the pointer take the moves (a row there was selected); wholly transparent, the pointer went
            // through it; moved along with the pointer, it saw the pointer never move; left where it stood, or turned
            // invisible only a moment later, the pointer left it and the drag stopped for good. So at once it turns
            // invisible (not 0: a wholly transparent window is one the pointer goes through) over every screen, the pointer
            // moving in it wherever the hand goes. Until the window it becomes is drawn — a frame — nothing stands there.
            // Off the screen only where the platform cannot make it so.
            hiddenInPlace = runCatching {
                window.opacity = 0.01f
                val screens = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices
                    .map { it.defaultConfiguration.bounds }
                    .reduce { all, one -> all.union(one) }
                window.bounds = screens
            }.isSuccess
            if (!hiddenInPlace) window.location = Point(-32000, -32000)
        }

        override fun drag() {
            val window = dialog ?: return
            val from = dragFrom ?: return
            val pointerFrom = dragPointerFrom ?: return
            val pointer = java.awt.MouseInfo.getPointerInfo()?.location ?: return
            // Read off the screen: the content the drag is said in moves with the window.
            window.location = Point(from.x + pointer.x - pointerFrom.x, from.y + pointer.y - pointerFrom.y)
        }

        override fun pointerMoved(): Offset {
            val now = java.awt.MouseInfo.getPointerInfo()?.location ?: return Offset.Zero
            val before = lastPointer ?: now
            lastPointer = now
            val scale = generateSequence(owner) { it.owner }.last().graphicsConfiguration?.defaultTransform?.scaleX ?: 1.0
            return Offset(((now.x - before.x) * scale).toFloat(), ((now.y - before.y) * scale).toFloat())
        }

        override fun boundsInApp(): Rect? {
            val window = dialog ?: return null
            return runCatching {
                val app = generateSequence(owner) { it.owner }.last()
                val origin = ((app as? RootPaneContainer)?.contentPane ?: app).locationOnScreen
                val at = window.locationOnScreen
                val scale = app.graphicsConfiguration?.defaultTransform?.scaleX ?: 1.0
                Rect(
                    ((at.x - origin.x) * scale).toFloat(), ((at.y - origin.y) * scale).toFloat(),
                    ((at.x - origin.x + window.width) * scale).toFloat(), ((at.y - origin.y + window.height) * scale).toFloat(),
                )
            }.getOrNull()
        }
    }

    /**
     * User rule 2026-11-15: *"as the app gets resized"* and *"as the user moves the app"* — the window the menu hangs
     * from moving or changing size places it again, afresh: its anchor went with it. (A resize lays the anchor out
     * again too, which reports it; a move alone does not, the anchor being said in the window.)
     */
    private val ownerMoves = object : java.awt.event.ComponentAdapter() {
        override fun componentMoved(event: java.awt.event.ComponentEvent) = placeAfresh()
        override fun componentResized(event: java.awt.event.ComponentEvent) = placeAfresh()
    }


    private fun placeAfresh() {
        if (closed || dialog == null || heldByHand) return
        place()
    }
    private var anchor: Rect? = null
    private var offsetPx = Offset.Zero
    private var dialog: ComposeDialog? = null
    /** The size the content wants (AWT units) — what the menu is placed for. */
    private var size: Dimension? = null

    /**
     * User rule 2026-11-21 (*"even if it means reducing the size of the pop-up window"*): the size the window is shown at,
     * less than [size] where [menuFit] shrank it to keep rows in view; null while it is [size].
     */
    private var shrunkTo: Dimension? = null

    /** [shrunkTo] in the window's pixels, read by its content as it lays out ([MenuSurface]): it is laid out within it. */
    private val cap = androidx.compose.runtime.mutableStateOf<IntSize?>(null)
    private var closed = false

    /**
     * User rule 2026-11-15: *"when I click on a pop-up window to pin it, make sure it doesn't disappear when click outside
     * the app, like dragging the pip away for example"* — **a pinned pop-up stays while the keyboard is in ANOTHER
     * application** (AWT names no window of ours then: [opposite] null). A press elsewhere in the app still closes it
     * ([activeListener], [MenuHoverTracker.windowLeft]). Since 2026-11-11 the user going to another application closed it.
     */
    private fun pinnedAway(opposite: AwtWindow?): Boolean {
        // Anomaly 2026-11-15 (4, *"I right-clicked, which opened a window, then I clicked outside the app, then came back
        // on the app, but the right-click menu disappeared"*): every menu stays while the user is in another application,
        // a right-click's included; but a hover pop-up not pinned, which the pointer leaving closes anyway.
        val stays = opposite == null && (hover == null || hover?.pinned == true)
        if (stays && awayPresses == null) awayPresses = RightPresses.presses
        return stays
    }

    /** The presses heard when the user went to another application with the menu open ([pinnedAway]); null: not away. */
    private var awayPresses: Long? = null

    /**
     * Anomaly 2026-11-15 (8, *"Sometimes, even when there is no right-click menu open it needs a second right-click to open
     * the menu"*): **the keyboard settling after the menu opened is no reason to close it.** The right press activates the
     * app's window as Windows answers it, and that can land after the menu took the keyboard: the menu lost it to a window
     * of the app with no press since, and closed — the right-click had done nothing. For [SETTLE_NANOS] after it opens,
     * a change to a window of the app ([to] non-null) with no press since ([RightPresses.presses]) leaves it open; a press,
     * another application, or the time gone, and it is the change it always was.
     */
    private var openedAt = 0L
    private var pressesAtOpen = 0L

    private fun settling(to: AwtWindow?): Boolean =
        // Dragged by its head, it is a window of the app being placed: nothing closes it until the drag ends.
        heldByHand || (to != null && RightPresses.presses == pressesAtOpen && System.nanoTime() - openedAt < SETTLE_NANOS)

    private val ownerListener = object : WindowAdapter() {
        override fun windowDeactivated(event: WindowEvent) {
            if (!ours(event.oppositeWindow) && !pinnedAway(event.oppositeWindow) && !settling(event.oppositeWindow)) {
                dismiss.value()
            }
        }
    }
    private var heldKeyboard = false
    /**
     * Anomaly 2026-11-21 (*"the right-click must work, even if the right-click menu is already open"*): **a right-click in the
     * app's window is the app's to answer** — its root observer closes the open menus on that press, and what was
     * right-clicked opens its menu there, in the same event. The keyboard leaving this window is told after that event: closing
     * the menu then closed the one the right-click had just opened (the calendar's: one menu, its anchor moved). So the loss of
     * the keyboard to the app's window by a right press since this menu opened does nothing here.
     */
    private fun rightPressedInApp(): Boolean =
        RightPresses.presses != pressesAtOpen && RightPresses.justPressed() &&
            // Anomaly 2026-11-25 (*"right-clicking twice doesn't open a window, which is not the case on A"*): the window the
            // menu hangs from, or any it hangs from in turn — a menu's own window (a pop-up's background menu) as the
            // app's: the right-click there answers, never the loss of the keyboard to it.
            generateSequence(owner) { it.owner }.any { it === RightPresses.window }

    private val focusListener = object : WindowFocusListener {
        override fun windowGainedFocus(event: WindowEvent) {
            heldKeyboard = true
        }
        override fun windowLostFocus(event: WindowEvent) {
            if (rightPressedInApp()) return
            if (focusable && !ours(event.oppositeWindow) && !pinnedAway(event.oppositeWindow) && !settling(event.oppositeWindow)) {
                keyboardLeft { dismiss.value() }
            }
        }
    }

    /**
     * User rule 2026-11-11: a menu that holds the keyboard stays only while a window of ITS tree is the active one (it,
     * or a menu opened from it). A pinned hover pop-up whose child held the keyboard never lost it itself, so the user
     * going to another application left it on screen; now any change of the active window is asked, once the queue has
     * settled (between two of the app's windows there is a moment with none).
     */
    private val activeListener = java.beans.PropertyChangeListener {
        EventQueue.invokeLater {
            // Not before it has held the keyboard once: opening, the app's window is still the active one.
            if (closed || dialog == null || !heldKeyboard) return@invokeLater
            val active = KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow
            // Another application active: a pinned pop-up stays ([pinnedAway]).
            if (pinnedAway(active)) return@invokeLater
            // Back in the app with no press in it (the taskbar, Alt+Tab, the menu itself): it is there as it was left.
            // A press in the app is a press outside the menu, which closes it as ever.
            val away = awayPresses
            awayPresses = null
            if (!ours(active) && away != null && RightPresses.presses == away) return@invokeLater
            // The keyboard settling as it opens ([settling]): nothing to close. Never taken back: two pop-ups that did took it
            // from each other without end (anomaly 2026-11-15, *"a window that blinks continuously"*).
            if (!ours(active) && settling(active)) return@invokeLater
            // A right-click in the app's window: the app closed the menus and opened the right-clicked one ([rightPressedInApp]).
            if (!ours(active) && rightPressedInApp()) return@invokeLater
            if (!ours(active)) {
                // Back in the app by a press on another of its windows — which no loss of the keyboard told the pop-up,
                // having lost it to the other application: it closes as a press elsewhere in the app closes it.
                // Anomaly 2026-11-16 (*"clicked on Parents and Children, and instead of pinning the children window … it
                // removed the window that appeared from the down arrow"*, the `[popup]` trace): the press on a row of the ▾
                // tree made the tree's own window the active one. Told nothing of where the keyboard went, the pinned
                // branch closed every pop-up up to the tree's root. Gone to a pop-up it hangs from, only what stands
                // below that one closes — the keyboard's loss says the same ([gone]).
                keyboardLeft {
                    if (hover?.pinned == true) hover?.windowLeft(gone(active))
                    dismiss.value()
                }
            }
        }
    }

    /** What the app makes of a key its windows would have heard ([LocalMenuKeyForward]): the chords that walk every window. */
    var keyForward: ((Key, Boolean, Boolean, Boolean) -> Boolean)? = null

    /** The hover pop-up this menu is, or is opened in ([LocalMenuHover]): what a key typed in it pins. */
    private var hover: MenuHoverTracker? = null

    /**
     * Anomaly 2026-11-11 (*"it still didn't close it"*, measured): a pop-up brought back holds the keyboard with nothing
     * of it focused, and then no key reaches its content, nor the window's own key handler — Ctrl+Alt+← struck there was
     * heard by nothing. So the keys the app walks its positions with are taken here, from AWT, for this window, before
     * Compose: the arrows, handed to [keyForward] with their modifiers.
     */
    private val keyDispatcher = java.awt.KeyEventDispatcher { event ->
        if (event.id != java.awt.event.KeyEvent.KEY_PRESSED || dialog == null) return@KeyEventDispatcher false
        val source = event.component
        val window = source as? AwtWindow ?: source?.let { javax.swing.SwingUtilities.getWindowAncestor(it) }
        if (window !== dialog) return@KeyEventDispatcher false
        val key = when (event.keyCode) {
            java.awt.event.KeyEvent.VK_LEFT -> Key.DirectionLeft
            java.awt.event.KeyEvent.VK_RIGHT -> Key.DirectionRight
            else -> return@KeyEventDispatcher false
        }
        keyForward?.invoke(key, event.isControlDown || event.isMetaDown, event.isShiftDown, event.isAltDown) ?: false
    }

    /**
     * Where the keyboard went, as [MenuHoverTracker.windowLeft] asks it: whether [to] is one of a pop-up's windows or a
     * menu opened from one — the one test for both ways the keyboard is heard leaving (its loss, another window active).
     */
    private fun gone(to: AwtWindow?): (Set<Any>) -> Boolean = { windows -> generateSequence(to) { it.owner }.any { it in windows } }

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
        openedAt = System.nanoTime()
        pressesAtOpen = RightPresses.presses
        window.focusableWindowState = focusable
        size = MenuWindows.lastSize[site]
        window.size = size ?: Dimension(2, 2)
        // A size not known yet: composed and measured out of sight, shown where it belongs once it is ([fit]).
        if (size == null) window.location = Point(-32000, -32000) else applyPlacement(window, location())
        window.compositionLocalContext = locals.value
        window.setContent(
            // A focusable menu is material's: Escape closes it. A non-focusable one never sees a key.
            onPreviewKeyEvent = { event ->
                if (focusable && event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                    dismiss.value()
                    true
                } else {
                    // User rule 2026-11-14: typing in a hover pop-up pins it, as a press in it does.
                    if (event.type == KeyEventType.KeyDown) hover?.pin()
                    false
                }
            },
        ) {
            keyForward = LocalMenuKeyForward.current
            // The pop-up as a position of the app (user rule 2026-11-11): what its tracker hears of the keyboard coming to
            // it, and how a walk through the positions brings the keyboard back to it ([MenuHoverTracker.focusWindow]).
            val tracker = LocalMenuHover.current
            hover = tracker
            DisposableEffect(tracker) {
                if (tracker == null) return@DisposableEffect onDispose { }
                val focus = object : WindowFocusListener {
                    override fun windowGainedFocus(event: WindowEvent) = tracker.windowFocused()

                    // User rule 2026-11-14: the keyboard gone to a window that is none of the pop-up's own nor of its
                    // descendants — a press elsewhere in the app, or in another application.
                    override fun windowLostFocus(event: WindowEvent) {
                        val inside = generateSequence(event.oppositeWindow) { it.owner }.any { it in tracker.windows }
                        // Gone to another application, pinned: it stays (user rule 2026-11-15, [pinnedAway]).
                        // Gone to a pop-up this one hangs from: what is below that one closes, it stays.
                        if (!inside && !(event.oppositeWindow == null && tracker.pinned) && !heldByHand) {
                            val opposite = event.oppositeWindow
                            keyboardLeft { tracker.windowLeft(gone(opposite)) }
                        }
                    }
                }
                tracker.windows += window
                tracker.focusWindow = { window.giveKeyboard() }
                // The app's window at the root of the menus it hangs from.
                tracker.focusAppWindow = { generateSequence(window.owner) { it.owner }.lastOrNull()?.giveKeyboard() }
                window.addWindowFocusListener(focus)
                if (window.isFocused) tracker.windowFocused()
                onDispose {
                    window.removeWindowFocusListener(focus)
                    tracker.windows -= window
                    tracker.focusWindow = null
                    tracker.focusAppWindow = null
                }
            }
            // Inside, a menu opened is this window's, and opens by the default sides unless its call site says otherwise.
            androidx.compose.runtime.CompositionLocalProvider(
                LocalMenuWindow provides window,
                LocalMenuSides provides MenuSide.DEFAULT,
                // Its rows are in no list of the caller's: a list inside it says so itself.
                LocalCallerList provides null,
                LocalPopupWindowControl provides control,
                LocalPopupFarSide provides farSide.value,
                // A menu opened inside it is not opened by what opened it.
                LocalPopupOpener provides null,
            ) {
                MenuSurface(owner, modifier.value, content.value, cap, ::fit)
            }
        }
        // `popups.md`: a menu leaves on the first press outside it. A press in the app is the root observer's to see;
        // a press in ANOTHER application is seen here, as the window the menu hangs from losing the foreground — or,
        // for a menu that holds the keyboard, as the menu losing it.
        owner.addWindowListener(ownerListener)
        owner.addComponentListener(ownerMoves)
        window.addWindowFocusListener(focusListener)
        if (focusable) {
            KeyboardFocusManager.getCurrentKeyboardFocusManager().addPropertyChangeListener("activeWindow", activeListener)
            KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(keyDispatcher)
        }
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
            // User rule 2026-11-16: no window outside the app — never larger than the app's window either.
            val app = appArea(owner)
            val reported = Dimension(
                ceil(wanted.width / scale).toInt().coerceAtLeast(1).let { if (app != null) it.coerceAtMost(app.width.coerceAtLeast(1)) else it },
                ceil(wanted.height / scale).toInt().coerceAtLeast(1).let { if (app != null) it.coerceAtMost(app.height.coerceAtLeast(1)) else it },
            )
            // Laid out within a shrunk size, the content says that size, not the one it wants: on an axis where it fills
            // the cap, the size it wanted before stands (it may want more); where it is smaller than the cap, it is that.
            val capped = shrunkTo
            val before = size
            fun wantedOn(reported: Int, cap: Int?, before: Int?): Int =
                if (cap == null || reported < cap - 1 || before == null) reported else maxOf(before, reported)
            val fitted = Dimension(
                wantedOn(reported.width, capped?.width, before?.width),
                wantedOn(reported.height, capped?.height, before?.height),
            )
            MenuWindows.lastSize[site] = fitted
            size = fitted
            // Shown already: it stays where it is if it can ([keepsSpot]) — a menu never moves under the eye. Not while it
            // settles: a pop-up fitted to its content in its first moments (the ▾ tree) is placed for the size it ends
            // at, never left where its first size put it (anomaly 2026-11-15: *"appear too far to the top"*).
            val shown = window.location.takeIf { window.isShowing && it.x > OFF_SCREEN && it.y > OFF_SCREEN }
            val settled = System.nanoTime() - openedAt >= SETTLE_NANOS
            if (capped == null && window.size != fitted) window.size = fitted
            // Settling, it is not kept where its first size put it — but a pop-up is put back against its caller by the edge
            // the pointer crossed rather than placed afresh, which moved it from under the pointer (anomaly 2026-11-19).
            place(keeping = shown, settling = !settled)
        }
    }

    /**
     * Placed by its anchor, or kept at [keeping] (where it is shown) if it still may stand there ([keepsSpot]). [settling]:
     * in its first moments it is never kept as it stands, only put back against its caller ([reStuck]).
     */
    private fun place(keeping: Point? = null, settling: Boolean = false) {
        val window = dialog ?: return
        if (size == null) return
        // Dragged by its head: where the hand left it ([control]).
        if (heldByHand && window.isShowing) return
        val placed = menuPlacement(
            owner, anchor ?: Rect.Zero, offsetPx, size ?: Dimension(0, 0), sides, keeping, listNow(), stuck = avoidsList, settling = settling,
            shown = shrunkTo,
        )
        applyPlacement(window, placed)
    }

    private fun location(): Placed =
        menuPlacement(owner, anchor ?: Rect.Zero, offsetPx, size ?: Dimension(0, 0), sides, list = listNow(), stuck = avoidsList)

    /** The window at [placed]: its place, and its size — the content's, or less where [menuFit] shrank it ([cap]). */
    private fun applyPlacement(window: ComposeDialog, placed: Placed) {
        val wanted = size ?: placed.size
        val shrunk = placed.size.takeIf { it.width < wanted.width || it.height < wanted.height }
        shrunkTo = shrunk
        val scale = window.graphicsConfiguration?.defaultTransform?.scaleX ?: 1.0
        val capPx = shrunk?.let { IntSize((it.width * scale).toInt(), (it.height * scale).toInt()) }
        if (cap.value != capPx) cap.value = capPx
        if (window.size != placed.size) window.size = placed.size
        if (window.location != placed.at) window.location = placed.at
        noteFarSide(placed.at)
    }

    /**
     * User rule 2026-11-19: the side of this pop-up furthest from its caller ([farSideOf]), told to what it holds
     * ([LocalPopupFarSide]): where the ▾ of its search bar stands, and the side that ▾'s tree opens on first.
     */
    private val farSide = androidx.compose.runtime.mutableStateOf<FarSide?>(null)

    private fun noteFarSide(at: Point) {
        val shown = shrunkTo ?: size ?: return
        if (at.x <= OFF_SCREEN || at.y <= OFF_SCREEN) return
        val caller = rectOnScreen(owner, anchor ?: Rect.Zero, offsetPx)
        val next = farSideOf(IntRect(at.x, at.y, at.x + shown.width, at.y + shown.height), caller)
        if (farSide.value != next) farSide.value = next
    }

    fun close() {
        closed = true
        val window = dialog ?: return
        dialog = null
        owner.removeWindowListener(ownerListener)
        owner.removeComponentListener(ownerMoves)
        window.removeWindowFocusListener(focusListener)
        KeyboardFocusManager.getCurrentKeyboardFocusManager().removePropertyChangeListener("activeWindow", activeListener)
        KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(keyDispatcher)
        // A menu closed while it holds the keyboard (a hover pop-up vanishing, which no press outside ended) gives it back
        // to the nearest window still on screen: hidden with the keyboard, it left none of the app's windows active.
        val hadFocus = focusable && window.isActive
        MenuWindows.give(owner, window)
        if (hadFocus) generateSequence(owner) { it.owner }.firstOrNull { it.isShowing }?.toFront()
    }
}

/**
 * The keyboard given to this window: to the front, and the keyboard to what held it last in it. Anomaly 2026-11-11
 * (measured): `requestFocus()` on the window itself made the bare frame the focus owner, not the Compose surface in it,
 * and from then on no key reached the app — the walk through the positions stopped after giving the app's window back
 * the keyboard.
 */
private fun AwtWindow.giveKeyboard() {
    toFront()
    (mostRecentFocusOwner ?: this).requestFocus()
}

/** What a menu's window shows: material's menu surface, measured without bounds so that it says the size it wants. */
@Composable
private fun MenuSurface(
    owner: AwtWindow,
    modifier: Modifier,
    content: @Composable ColumnScope.() -> Unit,
    /** The size it is shown at where it was shrunk ([menuFit]), in pixels: it is laid out within it, never cut. */
    cap: State<IntSize?>,
    onWanted: (IntSize) -> Unit,
) {
    val within = Modifier.layout { measurable, constraints ->
        val c = cap.value
        val placeable = measurable.measure(
            if (c == null) constraints
            else constraints.copy(
                minWidth = minOf(constraints.minWidth, c.width), maxWidth = minOf(constraints.maxWidth, c.width),
                minHeight = minOf(constraints.minHeight, c.height), maxHeight = minOf(constraints.maxHeight, c.height),
            ),
        )
        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }
    // Never taller than the screen it is on: past that the menu scrolls, as material's does.
    val tallest = remember(owner) { (workAreaAt(ScreenPoint(owner.x, owner.y))?.height ?: 2000).dp }
    // A menu opened inside a hover pop-up is one of its descendants ([LocalMenuHover]): the pointer over it holds the pop-up.
    // A Search window's menu ([LocalBareMenu]) is shown bare: it wears a window's surface itself (anomaly 2026-11-15, 5).
    if (LocalBareMenu.current) {
        Box(Modifier.reportsHover(LocalMenuHover.current).wrapContentSize(align = Alignment.TopStart, unbounded = true).then(within).onSizeChanged(onWanted)) {
            androidx.compose.runtime.CompositionLocalProvider(LocalBareMenu provides false) {
                Column(modifier = modifier.heightIn(max = tallest), content = content)
            }
        }
        return
    }
    Box(Modifier.reportsHover(LocalMenuHover.current).wrapContentSize(align = Alignment.TopStart, unbounded = true).then(within).onSizeChanged(onWanted)) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            contentColor = MaterialTheme.colorScheme.onSurface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.heightIn(max = tallest),
        ) {
            // Material's own menu column: the caller's modifier, the menu's padding, as wide as its widest entry.
            Column(
                modifier = modifier.padding(vertical = 8.dp).width(IntrinsicSize.Max).verticalScrollWithBar(rememberScrollState()),
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
            // User rule 2026-11-15: *"I want the search windows to never appear above what is supposed to be above the
            // app (like the Firefox's picture-in-picture)"* — owned by the app's window, a menu stands above IT and
            // nothing more: never always-on-top. (It was, from 2026-10-13: "even above Firefox's picture-in-picture".)
            type = AwtWindow.Type.POPUP
        }

    fun take(owner: AwtWindow): ComposeDialog =
        (spare.remove(owner)?.takeIf { it.isDisplayable } ?: make(owner)).also(::hideOwned).also(::shownWhole)

    /**
     * Anomaly 2026-11-25 (*"when a pop-up window becomes a window …, the right-click doesn't work anymore anywhere"*): a
     * window that carried a pop-up's drag was left all but transparent ([PopupWindowControl.startDrag]), and the next menu
     * reusing it opened unseen — every right-click's menu after a pop-up became a window. A window is opaque again as it
     * is given back and as it is taken.
     */
    private fun shownWhole(window: AwtWindow) {
        runCatching { if (window.opacity < 1f) window.opacity = 1f }
    }

    /** Every window [window] owns, at any depth, hidden while it is still on screen: none comes back with it. */
    private fun hideOwned(window: AwtWindow) {
        for (owned in window.ownedWindows) {
            hideOwned(owned)
            if (owned.isVisible) owned.isVisible = false
        }
    }

    /** The menu closed: its content is let go, and the window is kept hidden for the next one — one per owner. */
    fun give(owner: AwtWindow, dialog: ComposeDialog) {
        // Its own menus first (a menu opened in this menu, the configurations tree's next column): hidden WITH their
        // owner, AWT shows them again when that window is next shown — reused for another menu, it brought back an
        // empty grey window where the closed child had been (seen on screen, 2026-11-04).
        hideOwned(dialog)
        dialog.isVisible = false
        shownWhole(dialog)
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
fun warmMenuWindows(owner: AwtWindow) {
    RightPresses.install()
    MenuWindows.warm(owner)
}

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

/**
 * **The right-clicks of the app, as AWT sees them** — before any of its components handles one, so a menu composed after
 * the press can tell a right-click came ([count]) and where ([window]), and a menu as it opens whether a right-click
 * opened it ([justPressed]). Decided per input event (`docs/PLATFORMS.md`): the button of the press itself.
 */
internal object RightPresses {
    /** How many right presses the app has heard: read in composition, so a new one recomposes the open menus. */
    var count: Int by androidx.compose.runtime.mutableIntStateOf(0)
        private set

    /** The window the last right press landed in. */
    var window: AwtWindow? = null
        private set

    /** Whether the last press of any button was the right one. */
    private var lastWasRight: Boolean = false

    /** How many presses of any button the app has heard ([MenuSession.settling]). */
    var presses: Long = 0L
        private set

    /** When the last right press was heard ([System.nanoTime]). */
    private var lastRightAt: Long = 0L

    /**
     * Whether a menu opening now was opened by a right-click: the last press was the right button's, and a moment ago — a
     * right-click's menu opens a frame or two after it, where a pop-up opened by a hover long after one is not its menu.
     */
    fun justPressed(): Boolean = lastWasRight && System.nanoTime() - lastRightAt < RIGHT_CLICK_OPENS_WITHIN_NANOS

    init {
        runCatching {
            java.awt.Toolkit.getDefaultToolkit().addAWTEventListener({ event ->
                if (event is java.awt.event.MouseEvent && event.id == java.awt.event.MouseEvent.MOUSE_PRESSED) {
                    presses++
                    lastWasRight = javax.swing.SwingUtilities.isRightMouseButton(event)
                    if (lastWasRight) {
                        val source = event.component
                        window = source as? AwtWindow ?: source?.let { javax.swing.SwingUtilities.getWindowAncestor(it) }
                        lastRightAt = System.nanoTime()
                        count++
                    }
                }
            }, java.awt.AWTEvent.MOUSE_EVENT_MASK)
        }
    }

    /** Installs the listener ahead of the first menu: the press that opens it must already be heard. */
    fun install() = Unit

    private const val RIGHT_CLICK_OPENS_WITHIN_NANOS = 750_000_000L
}

/** Where the pointer is, in pixels of [owner]'s content — what an anchor is said in. Null where it cannot be asked. */
private fun pointerIn(owner: AwtWindow): Offset? =
    runCatching {
        val at = java.awt.MouseInfo.getPointerInfo()?.location ?: return@runCatching null
        val origin = ((owner as? RootPaneContainer)?.contentPane ?: owner).locationOnScreen
        val scale = owner.graphicsConfiguration?.defaultTransform?.scaleX ?: 1.0
        Offset(((at.x - origin.x) * scale).toFloat(), ((at.y - origin.y) * scale).toFloat())
    }.getOrNull()

/**
 * **Where a menu of [size] (AWT units) opens, on the screen**: beside its anchor, the first of [sides] the whole menu fits
 * on ([menuSpot], user rule 2026-10-14), off the [list] of rows its caller is in. [anchor], [list] and [offsetPx] are
 * pixels in [owner]'s content; the offset moves the anchor (the window bar's menu: the bar, at the pointer's x).
 *
 * User rule 2026-11-16: *"No window must appear outside the app."* The room it has is the APP's window (its content, as
 * shown on the screen's work area) — no longer the screen's, which the 2026-11-15 rule gave it.
 */
/** Where a menu window stands and the size it is shown at ([menuPlacement]), in AWT units. */
private class Placed(val at: Point, val size: Dimension)

private fun menuPlacement(
    owner: AwtWindow,
    anchor: Rect,
    offsetPx: Offset,
    size: Dimension,
    sides: List<MenuSide> = MenuSide.DEFAULT,
    /** Where the menu is shown, kept if it still may stand there ([keepsSpot]); null for a menu placed afresh. */
    keeping: Point? = null,
    list: Rect? = null,
    /** User rule 2026-11-19: a pop-up is kept only where it still touches its caller ([stuckTo]). */
    stuck: Boolean = false,
    /** In its first moments: never kept as it stands, only put back against its caller ([reStuck]). */
    settling: Boolean = false,
    /** The size it is shown at now where it is shrunk ([menuFit]); null: [size]. */
    shown: Dimension? = null,
): Placed {
    val on = rectOnScreen(owner, anchor, offsetPx)
    val listOn = list?.let { rectOnScreen(owner, it, Offset.Zero) }
    val area = appArea(owner)
        ?: workAreaAt(ScreenPoint(on.left, on.top))?.let { IntRect(it.x, it.y, it.x + it.width, it.y + it.height) }
        ?: return Placed(keeping ?: Point(on.left, on.bottom), shown ?: size)
    // Kept, it keeps the size it is shown at: a shrunk pop-up grows back when it is placed afresh (the app resized or moved).
    val now = shown?.let { Dimension(minOf(it.width, size.width), minOf(it.height, size.height)) } ?: size
    if (keeping != null && !settling && keepsSpot(IntOffset(keeping.x, keeping.y), IntSize(now.width, now.height), on, area, listOn, stuck)) {
        return Placed(keeping, now)
    }
    // A pop-up come off its caller by a change of size: back against it by the edge the pointer crossed (anomaly 2026-11-19).
    if (keeping != null && stuck && shown == null) {
        reStuck(IntOffset(keeping.x, keeping.y), IntSize(size.width, size.height), on, area, listOn)?.let { return Placed(Point(it.x, it.y), size) }
    }
    // User rule 2026-11-21: a pop-up stuck to its caller, shrunk rather than hiding its rows ([menuFit]); a right-click's
    // menu at its size ([menuSpot]).
    if (stuck) {
        val fit = menuFit(on, IntSize(size.width, size.height), area, sides, listOn)
        return Placed(Point(fit.at.x, fit.at.y), Dimension(fit.size.width, fit.size.height))
    }
    val spot = menuSpot(on, IntSize(size.width, size.height), area, sides, listOn)
    return Placed(Point(spot.x, spot.y), size)
}

/** [r] (pixels of [owner]'s content, moved by [by]) on the screen, in AWT units. */
private fun rectOnScreen(owner: AwtWindow, r: Rect, by: Offset): IntRect {
    val pane = (owner as? RootPaneContainer)?.contentPane
    val origin = runCatching { (pane ?: owner).locationOnScreen }.getOrNull() ?: owner.location
    val scale = owner.graphicsConfiguration?.defaultTransform?.scaleX ?: 1.0
    fun screen(px: Float) = (px / scale).roundToInt()
    return IntRect(
        left = origin.x + screen(r.left + by.x),
        top = origin.y + screen(r.top + by.y),
        right = origin.x + screen(r.right + by.x),
        bottom = origin.y + screen(r.bottom + by.y),
    )
}

/**
 * User rule 2026-11-16: **the room every menu and pop-up has — the app's window**, its content on the screen (the window
 * at the root of the menus [owner] hangs from), cut to the work area of the screen it is on. Null where it cannot be read.
 */
private fun appArea(owner: AwtWindow): IntRect? = runCatching {
    val app = generateSequence(owner) { it.owner }.last()
    if (!app.isShowing) return@runCatching null
    val pane = (app as? RootPaneContainer)?.contentPane ?: app
    val at = pane.locationOnScreen
    val own = IntRect(at.x, at.y, at.x + pane.width, at.y + pane.height)
    val work = workAreaAt(ScreenPoint(own.left + own.width / 2, own.top + own.height / 2))
    if (work == null) own
    else IntRect(maxOf(own.left, work.x), maxOf(own.top, work.y), minOf(own.right, work.x + work.width), minOf(own.bottom, work.y + work.height))
        .takeIf { it.width > 0 && it.height > 0 } ?: own
}.getOrNull()

/** How long after a menu opens the keyboard settling is not taken for the user going elsewhere ([MenuSession]). */
private const val SETTLE_NANOS = 600_000_000L
