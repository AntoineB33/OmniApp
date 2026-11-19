package org.example.project.ui

/**
 * **THE pinned parent row** — the task tree's rule, and the one reading of it every list of the app makes (the tree's
 * three drawings, the Search window's result list and actions section, the menu's folders). Over a band of one row height at
 * the top of what is shown, the **direct parent** of the row appearing RIGHT BELOW the band (the first whose bottom lies
 * past it, so a row the band half covers still counts) is drawn, and only once that parent's own row has started to
 * leave the top. **One row, never a stack of its ancestors.**
 *
 * Anomaly 2026-11-19 (*"both 'Calendar: Calendar' and 'Calendar' are showing on the top … The correct behavior is shown
 * in the children of the 'AI' task element, where only one element is stuck at the top"*): the Search window's lists
 * pinned a CHAIN (every open row above the top one, then the open one), a second rule beside the tree's.
 *
 * [bandOf] is a row's band in the same coordinates as [top] — null for a row not laid out yet (nothing is pinned on
 * it), and a band wholly above [top] for a row known to be scrolled off above.
 */
internal fun <R : Any> pinnedParent(
    rows: List<R>,
    top: Float,
    band: Float,
    bandOf: (R) -> ClosedFloatingPointRange<Float>?,
    parentOf: (R) -> R?,
): R? {
    val first = rows.firstOrNull { row -> bandOf(row)?.let { it.endInclusive > top + band + 0.5f } == true } ?: return null
    val parent = parentOf(first) ?: return null
    val parentTop = bandOf(parent)?.start ?: return null
    if (parentTop >= top - 0.5f) return null
    return parent
}

/** An item of a lazy list as laid out: its [index], its top [offset] in the viewport and its [size]. */
internal class PinItem(val index: Int, val offset: Int, val size: Int)

/**
 * [pinnedParent] over a LAZY list whose items may hold content under their head (an open action's fields, an expanded
 * row's sub-tree, an open element's children): each item is read as its head — one [band] tall, hanging under the
 * nearest item above of a smaller [depthOf] (null: a list of no nesting) — and, when it is taller, its content, which
 * hangs under its own head when the item is [pinnable] and under nothing otherwise (a row whose sub-tree pins a deeper
 * parent itself). An ancestor scrolled out of the laid-out items is above the top. The pinned item's index, or null.
 */
internal fun pinnedListRow(
    items: List<PinItem>,
    top: Int,
    band: Float,
    depthOf: (Int) -> Int?,
    pinnable: (Int) -> Boolean,
): Int? {
    class Row(val index: Int, val content: Boolean)
    val byIndex = items.associateBy { it.index }
    val rows = ArrayList<Row>()
    for (item in items) {
        rows += Row(item.index, content = false)
        if (item.size > band + 0.5f) rows += Row(item.index, content = true)
    }
    val above = Float.NEGATIVE_INFINITY..Float.NEGATIVE_INFINITY
    fun bandOf(row: Row): ClosedFloatingPointRange<Float> {
        val item = byIndex[row.index] ?: return above
        val start = item.offset.toFloat()
        return if (row.content) (start + band)..(start + item.size) else start..(start + minOf(band, item.size.toFloat()))
    }
    fun parentOf(row: Row): Row? {
        if (row.content) return if (pinnable(row.index)) Row(row.index, content = false) else null
        var depth = depthOf(row.index) ?: return null
        var i = row.index - 1
        while (i >= 0 && depth > 0) {
            val d = depthOf(i)
            if (d != null && d < depth) return Row(i, content = false)
            i--
        }
        return null
    }
    return pinnedParent(rows, top.toFloat(), band, ::bandOf, ::parentOf)?.index
}
