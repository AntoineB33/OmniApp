package org.example.project

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.example.project.scheduler.model.AlarmEntry
import org.example.project.scheduler.model.CellId
import org.example.project.scheduler.persistence.SchedulerStateCodec
import org.example.project.scheduler.state.FocusDelta
import org.example.project.scheduler.state.HistoryCategory
import org.example.project.scheduler.state.HistoryWindow
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState
import org.example.project.scheduler.state.windowSelectionKey

/**
 * PRD §5, user rule 2026-09-25: *"ctrl+z or ctrl+y or ctrl+shift+z navigates in changes relative to the focused
 * window, alt+arrow keys navigates in positions/selections relative to the focused window, and shift+alt+arrow
 * keys navigates in all positions/selections."* Settled with the user: a position is **which window has the
 * focus and what is selected in it**, and every window with a selection records it.
 *
 * It came out of an anomaly: the Search window listing history units showed no new unit when the user clicked
 * from window to window — because only five windows could take the focus, so a press in any other recorded
 * nothing. Every window is a focus target now.
 */
class HistoryChordsByWindowTest {

    private var stamped = false

    @BeforeTest
    fun stampWindows() {
        // The shell's setting: every unit is stamped with the focused window.
        stamped = SchedulerReducer.stampsWindow
        SchedulerReducer.stampsWindow = true
    }

    @AfterTest
    fun restore() {
        SchedulerReducer.stampsWindow = stamped
    }

    private fun r(state: SchedulerState, intent: SchedulerIntent) = SchedulerReducer.reduce(state, intent)

    private fun focus(state: SchedulerState, window: HistoryWindow, instance: String = "") =
        r(state, SchedulerIntent.FocusWindow(window, instance))

    private fun firstCell(state: SchedulerState): CellId = state.lists[state.rootListId]!!.cellIds.first()

    private fun searchRow(state: SchedulerState, instance: String = ""): String? =
        state.windowSelections[windowSelectionKey(HistoryWindow.Search, instance)]

    private fun select(state: SchedulerState, key: String?, instance: String = "", record: Boolean = true) =
        r(state, SchedulerIntent.SelectInWindow(HistoryWindow.Search, instance, key, record))

    @Test
    fun every_window_takes_the_focus_and_each_move_is_a_unit() {
        var s = SchedulerState.empty()
        for (window in listOf(HistoryWindow.Search, HistoryWindow.ConfigSearch, HistoryWindow.Categories, HistoryWindow.Tree)) {
            s = focus(s, window)
            assertEquals(window, s.focusedWindow)
        }
        val moves = s.histories.forCategory(HistoryCategory.WindowNav).units
        assertEquals(listOf("Focus Search", "Focus ConfigSearch", "Focus Categories", "Focus Tree"), moves.map { it.delta.label })
        // Pressing again in the window that has it is no move.
        assertEquals(s, focus(s, HistoryWindow.Tree))
        // A copy of a window is a place of its own.
        s = focus(s, HistoryWindow.Search, "#2")
        assertEquals("#2", s.focusedInstance)
        assertEquals("Focus Search#2", s.histories.forCategory(HistoryCategory.WindowNav).units.last().delta.label)
    }

    @Test
    fun ctrl_z_undoes_the_last_change_made_in_the_focused_window_only() {
        var s = SchedulerState.empty()
        val cell = firstCell(s)
        s = r(s, SchedulerIntent.SetCellTitle(cell, "Daily"))
        s = focus(s, HistoryWindow.Alarms)
        s = r(s, SchedulerIntent.SetAlarms(listOf(AlarmEntry(id = "a1", label = "Wake", timeOfDayMinutes = 420))))
        s = focus(s, HistoryWindow.Tree)

        // In the tree: the title goes, the alarm made later in another window stays.
        val inTree = r(s, SchedulerIntent.Undo)
        assertEquals("", inTree.tasks[inTree.cells[cell]?.taskId]?.title.orEmpty())
        assertEquals(1, inTree.alarms.size)
        // In the Alarms window: the alarm goes, the title stays.
        val inAlarms = r(focus(s, HistoryWindow.Alarms), SchedulerIntent.Undo)
        assertTrue(inAlarms.alarms.isEmpty())
        assertEquals("Daily", inAlarms.tasks[inAlarms.cells[cell]!!.taskId]!!.title)
        // Redo is relative to the window too, and a window with no change of its own has nothing to walk.
        assertEquals(1, r(inAlarms, SchedulerIntent.Redo).alarms.size)
        val inSearch = focus(s, HistoryWindow.Search)
        assertEquals(inSearch.histories, r(inSearch, SchedulerIntent.Undo).histories)
    }

    @Test
    fun a_change_that_knows_no_window_is_the_trees() {
        // Units written before units knew their window (or by a headless host) are undone from the tree, where
        // they were undone from then — and a calendar one from the calendar.
        SchedulerReducer.stampsWindow = false
        var s = SchedulerState.empty()
        val cell = firstCell(s)
        s = r(s, SchedulerIntent.SetCellTitle(cell, "Daily"))
        assertNull(s.histories.forCategory(HistoryCategory.Main).units.last().window)
        assertEquals("Daily", r(s.copy(focusedWindow = HistoryWindow.Alarms), SchedulerIntent.Undo).let { it.tasks[it.cells[cell]!!.taskId]!!.title })
        assertEquals("", r(s, SchedulerIntent.Undo).let { it.tasks[it.cells[cell]?.taskId]?.title.orEmpty() })
    }

    @Test
    fun alt_arrows_walk_the_selections_of_the_focused_window_only() {
        var s = SchedulerState.empty()
        val cell = firstCell(s)
        s = r(s, SchedulerIntent.SetCellTitle(cell, "Daily"))
        s = r(s, SchedulerIntent.ClickCell(cell, ctrl = false, shift = false, visibleOrder = listOf(cell)))
        s = focus(s, HistoryWindow.Search)
        s = select(s, "task:a")
        s = select(s, "task:b")
        // A copy's row is the copy's.
        s = select(s, "alarm:x", instance = "#2")

        // In Search: its own row goes back, the tree's selection and the copy's row stay.
        val back = r(s, SchedulerIntent.UndoSelection)
        assertEquals("task:a", searchRow(back))
        assertEquals(cell, back.selection.main)
        assertEquals("alarm:x", searchRow(back, "#2"))
        assertEquals("task:b", searchRow(r(back, SchedulerIntent.RedoSelection)))
        // In the tree: the tree's selection goes back, Search's rows stay.
        val inTree = r(focus(s, HistoryWindow.Tree), SchedulerIntent.UndoSelection)
        assertNull(inTree.selection.main)
        assertEquals("task:b", searchRow(inTree))
        // In the copy: the copy's.
        assertNull(searchRow(r(focus(s, HistoryWindow.Search, "#2"), SchedulerIntent.UndoSelection), "#2"))
    }

    @Test
    fun a_selection_the_window_resets_on_its_own_is_no_position() {
        var s = focus(SchedulerState.empty(), HistoryWindow.Search)
        val before = s.histories.forCategory(HistoryCategory.Selection).units.size
        s = select(s, "task:a", record = false)
        assertEquals("task:a", searchRow(s))
        assertEquals(before, s.histories.forCategory(HistoryCategory.Selection).units.size)
        // Selecting what is already selected is nothing either.
        assertEquals(s, select(s, "task:a"))
    }

    @Test
    fun shift_alt_arrows_walk_every_selection_and_every_move_of_the_focus_in_order() {
        var s = SchedulerState.empty()
        val cell = firstCell(s)
        s = r(s, SchedulerIntent.SetCellTitle(cell, "Daily"))
        s = focus(s, HistoryWindow.Search)
        s = select(s, "task:a")
        s = focus(s, HistoryWindow.Tree)
        s = r(s, SchedulerIntent.ClickCell(cell, ctrl = false, shift = false, visibleOrder = listOf(cell)))
        val end = s

        // Back: the tree's selection, then the move to the tree, then Search's row, then the move to Search.
        s = r(s, SchedulerIntent.UndoPosition)
        assertNull(s.selection.main)
        assertEquals(HistoryWindow.Tree, s.focusedWindow)
        s = r(s, SchedulerIntent.UndoPosition)
        assertEquals(HistoryWindow.Search, s.focusedWindow)
        assertEquals("task:a", searchRow(s))
        s = r(s, SchedulerIntent.UndoPosition)
        assertNull(searchRow(s))
        s = r(s, SchedulerIntent.UndoPosition)
        assertEquals(HistoryWindow.Tree, s.focusedWindow)
        // The title is a change, not a position: Shift+Alt never touches it.
        assertEquals("Daily", s.tasks[s.cells[cell]!!.taskId]!!.title)

        // Forward again, in the same order, back to where it was.
        repeat(4) { s = r(s, SchedulerIntent.RedoPosition) }
        assertEquals(end.focusedWindow, s.focusedWindow)
        assertEquals(end.selection, s.selection)
        assertEquals(end.windowSelections, s.windowSelections)
    }

    @Test
    fun a_focus_move_and_a_selection_in_the_same_millisecond_are_still_walked_in_order() {
        // A press in a window moves the focus AND selects, in one instant. The two land in two categories; the
        // device's units are still totally ordered, so the selection is walked back first and redone last.
        val clock = SchedulerReducer.clock
        SchedulerReducer.clock = object : org.example.project.time.AppClock {
            override fun nowMillis(): Long = 1_700_000_000_000L
        }
        try {
            var s = focus(SchedulerState.empty(), HistoryWindow.Search)
            s = select(s, "task:a")
            val nav = s.histories.forCategory(HistoryCategory.WindowNav).units.last()
            val sel = s.histories.forCategory(HistoryCategory.Selection).units.last()
            assertTrue(sel.deviceSeq > nav.deviceSeq, "the selection was made after the move")
            s = r(s, SchedulerIntent.UndoPosition)
            assertNull(searchRow(s))
            assertEquals(HistoryWindow.Search, s.focusedWindow)
            s = r(s, SchedulerIntent.UndoPosition)
            assertEquals(HistoryWindow.Tree, s.focusedWindow)
            s = r(s, SchedulerIntent.RedoPosition)
            assertEquals(HistoryWindow.Search, s.focusedWindow)
            assertNull(searchRow(s))
        } finally {
            SchedulerReducer.clock = clock
        }
    }

    @Test
    fun the_all_tasks_window_records_its_selection_and_alt_left_there_puts_it_back() {
        var s = SchedulerState.empty()
        val cell = firstCell(s)
        s = r(s, SchedulerIntent.SetCellTitle(cell, "Daily"))
        s = focus(s, HistoryWindow.TaskList)
        val rows = listOf(cell)
        s = r(s, SchedulerIntent.InTaskList(SchedulerIntent.ClickCell(cell, ctrl = false, shift = false, visibleOrder = rows), rows))
        assertEquals(cell, s.taskListSelection.main)
        assertNull(s.selection.main, "the tree's own selection is not the window's")
        assertEquals("Selection (All tasks)", s.histories.forCategory(HistoryCategory.Selection).units.last().delta.label)

        val back = r(s, SchedulerIntent.UndoSelection)
        assertNull(back.taskListSelection.main)
        assertEquals(cell, r(back, SchedulerIntent.RedoSelection).taskListSelection.main)
    }

    // ----- Persistence (CLAUDE.md: persisted-DB compatibility) --------------------------------------

    @Test
    fun the_new_positions_survive_the_codec_and_the_old_shapes_still_decode() {
        var s = SchedulerState.empty()
        val cell = firstCell(s)
        s = r(s, SchedulerIntent.SetCellTitle(cell, "Daily"))
        s = focus(s, HistoryWindow.Search, "#2")
        s = select(s, "task:a", instance = "#2")
        s = focus(s, HistoryWindow.TaskList)
        s = r(s, SchedulerIntent.InTaskList(SchedulerIntent.ClickCell(cell, ctrl = false, shift = false, visibleOrder = listOf(cell)), listOf(cell)))

        val decoded = assertNotNull(SchedulerStateCodec.decode(SchedulerStateCodec.encode(s)))
        assertEquals(HistoryWindow.TaskList, decoded.focusedWindow)
        for (category in listOf(HistoryCategory.WindowNav, HistoryCategory.Selection)) {
            assertEquals(
                s.histories.forCategory(category).units.map { it.delta },
                decoded.histories.forCategory(category).units.map { it.delta },
            )
        }

        // A focus move written before copies existed (no instances), between two of the five old targets.
        val old = SchedulerStateCodec.decodeUnit(
            timeMillis = 1L, chronoId = 0L, tainted = false, window = null, deviceId = "d", deviceSeq = 1000L,
            undone = false, text = """{"type":"focus","before":"Tree","after":"Calendar"}""",
        )
        assertEquals(FocusDelta(HistoryWindow.Tree, HistoryWindow.Calendar), old?.delta)

        // The focused window as an older build wrote it, and a name this build does not know.
        val encoded = SchedulerStateCodec.encode(s)
        assertTrue("\"focusedWindow\":\"TaskList\"" in encoded)
        assertEquals(
            HistoryWindow.Calendar,
            SchedulerStateCodec.decode(encoded.replace("\"focusedWindow\":\"TaskList\"", "\"focusedWindow\":\"Calendar\""))?.focusedWindow,
        )
        assertEquals(
            HistoryWindow.Tree,
            SchedulerStateCodec.decode(encoded.replace("\"focusedWindow\":\"TaskList\"", "\"focusedWindow\":\"Wormhole\""))?.focusedWindow,
        )
    }
}
