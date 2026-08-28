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
 * **The task colours.** The tasks with an empty sub-tree are spread as far apart as the hue circle allows,
 * in the tree's own depth-first order; every other task then takes the point of its sub-tree's arc furthest
 * from every colour already given out; and wherever several answers tie, the one closest to the previous
 * answer wins.
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
     *     root ─ Book  ─ Chapter ─ Write        leaves, depth-first: Write, Draft, Other, Read
     *          │       │         └ Draft
     *          │       └ Other
     *          └ Notes ─ Read
     *                  └ Write                             (the same task as under Chapter)
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

    /** The circle's shorter way round between two hues. */
    private fun distance(a: Double, b: Double): Double {
        val d = abs(a - b) % 1.0
        return if (d > 0.5) 1.0 - d else d
    }

    /**
     * Rule 1: the four childless tasks take the four quarters of the circle — the arrangement that puts
     * every one of them as far from the others as it can be — in the tree's own depth-first order, so
     * Book's three leaves are a contiguous run and Notes' is the odd one out.
     */
    @Test
    fun theTasksWithAnEmptySubTreeAreSpreadEvenlyAroundTheCircle() {
        val (state, ids) = fixture()
        val hues = TaskColorSpace.hues(state)

        assertHue(0.00, hues[ids["Write"]]!!.hue, "Write")
        assertHue(0.25, hues[ids["Draft"]]!!.hue, "Draft")
        assertHue(0.50, hues[ids["Other"]]!!.hue, "Other")
        assertHue(0.75, hues[ids["Read"]]!!.hue, "Read")

        // Depth is still the row's nesting level, counted from the root list.
        assertEquals(0, hues[ids["Book"]]!!.depth)
        assertEquals(1, hues[ids["Chapter"]]!!.depth)
        assertEquals(2, hues[ids["Draft"]]!!.depth)
    }

    /**
     * Rule 2: everything else takes the widest gap it can reach inside its OWN sub-tree's arc — most
     * constrained first, so Chapter (two leaves) picks before Notes and Book. Every one of them lands
     * between colours of its own branch, and none of them lands on a colour already taken.
     */
    @Test
    fun everyOtherTaskTakesTheWidestGapInsideItsOwnBranch() {
        val (state, ids) = fixture()
        val hues = TaskColorSpace.hues(state)

        // Chapter is the narrowest arc (Write 0.0 and Draft 0.25), so it picks first: the gap between them.
        assertHue(0.125, hues[ids["Chapter"]]!!.hue, "Chapter")
        // Notes covers Read (0.75) and — through the mirror — Write (0.0); it takes the half-step before Read.
        assertHue(0.625, hues[ids["Notes"]]!!.hue, "Notes")
        // Book covers Write, Draft and Other; the widest gap left inside that arc is Draft ↔ Other.
        assertHue(0.375, hues[ids["Book"]]!!.hue, "Book")
    }

    /**
     * The collision the old *average of my own arc* rule had by construction: a parent whose child sat in
     * the middle of its arc got the child's very hue (Book and Draft both averaged to 0.3). A parent is now
     * placed AWAY from every colour already given out, so no two tasks share a hue at all.
     */
    @Test
    fun noTwoTasksShareAHue() {
        val (state, _) = fixture()
        val hues = TaskColorSpace.hues(state)

        assertEquals(7, hues.size)
        val sorted = hues.values.map { it.hue }.sorted()
        sorted.zipWithNext().forEach { (a, b) ->
            assertTrue(b - a > 1e-6, "two tasks landed on the same hue: $a and $b")
        }
        // Including the pair the old rule could not separate.
        val byTitle = hues.entries.associate { (id, hue) -> state.tasks[id]!!.title to hue.hue }
        assertTrue(distance(byTitle["Book"]!!, byTitle["Draft"]!!) > 1e-6, "Book vs Draft")
    }

    /**
     * "The closer in the tree, the closer in the colour space": a branch is a contiguous run of the circle.
     * Book holds Write, Draft, Other and its own colour — four of the seven hues — and nothing from the
     * other branch falls between them.
     */
    @Test
    fun aBranchIsAContiguousRunOfTheCircle() {
        val (state, ids) = fixture()
        val hues = TaskColorSpace.hues(state)

        val bookBranch = listOf("Book", "Chapter", "Write", "Draft", "Other").map { hues[ids[it]]!!.hue }
        val notesBranch = listOf("Notes", "Read").map { hues[ids[it]]!!.hue }
        // Every Book hue is nearer to every other Book hue than the two branches' nearest members are to
        // each other would allow them to interleave: no Notes hue sits strictly inside [min, max] of Book's.
        val low = bookBranch.min()
        val high = bookBranch.max()
        notesBranch.forEach { hue ->
            assertTrue(hue < low - 1e-9 || hue > high + 1e-9, "a Notes hue ($hue) fell inside Book's run")
        }
    }

    /**
     * A mirrored task is ONE colour: the first occurrence reached names it, and the second adds nothing —
     * but the branch it is mirrored into still counts it as one of its own, which is why Notes' arc reaches
     * back around the circle to Write.
     */
    @Test
    fun aMirroredTaskKeepsOneColour() {
        val (state, ids) = fixture()
        val hues = TaskColorSpace.hues(state)

        // Write is under Chapter AND under Notes; it is coloured once, by the first of the two reached.
        assertHue(0.0, hues[ids["Write"]]!!.hue, "Write")
        assertEquals(2, hues[ids["Write"]]!!.depth)
    }

    /**
     * Rule 3, the rotation: the circle has no origin of its own, so every rotation of the ring is equally
     * good. Handed a previous answer in which the leaves sat half a turn round, the solver puts them back
     * exactly there rather than at the canonical hues.
     */
    @Test
    fun theRingIsRotatedBackOntoThePreviousAnswer() {
        val (state, ids) = fixture()
        val canonical = TaskColorSpace.hues(state)
        val shifted =
            listOf("Write", "Draft", "Other", "Read").associate { title ->
                val id = ids[title]!!
                id to canonical[id]!!.copy(hue = (canonical[id]!!.hue + 0.5) % 1.0)
            }

        val hues = TaskColorSpace.hues(state, previous = shifted)

        assertHue(0.50, hues[ids["Write"]]!!.hue, "Write")
        assertHue(0.75, hues[ids["Draft"]]!!.hue, "Draft")
        assertHue(0.00, hues[ids["Other"]]!!.hue, "Other")
        assertHue(0.25, hues[ids["Read"]]!!.hue, "Read")
    }

    /**
     * Rule 3, a parent's pick: Chapter can sit at 0.125, 0.375 or 0.875 — all three are exactly as far from
     * every taken colour, and with nothing to remember it takes the first. Told where it was, it goes back
     * to the one nearest that.
     */
    @Test
    fun aTiedPlacementGoesBackToWhereItWas() {
        val (state, ids) = fixture()
        val chapter = ids["Chapter"]!!
        assertHue(0.125, TaskColorSpace.hues(state)[chapter]!!.hue, "Chapter, cold")

        // The leaves are handed back unchanged (so the ring does not rotate); only Chapter has moved.
        val previous =
            TaskColorSpace.hues(state).mapValues { (id, hue) ->
                if (id == chapter) hue.copy(hue = 0.87) else hue
            }
        assertHue(0.875, TaskColorSpace.hues(state, previous)[chapter]!!.hue, "Chapter, remembered")
    }

    /**
     * The answer is a fixed point of itself — feeding a solution back in as the previous one reproduces it
     * exactly. Without that, the debounced recompute `TaskHueMemo` runs on every tree change would drift
     * the whole palette even when the tree did not move.
     */
    @Test
    fun feedingAnAnswerBackInChangesNothing() {
        val (state, _) = fixture()
        val once = TaskColorSpace.hues(state)
        assertEquals(once, TaskColorSpace.hues(state, previous = once))
        assertEquals(once, TaskColorSpace.hues(state, previous = TaskColorSpace.hues(state, previous = once)))
    }

    /**
     * An empty placeholder row is not a task the user has: it takes no colour, and — the part that would be
     * silently wrong — no room on the circle either. Every list carries a trailing empty cell, so this is
     * every tree, not an edge case.
     */
    @Test
    fun emptyCellsNeitherTakeAColourNorConsumeTheCircle() {
        val (state, ids) = fixture()
        val hues = TaskColorSpace.hues(state)

        assertTrue(
            state.lists[state.rootListId]!!.cellIds.any { state.cells[it]?.taskId == null },
            "the fixture is meant to carry an empty placeholder row",
        )
        assertEquals(
            setOf("Book", "Notes", "Chapter", "Other", "Write", "Draft", "Read"),
            hues.keys.map { state.tasks[it]!!.title }.toSet(),
        )
        // Four leaves, four quarters — an empty row would have made it five slots.
        assertHue(0.25, hues[ids["Draft"]]!!.hue, "Draft")
    }

    /** A tree with nothing in it colours nothing — and does not divide by zero doing it. */
    @Test
    fun anEmptyTreeColoursNothing() {
        assertEquals(emptyMap(), TaskColorSpace.hues(SchedulerState.empty()))
    }
}
