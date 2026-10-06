package org.example.project.ui

/**
 * User rule 2026-10-06: **a window opened from another has its tab beside that one's in the window bar, and a line
 * under the two** — a Search configurations window right of its Search window, a Search window the calendar's
 * right-click opened right of the calendar.
 *
 * The windows are a forest: a window's parent is the one it was opened from, while that one is open. The bar lists a
 * window, then the windows opened from it (each followed by its own), in the order they were opened — so a window with
 * no parent stays where the order of opening puts it, and only the opened ones move. Each window that others were
 * opened from is underlined from its own tab to the tab of the last of them. A line is told by the DEPTH of the window
 * it starts at (0 for one with no parent): the deeper line is drawn above, nearer the tabs — the calendar, a Search
 * window from it and that one's configurations give a line under the first two and, above it, a line under the last two.
 *
 * Derived from what the windows hold, never stored.
 */
internal object WindowTabGroups {
    /** A line under the tabs from [from] (a window others were opened from) to [to] (the last of them). */
    data class Underline(val from: String, val to: String, val depth: Int)

    /** The bar: its tabs in [order], and the lines under them. */
    data class Layout(val order: List<String>, val underlines: List<Underline>) {
        /** How many levels of lines the bar draws. */
        val levels: Int get() = underlines.maxOfOrNull { it.depth + 1 } ?: 0
    }

    /**
     * The bar for the windows [opened] (frame ids, in the order they were opened), [parentOf] naming the window each
     * was opened from. A parent that is not open, or a window named as its own ancestor, is no parent.
     */
    fun layout(opened: List<String>, parentOf: (String) -> String?): Layout {
        val ids = opened.toSet()
        val named = opened.associateWith { id -> parentOf(id)?.takeIf { it in ids && it != id } }
        // A window that is its own ancestor (never made by the app, but a rule must not loop on it) stands alone.
        fun parent(id: String): String? {
            var up = named[id]
            repeat(opened.size) {
                if (up == null) return named[id]
                if (up == id) return null
                up = named[up]
            }
            return null
        }
        val children = LinkedHashMap<String, MutableList<String>>()
        val roots = ArrayList<String>()
        for (id in opened) {
            val up = parent(id)
            if (up == null) roots += id else children.getOrPut(up) { ArrayList() } += id
        }
        val order = ArrayList<String>(opened.size)
        val underlines = ArrayList<Underline>()
        fun place(id: String, depth: Int) {
            order += id
            val fromIt = children[id] ?: return
            underlines += Underline(from = id, to = fromIt.last(), depth = depth)
            for (child in fromIt) place(child, depth + 1)
        }
        for (root in roots) place(root, 0)
        return Layout(order, underlines)
    }
}
