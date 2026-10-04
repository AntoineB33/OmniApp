package org.example.project.ui

import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerType

/** How a touch press that might be a long-press ended — see [awaitTouchLongPress]. */
internal enum class TouchPressOutcome {
    /** Not a touch press (mouse, pen, stylus): the caller's mouse path applies. */
    NotTouch,

    /**
     * Held in place for the long-press timeout: the touch stand-in for a right-click. Returned the moment the
     * timeout passes, finger still down, so the menu opens under it; the caller then [consumeUntilUp]s.
     */
    Held,

    /** Lifted before the timeout without travelling: a tap. */
    Released,

    /** Travelled past the touch slop, or a second finger landed: a scroll, a drag or a pinch, never a menu. */
    Moved,
}

/**
 * The ONE touch stand-in for a right-click outside the calendar grid (`docs/PLATFORMS.md`): a finger held still
 * for the platform's long-press timeout. A phone has no secondary button, so every menu that only a right-click
 * opened was simply unreachable there. (The calendar grid keeps its own PRD §8 double-tap-and-release, which it
 * needs to tell apart from its double-tap-and-drag zoom.)
 *
 * Decided per POINTER, never per platform: a touchscreen laptop or a phone's browser gets it, a mouse on the same
 * device does not. Nothing is consumed while waiting, so a parent's scroll still sees the drag that makes this
 * [TouchPressOutcome.Moved].
 */
internal suspend fun AwaitPointerEventScope.awaitTouchLongPress(down: PointerInputChange): TouchPressOutcome {
    if (down.type != PointerType.Touch) return TouchPressOutcome.NotTouch
    val slop = viewConfiguration.touchSlop
    val early = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
        var outcome: TouchPressOutcome? = null
        while (outcome == null) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id }
            outcome = when {
                change == null || !change.pressed -> TouchPressOutcome.Released
                event.changes.count { it.pressed } > 1 -> TouchPressOutcome.Moved
                change.isConsumed || (change.position - down.position).getDistance() > slop -> TouchPressOutcome.Moved
                else -> null
            }
        }
        outcome
    }
    return early ?: TouchPressOutcome.Held
}

/** After a [TouchPressOutcome.Held]: swallow the rest of the gesture, so nothing beneath reads its release as a tap. */
internal suspend fun AwaitPointerEventScope.consumeUntilUp() {
    do {
        val event = awaitPointerEvent()
        event.changes.forEach { it.consume() }
    } while (event.changes.any { it.pressed })
}
