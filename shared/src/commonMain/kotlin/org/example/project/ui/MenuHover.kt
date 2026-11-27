package org.example.project.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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
    var pinned: Boolean by androidx.compose.runtime.mutableStateOf(false)
        private set

    /** Pinned ([pinned]), it and its ancestors. A wait already running for the pointer gone is dropped. */
    fun pin() {
        parent?.pin()
        pinned = true
        pending?.cancel()
        pending = null
    }

    /**
     * A click on what opens this pop-up ([opener]: the ▾, a category's row) — whether the pop-up is open after it, to be
     * pinned then by the caller. A hover pop-up is pinned by it; a pinned one is closed by it where the control [toggles]
     * (the ▾), kept where it does not (a row); a quick run of clicks is one ([PopupOpener.click]).
     */
    fun clickedOpener(opener: PopupOpener, isOpen: Boolean, toggles: Boolean): Boolean =
        opener.click(isOpen, closes = toggles && pinned).also {
            org.example.project.scheduler.platform.Diagnostics.log("[popup] hover #${hashCode()} opener clicked: open=$isOpen pinned=$pinned toggles=$toggles -> open=$it")
        }

    /** The pop-up closed: the next opening is a hover pop-up again, until a press pins it. */
    fun unpin() {
        pinned = false
        if (focused === this) focused = null
        if (walkingTo === this) walkingTo = null
    }

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
     * its ancestors ([takeKeyboard]) closes what stands below that ancestor alone — and so does the keyboard going to
     * an ancestor's own window ([goneTo] true of that ancestor's [windows]): anomaly 2026-11-15, *"I go to down arrow
     * then click on 'task', but instead of pinning the two pop-up window, it closes the pop-up window"* — the press on
     * the category's row pinned the row's branch, and the keyboard leaving the branch's pop-up for the row's closed
     * every pop-up up to the tree's root. The row's click opens the branch again, pinned.
     */
    fun windowLeft(goneTo: ((Set<Any>) -> Boolean)? = null) {
        if (focused === this) focused = null
        if (!pinned) return
        // A press on what opens it (the ▾, a category's row) never reaches here: its click answers it ([PopupOpeners]).
        // Anomaly 2026-11-21 (*"in 'Currently on' … I click on the type selector field, then I click again on A, and A
        // disappears"*): a drop-down opened inside the pop-up reports to this tracker, and the press back in the pop-up
        // that closed the drop-down took the keyboard to the pop-up's OWN window — read against its ancestors alone, that
        // was nowhere, and the pop-up closed. Gone to its own window, nothing of it closes.
        val target = walkingTo ?: goneTo?.let { test -> generateSequence(this) { it.parent }.firstOrNull { test(it.windows) } }
        var at: MenuHoverTracker? = this
        while (at != null && at !== target) {
            val above = at.parent
            at.pinned = false
            at.pending?.cancel()
            at.pending = null
            org.example.project.scheduler.platform.Diagnostics.log("[popup] hover #${at.hashCode()} closed: keyboard left (pinned)")
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
     * the pop-ups' tree is walked back to ([PopupFocus.restore]).
     */
    var focusAppWindow: (() -> Unit)? = null

    /** What the pop-up's owner does when the keyboard comes to it ([PopupFocus.focused]). */
    var onFocused: (() -> Unit)? = null

    /** The keyboard came to the pop-up's window — told by the platform's menu. */
    fun windowFocused() {
        focused = this
        walkingTo = null
        onFocused?.invoke()
    }

    fun enter(part: Any) {
        parent?.enter(part)
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
            if (inside.isEmpty() && !pinned) {
                org.example.project.scheduler.platform.Diagnostics.log("[popup] hover #${this@MenuHoverTracker.hashCode()} closed: pointer left (last part $part)")
                onLeft()
            }
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
 *
 * [pinOnPress] false: the press does not pin — the button opening the pop-up, whose click pins it itself. Anomaly
 * 2026-11-16 (*"when I click on the down arrow, it disappears instead of getting pinned"*): the press reaches Compose
 * before AWT tells the hover pop-up it lost the keyboard to the app's window, so a pin on the press made that loss read
 * as "a pinned pop-up left by a press outside" ([MenuHoverTracker.windowLeft]) — closed, and the click kept it closed.
 */
fun Modifier.reportsHover(tracker: MenuHoverTracker?, pinOnPress: Boolean = true): Modifier =
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
                        PointerEventType.Press -> if (pinOnPress) latest.pin()
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
 * What the app does with a key its own windows would have heard, given to the pop-ups: a pop-up is a window of its own
 * holding the keyboard, and the chords that walk every window (user rule 2026-11-11) must work from it too. The key and
 * its modifiers (Ctrl or Cmd, Shift, Alt), as `undoRedoIntentFor` reads them; true when taken. Null where nothing gives
 * it.
 */
val LocalMenuKeyForward = compositionLocalOf<((androidx.compose.ui.input.key.Key, Boolean, Boolean, Boolean) -> Boolean)?> { null }
