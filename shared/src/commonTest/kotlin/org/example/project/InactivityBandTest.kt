package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
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
}
