package org.example.project.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.window.PopupProperties

/** [row] as a [HoverPopupRow] showing [popup], or as it is where [popup] is null. */
@Composable
internal fun WithHoverPopup(popup: (@Composable () -> Unit)?, row: @Composable () -> Unit) {
    if (popup == null) row() else HoverPopupRow(popup, row = row)
}

/**
 * User rule 2026-11-27: *"Rows that open a window when hovered over must now open it only when the arrow is hovered
 * over. The caller is the arrow, so be careful that the opened window always appears sticking to the caller."* — **what a
 * row hands its [HoverPopupArrow]**: the arrow, not the row, is what a hover opens the pop-up from ([onHover]), what the
 * pop-up's hover branch counts as its caller ([branch]), and what the pop-up hangs from: [popup] is composed inside the
 * arrow, so the desktop's menu takes the arrow as its anchor, and with no list of the caller's ([LocalCallerList] null,
 * as the ▾'s tree) it is placed against the arrow itself ([menuFit]: always stuck to it).
 */
internal class HoverPopupCaller(
    val branch: MenuHoverTracker,
    /** The pop-up the row stands in: where a hover over the arrow is read from ([MenuHoverTracker.hoverActs]). */
    val around: MenuHoverTracker?,
) {
    var onHover: () -> Unit = {}

    /** The pop-up, drawn while open: its own [AppDropdownMenu]. */
    var popup: @Composable () -> Unit = {}

    /** How many arrows draw it. None (a row not wearing its arrow): the row hangs the pop-up from itself. */
    var arrows by mutableStateOf(0)
}

/** The row an arrow stands in, where that row opens a pop-up ([HoverPopupCaller]); null elsewhere and inside the pop-up. */
internal val LocalHoverPopupCaller = androidx.compose.runtime.compositionLocalOf<HoverPopupCaller?> { null }

/**
 * User rule 2026-11-21: *"When a row shows a window when hovered over, there must be an arrow indicating it."* — **the
 * mark of every row that opens a pop-up when hovered**: a configurations tree's category ([ConfigurationTree]) and a menu
 * entry with a pop-up of its own ([HoverPopupRow]). One drawing, so the two read as the same promise. Since 2026-11-27 it
 * is also the CALLER ([HoverPopupCaller]): hovering it, and only it, opens the pop-up, which hangs from it.
 */
@Composable
internal fun HoverPopupArrow(modifier: Modifier = Modifier) {
    val caller = LocalHoverPopupCaller.current
    if (caller == null) {
        HoverPopupGlyph(modifier)
        return
    }
    androidx.compose.runtime.DisposableEffect(caller) {
        caller.arrows++
        onDispose { caller.arrows-- }
    }
    Box(
        modifier
            .reportsHover(caller.branch)
            .onHoverEnter { if (MenuHoverTracker.hoverActs(caller.around)) caller.onHover() },
    ) {
        HoverPopupGlyph(Modifier)
        // Nothing inside the pop-up is this row's: an arrow there is its own row's, or none.
        androidx.compose.runtime.CompositionLocalProvider(LocalHoverPopupCaller provides null) { caller.popup() }
    }
}

@Composable
private fun HoverPopupGlyph(modifier: Modifier) {
    Text("◂", modifier = modifier, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/**
 * User rule 2026-11-21: *"Right-clicking in the calendar must open a search window with options such like 'add' or
 * 'edit'. Those two options open when hovered over windows to precise what to add or edit."* — **a menu's row that shows
 * a pop-up of its own when hovered** ([MenuAction.hoverPopup]), the configurations tree's category row made general: the
 * pop-up opens on [MenuSide.SUBMENU]'s sides and vanishes once the pointer has left its caller, it and the pop-ups opened
 * inside it ([MenuHoverTracker]). A click on the row opens it pinned — a finger's way to it too, a finger making no
 * hover — and a press inside it pins it. The row draws [HoverPopupArrow] before its name (the Search window's option
 * rows: their `leading` mark). User rule 2026-11-27: only a hover of that ARROW opens it, and it hangs from the arrow
 * ([HoverPopupCaller]); a row drawing no arrow (one that opens on a click alone) hangs it from itself, off its list
 * ([LocalCallerList]).
 */
@Composable
internal fun HoverPopupRow(
    popup: @Composable () -> Unit,
    /** User rule 2026-11-25: raised, the pop-up is shown pinned — a blank child row's double-click or Enter. */
    ask: Int = 0,
    /**
     * False: a hover shows nothing, only a click does (user rule 2026-11-26: *"The pop-up window must appear only when the
     * row is clicked"* — the blank row ending a list of children, which then wears no [HoverPopupArrow]).
     */
    opensOnHover: Boolean = true,
    row: @Composable () -> Unit,
) {
    // The list of rows the row is in (the menu's result list gives it), which a pop-up hung from the row must not hide.
    val callerList = LocalCallerList.current
    var open by remember { mutableStateOf(false) }
    val around = LocalMenuHover.current
    val branch = rememberMenuHoverTracker(parent = around) { open = false }
    val opener = rememberPopupOpener()
    val latestOpen by rememberUpdatedState(open)
    androidx.compose.runtime.LaunchedEffect(ask) {
        if (ask > 0) {
            open = true
            branch.pin()
        }
    }
    // User rule 2026-11-27: a row opening on hover does so from its arrow alone, and hangs the pop-up from it.
    val caller = remember(branch, around) { HoverPopupCaller(branch, around) }
    val shown: @Composable (fromArrow: Boolean) -> Unit = { fromArrow ->
        if (open) {
            androidx.compose.runtime.CompositionLocalProvider(
                LocalMenuSides provides MenuSide.SUBMENU,
                // The arrow is the caller: the pop-up stands against IT, even over the list (as the ▾'s tree).
                LocalCallerList provides if (fromArrow) null else callerList,
                LocalMenuHover provides branch,
                LocalPopupOpener provides opener,
            ) {
                androidx.compose.runtime.CompositionLocalProvider(LocalBareMenu provides true) {
                    AppDropdownMenu(
                        expanded = true,
                        onDismissRequest = { open = false },
                        // The whole pop-up is the branch, wherever the platform's menu does not report its surface itself.
                        modifier = Modifier.reportsHover(branch),
                        properties = PopupProperties(focusable = true),
                    ) {
                        // It is a Search window: it wears a window's surface itself.
                        androidx.compose.runtime.CompositionLocalProvider(LocalPopupChrome provides PopupChrome()) {
                            popup()
                        }
                    }
                }
            }
        }
    }
    caller.onHover = { if (opensOnHover) open = true }
    caller.popup = { shown(true) }
    val fromArrow = opensOnHover && caller.arrows > 0
    Box(
        Modifier
            // Hung from the row itself (no arrow drawn), the row is its caller for the hover branch too.
            .then(if (fromArrow) Modifier else Modifier.reportsHover(branch))
            .popupOpener(opener, open)
            // The row's own press handling (it picks the option) is left alone: the release is read, never consumed.
            .pointerInput(branch, opener) {
                awaitPointerEventScope {
                    while (true) {
                        if (awaitPointerEvent().type != PointerEventType.Release) continue
                        if (branch.clickedOpener(opener, latestOpen, toggles = false)) {
                            open = true
                            branch.pin()
                        }
                    }
                }
            },
    ) {
        androidx.compose.runtime.CompositionLocalProvider(LocalHoverPopupCaller provides caller.takeIf { opensOnHover }) {
            row()
        }
        if (!fromArrow) shown(false)
    }
}
