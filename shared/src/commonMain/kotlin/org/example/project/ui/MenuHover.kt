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
class MenuHoverTracker(private val scope: CoroutineScope, private val onLeft: () -> Unit) {
    private val inside = HashSet<Any>()
    private var pending: Job? = null

    /** Whether the pointer is over some part now. */
    val held: Boolean get() = inside.isNotEmpty()

    fun enter(part: Any) {
        inside += part
        pending?.cancel()
        pending = null
    }

    /** [part] left by the pointer, or gone from the screen with the pointer over it. */
    fun exit(part: Any) {
        if (!inside.remove(part) || inside.isNotEmpty()) return
        pending?.cancel()
        pending = scope.launch {
            delay(GRACE_MILLIS)
            if (inside.isEmpty()) onLeft()
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

/** This element as one part of [tracker]'s pop-up: the pointer entering and leaving it, and it going while held. */
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
                        else -> Unit
                    }
                }
            }
        }
    }

/** [MenuHoverTracker] for a pop-up whose composition holds it, calling the latest [onLeft]. */
@Composable
fun rememberMenuHoverTracker(onLeft: () -> Unit): MenuHoverTracker {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val latest by rememberUpdatedState(onLeft)
    return remember(scope) { MenuHoverTracker(scope) { latest() } }
}
