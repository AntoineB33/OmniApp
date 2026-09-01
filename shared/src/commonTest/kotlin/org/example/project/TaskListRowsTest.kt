package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.CellId
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.state.CellEditMode
import org.example.project.scheduler.state.HistoryCategory
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState
import org.example.project.scheduler.state.TASK_LIST_ROOT_ID
import org.example.project.scheduler.state.projectTaskList

/**
 * PRD §7 **All tasks**, the rows: that window draws the *task tree's own cells*, re-rooted at a synthetic
 * list holding one cell per task in the sorter's order
 * ([org.example.project.scheduler.state.projectTaskList]), with its intents wrapped in
 * [SchedulerIntent.InTaskList].
 *
 * What this pins is everything that follows from the root being the sorter's order and not the tree's: an
 * edit there is an edit to the tree (one Main unit) but never moves the tree's caret or its open rows; a
 * root row is always renaming; nothing may be dropped into the root; the synthetic list never escapes the
 * projection; and the re-rooting must not make the real root's own cells read as detached.
 */
class TaskListRowsTest {

    /**
     * ```
     * root ─ Book  ─ Chapter ─ Write     (Write is ONE task under two parents)
     *      │       └ Other
     *      └ Notes ─ Read
     *              └ Write
     * ```
     */
    private fun fixture(): SchedulerState {
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
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(s.lists[bookList]!!.cellIds[1], "Other"))

        val chapter = s.cells[chapterCell]!!.taskId!!
        val chapterList = s.tasks[chapter]!!.childListId!!
        val writeCell = s.lists[chapterList]!!.cellIds[0]
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(writeCell, "Write"))
        val write = s.cells[writeCell]!!.taskId!!

        val notes = s.cells[notesCell]!!.taskId!!
        val notesList = s.tasks[notes]!!.childListId!!
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(s.lists[notesList]!!.cellIds[0], "Read"))
        s = SchedulerReducer.reduce(
            s,
            SchedulerIntent.AssignTaskId(s.lists[notesList]!!.cellIds[1], write),
        )
        return s
    }

    /** The window's rows, in the sorter's default order — exactly what `TaskListWindow` builds. */
    private fun rowsOf(state: SchedulerState): List<CellId> {
        val occurrences = SchedulerDomain.firstTaskOccurrences(state)
        return SchedulerDomain.taskListEntries(state).mapNotNull { occurrences[it.taskId]?.cellId }
    }

    private fun taskOf(state: SchedulerState, title: String): TaskId =
        state.tasks.values.first { it.title == title }.id

    private fun rowOf(state: SchedulerState, title: String): CellId =
        SchedulerDomain.firstTaskOccurrences(state)[taskOf(state, title)]!!.cellId

    private fun inWindow(state: SchedulerState, inner: SchedulerIntent): SchedulerState =
        SchedulerReducer.reduce(state, SchedulerIntent.InTaskList(inner, rowsOf(state)))

    // ----- the rows themselves -------------------------------------------------------------------

    @Test
    fun everyRowIsOneOfTheTreesOwnCells() {
        val state = fixture()
        val rows = rowsOf(state)

        // One row per listed task, and each is a real cell of the live tree — never a synthetic one, which
        // is what keeps every occurrence count honest (they are read off `state.cells`).
        assertEquals(SchedulerDomain.taskListEntries(state).size, rows.size)
        assertEquals(rows.size, rows.toSet().size, "a task must not take two rows")
        for (cellId in rows) assertNotNull(state.cells[cellId], "row $cellId is not a live cell")
    }

    @Test
    fun theBulkWalkAgreesWithTheSingleOne() {
        val state = fixture()
        val all = SchedulerDomain.firstTaskOccurrences(state)

        // The window asks for every task at once (one walk, not one per row); "go to task tree" asks for
        // one. The two must never disagree about which cell "the first occurrence" is.
        for (entry in SchedulerDomain.taskListEntries(state)) {
            assertEquals(
                SchedulerDomain.firstTaskOccurrence(state, entry.taskId),
                all[entry.taskId],
                "first occurrence of ${entry.title}",
            )
        }
    }

    // ----- an edit in the window is an edit to the tree ------------------------------------------

    @Test
    fun renamingARowRenamesTheTaskAsOneMainUnit() {
        val state = fixture()
        val book = taskOf(state, "Book")
        val mainBefore = state.histories.forCategory(HistoryCategory.Main).units.size

        val next = inWindow(state, SchedulerIntent.SetCellTitle(rowOf(state, "Book"), "Booklet"))

        assertEquals("Booklet", next.tasks[book]?.title)
        // One gesture, one unit — the inner reduction's own units evaporate with the projection.
        assertEquals(
            mainBefore + 1,
            next.histories.forCategory(HistoryCategory.Main).units.size,
        )
        assertEquals("All tasks", next.histories.forCategory(HistoryCategory.Main).units.last().delta.label)
    }

    @Test
    fun theSyntheticRootNeverEscapesTheProjection() {
        val state = fixture()
        val edited = inWindow(state, SchedulerIntent.SetCellTitle(rowOf(state, "Book"), "Booklet"))

        assertTrue(TASK_LIST_ROOT_ID !in edited.lists, "the window's own list must never reach the state")
        assertEquals(state.rootListId, edited.rootListId)
        // ... nor a history unit, which would put it back on a later undo and then persist and sync it.
        val delta = edited.histories.forCategory(HistoryCategory.Main).units.last().delta
        val undone = delta.undo(edited)
        assertTrue(TASK_LIST_ROOT_ID !in undone.lists)
    }

    @Test
    fun theWindowsCaretIsNotTheTrees() {
        val state = fixture()
        val row = rowOf(state, "Book")

        val next = inWindow(state, SchedulerIntent.BeginEdit(row))

        // The window has a session; the tree has none, and its selection has not moved.
        assertNotNull(next.taskListEditSession)
        assertNull(next.editSession)
        assertEquals(state.selection, next.selection)
        assertEquals(row, next.taskListSelection.main)
    }

    @Test
    fun aRootRowIsAlwaysRenaming() {
        val state = fixture()

        // The row IS the task, and the order is the sorter's, so "change task" there could only re-point a
        // cell the user is not looking at. The window shows no Mode selector; this is what makes it true.
        val onRoot = inWindow(state, SchedulerIntent.BeginEdit(rowOf(state, "Book")))
        assertEquals(CellEditMode.Rename, onRoot.taskListEditSession?.mode)

        // A cell that is NOT one of the window's rows — here Write's second occurrence, under Notes — is an
        // ordinary cell again: it opens on PRD §4's default, Mode selector and all.
        val notesList = state.tasks[taskOf(state, "Notes")]!!.childListId!!
        val secondWrite = state.lists[notesList]!!.cellIds[1]
        assertTrue(secondWrite !in rowsOf(state), "the window lists Write's FIRST occurrence, not this one")
        val onChild = inWindow(state, SchedulerIntent.BeginEdit(secondWrite))
        assertEquals(CellEditMode.ChangeTask, onChild.taskListEditSession?.mode)
    }

    // ----- the root's order is the sorter's ------------------------------------------------------

    @Test
    fun nothingCanBeMovedIntoTheRoot() {
        val state = fixture()
        val bookRow = rowOf(state, "Book")
        // Select a sub cell, then try to drop it at root level.
        val withSelection =
            inWindow(
                inWindow(state, SchedulerIntent.ToggleExpand(bookRow)),
                SchedulerIntent.ClickCell(
                    cellId = rowOf(state, "Chapter"),
                    ctrl = false,
                    shift = false,
                    visibleOrder = emptyList(),
                ),
            )

        val attempted =
            inWindow(withSelection, SchedulerIntent.MoveSelectedCells(bookRow, insertBefore = true))

        // Refused outright: the order is the sorter's, so a drop there would be a reordering the next
        // re-sort silently undoes.
        assertSame(withSelection, attempted)
    }

    // ----- re-rooting must not make the real root read as detached -------------------------------

    @Test
    fun aRootCellThatIsNotAFirstOccurrenceSurvivesTheWindow() {
        // root ─ Book ─ Write        <- the first occurrence of Write
        //      └ Write               <- a real root cell that the window's list therefore does NOT hold
        var s = SchedulerState.empty()
        val bookCell = s.lists[s.rootListId]!!.cellIds[0]
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(bookCell, "Book"))
        val bookList = s.tasks[s.cells[bookCell]!!.taskId!!]!!.childListId!!
        val nestedWrite = s.lists[bookList]!!.cellIds[0]
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(nestedWrite, "Write"))
        val write = s.cells[nestedWrite]!!.taskId!!
        val rootWrite = s.lists[s.rootListId]!!.cellIds[1]
        s = SchedulerReducer.reduce(s, SchedulerIntent.AssignTaskId(rootWrite, write))

        val rows = rowsOf(s)
        assertEquals(nestedWrite, SchedulerDomain.firstTaskOccurrences(s)[write]?.cellId)
        assertTrue(rootWrite !in rows, "the window lists the FIRST occurrence, so not this cell")

        // The cleanup pass every edit boundary runs must still reach it. Reachability walks from the
        // state's root — which the projection has re-pointed at the window's own list — so without
        // MAIN_LIST being seeded too, this cell would be pruned out of the tree by the first edit here.
        val pruned = SchedulerDomain.pruneDetachedTree(s.projectTaskList(rows))
        assertTrue(rootWrite in pruned.cells, "a real root cell must not read as detached")

        // ... and through a real gesture: a move is one of the boundaries that runs the cleanup.
        val moved =
            inWindow(
                inWindow(
                    s,
                    SchedulerIntent.ClickCell(
                        cellId = nestedWrite,
                        ctrl = false,
                        shift = false,
                        visibleOrder = emptyList(),
                    ),
                ),
                SchedulerIntent.MoveSelectedCells(nestedWrite, insertBefore = true),
            )
        assertTrue(rootWrite in moved.cells)
    }

    // ----- collapse a sub-tree -------------------------------------------------------------------

    @Test
    fun collapsingASubtreeClosesThisCellAndEveryDescendant() {
        val state = fixture()
        val bookRow = rowOf(state, "Book")
        val chapterRow = rowOf(state, "Chapter")
        val notesRow = rowOf(state, "Notes")

        val expanded = state.copy(expanded = setOf(bookRow, chapterRow))
        val collapsed = SchedulerReducer.reduce(expanded, SchedulerIntent.CollapseSubtree(bookRow))

        assertTrue(bookRow !in collapsed.expanded)
        assertTrue(chapterRow !in collapsed.expanded)
        assertEquals(state.expanded, collapsed.expanded)
        assertEquals(state.captureTree(), collapsed.captureTree())

        val nested = SchedulerReducer.reduce(state.copy(expanded = setOf(notesRow)), SchedulerIntent.CollapseSubtree(notesRow))
        assertTrue(notesRow !in nested.expanded)
        assertEquals(state.expanded, nested.expanded)
    }

    // ----- collapse all --------------------------------------------------------------------------

    @Test
    fun collapseAllClosesTheWindowsRowsAndOnlyThose() {
        val state = fixture()
        val bookRow = rowOf(state, "Book")

        val opened = inWindow(state, SchedulerIntent.ToggleExpand(bookRow))
        assertTrue(bookRow in opened.taskListExpanded)
        // Opening a row in the window is not opening it in the tree, and records no history unit at all.
        assertEquals(state.expanded, opened.expanded)
        assertEquals(state.captureTree(), opened.captureTree())
        assertEquals(
            state.histories.forCategory(HistoryCategory.Main).units.size,
            opened.histories.forCategory(HistoryCategory.Main).units.size,
        )

        val collapsed = SchedulerReducer.reduce(opened, SchedulerIntent.CollapseTaskListRows)
        assertEquals(emptySet<CellId>(), collapsed.taskListExpanded)
        assertEquals(state.expanded, collapsed.expanded)
        // Local view state, like the sorter beside it: no unit, so Ctrl+Z does not re-open the rows.
        assertEquals(
            opened.histories.forCategory(HistoryCategory.Main).units.size,
            collapsed.histories.forCategory(HistoryCategory.Main).units.size,
        )
        assertSame(collapsed, SchedulerReducer.reduce(collapsed, SchedulerIntent.CollapseTaskListRows))
    }
}
