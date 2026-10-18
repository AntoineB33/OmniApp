package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import org.example.project.scheduler.domain.NewDate
import org.example.project.scheduler.domain.NewElementDefaults
import org.example.project.scheduler.domain.NewElementTimes
import org.example.project.scheduler.domain.NewTime
import org.example.project.scheduler.domain.SearchDomain
import org.example.project.scheduler.model.AlarmEntry
import org.example.project.scheduler.persistence.SchedulerStateCodec
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerState

/**
 * User rule 2026-10-18: an alarm's and a reminder's default configuration in the action section ("the default micro
 * group"), and WHEN a new one is set — its date (an alarm's) and its time, from the day / time it is made, from the
 * element whose "New" made it, or a time of its own.
 */
class NewElementTimesTest {
    private val utc = TimeZone.UTC
    private val day = LocalDate(2026, 11, 3)
    private val today = day.toEpochDays().toLong()
    private val now = day.atStartOfDayIn(utc).toEpochMilliseconds() + (14 * 60 + 25) * 60_000L
    private val source = AlarmEntry("alarm-1", timeOfDayMinutes = 6 * 60 + 45, onlyOnEpochDay = today + 4)

    private fun alarm(times: NewElementTimes, from: AlarmEntry? = null) =
        NewElementDefaults.newAlarm(NewElementDefaults.ALARM, times, "alarm-2", now, utc, from)

    @Test
    fun by_default_a_new_alarm_rings_on_its_days_at_a_time_of_its_own_not_the_minute_it_is_made() {
        val made = alarm(NewElementDefaults.ALARM_TIMES)
        assertNull(made.onlyOnEpochDay)
        assertEquals(NewElementDefaults.DEFAULT_ALARM_MINUTES, made.timeOfDayMinutes)
        assertEquals(NewElementDefaults.ALARM_TIMES, SchedulerState.empty().newAlarmTimes)
        // A reminder is still set at the time it is made.
        assertEquals(NewElementTimes(), SchedulerState.empty().newReminderTimes)
        // "The time it is made" is still a rule to choose.
        assertEquals(14 * 60 + 25, alarm(NewElementTimes()).timeOfDayMinutes)
    }

    @Test
    fun its_date_and_time_follow_the_rules_of_its_default_configuration() {
        assertEquals(today, alarm(NewElementTimes(date = NewDate.Today)).onlyOnEpochDay)
        assertEquals(today + 4, alarm(NewElementTimes(date = NewDate.FromElement), source).onlyOnEpochDay)
        assertEquals(7 * 60, alarm(NewElementTimes(time = NewTime.Fixed, fixedMinutes = 7 * 60)).timeOfDayMinutes)
        assertEquals(6 * 60 + 45, alarm(NewElementTimes(time = NewTime.FromElement), source).timeOfDayMinutes)
    }

    @Test
    fun what_is_not_there_to_take_is_the_day_and_the_time_it_is_made() {
        // Made from no element, or from one that repeats (it has no date).
        assertEquals(today, alarm(NewElementTimes(date = NewDate.FromElement)).onlyOnEpochDay)
        assertEquals(today, alarm(NewElementTimes(date = NewDate.FromElement), source.copy(onlyOnEpochDay = null)).onlyOnEpochDay)
        assertEquals(14 * 60 + 25, alarm(NewElementTimes(time = NewTime.FromElement)).timeOfDayMinutes)
        // A reminder with no time of its own gives none.
        assertEquals(14 * 60 + 25, NewElementDefaults.newMinutes(NewElementTimes(time = NewTime.FromElement), now, utc, null))
    }

    @Test
    fun a_default_never_carries_a_date_of_its_own() {
        val dated = AlarmEntry("", onlyOnEpochDay = today, skippedEpochDays = setOf(today - 1))
        val kept = NewElementDefaults.alarmDefaults(dated)
        assertNull(kept.onlyOnEpochDay)
        assertTrue(kept.skippedEpochDays.isEmpty())
    }

    @Test
    fun an_alarm_and_a_reminder_have_their_default_configuration_among_the_actions() {
        val state = SchedulerState.empty().copy(alarms = listOf(source))
        // One alarm added: its group holds the default configuration of a new alarm.
        val one = SearchDomain.resolve(state, listOf("Alarm/alarm-1"))
        val sections = SearchDomain.actionsFor(SearchDomain.addedActions("", SearchDomain.Kind.entries.toSet(), SearchDomain.actionKindsOf(one)), one)
        val inner = SearchDomain.actionGroups(sections, one).single().inner.single()
        assertEquals("Default configuration of a new alarm", inner.heading)
        assertTrue(listOf(SearchDomain.AddedAction.AlarmTime, SearchDomain.AddedAction.AlarmDays, SearchDomain.AddedAction.AlarmAlert).all { it in inner.actions })
        // A reminder's too.
        val reminderRow = SearchDomain.ItemResult(SearchDomain.Kind.Creation, SearchDomain.Kind.Reminder.name, "New reminder", "")
        assertEquals(SearchDomain.Kind.Reminder, SearchDomain.actionKindOf(reminderRow))
        assertTrue(SearchDomain.AddedAction.ReminderEvery in SearchDomain.DEFAULT_CONFIGURATION_ACTIONS)
    }

    @Test
    fun an_edit_of_the_default_configuration_writes_the_default_and_no_alarm() {
        val state = SchedulerState.empty().copy(alarms = listOf(source))
        val newAlarm = SearchDomain.ItemResult(SearchDomain.Kind.Creation, SearchDomain.Kind.Alarm.name, "New alarm", "")
        val intents = SearchDomain.addedIntents(state, listOf(newAlarm), SearchDomain.AddedCommand.AlarmsEdit { it.copy(soundSeconds = 9) }, now)
        assertEquals(9, intents.filterIsInstance<SchedulerIntent.SetNewAlarmDefaults>().single().defaults.soundSeconds)
        assertTrue(intents.none { it is SchedulerIntent.SetAlarms })
    }

    @Test
    fun the_rules_survive_the_store_and_a_payload_from_before_them_reads_as_before() {
        val times = NewElementTimes(date = NewDate.FromElement, time = NewTime.Fixed, fixedMinutes = 8 * 60)
        val state = SchedulerState.empty().copy(newAlarmTimes = times, newReminderTimes = NewElementTimes(time = NewTime.FromElement))
        val back = SchedulerStateCodec.decode(SchedulerStateCodec.encode(state))!!
        assertEquals(times, back.newAlarmTimes)
        assertEquals(NewTime.FromElement, back.newReminderTimes.time)
        // Untouched, they are no field at all: an account that never set them stores what it stored before, and reads
        // the kind's own — an alarm at a time of its own.
        assertTrue("newAlarmTimes" !in SchedulerStateCodec.encode(SchedulerState.empty()))
        val older = SchedulerStateCodec.decode(SchedulerStateCodec.encode(SchedulerState.empty()))!!
        assertEquals(NewElementDefaults.ALARM_TIMES, older.newAlarmTimes)
        assertEquals(NewElementTimes(), older.newReminderTimes)
        // Set back to "the time it is made", an alarm's rule is no longer its kind's own, so it is stored.
        val madeAt = SchedulerState.empty().copy(newAlarmTimes = NewElementTimes())
        assertEquals(NewElementTimes(), SchedulerStateCodec.decode(SchedulerStateCodec.encode(madeAt))!!.newAlarmTimes)
    }

    @Test
    fun the_action_search_finds_the_default_configuration_like_any_micro_group() {
        val state = SchedulerState.empty().copy(alarms = listOf(source))
        val one = SearchDomain.resolve(state, listOf("Alarm/alarm-1"))
        val sections = SearchDomain.actionsFor(SearchDomain.addedActions("", SearchDomain.Kind.entries.toSet(), SearchDomain.actionKindsOf(one)), one)
        val group = SearchDomain.actionGroups(sections, one, "default").single()
        // Its title answers: it comes first. With nothing typed it stands after the actions, where it always stood.
        assertTrue(SearchDomain.microGroups(group, "default").first() is SearchDomain.MicroGroup.Inner)
        assertTrue(SearchDomain.microGroups(group, "").last() is SearchDomain.MicroGroup.Inner)
        // An action of it answers too, as a text inside it: after an action whose own title answers.
        val alert = SearchDomain.microGroups(group, "alert")
        assertEquals(SearchDomain.MicroGroup.One(SearchDomain.AddedAction.AlarmAlert), alert[0])
        assertTrue(alert[1] is SearchDomain.MicroGroup.Inner)
    }

    @Test
    fun every_micro_group_retracts_on_its_own_and_keeps_it_when_another_is_toggled() {
        val state = SchedulerState.empty().copy(alarms = listOf(source, AlarmEntry("alarm-3")))
        val two = SearchDomain.resolve(state, listOf("Alarm/alarm-1", "Alarm/alarm-3"))
        val sections = SearchDomain.actionsFor(SearchDomain.addedActions("", SearchDomain.Kind.entries.toSet(), SearchDomain.actionKindsOf(two)), two)
        val groups = SearchDomain.actionGroups(sections, two)
        val listed = SearchDomain.listedGroupIds(groups)
        val first = groups.first { it.element }
        val second = groups.last { it.element }
        val time = SearchDomain.microGroupId(first, SearchDomain.AddedAction.AlarmTime)
        // Each action's micro group has an arrow of its own, apart from the same action of another element.
        assertTrue(time in listed)
        assertTrue(time != SearchDomain.microGroupId(second, SearchDomain.AddedAction.AlarmTime))
        var config = SearchDomain.withActionGroupToggled(SearchDomain.Config(), time, listed)
        config = SearchDomain.withActionGroupToggled(config, second.id, listed)
        assertEquals(setOf(time, second.id), config.collapsedActionGroups)
        // "Show only its actions" retracts the groups of elements, not what the user retracted inside them.
        val (only, _) = SearchDomain.withOnlyElementGroupOpen(config, two, two.first())!!
        assertTrue(time in only.collapsedActionGroups)
    }
}
