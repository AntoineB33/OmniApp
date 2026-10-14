package org.example.project

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.model.TaskTimeRange
import org.example.project.ui.CalendarFloatingWindow
import org.example.project.ui.CalendarRecord
import org.jetbrains.skia.Bitmap

/**
 * User rule 2026-10-14: the calendar under an "unfocused notif" window opens on EXACTLY its notifications, the
 * now-line included — and two of them can be seconds apart (anomaly 2026-10-14: a 6-second pair opened on ~5 minutes,
 * the old zoom ceiling). A day that tall is ~9 million px, past anything Compose can measure, so the rows lay out only
 * their slice ([org.example.project.ui.DaySlice]). This RENDERS such a calendar headlessly, an eight-hour task panel
 * running through the six seconds: it must lay out at all, and the panel — cut to the slice — must still fill the view.
 */
class CalendarSecondsRenderTest {

    private val tz = TimeZone.currentSystemDefault()
    private val day = LocalDate(2026, 10, 14)
    private fun at(hour: Int, minute: Int, second: Int = 0) =
        LocalDateTime(day.year, day.month, day.day, hour, minute, second).toInstant(tz).toEpochMilliseconds()

    /**
     * The longest vertical run of the panel's colour down any one pixel column of the rendered window, as a share of
     * the image's height: the panel crossing the whole grid is about the grid's height.
     */
    private fun panelRun(openingSpan: Pair<Long, Long>?): Double = panelRuns(at(20, 0), listOf(openingSpan)).single()

    /** [panelRun] for a panel ending at [panelEnd], the window given each of [spans] in turn while it stays open. */
    private fun panelRuns(
        panelEnd: Long,
        spans: List<Pair<Long, Long>?>,
        /** The exact clock at each step — what the frame sampler reads; the line at 17:10:06 by default. */
        lines: List<Long> = spans.map { at(17, 10, 6) },
    ): List<Double> {
        val span = androidx.compose.runtime.mutableStateOf(spans.first())
        var line = lines.first()
        val panel = CalendarRecord(
            title = "Work",
            range = TaskTimeRange(at(12, 0), panelEnd),
            scheduled = true,
            manual = true,
            entryId = "panel-1",
            entryIds = listOf("panel-1"),
            taskId = TaskId("task-1"),
        )
        val width = 1800
        val height = 1000
        val scene =
            ImageComposeScene(width = width, height = height, density = Density(2f)) {
                val chips = listOf(
                    org.example.project.scheduler.domain.CalendarNotifications.Chip("a", at(17, 10, 2), "Alarm"),
                    org.example.project.scheduler.domain.CalendarNotifications.Chip("b", at(17, 10, 4), "Timer"),
                )
                androidx.compose.runtime.CompositionLocalProvider(
                    org.example.project.ui.LocalCalendarNotifications provides
                        org.example.project.ui.CalendarNotificationsView(chips, emptySet(), {}, mapOf(day to chips), { it.title }),
                ) {
                CalendarFloatingWindow(
                    selectedDate = day,
                    today = day,
                    nowMillis = at(17, 10, 6),
                    nowExactMillis = { line },
                    onDismiss = {},
                    records = listOf(panel),
                    taskColors = mapOf(TaskId("task-1") to Color.Magenta),
                    initialSize = androidx.compose.ui.geometry.Size(width.toFloat(), height.toFloat()),
                    openingSpan = span.value,
                )
                }
            }
        try {
            var time = 0L
            return spans.mapIndexed { i, next ->
                span.value = next
                line = lines[i]
                // The fit waits for the measured viewport and an effect: a few frames.
                var image = scene.render(time)
                repeat(6) { time += 50_000_000L; image = scene.render(time) }
                measureRun(Bitmap.makeFromImage(image), width, height)
            }
        } finally {
            scene.close()
        }
    }

    private fun measureRun(bitmap: Bitmap, width: Int, height: Int): Double {
        run {
            fun magenta(x: Int, y: Int): Boolean {
                val c = bitmap.getColor(x, y)
                return (c shr 16) and 0xFF > 200 && (c shr 8) and 0xFF < 80 && c and 0xFF > 200
            }
            var longest = 0
            for (x in 0 until width step 4) {
                var run = 0
                for (y in 0 until height) {
                    // A graduation line redrawn over the panel, or the now-line across it, is a few pixels: it does
                    // not end the run.
                    run = if (magenta(x, y) || (y + 8 < height && magenta(x, y + 8))) run + 1 else 0
                    longest = maxOf(longest, run)
                }
            }
            return longest.toDouble() / height
        }
    }

    @Test
    fun a_calendar_opened_on_six_seconds_lays_out_and_draws_the_panel_running_through_them() {
        // Six seconds of an eight-hour panel: its day's column is the panel from the top of the grid to the bottom
        // (the window's title bar and the day headers take the rest of the height).
        val seconds = panelRun(at(17, 10, 0) to at(17, 10, 6))
        assertTrue(seconds > 0.6, "the panel runs down ${seconds * 100} % of the window")
        // The ordinary opening (an hour and a half around the line) draws it the same — the same calendar, unsliced.
        val ordinary = panelRun(null)
        assertTrue(ordinary > 0.6, "the panel runs down ${ordinary * 100} % of the ordinary window")
    }

    @Test
    fun the_open_calendar_is_fitted_again_when_the_notifications_it_shows_change() {
        // Anomaly 2026-10-14: a standing pair given new notifications kept the view fitted before them. A panel ending
        // at 17:10:03: over 17:10:00–17:10:06 it fills half the grid; the window then shows a notification from
        // 17:09:00 (the stretch 17:09:00–17:10:06), and the panel must fill 63/66 of it — the view followed.
        val (before, after) = panelRuns(at(17, 10, 3), listOf(at(17, 10, 0) to at(17, 10, 6), at(17, 9, 0) to at(17, 10, 6)))
        assertTrue(after > before * 1.5, "the panel ran down ${before * 100} % then ${after * 100} %")
    }

    @Test
    fun the_grid_sections_title_retracts_it_like_its_arrow() {
        // User rule 2026-10-14: "the title of both sections must be clickable to expand or retract like the arrow
        // button". A click on the month ("October 2026", the grid's title) retracts the grid: the panel is gone.
        val width = 1800
        val height = 1000
        val panel = CalendarRecord(
            title = "Work", range = TaskTimeRange(at(12, 0), at(20, 0)), scheduled = true, manual = true,
            entryId = "panel-1", entryIds = listOf("panel-1"), taskId = TaskId("task-1"),
        )
        val scene =
            ImageComposeScene(width = width, height = height, density = Density(2f)) {
                CalendarFloatingWindow(
                    selectedDate = day,
                    today = day,
                    nowMillis = at(17, 10, 6),
                    onDismiss = {},
                    records = listOf(panel),
                    taskColors = mapOf(TaskId("task-1") to Color.Magenta),
                    initialSize = androidx.compose.ui.geometry.Size(width.toFloat(), height.toFloat()),
                )
            }
        try {
            var time = 0L
            fun frames(): Double {
                var image = scene.render(time)
                repeat(6) { time += 50_000_000L; image = scene.render(time) }
                return measureRun(Bitmap.makeFromImage(image), width, height)
            }
            assertTrue(frames() > 0.6, "the grid is there before the click")
            // The month label, at the top left of the grid (24..226 px, 108..140 px in this window).
            val title = androidx.compose.ui.geometry.Offset(120f, 124f)
            scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Move, title)
            scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Press, title)
            scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Release, title)
            val after = frames()
            assertTrue(after < 0.05, "the panel still runs down ${after * 100} % after the title was clicked")
        } finally {
            scene.close()
        }
    }

    @Test
    fun the_view_grows_as_the_line_moves_on() {
        // Anomaly 2026-10-14: *"the $now line$ is moving forward, so the window is supposed to get larger"*. The same
        // stretch (17:10:00, the line at 17:10:06), the window left open while the line moves on to 17:10:12: the view
        // is now 17:10:00–17:10:12, so a panel ending at 17:10:03 fills a quarter of it instead of half.
        val span = at(17, 10, 0) to at(17, 10, 6)
        val (before, after) = panelRuns(at(17, 10, 3), listOf(span, span), lines = listOf(at(17, 10, 6), at(17, 10, 12)))
        assertTrue(after < before * 0.65, "the panel ran down ${before * 100} % then ${after * 100} %")
        assertTrue(after > before * 0.35, "the panel ran down ${before * 100} % then ${after * 100} %")
    }
}
