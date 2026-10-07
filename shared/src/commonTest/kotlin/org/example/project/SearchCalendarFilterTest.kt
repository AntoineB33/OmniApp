package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.datetime.toLocalDateTime
import org.example.project.scheduler.domain.CalendarElements
import org.example.project.scheduler.domain.PeriodKinds
import org.example.project.scheduler.domain.SearchDomain
import org.example.project.scheduler.model.TaskTimeRange
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

    /** Anomaly 2026-10-03: "edit…" on a past 20 s break listed no break — it is not a panel of the state. */
    @Test
    fun a_screen_break_is_on_the_calendar_at_the_minute_it_falls_in_with_no_screen() {
        val kinds = org.example.project.scheduler.domain.PeriodKinds
        val config = org.example.project.scheduler.domain.PeriodKindConfig.DEFAULT
        val minute = 60_000L
        // 19:09:48–19:10:08, and a right-click rounded down to 19:09:00.
        val lookAway = TaskTimeRange(1_000 * minute + 48_000L, 1_000 * minute + 68_000L) to kinds.BREAK_20S
        val pose = TaskTimeRange(2_000 * minute, 2_015 * minute) to kinds.BREAK_15MIN
        val breaks = listOf(lookAway, pose)
        assertEquals(listOf(lookAway), SearchDomain.calendarBreaksAt(breaks, 1_000 * minute))
        assertEquals(listOf(lookAway), SearchDomain.calendarBreaksAt(breaks, 1_001 * minute), "still inside it")
        assertEquals(emptyList(), SearchDomain.calendarBreaksAt(breaks, 998 * minute))
        assertEquals(listOf(pose), SearchDomain.calendarBreaksAt(breaks, 2_007 * minute))
        // What it lists: the break's own kind and "no screen" — the 20 s break is a kind like any other, and the one
        // the machine gives a 20 s break; nobody is resilient to it.
        assertEquals(setOf(kinds.BREAK_20S, kinds.NO_SCREEN), SearchDomain.calendarBreakKinds(kinds.BREAK_20S, config))
        assertEquals(
            kinds.BREAK_20S,
            org.example.project.scheduler.domain.DynamicPeriods.breakKind(org.example.project.scheduler.domain.DynamicPeriods.LABEL_20S),
        )
        assertTrue(kinds.BREAK_20S in org.example.project.scheduler.state.SchedulerState.empty().allPeriodKinds)
        assertEquals(0.0, kinds.resilienceFor(mapOf(kinds.BREAK_20S to 1.0), kinds.BREAK_20S), "allows no task")
        assertEquals(setOf(kinds.BREAK_15MIN, kinds.NO_SCREEN), SearchDomain.calendarBreakKinds(kinds.BREAK_15MIN, config))
    }
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
            alarms = listOf(org.example.project.scheduler.model.AlarmEntry(id = "alarm-0", label = "Wake", timeOfDayMinutes = 420, days = emptySet())),
            timers = listOf(org.example.project.scheduler.model.TimerEntry(id = "timer-0", label = "Tea")),
        )
    }

    private fun names(state: SchedulerState, config: SearchDomain.Config, now: Long = at - hour): List<String> =
        SearchDomain.results(state, SearchDomain.Kind.entries.toSet() - SearchDomain.Kind.Window, "", filters = config.filters, nowMillis = now)
            .map { if (it is SearchDomain.TaskResult) state.tasks[it.taskId]!!.title else it.kind.name + ":" + (it as SearchDomain.ItemResult).id }

    @Test
    fun the_filter_keeps_only_what_can_be_added_at_that_instant() {
        val s = account()
        val shown = names(s, SearchDomain.calendarAddConfig(at))
        // "Can be added" (what "add…" opens on): every schedulable task, whatever period stands there (anomaly
        // 2026-10-07). What the period refuses is the "without removing anything" state's question.
        assertTrue("Read" in shown && "Walk" in shown, "every schedulable task can be added")
        val keeping = SearchDomain.calendarAddConfig(at).let {
            it.copy(filters = it.filters.withCalendarAddFilter(SearchDomain.CalendarAddFilter.KeepingEverything))
        }
        assertFalse("Walk" in names(s, keeping), "Walk's resilience to the period is 0: it could only stand there with the period gone")
        assertTrue("RestrictivePeriod:deep work" in shown && "RestrictivePeriod:" + PeriodKinds.SLEEP in shown, "any kind of period")
        assertTrue("Reminder:reminder-0" in shown, "a tag of a reminder")
        assertTrue("Alarm:alarm-0" in shown, "an alarm: it then rings there (user rule 2026-10-01)")
        assertTrue("Timer:timer-0" in shown, "a timer: it then ends there")
        assertFalse("Timer:timer-0" in names(s, SearchDomain.calendarAddConfig(at), now = at + 1), "not once the instant is past")
        assertFalse("Timer:timer-0" in names(s, SearchDomain.calendarAddConfig(at), now = at - 25 * hour), "nor beyond a timer's longest run")
        assertFalse(shown.any { it.startsWith("Category:") || it.startsWith("Chrono:") || it.startsWith("HistoryUnit:") })
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
            listOf(
                "Task/" + read.value, "Task/" + taskWithTitle(s, "Walk").value, "RestrictivePeriod/deep work", "Reminder/reminder-0",
                "Alarm/alarm-0", "Timer/timer-0",
            ),
        )
        val tz = kotlinx.datetime.TimeZone.UTC
        val drafts = SearchDomain.calendarDrafts(s, added, at, tz)
        assertEquals(
            listOf(
                CalendarElements.Kind.TaskPanel, CalendarElements.Kind.TaskPanel, CalendarElements.Kind.RestrictivePeriod,
                CalendarElements.Kind.Reminder, CalendarElements.Kind.Alarm,
            ),
            drafts.map { it.kind },
            "Walk can be added there too, whatever the period refuses; a timer is not a draft (it is put on the clock)",
        )
        val alarm = drafts[4]
        assertEquals("alarm-0", alarm.existingId, "the alarm itself, edited, not a new one")
        assertEquals(at, alarm.startMillis)
        assertTrue(alarm.alarmArmed)
        val weekday = kotlin.time.Instant.fromEpochMilliseconds(at).toLocalDateTime(tz).dayOfWeek
        assertEquals(setOf(weekday), alarm.alarmDays, "that weekday among its days")
        // A timer ends there: one list edit that starts it to run out at the instant.
        val now = at - 10 * 60_000L
        val timerIntents = SearchDomain.calendarTimerIntents(s, added, at, now)
        val started = (timerIntents.single() as SchedulerIntent.SetTimers).entries.single()
        assertEquals(at, started.endsAtMillis)
        assertEquals(emptyList(), SearchDomain.calendarTimerIntents(s, added, at, at + 1))
        val panel = drafts[0]
        assertEquals(read, panel.taskId)
        assertEquals(at, panel.startMillis)
        assertEquals(at + s.tasks[read]!!.minimumMinutes * 60_000L, panel.endMillis, "its task's minimum time")
        assertEquals(taskWithTitle(s, "Walk"), drafts[1].taskId)
        assertEquals(at + hour, drafts[2].endMillis, "a period an hour")
        assertEquals(at, drafts[3].endMillis, "a tag has no duration")
        assertEquals("reminder-0", drafts[3].reminderId)
        val newAlarm = SearchDomain.calendarAlarmDraft(s, at)
        assertEquals(CalendarElements.Kind.Alarm, newAlarm.kind)
        assertEquals(at + s.newAlarmDefaults.soundSeconds * 1000L, newAlarm.endMillis)
    }

    // ----- A ring dragged on the calendar (2026-10-02) ---------------------------------------------------

    @Test
    fun a_ring_dragged_on_the_calendar_leaves_its_rule_and_becomes_an_isolated_blue_ring() {
        val tz = kotlinx.datetime.TimeZone.UTC
        val domain = org.example.project.scheduler.domain.SchedulerDomain
        val alarmDomain = org.example.project.scheduler.domain.AlarmDomain
        val codec = org.example.project.scheduler.persistence.SchedulerStateCodec
        val day = 24 * hour
        // A daily alarm at 07:00; its ring of the day holding [at] is dragged two hours later.
        val s0 = account().let { it.copy(alarms = it.alarms.map { a -> a.copy(days = org.example.project.scheduler.model.AlarmEntry.EVERY_DAY) }) }
        val dayStart = at - at % day
        val from = dayStart + 7 * hour
        val to = dayStart + 9 * hour
        val s = r(s0, SearchDomain.calendarRingMoveIntent(s0, "alarm-0", timer = false, fromMillis = from, atMillis = to, nowMillis = dayStart, timeZone = tz)!!)
        val rule = s.alarms.first { it.id == "alarm-0" }
        val isolated = s.alarms.single { it.id != "alarm-0" }
        assertEquals(420, rule.timeOfDayMinutes, "the rule keeps its time: the other days do not move")
        assertEquals(org.example.project.scheduler.domain.SchedulerDomain.PanelOutline.Pattern, domain.ringOutline(rule.isolated), "and stays orange")
        assertEquals(540, isolated.timeOfDayMinutes)
        assertEquals(org.example.project.scheduler.domain.SchedulerDomain.PanelOutline.User, domain.ringOutline(isolated.isolated), "the dragged ring is blue")
        // What rings over the three days around it: the rule the day before and after, the isolated ring that day.
        val rings = alarmDomain.occurrencesInWindow(s.alarms, dayStart - day, dayStart + 2 * day, tz).map { it.entry.id to it.instant }
        assertEquals(
            listOf("alarm-0" to from - day, isolated.id to to, "alarm-0" to from + day),
            rings,
        )
        assertEquals(to, alarmDomain.nextOccurrenceMillis(isolated, dayStart, tz))
        assertNull(alarmDomain.nextOccurrenceMillis(isolated, to, tz), "it rings once")
        // Dragged again, the isolated ring is simply given its new time — no third row.
        val again = r(s, SearchDomain.calendarRingMoveIntent(s, isolated.id, timer = false, fromMillis = to, atMillis = to + hour, nowMillis = dayStart, timeZone = tz)!!)
        assertEquals(2, again.alarms.size)
        assertEquals(600, again.alarms.single { it.id == isolated.id }.timeOfDayMinutes)
        // The timer: put on the clock to end there, blue; left alone where it cannot end (the past).
        val now = at - 10 * 60_000L
        val movedTimer = r(s, SearchDomain.calendarRingMoveIntent(s, "timer-0", timer = true, fromMillis = at, atMillis = at, nowMillis = now)!!).timers.single()
        assertEquals(at, movedTimer.endsAtMillis)
        assertTrue(movedTimer.calendarPlaced)
        assertNull(SearchDomain.calendarRingMoveIntent(s, "timer-0", timer = true, fromMillis = at, atMillis = at, nowMillis = at + 1))
        assertNull(SearchDomain.calendarRingMoveIntent(s, "alarm-9", timer = false, fromMillis = from, atMillis = to, nowMillis = now, timeZone = tz))
        // Persisted: it survives a save and a load…
        val saved = s.copy(timers = listOf(movedTimer))
        val decoded = codec.decode(codec.encode(saved))!!
        assertEquals(saved.alarms, decoded.alarms)
        assertTrue(decoded.timers.single().calendarPlaced)
        // …and a payload written before the fields existed reads as plain rules that skip no date.
        val older = codec.decode(codec.encode(s0))!!
        assertEquals(s0.alarms, older.alarms)
        assertTrue(older.alarms.none { it.isolated || it.skippedEpochDays.isNotEmpty() })
        assertFalse("onlyOnEpochDay\":1" in codec.encode(s0) || "calendarPlaced\":true" in codec.encode(s0))
    }

    // ----- The calendar's "edit…" (user rule 2026-10-01) -------------------------------------------------

    @Test
    fun the_is_at_filter_keeps_only_what_is_on_the_timeline_there() {
        val tz = kotlinx.datetime.TimeZone.UTC
        var s = account()
        val read = taskWithTitle(s, "Read")
        val local = kotlin.time.Instant.fromEpochMilliseconds(at).toLocalDateTime(tz)
        s = s.copy(
            panels = s.panels +
                TaskPanel("panel/read", read, "Read", at - hour, at + hour) +
                TaskPanel("chore/reminder-0/d1", null, "Water", at + 5 * 60_000L, at + 5 * 60_000L, chore = true),
            alarms = listOf(
                org.example.project.scheduler.model.AlarmEntry(id = "alarm-0", label = "Wake", timeOfDayMinutes = local.hour * 60 + local.minute + 10),
                org.example.project.scheduler.model.AlarmEntry(id = "alarm-1", label = "Late", timeOfDayMinutes = (local.hour * 60 + local.minute + 120) % 1440),
            ),
            timers = listOf(org.example.project.scheduler.model.TimerEntry(id = "timer-0", label = "Tea", endsAtMillis = at - 60_000L)),
        )
        val there = SearchDomain.calendarElementsAt(s, at, tz)
        assertEquals(
            setOf(
                "Task/" + read.value, "RestrictivePeriod/deep work", "Reminder/reminder-0", "Alarm/alarm-0", "Timer/timer-0",
            ),
            there.filterNot { it.startsWith("RestrictivePeriod/") && it != "RestrictivePeriod/deep work" }.toSet(),
            "the task's box, the period, the tag, the ring and the timer's end there — not Walk, not the alarm two hours on",
        )
        val config = SearchDomain.calendarAtConfig(at)
        assertEquals(config, SearchDomain.Config.decode(config.encode()))
        assertTrue(config.filters.isOn(SearchDomain.Setting.CalendarAt))
        val shown = SearchDomain.results(s, SearchDomain.CALENDAR_AT_KINDS, "", filters = config.filters, timeZone = tz).map(SearchDomain::keyOf)
        assertTrue("Task/" + read.value in shown && "Task/" + taskWithTitle(s, "Walk").value !in shown)
        assertTrue("Alarm/alarm-0" in shown && "Alarm/alarm-1" !in shown)
        // User rule 2026-10-02: "edit…" opens sorted the way the calendar's hover bubble names what is there —
        // reminder, ring (alarm / timer), task, period, layer — and the method is one the window offers.
        assertEquals(SearchDomain.CALENDAR_BUBBLE_SORT, config.sorts.first())
        assertTrue(SearchDomain.SortKey.CalendarBubble in SearchDomain.sortKeysOf(null))
        val sorted = SearchDomain.results(s, SearchDomain.CALENDAR_AT_KINDS, "", filters = config.filters, sorts = config.sorts, timeZone = tz)
        assertEquals(
            listOf(
                SearchDomain.Kind.Reminder, SearchDomain.Kind.Alarm, SearchDomain.Kind.Timer, SearchDomain.Kind.Task,
                SearchDomain.Kind.RestrictivePeriod,
            ),
            sorted.map { it.kind }.distinct(),
        )
        val periodRanks = sorted.filter { it.kind == SearchDomain.Kind.RestrictivePeriod }
            .map { org.example.project.scheduler.domain.CalendarBubbleRank.ofPeriodKind((it as SearchDomain.ItemResult).id) }
        assertEquals(periodRanks.sorted(), periodRanks, "a layer's kind after every other period's")
        // An "edit…" on a window that is already open turns it on, dominant, once.
        val name = SearchDomain.SortMethod(null, SearchDomain.SortKey.Name)
        assertEquals(
            listOf(SearchDomain.CALENDAR_BUBBLE_SORT, name),
            SearchDomain.withCalendarBubbleSort(listOf(name, SearchDomain.CALENDAR_BUBBLE_SORT.copy(descending = true))),
        )
        // A layer band (read off the lock history, not in the state) is there too, as its kind.
        val withLayer = SearchDomain.calendarElementsAt(s, at, tz) { instant -> if (instant == at) setOf(PeriodKinds.NO_PHONE_UNLOCKED) else emptySet() }
        assertTrue("RestrictivePeriod/" + PeriodKinds.NO_PHONE_UNLOCKED in withLayer)
        // Off: everything again.
        val off = config.filters.copy(calendarAtOn = false)
        assertTrue("Alarm/alarm-1" in SearchDomain.results(s, SearchDomain.CALENDAR_AT_KINDS, "", filters = off, timeZone = tz).map(SearchDomain::keyOf))
        assertFalse(SearchDomain.Config.decode("""{"kinds":["Task"]}""")!!.filters.calendarAtOn)
    }
}
