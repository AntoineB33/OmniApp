package org.example.project.scheduler.domain

import org.example.project.scheduler.model.CellId
import org.example.project.scheduler.model.CellListId
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.state.SchedulerState

/**
 * PRD §4 **Find & replace**: the pure half of the task tree's Ctrl+F bar — which titles the query hits,
 * where in each title, and what a replacement leaves behind.
 *
 * Two things it deliberately gets right:
 *
 * - **It searches the WHOLE tree, not the visible rows.** A task tree is mostly collapsed, so a find that
 *   only saw [SchedulerDomain.selectableVisibleOccurrences] would quietly miss most of the account. Each
 *   match therefore carries the [Match.ancestors] chain the walk descended through, which is what the
 *   reducer expands to bring the row on screen ([org.example.project.scheduler.state.SchedulerIntent.RevealCell]).
 * - **A sub-list belongs to the task id**, so a task pointed at by several cells shows the *same* children
 *   under each of them. Walking that structure naively re-walks a mirrored sub-tree once per occurrence —
 *   exponential in the worst case. The walk visits each **list** once, so every cell is met exactly once,
 *   by the first path that reaches it; the ancestors recorded are that path.
 *
 * A match is a **range inside one cell's title**, as in VS Code: "aa" in "banana" is two matches. Replace
 * rewrites that one range; Replace All rewrites every range of every matched **task**, once per task —
 * because renaming is per task ([org.example.project.scheduler.state.CellEditMode.Rename]), and a task
 * mirrored under three parents must not be renamed three times over.
 */
object TaskTreeSearch {

    /** The two toggles on the find bar. Regex is deliberately not offered. */
    data class Options(
        val matchCase: Boolean = false,
        val wholeWord: Boolean = false,
    )

    /**
     * One hit: the half-open range `[start, end)` of [taskId]'s title shown by the cell [cellId], reached
     * through [ancestors] (outermost first, the cell itself excluded).
     */
    data class Match(
        val cellId: CellId,
        val taskId: TaskId,
        val ancestors: List<CellId>,
        val start: Int,
        val end: Int,
    ) {
        /** The parent occurrence the row is rendered under — `null` for a root-viewport row. */
        val renderVia: CellId? get() = ancestors.lastOrNull()
    }

    /**
     * The non-overlapping ranges of [query] in [title], left to right. A range rejected by
     * [Options.wholeWord] does not consume the text it overlapped — the scan resumes one character in, so
     * "cat" still matches the second word of "concat cat".
     */
    fun ranges(title: String, query: String, options: Options): List<IntRange> {
        if (query.isEmpty() || title.length < query.length) return emptyList()
        val result = mutableListOf<IntRange>()
        var from = 0
        while (from <= title.length - query.length) {
            val at = title.indexOf(query, from, ignoreCase = !options.matchCase)
            if (at < 0) break
            val end = at + query.length
            if (!options.wholeWord || isWholeWord(title, at, end)) {
                result += at until end
                from = end
            } else {
                from = at + 1
            }
        }
        return result
    }

    /** Every hit in the tree, in the order the rows appear once their ancestors are expanded. */
    fun matches(state: SchedulerState, query: String, options: Options): List<Match> {
        if (query.isEmpty()) return emptyList()
        val result = mutableListOf<Match>()
        val visitedLists = mutableSetOf<CellListId>()

        fun walk(listId: CellListId, ancestors: List<CellId>) {
            // One visit per list: a mirrored sub-tree is the SAME list under every cell pointing at its
            // task, so without this the walk re-enters it once per occurrence.
            if (!visitedLists.add(listId)) return
            val list = state.lists[listId] ?: return
            for (cellId in list.cellIds) {
                val cell = state.cells[cellId] ?: continue
                val taskId = cell.taskId ?: continue
                val task = state.tasks[taskId] ?: continue
                // The conceptual root/main cells are not rows the user can select or rename; their
                // sub-list is still walked (it is the tree the viewport shows).
                if (SchedulerDomain.isSelectableCell(state, cellId)) {
                    for (range in ranges(task.title, query, options)) {
                        result += Match(
                            cellId = cellId,
                            taskId = taskId,
                            ancestors = ancestors,
                            start = range.first,
                            end = range.last + 1,
                        )
                    }
                }
                val childListId = task.childListId ?: continue
                walk(childListId, ancestors + cellId)
            }
        }

        walk(state.rootListId, emptyList())
        return result
    }

    /** [title] with the half-open range `[start, end)` swapped for [replacement]. */
    fun replaceRange(title: String, start: Int, end: Int, replacement: String): String {
        if (start < 0 || end > title.length || start > end) return title
        return title.substring(0, start) + replacement + title.substring(end)
    }

    /** [title] with every hit of [query] swapped for [replacement]. */
    fun replaceAll(title: String, query: String, options: Options, replacement: String): String {
        val hits = ranges(title, query, options)
        if (hits.isEmpty()) return title
        val builder = StringBuilder()
        var cursor = 0
        for (hit in hits) {
            builder.append(title, cursor, hit.first)
            builder.append(replacement)
            cursor = hit.last + 1
        }
        builder.append(title, cursor, title.length)
        return builder.toString()
    }

    /**
     * The new title of every task [matches] hit — the map [org.example.project.scheduler.state.SchedulerIntent.ReplaceTaskTitles]
     * applies as ONE history unit. Keyed by task, so a task mirrored under several parents is rewritten once.
     */
    fun replaceAllTitles(
        state: SchedulerState,
        query: String,
        options: Options,
        replacement: String,
    ): Map<TaskId, String> {
        if (query.isEmpty()) return emptyMap()
        return matches(state, query, options)
            .map { it.taskId }
            .distinct()
            .mapNotNull { taskId ->
                val title = state.tasks[taskId]?.title ?: return@mapNotNull null
                val replaced = replaceAll(title, query, options, replacement)
                if (replaced == title) null else taskId to replaced
            }
            .toMap()
    }

    private fun isWholeWord(title: String, start: Int, end: Int): Boolean {
        val before = if (start == 0) null else title[start - 1]
        val after = if (end >= title.length) null else title[end]
        return !before.isWordChar() && !after.isWordChar()
    }

    private fun Char?.isWordChar(): Boolean = this != null && (isLetterOrDigit() || this == '_')
}
