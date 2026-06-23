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
}
