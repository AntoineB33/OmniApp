package org.example.project.scheduler.domain

import org.example.project.scheduler.model.ChronoEntry

/**
 * PRD §18 Chronos: the pure arithmetic behind the Alarms window's **Chronos** section — the three transitions a
 * chronometer row can make (start/resume, pause, reset) and the healing of its run fields.
 *
 * Deliberately [TimerDomain]'s shape turned round: a timer counts DOWN to an instant it rings at, a chrono counts
 * UP from the instant it was started and rings at nothing — so there is no arming, no sweep and no calendar
 * marker here, only the time it shows ([ChronoEntry.elapsedAtMillis]).
 */
object ChronoDomain {
    /** Start [entry] from zero, or resume it from what a pause banked. A chrono already running is unchanged. */
    fun started(entry: ChronoEntry, nowMillis: Long): ChronoEntry =
        if (entry.running) entry else entry.copy(startedAtMillis = nowMillis)

    /** Hold [entry] where it is: bank what it counted up to [nowMillis]. One not running is unchanged. */
    fun paused(entry: ChronoEntry, nowMillis: Long): ChronoEntry =
        if (!entry.running) entry else entry.copy(startedAtMillis = null, bankedMillis = entry.elapsedAtMillis(nowMillis))

    /** Back to 0:00, stopped. */
    fun reset(entry: ChronoEntry): ChronoEntry =
        if (entry.idle) entry else entry.copy(startedAtMillis = null, bankedMillis = 0L)

    /**
     * The run fields in the one shape they are allowed to be in: nothing banked below zero. Applied on decode, on
     * merge and in the reducer — the timers' rule ([TimerDomain.healed]), for a row whose fields are synced.
     */
    fun healed(entry: ChronoEntry): ChronoEntry =
        if (entry.bankedMillis >= 0L) entry else entry.copy(bankedMillis = 0L)

    /** How the Alarms window and the Search results print a chrono: `M:SS`, or `H:MM:SS` from an hour up. */
    fun format(millis: Long): String = TimerDomain.formatCountdown(millis.coerceAtLeast(0L) / 1_000L * 1_000L)

    /** Mints an id no chrono in [existing] uses — the alarms' and timers' scheme. */
    fun mintChronoId(existing: Collection<String>): String {
        val used = existing.toSet()
        var n = 0
        while ("chrono-$n" in used) n++
        return "chrono-$n"
    }

    /** Fills any blank id in [chronos] with a fresh unique one. */
    fun assignChronoIds(chronos: List<ChronoEntry>): List<ChronoEntry> {
        if (chronos.none { it.id.isBlank() }) return chronos
        val used = chronos.filter { it.id.isNotBlank() }.mapTo(mutableSetOf()) { it.id }
        return chronos.map { entry ->
            if (entry.id.isNotBlank()) {
                entry
            } else {
                val id = mintChronoId(used)
                used.add(id)
                entry.copy(id = id)
            }
        }
    }
}
