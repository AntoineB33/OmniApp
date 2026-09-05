package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import org.example.project.scheduler.domain.SchedulerDomain

/**
 * `docs/scheduler_requirements.md`: the scheduler returns a **set of rules**, and everything the calendar
 * draws is read out of it — so the display is a PIECEWISE function of the now-line and there is nothing to
 * poll.
 *
 * Nothing in the past is a function of the line (the past is frozen; only an event — a "look away now", a
 * hand edit — changes it), and nothing in the future is either until the line crosses a boundary the rules
 * themselves named. The one thing that does follow the line follows it AFFINELY: a pose the line drags in
 * mode 1 is the half-open `(t_p, t_p + d]`, the panel growing behind it ends at the line, a live band ends
 * at it. So the display's clock is *"the next boundary, or the moment something pinned to the line would
 * have moved by one pixel"* — and the app sleeps through everything in between.
 *
 * What this pins is that rule ([SchedulerDomain.displayResampleDelayMillis]). It replaced a flat 250 ms poll,
 * which itself replaced a resample on every display frame.
 */
class DisplayResampleBoundaryTest {
    private val tz = TimeZone.UTC
    private val MIN = 60_000L
    private val HOUR = 3_600_000L

    private fun at(h: Int, m: Int, s: Int = 0): Long =
        LocalDateTime(2026, 9, 5, h, m, s).toInstant(tz).toEpochMilliseconds()

    /** The default zoom: a whole minute per pixel. Nothing sub-minute is visible there. */
    private val coarsePixel = 60_000L

    @Test
    fun with_nothing_pinned_the_app_sleeps_to_the_next_boundary() {
        val now = at(10, 0)
        // A panel running to 10:45 and a break due at 10:20: the picture cannot change before 10:20, however
        // far the line moves inside that. The zoom says nothing here — nothing is pinned to the line.
        val bounds = listOf(at(9, 30), at(10, 45), at(10, 20), at(12, 0))
        assertEquals(
            20 * MIN,
            SchedulerDomain.displayResampleDelayMillis(bounds, now, tz, millisPerPixel = 1L),
        )
    }

    @Test
    fun a_pose_the_line_is_dragging_is_re_derived_once_it_would_have_moved_a_pixel() {
        val now = at(10, 0)
        // `side-dev/README.md` mode 1: the owed pose is pushed onto the line as `(t_p, t_p + d]`, so its
        // start is literally `t_p + 1`. Answering "one millisecond" there is a busy loop; answering "the
        // next real boundary, 15 minutes away" would draw the band 15 minutes behind the line.
        val dragged = listOf(now + 1, now + 5 * MIN, at(10, 15))
        assertEquals(
            600L,
            SchedulerDomain.displayResampleDelayMillis(dragged, now, tz, millisPerPixel = 600L),
            "a pinned band is re-derived at the display's own resolution, not at its bound",
        )
        // Zoomed out, one pixel is a whole minute of timeline — so the same dragged pose costs one resample
        // a minute, not four a second. This is the case the report was about ("recalculating 4 times a
        // second"): the answer is that the picture cannot show the difference.
        assertEquals(
            coarsePixel,
            SchedulerDomain.displayResampleDelayMillis(dragged, now, tz, millisPerPixel = coarsePixel),
        )
    }

    @Test
    fun a_boundary_that_lands_before_the_next_pixel_still_wins() {
        val now = at(10, 0)
        // Pinned AND a boundary two seconds out: the boundary is a real change, so it is not slept through
        // just because the pin would not have moved yet.
        val bounds = listOf(now + 1, now + 2_000)
        assertEquals(
            2_000L,
            SchedulerDomain.displayResampleDelayMillis(bounds, now, tz, millisPerPixel = coarsePixel),
        )
    }

    @Test
    fun the_past_is_never_a_reason_to_wake_up() {
        val now = at(10, 0)
        // Every bound behind the line: a conducted break, a banked record, a period the user drew. The past
        // is frozen — only an event touches it — so the next thing to happen is the day rollover.
        val past = listOf(at(2, 0), at(3, 30), at(9, 59, 30))
        assertEquals(
            14 * HOUR,
            SchedulerDomain.displayResampleDelayMillis(past, now, tz, millisPerPixel = 1L),
            "nothing ahead of the line and nothing on it: sleep to midnight",
        )
    }

    @Test
    fun midnight_is_a_boundary_no_panel_carries() {
        // The day rollover (and the $t_goal$ staircase that steps with it) is the one boundary the derived
        // model does not name, so it is added by hand — an empty calendar must still roll over on time.
        val now = at(23, 59, 30)
        assertEquals(
            30_000L,
            SchedulerDomain.displayResampleDelayMillis(emptyList(), now, tz, millisPerPixel = 1L),
        )
    }

    @Test
    fun the_answer_is_never_zero_or_negative() {
        val now = at(10, 0)
        // A bound exactly ON the line is a pin, not a boundary — and a pin with a degenerate resolution must
        // still hand back something a `delay` can take.
        assertTrue(SchedulerDomain.displayResampleDelayMillis(listOf(now), now, tz, 0L) >= 1L)
        assertTrue(SchedulerDomain.displayResampleDelayMillis(listOf(now - 1), now, tz, -5L) >= 1L)
    }
}
