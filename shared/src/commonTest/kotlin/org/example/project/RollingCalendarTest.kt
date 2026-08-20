package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import org.example.project.ui.nowLineCenterOffset
import org.example.project.ui.rollingDayAt
import org.example.project.ui.rollingDayShift
import org.example.project.ui.rollingRowCount
import org.example.project.ui.weekAnchorDay
import org.example.project.ui.wholeDayZoom
import org.example.project.ui.zoomAnchoredOffset

/**
 * PRD §8 infinite scroll: the calendar is not a week grid but a set of endless vertical timelines, one per
 * column. The user scrolls up and down forever; under day d sits day d+1 and above it d-1. These are the
 * pure pieces that make that true — the day the grid draws at (row, column), the rebase that keeps the
 * scroll offset inside one day (so there is no scroll range to run out of), and the row count that keeps
 * the work bounded by the SCREEN rather than by how far the user has scrolled.
 */
class RollingCalendarTest {

    private val dayHeight = 1152f // 24 h × 48 dp at zoom 1

    // ----- the layout law ---------------------------------------------------------------------

    @Test
    fun under_day_d_is_day_d_plus_one_and_above_it_d_minus_one() {
        val anchor = LocalDate(2026, 8, 20)
        // Straight down one column: consecutive days, the whole point of the rolling grid.
        assertEquals(LocalDate(2026, 8, 20), rollingDayAt(anchor, row = 0, column = 0))
        assertEquals(LocalDate(2026, 8, 21), rollingDayAt(anchor, row = 1, column = 0))
        assertEquals(LocalDate(2026, 8, 22), rollingDayAt(anchor, row = 2, column = 0))
        // ...and one row UP is the day before, which is what an endless scroll upward walks into.
        assertEquals(LocalDate(2026, 8, 19), rollingDayAt(anchor, row = -1, column = 0))

        // Across a row: consecutive days left→right, so the columns are a week-wide window on the timeline.
        val topRow = (0..6).map { rollingDayAt(anchor, row = 0, column = it) }
        assertEquals((0..6).map { anchor.plus(it, DateTimeUnit.DAY) }, topRow)

        // Every column is the same timeline phase-shifted by a day: what is one row down in column i is
        // what is on the top row of column i+1.
        for (column in 0..5) {
            assertEquals(
                rollingDayAt(anchor, row = 1, column = column),
                rollingDayAt(anchor, row = 0, column = column + 1),
                "column $column's next day must be its right neighbour's top day",
            )
        }
    }

    @Test
    fun a_month_and_a_year_boundary_roll_like_any_other_day() {
        assertEquals(LocalDate(2026, 9, 1), rollingDayAt(LocalDate(2026, 8, 31), row = 1, column = 0))
        assertEquals(LocalDate(2027, 1, 1), rollingDayAt(LocalDate(2026, 12, 31), row = 0, column = 1))
    }

    // ----- the rebase that makes the scroll endless -------------------------------------------

    @Test
    fun the_scroll_offset_rolls_whole_days_into_the_anchor_instead_of_hitting_a_range() {
        // Inside the anchor day: nothing to roll.
        assertEquals(0, rollingDayShift(0f, dayHeight))
        assertEquals(0, rollingDayShift(dayHeight - 1f, dayHeight))
        // Exactly one day down, and deep into the future: the anchor absorbs the whole days.
        assertEquals(1, rollingDayShift(dayHeight, dayHeight))
        assertEquals(3, rollingDayShift(3 * dayHeight + 10f, dayHeight))
        // Scrolled UP past the anchor day's midnight — negative, so the anchor walks backwards. This is
        // the half a clamped ScrollState could never express, and it is why the grid can scroll up forever.
        assertEquals(-1, rollingDayShift(-1f, dayHeight))
        assertEquals(-2, rollingDayShift(-dayHeight - 1f, dayHeight))
        // A zero/absent height (first frame, before layout) must not divide by zero.
        assertEquals(0, rollingDayShift(500f, 0f))
    }

    @Test
    fun rebasing_leaves_the_offset_inside_one_day_wherever_the_user_scrolled_to() {
        // The grid's invariant: after rolling the whole days out, 0 <= offset < dayHeight — whatever the
        // raw offset was. Walk a long scroll in both directions and check the invariant every step.
        var anchor = LocalDate(2026, 8, 20)
        var offset = 0f
        // Down for ten days, then back up for twenty, in awkward 400 px steps that never land on a boundary.
        val steps = List(25) { 400f } + List(50) { -400f }
        for (delta in steps) {
            offset += delta
            val roll = rollingDayShift(offset, dayHeight)
            anchor = anchor.plus(roll, DateTimeUnit.DAY)
            offset -= roll * dayHeight
            assertTrue(offset >= 0f && offset < dayHeight, "offset escaped its day: $offset")
        }
        // 25 down and 50 up at 400 px is a net -10000 px ≈ -8.68 days ⇒ nine days back, part-way into it.
        assertEquals(LocalDate(2026, 8, 11), anchor)
        assertEquals(9 * dayHeight - 10000f, offset, 0.01f)
    }

    @Test
    fun a_zoom_that_scrolls_past_midnight_rebases_onto_the_neighbouring_day() {
        // Zooming out near the top of the anchor day pulls the offset negative; the rebase must answer
        // "the previous day", not "the top of the calendar" — there is no top.
        val offset = zoomAnchoredOffset(currentOffset = 0f, focalY = 100f, scaleFactor = 0.5f)
        assertEquals(-50f, offset)
        assertEquals(-1, rollingDayShift(offset, dayHeight))
        assertEquals(dayHeight - 50f, offset - rollingDayShift(offset, dayHeight) * dayHeight, 0.01f)
    }

    // ----- the now-line lock --------------------------------------------------------------------

    /** Where the now-line lands in the viewport, given the offset the lock chose. Mirrors the layout. */
    private fun nowLineY(daysFromAnchor: Int, fraction: Float, dayHeightPx: Float, offset: Float): Float =
        (daysFromAnchor + fraction) * dayHeightPx - offset

    @Test
    fun the_lock_puts_the_now_line_on_the_middle_of_the_viewport() {
        val viewport = 600f
        // Noon, today at the top of the grid: half a day down, minus half a viewport.
        val offset = nowLineCenterOffset(
            daysFromAnchorToToday = 0,
            dayFraction = 0.5f,
            dayHeightPx = dayHeight,
            viewportPx = viewport,
        )
        assertEquals(0.5f * dayHeight - 300f, offset, 0.01f)
        assertEquals(300f, nowLineY(0, 0.5f, dayHeight, offset), 0.01f)

        // ...and it stays centred wherever in the day the now-line is, and whatever the viewport height.
        for (fraction in listOf(0f, 0.01f, 0.25f, 0.5f, 0.75f, 0.999f)) {
            for (viewportPx in listOf(200f, 600f, 1500f, 4000f)) {
                val o = nowLineCenterOffset(0, fraction, dayHeight, viewportPx)
                assertEquals(
                    viewportPx / 2f,
                    nowLineY(0, fraction, dayHeight, o),
                    0.01f,
                    "now-line off centre at fraction $fraction, viewport $viewportPx",
                )
            }
        }
    }

    @Test
    fun the_lock_survives_the_rebase_that_rolls_the_middle_of_the_view_onto_another_day() {
        // Just after midnight the middle of a centred view belongs to YESTERDAY, so the chosen offset is
        // negative and the rebase walks the anchor a day back. The now-line must still be dead centre
        // afterwards — this is the pairing that makes the lock work at either end of a day.
        val viewport = 600f
        var anchor = LocalDate(2026, 8, 20) // == today
        val fraction = 10f / (24f * 60f) // 00:10
        var offset = nowLineCenterOffset(0, fraction, dayHeight, viewport) // anchor == today
        assertTrue(offset < 0f, "expected the centred view to reach back into the previous day")

        val roll = rollingDayShift(offset, dayHeight)
        assertEquals(-1, roll)
        anchor = anchor.plus(roll, DateTimeUnit.DAY)
        offset -= roll * dayHeight
        assertTrue(offset >= 0f && offset < dayHeight, "offset escaped its day: $offset")
        // Today is now one row DOWN from the anchor, and the now-line is still centred.
        assertEquals(LocalDate(2026, 8, 19), anchor)
        assertEquals(300f, nowLineY(1, fraction, dayHeight, offset), 0.01f)

        // The symmetric case: late in the day the centred view reaches into tomorrow, and rolls forward.
        anchor = LocalDate(2026, 8, 20)
        val late = 1f - 10f / (24f * 60f) // 23:50
        offset = nowLineCenterOffset(0, late, dayHeight, viewport)
        val rollForward = rollingDayShift(offset, dayHeight)
        assertEquals(0, rollForward) // 23:50 minus 5 min of viewport is still inside the same day
        assertEquals(300f, nowLineY(0, late, dayHeight, offset), 0.01f)
    }

    @Test
    fun a_zoom_under_the_lock_pivots_on_the_now_line_and_not_on_the_cursor() {
        // The unlocked zoom keeps whatever is under the CURSOR fixed; the locked one re-centres, so the
        // now-line is at the middle before and after however deep the zoom goes.
        val viewport = 600f
        val fraction = 0.5f
        var dayH = dayHeight
        var offset = nowLineCenterOffset(0, fraction, dayH, viewport)
        for (step in listOf(1.15f, 1.15f, 8f, 1f / 3f, 0.5f)) {
            dayH *= step
            offset = nowLineCenterOffset(0, fraction, dayH, viewport)
            assertEquals(300f, nowLineY(0, fraction, dayH, offset), 0.01f, "lost the now-line at zoom $step")
        }
        // For contrast: the same zoom step anchored at a cursor 100 px from the top moves the now-line.
        val cursorAnchored = zoomAnchoredOffset(nowLineCenterOffset(0, fraction, dayHeight, viewport), 100f, 2f)
        assertTrue(
            nowLineY(0, fraction, dayHeight * 2f, cursorAnchored) != 300f,
            "a cursor-anchored zoom should NOT keep the now-line centred",
        )
    }

    @Test
    fun the_lock_needs_no_measured_viewport_to_be_well_defined() {
        // Before the first layout the viewport is 0 px; the offset is then simply the now-line's own
        // content position, which is harmless (the caller skips the write until the height is known).
        assertEquals(0.5f * dayHeight, nowLineCenterOffset(0, 0.5f, dayHeight, 0f), 0.01f)
    }

    // ----- the date pick's whole-day fit --------------------------------------------------------

    @Test
    fun picking_a_date_zooms_so_exactly_one_whole_day_fills_the_viewport() {
        // PRD §7: the pick resets the view, and "reset" means the WHOLE day is on screen — so the zoom it
        // chooses must make a day exactly as tall as the viewport, whatever the viewport is.
        for (viewport in listOf(400f, 600f, 1152f, 2000f, 4000f)) {
            val zoom = wholeDayZoom(viewport, dayHeight)
            assertEquals(viewport, dayHeight * zoom, 0.01f, "a day does not fill a $viewport px viewport")
            // ...and the pick also resets the offset to 0, so that day's midnight is at the top of the
            // viewport and the next day's is exactly at its bottom: the visible span is the whole day.
            val offsetAfterPick = 0f
            assertEquals(0f, 0 * dayHeight * zoom - offsetAfterPick, 0.01f)
            assertEquals(viewport, 1 * dayHeight * zoom - offsetAfterPick, 0.01f)
        }
    }

    @Test
    fun the_whole_day_fit_stays_inside_the_zoom_bounds_and_survives_the_first_frame() {
        // A viewport far shorter/taller than any legal zoom can render a day at is clamped like any other
        // zoom — the fit is a zoom, not an escape from the bounds.
        assertEquals(0.25f, wholeDayZoom(viewportPx = 10f, dayHeightPxAtZoom1 = dayHeight))
        assertEquals(16f, wholeDayZoom(viewportPx = 100_000f, dayHeightPxAtZoom1 = dayHeight))
        // The zoom floor must still be low enough for a genuinely small window to show a whole day, or the
        // pick would silently stop keeping its promise there.
        assertEquals(0.5f, wholeDayZoom(viewportPx = dayHeight / 2f, dayHeightPxAtZoom1 = dayHeight))
        // Before the first layout there is no viewport to fit to: neutral, and the caller skips the write.
        assertEquals(1f, wholeDayZoom(viewportPx = 0f, dayHeightPxAtZoom1 = dayHeight))
        assertEquals(1f, wholeDayZoom(viewportPx = 600f, dayHeightPxAtZoom1 = 0f))
    }

    @Test
    fun the_whole_day_fit_shows_a_whole_week_of_whole_days() {
        // The columns are consecutive days (rollingDayAt), so a viewport exactly one day tall, scrolled to
        // the anchor's midnight, shows seven whole days — the picked day's WEEK, since the pick anchors on
        // that week's Monday ([weekAnchorDay]) rather than on the picked day itself.
        val viewport = 900f
        val zoom = wholeDayZoom(viewport, dayHeight)
        val picked = LocalDate(2026, 8, 20) // a Thursday
        val anchor = weekAnchorDay(picked)
        assertEquals(
            (0..6).map { anchor.plus(it, DateTimeUnit.DAY) },
            (0..6).map { rollingDayAt(anchor, row = 0, column = it) },
        )
        // One row is enough to cover the viewport (the count adds its usual spare for the scrolled row).
        assertEquals(2, rollingRowCount(viewport, dayHeight * zoom))
        // And the reset offset needs no rebase: it is already inside the anchor day.
        assertEquals(0, rollingDayShift(0f, dayHeight * zoom))
    }

    @Test
    fun picking_a_day_shows_the_whole_week_it_belongs_to_not_the_six_days_after_it() {
        // PRD §7: the pick means "take me to that week", so the picked day must be somewhere IN the
        // columns with its own week around it — Monday at the left edge, Sunday at the right, whichever
        // weekday was picked. Anchoring on the picked day itself (the old behaviour) put Thursday at the
        // left and hid Monday-Wednesday entirely.
        val monday = LocalDate(2026, 8, 17)
        for (offset in 0..6) {
            val picked = monday.plus(offset, DateTimeUnit.DAY)
            val anchor = weekAnchorDay(picked)
            assertEquals(monday, anchor, "$picked belongs to the week of $monday")
            val columns = (0..6).map { rollingDayAt(anchor, row = 0, column = it) }
            assertEquals(monday, columns.first())
            assertEquals(monday.plus(6, DateTimeUnit.DAY), columns.last()) // the Sunday
            assertEquals(offset, columns.indexOf(picked), "the picked day must be its weekday's column")
        }
    }

    @Test
    fun the_week_anchor_is_monday_first_like_the_side_menu_s_month_rail() {
        // A Monday anchors on itself (nothing to walk back), a Sunday walks back six days — and the walk
        // crosses month and year boundaries like any other date arithmetic.
        assertEquals(LocalDate(2026, 8, 17), weekAnchorDay(LocalDate(2026, 8, 17))) // Monday
        assertEquals(LocalDate(2026, 8, 17), weekAnchorDay(LocalDate(2026, 8, 23))) // the Sunday after it
        assertEquals(LocalDate(2026, 8, 31), weekAnchorDay(LocalDate(2026, 9, 1))) // a Tuesday in September
        assertEquals(LocalDate(2025, 12, 29), weekAnchorDay(LocalDate(2026, 1, 1))) // a Thursday in January
    }

    // ----- cost follows the screen -------------------------------------------------------------

    @Test
    fun the_rows_composed_follow_the_viewport_and_the_zoom_not_the_distance_scrolled() {
        // CLAUDE.md hot-path rule: what the grid composes must be bounded by the SCREEN. Under the
        // `0 <= offset < dayHeight` invariant, covering the viewport needs the days that fit plus one for
        // the partly-scrolled row at the top — and nothing about how far from today the user has scrolled.
        assertEquals(2, rollingRowCount(viewportPx = 600f, dayHeightPx = dayHeight))
        assertEquals(2, rollingRowCount(viewportPx = dayHeight, dayHeightPx = dayHeight))
        // Zoomed out to half height, a tall window shows parts of three days.
        assertEquals(3, rollingRowCount(viewportPx = 900f, dayHeightPx = dayHeight / 2f))
        // Zoomed right in, one day towers over the viewport: one row plus the top one.
        assertEquals(2, rollingRowCount(viewportPx = 600f, dayHeightPx = dayHeight * 8f))
        // Degenerate first frames (no measured viewport / no height yet) still compose something.
        assertEquals(1, rollingRowCount(viewportPx = 0f, dayHeightPx = dayHeight))
        assertEquals(1, rollingRowCount(viewportPx = 600f, dayHeightPx = 0f))
    }

    @Test
    fun the_rows_always_cover_the_viewport_whatever_the_offset_inside_the_day() {
        // The count drops the offset from its calculation, so prove it is still enough at the worst case:
        // scrolled to the very last pixel of the anchor day.
        for (viewport in listOf(200f, 600f, 1151f, 1152f, 2000f, 5000f)) {
            val rows = rollingRowCount(viewport, dayHeight)
            val worstOffset = dayHeight - 0.001f
            val covered = rows * dayHeight - worstOffset
            assertTrue(covered >= viewport, "viewport $viewport not covered by $rows rows")
        }
    }
}
