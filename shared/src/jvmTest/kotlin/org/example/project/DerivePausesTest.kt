package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.TaskTimeRange

/**
 * PRD §15 server-derived pauses: [SchedulerDomain.derivePauses] is the Kotlin reference the Supabase
 * `derive_pauses` RPC mirrors — the complement of the union of every device's active intervals over a
 * window, emitting EVERY uncovered gap (leading, interior, and trailing): inactivity unless a device
 * reported activity. These cases pin the union/complement + clipping behaviour.
 */
class DerivePausesTest {
    private fun r(start: Long, end: Long) = TaskTimeRange(start, end)

    @Test
    fun a_gap_between_two_devices_active_windows_is_a_pause() {
        // The report-model example: A active [0,40][70,120], B active [30,50]; union [0,50][70,120]; pause [50,70].
        val pauses = SchedulerDomain.derivePauses(
            active = listOf(r(0, 40), r(70, 120), r(30, 50)),
            sinceMillis = 0,
            untilMillis = 120,
        )
        assertEquals(listOf(r(50, 70)), pauses)
    }

    @Test
    fun overlapping_active_windows_leave_no_pause() {
        val pauses = SchedulerDomain.derivePauses(
            active = listOf(r(0, 50), r(40, 100)),
            sinceMillis = 0,
            untilMillis = 100,
        )
        assertEquals(emptyList(), pauses)
    }

    @Test
    fun adjacent_windows_touch_and_leave_no_pause() {
        // [0,50] and [50,100] abut exactly — merged, so no interior gap.
        val pauses = SchedulerDomain.derivePauses(listOf(r(0, 50), r(50, 100)), 0, 100)
        assertEquals(emptyList(), pauses)
    }

    @Test
    fun leading_interior_and_trailing_gaps_are_all_pauses() {
        // Activity only in the middle: window [0,100], active [30,40][60,70]. The LEADING gap [0,30], the
        // interior [40,60] AND the trailing [70,100] are all pauses — inactivity unless a device reported
        // activity. (An ACTIVE device never leaves a trailing gap because its open session is freshened to
        // `now` before deriving; a finalized last session at 70 genuinely means nobody was active after 70.)
        val pauses = SchedulerDomain.derivePauses(listOf(r(30, 40), r(60, 70)), 0, 100)
        assertEquals(listOf(r(0, 30), r(40, 60), r(70, 100)), pauses)
    }

    @Test
    fun sessions_are_clipped_to_the_window() {
        // A session spilling past the window is clipped; the gap after it is still interior to later activity.
        val pauses = SchedulerDomain.derivePauses(listOf(r(-100, 20), r(50, 200)), 0, 100)
        assertEquals(listOf(r(20, 50)), pauses)
    }

    @Test
    fun no_activity_is_the_whole_window_as_one_pause() {
        // A freshly emptied account (no app ever active) shows the entire window as a single pause.
        assertEquals(listOf(r(0, 100)), SchedulerDomain.derivePauses(emptyList(), 0, 100))
    }

    @Test
    fun a_single_current_session_fills_the_past_as_a_leading_pause() {
        // The anomaly case: this device started at 100 and is the only activity; everything before it — the
        // whole past window [0,100] — is a pause (no trailing band past the session).
        assertEquals(listOf(r(0, 100)), SchedulerDomain.derivePauses(listOf(r(100, 168)), 0, 168))
    }

    @Test
    fun a_just_opened_zero_length_session_bounds_the_preceding_pause() {
        // The device woke at 70 = now and its new session is still a point [70,70] (the engine freshens the
        // open session to `now` before deriving, so `until` == the point); the pause [40,70] must show now,
        // not wait for the session to grow (this is the real-wake / debug simulate-pause immediacy case).
        val pauses = SchedulerDomain.derivePauses(listOf(r(0, 40), r(70, 70)), 0, 70)
        assertEquals(listOf(r(40, 70)), pauses)
    }

    @Test
    fun a_finalized_peer_session_leaves_the_quiet_window_before_a_later_device_as_a_pause() {
        // The reported incident (desktop quiet 13:25→14:44, phone opens at 14:44): the peer's session is
        // finalized at 30, this device opens at 90 (= now; its open session is freshened to `now` before
        // deriving, here still a point). The quiet window [30,90] IS a pause. The old trailing-drop rule
        // instead presumed it active — and the old startup adoption then wrote that presumption durably into
        // the second device's store, hiding the pause forever.
        val pauses = SchedulerDomain.derivePauses(listOf(r(10, 30), r(90, 90)), 0, 90)
        assertEquals(listOf(r(0, 10), r(30, 90)), pauses)
    }

    @Test
    fun three_devices_union_before_complement() {
        // Union of A[0,10] B[20,30] C[25,60] = [0,10][20,60]; the only interior gap is [10,20].
        val pauses = SchedulerDomain.derivePauses(listOf(r(0, 10), r(20, 30), r(25, 60)), 0, 60)
        assertEquals(listOf(r(10, 20)), pauses)
    }

    // ----- Display filtering: the "20-second pause right after sleep" anomaly ---------------------

    private val tz = TimeZone.UTC
    private fun utc(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int = 0): Long =
        LocalDateTime(y, mo, d, h, mi, s).toInstant(tz).toEpochMilliseconds()

    @Test
    fun sub_minute_morning_sliver_after_sleep_is_dropped_from_the_bands() {
        // Reported anomaly (account1-empty-and-open): a freshly-opened account whose first session starts a
        // few seconds after the §17 scheduled wake. The one ongoing session leaves a leading pause up to
        // app-open; carving the "Sleep" window (ending at wake) out of it leaves a 20-second sliver
        // [wake, appOpen] that must NOT render as an "Inactivity" band.
        val wake = utc(2026, 7, 5, 7, 30)
        val appOpen = wake + 20_000L // opened 20 s after the scheduled wake
        val now = appOpen
        val since = now - SchedulerDomain.SCHEDULE_HORIZON_MILLIS

        val pauses = SchedulerDomain.derivePauses(listOf(r(appOpen, now)), since, now)
        val sleep = SchedulerDomain.sleepPanels(SchedulerDomain.DEFAULT_SLEEP, since, now, tz)
            .map { r(it.startEpochMillis, it.endEpochMillis) }
        val bands = SchedulerDomain.subtractRegions(pauses, sleep)
            .filter { it.endEpochMillis - it.startEpochMillis >= SchedulerDomain.MIN_INACTIVITY_BAND_MILLIS }

        // The 20-second [wake, appOpen] sliver is gone.
        assertTrue(
            bands.none { it.startEpochMillis in wake until appOpen },
            "the sub-minute morning sliver should be filtered out: $bands",
        )
    }

    // ----- Reconciliation: a device never shows a pause over time IT was active -------------------

    @Test
    fun own_active_sessions_cancel_a_phantom_server_pause() {
        // The reported cross-device anomaly: the desktop was active [40,90] but had only uploaded a stale/sparse
        // slice of it, so the server derived a pause [40,90] and handed it back. The engine subtracts THIS
        // device's own local active sessions from the server pauses before display, so a window this device
        // knows it was active can never render as Inactivity — it can only ever SHRINK the band. Here the
        // genuine leading pause [0,40] survives; the phantom [40,90] over our own activity is removed.
        val serverPauses = listOf(r(0, 40), r(40, 90))
        val ownActive = listOf(r(40, 90))
        assertEquals(listOf(r(0, 40)), SchedulerDomain.subtractRegions(serverPauses, ownActive))
    }

    @Test
    fun own_activity_trims_only_the_overlapped_part_of_a_pause() {
        // A partial overlap: the server pause is [40,120] but we were active [70,100]; only that middle slice is
        // demonstrably not a pause, so the band splits into [40,70] and [100,120] rather than vanishing.
        val serverPauses = listOf(r(40, 120))
        val ownActive = listOf(r(70, 100))
        assertEquals(listOf(r(40, 70), r(100, 120)), SchedulerDomain.subtractRegions(serverPauses, ownActive))
    }

    @Test
    fun a_genuine_multi_minute_pause_survives_the_band_filter() {
        // A real 30-minute daytime gap between two sessions stays a band (the filter only drops noise).
        val a = utc(2026, 7, 5, 10, 0)
        val b = utc(2026, 7, 5, 10, 30)
        val now = utc(2026, 7, 5, 12, 0)
        val pauses = SchedulerDomain.derivePauses(listOf(r(now - SchedulerDomain.SCHEDULE_HORIZON_MILLIS, a), r(b, now)), now - SchedulerDomain.SCHEDULE_HORIZON_MILLIS, now)
        val bands = pauses.filter { it.endEpochMillis - it.startEpochMillis >= SchedulerDomain.MIN_INACTIVITY_BAND_MILLIS }
        assertTrue(bands.any { it.startEpochMillis == a && it.endEpochMillis == b }, "the 30-min pause must survive: $bands")
    }
}
