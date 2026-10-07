package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.PeriodKinds
import org.example.project.scheduler.domain.SearchDomain
import org.example.project.scheduler.model.PanelPins
import org.example.project.scheduler.model.SleepSchedule
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState

/**
 * User rule 2026-10-07: *"When the user right-clicks on the calendar, clicks 'add...', opens the Search configurations
 * window, there must be a filter for three states: what can be added without removing anything where the user
 * right-clicked, what can be added, or no filter."* ([SearchDomain.CalendarAddFilter])
 */
class CalendarAddFilterTest {
    private val HOUR = 3_600_000L
    // Ahead of the real clock: where a period laid by hand trims the panels it refuses.
    private val DAY = 4_000_000_000_000L / (24 * HOUR) * (24 * HOUR)

    private fun at(hour: Double) = DAY + (hour * HOUR).toLong()

    /** Two tasks, "Work" with a panel 10–11. */
    private fun state(): Triple<SchedulerState, TaskId, TaskId> {
        var s = SchedulerState.empty()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(s.lists[s.rootListId]!!.cellIds[0], "Work"))
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(s.lists[s.rootListId]!!.cellIds.last(), "Read"))
        s = s.copy(sleep = SleepSchedule(sleepDurationMinutes = 0), automaticSchedule = false)
        val work = s.tasks.values.single { it.title == "Work" }.id
        val read = s.tasks.values.single { it.title == "Read" }.id
        s = SchedulerReducer.reduce(s, SchedulerIntent.AddTaskPanel(work, "Work", at(10.0), at(11.0), PanelPins(existence = true)))
        return Triple(s, work, read)
    }

    private val kinds = setOf(SearchDomain.Kind.Task, SearchDomain.Kind.RestrictivePeriod)

    private fun keys(s: SchedulerState, filter: SearchDomain.CalendarAddFilter, hour: Double): Set<String> =
        SearchDomain.results(
            s, kinds, "",
            filters = SearchDomain.Filters(calendarAddAtMillis = at(hour)).withCalendarAddFilter(filter),
            nowMillis = at(0.0),
        ).mapTo(HashSet()) { SearchDomain.keyOf(it) }

    private val noScreen = SearchDomain.Kind.RestrictivePeriod.name + "/" + PeriodKinds.NO_SCREEN

    @Test
    fun the_three_states() {
        val (s, _, read) = state()
        val everything = keys(s, SearchDomain.CalendarAddFilter.None, 10.0)
        val addable = keys(s, SearchDomain.CalendarAddFilter.Addable, 10.0)
        val keeping = keys(s, SearchDomain.CalendarAddFilter.KeepingEverything, 10.0)

        assertTrue(everything.containsAll(addable) && addable.containsAll(keeping), "each state keeps a part of the one before")
        assertTrue(noScreen in addable, "a \"no screen\" period can be added over the task's panel…")
        assertFalse(noScreen in keeping, "…but it removes that panel: the task is on screen")
        assertTrue(SearchDomain.taskKey(read) in keeping, "another task's panel beside it removes nothing")
        // Where nothing stands, everything that can be added removes nothing.
        assertEquals(keys(s, SearchDomain.CalendarAddFilter.Addable, 14.0), keys(s, SearchDomain.CalendarAddFilter.KeepingEverything, 14.0))
    }

    @Test
    fun what_is_removed_is_asked_of_the_add_itself() {
        val (s, work, _) = state()
        val rows = SearchDomain.results(s, kinds, "")
        val period = rows.single { SearchDomain.keyOf(it) == noScreen }
        fun keeps(from: Double, to: Double) =
            SearchDomain.calendarAddKeepsEverything(SearchDomain.calendarAddSurroundings(s, at(from), at(to)), period, at(from), at(to))

        assertFalse(keeps(10.0, 11.0))
        assertFalse(keeps(10.5, 11.5), "half of the panel is removed")
        assertTrue(keeps(11.0, 12.0), "beside it, nothing is")
        // Exactly what the add does: the reducer trims the panel where the filter says something is removed.
        val added = SchedulerReducer.reduce(s, SchedulerIntent.AddRestrictivePeriod(PeriodKinds.NO_SCREEN, at(10.5), at(11.5)))
        assertEquals(listOf(at(10.0) to at(10.5)), added.panels.filter { it.taskId == work }.map { it.startEpochMillis to it.endEpochMillis })
    }

    /**
     * Anomaly 2026-10-07: right-click at 03:14, at the screen, inside the schedule's Sleep window — "add…" listed no
     * task at all. The stored window covers the whole night; the calendar draws it cut where the line crossed it at a
     * screen, and that drawn reading is the one "what can be added there" must take.
     */
    @Test
    fun what_can_be_added_is_asked_of_the_periods_as_the_calendar_draws_them() {
        val (s0, _, read) = state()
        val night = org.example.project.scheduler.model.TaskPanel("sleep/1", null, "Sleep", at(0.0), at(7.5), sleep = true)
        val s = s0.copy(panels = s0.panels + night)
        try {
            assertFalse(SearchDomain.taskKey(read) in keys(s, SearchDomain.CalendarAddFilter.Addable, 3.0), "the stored window refuses every task")
            // The calendar draws the window from 04:00 on: the line crossed the rest at a screen.
            SearchDomain.drawnPeriodKindsAt = { t -> if (t >= at(4.0) && t < at(7.5)) setOf(PeriodKinds.SLEEP) else emptySet() }
            assertTrue(SearchDomain.taskKey(read) in keys(s, SearchDomain.CalendarAddFilter.Addable, 3.0), "where it gave way, tasks can be added")
            assertFalse(SearchDomain.taskKey(read) in keys(s, SearchDomain.CalendarAddFilter.Addable, 5.0), "where it still stands, they cannot")
            // Outside what the calendar shows, the stored panels answer.
            SearchDomain.drawnPeriodKindsAt = { null }
            assertFalse(SearchDomain.taskKey(read) in keys(s, SearchDomain.CalendarAddFilter.Addable, 3.0))
        } finally {
            SearchDomain.drawnPeriodKindsAt = { null }
        }
    }

    /** Persisted-view compatibility: the new field has a default, and a configuration written before it still reads. */
    @Test
    fun the_state_round_trips_and_a_configuration_written_before_it_keeps_whatever_can_be_added() {
        val strict = SearchDomain.calendarAddConfig(at(10.0)).let { it.copy(filters = it.filters.withCalendarAddFilter(SearchDomain.CalendarAddFilter.KeepingEverything)) }
        assertEquals(SearchDomain.CalendarAddFilter.KeepingEverything, assertNotNull(SearchDomain.Config.decode(strict.encode())).filters.calendarAddFilter)

        val before = SearchDomain.calendarAddConfig(at(10.0)).encode()
        assertFalse("calendarAddKeeping" in before, "the previous shape: no such field")
        assertEquals(SearchDomain.CalendarAddFilter.Addable, assertNotNull(SearchDomain.Config.decode(before)).filters.calendarAddFilter)
        assertEquals(SearchDomain.CalendarAddFilter.None, SearchDomain.Filters().calendarAddFilter)
    }
}
