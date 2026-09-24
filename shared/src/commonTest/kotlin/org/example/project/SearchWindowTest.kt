package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
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
