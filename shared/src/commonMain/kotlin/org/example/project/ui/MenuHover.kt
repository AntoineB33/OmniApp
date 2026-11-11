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
    }

    /**
     * Brings the keyboard to the pop-up's own window, while it is on screen — given by the platform's menu (the
     * desktop's windows), null where a menu is drawn in the app's window.
     */
    var focusWindow: (() -> Unit)? = null

    /** What the pop-up's owner does when the keyboard comes to it ([PopupPositions.focused]). */
    var onFocused: (() -> Unit)? = null

    /** The keyboard came to the pop-up's window — told by the platform's menu. */
    fun windowFocused() {
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
    }
}

/**
 * The tracker the parts of a hover pop-up report to, null elsewhere. A menu opened from inside the pop-up inherits it
 * (the desktop's menus report their window to it), so it counts as a descendant.
 */
val LocalMenuHover = compositionLocalOf<MenuHoverTracker?> { null }

/**
 * This element as one part of [tracker]'s pop-up: the pointer entering and leaving it, it going while held, and a press
 * on it pinning the pop-up ([MenuHoverTracker.pin]) — whatever is pressed, its background or a control in it.
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
    return remember(scope, parent) { MenuHoverTracker(scope, parent) { latest() } }
}

/**
 * User rule 2026-11-11: *"ctrl+alt+->/<- does the same but forall windows"*, and *"If a Search window disappears because
 * the user hovered outside, ctrl+alt+<- brings it back, and does like the user clicked on the background"* — **the
 * pop-ups the keyboard went to, as positions of the app**: each one the keyboard comes to is a new position
 * ([focused]); back and forward ([back], [forward]) walk them, bringing the keyboard back to one still open, or bringing
 * one back, pinned, that closed (the pointer leaving it, most often). A walk lands on the pop-up the keyboard is in only
 * when that one closed meanwhile: then back reopens it first.
 *
 * They come before the app's own positions (`SchedulerIntent.UndoPosition`): while none of those was recorded or walked
 * since the newest pop-up position ([stampNow], `SchedulerReducer.positionStamp`); past the oldest, the walk goes on in
 * the app's history. In this run only; a pop-up that left the screen with what holds it is no position any more
 * ([forget]).
 */
class PopupPositions(private val stampNow: () -> Any?) {
    private class Entry(val owner: Any, val isOpen: () -> Boolean, val bringBack: () -> Unit)

    private val entries = ArrayList<Entry>()
    private var index = -1
    private var stamp: Any? = null

    /** The pop-up a walk is bringing the keyboard to: its focus is no new position. */
    private var expected: Any? = null

    /** The keyboard came to [owner]'s pop-up; [bringBack] brings it back to it (reopened and pinned if it closed). */
    fun focused(owner: Any, isOpen: () -> Boolean, bringBack: () -> Unit) {
        if (expected === owner) {
            expected = null
            return
        }
        expected = null
        if (stampNow() != stamp) clear()
        while (entries.size > index + 1) entries.removeAt(entries.lastIndex)
        if (entries.lastOrNull()?.owner === owner) entries.removeAt(entries.lastIndex)
        entries += Entry(owner, isOpen, bringBack)
        index = entries.lastIndex
        stamp = stampNow()
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
        if (index < 0 && entries.isNotEmpty()) index = 0
    }

    /** Back one pop-up position: whether there was one (else the app's own positions are walked). */
    fun back(): Boolean {
        if (!current()) return false
        val here = entries[index]
        if (!here.isOpen()) return bring(here)
        if (index == 0) return false
        index--
        return bring(entries[index])
    }

    /** Forward one pop-up position: whether there was one. */
    fun forward(): Boolean {
        if (!current() || index >= entries.lastIndex) return false
        index++
        return bring(entries[index])
    }

    /** Whether the pop-up positions are the newest of the app's — none recorded or walked in its history since. */
    private fun current(): Boolean {
        if (entries.isEmpty()) return false
        if (stampNow() != stamp) {
            clear()
            return false
        }
        return true
    }

    private fun bring(entry: Entry): Boolean {
        expected = entry.owner
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
 * holding the keyboard, and the chords that walk every window (user rule 2026-11-11) must work from it too. True when
 * taken. Null where nothing gives it.
 */
val LocalMenuKeyForward = compositionLocalOf<((androidx.compose.ui.input.key.KeyEvent) -> Boolean)?> { null }
