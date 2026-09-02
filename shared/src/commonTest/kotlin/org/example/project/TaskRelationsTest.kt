package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.TaskRelationsDomain
import org.example.project.scheduler.model.CellId
import org.example.project.scheduler.model.CellListId
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.model.TaskRelationKey
import org.example.project.scheduler.model.WellKnownIds
import org.example.project.scheduler.persistence.SchedulerStateCodec
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState

/**
 * PRD §5 the **task relations** window: the four sections of (task, relational target) pairs, what puts a
 * pair into each of them, and the two buttons that move one between the first section and nowhere at all.
 */
class TaskRelationsTest {

    /**
     * The same tree [RelativePriorityTest] uses — a task occurring under two different parents, every cell
     * with a sibling:
     *
     * ```
     * root ─ Book  ─ Chapter ─ Write
     *      │       │         └ Draft
     *      │       └ Other
     *      └ Notes ─ Read
     *              └ Write        (the same task, assigned to a second cell)
     * ```
     */
    private class Fixture(
        val state: SchedulerState,
        val write: TaskId,
        val read: TaskId,
        val book: TaskId,
        val bookCell: CellId,
        val bookList: CellListId,
        val writeUnderChapter: CellId,
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

        val notes = s.cells[notesCell]!!.taskId!!
        val notesList = s.tasks[notes]!!.childListId!!
        val readCell = s.lists[notesList]!!.cellIds[0]
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(readCell, "Read"))
        val read = s.cells[readCell]!!.taskId!!
        val writeUnderNotes = s.lists[notesList]!!.cellIds[1]
        s = SchedulerReducer.reduce(s, SchedulerIntent.AssignTaskId(writeUnderNotes, write))

        return Fixture(s, write, read, book, bookCell, bookList, writeUnderChapter)
    }

    private fun rowOf(state: SchedulerState, key: TaskRelationKey): TaskRelationsDomain.Row? =
        TaskRelationsDomain.rows(state).firstOrNull { it.key == key }

    // ----- sections 2 and 3: what the relative-priority window leaves behind ---------------------

    @Test
    fun opening_the_window_files_the_pair_as_opened_and_changing_the_percentage_moves_it_to_edited() {
        val f = fixture()
        val key = TaskRelationKey(f.write, WellKnownIds.MAIN_TASK)

        // Section 3: the window settled on the pair and reported the percentage unchanged.
        var s = SchedulerReducer.reduce(f.state, SchedulerIntent.RecordTaskRelation(f.write, WellKnownIds.MAIN_TASK, false))
        assertEquals(TaskRelationsDomain.Section.Opened, rowOf(s, key)?.section)
        assertEquals("Write", rowOf(s, key)?.taskTitle)
        assertEquals(TaskRelationsDomain.ROOT_LABEL, rowOf(s, key)?.targetTitle)

        // Section 2: the same window session ended on a different number.
        s = SchedulerReducer.reduce(s, SchedulerIntent.RecordTaskRelation(f.write, WellKnownIds.MAIN_TASK, true))
        assertEquals(TaskRelationsDomain.Section.Edited, rowOf(s, key)?.section)
        assertTrue(rowOf(s, key)!!.retargeted)
    }

    @Test
    fun a_percentage_typed_and_put_back_leaves_the_pair_in_section_three() {
        val f = fixture()
        val key = TaskRelationKey(f.write, WellKnownIds.MAIN_TASK)
        // The window commits every keystroke, so it reports the verdict on each one: changed, then back.
        var s = SchedulerReducer.reduce(f.state, SchedulerIntent.RecordTaskRelation(f.write, WellKnownIds.MAIN_TASK, false))
        s = SchedulerReducer.reduce(s, SchedulerIntent.RecordTaskRelation(f.write, WellKnownIds.MAIN_TASK, true))
        s = SchedulerReducer.reduce(s, SchedulerIntent.RecordTaskRelation(f.write, WellKnownIds.MAIN_TASK, false))
        assertEquals(TaskRelationsDomain.Section.Opened, rowOf(s, key)?.section)
    }

    @Test
    fun a_recording_that_changes_nothing_returns_the_same_state() {
        val f = fixture()
        val opened = SchedulerReducer.reduce(f.state, SchedulerIntent.RecordTaskRelation(f.write, f.book, false))
        val again = SchedulerReducer.reduce(opened, SchedulerIntent.RecordTaskRelation(f.write, f.book, false))
        // Every keystroke re-reports the verdict; a save and a sync push per keystroke is what this avoids.
        assertTrue(opened === again)
    }

    // ----- section 2's other half: a weight table's optional row ---------------------------------

    @Test
    fun a_task_added_to_a_priority_weight_table_is_an_edited_pair_and_is_never_stored() {
        val f = fixture()
        val s = SchedulerReducer.reduce(f.state, SchedulerIntent.AddPriorityWeightTableTask(f.bookList, f.write))
        val key = TaskRelationKey(f.write, f.book)

        val row = rowOf(s, key)
        assertEquals(TaskRelationsDomain.Section.Edited, row?.section)
        assertTrue(row!!.inWeightTable)
        // Derived from the live table, so removing the row from it takes the pair off this list by itself.
        assertEquals(emptyMap(), s.taskRelations)
        assertEquals(setOf(key), TaskRelationsDomain.weightTableRelations(s))
    }

    // ----- section 1 and the two buttons ---------------------------------------------------------

    @Test
    fun keeping_a_pair_files_it_in_section_one_and_dropping_it_takes_it_off_the_list() {
        val f = fixture()
        val key = TaskRelationKey(f.write, f.book)
        var s = SchedulerReducer.reduce(f.state, SchedulerIntent.RecordTaskRelation(f.write, f.book, true))
        assertEquals(TaskRelationsDomain.Section.Edited, rowOf(s, key)?.section)

        s = SchedulerReducer.reduce(s, SchedulerIntent.KeepTaskRelation(f.write, f.book))
        assertEquals(TaskRelationsDomain.Section.Kept, rowOf(s, key)?.section)
        assertTrue(rowOf(s, key)!!.kept)

        s = SchedulerReducer.reduce(s, SchedulerIntent.DropTaskRelation(f.write, f.book))
        assertNull(rowOf(s, key))
    }

    @Test
    fun a_dropped_pair_survives_a_weight_table_row_and_comes_back_only_when_it_is_retargeted() {
        val f = fixture()
        val key = TaskRelationKey(f.write, f.book)
        // Struck off while it IS a live weight-table row: "disappear from this list" outranks every source.
        var s = SchedulerReducer.reduce(f.state, SchedulerIntent.AddPriorityWeightTableTask(f.bookList, f.write))
        s = SchedulerReducer.reduce(s, SchedulerIntent.DropTaskRelation(f.write, f.book))
        assertNull(rowOf(s, key))

        // Merely looking at it again is not working on it again.
        s = SchedulerReducer.reduce(s, SchedulerIntent.RecordTaskRelation(f.write, f.book, false))
        assertNull(rowOf(s, key))

        // Actually retargeting it is.
        s = SchedulerReducer.reduce(s, SchedulerIntent.RecordTaskRelation(f.write, f.book, true))
        assertEquals(TaskRelationsDomain.Section.Edited, rowOf(s, key)?.section)
    }

    @Test
    fun opening_the_window_on_a_kept_pair_never_unfiles_it() {
        val f = fixture()
        val key = TaskRelationKey(f.write, f.book)
        var s = SchedulerReducer.reduce(f.state, SchedulerIntent.KeepTaskRelation(f.write, f.book))
        s = SchedulerReducer.reduce(s, SchedulerIntent.RecordTaskRelation(f.write, f.book, false))
        assertEquals(TaskRelationsDomain.Section.Kept, rowOf(s, key)?.section)
    }

    @Test
    fun filing_a_pair_records_no_history_unit() {
        val f = fixture()
        val before = f.state.histories
        val s = SchedulerReducer.reduce(
            SchedulerReducer.reduce(f.state, SchedulerIntent.RecordTaskRelation(f.write, f.book, true)),
            SchedulerIntent.KeepTaskRelation(f.write, f.book),
        )
        // Like a relative-priority pin: it changes no priority, so nothing undoes it.
        assertEquals(before, s.histories)
    }

    // ----- section 4: the pairs an external change has broken -------------------------------------

    @Test
    fun a_pair_whose_task_is_deleted_is_broken() {
        val f = fixture()
        val key = TaskRelationKey(f.write, WellKnownIds.MAIN_TASK)
        var s = SchedulerReducer.reduce(f.state, SchedulerIntent.KeepTaskRelation(f.write, WellKnownIds.MAIN_TASK))
        // PRD §4: emptying a cell blanks its task's title, and a blank title is what deletes.
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(f.writeUnderChapter, ""))

        val row = rowOf(s, key)
        assertEquals(TaskRelationsDomain.Section.Broken, row?.section)
        assertEquals(TaskRelationsDomain.Break.TaskGone, row?.broken)
        // Broken outranks section 1, but the row still offers section 1's own button.
        assertTrue(row!!.kept)
    }

    @Test
    fun a_pair_whose_target_is_deleted_is_broken() {
        val f = fixture()
        val key = TaskRelationKey(f.write, f.book)
        var s = SchedulerReducer.reduce(f.state, SchedulerIntent.RecordTaskRelation(f.write, f.book, true))
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(f.bookCell, ""))

        val row = rowOf(s, key)
        assertEquals(TaskRelationsDomain.Section.Broken, row?.section)
        assertEquals(TaskRelationsDomain.Break.TargetGone, row?.broken)
    }

    @Test
    fun a_pair_whose_task_has_no_occurrence_under_the_target_is_broken() {
        val f = fixture()
        // "Read" lives under Notes, so it has no chain under Book at all.
        val key = TaskRelationKey(f.read, f.book)
        val s = SchedulerReducer.reduce(f.state, SchedulerIntent.RecordTaskRelation(f.read, f.book, true))

        val row = rowOf(s, key)
        assertEquals(TaskRelationsDomain.Section.Broken, row?.section)
        assertEquals(TaskRelationsDomain.Break.Moved, row?.broken)
    }

    // ----- order and persistence ------------------------------------------------------------------

    @Test
    fun the_rows_come_out_section_by_section_then_by_title() {
        val f = fixture()
        var s = SchedulerReducer.reduce(f.state, SchedulerIntent.RecordTaskRelation(f.read, f.book, true))
        s = SchedulerReducer.reduce(s, SchedulerIntent.RecordTaskRelation(f.write, WellKnownIds.MAIN_TASK, false))
        s = SchedulerReducer.reduce(s, SchedulerIntent.RecordTaskRelation(f.write, f.book, true))
        s = SchedulerReducer.reduce(s, SchedulerIntent.KeepTaskRelation(f.write, f.book))

        assertEquals(
            listOf(
                TaskRelationsDomain.Section.Kept,
                TaskRelationsDomain.Section.Opened,
                TaskRelationsDomain.Section.Broken,
            ),
            TaskRelationsDomain.rows(s).map { it.section },
        )
    }

    @Test
    fun the_pairs_are_persisted_and_synced_and_an_older_payload_decodes_without_them() {
        val f = fixture()
        var kept = SchedulerReducer.reduce(f.state, SchedulerIntent.KeepTaskRelation(f.write, f.book))
        kept = SchedulerReducer.reduce(kept, SchedulerIntent.RecordTaskRelation(f.read, WellKnownIds.MAIN_TASK, false))

        val decoded = SchedulerStateCodec.decode(SchedulerStateCodec.encode(kept))!!
        assertEquals(kept.taskRelations, decoded.taskRelations)
        // An all-false mark is the "opened, never changed" fact, so it must survive the round trip.
        assertTrue(TaskRelationKey(f.read, WellKnownIds.MAIN_TASK) in decoded.taskRelations)

        // Authoritative user data: which pairs are worth keeping is a judgement the other devices must see.
        assertNotEquals(
            SchedulerStateCodec.syncFingerprint(f.state),
            SchedulerStateCodec.syncFingerprint(kept),
        )

        // A payload written before the window existed carries no such list at all and must still load.
        val legacy = SchedulerStateCodec.encode(f.state)
        assertTrue("taskRelations" !in legacy || "\"taskRelations\":[]" in legacy)
        assertEquals(emptyMap(), SchedulerStateCodec.decode(legacy)!!.taskRelations)
    }
}
