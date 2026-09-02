package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.domain.SchedulerDomain.TaskListSort
import org.example.project.scheduler.domain.TitleSimilarity
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState

/**
 * PRD §7 **All tasks**, the third sorter figure: how alike a task's title is to another task's.
 *
 * The order is the PRD's, and it is two figures deep: the **best** score a task reaches against any other
 * task leads, and tasks sharing one best score are ranked by **how many other tasks they reach it against**.
 */
class TitleSimilaritySortTest {

    /** A flat tree of root cells, one per title — the shape the window lists. */
    private fun treeOf(vararg titles: String): SchedulerState {
        var s = SchedulerState.empty()
        for ((index, title) in titles.withIndex()) {
            val cell = s.lists[s.rootListId]!!.cellIds[index]
            s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(cell, title))
        }
        return s
    }

    private fun titles(state: SchedulerState, descending: Boolean) =
        SchedulerDomain.taskListEntries(state, TaskListSort.Similarity, descending).map { it.title }

    // ----- the score itself ---------------------------------------------------------------------

    @Test
    fun punctuationAndCaseSayNothing() {
        // Normalization case-folds and turns every non-alphanumeric run into one space, so two spellings of
        // one name are the SAME title as far as the sort is concerned.
        assertEquals(TitleSimilarity.PERFECT, TitleSimilarity.score("Write report", "write  REPORT!"))
        assertEquals(TitleSimilarity.PERFECT, TitleSimilarity.score("e-mail Bob", "E MAIL bob"))
    }

    @Test
    fun nearSpellingsScoreHighAndStrangersScoreLow() {
        val near = TitleSimilarity.score("Write report", "Write reports")
        val stranger = TitleSimilarity.score("Alpha", "Zephyr")
        // 11 of the 23 bigrams are shared both ways: 2*11/23 = 96 %.
        assertEquals(96, near)
        // Only "ph" is shared: 2*1/9 = 22 %.
        assertEquals(22, stranger)
        assertTrue(near > stranger)
    }

    @Test
    fun theScoreIsSymmetric() {
        // The figure is a fact about a PAIR, and `of` fills both sides of each pair from one measurement —
        // an asymmetric metric would make the two halves disagree.
        assertEquals(
            TitleSimilarity.score("Buy milk", "Buy the milk"),
            TitleSimilarity.score("Buy the milk", "Buy milk"),
        )
    }

    @Test
    fun aTitleWithNoBigramMatchesOnlyItsOwnTwin() {
        assertEquals(TitleSimilarity.PERFECT, TitleSimilarity.score("a", "A!"))
        assertEquals(0, TitleSimilarity.score("a", "ab"))
        // Nothing alphanumeric left at all: there is no title to compare, so not even its twin matches.
        assertEquals(0, TitleSimilarity.score("???", "???"))
    }

    // ----- the order ----------------------------------------------------------------------------

    @Test
    fun theBestScoreLeadsAndTheNumberOfMatchesBreaksTheTie() {
        // Alpha ×3 and Beta ×2 both reach 100 %; "Zephyr" reaches only 22 % (against "Alpha"). So the two
        // perfect groups lead, and among them the Alphas come first because each of them is a duplicate of
        // TWO other tasks where each Beta is a duplicate of one.
        val state = treeOf("Alpha", "Alpha", "Alpha", "Beta", "Beta", "Zephyr")

        assertEquals(
            listOf("Alpha", "Alpha", "Alpha", "Beta", "Beta", "Zephyr"),
            titles(state, descending = true),
        )

        val entries = SchedulerDomain.taskListEntries(state, TaskListSort.Similarity, descending = true)
        assertEquals(TitleSimilarity(best = 100, matches = 2), entries.first().similarity)
        assertEquals(TitleSimilarity(best = 100, matches = 1), entries.first { it.title == "Beta" }.similarity)
        // ...and "Zephyr" reaches its own 22 % against all three Alphas at once.
        assertEquals(TitleSimilarity(best = 22, matches = 3), entries.last().similarity)
    }

    @Test
    fun theDirectionReversesBothFigures() {
        val state = treeOf("Alpha", "Alpha", "Alpha", "Beta", "Beta", "Zephyr")
        // The tie-break is part of the figure, not part of the alphabetical fallback, so flipping the
        // direction puts the LEAST duplicated of the perfect matches first.
        assertEquals(
            listOf("Zephyr", "Beta", "Beta", "Alpha", "Alpha", "Alpha"),
            titles(state, descending = false),
        )
    }

    @Test
    fun aLoneTaskIsAlikeToNothing() {
        val entries =
            SchedulerDomain.taskListEntries(treeOf("Alpha"), TaskListSort.Similarity, descending = true)
        // Zero is "not alike", never a tie at zero — otherwise every task in an account of strangers would
        // report a match against every other one.
        assertEquals(TitleSimilarity(best = 0, matches = 0), entries.single().similarity)
    }

    @Test
    fun theFigureIsMeasuredOnlyWhenItIsTheSort() {
        // It costs a pass over every PAIR of titles, so the two cheap sorts (and every other caller of
        // `taskListEntries`) must not pay for it — ADR 0009.
        val state = treeOf("Alpha", "Alpha", "Beta")
        for (entry in SchedulerDomain.taskListEntries(state, TaskListSort.Priority, descending = true)) {
            assertNull(entry.similarity, "priority sort measured ${entry.title}")
        }
        for (entry in SchedulerDomain.taskListEntries(state, TaskListSort.Occurrences, descending = true)) {
            assertNull(entry.similarity, "occurrences sort measured ${entry.title}")
        }
        for (entry in SchedulerDomain.taskListEntries(state, TaskListSort.Similarity, descending = true)) {
            assertNotNull(entry.similarity, "similarity sort skipped ${entry.title}")
        }
    }
}
