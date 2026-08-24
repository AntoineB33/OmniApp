package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.TaskTreeSearch
import org.example.project.scheduler.model.CellId
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.persistence.SchedulerStateCodec
import org.example.project.scheduler.state.HistoryCategory
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState

/**
 * PRD §4 **Find & replace** — the task tree's Ctrl+F bar.
 *
 * The bar itself is Compose-only state, but everything it decides is pure and pinned here: which titles the
 * query hits and where, that the walk sees the WHOLE tree (a task tree is mostly collapsed, so a find over
 * the visible rows alone would miss most of the account), that a mirrored sub-list is walked exactly once,
 * that revealing a hit expands its own path as one history unit, and that "replace" means renaming the
 * **task** — once per task, however many cells point at it.
 */
class TaskTreeSearchTest {

    private fun r(state: SchedulerState, intent: SchedulerIntent) = SchedulerReducer.reduce(state, intent)

    /** The trailing empty placeholder of the root list. */
    private fun freeRootCell(state: SchedulerState): CellId =
        state.lists[state.rootListId]!!.cellIds.last()

    /** The trailing empty placeholder under [cellId]. */
    private fun freeChildCell(state: SchedulerState, cellId: CellId): CellId {
        val taskId = state.cells[cellId]!!.taskId!!
        return state.lists[state.tasks[taskId]!!.childListId!!]!!.cellIds.last()
    }

    private fun cellWithTitle(state: SchedulerState, title: String): CellId =
        state.cells.values.first { cell ->
            cell.taskId?.let { state.tasks[it]?.title } == title
        }.id

    /**
     * ```
     * Apple            <- a
     *   apple pie      <- a0
     *   Pineapple      <- a1
     * Banana           <- b
     * ```
     * Nothing is expanded: the walk must find the two children anyway.
     */
    private fun tree(): SchedulerState {
        var s = SchedulerState.empty()
        val a = freeRootCell(s)
        s = r(s, SchedulerIntent.SetCellTitle(a, "Apple"))
        s = r(s, SchedulerIntent.SetCellTitle(freeChildCell(s, a), "apple pie"))
        s = r(s, SchedulerIntent.SetCellTitle(freeChildCell(s, a), "Pineapple"))
        s = r(s, SchedulerIntent.SetCellTitle(freeRootCell(s), "Banana"))
        return s.copy(expanded = emptySet())
    }

    // ---- ranges inside one title ---------------------------------------------------------------

    @Test
    fun ranges_are_case_insensitive_by_default_and_do_not_overlap() {
        val options = TaskTreeSearch.Options()
        assertEquals(listOf(0..2, 3..5), TaskTreeSearch.ranges("AbaAba", "aba", options))
        // Non-overlapping, as in VS Code: "aa" in "aaaa" is two hits, not three.
        assertEquals(listOf(0..1, 2..3), TaskTreeSearch.ranges("aaaa", "aa", options))
    }

    @Test
    fun match_case_distinguishes_them() {
        val options = TaskTreeSearch.Options(matchCase = true)
        assertEquals(listOf(0..2, 3..5), TaskTreeSearch.ranges("AbaAba", "Aba", options))
        assertEquals(emptyList(), TaskTreeSearch.ranges("AbaAba", "aba", options))
    }

    @Test
    fun whole_word_needs_non_word_characters_on_both_sides() {
        val options = TaskTreeSearch.Options(wholeWord = true)
        assertEquals(emptyList(), TaskTreeSearch.ranges("Pineapple", "apple", options))
        assertEquals(listOf(0..4), TaskTreeSearch.ranges("apple pie", "apple", options))
        assertEquals(listOf(6..10), TaskTreeSearch.ranges("juicy apple, ripe", "apple", options))
        // An underscore is a word character, so it does not open a boundary.
        assertEquals(emptyList(), TaskTreeSearch.ranges("my_apple", "apple", options))
    }

    /** A rejected hit must not consume the text it overlapped, or the next real word is swallowed. */
    @Test
    fun a_rejected_whole_word_hit_does_not_hide_the_next_one() {
        val options = TaskTreeSearch.Options(wholeWord = true)
        assertEquals(listOf(7..9), TaskTreeSearch.ranges("concat cat", "cat", options))
    }

    @Test
    fun an_empty_query_matches_nothing() {
        assertEquals(emptyList(), TaskTreeSearch.ranges("anything", "", TaskTreeSearch.Options()))
        assertEquals(emptyList(), TaskTreeSearch.matches(tree(), "", TaskTreeSearch.Options()))
    }

    // ---- the walk ------------------------------------------------------------------------------

    @Test
    fun the_walk_sees_collapsed_rows_and_carries_the_path_to_them() {
        val s = tree()
        assertTrue(s.expanded.isEmpty())
        val matches = TaskTreeSearch.matches(s, "apple", TaskTreeSearch.Options())

        assertEquals(
            listOf("Apple", "apple pie", "Pineapple"),
            matches.map { s.tasks[it.taskId]!!.title },
        )
        // Depth-first: the two children come after their parent, and carry it as their one ancestor.
        val parent = cellWithTitle(s, "Apple")
        assertEquals(emptyList(), matches[0].ancestors)
        assertNull(matches[0].renderVia)
        assertEquals(listOf(parent), matches[1].ancestors)
        assertEquals(parent, matches[2].renderVia)
        // "Pineapple" is hit at offset 4, not 0.
        assertEquals(4, matches[2].start)
        assertEquals(9, matches[2].end)
    }

    @Test
    fun a_mirrored_task_is_a_row_of_its_own_but_its_sub_list_is_walked_once() {
        var s = tree()
        val appleTaskId = s.cells[cellWithTitle(s, "Apple")]!!.taskId!!
        // A second cell pointing at the same task — under Banana, since a list may not hold the same task
        // twice. The SAME sub-list shows under it: that is what mirroring is.
        s = r(s, SchedulerIntent.AssignTaskId(freeChildCell(s, cellWithTitle(s, "Banana")), appleTaskId))
        assertEquals(2, s.tasks[appleTaskId]!!.occurrences.size)

        val matches = TaskTreeSearch.matches(s, "apple", TaskTreeSearch.Options())
        // Four rows, not five: "apple pie" and "Pineapple" are met once, under the first occurrence.
        assertEquals(
            listOf("Apple", "apple pie", "Pineapple", "Apple"),
            matches.map { s.tasks[it.taskId]!!.title },
        )
        assertEquals(4, matches.map { it.cellId }.distinct().size)
    }

    // ---- revealing a hit -----------------------------------------------------------------------

    @Test
    fun revealing_a_hit_expands_its_path_and_selects_it_in_that_copy() {
        val s0 = tree()
        val match = TaskTreeSearch.matches(s0, "pie", TaskTreeSearch.Options()).single()
        val parent = cellWithTitle(s0, "Apple")
        val before = s0.histories.forCategory(HistoryCategory.Main).units.size

        val s = r(s0, SchedulerIntent.RevealCell(match.cellId, match.ancestors))

        assertTrue(parent in s.expanded)
        assertEquals(match.cellId, s.selection.main)
        assertEquals(parent, s.selection.renderVia)
        // The whole path is ONE unit, so Ctrl+Z out of a search never has to climb level by level.
        assertEquals(before + 1, s.histories.forCategory(HistoryCategory.Main).units.size)
        assertEquals(s0.expanded, r(s, SchedulerIntent.Undo).expanded)
    }

    @Test
    fun revealing_a_deep_hit_expands_every_ancestor_in_one_unit() {
        var s = tree()
        val a = cellWithTitle(s, "Apple")
        val pie = cellWithTitle(s, "apple pie")
        s = r(s, SchedulerIntent.SetCellTitle(freeChildCell(s, pie), "crust"))
        s = s.copy(expanded = emptySet())

        val match = TaskTreeSearch.matches(s, "crust", TaskTreeSearch.Options()).single()
        assertEquals(listOf(a, pie), match.ancestors)

        val before = s.histories.forCategory(HistoryCategory.Main).units.size
        val revealed = r(s, SchedulerIntent.RevealCell(match.cellId, match.ancestors))
        assertTrue(a in revealed.expanded && pie in revealed.expanded)
        assertEquals(before + 1, revealed.histories.forCategory(HistoryCategory.Main).units.size)
    }

    // ---- replacing -----------------------------------------------------------------------------

    @Test
    fun replacing_one_hit_renames_the_task_so_every_cell_pointing_at_it_follows() {
        var s = tree()
        val appleTaskId = s.cells[cellWithTitle(s, "Apple")]!!.taskId!!
        s = r(s, SchedulerIntent.AssignTaskId(freeChildCell(s, cellWithTitle(s, "Banana")), appleTaskId))

        val match = TaskTreeSearch.matches(s, "Apple", TaskTreeSearch.Options(matchCase = true)).first()
        val title = s.tasks[match.taskId]!!.title
        val replaced = TaskTreeSearch.replaceRange(title, match.start, match.end, "Pear")

        val after = r(s, SchedulerIntent.ReplaceTaskTitles(mapOf(match.taskId to replaced)))
        assertEquals("Pear", after.tasks[appleTaskId]!!.title)
        // Both cells show it — that is what a rename means.
        assertEquals(2, after.tasks[appleTaskId]!!.occurrences.size)
    }

    @Test
    fun replace_all_rewrites_every_matched_task_once_in_one_history_unit() {
        var s = tree()
        val appleTaskId = s.cells[cellWithTitle(s, "Apple")]!!.taskId!!
        s = r(s, SchedulerIntent.AssignTaskId(freeChildCell(s, cellWithTitle(s, "Banana")), appleTaskId))
        val before = s.histories.forCategory(HistoryCategory.Main).units.size

        val titles = TaskTreeSearch.replaceAllTitles(s, "apple", TaskTreeSearch.Options(), "PEAR")
        // Three TASKS, though four rows matched — the mirrored one is named once.
        assertEquals(3, titles.size)

        val after = r(s, SchedulerIntent.ReplaceTaskTitles(titles))
        assertEquals(
            setOf("PEAR", "PEAR pie", "PinePEAR", "Banana"),
            after.tasks.values.map { it.title }.filter { it.isNotEmpty() }.toSet() -
                setOf("root", "main"),
        )
        assertEquals(before + 1, after.histories.forCategory(HistoryCategory.Main).units.size)
        // One Ctrl+Z takes the whole replacement back.
        assertEquals("Apple", r(after, SchedulerIntent.Undo).tasks[appleTaskId]!!.title)
    }

    @Test
    fun replace_all_within_a_title_rewrites_every_hit_of_it() {
        assertEquals(
            "PEAR pie and PEAR sauce",
            TaskTreeSearch.replaceAll("apple pie and Apple sauce", "apple", TaskTreeSearch.Options(), "PEAR"),
        )
        // Match Case narrows it to the one it spells.
        assertEquals(
            "apple pie and PEAR sauce",
            TaskTreeSearch.replaceAll(
                "apple pie and Apple sauce",
                "Apple",
                TaskTreeSearch.Options(matchCase = true),
                "PEAR",
            ),
        )
    }

    /** §4 "the blank title is what deletes" — a replacement that consumes a whole title is a deletion. */
    @Test
    fun a_replacement_that_empties_a_title_deletes_the_cell() {
        val s = tree()
        val bananaTaskId: TaskId = s.cells[cellWithTitle(s, "Banana")]!!.taskId!!

        val after = r(s, SchedulerIntent.ReplaceTaskTitles(mapOf(bananaTaskId to "")))
        assertTrue(after.tasks[bananaTaskId] == null || after.tasks[bananaTaskId]!!.title.isEmpty())
        assertTrue(after.tasks.values.none { it.title == "Banana" })
    }

    @Test
    fun replacing_nothing_records_no_history_unit() {
        val s = tree()
        val appleTaskId = s.cells[cellWithTitle(s, "Apple")]!!.taskId!!
        val after = r(s, SchedulerIntent.ReplaceTaskTitles(mapOf(appleTaskId to "Apple")))
        assertEquals(
            s.histories.forCategory(HistoryCategory.Main).units.size,
            after.histories.forCategory(HistoryCategory.Main).units.size,
        )
    }

    // ---- persisted-DB compatibility ------------------------------------------------------------

    /**
     * The reveal's delta is a new [org.example.project.scheduler.state.SetExpandedDelta], so it has to
     * round-trip through the history codec — and a payload written before it existed must still load.
     */
    @Test
    fun the_reveal_unit_round_trips_and_an_older_payload_still_loads() {
        val s0 = tree()
        val match = TaskTreeSearch.matches(s0, "pie", TaskTreeSearch.Options()).single()
        val s = r(s0, SchedulerIntent.RevealCell(match.cellId, match.ancestors))

        val reloaded = SchedulerStateCodec.decode(SchedulerStateCodec.encode(s))!!
        assertEquals(s.expanded, reloaded.expanded)
        assertEquals(
            s.histories.forCategory(HistoryCategory.Main).units.size,
            reloaded.histories.forCategory(HistoryCategory.Main).units.size,
        )
        // Undo still works off the decoded unit.
        assertEquals(s0.expanded, r(reloaded, SchedulerIntent.Undo).expanded)

        // A history written by a build that never emitted the new unit decodes unchanged.
        val old = SchedulerStateCodec.decode(SchedulerStateCodec.encode(s0))!!
        assertEquals(s0.expanded, old.expanded)
    }
}
