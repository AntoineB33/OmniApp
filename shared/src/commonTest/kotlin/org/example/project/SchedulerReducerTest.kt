package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState

class SchedulerReducerTest {
    @Test
    fun click_sets_main_and_clears_multi() {
        var s = seedThreeTasks()
        val visible = s.lists[s.rootListId]!!.cellIds

        s = SchedulerReducer.reduce(
            s,
            SchedulerIntent.ClickCell(cellId = visible[0], ctrl = false, shift = false, visibleOrder = visible)
        )
        assertEquals(visible[0], s.selection.main)
        assertTrue(s.selection.selected.isEmpty())

        s = SchedulerReducer.reduce(
            s,
            SchedulerIntent.ClickCell(cellId = visible[2], ctrl = true, shift = false, visibleOrder = visible)
        )
        assertEquals(visible[2], s.selection.main)
        assertTrue(s.selection.selected.contains(visible[2]))

        s = SchedulerReducer.reduce(
            s,
            SchedulerIntent.ClickCell(cellId = visible[1], ctrl = false, shift = false, visibleOrder = visible)
        )
        assertEquals(visible[1], s.selection.main)
        assertTrue(s.selection.selected.isEmpty())
    }

    @Test
    fun shift_click_selects_range_in_visible_order() {
        var s = seedThreeTasks()
        val visible = s.lists[s.rootListId]!!.cellIds

        s = SchedulerReducer.reduce(
            s,
            SchedulerIntent.ClickCell(cellId = visible[0], ctrl = false, shift = false, visibleOrder = visible)
        )
        s = SchedulerReducer.reduce(
            s,
            SchedulerIntent.ClickCell(cellId = visible[2], ctrl = false, shift = true, visibleOrder = visible)
        )

        assertEquals(visible[2], s.selection.main)
        assertEquals(setOf(visible[0], visible[1], visible[2]), s.selection.selected)
    }

    @Test
    fun toggle_expand_is_undoable() {
        var s = seedThreeTasks()
        val cellId = s.lists[s.rootListId]!!.cellIds.first()

        s = SchedulerReducer.reduce(s, SchedulerIntent.ToggleExpand(cellId))
        assertTrue(s.expanded.contains(cellId))

        s = SchedulerReducer.reduce(s, SchedulerIntent.Undo)
        assertTrue(!s.expanded.contains(cellId))

        s = SchedulerReducer.reduce(s, SchedulerIntent.Redo)
        assertTrue(s.expanded.contains(cellId))
    }

    private fun seedThreeTasks(): SchedulerState {
        var s = SchedulerState.empty()
        val root = s.rootListId

        fun setTitleAt(index: Int, title: String) {
            val id = s.lists[root]!!.cellIds[index]
            s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(id, title))
        }

        setTitleAt(0, "A")
        setTitleAt(1, "B")
        setTitleAt(2, "C")

        return s
    }
}

