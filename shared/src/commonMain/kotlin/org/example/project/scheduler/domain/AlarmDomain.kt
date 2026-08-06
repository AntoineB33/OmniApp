package org.example.project.scheduler.domain

import kotlin.time.Instant
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.minus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import org.example.project.scheduler.model.AlarmEntry

/**
 * PRD §18 Alarms: the pure time arithmetic behind the alarm rings — when an [AlarmEntry] next goes off and
 * which alarm instants a clock interval crossed.
 *
 * Per CLAUDE.md, a trigger must be a pure function of the boundary instants the clock crossed (each fires
 * exactly once, in order), never of how a sweep happens to align with the calendar: an alarm's boundary is
 * the **fixed** wall-clock instant `startOfDay(localDay) + timeOfDayMinutes` **on a day the alarm rings on**
 * ([AlarmEntry.days], every day by default), so [crossingsBetween] returns every such instant in a half-open
 * interval and a jump over several days still yields each one, in order. Everything here is derived from the
 * local calendar, so an alarm keeps its wall-clock time across a DST shift (the instant moves, the displayed
 * time does not) and its days are the days the *user's* calendar shows.
 */
object AlarmDomain {
    /** One alarm going off: the [entry] that rings and the exact boundary [instant] it was due at. */
    data class AlarmOccurrence(val entry: AlarmEntry, val instant: Long)

    /**
     * The next instant [entry] rings, **strictly after** [afterMillis], or null when it can never ring
     * ([AlarmEntry.schedulable]). Walks forward from the local day containing [afterMillis]; [SCAN_DAYS] days
     * is always enough (today's occurrence may already have passed, then the next selected weekday is at most
     * a week out, and a DST-skipped local time lands on the following day's).
     */
    fun nextOccurrenceMillis(entry: AlarmEntry, afterMillis: Long, timeZone: TimeZone): Long? {
        if (!entry.schedulable) return null
        var date = Instant.fromEpochMilliseconds(afterMillis).toLocalDateTime(timeZone).date
        repeat(SCAN_DAYS) {
            if (entry.ringsOn(date.dayOfWeek)) {
                val instant = occurrenceMillis(entry, date, timeZone)
                if (instant > afterMillis) return instant
            }
            date = date.plus(DatePeriod(days = 1))
        }
        return null
    }

    /**
     * The soonest ring across [alarms] strictly after [afterMillis] — what a device arms its OS-level alarm
     * for. Ties (two alarms at the same minute) are broken by id so every device picks the same one.
     */
    fun nextOccurrence(alarms: List<AlarmEntry>, afterMillis: Long, timeZone: TimeZone): AlarmOccurrence? =
        alarms
            .mapNotNull { entry -> nextOccurrenceMillis(entry, afterMillis, timeZone)?.let { AlarmOccurrence(entry, it) } }
            .minWithOrNull(compareBy({ it.instant }, { it.entry.id }))

    /**
     * Every alarm instant in `(fromMillis, toMillis]`, in boundary order (ties by id) — the rings a now-line
     * that moved from [fromMillis] to [toMillis] passed. Half-open at the start so consecutive sweeps tile the
     * timeline with no gaps and no double-fire when one sweep's end is the next one's start.
     */
    fun crossingsBetween(
        alarms: List<AlarmEntry>,
        fromMillis: Long,
        toMillis: Long,
        timeZone: TimeZone,
    ): List<AlarmOccurrence> = occurrencesIn(alarms, fromMillis, toMillis, timeZone)

    /**
     * Every alarm instant in `[fromMillis, toMillis)` — what the calendar DRAWS over the window it is
     * showing. Closed at the start and open at the end so each occurrence belongs to exactly one displayed
     * window, and bounded by that window rather than by the account's history, per the CLAUDE.md hot-path
     * rule (cost follows the screen: days-on-screen × alarms).
     */
    fun occurrencesInWindow(
        alarms: List<AlarmEntry>,
        fromMillis: Long,
        toMillis: Long,
        timeZone: TimeZone,
    ): List<AlarmOccurrence> = occurrencesIn(alarms, fromMillis - 1, toMillis - 1, timeZone)

    /**
     * Every occurrence of every schedulable alarm in `(fromMillis, toMillis]` — the shared engine behind
     * [crossingsBetween] and [occurrencesInWindow], which differ only in which end of their half-open window
     * they keep (the display form shifts both bounds down by a millisecond to close the start instead).
     */
    private fun occurrencesIn(
        alarms: List<AlarmEntry>,
        fromMillis: Long,
        toMillis: Long,
        timeZone: TimeZone,
    ): List<AlarmOccurrence> {
        if (toMillis <= fromMillis) return emptyList()
        val schedulable = alarms.filter { it.schedulable }
        if (schedulable.isEmpty()) return emptyList()
        // A local time of day can only produce one instant per local day, so scanning the days the interval
        // touches (padded by one on each side for time-zone offsets) sees every crossing exactly once.
        val firstDate = Instant.fromEpochMilliseconds(fromMillis).toLocalDateTime(timeZone).date
            .minus(DatePeriod(days = 1))
        val lastDate = Instant.fromEpochMilliseconds(toMillis).toLocalDateTime(timeZone).date
            .plus(DatePeriod(days = 1))
        val result = mutableListOf<AlarmOccurrence>()
        for (entry in schedulable) {
            var date = firstDate
            while (date <= lastDate) {
                // The days the alarm is triggered on: a weekday the user left out yields no boundary at all,
                // so it neither rings nor draws.
                if (entry.ringsOn(date.dayOfWeek)) {
                    val instant = occurrenceMillis(entry, date, timeZone)
                    if (instant > fromMillis && instant <= toMillis) result.add(AlarmOccurrence(entry, instant))
                }
                date = date.plus(DatePeriod(days = 1))
            }
        }
        return result.sortedWith(compareBy({ it.instant }, { it.entry.id }))
    }

    /**
     * The instant [entry] is due at on the local day [date].
     *
     * Built from the local *wall-clock time* (not `startOfDay + minutes`), so the alarm keeps its displayed
     * time across a DST transition: on a spring-forward day `startOfDay + 7h` would be 08:00 local, while
     * 07:00 is what the user set. A local time the shift skips entirely is resolved forward by
     * [LocalDateTime.toInstant] (the alarm still rings exactly once that day).
     */
    private fun occurrenceMillis(
        entry: AlarmEntry,
        date: LocalDate,
        timeZone: TimeZone,
    ): Long {
        val minutes = entry.timeOfDayMinutes.coerceIn(0, AlarmEntry.MINUTES_PER_DAY - 1)
        return LocalDateTime(date.year, date.month, date.day, minutes / 60, minutes % 60)
            .toInstant(timeZone)
            .toEpochMilliseconds()
    }

    /**
     * How many local days forward [nextOccurrenceMillis] looks: a full week (the longest gap between two of
     * an alarm's selected days) plus today, whose occurrence may already have passed, plus one for a local
     * time a DST shift skips entirely.
     */
    private const val SCAN_DAYS: Int = 9

    /** Mints an id no alarm in [existing] uses, mirroring the reminders' `reminder-{n}` scheme. */
    fun mintAlarmId(existing: Collection<String>): String {
        val used = existing.toSet()
        var n = 0
        while ("alarm-$n" in used) n++
        return "alarm-$n"
    }

    /** Fills any blank id in [alarms] with a fresh unique one (ids are minted on save, like reminders'). */
    fun assignAlarmIds(alarms: List<AlarmEntry>): List<AlarmEntry> {
        if (alarms.none { it.id.isBlank() }) return alarms
        val used = alarms.filter { it.id.isNotBlank() }.mapTo(mutableSetOf()) { it.id }
        return alarms.map { entry ->
            if (entry.id.isNotBlank()) {
                entry
            } else {
                val id = mintAlarmId(used)
                used.add(id)
                entry.copy(id = id)
            }
        }
    }
}
