package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import org.example.project.scheduler.domain.PeriodKinds
import org.example.project.scheduler.domain.SearchDomain
import org.example.project.scheduler.model.AlarmEntry
import org.example.project.scheduler.model.CellId
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.model.TaskPanel
import org.example.project.scheduler.model.TaskTimeRange
import org.example.project.scheduler.state.HistoryCategory
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState

/**
 * PRD §7 *Search*, user spec 2026-09-27: the Search window's **added elements** (check boxes, Add, the list on
 * the right), the actions that act on every added element at once, and the **calendar filters** of the
 * Configuration Search window.
 */
class SearchAddedElementsTest {

    private fun r(state: SchedulerState, intent: SchedulerIntent) = SchedulerReducer.reduce(state, intent)

    private fun freeRootCell(state: SchedulerState): CellId = state.lists[state.rootListId]!!.cellIds.last()

    private fun taskWithTitle(state: SchedulerState, title: String): TaskId = state.tasks.values.first { it.title == title }.id

    private fun units(state: SchedulerState): Int = HistoryCategory.entries.sumOf { state.histories.forCategory(it).units.size }

    /** Two top-level tasks, Apple and Banana, and a category "Home". */
    private fun account(): SchedulerState {
        var s = SchedulerState.empty()
        s = r(s, SchedulerIntent.SetCellTitle(freeRootCell(s), "Apple"))
        s = r(s, SchedulerIntent.SetCellTitle(freeRootCell(s), "Banana"))
        return r(s, SchedulerIntent.CreateCategory("Home"))
    }

    private fun millis(day: String): Long = LocalDate.parse(day).atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds()

    private fun panel(id: String, taskId: TaskId?, from: String, hours: Int = 1): TaskPanel =
        TaskPanel(id, taskId, "", millis(from) + 9 * HOUR, millis(from) + (9 + hours) * HOUR)

    // ----- The added list ---------------------------------------------------------------------------

    @Test
    fun an_added_key_resolves_to_its_row_in_the_order_added_and_a_gone_one_lists_nothing() {
        val s = account().copy(alarms = listOf(AlarmEntry(id = "alarm-1", label = "Wake")))
        val apple = taskWithTitle(s, "Apple")
        val keys =
            SearchDomain.withAdded(
                emptyList(),
                listOf("Alarm/alarm-1", "Task/" + apple.value, "Alarm/alarm-1", "Alarm/gone"),
            )
        assertEquals(listOf("Alarm/alarm-1", "Task/" + apple.value, "Alarm/gone"), keys, "each key once, first place kept")
        val rows = SearchDomain.resolve(s, keys)
        assertEquals(listOf("Wake", "Apple"), rows.map { it.name })
        assertEquals(keys.take(2), rows.map(SearchDomain::keyOf))
    }

    @Test
    fun the_added_list_and_the_calendar_filters_survive_their_local_encoding() {
        val config =
            SearchDomain.Config(
                filters = SearchDomain.Filters(
                    taskOnCalendar = SearchDomain.Tri.Yes,
                    taskBoxesFrom = LocalDate(2026, 9, 1),
                    taskBoxesUntil = LocalDate(2026, 9, 30),
                    periodOnCalendar = SearchDomain.Tri.No,
                    periodBoxesFrom = LocalDate(2026, 1, 2),
                    periodBoxesUntil = LocalDate(2026, 12, 31),
                ),
                added = listOf("Task/t1", "Alarm/alarm-1"),
            )
        assertEquals(config, SearchDomain.Config.decode(config.encode()))
    }

    @Test
    fun a_configuration_stored_before_the_added_list_reads_as_none_added_and_no_calendar_filter() {
        // What the build before 2026-09-27 wrote: none of the new fields.
        val config = SearchDomain.Config.decode("""{"query":"x","kinds":["Task"],"taskInTree":"Yes"}""")!!
        assertEquals(emptyList(), config.added)
        assertEquals(SearchDomain.Tri.Any, config.filters.taskOnCalendar)
        assertEquals(null, config.filters.taskBoxesFrom)
        assertEquals(SearchDomain.Tri.Yes, config.filters.taskInTree)
        assertEquals(0, config.filters.activeCount - 1, "only the stored filter is on")
        // A day that does not parse is "any", not a failed configuration.
        assertEquals(null, SearchDomain.Config.decode("""{"taskBoxesFrom":"someday"}""")!!.filters.taskBoxesFrom)
    }

    // ----- The calendar filters ---------------------------------------------------------------------

    private fun taskRows(s: SchedulerState, filters: SearchDomain.Filters): List<String> =
        SearchDomain.results(s, setOf(SearchDomain.Kind.Task), "", filters = filters, timeZone = TimeZone.UTC).map { it.name }

    @Test
    fun a_task_is_on_the_calendar_by_a_panel_placed_for_it_or_by_a_banked_record() {
        var s = account()
        val apple = taskWithTitle(s, "Apple")
        val banana = taskWithTitle(s, "Banana")
        s = s.copy(panels = listOf(panel("p1", apple, "2026-09-10")))
        val on = SearchDomain.Filters(taskOnCalendar = SearchDomain.Tri.Yes)
        assertEquals(listOf("Apple"), taskRows(s, on))
        assertEquals(listOf("Banana"), taskRows(s, on.copy(taskOnCalendar = SearchDomain.Tri.No)))
        val record = TaskTimeRange(millis("2026-08-01"), millis("2026-08-01") + HOUR)
        s = s.copy(tasks = s.tasks + (banana to s.tasks.getValue(banana).copy(record = listOf(record))))
        assertEquals(listOf("Apple", "Banana"), taskRows(s, on))
    }

    @Test
    fun a_day_bound_keeps_a_task_only_when_every_one_of_its_boxes_is_within_it() {
        var s = account()
        val apple = taskWithTitle(s, "Apple")
        val banana = taskWithTitle(s, "Banana")
        s = s.copy(
            panels = listOf(
                panel("a1", apple, "2026-09-10"),
                panel("a2", apple, "2026-09-20"),
                panel("b1", banana, "2026-09-05"),
                panel("b2", banana, "2026-09-25"),
            ),
        )
        assertEquals(listOf("Apple"), taskRows(s, SearchDomain.Filters(taskBoxesFrom = LocalDate(2026, 9, 10))))
        assertEquals(emptyList(), taskRows(s, SearchDomain.Filters(taskBoxesFrom = LocalDate(2026, 9, 11))))
        // "Until" includes the whole of its day.
        assertEquals(listOf("Apple"), taskRows(s, SearchDomain.Filters(taskBoxesUntil = LocalDate(2026, 9, 20))))
        assertEquals(
            listOf("Apple", "Banana"),
            taskRows(s, SearchDomain.Filters(taskBoxesFrom = LocalDate(2026, 9, 1), taskBoxesUntil = LocalDate(2026, 9, 30))),
        )
        // A task with no box at all is not "every box after that day".
        s = s.copy(panels = s.panels.filter { it.taskId == apple })
        assertEquals(listOf("Apple"), taskRows(s, SearchDomain.Filters(taskBoxesFrom = LocalDate(2026, 1, 1))))
    }

    @Test
    fun a_restrictive_period_kind_is_on_the_calendar_by_a_period_of_that_kind() {
        val s = account().copy(panels = listOf(panel("n1", null, "2026-09-10").copy(noScreen = true)))
        fun rows(filters: SearchDomain.Filters) =
            SearchDomain.results(s, setOf(SearchDomain.Kind.RestrictivePeriod), "", filters = filters, timeZone = TimeZone.UTC)
                .map { (it as SearchDomain.ItemResult).id }
        val on = rows(SearchDomain.Filters(periodOnCalendar = SearchDomain.Tri.Yes))
        assertEquals(listOf(PeriodKinds.NO_SCREEN), on)
        assertTrue(PeriodKinds.NO_SCREEN !in rows(SearchDomain.Filters(periodOnCalendar = SearchDomain.Tri.No)))
        assertEquals(on, rows(SearchDomain.Filters(periodBoxesFrom = LocalDate(2026, 9, 10))))
        assertEquals(emptyList(), rows(SearchDomain.Filters(periodBoxesUntil = LocalDate(2026, 9, 9))))
    }

    // ----- The actions on the added elements -------------------------------------------------------

    @Test
    fun a_category_given_to_every_added_task_is_one_undo_unit_and_a_repeat_records_nothing() {
        var s = account()
        val home = s.categories.single().id
        val added = SearchDomain.resolve(s, listOf("Task/" + taskWithTitle(s, "Apple").value, "Task/" + taskWithTitle(s, "Banana").value))
        val intents = SearchDomain.addedIntents(s, added, SearchDomain.AddedCommand.Category(home, carried = true), 0L)
        assertEquals(1, intents.size, "one intent for every task")
        val before = units(s)
        s = intents.fold(s, ::r)
        assertTrue(s.tasks.values.filter { it.title == "Apple" || it.title == "Banana" }.all { home in it.categoryIds })
        assertEquals(before + 1, units(s))
        val again = SearchDomain.addedIntents(s, added, SearchDomain.AddedCommand.Category(home, carried = true), 0L).fold(s, ::r)
        assertEquals(units(s), units(again), "nothing to change: no unit")
        s = SearchDomain.addedIntents(s, added, SearchDomain.AddedCommand.Category(home, carried = false), 0L).fold(s, ::r)
        assertTrue(s.tasks.values.none { home in it.categoryIds })
    }

    @Test
    fun a_minimum_time_given_to_every_added_task_is_one_undo_unit() {
        var s = account()
        val added = SearchDomain.resolve(s, listOf("Task/" + taskWithTitle(s, "Apple").value, "Task/" + taskWithTitle(s, "Banana").value))
        val before = units(s)
        s = SearchDomain.addedIntents(s, added, SearchDomain.AddedCommand.MinimumTime(25), 0L).fold(s, ::r)
        assertEquals(listOf(25, 25), listOf("Apple", "Banana").map { s.tasks.getValue(taskWithTitle(s, it)).minimumMinutes })
        assertEquals(before + 1, units(s))
    }

    @Test
    fun the_alarms_switch_acts_on_the_added_alarms_only_through_the_rows_own_list() {
        val s = account().copy(
            alarms = listOf(AlarmEntry(id = "a", label = "A", enabled = false), AlarmEntry(id = "b", label = "B", enabled = false)),
        )
        val added = SearchDomain.resolve(s, listOf("Alarm/a"))
        val intents = SearchDomain.addedIntents(s, added, SearchDomain.AddedCommand.AlarmsOn(true), 0L)
        val after = intents.fold(s, ::r)
        assertEquals(listOf(true, false), after.alarms.map { it.enabled })
        assertEquals(emptyList(), SearchDomain.addedIntents(after, added, SearchDomain.AddedCommand.AlarmsOn(true), 0L))
    }

    @Test
    fun set_to_the_current_time_puts_the_added_alarms_and_reminders_on_the_clocks_minute() {
        val reminder = org.example.project.scheduler.model.ChoreEntry(title = "Water", spanDays = 1.0, timeOfDayMinutes = 60, id = "reminder-1")
        val other = reminder.copy(title = "Bins", id = "reminder-2")
        val s = account().copy(
            alarms = listOf(AlarmEntry(id = "a", label = "A", timeOfDayMinutes = 60), AlarmEntry(id = "b", label = "B", timeOfDayMinutes = 60)),
            chores = listOf(reminder, other),
        )
        val now = millis("2026-10-02") + 14 * HOUR + 37 * 60_000L + 42_000L
        val added = SearchDomain.resolve(s, listOf("Alarm/a", "Reminder/reminder-1"))
        val alarms = SearchDomain.addedIntents(s, added, SearchDomain.AddedCommand.AlarmsTimeNow, now, TimeZone.UTC)
        val reminders = SearchDomain.addedIntents(s, added, SearchDomain.AddedCommand.RemindersTimeNow, now, TimeZone.UTC)
        val after = (alarms + reminders).fold(s, ::r)
        assertEquals(listOf(14 * 60 + 37, 60), after.alarms.map { it.timeOfDayMinutes }, "the added alarm only, to the minute")
        assertEquals(listOf(14 * 60 + 37, 60), after.chores.map { it.timeOfDayMinutes }, "the added reminder only")
        // Already there: nothing to change, no intent.
        assertEquals(emptyList(), SearchDomain.addedIntents(after, added, SearchDomain.AddedCommand.AlarmsTimeNow, now, TimeZone.UTC))
        assertEquals(emptyList(), SearchDomain.addedIntents(after, added, SearchDomain.AddedCommand.RemindersTimeNow, now, TimeZone.UTC))
    }

    // ----- Reset of the default periods (user rule 2026-10-01) -------------------------------------

    private fun periodKeys(state: SchedulerState, vararg kinds: String): List<String> =
        SearchDomain.itemResults(state, SearchDomain.Kind.RestrictivePeriod, "")
            .filter { it.id in kinds }
            .map(SearchDomain::keyOf)

    @Test
    fun reset_puts_an_added_default_period_back_as_the_app_ships_it_and_leaves_the_others() {
        var s = r(account(), SchedulerIntent.AddPeriodKind("deep work"))
        val shipped = s.periodCombinations
        s = r(s, SchedulerIntent.SetPeriodDrawing(PeriodKinds.NO_SCREEN, org.example.project.scheduler.domain.PeriodDrawing.Crosses))
        s = r(s, SchedulerIntent.SetPeriodDrawing(PeriodKinds.SLEEP, org.example.project.scheduler.domain.PeriodDrawing.Crosses))
        val layers = s.periodCombinations.first { it.id == PeriodKinds.LAYERS_RULE.id }
        val mine = PeriodKinds.companionRule(PeriodKinds.NO_SCREEN, setOf(PeriodKinds.INACTIVITY)).copy(id = "combination-1")
        val withDeepWork = PeriodKinds.companionRule("deep work", setOf(PeriodKinds.NO_SCREEN)).copy(id = "combination-2")
        s = r(
            s,
            SchedulerIntent.SetPeriodCombinations(
                // "layers" edited until it no longer names "no screen", the no-screen-layers rule deleted, and two of the
                // user's own: one about default periods only, one that also names their own kind.
                s.periodCombinations
                    .filterNot { it.id == PeriodKinds.NO_SCREEN_LAYERS_RULE.id }
                    .map { if (it.id == layers.id) it.copy(then = org.example.project.scheduler.domain.PeriodFormula.of(setOf(PeriodKinds.SLEEP))) else it } +
                    mine + withDeepWork,
            ),
        )

        val added = SearchDomain.resolve(s, periodKeys(s, PeriodKinds.NO_SCREEN, "deep work"))
        assertEquals(listOf(PeriodKinds.NO_SCREEN), SearchDomain.modifiedDefaultPeriods(s, added), "your own kind has no default")
        val after = SearchDomain.addedIntents(s, added, SearchDomain.AddedCommand.ResetDefaultPeriods, 0L).fold(s, ::r)

        assertEquals(PeriodKinds.defaultStyle(PeriodKinds.NO_SCREEN), after.periodKindConfig.style(PeriodKinds.NO_SCREEN))
        assertEquals(org.example.project.scheduler.domain.PeriodDrawing.Crosses, after.periodKindConfig.drawing(PeriodKinds.SLEEP), "not added: untouched")
        assertEquals(
            shipped.filterNot { it.id == PeriodKinds.NO_SCREEN_LAYERS_RULE.id } + withDeepWork + PeriodKinds.NO_SCREEN_LAYERS_RULE,
            after.periodCombinations,
            "the edited default rule is found by its id, the deleted one comes back, the user's rule of default periods goes, " +
                "the one naming their own kind stays",
        )
        assertEquals(emptyList(), SearchDomain.modifiedDefaultPeriods(after, added), "nothing left to reset: the button greys")
        assertEquals(emptyList(), SearchDomain.addedIntents(after, added, SearchDomain.AddedCommand.ResetDefaultPeriods, 0L))
        assertEquals(units(s) + 1, units(after), "one History Unit (2026-10-01: an account setting is one too)")
        assertEquals(s.periodKindConfig.drawing(PeriodKinds.NO_SCREEN), r(after, SchedulerIntent.Undo).periodKindConfig.drawing(PeriodKinds.NO_SCREEN))
    }

    @Test
    fun the_default_periods_filter_keeps_the_shipped_kinds_or_the_accounts_own() {
        val s = r(account(), SchedulerIntent.AddPeriodKind("deep work"))
        val stored = SearchDomain.Config.decode(
            SearchDomain.Config(filters = SearchDomain.Filters(periodOrigin = SearchDomain.PeriodOrigin.BuiltIn)).encode(),
        )
        assertEquals(SearchDomain.PeriodOrigin.BuiltIn, stored?.filters?.periodOrigin)
        assertEquals("Default periods", SearchDomain.Setting.PeriodOriginSetting.label)
        fun ids(origin: SearchDomain.PeriodOrigin) =
            SearchDomain.results(s, setOf(SearchDomain.Kind.RestrictivePeriod), "", filters = SearchDomain.Filters(periodOrigin = origin))
                .map { (it as SearchDomain.ItemResult).id }
        assertEquals(listOf("deep work"), ids(SearchDomain.PeriodOrigin.Yours))
        assertTrue(PeriodKinds.NO_SCREEN in ids(SearchDomain.PeriodOrigin.BuiltIn) && "deep work" !in ids(SearchDomain.PeriodOrigin.BuiltIn))
    }

    @Test
    fun the_actions_window_lists_the_general_actions_then_only_the_kinds_asked_for() {
        val all = SearchDomain.addedActions("", SearchDomain.Kind.entries.toSet(), setOf(SearchDomain.Kind.Alarm))
        assertEquals(listOf(null, SearchDomain.Kind.Alarm), all.map { it.first })
        assertEquals(
            listOf(SearchDomain.AddedAction.TaskMinimumTime),
            SearchDomain.addedActions("minimum", SearchDomain.Kind.entries.toSet()).flatMap { it.second },
        )
    }

    private companion object {
        const val HOUR: Long = 3_600_000L
    }
}
