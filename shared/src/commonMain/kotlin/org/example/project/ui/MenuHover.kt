package org.example.project.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * User rule 2026-11-04: **a hover pop-up that vanishes once the pointer has left it and all of its descendants** — the
 * configurations tree's. Each part of it (the arrow that opened it, each of its pop-ups, a menu opened from inside one)
 * says when the pointer enters and leaves it ([reportsHover]); once none holds the pointer for [GRACE_MILLIS], [onLeft].
 * The grace is the pointer crossing from one part to the next, which leaves the first before it enters the second.
 * Event-driven: one wait per time the pointer leaves the last part, cancelled by the next entry, never a poll.
 */
class MenuHoverTracker(
    private val scope: CoroutineScope,
    /**
     * The pop-up this one is a branch of (a child pop-up of the configurations tree), or null: each part of this one is
     * a part of [parent] too, so the pointer in the branch holds the whole pop-up.
     */
    private val parent: MenuHoverTracker? = null,
    private val onLeft: () -> Unit,
) {
    private val inside = HashSet<Any>()
    private var pending: Job? = null

    /** Whether the pointer is over some part now. */
    val held: Boolean get() = inside.isNotEmpty()

    /**
     * User rule 2026-11-11: *"Clicking on the background of a window that is supposed to disappear when hovering outside
     * must make the window stay open until the user brings the focus on another window that is not in its
     * descendants."* — a press in the pop-up pins it, and every pop-up it is a branch of: the pointer leaving no longer
     * closes them. What closes them then is the keyboard going to a window outside the tree (the menu's own dismissal).
     */
    var pinned: Boolean = false
        private set

    /** Pinned ([pinned]), it and its ancestors. A wait already running for the pointer gone is dropped. */
    fun pin() {
        parent?.pin()
        pinned = true
        pending?.cancel()
        pending = null
    }

    /** The pop-up closed: the next opening is a hover pop-up again, until a press pins it. */
    fun unpin() {
        pinned = false
        if (focused === this) focused = null
        if (walkingTo === this) walkingTo = null
    }

    /**
     * Set as a press outside closed the pop-up while pinned ([windowLeft]), until the pointer next enters a part of it:
     * the press that closed it, landing on the button that opens it, must not open it again ([takeClosedPinned]).
     */
    private var closedPinned = false

    /** Whether the press being answered is the one that just closed the pop-up, pinned; asked once. */
    fun takeClosedPinned(): Boolean = closedPinned.also { closedPinned = false }

    /**
     * The windows the pop-up is drawn in, as the platform knows them (the desktop's menu windows: its own, and each menu
     * opened inside it that reports to this tracker) — what "the keyboard left it" is read against ([windowLeft]).
     */
    val windows = HashSet<Any>()

    /**
     * User rule 2026-11-14: **the keyboard left the pop-up for a window that is none of its own nor of its
     * descendants** — a click or a right-click *"on anything in the app than the currently focused window and its
     * descendant ones"*. Pinned, it and every pop-up it is a branch of disappear; not pinned, nothing more than before
     * (its own menu leaves, its ancestors stay). A walk through the positions that is bringing the keyboard to one of
     * its ancestors ([takeKeyboard]) closes what stands below that ancestor alone.
     */
    fun windowLeft() {
        if (focused === this) focused = null
        if (!pinned) return
        val target = walkingTo
        var at: MenuHoverTracker? = this
        while (at != null && at !== target) {
            val above = at.parent
            at.pinned = false
            at.closedPinned = true
            at.pending?.cancel()
            at.pending = null
            at.onLeft()
            at = above
        }
    }

    /** The keyboard brought to the pop-up's window by the app (a walk through the positions), not by a press. */
    fun takeKeyboard() {
        walkingTo = this
        focusWindow?.invoke()
    }

    /**
     * Brings the keyboard to the pop-up's own window, while it is on screen — given by the platform's menu (the
     * desktop's windows), null where a menu is drawn in the app's window.
     */
    var focusWindow: (() -> Unit)? = null

    /**
     * Gives the keyboard back to the app's window the pop-up hangs from — given by the platform's menu: the position
     * before the first pop-up ([PopupPositions.back]).
     */
    var focusAppWindow: (() -> Unit)? = null

    /** What the pop-up's owner does when the keyboard comes to it ([PopupPositions.focused]). */
    var onFocused: (() -> Unit)? = null

    /** The keyboard came to the pop-up's window — told by the platform's menu. */
    fun windowFocused() {
        focused = this
        walkingTo = null
        onFocused?.invoke()
    }

    fun enter(part: Any) {
        parent?.enter(part)
        closedPinned = false
        inside += part
        pending?.cancel()
        pending = null
    }

    /** [part] left by the pointer, or gone from the screen with the pointer over it. */
    fun exit(part: Any) {
        parent?.exit(part)
        if (!inside.remove(part) || inside.isNotEmpty()) return
        pending?.cancel()
        if (pinned) return
        pending = scope.launch {
            delay(GRACE_MILLIS)
            if (inside.isEmpty() && !pinned) onLeft()
        }
    }

    companion object {
        /**
         * Long enough to cross the seam between two parts (the pointer leaves one and enters the next within a few
         * milliseconds: they touch), short enough to read as "it vanished as I left" — 300 ms was seen to linger
         * (anomaly 2026-11-04).
         */
        const val GRACE_MILLIS: Long = 80

        /** The hover pop-up whose window holds the keyboard, null while none does. One app, one keyboard. */
        var focused: MenuHoverTracker? = null
            private set

        /** The pop-up a walk through the positions is bringing the keyboard to ([takeKeyboard]), until it has it. */
        private var walkingTo: MenuHoverTracker? = null

        /**
         * User rule 2026-11-14: once a press, a right-click or a key pinned the pop-up holding the keyboard, *"any object
         * that is supposed to make a window appear when hovered over won't, except if it is in the currently focused
         * window or one of its descendant windows"*. Whether a hover over something standing in [from]'s pop-up (null:
         * in a window of the app) opens or closes a pop-up: always while nothing is pinned; else only inside the pinned
         * pop-up nearest the keyboard — the focused one, or the one a fresh hover pop-up holding it hangs from.
         */
        fun hoverActs(from: MenuHoverTracker?): Boolean {
            val pinnedOne = generateSequence(focused) { it.parent }.firstOrNull { it.pinned } ?: return true
            return generateSequence(from) { it.parent }.any { it === pinnedOne }
        }
    }
}

/**
 * The tracker the parts of a hover pop-up report to, null elsewhere. A menu opened from inside the pop-up inherits it
 * (the desktop's menus report their window to it), so it counts as a descendant.
 */
val LocalMenuHover = compositionLocalOf<MenuHoverTracker?> { null }

/**
 * This element as one part of [tracker]'s pop-up: the pointer entering and leaving it, it going while held, and a press
 * on it pinning the pop-up ([MenuHoverTracker.pin]) — whatever is pressed, with either button: its background, a control
 * in it, or the button that opens it (user rule 2026-11-14).
 */
fun Modifier.reportsHover(tracker: MenuHoverTracker?): Modifier =
    if (tracker == null) this else composed {
        val part = remember { Any() }
        val latest by rememberUpdatedState(tracker)
        DisposableEffect(Unit) { onDispose { latest.exit(part) } }
        pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    when (awaitPointerEvent().type) {
                        PointerEventType.Enter -> latest.enter(part)
                        PointerEventType.Exit -> latest.exit(part)
                        PointerEventType.Press -> latest.pin()
                        else -> Unit
                    }
                }
            }
        }
    }

/** [MenuHoverTracker] for a pop-up whose composition holds it, a branch of [parent] if any, calling the latest [onLeft]. */
@Composable
fun rememberMenuHoverTracker(parent: MenuHoverTracker? = null, onLeft: () -> Unit): MenuHoverTracker {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val latest by rememberUpdatedState(onLeft)
    val tracker = remember(scope, parent) { MenuHoverTracker(scope, parent) { latest() } }
    // Gone from the composition (the pop-up it hangs from closed): it holds no keyboard and pins nothing any more.
    DisposableEffect(tracker) { onDispose { tracker.unpin() } }
    return tracker
}

/**
 * User rule 2026-11-11: *"ctrl+alt+->/<- does the same but forall windows"*, and *"If a Search window disappears because
 * the user hovered outside, ctrl+alt+<- brings it back, and does like the user clicked on the background"* — **the
 * pop-ups the keyboard went to, and the app's window it went back to, as positions of the app**. Each pop-up the
 * keyboard comes to is a new position ([focused]), and so is the app's window each time the keyboard goes back to it
 * as the pop-ups close ([appFocused]) — and before the first pop-up. Back and forward ([back], [forward]) walk them: a
 * pop-up gets the keyboard back if open, or comes back, pinned, if it closed; the app's window gets the keyboard back,
 * which closes the pop-ups. Hovering over the arrow and out again and again leaves as many pop-up / window pairs, and
 * the walk shows and hides the pop-up as many times (anomaly 2026-11-11: two openings of one pop-up were one position,
 * and the window between them none).
 *
 * They come before the app's own positions (`SchedulerIntent.UndoPosition`): while the app's history stands where it
 * stood after the newest of them ([stampNow], `SchedulerReducer.positionStamp`); past the oldest, the walk goes on in
 * the app's history, and forward comes back through it to them. In this run only; a pop-up that left the screen with what holds it is no position any more ([forget]).
 */
class PopupPositions(private val stampNow: () -> Any?) {
    /** A position: a pop-up ([owner]), or the app's window (no owner). */
    private class Entry(val owner: Any?, val isOpen: () -> Boolean, val bringBack: () -> Unit)

    private object AppWindow

    private val entries = ArrayList<Entry>()
    private var index = -1
    private var stamp: Any? = null

    /** Where a walk is bringing the keyboard: its coming there is no new position. */
    private var expected: Any? = null

    /**
     * The keyboard came to [owner]'s pop-up; [bringBack] brings it back to it (reopened and pinned if it closed), [leave]
     * gives it back to the app's window the pop-up hangs from.
     */
    fun focused(owner: Any, isOpen: () -> Boolean, leave: () -> Unit = {}, bringBack: () -> Unit) {
        if (arrived(owner)) return
        // The first pop-up of a walk: the app's window the keyboard came from stands before it.
        if (entries.isEmpty() || stampNow() != stamp) {
            clear()
            add(Entry(null, { true }, leave))
        }
        add(Entry(owner, isOpen, bringBack))
    }

    /** The keyboard went back to the app's window as the pop-ups closed; [leave] gives it the keyboard again. */
    fun appFocused(leave: () -> Unit) {
        if (arrived(AppWindow)) return
        if (entries.isEmpty() || stampNow() != stamp) return
        add(Entry(null, { true }, leave))
    }

    /** [owner] left the screen with what holds it: nothing of it to bring back. */
    fun forget(owner: Any) {
        var i = entries.size - 1
        while (i >= 0) {
            if (entries[i].owner === owner) {
                entries.removeAt(i)
                if (i <= index) index--
            }
            i--
        }
        index = index.coerceIn(-1, entries.lastIndex)
    }

    /** Back one position: whether there was one (else the app's own positions are walked). */
    fun back(): Boolean {
        expected = null
        if (!current() || index < 0) return false
        val here = entries[index]
        // The pop-up the keyboard is in closed meanwhile: back brings it back first.
        if (here.owner != null && !here.isOpen()) return bring(here)
        if (index == 0) return false
        index--
        return bring(entries[index])
    }

    /** Forward one position: whether there was one. */
    fun forward(): Boolean {
        expected = null
        if (!current() || index >= entries.lastIndex) return false
        index++
        return bring(entries[index])
    }

    private fun add(entry: Entry) {
        while (entries.size > index + 1) entries.removeAt(entries.lastIndex)
        // The same position twice in a row is one.
        if (entries.isNotEmpty() && entries.last().owner === entry.owner) entries.removeAt(entries.lastIndex)
        entries += entry
        index = entries.lastIndex
        stamp = stampNow()
    }

    /** Whether [where] is where a walk is bringing the keyboard — then its coming there is no new position. */
    private fun arrived(where: Any): Boolean {
        if (expected !== where) return false
        expected = null
        return true
    }

    /**
     * Whether these positions are the newest of the app's — its history stands where it stood after the newest of them.
     * Walked elsewhere (back past the oldest, into the app's own positions), they wait, kept: forward redoes the app's
     * history back to where it stood, then goes on through them (anomaly 2026-11-11: thrown away there, Ctrl+Alt+→
     * never showed the pop-up again). A new position recorded meanwhile starts them anew ([focused]).
     */
    private fun current(): Boolean = entries.isNotEmpty() && stampNow() == stamp

    private fun bring(entry: Entry): Boolean {
        expected = entry.owner ?: AppWindow
        entry.bringBack()
        return true
    }

    private fun clear() {
        entries.clear()
        index = -1
        expected = null
    }
}

/** The app's [PopupPositions], null where nothing gives it (a test scene): no pop-up is then a position. */
val LocalPopupPositions = compositionLocalOf<PopupPositions?> { null }

/**
 * What the app does with a key its own windows would have heard, given to the pop-ups: a pop-up is a window of its own
 * holding the keyboard, and the chords that walk every window (user rule 2026-11-11) must work from it too. The key and
 * its modifiers (Ctrl or Cmd, Shift, Alt), as `undoRedoIntentFor` reads them; true when taken. Null where nothing gives
 * it.
 */
val LocalMenuKeyForward = compositionLocalOf<((androidx.compose.ui.input.key.Key, Boolean, Boolean, Boolean) -> Boolean)?> { null }
