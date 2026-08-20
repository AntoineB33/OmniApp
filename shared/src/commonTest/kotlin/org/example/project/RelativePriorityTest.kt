package org.example.project

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.RelativePriorityDomain
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.CellId
import org.example.project.scheduler.model.RelativePriorityPinKey
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.model.WellKnownIds
import org.example.project.scheduler.persistence.SchedulerStateCodec
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState

/**
 * PRD §5 the **relative priority** window (the percentage's right-click menu): the priority of a task
 * within any ancestor's sub-tree, and the retargeting that scales every unpinned cell of every occurrence
 * chain by one common factor.
 */
class RelativePriorityTest {

    /**
     * A tree with a task that occurs twice, under two different parents:
     *
     * ```
     * root ─ Book  ─ Chapter ─ Write
     *      │       │         └ Draft
     *      │       └ Other
     *      └ Notes ─ Write        (the same task, assigned to a second cell)
     *              └ Read
     * ```
     *
     * Every cell of both chains has a sibling on purpose: an only child holds 100% of its parent whatever
     * its weight, so a tree of only children has nothing the scaling could move.
     */
    private class Fixture(
        val state: SchedulerState,
        val write: TaskId,
        val book: TaskId,
        val bookCell: CellId,
        val chapterCell: CellId,
        val writeUnderChapter: CellId,
        val notesCell: CellId,
        val writeUnderNotes: CellId,
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
        val writeUnderChapter = s.lists[chapterList]!!.cellIds[0]
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(writeUnderChapter, "Write"))
        val write = s.cells[writeUnderChapter]!!.taskId!!
        val draftCell = s.lists[chapterList]!!.cellIds[1]
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(draftCell, "Draft"))

        // The second occurrence is the SAME task (assigned), not another task with the same title. The
        // sibling is titled first: only a title mints the next empty placeholder to assign into.
        val notes = s.cells[notesCell]!!.taskId!!
        val notesList = s.tasks[notes]!!.childListId!!
        val readCell = s.lists[notesList]!!.cellIds[0]
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(readCell, "Read"))
        val writeUnderNotes = s.lists[notesList]!!.cellIds[1]
        s = SchedulerReducer.reduce(s, SchedulerIntent.AssignTaskId(writeUnderNotes, write))

        return Fixture(s, write, book, bookCell, chapterCell, writeUnderChapter, notesCell, writeUnderNotes)
    }

    private fun assertClose(expected: Double, actual: Double, tolerance: Double = 1e-9) {
        assertTrue(
            abs(expected - actual) <= tolerance,
            "expected $expected but was $actual (tolerance $tolerance)",
        )
    }

    @Test
    fun relative_priority_against_the_root_is_the_absolute_percentage() {
        val f = fixture()
        val absolute = SchedulerDomain.absoluteTaskPriorities(f.state)[f.write]!!
        assertClose(absolute, RelativePriorityDomain.relativePriority(f.state, f.write, WellKnownIds.MAIN_TASK))
        // Not a degenerate zero: the task really does hold a share of the tree.
        assertTrue(absolute > 0.0)
    }

    @Test
    fun relative_priority_against_a_parent_counts_only_the_occurrences_under_it() {
        val f = fixture()
        // Under "Book" there is one occurrence: Chapter (1/2 of Book) then Write (1/2 of Chapter).
        assertClose(0.25, RelativePriorityDomain.relativePriority(f.state, f.write, f.book))
        val chains = RelativePriorityDomain.occurrenceChains(f.state, f.write, f.book)
        assertEquals(listOf(listOf(f.chapterCell, f.writeUnderChapter)), chains)
        // Against the root BOTH occurrences count, so the chains are the two full paths (shallowest first).
        assertEquals(
            listOf(
                listOf(f.notesCell, f.writeUnderNotes),
                listOf(f.bookCell, f.chapterCell, f.writeUnderChapter),
            ),
            RelativePriorityDomain.occurrenceChains(f.state, f.write, WellKnownIds.MAIN_TASK),
        )
        // 1/2 · 1/2 · 1/2 (under Book) + 1/2 · 1/2 (under Notes).
        assertClose(0.375, RelativePriorityDomain.relativePriority(f.state, f.write, WellKnownIds.MAIN_TASK))
    }

    @Test
    fun the_drop_down_offers_the_root_then_the_cell_s_ancestors_from_root_to_closest_parent() {
        val f = fixture()
        val chapter = f.state.cells[f.chapterCell]!!.taskId!!
        assertEquals(
            listOf(WellKnownIds.MAIN_TASK, f.book, chapter),
            RelativePriorityDomain.relativeToOptions(f.state, f.writeUnderChapter),
        )
    }

    @Test
    fun setting_the_relative_priority_scales_every_unpinned_cell_by_one_common_factor() {
        val f = fixture()
        val before = f.state
        val target = 0.4
        // Measured against "Book", where the chain's two cells live in two different sub-lists — the case
        // where one common factor is actually attainable (see the same-list caveat below).
        val after = RelativePriorityDomain.setRelativePriority(
            before,
            f.write,
            f.book,
            target,
            pinned = emptySet(),
        )

        assertClose(target, RelativePriorityDomain.relativePriority(after, f.write, f.book), 1e-6)
        // One factor, shared by both cells of the chain — the user's "they all equally change". Each was
        // 1/2, so each is now sqrt(0.4) ≈ 0.632.
        val factors = listOf(f.chapterCell, f.writeUnderChapter).map { id ->
            RelativePriorityDomain.cellShare(after, id) / RelativePriorityDomain.cellShare(before, id)
        }
        assertClose(factors[0], factors[1], 1e-6)
        assertTrue(factors[0] > 1.0, "raising the priority must raise the shares, not lower them")
    }

    @Test
    fun the_target_is_still_hit_when_two_chain_cells_share_a_sub_list() {
        // "Book" and "Notes" are siblings and both carry an occurrence, so their shares sum to 1 and no
        // common factor above 1 can apply to both. The guarantee that survives is the one that matters:
        // the number the user typed is what the tree ends up holding.
        val f = fixture()
        val after = RelativePriorityDomain.setRelativePriority(
            f.state,
            f.write,
            WellKnownIds.MAIN_TASK,
            target = 0.5,
            pinned = emptySet(),
        )
        assertClose(0.5, RelativePriorityDomain.relativePriority(after, f.write, WellKnownIds.MAIN_TASK), 1e-6)
    }

    @Test
    fun a_pinned_cell_holds_its_percentage_while_the_rest_move() {
        val f = fixture()
        val before = f.state
        val after = RelativePriorityDomain.setRelativePriority(
            before,
            f.write,
            f.book,
            target = 0.4,
            pinned = setOf(f.chapterCell),
        )

        assertClose(
            RelativePriorityDomain.cellShare(before, f.chapterCell),
            RelativePriorityDomain.cellShare(after, f.chapterCell),
            1e-6,
        )
        assertNotEquals(
            RelativePriorityDomain.cellShare(before, f.writeUnderChapter),
            RelativePriorityDomain.cellShare(after, f.writeUnderChapter),
        )
        assertClose(0.4, RelativePriorityDomain.relativePriority(after, f.write, f.book), 1e-6)
    }

    @Test
    fun a_pin_holds_its_share_even_when_an_unpinned_sibling_of_the_same_list_grows() {
        // "Book" and "Notes" share the root list: pinning Book means Book must KEEP 50% while Notes moves,
        // which takes a weight change on Book itself — pinning is not "leave this weight alone".
        val f = fixture()
        val after = RelativePriorityDomain.setRelativePriority(
            f.state,
            f.write,
            WellKnownIds.MAIN_TASK,
            target = 0.3,
            pinned = setOf(f.bookCell),
        )
        assertClose(0.5, RelativePriorityDomain.cellShare(after, f.bookCell), 1e-6)
        assertClose(0.3, RelativePriorityDomain.relativePriority(after, f.write, WellKnownIds.MAIN_TASK), 1e-6)
    }

    @Test
    fun pinning_every_cell_leaves_the_tree_untouched() {
        val f = fixture()
        val everyCell = RelativePriorityDomain
            .occurrenceChains(f.state, f.write, WellKnownIds.MAIN_TASK)
            .flatten()
            .toSet()
        val after = RelativePriorityDomain.setRelativePriority(
            f.state,
            f.write,
            WellKnownIds.MAIN_TASK,
            target = 0.4,
            pinned = everyCell,
        )
        assertEquals(f.state.cells, after.cells)
    }

    @Test
    fun retargeting_twice_lands_where_going_straight_there_would() {
        // The window's field commits every keystroke, so a half-typed number must not skew the tree: the
        // scale factors compose, so 5% then 40% is exactly 40%.
        val f = fixture()
        val direct = RelativePriorityDomain
            .setRelativePriority(f.state, f.write, f.book, 0.4, emptySet())
        val stepped = RelativePriorityDomain
            .setRelativePriority(f.state, f.write, f.book, 0.05, emptySet())
            .let { RelativePriorityDomain.setRelativePriority(it, f.write, f.book, 0.4, emptySet()) }
        for (cellId in direct.cells.keys) {
            assertClose(
                RelativePriorityDomain.cellShare(direct, cellId),
                RelativePriorityDomain.cellShare(stepped, cellId),
                1e-6,
            )
        }
    }

    @Test
    fun the_intent_reads_the_pins_of_its_own_task_and_ancestor_pair() {
        val f = fixture()
        val key = RelativePriorityPinKey(f.write, WellKnownIds.MAIN_TASK)
        var s = SchedulerReducer.reduce(
            f.state,
            SchedulerIntent.ToggleRelativePriorityPin(f.write, WellKnownIds.MAIN_TASK, f.bookCell),
        )
        assertEquals(setOf(f.bookCell), s.relativePriorityPins[key])
        // A pin filed under another ancestor is a different set entirely (the user's rule).
        assertEquals(null, s.relativePriorityPins[RelativePriorityPinKey(f.write, f.book)])

        s = SchedulerReducer.reduce(s, SchedulerIntent.SetRelativePriority(f.write, WellKnownIds.MAIN_TASK, 0.3))
        assertClose(
            RelativePriorityDomain.cellShare(f.state, f.bookCell),
            RelativePriorityDomain.cellShare(s, f.bookCell),
            1e-6,
        )
        assertClose(0.3, RelativePriorityDomain.relativePriority(s, f.write, WellKnownIds.MAIN_TASK), 1e-6)

        // Toggling the same cell again unpins it, and the empty set is dropped rather than stored.
        s = SchedulerReducer.reduce(
            s,
            SchedulerIntent.ToggleRelativePriorityPin(f.write, WellKnownIds.MAIN_TASK, f.bookCell),
        )
        assertEquals(null, s.relativePriorityPins[key])
    }

    @Test
    fun clearing_the_pins_drops_only_that_pair_s_set() {
        val f = fixture()
        var s = SchedulerReducer.reduce(
            f.state,
            SchedulerIntent.ToggleRelativePriorityPin(f.write, WellKnownIds.MAIN_TASK, f.bookCell),
        )
        s = SchedulerReducer.reduce(
            s,
            SchedulerIntent.ToggleRelativePriorityPin(f.write, f.book, f.chapterCell),
        )
        s = SchedulerReducer.reduce(s, SchedulerIntent.ClearRelativePriorityPins(f.write, WellKnownIds.MAIN_TASK))

        assertEquals(null, s.relativePriorityPins[RelativePriorityPinKey(f.write, WellKnownIds.MAIN_TASK)])
        assertEquals(setOf(f.chapterCell), s.relativePriorityPins[RelativePriorityPinKey(f.write, f.book)])
    }

    @Test
    fun the_pins_are_persisted_and_synced_and_an_older_payload_decodes_without_them() {
        val f = fixture()
        val pinned = SchedulerReducer.reduce(
            f.state,
            SchedulerIntent.ToggleRelativePriorityPin(f.write, WellKnownIds.MAIN_TASK, f.bookCell),
        )
        val decoded = SchedulerStateCodec.decode(SchedulerStateCodec.encode(pinned))!!
        assertEquals(
            setOf(f.bookCell),
            decoded.relativePriorityPins[RelativePriorityPinKey(f.write, WellKnownIds.MAIN_TASK)],
        )
        // Authoritative user data: a pin is a change the other devices must see.
        assertNotEquals(
            SchedulerStateCodec.syncFingerprint(f.state),
            SchedulerStateCodec.syncFingerprint(pinned),
        )

        // A payload written before the window existed carries no such key at all and must still load.
        val legacy = SchedulerStateCodec.encode(f.state)
        assertTrue("relativePriorityPins" !in legacy || "\"relativePriorityPins\":[]" in legacy)
        val legacyDecoded = SchedulerStateCodec.decode(legacy)!!
        assertEquals(emptyMap(), legacyDecoded.relativePriorityPins)
    }
}
