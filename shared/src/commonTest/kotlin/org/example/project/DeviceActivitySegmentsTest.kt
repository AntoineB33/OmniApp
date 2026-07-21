package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import org.example.project.scheduler.model.TaskTimeRange
import org.example.project.scheduler.persistence.ActiveSessionRecord
import org.example.project.ui.CalendarRecord
import org.example.project.ui.DeviceActivitySegment
import org.example.project.ui.deviceActivitySegments
import org.example.project.ui.recordsForDay

/**
 * The calendar's "which devices were open" data behind past task panels: [deviceActivitySegments] tiles a
 * panel's elapsed, data-covered part into segments of constant device set (the hover bubble's "Open:" line;
 * a dashed separator is drawn at each interior boundary). Pure sweep over the stored active sessions —
 * this device's own rows plus the peers' rows the Sync button pulled.
 */
class DeviceActivitySegmentsTest {
    private fun session(device: String, start: Long, end: Long, kind: String) =
        ActiveSessionRecord(deviceId = device, startMillis = start, endMillis = end, updatedAtMillis = end, kind = kind)

    @Test
    fun no_session_data_claims_nothing() {
        assertTrue(deviceActivitySegments(TaskTimeRange(0, 100), emptyList(), 1_000).isEmpty())
    }

    @Test
    fun segments_change_where_the_device_set_changes() {
        // Desktop open 0..60, phone joins at 40 and stays to 100: sets are [Desktop], [Desktop, Phone], [Phone].
        val sessions = listOf(
            session("d1", 0, 60, "desktop"),
            session("p1", 40, 100, "phone"),
        )
        val segments = deviceActivitySegments(TaskTimeRange(0, 100), sessions, untilMillis = 100)
        assertEquals(
            listOf(
                DeviceActivitySegment(0, 40, listOf("Desktop")),
                DeviceActivitySegment(40, 60, listOf("Desktop", "Phone")),
                DeviceActivitySegment(60, 100, listOf("Phone")),
            ),
            segments,
        )
    }

    @Test
    fun uncovered_middle_is_a_real_no_device_segment() {
        val sessions = listOf(session("d1", 0, 20, "desktop"), session("d1", 50, 80, "desktop"))
        val segments = deviceActivitySegments(TaskTimeRange(0, 80), sessions, untilMillis = 80)
        assertEquals(
            listOf(
                DeviceActivitySegment(0, 20, listOf("Desktop")),
                DeviceActivitySegment(20, 50, emptyList()),
                DeviceActivitySegment(50, 80, listOf("Desktop")),
            ),
            segments,
        )
    }

    @Test
    fun adjacent_leases_of_one_device_merge_into_one_segment() {
        // The phone's one-minute foreground leases tile contiguously; the display must not cut them apart.
        val sessions = listOf(
            session("p1", 0, 60, "phone"),
            session("p1", 60, 120, "phone"),
        )
        val segments = deviceActivitySegments(TaskTimeRange(0, 120), sessions, untilMillis = 120)
        assertEquals(listOf(DeviceActivitySegment(0, 120, listOf("Phone"))), segments)
    }

    @Test
    fun nothing_is_claimed_before_the_oldest_known_session_or_after_until() {
        val sessions = listOf(session("d1", 100, 200, "desktop"))
        // Panel starts before any data exists and ends after `until` (still running): only [100, 150] is known.
        val segments = deviceActivitySegments(TaskTimeRange(0, 400), sessions, untilMillis = 150)
        assertEquals(listOf(DeviceActivitySegment(100, 150, listOf("Desktop"))), segments)
    }

    @Test
    fun two_installs_of_the_same_kind_are_numbered() {
        val sessions = listOf(
            session("p1", 0, 50, "phone"),
            session("p2", 20, 80, "phone"),
        )
        val segments = deviceActivitySegments(TaskTimeRange(0, 80), sessions, untilMillis = 80)
        assertEquals(
            listOf(
                DeviceActivitySegment(0, 20, listOf("Phone")),
                DeviceActivitySegment(20, 50, listOf("Phone", "Phone 2")),
                DeviceActivitySegment(50, 80, listOf("Phone 2")),
            ),
            segments,
        )
    }

    @Test
    fun legacy_rows_without_a_kind_read_as_generic_device() {
        val sessions = listOf(session("old", 0, 10, ""))
        val segments = deviceActivitySegments(TaskTimeRange(0, 10), sessions, untilMillis = 10)
        assertEquals(listOf(DeviceActivitySegment(0, 10, listOf("Device"))), segments)
    }

    // ---- recordsForDay carries the segments into the day view as hour-of-day sub-ranges ----

    private val tz = TimeZone.UTC

    private fun at(hour: Int, minute: Int): Long =
        LocalDateTime(2026, 7, 4, hour, minute).toInstant(tz).toEpochMilliseconds()

    @Test
    fun placed_record_clips_segments_to_the_day_in_hour_space() {
        val record = CalendarRecord(
            title = "Work",
            range = TaskTimeRange(at(9, 0), at(11, 0)),
            deviceSegments = listOf(
                DeviceActivitySegment(at(9, 0), at(10, 30), listOf("Desktop")),
                DeviceActivitySegment(at(10, 30), at(11, 0), listOf("Desktop", "Phone")),
            ),
        )
        val placed = recordsForDay(listOf(record), LocalDate(2026, 7, 4), tz).single()
        assertEquals(2, placed.deviceSegments.size)
        assertEquals(9f, placed.deviceSegments[0].startHour)
        assertEquals(10.5f, placed.deviceSegments[0].endHour)
        assertEquals(listOf("Desktop"), placed.deviceSegments[0].devices)
        assertEquals(10.5f, placed.deviceSegments[1].startHour)
        assertEquals(11f, placed.deviceSegments[1].endHour)
        assertEquals(listOf("Desktop", "Phone"), placed.deviceSegments[1].devices)
    }

    @Test
    fun sub_minute_screen_break_keeps_a_nonzero_span_so_it_stays_hoverable() {
        // A 20-s look-away entirely within one minute (09:30:00 – 09:30:20). Dropping seconds would
        // collapse it to startHour == endHour, and a zero-length span tiles no hover zone (no info bubble).
        val start = LocalDateTime(2026, 7, 4, 9, 30, 0).toInstant(tz).toEpochMilliseconds()
        val end = LocalDateTime(2026, 7, 4, 9, 30, 20).toInstant(tz).toEpochMilliseconds()
        val record = CalendarRecord(
            title = "Look away",
            range = TaskTimeRange(start, end),
            screenBreak = true,
        )
        val placed = recordsForDay(listOf(record), LocalDate(2026, 7, 4), tz).single()
        assertTrue(placed.endHour > placed.startHour, "sub-minute break must have a nonzero span")
    }
}
