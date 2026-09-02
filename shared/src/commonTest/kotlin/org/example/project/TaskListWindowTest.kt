package org.example.project

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.domain.SchedulerDomain.TaskListSort
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.state.CellEditMode
import org.example.project.scheduler.state.EditExitNavigation
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState

/**
 * PRD §7 lateral menu, **All tasks**: the flat list of every task in the tree, and the two figures its
 * sorter orders it by — a task's number of occurrences, and its absolute priority percentage.
 */
class TaskListWindowTest {

    /**
     * The same shape [RelativePriorityTest] uses, because it is the one that makes both columns
     * interesting: "Write" is ONE task held by TWO cells under different parents, so it is a single row
     * with two occurrences whose priority is the sum of both chains.
     *
     * ```
     * root ─ Book  ─ Chapter ─ Write     50% ─ 25% ─ 12.5%
     *      │       │         └ Draft                 12.5%
     *      │       └ Other          25%
     *      └ Notes ─ Read      50% ─ 25%
     *              └ Write           25%   (the same task as under Chapter)
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

        // The second occurrence is the SAME task (assigned), not another task with the same title.
        val notes = s.cells[notesCell]!!.taskId!!
        val notesList = s.tasks[notes]!!.childListId!!
        val readCell = s.lists[notesList]!!.cellIds[0]
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(readCell, "Read"))
        val writeUnderNotes = s.lists[notesList]!!.cellIds[1]
        s = SchedulerReducer.reduce(s, SchedulerIntent.AssignTaskId(writeUnderNotes, write))

        return s to write
    }

    private fun titles(entries: List<SchedulerDomain.TaskListEntry>) = entries.map { it.title }

    private fun cellOfTitle(state: SchedulerState, title: String) =
        state.cells.values.first { cell -> cell.taskId?.let { state.tasks[it]?.title } == title }.id

    @Test
    fun mirroredTaskIsOneRowCarryingBothOccurrences() {
        val (state, write) = fixture()
        val entries = SchedulerDomain.taskListEntries(state)

        // Eight populated cells, seven tasks: "Write" is listed ONCE, not once per cell.
        assertEquals(7, entries.size)
        val writeRow = entries.single { it.taskId == write }
        assertEquals("Write", writeRow.title)
        assertEquals(2, writeRow.occurrences)
        // ...and its priority is the sum over both chains: 12.5% under Chapter + 25% under Notes.
        assertTrue(
            abs(0.375 - writeRow.priority) <= 1e-9,
            "expected Write at 37.5% but was ${writeRow.priority}",
        )
        assertEquals(1, entries.single { it.title == "Book" }.occurrences)
    }

    @Test
    fun eachRowShowsTheSamePriorityTheTreeShows() {
        val (state, _) = fixture()
        val absolute = SchedulerDomain.absoluteTaskPriorities(state)

        // The window is a readout of the tree on screen, so every row must agree with the percentage
        // column of that same tree — never with the scheduler's blended (keyframe) view.
        for (entry in SchedulerDomain.taskListEntries(state)) {
            assertEquals(absolute[entry.taskId], entry.priority, "priority of ${entry.title}")
        }
    }

    @Test
    fun sortsByPriorityHighestFirst() {
        val (state, _) = fixture()
        // 50 / 50 / 37.5 / 25 / 25 / 25 / 12.5 — ties (Book vs Notes, Chapter vs Other vs Read) fall back
        // to the title, so the order is total and cannot shuffle between recompositions.
        assertEquals(
            listOf("Book", "Notes", "Write", "Chapter", "Other", "Read", "Draft"),
            titles(SchedulerDomain.taskListEntries(state, TaskListSort.Priority, descending = true)),
        )
    }

    @Test
    fun theDirectionReversesTheFigureOnly() {
        val (state, _) = fixture()
        val down = SchedulerDomain.taskListEntries(state, TaskListSort.Priority, descending = true)
        val up = SchedulerDomain.taskListEntries(state, TaskListSort.Priority, descending = false)

        assertEquals(down.map { it.priority }.reversed(), up.map { it.priority })
        assertEquals("Draft", up.first().title)
        // The tie-break is NOT reversed: equal figures stay alphabetical whichever way the list runs, so
        // flipping the direction never re-shuffles a block of tasks that share one percentage.
        assertEquals(listOf("Chapter", "Other", "Read"), titles(up).subList(1, 4))
    }

    @Test
    fun sortsByOccurrences() {
        val (state, _) = fixture()

        val down = titles(SchedulerDomain.taskListEntries(state, TaskListSort.Occurrences, descending = true))
        val up = titles(SchedulerDomain.taskListEntries(state, TaskListSort.Occurrences, descending = false))
        assertEquals("Write", down.first())
        assertEquals("Write", up.last())
        // Everything else has exactly one occurrence, so the whole tail is the alphabetical tie-break.
        assertEquals(listOf("Book", "Chapter", "Draft", "Notes", "Other", "Read"), down.drop(1))
    }

    @Test
    fun aDeletedTaskLeavesTheList() {
        val (built, _) = fixture()
        val draftCell = cellOfTitle(built, "Draft")

        // PRD §4 Deletion: emptying a cell blanks its task's title. A blank-titled task is not part of the
        // tree any more (it may still linger, kept alive by its records), so it must not be listed.
        val state = SchedulerReducer.reduce(built, SchedulerIntent.SetCellTitle(draftCell, ""))

        val entries = SchedulerDomain.taskListEntries(state)
        assertTrue(entries.none { it.title == "Draft" }, "a deleted task must not be listed: ${titles(entries)}")
        assertTrue(entries.none { it.title.isBlank() }, "no blank row: ${titles(entries)}")
    }

    /**
     * PRD §4/§7: a task **stranded inside a detached parent's sub-tree** still has a cell, and is still
     * offered by Edit Mode's Tasks menu (picking it is how it is pulled back), but the tree cannot show it
     * anywhere — so it is not in the list, and its "go to task" is greyed. The two answers are the SAME
     * walk; before this, "has a populated cell" listed it here and `TaskListWindow` then dropped it again
     * with no row, so a task could be counted by the sort and be invisible in the window it sorted.
     */
    @Test
    fun aTaskStrandedUnderADetachedParentIsNotInTheList() {
        val (built, _) = fixture()
        // Re-point the "Chapter" cell at a brand-new task: Chapter keeps its sub-list (Write, Draft) but
        // loses its only cell, so it becomes a detached parent and "Draft" is left with nowhere to be shown.
        val chapterCell = cellOfTitle(built, "Chapter")
        var s = SchedulerReducer.reduce(built, SchedulerIntent.BeginEdit(chapterCell))
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetEditMode(CellEditMode.ChangeTask))
        s = SchedulerReducer.reduce(s, SchedulerIntent.SelectCreateAssignTask)
        s = SchedulerReducer.reduce(s, SchedulerIntent.ExitEdit(EditExitNavigation.Stay))

        val draft = built.cells[cellOfTitle(built, "Draft")]!!.taskId!!
        // The cell survives — this is not a deletion, and the sub-tree comes back with the id...
        assertTrue(s.cells.values.any { it.taskId == draft }, "the stranded cell must survive")
        // ...but nothing reachable from the root reaches it, so it is not in the tree and has no row.
        assertNull(SchedulerDomain.firstTaskOccurrence(s, draft))
        val entries = SchedulerDomain.taskListEntries(s)
        assertTrue(entries.none { it.title == "Draft" }, "a stranded task must not be listed: ${titles(entries)}")
        // Every listed task has the row cell the window asks for: the list can never hold a rowless entry.
        val occurrences = SchedulerDomain.firstTaskOccurrences(s)
        assertTrue(
            entries.all { occurrences[it.taskId] != null },
            "every listed task must have a first occurrence: ${titles(entries)}",
        )
    }

    @Test
    fun anEmptyTreeListsNothing() {
        assertEquals(emptyList(), SchedulerDomain.taskListEntries(SchedulerState.empty()))
    }
}
