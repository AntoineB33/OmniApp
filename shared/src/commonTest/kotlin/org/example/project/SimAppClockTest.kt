package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import org.example.project.time.SimAppClock

/**
 * Unit tests for [SimAppClock], the debug time-acceleration clock. The class underpins the time-sim
 * debug tooling (acceleration, pause and the "simulate pause + leap" control), so its anchor math is
 * verified directly with a controllable real-time source.
 */
class SimAppClockTest {
    @Test
    fun at_one_x_virtual_time_tracks_real_time_exactly() {
        var real = 1_000L
        val clock = SimAppClock(realNowMillis = { real })
        assertEquals(1_000L, clock.nowMillis())
        real = 5_000L
        assertEquals(5_000L, clock.nowMillis())
    }

    @Test
    fun set_speed_accelerates_from_the_current_instant_without_jumping() {
        var real = 1_000L
        val clock = SimAppClock(realNowMillis = { real })
        real = 2_000L

        // Changing speed re-anchors at the current virtual instant, so the readout never jumps.
        clock.setSpeed(10.0)
        assertEquals(10.0, clock.speed)
        assertEquals(2_000L, clock.nowMillis())

        // From there virtual time runs at 10× real time.
        real = 2_100L
        assertEquals(2_000L + (2_100L - 2_000L) * 10, clock.nowMillis()) // 3_000
    }

    @Test
    fun pausing_with_zero_speed_freezes_virtual_time() {
        var real = 1_000L
        val clock = SimAppClock(realNowMillis = { real })
        real = 3_000L

        clock.setSpeed(0.0)
        real = 9_999L
        assertEquals(3_000L, clock.nowMillis())
    }

    @Test
    fun leap_jumps_forward_by_the_delta_and_keeps_the_speed() {
        var real = 1_000L
        val clock = SimAppClock(speed = 10.0, realNowMillis = { real })
        real = 1_100L // virtual = 1_000 + (1_100 - 1_000)·10 = 2_000

        clock.leap(5_000L)
        assertEquals(7_000L, clock.nowMillis()) // 2_000 + 5_000, immediately after the leap
        assertEquals(10.0, clock.speed) // speed preserved across the leap

        real = 1_200L
        assertEquals(7_000L + (1_200L - 1_100L) * 10, clock.nowMillis()) // 8_000
    }

    @Test
    fun reset_snaps_back_to_real_time_at_one_x() {
        var real = 1_000L
        val clock = SimAppClock(speed = 10.0, realNowMillis = { real })
        real = 2_000L
        clock.leap(100_000L) // far ahead of real time

        clock.reset()
        assertEquals(1.0, clock.speed)
        assertEquals(2_000L, clock.nowMillis()) // back on the real clock

        real = 5_000L
        assertEquals(5_000L, clock.nowMillis()) // and tracking it 1:1 again
    }

    // The `reconfigured` signal is what lets the engine's poll loops wake out of a coarse production sleep the
    // instant acceleration turns on (the x300 desktop→phone now-line lag). It must bump on a real change and
    // stay quiet on the steady per-frame tracking of an un-accelerated time-link.
    @Test
    fun set_speed_bumps_reconfigured_only_when_the_speed_actually_changes() {
        var real = 1_000L
        val clock = SimAppClock(realNowMillis = { real })
        val before = clock.reconfigured.value

        clock.setSpeed(300.0) // 1× -> 300×: a genuine change
        assertEquals(before + 1, clock.reconfigured.value)

        clock.setSpeed(300.0) // same speed again: no bump
        assertEquals(before + 1, clock.reconfigured.value)
    }

    @Test
    fun leap_and_reset_bump_reconfigured() {
        var real = 1_000L
        val clock = SimAppClock(speed = 10.0, realNowMillis = { real })
        val afterLeap = clock.reconfigured.value + 1
        clock.leap(5_000L)
        assertEquals(afterLeap, clock.reconfigured.value)

        clock.reset()
        assertEquals(afterLeap + 1, clock.reconfigured.value)
    }

    @Test
    fun adopt_bumps_on_a_speed_change_or_a_leap_but_not_on_steady_tracking() {
        var real = 1_000L
        val clock = SimAppClock(realNowMillis = { real })
        val start = clock.reconfigured.value

        // First frame from the desktop: 1× at the same instant — steady tracking, no bump.
        clock.adopt(1_000L, 1.0)
        assertEquals(start, clock.reconfigured.value)

        // Desktop clicks x300 -> next frame carries the new speed: a change, so it bumps.
        real = 1_250L
        clock.adopt(1_075_000L, 300.0) // ~250 ms real * 300 of accelerated virtual time
        assertEquals(start + 1, clock.reconfigured.value)

        // Steady 300× tracking: the phone extrapolates at 300× between frames, so the next frame matches
        // within a frame's drift — no further bump.
        real = 1_500L
        clock.adopt(1_150_000L, 300.0)
        assertEquals(start + 1, clock.reconfigured.value)

        // A leap propagated over the link (virtual time jumps far past the extrapolation) bumps.
        real = 1_750L
        clock.adopt(9_000_000L, 300.0)
        assertEquals(start + 2, clock.reconfigured.value)
    }
}
