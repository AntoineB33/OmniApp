package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.TaskTimeRange
import org.example.project.ui.CalendarRecord
import org.example.project.ui.recordsForDay

/**
 * PRD §15 device-sleep gaps rendered as greyed "Inactivity" bands. Covers the display mapping only — the
 * bands are derived from the synced gap store (see `SchedulerEngine.inactivityGaps`), not persisted anew.
 */
class InactivityBandTest {
    private val tz = TimeZone.UTC

    private fun at(hour: Int, minute: Int): Long =
        LocalDateTime(2026, 7, 4, hour, minute).toInstant(tz).toEpochMilliseconds()

    @Test
    fun inactivity_record_places_as_a_band_over_its_interval() {
        val gap = CalendarRecord(
            title = "Inactivity",
            range = TaskTimeRange(at(9, 0), at(10, 30)),
            inactivity = true,
        )
        val placed = recordsForDay(listOf(gap), LocalDate(2026, 7, 4), tz)
        assertEquals(1, placed.size)
        val band = placed.single()
        assertTrue(band.inactivity, "the gap must carry the inactivity flag into the day view")
        assertFalse(band.sleep)
        assertFalse(band.manual)
        assertEquals(9f, band.startHour)
        assertEquals(10.5f, band.endHour)
    }

    @Test
    fun a_plain_block_is_not_flagged_inactivity() {
        val block = CalendarRecord(title = "Work", range = TaskTimeRange(at(11, 0), at(12, 0)), manual = true)
        val placed = recordsForDay(listOf(block), LocalDate(2026, 7, 4), tz).single()
        assertFalse(placed.inactivity)
    }

    // ---- SchedulerDomain.displayInactivityGaps: the live "Inactivity" tail of an ongoing pause ----
    // The band must grow behind an advancing now-line (a real walk-away, or the debug pause-leap) instead
    // of appearing whole at the next derive — and must never flicker out between the return and the derive.

    @Test
    fun live_tail_grows_with_the_now_line_while_the_device_stays_inactive() {
        val derived = listOf(TaskTimeRange(at(2, 0), at(7, 0)))
        val walkedAwayAt = at(10, 0)
        val early = SchedulerDomain.displayInactivityGaps(derived, walkedAwayAt, null, at(10, 1))
        val later = SchedulerDomain.displayInactivityGaps(derived, walkedAwayAt, null, at(10, 4))
        assertEquals(derived + TaskTimeRange(walkedAwayAt, at(10, 1)), early)
        assertEquals(derived + TaskTimeRange(walkedAwayAt, at(10, 4)), later)
    }

    @Test
    fun tail_caps_at_the_reopened_session_so_the_band_holds_until_a_derive_covers_it() {
        val walkedAwayAt = at(10, 0)
        val backAt = at(10, 5)
        val gaps = SchedulerDomain.displayInactivityGaps(emptyList(), walkedAwayAt, backAt, at(10, 6))
        assertEquals(listOf(TaskTimeRange(walkedAwayAt, backAt)), gaps)
    }

    @Test
    fun tail_fuses_with_a_derived_gap_that_partially_covers_the_same_pause() {
        // A mid-pause derive already returned the pause up to its own `until`; the live tail keeps growing
        // past it and the two must render as ONE band, not a split.
        val walkedAwayAt = at(10, 0)
        val derived = listOf(TaskTimeRange(walkedAwayAt, at(10, 3)))
        val gaps = SchedulerDomain.displayInactivityGaps(derived, walkedAwayAt, null, at(10, 6))
        assertEquals(listOf(TaskTimeRange(walkedAwayAt, at(10, 6))), gaps)
    }

    @Test
    fun no_locally_observed_pause_renders_the_derived_gaps_unchanged() {
        val derived = listOf(TaskTimeRange(at(2, 0), at(7, 0)))
        assertEquals(derived, SchedulerDomain.displayInactivityGaps(derived, null, null, at(10, 0)))
    }

    @Test
    fun an_empty_or_inverted_tail_is_dropped() {
        val derived = listOf(TaskTimeRange(at(2, 0), at(7, 0)))
        // Finalize instant == now (the walk-away just happened): nothing to draw yet.
        assertEquals(derived, SchedulerDomain.displayInactivityGaps(derived, at(10, 0), null, at(10, 0)))
        // Degenerate ordering never yields a negative range.
        assertEquals(derived, SchedulerDomain.displayInactivityGaps(derived, at(10, 0), at(9, 0), at(10, 0)))
    }
}
