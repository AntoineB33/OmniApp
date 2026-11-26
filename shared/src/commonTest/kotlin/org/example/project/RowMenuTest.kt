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
import org.example.project.scheduler.ui.COLLAPSE_LABEL
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
        val bare = TaskCellMenuActions(null, null, null, null, null, null)
        val full = TaskCellMenuActions({}, {}, {}, {}, {}, {})
        val labels = taskCellMenuActions(bare).map { it.label }
        // A row with nothing to do still shows them, greyed: the menu reads the same on every row.
        // User 2026-11-26: "collapse sub-trees" is "collapse", "retract with every sub-child" is gone.
        assertEquals(listOf(COPY_ID_LABEL, COPY_PATH_LABEL, "deep copy", COLLAPSE_LABEL), labels)
        assertTrue(taskCellMenuActions(bare).none { it.enabled })
        val all = taskCellMenuActions(full).map { it.label }
        for (gone in listOf("go to task tree", "go to calendar", "add default sub-tree")) assertFalse(gone in all, gone)
        assertTrue(taskCellMenuActions(full).all { it.enabled })
    }

    @Test
    fun collapse_keeps_the_row_open_and_its_level_keeps_the_deeper_rows() {
        var s = tree()
        val apple = cell(s, "Apple")
        val pie = cell(s, "Pie")
        s = s.copy(expanded = s.expanded + apple + pie)
        val collapsed = r(s, SchedulerIntent.CollapseSubtrees(apple))
        assertTrue(apple in collapsed.expanded)
        assertFalse(pie in collapsed.expanded)
        // From level 2 the rows stay as they are: Pie (level 1) closes, Crust (level 2) keeps its state.
        val crust = cell(s, "Crust")
        val deep = s.copy(expanded = s.expanded + crust)
        val kept = r(deep, SchedulerIntent.CollapseSubtrees(apple, keepFromLevel = 2))
        assertTrue(apple in kept.expanded && crust in kept.expanded)
        assertFalse(pie in kept.expanded)
        // From level 1: nothing under it closes.
        assertEquals(deep.expanded, r(deep, SchedulerIntent.CollapseSubtrees(apple, keepFromLevel = 1)).expanded)
        // One undoable unit, back to both open.
        val undone = r(collapsed, SchedulerIntent.Undo)
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

    /**
     * User 2026-11-26 (*"deep copy … Why would it be only for tasks?"*): an element that is no task copies with its children
     * ([org.example.project.scheduler.domain.ElementTree.childrenOf]) down to the depth asked, its ids where asked.
     */
    @Test
    fun an_element_that_is_no_task_deep_copies_with_its_children() {
        fun key(id: String) = "Chrono/$id"
        val s = SchedulerState.empty().copy(
            chronos = listOf("p", "a", "b").map { org.example.project.scheduler.model.ChronoEntry(id = it, label = it.uppercase()) },
            elementLinks = listOf("p" to "a", "a" to "b").map { (parent, child) ->
                org.example.project.scheduler.domain.ElementLink(
                    org.example.project.scheduler.domain.ElementTree.linkId(key(parent), key(child)), key(parent), key(child),
                )
            },
        )
        val tab = 9.toChar()
        val nl = 10.toChar()
        assertEquals("P${nl}${tab}A${nl}", SearchDomain.elementsCopyText(s, listOf(key("p")), 2, includeIds = false, excludeTitle = ""))
        assertEquals("P${nl}${tab}A${nl}${tab}${tab}B${nl}", SearchDomain.elementsCopyText(s, listOf(key("p")), 9, includeIds = false, excludeTitle = ""))
        assertEquals("P${nl}${tab}- id: Chrono/p${nl}", SearchDomain.elementsCopyText(s, listOf(key("p")), 1, includeIds = true, excludeTitle = ""))
        assertEquals("P${nl}", SearchDomain.elementsCopyText(s, listOf(key("p")), 9, includeIds = false, excludeTitle = "A"))
        assertEquals(listOf("P", "A", "B"), SearchDomain.elementsDeepestPath(s, listOf(key("p")), 9))
        // The deep copy rows are offered for it, those only a task carries not.
        val rows = SearchDomain.resolve(s, listOf(key("p")), { emptyMap() })
        val actions = SearchDomain.actionsFor(SearchDomain.addedActions("", SearchDomain.Kind.entries.toSet(), SearchDomain.actionKindsOf(rows)), rows)
            .flatMap { it.second }
        assertTrue(SearchDomain.AddedAction.TaskDeepCopy in actions && SearchDomain.AddedAction.DeepCopyMaxDepth in actions)
        assertFalse(SearchDomain.AddedAction.DeepCopyMinimumTime in actions)
        assertTrue(SearchDomain.deepCopyGroupId(rows) != null, "its \"Deep copy\" group, which the menu pins")
    }

    /** User 2026-11-26: "collapse" closes the rows under the row above the level asked; the row itself stays open. */
    @Test
    fun collapse_closes_the_levels_above_the_one_asked() {
        assertTrue(SearchDomain.collapses(1, null) && SearchDomain.collapses(5, null))
        assertFalse(SearchDomain.collapses(0, null), "the row itself")
        assertTrue(SearchDomain.collapses(1, 2))
        assertFalse(SearchDomain.collapses(2, 2))
        assertFalse(SearchDomain.collapses(1, 1))
    }

    /** User 2026-11-26: a saved "Entries, in order" naming the old entries keeps "collapse" and drops "retract". */
    @Test
    fun a_saved_entries_order_is_healed_on_load() {
        val old = SearchDomain.Config(entries = listOf("copy path", "collapse sub-trees", "retract with every sub-child", "deep copy"))
        assertEquals(listOf("copy path", "collapse", "deep copy"), SearchDomain.Config.decode(old.encode())!!.entries)
    }
}
