package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.domain.SearchDomain
import org.example.project.scheduler.model.CellId
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState
import org.example.project.scheduler.ui.COPY_ID_LABEL
import org.example.project.scheduler.ui.COPY_PATH_LABEL
import org.example.project.scheduler.ui.RETRACT_ALL_LABEL
import org.example.project.scheduler.ui.TaskCellMenuActions
import org.example.project.scheduler.ui.taskCellMenuActions

/**
 * User rule 2026-11-25: *"Unify the right-click menu of all the rows of the app. ctrl+c must work on any row (to copy its
 * id). Put 'go to calendar' in the action section. Remove 'go to task tree' and 'add default sub-tree'. Add … deep copy,
 * copy the path (list of ancestors) and retract the children and every sub child."*
 */
class RowMenuTest {
    private fun r(state: SchedulerState, intent: SchedulerIntent) = SchedulerReducer.reduce(state, intent)

    private fun freeRootCell(state: SchedulerState): CellId = state.lists[state.rootListId]!!.cellIds.last()

    private fun freeChildCell(state: SchedulerState, taskId: TaskId): CellId =
        state.lists[state.tasks[taskId]!!.childListId!!]!!.cellIds.last()

    private fun task(state: SchedulerState, title: String): TaskId = state.tasks.values.first { it.title == title }.id

    private fun cell(state: SchedulerState, title: String): CellId = SchedulerDomain.firstTaskOccurrence(state, task(state, title))!!.cellId

    /** Apple / Pie / Crust, and Banana at the top level. */
    private fun tree(): SchedulerState {
        var s = SchedulerState.empty()
        s = r(s, SchedulerIntent.SetCellTitle(freeRootCell(s), "Apple"))
        s = r(s, SchedulerIntent.SetCellTitle(freeChildCell(s, task(s, "Apple")), "Pie"))
        s = r(s, SchedulerIntent.SetCellTitle(freeChildCell(s, task(s, "Pie")), "Crust"))
        s = r(s, SchedulerIntent.SetCellTitle(freeRootCell(s), "Banana"))
        return s
    }

    @Test
    fun every_row_offers_the_same_entries_and_none_of_the_removed_ones() {
        val bare = TaskCellMenuActions(null, null, null, null, null, null, null)
        val full = TaskCellMenuActions({}, {}, {}, {}, {}, {}, {})
        val labels = taskCellMenuActions(bare).map { it.label }
        // A row with nothing to do still shows them, greyed: the menu reads the same on every row.
        assertEquals(listOf(COPY_ID_LABEL, COPY_PATH_LABEL, "deep copy", "collapse sub-trees", RETRACT_ALL_LABEL), labels)
        assertTrue(taskCellMenuActions(bare).none { it.enabled })
        val all = taskCellMenuActions(full).map { it.label }
        for (gone in listOf("go to task tree", "go to calendar", "add default sub-tree")) assertFalse(gone in all, gone)
        assertTrue(taskCellMenuActions(full).all { it.enabled })
    }

    @Test
    fun retract_closes_the_row_and_every_sub_child_collapse_keeps_the_row_open() {
        var s = tree()
        val apple = cell(s, "Apple")
        val pie = cell(s, "Pie")
        s = s.copy(expanded = s.expanded + apple + pie)
        val collapsed = r(s, SchedulerIntent.CollapseSubtrees(apple))
        assertTrue(apple in collapsed.expanded)
        assertFalse(pie in collapsed.expanded)
        val retracted = r(s, SchedulerIntent.CollapseSubtrees(apple, includingSelf = true))
        assertFalse(apple in retracted.expanded)
        assertFalse(pie in retracted.expanded)
        // One undoable unit, back to both open.
        val undone = r(retracted, SchedulerIntent.Undo)
        assertTrue(apple in undone.expanded && pie in undone.expanded)
        assertEquals(setOf(pie, cell(s, "Crust")), SchedulerDomain.cellsUnder(s, s.tasks[task(s, "Apple")]!!.childListId!!).filter { s.cells[it]?.taskId != null && s.tasks[s.cells[it]!!.taskId!!]!!.title.isNotEmpty() }.toSet())
    }

    @Test
    fun copy_path_is_the_ancestors_titles_root_first_from_any_drawing() {
        val s = tree()
        val apple = cell(s, "Apple")
        val pie = cell(s, "Pie")
        val crust = cell(s, "Crust")
        val root = SchedulerDomain.ancestorsOfCell(s, apple)!!
        // The tree's drawing: every row above, the root's first.
        val label = SearchDomain.cellPathLabel(s, root + apple + pie)
        assertEquals(SearchDomain.pathLabel(listOf(SchedulerDomain.ROOT_TASK_TITLE, "Apple", "Pie")), label)
        // A Search sub-tree's drawing starts lower: the live tree's cells above it come first, the same path.
        assertEquals(label, SearchDomain.cellPathLabel(s, listOf(apple, pie)))
        assertEquals(SearchDomain.pathLabel(listOf(SchedulerDomain.ROOT_TASK_TITLE, "Apple")), SearchDomain.cellPathLabel(s, listOf(apple)))
        assertEquals(root + apple + pie, SchedulerDomain.ancestorsOfCell(s, crust))
        // A top-level task's path is the tree's label alone, as its Search row shows it.
        assertEquals(SchedulerDomain.ROOT_TASK_TITLE, SearchDomain.cellPathLabel(s, emptyList()))
        assertEquals(listOf(SchedulerDomain.ROOT_TASK_TITLE), SearchDomain.allPathsInAnyTree(s)[task(s, "Apple")]!!.first())
    }

    @Test
    fun copy_path_includes_the_row_clicked_each_segment_its_title_or_its_id() {
        var s = tree()
        val pie = cell(s, "Pie")
        val crust = cell(s, "Crust")
        val ancestors = SchedulerDomain.ancestorsOfCell(s, crust)!!
        assertEquals(
            SearchDomain.pathLabel(listOf(SchedulerDomain.ROOT_TASK_TITLE, "Apple", "Pie", "Crust")),
            SearchDomain.cellPathLabel(s, ancestors + crust),
        )
        // A task with no title: its id stands in its place.
        val pieTask = task(s, "Pie")
        s = s.copy(tasks = s.tasks + (pieTask to s.tasks[pieTask]!!.copy(title = "")))
        assertEquals(
            SearchDomain.pathLabel(listOf(SchedulerDomain.ROOT_TASK_TITLE, "Apple", pieTask.value)),
            SearchDomain.cellPathLabel(s, SchedulerDomain.ancestorsOfCell(s, pie)!! + pie),
        )
        assertEquals("Title", SearchDomain.pathSegment("Title", "id"))
        assertEquals("id", SearchDomain.pathSegment(" ", "id"))
        assertEquals("id", SearchDomain.pathSegment(null, "id"))
    }

    @Test
    fun the_separators_are_a_search_configuration_on_in_a_menus_list_and_absent_from_an_older_payload() {
        assertTrue(SearchDomain.OPTION_SEARCH.separators, "a menu's list shows them")
        assertFalse(SearchDomain.Config().separators, "a plain Search window has none")
        assertEquals(SearchDomain.SettingRole.Display, SearchDomain.roleOf(SearchDomain.Setting.Separators))
        assertTrue(SearchDomain.Setting.Separators in SearchDomain.activeSettings(SearchDomain.OPTION_SEARCH))
        assertTrue("separator" !in SearchDomain.settingDescription(SearchDomain.Setting.Separators).lowercase() ||
            SearchDomain.settingDescription(SearchDomain.Setting.Separators) != SearchDomain.Setting.Separators.label)
        // Kept and read back.
        val on = SearchDomain.Config(separators = true)
        assertEquals(on, SearchDomain.Config.decode(on.encode()))
        // A payload written before 2026-11-25 has no such field: it decodes, with none.
        val older = SearchDomain.Config().encode().replace(Regex(",?\"separators\":(true|false)"), "")
        assertFalse(older.contains("separators"))
        assertFalse(SearchDomain.Config.decode(older)!!.separators)
    }

    @Test
    fun copy_id_is_a_tasks_reference_and_any_other_elements_key() {
        assertEquals(SchedulerDomain.TASK_ID_REFERENCE_PREFIX + "abc", SearchDomain.copiedId("Task/abc"))
        assertEquals("Alarm/7", SearchDomain.copiedId("Alarm/7"))
        assertEquals("Option/beta", SearchDomain.copiedId(SearchDomain.optionKey("beta")))
    }
}
