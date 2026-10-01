package org.example.project.ui

/**
 * **Selecting things in a row or a list by clicking** — the file explorer's rule, over the elements in their drawn
 * order: a plain click selects the clicked element alone and makes it the anchor; Shift+click selects the range from
 * the anchor to it (the range itself is [CheckRange.keysToSet], the app's one reading of a Shift range), replacing the
 * selection — or adding to it with Ctrl held too — and keeps the anchor, so a second Shift+click re-draws the range
 * from the same element; Ctrl+click adds or takes the clicked element and makes it the anchor.
 *
 * The one funnel for it: the window bar's tabs ([WindowFrameHost.onTabPressed]) and the Search window's result list
 * (user rules 2026-10-01). Never a second copy per list.
 */
object ClickSelection {
    data class Result(val selected: Set<String>, val anchor: String?)

    fun click(
        order: List<String>,
        selected: Set<String>,
        anchor: String?,
        clicked: String,
        shift: Boolean,
        ctrl: Boolean,
    ): Result =
        when {
            shift -> {
                val from = anchor?.takeIf { it in order }
                val range = CheckRange.keysToSet(order, from, clicked, shift = true)
                Result((if (ctrl) selected else emptySet()) + range, from ?: clicked)
            }
            ctrl -> Result(if (clicked in selected) selected - clicked else selected + clicked, clicked)
            else -> Result(setOf(clicked), clicked)
        }
}
