package org.example.project.scheduler.domain

import org.example.project.scheduler.model.TimerEntry

/**
 * PRD §18 Timers: the pure arithmetic behind the Alarms window's **Timers** section — when a running
 * [TimerEntry] is due, which timers a moving clock ran past, the three transitions a timer row can make
 * (start/resume, pause, reset) and the countdown's own writes — [withCountdownField] for the three typed
 * components and [nudged] for the ± second buttons, both over the [withRemaining] primitive, and both usable
 * **before** the timer is started as well as while it runs.
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
     * it has actually run out. Past zero (a timer that [TimerEntry.goesNegative]) it reads `−0:01`, `−0:02`…
     * — [countdownOf]'s split, so the readout and the fields agree.
     */
    fun formatCountdown(millis: Long): String {
        val c = countdownOf(millis)
        val body =
            if (c.hours > 0) {
                "${c.hours}:${c.minutes.toString().padStart(2, '0')}:${c.seconds.toString().padStart(2, '0')}"
            } else {
                "${c.minutes}:${c.seconds.toString().padStart(2, '0')}"
            }
        return (if (c.negative) "−" else "") + body
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

    /**
     * A countdown split the way the window shows it: [formatCountdown]'s own arithmetic, as numbers. The
     * components are a magnitude; [negative] is the sign, for a timer counting on past zero
     * ([TimerEntry.goesNegative]) — every figure below ([millis], [millisDownTo]) is signed by it, so an edit of
     * a component of `−0:05:10` is measured in the same direction it reads.
     */
    data class TimerCountdown(val hours: Int, val minutes: Int, val seconds: Int, val negative: Boolean = false) {
        fun component(field: TimerField): Int = when (field) {
            TimerField.HOURS -> hours
            TimerField.MINUTES -> minutes
            TimerField.SECONDS -> seconds
        }

        private val sign: Long get() = if (negative) -1L else 1L

        /** The whole countdown as millis, on the second — what a snapped seconds edit banks. */
        val millis: Long get() = sign * (hours.toLong() * 3600L + minutes.toLong() * 60L + seconds.toLong()) * 1_000L

        /** This countdown with [field] set to [value]. */
        fun with(field: TimerField, value: Int): TimerCountdown = when (field) {
            TimerField.HOURS -> copy(hours = value)
            TimerField.MINUTES -> copy(minutes = value)
            TimerField.SECONDS -> copy(seconds = value)
        }

        /** The millis of [field] and every coarser component — what an edit of [field] is a change of. */
        fun millisDownTo(field: TimerField): Long =
            sign * TimerField.entries.filter { it.ordinal <= field.ordinal }.sumOf { component(it) * it.unitMillis }
    }

    /**
     * [millis] as the hours/minutes/seconds the window prints, rounded **up** to the whole second exactly as
     * [formatCountdown] does — the two must agree digit for digit, since one is the readout and the other is
     * what an edit of that readout is measured against.
     */
    fun countdownOf(millis: Long): TimerCountdown {
        // Up above zero (5:00 until it has actually started), down below it: `0:00` is the one second just past
        // the ring, then `−0:01` a second after it — so the readout moves one second per second across zero.
        val total = if (millis >= 0L) (millis + 999L) / 1_000L else -millis / 1_000L
        return TimerCountdown(
            hours = (total / 3600).toInt(),
            minutes = ((total % 3600) / 60).toInt(),
            seconds = (total % 60).toInt(),
            negative = millis < 0L && total > 0L,
        )
    }

    /**
     * PRD §7 *Search*, a timer's own window: the countdown **in reverse** — how far the run has come, which goes
     * up exactly as fast as the countdown goes down: the run's length ([TimerEntry.runMillis]) minus the time
     * left. **A change to the countdown shows here mirrored** — a minute added to the time left is a minute
     * taken off the elapsed time — because the run's length stays put; **the duration does not reach it**, the
     * run's length having been fixed when the run began. An idle row reads 0. Negative when the time left was
     * pushed above the run's length (the window prints that with a minus). Measured against the countdown **as
     * printed** ([countdownOf], rounded up to the second), so the two readouts tick on the same instant.
     */
    fun elapsedMillis(entry: TimerEntry, nowMillis: Long): Long =
        elapsedMillis(entry, countdownOf(entry.remainingAtMillis(nowMillis)))

    /**
     * [elapsedMillis] against the countdown **as the window shows it** — which, while a countdown field is being
     * edited, is not the live one ([displayedCountdown]). The elapsed reading mirrors what is on screen, so it
     * stops exactly when the countdown fields do, and moves with what is typed into them.
     */
    fun elapsedMillis(entry: TimerEntry, shown: TimerCountdown): Long =
        if (entry.idle) 0L else (entry.runMillis ?: entry.durationMillis) - shown.millis

    /**
     * The countdown the window shows while [editing] holds the caret: [editing] and every coarser component as
     * [held] (they stand still while it is edited), the finer ones [live]. With nothing edited, [live].
     */
    fun displayedCountdown(live: TimerCountdown, held: TimerCountdown?, editing: TimerField?): TimerCountdown =
        if (held == null || editing == null) live else live.withHeld(held, through = editing.ordinal)

    /** This countdown with every component down to the one at [through] (an ordinal) taken from [held]. */
    private fun TimerCountdown.withHeld(held: TimerCountdown, through: Int): TimerCountdown =
        TimerField.entries.filter { it.ordinal <= through }.fold(this) { acc, f -> acc.with(f, held.component(f)) }

    /** The run's length, fixed as the timer leaves idle ([TimerEntry.runMillis]). */
    private fun withRunFixed(entry: TimerEntry): TimerEntry =
        if (entry.runMillis != null) entry else entry.copy(runMillis = entry.durationMillis)

    /**
     * An idle row about to begin a NEW run: what the last run left on it — its length and the instant it reached
     * zero ([TimerEntry.endedAtMillis]) — belongs to that run, not this one.
     */
    private fun fresh(entry: TimerEntry): TimerEntry =
        if (entry.idle && (entry.runMillis != null || entry.endedAtMillis != null)) {
            entry.copy(runMillis = null, endedAtMillis = null)
        } else {
            entry
        }

    private const val MAX_MILLIS: Long = TimerEntry.MAX_TIMER_SECONDS.toLong() * 1_000L

    /** The least time left a write may put on [entry]: zero, or as far below it as above for one that goes negative. */
    private fun floorMillis(entry: TimerEntry): Long = if (entry.goesNegative) -MAX_MILLIS else 0L

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
     * An **idle** row is edited too — the countdown is set up *before* the start, which is what the row's
     * button then saying **Resume** rather than *Start* means. [withRemaining] is where that is decided.
     *
     * [held] is the countdown **as the window showed it when the field took the caret**. While a field is in
     * edit mode, it and every coarser field are held still on screen (a number that ticks down under the
     * cursor cannot be typed into), so the edit is measured against those held numbers and not against the
     * live ones — otherwise typing "5" into the minutes while the hours had quietly ticked from 1 to 0 would
     * land on 0:05 when the user saw 1:05. The finer components are the live ones either way.
     */
    fun withCountdownField(
        entry: TimerEntry,
        field: TimerField,
        value: Int,
        nowMillis: Long,
        held: TimerCountdown? = null,
    ): TimerEntry {
        val remaining = entry.remainingAtMillis(nowMillis)
        val live = countdownOf(remaining)
        // What the user sees: the coarser components held, [field] and the finer ones live.
        val shown = held?.let { live.withHeld(it, through = field.ordinal - 1) } ?: live
        if (field == TimerField.SECONDS) {
            val snapped = shown.copy(seconds = value).millis.coerceIn(floorMillis(entry), MAX_MILLIS)
            // Only a RUNNING row is stopped here (that stop is what makes a typed seconds value stick). A
            // paused or idle one has no countdown to stop, so it banks the snapped value like any other
            // write, through the one primitive.
            return if (entry.running) {
                entry.copy(endsAtMillis = null, remainingMillis = snapped)
            } else {
                withRemaining(entry, snapped, nowMillis)
            }
        }
        // A shift, so the finer components (and the sub-second part) run on untouched. Without [held] this is
        // exactly `(value − live) × unit`.
        val delta = shown.with(field, value).millisDownTo(field) - live.millisDownTo(field)
        return withRemaining(entry, remaining + delta, nowMillis)
    }

    /**
     * PRD §18 Timers: shift the time left by [deltaMillis], **leaving the row in the state it is in** — the
     * window's `−10s / −5s / −1s / +1s / +5s / +10s` buttons, which exist precisely so the seconds can be
     * moved without the stop [withCountdownField] makes for a typed seconds value.
     *
     * A running row stays running and simply becomes due that much sooner or later; a paused one stays paused
     * with the new amount banked; an idle one banks the new amount too and so becomes a **paused** row, ready
     * to be resumed at what the buttons made of it ([withRemaining]). Driving a running timer below
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
        val base = fresh(entry)
        val remaining = base.remainingMillis?.coerceAtLeast(floorMillis(base)) ?: base.durationMillis
        // A held countdown past zero resumes past zero, for a timer that goes negative: it has rung already.
        if (remaining <= 0L && !base.goesNegative) return entry
        return withRunFixed(base).copy(endsAtMillis = nowMillis + remaining, remainingMillis = null)
    }

    /**
     * Hold [entry] where it is: bank the time left at [nowMillis] so [started] can resume from it. A timer
     * that is not running has nothing to hold and is returned unchanged.
     */
    fun paused(entry: TimerEntry, nowMillis: Long): TimerEntry {
        val endsAt = entry.endsAtMillis ?: return entry
        return entry.copy(endsAtMillis = null, remainingMillis = (endsAt - nowMillis).coerceAtLeast(floorMillis(entry)))
    }

    /**
     * PRD §18 Timers: put [remainingMillis] on the clock — the primitive under [withCountdownField] and
     * [nudged], and the one place the run fields are written for a countdown edit.
     *
     * Each state answers in its own currency, which is the whole of the rule: a **running** timer's time left
     * is derived from [TimerEntry.endsAtMillis], so it is moved by moving that instant to
     * `nowMillis + remaining` and the row stays running; a **paused** one's is the banked
     * [TimerEntry.remainingMillis], so it is moved by writing it and the row stays paused. Neither ever writes
     * the other's field, which is what keeps the three-state invariant true here without [healed] catching it.
     *
     * An **idle** row banks it too, and so **becomes paused** — a countdown set up before the start is exactly
     * a held one, which is why the window's button then reads *Resume*. The single exception is an edit that
     * lands back on the number the idle row was already showing, its
     * [TimerEntry.durationSeconds]: nothing changed, so nothing is banked and the row stays idle (its *Start*
     * does not turn into a *Resume* for a value retyped as it was). The duration itself is untouched either
     * way — it is a *setting*, the field beside the countdown edits it, and [reset] still goes back to it.
     *
     * [nowMillis] is passed in rather than read, like [started] and [paused], so this stays a pure function of
     * its inputs. The value is clamped into `0..`[TimerEntry.MAX_TIMER_SECONDS] — or as far below zero as above
     * it, for a timer that [TimerEntry.goesNegative].
     */
    fun withRemaining(entry: TimerEntry, remainingMillis: Long, nowMillis: Long): TimerEntry {
        val remaining = remainingMillis.coerceIn(floorMillis(entry), MAX_MILLIS)
        return when {
            entry.running -> entry.copy(endsAtMillis = nowMillis + remaining)
            entry.paused -> entry.copy(remainingMillis = remaining)
            remaining == entry.durationMillis -> entry
            else -> withRunFixed(fresh(entry)).copy(remainingMillis = remaining)
        }
    }

    /**
     * Back to idle at the full duration — the button, and also what a **ring** does: a timer is a one-off by
     * its nature, so going off returns it to the row it was started from rather than disarming it the way a
     * one-off alarm does (there is no on/off switch here to leave off).
     */
    fun reset(entry: TimerEntry): TimerEntry =
        if (entry.idle && entry.runMillis == null && entry.endedAtMillis == null) entry
        else entry.copy(endsAtMillis = null, remainingMillis = null, runMillis = null, endedAtMillis = null)

    /**
     * How far past [rang]'s clock the row may still be due and its end be taken as the instant it reached zero.
     * A row due later than that is not the run that rang (it was restarted meanwhile, here or on a peer), so no
     * instant is kept for it.
     */
    const val RING_TOLERANCE_MILLIS: Long = 2_000L

    /**
     * What a **ring** does to the row that rang, at [nowMillis]. A timer that [TimerEntry.goesNegative] is left
     * running: it rang at zero and counts on below it. Any other goes back to idle like [reset], as a timer
     * always did — but keeps the instant it reached zero ([TimerEntry.endedAtMillis]) and the run's length, so
     * turning the option on afterwards resumes it from there ([withGoesNegative]). That instant is kept only when
     * the row's end is this ring's (not later than [nowMillis]); a row that is not running is left alone.
     */
    fun rang(entry: TimerEntry, nowMillis: Long): TimerEntry {
        val endsAt = entry.endsAtMillis ?: return entry
        if (entry.goesNegative) return entry
        if (endsAt > nowMillis + RING_TOLERANCE_MILLIS) return reset(entry)
        return entry.copy(endsAtMillis = null, remainingMillis = null, endedAtMillis = endsAt)
    }

    /**
     * PRD §18 Timers: set the "goes negative" option of [entry] at [nowMillis] — the ONE setting that moves the
     * run state, because what it says is how a run ends. The row is left **as if the option had always been
     * what it is now set to**:
     *
     * - **on**, on a row that rang with it off ([TimerEntry.endedAtMillis]): it runs again from the instant it
     *   reached zero, so its countdown reads exactly the time since then, below zero, and counts on;
     * - **off**, on a row already past zero: it is what a ring with the option off would have left — idle,
     *   keeping the instant it reached zero (a held countdown below zero is taken to have reached it that long
     *   before [nowMillis]), so turning the option back on puts it back where it was.
     *
     * Otherwise only the flag moves. Pure — [nowMillis] is the caller's clock, like every run-state write here.
     */
    fun withGoesNegative(entry: TimerEntry, on: Boolean, nowMillis: Long): TimerEntry {
        if (entry.goesNegative == on) return entry
        val flipped = entry.copy(goesNegative = on)
        if (on) {
            val ended = entry.endedAtMillis ?: return flipped
            if (!entry.idle) return flipped.copy(endedAtMillis = null)
            return flipped.copy(endsAtMillis = ended, endedAtMillis = null, runMillis = entry.runMillis ?: entry.durationMillis)
        }
        val endsAt = entry.endsAtMillis
        val banked = entry.remainingMillis
        return when {
            endsAt != null && endsAt < nowMillis -> flipped.copy(endsAtMillis = null, endedAtMillis = endsAt)
            banked != null && banked < 0L -> flipped.copy(remainingMillis = null, endedAtMillis = nowMillis + banked)
            else -> flipped
        }
    }

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
        val remaining = if (entry.endsAtMillis != null) null else entry.remainingMillis?.coerceAtLeast(floorMillis(entry))
        // Only an idle row remembers where its last run ended.
        val ended = if (entry.endsAtMillis != null || remaining != null) null else entry.endedAtMillis
        // An idle row has no run, so no run length — unless it keeps the one that rang; a negative one is not a
        // length.
        val run =
            if (entry.endsAtMillis == null && remaining == null && ended == null) null
            else entry.runMillis?.coerceIn(0L, MAX_MILLIS)
        val result = entry.copy(durationSeconds = duration, remainingMillis = remaining, runMillis = run, endedAtMillis = ended)
        // A row that goes negative never rests idle at an instant it reached zero (a merge of the option from one
        // side and the ring from the other): it counts on from there, as [withGoesNegative] would have put it.
        if (result.goesNegative && ended != null) {
            return result.copy(endsAtMillis = ended, endedAtMillis = null, runMillis = run ?: result.durationMillis)
        }
        return if (result == entry) entry else result
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
