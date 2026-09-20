package org.example.project.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt

/*
 * # Every window in the app wears this frame
 *
 * `docs/invariants/popups.md`. A window of this app — a lateral-menu window, a task's edit window, a
 * period's, a notice — is one thing with one set of manners, and they are all here rather than in each
 * window's own file:
 *
 *  - **it stays until it is closed.** No window leaves because a press landed somewhere else. The single
 *    exception is a right-click contextual MENU or a drop-down, which is not a window and still closes on
 *    the first press outside it ([TransientMenuHost]);
 *  - **it has a head**, which is the handle it is dragged by and carries the five buttons below;
 *  - **fill width / fill height / reduce / maximize / close**, in that order. Maximize is exactly "fill
 *    both axes" ([WindowFill.Both]) rather than a sixth, separate state — pressing the two fill buttons in
 *    turn therefore lands on the same window as pressing maximize, and un-maximizing puts back the size and
 *    position the window had before the first of them;
 *  - **double-clicking the head** maximizes it, and un-maximizes it when it already is;
 *  - **it is resized by its left, right or bottom edge**. Not the top: the head is there, and a window
 *    whose head moves under the cursor mid-drag is the one edge that cannot be made to feel right;
 *  - **reduced windows go to the bar along the bottom of the app** ([MinimizedWindowBar]), which is drawn
 *    over the lateral menu because a window reduced while the menu is open must not be filed behind it.
 *
 * Why one composable instead of a shared "header" helper: the five buttons and the three edges are not
 * decoration, they are the *same* geometry ([WindowFrameState]) read five ways. A second copy of that
 * arithmetic is how two windows come to disagree about what "maximized" means — the mistake
 * `CLAUDE.md`'s *one rule, one funnel* names.
 */

/** Which axes a window has been told to fill. [Both] is what "maximized" means. */
enum class WindowFill {
    None,
    Width,
    Height,
    Both,
    ;

    val fillsWidth: Boolean get() = this == Width || this == Both
    val fillsHeight: Boolean get() = this == Height || this == Both

    fun withWidth(on: Boolean): WindowFill = of(on, fillsHeight)
    fun withHeight(on: Boolean): WindowFill = of(fillsWidth, on)

    companion object {
        fun of(width: Boolean, height: Boolean): WindowFill =
            when {
                width && height -> Both
                width -> Width
                height -> Height
                else -> None
            }
    }
}

/** Smallest a window may be dragged down to, in px at a 1× density — below this the head stops fitting. */
const val MIN_WINDOW_WIDTH_PX: Float = 220f

/** …and its vertical counterpart: the head plus a usable sliver of content. */
const val MIN_WINDOW_HEIGHT_PX: Float = 120f

/**
 * The geometry of ONE window, and every gesture that can move it.
 *
 * Deliberately a plain observable class rather than a bundle of `remember`s inside each window: the
 * reduce bar at the bottom of the app has to see every window's [minimized] flag, the frame has to see
 * the same numbers the resize edges write, and `App` has to be able to persist them. It is also the whole
 * of the arithmetic, so it can be tested without a UI (`WindowFrameStateTest`).
 *
 * [offset] is the drag offset from the window's centred resting position, in px — the same quantity
 * `WindowPlacement` has always persisted. [size] is the window's size in px; `0` on an axis means "not
 * measured yet", filled in from the first layout pass ([measured]).
 *
 * The **normal** geometry (position and size with no axis filled) is remembered separately, because
 * filling an axis overwrites what is on screen and un-filling has to put it back — per axis, so that
 * releasing "fill width" on a window that is also filling its height restores only its width.
 */
class WindowFrameState(
    val id: String,
    initialOffset: Offset = Offset.Zero,
    initialSize: Size = Size.Zero,
    val minWidth: Float = MIN_WINDOW_WIDTH_PX,
    val minHeight: Float = MIN_WINDOW_HEIGHT_PX,
) {
    var offset: Offset by mutableStateOf(initialOffset)
        private set

    var size: Size by mutableStateOf(initialSize)
        private set

    var fill: WindowFill by mutableStateOf(WindowFill.None)
        private set

    var minimized: Boolean by mutableStateOf(false)
        private set

    private var normalOffset: Offset = initialOffset
    private var normalSize: Size = initialSize

    /** Maximized is not a state of its own: it is both axes filled. */
    val maximized: Boolean get() = fill == WindowFill.Both

    /**
     * The offset actually applied. A filled axis is pinned to the container, so the drag offset on that
     * axis is not applied — but it is not *forgotten* either, which is what lets un-filling restore it.
     */
    val appliedOffset: Offset
        get() = Offset(if (fill.fillsWidth) 0f else offset.x, if (fill.fillsHeight) 0f else offset.y)

    /**
     * The size the first layout pass measured, adopted for any axis that has none yet. Called from the
     * frame's `onGloballyPositioned`, so a window that has never been resized still knows the numbers the
     * resize edges do arithmetic on. A filled axis is skipped: what it measures is the container, not the
     * window's own size, and adopting it would make un-filling a no-op.
     */
    fun measured(measured: Size) {
        val width = if (size.width <= 0f && !fill.fillsWidth) measured.width else size.width
        val height = if (size.height <= 0f && !fill.fillsHeight) measured.height else size.height
        if (width != size.width || height != size.height) {
            size = Size(width, height)
            normalSize = Size(
                if (normalSize.width <= 0f && !fill.fillsWidth) width else normalSize.width,
                if (normalSize.height <= 0f && !fill.fillsHeight) height else normalSize.height,
            )
        }
    }

    /** A drag of the head. A filled axis does not move: the window is pinned to the container on it. */
    fun moveBy(delta: Offset) {
        val dx = if (fill.fillsWidth) 0f else delta.x
        val dy = if (fill.fillsHeight) 0f else delta.y
        if (dx == 0f && dy == 0f) return
        offset = Offset(offset.x + dx, offset.y + dy)
        normalOffset = Offset(normalOffset.x + dx, normalOffset.y + dy)
    }

    /**
     * The LEFT edge dragged by [dx] px. The window is centred, so growing it leftwards by `d` moves its
     * centre by `-d/2` — that is what keeps the *other* edge where the user left it, which is the whole
     * point of grabbing this one rather than the opposite one. Clamped at [minWidth], and the offset moves
     * by the width actually applied, so a clamped drag does not slide the window sideways.
     */
    fun resizeLeftBy(dx: Float) {
        if (fill.fillsWidth) return
        val width = (size.width - dx).coerceAtLeast(minWidth)
        val applied = size.width - width
        if (applied == 0f) return
        setNormalWidth(width)
        setNormalOffsetX(offset.x + applied / 2f)
    }

    /** The RIGHT edge dragged by [dx] px: the left edge stays, so the centre moves by half of it. */
    fun resizeRightBy(dx: Float) {
        if (fill.fillsWidth) return
        val width = (size.width + dx).coerceAtLeast(minWidth)
        val applied = width - size.width
        if (applied == 0f) return
        setNormalWidth(width)
        setNormalOffsetX(offset.x + applied / 2f)
    }

    /** The BOTTOM edge dragged by [dy] px: the head stays where it is, so the centre moves by half of it. */
    fun resizeBottomBy(dy: Float) {
        if (fill.fillsHeight) return
        val height = (size.height + dy).coerceAtLeast(minHeight)
        val applied = height - size.height
        if (applied == 0f) return
        setNormalHeight(height)
        setNormalOffsetY(offset.y + applied / 2f)
    }

    /**
     * Keeps the **head reachable**: whatever the window's size and wherever it was dragged, its head stays
     * between the top of the content area and its lowest row. An over-tall window (a small screen, a window
     * dragged up) would otherwise put its own head — and with it every one of its five buttons — out of
     * reach, which is the one state a window manager must not allow.
     *
     * Called from the frame's layout with the container's and the window's measured heights. Clamping a
     * fixed point converges, so re-applying it on every layout pass is safe.
     */
    fun clampVertical(containerHeight: Float, windowHeight: Float, headHeight: Float) {
        if (minimized || fill.fillsHeight) return
        if (containerHeight <= 0f || windowHeight <= 0f || headHeight <= 0f) return
        val restingTop = (containerHeight - windowHeight) / 2f
        val lowestTop = (containerHeight - headHeight).coerceAtLeast(0f)
        // The two ends, as offsets: the head's top at 0, and at the lowest row it may occupy. Which is the
        // lower number depends on whether the window is taller than the container, so they are ordered.
        val atTop = -restingTop
        val atBottom = lowestTop - restingTop
        val y = offset.y.coerceIn(minOf(atTop, atBottom), maxOf(atTop, atBottom))
        if (y != offset.y) setNormalOffsetY(y)
    }

    fun setFillWidth(on: Boolean) = applyFill(fill.withWidth(on))

    fun setFillHeight(on: Boolean) = applyFill(fill.withHeight(on))

    /** The maximize button, and the head's double-click: both axes, or back to the normal geometry. */
    fun toggleMaximize() = applyFill(if (maximized) WindowFill.None else WindowFill.Both)

    fun minimize() {
        minimized = true
    }

    /** Back from the reduce bar. The window returns exactly as it was, filled axes included. */
    fun restore() {
        minimized = false
    }

    private fun applyFill(next: WindowFill) {
        val previous = fill
        if (next == previous) return
        if (previous == WindowFill.None) {
            normalOffset = offset
            normalSize = size
        }
        fill = next
        if (previous.fillsWidth && !next.fillsWidth) {
            offset = Offset(normalOffset.x, offset.y)
            size = Size(normalSize.width, size.height)
        }
        if (previous.fillsHeight && !next.fillsHeight) {
            offset = Offset(offset.x, normalOffset.y)
            size = Size(size.width, normalSize.height)
        }
    }

    private fun setNormalWidth(width: Float) {
        size = Size(width, size.height)
        normalSize = Size(width, normalSize.height)
    }

    private fun setNormalHeight(height: Float) {
        size = Size(size.width, height)
        normalSize = Size(normalSize.width, height)
    }

    private fun setNormalOffsetX(x: Float) {
        offset = Offset(x, offset.y)
        normalOffset = Offset(x, normalOffset.y)
    }

    private fun setNormalOffsetY(y: Float) {
        offset = Offset(offset.x, y)
        normalOffset = Offset(normalOffset.x, y)
    }
}

/**
 * Every framed window that is currently composed, so the app can draw the reduce bar, answer "which window
 * has the keyboard", and know **what is drawn over what**.
 *
 * The three questions are here together because they are the same registry read three times, and because
 * all of them outlive the window's own file: the bar is drawn at the app root (it has to cross the lateral
 * menu), the tree reads the keyboard answer through a composition local, exactly as it used to read
 * [TransientMenuHost.anyOpen], and the stacking order has to compare a lateral-menu window against a
 * per-object one.
 */
class WindowFrameHost {
    class Registration(
        val id: String,
        title: String,
        val state: WindowFrameState,
        /** Whether this window takes the keyboard off the task tree while it is the focused one. */
        val claimsKeyboard: Boolean,
        val onClose: () -> Unit,
    ) {
        /**
         * What the reduce bar's chip reads. Observable and mutable rather than a `val` re-registered on
         * every change: a window titled after its subject (a task's edit window is titled with the task)
         * is re-titled while it stands there, and re-registering would take the keyboard back off whatever
         * the user had moved to.
         */
        var title: String by mutableStateOf(title)
    }

    private val entries = mutableStateListOf<Registration>()

    val registrations: List<Registration> get() = entries

    /** The reduced windows, in the order they were opened — the order the bar lists them in. */
    val minimizedWindows: List<Registration> get() = entries.filter { it.state.minimized }

    /** Whether the bar has anything to show. Read by `App` to inset the content area above it. */
    val hasMinimized: Boolean get() = entries.any { it.state.minimized }

    /** The window the last press landed in, or null for the task tree behind them. */
    var focusedId: String? by mutableStateOf(null)
        private set

    /**
     * The app's ONE stacking order, bottom first.
     *
     * It lives here because this class already sees the only two events that decide it: a window OPENING
     * ([register] puts it on top) and a press landing inside one ([focus]). Before that, `App` kept a
     * stacking order of its own for the lateral-menu windows and every per-object window was pinned above
     * the lot of it on a fixed `zIndex(100f)` — correct only while such a window was a thing that left on
     * the next press. A window that STAYS has to be able to go **under** the one the user has moved to, or
     * the priority-weight table stands over every window opened after it (ADR 0014).
     *
     * Deliberately a second list beside [entries] rather than a re-ordering of it: [entries] is the order
     * windows were *opened* in, which is what the reduce bar lists, and a chip must not jump along the bar
     * because its window was raised.
     */
    private val stack = mutableStateListOf<String>()

    /** The stacking order, bottom first. */
    val stackOrder: List<String> get() = stack

    /**
     * The window at the very TOP of it, or null when none is open — what "the front window" means.
     *
     * Every window is in the one stack, so this answers a per-object window standing over a lateral-menu
     * one, which is the case a "topmost lateral-menu window" answer gets wrong: `App`'s lateral-menu
     * button closes the window it names only when that window is already the front one, and with the
     * priority-weight table over the Alarms window it must bring Alarms **back** instead.
     */
    val frontId: String? get() = stack.lastOrNull()

    /**
     * Where [id] sits in the stack, as a `zIndex`. A window not in it yet — one composing for the first
     * time, whose [register] runs after this pass — reads as the **top**, because a window that has just
     * opened is on top; that is also what keeps it from being drawn under its neighbours for one frame.
     */
    fun zOf(id: String): Float {
        val index = stack.indexOf(id)
        return if (index < 0) stack.size.toFloat() else index.toFloat()
    }

    /**
     * Where a **pair** sits — a window and the companion window it opens beside itself (the History window
     * and its row info, the task-trees list and a tree's detail). The two are drawn in ONE wrapper `Box`, so
     * the pair stands among the app's windows as a single thing, and that thing sits where its **topmost**
     * member does: a press in the companion raises the companion, and the pair has to come forward with it.
     * Reading the first window's z alone is what left the History row-info window unable to bring its pair
     * back over whatever the user had moved to — the press landed in a window and nothing came forward.
     *
     * [companion] is null while the companion is not open. Never its id: an id that is not in the stack
     * reads as the TOP (see [zOf]), so a closed companion would pin the pair over every other window.
     */
    fun zOf(id: String, companion: String?): Float =
        if (companion == null) zOf(id) else maxOf(zOf(id), zOf(companion))

    /** Puts [id] on top. Called when a window opens, on every press inside one, and by `App`'s raises. */
    fun raise(id: String) {
        if (stack.lastOrNull() == id) return
        stack.remove(id)
        stack += id
    }

    /**
     * Whether the focused window answers the keyboard itself, which is what makes the task tree go **deaf**
     * (`TaskTreeView`'s `keyboardOwned`). PRD §4's "type a letter on the selected cell and it starts
     * renaming" would otherwise fire behind the window being typed into.
     *
     * Focus-based, not open-based: a window that no longer leaves on an outside press would otherwise hold
     * the keyboard for as long as it stayed open, and the tree could never be typed in again without
     * closing it. A window that claims the keyboard takes the focus when it opens, and gives it back on the
     * first press in the tree.
     */
    val keyboardClaimed: Boolean
        get() = entries.any { it.id == focusedId && it.claimsKeyboard }

    fun register(registration: Registration) {
        entries.removeAll { it.id == registration.id }
        entries += registration
        // A window that has just opened is the one the user asked for, so it opens on top.
        raise(registration.id)
    }

    /** The window's title changed. Not a re-registration — see [Registration.title]. */
    fun retitle(id: String, title: String) {
        entries.firstOrNull { it.id == id }?.title = title
    }

    fun unregister(id: String) {
        entries.removeAll { it.id == id }
        stack.remove(id)
        if (focusedId == id) focusedId = null
    }

    /** A press landed inside [id]: it takes the focus AND comes to the top of the stack. */
    fun focus(id: String) {
        focusedId = id
        raise(id)
    }

    /**
     * The user asked for [id] again — pressed the control that opens it while it is already open. It must come
     * back to them however it had been put away: out of the reduce bar, to the top of the stack, and into the
     * focus. Opening it the first time needs none of this ([register] raises it), but asking a second time
     * changes no state of the caller's, so without this the window stays wherever it was, under whatever the
     * user had moved to. Safe before the window registers: the focus and the stack slot are simply taken early.
     */
    fun present(id: String) {
        entries.firstOrNull { it.id == id }?.state?.restore()
        focus(id)
    }

    /** The task tree took a press: no framed window is focused any more. */
    fun blur() {
        focusedId = null
    }
}

val LocalWindowFrameHost = staticCompositionLocalOf<WindowFrameHost?> { null }

/**
 * A window's place in the app's ONE stacking order ([WindowFrameHost.stackOrder]).
 *
 * It goes on the window's **outermost** element — [AppWindowFrame] applies it to itself, and a window that
 * is drawn inside a wrapper (the `Box` a window pairs itself with a companion window in, the layer a
 * per-object window is centred in) applies it there as well, because `zIndex` only orders a node among its
 * own siblings and the wrapper is what stands beside the other windows.
 *
 * A wrapper holding a PAIR — a window and the companion window it opens beside itself — names the companion
 * in [companion] while that companion is open, so that a press in either half brings the pair forward
 * ([WindowFrameHost.zOf]).
 *
 * Never a constant: a fixed z passed in from outside is exactly how the priority-weight table and every
 * other per-object window came to stand over whatever the user moved to afterwards. They could, while a
 * window about one object was a thing that left on the next press; nothing leaves any more (ADR 0014).
 */
@Composable
fun Modifier.windowStackZ(id: String, companion: String? = null): Modifier {
    val host = LocalWindowFrameHost.current
    return this.zIndex(host?.zOf(id, companion) ?: 0f)
}

/**
 * The frame's state, remembered for as long as the window is composed. [id] must be unique among the
 * windows that can be open at once — it is the key the reduce bar and the focus answer are kept under.
 */
@Composable
fun rememberWindowFrameState(
    id: String,
    initialOffset: Offset = Offset.Zero,
    initialSize: Size = Size.Zero,
): WindowFrameState = remember(id) { WindowFrameState(id, initialOffset, initialSize) }

/** Height reserved for [MinimizedWindowBar]; `App` insets the content area by it while it has rows. */
val MINIMIZED_BAR_HEIGHT: Dp = 38.dp

/** How close two presses on the head must be to count as the double-click that maximizes it. */
private const val HEAD_DOUBLE_CLICK_MILLIS: Long = 350

private val HEAD_BUTTON_SIZE: Dp = 24.dp
private val RESIZE_EDGE_THICKNESS: Dp = 6.dp

/**
 * Draws [content] inside the app's one window frame. Put the caller's `align` in [modifier]; the frame
 * owns everything else about the window's shape and position — **including where it sits in the stack**,
 * which it reads off [WindowFrameHost.zOf] so that no caller can pin a window above the order.
 *
 * [defaultWidth]/[defaultHeight] are the size the window opens at. Both are explicit — never "as tall as
 * the content" — because a window whose height is its content's cannot be given a *different* height by
 * dragging its bottom edge. [content] is laid out in a column below the head, so a scrolling list inside
 * it takes `Modifier.weight(1f)` and follows the window's height instead of a `heightIn(max = …)` cap.
 */
@Composable
fun AppWindowFrame(
    title: String,
    state: WindowFrameState,
    onClose: () -> Unit,
    defaultWidth: Dp,
    defaultHeight: Dp,
    modifier: Modifier = Modifier,
    /** Raise this window in `App`'s stack — fired on a press anywhere inside it, as it always was. */
    onRaise: () -> Unit = {},
    /** Persisted (locally only) at the end of a move or resize gesture. Never mid-drag. */
    onGeometryChange: (Offset, Size) -> Unit = { _, _ -> },
    /** True for a window that answers keystrokes itself: it takes the keyboard off the tree while focused. */
    claimsKeyboard: Boolean = false,
    /** A notice has nothing to come back to, so it is not reducible. */
    canMinimize: Boolean = true,
    /** Extra head controls, drawn between the title and the five buttons. */
    headTrailing: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val host = LocalWindowFrameHost.current
    val density = LocalDensity.current
    val latestClose by rememberUpdatedState(onClose)
    val latestTitle by rememberUpdatedState(title)
    DisposableEffect(host, state.id, claimsKeyboard) {
        host?.register(
            WindowFrameHost.Registration(state.id, latestTitle, state, claimsKeyboard) { latestClose() },
        )
        // A window that answers keystrokes takes the keyboard the moment it OPENS — the press that opened
        // it landed in the tree, so nothing else would hand it over, and PRD §4's "type a letter to rename"
        // would fire behind it. Only on opening: the title is deliberately not a key of this effect, so a
        // window re-titled while it stands there does not snatch the keyboard back.
        if (claimsKeyboard) host?.focus(state.id)
        onDispose { host?.unregister(state.id) }
    }
    SideEffect { host?.retitle(state.id, title) }

    // The head's measured height, for the clamp that keeps it reachable. Written from the layout phase and
    // read from another layout pass, so deliberately not Compose state — nothing should recompose on it.
    val headHeight = remember(state.id) { FloatArray(1) }
    val widthDp = with(density) { state.size.width.takeIf { it > 0f }?.toDp() } ?: defaultWidth
    val heightDp = with(density) { state.size.height.takeIf { it > 0f }?.toDp() } ?: defaultHeight
    val commit = { onGeometryChange(state.offset, state.size) }

    Box(
        modifier = modifier
            // Where this window is in the app's one stacking order — the frame's own business, never the
            // caller's ([windowStackZ]).
            .windowStackZ(state.id)
            // Reduced: still composed (a half-typed edit survives being put down and picked up again), but
            // measured into nothing and never placed, so it neither draws nor takes a press.
            .unplaced(state.minimized)
            .offset { IntOffset(state.appliedOffset.x.roundToInt(), state.appliedOffset.y.roundToInt()) }
            // requiredWidth/Height (not width/height) so a window keeps its own size instead of adapting to
            // the content area when that is narrower than it; a FILLED axis is exactly the opposite request.
            .then(if (state.fill.fillsWidth) Modifier.fillMaxWidth() else Modifier.requiredWidth(widthDp))
            .then(if (state.fill.fillsHeight) Modifier.fillMaxHeight() else Modifier.requiredHeight(heightDp))
            // Raise AFTER the offset so the hit region tracks the (possibly dragged) window.
            .raiseOnPress {
                host?.focus(state.id)
                onRaise()
            }
            // Measure the window and the content area around it, so a window can never be left with its
            // head — and its five buttons — off the top or the bottom of the app.
            .onGloballyPositioned { coordinates ->
                state.clampVertical(
                    containerHeight = coordinates.parentLayoutCoordinates?.size?.height?.toFloat() ?: 0f,
                    windowHeight = coordinates.size.height.toFloat(),
                    headHeight = headHeight[0],
                )
            },
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 10.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier
                .fillMaxSize()
                .onWindowFrameMeasured(state),
        ) {
            Column(Modifier.fillMaxSize()) {
                WindowHead(
                    title = title,
                    state = state,
                    onClose = onClose,
                    canMinimize = canMinimize,
                    onCommit = commit,
                    onHeadHeight = { headHeight[0] = it },
                    headTrailing = headTrailing,
                )
                Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
                content()
            }
        }

        // The three resizable edges. A filled axis offers none: the window is pinned to the container on
        // it, so there is nothing an edge could do but fight the fill.
        if (!state.fill.fillsWidth) {
            ResizeEdge(
                modifier = Modifier.align(Alignment.CenterStart).fillMaxHeight().width(RESIZE_EDGE_THICKNESS),
                icon = horizontalResizePointerIcon(),
                onCommit = commit,
            ) { delta -> state.resizeLeftBy(delta.x) }
            ResizeEdge(
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(RESIZE_EDGE_THICKNESS),
                icon = horizontalResizePointerIcon(),
                onCommit = commit,
            ) { delta -> state.resizeRightBy(delta.x) }
        }
        if (!state.fill.fillsHeight) {
            ResizeEdge(
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(RESIZE_EDGE_THICKNESS),
                icon = verticalResizePointerIcon(),
                onCommit = commit,
            ) { delta -> state.resizeBottomBy(delta.y) }
        }
    }
}

/**
 * The head: the drag handle, the title, and the five buttons — fill width, fill height, reduce, maximize,
 * close. Their order is fixed here rather than per window, so every window's close button is under the
 * same pixel.
 */
@Composable
private fun WindowHead(
    title: String,
    state: WindowFrameState,
    onClose: () -> Unit,
    canMinimize: Boolean,
    onCommit: () -> Unit,
    onHeadHeight: (Float) -> Unit,
    headTrailing: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .onSizeChanged { onHeadHeight(it.height.toFloat()) }
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .windowHeadGestures(
                onDrag = { state.moveBy(it) },
                onDragEnd = onCommit,
                onDoubleClick = {
                    state.toggleMaximize()
                    onCommit()
                },
            )
            .padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        headTrailing()
        WindowHeadButton("↔", "Fill the width") {
            state.setFillWidth(!state.fill.fillsWidth)
            onCommit()
        }
        WindowHeadButton("↕", "Fill the height") {
            state.setFillHeight(!state.fill.fillsHeight)
            onCommit()
        }
        if (canMinimize) WindowHeadButton("—", "Reduce") { state.minimize() }
        WindowHeadButton(if (state.maximized) "❐" else "▢", "Maximize") {
            state.toggleMaximize()
            onCommit()
        }
        WindowHeadButton("✕", "Close", onClose)
    }
}

/**
 * One head button. [label] is what it does, carried for accessibility rather than drawn: the head has five
 * of them and only the glyph fits.
 */
@Composable
private fun WindowHeadButton(glyph: String, label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(HEAD_BUTTON_SIZE)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = glyph,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** One resizable edge. The drag is consumed, so it never also scrolls or selects what is under it. */
@Composable
private fun ResizeEdge(
    modifier: Modifier,
    icon: PointerIcon,
    onCommit: () -> Unit,
    onDrag: (Offset) -> Unit,
) {
    val latestDrag by rememberUpdatedState(onDrag)
    val latestCommit by rememberUpdatedState(onCommit)
    Box(
        modifier = modifier
            .pointerHoverIcon(icon)
            .pointerInput(Unit) {
                detectDragGestures(onDragEnd = { latestCommit() }) { change, delta ->
                    change.consume()
                    latestDrag(delta)
                }
            },
    )
}

/**
 * The head's own gesture: a drag moves the window, and two presses inside [HEAD_DOUBLE_CLICK_MILLIS]
 * maximize it (or put it back).
 *
 * One gesture detector, not a drag one beside a tap one: two `pointerInput`s on the same head race for the
 * press, and whichever wins it decides whether the other ever sees the gesture. Here the press is only
 * counted as a click once the slop has been awaited and NOT crossed — i.e. once it is known not to be a
 * drag — so a slow drag can never be read as a double-click.
 */
private fun Modifier.windowHeadGestures(
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDoubleClick: () -> Unit,
): Modifier = pointerInput(Unit) {
    var previousPressMillis = 0L
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = true)
        var overSlop = Offset.Zero
        // Named `slopCrossing`, not `drag`: a local of that name would shadow the `drag(...)` gesture
        // function called just below.
        var slopCrossing: PointerInputChange?
        do {
            slopCrossing = awaitTouchSlopOrCancellation(down.id) { change, over ->
                change.consume()
                overSlop = over
            }
        } while (slopCrossing != null && !slopCrossing.isConsumed)
        if (slopCrossing != null) {
            // A drag: it is not a click at all, so it also breaks any double-click in progress.
            previousPressMillis = 0L
            onDrag(overSlop)
            val completed = drag(slopCrossing.id) { change ->
                onDrag(change.positionChange())
                change.consume()
            }
            if (completed) onDragEnd()
            return@awaitEachGesture
        }
        val pressMillis = down.uptimeMillis
        if (previousPressMillis != 0L && pressMillis - previousPressMillis <= HEAD_DOUBLE_CLICK_MILLIS) {
            previousPressMillis = 0L
            onDoubleClick()
        } else {
            previousPressMillis = pressMillis
        }
    }
}

/** Publishes the card's measured size to the state, so the resize edges have numbers to work from. */
private fun Modifier.onWindowFrameMeasured(state: WindowFrameState): Modifier =
    layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        state.measured(Size(placeable.width.toFloat(), placeable.height.toFloat()))
        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }

/**
 * Measures the window but never places it — what "reduced" is. The alternative, not composing it at all,
 * throws away everything half-typed in it, which is not what pressing *reduce* asks for.
 */
private fun Modifier.unplaced(active: Boolean): Modifier =
    if (!active) {
        this
    } else {
        this.layout { measurable, constraints ->
            measurable.measure(constraints)
            layout(0, 0) {}
        }
    }

/**
 * The bar of reduced windows along the bottom of the app. Drawn at the app ROOT, over the lateral menu:
 * a window reduced while the menu is open must not be filed behind it.
 *
 * Each chip restores its window; its ✕ closes it outright, so a window put down here is not a window the
 * user has to bring back before they can be rid of it.
 */
@Composable
fun MinimizedWindowBar(host: WindowFrameHost, modifier: Modifier = Modifier) {
    val rows = host.minimizedWindows
    if (rows.isEmpty()) return
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shadowElevation = 12.dp,
        modifier = modifier.fillMaxWidth().height(MINIMIZED_BAR_HEIGHT),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (row in rows) MinimizedChip(row, host)
        }
    }
}

@Composable
private fun MinimizedChip(row: WindowFrameHost.Registration, host: WindowFrameHost) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.padding(start = 10.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = row.title,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // Picking a window back up puts it on top: it is the window the user has just asked for,
                // exactly as when it was opened.
                // …and into the FOCUS, like every other way of asking for a window that is already
                // open ([WindowFrameHost.present]): raising alone left the app believing the user was
                // still in whatever they had focused before, so a window that answers keystrokes came
                // back from the bar without its keyboard.
                modifier = Modifier.clickable { host.present(row.id) },
            )
            Box(
                modifier = Modifier.size(20.dp).clip(CircleShape).clickable { row.onClose() },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "✕",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
