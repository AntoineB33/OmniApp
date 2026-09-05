package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.CellId
import org.example.project.scheduler.model.CellListId
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState
import org.example.project.scheduler.ui.priorityWeightTableRows

/**
 * PRD §5 the priority-weight table's **optional rows**: the trailing add row, what its identity menu may
 * offer, and the one intent behind adding, re-pointing and removing a row.
 */
class PriorityWeightTableRowTest {

    /**
     * ```
     * root ─ Book  ─ Chapter ─ Write
     *      │       └ Other
     *      └ Notes ─ Read
     * ```
     */
    private class Fixture(
        val state: SchedulerState,
        val book: TaskId,
        val bookList: CellListId,
        val chapter: TaskId,
        val write: TaskId,
        val read: TaskId,
    )

    private fun fixture(): Fixture {
        var s = SchedulerState.empty()
        val rootCells = { s.lists[s.rootListId]!!.cellIds }

        val bookCell = rootCells()[0]
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(bookCell, "Book"))
        val notesCell = rootCells()[1]
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(notesCell, "Notes"))

        val book = s.cells[bookCell]!!.taskId!!
        val bookList = s.tasks[book]!!.childListId!!
        val chapterCell = s.lists[bookList]!!.cellIds[0]
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(chapterCell, "Chapter"))
        val otherCell = s.lists[bookList]!!.cellIds[1]
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(otherCell, "Other"))

        val chapter = s.cells[chapterCell]!!.taskId!!
        val chapterList = s.tasks[chapter]!!.childListId!!
        val writeCell = s.lists[chapterList]!!.cellIds[0]
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(writeCell, "Write"))
        val write = s.cells[writeCell]!!.taskId!!

        val notes = s.cells[notesCell]!!.taskId!!
        val notesList = s.tasks[notes]!!.childListId!!
        val readCell = s.lists[notesList]!!.cellIds[0]
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(readCell, "Read"))
        val read = s.cells[readCell]!!.taskId!!

        return Fixture(s, book, bookList, chapter, write, read)
    }

    // ----- the trailing add row -------------------------------------------------------------------

    @Test
    fun the_add_row_is_offered_even_when_the_sub_list_holds_no_empty_cell() {
        val f = fixture()
        // The release account's root list: fourteen cells, every one of them carrying a task — the last a
        // DELETED one, PRD §4's blank title, which leaves the cell exactly where it was. Asking for a cell
        // with no task at all found none, so the table offered no way to add a row.
        val list = f.state.lists[f.bookList]!!
        val populated = list.cellIds.filter { f.state.cells[it]?.taskId != null }
        val s = f.state.copy(lists = f.state.lists + (f.bookList to list.copy(cellIds = populated)))
        assertTrue(s.lists[f.bookList]!!.cellIds.none { s.cells[it]?.taskId == null })

        val rows = priorityWeightTableRows(s, f.bookList)
        assertEquals(1, rows.count { it.isAddRow })
        // It names no cell of the tree: it is the table's own placeholder, not a borrowed one.
        assertTrue(rows.single { it.isAddRow }.let { it.cellId == null && it.taskId == null })
    }

    @Test
    fun the_add_row_names_no_cell_so_the_tree_can_never_take_it_away() {
        val f = fixture()
        val rows = priorityWeightTableRows(f.state, f.bookList)
        assertEquals(1, rows.count { it.isAddRow })
        assertEquals(rows.last(), rows.single { it.isAddRow })
    }

    // ----- what the add row may offer -------------------------------------------------------------

    @Test
    fun the_identity_menu_offers_only_tasks_under_this_lists_own_parent() {
        val f = fixture()
        // "Write" sits under Book (through Chapter), so its share of Book is a thing the table can state.
        assertEquals(
            listOf(f.write),
            SchedulerDomain.eligibleWeightTableTaskIds(f.state, f.bookList, "Write"),
        )
        // "Read" lives under Notes: it has no occurrence under Book at all, so it states nothing here —
        // and this is the predicate the intent itself enforces, so a refused pick is never offered.
        assertTrue(SchedulerDomain.eligibleWeightTableTaskIds(f.state, f.bookList, "Read").isEmpty())
        // A member cell of this very list already has its own row, with its own weight.
        assertTrue(SchedulerDomain.eligibleWeightTableTaskIds(f.state, f.bookList, "Chapter").isEmpty())
        // The list's own parent task is what the shares are measured INSIDE.
        assertTrue(SchedulerDomain.eligibleWeightTableTaskIds(f.state, f.bookList, "Book").isEmpty())
    }

    @Test
    fun a_task_already_in_the_table_is_no_longer_offered() {
        val f = fixture()
        val s = SchedulerReducer.reduce(
            f.state,
            SchedulerIntent.SetPriorityWeightTableRow(f.bookList, taskId = f.write),
        )
        assertTrue(SchedulerDomain.eligibleWeightTableTaskIds(s, f.bookList, "Write").isEmpty())
        // Except to the row that already names it: that is the answer the row is giving, so it stays a live
        // one while the row is edited — which is what keeps the row's own colour on screen as its title
        // sits in the field.
        assertEquals(
            listOf(f.write),
            SchedulerDomain.eligibleWeightTableTaskIds(s, f.bookList, "Write", replacing = f.write),
        )
    }

    // ----- the row's identity: add, re-point, remove ----------------------------------------------

    @Test
    fun adding_a_row_seeds_it_at_zero_in_every_column() {
        val f = fixture()
        var s = SchedulerReducer.reduce(f.state, SchedulerIntent.AddPriorityColumn(f.bookList))
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetPriorityWeightTableRow(f.bookList, taskId = f.write))
        val list = s.lists[f.bookList]!!
        assertEquals(setOf(f.write), list.optionalTaskIds)
        assertEquals(List(list.weightColumns.size) { 0.0 }, list.optionalTaskValues[f.write])
    }

    @Test
    fun a_row_is_removed_by_emptying_it_and_that_is_one_history_unit() {
        val f = fixture()
        var s = SchedulerReducer.reduce(f.state, SchedulerIntent.SetPriorityWeightTableRow(f.bookList, taskId = f.write))
        val units = s.histories.main.units.size

        s = SchedulerReducer.reduce(s, SchedulerIntent.SetPriorityWeightTableRow(f.bookList, replacing = f.write))
        assertEquals(emptySet(), s.lists[f.bookList]!!.optionalTaskIds)
        assertEquals(emptyMap(), s.lists[f.bookList]!!.optionalTaskValues)
        assertEquals(units + 1, s.histories.main.units.size)
        assertEquals("Remove table row", s.histories.main.units.last().delta.label)

        // The add and its inverse are undoable the same way.
        s = SchedulerReducer.reduce(s, SchedulerIntent.Undo)
        assertEquals(setOf(f.write), s.lists[f.bookList]!!.optionalTaskIds)
    }

    @Test
    fun re_pointing_a_row_replaces_it_in_one_unit() {
        val f = fixture()
        var s = SchedulerReducer.reduce(f.state, SchedulerIntent.SetPriorityWeightTableRow(f.bookList, taskId = f.write))
        val units = s.histories.main.units.size

        s = SchedulerReducer.reduce(
            s,
            SchedulerIntent.SetPriorityWeightTableRow(f.bookList, replacing = f.write, taskId = f.chapter),
        )
        assertEquals(setOf(f.chapter), s.lists[f.bookList]!!.optionalTaskIds)
        // The value belonged to the task the row named, never to the row's place in the table.
        assertEquals(listOf(0.0), s.lists[f.bookList]!!.optionalTaskValues[f.chapter])
        assertEquals(units + 1, s.histories.main.units.size)
        assertEquals("Change table row", s.histories.main.units.last().delta.label)
    }

    @Test
    fun a_row_that_changes_nothing_records_no_history_unit() {
        val f = fixture()
        val s = SchedulerReducer.reduce(f.state, SchedulerIntent.SetPriorityWeightTableRow(f.bookList, taskId = f.write))
        val before = s.histories

        // A task with no occurrence under this list's parent states nothing here.
        val refused = SchedulerReducer.reduce(s, SchedulerIntent.SetPriorityWeightTableRow(f.bookList, taskId = f.read))
        assertEquals(before, refused.histories)
        assertEquals(setOf(f.write), refused.lists[f.bookList]!!.optionalTaskIds)

        // A row the table no longer holds is a stale press, never a silent add under another name.
        val stale = SchedulerReducer.reduce(
            s,
            SchedulerIntent.SetPriorityWeightTableRow(f.bookList, replacing = f.chapter, taskId = f.chapter),
        )
        assertEquals(before, stale.histories)
        assertEquals(setOf(f.write), stale.lists[f.bookList]!!.optionalTaskIds)

        // Picking the task the row already names.
        val same = SchedulerReducer.reduce(
            s,
            SchedulerIntent.SetPriorityWeightTableRow(f.bookList, replacing = f.write, taskId = f.write),
        )
        assertEquals(before, same.histories)
        assertEquals(setOf(f.write), same.lists[f.bookList]!!.optionalTaskIds)
    }

    @Test
    fun an_optional_row_is_drawn_under_an_id_of_its_own_never_a_cell_of_the_tree() {
        val f = fixture()
        val s = SchedulerReducer.reduce(f.state, SchedulerIntent.SetPriorityWeightTableRow(f.bookList, taskId = f.write))
        val rows = priorityWeightTableRows(s, f.bookList, s.lists[f.bookList]!!.optionalTaskIds)
        val ids = rows.map { org.example.project.scheduler.ui.priorityWeightRowId(f.bookList, it) }
        // Every row has its own identity, and the two the table owns are not cells of the tree.
        assertEquals(ids.size, ids.distinct().size)
        for (row in rows.filter { it.isOptional }) {
            val id: CellId = org.example.project.scheduler.ui.priorityWeightRowId(f.bookList, row)
            assertFalse(id in s.cells)
        }
    }
}
