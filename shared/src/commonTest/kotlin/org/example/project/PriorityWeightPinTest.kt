package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.example.project.scheduler.model.CellId
import org.example.project.scheduler.model.CellListId
import org.example.project.scheduler.model.PriorityWeightPin
import org.example.project.scheduler.persistence.SchedulerStateCodec
import org.example.project.scheduler.state.HistoryCategory
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState

/**
 * PRD §5 the priority-weight window's **pins**: the inputs the user holds while an optional-row edit scales
 * the path around them.
 *
 * A pin is the ACCOUNT's, not the open window's: it survives closing and re-opening the table, it is
 * persisted, and it reaches the other devices — which is what separates it from the row selection and the
 * row editor beside it, both of which are readings of the table and stay in Compose.
 *
 * A pin names its column by INDEX (a column has no identity of its own), so the three structural column
 * edits have to carry the table's pins with them, exactly as they carry every row's value.
 */
class PriorityWeightPinTest {

    /** `root ─ Book ─ (Chapter, Other)`, plus the cells of both tables. */
    private class Fixture(
        val state: SchedulerState,
        val root: CellListId,
        val bookList: CellListId,
        val bookCell: CellId,
        val chapterCell: CellId,
        val otherCell: CellId,
    )

    private fun fixture(): Fixture {
        var s = SchedulerState.empty()
        val rootCells = { s.lists[s.rootListId]!!.cellIds }
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(rootCells()[0], "Book"))
        val bookCell = rootCells()[0]
        val book = s.cells[bookCell]!!.taskId!!
        val bookList = s.tasks[book]!!.childListId!!
        val bookCells = { s.lists[bookList]!!.cellIds }
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(bookCells()[0], "Chapter"))
        val chapterCell = bookCells()[0]
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(bookCells()[1], "Other"))
        val otherCell = bookCells()[1]
        return Fixture(s, s.rootListId, bookList, bookCell, chapterCell, otherCell)
    }

    private fun toggle(s: SchedulerState, listId: CellListId, cellId: CellId?, column: Int) =
        SchedulerReducer.reduce(s, SchedulerIntent.TogglePriorityWeightPin(listId, cellId, column))

    // ----- what a pin is filed under ----------------------------------------------------------------

    @Test
    fun a_pin_is_filed_under_its_table_and_toggles_off_again() {
        val f = fixture()
        var s = toggle(f.state, f.bookList, f.chapterCell, column = 0)
        assertEquals(setOf(PriorityWeightPin(f.chapterCell, 0)), s.priorityWeightPins[f.bookList])
        // Another table's pins are a different set entirely — a pin belongs to the table it was set in.
        assertEquals(null, s.priorityWeightPins[f.root])

        // A column HEADER pin is the same column with no cell, and is a pin of its own.
        s = toggle(s, f.bookList, cellId = null, column = 0)
        assertEquals(
            setOf(PriorityWeightPin(f.chapterCell, 0), PriorityWeightPin(null, 0)),
            s.priorityWeightPins[f.bookList],
        )

        s = toggle(s, f.bookList, f.chapterCell, column = 0)
        assertEquals(setOf(PriorityWeightPin(null, 0)), s.priorityWeightPins[f.bookList])
        // The empty set is dropped rather than stored, so an account that unpins everything encodes nothing.
        s = toggle(s, f.bookList, cellId = null, column = 0)
        assertEquals(null, s.priorityWeightPins[f.bookList])
        assertEquals(emptyMap(), s.priorityWeightPins)
    }

    @Test
    fun pinning_is_one_undo_unit() {
        val f = fixture()
        val units = f.state.histories.forCategory(HistoryCategory.Main).units.size
        val s = toggle(f.state, f.bookList, f.chapterCell, column = 0)
        // User rule 2026-11-01: a user's action, so a unit, exactly like the relative-priority window's own pins.
        assertEquals(units + 1, s.histories.forCategory(HistoryCategory.Main).units.size)
        assertEquals(f.state.priorityWeightPins, SchedulerReducer.reduce(s, SchedulerIntent.Undo).priorityWeightPins)
    }

    // ----- the anomaly this exists for: the pin outlives the window --------------------------------

    @Test
    fun the_pins_are_persisted_and_synced_and_an_older_payload_decodes_without_them() {
        val f = fixture()
        var pinned = toggle(f.state, f.bookList, f.chapterCell, column = 0)
        pinned = toggle(pinned, f.bookList, cellId = null, column = 0)

        // Closing and re-opening the window is a new composition reading THIS map, so the round trip
        // through the store is what "the pin is still there" means.
        val decoded = SchedulerStateCodec.decode(SchedulerStateCodec.encode(pinned))!!
        assertEquals(
            setOf(PriorityWeightPin(f.chapterCell, 0), PriorityWeightPin(null, 0)),
            decoded.priorityWeightPins[f.bookList],
        )

        // Authoritative user data: a pin the desktop set is one the phone must honour.
        assertNotEquals(
            SchedulerStateCodec.syncFingerprint(f.state),
            SchedulerStateCodec.syncFingerprint(pinned),
        )

        // A payload written before pins outlived their window carries no such key at all and must load.
        val legacy = SchedulerStateCodec.encode(f.state)
        assertTrue("priorityWeightPins" !in legacy || "\"priorityWeightPins\":[]" in legacy)
        assertEquals(emptyMap(), SchedulerStateCodec.decode(legacy)!!.priorityWeightPins)
    }

    @Test
    fun the_encoded_payload_does_not_depend_on_iteration_order() {
        val f = fixture()
        val one = toggle(toggle(f.state, f.bookList, f.chapterCell, 0), f.bookList, f.otherCell, 0)
        val other = toggle(toggle(f.state, f.bookList, f.otherCell, 0), f.bookList, f.chapterCell, 0)
        // The state's, not the history's: two orders are two histories.
        assertEquals(SchedulerStateCodec.syncFingerprint(one).statePayload, SchedulerStateCodec.syncFingerprint(other).statePayload)
    }

    // ----- a pin follows its column ---------------------------------------------------------------

    @Test
    fun an_added_column_pushes_the_pins_to_its_right() {
        val f = fixture()
        var s = SchedulerReducer.reduce(f.state, SchedulerIntent.AddPriorityColumn(f.bookList))
        s = toggle(s, f.bookList, f.chapterCell, column = 0)
        s = toggle(s, f.bookList, f.chapterCell, column = 1)

        s = SchedulerReducer.reduce(s, SchedulerIntent.AddPriorityColumn(f.bookList, index = 0))
        assertEquals(listOf(0.0, 1.0, 0.0), s.lists[f.bookList]!!.weightColumns)
        assertEquals(
            setOf(PriorityWeightPin(f.chapterCell, 1), PriorityWeightPin(f.chapterCell, 2)),
            s.priorityWeightPins[f.bookList],
        )
    }

    @Test
    fun a_deleted_column_takes_its_own_pins_and_pulls_the_rest_left() {
        val f = fixture()
        var s = SchedulerReducer.reduce(f.state, SchedulerIntent.AddPriorityColumn(f.bookList))
        s = SchedulerReducer.reduce(s, SchedulerIntent.AddPriorityColumn(f.bookList))
        s = toggle(s, f.bookList, f.chapterCell, column = 0)
        s = toggle(s, f.bookList, cellId = null, column = 1)
        s = toggle(s, f.bookList, f.otherCell, column = 2)

        s = SchedulerReducer.reduce(s, SchedulerIntent.DeletePriorityColumn(f.bookList, column = 1))
        assertEquals(
            setOf(PriorityWeightPin(f.chapterCell, 0), PriorityWeightPin(f.otherCell, 1)),
            s.priorityWeightPins[f.bookList],
        )

        s = SchedulerReducer.reduce(s, SchedulerIntent.DeletePriorityColumn(f.bookList, column = 1))
        assertEquals(setOf(PriorityWeightPin(f.chapterCell, 0)), s.priorityWeightPins[f.bookList])

        // The table keeps at least one column, so a refused delete moves no pin either.
        s = SchedulerReducer.reduce(s, SchedulerIntent.DeletePriorityColumn(f.bookList, column = 0))
        assertEquals(listOf(1.0), s.lists[f.bookList]!!.weightColumns)
        assertEquals(setOf(PriorityWeightPin(f.chapterCell, 0)), s.priorityWeightPins[f.bookList])
    }

    @Test
    fun a_moved_column_carries_its_pins_with_it() {
        val f = fixture()
        var s = SchedulerReducer.reduce(f.state, SchedulerIntent.AddPriorityColumn(f.bookList))
        s = SchedulerReducer.reduce(s, SchedulerIntent.AddPriorityColumn(f.bookList))
        s = toggle(s, f.bookList, f.chapterCell, column = 0)
        s = toggle(s, f.bookList, f.otherCell, column = 2)
        // Tell the three columns apart by their headers, so the pins can be checked against them.
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetPriorityColumnWeight(f.bookList, 1, 0.5))
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetPriorityColumnWeight(f.bookList, 2, 0.25))

        // The first column is dropped at the far end: 0→2, and the two behind it shift left.
        s = SchedulerReducer.reduce(s, SchedulerIntent.MovePriorityColumn(f.bookList, from = 0, to = 3))
        assertEquals(listOf(0.5, 0.25, 1.0), s.lists[f.bookList]!!.weightColumns)
        assertEquals(
            setOf(PriorityWeightPin(f.chapterCell, 2), PriorityWeightPin(f.otherCell, 1)),
            s.priorityWeightPins[f.bookList],
        )

        // …and back the other way: the last column to the front, the two ahead of it shifting right.
        s = SchedulerReducer.reduce(s, SchedulerIntent.MovePriorityColumn(f.bookList, from = 2, to = 0))
        assertEquals(listOf(1.0, 0.5, 0.25), s.lists[f.bookList]!!.weightColumns)
        assertEquals(
            setOf(PriorityWeightPin(f.chapterCell, 0), PriorityWeightPin(f.otherCell, 2)),
            s.priorityWeightPins[f.bookList],
        )

        // A move that lands where it started moves nothing at all.
        val before = s.priorityWeightPins
        s = SchedulerReducer.reduce(s, SchedulerIntent.MovePriorityColumn(f.bookList, from = 1, to = 1))
        assertEquals(before, s.priorityWeightPins)
    }

    @Test
    fun resetting_a_column_leaves_its_pins_alone() {
        val f = fixture()
        var s = SchedulerReducer.reduce(f.state, SchedulerIntent.AddPriorityColumn(f.bookList))
        s = toggle(s, f.bookList, f.chapterCell, column = 1)
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetPriorityWeight(f.chapterCell, 1, 4.0))
        s = SchedulerReducer.reduce(s, SchedulerIntent.ResetPriorityColumn(f.bookList, column = 1))
        // The field the user pinned is still that field; a reset moved no column, only what it says.
        assertEquals(0.0, s.cells[f.chapterCell]!!.priorityWeights[1])
        assertEquals(setOf(PriorityWeightPin(f.chapterCell, 1)), s.priorityWeightPins[f.bookList])
    }
}
