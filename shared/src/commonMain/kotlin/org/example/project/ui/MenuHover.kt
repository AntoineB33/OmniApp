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
 * User rule 2026-11-11: *"If a Search window disappears because the user hovered outside, ctrl+alt+<- brings it back,
 * and does like the user clicked on the background"* — **the hover pop-ups the pointer closed**, newest last, each
 * stamped with where the position history stood ([stampNow], `SchedulerReducer.positionStamp`). Back through the
 * positions ([reopenLatest]) first reopens the newest of them, pinned, while no position was recorded or walked since
 * it closed; after one was, it is older than what the walk goes back to, and all of them are forgotten.
 *
 * Local to this run and to the pop-ups still able to come back: one that left the screen with what holds it (a pop-up
 * inside a pop-up that closed too) takes its entry with it ([forget]).
 */
class HoverClosedPopups(private val stampNow: () -> Any?) {
    private class Entry(val owner: Any, val stamp: Any?, val reopen: () -> Unit)

    private val entries = ArrayList<Entry>()

    /** [owner]'s pop-up closed by the pointer; [reopen] brings it back, pinned. */
    fun closed(owner: Any, reopen: () -> Unit) {
        entries.removeAll { it.owner === owner }
        entries += Entry(owner, stampNow(), reopen)
    }

    /** [owner] opened again some other way, or left the screen: nothing to bring back. */
    fun forget(owner: Any) {
        entries.removeAll { it.owner === owner }
    }

    /** Reopens the newest pop-up the pointer closed since the last position, if any: whether one came back. */
    fun reopenLatest(): Boolean {
        val latest = entries.lastOrNull() ?: return false
        if (latest.stamp != stampNow()) {
            entries.clear()
            return false
        }
        entries.removeAt(entries.lastIndex)
        latest.reopen()
        return true
    }
}

/** The app's [HoverClosedPopups], null where nothing gives it (a test scene): nothing is then brought back. */
val LocalHoverClosedPopups = compositionLocalOf<HoverClosedPopups?> { null }
