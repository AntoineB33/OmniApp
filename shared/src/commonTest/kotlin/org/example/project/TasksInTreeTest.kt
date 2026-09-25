package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.state.CellEditMode
import org.example.project.scheduler.state.EditExitNavigation
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState

/**
 * [SchedulerDomain.tasksInTree]: which tasks the tree actually holds — the membership the period edit window's
 * rows are drawn from, and the same walk "go to task" / "go to task tree" ask.
 */
class TasksInTreeTest {

    /**
     * "Write" is ONE task held by TWO cells under different parents.
     *
     * ```
     * root ─ Book  ─ Chapter ─ Write
     *      │       │         └ Draft
     *      │       └ Other
     *      └ Notes ─ Read
     *              └ Write   (the same task as under Chapter)
     * ```
     */
    private fun fixture(): Pair<SchedulerState, TaskId> {
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
        val writeUnderChapter = s.lists[chapterList]!!.cellIds[0]
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(writeUnderChapter, "Write"))
        val write = s.cells[writeUnderChapter]!!.taskId!!
        val draftCell = s.lists[chapterList]!!.cellIds[1]
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(draftCell, "Draft"))

        val notes = s.cells[notesCell]!!.taskId!!
        val notesList = s.tasks[notes]!!.childListId!!
        val readCell = s.lists[notesList]!!.cellIds[0]
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(readCell, "Read"))
        val writeUnderNotes = s.lists[notesList]!!.cellIds[1]
        s = SchedulerReducer.reduce(s, SchedulerIntent.AssignTaskId(writeUnderNotes, write))

        return s to write
    }

    private fun titles(state: SchedulerState, ids: Set<TaskId>) = ids.map { state.tasks[it]?.title }

    private fun cellOfTitle(state: SchedulerState, title: String) =
        state.cells.values.first { cell -> cell.taskId?.let { state.tasks[it]?.title } == title }.id

    @Test
    fun aMirroredTaskIsHeldOnce() {
        val (state, write) = fixture()
        val held = SchedulerDomain.tasksInTree(state)
        // Eight populated cells, seven tasks.
        assertEquals(7, held.size)
        assertTrue(write in held)
    }

    @Test
    fun aDeletedTaskIsNotHeld() {
        val (built, _) = fixture()
        // PRD §4 Deletion: emptying a cell blanks its task's title; it may linger for its records.
        val state = SchedulerReducer.reduce(built, SchedulerIntent.SetCellTitle(cellOfTitle(built, "Draft"), ""))
        val held = SchedulerDomain.tasksInTree(state)
        assertTrue(titles(state, held).none { it == "Draft" || it.isNullOrBlank() }, "${titles(state, held)}")
    }

    /**
     * PRD §4/§7: a task **stranded inside a detached parent's sub-tree** still has a cell, but the tree cannot
     * show it anywhere — so it is not held, the same answer that greys its "go to task".
     */
    @Test
    fun aTaskStrandedUnderADetachedParentIsNotHeld() {
        val (built, _) = fixture()
        // Re-point the "Chapter" cell at a brand-new task: Chapter keeps its sub-list but loses its only cell.
        val chapterCell = cellOfTitle(built, "Chapter")
        var s = SchedulerReducer.reduce(built, SchedulerIntent.BeginEdit(chapterCell))
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetEditMode(CellEditMode.ChangeTask))
        s = SchedulerReducer.reduce(s, SchedulerIntent.SelectCreateAssignTask)
        s = SchedulerReducer.reduce(s, SchedulerIntent.ExitEdit(EditExitNavigation.Stay))

        val draft = built.cells[cellOfTitle(built, "Draft")]!!.taskId!!
        assertTrue(s.cells.values.any { it.taskId == draft }, "the stranded cell must survive")
        assertNull(SchedulerDomain.firstTaskOccurrence(s, draft))
        val held = SchedulerDomain.tasksInTree(s)
        assertTrue(draft !in held, "a stranded task must not be held: ${titles(s, held)}")
        val occurrences = SchedulerDomain.firstTaskOccurrences(s)
        assertTrue(held.all { occurrences[it] != null }, "every held task must have a first occurrence")
    }

    @Test
    fun anEmptyTreeHoldsNothing() {
        assertEquals(emptySet(), SchedulerDomain.tasksInTree(SchedulerState.empty()))
    }
}
