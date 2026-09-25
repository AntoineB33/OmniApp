package org.example.project.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.geometry.Rect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
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
 *  - **it is resized by any edge or corner** — the top ones kept inside the head's top padding, so they never
 *    cover a head button, and growing upward stopping at the content area's top, so the head stays reachable;
 *  - **reduced windows go to the bar along the bottom of the app** ([WindowBar]), which is drawn
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
    /**
     * The chrome state the window was last left in ([WindowChromeMemory]), or null for none. [initialOffset]
     * and [initialSize] are then its NORMAL geometry, which is what un-filling an axis goes back to.
     */
    initialChrome: WindowChrome? = null,
) {
    var offset: Offset by mutableStateOf(initialOffset)
        private set

    var size: Size by mutableStateOf(initialSize)
        private set

    var fill: WindowFill by mutableStateOf(initialChrome?.fill ?: WindowFill.None)
        private set

    var minimized: Boolean by mutableStateOf(initialChrome?.minimized ?: false)
        private set

    /** What [WindowChromeMemory] keeps of this window: its filled axes, and whether it is reduced. */
    val chrome: WindowChrome get() = WindowChrome(fill, minimized)

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
     * The TOP edge dragged by [dy] px: the bottom edge stays, so the centre moves by half of it — the bottom
     * edge's mirror. Growing upward stops at the top of the content area (the height [clampVertical] last
     * saw), never beyond: past it the head would leave the screen, and the clamp would then push the whole
     * window down — the bottom edge moving instead of the top.
     */
    fun resizeTopBy(dy: Float) {
        if (fill.fillsHeight) return
        var height = (size.height - dy).coerceAtLeast(minHeight)
        if (containerHeight > 0f) {
            val top = (containerHeight - size.height) / 2f + offset.y
            height = height.coerceAtMost(size.height + top.coerceAtLeast(0f))
        }
        val applied = size.height - height
        if (applied == 0f) return
        setNormalHeight(height)
        setNormalOffsetY(offset.y + applied / 2f)
    }

    /** The content area's height as [clampVertical] last saw it — what bounds [resizeTopBy]. */
    private var containerHeight: Float = 0f

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
        if (containerHeight > 0f) this.containerHeight = containerHeight
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
        /**
         * What a lateral-menu button made from this window's ☆ names it by: the frame id of a lateral-menu
         * window, the [WindowInstance.menuKey] of a per-object one, null for a window no button can reopen.
         */
        menuKey: String?,
        val onClose: () -> Unit,
    ) {
        /** Observable for the reason [title] is: a per-object window moves on to another object while it stands. */
        var menuKey: String? by mutableStateOf(menuKey)

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

    /** The window's ☆ key changed (it moved on to another object) — see [Registration.menuKey]. */
    fun rekey(id: String, menuKey: String?) {
        entries.firstOrNull { it.id == id }?.menuKey = menuKey
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
 * A window [WindowChromeMemory] knows comes back filled and reduced as it was left.
 */
@Composable
fun rememberWindowFrameState(
    id: String,
    initialOffset: Offset = Offset.Zero,
    initialSize: Size = Size.Zero,
    /** How the window opens when nothing was kept for it — the task tree's maximized first opening. */
    defaultChrome: WindowChrome? = null,
): WindowFrameState {
    val memory = LocalWindowChromeMemory.current
    // A COPY of a window ([LocalWindowInstance]) is the same window under an id of its own, placed where its
    // copy says, never where the original's caller says.
    val instance = LocalWindowInstance.current
    val fullId = id + (instance?.suffix ?: "")
    val offset = instance?.copy?.initialOffset ?: initialOffset
    val size = instance?.copy?.initialSize ?: initialSize
    return remember(fullId) {
        WindowFrameState(fullId, offset, size, initialChrome = memory?.saved(fullId) ?: defaultChrome)
    }
}

/**
 * Which copy of a window is being composed — the head's **duplicate** button (`docs/invariants/popups.md`,
 * *Duplicating a window*). Provided by whoever opens the window: `App` for the lateral-menu windows and the
 * per-object ones ([ObjectWindowsHost]).
 *
 * [suffix] makes every frame id composed under it unique ("" for the original, `#2`, `#3`… for copies), so a
 * copy has a place of its own in the stacking order, the reduce bar and the chrome memory — the windows nested
 * in a copy included, which inherit it. [onDuplicate] is what the head's button does; null = no button. [copy]
 * is non-null for a copy, and replaces what the ORIGINAL's caller wired to the frame: the copy's placement, and
 * its close / geometry / raise, which would otherwise close, move or raise the original.
 *
 * [menuKey] is what the head's ☆ makes a button for, for a per-object window ([ObjectWindows]): the window kind
 * and its object, which `App` reopens it from. Null = the frame id itself, which `App` can reopen only for a
 * lateral-menu window. [presentRequests] counts the times the window was asked for again while open: each one
 * brings it back ([WindowFrameHost.present]).
 */
class WindowInstance(
    val suffix: String,
    val onDuplicate: (() -> Unit)?,
    val copy: WindowCopy?,
    val menuKey: String? = null,
    val presentRequests: Int = 0,
)

/**
 * PRD §7: the head's ☆ — a button for THIS window (the copy, for a copy) at the bottom of the lateral menu. `App`
 * answers which frame ids it can reopen from a button ([canAdd]: the lateral-menu windows and their copies, the
 * ones it opens by id) and makes the button ([add], handed the window's key and its title as its first name). A
 * per-object window names itself by its [WindowInstance.menuKey] instead, and needs no [canAdd]: its
 * [ObjectWindows] made the key because `App` can reopen it. One host for every window, so no window has to be
 * wired for it and none can be forgotten.
 */
class MenuButtonHost(val canAdd: (frameId: String) -> Boolean, val add: (key: String, title: String) -> Unit)

val LocalMenuButtonHost = staticCompositionLocalOf<MenuButtonHost?> { null }

/**
 * A copy's own frame wiring — see [WindowInstance.copy]. [number] is put after the title (`Search (2)`) — null
 * for a per-object window that is no one's copy, only one of several open on different objects.
 */
class WindowCopy(
    val initialOffset: Offset,
    val initialSize: Size,
    val onClose: () -> Unit,
    val onGeometryChange: (Offset, Size) -> Unit = { _, _ -> },
    val onRaise: () -> Unit = {},
    val number: Int? = null,
)

val LocalWindowInstance = compositionLocalOf<WindowInstance?> { null }

/** [base] as the window composed here names it — with the copy's suffix, like its frame id. */
@Composable
fun windowInstanceId(base: String): String = base + (LocalWindowInstance.current?.suffix ?: "")

/**
 * A companion window drawn beside its window, in the same wrapper (the History row info, a task tree's
 * detail): it keeps the id suffix of the window it belongs to, but none of that window's copy wiring — its
 * close is its own — and it has no duplicate button: duplicating the window duplicates the pair.
 */
@Composable
fun CompanionWindowScope(content: @Composable () -> Unit) {
    val suffix = LocalWindowInstance.current?.suffix ?: ""
    CompositionLocalProvider(LocalWindowInstance provides WindowInstance(suffix, null, null), content = content)
}

/**
 * The open windows of ONE kind of per-object window — the edit window of a task, of a category, of an alarm… —
 * each about one object (PRD §7, `popups.md`). **Opening one on another object opens a second window**: the
 * one already open stays. Asking again for an object whose window is open brings that window back instead of
 * opening a second on it ([open]); the head's ⧉ is the one way to have two on the same object.
 *
 * Plain state, held by `App` (`remember`) and drawn by [ObjectWindowsHost]. Every window persists nothing: they
 * live for the session. [menuKeyOf] is the ☆'s key for a window on that object (the kind and the object's id,
 * which `App` reopens it from), or null for a kind no button can reopen — a window about something transient.
 */
@Stable
class ObjectWindows<T : Any>(private val menuKeyOf: ((T) -> String?)? = null) {
    /** One open window. [subject] follows the window when it moves on to another object ([retarget]). */
    inner class Window internal constructor(val number: Int, subject: T, internal val duplicated: Boolean) {
        var subject: T by mutableStateOf(subject)
            private set
        internal var presentRequests: Int by mutableIntStateOf(0)

        fun close() {
            windows.remove(this)
        }

        /** The window now shows [to] (an alarm window's "+ New" moved it on): what [open] and the ☆ now name. */
        fun retarget(to: T) {
            subject = to
        }

        internal val menuKey: String? get() = menuKeyOf?.invoke(subject)
    }

    private val windows = mutableStateListOf<Window>()
    private var next = 1

    internal val open: List<Window> get() = windows

    /** The objects the open windows are about, in the order they were opened. */
    val subjects: List<T> get() = windows.map { it.subject }

    /** Open a window on [subject], or bring back the one already open on it. */
    fun open(subject: T) {
        val existing = windows.firstOrNull { it.subject == subject }
        if (existing != null) existing.presentRequests++ else windows.add(Window(next++, subject, duplicated = false))
    }

    /** Close every window on an object [which] names — every window, by default. */
    fun closeAll(which: (T) -> Boolean = { true }) {
        windows.removeAll { which(it.subject) }
    }

    internal fun duplicate(of: Window) {
        windows.add(Window(next++, of.subject, duplicated = true))
    }
}

/**
 * Draws every open window of [windows], each under a frame id of its own (`TaskEdit#3`) so it has its own place
 * in the stacking order and the reduce bar, cascaded off the centre so two do not sit exactly over each other.
 * [window] draws one; it must close through [ObjectWindows.Window.close] (its Save, bin and ✕), and read its
 * object off [ObjectWindows.Window.subject].
 */
@Composable
fun <T : Any> ObjectWindowsHost(
    windows: ObjectWindows<T>,
    window: @Composable (ObjectWindows<T>.Window) -> Unit,
) {
    val parentSuffix = LocalWindowInstance.current?.suffix ?: ""
    for (w in windows.open.toList()) {
        key(w.number) {
            val cascade = COPY_CASCADE_PX * ((w.number - 1) % 6)
            CompositionLocalProvider(
                LocalWindowInstance provides WindowInstance(
                    suffix = "$parentSuffix#${w.number}",
                    onDuplicate = { windows.duplicate(w) },
                    copy = WindowCopy(
                        Offset(cascade, cascade),
                        Size.Zero,
                        onClose = { w.close() },
                        number = w.number.takeIf { w.duplicated },
                    ),
                    menuKey = w.menuKey,
                    presentRequests = w.presentRequests,
                ),
            ) { window(w) }
        }
    }
}

/** How far each copy is set off the one before it. */
const val COPY_CASCADE_PX: Float = 32f

/** A window's chrome state: which axes it fills (both = maximized), and whether it is reduced to the bar. */
data class WindowChrome(val fill: WindowFill, val minimized: Boolean)

/**
 * Where a window's [WindowChrome] is kept LOCALLY between one opening and the next, and across restarts —
 * local-only view state, never synced (`docs/invariants/popups.md`). `App` provides it for the lateral-menu
 * windows, whose placement it already persists; a window it does not know (a per-object window) gets null
 * from [saved] and has [save] ignored, so it opens at its normal size, as it always did.
 */
interface WindowChromeMemory {
    fun saved(id: String): WindowChrome?

    fun save(id: String, chrome: WindowChrome)
}

val LocalWindowChromeMemory = staticCompositionLocalOf<WindowChromeMemory?> { null }

/**
 * Where something drawn over every window stands, in ROOT coordinates — the lateral menu's collapse toggle. A
 * window head that runs under it moves its title out from under it ([WindowHead]), live, as the window is moved
 * or resized, and back when it no longer does. Null = nothing stands over the heads.
 */
val LocalHeadObstacle = staticCompositionLocalOf<State<Rect?>?> { null }

/** Height reserved for [WindowBar]; `App` insets the content area by it while it has rows. */
val MINIMIZED_BAR_HEIGHT: Dp = 38.dp

/** How close two presses on the head must be to count as the double-click that maximizes it. */
private const val HEAD_DOUBLE_CLICK_MILLIS: Long = 350

private val HEAD_BUTTON_SIZE: Dp = 24.dp

/** Where a head's title starts, from the window's left edge. */
private val HEAD_TITLE_START: Dp = 14.dp
private val RESIZE_EDGE_THICKNESS: Dp = 6.dp

/** The square each resizable corner takes — larger than the edges' thickness, so a corner is easy to hit. */
private val RESIZE_CORNER_SIZE: Dp = 14.dp

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
    // A copy closes, moves and raises ITSELF — the caller's wiring is the original's ([WindowInstance.copy]).
    val instance = LocalWindowInstance.current
    val copy = instance?.copy
    val onClose = copy?.onClose ?: onClose
    val onGeometryChange = copy?.onGeometryChange ?: onGeometryChange
    val onRaise = copy?.onRaise ?: onRaise
    val title = copy?.number?.let { "$title ($it)" } ?: title
    // The ☆'s key: a per-object window's own, else the frame id where `App` can reopen the window by it.
    val menuButtons = LocalMenuButtonHost.current
    val menuKey = instance?.menuKey ?: state.id.takeIf { id -> menuButtons?.canAdd?.invoke(id) == true }
    val latestClose by rememberUpdatedState(onClose)
    val latestTitle by rememberUpdatedState(title)
    DisposableEffect(host, state.id, claimsKeyboard) {
        host?.register(
            WindowFrameHost.Registration(state.id, latestTitle, state, claimsKeyboard, menuKey) { latestClose() },
        )
        // A window that answers keystrokes takes the keyboard the moment it OPENS — the press that opened
        // it landed in the tree, so nothing else would hand it over, and PRD §4's "type a letter to rename"
        // would fire behind it. Only on opening: the title is deliberately not a key of this effect, so a
        // window re-titled while it stands there does not snatch the keyboard back.
        if (claimsKeyboard) host?.focus(state.id)
        onDispose { host?.unregister(state.id) }
    }
    SideEffect {
        host?.retitle(state.id, title)
        host?.rekey(state.id, menuKey)
    }
    // Asked for again while open (a per-object window re-opened on its object): it comes back to the user.
    val presentRequests = instance?.presentRequests ?: 0
    LaunchedEffect(host, presentRequests) { if (presentRequests > 0) host?.present(state.id) }
    val headObstacle = LocalHeadObstacle.current?.value
    // Every change of the chrome state is kept at once — the buttons, the head's double-click, the reduce
    // bar's chip — so the window comes back as it was left, whatever closes it or the app.
    val chromeMemory = LocalWindowChromeMemory.current
    LaunchedEffect(chromeMemory, state, state.fill, state.minimized) { chromeMemory?.save(state.id, state.chrome) }

    // The head's measured height, for the clamp that keeps it reachable. Written from the layout phase and
    // read from another layout pass, so deliberately not Compose state — nothing should recompose on it.
    val headHeight = remember(state.id) { FloatArray(1) }
    val widthDp = with(density) { state.size.width.takeIf { it > 0f }?.toDp() } ?: defaultWidth
    val heightDp = with(density) { state.size.height.takeIf { it > 0f }?.toDp() } ?: defaultHeight
    val commit = { onGeometryChange(state.offset, state.size) }

    Box(
        modifier = Modifier
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
            // THE CALLER'S MODIFIER GOES INSIDE THE OFFSET AND THE SIZE, never around them. `offset`
            // reports its child's size *at its own position* and merely places the child elsewhere, so
            // anything hung outside it keeps the bounds the window would have had undragged: a hit region
            // at the centre of the content area that nothing draws, standing at this window's z over every
            // window behind it. The Reminders window's "a press on bare chrome leaves Edit mode"
            // `detectTapGestures` was exactly that, and it ate every press aimed at the window underneath
            // (the Alarms window could not be brought forward at all, 2026-09-21). `align` is parent data
            // and is read from anywhere in the chain, so nothing is lost by moving the caller down here.
            .then(modifier)
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
                    obstacle = headObstacle,
                    onClose = onClose,
                    onDuplicate = instance?.onDuplicate,
                    onAddToMenu = menuKey?.let { key -> menuButtons?.let { { title: String -> it.add(key, title) } } },
                    canMinimize = canMinimize,
                    onCommit = commit,
                    onHeadHeight = { headHeight[0] = it },
                    headTrailing = headTrailing,
                )
                Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
                // What is drawn inside keeps the suffix (a window nested in a copy is that copy's) but not the
                // copy's wiring or its button, which are this frame's alone.
                CompositionLocalProvider(
                    LocalWindowInstance provides instance?.let { WindowInstance(it.suffix, null, null) },
                ) { content() }
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
        // The TOP edge. Only as thick as the head's own top padding, so it never covers a head button (the
        // head is still the drag handle everywhere below it); growing upward stops at the content area's top
        // ([WindowFrameState.resizeTopBy]), so the head stays reachable.
        if (!state.fill.fillsHeight) {
            ResizeEdge(
                modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().height(RESIZE_EDGE_THICKNESS),
                icon = verticalResizePointerIcon(),
                onCommit = commit,
            ) { delta -> state.resizeTopBy(delta.y) }
        }
        // The four corners resize both axes at once, under the oblique double arrow. Drawn after the edges so
        // they win the square the edges overlap in; offered only while neither axis is filled (a filled axis
        // has nothing to resize, and the remaining edge already does the other). The TOP two are a strip as
        // thin as the top edge rather than a square: a square there would cover the head's ✕.
        if (!state.fill.fillsWidth && !state.fill.fillsHeight) {
            ResizeEdge(
                modifier = Modifier.align(Alignment.TopStart).width(RESIZE_CORNER_SIZE).height(RESIZE_EDGE_THICKNESS),
                icon = diagonalResizePointerIcon(bottomRight = true),
                onCommit = commit,
            ) { delta ->
                state.resizeLeftBy(delta.x)
                state.resizeTopBy(delta.y)
            }
            ResizeEdge(
                modifier = Modifier.align(Alignment.TopEnd).width(RESIZE_CORNER_SIZE).height(RESIZE_EDGE_THICKNESS),
                icon = diagonalResizePointerIcon(bottomRight = false),
                onCommit = commit,
            ) { delta ->
                state.resizeRightBy(delta.x)
                state.resizeTopBy(delta.y)
            }
            ResizeEdge(
                modifier = Modifier.align(Alignment.BottomStart).size(RESIZE_CORNER_SIZE),
                icon = diagonalResizePointerIcon(bottomRight = false),
                onCommit = commit,
            ) { delta ->
                state.resizeLeftBy(delta.x)
                state.resizeBottomBy(delta.y)
            }
            ResizeEdge(
                modifier = Modifier.align(Alignment.BottomEnd).size(RESIZE_CORNER_SIZE),
                icon = diagonalResizePointerIcon(bottomRight = true),
                onCommit = commit,
            ) { delta ->
                state.resizeRightBy(delta.x)
                state.resizeBottomBy(delta.y)
            }
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
    /** [LocalHeadObstacle]'s bounds, in root coordinates. */
    obstacle: Rect?,
    onClose: () -> Unit,
    /** The duplicate button, left of the five; null = none. */
    onDuplicate: (() -> Unit)?,
    /** The ☆ left of the duplicate button: a lateral-menu button for this window, named [title]; null = none. */
    onAddToMenu: ((String) -> Unit)?,
    canMinimize: Boolean,
    onCommit: () -> Unit,
    onHeadHeight: (Float) -> Unit,
    headTrailing: @Composable RowScope.() -> Unit,
) {
    // Where the title must START so none of it is hidden: the head's first point that is both visible (a window
    // dragged past the content area's edge is cut there) and not under the obstacle, plus a small gap. The title
    // then takes everything from there to the buttons.
    //
    // Measured against the head's REAL left edge ([headLeft], unclipped): the visible bounds ([headVisible]) stop
    // at the content area's edge, which is exactly where the toggle stands — measured from those, the shift came
    // out as the toggle's width minus the title margin, a nudge that left the title under the toggle.
    //
    // Worked out whenever the head moves (a drag, a resize, a fill) and whenever the obstacle does (the menu
    // folding away moves the toggle); only the result is state, so a drag recomposes the head only when it changes.
    val density = LocalDensity.current
    val titleStartPx = with(density) { HEAD_TITLE_START.toPx() }
    val gapPx = with(density) { 6.dp.toPx() }
    val headVisible = remember { arrayOfNulls<Rect>(1) }
    val headLeft = remember { FloatArray(1) }
    var titleShiftPx by remember { mutableStateOf(0f) }
    val latestObstacle by rememberUpdatedState(obstacle)
    fun updateShift() {
        val visible = headVisible[0] ?: return
        val left = headLeft[0]
        var start = left + titleStartPx
        // Cut by the content area's edge: start at the first visible point.
        if (visible.left > left + 0.5f) start = maxOf(start, visible.left + gapPx)
        // Under the obstacle: start past it.
        val o = latestObstacle
        if (o != null && o.bottom > visible.top && o.top < visible.bottom && o.right > visible.left && o.left < visible.right) {
            start = maxOf(start, o.right + gapPx)
        }
        val shift = (start - (left + titleStartPx)).coerceAtLeast(0f)
        if (shift != titleShiftPx) titleShiftPx = shift
    }
    LaunchedEffect(obstacle) { updateShift() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .onSizeChanged { onHeadHeight(it.height.toFloat()) }
            .onGloballyPositioned {
                headVisible[0] = it.boundsInRoot()
                headLeft[0] = it.positionInRoot().x
                updateShift()
            }
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .windowHeadGestures(
                onDrag = { state.moveBy(it) },
                onDragEnd = onCommit,
                onDoubleClick = {
                    state.toggleMaximize()
                    onCommit()
                },
            )
            // `end` clears the right resize edge and `top` the top one, so neither covers a head button.
            .padding(start = HEAD_TITLE_START, end = RESIZE_EDGE_THICKNESS + 2.dp, top = RESIZE_EDGE_THICKNESS, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = with(density) { titleShiftPx.toDp() }),
        )
        headTrailing()
        onAddToMenu?.let { WindowHeadButton("☆", "Add a button for this window to the left menu") { it(title) } }
        onDuplicate?.let { WindowHeadButton("⧉", "Duplicate", it) }
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
 * press, and whichever wins it decides whether the other ever sees the gesture.
 *
 * The window follows the pointer from its FIRST movement — there is no dead zone around the press. The touch
 * slop only decides, once the pointer is up, whether the press was a click: one that travelled past it was a
 * drag, so a slow drag can never be read as a double-click. The head takes an **unconsumed** press only, so
 * its buttons (which consume theirs) never move the window; nothing else interactive belongs in the head.
 */
private fun Modifier.windowHeadGestures(
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDoubleClick: () -> Unit,
): Modifier = pointerInput(Unit) {
    var previousPressMillis = 0L
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = true)
        var travelled = Offset.Zero
        var moved = false
        val completed = drag(down.id) { change ->
            val delta = change.positionChange()
            travelled += delta
            if (delta != Offset.Zero) {
                moved = true
                onDrag(delta)
            }
            change.consume()
        }
        if (moved && completed) onDragEnd()
        if (!completed) return@awaitEachGesture
        if (travelled.getDistance() > viewConfiguration.touchSlop) {
            // A drag: it is not a click at all, so it also breaks any double-click in progress.
            previousPressMillis = 0L
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
 * The app's **window bar** along the bottom — its system tray. It appears whenever a window is open and has a
 * TAB for each one, the reduced ones included, in the order they were opened; drawn at the app ROOT, over the
 * lateral menu, so a window reduced while the menu is open is not filed behind it.
 *
 * A tab brings its window back — out of the bar, to the top, into the focus ([WindowFrameHost.present]); its ✕
 * closes it outright. **Close all**, at the bar's right corner, closes every window at once (it replaced the
 * lateral menu's "Close windows").
 */
@Composable
fun WindowBar(host: WindowFrameHost, modifier: Modifier = Modifier) {
    val rows = host.registrations
    if (rows.isEmpty()) return
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shadowElevation = 12.dp,
        modifier = modifier.fillMaxWidth().height(MINIMIZED_BAR_HEIGHT),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (row in rows.toList()) MinimizedChip(row, host)
            }
            Text(
                text = "✕ Close all",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(horizontal = 6.dp)
                    .clip(RoundedCornerShape(8.dp))
                    // Over a snapshot: each close takes its window out of the list being walked.
                    .clickable { host.registrations.toList().forEach { it.onClose() } }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun MinimizedChip(row: WindowFrameHost.Registration, host: WindowFrameHost) {
    // A reduced window's tab is set back, so the bar tells at a glance which windows are on screen.
    val reduced = row.state.minimized
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (reduced) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface,
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
                fontStyle = if (reduced) FontStyle.Italic else FontStyle.Normal,
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
