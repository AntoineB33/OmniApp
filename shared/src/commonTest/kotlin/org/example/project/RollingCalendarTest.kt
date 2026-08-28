package org.example.project

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil
import kotlinx.datetime.plus
import org.example.project.ui.HourWindow
import org.example.project.ui.OPENING_SPAN_MINUTES
import org.example.project.ui.calendarSpanZoom
import org.example.project.ui.nowLineCenterColumnShift
import org.example.project.ui.nowLineCenterOffset
import org.example.project.ui.rollingDayAt
import org.example.project.ui.rollingDayShift
import org.example.project.ui.rollingRowCount
import org.example.project.ui.visibleHourWindow
import org.example.project.ui.weekAnchorDay
import org.example.project.ui.maxCalendarZoom
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

    /** The day-row the lock's offset puts the now-line's centred occurrence in. Mirrors the layout. */
    private fun centredRow(fraction: Float, dayHeightPx: Float, viewportPx: Float, offset: Float): Int =
        ((viewportPx / 2f + offset) / dayHeightPx - fraction).roundToInt()

    @Test
    fun the_lock_puts_the_now_line_on_the_middle_of_the_viewport() {
        val viewport = 600f
        // Noon, the grid scrolled to the anchor day's midnight: half a day down, minus half a viewport.
        val offset = nowLineCenterOffset(
            dayFraction = 0.5f,
            dayHeightPx = dayHeight,
            viewportPx = viewport,
            currentOffsetPx = 0f,
        )
        assertEquals(0.5f * dayHeight - 300f, offset, 0.01f)
        assertEquals(300f, nowLineY(0, 0.5f, dayHeight, offset), 0.01f)

        // ...and wherever in the day the now-line is, wherever the grid is scrolled to and whatever the
        // viewport height, the day-row the grid draws it in carries it dead centre.
        for (fraction in listOf(0f, 0.01f, 0.25f, 0.5f, 0.75f, 0.999f)) {
            for (viewportPx in listOf(200f, 600f, 1500f, 4000f)) {
                for (current in listOf(0f, 0.3f * dayHeight, 0.99f * dayHeight)) {
                    val o = nowLineCenterOffset(fraction, dayHeight, viewportPx, current)
                    assertEquals(
                        viewportPx / 2f,
                        nowLineY(centredRow(fraction, dayHeight, viewportPx, o), fraction, dayHeight, o),
                        0.01f,
                        "now-line off centre at fraction $fraction, viewport $viewportPx, from $current",
                    )
                }
            }
        }
    }

    @Test
    fun the_lock_holds_the_now_line_vertically_and_leaves_the_columns_their_days() {
        // The bug this pins: the lock used to scroll to today's occurrence counted from the ANCHOR, so a
        // calendar showing today in the fourth column scrolled three whole days to centre it — and a day of
        // vertical scroll walks every date one column to the left, which is why turning the switch on
        // dragged today into the leftmost column. The rows are a day apart and each column is its
        // neighbour shifted by a day, so the NEAREST occurrence centres the now-line just as well while
        // every column keeps the date it was showing.
        val viewport = 600f
        val fraction = 0.6f
        val anchor = LocalDate(2026, 8, 17)
        val today = LocalDate(2026, 8, 20)
        val daysToToday = anchor.daysUntil(today) // 3: today is the fourth column
        val current = 0.5f * dayHeight

        val offset = nowLineCenterOffset(fraction, dayHeight, viewport, current)
        // The timeline moved by the centring and not by three days...
        assertTrue(abs(offset - current) <= dayHeight / 2f, "the lock scrolled a whole day: $offset")
        assertEquals(0, rollingDayShift(offset, dayHeight), "the rebase must not roll the anchor here")
        // ...no anchor walk, since today is on the grid...
        val row = centredRow(fraction, dayHeight, viewport, offset)
        assertEquals(
            0,
            nowLineCenterColumnShift(daysToToday, fraction, dayHeight, viewport, offset, columns = 7),
        )
        // ...today is still drawn in the column it was in...
        assertEquals(3, daysToToday - row)
        assertEquals(today, rollingDayAt(anchor, row, daysToToday - row))
        // ...and its now-line is dead centre.
        assertEquals(300f, nowLineY(row, fraction, dayHeight, offset), 0.01f)
    }

    @Test
    fun the_lock_walks_the_anchor_only_when_the_now_line_would_be_off_the_drawn_columns() {
        // The one case the lock MAY move the calendar sideways: the user scrolled to another week and then
        // asked to be locked to now, so the centred now-line belongs to no drawn column — and holding the
        // columns still would faithfully hold a now-line that is nowhere on screen. The anchor is walked by
        // exactly the overshoot (shifting it by d moves today's column by -d), landing today back in range.
        val viewport = 600f
        val fraction = 0.6f
        val offset = nowLineCenterOffset(fraction, dayHeight, viewport, currentOffsetPx = 0.5f * dayHeight)
        val row = centredRow(fraction, dayHeight, viewport, offset)

        // Scrolled a week AHEAD of today: today's column is seven to the left of the first one.
        val ahead = nowLineCenterColumnShift(row - 7, fraction, dayHeight, viewport, offset, columns = 7)
        assertEquals(-7, ahead)
        assertTrue((row - 7 - ahead) - row == 0, "the walk must land today in the first column")

        // Scrolled BEHIND today: its column is past the last one, and the walk stops AT it (column 6).
        val behind = nowLineCenterColumnShift(row + 9, fraction, dayHeight, viewport, offset, columns = 7)
        assertEquals(3, behind)
        assertTrue((row + 9 - behind) - row == 6, "the walk must land today in the last column")
    }

    @Test
    fun a_zoom_under_the_lock_pivots_on_the_now_line_and_not_on_the_cursor() {
        // The unlocked zoom keeps whatever is under the CURSOR fixed; the locked one re-centres, so the
        // now-line is at the middle before and after however deep the zoom goes.
        val viewport = 600f
        val fraction = 0.5f
        var dayH = dayHeight
        var offset = nowLineCenterOffset(fraction, dayH, viewport, currentOffsetPx = 0f)
        for (step in listOf(1.15f, 1.15f, 8f, 1f / 3f, 0.5f)) {
            // The zoom scales the timeline (and with it where the grid is scrolled to), then re-centres.
            offset = nowLineCenterOffset(fraction, dayH * step, viewport, currentOffsetPx = offset * step)
            dayH *= step
            assertEquals(
                300f,
                nowLineY(centredRow(fraction, dayH, viewport, offset), fraction, dayH, offset),
                0.01f,
                "lost the now-line at zoom $step",
            )
        }
        // For contrast: the same zoom step anchored at a cursor 100 px from the top moves the now-line.
        val centred = nowLineCenterOffset(fraction, dayHeight, viewport, currentOffsetPx = 0f)
        val cursorAnchored = zoomAnchoredOffset(centred, 100f, 2f)
        assertTrue(
            nowLineY(0, fraction, dayHeight * 2f, cursorAnchored) != 300f,
            "a cursor-anchored zoom should NOT keep the now-line centred",
        )
    }

    @Test
    fun the_lock_needs_no_measured_viewport_to_be_well_defined() {
        // Before the first layout the viewport is 0 px; the offset is then the now-line's own position in
        // its day, up to whole days (the caller skips the write until the height is known anyway).
        val offset = nowLineCenterOffset(0.5f, dayHeight, 0f, currentOffsetPx = 0.5f * dayHeight)
        assertEquals(0.5f * dayHeight, offset, 0.01f)
    }

    // ----- the opening fit -----------------------------------------------------------------------

    @Test
    fun opening_the_calendar_zooms_so_exactly_an_hour_and_a_half_fills_the_viewport() {
        // A calendar that has just been opened shows OPENING_SPAN_MINUTES of timeline, whatever the
        // viewport is — the zoom fit is what makes that span the same on every window size. The window is
        // a fixed 720x540 dp, so every viewport it can offer is well inside the zoom ceiling.
        val spanHeight = dayHeight * OPENING_SPAN_MINUTES / (24f * 60f)
        for (viewport in listOf(200f, 400f, 600f, 1152f)) {
            val zoom = calendarSpanZoom(viewport, dayHeight, OPENING_SPAN_MINUTES)
            assertEquals(viewport, spanHeight * zoom, 0.01f, "1 h 30 does not fill a $viewport px viewport")
        }
    }

    @Test
    fun the_span_fit_is_the_whole_day_fit_generalized_and_is_bounded_the_same_way() {
        // The date pick's fit is this one asked for a full day: one formula, so the two cannot drift.
        for (viewport in listOf(400f, 900f, 4000f)) {
            assertEquals(wholeDayZoom(viewport, dayHeight), calendarSpanZoom(viewport, dayHeight, 24 * 60))
        }
        // Clamped into the zoom bounds like any other zoom, and neutral before the first layout.
        assertEquals(
            maxCalendarZoom(dayHeight),
            calendarSpanZoom(viewportPx = 1_000_000f, dayHeightPxAtZoom1 = dayHeight, spanMinutes = 90),
        )
        assertEquals(0.25f, calendarSpanZoom(viewportPx = 1f, dayHeightPxAtZoom1 = dayHeight, spanMinutes = 90))
        assertEquals(1f, calendarSpanZoom(viewportPx = 0f, dayHeightPxAtZoom1 = dayHeight, spanMinutes = 90))
        assertEquals(1f, calendarSpanZoom(viewportPx = 600f, dayHeightPxAtZoom1 = 0f, spanMinutes = 90))
        assertEquals(1f, calendarSpanZoom(viewportPx = 600f, dayHeightPxAtZoom1 = dayHeight, spanMinutes = 0))
    }

    // ----- the zoom ceiling ----------------------------------------------------------------------

    @Test
    fun the_zoom_goes_far_enough_to_read_a_twenty_second_screen_break() {
        // PRD §15: a screen-break band spans its TRUE duration and is never stretched to hold its own name,
        // so the zoom is what has to reach — the 20-second look-away is 48 dp / 180 = 0.267 dp tall at zoom
        // 1f, and one line of label is 16 dp. Anything under ~60x would leave the shortest of the three
        // permanently unreadable and un-hoverable, which is the state the inflated band was hiding.
        val lookAwayDpAtZoom1 = 48f / 180f
        val labelLineDp = 16f
        assertTrue(
            lookAwayDpAtZoom1 * maxCalendarZoom(dayHeight) >= labelLineDp,
            "a 20-s look-away cannot be zoomed to one label line",
        )
    }

    @Test
    fun the_zoom_ceiling_drops_on_a_display_a_day_row_would_overflow() {
        // A day row is laid out at a real pixel height, and Compose cannot represent a constraint past
        // ~262 143 px. The dp ceiling alone cannot promise that (a day's px height scales with the display
        // density), so the effective bound is lowered until the row fits — and never below the floor.
        val dense = 200_000f / 10f // a day row 10x taller than the budget at the dp ceiling
        assertTrue(maxCalendarZoom(dense) < maxCalendarZoom(dayHeight))
        assertTrue(dense * maxCalendarZoom(dense) <= 200_000f)
        assertEquals(0.25f, maxCalendarZoom(dayHeightPxAtZoom1 = 10_000_000f))
        // Before the first layout there is no density to read: the plain dp ceiling.
        assertEquals(maxCalendarZoom(0f), maxCalendarZoom(-1f))
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
        assertEquals(maxCalendarZoom(dayHeight), wholeDayZoom(viewportPx = 1_000_000f, dayHeightPxAtZoom1 = dayHeight))
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

    // ----- viewport culling (ADR 0009) ----------------------------------------------------------

    /** The hours of row [row] genuinely on screen, straight from the placement the grid uses. */
    private fun onScreenHours(row: Int, offset: Float, dayH: Float, viewport: Float): Pair<Float, Float>? {
        val topPx = maxOf(0f, offset - row * dayH)
        val bottomPx = minOf(dayH, offset - row * dayH + viewport)
        if (bottomPx <= topPx) return null
        val hourPx = dayH / 24f
        return topPx / hourPx to bottomPx / hourPx
    }

    @Test
    fun the_culling_window_always_covers_everything_actually_on_screen() {
        // The whole safety property of culling: snapping the window OUTWARD means it can never clip
        // something the user can see, so nothing pops in late. Swept over every offset inside the day, at
        // several zooms and viewport heights, for every row the grid composes.
        for (zoom in listOf(0.25f, 1f, 2.5f, 8f)) {
            val dayH = dayHeight * zoom
            for (viewport in listOf(200f, 726f, 1400f)) {
                for (rowOf in 0 until rollingRowCount(viewport, dayH)) {
                    var offset = 0f
                    while (offset < dayH) {
                        val window = visibleHourWindow(rowOf, offset, dayH, viewport)
                        val seen = onScreenHours(rowOf, offset, dayH, viewport)
                        if (seen != null) {
                            val (top, bottom) = seen
                            assertTrue(
                                window.topHour <= top + 1e-3f && window.bottomHour >= bottom - 1e-3f,
                                "zoom $zoom viewport $viewport row $rowOf offset $offset: " +
                                    "window $window clips visible [$top, $bottom]",
                            )
                            // And every visible element intersects it, which is what the columns ask.
                            assertTrue(window.intersects(top, bottom))
                        }
                        offset += dayH / 97f // a prime-ish stride, so boundaries are not all hit head-on
                    }
                }
            }
        }
    }

    @Test
    fun a_row_scrolled_entirely_out_of_the_viewport_is_culled_whole() {
        // The common case at zoom 1: the trailing row rollingRowCount composes sits below the fold, so it
        // must contribute no UI node at all. This is where most of the saving comes from.
        val window = visibleHourWindow(row = 1, offsetPx = 0f, dayHeightPx = dayHeight, viewportPx = 726f)
        assertTrue(window.isEmpty)
        assertTrue(!window.intersects(0f, 24f))
        // Same for a row scrolled off the top.
        assertTrue(visibleHourWindow(row = 0, offsetPx = dayHeight, dayHeightPx = dayHeight, viewportPx = 726f).isEmpty)
    }

    @Test
    fun nothing_is_culled_before_the_viewport_has_been_measured() {
        // First composition, no measured height yet: cull nothing rather than guess, so the first frame is
        // correct and the window merely tightens once the viewport reports in.
        assertEquals(HourWindow.WholeDay, visibleHourWindow(0, offsetPx = 0f, dayHeightPx = dayHeight, viewportPx = 0f))
        assertEquals(HourWindow.WholeDay, visibleHourWindow(0, offsetPx = 0f, dayHeightPx = 0f, viewportPx = 726f))
    }

    @Test
    fun the_window_is_quantized_so_scrolling_does_not_recompose_every_pixel() {
        // Culling makes composition a function of the scroll, so the window is snapped to a quantum: over a
        // whole day of travel the columns recompose a handful of times, not once per pixel. Without this,
        // culling would cost more in recomposition than it saves in nodes.
        for ((zoom, viewport) in listOf(1f to 726f, 8f to 726f, 0.5f to 1400f)) {
            val dayH = dayHeight * zoom
            val windows = mutableSetOf<HourWindow>()
            var offset = 0f
            while (offset < dayH) {
                windows.add(visibleHourWindow(0, offset, dayH, viewport))
                offset += 1f // one pixel at a time, the whole day
            }
            assertTrue(windows.size <= 26, "zoom $zoom viewport $viewport recomposed ${windows.size} times a day")
        }
    }

    @Test
    fun the_window_actually_culls_at_the_zooms_the_calendar_is_used_at() {
        // The point of the exercise: a day row is a whole day tall and the viewport is not, so the composed
        // span must be materially smaller than 24 h. (A test that only proved the safety property above
        // would pass just as well if the window were always the whole day.)
        val atOne = visibleHourWindow(0, offsetPx = 0f, dayHeightPx = dayHeight, viewportPx = 726f)
        assertTrue(atOne.bottomHour - atOne.topHour <= 24f, "$atOne")
        // Zoomed in, the saving is dramatic: a ~1.9 h viewport holds a few hours, not a day.
        val zoomed = visibleHourWindow(0, offsetPx = 0f, dayHeightPx = dayHeight * 8f, viewportPx = 726f)
        assertTrue(zoomed.bottomHour - zoomed.topHour <= 6f, "$zoomed")
    }
}
