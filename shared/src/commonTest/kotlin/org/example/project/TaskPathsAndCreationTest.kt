package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.SearchDomain
import org.example.project.scheduler.domain.TaskPathsDomain
import org.example.project.scheduler.model.CellId
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState

/**
 * User spec 2026-09-26: the Search window's "creation" type — one row per kind of element the user makes — the
 * task edit window's **Paths** (where a task sits, and where else it may go), and the window rows listed once per
 * window TYPE.
 */
class TaskPathsAndCreationTest {
    private fun r(state: SchedulerState, intent: SchedulerIntent) = SchedulerReducer.reduce(state, intent)

    private fun freeRootCell(state: SchedulerState): CellId = state.lists[state.rootListId]!!.cellIds.last()

    private fun freeChildCell(state: SchedulerState, taskId: TaskId): CellId =
        state.lists[state.tasks[taskId]!!.childListId!!]!!.cellIds.last()

    private fun taskWithTitle(state: SchedulerState, title: String): TaskId = state.tasks.values.first { it.title == title }.id

    /** Apple / Pie, and Banana at the top level. */
    private fun tree(): SchedulerState {
        var s = SchedulerState.empty()
        s = r(s, SchedulerIntent.SetCellTitle(freeRootCell(s), "Apple"))
        s = r(s, SchedulerIntent.SetCellTitle(freeChildCell(s, taskWithTitle(s, "Apple")), "Pie"))
        s = r(s, SchedulerIntent.SetCellTitle(freeRootCell(s), "Banana"))
        return s
    }

    @Test
    fun a_created_task_sits_at_the_top_of_the_tree() {
        val s = r(tree(), SchedulerIntent.CreateTask("New task"))
        val created = taskWithTitle(s, "New task")
        assertEquals(listOf(TaskPathsDomain.TOP_LEVEL), TaskPathsDomain.occurrences(s, created).map { it.label })
        // The top level keeps its placeholder, so the next one can be made too.
        assertTrue(org.example.project.scheduler.domain.SchedulerDomain.isTextuallyEmptyCell(s, freeRootCell(s)))
        assertEquals(tree().tasks.size, r(s, SchedulerIntent.Undo).tasks.size, "one undoable unit")
    }

    @Test
    fun a_path_is_added_under_a_parent_and_removed_again_but_never_the_last() {
        var s = tree()
        val pie = taskWithTitle(s, "Pie")
        val banana = taskWithTitle(s, "Banana")
        s = r(s, SchedulerIntent.AddTaskPath(pie, banana))
        assertEquals(listOf("Apple", "Banana"), TaskPathsDomain.occurrences(s, pie).map { it.label }.sorted())
        // The title is shared: it is one task in two places.
        assertEquals(1, s.tasks.values.count { it.title == "Pie" })

        val underApple = TaskPathsDomain.occurrences(s, pie).first { it.label == "Apple" }.cellId
        s = r(s, SchedulerIntent.RemoveTaskPath(underApple))
        assertEquals(listOf("Banana"), TaskPathsDomain.occurrences(s, pie).map { it.label })
        assertEquals("Pie", s.tasks[pie]?.title, "removing a path never deletes the task")

        val last = TaskPathsDomain.occurrences(s, pie).single().cellId
        assertEquals(s, r(s, SchedulerIntent.RemoveTaskPath(last)), "the last place is kept")
        // Undo puts the removed path back.
        assertEquals(2, TaskPathsDomain.occurrences(r(s, SchedulerIntent.Undo), pie).size)
    }

    @Test
    fun the_trees_rules_refuse_a_cycle_and_a_second_place_in_one_list() {
        val s = tree()
        val apple = taskWithTitle(s, "Apple")
        val pie = taskWithTitle(s, "Pie")
        assertFalse(TaskPathsDomain.canAddUnder(s, apple, pie), "Apple under its own child")
        assertEquals(s, r(s, SchedulerIntent.AddTaskPath(apple, pie)))
        assertFalse(TaskPathsDomain.canAddUnder(s, pie, apple), "Pie is already in Apple's list")
        // The candidates are only the places it may go, the top level first.
        val offered = TaskPathsDomain.candidates(s, pie, "")
        assertEquals(listOf(null, taskWithTitle(s, "Banana")), offered.map { it.parentTaskId })
        assertEquals(listOf("Banana"), TaskPathsDomain.candidates(s, pie, "ban").map { it.label })
    }

    @Test
    fun the_creation_type_lists_one_row_per_kind_the_user_makes() {
        val kinds = setOf(SearchDomain.Kind.Creation)
        val rows = SearchDomain.results(SchedulerState.empty(), kinds, "").map { it as SearchDomain.ItemResult }
        assertEquals(SearchDomain.CREATABLE.map { it.name }.toSet(), rows.map { it.id }.toSet())
        assertTrue(rows.all { it.kind == SearchDomain.Kind.Creation })
        assertEquals(listOf("New alarm"), SearchDomain.results(SchedulerState.empty(), kinds, "alarm").map { it.name })
        // Not made by the user: no creation row.
        listOf(SearchDomain.Kind.HistoryUnit, SearchDomain.Kind.TaskRelation, SearchDomain.Kind.Shortcut).forEach {
            assertFalse(it in SearchDomain.CREATABLE)
        }
    }

    @Test
    fun window_rows_can_be_one_per_type_and_the_default_timer_is_a_type_of_its_own() {
        val windows =
            listOf(
                SearchDomain.WindowEntry("AlarmOrTimerEdit#1", "Tea", SearchDomain.WindowStatus.Open, "object:Timer", "Timer"),
                SearchDomain.WindowEntry("AlarmOrTimerEdit#2", "Pasta", SearchDomain.WindowStatus.Minimized, "object:Timer", "Timer"),
                SearchDomain.WindowEntry("Search", "Search", SearchDomain.WindowStatus.NotOpen, "Search", "Search"),
                SearchDomain.WindowEntry(
                    "object:TimerDefaults", "Default timer", SearchDomain.WindowStatus.NotOpen,
                    "object:TimerDefaults", "Default timer", placeholder = true,
                ),
            )
        val kinds = setOf(SearchDomain.Kind.Window)
        fun names(filters: SearchDomain.Filters) =
            SearchDomain.results(SchedulerState.empty(), kinds, "", filters = filters, windows = windows)
                .map { (it as SearchDomain.ItemResult).let { row -> row.name to row.detail } }
                .toSet()
        // Every window: the two timers each, the closed Search; never a placeholder type.
        assertEquals(
            setOf("Tea" to "open", "Pasta" to "minimized", "Search" to "not open"),
            names(SearchDomain.Filters()),
        )
        // One per type: the timers as one row, open because one is; the default timer's window apart.
        assertEquals(
            setOf("Timer" to "open", "Search" to "not open", "Default timer" to "not open"),
            names(SearchDomain.Filters(windowDuplicates = SearchDomain.WindowDuplicates.Hidden)),
        )
        // The "New window" creation row opens a Search window configured exactly so; it survives its encoding.
        val config = SearchDomain.WINDOW_TYPES_CONFIG
        assertEquals(config, SearchDomain.Config.decode(config.encode()))
        assertEquals(SearchDomain.WindowDuplicates.Shown, SearchDomain.Config.decode("""{"kinds":["Window"]}""")!!.filters.windowDuplicates)
    }
}
