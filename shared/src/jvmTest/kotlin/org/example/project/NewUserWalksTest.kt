package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import org.example.project.scheduler.domain.SearchDomain
import org.example.project.scheduler.domain.SearchDomain.Kind
import org.example.project.scheduler.domain.SearchDomain.Setting
import org.example.project.scheduler.domain.SearchDomain.SortKey
import org.example.project.scheduler.domain.SearchDomain.SortMethod
import org.example.project.scheduler.model.AlarmEntry
import org.example.project.scheduler.state.ExternalDelta
import org.example.project.scheduler.state.ExternalKeys
import org.example.project.scheduler.state.HistoryCategory
import org.example.project.scheduler.state.HistoryUnit
import org.example.project.scheduler.state.SchedulerHistory
import org.example.project.scheduler.state.SchedulerState

/**
 * User 2026-11-28: *"I want proper tree architectures to ensure very intuitive navigation for a new user … think of any
 * random search the user wants to do"*, then *"start walks"*. **A batch of requests a new user makes, one or more per
 * top category of the ▾ tree, each with the categories they hover in order and the configuration they set**
 * (`docs/new-user-walks.md` tells each walk's thinking). A walk that stops leading where it led fails here; a request
 * the tree cannot answer is fixed in the tree, then added here.
 */
class NewUserWalksTest {
    /** One request: the user's words, the categories hovered from the ▾'s root, the configuration set there. */
    private data class Walk(val request: String, val path: List<String>, val setting: Setting)

    private val walks = listOf(
        // Find by text
        Walk("tasks whose description mentions the dentist", listOf("Find by text", "Where to search"), Setting.SearchInDescription),
        Walk("the word run, not running", listOf("Find by text", "Matching"), Setting.WholeWord),
        Walk("tasks with gym or sport in them", listOf("Find by text"), Setting.SearchBars),
        Walk("tasks whose subtasks say buy", listOf("Find by text", "Where to search", "In children"), Setting.SearchInChildren),
        // Which elements
        Walk("only the alarms and the timers", listOf("Which elements"), Setting.Types),
        Walk("everything but one task", listOf("Which elements", "Include & exclude"), Setting.ExcludedElements),
        Walk("what I changed yesterday", listOf("Which elements", "Date & calendar"), Setting.DatedFrom),
        Walk("what is on my calendar tomorrow at 3 pm", listOf("Which elements", "Date & calendar"), Setting.CalendarAt),
        Walk("the tasks with no parent task", listOf("Which elements", "By parent"), Setting.NoParentOfKinds),
        // Order
        Walk("everything by name", listOf("Order"), Setting.SortResults),
        Walk("the alarms by their time, looked for under Order", listOf("Order"), Setting.SortResults),
        Walk("the alarms by their time, looked for under Alarms", listOf("Alarms, timers & alerts", "Alarms"), Setting.AlarmSort),
        // Display
        Walk("this window bigger when it opens", listOf("Display"), Setting.Size),
        Walk("the results opened on their children", listOf("Display"), Setting.ExpandResults),
        // Tasks & targets
        Walk("the tasks I deleted", listOf("Tasks & targets", "Tasks"), Setting.TaskInTree),
        Walk("the tasks the scheduler can place", listOf("Tasks & targets", "Tasks"), Setting.TaskSchedulable),
        Walk("the tasks of one category", listOf("Tasks & targets", "Tasks"), Setting.TaskCategory),
        // Calendar
        Walk("the restrictive periods I made myself", listOf("Calendar", "Restrictive periods"), Setting.PeriodOriginSetting),
        Walk("the periods never put on the calendar", listOf("Calendar", "Restrictive periods"), Setting.PeriodOnCalendar),
        // Alarms, timers & alerts
        Walk("the alarms that are off", listOf("Alarms, timers & alerts", "Alarms"), Setting.AlarmStateSetting),
        Walk("the alarms ringing at the weekend", listOf("Alarms, timers & alerts", "Alarms"), Setting.AlarmDays),
        Walk("the timers running now", listOf("Alarms, timers & alerts", "Timers"), Setting.TimerStateSetting),
        Walk("the repeating reminders", listOf("Alarms, timers & alerts", "Reminders"), Setting.ReminderRepeatsSetting),
        Walk("the screen-break notifications", listOf("Alarms, timers & alerts", "Notifications"), Setting.NotificationSourceSetting),
        Walk("the notifications of the last 7 days", listOf("Alarms, timers & alerts", "Notifications"), Setting.DatedFrom),
        // App & history
        Walk("the history units about a window opening or closing", listOf("App & history", "History"), Setting.HistoryWindowChangeSetting),
        Walk("the same, looked for under Windows", listOf("App & history", "Windows"), Setting.HistoryWindowChangeSetting),
        Walk("the history, newest first", listOf("App & history", "History"), Setting.HistorySort),
        Walk("what I undid", listOf("App & history", "History"), Setting.HistoryUndoneSetting),
        Walk("every change to my alarm Wake up", listOf("App & history", "History"), Setting.HistoryChangedElementSetting),
        Walk("yesterday's history", listOf("App & history", "History"), Setting.DatedFrom),
        Walk("the windows minimized now", listOf("App & history", "Windows"), Setting.WindowStatusSetting),
        Walk("the keyboard shortcuts I changed", listOf("App & history", "Keyboard shortcuts"), Setting.ShortcutReboundSetting),
        // Other pages
        Walk("my Claude prompts in one project", listOf("Other pages", "Claude Code logs", "Prompts"), Setting.PromptProject),
    )

    /** The configurations at [path] in the ▾ tree as the default Search window draws it; null where a category is missing. */
    private fun leavesAt(path: List<String>): List<Setting>? {
        var nodes: List<SearchDomain.ConfigurationNode> = SearchDomain.configurationTree(SearchDomain.pageDefaultConfig("scheduler"))
        for (label in path) {
            nodes = nodes.filterIsInstance<SearchDomain.ConfigurationCategory>().firstOrNull { it.label == label }?.children ?: return null
        }
        return nodes.filterIsInstance<SearchDomain.ConfigurationLeaf>().map { it.setting }
    }

    @Test
    fun every_walk_reaches_its_configuration_in_at_most_three_hovers() {
        val broken = walks.filter { leavesAt(it.path)?.contains(it.setting) != true }
        assertEquals(emptyList(), broken.map { "${it.request}: ${it.path.joinToString(" › ")} › ${it.setting.label}" })
        assertTrue(walks.all { it.path.size <= 3 }, "a walk longer than three hovers")
    }

    /** Every top category of the tree has at least one walk: the batch covers the tree. */
    @Test
    fun the_batch_walks_every_top_category() {
        val tops = SearchDomain.CONFIGURATION_LAYOUT.map { it.label }
        assertEquals(emptyList(), tops.filter { top -> walks.none { it.path.first() == top } })
    }

    // The requests the batch found unanswered, now answered — each by what the list holds.

    private val zone = TimeZone.UTC

    private fun at(y: Int, m: Int, d: Int, h: Int = 12): Long = LocalDateTime(y, m, d, h, 0).toInstant(zone).toEpochMilliseconds()

    /** "What I changed yesterday": "Dated from" › yesterday — not "Dated on"'s weekday, which every other week shares. */
    @Test
    fun what_i_changed_yesterday() {
        fun unit(time: Long) = HistoryUnit(timeMillis = time, delta = ExternalDelta(ExternalKeys.TAB_ORDER, "a", "b", "Tabs"), deviceId = "desk", deviceSeq = time)
        val now = at(2026, 11, 28, 9)
        val units = listOf(unit(at(2026, 11, 20)), unit(at(2026, 11, 27, 0)), unit(at(2026, 11, 27, 23)), unit(at(2026, 11, 28, 8)))
        val state = SchedulerState.empty().let { s -> s.copy(histories = s.histories.withCategory(HistoryCategory.Main, SchedulerHistory(pointer = units.size - 1, units = units))) }
        val (from, until) = SearchDomain.datedRangeOf(SearchDomain.DatedRange.Yesterday, now, zone)
        assertEquals(at(2026, 11, 27, 0) to at(2026, 11, 28, 0) - 1, from to until)
        val config = SearchDomain.pageDefaultConfig("scheduler").let { it.copy(filters = it.filters.copy(datedFrom = from, datedUntil = until)) }
        val found = SearchDomain.results(state, config.searchedKinds, "", filters = config.filters, sorts = config.sorts, timeZone = zone)
        assertEquals(listOf(units[1], units[2]).map { Kind.HistoryUnit.name + "/" + SearchDomain.historyUnitId(HistoryCategory.Main, it) }.toSet(), found.map(SearchDomain::keyOf).toSet())
        // The last 7 days end today; today alone is today.
        assertEquals(at(2026, 11, 22, 0), SearchDomain.datedRangeOf(SearchDomain.DatedRange.Last7Days, now, zone).first)
        assertEquals(at(2026, 11, 28, 0), SearchDomain.datedRangeOf(SearchDomain.DatedRange.Today, now, zone).first)
        // Stored and read back; an older build's shape reads as no bound.
        assertEquals(from to until, SearchDomain.Config.decode(config.encode())!!.filters.let { it.datedFrom to it.datedUntil })
        val old = SearchDomain.Config.decode(SearchDomain.Config(kinds = setOf(Kind.Task)).encode().replace(Regex(""","?dated(From|Until)Millis":(null|-?\d+)"""), ""))!!.filters
        assertEquals(null to null, old.datedFrom to old.datedUntil)
    }

    /** "The alarms by their time", looked for under Order: the whole list's Sort by offers "alarm: time". */
    @Test
    fun the_alarms_by_their_time_from_order() {
        val method = SearchDomain.sortMethodsOffered(null).single { it.key == SortKey.AlarmTime }
        assertEquals(SortMethod(Kind.Alarm, SortKey.AlarmTime), method)
        assertEquals("alarm: time", method.label)
        assertEquals(true, SearchDomain.sortMethodsOffered(Kind.Alarm).none { it.kind == null && it.key == SortKey.AlarmTime })
    }

    /** The ▾'s bar finds the new rows by the request's words. */
    @Test
    fun the_arrow_bar_finds_the_batch_fixes_by_their_words() {
        val tree = SearchDomain.configurationTree(SearchDomain.Config(kinds = Kind.entries.toSet()))
        val rows = SearchDomain.ConfigurationTreeRows(tree)
        fun typed(q: String) = SearchDomain.shownConfigurationNodes(SchedulerState.empty(), tree, root = true, rows, SearchDomain.CONFIGURATION_SEARCH.copy(query = q))
            .filterIsInstance<SearchDomain.ConfigurationLeaf>().map { it.setting }.toSet()
        assertTrue(Setting.DatedFrom in typed("yesterday"), typed("yesterday").toString())
        assertTrue(Setting.DatedFrom in typed("last 7 days"))
        assertTrue(Setting.SortResults in typed("sort by time"))
        assertTrue(Setting.PeriodOriginSetting in typed("yours"))
    }

    /** A mixed list with "alarm: time" from Order: the alarms ordered by time among the places they hold. */
    @Test
    fun alarm_time_set_from_order_orders_the_alarms() {
        val state = SchedulerState.empty().copy(
            alarms = listOf(
                AlarmEntry(id = "a", label = "Late", timeOfDayMinutes = 22 * 60),
                AlarmEntry(id = "b", label = "Early", timeOfDayMinutes = 6 * 60),
            ),
        )
        val config = SearchDomain.Config(kinds = setOf(Kind.Alarm), sorts = listOf(SortMethod(Kind.Alarm, SortKey.AlarmTime)))
        val found = SearchDomain.results(state, config.searchedKinds, "", filters = config.filters, sorts = config.sorts, timeZone = zone)
            .filter { it.kind == Kind.Alarm }.map { (it as SearchDomain.ItemResult).id }
        assertEquals(listOf("b", "a"), found)
    }
}
