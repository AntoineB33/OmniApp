package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.ContextDomain
import org.example.project.scheduler.domain.SearchDomain
import org.example.project.scheduler.domain.SearchDomain.AddedAction
import org.example.project.scheduler.domain.SearchDomain.Kind
import org.example.project.scheduler.domain.TaskTreesAsTasks
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.state.CellEditMode
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState

/**
 * User 2026-11-28: *"proper tree architectures … a new user … They don't know in advance where to look, and they don't
 * necessarily read fast … Start with adjusting the task tree"*. **What a new user reaches from a task**: a task's actions
 * (its "edit task" window) under a few plain categories ([SearchDomain.TASK_ACTION_CATEGORIES]), and the tree row's own
 * edits named in its right-click menu. `docs/new-user-walks.md` § *The task tree* tells each walk's thinking.
 */
class TaskActionsTreeTest {
    private fun task(id: String = "t1", title: String = "dentist") =
        SearchDomain.TaskResult(TaskId(id), title, listOf(listOf("root", "health", title)), true, setOf(TaskId("h")))

    private fun groups(added: List<SearchDomain.Result>) =
        SearchDomain.actionGroups(SearchDomain.actionsFor(SearchDomain.addedActions("", Kind.entries.toSet(), setOf(Kind.Task)), added), added)

    /** The titles from the task's own group down to [action], the group's title left out: the categories hovered. */
    private fun pathTo(group: SearchDomain.ActionGroup, action: AddedAction): List<String>? {
        if (action in group.actions) return emptyList()
        for (inner in group.inner) pathTo(inner, action)?.let { return listOf(inner.title) + it }
        return null
    }

    /** One request: the user's words, the categories opened under the task, the action they use. */
    private data class Walk(val request: String, val path: List<String>, val action: AddedAction)

    private val walks = listOf(
        Walk("make it last at least 45 minutes", listOf("How long it takes"), AddedAction.TaskMinimumTime),
        Walk("split it into a 10-minute warm-up and the rest", listOf("How long it takes"), AddedAction.TaskScheduleUnit),
        Walk("do it right now", listOf("Calendar"), AddedAction.TaskStartNow),
        Walk("put it on Thursday at 6 pm", listOf("Calendar"), AddedAction.PlaceOnCalendar),
        Walk("allow it during my sleep period", listOf("Calendar"), AddedAction.TaskResilience),
        Walk("see where it is on the calendar", listOf("Open & go to"), AddedAction.TaskGoToCalendar),
        Walk("find it in the tree", listOf("Open & go to"), AddedAction.TaskGoToTree),
        Walk("make it count more than its siblings", listOf("Priority"), AddedAction.TaskPriorityWeights),
        Walk("how important is it overall", listOf("Priority"), AddedAction.TaskRelativePriority),
        Walk("doing it also counts for Health", listOf("Priority"), AddedAction.TaskFulfilment),
        Walk("put it under Work too", listOf("Sub-tasks & place in the tree"), AddedAction.TaskAddUnder),
        Walk("see its sub-tasks", listOf("Sub-tasks & place in the tree"), AddedAction.TaskChildren),
        Walk("every place it stands in the tree", listOf("Sub-tasks & place in the tree"), AddedAction.TaskPaths),
        Walk("give it my usual sub-steps", listOf("Sub-tasks & place in the tree"), AddedAction.TaskAddDefaultSubtree),
        Walk("label it urgent", listOf("Categories"), AddedAction.TaskAddCategory),
        Walk("make a copy of it", listOf("Copy & create"), AddedAction.TaskDuplicate),
        Walk("copy it with its sub-tree as text", listOf("Copy & create", "Deep copy"), AddedAction.TaskDeepCopy),
        Walk("change its blocks already on the calendar", listOf("Calendar", "Checked blocks"), AddedAction.CalendarBlocks),
        Walk("write a note about it", emptyList(), AddedAction.ElementMd),
    )

    @Test
    fun every_walk_leads_to_its_action() {
        val group = groups(listOf(task())).single()
        for (walk in walks) assertEquals(walk.path, pathTo(group, walk.action), walk.request)
    }

    @Test
    fun a_task_reads_at_a_glance_every_node_at_most_fifteen_rows() {
        val group = groups(listOf(task())).single()
        fun check(g: SearchDomain.ActionGroup) {
            assertTrue(g.actions.size + g.inner.size <= 15, "${g.title}: ${g.actions.size + g.inner.size} rows")
            g.inner.forEach(::check)
        }
        check(group)
        // The top reads a handful of names, where it read 32 rows.
        assertTrue(group.actions.size + group.inner.size <= 10, "top: ${group.actions.size + group.inner.size}")
        assertEquals(AddedAction.OpenEach, group.actions.first(), "open or remove stays first")
    }

    @Test
    fun no_action_of_a_task_is_lost_by_the_categories() {
        val added = listOf<SearchDomain.Result>(task())
        val listed = SearchDomain.actionsFor(SearchDomain.addedActions("", Kind.entries.toSet(), setOf(Kind.Task)), added)
            .flatMap { it.second }.toSet()
        fun all(g: SearchDomain.ActionGroup): Set<AddedAction> = g.actions.toSet() + g.inner.flatMap(::all)
        val shown = groups(added).flatMap(::all).toSet()
        assertEquals(listed - shown, emptySet(), "listed but reached by no category")
    }

    @Test
    fun several_tasks_keep_the_categories_and_a_lone_category_row_stays_flat() {
        val two = groups(listOf(task("a", "gym"), task("b", "run")))
        val kindGroup = two.first { it.kind == Kind.Task && !it.element }
        assertTrue(kindGroup.inner.any { it.title == "How long it takes" }, "the fields over both tasks are grouped too")
        // An alarm has its own categories and the shared ones, never a task's.
        assertTrue(groupOf(Kind.Alarm).inner.none { it.tag?.startsWith("task-") == true })
    }

    @Test
    fun the_search_bar_still_finds_an_action_inside_a_category() {
        val added = listOf<SearchDomain.Result>(task())
        val config = SearchDomain.Config(actionSearch = "minimum time", actionSearchSortsOnly = false)
        val shown = SearchDomain.shownActionGroups(
            SearchDomain.addedActions("", Kind.entries.toSet(), setOf(Kind.Task)), added, config,
        )
        val rows = SearchDomain.actionListRows(shown, config, expandAll = true).map { it.title }
        assertTrue("Minimum time" in rows, rows.toString())
    }

    private fun groupOf(kind: Kind): SearchDomain.ActionGroup {
        val row = SearchDomain.ItemResult(kind, "x1", "One " + kind.label, "")
        return SearchDomain.actionGroups(
            SearchDomain.actionsFor(SearchDomain.addedActions("", Kind.entries.toSet(), setOf(kind)), listOf(row)), listOf(row),
        ).single()
    }

    /** One request about an element of another type: its type, the categories opened, the action used. */
    private data class KindWalk(val request: String, val kind: Kind, val path: List<String>, val action: AddedAction)

    private val kindWalks = listOf(
        KindWalk("my wake-up alarm on weekdays only", Kind.Alarm, listOf("When it rings"), AddedAction.AlarmDays),
        KindWalk("the alarm at 6:45", Kind.Alarm, listOf("When it rings"), AddedAction.AlarmTime),
        KindWalk("the alarm rings softer, no voice", Kind.Alarm, listOf("How it rings"), AddedAction.AlarmAlert),
        KindWalk("turn the alarm off", Kind.Alarm, emptyList(), AddedAction.AlarmOnOff),
        KindWalk("delete this alarm", Kind.Alarm, listOf("Copy, create & delete"), AddedAction.AlarmDelete),
        KindWalk("start the timer", Kind.Timer, emptyList(), AddedAction.TimerRun),
        KindWalk("the timer keeps counting past zero", Kind.Timer, listOf("How it rings"), AddedAction.TimerBelowZero),
        KindWalk("water the plants every 3 days", Kind.Reminder, listOf("When it reminds"), AddedAction.ReminderEvery),
        KindWalk("the quota restarts every week", Kind.Quota, listOf("Its loop"), AddedAction.QuotaLoop),
        KindWalk("how far the quota is", Kind.Quota, emptyList(), AddedAction.QuotaProgress),
        KindWalk("warn me 10 minutes before Sleep", Kind.RestrictivePeriod, listOf("Notifications"), AddedAction.PeriodNotices),
        KindWalk("put the period on the calendar", Kind.RestrictivePeriod, listOf("Calendar"), AddedAction.PlaceOnCalendar),
        KindWalk("rename the category", Kind.Category, emptyList(), AddedAction.CategoryName),
        KindWalk("file the chrono under a category", Kind.Chrono, listOf("Categories, parents & notes"), AddedAction.ElementAddCategory),
        KindWalk("a note on the shortcut", Kind.Shortcut, listOf("Categories, parents & notes"), AddedAction.ElementMd),
    )

    @Test
    fun every_walk_over_another_type_leads_to_its_action() {
        for (walk in kindWalks) assertEquals(walk.path, pathTo(groupOf(walk.kind), walk.action), walk.request)
        // "Every new alarm rings 30 s": the default, a row of its own after the categories.
        val defaults = groupOf(Kind.Alarm).inner.single { it.title == "Default configuration of a new alarm" }
        assertTrue(AddedAction.AlarmRingsFor in defaults.actions)
    }

    @Test
    fun every_type_reads_at_a_glance_and_loses_no_action() {
        for (kind in Kind.entries.filter { it != Kind.Task }) {
            val group = groupOf(kind)
            fun check(g: SearchDomain.ActionGroup) {
                assertTrue(g.actions.size + g.inner.size <= 15, "$kind › ${g.title}: ${g.actions.size + g.inner.size} rows")
                g.inner.forEach(::check)
            }
            check(group)
            assertTrue(group.actions.size + group.inner.size <= 11, "$kind top: ${group.actions.size + group.inner.size}")
            val row = group.members.single()
            val listed = SearchDomain.actionsFor(SearchDomain.addedActions("", Kind.entries.toSet(), setOf(kind)), listOf(row))
                .flatMap { it.second }.toSet()
            fun all(g: SearchDomain.ActionGroup): Set<AddedAction> = g.actions.toSet() + g.inner.flatMap(::all)
            assertEquals(emptySet(), listed - all(group), "$kind: listed but reached by no category")
        }
    }

    private fun accountWithTask(): Pair<SchedulerState, org.example.project.scheduler.model.CellId> {
        var account = TaskTreesAsTasks.seeded(SchedulerState.empty(), "tree-2026-11-28")
        val tree = account.lists[account.rootListId]!!.cellIds.first { account.cells[it]?.taskId != null }
        val treeTask = account.cells[tree]!!.taskId!!
        val kidCell = account.lists[account.tasks[treeTask]!!.childListId!!]!!.cellIds[0]
        account = SchedulerReducer.reduce(account, SchedulerIntent.SetCellTitle(kidCell, "Dentist"))
        return account to kidCell
    }

    @Test
    fun add_a_sub_task_opens_the_row_and_types_into_its_empty_child_row() {
        val (account, cell) = accountWithTask()
        val after = SchedulerReducer.reduce(account, SchedulerIntent.AddSubTask(cell))
        assertTrue(cell in after.expanded, "the row is opened")
        val session = assertNotNull(after.editSession, "a row is in Edit Mode")
        val childList = after.tasks[after.cells[cell]!!.taskId!!]!!.childListId!!
        assertEquals(after.lists[childList]!!.cellIds.last(), session.cellId, "the trailing empty row of its sub-list")
        // Typed and left: the task has its sub-task.
        val typed = SchedulerReducer.reduce(SchedulerReducer.reduce(after, SchedulerIntent.UpdateEditText("Book it")),
            SchedulerIntent.ExitEdit(org.example.project.scheduler.state.EditExitNavigation.Stay))
        val titles = typed.lists[childList]!!.cellIds.mapNotNull { typed.cells[it]?.taskId?.let { t -> typed.tasks[t]?.title } }
        assertTrue("Book it" in titles, titles.toString())
    }

    @Test
    fun rename_from_the_menu_renames_the_task() {
        val (account, cell) = accountWithTask()
        val editing = SchedulerReducer.reduce(account, SchedulerIntent.BeginEdit(cell, mode = CellEditMode.Rename))
        assertEquals(CellEditMode.Rename, editing.editSession?.mode)
    }

    @Test
    fun the_tree_row_menu_names_its_own_edits_by_default() {
        for (label in listOf("rename (enter)", "add a sub-task", "delete (del)")) {
            assertTrue(label in ContextDomain.ROW_MENU_ENTRIES, label)
        }
        assertTrue(
            ContextDomain.ROW_MENU_ENTRIES.indexOf("delete (del)") < ContextDomain.ROW_MENU_ENTRIES.indexOf("edit task"),
            "the row's own edits come before the windows it opens",
        )
    }
}
