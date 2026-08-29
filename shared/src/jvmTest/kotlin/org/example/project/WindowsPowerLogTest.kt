package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.example.project.scheduler.platform.DeviceSleepGap
import org.example.project.scheduler.platform.PowerTransition
import org.example.project.scheduler.platform.WindowsPowerLog

/**
 * PRD §8 / ADR 0002: the Windows power history feeds the calendar's own layer AND the record bank's no-screen
 * evidence, so every way a raw event stream can lie about whether the user was there is pinned here.
 *
 * The three that shipped wrong: a machine that was SHUT DOWN logged no sleep event and so read as time at the
 * desk; a sub-minute standby bounce became a real "locked" interval (or swallowed the long absence around it);
 * and a window that opened mid-absence dropped its unmatched wake and reported the whole lead-in as present.
 */
class WindowsPowerLogTest {
    private val t0 = 1_700_000_000_000L
    private val minute = 60_000L
    private val hour = 3_600_000L

    private fun down(at: Long) = PowerTransition(at, down = true)
    private fun up(at: Long) = PowerTransition(at, down = false)

    private fun lines(vararg events: Pair<Long, Int>): List<String> =
        listOf("OK") + events.map { (millis, id) -> "$millis,$id" }

    // --- the event vocabulary ---------------------------------------------------------------------------

    @Test
    fun a_shutdown_and_the_boot_that_followed_are_an_absence() {
        // 109 (kernel shutdown initiated), 6006 (event log stopped), then 6005/12 on the way back up. No
        // Kernel-Power sleep event exists anywhere in this sequence.
        val read =
            WindowsPowerLog.transitions(
                lines(t0 to 109, (t0 + 1_000) to 6006, (t0 + 8 * hour) to 6005, (t0 + 8 * hour + 2_000) to 12),
            )!!
        assertEquals(listOf(DeviceSleepGap(t0, t0 + 8 * hour)), WindowsPowerLog.intervals(read))
    }

    @Test
    fun a_power_loss_is_a_down_transition() {
        val read = WindowsPowerLog.transitions(lines(t0 to 6008, (t0 + 2 * hour) to 6005))!!
        assertEquals(listOf(DeviceSleepGap(t0, t0 + 2 * hour)), WindowsPowerLog.intervals(read))
    }

    @Test
    fun an_unknown_event_id_is_ignored() {
        val read = WindowsPowerLog.transitions(lines(t0 to 41, (t0 + hour) to 42, (t0 + 2 * hour) to 1))!!
        assertEquals(listOf(DeviceSleepGap(t0 + hour, t0 + 2 * hour)), WindowsPowerLog.intervals(read))
    }

    // --- debouncing ------------------------------------------------------------------------------------

    @Test
    fun a_sub_minute_bounce_leaves_no_trace() {
        // 506 -> 507 three seconds later is standby jitter, not three seconds at the machine.
        assertEquals(emptyList(), WindowsPowerLog.debounce(listOf(down(t0), up(t0 + 3_000))))
    }

    @Test
    fun a_bounce_at_the_start_of_a_long_absence_leaves_one_interval() {
        // Standby that bounces awake and settles again: the night is ONE absence, not a three-second sliver
        // of "locked" followed by the rest of it. The sliver is what the undebounced pairing emitted.
        val read =
            WindowsPowerLog.transitions(
                lines(t0 to 42, (t0 + 3_000) to 507, (t0 + 6_000) to 506, (t0 + 8 * hour) to 1),
            )!!
        assertEquals(listOf(DeviceSleepGap(t0 + 6_000, t0 + 8 * hour)), WindowsPowerLog.intervals(read))
    }

    @Test
    fun a_bounce_before_a_wake_does_not_swallow_the_absence_behind_it() {
        // Two wakes with NO sleep between them is not something the machine can do: one of them is spurious.
        // It cannot be the later one (a resume is what ends a standby), so the machine never came back at
        // t0+3s and the absence runs to the real resume. Cancelling the bounce as an ordinary jitter pair
        // used to drop the sleep outright, leaving the genuine wake with nothing to close and reporting the
        // whole stretch as time at the desk.
        //
        // Observed on the release machine 2026-08-29: 506@15:12:40, 507@15:12:41, 507@15:26:53 lost a real
        // 14-minute standby — which the §9 record bank then banked straight through.
        val read = WindowsPowerLog.transitions(lines(t0 to 42, (t0 + 3_000) to 1, (t0 + 8 * hour) to 1))!!
        assertEquals(listOf(DeviceSleepGap(t0, t0 + 8 * hour)), WindowsPowerLog.intervals(read))
    }

    @Test
    fun a_restored_bounce_is_still_re_tested_against_the_debounce() {
        // The restoration is not a licence to invent short absences: the recovered pair goes back through
        // the same minimum-dwell test, so a genuinely brief flip stays jitter and claims nothing.
        val read = WindowsPowerLog.transitions(lines(t0 to 42, (t0 + 1_000) to 1, (t0 + 2_000) to 1))!!
        assertEquals(emptyList(), WindowsPowerLog.intervals(read))
    }

    @Test
    fun a_bounce_the_machine_really_returned_from_still_leaves_no_trace() {
        // The counter-case that keeps the fix honest: here a genuine SLEEP follows the bounce, so the wake at
        // t0+3s really did happen and the cancellation stands — the absence starts at the second sleep, not
        // the first. (Same shape as the long-absence test above, checked from the debouncer's side.)
        val kept = WindowsPowerLog.debounce(listOf(down(t0), up(t0 + 3_000), down(t0 + 6_000), up(t0 + 8 * hour)))
        assertEquals(listOf(down(t0 + 6_000), up(t0 + 8 * hour)), kept)
    }

    @Test
    fun a_repeat_of_the_state_already_held_counts_from_the_first() {
        // A shutdown legitimately logs several downs in a row; the user left at the first one.
        val read = WindowsPowerLog.transitions(lines(t0 to 42, (t0 + 5_000) to 506, (t0 + 3 * hour) to 507))!!
        assertEquals(listOf(DeviceSleepGap(t0, t0 + 3 * hour)), WindowsPowerLog.intervals(read))
    }

    @Test
    fun one_wake_logged_by_two_providers_is_one_wake() {
        val read = WindowsPowerLog.transitions(lines(t0 to 42, (t0 + hour) to 1, (t0 + hour + 500) to 507))!!
        assertEquals(listOf(DeviceSleepGap(t0, t0 + hour)), WindowsPowerLog.intervals(read))
    }

    @Test
    fun the_debounced_timeline_always_alternates() {
        val noisy =
            listOf(
                down(t0), up(t0 + 1_000), down(t0 + 2_000), down(t0 + 5 * minute),
                up(t0 + 2 * hour), up(t0 + 2 * hour + 400), down(t0 + 5 * hour),
            )
        val kept = WindowsPowerLog.debounce(noisy)
        assertTrue(kept.zipWithNext().all { (a, b) -> a.down != b.down }, "not alternating: $kept")
    }

    @Test
    fun a_flip_that_held_for_a_minute_is_a_real_transition() {
        val read = WindowsPowerLog.transitions(lines(t0 to 42, (t0 + minute) to 1))!!
        assertEquals(listOf(DeviceSleepGap(t0, t0 + minute)), WindowsPowerLog.intervals(read))
    }

    // --- window edges ----------------------------------------------------------------------------------

    @Test
    fun a_window_that_opens_mid_absence_pairs_with_the_event_before_it() {
        // What the prior-events query buys: the down is older than the asked window, and without it the wake
        // would be dropped and the whole lead-in reported as time at the machine.
        val read = WindowsPowerLog.transitions(lines((t0 - 3 * hour) to 42, (t0 + hour) to 1))!!
        assertEquals(listOf(DeviceSleepGap(t0 - 3 * hour, t0 + hour)), WindowsPowerLog.intervals(read))
    }

    @Test
    fun an_absence_still_open_at_the_end_of_the_window_is_clipped() {
        val read = WindowsPowerLog.transitions(lines(t0 to 42))!!
        assertEquals(
            listOf(DeviceSleepGap(t0, t0 + hour)),
            WindowsPowerLog.intervals(read, openEndMillis = t0 + hour),
        )
    }

    @Test
    fun an_open_absence_is_dropped_when_nothing_may_close_it() {
        val read = WindowsPowerLog.transitions(lines(t0 to 42))!!
        assertEquals(emptyList(), WindowsPowerLog.intervals(read))
    }

    @Test
    fun a_wake_with_nothing_to_close_claims_nothing() {
        val read = WindowsPowerLog.transitions(lines(t0 to 1))!!
        assertEquals(emptyList(), WindowsPowerLog.intervals(read))
    }

    // --- the sentinel ----------------------------------------------------------------------------------

    @Test
    fun output_without_the_sentinel_means_nothing_is_known() {
        // "Could not read the log" and "read it, the device was never away" are opposite answers: null keeps
        // the record bank from treating a failed query as evidence.
        assertNull(WindowsPowerLog.transitions(listOf("$t0,42", "${t0 + hour},1")))
        assertNull(WindowsPowerLog.transitions(null))
    }

    @Test
    fun the_sentinel_alone_means_the_device_was_never_away() {
        assertEquals(emptyList(), WindowsPowerLog.transitions(listOf("OK")))
    }

    @Test
    fun duplicate_lines_from_the_overlapping_queries_are_one_event() {
        val read =
            WindowsPowerLog.transitions(listOf("OK", "$t0,42", "$t0,42", "${t0 + hour},1", "${t0 + hour},1"))!!
        assertEquals(listOf(down(t0), up(t0 + hour)), read)
    }

    // --- the query itself ------------------------------------------------------------------------------

    @Test
    fun every_id_belongs_to_exactly_one_provider() {
        // Ids are unique only WITHIN a provider: `1` is Kernel-Power's "the system has resumed" and also
        // Kernel-General's "the system time has changed", which Windows writes a second after almost every
        // sleep. Asking all three providers for one flat id list turns each clock resync into a wake and eats
        // the night around it. This partition is also what lets a printed line stay a plain `millis,id`.
        val claimed = WindowsPowerLog.PROVIDERS.flatMap { it.second }
        assertEquals(claimed.size, claimed.distinct().size, "an id is claimed by two providers: $claimed")
        assertEquals(
            (WindowsPowerLog.UP_IDS + WindowsPowerLog.DOWN_IDS).sorted(),
            claimed.sorted(),
            "the queried ids and the classified ids have drifted apart",
        )
    }

    @Test
    fun the_script_carries_every_id_and_asks_for_the_state_before_the_window() {
        val windowed = WindowsPowerLog.script(1000, t0, t0 + hour)
        WindowsPowerLog.PROVIDERS.forEach { (provider, ids) ->
            assertTrue(
                windowed.contains("Read-Power '$provider' @(${ids.joinToString(",")}) \$since \$until 1000"),
                "$provider is not asked for its own ids over the window",
            )
            assertTrue(
                windowed.contains(
                    "Read-Power '$provider' @(${ids.joinToString(",")}) \$null \$since ${WindowsPowerLog.PRIOR_EVENTS}",
                ),
                "$provider is not asked for the state the window opens in",
            )
        }
        assertTrue(windowed.contains("NoMatchingEventsFound"), "an unreadable log would print the sentinel")
        // The unbounded form asks the same question with no window and no prior-state probe.
        val unbounded = WindowsPowerLog.script(240)
        assertTrue(unbounded.contains("Read-Power 'EventLog' @(6005,6006,6008) \$null \$null 240"))
        assertTrue(!unbounded.contains("\$since"))
    }
}
