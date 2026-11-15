package org.example.project.ui

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size

/**
 * User rule 2026-11-15: *"I click on show the header for a pop-up window, but it doesn't show the usual header with the
 * buttons to save, duplicate, resize… When the user does this, they can drag it somewhere. When a pop-up window gets the
 * position of the side that is stuck to the caller change, it gets shown in the [window bar] and all its children
 * windows disappear."* — **a pop-up dragged by its head, or asked by one of its head's window buttons, becomes a window
 * of the app**: the window it is the pop-up form of, where the pop-up stood, listed in the window bar; its child pop-ups
 * close as the drag starts, and the pop-up itself as the window opens.
 *
 * **What the pop-up's own window can do** while its head is dragged — given by the platform's menu (the desktop's, a
 * window of its own); null where a menu is drawn in the app's window, and then the head has no window buttons.
 */
interface PopupWindowControl {
    /** The drag of the head starts: the pop-up stops being placed beside what opened it, and follows the pointer. */
    fun startDrag()

    /** The pointer moved, the button held: the pop-up's window follows it (read off the screen, not the content). */
    fun drag()

    /** Where the pop-up stands, in pixels of the app window's content; null where it cannot be told. */
    fun boundsInApp(): Rect?
}

val LocalPopupWindowControl = staticCompositionLocalOf<PopupWindowControl?> { null }

/** What a pop-up's head asked the window it becomes to be ([PopupDetacher.detach]): the head's buttons, or a drag. */
enum class DetachedAs { Moved, Duplicated, FillWidth, FillHeight, Reduced, Maximized, AddedToMenu }

/**
 * `App`'s side of a pop-up becoming a window: whether the ▾ configurations tree of the Search window [canDetach] names
 * may become its Search configurations window, and [detach] — that window opened (or brought back) at [Rect] (pixels
 * of the app window's content), then as the head's button asked. [detachSearch]: a Search pop-up (a field's, the "List
 * of parents") opened as a Search window of the app with its configuration, there.
 */
class PopupDetacher(
    val canDetach: (searchId: String) -> Boolean,
    val detach: (searchId: String, bounds: Rect, how: DetachedAs, name: String?) -> Unit,
    val detachSearch: (config: org.example.project.scheduler.domain.SearchDomain.Config, bounds: Rect, how: DetachedAs, name: String?) -> Unit,
)

val LocalPopupDetacher = compositionLocalOf<PopupDetacher?> { null }

/**
 * What the head of the Search pop-up drawn here does as a window's ([SearchPopupHead]), given by what hosts the pop-up
 * (a field's pop-up layer); null: a title and its ✕. Read by the Search window drawn in no frame, which gives its own
 * content null back — the Search windows inside it (its sections) are not the pop-up.
 */
val LocalSearchPopupWindow = compositionLocalOf<PopupWindowHead?> { null }

/**
 * The offset a window frame keeps ([WindowFrameState.offset]: from its centred resting place in the content area
 * [area]) for a window of [size] whose top-left stands at [topLeft], both in the app window's pixels.
 */
internal fun frameOffsetAt(topLeft: Offset, size: Size, area: Rect): Offset =
    Offset(
        topLeft.x - area.left - (area.width - size.width) / 2f,
        topLeft.y - area.top - (area.height - size.height) / 2f,
    )
