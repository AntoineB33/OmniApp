package org.example.project

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.domain.SearchDomain
import org.example.project.scheduler.model.CellId
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.state.EditExitNavigation
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState
import org.example.project.ui.cellEditPopupOffset

/** User rule 2026-11-02: the rectangle of a task cell in Edit Mode, and the search bar's completion list. */
class CellEditPopupRulesTest {
    private val popup = IntSize(300, 200)
    private val room = IntSize(1000, 800)

    @Test
    fun the_rectangle_stands_below_the_cell() {
        assertEquals(IntOffset(100, 130), cellEditPopupOffset(Rect(100f, 100f, 250f, 130f), popup, room))
    }

    @Test
    fun above_when_there_is_not_enough_room_below() {
        assertEquals(IntOffset(100, 500), cellEditPopupOffset(Rect(100f, 700f, 250f, 730f), popup, room))
    }

    @Test
    fun on_the_left_when_neither_below_nor_above_fit() {
        val tall = IntSize(300, 500)
        assertEquals(IntOffset(300, 300), cellEditPopupOffset(Rect(600f, 400f, 750f, 430f), tall, IntSize(1000, 800)))
    }

    @Test
    fun on_the_right_when_the_left_has_no_room_either() {
        val tall = IntSize(300, 500)
        assertEquals(IntOffset(250, 300), cellEditPopupOffset(Rect(100f, 400f, 250f, 430f), tall, IntSize(1000, 800)))
    }

    @Test
    fun fitting_nowhere_it_stands_below_as_far_as_the_room_lets_it() {
        assertEquals(IntOffset(0, 0), cellEditPopupOffset(Rect(10f, 100f, 90f, 130f), IntSize(100, 200), IntSize(100, 200)))
    }

    private fun r(state: SchedulerState, intent: SchedulerIntent) = SchedulerReducer.reduce(state, intent)

    private class Fixture(val state: SchedulerState, val apple: CellId, val pear: TaskId, val kiwi: TaskId)

    /** Apple and Pear at the top level, Kiwi under Pear. */
    private fun fixture(): Fixture {
        var s = SchedulerState.empty()
        val apple = s.lists[s.rootListId]!!.cellIds.last()
        s = r(s, SchedulerIntent.SetCellTitle(apple, "Apple"))
        val pearCell = s.lists[s.rootListId]!!.cellIds.last()
        s = r(s, SchedulerIntent.SetCellTitle(pearCell, "Pear"))
        val pear = s.cells[pearCell]!!.taskId!!
        val kiwiCell = s.lists[s.tasks[pear]!!.childListId!!]!!.cellIds.last()
        s = r(s, SchedulerIntent.SetCellTitle(kiwiCell, "Kiwi"))
        return Fixture(s, apple, pear, s.cells[kiwiCell]!!.taskId!!)
    }

    @Test
    fun the_list_is_what_the_reducer_would_take() {
        val f = fixture()
        val assignable = SchedulerDomain.assignableTaskIds(f.state, f.apple)
        assertTrue(f.kiwi in assignable)
        assertFalse(f.pear in assignable, "a task of the cell's own list")
        for (task in f.state.tasks.keys) {
            assertEquals(SchedulerDomain.canAssignTaskId(f.state, f.apple, task), task in assignable, task.value)
        }
    }

    @Test
    fun a_pick_replaces_the_task_and_leaves_edit_mode() {
        val f = fixture()
        var s = r(f.state, SchedulerIntent.BeginEdit(f.apple, initialText = "K"))
        s = r(s, SchedulerIntent.PickTaskFromMenu(f.kiwi))
        s = r(s, SchedulerIntent.ExitEdit(EditExitNavigation.Stay))
        assertNull(s.editSession)
        assertEquals(f.kiwi, s.cells[f.apple]!!.taskId)
    }

    @Test
    fun the_completion_list_is_off_by_default() {
        assertNull(SearchDomain.Config().completion)
        assertEquals(emptyList(), SearchDomain.completionOptions(fixture().state, null, ""))
    }

    @Test
    fun an_option_is_listed_once_and_never_the_text_of_the_bar() {
        var s = fixture().state
        // A second task of the same title: one option.
        s = r(s, SchedulerIntent.SetCellTitle(s.lists[s.rootListId]!!.cellIds.last(), "Kiwi"))
        val all = SearchDomain.completionOptions(s, SearchDomain.Completion.TaskTitles, "")
        assertEquals(all.distinct(), all)
        assertEquals(1, all.count { it == "Kiwi" })
        val typed = SearchDomain.completionOptions(s, SearchDomain.Completion.TaskTitles, "kiwi")
        assertFalse(typed.any { it.equals("kiwi", ignoreCase = true) })
        assertEquals(listOf("Apple"), SearchDomain.completionOptions(s, SearchDomain.Completion.TaskTitles, "ppl"))
    }

    @Test
    fun a_configuration_stored_before_the_list_has_none_and_the_new_one_comes_back() {
        assertNull(SearchDomain.Config.decode("""{"query":"x"}""")!!.completion)
        val on = SearchDomain.Config(completion = SearchDomain.Completion.TaskTitles)
        assertEquals(SearchDomain.Completion.TaskTitles, SearchDomain.Config.decode(on.encode())!!.completion)
    }
}
