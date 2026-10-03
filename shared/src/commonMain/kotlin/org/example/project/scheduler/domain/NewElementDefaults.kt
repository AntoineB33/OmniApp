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

    /**
     * User rule 2026-10-03: what a new quota starts with before the account says otherwise. Its loop is UNSET (start
     * and end both 0): a new quota then runs over the week it is made in, from its Monday ([newQuota]).
     */
    val QUOTA: org.example.project.scheduler.model.QuotaEntry = org.example.project.scheduler.model.QuotaEntry(id = "", title = "Quota")

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

    /**
     * A new quota from [defaults]: its title, amount, unit, resilience and whether it repeats, over the loop of the
     * default's that [nowMillis] is in — so a default "every week from Wednesday 08:00" makes a quota whose first loop
     * is THIS week's — or, while the default's loop is unset, over `[weekStartMillis, weekEndMillis)`, the week being
     * lived. A default that does not repeat keeps its own dates. Nothing particular to one loop is carried.
     */
    fun newQuota(
        defaults: org.example.project.scheduler.model.QuotaEntry,
        id: String,
        nowMillis: Long,
        weekStartMillis: Long,
        weekEndMillis: Long,
    ): org.example.project.scheduler.model.QuotaEntry {
        val base = quotaDefaults(defaults)
        val (start, end) =
            when {
                !quotaLoopSet(base) -> weekStartMillis to weekEndMillis
                base.repeats -> QuotaDomain.loopAt(base, nowMillis).let { it.startMillis to it.endMillis }
                else -> base.startMillis to base.endMillis
            }
        return base.copy(id = id, startMillis = start, endMillis = end)
    }

    /** Whether a default quota says when its loop runs (else: the week a quota is made in). */
    fun quotaLoopSet(defaults: org.example.project.scheduler.model.QuotaEntry): Boolean = defaults.endMillis > defaults.startMillis

    /** [entry] as a default is kept: its settings, no identity, no loop of its own particulars; an unset loop is 0..0. */
    fun quotaDefaults(entry: org.example.project.scheduler.model.QuotaEntry): org.example.project.scheduler.model.QuotaEntry {
        val set = entry.endMillis > entry.startMillis
        return entry.copy(
            id = "",
            amount = entry.amount.takeIf { it.isFinite() && it >= 0.0 } ?: 1.0,
            startMillis = if (set) entry.startMillis else 0L,
            endMillis = if (set) entry.endMillis else 0L,
            resilience = entry.resilience.mapValues { PeriodKinds.clamp(it.value) },
            loops = emptyList(),
        )
    }

    /** [entry] as a default is kept: its settings, nothing of its identity. */
    fun alarmDefaults(entry: AlarmEntry): AlarmEntry = newAlarm(entry, id = "", timeOfDayMinutes = 0)

    fun timerDefaults(entry: TimerEntry): TimerEntry = newTimer(entry, id = "")

    fun reminderDefaults(entry: ChoreEntry): ChoreEntry = newReminder(entry, id = "", timeOfDayMinutes = 0)
}
