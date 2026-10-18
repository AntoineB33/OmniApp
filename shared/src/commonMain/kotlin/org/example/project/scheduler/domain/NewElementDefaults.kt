package org.example.project.scheduler.domain

import org.example.project.scheduler.model.AlarmEntry
import org.example.project.scheduler.model.AlertSettings
import org.example.project.scheduler.model.ChoreEntry
import org.example.project.scheduler.model.TimerEntry
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/** User rule 2026-10-18: where a new alarm's DATE comes from ([NewElementTimes.date]). */
enum class NewDate(val label: String) {
    /** None: it rings on its days, as every new alarm did before. */
    OnItsDays("on its days"),
    Today("the day it is made"),
    /** The date of the alarm whose "New alarm" made it — that alarm's group's button; else the day it is made. */
    FromElement("the day of the alarm it is made from"),
}

/** User rule 2026-10-18: where a new alarm's or reminder's TIME of day comes from ([NewElementTimes.time]). */
enum class NewTime(val label: String) {
    /** The time it is made at, as every new one was before. */
    MadeAt("the time it is made"),
    /** [NewElementTimes.fixedMinutes]. */
    Fixed("a time of its own"),
    /** The time of the element whose "New" made it — that element's group's button; else the time it is made. */
    FromElement("the time of the one it is made from"),
}

/**
 * User rule 2026-10-18 (*"the default configuration can define a default date, make it to the current day, to the day of
 * the alarm element where the user clicked on New alarm if that is how it got created, same thing for the time field"*):
 * **when a new element is set**, part of its kind's default configuration. Authoritative, persisted + synced beside the
 * default it belongs to ([org.example.project.scheduler.state.SchedulerState.newAlarmTimes]), not an Undo/Redo unit.
 * What a kind starts with is [NewElementDefaults.ALARM_TIMES] / [NewElementDefaults.REMINDER_TIMES].
 */
data class NewElementTimes(
    val date: NewDate = NewDate.OnItsDays,
    val time: NewTime = NewTime.MadeAt,
    /** The time of day [NewTime.Fixed] gives, in minutes. */
    val fixedMinutes: Int = 0,
) {
    /** These times as stored: [fixedMinutes] within a day. */
    fun healed(): NewElementTimes = copy(fixedMinutes = fixedMinutes.coerceIn(0, AlarmEntry.MINUTES_PER_DAY - 1))
}

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

    /**
     * User rule 2026-10-18 (*"by default, the default configuration for a new alarm is a default time, so that it doesn't
     * ring as soon as the user is creating a new alarm"*): a new alarm rings on its days at [DEFAULT_ALARM_MINUTES] —
     * made at the time it is made, it rang the minute it was made.
     */
    const val DEFAULT_ALARM_MINUTES: Int = 7 * 60
    val ALARM_TIMES: NewElementTimes = NewElementTimes(time = NewTime.Fixed, fixedMinutes = DEFAULT_ALARM_MINUTES)

    /** A new reminder is set at the time it is made, as it always was. */
    val REMINDER_TIMES: NewElementTimes = NewElementTimes()

    /**
     * The time of day [times] gives a new element made at [nowMillis] from the element of time [fromMinutes] (null: made
     * from none, or from one with no time) — the time it is made at where the rule asks for what is not there.
     */
    fun newMinutes(times: NewElementTimes, nowMillis: Long, timeZone: TimeZone, fromMinutes: Int?): Int {
        val now = Instant.fromEpochMilliseconds(nowMillis).toLocalDateTime(timeZone).let { it.hour * 60 + it.minute }
        return when (times.time) {
            NewTime.MadeAt -> now
            NewTime.Fixed -> times.fixedMinutes
            NewTime.FromElement -> fromMinutes ?: now
        }
    }

    /**
     * The date [times] gives a new alarm made at [nowMillis] from the alarm [from] (null: made from none): none (it rings
     * on its days), the day it is made, or [from]'s date — the day it is made where [from] has none to give (it repeats,
     * or there is no [from]).
     */
    fun newAlarmEpochDay(times: NewElementTimes, nowMillis: Long, timeZone: TimeZone, from: AlarmEntry?): Long? {
        val today = Instant.fromEpochMilliseconds(nowMillis).toLocalDateTime(timeZone).date.toEpochDays().toLong()
        return when (times.date) {
            NewDate.OnItsDays -> null
            NewDate.Today -> today
            NewDate.FromElement -> from?.onlyOnEpochDay ?: today
        }
    }

    /** **A new alarm** from [defaults] and its [times], made at [nowMillis] — by "New alarm" in the group of [from], if so. */
    fun newAlarm(defaults: AlarmEntry, times: NewElementTimes, id: String, nowMillis: Long, timeZone: TimeZone, from: AlarmEntry?): AlarmEntry =
        newAlarm(defaults, id, newMinutes(times, nowMillis, timeZone, from?.timeOfDayMinutes))
            .copy(onlyOnEpochDay = newAlarmEpochDay(times, nowMillis, timeZone, from))

    // No date of its own, nor dates it skips: when a new one rings is its [NewElementTimes], not its default's.
    fun newAlarm(defaults: AlarmEntry, id: String, timeOfDayMinutes: Int): AlarmEntry =
        defaults.copy(id = id, label = "", timeOfDayMinutes = timeOfDayMinutes, onlyOnEpochDay = null, skippedEpochDays = emptySet())

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
