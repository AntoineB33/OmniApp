package org.example.project

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.TaskColorSpace
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState

/**
 * **The task colours.** One colour space handed down the tree: a list's arc split between its cells in
 * proportion to the childless tasks each one's sub-tree holds, a cell's colour the average of its own arc,
 * and that arc the space its sub-list then divides.
 *
 * Everything here is arithmetic on hue fractions — what a hue *looks like* is
 * `org.example.project.ui.TaskPalette`, and is deliberately not pinned by these tests.
 */
class TaskColorSpaceTest {

    /**
     * The shape [TaskListWindowTest] and [RelativePriorityTest] use, because it is the one that makes the
     * partition interesting: the branches are of unequal width (3 childless tasks against 2) and "Write" is
     * ONE task held by TWO cells under different parents.
     *
     *     root ─ Book  ─ Chapter ─ Write        3 leaves ─ 2 ─ 1
     *          │       │         └ Draft                      1
     *          │       └ Other                             1
     *          └ Notes ─ Read                   2 leaves ─ 1
     *                  └ Write                             1   (the same task as under Chapter)
     */
    private fun fixture(): Pair<SchedulerState, Map<String, TaskId>> {
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
        val draftCell = s.lists[chapterList]!!.cellIds[1]
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(draftCell, "Draft"))

        val notes = s.cells[notesCell]!!.taskId!!
        val notesList = s.tasks[notes]!!.childListId!!
        val readCell = s.lists[notesList]!!.cellIds[0]
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(readCell, "Read"))
        // The second occurrence is the SAME task (assigned), not another task with the same title.
        val writeUnderNotes = s.lists[notesList]!!.cellIds[1]
        s = SchedulerReducer.reduce(s, SchedulerIntent.AssignTaskId(writeUnderNotes, write))

        val byTitle =
            listOf("Book", "Notes", "Chapter", "Other", "Write", "Draft", "Read").associateWith { title ->
                s.tasks.values.first { it.title == title }.id
            }
        return s to byTitle
    }

    private fun assertHue(expected: Double, actual: Double, what: String) =
        assertTrue(abs(expected - actual) <= 1e-9, "$what: expected hue $expected but was $actual")

    /**
     * The whole rule on one tree. Book holds 3 childless tasks and Notes 2, so the circle splits 3:2 — NOT
     * in half — and each of the five leaves ends up with the same fifth of it.
     */
    @Test
    fun eachListSplitsItsArcByTheChildlessTasksBelowIt() {
        val (state, ids) = fixture()
        val hues = TaskColorSpace.hues(state)

        // Root: Book takes [0, 0.6), Notes [0.6, 1) — each the average of its own arc.
        assertHue(0.3, hues[ids["Book"]]!!.hue, "Book")
        assertHue(0.8, hues[ids["Notes"]]!!.hue, "Notes")
        // Book's arc [0, 0.6) split 2:1 between Chapter and Other.
        assertHue(0.2, hues[ids["Chapter"]]!!.hue, "Chapter")
        assertHue(0.5, hues[ids["Other"]]!!.hue, "Other")
        // Chapter's arc [0, 0.4) split evenly between its two leaves.
        assertHue(0.1, hues[ids["Write"]]!!.hue, "Write")
        assertHue(0.3, hues[ids["Draft"]]!!.hue, "Draft")
        // Notes' arc [0.6, 1.0): Read takes [0.6, 0.8), the mirrored Write [0.8, 1.0).
        assertHue(0.7, hues[ids["Read"]]!!.hue, "Read")

        // Depth is the row's nesting level, counted from the root list.
        assertEquals(0, hues[ids["Book"]]!!.depth)
        assertEquals(1, hues[ids["Chapter"]]!!.depth)
        assertEquals(2, hues[ids["Draft"]]!!.depth)
    }

    /**
     * A mirrored task is ONE colour: the first occurrence reached names it, and the second keeps its share
     * of its own parent's arc (so its siblings' widths stay proportional) without re-dividing it.
     */
    @Test
    fun aMirroredTaskKeepsOneColour() {
        val (state, ids) = fixture()
        val hues = TaskColorSpace.hues(state)

        // Write is under Chapter AND under Notes; it is coloured once, by the first of the two reached.
        assertHue(0.1, hues[ids["Write"]]!!.hue, "Write")
        assertEquals(2, hues[ids["Write"]]!!.depth)
        // Its second occurrence still CONSUMED half of Notes' arc — which is why Read got [0.6, 0.8) and
        // not the whole of it.
        assertHue(0.7, hues[ids["Read"]]!!.hue, "Read")
    }

    /**
     * Siblings can never share a colour (their arcs are disjoint), so the only pairs a hue cannot separate
     * are ancestor/descendant ones — which is the whole reason [TaskColorSpace.TaskHue] carries a depth.
     * Book's arc is [0, 0.6) and Draft's [0.2, 0.4): both average to 0.3.
     */
    @Test
    fun anAncestorAndADescendantCanShareAHueAndAreToldApartByDepth() {
        val (state, ids) = fixture()
        val hues = TaskColorSpace.hues(state)

        val book = hues[ids["Book"]]!!
        val draft = hues[ids["Draft"]]!!
        assertHue(book.hue, draft.hue, "Book vs Draft")
        assertTrue(book.depth != draft.depth, "the depth must separate what the hue cannot")

        // Every sibling pair, on the other hand, is separated by the hue alone.
        assertTrue(hues[ids["Chapter"]]!!.hue != hues[ids["Other"]]!!.hue)
        assertTrue(hues[ids["Write"]]!!.hue != hues[ids["Draft"]]!!.hue)
        assertTrue(hues[ids["Book"]]!!.hue != hues[ids["Notes"]]!!.hue)
    }

    /**
     * An empty placeholder row is not a task the user has: it takes no colour, and — the part that would be
     * silently wrong — it consumes none of the arc its siblings are sharing. Every list carries a trailing
     * empty cell, so this is every tree, not an edge case.
     */
    @Test
    fun emptyCellsNeitherTakeAColourNorConsumeTheArc() {
        val (state, ids) = fixture()
        val hues = TaskColorSpace.hues(state)

        // The root list holds Book, Notes and at least one empty cell...
        assertTrue(
            state.lists[state.rootListId]!!.cellIds.any { state.cells[it]?.taskId == null },
            "the fixture is meant to carry an empty placeholder row",
        )
        // ...and only the seven real tasks are coloured...
        assertEquals(
            setOf("Book", "Notes", "Chapter", "Other", "Write", "Draft", "Read"),
            hues.keys.map { state.tasks[it]!!.title }.toSet(),
        )
        // ...with Book and Notes still dividing the WHOLE circle between them, 3:2.
        assertHue(0.3, hues[ids["Book"]]!!.hue, "Book")
        assertHue(0.8, hues[ids["Notes"]]!!.hue, "Notes")
    }

    /** A tree with nothing in it colours nothing — and does not divide by zero doing it. */
    @Test
    fun anEmptyTreeColoursNothing() {
        assertEquals(emptyMap(), TaskColorSpace.hues(SchedulerState.empty()))
    }
}
