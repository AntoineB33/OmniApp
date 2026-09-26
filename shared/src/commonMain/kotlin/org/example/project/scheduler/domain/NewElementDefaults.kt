package org.example.project.scheduler.domain

import org.example.project.scheduler.model.AlarmEntry
import org.example.project.scheduler.model.AlertSettings
import org.example.project.scheduler.model.ChoreEntry
import org.example.project.scheduler.model.TimerEntry

/**
 * PRD §14/§18: **what a new alarm, timer or reminder starts with** — the account's default configuration of that
 * kind, edited in its "Default …" window (the button at the bottom of any alarm's, timer's or reminder's own
 * window), and the ONE place a new element is built from it. Every way of making one goes through here: the
 * windows' "+ New …" and "+ Add …", and the calendar's "add…" for an alarm.
 *
 * A default is kept as a whole element of its kind, but only its SETTINGS are defaults: a new element has its
 * own id, a blank name and — an alarm, a reminder — the time of day it is made at, and a timer starts idle. The
 * setters store a default stripped of all of that ([alarmDefaults], …), and the codec heals a decoded one the
 * same way, so a default never carries an identity or a run.
 */
object NewElementDefaults {
    /** The built-in defaults: what every new element started with before the account could change them. */
    val ALARM: AlarmEntry = AlarmEntry(id = "")
    val TIMER: TimerEntry = TimerEntry(id = "")
    val REMINDER: ChoreEntry = ChoreEntry(title = "", spanDays = 0.0, alert = AlertSettings.REMINDER)

    fun newAlarm(defaults: AlarmEntry, id: String, timeOfDayMinutes: Int): AlarmEntry =
        defaults.copy(id = id, label = "", timeOfDayMinutes = timeOfDayMinutes)

    fun newTimer(defaults: TimerEntry, id: String): TimerEntry =
        TimerEntry(
            id = id,
            durationSeconds = defaults.durationSeconds,
            soundSeconds = defaults.soundSeconds,
            alert = defaults.alert,
            goesNegative = defaults.goesNegative,
        )

    fun newReminder(defaults: ChoreEntry, id: String, timeOfDayMinutes: Int): ChoreEntry =
        defaults.copy(id = id, title = "", timeOfDayMinutes = timeOfDayMinutes)

    /** [entry] as a default is kept: its settings, nothing of its identity. */
    fun alarmDefaults(entry: AlarmEntry): AlarmEntry = newAlarm(entry, id = "", timeOfDayMinutes = 0)

    fun timerDefaults(entry: TimerEntry): TimerEntry = newTimer(entry, id = "")

    fun reminderDefaults(entry: ChoreEntry): ChoreEntry = newReminder(entry, id = "", timeOfDayMinutes = 0)
}
