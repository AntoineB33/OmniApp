package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.domain.SchedulerDomain.VisibleOccurrence
import org.example.project.scheduler.model.WellKnownIds
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState

/**
 * [SchedulerDomain.visibleRows] — the walk the tree's pinned parent row reads: every drawn row with the row it
 * hangs directly under and its depth. It must be [SchedulerDomain.visibleOccurrences] row for row (that function
 * is now a view of it), and a row's parent must be the parent ROW, which a child's via cannot say on its own:
 * the root row is no via at all.
 */
class VisibleRowsTest {

    /** "Book" (expanded, with a child "Chapter", itself expanded with "Page") and "Notes" at the top level. */
    private fun tree(): SchedulerState {
        var s = SchedulerState.empty()
        val bookCell = s.lists[s.rootListId]!!.cellIds[0]
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(bookCell, "Book"))
        val notesCell = s.lists[s.rootListId]!!.cellIds[1]
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(notesCell, "Notes"))
        val bookList = s.tasks[s.cells[bookCell]!!.taskId!!]!!.childListId!!
        val chapterCell = s.lists[bookList]!!.cellIds[0]
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(chapterCell, "Chapter"))
        val chapterList = s.tasks[s.cells[chapterCell]!!.taskId!!]!!.childListId!!
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(s.lists[chapterList]!!.cellIds[0], "Page"))
        s = SchedulerReducer.reduce(s, SchedulerIntent.ToggleExpand(bookCell))
        s = SchedulerReducer.reduce(s, SchedulerIntent.ToggleExpand(chapterCell))
        return s
    }

    private fun SchedulerState.cellTitled(title: String) =
        cells.values.first { c -> c.taskId?.let { tasks[it]?.title } == title }.id

    @Test
    fun the_rows_are_the_visible_occurrences_in_order() {
        val s = tree()
        assertEquals(SchedulerDomain.visibleOccurrences(s), SchedulerDomain.visibleRows(s).map { it.occurrence })
    }

    @Test
    fun each_row_names_the_row_it_hangs_under_and_its_depth() {
        val s = tree()
        val rows = SchedulerDomain.visibleRows(s).associateBy { it.occurrence.cellId }
        val root = rows.getValue(WellKnownIds.ROOT_CELL)
        assertNull(root.parent, "the first list's rows hang under nothing")
        assertEquals(0, root.depth)

        val book = rows.getValue(s.cellTitled("Book"))
        // The root row is not a via, so this is the one parent a child's via could never have named.
        assertNull(book.occurrence.renderVia)
        assertEquals(root.occurrence, book.parent)
        assertEquals(1, book.depth)

        val chapter = rows.getValue(s.cellTitled("Chapter"))
        assertEquals(book.occurrence, chapter.parent)
        assertEquals(2, chapter.depth)

        val page = rows.getValue(s.cellTitled("Page"))
        assertEquals(chapter.occurrence, page.parent)
        assertEquals(VisibleOccurrence(page.occurrence.cellId, chapter.occurrence.cellId), page.occurrence)
        assertEquals(3, page.depth)

        assertEquals(root.occurrence, rows.getValue(s.cellTitled("Notes")).parent)
    }

    /**
     * The release account's shape (a mirrored "english" drawn twice): below a mirror's first level, the SAME
     * occurrence is drawn once per mirror. Only the path tells the two rows apart — keyed by occurrence, the
     * tree took the empty row under the first "writing" to be where its twin further down was, and pinned
     * "writing" over "eye incision".
     */
    @Test
    fun a_mirrored_sub_tree_repeats_occurrences_but_never_a_path() {
        var s = tree()
        val notesCell = s.cellTitled("Notes")
        val book = s.cells[s.cellTitled("Book")]!!.taskId!!
        val notesList = s.tasks[s.cells[notesCell]!!.taskId!!]!!.childListId!!
        val mirror = s.lists[notesList]!!.cellIds.first()
        s = SchedulerReducer.reduce(s, SchedulerIntent.AssignTaskId(mirror, book))
        s = SchedulerReducer.reduce(s, SchedulerIntent.ToggleExpand(notesCell))
        s = SchedulerReducer.reduce(s, SchedulerIntent.ToggleExpand(mirror))

        val rows = SchedulerDomain.visibleRows(s)
        val pages = rows.filter { it.occurrence.cellId == s.cellTitled("Page") }
        assertEquals(2, pages.size, "Page is drawn under both Book cells")
        assertEquals(pages[0].occurrence, pages[1].occurrence, "and the occurrence cannot tell them apart")
        assertEquals(rows.size, rows.map { it.path }.toSet().size, "every row has its own path")
        rows.forEach { row ->
            assertEquals(row.depth + 1, row.path.size)
            assertEquals(row.occurrence.cellId, row.path.last())
            val parent = row.parent ?: return@forEach
            assertEquals(parent.cellId, row.path[row.path.size - 2], "the path's last-but-one cell is the parent's")
        }
    }
}
