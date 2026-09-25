package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.datetime.DayOfWeek
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.example.project.scheduler.domain.NewElementDefaults
import org.example.project.scheduler.model.AlarmEntry
import org.example.project.scheduler.model.AlertSettings
import org.example.project.scheduler.model.ChoreEntry
import org.example.project.scheduler.model.ChoreRecurrenceUnit
import org.example.project.scheduler.model.TimerEntry
import org.example.project.scheduler.persistence.SchedulerStateCodec
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState
import org.example.project.scheduler.sync.EntityRows
import org.example.project.ui.ObjectWindowKey

/**
 * PRD §14/§18: the account's default configuration of a new alarm, timer and reminder — the one funnel a new
 * element is built through, the setting that holds it, and the payloads written before it existed.
 */
class NewElementDefaultsTest {

    private val quietAlarm =
        AlarmEntry(
            id = "alarm-7", label = "Wake up", timeOfDayMinutes = 420, soundSeconds = 12,
            alert = AlertSettings.REMINDER, days = setOf(DayOfWeek.MONDAY), repeats = false, enabled = false,
        )
    private val longTimer =
        TimerEntry(id = "timer-2", label = "Tea", durationSeconds = 900, soundSeconds = 20, endsAtMillis = 5_000L, runMillis = 900_000L)
    private val weeklyReminder =
        ChoreEntry(
            title = "Pay rent", spanDays = 7.0, timeOfDayMinutes = 600, daysFormula = "7", id = "reminder-3",
            recurrenceUnit = ChoreRecurrenceUnit.Days, alert = AlertSettings.RING,
        )

    @Test
    fun a_new_element_takes_the_settings_never_the_identity_name_time_or_run() {
        val alarm = NewElementDefaults.newAlarm(quietAlarm, id = "alarm-9", timeOfDayMinutes = 1_000)
        assertEquals(quietAlarm.copy(id = "alarm-9", label = "", timeOfDayMinutes = 1_000), alarm)

        val timer = NewElementDefaults.newTimer(longTimer, id = "timer-5")
        assertEquals(TimerEntry(id = "timer-5", durationSeconds = 900, soundSeconds = 20, alert = longTimer.alert), timer)
        assertTrue(timer.idle, "a new timer starts idle")

        val reminder = NewElementDefaults.newReminder(weeklyReminder, id = "reminder-8", timeOfDayMinutes = 30)
        assertEquals(weeklyReminder.copy(id = "reminder-8", title = "", timeOfDayMinutes = 30), reminder)
    }

    @Test
    fun the_built_in_defaults_are_what_a_new_element_had_before() {
        val s = SchedulerState.empty()
        assertEquals(AlarmEntry(id = ""), s.newAlarmDefaults)
        assertEquals(TimerEntry(id = ""), s.newTimerDefaults)
        assertEquals(AlertSettings.REMINDER, s.newReminderDefaults.alert)
        assertEquals(0.0, s.newReminderDefaults.spanDays)
    }

    @Test
    fun the_setters_keep_settings_only_and_record_no_history_unit() {
        val before = SchedulerState.empty()
        val after = SchedulerReducer.reduce(before, SchedulerIntent.SetNewTimerDefaults(longTimer))
        assertEquals(NewElementDefaults.timerDefaults(longTimer), after.newTimerDefaults)
        assertEquals("", after.newTimerDefaults.id)
        assertNull(after.newTimerDefaults.endsAtMillis)
        assertEquals(before.histories, after.histories)

        val alarms = SchedulerReducer.reduce(after, SchedulerIntent.SetNewAlarmDefaults(quietAlarm))
        assertEquals("", alarms.newAlarmDefaults.label)
        assertEquals(0, alarms.newAlarmDefaults.timeOfDayMinutes)
        assertEquals(setOf(DayOfWeek.MONDAY), alarms.newAlarmDefaults.days)

        // Setting what is already there changes nothing.
        assertSame(alarms, SchedulerReducer.reduce(alarms, SchedulerIntent.SetNewAlarmDefaults(quietAlarm)))
    }

    // ----- persisted-DB compatibility (CLAUDE.md) ----------------------------------------------------------

    @Test
    fun a_payload_written_before_the_defaults_existed_decodes_to_the_built_in_ones() {
        val encoded = SchedulerStateCodec.encode(SchedulerState.empty())
        val root = Json.parseToJsonElement(encoded).jsonObject
        assertTrue("newAlarmDefaults" in root)
        val older = JsonObject(root - setOf("newAlarmDefaults", "newTimerDefaults", "newReminderDefaults")).toString()
        val decoded = assertNotNull(SchedulerStateCodec.decode(older))
        assertEquals(NewElementDefaults.ALARM, decoded.newAlarmDefaults)
        assertEquals(NewElementDefaults.TIMER, decoded.newTimerDefaults)
        assertEquals(NewElementDefaults.REMINDER, decoded.newReminderDefaults)
    }

    @Test
    fun defaults_the_user_set_survive_the_codec_and_the_sync_rows() {
        var s = SchedulerState.empty()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetNewAlarmDefaults(quietAlarm))
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetNewTimerDefaults(longTimer))
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetNewReminderDefaults(weeklyReminder))

        val decoded = assertNotNull(SchedulerStateCodec.decode(SchedulerStateCodec.encode(s)))
        assertEquals(s.newAlarmDefaults, decoded.newAlarmDefaults)
        assertEquals(s.newTimerDefaults, decoded.newTimerDefaults)
        assertEquals(s.newReminderDefaults, decoded.newReminderDefaults)

        // Synced as one field row each, and joined back into a payload that decodes to the same.
        val payload = SchedulerStateCodec.syncFingerprint(s).statePayload
        val rows = EntityRows.split(payload)
        assertTrue(EntityRows.Key(EntityRows.FIELD_KIND, "newTimerDefaults") in rows)
        val pulled = assertNotNull(SchedulerStateCodec.decode(EntityRows.join(rows)))
        assertEquals(s.newTimerDefaults, pulled.newTimerDefaults)
    }

    @Test
    fun a_stored_default_that_carries_an_identity_is_healed_to_settings_only() {
        val encoded = SchedulerStateCodec.encode(SchedulerState.empty().copy(newTimerDefaults = longTimer))
        val decoded = assertNotNull(SchedulerStateCodec.decode(encoded))
        assertEquals(NewElementDefaults.timerDefaults(longTimer), decoded.newTimerDefaults)
    }

    // ----- the default configuration's window ---------------------------------------------------------------

    @Test
    fun the_default_configuration_windows_are_always_there_and_named_by_what_they_are() {
        val s = SchedulerState.empty()
        for ((kind, title) in listOf(
            ObjectWindowKey.Kind.AlarmDefaults to "Default alarm",
            ObjectWindowKey.Kind.TimerDefaults to "Default timer",
            ObjectWindowKey.Kind.ReminderDefaults to "Default reminder",
        )) {
            val key = ObjectWindowKey(kind, "")
            assertTrue(key.exists(s))
            assertEquals(title, key.buttonTitle(s))
            assertEquals(key, ObjectWindowKey.decode(key.encode()))
        }
    }
}
