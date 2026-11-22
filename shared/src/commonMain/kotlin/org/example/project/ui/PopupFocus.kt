package org.example.project.ui

import androidx.compose.runtime.compositionLocalOf
import org.example.project.scheduler.state.SchedulerReducer

/**
 * User rule 2026-11-22 (*"fold them in"*): **a pop-up of a ▾ configurations tree, by an address that outlives it** — the
 * id of the outermost arrow ([ArrowSearches], `Search#3▾`) and the address its [TreeKeeper] shows it by (for each tree
 * from the outermost down, the categories it shows). Text, so it can be a History Unit's before and after.
 */
object PopupAddress {
    private const val ARROW: Char = '\u001D'
    private const val LEVEL: Char = '\u001E'
    private const val STEP: Char = '\u001F'

    fun encode(arrowId: String, address: List<List<String>>): String =
        arrowId + ARROW + address.joinToString(LEVEL.toString()) { it.joinToString(STEP.toString()) }

    /** The arrow and the address [text] names, or null for a text that is none. */
    fun decode(text: String): Pair<String, List<List<String>>>? {
        if (ARROW !in text) return null
        val levels = text.substringAfter(ARROW).split(LEVEL).map { level -> if (level.isEmpty()) emptyList() else level.split(STEP) }
        return text.substringBefore(ARROW) to levels
    }
}

/**
 * User rule 2026-11-22: **the keyboard going to a pop-up is a position of the app, a History Unit like a move of the
 * focus** (`ExternalKeys.POPUP`, in the `WindowNav` list): walked by `Shift+Alt+←/→` under the same two rules as every
 * position (`SchedulerReducer.Pin`), kept across a restart, and branched like the rest. It replaced `PopupPositions`, a
 * second timeline kept in memory beside the history and joined to it by stamps.
 *
 * [holder] is where the history says the keyboard is (`SchedulerReducer.popupHolder`); [record] commits a unit from
 * there to a pop-up's address, or back to its window (null). A pop-up already holding the keyboard records nothing — so a
 * walk bringing one back, whose coming there the tree reports like any other, adds no unit. The outermost arrow of each
 * tree registers its [TreeKeeper] in [keepers], by its id: what a unit walked to is brought back through.
 */
internal class PopupFocus(
    private val holder: () -> String?,
    private val record: (before: String?, after: String?, label: String) -> Unit,
) {
    val keepers = HashMap<String, TreeKeeper>()

    /** The keyboard came to the pop-up at [address] of the tree whose outermost arrow is [arrowId]. */
    fun focused(arrowId: String?, address: List<List<String>>) {
        arrowId ?: return
        val to = PopupAddress.encode(arrowId, address)
        val from = holder()
        if (from != to) record(from, to, SchedulerReducer.POPUP_LABEL)
    }

    /** The keyboard went back from the pop-ups to their window. */
    fun left() {
        val from = holder() ?: return
        record(from, null, SchedulerReducer.POPUP_LEFT_LABEL)
    }

    /**
     * A unit walked: the keyboard brought to [to] — the pop-up, its trees opening down to it, pinned — or, null, back to
     * the window of the pop-up it left ([from]), which closes the tree.
     */
    fun restore(from: String?, to: String?) {
        if (to != null) {
            PopupAddress.decode(to)?.let { (arrow, address) -> keepers[arrow]?.bringBack(address) }
        } else {
            from?.let(PopupAddress::decode)?.let { (arrow, _) -> keepers[arrow]?.closeAll?.invoke() }
        }
    }
}

/** The app's [PopupFocus]; null where nothing gives it (a test scene): no pop-up is then a position. */
internal val LocalPopupFocus = compositionLocalOf<PopupFocus?> { null }
