package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.datetime.TimeZone
import org.example.project.scheduler.domain.CalendarElements
import org.example.project.scheduler.domain.PeriodKinds
import org.example.project.scheduler.domain.SearchDomain
import org.example.project.scheduler.model.AlarmEntry
import org.example.project.scheduler.model.CellId
import org.example.project.scheduler.model.ChoreEntry
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.model.TaskPanel
import org.example.project.scheduler.model.TimerEntry
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState
import org.example.project.ui.TIME_NUDGE_MINUTES
import org.example.project.ui.draftAfterStoreChange
import org.example.project.ui.nudgedTimeOfDay
import org.example.project.ui.timeNudgeLabel
import org.example.project.ui.timeOfDayOf
import org.example.project.ui.timeOfDayText

/**
 * User rule 2026-10-05: the actions of an element that can be on the calendar hold the START and the END "Add to the
 * calendar" lays it at — said as a quota's loop is — and a button to the Search window of its **blue outlined blocks**:
 * what a hand placed on the calendar for it ([SearchDomain.Kind.CalendarBlock]).
 */
class CalendarBlocksSearchTest {
    private fun r(state: SchedulerState, intent: SchedulerIntent) = SchedulerReducer.reduce(state, intent)

    private fun freeRootCell(state: SchedulerState): CellId = state.lists[state.rootListId]!!.cellIds.last()

    private fun taskWithTitle(state: SchedulerState, title: String): TaskId = state.tasks.values.first { it.title == title }.id

    private val tz = TimeZone.UTC
    private val at = 1_000_000_000_000L
    private val hour = 3_600_000L

    private fun account(): SchedulerState {
        var s = SchedulerState.empty()
        s = r(s, SchedulerIntent.SetCellTitle(freeRootCell(s), "Read"))
        s = r(s, SchedulerIntent.SetCellTitle(freeRootCell(s), "Walk"))
        s = r(s, SchedulerIntent.AddPeriodKind("deep work"))
        return s.copy(
            chores = listOf(ChoreEntry(title = "Water", spanDays = 3.0, id = "reminder-0")),
            alarms = listOf(AlarmEntry(id = "alarm-0", label = "Wake", timeOfDayMinutes = 420, days = emptySet())),
            timers = listOf(TimerEntry(id = "timer-0", label = "Tea")),
        )
    }

    private fun added(state: SchedulerState, vararg keys: String) = SearchDomain.resolve(state, keys.toList())

    private fun rows(state: SchedulerState, config: SearchDomain.Config): List<SearchDomain.ItemResult> =
        SearchDomain.results(state, config.kinds, config.query, filters = config.filters, sorts = config.sorts, timeZone = tz)
            .map { it as SearchDomain.ItemResult }

    @Test
    fun the_start_is_the_calendar_filters_position_until_one_is_given_and_the_end_a_length_or_an_instant() {
        val fromTheCalendar = SearchDomain.calendarAddConfig(at)
        assertEquals(at, SearchDomain.placementStart(fromTheCalendar), "the right-click, as before the fields existed")
        assertNull(SearchDomain.placementStart(SearchDomain.Config()), "nothing said, nowhere to lay")
        val given = fromTheCalendar.copy(placement = SearchDomain.Placement(startMillis = at + hour))
        assertEquals(at + hour, SearchDomain.placementStart(given), "a start of its own wins")

        // The end: 1 hour after the start until the user says otherwise; a length follows the start; an instant stays put.
        assertEquals(at + hour, SearchDomain.placementEnd(SearchDomain.Placement(), at))
        assertEquals(at + hour, SearchDomain.placementEnd(SearchDomain.Placement(endByDelta = false), at), "no instant given yet")
        val byLength = SearchDomain.Placement(lengthMillis = 2 * hour)
        assertEquals(at + 2 * hour, SearchDomain.placementEnd(byLength, at))
        assertEquals(at + 3 * hour, SearchDomain.placementEnd(byLength, at + hour), "a moved start carries a length along")
        val byInstant = SearchDomain.Placement(endByDelta = false, endMillis = at + 2 * hour, lengthMillis = 5 * hour)
        assertEquals(at + 2 * hour, SearchDomain.placementEnd(byInstant, at + hour), "and leaves an instant where it is")
        assertFalse(SearchDomain.placementRefused(byInstant, at + hour))
        assertTrue(SearchDomain.placementRefused(byInstant, at + 2 * hour), "an end not after the start")
        assertFalse(SearchDomain.placementRefused(SearchDomain.Placement(), at))
    }

    @Test
    fun the_start_and_the_end_are_kept_with_the_configuration_and_absent_from_an_older_one() {
        val config = SearchDomain.Config(
            placement = SearchDomain.Placement(startMillis = at, endByDelta = false, lengthMillis = hour, endMillis = at + hour),
        )
        assertEquals(config, SearchDomain.Config.decode(config.encode()))
        val blocks = SearchDomain.blocksSearchConfig(listOf("Task/task-1", "Alarm/alarm-0"))
        assertEquals(blocks, SearchDomain.Config.decode(blocks.encode()))
        // Written by a build before the fields existed: no start, an end 1 hour after it, every block.
        val old = SearchDomain.Config.decode("""{"kinds":["Task"],"calendarAddOn":true,"calendarAddAtMillis":$at}""")!!
        assertEquals(SearchDomain.Placement(), old.placement)
        assertTrue(old.placement.endByDelta)
        assertEquals(hour, old.placement.lengthMillis)
        assertEquals(emptySet(), old.filters.blocksOf)
        assertEquals(at, SearchDomain.placementStart(old))
    }

    @Test
    fun a_panel_and_a_period_take_the_end_and_a_tag_and_a_ring_stay_an_instant() {
        val s = account()
        val read = taskWithTitle(s, "Read")
        val list = added(s, "Task/" + read.value, "RestrictivePeriod/deep work", "Reminder/reminder-0", "Alarm/alarm-0")
        val end = at + 3 * hour
        val drafts = SearchDomain.calendarDrafts(s, list, at, tz, endMillis = end)
        assertEquals(
            listOf(CalendarElements.Kind.TaskPanel, CalendarElements.Kind.RestrictivePeriod, CalendarElements.Kind.Reminder, CalendarElements.Kind.Alarm),
            drafts.map { it.kind },
        )
        assertEquals(listOf(end, end), drafts.take(2).map { it.endMillis })
        val own = SearchDomain.calendarDrafts(s, list, at, tz)
        assertEquals(own.drop(2), drafts.drop(2), "a tag and a ring are what they were")
        // An end not after the start is no end: each keeps its own length.
        assertEquals(own, SearchDomain.calendarDrafts(s, list, at, tz, endMillis = at))
    }

    @Test
    fun the_blocks_of_an_element_are_what_a_hand_placed_for_it_and_nothing_the_app_laid() {
        var s = account()
        val read = taskWithTitle(s, "Read")
        val walk = taskWithTitle(s, "Walk")
        // Laid by the action itself: Read at two starts, and a period clear of both (Read does not work through it).
        fun lay(key: String, start: Long, end: Long? = null) {
            s = r(s, SchedulerIntent.AddCalendarElements(SearchDomain.calendarDrafts(s, added(s, key), start, tz, endMillis = end)))
        }
        lay("Task/" + read.value, at + 5 * hour, at + 6 * hour)
        lay("Task/" + read.value, at)
        lay("RestrictivePeriod/deep work", at + 8 * hour, at + 9 * hour)
        // What the app lays by itself, and a tag, an isolated ring and a timer's ring moved on the calendar.
        s = s.copy(
            panels = s.panels +
                TaskPanel("auto/1", walk, "Walk", at, at + hour, auto = true) +
                TaskPanel("break/1", null, "20 s", at, at + 20_000L, screenBreak = true) +
                TaskPanel("chore/reminder-0/0", null, "Water", at + 2 * hour, at + 2 * hour, chore = true),
            alarms = s.alarms + AlarmEntry(id = "alarm-1", label = "Once", timeOfDayMinutes = 60, onlyOnEpochDay = at / (24 * hour)),
            timers = listOf(TimerEntry(id = "timer-0", label = "Tea", endsAtMillis = at + 4 * hour, calendarPlaced = true)),
        )
        val blocks = SearchDomain.calendarBlocks(s, tz)
        assertEquals(blocks.sortedBy { it.startMillis }, blocks, "in the timeline's order")
        assertEquals(
            setOf(
                "Task/" + read.value, "RestrictivePeriod/deep work", "Reminder/reminder-0", "Alarm/alarm-1", "Timer/timer-0",
            ),
            blocks.mapTo(HashSet()) { it.owner },
            "never the fill's panel of Walk, a screen break, or the alarm that is a rule",
        )
        assertEquals((at / (24 * hour)) * 24 * hour + hour, blocks.first { it.owner == "Alarm/alarm-1" }.startMillis)

        // "Blocks on the calendar" over Read alone: its two panels, the earlier first.
        val ofRead = rows(s, SearchDomain.blocksSearchConfig(SearchDomain.blockOwners(added(s, "Task/" + read.value))))
        assertEquals(listOf("Read", "Read"), ofRead.map { it.name })
        assertEquals(listOf(at, at + 5 * hour), ofRead.map { SearchDomain.calendarBlockOf(s, it.id, tz)!!.startMillis })
        assertTrue(ofRead.all { it.kind == SearchDomain.Kind.CalendarBlock })
        assertTrue(ofRead.first().detail.contains(" → "), "a block reads from when to when")
        // Over several elements at once; an element with no block lists nothing.
        val several = SearchDomain.blocksSearchConfig(listOf("RestrictivePeriod/deep work", "Timer/timer-0", "Task/" + walk.value))
        assertEquals(setOf("Tea", PeriodKinds.periodTitle("deep work")), rows(s, several).mapTo(HashSet()) { it.name })
        assertEquals(emptyList(), rows(s, SearchDomain.blocksSearchConfig(listOf("Task/" + walk.value))))
        // The filter taken off: every block of the calendar.
        assertEquals(blocks.size, rows(s, SearchDomain.kindSearchConfig(SearchDomain.Kind.CalendarBlock)).size)
        assertTrue(several.filters.isOn(SearchDomain.Setting.BlockElements))
        assertFalse(SearchDomain.Filters().isOn(SearchDomain.Setting.BlockElements))
        // A row is found again by its key, and is gone with its block.
        val key = SearchDomain.keyOf(ofRead.first())
        assertEquals(listOf<SearchDomain.Result>(ofRead.first()), SearchDomain.resolve(s, listOf(key)))
        assertEquals(emptyList(), SearchDomain.resolve(s.copy(panels = emptyList()), listOf(key)))
    }

    /** Anomaly 2026-10-05: the start read `20:30`; one backspace at its end, and it read `20:03`. */
    @Test
    fun a_field_is_not_rewritten_under_the_hand_typing_it() {
        fun after(draft: String, stored: Int?) =
            draftAfterStoreChange(draft, stored, ::timeOfDayText, ::timeOfDayOf)
        // `20:3` is written as 20:03 — and the field goes on reading what was typed, so the next digit lands after it.
        assertEquals(20 * 60 + 3, timeOfDayOf("20:3"))
        assertEquals("20:3", after("20:3", 20 * 60 + 3))
        assertEquals("20", after("20", 20 * 60), "nor `20` turned into `20:00` on the way to `20:45`")
        assertEquals("2", after("2", 2 * 60))
        // Written from elsewhere ("Now", another field, Undo): the field reads the stored time.
        assertEquals("21:15", after("20:3", 21 * 60 + 15))
        assertEquals("21:15", after("", 21 * 60 + 15))
        assertEquals("", after("20:30", null))
        // Half typed and not a time: nothing was written, nothing to read again.
        assertEquals("20:", after("20:", null))
    }

    /** User rule 2026-10-05: the right-click menu of an `HH:MM` field adds or removes 1 h, 10 min or 1 min. */
    @Test
    fun a_time_fields_menu_steps_by_an_hour_ten_minutes_or_one_round_the_clock() {
        assertEquals(listOf("+1 h", "+10 min", "+1 min", "−1 min", "−10 min", "−1 h"), TIME_NUDGE_MINUTES.map(::timeNudgeLabel))
        assertEquals("21:30", timeOfDayText(nudgedTimeOfDay(20 * 60 + 30, 60)))
        assertEquals("20:20", timeOfDayText(nudgedTimeOfDay(20 * 60 + 30, -10)))
        assertEquals("20:31", timeOfDayText(nudgedTimeOfDay(20 * 60 + 30, 1)))
        assertEquals("00:30", timeOfDayText(nudgedTimeOfDay(23 * 60 + 30, 60)), "round the clock")
        assertEquals("23:59", timeOfDayText(nudgedTimeOfDay(0, -1)))
        // The field then reads the stepped time: it is not what was on screen.
        assertEquals("21:30", draftAfterStoreChange("20:30", 21 * 60 + 30, ::timeOfDayText, ::timeOfDayOf))
    }

    @Test
    fun the_calendars_actions_are_listed_for_what_can_be_on_the_calendar() {
        val s = account().let { r(it, SchedulerIntent.CreateCategory("Home")) }
        fun general(vararg keys: String): List<SearchDomain.AddedAction> {
            val list = added(s, *keys)
            return SearchDomain.actionsFor(
                SearchDomain.addedActions("", SearchDomain.Kind.entries.toSet(), SearchDomain.actionKindsOf(list)), list,
            ).firstOrNull { it.first == null }?.second.orEmpty()
        }
        val calendar = listOf(SearchDomain.AddedAction.PlaceOnCalendar, SearchDomain.AddedAction.CalendarBlocks)
        for (key in listOf("Task/" + taskWithTitle(s, "Read").value, "RestrictivePeriod/deep work", "Reminder/reminder-0", "Alarm/alarm-0", "Timer/timer-0")) {
            assertTrue(general(key).containsAll(calendar), key)
        }
        val category = "Category/" + s.categories.first { it.title == "Home" }.id.value
        assertTrue(general(category).none { it in calendar }, "a category is never on the calendar")
        assertTrue(general(category).contains(SearchDomain.AddedAction.OpenEach), "its other general actions stand")
        assertTrue(general(category, "Alarm/alarm-0").containsAll(calendar))
        assertEquals(listOf("Alarm/alarm-0"), SearchDomain.blockOwners(added(s, category, "Alarm/alarm-0")))
    }
}
