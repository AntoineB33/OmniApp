package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import org.example.project.scheduler.domain.AlarmDomain
import org.example.project.scheduler.domain.CalendarElements
import org.example.project.scheduler.model.ChoreEntry
import org.example.project.scheduler.model.TimerEntry
import org.example.project.scheduler.domain.SearchDomain
import org.example.project.scheduler.model.AlarmEntry
import org.example.project.scheduler.model.CategoryId
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerState
import org.example.project.ui.SearchRowOpeners

/**
 * User rule 2026-10-18: a Search window opened from a Search window ("New alarm" in the window the calendar's "add…"
 * opened) is linked to it, and holds the calendar right-click it came from — so its time fields offer "Right-click time".
 */
class SearchWindowLinkTest {
    private val click = 1_760_000_000_000L
    private val fromCalendar = SearchDomain.calendarAddConfig(click)

    @Test
    fun a_window_opened_from_another_is_linked_to_it_and_holds_its_right_click() {
        val alarm = SearchDomain.elementSearchConfig(SearchDomain.Kind.Alarm, "a1")
        val linked = SearchDomain.linkedConfig(alarm, "Search#2", fromCalendar)
        assertEquals("Search#2", linked.openedFrom)
        assertEquals(click, linked.calendarClickMillis)
        // Everything else is the window it was asked for.
        assertEquals(alarm.copy(openedFrom = "Search#2", calendarClickMillis = click), linked)
        // …and a window opened from that one holds the same right-click: the chain goes back to the calendar.
        val next = SearchDomain.linkedConfig(SearchDomain.kindSearchConfig(SearchDomain.Kind.Alarm), "Search#3", linked)
        assertEquals(click, next.calendarClickMillis)
        assertEquals("Search#3", next.openedFrom)
    }

    @Test
    fun a_window_with_a_right_click_of_its_own_keeps_it_and_one_from_nowhere_has_none() {
        val own = SearchDomain.calendarAtConfig(click + 60_000L)
        assertEquals(click + 60_000L, SearchDomain.linkedConfig(own, "Search", fromCalendar).calendarClickMillis)
        val plain = SearchDomain.linkedConfig(SearchDomain.kindSearchConfig(SearchDomain.Kind.Task), "Search", SearchDomain.Config())
        assertNull(plain.calendarClickMillis)
    }

    @Test
    fun the_link_survives_the_store_and_an_older_row_reads_as_none() {
        val linked = SearchDomain.linkedConfig(SearchDomain.elementSearchConfig(SearchDomain.Kind.Alarm, "a1"), "Search#2", fromCalendar)
        assertEquals(linked, SearchDomain.Config.decode(linked.encode()))
        assertNull(SearchDomain.Config.decode("""{"query":"x","kinds":["Alarm"],"calendarClickMillis":5}""")!!.openedFrom)
    }

    @Test
    fun an_alarm_given_a_date_rings_on_that_date_alone_and_on_its_days_again_once_it_is_taken_off() {
        val utc = TimeZone.UTC
        val state = SchedulerState.empty().copy(alarms = listOf(AlarmEntry("alarm-1", timeOfDayMinutes = 7 * 60)))
        val added = SearchDomain.resolve(state, listOf(SearchDomain.Kind.Alarm.name + "/alarm-1"))
        val day = LocalDate(2026, 11, 3)
        val dated =
            SearchDomain.addedIntents(state, added, SearchDomain.AddedCommand.AlarmsEdit { it.copy(onlyOnEpochDay = day.toEpochDays().toLong(), timeOfDayMinutes = 9 * 60) }, 0L)
                .filterIsInstance<SchedulerIntent.SetAlarms>().single().entries.single()
        val dayStart = day.atStartOfDayIn(utc).toEpochMilliseconds()
        assertEquals(dayStart + 9 * 3_600_000L, AlarmDomain.nextOccurrenceMillis(dated, dayStart - 86_400_000L, utc))
        assertNull(AlarmDomain.nextOccurrenceMillis(dated, dayStart + 10 * 3_600_000L, utc), "rings that date and no other")
        val repeating = dated.copy(onlyOnEpochDay = null)
        assertEquals(dayStart + 86_400_000L + 9 * 3_600_000L, AlarmDomain.nextOccurrenceMillis(repeating, dayStart + 10 * 3_600_000L, utc))
    }

    @Test
    fun the_openers_a_search_window_is_handed_say_it_asked_every_one() {
        val asked = ArrayList<String>()
        val opened = ArrayList<String>()
        val plain =
            SearchRowOpeners(
                onOpenTaskEdit = { opened += "task" },
                onOpenCategory = { opened += "category" },
                onOpenPeriodKind = { opened += "period" },
                onEditAlarmOrTimer = { opened += "alarm" },
                onEditReminder = { opened += "reminder" },
                onCreate = { opened += "new" },
            )
        val fromWindow = plain.askedThrough { block -> asked += "Search#2"; block() }
        fromWindow.onOpenTaskEdit(TaskId("t"))
        fromWindow.onOpenCategory(CategoryId("c"))
        fromWindow.onCreate(SearchDomain.Kind.Alarm)
        assertEquals(listOf("task", "category", "new"), opened)
        assertEquals(listOf("Search#2", "Search#2", "Search#2"), asked)
        // The plain ones (the lateral menu's) say nothing.
        plain.onEditReminder("r")
        assertEquals(3, asked.size)
    }

    @Test
    fun an_alarm_on_one_date_put_on_another_day_rings_there_and_a_repeating_one_keeps_repeating() {
        val draft = CalendarElements.Draft(CalendarElements.Kind.Alarm, existingId = "alarm-1", startMillis = 9 * 3_600_000L, endMillis = 9 * 3_600_000L + 30_000L)
        val dated = AlarmEntry("alarm-1", onlyOnEpochDay = 20_000L)
        assertEquals(20_005L, CalendarElements.alarmEdited(dated, draft, dayStartMillis = 0L, startEpochDay = 20_005L).onlyOnEpochDay)
        assertEquals(9 * 60, CalendarElements.alarmEdited(dated, draft, 0L, 20_005L).timeOfDayMinutes)
        assertNull(CalendarElements.alarmEdited(AlarmEntry("alarm-1"), draft, 0L, 20_005L).onlyOnEpochDay)
    }

    @Test
    fun the_added_elements_times_of_day_are_their_own_and_named_after_them() {
        val state =
            SchedulerState.empty().copy(
                alarms = listOf(AlarmEntry("alarm-1", label = "Wake up", timeOfDayMinutes = 7 * 60 + 30)),
                chores = listOf(
                    ChoreEntry(title = "Water", spanDays = 1.0, timeOfDayMinutes = 18 * 60, id = "r1"),
                    ChoreEntry(title = "Floss", spanDays = 1.0, timeOfDayMinutes = -1, id = "r2"),
                ),
                timers = listOf(TimerEntry("timer-1", label = "Tea", durationSeconds = 180)),
            )
        val added = SearchDomain.resolve(state, listOf("Alarm/alarm-1", "Reminder/r1", "Reminder/r2", "Timer/timer-1"))
        val times = SearchDomain.elementTimesOfDay(state, added, nowMillis = 10 * 3_600_000L, timeZone = TimeZone.UTC)
        assertEquals(
            listOf(
                SearchDomain.ElementTime("Wake up", (7 * 60 + 30) * 60),
                SearchDomain.ElementTime("Water", 18 * 3600),
                // An idle timer ends its duration from now; a reminder with no time of its own has no button.
                SearchDomain.ElementTime("Tea (end)", 10 * 3600 + 180),
            ),
            times,
        )
    }
}
