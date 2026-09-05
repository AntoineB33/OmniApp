package org.example.project

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.floor
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
import org.example.project.ui.hourOfDayExact
import org.example.project.ui.nowLineOffsetPx
import org.example.project.ui.recordsForDay

/**
 * PRD §8: the now-line, and everything the line DRAGS, are placed in one hour-of-day space and at the full
 * resolution of the instant — see [hourOfDayExact] and [nowLineOffsetPx].
 *
 * Three bugs, each one order of magnitude below the last, and each found by the same report ("it moves one
 * step at a time"):
 *
 *  1. **The minute.** The indicator was drawn at `hour + minute / 60`, so it sat at the start of the current
 *     minute — up to 59 s behind the instant everything around it was placed at. At the zoom the ceiling
 *     exists for (a 20-second look-away has to be visible and hoverable, ADR 0002) a minute is hundreds of
 *     pixels, and everything the calendar placed truthfully read as being on the wrong side of the line.
 *  2. **The second.** Fixed for the line ([hourOfDayExact]) but not for the BANDS, which [recordsForDay]
 *     still floored: the pose the line drags is `(t_p, t_p + d]`, so its top edge is the line, and it moved
 *     a whole second at a time — ~1.7 dp at the ceiling — beside a line that glided.
 *  3. **The pixel.** The line was frame-sampled and then rounded to the pixel grid by `IntOffset`, which
 *     turns a glide back into a jump held still for one pixel's worth of travel (~75 s at zoom 1). A
 *     fractional `translationY` is what buys the last order of magnitude; below one pixel a display has
 *     nothing left but intensity, so anti-aliasing IS the continuous answer.
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
    fun the_now_line_itself_is_placed_below_the_second() {
        // `docs/scheduler_requirements.md` § *$now line$*: *"the $now line$ moves continuously forward in
        // time"*. [hourOfDay] answers to the second, which is all anything DERIVED from the clock needs — it
        // is recomputed on the app's quantized display instant. The LINE is not one of those: it is one
        // number, placed on every frame, so it reads [hourOfDayExact] and the second is no longer its floor.
        //
        // The reported anomaly: at the zoom ceiling (128x, so 6144 dp per hour) one second is ~1.7 dp, and a
        // line placed to the second advances in 1.7 dp jerks once a second instead of gliding.
        val onTheSecond = LocalTime(16, 41, 47)
        val quarterLater = LocalTime(16, 41, 47, 250_000_000)
        // The tolerance is the FLOAT's own: [hourOfDay] is a `Float` around 24, whose steps are ~3e-6 h
        // (~10 ms). That is exactly why the line's own reading is a `Double` — at the zoom ceiling the
        // coarse type alone would quantize it, before the second does.
        assertEquals(onTheSecond.hourOfDay().toDouble(), onTheSecond.hourOfDayExact(), 1e-5)
        assertTrue(
            quarterLater.hourOfDayExact() > onTheSecond.hourOfDayExact(),
            "a quarter of a second must not place at the same point as the second it is inside",
        )
        // ...and it is the RIGHT quarter second: a quarter of 1/3600 of an hour.
        assertEquals(
            0.25 / 3600.0,
            quarterLater.hourOfDayExact() - onTheSecond.hourOfDayExact(),
            1e-12,
        )
        // The whole second is still exactly where [hourOfDay] puts it, so the line and everything placed
        // beside it agree at every instant the coarse reading can name.
        assertEquals(LocalTime(23, 59, 59).hourOfDay().toDouble(), LocalTime(23, 59, 59).hourOfDayExact(), 1e-5)
    }

    @Test
    fun a_band_pinned_to_the_now_line_is_placed_below_the_second_too() {
        // The sequel to the test above, and the reported anomaly: the LINE went sub-second, but every BAND
        // the line drags was still floored to the second by [recordsForDay]. At the zoom ceiling
        // (128 x 48 dp = 6144 dp per hour) a second is ~1.7 dp, so a pose the line drags — `(t_p, t_p + d]`,
        // i.e. a band whose top edge IS the line — held still for a whole second and then jumped 1.7 dp,
        // while the line beside it glided. "The lag is one pixel" was only true at zoom 1.
        val quarterPast = at(16, 41, 47) + 250L
        val pose = CalendarRecord(
            title = "rest",
            range = TaskTimeRange(quarterPast, quarterPast + 5 * 60_000L),
            screenBreak = true,
        )
        val onTheSecond = CalendarRecord(
            title = "rest",
            range = TaskTimeRange(at(16, 41, 47), at(16, 41, 47) + 5 * 60_000L),
            screenBreak = true,
        )
        val placedQuarter = recordsForDay(listOf(pose), day, tz).single()
        val placedSecond = recordsForDay(listOf(onTheSecond), day, tz).single()
        assertTrue(
            placedQuarter.startHour > placedSecond.startHour,
            "a quarter of a second later must not place at the same point — that collapse IS the jerk",
        )
        // And at the RIGHT distance: a quarter of a second, in hours. The tolerance is the `Float`'s own
        // step around hour 16 (~7 ms), which is under 0.02 dp even at the zoom ceiling.
        assertEquals(
            (0.25 / 3600.0).toFloat(),
            placedQuarter.startHour - placedSecond.startHour,
            2e-6f,
        )
    }

    @Test
    fun the_now_line_is_placed_between_pixels_not_on_them() {
        // `docs/scheduler_requirements.md` § *$now line$*: *"the $now line$ moves continuously forward in
        // time"*. Sampling the clock per frame is only half of that — the other half is that the answer must
        // reach the screen unrounded. `offset { IntOffset(…) }` cannot carry a fraction, so the line used to
        // be snapped to the pixel grid: it stood still and then jumped a whole pixel (~75 s of travel at the
        // default zoom, ~0.5 s at the ceiling), which is exactly the "moves one step at a time" report.
        val density = Density(1f)
        val hourHeight = 48.dp // zoom 1
        with(density) {
            // Ten frames at 60 Hz cover ~0.17 s: far under one pixel at this zoom (one pixel is ~75 s), so
            // EVERY sample must still be a distinct position — that is what anti-aliasing then renders as
            // motion. Rounded to the grid all ten would collapse onto one value.
            val hours = (0 until 10).map { 16.0 + (it * 16_666_666L) / 3_600_000_000_000.0 }
            val offsets = hours.map { nowLineOffsetPx(hourHeight, it) }
            offsets.zipWithNext { a, b -> assertTrue(b > a, "frame $b must not land on frame $a") }
            assertTrue(
                offsets.any { abs(it - floor(it)) > 1e-4f },
                "at least one frame must fall BETWEEN two pixels",
            )
        }
    }

    @Test
    fun the_now_line_offset_keeps_its_precision_at_the_zoom_ceiling() {
        // At the ceiling the offset is ~150 000 px, where a `Float` step is ~0.016 px. Taking the product in
        // `Double` keeps a frame's worth of travel (~0.03 px there) above that; taking it in `Float` — or
        // reading the hour as a `Float`, whose own step around 24 is ~7 ms — would quantize the glide before
        // it ever reached the pixel grid.
        val density = Density(1f)
        val hourHeight = (128 * 48).dp // MAX_CALENDAR_ZOOM x BASE_HOUR_HEIGHT
        with(density) {
            val a = nowLineOffsetPx(hourHeight, 23.5)
            val b = nowLineOffsetPx(hourHeight, 23.5 + 16_666_666L / 3_600_000_000_000.0)
            assertTrue(b > a, "one frame of travel must survive the multiply at the zoom ceiling")
            // One frame at 60 Hz is 1/216000 of an hour; at 6144 px per hour that is ~0.028 px.
            assertEquals(0.0284f, b - a, 5e-3f)
        }
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
