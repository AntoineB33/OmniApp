package org.example.project.scheduler.domain

import org.example.project.scheduler.model.TimerEntry

/**
 * PRD §18 Timers: the pure arithmetic behind the Alarms window's **Timers** section — when a running
 * [TimerEntry] is due, which timers a moving clock ran past, the three transitions a timer row can make
 * (start/resume, pause, reset) and the countdown's own writes — [withCountdownField] for the three typed
 * components and [nudged] for the ± second buttons, both over the [withRemaining] primitive.
 *
 * The counterpart of [AlarmDomain], and deliberately much smaller: an alarm's boundary has to be derived from
 * the local calendar on every day it rings, whereas a timer's boundary IS its stored
 * [TimerEntry.endsAtMillis] — one absolute instant, fixed at the moment it was started. There is therefore no
 * time zone here and no DST question, and the CLAUDE.md trigger rule is satisfied trivially: the crossings a
 * clock interval passed are exactly the running timers whose end instant falls in it, so each fires once, in
 * order, however the sweep happens to align.
 *
 * Everything downstream of the due instant is the alarms' machinery unchanged — the phone arms the soonest
 * of *both* lists with the OS, the desktop sweep merges both crossing streams, and both ring through
 * `SchedulerEngine.onAlarmFire`.
 */
object TimerDomain {
    /** One timer going off: the [entry] that rings and the exact instant it was due at. */
    data class TimerOccurrence(val entry: TimerEntry, val instant: Long)

    /**
     * The instant [entry] rings, **strictly after** [afterMillis], or null when it is not running (idle or
     * paused rows are not due — a timer that is not counting down simply has no boundary) or can never ring
     * ([TimerEntry.schedulable]).
     */
    fun nextOccurrenceMillis(entry: TimerEntry, afterMillis: Long): Long? =
        entry.endsAtMillis?.takeIf { entry.schedulable && it > afterMillis }

    /**
     * The soonest ring across [timers] strictly after [afterMillis] — what a device arms its OS-level alarm
     * for (jointly with [AlarmDomain.nextOccurrence]; only the soonest of the two is ever armed). Ties are
     * broken by id so every device picks the same one.
     */
    fun nextOccurrence(timers: List<TimerEntry>, afterMillis: Long): TimerOccurrence? =
        timers
            .mapNotNull { entry -> nextOccurrenceMillis(entry, afterMillis)?.let { TimerOccurrence(entry, it) } }
            .minWithOrNull(compareBy({ it.instant }, { it.entry.id }))

    /**
     * Every timer instant in `(fromMillis, toMillis]`, in boundary order (ties by id) — the rings a now-line
     * that moved from [fromMillis] to [toMillis] passed. Half-open at the start, exactly like
     * [AlarmDomain.crossingsBetween], so consecutive sweeps tile the timeline with no gaps and no double-fire
     * when one sweep's end is the next one's start.
     */
    fun crossingsBetween(
        timers: List<TimerEntry>,
        fromMillis: Long,
        toMillis: Long,
    ): List<TimerOccurrence> {
        if (toMillis <= fromMillis) return emptyList()
        return timers
            .mapNotNull { entry ->
                entry.endsAtMillis
                    ?.takeIf { entry.schedulable && it > fromMillis && it <= toMillis }
                    ?.let { TimerOccurrence(entry, it) }
            }
            .sortedWith(compareBy({ it.instant }, { it.entry.id }))
    }

    /**
     * The timer rings falling in `[fromMillis, toMillis)` — what the calendar DRAWS over the window it is
     * showing. Closed at the start and open at the end so each ring belongs to exactly one displayed window,
     * and bounded by that window rather than by the account's timers' history, per the CLAUDE.md hot-path
     * rule.
     *
     * A timer has at most ONE occurrence, and only while it is running: its due instant is stored, not
     * derived from the local calendar, so there is no per-day walk here and nothing to draw for an idle or
     * paused row (a timer that is not counting down has no instant to mark). That is the whole difference
     * from [AlarmDomain.occurrencesInWindow] — the marker itself is the alarms' unchanged.
     */
    fun occurrencesInWindow(
        timers: List<TimerEntry>,
        fromMillis: Long,
        toMillis: Long,
    ): List<TimerOccurrence> = crossingsBetween(timers, fromMillis - 1, toMillis - 1)

    /**
     * A duration in seconds as `M:SS`, or `H:MM:SS` once it reaches an hour — the Alarms window's countdown
     * column and the calendar marker's label for a timer with no name. It lives here, once, so the two can
     * never disagree about how long a timer is.
     */
    fun formatDuration(seconds: Int): String = formatCountdown(seconds.toLong() * 1_000L)

    /**
     * PRD §18 Timers: how much is left, as `M:SS` (or `H:MM:SS` from an hour up). Rounded **up** to the next
     * whole second, so a freshly started 5:00 timer reads 5:00 rather than 4:59 and `0:00` appears only when
     * it has actually run out.
     */
    fun formatCountdown(millis: Long): String {
        val total = ((millis.coerceAtLeast(0L) + 999L) / 1_000L)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) {
            "$h:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}"
        } else {
            "$m:${s.toString().padStart(2, '0')}"
        }
    }

    /**
     * PRD §18 Timers: which of the Alarms window's three countdown fields an edit is about, and how many
     * millis one of its units is worth. The unit is the whole of what the field means — setting it moves the
     * countdown by *its own* delta, so the finer components are left exactly where they are.
     */
    enum class TimerField(val unitMillis: Long) {
        HOURS(3_600_000L),
        MINUTES(60_000L),
        SECONDS(1_000L),
    }

    /** A countdown split the way the window shows it: [formatCountdown]'s own arithmetic, as numbers. */
    data class TimerCountdown(val hours: Int, val minutes: Int, val seconds: Int) {
        fun component(field: TimerField): Int = when (field) {
            TimerField.HOURS -> hours
            TimerField.MINUTES -> minutes
            TimerField.SECONDS -> seconds
        }

        /** The whole countdown as millis, on the second — what a snapped seconds edit banks. */
        val millis: Long get() = (hours.toLong() * 3600L + minutes.toLong() * 60L + seconds.toLong()) * 1_000L
    }

    /**
     * [millis] as the hours/minutes/seconds the window prints, rounded **up** to the whole second exactly as
     * [formatCountdown] does — the two must agree digit for digit, since one is the readout and the other is
     * what an edit of that readout is measured against.
     */
    fun countdownOf(millis: Long): TimerCountdown {
        val total = (millis.coerceAtLeast(0L) + 999L) / 1_000L
        return TimerCountdown(
            hours = (total / 3600).toInt(),
            minutes = ((total % 3600) / 60).toInt(),
            seconds = (total % 60).toInt(),
        )
    }

    /**
     * PRD §18 Timers: set one component of [entry]'s countdown to [value] — what the window's three countdown
     * fields write.
     *
     * **The edit is a SHIFT by that component's own delta, not a rewrite of the countdown**, which is the
     * whole reason the finer components carry on untouched: setting the hours moves the due instant by
     * `(value − hours) × 1 h`, so the minutes and seconds under it go on reading down through the edit without
     * so much as a jump; setting the minutes likewise leaves the seconds running. That is the difference
     * between "make it 2 hours" and "restart it at 2 hours", and only the first is what the user asked for.
     *
     * **[TimerField.SECONDS] is the exception, and it PAUSES the row** (the window's Pause button becomes
     * Resume). The seconds field is the one that is itself reading down, so a value typed into a running timer
     * would be consumed by the very next tick — there is no way to *set* it while it moves. So that edit
     * stops the countdown and snaps it to the whole second the user typed, which is what makes the value
     * stick; the ± buttons ([nudged]) are how the seconds are moved **without** stopping.
     *
     * An **idle** row is returned unchanged, for the reason [withRemaining] gives.
     */
    fun withCountdownField(
        entry: TimerEntry,
        field: TimerField,
        value: Int,
        nowMillis: Long,
    ): TimerEntry {
        if (entry.idle) return entry
        val remaining = entry.remainingAtMillis(nowMillis)
        val shown = countdownOf(remaining)
        if (field == TimerField.SECONDS) {
            val snapped = shown.copy(seconds = value).millis
                .coerceIn(0L, TimerEntry.MAX_TIMER_SECONDS.toLong() * 1_000L)
            return if (entry.endsAtMillis == null && entry.remainingMillis == snapped) {
                entry
            } else {
                entry.copy(endsAtMillis = null, remainingMillis = snapped)
            }
        }
        val delta = (value - shown.component(field)).toLong() * field.unitMillis
        return withRemaining(entry, remaining + delta, nowMillis)
    }

    /**
     * PRD §18 Timers: shift the time left by [deltaMillis], **leaving the row in the state it is in** — the
     * window's `−10s / −5s / −1s / +1s / +5s / +10s` buttons, which exist precisely so the seconds can be
     * moved without the stop [withCountdownField] makes for a typed seconds value.
     *
     * A running row stays running and simply becomes due that much sooner or later; a paused one stays paused
     * with the new amount banked; an idle one is unchanged ([withRemaining]). Driving a running timer below
     * zero leaves it due **now**, so it rings — the honest answer to "take ten more seconds off a countdown
     * with three left", and the same clamp every other write here goes through.
     */
    fun nudged(entry: TimerEntry, deltaMillis: Long, nowMillis: Long): TimerEntry =
        withRemaining(entry, entry.remainingAtMillis(nowMillis) + deltaMillis, nowMillis)

    /**
     * Start [entry] (from its full duration) or resume it (from what a pause banked), due at [nowMillis] plus
     * whatever is left. A timer already running is returned unchanged — pressing start twice must not push
     * its end away — and so is one with nothing to count down.
     */
    fun started(entry: TimerEntry, nowMillis: Long): TimerEntry {
        if (entry.running) return entry
        val remaining = entry.remainingMillis?.coerceAtLeast(0L) ?: entry.durationMillis
        if (remaining <= 0L) return entry
        return entry.copy(endsAtMillis = nowMillis + remaining, remainingMillis = null)
    }

    /**
     * Hold [entry] where it is: bank the time left at [nowMillis] so [started] can resume from it. A timer
     * that is not running has nothing to hold and is returned unchanged.
     */
    fun paused(entry: TimerEntry, nowMillis: Long): TimerEntry {
        val endsAt = entry.endsAtMillis ?: return entry
        return entry.copy(endsAtMillis = null, remainingMillis = (endsAt - nowMillis).coerceAtLeast(0L))
    }

    /**
     * PRD §18 Timers: put [remainingMillis] on the clock **without changing which of the three states the row
     * is in** — the primitive under [withCountdownField] and [nudged], and the one place the run fields are
     * written for a countdown edit.
     *
     * The two states that have something to count down answer it in their own currency, which is the whole of
     * the rule: a **running** timer's time left is derived from [TimerEntry.endsAtMillis], so it is moved by
     * moving that instant to `nowMillis + remaining`; a **paused** one's is the banked
     * [TimerEntry.remainingMillis], so it is moved by writing it. An **idle** row is returned unchanged: it is
     * not counting down at all, and the number it shows is its [TimerEntry.durationSeconds] — a *setting*, and
     * the field beside it in the window is what edits that.
     *
     * [nowMillis] is passed in rather than read, like [started] and [paused], so this stays a pure function of
     * its inputs. The value is clamped into `0..`[TimerEntry.MAX_TIMER_SECONDS].
     */
    fun withRemaining(entry: TimerEntry, remainingMillis: Long, nowMillis: Long): TimerEntry {
        val remaining = remainingMillis.coerceIn(0L, TimerEntry.MAX_TIMER_SECONDS.toLong() * 1_000L)
        return when {
            entry.running -> entry.copy(endsAtMillis = nowMillis + remaining)
            entry.paused -> entry.copy(remainingMillis = remaining)
            else -> entry
        }
    }

    /**
     * Back to idle at the full duration — the button, and also what a **ring** does: a timer is a one-off by
     * its nature, so going off returns it to the row it was started from rather than disarming it the way a
     * one-off alarm does (there is no on/off switch here to leave off).
     */
    fun reset(entry: TimerEntry): TimerEntry =
        if (entry.idle) entry else entry.copy(endsAtMillis = null, remainingMillis = null)

    /**
     * The at-most-one-non-null invariant of [TimerEntry]'s two run fields, applied. A running timer wins over
     * a banked remainder (it has a real instant to fire at, the other is a leftover), and the duration is
     * clamped into its allowed range.
     *
     * Exists because both halves are persisted and synced: a merge that took [TimerEntry.endsAtMillis] from
     * one side and [TimerEntry.remainingMillis] from the other, or a payload an older/hand-edited build wrote,
     * can hold a combination the current invariants forbid — and CLAUDE.md says decode must **heal** those,
     * not surface them.
     */
    fun healed(entry: TimerEntry): TimerEntry {
        val duration = entry.durationSeconds.coerceIn(1, TimerEntry.MAX_TIMER_SECONDS)
        val remaining = if (entry.endsAtMillis != null) null else entry.remainingMillis?.coerceAtLeast(0L)
        return if (duration == entry.durationSeconds && remaining == entry.remainingMillis) {
            entry
        } else {
            entry.copy(durationSeconds = duration, remainingMillis = remaining)
        }
    }

    /** Mints an id no timer in [existing] uses, mirroring the alarms' `alarm-{n}` scheme. */
    fun mintTimerId(existing: Collection<String>): String {
        val used = existing.toSet()
        var n = 0
        while ("timer-$n" in used) n++
        return "timer-$n"
    }

    /** Fills any blank id in [timers] with a fresh unique one (ids are minted on save, like the alarms'). */
    fun assignTimerIds(timers: List<TimerEntry>): List<TimerEntry> {
        if (timers.none { it.id.isBlank() }) return timers
        val used = timers.filter { it.id.isNotBlank() }.mapTo(mutableSetOf()) { it.id }
        return timers.map { entry ->
            if (entry.id.isNotBlank()) {
                entry
            } else {
                val id = mintTimerId(used)
                used.add(id)
                entry.copy(id = id)
            }
        }
    }
}
