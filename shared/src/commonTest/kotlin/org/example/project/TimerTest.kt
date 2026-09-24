package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.TimerDomain
import org.example.project.scheduler.model.AlarmEntry
import org.example.project.scheduler.model.AlertSettings
import org.example.project.scheduler.model.TimerEntry
import org.example.project.scheduler.persistence.SchedulerStateCodec
import org.example.project.scheduler.state.HistoryCategory
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState

/**
 * PRD §18 Timers: the Alarms window's second section — a countdown the user starts on demand, which rings
 * through the alarms' own machinery when it runs out.
 *
 * Covers the [TimerDomain] arithmetic (when a running timer is due, the crossings a moving now-line passed,
 * the three run transitions, and the countdown's own writes [TimerDomain.withCountdownField] /
 * [TimerDomain.nudged]), the [SchedulerIntent.SetTimers] / [SchedulerIntent.StartTimer] /
 * [SchedulerIntent.PauseTimer] / [SchedulerIntent.ResetTimer] / [SchedulerIntent.SetTimerCountdownField] /
 * [SchedulerIntent.NudgeTimerRemaining] mutations, and persistence — including that a
 * DB written **before** timers existed still loads and that a payload holding a run shape the current
 * invariants forbid is healed rather than surfaced (CLAUDE.md persisted-DB rule). The engine half is in
 * [TimerEngineTest]; the window itself is UI code and not unit-tested here.
 */
class TimerTest {

    private val second = 1_000L
    private val minute = 60 * second
    private val hour = 60 * minute

    /** An arbitrary but fixed "now", so nothing here depends on the wall clock. */
    private val now = 1_800_000_000_000L

    private fun timer(
        id: String = "timer-0",
        durationSeconds: Int = 5 * 60,
        soundSeconds: Int = 30,
        alert: AlertSettings = AlertSettings.RING,
        label: String = "",
        endsAtMillis: Long? = null,
        remainingMillis: Long? = null,
    ) = TimerEntry(
        id = id,
        label = label,
        durationSeconds = durationSeconds,
        soundSeconds = soundSeconds,
        alert = alert,
        endsAtMillis = endsAtMillis,
        remainingMillis = remainingMillis,
    )

    // ----- the three states ---------------------------------------------------------------------

    @Test
    fun a_fresh_timer_is_idle_and_shows_its_whole_duration() {
        val t = timer(durationSeconds = 90)
        assertTrue(t.idle)
        assertFalse(t.running)
        assertFalse(t.paused)
        assertEquals(90 * second, t.remainingAtMillis(now))
        assertFalse(t.schedulable, "an idle timer is not due — it is not a silenced one either")
    }

    @Test
    fun starting_makes_it_due_one_duration_from_now_and_the_remaining_time_is_derived() {
        val started = TimerDomain.started(timer(durationSeconds = 300), now)
        assertTrue(started.running)
        assertEquals(now + 5 * minute, started.endsAtMillis)
        assertNull(started.remainingMillis, "a running timer banks no remainder — it is derived")
        // The countdown is a pure function of the end instant and the now-line: nothing is written as it runs.
        assertEquals(5 * minute, started.remainingAtMillis(now))
        assertEquals(4 * minute, started.remainingAtMillis(now + minute))
        assertEquals(0L, started.remainingAtMillis(now + 10 * minute), "it never reads below zero")
    }

    @Test
    fun pausing_banks_what_is_left_and_resuming_continues_from_it() {
        val started = TimerDomain.started(timer(durationSeconds = 300), now)
        val paused = TimerDomain.paused(started, now + 2 * minute)

        assertTrue(paused.paused)
        assertNull(paused.endsAtMillis, "a paused timer is not due at any instant")
        assertEquals(3 * minute, paused.remainingMillis)
        assertEquals(3 * minute, paused.remainingAtMillis(now + 10 * minute), "a held timer does not read down")

        // Resuming is the same transition as starting: it is what is LEFT that differs.
        val resumed = TimerDomain.started(paused, now + 10 * minute)
        assertTrue(resumed.running)
        assertEquals(now + 13 * minute, resumed.endsAtMillis)
        assertNull(resumed.remainingMillis)
    }

    @Test
    fun resetting_returns_it_to_idle_at_the_full_duration() {
        val running = TimerDomain.started(timer(durationSeconds = 300), now)
        val reset = TimerDomain.reset(running)
        assertTrue(reset.idle)
        assertEquals(5 * minute, reset.remainingAtMillis(now))
        assertEquals(timer(durationSeconds = 300), reset, "nothing but the run state is touched")
    }

    @Test
    fun the_transitions_are_no_ops_where_they_have_nothing_to_do() {
        val running = TimerDomain.started(timer(), now)
        assertEquals(running, TimerDomain.started(running, now + minute), "start twice must not push the end away")
        val idle = timer()
        assertEquals(idle, TimerDomain.paused(idle, now), "there is nothing to hold")
        assertEquals(idle, TimerDomain.reset(idle))
        assertEquals(
            idle.copy(durationSeconds = 0),
            TimerDomain.started(idle.copy(durationSeconds = 0), now),
            "a timer with nothing to count down does not start",
        )
    }

    // ----- editing the time left ----------------------------------------------------------------

    @Test
    fun the_countdown_splits_the_way_the_window_prints_it() {
        // countdownOf and formatCountdown must agree digit for digit: one is the readout, the other is what an
        // edit of that readout is measured against.
        assertEquals(TimerDomain.TimerCountdown(0, 5, 0), TimerDomain.countdownOf(5 * minute))
        assertEquals(TimerDomain.TimerCountdown(2, 3, 4), TimerDomain.countdownOf(2 * hour + 3 * minute + 4 * second))
        // Rounded UP, like formatCountdown: a timer 4:59.6 from its end still reads 5:00.
        assertEquals(TimerDomain.TimerCountdown(0, 5, 0), TimerDomain.countdownOf(5 * minute - 400L))
        assertEquals(TimerDomain.TimerCountdown(0, 0, 0), TimerDomain.countdownOf(-1L))
    }

    @Test
    fun setting_the_hours_leaves_the_minutes_and_seconds_running() {
        // 2:30:45 left, and the sub-second phase deliberately non-zero so a rewrite would show up as a jump.
        val running = timer(endsAtMillis = now + 2 * hour + 30 * minute + 45 * second - 400L)
        val edited = TimerDomain.withCountdownField(running, TimerDomain.TimerField.HOURS, 5, now)

        assertTrue(edited.running, "editing the hours must not stop the timer")
        assertEquals(
            running.endsAtMillis!! + 3 * hour,
            edited.endsAtMillis,
            "the due instant SHIFTS by the hours delta - it is not rewritten",
        )
        // Which is exactly what "the minutes and seconds don't stop" means: they are where they were, and they
        // go on reading down through the edit.
        assertEquals(TimerDomain.TimerCountdown(5, 30, 45), TimerDomain.countdownOf(edited.remainingAtMillis(now)))
        assertEquals(
            TimerDomain.TimerCountdown(5, 30, 44),
            TimerDomain.countdownOf(edited.remainingAtMillis(now + second)),
        )
    }

    @Test
    fun an_edit_is_measured_against_the_numbers_held_on_screen_not_the_live_ones() {
        // The minutes field took the caret at 1:00:02, which the window holds on screen; the seconds ran on,
        // and the hours quietly ticked to 0 underneath (0:59:58 live). Typing 5 into the minutes is asked of
        // the 1:0_:__ the user sees: it lands on 1:05, the live seconds unchanged — never 0:05.
        val running = timer(endsAtMillis = now + 59 * minute + 58 * second)
        val held = TimerDomain.TimerCountdown(1, 0, 2)
        val edited = TimerDomain.withCountdownField(running, TimerDomain.TimerField.MINUTES, 5, now, held)
        assertTrue(edited.running)
        assertEquals(TimerDomain.TimerCountdown(1, 5, 58), TimerDomain.countdownOf(edited.remainingAtMillis(now)))

        // The seconds snap to the held hours and minutes too.
        val seconds = TimerDomain.withCountdownField(running, TimerDomain.TimerField.SECONDS, 30, now, held)
        assertEquals(TimerDomain.TimerCountdown(1, 0, 30), TimerDomain.countdownOf(seconds.remainingAtMillis(now)))

        // With nothing held the edit is the plain shift it always was.
        assertEquals(
            TimerDomain.withCountdownField(running, TimerDomain.TimerField.MINUTES, 5, now),
            TimerDomain.withCountdownField(running, TimerDomain.TimerField.MINUTES, 5, now, null),
        )
    }

    @Test
    fun the_elapsed_reading_is_the_countdown_in_reverse() {
        val running = TimerDomain.started(timer(durationSeconds = 300), now)
        assertEquals(0L, TimerDomain.elapsedMillis(running, now))
        // It goes up exactly as the countdown goes down, second for second, and the two add up to the duration.
        val later = now + 61 * second + 300L
        assertEquals(61 * second, TimerDomain.elapsedMillis(running, later))
        assertEquals(
            300 * second,
            TimerDomain.elapsedMillis(running, later) + TimerDomain.countdownOf(running.remainingAtMillis(later)).millis,
        )
        // Nudged above its duration, the run is "before its start": negative.
        val nudged = TimerDomain.nudged(running, 10 * second, now)
        assertEquals(-10 * second, TimerDomain.elapsedMillis(nudged, now))
    }

    @Test
    fun the_elapsed_reading_stands_still_with_the_countdown_fields_a_caret_holds() {
        // The seconds field took the caret at 4:00 left and holds it; the timer runs on underneath.
        val running = TimerDomain.started(timer(durationSeconds = 300), now)
        val held = TimerDomain.countdownOf(running.remainingAtMillis(now + 60 * second))
        val later = TimerDomain.countdownOf(running.remainingAtMillis(now + 75 * second))

        // Every field is held while the SECONDS are edited: the countdown shows 4:00, and Elapsed 1:00.
        val shown = TimerDomain.displayedCountdown(later, held, TimerDomain.TimerField.SECONDS)
        assertEquals(TimerDomain.TimerCountdown(0, 4, 0), shown)
        assertEquals(60 * second, TimerDomain.elapsedMillis(running, shown))

        // While the MINUTES are edited the seconds run on — and Elapsed with them.
        val minutes = TimerDomain.displayedCountdown(later, held, TimerDomain.TimerField.MINUTES)
        assertEquals(TimerDomain.TimerCountdown(0, 4, 45), minutes)
        assertEquals(15 * second, TimerDomain.elapsedMillis(running, minutes))

        // With nothing edited it is the live reading.
        assertEquals(later, TimerDomain.displayedCountdown(later, held, null))
        assertEquals(TimerDomain.elapsedMillis(running, now + 75 * second), TimerDomain.elapsedMillis(running, later))
    }

    @Test
    fun a_countdown_change_is_mirrored_in_the_elapsed_reading_and_the_duration_is_not() {
        val running = TimerDomain.started(timer(durationSeconds = 300), now)
        val t = now + 60 * second
        assertEquals(60 * second, TimerDomain.elapsedMillis(running, t))

        // A minute added to the countdown is a minute taken off the elapsed time — by the menu or typed.
        val nudged = TimerDomain.nudged(running, 60 * second, t)
        assertEquals(0L, TimerDomain.elapsedMillis(nudged, t))
        val typed = TimerDomain.withCountdownField(running, TimerDomain.TimerField.MINUTES, 2, t)
        assertEquals(3 * minute, TimerDomain.elapsedMillis(typed, t), "4:00 left typed down to 2:00: two more elapsed")

        // The Duration — what Reset goes back to — does not reach the running reading.
        val longer = running.copy(durationSeconds = 900)
        assertEquals(60 * second, TimerDomain.elapsedMillis(longer, t))
        // A paused run neither.
        val paused = TimerDomain.paused(running, t)
        assertEquals(60 * second, TimerDomain.elapsedMillis(paused.copy(durationSeconds = 10), t + hour))

        // An idle row has no run: 0, whatever its duration. A countdown dialled in before the start is a
        // run, measured against the duration of that moment.
        val idle = timer(durationSeconds = 300)
        assertEquals(0L, TimerDomain.elapsedMillis(idle.copy(durationSeconds = 600), now))
        val dialled = TimerDomain.withCountdownField(idle, TimerDomain.TimerField.MINUTES, 4, now)
        assertEquals(minute, TimerDomain.elapsedMillis(dialled.copy(durationSeconds = 30), now))

        // Reset ends the run, and a new one starts from the duration as it now is.
        val reset = TimerDomain.reset(longer)
        assertNull(reset.runMillis)
        assertEquals(900 * second, TimerDomain.started(reset, t).runMillis)
    }

    @Test
    fun setting_the_minutes_leaves_the_seconds_running() {
        val running = timer(endsAtMillis = now + 5 * minute + 20 * second)
        val edited = TimerDomain.withCountdownField(running, TimerDomain.TimerField.MINUTES, 2, now)

        assertTrue(edited.running)
        assertEquals(now + 2 * minute + 20 * second, edited.endsAtMillis, "shifted by -3 minutes; the 20 s stay")
        assertEquals(
            TimerDomain.TimerCountdown(0, 2, 19),
            TimerDomain.countdownOf(edited.remainingAtMillis(now + second)),
        )
    }

    @Test
    fun setting_the_seconds_stops_the_timer_and_snaps_the_countdown() {
        // The seconds are the digit that is itself reading down, so a typed value could only be consumed by
        // the next tick. The edit therefore pauses the row - the window's Pause button becomes Resume - and
        // snaps to the whole second asked for, which is what makes it stick.
        val running = timer(endsAtMillis = now + 5 * minute + 20 * second - 400L)
        val edited = TimerDomain.withCountdownField(running, TimerDomain.TimerField.SECONDS, 45, now)

        assertTrue(edited.paused, "typing the seconds is the one countdown edit that stops it")
        assertNull(edited.endsAtMillis)
        assertEquals(5 * minute + 45 * second, edited.remainingMillis, "on the second, so the typed value holds")
        assertEquals(5 * minute + 45 * second, edited.remainingAtMillis(now + hour), "held: it does not read down")
        // And the row resumes from what was typed.
        assertEquals(now + hour + 5 * minute + 45 * second, TimerDomain.started(edited, now + hour).endsAtMillis)
    }

    @Test
    fun editing_a_paused_timers_components_leaves_it_paused() {
        val paused = timer(remainingMillis = 5 * minute + 20 * second)
        val hours = TimerDomain.withCountdownField(paused, TimerDomain.TimerField.HOURS, 1, now)
        assertTrue(hours.paused)
        assertEquals(hour + 5 * minute + 20 * second, hours.remainingMillis)

        val seconds = TimerDomain.withCountdownField(paused, TimerDomain.TimerField.SECONDS, 5, now)
        assertTrue(seconds.paused, "nothing to stop - it is already held")
        assertEquals(5 * minute + 5 * second, seconds.remainingMillis)
    }

    @Test
    fun the_component_edits_never_touch_the_rows_settings() {
        val running = TimerDomain.started(timer(durationSeconds = 300, soundSeconds = 30, label = "Tea"), now)
        val edited = TimerDomain.withCountdownField(running, TimerDomain.TimerField.MINUTES, 2, now)
        assertEquals(300, edited.durationSeconds, "the DURATION is a setting: the next start from idle is 5:00")
        assertEquals(30, edited.soundSeconds)
        assertEquals("Tea", edited.label)
    }

    @Test
    fun the_nudge_buttons_move_the_seconds_without_stopping_the_timer() {
        val running = timer(endsAtMillis = now + 5 * minute)
        val plus = TimerDomain.nudged(running, 10 * second, now)
        assertTrue(plus.running, "this is the whole point of the buttons: the countdown does not stop")
        assertEquals(now + 5 * minute + 10 * second, plus.endsAtMillis)

        val minus = TimerDomain.nudged(running, -5 * second, now)
        assertTrue(minus.running)
        assertEquals(now + 4 * minute + 55 * second, minus.endsAtMillis)

        // A paused row stays paused, with the new amount banked.
        val paused = TimerDomain.nudged(timer(remainingMillis = 30 * second), -10 * second, now)
        assertTrue(paused.paused)
        assertEquals(20 * second, paused.remainingMillis)
    }

    @Test
    fun a_nudge_past_zero_leaves_a_running_timer_due_now() {
        // The honest answer to "take ten more seconds off a countdown with three left", and the same clamp
        // every other write here goes through.
        val edited = TimerDomain.nudged(timer(endsAtMillis = now + 3 * second), -10 * second, now)
        assertEquals(now, edited.endsAtMillis)
        assertEquals(0L, edited.remainingAtMillis(now))
    }

    @Test
    fun editing_an_idle_timers_countdown_banks_it_and_the_row_reads_resume() {
        // The countdown is dialled in BEFORE the start as readily as during the run: the amount is banked,
        // which is precisely a PAUSED row - so the window's Start button becomes Resume and the run begins
        // from what was typed rather than from the duration.
        val idle = timer(durationSeconds = 300)

        val minutes = TimerDomain.withCountdownField(idle, TimerDomain.TimerField.MINUTES, 2, now)
        assertTrue(minutes.paused, "a countdown set up before the start is a held one")
        assertEquals(2 * minute, minutes.remainingMillis)
        assertEquals(now + 2 * minute, TimerDomain.started(minutes, now).endsAtMillis, "Resume runs the 2:00")

        // The DURATION is a setting and the field beside the countdown is what edits it, so it is untouched -
        // and Reset still goes back to it. That is what keeps the two numbers one each rather than two for one.
        assertEquals(300, minutes.durationSeconds)
        assertTrue(TimerDomain.reset(minutes).idle)
        assertEquals(5 * minute, TimerDomain.reset(minutes).remainingAtMillis(now))

        // Each field still shifts by its own unit, idle or not.
        val hours = TimerDomain.withCountdownField(idle, TimerDomain.TimerField.HOURS, 1, now)
        assertEquals(hour + 5 * minute, hours.remainingMillis)

        // And the seconds, which stop a RUNNING row, have nothing to stop here - they just bank the snap.
        val seconds = TimerDomain.withCountdownField(idle, TimerDomain.TimerField.SECONDS, 30, now)
        assertTrue(seconds.paused)
        assertEquals(5 * minute + 30 * second, seconds.remainingMillis)
    }

    @Test
    fun the_nudge_buttons_work_before_the_start_too() {
        val idle = timer(durationSeconds = 300)
        val plus = TimerDomain.nudged(idle, 10 * second, now)
        assertTrue(plus.paused)
        assertEquals(5 * minute + 10 * second, plus.remainingMillis)
        // Nudged back down, it is HELD at the full duration rather than idle again: a row that has been
        // dialled in stays held whatever the number lands on (a paused row is written in its own currency,
        // always), and Reset is what puts it back to idle. Resuming 5:00 and starting 5:00 run the same run.
        val back = TimerDomain.nudged(plus, -10 * second, now)
        assertTrue(back.paused)
        assertEquals(5 * minute, back.remainingMillis)
        assertTrue(TimerDomain.reset(back).idle)
    }

    @Test
    fun an_edit_that_changes_nothing_leaves_an_idle_row_idle() {
        // Retyping the number the row was already showing must not turn Start into Resume: nothing moved.
        val idle = timer(durationSeconds = 300)
        assertEquals(idle, TimerDomain.withCountdownField(idle, TimerDomain.TimerField.MINUTES, 5, now))
        assertEquals(idle, TimerDomain.withCountdownField(idle, TimerDomain.TimerField.SECONDS, 0, now))
        assertEquals(idle, TimerDomain.nudged(idle, 0L, now))
        assertEquals(idle, TimerDomain.withRemaining(idle, 5 * minute, now))
    }

    @Test
    fun a_countdown_edit_is_clamped_into_the_allowed_range() {
        val running = TimerDomain.started(timer(), now)
        val maxMillis = TimerEntry.MAX_TIMER_SECONDS.toLong() * second
        assertEquals(now + maxMillis, TimerDomain.withRemaining(running, 99L * 24 * hour, now).endsAtMillis)
        assertEquals(now, TimerDomain.withRemaining(running, -5_000L, now).endsAtMillis)
        // The hours field tops out at 24, so the coarsest edit it can make is clamped rather than overflowing.
        assertEquals(
            now + maxMillis,
            TimerDomain.withCountdownField(running, TimerDomain.TimerField.HOURS, 24, now).endsAtMillis,
        )
    }

    // ----- when a timer is due ------------------------------------------------------------------

    @Test
    fun only_a_running_timer_is_ever_due() {
        val idle = timer()
        val paused = timer(remainingMillis = minute)
        val running = timer(endsAtMillis = now + minute)

        assertNull(TimerDomain.nextOccurrenceMillis(idle, now))
        assertNull(TimerDomain.nextOccurrenceMillis(paused, now), "a held timer has no boundary at all")
        assertEquals(now + minute, TimerDomain.nextOccurrenceMillis(running, now))
        // A ring already behind the cursor is not "next".
        assertNull(TimerDomain.nextOccurrenceMillis(running, now + 2 * minute))
        assertNull(
            TimerDomain.nextOccurrenceMillis(timer(endsAtMillis = now + minute, soundSeconds = 0), now),
            "a ring of no length can never sound",
        )
    }

    @Test
    fun next_occurrence_across_timers_picks_the_soonest_then_the_id() {
        val timers = listOf(
            timer(id = "timer-2", endsAtMillis = now + 9 * minute),
            timer(id = "timer-1", endsAtMillis = now + minute),
            timer(id = "timer-0", endsAtMillis = now + minute),
        )
        val next = TimerDomain.nextOccurrence(timers, now)
        assertNotNull(next)
        assertEquals("timer-0", next.entry.id, "a tie is broken by id so every device picks the same one")
        assertEquals(now + minute, next.instant)
    }

    @Test
    fun crossings_are_half_open_so_consecutive_sweeps_neither_gap_nor_double_fire() {
        // CLAUDE.md: consecutive scans must tile the timeline — the end instant belongs to the sweep that
        // ends on it, and to that one only.
        val t = timer(endsAtMillis = now + minute)
        assertEquals(
            listOf(now + minute),
            TimerDomain.crossingsBetween(listOf(t), now, now + minute).map { it.instant },
        )
        assertTrue(
            TimerDomain.crossingsBetween(listOf(t), now + minute, now + 2 * minute).isEmpty(),
            "the next sweep, starting where that one ended, must not fire it again",
        )
        assertTrue(TimerDomain.crossingsBetween(listOf(t), now, now).isEmpty(), "an empty window crosses nothing")
    }

    @Test
    fun a_long_jump_yields_every_crossed_timer_in_boundary_order() {
        // A clock leap (or a sweep the app was slow to run) must still fire each one, in order.
        val timers = listOf(
            timer(id = "timer-0", endsAtMillis = now + 30 * minute),
            timer(id = "timer-1", endsAtMillis = now + 2 * minute),
            timer(id = "timer-2", endsAtMillis = now + 10 * minute),
            timer(id = "timer-3"), // idle: never crossed
        )
        assertEquals(
            listOf("timer-1", "timer-2", "timer-0"),
            TimerDomain.crossingsBetween(timers, now, now + 60 * minute).map { it.entry.id },
        )
    }

    // ----- what the calendar draws --------------------------------------------------------------

    @Test
    fun the_display_window_is_closed_at_the_start_so_a_ring_belongs_to_one_window_only() {
        // The mirror of [AlarmDomain.occurrencesInWindow]: a ring exactly on a day boundary is drawn by the
        // window that STARTS there, never by the one that ends there, so scrolling never doubles or loses it.
        val t = timer(endsAtMillis = now + minute)
        assertEquals(
            listOf(now + minute),
            TimerDomain.occurrencesInWindow(listOf(t), now, now + 2 * minute).map { it.instant },
        )
        assertEquals(
            listOf(now + minute),
            TimerDomain.occurrencesInWindow(listOf(t), now + minute, now + 2 * minute).map { it.instant },
            "the window opening on the ring draws it",
        )
        assertTrue(
            TimerDomain.occurrencesInWindow(listOf(t), now, now + minute).isEmpty(),
            "the window ending on the ring does not",
        )
    }

    @Test
    fun only_a_running_timer_is_drawn_and_only_once() {
        // A timer's instant is stored, not derived per day, so unlike an everyday alarm it marks the
        // calendar at most once — and an idle or paused row has no instant at all.
        val timers = listOf(
            timer(id = "timer-0", endsAtMillis = now + 10 * minute),
            timer(id = "timer-1"), // idle
            timer(id = "timer-2", remainingMillis = 42 * second), // paused
            timer(id = "timer-3", soundSeconds = 0, endsAtMillis = now + minute), // can never ring
        )
        assertEquals(
            listOf("timer-0"),
            TimerDomain.occurrencesInWindow(timers, now, now + 7L * 24 * 60 * minute).map { it.entry.id },
        )
    }

    @Test
    fun a_nameless_timer_is_named_by_its_duration() {
        // The calendar marker's label falls back to this, exactly as an alarm's falls back to its time of
        // day — and it is the Alarms window's own countdown spelling, from the one place it lives.
        assertEquals("5:00", TimerDomain.formatDuration(5 * 60))
        assertEquals("0:45", TimerDomain.formatDuration(45))
        assertEquals("1:30:00", TimerDomain.formatDuration(90 * 60))
        assertEquals("5:00", TimerDomain.formatCountdown(5 * minute), "rounded up: a fresh 5:00 reads 5:00")
        assertEquals("0:01", TimerDomain.formatCountdown(1), "and 0:00 only once it has really run out")
        assertEquals("0:00", TimerDomain.formatCountdown(0))
    }

    // ----- ids and healing ----------------------------------------------------------------------

    @Test
    fun ids_are_minted_in_the_alarms_scheme_and_never_collide() {
        assertEquals("timer-0", TimerDomain.mintTimerId(emptyList()))
        assertEquals("timer-2", TimerDomain.mintTimerId(listOf("timer-0", "timer-1")))
        val assigned = TimerDomain.assignTimerIds(listOf(timer(id = "timer-1"), timer(id = "")))
        assertEquals(listOf("timer-1", "timer-0"), assigned.map { it.id })
    }

    @Test
    fun healing_keeps_the_instant_a_timer_is_due_at_over_a_stale_remainder() {
        // The two run fields are both persisted and both synced, so a merge (or an older build) can produce a
        // row holding both. CLAUDE.md: decode HEALS such a state rather than surfacing it.
        val both = timer(endsAtMillis = now + minute, remainingMillis = 9 * minute)
        val healed = TimerDomain.healed(both)
        assertEquals(now + minute, healed.endsAtMillis)
        assertNull(healed.remainingMillis)
        assertTrue(healed.running)
        // A negative remainder is clamped, and a duration outside its range is brought back into it.
        assertEquals(0L, TimerDomain.healed(timer(remainingMillis = -5L)).remainingMillis)
        assertEquals(1, TimerDomain.healed(timer(durationSeconds = 0)).durationSeconds)
        assertEquals(
            TimerEntry.MAX_TIMER_SECONDS,
            TimerDomain.healed(timer(durationSeconds = TimerEntry.MAX_TIMER_SECONDS * 2)).durationSeconds,
        )
        val clean = timer(endsAtMillis = now + minute)
        assertEquals(clean, TimerDomain.healed(clean), "a legal row is returned untouched")
    }

    // ----- the reducer --------------------------------------------------------------------------

    @Test
    fun timers_default_to_an_empty_list() {
        assertTrue(SchedulerState.empty().timers.isEmpty())
    }

    @Test
    fun set_timers_stores_the_list_and_mints_ids_for_blank_rows() {
        val s = SchedulerReducer.reduce(
            SchedulerState.empty(),
            SchedulerIntent.SetTimers(listOf(timer(id = ""), timer(id = "", durationSeconds = 60))),
        )
        assertEquals(listOf("timer-0", "timer-1"), s.timers.map { it.id })
        assertEquals(60, s.timers[1].durationSeconds)
    }

    @Test
    fun set_timers_replaces_the_list_and_is_a_no_op_when_unchanged() {
        val s1 = SchedulerReducer.reduce(SchedulerState.empty(), SchedulerIntent.SetTimers(listOf(timer())))
        val s2 = SchedulerReducer.reduce(s1, SchedulerIntent.SetTimers(listOf(timer())))
        assertTrue(s1 === s2, "an unchanged list must not produce a new state (it would push over sync)")
    }

    @Test
    fun start_pause_and_reset_move_only_the_named_timer() {
        val s0 = SchedulerReducer.reduce(
            SchedulerState.empty(),
            SchedulerIntent.SetTimers(listOf(timer(id = "timer-0"), timer(id = "timer-1"))),
        )
        val started = SchedulerReducer.reduce(s0, SchedulerIntent.StartTimer("timer-0", now))
        assertEquals(now + 5 * minute, started.timers[0].endsAtMillis)
        assertTrue(started.timers[1].idle, "the other row is untouched")

        val paused = SchedulerReducer.reduce(started, SchedulerIntent.PauseTimer("timer-0", now + minute))
        assertEquals(4 * minute, paused.timers[0].remainingMillis)

        val reset = SchedulerReducer.reduce(paused, SchedulerIntent.ResetTimer("timer-0"))
        assertTrue(reset.timers[0].idle)
    }

    @Test
    fun a_transition_on_an_unknown_or_settled_timer_is_a_no_op() {
        val s = SchedulerReducer.reduce(SchedulerState.empty(), SchedulerIntent.SetTimers(listOf(timer())))
        assertTrue(s === SchedulerReducer.reduce(s, SchedulerIntent.StartTimer("timer-9", now)))
        assertTrue(s === SchedulerReducer.reduce(s, SchedulerIntent.PauseTimer("timer-0", now)))
        assertTrue(s === SchedulerReducer.reduce(s, SchedulerIntent.ResetTimer("timer-0")))
        assertTrue(
            s === SchedulerReducer.reduce(
                s,
                SchedulerIntent.SetTimerCountdownField("timer-0", TimerDomain.TimerField.MINUTES, 5, now),
            ),
            "an idle row retyped as the 5:00 it already showed has not moved",
        )
        assertTrue(s === SchedulerReducer.reduce(s, SchedulerIntent.NudgeTimerRemaining("timer-0", 0L, now)))
        assertTrue(
            s === SchedulerReducer.reduce(
                s,
                SchedulerIntent.SetTimerCountdownField("timer-9", TimerDomain.TimerField.MINUTES, 2, now),
            ),
        )
        assertTrue(s === SchedulerReducer.reduce(s, SchedulerIntent.NudgeTimerRemaining("timer-9", 10 * second, now)))
    }

    @Test
    fun a_countdown_edit_moves_only_the_named_timers_countdown() {
        val s0 = SchedulerReducer.reduce(
            SchedulerState.empty(),
            SchedulerIntent.SetTimers(listOf(timer(id = "timer-0"), timer(id = "timer-1"))),
        )
        val running = SchedulerReducer.reduce(s0, SchedulerIntent.StartTimer("timer-0", now))

        // Setting the minutes shifts the due instant by the minutes delta and leaves the row running.
        val edited = SchedulerReducer.reduce(
            running,
            SchedulerIntent.SetTimerCountdownField("timer-0", TimerDomain.TimerField.MINUTES, 2, now + minute),
        )
        // 4:00 was left, so retyping the minutes as 2 shifts the due instant back by two minutes.
        assertEquals(now + 3 * minute, edited.timers[0].endsAtMillis)
        assertEquals(2 * minute, edited.timers[0].remainingAtMillis(now + minute))
        assertTrue(edited.timers[0].running, "still counting down, just less of it")
        assertTrue(edited.timers[1].idle, "the other row is untouched")

        // A nudge does the same in seconds, and equally does not stop it.
        val nudged = SchedulerReducer.reduce(
            edited,
            SchedulerIntent.NudgeTimerRemaining("timer-0", -10 * second, now + minute),
        )
        assertEquals(now + 3 * minute - 10 * second, nudged.timers[0].endsAtMillis)
        assertTrue(nudged.timers[0].running)
        assertTrue(nudged.timers[1].idle)
    }

    @Test
    fun a_countdown_edit_before_the_start_makes_the_row_resumable_through_the_reducer() {
        val s0 = SchedulerReducer.reduce(SchedulerState.empty(), SchedulerIntent.SetTimers(listOf(timer())))
        val dialled = SchedulerReducer.reduce(
            s0,
            SchedulerIntent.SetTimerCountdownField("timer-0", TimerDomain.TimerField.MINUTES, 2, now),
        )
        assertTrue(dialled.timers.single().paused, "the window's Start reads Resume from here on")
        assertEquals(2 * minute, dialled.timers.single().remainingMillis)
        assertEquals(300, dialled.timers.single().durationSeconds, "the duration setting is untouched")

        // The +/- buttons move it before the start as well.
        val nudged = SchedulerReducer.reduce(
            dialled,
            SchedulerIntent.NudgeTimerRemaining("timer-0", 10 * second, now),
        )
        assertEquals(2 * minute + 10 * second, nudged.timers.single().remainingMillis)

        // Resume runs what was dialled in; Reset still goes back to the duration.
        val started = SchedulerReducer.reduce(nudged, SchedulerIntent.StartTimer("timer-0", now + hour))
        assertEquals(now + hour + 2 * minute + 10 * second, started.timers.single().endsAtMillis)
        val reset = SchedulerReducer.reduce(started, SchedulerIntent.ResetTimer("timer-0"))
        assertTrue(reset.timers.single().idle)
        assertEquals(5 * minute, reset.timers.single().remainingAtMillis(now))
    }

    @Test
    fun typing_the_seconds_pauses_the_row_through_the_reducer() {
        // What the window shows for it: the Pause button becomes Resume, because the row really is paused.
        val s0 = SchedulerReducer.reduce(SchedulerState.empty(), SchedulerIntent.SetTimers(listOf(timer())))
        val running = SchedulerReducer.reduce(s0, SchedulerIntent.StartTimer("timer-0", now))
        val edited = SchedulerReducer.reduce(
            running,
            SchedulerIntent.SetTimerCountdownField("timer-0", TimerDomain.TimerField.SECONDS, 30, now),
        )
        assertTrue(edited.timers.single().paused)
        assertEquals(5 * minute + 30 * second, edited.timers.single().remainingMillis)
    }

    @Test
    fun the_countdown_and_the_settings_never_disturb_each_other() {
        // The two halves of the same rule: SetTimers carries the settings and must not move the due instant;
        // the countdown writes move the due instant and must not touch the settings.
        val s0 = SchedulerReducer.reduce(SchedulerState.empty(), SchedulerIntent.SetTimers(listOf(timer())))
        val running = SchedulerReducer.reduce(s0, SchedulerIntent.StartTimer("timer-0", now))
        val edited = SchedulerReducer.reduce(
            running,
            SchedulerIntent.SetTimerCountdownField("timer-0", TimerDomain.TimerField.MINUTES, 1, now),
        )
        assertEquals(300, edited.timers.single().durationSeconds)
        assertEquals(30, edited.timers.single().soundSeconds)

        // Now the window pushes the row's settings back (a label typed while it counts down): the shortened
        // countdown rides through untouched, because the push carries the live run fields.
        val renamed = SchedulerReducer.reduce(
            edited,
            SchedulerIntent.SetTimers(listOf(edited.timers.single().copy(label = "Tea"))),
        )
        assertEquals(now + minute, renamed.timers.single().endsAtMillis)
        assertEquals("Tea", renamed.timers.single().label)
    }

    @Test
    fun editing_a_running_timers_settings_does_not_disturb_its_end_instant() {
        // The window pushes the row's settings while it counts down (a label typed mid-countdown); the run
        // state travels with the entry, so it must survive the round trip untouched.
        val s0 = SchedulerReducer.reduce(SchedulerState.empty(), SchedulerIntent.SetTimers(listOf(timer())))
        val running = SchedulerReducer.reduce(s0, SchedulerIntent.StartTimer("timer-0", now))
        val endsAt = running.timers.single().endsAtMillis

        val renamed = SchedulerReducer.reduce(
            running,
            SchedulerIntent.SetTimers(listOf(running.timers.single().copy(label = "Tea"))),
        )
        assertEquals("Tea", renamed.timers.single().label)
        assertEquals(endsAt, renamed.timers.single().endsAtMillis)
    }

    /**
     * PRD §5: the timer LIST is routed through Undo/Redo, like the alarms beside it — the bin is why. The
     * **run state** is not, and deliberately: its currency is an absolute due instant, which does not mean the
     * same thing when a delta is replayed later. `AlarmHistoryTest` is the whole of it.
     */
    @Test
    fun the_timer_list_is_undoable_and_the_run_state_is_not() {
        val s0 = SchedulerState.empty()
        val s1 = SchedulerReducer.reduce(s0, SchedulerIntent.SetTimers(listOf(timer())))
        assertEquals(1, s1.histories.forCategory(HistoryCategory.Main).units.size)
        assertTrue(SchedulerReducer.reduce(s1, SchedulerIntent.Undo).timers.isEmpty())

        val s2 = SchedulerReducer.reduce(s1, SchedulerIntent.StartTimer("timer-0", now))
        assertEquals(s1.histories, s2.histories)
    }

    // ----- persistence --------------------------------------------------------------------------

    @Test
    fun codec_round_trip_preserves_every_timer_field_including_a_running_countdown() {
        val entries = listOf(
            timer(id = "timer-0", label = "Tea", durationSeconds = 180, soundSeconds = 45, alert = AlertSettings.RING),
            timer(id = "timer-1", durationSeconds = 600, soundSeconds = 5, alert = AlertSettings.RING.copy(vibrate = false), endsAtMillis = now),
            timer(id = "timer-2", durationSeconds = 60, remainingMillis = 12_000L),
        )
        val s = SchedulerReducer.reduce(SchedulerState.empty(), SchedulerIntent.SetTimers(entries))
        val decoded = SchedulerStateCodec.decode(SchedulerStateCodec.encode(s))
        assertNotNull(decoded)
        assertEquals(entries, decoded.timers)
    }

    @Test
    fun codec_decodes_a_payload_written_before_timers_existed() {
        // Persisted-DB rule: an on-disk DB from a build with no timer list must still load, with no timers —
        // and its alarms must be unaffected.
        val oldJson =
            """
            {"rootListId":"L","lists":[{"id":"L","parentCellId":null,"cellIds":["c0"]}],
             "cells":[{"id":"c0","parentListId":"L","taskId":null}],
             "tasks":[{"id":"t0","title":"X"}],
             "alarms":[{"id":"alarm-0","timeOfDayMinutes":420}]}
            """.trimIndent()
        val decoded = SchedulerStateCodec.decode(oldJson)
        assertNotNull(decoded)
        assertTrue(decoded.timers.isEmpty())
        assertEquals(1, decoded.alarms.size)
    }

    @Test
    fun codec_decodes_a_timer_row_missing_the_newer_fields() {
        val json =
            """
            {"rootListId":"L","lists":[{"id":"L","parentCellId":null,"cellIds":["c0"]}],
             "cells":[{"id":"c0","parentListId":"L","taskId":null}],
             "tasks":[{"id":"t0","title":"X"}],
             "timers":[{"id":"timer-0"}]}
            """.trimIndent()
        val decoded = SchedulerStateCodec.decode(json)
        assertNotNull(decoded)
        val t = decoded.timers.single()
        assertEquals(TimerEntry.DEFAULT_TIMER_SECONDS, t.durationSeconds)
        assertEquals(AlarmEntry.DEFAULT_ALARM_SOUND_SECONDS, t.soundSeconds)
        assertTrue(t.alert.vibrate)
        assertTrue(t.idle, "a row that says nothing about running is idle")
    }

    @Test
    fun codec_decodes_a_running_timer_written_before_the_run_length_existed() {
        // Persisted-DB rule: a run started by a build without `runMillis` still loads, still runs, and its
        // elapsed reading falls back to the duration — exactly what that build showed.
        val json =
            """
            {"rootListId":"L","lists":[{"id":"L","parentCellId":null,"cellIds":["c0"]}],
             "cells":[{"id":"c0","parentListId":"L","taskId":null}],
             "tasks":[{"id":"t0","title":"X"}],
             "timers":[{"id":"timer-0","durationSeconds":300,"endsAtMillis":${now + 200 * second}}]}
            """.trimIndent()
        val decoded = SchedulerStateCodec.decode(json)
        assertNotNull(decoded)
        val t = decoded.timers.single()
        assertTrue(t.running)
        assertNull(t.runMillis)
        assertEquals(100 * second, TimerDomain.elapsedMillis(t, now))

        // And the field round-trips once written.
        val started = TimerDomain.started(timer(durationSeconds = 300), now)
        val again = SchedulerStateCodec.decode(SchedulerStateCodec.encode(decoded.copy(timers = listOf(started))))
        assertEquals(300 * second, again!!.timers.single().runMillis)
    }

    @Test
    fun codec_heals_a_payload_holding_both_run_fields() {
        // A shape the current invariants forbid — reachable through a per-field merge or a hand-edited DB.
        val json =
            """
            {"rootListId":"L","lists":[{"id":"L","parentCellId":null,"cellIds":["c0"]}],
             "cells":[{"id":"c0","parentListId":"L","taskId":null}],
             "tasks":[{"id":"t0","title":"X"}],
             "timers":[{"id":"timer-0","endsAtMillis":$now,"remainingMillis":99000}]}
            """.trimIndent()
        val decoded = SchedulerStateCodec.decode(json)
        assertNotNull(decoded)
        val t = decoded.timers.single()
        assertTrue(t.running)
        assertEquals(now, t.endsAtMillis)
        assertNull(t.remainingMillis)
    }

    @Test
    fun timers_are_authoritative_so_they_ride_the_sync_wire() {
        // CLAUDE.md reconstructibility rule: a timer's settings AND the instant it is due at are
        // user-authored and not re-derivable, so both must move the sync fingerprint — a peer that never
        // heard the start could not ring.
        val s0 = SchedulerState.empty()
        val s1 = SchedulerReducer.reduce(s0, SchedulerIntent.SetTimers(listOf(timer())))
        assertTrue(
            SchedulerStateCodec.syncFingerprint(s0) != SchedulerStateCodec.syncFingerprint(s1),
            "adding a timer must change the sync fingerprint",
        )
        val s2 = SchedulerReducer.reduce(s1, SchedulerIntent.StartTimer("timer-0", now))
        assertTrue(
            SchedulerStateCodec.syncFingerprint(s1) != SchedulerStateCodec.syncFingerprint(s2),
            "starting a timer must change the sync fingerprint",
        )
        val s3 = SchedulerReducer.reduce(s2, SchedulerIntent.PauseTimer("timer-0", now + minute))
        assertTrue(
            SchedulerStateCodec.syncFingerprint(s2) != SchedulerStateCodec.syncFingerprint(s3),
            "pausing a timer must change the sync fingerprint",
        )
    }

    @Test
    fun a_running_timer_writes_nothing_as_it_counts_down() {
        // The remaining time is DERIVED (CLAUDE.md § State), so the state — and therefore the sync
        // fingerprint — is identical however far the clock has moved since the start.
        val s = SchedulerReducer.reduce(
            SchedulerReducer.reduce(SchedulerState.empty(), SchedulerIntent.SetTimers(listOf(timer()))),
            SchedulerIntent.StartTimer("timer-0", now),
        )
        val fingerprint = SchedulerStateCodec.syncFingerprint(s)
        val entry = s.timers.single()
        assertEquals(5 * minute, entry.remainingAtMillis(now))
        assertEquals(minute, entry.remainingAtMillis(now + 4 * minute))
        assertEquals(
            fingerprint,
            SchedulerStateCodec.syncFingerprint(s),
            "reading the countdown must not be able to move the fingerprint",
        )
    }
}
