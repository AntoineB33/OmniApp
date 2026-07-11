package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.example.project.scheduler.engine.BoundarySweep

/**
 * The engine's notification/cue triggers must be **mathematically accurate** — a function of which boundary
 * instants the clock crossed, never of how a sweep heartbeat happens to align with the calendar. This pins
 * the core measurement: [BoundarySweep.realLatenessMillis] answers "how many REAL ms ago was this boundary
 * crossed" exactly under a continuous clock and tightly-bounded across a clock reconfiguration, so the 2-s
 * real freshness budget passes every live crossing (however accelerated or jumped-over) and rejects only
 * crossings the process genuinely slept through.
 */
class BoundarySweepTest {
    private var real = 0L
    private val sweep = BoundarySweep(realNow = { real })

    @Test
    fun continuous_clock_at_1x_lateness_is_exact_sim_distance() {
        sweep.beginSweep(simNow = 1_000L, speed = 1.0, clockGeneration = 0L)
        real = 30_000L
        sweep.beginSweep(simNow = 31_000L, speed = 1.0, clockGeneration = 0L)
        // Boundary crossed 500 sim-ms (= 500 real ms at 1×) before this sweep woke.
        assertEquals(500L, sweep.realLatenessMillis(30_500L))
    }

    @Test
    fun continuous_accelerated_clock_divides_sim_distance_by_speed() {
        sweep.beginSweep(simNow = 0L, speed = 300.0, clockGeneration = 7L)
        real = 1_000L
        sweep.beginSweep(simNow = 300_000L, speed = 300.0, clockGeneration = 7L)
        // A whole 20-s break lies 20 sim-seconds back — at 300× that is only 66 real ms: FRESH, must fire.
        // (The old sim-distance gate capped the window at duration−1 and judged real wakes against it.)
        assertEquals(66L, sweep.realLatenessMillis(280_000L))
    }

    @Test
    fun reanchor_jump_is_judged_by_real_gap_not_sim_distance() {
        // The reported anomaly: a time-link frame re-anchors the phone's clock 75 sim-seconds forward
        // (250 ms of desktop time at 300×), leaping clean over a 20-s break. The generation bump marks the
        // discontinuity; lateness falls back to the REAL gap between sweeps — 250 ms, comfortably fresh —
        // so the jumped-over break still fires instead of being swallowed as "75 s stale".
        sweep.beginSweep(simNow = 0L, speed = 300.0, clockGeneration = 1L)
        real = 250L
        sweep.beginSweep(simNow = 75_000L, speed = 300.0, clockGeneration = 2L)
        assertEquals(250L, sweep.realLatenessMillis(40_000L))
    }

    @Test
    fun first_sweep_reports_already_crossed_boundaries_as_stale() {
        // Engine start: boundaries that predate the process must never replay.
        sweep.beginSweep(simNow = 1_000_000L, speed = 1.0, clockGeneration = 0L)
        assertTrue(sweep.realLatenessMillis(999_999L) > 60L * 60 * 1_000)
    }

    @Test
    fun real_suspension_reports_stale_under_a_continuous_clock() {
        // Device slept 8 real hours at 1× (production clock: generation never changes). A boundary crossed
        // mid-sleep is hours old in real time and must be swallowed.
        sweep.beginSweep(simNow = 0L, speed = 1.0, clockGeneration = 0L)
        val eightHours = 8L * 60 * 60 * 1_000
        real = eightHours
        sweep.beginSweep(simNow = eightHours, speed = 1.0, clockGeneration = 0L)
        assertEquals(4L * 60 * 60 * 1_000, sweep.realLatenessMillis(eightHours / 2))
    }

    @Test
    fun boundary_crossed_at_the_wake_instant_is_maximally_fresh() {
        sweep.beginSweep(simNow = 0L, speed = 1.0, clockGeneration = 0L)
        real = 20_000L
        sweep.beginSweep(simNow = 20_000L, speed = 1.0, clockGeneration = 0L)
        assertEquals(0L, sweep.realLatenessMillis(20_000L))
    }

    @Test
    fun scan_floor_tiles_the_timeline_across_a_jump_larger_than_the_cap() {
        val cap = 10L * 60 * 1_000
        sweep.beginSweep(simNow = 0L, speed = 300.0, clockGeneration = 1L)
        real = 2_000L
        // One re-anchor moved sim-now 100 minutes forward — far past the cap. The scan floor must reach
        // back to the previous sweep's position so no crossing inside the jump is clipped out of the scan.
        val jumped = 100L * 60 * 1_000
        sweep.beginSweep(simNow = jumped, speed = 300.0, clockGeneration = 2L)
        assertEquals(0L, sweep.scanFloorMillis(cap))
    }

    @Test
    fun scan_floor_is_the_cap_while_sweeps_run_steadily() {
        val cap = 10L * 60 * 1_000
        sweep.beginSweep(simNow = 1_000_000L, speed = 1.0, clockGeneration = 0L)
        real = 15_000L
        sweep.beginSweep(simNow = 1_015_000L, speed = 1.0, clockGeneration = 0L)
        assertEquals(1_015_000L - cap, sweep.scanFloorMillis(cap))
    }
}
