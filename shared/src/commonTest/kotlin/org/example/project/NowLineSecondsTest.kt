package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import org.example.project.scheduler.model.TaskTimeRange
import org.example.project.ui.CalendarRecord
import org.example.project.ui.hourOfDay
import org.example.project.ui.recordsForDay

/**
 * PRD §8: the now-line is placed in the SAME hour-of-day space every band, panel and layer region is —
 * seconds included ([hourOfDay]).
 *
 * The bug this pins: the current-time indicator was drawn at `hour + minute / 60`, so it sat at the start of
 * the current minute — up to 59 seconds behind the instant everything around it was placed at. At zoom 1 a
 * minute is under a pixel and it never showed; at the zoom the ceiling exists for (a 20-second look-away has
 * to be visible and hoverable, ADR 0002) a minute is hundreds of pixels, and everything the calendar placed
 * truthfully read as being on the wrong side of the line: a "no phone unlocked" layer region ending at `now`
 * looked like a claim about the future, a grey Inactivity band ending before `now` looked like scheduled
 * emptiness after it, and the elapsed part of the panel the line sits in looked entirely unelapsed.
 */
class NowLineSecondsTest {
    private val tz = TimeZone.UTC
    private val day = LocalDate(2026, 8, 29)

    private fun at(h: Int, m: Int, s: Int): Long =
        LocalDateTime(2026, 8, 29, h, m, s).toInstant(tz).toEpochMilliseconds()

    @Test
    fun the_now_line_carries_its_seconds() {
        assertEquals(16f + 41f / 60f + 47f / 3600f, LocalTime(16, 41, 47).hourOfDay(), 1e-6f)
        // Half a minute apart must not place at the same point — that collapse is the whole bug.
        assertTrue(LocalTime(16, 41, 0).hourOfDay() < LocalTime(16, 41, 30).hourOfDay())
    }

    @Test
    fun a_band_ending_at_now_ends_ON_the_now_line_not_after_it() {
        // The observed anomaly, in numbers: a layer region [16:38:00, 16:41:47] against a now-line at
        // 16:41:47. Read to the minute the line landed at 16:41:00 and the region's last 47 seconds drew
        // BELOW it, which reads as the app claiming to know a phone would stay locked.
        val now = LocalTime(16, 41, 47)
        val layer = CalendarRecord(
            title = "No phone unlocked",
            range = TaskTimeRange(at(16, 38, 0), at(16, 41, 47)),
        )
        val placed = recordsForDay(listOf(layer), day, tz).single()
        assertEquals(placed.endHour, now.hourOfDay(), 1e-6f)
    }

    @Test
    fun the_panel_the_now_line_sits_in_is_part_elapsed() {
        // The panel of the same report: "planning" 16:41:17 – 16:47, now 16:41:47. Thirty seconds of it are
        // behind the line — which is why its hover bubble legitimately names the device that was open.
        val now = LocalTime(16, 41, 47)
        val panel = CalendarRecord(title = "planning", range = TaskTimeRange(at(16, 41, 17), at(16, 47, 0)))
        val placed = recordsForDay(listOf(panel), day, tz).single()
        assertTrue(placed.startHour < now.hourOfDay(), "the panel starts before the now-line")
        assertTrue(placed.endHour > now.hourOfDay(), "and ends after it")
    }
}
