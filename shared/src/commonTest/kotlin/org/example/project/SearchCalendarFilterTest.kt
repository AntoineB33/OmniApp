package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.CalendarElements
import org.example.project.scheduler.domain.PeriodKinds
import org.example.project.scheduler.domain.SearchDomain
import org.example.project.scheduler.model.CellId
import org.example.project.scheduler.model.ChoreEntry
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.model.TaskPanel
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState

/**
 * User rule 2026-10-01: the calendar's "add…" opens the Search window of what can be added AT the right-click — a
 * global filter of the Search configurations window (a position, a switch, and a button back to the right-click) — and
 * its "Add to the calendar" action lays the added elements there through the element window's own drafts.
 */
class SearchCalendarFilterTest {
    private fun r(state: SchedulerState, intent: SchedulerIntent) = SchedulerReducer.reduce(state, intent)

    private fun freeRootCell(state: SchedulerState): CellId = state.lists[state.rootListId]!!.cellIds.last()

    private fun taskWithTitle(state: SchedulerState, title: String): TaskId = state.tasks.values.first { it.title == title }.id

    private val at = 1_000_000_000_000L
    private val hour = 3_600_000L

    /** Read and Walk, a "deep work" period over [at] that only Read may work through, a reminder and an alarm. */
    private fun account(): SchedulerState {
        var s = SchedulerState.empty()
        s = r(s, SchedulerIntent.SetCellTitle(freeRootCell(s), "Read"))
        s = r(s, SchedulerIntent.SetCellTitle(freeRootCell(s), "Walk"))
        s = r(s, SchedulerIntent.AddPeriodKind("deep work"))
        s = r(s, SchedulerIntent.SetTaskResilience(taskWithTitle(s, "Read"), "deep work", 0.5))
        s = r(s, SchedulerIntent.CreateCategory("Home"))
        return s.copy(
            panels = s.panels + TaskPanel("panel/deep", null, "deep work", at - hour, at + hour, periodKind = "deep work"),
            chores = listOf(ChoreEntry(title = "Water", spanDays = 3.0, id = "reminder-0")),
            alarms = listOf(org.example.project.scheduler.model.AlarmEntry(id = "alarm-0", label = "Wake", timeOfDayMinutes = 420)),
        )
    }

    private fun names(state: SchedulerState, config: SearchDomain.Config): List<String> =
        SearchDomain.results(state, SearchDomain.Kind.entries.toSet() - SearchDomain.Kind.Window, "", filters = config.filters)
            .map { if (it is SearchDomain.TaskResult) state.tasks[it.taskId]!!.title else it.kind.name + ":" + (it as SearchDomain.ItemResult).id }

    @Test
    fun the_filter_keeps_only_what_can_be_added_at_that_instant() {
        val s = account()
        val shown = names(s, SearchDomain.calendarAddConfig(at))
        assertTrue("Read" in shown, "Read may work through the deep-work period")
        assertFalse("Walk" in shown, "Walk's resilience to it is 0: it cannot be put there")
        assertTrue("RestrictivePeriod:deep work" in shown && "RestrictivePeriod:" + PeriodKinds.SLEEP in shown, "any kind of period")
        assertTrue("Reminder:reminder-0" in shown, "a tag of a reminder")
        assertFalse(shown.any { it.startsWith("Alarm:") }, "an existing alarm's occurrences are its weekdays'")
        assertFalse(shown.any { it.startsWith("Category:") || it.startsWith("Timer:") || it.startsWith("HistoryUnit:") })
        // Outside the period Walk can go there too; the switch off keeps everything.
        assertTrue("Walk" in names(s, SearchDomain.calendarAddConfig(at + 2 * hour)))
        val off = SearchDomain.calendarAddConfig(at).let { it.copy(filters = it.filters.copy(calendarAddOn = false)) }
        assertTrue(names(s, off).any { it.startsWith("Category:") })
        assertNull(off.filters.calendarAddAt)
        assertFalse(off.filters.isOn(SearchDomain.Setting.CalendarAdd))
        assertTrue(SearchDomain.calendarAddConfig(at).filters.isOn(SearchDomain.Setting.CalendarAdd))
    }

    @Test
    fun the_filter_and_the_right_click_are_kept_with_the_configuration_and_absent_from_an_older_one() {
        val config = SearchDomain.calendarAddConfig(at)
        assertEquals(SearchDomain.CALENDAR_ADD_KINDS, config.kinds)
        assertEquals(at, config.calendarClickMillis)
        assertEquals(config, SearchDomain.Config.decode(config.encode()))
        val old = SearchDomain.Config.decode("""{"kinds":["Task"]}""")!!
        assertFalse(old.filters.calendarAddOn)
        assertNull(old.filters.calendarAddAtMillis)
        assertNull(old.calendarClickMillis)
    }

    @Test
    fun add_to_the_calendar_seeds_each_added_element_as_the_element_window_does() {
        val s = account()
        val read = taskWithTitle(s, "Read")
        val added = SearchDomain.resolve(
            s,
            listOf("Task/" + read.value, "Task/" + taskWithTitle(s, "Walk").value, "RestrictivePeriod/deep work", "Reminder/reminder-0", "Alarm/alarm-0"),
        )
        val drafts = SearchDomain.calendarDrafts(s, added, at)
        assertEquals(
            listOf(CalendarElements.Kind.TaskPanel, CalendarElements.Kind.RestrictivePeriod, CalendarElements.Kind.Reminder),
            drafts.map { it.kind },
            "Walk cannot go there, nor an existing alarm",
        )
        val panel = drafts[0]
        assertEquals(read, panel.taskId)
        assertEquals(at, panel.startMillis)
        assertEquals(at + s.tasks[read]!!.minimumMinutes * 60_000L, panel.endMillis, "its task's minimum time")
        assertEquals(at + hour, drafts[1].endMillis, "a period an hour")
        assertEquals(at, drafts[2].endMillis, "a tag has no duration")
        assertEquals("reminder-0", drafts[2].reminderId)
        val alarm = SearchDomain.calendarAlarmDraft(s, at)
        assertEquals(CalendarElements.Kind.Alarm, alarm.kind)
        assertEquals(at + s.newAlarmDefaults.soundSeconds * 1000L, alarm.endMillis)
    }
}
