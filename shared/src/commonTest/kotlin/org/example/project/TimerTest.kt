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
 * PRD §18 Timers: the Alarms window's second section — a countdown the user starts on demand, which rings
 * through the alarms' own machinery when it runs out.
 *
 * Covers the [TimerDomain] arithmetic (when a running timer is due, the crossings a moving now-line passed,
 * and the three run transitions), the [SchedulerIntent.SetTimers] / [SchedulerIntent.StartTimer] /
 * [SchedulerIntent.PauseTimer] / [SchedulerIntent.ResetTimer] mutations, and persistence — including that a
 * DB written **before** timers existed still loads and that a payload holding a run shape the current
 * invariants forbid is healed rather than surfaced (CLAUDE.md persisted-DB rule). The engine half is in
 * [TimerEngineTest]; the window itself is UI code and not unit-tested here.
 */
class TimerTest {

    private val second = 1_000L
    private val minute = 60 * second

    /** An arbitrary but fixed "now", so nothing here depends on the wall clock. */
    private val now = 1_800_000_000_000L

    private fun timer(
        id: String = "timer-0",
        durationSeconds: Int = 5 * 60,
        soundSeconds: Int = 30,
        vibrate: Boolean = true,
        label: String = "",
        endsAtMillis: Long? = null,
        remainingMillis: Long? = null,
    ) = TimerEntry(
        id = id,
        label = label,
        durationSeconds = durationSeconds,
        soundSeconds = soundSeconds,
        vibrate = vibrate,
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

    @Test
    fun editing_timers_is_not_part_of_the_tree_undo_history() {
        // Like the alarms beside them: authoritative, but not routed through the Undo/Redo stacks.
        val s0 = SchedulerState.empty()
        val s1 = SchedulerReducer.reduce(s0, SchedulerIntent.SetTimers(listOf(timer())))
        assertEquals(s0.histories, s1.histories)
        val s2 = SchedulerReducer.reduce(s1, SchedulerIntent.StartTimer("timer-0", now))
        assertEquals(s0.histories, s2.histories)
    }

    // ----- persistence --------------------------------------------------------------------------

    @Test
    fun codec_round_trip_preserves_every_timer_field_including_a_running_countdown() {
        val entries = listOf(
            timer(id = "timer-0", label = "Tea", durationSeconds = 180, soundSeconds = 45, vibrate = true),
            timer(id = "timer-1", durationSeconds = 600, soundSeconds = 5, vibrate = false, endsAtMillis = now),
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
        assertEquals(30, t.soundSeconds)
        assertTrue(t.vibrate)
        assertTrue(t.idle, "a row that says nothing about running is idle")
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
