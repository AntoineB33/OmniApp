package org.example.project.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.time.Duration
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * **What opens a pop-up — a ▾, a drop-down's field, a row that opens a window — and the ONE rule for a press on it**
 * (`docs/invariants/popups.md` § *A press on what opens a pop-up is its click's to answer*).
 *
 * Anomaly 2026-11-20 (*"Often when I rapidly click on a down arrow, it appears and disappears in a blink. It also happens
 * for rows that show a window when hovering over it."*): a press on the control of an OPEN pop-up was answered twice —
 * once by whatever closes a pop-up on a press outside it (the root observer, [TransientMenuHost]; on the desktop, the
 * pop-up's window losing the keyboard to the window the control stands in), and once by the control's own click. The
 * two came in either order: the platform reports the keyboard's move when it likes, before the click or after it. When
 * it came after a click that had kept the pop-up (a hover pop-up pinned by the click, a category's pop-up clicked open),
 * it closed what the click had just kept. And a double click toggled the pop-up open and shut.
 *
 * So, for every opener:
 *  - **a press on the control of an open pop-up is the control's click to answer, never the outside press's**: the
 *    root observer leaves that control's menus alone ([pressAnsweredBy]), and the keyboard leaving the pop-up for the
 *    window the control stands in waits for the click ([PopupOpeners.keyboardLeft]) — given back to the pop-up if the
 *    click keeps it, dropped if the click closes it, applied as ever if no click comes ([SAME_GESTURE], or the pointer
 *    leaving the control);
 *  - **a click within [SAME_GESTURE] of the last one is the same gesture** ([click]): a double click, or any quick run of
 *    clicks, leaves the pop-up as the first click left it.
 */
class PopupOpener {
    /** Whether its pop-up is open now — as last composed ([popupOpener]). */
    internal var isOpen: () -> Boolean = { false }

    private var lastClick: TimeMark? = null
    private var lastOpenAfter = false

    /**
     * One click on the control: whether its pop-up is open after it. [isOpen]: it is open now; [closes]: a click on it
     * open closes it (a drop-down's toggle, a pinned pop-up's ▾) rather than keeping it (a hover pop-up the click pins, a
     * category's row). A click within [SAME_GESTURE] of the last answers as that one did. The keyboard losses waiting for
     * this click are answered with it.
     */
    fun click(isOpen: Boolean, closes: Boolean): Boolean {
        val sameGesture = lastClick?.let { it.elapsedNow() < SAME_GESTURE } == true
        val openAfter = when {
            sameGesture -> lastOpenAfter
            !isOpen -> true
            else -> !closes
        }
        lastClick = TimeSource.Monotonic.markNow()
        lastOpenAfter = openAfter
        PopupOpeners.answered(this, openAfter)
        return openAfter
    }

    /** What its last click left the pop-up as, if that click was under [SAME_GESTURE] ago; null else. */
    internal fun recentAnswer(): Boolean? =
        if (lastClick?.let { it.elapsedNow() < SAME_GESTURE } == true) lastOpenAfter else null

    companion object {
        /**
         * How long two clicks are one gesture, and how late the platform reports the press of a click as the keyboard
         * moving, at most: Windows' default double-click time.
         */
        val SAME_GESTURE: Duration = Duration.parse("500ms")
    }
}

/** The pointer's openers: one pointer, so at most one control under it. */
object PopupOpeners {
    /** The opener the pointer is over, null when none ([popupOpener]). */
    var under: PopupOpener? = null
        private set

    private class Loss(val opener: PopupOpener, val lost: () -> Unit, val kept: () -> Unit)

    private val waiting = ArrayList<Loss>()

    /** Whether a press arriving now is on [opener]'s control while its pop-up is open: its click answers it. */
    fun pressAnsweredBy(opener: PopupOpener?): Boolean = opener != null && opener === under && opener.isOpen()

    /**
     * A pop-up opened by [opener] (null: by nothing that answers a press) lost the keyboard to a window of the app.
     * [lost] is what that loss does (it closes the pop-up), [kept] gives the keyboard back to the pop-up. The pointer on
     * the opener: the loss is the report of a press on it, and its click answers — at once if that click came already,
     * else when it comes ([answered]). Returns a handle to [expire] if it waits, null when answered now.
     */
    fun keyboardLeft(opener: PopupOpener?, lost: () -> Unit, kept: () -> Unit = {}): Any? {
        if (opener == null || opener !== under) {
            lost()
            return null
        }
        val recent = opener.recentAnswer()
        if (recent != null) {
            if (recent) kept()
            return null
        }
        if (!opener.isOpen()) {
            lost()
            return null
        }
        return Loss(opener, lost, kept).also { waiting += it }
    }

    /** No click came for the loss [handle] in time ([PopupOpener.SAME_GESTURE]): it does what it always did. */
    fun expire(handle: Any) {
        if (waiting.remove(handle)) (handle as Loss).lost()
    }

    internal fun answered(opener: PopupOpener, openAfter: Boolean) {
        val mine = waiting.filter { it.opener === opener }
        waiting.removeAll(mine)
        // Closed by the click itself: nothing more to do for the losses.
        if (openAfter) mine.forEach { it.kept() }
    }

    internal fun entered(opener: PopupOpener) {
        under = opener
    }

    internal fun exited(opener: PopupOpener) {
        if (under === opener) under = null
        // The pointer gone from the control before any click: the press was no click on it.
        val mine = waiting.filter { it.opener === opener }
        waiting.removeAll(mine)
        mine.forEach { it.lost() }
    }
}

/**
 * The opener of the pop-up declared under it ([AppDropdownMenu], [transientMenuDismissal]): what a press on it is
 * answered by. Provided around the menu alone, and reset inside it, so a menu opened inside the pop-up is not taken for
 * it.
 */
val LocalPopupOpener = compositionLocalOf<PopupOpener?> { null }

@Composable
fun rememberPopupOpener(): PopupOpener = remember { PopupOpener() }

/** This element as [opener]'s control: whether the pointer is over it, and whether its pop-up is open ([isOpen]). */
fun Modifier.popupOpener(opener: PopupOpener, isOpen: Boolean): Modifier =
    composed {
        val open by rememberUpdatedState(isOpen)
        opener.isOpen = { open }
        DisposableEffect(opener) { onDispose { PopupOpeners.exited(opener) } }
        pointerInput(opener) {
            awaitPointerEventScope {
                while (true) {
                    when (awaitPointerEvent(PointerEventPass.Initial).type) {
                        PointerEventType.Enter -> PopupOpeners.entered(opener)
                        PointerEventType.Exit -> PopupOpeners.exited(opener)
                        else -> Unit
                    }
                }
            }
        }
    }

/** [content] — the pop-up's menu — opened by [opener] ([LocalPopupOpener]). */
@Composable
fun OpenedBy(opener: PopupOpener, content: @Composable () -> Unit) =
    androidx.compose.runtime.CompositionLocalProvider(LocalPopupOpener provides opener, content = content)
