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

    /**
     * User rule 2026-10-10: *"when there are several added elements, there should be the action groups that include
     * all the actions that can be applied to all the elements, then a group for less elements and so on… when there are
     * only two quota elements, there must be three groups: the one for both quota (remove them from the list, default
     * configurations for a quota element etc…), the one for the first quota in the list (progression, title etc…) and
     * the one for the second quota"* — and one element alone: *"the first displayed thing should be the progression
     * percentage, then the title and maybe after the 'new' button… there shouldn't be the 'Remove all' button"*.
     */
    @Test
    fun the_actions_are_grouped_from_every_element_down_to_each_one() {
        fun quota(id: String, title: String) = SearchDomain.ItemResult(SearchDomain.Kind.Quota, id, title, "")
        fun alarm(id: String) = SearchDomain.ItemResult(SearchDomain.Kind.Alarm, id, "Wake", "")
        fun groups(added: List<SearchDomain.Result>) =
            SearchDomain.actionGroups(
                SearchDomain.actionsFor(
                    SearchDomain.addedActions("", SearchDomain.Kind.entries.toSet(), SearchDomain.actionKindsOf(added)), added,
                ),
                added,
            )
        val a = quota("q1", "Sport")
        val b = quota("q2", "Reading")

        // Two quotas: both, then each.
        val two = groups(listOf(a, b))
        assertEquals(listOf(listOf(a, b), listOf(a), listOf(b)), two.map { it.members })
        assertEquals(listOf(false, true, true), two.map { it.element })
        assertEquals(listOf("Quota  ·  2", "Quota: Sport", "Quota: Reading"), two.map { it.title })
        assertTrue(two[0].actions.containsAll(listOf(SearchDomain.AddedAction.ClearList, SearchDomain.AddedAction.OpenEach, SearchDomain.AddedAction.QuotaNew, SearchDomain.AddedAction.QuotaDuplicate, SearchDomain.AddedAction.QuotaDelete)))
        // "There must also be a title field to rename every added quota elements at the same time, among other
        // things": the fields that write to both — but not where each one stands, which is each one's.
        assertTrue(two[0].actions.containsAll(listOf(SearchDomain.AddedAction.QuotaTitle, SearchDomain.AddedAction.QuotaAmount, SearchDomain.AddedAction.QuotaLoop)))
        assertTrue(two[0].actions.none { it in SearchDomain.PER_ELEMENT_ACTIONS })
        assertTrue(two[0].actions.indexOf(SearchDomain.AddedAction.QuotaTitle) > two[0].actions.indexOf(SearchDomain.AddedAction.QuotaDelete))
        // "The first group should have the configurations for the default configurations of the quota elements".
        val defaults = two[0].inner.single()
        assertEquals("Default configuration of a new quota", defaults.title)
        assertEquals(SearchDomain.Kind.Creation to SearchDomain.Kind.Quota.name, (defaults.members.single() as SearchDomain.ItemResult).let { it.kind to it.id })
        assertEquals(
            listOf(SearchDomain.AddedAction.QuotaTitle, SearchDomain.AddedAction.QuotaAmount, SearchDomain.AddedAction.QuotaLoop, SearchDomain.AddedAction.QuotaRestarts, SearchDomain.AddedAction.QuotaResilience),
            defaults.actions,
        )
        assertTrue(two.drop(1).all { it.inner.isEmpty() })
        for (own in two.drop(1)) {
            assertEquals(listOf(SearchDomain.AddedAction.QuotaProgress, SearchDomain.AddedAction.QuotaTitle), own.actions.take(2), "where it stands, then what it is called")
            assertTrue(SearchDomain.AddedAction.QuotaAmount in own.actions && SearchDomain.AddedAction.QuotaLoop in own.actions)
            assertTrue(own.actions.none(SearchDomain::isSetAction), "what is about both is said once, in their group")
        }
        // Nothing is lost.
        val listed =
            SearchDomain.actionsFor(SearchDomain.addedActions("", SearchDomain.Kind.entries.toSet(), setOf(SearchDomain.Kind.Quota)), listOf(a, b))
                .flatMap { it.second }
        assertEquals(listed.toSet(), (two[0].actions + two[1].actions).toSet())

        // One quota: one group — its progression, its title, its other settings, then "New" and the rest; no
        // "Remove all" (the cross of its row does it) and no "Open each".
        val one = groups(listOf(a)).single()
        assertTrue(one.element)
        assertEquals("Quota: Sport", one.title)
        assertEquals(listOf(SearchDomain.AddedAction.QuotaProgress, SearchDomain.AddedAction.QuotaTitle), one.actions.take(2))
        assertTrue(one.actions.indexOf(SearchDomain.AddedAction.QuotaNew) > one.actions.indexOf(SearchDomain.AddedAction.QuotaLoops), "its own configuration before the buttons about quotas")
        assertTrue(SearchDomain.AddedAction.ClearList !in one.actions && SearchDomain.AddedAction.OpenEach !in one.actions)
        assertTrue(SearchDomain.AddedAction.QuotaDelete in one.actions)

        // Mixed: every element, then the kind holding several of them, then each element — the lone alarm's group
        // holding the alarms' own buttons too.
        val x = alarm("x")
        val mixed = groups(listOf(a, x, b))
        assertEquals(listOf(listOf(a, x, b), listOf(a, b), listOf(a), listOf(x), listOf(b)), mixed.map { it.members })
        assertEquals(listOf("Every element  ·  3", "Quota  ·  2", "Quota: Sport", "Alarm: Wake", "Quota: Reading"), mixed.map { it.title })
        assertTrue(mixed[0].actions.all { it.section == null })
        assertEquals(listOf(SearchDomain.AddedAction.OpenEach, SearchDomain.AddedAction.ClearList), mixed[1].actions.take(2), "the quotas alone can be opened, or taken off the list")
        assertTrue(SearchDomain.AddedAction.QuotaNew in mixed[1].actions && SearchDomain.AddedAction.QuotaNew !in mixed[2].actions)
        assertTrue(SearchDomain.AddedAction.QuotaTitle in mixed[1].actions && mixed[1].inner.size == 1 && mixed[0].inner.isEmpty())
        assertEquals(SearchDomain.AddedAction.AlarmOnOff, mixed[3].actions.first())
        assertTrue(SearchDomain.AddedAction.AlarmNew in mixed[3].actions && SearchDomain.AddedAction.AlarmDelete in mixed[3].actions)

        // A "New quota" creation row beside a quota: its own group edits the default configuration, nothing else.
        val creation = SearchDomain.ItemResult(SearchDomain.Kind.Creation, SearchDomain.Kind.Quota.name, "New quota", "")
        assertTrue(groups(listOf(a, b, creation)).all { it.inner.isEmpty() }, "its creation row is added: the default has its own group")
        val default = groups(listOf(a, creation)).single { it.members == listOf(creation) }
        assertTrue(default.actions.containsAll(listOf(SearchDomain.AddedAction.QuotaTitle, SearchDomain.AddedAction.QuotaAmount, SearchDomain.AddedAction.QuotaLoop)))
        assertTrue(SearchDomain.AddedAction.QuotaProgress !in default.actions && SearchDomain.AddedAction.QuotaLoops !in default.actions)

        // User rule 2026-10-10: each group has its expansion arrow. What is retracted is remembered by what the group
        // IS — an element, a kind, every element, a kind's default configuration — and kept with the window.
        assertEquals(listOf("kind/Quota", "element/Quota/q1", "element/Quota/q2"), two.map { it.id })
        assertEquals("default/Quota", defaults.id)
        assertEquals(listOf("all", "kind/Quota", "element/Quota/q1", "element/Alarm/x", "element/Quota/q2"), mixed.map { it.id })
        val ids = two.mapTo(HashSet()) { it.id }
        val retracted = SearchDomain.withActionGroupToggled(SearchDomain.Config(), "element/Quota/q1", ids)
        assertEquals(setOf("element/Quota/q1"), retracted.collapsedActionGroups)
        assertEquals(retracted, SearchDomain.Config.decode(retracted.encode()))
        // A second press opens it again; a group the section no longer lists is forgotten on the way.
        assertEquals(emptySet(), SearchDomain.withActionGroupToggled(retracted, "element/Quota/q1", ids).collapsedActionGroups)
        assertEquals(setOf("kind/Quota"), SearchDomain.withActionGroupToggled(retracted, "kind/Quota", ids - "element/Quota/q1").collapsedActionGroups)
        // A configuration stored before the arrows: every group open.
        assertEquals(emptySet(), SearchDomain.Config.decode(SearchDomain.Config().encode().replace("\"collapsedActionGroups\":[],", ""))?.collapsedActionGroups)

        // Nothing added (the window of every configuration): one group per kind, as before.
        val sections = SearchDomain.addedActions("", SearchDomain.Kind.entries.toSet())
        assertEquals(sections, SearchDomain.actionGroups(sections, emptyList()).map { it.kind to it.actions })
    }

    @Test
    fun the_groups_of_actions_are_listed_by_how_many_added_elements_they_reach() {
        val s = account().copy(
            alarms = listOf(AlarmEntry(id = "a", label = "Wake"), AlarmEntry(id = "b", label = "Wake"), AlarmEntry(id = "c", label = "Nap")),
        )
        val added = SearchDomain.resolve(s, listOf("Task/" + taskWithTitle(s, "Apple").value, "Alarm/a", "Alarm/b", "Alarm/c"))
        val groups =
            SearchDomain.sortedByReach(
                SearchDomain.addedActions("", SearchDomain.Kind.entries.toSet(), added.mapTo(HashSet()) { it.kind }), added,
            ).map { it.first }
        // Every element (4), then the alarms (3) before the tasks (1) — though "task" is first in the drop-down.
        assertEquals(listOf(null, SearchDomain.Kind.Alarm, SearchDomain.Kind.Task), groups)
    }

    @Test
    fun the_title_field_is_empty_unless_shared_types_into_every_added_one_and_escape_puts_each_back() {
        val s = account().copy(
            alarms = listOf(AlarmEntry(id = "a", label = "Wake"), AlarmEntry(id = "b", label = "Nap"), AlarmEntry(id = "c", label = "Tea")),
        )
        val added = SearchDomain.resolve(s, listOf("Alarm/a", "Alarm/b"))
        val before = SearchDomain.addedTitles(s, added, SearchDomain.Kind.Alarm)
        assertEquals(mapOf("a" to "Wake", "b" to "Nap"), before)
        assertEquals("", SearchDomain.sharedTitle(before), "two titles: the field is empty")
        // Typing: every ADDED alarm takes it, the other one keeps its own.
        val typed = SearchDomain.AddedCommand.Titles(SearchDomain.Kind.Alarm, before.keys.associateWith { "Up" }, "k")
        val after = SearchDomain.addedIntents(s, added, typed, 0L).fold(s, ::r)
        assertEquals(listOf("Up", "Up", "Tea"), after.alarms.map { it.label })
        assertEquals("Up", SearchDomain.sharedTitle(SearchDomain.addedTitles(after, added, SearchDomain.Kind.Alarm)))
        // Escape: each its previous title.
        val restored =
            SearchDomain.addedIntents(after, added, SearchDomain.AddedCommand.Titles(SearchDomain.Kind.Alarm, before, "k"), 0L).fold(after, ::r)
        assertEquals(s.alarms, restored.alarms)
        assertEquals(emptyList(), SearchDomain.addedIntents(restored, added, SearchDomain.AddedCommand.Titles(SearchDomain.Kind.Alarm, before), 0L))
    }

    /** Anomaly 2026-10-02: the alarm group drew one editor PER added alarm; its settings are one field each. */
    @Test
    fun an_alarm_setting_is_one_field_over_every_added_alarm() {
        val monday = kotlinx.datetime.DayOfWeek.MONDAY
        val friday = kotlinx.datetime.DayOfWeek.FRIDAY
        val s = account().copy(
            alarms = listOf(
                AlarmEntry(id = "a", timeOfDayMinutes = 60, days = setOf(monday, friday), soundSeconds = 3),
                AlarmEntry(id = "b", timeOfDayMinutes = 90, days = setOf(monday), soundSeconds = 3),
                AlarmEntry(id = "c", timeOfDayMinutes = 120),
            ),
        )
        val added = SearchDomain.resolve(s, listOf("Alarm/a", "Alarm/b"))
        val alarms = SearchDomain.addedAlarms(s, added)
        assertEquals(listOf("a", "b"), alarms.map { it.id })
        assertEquals(null, SearchDomain.sharedValue(alarms) { it.timeOfDayMinutes }, "two times: the field is empty")
        assertEquals(3, SearchDomain.sharedValue(alarms) { it.soundSeconds })
        // The time typed: both added alarms, one list edit; the third alarm is not touched.
        val intents = SearchDomain.addedIntents(s, added, SearchDomain.AddedCommand.AlarmsEdit("k") { it.copy(timeOfDayMinutes = 7 * 60) }, 0L)
        assertEquals(1, intents.size)
        val after = intents.fold(s, ::r)
        assertEquals(listOf(420, 420, 120), after.alarms.map { it.timeOfDayMinutes })
        assertEquals(emptyList(), SearchDomain.addedIntents(after, added, SearchDomain.AddedCommand.AlarmsEdit { it.copy(timeOfDayMinutes = 420) }, 0L))
        // Days: Monday is lit (both have it). Pressing Friday gives it to both; pressing Monday off would leave "b"
        // with no day, so "b" keeps its own.
        val shown = setOf(monday)
        assertEquals(setOf(monday, friday), SearchDomain.withDaysChange(setOf(monday), shown, shown + friday))
        assertEquals(setOf(friday), SearchDomain.withDaysChange(setOf(monday, friday), shown, emptySet()))
        assertEquals(setOf(monday), SearchDomain.withDaysChange(setOf(monday), shown, emptySet()))
        // The alert: a channel is shown on only when every alarm has it, and a press writes that channel alone.
        val loud = org.example.project.scheduler.model.AlertSettings(sound = true, voice = true)
        val quiet = org.example.project.scheduler.model.AlertSettings(sound = false, voice = true)
        val both = SearchDomain.sharedAlert(listOf(loud, quiet))!!
        assertEquals(false to true, both.sound to both.voice)
        val edited = both.copy(voice = false)
        assertEquals(true to false, SearchDomain.withAlertChange(loud, both, edited).let { it.sound to it.voice })
        assertEquals(false to false, SearchDomain.withAlertChange(quiet, both, edited).let { it.sound to it.voice })
    }

    @Test
    fun the_alarm_groups_bin_deletes_every_added_alarm_as_one_undoable_edit() {
        val s = account().copy(alarms = listOf(AlarmEntry(id = "a"), AlarmEntry(id = "b"), AlarmEntry(id = "c")))
        val added = SearchDomain.resolve(s, listOf("Alarm/a", "Alarm/c"))
        val intents = SearchDomain.addedIntents(s, added, SearchDomain.AddedCommand.Delete(SearchDomain.Kind.Alarm), 0L)
        assertEquals(1, intents.size)
        val after = intents.fold(s, ::r)
        assertEquals(listOf("b"), after.alarms.map { it.id })
        assertEquals(units(s) + 1, units(after), "one History Unit: Undo brings them back")
        assertEquals(emptyList(), SearchDomain.addedIntents(after, added, SearchDomain.AddedCommand.Delete(SearchDomain.Kind.Alarm), 0L))
    }

    /** User rule 2026-10-02: "don't do that just for alarms" — the timers and the reminders, one field for all. */
    @Test
    fun a_timers_and_a_reminders_settings_are_one_field_over_every_added_one_and_each_kind_has_its_bin() {
        val timer = org.example.project.scheduler.model.TimerEntry(id = "t1", durationSeconds = 60)
        val chore = org.example.project.scheduler.model.ChoreEntry(title = "Water", spanDays = 1.0, timeOfDayMinutes = 60, id = "r1")
        var s = account().copy(
            timers = listOf(timer, timer.copy(id = "t2", durationSeconds = 90), timer.copy(id = "t3")),
            chores = listOf(chore, chore.copy(title = "Bins", id = "r2", spanDays = 7.0), chore.copy(title = "Post", id = "r3")),
        )
        val added = SearchDomain.resolve(s, listOf("Timer/t1", "Timer/t2", "Reminder/r1", "Reminder/r2"))
        assertEquals(listOf("t1", "t2"), SearchDomain.addedTimers(s, added).map { it.id })
        assertEquals(null, SearchDomain.sharedValue(SearchDomain.addedTimers(s, added)) { it.durationSeconds })
        // A timer field: every added timer, one list edit.
        val timed = SearchDomain.addedIntents(s, added, SearchDomain.AddedCommand.TimersEdit("k") { it.copy(durationSeconds = 300) }, 0L)
        assertEquals(1, timed.size)
        s = timed.fold(s, ::r)
        assertEquals(listOf(300, 300, 60), s.timers.map { it.durationSeconds })
        // A reminder field: "every 2 weeks" over both added reminders, by the editor's own arithmetic.
        val weeks = org.example.project.scheduler.model.ChoreRecurrenceUnit.Weeks
        val every = SearchDomain.AddedCommand.RemindersEdit { SearchDomain.withReminderEvery(it, "2", weeks) }
        s = SearchDomain.addedIntents(s, added, every, millis("2026-10-02")).fold(s, ::r)
        assertEquals(listOf(14.0, 14.0, 1.0), s.chores.map { it.spanDays })
        assertEquals("2", SearchDomain.sharedValue(SearchDomain.addedReminders(s, added), SearchDomain::reminderEveryText))
        // The bins: the added ones of the kind, and only them.
        s = SearchDomain.addedIntents(s, added, SearchDomain.AddedCommand.Delete(SearchDomain.Kind.Timer), 0L).fold(s, ::r)
        assertEquals(listOf("t3"), s.timers.map { it.id })
        s = SearchDomain.addedIntents(s, added, SearchDomain.AddedCommand.Delete(SearchDomain.Kind.Reminder), millis("2026-10-02")).fold(s, ::r)
        assertEquals(listOf("r3"), s.chores.map { it.id })
        assertEquals(emptyList(), SearchDomain.addedIntents(s, added, SearchDomain.AddedCommand.Delete(SearchDomain.Kind.Timer), 0L))
        // No kind keeps an editor per element for these any more.
        val labels = SearchDomain.AddedAction.entries.filter { it.section in setOf(SearchDomain.Kind.Alarm, SearchDomain.Kind.Timer, SearchDomain.Kind.Chrono, SearchDomain.Kind.Reminder) }.map { it.label }
        assertTrue("Edit" !in labels)
    }

    @Test
    fun remove_the_others_leaves_the_element_alone_and_a_stale_key_removes_nothing() {
        val added = listOf("Alarm/a", "Task/t", "Reminder/r")
        assertEquals(listOf("Task/t"), SearchDomain.keepingOnly(added, setOf("Task/t")))
        assertEquals(added, SearchDomain.keepingOnly(added, setOf("Task/gone")))
        // Over a selection of several: they stay, in the list's order — and "remove" takes exactly them.
        assertEquals(listOf("Alarm/a", "Reminder/r"), SearchDomain.keepingOnly(added, setOf("Reminder/r", "Alarm/a")))
        assertEquals(listOf("Task/t"), SearchDomain.removing(added, setOf("Reminder/r", "Alarm/a")))
        assertEquals(added, SearchDomain.removing(added, setOf("Task/gone")))
    }

    // ----- App settings (user spec 2026-10-02) ----------------------------------------------------

    @Test
    fun the_sound_setting_is_an_app_setting_element_whose_action_is_the_global_volume() {
        var s = account()
        val rows = SearchDomain.itemResults(s, SearchDomain.Kind.AppSetting, "")
        // User rule 2026-10-08: the default voice and notification switches "must then be present in an app setting
        // element" — beside the sound setting, each saying where it stands.
        assertEquals(
            listOf("Notifications" to "on", "Sound setting" to "volume 100 %", "Voice" to "on"),
            rows.map { it.name to it.detail },
        )
        val muted = s.copy(notificationsEnabled = false, notificationVoiceEnabled = false)
        assertEquals(
            listOf("off", "off"),
            SearchDomain.itemResults(muted, SearchDomain.Kind.AppSetting, "").filter { it.name != "Sound setting" }.map { it.detail },
        )
        // Found by name like any row, added and resolved like any element.
        assertEquals(1, SearchDomain.itemResults(s, SearchDomain.Kind.AppSetting, "sound").size)
        val sound = rows.single { it.name == "Sound setting" }
        val added = SearchDomain.resolve(s, listOf(SearchDomain.keyOf(sound)))
        assertTrue(SearchDomain.appSettingAdded(added, SearchDomain.AppSettingEntry.Sound))
        assertTrue(!SearchDomain.appSettingAdded(added, SearchDomain.AppSettingEntry.Voice), "each switch answers to its own element")
        assertTrue(
            SearchDomain.appSettingAdded(SearchDomain.resolve(s, rows.map { SearchDomain.keyOf(it) }), SearchDomain.AppSettingEntry.Notifications),
        )
        assertEquals(
            listOf(SearchDomain.AddedAction.SoundVolume, SearchDomain.AddedAction.VoiceSwitch, SearchDomain.AddedAction.NotificationsSwitch),
            SearchDomain.addedActions("", setOf(SearchDomain.Kind.AppSetting)).single { it.first == SearchDomain.Kind.AppSetting }.second,
        )
        // The slider's write: clamped, and a setting — no Undo/Redo unit.
        val before = units(s)
        s = r(s, SchedulerIntent.SetSoundVolume(0.4))
        assertEquals(0.4, s.soundVolume)
        assertEquals("volume 40 %", SearchDomain.itemResults(s, SearchDomain.Kind.AppSetting, "sound").single().detail)
        assertEquals(1.0, r(s, SchedulerIntent.SetSoundVolume(7.0)).soundVolume)
        assertEquals(0.0, r(s, SchedulerIntent.SetSoundVolume(-1.0)).soundVolume)
        assertEquals(before, units(s))
    }

    @Test
    fun the_global_volume_survives_a_save_and_a_payload_without_it_is_at_full_volume() {
        val saved = org.example.project.scheduler.persistence.SchedulerStateCodec.encode(SchedulerState.empty().copy(soundVolume = 0.25))
        assertEquals(0.25, org.example.project.scheduler.persistence.SchedulerStateCodec.decode(saved)!!.soundVolume)
        // What the build before 2026-10-02 wrote: no such field.
        val legacy = org.example.project.scheduler.persistence.SchedulerStateCodec.decode("""{"rootListId":"list/main","lists":[],"cells":[],"tasks":[]}""")!!
        assertEquals(1.0, legacy.soundVolume)
    }

    @Test
    fun the_volume_scales_16_bit_samples_and_never_writes_to_the_shared_array() {
        val pcm = byteArrayOf(0x00, 0x40, 0x00, 0xC0.toByte()) // 16384, -16384
        val half = org.example.project.scheduler.platform.AppVolume.scaledPcm16Le(pcm, level = 0.5f)
        assertEquals(listOf<Byte>(0x00, 0x20, 0x00, 0xE0.toByte()), half.toList())
        assertEquals(listOf<Byte>(0x00, 0x40, 0x00, 0xC0.toByte()), pcm.toList())
        assertTrue(org.example.project.scheduler.platform.AppVolume.scaledPcm16Le(pcm, level = 1f) === pcm)
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

    /**
     * User rule 2026-10-08: an action of the Search window kept in the lateral menu acts on the elements it was added
     * with — and "if then the button or field can't do anything, for example it is the button 'duplicate' for a task
     * that doesn't exist anymore, then the button is grayed in the left-side menu."
     */
    @Test
    fun an_action_kept_in_the_menu_can_act_while_one_of_its_elements_is_left() {
        var s = account()
        val apple = s.tasks.values.single { it.title == "Apple" }
        val keys = listOf(SearchDomain.taskKey(apple.id))
        assertTrue(SearchDomain.actionCanAct(s, SearchDomain.AddedAction.TaskDuplicate, keys))
        assertTrue(SearchDomain.actionCanAct(s, SearchDomain.AddedAction.OpenEach, keys), "an action on every element")
        assertTrue(!SearchDomain.actionCanAct(s, SearchDomain.AddedAction.AlarmDuplicate, keys), "no alarm among them")
        assertTrue(!SearchDomain.actionCanAct(s, SearchDomain.AddedAction.TaskDuplicate, emptyList()))
        // The task is deleted: nothing is left for the action to act on.
        val cell = s.cells.values.single { it.taskId == apple.id }.id
        s = r(s, SchedulerIntent.SetCellTitle(cell, ""))
        assertTrue(s.tasks[apple.id] == null || SearchDomain.resolve(s, keys).isEmpty(), "the task is gone from the account")
        assertTrue(!SearchDomain.actionCanAct(s, SearchDomain.AddedAction.TaskDuplicate, keys))
        assertTrue(!SearchDomain.actionCanAct(s, SearchDomain.AddedAction.OpenEach, keys))
    }
}
