package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.TimerDomain
import org.example.project.scheduler.model.TimerEntry
import org.example.project.scheduler.persistence.SchedulerStateCodec
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState

/**
 * PRD §18 Timers, "below zero" ([TimerEntry.goesNegative]): a timer with the option on rings at zero and counts on
 * below it; one with it off resets at the ring as ever but keeps the instant it reached zero, so turning the option
 * on afterwards counts on from that instant — as if it had always been on.
 */
class TimerBelowZeroTest {
    private val second = 1_000L
    private val minute = 60 * second
    private val now = 1_800_000_000_000L

    private fun timer(goesNegative: Boolean = false) =
        TimerEntry(id = "timer-0", durationSeconds = 300, goesNegative = goesNegative)

    @Test
    fun a_timer_that_goes_negative_rings_and_counts_on_below_zero() {
        val started = TimerDomain.started(timer(goesNegative = true), now)
        val end = now + 5 * minute
        val afterRing = TimerDomain.rang(started, end)
        assertEquals(started, afterRing, "the ring leaves it running")
        assertEquals(-7 * second, afterRing.remainingAtMillis(end + 7 * second))
        assertEquals("−0:07", TimerDomain.formatCountdown(afterRing.remainingAtMillis(end + 7 * second)))
        // Across zero the readout moves one second per second: 0:01, 0:00, −0:01.
        assertEquals("0:01", TimerDomain.formatCountdown(500L))
        assertEquals("0:00", TimerDomain.formatCountdown(-500L))
        assertEquals("−0:01", TimerDomain.formatCountdown(-1_000L))
        // It is not armed again: its instant is behind the clock.
        assertNull(TimerDomain.nextOccurrence(listOf(afterRing), end + second))
    }

    @Test
    fun without_the_option_the_ring_resets_it_and_keeps_the_instant_it_reached_zero() {
        val started = TimerDomain.started(timer(), now)
        val end = now + 5 * minute
        val rang = TimerDomain.rang(started, end)
        assertTrue(rang.idle)
        assertEquals(end, rang.endedAtMillis)
        assertEquals(5 * minute, rang.remainingAtMillis(end + minute), "an idle row shows its full duration")
        assertEquals(0L, rang.remainingAtMillis(end + minute).coerceAtMost(0L))
    }

    @Test
    fun turning_the_option_on_after_the_ring_counts_on_from_the_instant_it_reached_zero() {
        val end = now + 5 * minute
        val rang = TimerDomain.rang(TimerDomain.started(timer(), now), end)
        val later = end + 90 * second
        val revived = TimerDomain.withGoesNegative(rang, true, later)
        assertTrue(revived.running)
        assertTrue(revived.goesNegative)
        assertNull(revived.endedAtMillis)
        // Exactly as if it had always been on: 1:30 past zero, and counting.
        assertEquals(-90 * second, revived.remainingAtMillis(later))
        assertEquals(-100 * second, revived.remainingAtMillis(later + 10 * second))
        assertEquals(TimerDomain.started(timer(goesNegative = true), now), revived)
    }

    @Test
    fun turning_it_off_past_zero_puts_it_where_a_ring_would_have_and_back_on_restores_it() {
        val end = now + 5 * minute
        val running = TimerDomain.started(timer(goesNegative = true), now)
        val off = TimerDomain.withGoesNegative(running, false, end + minute)
        assertTrue(off.idle)
        assertEquals(end, off.endedAtMillis)
        assertEquals(running, TimerDomain.withGoesNegative(off, true, end + 2 * minute))
        // A timer still above zero: only the flag moves.
        val early = TimerDomain.withGoesNegative(running, false, now + minute)
        assertEquals(running.copy(goesNegative = false), early)
    }

    @Test
    fun a_new_run_forgets_where_the_last_one_ended_and_reset_does_too() {
        val end = now + 5 * minute
        val rang = TimerDomain.rang(TimerDomain.started(timer(), now), end)
        val restarted = TimerDomain.started(rang, end + minute)
        assertNull(restarted.endedAtMillis)
        assertEquals(end + minute + 5 * minute, restarted.endsAtMillis)
        assertEquals(5 * minute, restarted.runMillis)
        val reset = TimerDomain.reset(rang)
        assertNull(reset.endedAtMillis)
        assertNull(reset.runMillis)
        // Turning the option on after a reset brings nothing back.
        assertTrue(TimerDomain.withGoesNegative(reset, true, end + minute).idle)
    }

    @Test
    fun a_paused_negative_countdown_resumes_below_zero_and_its_fields_edit_in_the_direction_they_read() {
        val end = now + 5 * minute
        val running = TimerDomain.started(timer(goesNegative = true), now)
        val paused = TimerDomain.paused(running, end + 70 * second)
        assertEquals(-70 * second, paused.remainingMillis)
        val resumed = TimerDomain.started(paused, end + 10 * minute)
        assertEquals(-70 * second, resumed.remainingAtMillis(end + 10 * minute))
        // −0:01:10 with its minutes typed as 3 reads −0:03:10.
        val shown = TimerDomain.countdownOf(paused.remainingMillis!!)
        assertTrue(shown.negative)
        val edited = TimerDomain.withCountdownField(paused, TimerDomain.TimerField.MINUTES, 3, end + 10 * minute)
        assertEquals(-190 * second, edited.remainingMillis)
        // Without the option, nothing goes below zero.
        assertEquals(0L, TimerDomain.nudged(timer(), -10 * minute, now).remainingMillis)
    }

    @Test
    fun a_merge_that_left_a_negative_timer_idle_at_its_zero_is_healed_into_the_count_on() {
        val forged = timer(goesNegative = true).copy(endedAtMillis = now, runMillis = 5 * minute)
        val healed = TimerDomain.healed(forged)
        assertTrue(healed.running)
        assertEquals(now, healed.endsAtMillis)
        // Off, a banked countdown below zero is not a countdown.
        assertEquals(0L, TimerDomain.healed(timer().copy(remainingMillis = -5 * second)).remainingMillis)
    }

    @Test
    fun the_ring_intent_and_the_option_go_through_the_reducer_and_only_the_option_is_undoable() {
        val end = now + 5 * minute
        var s = SchedulerReducer.reduce(SchedulerState.empty(), SchedulerIntent.SetTimers(listOf(timer())))
        s = SchedulerReducer.reduce(s, SchedulerIntent.StartTimer("timer-0", now))
        s = SchedulerReducer.reduce(s, SchedulerIntent.TimerRang("timer-0", end))
        assertEquals(end, s.timers.single().endedAtMillis)
        // What the window pushes when the switch is turned on.
        val pushed = TimerDomain.withGoesNegative(s.timers.single(), true, end + minute)
        val on = SchedulerReducer.reduce(s, SchedulerIntent.SetTimers(listOf(pushed)))
        assertTrue(on.timers.single().running)
        val undone = SchedulerReducer.reduce(on, SchedulerIntent.Undo)
        assertFalse(undone.timers.single().goesNegative)
        assertEquals(end, undone.timers.single().endedAtMillis, "undoing the switch puts the rung row back")
    }

    @Test
    fun a_payload_written_before_the_option_decodes_to_off_and_the_new_fields_round_trip() {
        val json =
            """
            {"rootListId":"L","lists":[{"id":"L","parentCellId":null,"cellIds":["c0"]}],
             "cells":[{"id":"c0","parentListId":"L","taskId":null}],
             "tasks":[{"id":"t0","title":"X"}],
             "timers":[{"id":"timer-0","durationSeconds":300}]}
            """.trimIndent()
        val decoded = SchedulerStateCodec.decode(json)
        assertNotNull(decoded)
        assertFalse(decoded.timers.single().goesNegative)
        assertNull(decoded.timers.single().endedAtMillis)

        val rang = TimerDomain.rang(TimerDomain.started(timer(), now), now + 5 * minute)
        val again = SchedulerStateCodec.decode(SchedulerStateCodec.encode(decoded.copy(timers = listOf(rang))))
        assertEquals(rang, again!!.timers.single())
        val negative = TimerDomain.paused(TimerDomain.started(timer(goesNegative = true), now), now + 6 * minute)
        assertEquals(negative, SchedulerStateCodec.decode(SchedulerStateCodec.encode(decoded.copy(timers = listOf(negative))))!!.timers.single())
    }
}
