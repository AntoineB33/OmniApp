package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.datetime.DayOfWeek
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.domain.SearchDomain
import org.example.project.scheduler.model.AlarmEntry
import org.example.project.scheduler.model.ChoreEntry
import org.example.project.scheduler.model.CellId
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.model.TaskTimeRange
import org.example.project.scheduler.model.TaskTreeId
import org.example.project.scheduler.model.TimerEntry
import org.example.project.scheduler.persistence.SchedulerStateCodec
import org.example.project.scheduler.state.CellEditMode
import org.example.project.scheduler.state.EditExitNavigation
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState
import org.example.project.scheduler.state.TaskTreeEntry

/**
 * PRD §7 **Search**: the window's results, and the one fact about a task it needed the app to start keeping —
 * **the last path of a task no task tree holds any more** ([org.example.project.scheduler.model.Task.lastTreePath]).
 *
 * The stamp rules pinned here are the user's own (2026-09-23): a task that ceases to be in a task tree keeps
 * its last path; cut by deleting several of its occurrences at once, it keeps the **shortest**; and once
 * nothing references it, it is forgotten outright — which is the ordinary purge taking the task, and the path
 * with it.
 */
class SearchWindowTest {

    private fun r(state: SchedulerState, intent: SchedulerIntent) = SchedulerReducer.reduce(state, intent)

    private fun freeRootCell(state: SchedulerState): CellId = state.lists[state.rootListId]!!.cellIds.last()

    private fun freeChildCell(state: SchedulerState, cellId: CellId): CellId {
        val taskId = state.cells[cellId]!!.taskId!!
        return state.lists[state.tasks[taskId]!!.childListId!!]!!.cellIds.last()
    }

    private fun cellsWithTitle(state: SchedulerState, title: String): List<CellId> =
        state.cells.values.filter { cell -> cell.taskId?.let { state.tasks[it]?.title } == title }.map { it.id }

    private fun cellWithTitle(state: SchedulerState, title: String): CellId = cellsWithTitle(state, title).first()

    private fun taskWithTitle(state: SchedulerState, title: String): TaskId =
        state.tasks.values.first { it.title == title }.id

    /** A record on [taskId]: what keeps a task alive once no cell holds it (PRD §4 *Cleanup*). */
    private fun withRecord(state: SchedulerState, taskId: TaskId): SchedulerState {
        val task = state.tasks.getValue(taskId)
        return state.copy(tasks = state.tasks + (taskId to task.copy(record = listOf(TaskTimeRange(1_000L, 61_000L)))))
    }

    /** PRD §4's Delete: select [cells] and empty them — the gesture, edit boundary included. */
    private fun deleteCells(state: SchedulerState, cells: List<CellId>): SchedulerState {
        var s = state
        cells.forEachIndexed { i, cell ->
            s = r(s, SchedulerIntent.ClickCell(cell, ctrl = i > 0, shift = false, visibleOrder = emptyList()))
        }
        return r(s, SchedulerIntent.EmptySelectedCells)
    }

    /**
     * ```
     * Apple
     *   Pie        <- has a record
     * Banana
     *   Crumble
     * ```
     */
    private fun tree(): SchedulerState {
        var s = SchedulerState.empty()
        val apple = freeRootCell(s)
        s = r(s, SchedulerIntent.SetCellTitle(apple, "Apple"))
        s = r(s, SchedulerIntent.SetCellTitle(freeChildCell(s, apple), "Pie"))
        val banana = freeRootCell(s)
        s = r(s, SchedulerIntent.SetCellTitle(banana, "Banana"))
        s = r(s, SchedulerIntent.SetCellTitle(freeChildCell(s, banana), "Crumble"))
        return withRecord(s, taskWithTitle(s, "Pie"))
    }

    /** [tree], with Pie mirrored one level deeper: under Banana / Crumble as well as under Apple. */
    private fun mirroredTree(): SchedulerState {
        var s = tree()
        s = r(s, SchedulerIntent.AssignTaskId(freeChildCell(s, cellWithTitle(s, "Crumble")), taskWithTitle(s, "Pie")))
        assertEquals(2, cellsWithTitle(s, "Pie").size)
        return s
    }

    private fun root(s: SchedulerState): String = s.tasks.getValue(org.example.project.scheduler.model.WellKnownIds.ROOT_TASK).title

    // ----- The last path ----------------------------------------------------------------------------

    @Test
    fun a_task_cut_from_the_tree_keeps_the_path_it_had() {
        var s = tree()
        val pie = taskWithTitle(s, "Pie")
        assertEquals(emptyList(), s.tasks.getValue(pie).lastTreePath, "a task the tree holds carries no stamp")

        s = r(s, SchedulerIntent.SetCellTitle(cellWithTitle(s, "Pie"), ""))

        val task = assertNotNull(s.tasks[pie], "its record keeps it alive")
        assertEquals(listOf(root(s), "Apple"), task.lastTreePath)
        val row = SearchDomain.taskResults(s, "pie").single()
        assertFalse(row.inTaskTree)
        assertEquals(listOf(root(s), "Apple"), row.shownPath)
        assertFalse(row.hasSeveralPaths)
    }

    @Test
    fun several_occurrences_cut_at_once_keep_the_shortest_path() {
        var s = mirroredTree()
        val pie = taskWithTitle(s, "Pie")
        val (first, second) = cellsWithTitle(s, "Pie")
        s = deleteCells(s, listOf(first, second))

        assertTrue(cellsWithTitle(s, "Pie").isEmpty(), "both occurrences went in the one gesture")
        // root / Apple beats root / Banana / Crumble.
        assertEquals(listOf(root(s), "Apple"), s.tasks.getValue(pie).lastTreePath)
    }

    @Test
    fun cut_one_occurrence_at_a_time_it_keeps_the_last_one_it_had() {
        var s = mirroredTree()
        val pie = taskWithTitle(s, "Pie")
        // The shorter one first: the task is still in the tree, so nothing is stamped yet...
        val underApple = cellsWithTitle(s, "Pie").first { s.cells[it]!!.parentListId == s.tasks[taskWithTitle(s, "Apple")]!!.childListId }
        s = r(s, SchedulerIntent.SetCellTitle(underApple, ""))
        assertEquals(emptyList(), s.tasks.getValue(pie).lastTreePath)
        // ...and the path it keeps is the one it had when it left.
        s = r(s, SchedulerIntent.SetCellTitle(cellWithTitle(s, "Pie"), ""))
        assertEquals(listOf(root(s), "Banana", "Crumble"), s.tasks.getValue(pie).lastTreePath)
    }

    /**
     * A **detached parent** (its cell re-pointed at a new task) leaves the tree with its whole sub-tree. Only the
     * parent is stamped: the tasks under it read their path off it, since the parent's sub-list still holds
     * them. Stamping them all rewrote — and pushed — every task under a renamed parent (`ServerQuotaTest`).
     */
    @Test
    fun a_detached_parent_is_stamped_and_the_tasks_under_it_derive_their_path_from_it() {
        var s = tree()
        val appleCell = cellWithTitle(s, "Apple")
        val apple = taskWithTitle(s, "Apple")
        val pie = taskWithTitle(s, "Pie")
        s = r(s, SchedulerIntent.BeginEdit(appleCell))
        s = r(s, SchedulerIntent.SetEditMode(CellEditMode.ChangeTask))
        s = r(s, SchedulerIntent.SelectCreateAssignTask)
        s = r(s, SchedulerIntent.ExitEdit(EditExitNavigation.Stay))
        assertTrue(s.cells[appleCell]!!.taskId != apple, "the cell now names a new task")

        assertEquals(listOf(root(s)), s.tasks.getValue(apple).lastTreePath)
        assertEquals(emptyList(), s.tasks.getValue(pie).lastTreePath, "derivable, so not stored")
        val row = SearchDomain.taskResults(s, "Pie").single()
        assertFalse(row.inTaskTree)
        assertEquals(listOf(root(s), "Apple"), row.shownPath)
    }

    @Test
    fun a_task_back_in_the_tree_loses_its_stamp() {
        var s = tree()
        val pie = taskWithTitle(s, "Pie")
        s = r(s, SchedulerIntent.SetCellTitle(cellWithTitle(s, "Pie"), ""))
        assertTrue(s.tasks.getValue(pie).lastTreePath.isNotEmpty())

        s = r(s, SchedulerIntent.AssignTaskId(freeChildCell(s, cellWithTitle(s, "Banana")), pie))

        assertEquals(emptyList(), s.tasks.getValue(pie).lastTreePath)
        assertTrue(SearchDomain.taskResults(s, "Pie").single().inTaskTree)
    }

    /**
     * The user's own example: the only reference left is a record on the timeline, and the history cap takes
     * it. Nothing then holds the task, so the ordinary purge removes it — and the path goes with it, there
     * being no second place it was kept.
     */
    @Test
    fun a_task_nothing_references_any_more_is_forgotten_path_and_all() {
        var s = tree()
        val pie = taskWithTitle(s, "Pie")
        s = r(s, SchedulerIntent.SetCellTitle(cellWithTitle(s, "Pie"), ""))
        assertTrue(s.tasks.getValue(pie).lastTreePath.isNotEmpty())

        val capped = s.copy(tasks = s.tasks + (pie to s.tasks.getValue(pie).copy(record = emptyList())))
        s = SchedulerDomain.purgeOrphanTasks(capped)

        assertNull(s.tasks[pie])
        assertTrue(SearchDomain.taskResults(s, "Pie").isEmpty())
    }

    /**
     * Renaming a parent passes through a blank title between keystrokes, and a blank title holds nothing: read
     * mid-session, its whole sub-tree would leave the tree and come back — every task in it rewritten (and
     * pushed) twice. Nothing is stamped while a session is open, and its end is measured from where it began.
     */
    @Test
    fun a_parent_blank_for_a_keystroke_stamps_nothing_under_it() {
        var s = tree()
        val pie = taskWithTitle(s, "Pie")
        val apple = cellWithTitle(s, "Apple")
        s = r(s, SchedulerIntent.BeginEdit(apple, mode = CellEditMode.Rename))
        s = r(s, SchedulerIntent.SetCellTitle(apple, ""))
        assertNotNull(s.editSession)
        assertEquals(emptyList(), s.tasks.getValue(pie).lastTreePath, "mid-session: not stamped")
        s = r(s, SchedulerIntent.SetCellTitle(apple, "Apples"))
        s = r(s, SchedulerIntent.ExitEdit(EditExitNavigation.Stay))

        assertNull(s.editSession)
        assertEquals(emptyList(), s.tasks.getValue(pie).lastTreePath, "it never left")
    }

    @Test
    fun a_task_emptied_inside_a_session_is_stamped_when_the_session_ends() {
        var s = tree()
        val pie = taskWithTitle(s, "Pie")
        val cell = cellWithTitle(s, "Pie")
        s = r(s, SchedulerIntent.BeginEdit(cell))
        s = r(s, SchedulerIntent.SetCellTitle(cell, ""))
        s = r(s, SchedulerIntent.ExitEdit(EditExitNavigation.Stay))

        assertEquals(listOf(root(s), "Apple"), s.tasks[pie]?.lastTreePath)
    }

    // ----- Several task trees -----------------------------------------------------------------------

    /** [tree], with a stored (inactive) copy of itself named "Studies". */
    private fun withStoredCopy(s: SchedulerState): SchedulerState =
        s.copy(taskTrees = listOf(TaskTreeEntry(TaskTreeId("tree/9"), "Studies", s.captureTreeWithRecords())))

    @Test
    fun a_task_another_task_tree_still_holds_is_in_a_task_tree() {
        var s = withStoredCopy(tree())
        val pie = taskWithTitle(s, "Pie")
        s = r(s, SchedulerIntent.SetCellTitle(cellWithTitle(s, "Pie"), ""))

        assertEquals(emptyList(), s.tasks.getValue(pie).lastTreePath, "Studies still holds it")
        val row = SearchDomain.taskResults(s, "Pie").single()
        assertTrue(row.inTaskTree)
        // The root segment names the tree the path runs through.
        assertEquals(listOf("Studies", "Apple"), row.shownPath)
    }

    @Test
    fun deleting_the_last_task_tree_that_held_it_stamps_that_trees_path() {
        var s = withStoredCopy(tree())
        val pie = taskWithTitle(s, "Pie")
        s = r(s, SchedulerIntent.SetCellTitle(cellWithTitle(s, "Pie"), ""))
        s = r(s, SchedulerIntent.DeleteTaskTree(TaskTreeId("tree/9")))

        assertTrue(s.taskTrees.none { it.id == TaskTreeId("tree/9") })
        assertEquals(listOf("Studies", "Apple"), s.tasks[pie]?.lastTreePath)
    }

    @Test
    fun a_task_only_a_stored_tree_holds_is_a_result() {
        var s = withStoredCopy(tree())
        // The live tree loses Banana and Crumble entirely (no record, so the tasks are purged from it)...
        s = deleteCells(s, listOf(cellWithTitle(s, "Banana")))
        assertTrue(s.tasks.values.none { it.title == "Crumble" })

        // ...and the search still finds it, where Studies holds it.
        val row = SearchDomain.taskResults(s, "crumble").single()
        assertTrue(row.inTaskTree)
        assertEquals(listOf("Studies", "Banana"), row.shownPath)
    }

    // ----- Paths ------------------------------------------------------------------------------------

    @Test
    fun a_mirrored_task_lists_every_path_shortest_first() {
        val s = mirroredTree()
        val row = SearchDomain.taskResults(s, "Pie").single()
        assertTrue(row.hasSeveralPaths)
        assertEquals(listOf(listOf(root(s), "Apple"), listOf(root(s), "Banana", "Crumble")), row.paths)
        assertEquals(listOf(root(s), "Apple"), row.shownPath)
    }

    @Test
    fun a_top_level_task_has_the_root_alone_as_its_path() {
        val s = tree()
        assertEquals(listOf(root(s)), SearchDomain.taskResults(s, "Apple").single().shownPath)
    }

    // ----- Matching ---------------------------------------------------------------------------------

    @Test
    fun the_same_name_first_then_names_starting_with_it_then_names_containing_it() {
        var s = SchedulerState.empty()
        for (title in listOf("Read papers", "Paper", "Wallpaper", "Paperwork")) {
            s = r(s, SchedulerIntent.SetCellTitle(freeRootCell(s), title))
        }
        assertEquals(
            listOf("Paper", "Paperwork", "Read papers", "Wallpaper"),
            SearchDomain.taskResults(s, "PAPER").map { it.title },
        )
        // A blank query lists everything, alphabetically; the root task is never a result.
        assertEquals(
            listOf("Paper", "Paperwork", "Read papers", "Wallpaper"),
            SearchDomain.taskResults(s, " ").map { it.title },
        )
    }

    @Test
    fun a_task_the_tree_holds_comes_before_one_only_the_timeline_remembers() {
        var s = tree()
        s = r(s, SchedulerIntent.SetCellTitle(freeRootCell(s), "Pie"))
        s = r(s, SchedulerIntent.SetCellTitle(cellWithTitle(s, "Pie"), ""))
        val rows = SearchDomain.taskResults(s, "Pie")
        assertEquals(listOf(true, false), rows.map { it.inTaskTree })
    }

    @Test
    fun the_other_kinds_answer_with_a_name_and_one_detail() {
        var s = SchedulerState.empty()
        s = r(s, SchedulerIntent.CreateCategory("Deep work"))
        s =
            s.copy(
                alarms = listOf(AlarmEntry(id = "a1", label = "Wake up", timeOfDayMinutes = 7 * 60 + 5)),
                timers = listOf(TimerEntry(id = "t1", label = "", durationSeconds = 25 * 60)),
                chores = listOf(ChoreEntry(title = "Water plants", spanDays = 3.0, timeOfDayMinutes = 9 * 60, id = "c1")),
            )

        val category = SearchDomain.itemResults(s, SearchDomain.Kind.Category, "deep").single()
        assertEquals("Deep work", category.name)
        assertEquals("0 tasks · 0 rules", category.detail)

        val alarm = SearchDomain.itemResults(s, SearchDomain.Kind.Alarm, "wake").single()
        assertEquals("a1", alarm.id)
        assertEquals("07:05", alarm.detail)

        // An unlabelled timer is still findable by what the window calls it.
        val timer = SearchDomain.itemResults(s, SearchDomain.Kind.Timer, "timer").single()
        assertTrue(timer.detail.isNotBlank())

        val reminder = SearchDomain.itemResults(s, SearchDomain.Kind.Reminder, "plants").single()
        assertEquals("every 3 days · 09:00", reminder.detail)

        // The two kinds the README names are the account's own, and say so.
        val periods = SearchDomain.itemResults(s, SearchDomain.Kind.RestrictivePeriod, "")
        assertTrue(periods.isNotEmpty())
        assertTrue(periods.all { it.detail.startsWith("built-in") })

        assertTrue(SearchDomain.itemResults(s, SearchDomain.Kind.Alarm, "nothing like it").isEmpty())
    }

    @Test
    fun the_configuration_survives_its_local_encoding() {
        val config = SearchDomain.Config(query = "a, b\nc", kinds = setOf(SearchDomain.Kind.Alarm, SearchDomain.Kind.Task))
        assertEquals(config, SearchDomain.Config.decode(config.encode()))
        // Nothing checked is a configuration too, and so is an empty query.
        val empty = SearchDomain.Config(query = "", kinds = emptySet())
        assertEquals(empty, SearchDomain.Config.decode(empty.encode()))
        // Nothing stored: the window's own default.
        assertEquals(null, SearchDomain.Config.decode(null))
        // A kind a later build added is dropped, not the whole configuration.
        assertEquals(
            SearchDomain.Config(query = "x", kinds = setOf(SearchDomain.Kind.Timer)),
            SearchDomain.Config.decode("Timer,Wormhole\nx"),
        )
    }

    // ----- Renaming from a row, and a row's sub-tree ------------------------------------------------

    @Test
    fun a_row_renames_its_task_everywhere_and_undo_puts_it_back() {
        var s = withStoredCopy(tree())
        val pie = taskWithTitle(s, "Pie")
        s = r(s, SchedulerIntent.RenameTask(pie, "Tart"))
        assertEquals("Tart", s.tasks.getValue(pie).title)
        assertEquals(listOf(pie), s.titleToTaskIds["Tart"], "the title index follows")
        assertEquals("Tart", s.taskTrees.single().tree.tasks.getValue(pie).title, "the stored tree's copy too")
        s = r(s, SchedulerIntent.Undo)
        assertEquals("Pie", s.tasks.getValue(pie).title)
        assertEquals("Pie", s.taskTrees.single().tree.tasks.getValue(pie).title)
    }

    @Test
    fun a_task_no_cell_holds_is_renamed_too_and_a_blank_name_is_refused() {
        var s = tree()
        val pie = taskWithTitle(s, "Pie")
        // Cut from the tree, kept by its record.
        s = r(s, SchedulerIntent.SetCellTitle(cellWithTitle(s, "Pie"), ""))
        assertTrue(cellsWithTitle(s, "Pie").isEmpty())
        s = r(s, SchedulerIntent.RenameTask(pie, "Tart"))
        assertEquals("Tart", s.tasks.getValue(pie).title)
        assertEquals(s, r(s, SchedulerIntent.RenameTask(pie, "  ")), "a blank title is how a CELL deletes, not a row")
    }

    @Test
    fun an_expanded_rows_sub_tree_is_the_live_tree_with_the_windows_own_selection() {
        var s = tree()
        val apple = taskWithTitle(s, "Apple")
        val subList = s.tasks.getValue(apple).childListId!!
        val pieCell = cellWithTitle(s, "Pie")
        val treeSelection = s.selection
        s = r(s, SchedulerIntent.InSearchSubtree(SchedulerIntent.SetCellTitle(pieCell, "Tart"), subList, readOnly = false))
        assertEquals("Tart", s.tasks.getValue(taskWithTitle(s, "Tart")).title, "an edit there is an edit to the tree")
        assertEquals(treeSelection, s.selection, "the tree's own selection is untouched")
        s = r(s, SchedulerIntent.Undo)
        assertTrue(cellsWithTitle(s, "Pie").isNotEmpty(), "one undoable unit")
    }

    @Test
    fun a_cut_tasks_sub_tree_can_be_looked_through_but_not_modified() {
        val s = tree()
        val subList = s.tasks.getValue(taskWithTitle(s, "Apple")).childListId!!
        val pieCell = cellWithTitle(s, "Pie")
        val renamed = r(s, SchedulerIntent.InSearchSubtree(SchedulerIntent.SetCellTitle(pieCell, "Tart"), subList, readOnly = true))
        assertEquals(s.captureTree(), renamed.captureTree())
        assertEquals(s, r(s, SchedulerIntent.InSearchSubtree(SchedulerIntent.BeginEdit(pieCell), subList, readOnly = true)))
    }

    @Test
    fun each_path_of_a_mirrored_task_names_its_own_cell() {
        val s = mirroredTree()
        val pie = taskWithTitle(s, "Pie")
        val row = SearchDomain.taskResults(s, "pie").single()
        val (underApple, underCrumble) = row.paths
        // "go to task tree" from one line of the list reveals THAT occurrence, with its ancestors to expand.
        val a = assertNotNull(SearchDomain.occurrenceAtPath(s, pie, underApple))
        val b = assertNotNull(SearchDomain.occurrenceAtPath(s, pie, underCrumble))
        assertTrue(a.cellId != b.cellId)
        assertEquals(listOf(cellWithTitle(s, "Apple")), a.ancestors)
        assertEquals(listOf(cellWithTitle(s, "Banana"), cellWithTitle(s, "Crumble")), b.ancestors)
        assertEquals(setOf(a.cellId, b.cellId), cellsWithTitle(s, "Pie").toSet())
        // A path through another tree, or one that no longer leads there, names no live cell.
        assertNull(SearchDomain.occurrenceAtPath(s, pie, listOf("some other tree", "Apple")))
        assertNull(SearchDomain.occurrenceAtPath(s, pie, listOf(root(s), "Banana")))
    }

    // ----- History units, task trees, task relations, keyboard shortcuts -----------------------------

    @Test
    fun history_units_are_found_by_their_label_and_filtered_by_their_stack_and_state() {
        var s = tree()
        s = r(s, SchedulerIntent.RenameTask(taskWithTitle(s, "Pie"), "Tart"))
        val units = setOf(SearchDomain.Kind.HistoryUnit)
        val rename = SearchDomain.results(s, units, "rename").map { it as SearchDomain.ItemResult }
        assertTrue(rename.isNotEmpty(), "the rename is a unit of the Main stack")
        assertNotNull(SearchDomain.historyUnitOf(s, rename.first().id))
        val mainOnly = SearchDomain.Filters(historyCategory = org.example.project.scheduler.state.HistoryCategory.Main)
        assertTrue(SearchDomain.results(s, units, "", filters = mainOnly).all { it.name.isNotEmpty() })
        val calendarOnly = SearchDomain.Filters(historyCategory = org.example.project.scheduler.state.HistoryCategory.Calendar)
        assertTrue(SearchDomain.results(s, units, "rename", filters = calendarOnly).isEmpty())
        // Undone on its device: the filter follows.
        s = r(s, SchedulerIntent.Undo)
        val undone = SearchDomain.Filters(historyUndone = SearchDomain.Tri.Yes)
        assertTrue(SearchDomain.results(s, units, "rename", filters = undone).isNotEmpty())
    }

    @Test
    fun task_trees_are_listed_with_the_open_one_and_filtered() {
        val s = withStoredCopy(tree())
        val trees = setOf(SearchDomain.Kind.TaskTree)
        val rows = SearchDomain.results(s, trees, "").map { it as SearchDomain.ItemResult }
        assertEquals(listOf("Studies"), rows.map { it.name })
        assertTrue(rows.single().detail.startsWith("no date"), "a stored tree, not on the timeline")
        assertTrue(SearchDomain.results(s, trees, "", filters = SearchDomain.Filters(taskTreeOpen = SearchDomain.Tri.Yes)).isEmpty())
        assertEquals(1, SearchDomain.results(s, trees, "", filters = SearchDomain.Filters(taskTreeDated = SearchDomain.Tri.No)).size)
    }

    @Test
    fun keyboard_shortcuts_show_their_chord_and_a_rebound_one_says_so() {
        val shortcut = org.example.project.scheduler.platform.GlobalShortcut.entries.first()
        val other = shortcut.defaultBinding.copy(alt = !shortcut.defaultBinding.alt)
        val s = SchedulerState.empty().copy(shortcutBindings = mapOf(shortcut to other))
        val kinds = setOf(SearchDomain.Kind.Shortcut)
        val rows = SearchDomain.results(s, kinds, "").map { it as SearchDomain.ItemResult }
        assertEquals(org.example.project.scheduler.platform.GlobalShortcut.entries.size, rows.size)
        val row = rows.single { it.id == shortcut.name }
        assertEquals(other.chord + " · rebound", row.detail)
        val rebound = SearchDomain.results(s, kinds, "", filters = SearchDomain.Filters(shortcutRebound = SearchDomain.Tri.Yes))
        assertEquals(listOf(shortcut.action), rebound.map { it.name })
    }

    @Test
    fun task_relations_are_the_windows_own_rows_and_filtered_by_section() {
        var s = tree()
        val key = org.example.project.scheduler.model.TaskRelationKey(taskWithTitle(s, "Pie"), taskWithTitle(s, "Apple"))
        s = s.copy(taskRelations = mapOf(key to org.example.project.scheduler.model.TaskRelationMark(kept = true)))
        val kinds = setOf(SearchDomain.Kind.TaskRelation)
        val rows = SearchDomain.results(s, kinds, "pie").map { it as SearchDomain.ItemResult }
        assertEquals(SearchDomain.relationId(key), rows.single().id)
        assertEquals("Pie in Apple", rows.single().name)
        assertEquals(
            org.example.project.scheduler.domain.TaskRelationsDomain.rows(s).single { it.key == key }.section,
            org.example.project.scheduler.domain.TaskRelationsDomain.Section.Kept,
        )
        val broken = SearchDomain.Filters(relationSection = org.example.project.scheduler.domain.TaskRelationsDomain.Section.Broken)
        assertTrue(SearchDomain.results(s, kinds, "pie", filters = broken).isEmpty())
    }

    @Test
    fun the_new_filters_survive_their_local_encoding() {
        val config =
            SearchDomain.Config(
                kinds = setOf(SearchDomain.Kind.HistoryUnit, SearchDomain.Kind.Shortcut),
                filters = SearchDomain.Filters(
                    historyCategory = org.example.project.scheduler.state.HistoryCategory.Main,
                    historyWindow = org.example.project.scheduler.state.HistoryWindow.Search,
                    historyUndone = SearchDomain.Tri.No,
                    taskTreeOpen = SearchDomain.Tri.Yes,
                    taskTreeDated = SearchDomain.Tri.No,
                    relationSection = org.example.project.scheduler.domain.TaskRelationsDomain.Section.Edited,
                    shortcutRebound = SearchDomain.Tri.Yes,
                    windowStatus = SearchDomain.WindowStatus.Minimized,
                ),
            )
        assertEquals(config, SearchDomain.Config.decode(config.encode()))
    }

    @Test
    fun a_configuration_stored_before_the_window_kind_reads_its_window_filter_as_any() {
        // What the build before the "window" kind wrote: no `windowStatus` field at all.
        val stored = "{\"query\":\"x\",\"kinds\":[\"Task\",\"Shortcut\"],\"shortcutRebound\":\"Yes\"}"
        val config = SearchDomain.Config.decode(stored)!!
        assertEquals(null, config.filters.windowStatus)
        assertEquals(SearchDomain.Tri.Yes, config.filters.shortcutRebound)
        assertEquals(setOf(SearchDomain.Kind.Task, SearchDomain.Kind.Shortcut), config.kinds)
    }

    private val windows =
        listOf(
            SearchDomain.WindowEntry("Search", "Search", SearchDomain.WindowStatus.Open),
            SearchDomain.WindowEntry("Search#2", "Search (2)", SearchDomain.WindowStatus.Minimized),
            SearchDomain.WindowEntry("TaskEdit#1", "Pie", SearchDomain.WindowStatus.Open),
            SearchDomain.WindowEntry("History", "History", SearchDomain.WindowStatus.NotOpen),
        )

    @Test
    fun every_window_is_a_row_each_instance_its_own_and_its_detail_says_where_it_stands() {
        val kinds = setOf(SearchDomain.Kind.Window)
        val rows = SearchDomain.results(SchedulerState.empty(), kinds, "", windows = windows).map { it as SearchDomain.ItemResult }
        assertEquals(windows.map { it.id }.toSet(), rows.map { it.id }.toSet())
        assertTrue(rows.all { it.kind == SearchDomain.Kind.Window })
        // Two instances of one window are two rows, each saying where it stands.
        val search = SearchDomain.results(SchedulerState.empty(), kinds, "search", windows = windows).map { it as SearchDomain.ItemResult }
        assertEquals(listOf("Search" to "open", "Search (2)" to "minimized"), search.map { it.name to it.detail })
        assertEquals("not open", rows.single { it.id == "History" }.detail)
        // Not checked: no window row, whatever the app hands in.
        assertTrue(SearchDomain.results(SchedulerState.empty(), setOf(SearchDomain.Kind.Task), "", windows = windows).isEmpty())
    }

    @Test
    fun the_window_filter_and_sort_read_where_the_window_stands() {
        val kinds = setOf(SearchDomain.Kind.Window)
        fun ids(filters: SearchDomain.Filters = SearchDomain.Filters(), sorts: List<SearchDomain.SortMethod> = SearchDomain.DEFAULT_SORTS) =
            SearchDomain.results(SchedulerState.empty(), kinds, "", filters = filters, sorts = sorts, windows = windows).map { (it as SearchDomain.ItemResult).id }
        assertEquals(listOf("Search#2"), ids(SearchDomain.Filters(windowStatus = SearchDomain.WindowStatus.Minimized)))
        assertEquals(listOf("History"), ids(SearchDomain.Filters(windowStatus = SearchDomain.WindowStatus.NotOpen)))
        assertEquals(1, SearchDomain.Filters(windowStatus = SearchDomain.WindowStatus.Open).activeCount)
        val byState = listOf(SearchDomain.SortMethod(SearchDomain.Kind.Window, SearchDomain.SortKey.WindowState))
        val sorted = ids(sorts = byState)
        assertEquals("History", sorted.last())
        assertEquals("Search#2", sorted[sorted.size - 2])
        // The kinds in the results see the windows too.
        assertEquals(
            setOf(SearchDomain.Kind.Window),
            SearchDomain.kindsInResults(SchedulerState.empty(), SearchDomain.Config(kinds = kinds), windows),
        )
    }

    @Test
    fun the_filters_narrow_their_own_kind_and_nothing_else() {
        val s =
            SchedulerState.empty().copy(
                alarms = listOf(
                    AlarmEntry(id = "a1", label = "Weekday", timeOfDayMinutes = 7 * 60, days = setOf(DayOfWeek.MONDAY)),
                    AlarmEntry(id = "a2", label = "Off", timeOfDayMinutes = 8 * 60, enabled = false),
                ),
                timers = listOf(
                    TimerEntry(id = "t1", label = "Idle"),
                    TimerEntry(id = "t2", label = "Running", endsAtMillis = 5_000L),
                ),
                chores = listOf(
                    ChoreEntry(title = "Once", spanDays = 0.0, id = "c1"),
                    ChoreEntry(title = "Weekly", spanDays = 7.0, id = "c2"),
                ),
            )
        val all = setOf(SearchDomain.Kind.Alarm, SearchDomain.Kind.Timer, SearchDomain.Kind.Reminder)
        fun names(filters: SearchDomain.Filters) =
            SearchDomain.results(s, all, "", { emptyMap() }, filters).map { it.name }.toSet()

        assertEquals(setOf("Weekday", "Off", "Idle", "Running", "Once", "Weekly"), names(SearchDomain.Filters()))
        // An alarm filter removes alarms only — the timers and reminders are untouched.
        assertEquals(
            setOf("Weekday", "Idle", "Running", "Once", "Weekly"),
            names(SearchDomain.Filters(alarmState = SearchDomain.AlarmState.On)),
        )
        // "Rings on Monday": the Monday-only alarm; the off alarm rings every day, Monday included.
        assertEquals(
            setOf("Weekday", "Off"),
            names(SearchDomain.Filters(alarmDays = setOf(DayOfWeek.MONDAY))).intersect(setOf("Weekday", "Off")),
        )
        assertEquals(
            setOf("Off"),
            names(SearchDomain.Filters(alarmDays = setOf(DayOfWeek.SUNDAY))).intersect(setOf("Weekday", "Off")),
        )
        assertEquals(
            setOf("Running"),
            names(SearchDomain.Filters(timerState = SearchDomain.TimerState.Running)).intersect(setOf("Idle", "Running")),
        )
        assertEquals(
            setOf("Once"),
            names(SearchDomain.Filters(reminderRepeats = SearchDomain.ReminderRepeats.OneOff)).intersect(setOf("Once", "Weekly")),
        )
        assertEquals(2, SearchDomain.Filters(alarmState = SearchDomain.AlarmState.On, alarmDays = setOf(DayOfWeek.MONDAY)).activeCount)
    }

    @Test
    fun a_task_filter_reads_the_tree_and_the_category() {
        var s = tree()
        s = r(s, SchedulerIntent.CreateCategory("Deep work"))
        val deep = s.categories.single().id
        val pie = taskWithTitle(s, "Pie")
        s = s.copy(tasks = s.tasks + (pie to s.tasks.getValue(pie).copy(categoryIds = listOf(deep))))
        val tasks = setOf(SearchDomain.Kind.Task)
        val inCategory = SearchDomain.results(s, tasks, "", filters = SearchDomain.Filters(taskCategory = deep))
        assertEquals(listOf("Pie"), inCategory.map { it.name })
        val outOfTrees = SearchDomain.results(s, tasks, "", filters = SearchDomain.Filters(taskInTree = SearchDomain.Tri.No))
        assertTrue(outOfTrees.isEmpty(), "every task of this tree is in it")
    }

    @Test
    fun the_configuration_search_lists_sections_by_kind_and_finds_by_name() {
        val every = SearchDomain.Kind.entries.toSet()
        val sections = SearchDomain.configurations("", every)
        // The general section first, then one per kind, in the drop-down's order.
        assertEquals(listOf<SearchDomain.Kind?>(null) + SearchDomain.Kind.entries, sections.map { it.first })
        assertEquals(
            listOf(SearchDomain.Setting.SearchText, SearchDomain.Setting.Types, SearchDomain.Setting.SortResults),
            sections.first().second,
        )
        // Every kind's section ends with how its rows are ordered, and "sort" finds every ordering.
        assertTrue(sections.drop(1).all { (_, settings) -> settings.last().sorts })
        assertEquals(1 + SearchDomain.Kind.entries.size, SearchDomain.configurations("sort", every).sumOf { it.second.size })
        // The bar finds configurations by name; a section with nothing left is dropped.
        val state = SearchDomain.configurations("state", every)
        assertEquals(listOf(SearchDomain.Kind.Alarm, SearchDomain.Kind.Timer, SearchDomain.Kind.Window), state.map { it.first })
        // The kind selector, and the "only the Search results' kinds" button, cut the per-kind sections only.
        assertEquals(
            listOf<SearchDomain.Kind?>(null, SearchDomain.Kind.Timer),
            SearchDomain.configurations("", setOf(SearchDomain.Kind.Timer, SearchDomain.Kind.Alarm), setOf(SearchDomain.Kind.Timer))
                .map { it.first },
        )
    }

    @Test
    fun a_filter_that_empties_its_own_type_stays_listed_while_the_filters_that_are_on_are_shown() {
        // The case the button is for: "only the types in the Search results" is on, and the user sets the alarms'
        // State to "off" — no alarm is off, so the alarms leave the results, and their section with them.
        val s = SchedulerState.empty().copy(alarms = listOf(AlarmEntry(id = "a1", label = "Wake", timeOfDayMinutes = 60)))
        val config =
            SearchDomain.Config(kinds = setOf(SearchDomain.Kind.Alarm), filters = SearchDomain.Filters(alarmState = SearchDomain.AlarmState.Off))
        val resultKinds = SearchDomain.kindsInResults(s, config)
        assertEquals(emptySet(), resultKinds)
        val every = SearchDomain.Kind.entries.toSet()
        assertEquals(listOf<SearchDomain.Kind?>(null), SearchDomain.configurations("", every, resultKinds).map { it.first })
        // With the button: the filter that is on is back, alone in its section — the ones left at "any" are not.
        assertEquals(
            listOf(SearchDomain.Kind.Alarm to listOf(SearchDomain.Setting.AlarmStateSetting)),
            SearchDomain.configurations("", every, resultKinds, config.filters).drop(1),
        )
        // A type the results do hold keeps its whole section either way, and the name search still applies.
        assertEquals(
            SearchDomain.configurations("", every, every),
            SearchDomain.configurations("", every, every, config.filters),
        )
        // "Rings on" is an alarm filter left at "any": the button does not bring it back.
        assertEquals(emptyList(), SearchDomain.configurations("rings", every, resultKinds, config.filters))
        assertEquals(
            listOf(SearchDomain.Kind.Alarm to listOf(SearchDomain.Setting.AlarmStateSetting)),
            SearchDomain.configurations("state", every, resultKinds, config.filters),
        )
        // "On" is the same statement the Search window's count reads.
        assertEquals(1, config.filters.activeCount)
        assertTrue(config.filters.isOn(SearchDomain.Setting.AlarmStateSetting))
        assertFalse(config.filters.isOn(SearchDomain.Setting.SortResults))
    }

    @Test
    fun the_kinds_in_the_results_are_the_kinds_with_a_row() {
        val s = SchedulerState.empty().copy(alarms = listOf(AlarmEntry(id = "a1", label = "Wake", timeOfDayMinutes = 60)))
        val config = SearchDomain.Config(kinds = setOf(SearchDomain.Kind.Alarm, SearchDomain.Kind.Timer))
        assertEquals(setOf(SearchDomain.Kind.Alarm), SearchDomain.kindsInResults(s, config))
        val filtered = config.copy(filters = SearchDomain.Filters(alarmState = SearchDomain.AlarmState.Off))
        assertEquals(emptySet(), SearchDomain.kindsInResults(s, filtered))
    }

    @Test
    fun the_stored_configuration_keeps_its_filters_and_still_reads_the_first_shape() {
        val config =
            SearchDomain.Config(
                query = "wake",
                kinds = setOf(SearchDomain.Kind.Alarm),
                filters = SearchDomain.Filters(
                    alarmState = SearchDomain.AlarmState.On,
                    alarmDays = setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY),
                    taskCategory = org.example.project.scheduler.model.CategoryId("cat-1"),
                    timerState = SearchDomain.TimerState.Paused,
                ),
            )
        assertEquals(config, SearchDomain.Config.decode(config.encode()))
        // Local-DB compatibility: the previous build stored "kinds line, then the query" — it still opens.
        assertEquals(
            SearchDomain.Config(query = "wake", kinds = setOf(SearchDomain.Kind.Task, SearchDomain.Kind.Alarm)),
            SearchDomain.Config.decode("Task,Alarm\nwake"),
        )
        // A value this build does not know falls back to "any"; an unreadable text to nothing stored.
        assertEquals(
            SearchDomain.AlarmState.Any,
            SearchDomain.Config.decode("""{"alarmState":"Sometimes"}""")!!.filters.alarmState,
        )
        assertNull(SearchDomain.Config.decode("{not json"))

        val own = SearchDomain.ConfigurationSearch(
            "rings", setOf(SearchDomain.Kind.Alarm), onlyResultKinds = true, target = "Search#2", showFiltersOn = true,
        )
        assertEquals(own, SearchDomain.ConfigurationSearch.decode(own.encode()))
        // Stored before the "filters that are on" button: it is off.
        assertFalse(SearchDomain.ConfigurationSearch.decode("""{"query":"x","onlyResultKinds":true}""")!!.showFiltersOn)
        // Stored before copies existed: it edits the original Search window.
        assertEquals("Search", SearchDomain.ConfigurationSearch.decode("""{"query":"x"}""")!!.target)
    }

    // ----- Sorting ----------------------------------------------------------------------------------

    private fun method(kind: SearchDomain.Kind?, key: SearchDomain.SortKey, descending: Boolean = false) =
        SearchDomain.SortMethod(kind, key, descending)

    @Test
    fun the_sorting_methods_apply_dominant_first_and_a_kinds_method_keeps_the_other_kinds_in_place() {
        val s =
            SchedulerState.empty().copy(
                alarms = listOf(
                    AlarmEntry(id = "a1", label = "Bake", timeOfDayMinutes = 9 * 60),
                    AlarmEntry(id = "a2", label = "Alarm", timeOfDayMinutes = 7 * 60),
                    AlarmEntry(id = "a3", label = "Cook", timeOfDayMinutes = 8 * 60),
                ),
                timers = listOf(
                    TimerEntry(id = "t1", label = "Long", durationSeconds = 600),
                    TimerEntry(id = "t2", label = "Short", durationSeconds = 60),
                ),
            )
        val kinds = setOf(SearchDomain.Kind.Alarm, SearchDomain.Kind.Timer)
        fun names(vararg sorts: SearchDomain.SortMethod) =
            SearchDomain.results(s, kinds, "", sorts = sorts.toList()).map { it.name }
        val alarmTime = method(SearchDomain.Kind.Alarm, SearchDomain.SortKey.AlarmTime)
        val name = method(null, SearchDomain.SortKey.Name)

        // The default list (relevance) is the order before sorting; so is an empty list.
        val base = listOf("Alarm", "Bake", "Cook", "Long", "Short")
        assertEquals(base, SearchDomain.results(s, kinds, "").map { it.name })
        assertEquals(base, names())
        // One kind's methods reorder its rows in the places they held: the timers never move for the alarms.
        assertEquals(
            listOf("Alarm", "Cook", "Bake", "Long", "Short"),
            names(alarmTime, method(SearchDomain.Kind.Timer, SearchDomain.SortKey.TimerDuration, descending = true)),
        )
        // The whole list by name, Z first — the kinds interleave — and the alarms by time in the alarms' places.
        assertEquals(
            listOf("Short", "Long", "Alarm", "Cook", "Bake"),
            names(alarmTime, name.copy(descending = true)),
        )
        // Dominance: below the name (every name differs) the alarms' time decides nothing; above it, it decides.
        assertEquals(base, names(name, alarmTime))
        assertEquals(listOf("Alarm", "Cook", "Bake", "Long", "Short"), names(alarmTime, name))
        // By type, reversed: the timers first, the alarms still by time among themselves.
        assertEquals(
            listOf("Long", "Short", "Alarm", "Cook", "Bake"),
            names(method(null, SearchDomain.SortKey.Type, descending = true), alarmTime),
        )
    }

    @Test
    fun checking_adds_at_the_bottom_dragging_reorders_and_the_cross_removes() {
        val relevance = SearchDomain.DEFAULT_SORTS.single()
        val alarmTime = method(SearchDomain.Kind.Alarm, SearchDomain.SortKey.AlarmTime)
        val name = method(null, SearchDomain.SortKey.Name)
        var sorts = SearchDomain.withSortMethod(SearchDomain.DEFAULT_SORTS, alarmTime, on = true)
        sorts = SearchDomain.withSortMethod(sorts, name, on = true)
        assertEquals(listOf(relevance, alarmTime, name), sorts)
        // A method is in the list once, whatever its direction.
        assertEquals(
            listOf(relevance, name, alarmTime.copy(descending = true)),
            SearchDomain.withSortMethod(sorts, alarmTime.copy(descending = true), on = true),
        )
        // Dragged to the top, and to the bottom; the others keep their order around it.
        assertEquals(listOf(name, relevance, alarmTime), SearchDomain.movedSortMethod(sorts, 2, 0))
        assertEquals(listOf(alarmTime, name, relevance), SearchDomain.movedSortMethod(sorts, 0, 2))
        assertEquals(sorts, SearchDomain.movedSortMethod(sorts, 7, 0))
        // The ✕ (or unchecking) removes it.
        assertEquals(listOf(relevance, name), SearchDomain.withSortMethod(sorts, alarmTime, on = false))
        // "alarm: name" and "name" are two methods.
        assertEquals(4, SearchDomain.withSortMethod(sorts, method(SearchDomain.Kind.Alarm, SearchDomain.SortKey.Name), on = true).size)
    }

    @Test
    fun a_row_without_a_value_for_the_key_goes_last_in_either_direction() {
        var s = withStoredCopy(tree())
        // Pie leaves the live tree; the stored tree still holds it, so it is a row — with no live priority.
        val pie = taskWithTitle(s, "Pie")
        s = r(s, SchedulerIntent.SetCellTitle(cellWithTitle(s, "Pie"), ""))
        val tasks = setOf(SearchDomain.Kind.Task)
        val live = SchedulerDomain.absoluteTaskPriorities(s)
        assertFalse(pie in live)
        for (descending in listOf(false, true)) {
            val sorts = listOf(method(SearchDomain.Kind.Task, SearchDomain.SortKey.TaskPriority, descending))
            val rows = SearchDomain.results(s, tasks, "", sorts = sorts).map { it as SearchDomain.TaskResult }
            val withValue = rows.takeWhile { it.taskId in live }
            assertEquals(listOf(pie), rows.drop(withValue.size).map { it.taskId }, "the row without a priority closes the list")
            val values = withValue.map { live.getValue(it.taskId) }
            assertEquals(if (descending) values.sortedDescending() else values.sorted(), values)
        }
    }

    @Test
    fun the_sorting_methods_survive_their_local_encoding_and_an_unknown_one_is_dropped() {
        val config =
            SearchDomain.Config(
                sorts = listOf(
                    method(null, SearchDomain.SortKey.Type, descending = true),
                    method(SearchDomain.Kind.Alarm, SearchDomain.SortKey.AlarmTime),
                    method(SearchDomain.Kind.Task, SearchDomain.SortKey.Name, descending = true),
                ),
            )
        assertEquals(config, SearchDomain.Config.decode(config.encode()))
        // An emptied list stays empty; it does not come back as the default.
        val none = SearchDomain.Config(sorts = emptyList())
        assertEquals(none, SearchDomain.Config.decode(none.encode()))
        // Local-DB compatibility: a configuration stored before sorting decodes to the default list.
        assertEquals(SearchDomain.DEFAULT_SORTS, SearchDomain.Config.decode("""{"query":"x","alarmState":"On"}""")!!.sorts)
        // Local-DB compatibility: the first sorting shape (one whole-list sort, one per kind) becomes the list —
        // the whole-list method dominant, the kinds' below it in the drop-down's order.
        assertEquals(
            listOf(
                method(null, SearchDomain.SortKey.Name, descending = true),
                method(SearchDomain.Kind.Task, SearchDomain.SortKey.TaskPriority),
                method(SearchDomain.Kind.Alarm, SearchDomain.SortKey.AlarmTime, descending = true),
            ),
            SearchDomain.Config.decode(
                """{"sort":"Name","sortDescending":true,"kindSorts":{"Alarm":{"key":"AlarmTime","descending":true},""" +
                    """"Task":{"key":"TaskPriority"}}}""",
            )!!.sorts,
        )
        // That shape's defaults (a relevance whole-list sort, no kind sort) are today's default.
        assertEquals(SearchDomain.DEFAULT_SORTS, SearchDomain.Config.decode("""{"sort":"Relevance","kindSorts":{}}""")!!.sorts)
        // A kind or key this build does not know, a key its kind does not offer, and a repeat are dropped; the
        // rest keep their order.
        assertEquals(
            listOf(method(null, SearchDomain.SortKey.Name), method(SearchDomain.Kind.Alarm, SearchDomain.SortKey.AlarmTime)),
            SearchDomain.Config.decode(
                """{"sortMethods":[{"key":"Wormhole"},{"key":"Name"},{"kind":"Alarm","key":"TimerDuration"},""" +
                    """{"kind":"Nope","key":"Name"},{"kind":"Alarm","key":"AlarmTime"},{"key":"Name","descending":true}]}""",
            )!!.sorts,
        )
    }

    @Test
    fun every_alarm_and_every_timer_has_a_row_when_their_kinds_are_checked() {
        val s =
            SchedulerState.empty().copy(
                alarms = listOf(
                    AlarmEntry(id = "a1", label = "Wake up", timeOfDayMinutes = 7 * 60),
                    // Unlabelled, and switched off: still an alarm of the account.
                    AlarmEntry(id = "a2", label = "", timeOfDayMinutes = 8 * 60, enabled = false),
                    // The same label twice is two alarms, two rows.
                    AlarmEntry(id = "a3", label = "Wake up", timeOfDayMinutes = 9 * 60),
                ),
                timers = listOf(
                    TimerEntry(id = "t1", label = "Tea", durationSeconds = 180),
                    TimerEntry(id = "t2", label = "", durationSeconds = 60),
                ),
            )
        val rows = SearchDomain.results(s, setOf(SearchDomain.Kind.Alarm, SearchDomain.Kind.Timer), "")
        assertEquals(
            setOf("Alarm/a1", "Alarm/a2", "Alarm/a3", "Timer/t1", "Timer/t2"),
            rows.map { it as SearchDomain.ItemResult }.map { it.kind.name + "/" + it.id }.toSet(),
        )
        assertEquals(5, rows.size)
    }

    @Test
    fun every_checked_kind_is_searched_and_the_best_match_leads_across_kinds() {
        var s = tree()
        s = r(s, SchedulerIntent.SetCellTitle(freeRootCell(s), "Wake"))
        s =
            s.copy(
                alarms = listOf(
                    AlarmEntry(id = "a1", label = "Wake up", timeOfDayMinutes = 7 * 60),
                    AlarmEntry(id = "a2", label = "wake", timeOfDayMinutes = 8 * 60),
                ),
                timers = listOf(TimerEntry(id = "t1", label = "Wake timer", durationSeconds = 60)),
            )
        val both = setOf(SearchDomain.Kind.Task, SearchDomain.Kind.Alarm)
        // The two exact names first — the task before the alarm, the drop-down's order — then "Wake up". The
        // unchecked timer is not a result.
        assertEquals(
            listOf(SearchDomain.Kind.Task to "Wake", SearchDomain.Kind.Alarm to "wake", SearchDomain.Kind.Alarm to "Wake up"),
            SearchDomain.results(s, both, "wake").map { it.kind to it.name },
        )
        // One checked kind is exactly that kind's own list; none checked finds nothing.
        assertEquals(
            SearchDomain.itemResults(s, SearchDomain.Kind.Timer, "wake"),
            SearchDomain.results(s, setOf(SearchDomain.Kind.Timer), "wake"),
        )
        assertTrue(SearchDomain.results(s, emptySet(), "").isEmpty())
    }

    // ----- Persistence (CLAUDE.md: persisted-DB compatibility) --------------------------------------

    @Test
    fun the_last_path_survives_a_round_trip() {
        var s = tree()
        val pie = taskWithTitle(s, "Pie")
        s = r(s, SchedulerIntent.SetCellTitle(cellWithTitle(s, "Pie"), ""))
        val decoded = assertNotNull(SchedulerStateCodec.decode(SchedulerStateCodec.encode(s)))
        assertEquals(listOf(root(s), "Apple"), decoded.tasks[pie]?.lastTreePath)
    }

    @Test
    fun a_payload_written_before_the_last_path_decodes_to_none() {
        var s = tree()
        val pie = taskWithTitle(s, "Pie")
        s = r(s, SchedulerIntent.SetCellTitle(cellWithTitle(s, "Pie"), ""))
        val payload = SchedulerStateCodec.encode(s)
        assertTrue(payload.contains("lastTreePath"), "the fixture must carry the field")
        // The previous shape: the same payload with every occurrence of the field dropped.
        val before = payload.replace(Regex(""",\"lastTreePath\":\[[^]]*]"""), "")
        assertFalse(before.contains("lastTreePath"))

        val decoded = assertNotNull(SchedulerStateCodec.decode(before))
        val task = assertNotNull(decoded.tasks[pie], "the task itself is untouched")
        assertEquals(emptyList(), task.lastTreePath)
        // An older build's tombstone is still a result: in no tree, with an empty path box.
        val row = SearchDomain.taskResults(decoded, "Pie").single()
        assertFalse(row.inTaskTree)
        assertEquals(emptyList(), row.shownPath)
    }
}
