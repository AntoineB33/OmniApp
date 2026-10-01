package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.PeriodKinds
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.domain.SearchDomain
import org.example.project.scheduler.domain.TaskPathsDomain
import org.example.project.scheduler.model.CellId
import org.example.project.scheduler.model.ScheduleUnitEntry
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.state.HistoryCategory
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState

/**
 * User rule 2026-10-01: the Search window's actions on the added TASKS — a task cell's right-click menu and the task
 * edit window's sections, over every added task at once — and the actions' filter with the resilience action's period
 * field, which the period edit window's button sets.
 */
class SearchTaskActionsTest {
    private fun r(state: SchedulerState, intent: SchedulerIntent) = SchedulerReducer.reduce(state, intent)

    private fun freeRootCell(state: SchedulerState): CellId = state.lists[state.rootListId]!!.cellIds.last()

    private fun freeChildCell(state: SchedulerState, taskId: TaskId): CellId =
        state.lists[state.tasks[taskId]!!.childListId!!]!!.cellIds.last()

    private fun taskWithTitle(state: SchedulerState, title: String): TaskId = state.tasks.values.first { it.title == title }.id

    private fun units(state: SchedulerState): Int = HistoryCategory.entries.sumOf { state.histories.forCategory(it).units.size }

    /** Apple / Pie, and Banana and Basket at the top level. */
    private fun tree(): SchedulerState {
        var s = SchedulerState.empty()
        s = r(s, SchedulerIntent.SetCellTitle(freeRootCell(s), "Apple"))
        s = r(s, SchedulerIntent.SetCellTitle(freeChildCell(s, taskWithTitle(s, "Apple")), "Pie"))
        s = r(s, SchedulerIntent.SetCellTitle(freeRootCell(s), "Banana"))
        s = r(s, SchedulerIntent.SetCellTitle(freeRootCell(s), "Basket"))
        return s
    }

    private fun added(state: SchedulerState, vararg titles: String): List<SearchDomain.Result> =
        SearchDomain.resolve(state, titles.map { "Task/" + taskWithTitle(state, it).value })

    private fun apply(state: SchedulerState, added: List<SearchDomain.Result>, command: SearchDomain.AddedCommand) =
        SearchDomain.addedIntents(state, added, command, 0L).fold(state, ::r)

    @Test
    fun the_period_edit_windows_search_lists_the_tasks_and_only_the_resilience_action_set_on_that_period() {
        val config = SearchDomain.resilienceSearchConfig(PeriodKinds.SLEEP)
        assertEquals(setOf(SearchDomain.Kind.Task), config.kinds)
        assertEquals(SearchDomain.Tri.Yes, config.filters.taskSchedulable, "only the tasks a resilience is read for")
        assertEquals(
            listOf(SearchDomain.AddedAction.TaskResilience),
            SearchDomain.addedActions(config.actionQuery, SearchDomain.Kind.entries.toSet()).flatMap { it.second },
            "the filter finds the one action, in the configurations window and the top right quarter alike",
        )
        assertEquals(PeriodKinds.SLEEP, SearchDomain.resiliencePeriodOf(tree(), config))
        // A period the field cannot offer falls back to the first it can.
        assertEquals(
            SearchDomain.resilienceKinds(tree()).first(),
            SearchDomain.resiliencePeriodOf(tree(), config.copy(resiliencePeriod = PeriodKinds.INACTIVITY)),
        )
    }

    @Test
    fun the_schedulable_filter_keeps_the_leaves_the_scheduler_may_place_or_the_others() {
        val s = tree()
        fun titles(value: SearchDomain.Tri) =
            SearchDomain.results(s, setOf(SearchDomain.Kind.Task), "", filters = SearchDomain.Filters(taskSchedulable = value))
                .map { s.tasks[(it as SearchDomain.TaskResult).taskId]!!.title }
                .sorted()
        assertEquals(listOf("Banana", "Basket", "Pie"), titles(SearchDomain.Tri.Yes))
        assertEquals(listOf("Apple"), titles(SearchDomain.Tri.No), "a parent is a grouping, never placed")
        assertEquals(4, titles(SearchDomain.Tri.Any).size)
        assertTrue(SearchDomain.Filters(taskSchedulable = SearchDomain.Tri.Yes).isOn(SearchDomain.Setting.TaskSchedulable))
        val config = SearchDomain.Config(filters = SearchDomain.Filters(taskSchedulable = SearchDomain.Tri.No))
        assertEquals(config, SearchDomain.Config.decode(config.encode()))
        assertEquals(SearchDomain.Tri.Any, SearchDomain.Config.decode("""{"kinds":["Task"]}""")!!.filters.taskSchedulable, "older = any")
    }

    @Test
    fun the_actions_filter_and_the_period_field_are_kept_with_the_search_configuration() {
        val config = SearchDomain.resilienceSearchConfig("deep work")
        assertEquals(config, SearchDomain.Config.decode(config.encode()))
        // What an older build stored has neither: no filter, the first period.
        val old = SearchDomain.Config.decode("""{"query":"a","kinds":["Task"]}""")!!
        assertEquals("", old.actionQuery)
        assertNull(old.resiliencePeriod)
    }

    @Test
    fun resilience_goes_to_every_added_schedulable_leaf_as_one_unit() {
        val s = tree()
        val before = units(s)
        val list = added(s, "Apple", "Pie", "Banana")
        assertEquals(listOf("Pie", "Banana"), SearchDomain.addedLeafIds(s, list).map { s.tasks[it]!!.title }, "Apple is a parent")
        val after = apply(s, list, SearchDomain.AddedCommand.Resilience(PeriodKinds.SLEEP, 0.5))
        assertEquals(0.5, after.tasks[taskWithTitle(s, "Pie")]!!.resilienceFor(PeriodKinds.SLEEP))
        assertEquals(0.5, after.tasks[taskWithTitle(s, "Banana")]!!.resilienceFor(PeriodKinds.SLEEP))
        assertEquals(0.0, after.tasks[taskWithTitle(s, "Apple")]!!.resilienceFor(PeriodKinds.SLEEP))
        assertEquals(before + 1, units(after))
        assertEquals(units(after), units(apply(after, list, SearchDomain.AddedCommand.Resilience(PeriodKinds.SLEEP, 0.5))), "nobody moves")
    }

    @Test
    fun text_and_schedule_unit_are_one_unit_each_and_a_unit_too_long_for_a_task_leaves_it_alone() {
        var s = tree()
        s = r(s, SchedulerIntent.SetTaskMinimumTime(taskWithTitle(s, "Banana"), 30))
        s = r(s, SchedulerIntent.SetTaskMinimumTime(taskWithTitle(s, "Basket"), 5))
        val list = added(s, "Banana", "Basket")
        val before = units(s)
        val texted = apply(s, list, SearchDomain.AddedCommand.Text("shared notes"))
        assertEquals(listOf("shared notes", "shared notes"), list.map { texted.tasks[(it as SearchDomain.TaskResult).taskId]!!.text })
        assertEquals(before + 1, units(texted))

        val steps = listOf(ScheduleUnitEntry("warm up", 10), ScheduleUnitEntry("work", 10))
        val planned = apply(texted, list, SearchDomain.AddedCommand.ScheduleUnit(steps))
        assertEquals(steps, planned.tasks[taskWithTitle(s, "Banana")]!!.scheduleUnit)
        assertEquals(emptyList(), planned.tasks[taskWithTitle(s, "Basket")]!!.scheduleUnit, "20 min exceed its 5 min")
        assertEquals(before + 2, units(planned))
    }

    @Test
    fun add_under_puts_every_task_that_may_go_there_in_one_undoable_unit() {
        val s = tree()
        val apple = taskWithTitle(s, "Apple")
        val list = added(s, "Banana", "Basket", "Pie")
        // Pie is in Apple already: Apple is still offered, for the two that may go.
        assertTrue(apple in TaskPathsDomain.candidatesForAll(s, SearchDomain.addedTaskIds(s, list), "app").map { it.parentTaskId })
        val after = apply(s, list, SearchDomain.AddedCommand.AddUnder(apple))
        assertEquals(
            listOf("Pie", "Banana", "Basket"),
            after.lists[after.tasks[apple]!!.childListId!!]!!.cellIds.mapNotNull { after.cells[it]?.taskId }.map { after.tasks[it]!!.title },
        )
        assertEquals(units(s) + 1, units(after))
        val undone = r(after, SchedulerIntent.Undo)
        assertEquals(listOf("Apple"), TaskPathsDomain.occurrences(undone, taskWithTitle(s, "Pie")).map { it.label })
        assertEquals(listOf(TaskPathsDomain.TOP_LEVEL), TaskPathsDomain.occurrences(undone, taskWithTitle(s, "Banana")).map { it.label })
    }

    @Test
    fun the_cell_menu_entries_act_on_each_added_tasks_first_cell() {
        val s = tree()
        val list = added(s, "Apple", "Banana")
        val cells = SearchDomain.addedTaskCells(s, list)
        assertEquals(listOf("Apple", "Banana"), cells.map { s.tasks[s.cells[it]!!.taskId!!]!!.title })
        assertEquals(
            cells.map { SchedulerIntent.CollapseSubtrees(it) },
            SearchDomain.addedIntents(s, list, SearchDomain.AddedCommand.CollapseSubtrees, 0L),
        )
        // No template: nothing to add.
        assertEquals(emptyList(), SearchDomain.addedIntents(s, list, SearchDomain.AddedCommand.AddDefaultSubtree, 0L))
        assertTrue(SchedulerDomain.isUserTaskId(taskWithTitle(s, "Apple")), "its id is one the copy writes")
    }

    // ----- The element edit windows, as actions (user rule 2026-10-01) ---------------------------------

    @Test
    fun an_elements_own_window_is_the_search_window_holding_it_alone() {
        val s = r(tree(), SchedulerIntent.AddPeriodKind("deep work"))
        val config = SearchDomain.elementSearchConfig(SearchDomain.Kind.RestrictivePeriod, "deep work")
        assertEquals(setOf(SearchDomain.Kind.RestrictivePeriod), config.kinds)
        val added = SearchDomain.resolve(s, config.added)
        assertEquals(listOf("deep work"), added.map { (it as SearchDomain.ItemResult).id })
        // What the period edit window held is that window's period actions.
        val actions = SearchDomain.addedActions("", SearchDomain.Kind.entries.toSet(), added.mapTo(HashSet()) { it.kind })
            .flatMap { it.second }
        assertTrue(
            actions.containsAll(
                listOf(
                    SearchDomain.AddedAction.PeriodDrawing, SearchDomain.AddedAction.PeriodCombinations,
                    SearchDomain.AddedAction.PeriodTaskSearch, SearchDomain.AddedAction.PeriodDelete,
                ),
            ),
        )
        // A task's key is the one its row and its cells carry.
        val apple = taskWithTitle(s, "Apple")
        assertEquals(listOf(SearchDomain.taskKey(apple)), SearchDomain.elementSearchConfig(SearchDomain.Kind.Task, apple.value).added)
        // The resilience filter still finds the one action, never the period's "Search its tasks".
        assertEquals(
            listOf(SearchDomain.AddedAction.TaskResilience),
            SearchDomain.addedActions(SearchDomain.RESILIENCE_ACTION_QUERY, SearchDomain.Kind.entries.toSet()).flatMap { it.second },
        )
    }

    @Test
    fun the_periods_drawing_and_delete_act_on_every_added_period_and_never_delete_a_default_one() {
        var s = r(tree(), SchedulerIntent.AddPeriodKind("deep work"))
        val added = SearchDomain.resolve(s, listOf("RestrictivePeriod/deep work", "RestrictivePeriod/" + PeriodKinds.SLEEP))
        s = apply(s, added, SearchDomain.AddedCommand.Drawing(org.example.project.scheduler.domain.PeriodDrawing.Zigzags))
        assertEquals(org.example.project.scheduler.domain.PeriodDrawing.Zigzags, s.periodKindConfig.drawing("deep work"))
        assertEquals(org.example.project.scheduler.domain.PeriodDrawing.Zigzags, s.periodKindConfig.drawing(PeriodKinds.SLEEP))
        assertEquals(
            listOf(SchedulerIntent.RemovePeriodKind("deep work")),
            SearchDomain.addedIntents(s, added, SearchDomain.AddedCommand.DeletePeriods, 0L),
        )
        s = apply(s, added, SearchDomain.AddedCommand.DeletePeriods)
        assertTrue("deep work" !in s.allPeriodKinds)
        assertTrue(PeriodKinds.SLEEP in s.allPeriodKinds)
    }

    @Test
    fun a_tasks_path_is_taken_away_through_the_actions_as_the_edit_window_did() {
        var s = tree()
        val pie = taskWithTitle(s, "Pie")
        s = r(s, SchedulerIntent.AddTaskPath(pie, taskWithTitle(s, "Banana")))
        val underApple = TaskPathsDomain.occurrences(s, pie).first { it.label == "Apple" }.cellId
        s = apply(s, added(s, "Pie"), SearchDomain.AddedCommand.RemovePath(underApple))
        assertEquals(listOf("Banana"), TaskPathsDomain.occurrences(s, pie).map { it.label })
    }

    // ----- New and Duplicate (user rule 2026-10-01) ------------------------------------------------------

    @Test
    fun a_duplicated_task_is_a_new_task_beside_it_titled_copy_with_its_settings_in_one_unit() {
        var s = tree()
        val pie = taskWithTitle(s, "Pie")
        s = r(s, SchedulerIntent.SetTaskMinimumTime(pie, 40))
        s = r(s, SchedulerIntent.SetTaskText(pie, "notes"))
        s = r(s, SchedulerIntent.SetTaskResilience(pie, PeriodKinds.SLEEP, 0.25))
        val before = units(s)
        val list = added(s, "Pie")
        val after = SearchDomain.duplicateIntents(s, list, SearchDomain.Kind.Task, 0L).fold(s, ::r)
        val made = SearchDomain.newElementKeys(s, after, SearchDomain.Kind.Task)
        val copyId = TaskId(made.single().removePrefix("Task/"))
        val copy = after.tasks[copyId]!!
        assertEquals("Pie copy", copy.title)
        assertEquals(40, copy.minimumMinutes)
        assertEquals("notes", copy.text)
        assertEquals(0.25, copy.resilienceFor(PeriodKinds.SLEEP))
        assertEquals(listOf("Apple"), TaskPathsDomain.occurrences(after, copyId).map { it.label }, "in the original's list")
        assertEquals("Pie", after.tasks[pie]!!.title, "the original is untouched")
        assertEquals(before + 1, units(after))
        assertTrue(r(after, SchedulerIntent.Undo).tasks.values.none { it.title == "Pie copy" })
    }

    @Test
    fun a_duplicated_category_and_period_take_the_next_free_copy_name_and_their_settings() {
        var s = r(tree(), SchedulerIntent.CreateCategory("Home"))
        val home = s.categories.single { it.title == "Home" }.id
        val homeRow = SearchDomain.resolve(s, listOf("Category/" + home.value))
        s = SearchDomain.duplicateIntents(s, homeRow, SearchDomain.Kind.Category, 0L).fold(s, ::r)
        s = SearchDomain.duplicateIntents(s, homeRow, SearchDomain.Kind.Category, 0L).fold(s, ::r)
        assertEquals(listOf("Home", "Home copy", "Home copy 2"), s.categories.map { it.title })

        // "no screen": an on-screen task holds a 0 against it, an off-screen one 1 (its kind default) — the copy, a
        // kind of the account's own, defaults to 0, so every task is written to stand to it as it stands to the original.
        val banana = taskWithTitle(s, "Banana")
        s = r(s, SchedulerIntent.SetTaskResilience(banana, PeriodKinds.NO_SCREEN, 0.0))
        s = r(s, SchedulerIntent.SetTaskResilience(taskWithTitle(s, "Basket"), PeriodKinds.NO_SCREEN, 1.0))
        val period = SearchDomain.resolve(s, listOf("RestrictivePeriod/" + PeriodKinds.NO_SCREEN))
        val after = SearchDomain.duplicateIntents(s, period, SearchDomain.Kind.RestrictivePeriod, 0L).fold(s, ::r)
        val copy = "no screen copy"
        assertEquals(listOf("RestrictivePeriod/$copy"), SearchDomain.newElementKeys(s, after, SearchDomain.Kind.RestrictivePeriod))
        assertEquals(s.periodKindConfig.drawing(PeriodKinds.NO_SCREEN), after.periodKindConfig.drawing(copy))
        assertEquals(0.0, after.tasks[banana]!!.resilienceFor(copy))
        assertEquals(1.0, after.tasks[taskWithTitle(s, "Basket")]!!.resilienceFor(copy))
        assertTrue(after.periodCombinations.any { copy in it.named }, "the rules naming it are copied onto the copy")
        assertEquals(s.periodCombinations, after.periodCombinations.filterNot { copy in it.named }, "and the original's stay")
    }

    @Test
    fun a_duplicated_alarm_timer_chrono_and_reminder_are_one_more_idle_row_titled_copy() {
        var s = tree().copy(
            alarms = listOf(org.example.project.scheduler.model.AlarmEntry(id = "alarm-0", label = "Wake", timeOfDayMinutes = 420)),
            timers = listOf(org.example.project.scheduler.model.TimerEntry(id = "timer-0", label = "Tea", endsAtMillis = 99_000L)),
            chronos = listOf(org.example.project.scheduler.model.ChronoEntry(id = "chrono-0", label = "Run", startedAtMillis = 5L)),
        )
        s = r(s, SchedulerIntent.SetChores(listOf(org.example.project.scheduler.model.ChoreEntry(title = "Water", spanDays = 3.0, id = "reminder-0")), 0L, 0L))
        val all = SearchDomain.resolve(s, listOf("Alarm/alarm-0", "Timer/timer-0", "Chrono/chrono-0", "Reminder/reminder-0"))
        for (kind in listOf(SearchDomain.Kind.Alarm, SearchDomain.Kind.Timer, SearchDomain.Kind.Chrono, SearchDomain.Kind.Reminder)) {
            val after = SearchDomain.duplicateIntents(s, all, kind, 0L).fold(s, ::r)
            assertEquals(1, SearchDomain.newElementKeys(s, after, kind).size, "$kind")
            s = after
        }
        assertEquals(listOf("Wake", "Wake copy"), s.alarms.map { it.label })
        assertEquals(listOf("Tea", "Tea copy"), s.timers.map { it.label })
        assertNull(s.timers.last().endsAtMillis, "a copy is not a second run")
        assertEquals(listOf("Run", "Run copy"), s.chronos.map { it.label })
        assertTrue(!s.chronos.last().running)
        assertEquals(listOf("Water", "Water copy"), s.chores.map { it.title })
        assertTrue(s.chores.last().id.isNotBlank() && s.chores.last().id != "reminder-0")
    }
}
