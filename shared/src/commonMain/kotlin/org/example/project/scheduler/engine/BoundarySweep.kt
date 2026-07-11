package org.example.project.scheduler.engine

import org.example.project.time.SystemAppClock

/**
 * Real-time lateness measurement for the engine's boundary-crossing sweeps (look-away cues, rest-pose
 * cue commits, wind-down), making their triggers **mathematically exact** instead of heartbeat-dependent:
 * a crossed boundary fires iff the moment it was *crossed* lies within the freshness budget of REAL time,
 * no matter how the sweep wake-ups happen to align with the calendar.
 *
 * Why not judge freshness by sim distance (`boundary ≥ simNow − budget·speed`, the previous scheme): under
 * acceleration that window is only `budget·speed / speed = budget` real ms **when the clock runs
 * continuously**, but a time-link re-anchor frame (or a manual leap) adds sim distance in ZERO real time —
 * one late-applied frame on a linked phone leapt sim-now clean over a 20-s break (67 ms real at 300×) and
 * the sweep then swallowed the crossing as "stale" though it was milliseconds old. The reported
 * "phone silent on the first break after clicking 300×" anomaly.
 *
 * Call [beginSweep] once at the top of every sweep, then [realLatenessMillis] for each crossed boundary
 * (sim instant ≤ the sweep's sim-now):
 *  • Clock ran **continuously** since the previous sweep (same reconfiguration generation — always true
 *    for the real production clock): the crossing's real age is exact arithmetic,
 *    `(simNow − boundary) / speed`.
 *  • Clock was **reconfigured** since the previous sweep (speed change, manual leap, a time-link re-anchor
 *    on a linked phone): sim distance says nothing about real age, so the age is bounded by the real gap
 *    since the previous sweep — a bound that is tight exactly here, because every reconfiguration bumps
 *    `SimAppClock.reconfigured`, which re-keys the engine's tick (and thus the sweeps) within one display
 *    frame.
 *
 * The first sweep of a loop has no previous sweep: every boundary already crossed by then predates the
 * engine (or the loop's re-key at startup) and is reported [Long.MAX_VALUE]-old, so an app launch never
 * replays cues for boundaries it slept through.
 */
internal class BoundarySweep(private val realNow: () -> Long = SystemAppClock::nowMillis) {
    private var hasPreviousSweep = false
    private var previousRealMillis = 0L
    private var previousGeneration = 0L
    private var previousSimNow = 0L

    private var sweepSimNow = 0L
    private var sweepSpeed = 1.0
    private var continuousSincePreviousSweep = false
    private var realGapMillis = Long.MAX_VALUE

    /**
     * Start a sweep at sim instant [simNow], with the clock's current [speed] and reconfiguration
     * [clockGeneration] (any constant for a non-sim clock).
     */
    fun beginSweep(simNow: Long, speed: Double, clockGeneration: Long) {
        val real = realNow()
        continuousSincePreviousSweep = hasPreviousSweep && clockGeneration == previousGeneration
        realGapMillis = if (hasPreviousSweep) real - previousRealMillis else Long.MAX_VALUE
        previousSimNow = if (hasPreviousSweep) sweepSimNow else simNow
        sweepSimNow = simNow
        sweepSpeed = speed
        previousRealMillis = real
        previousGeneration = clockGeneration
        hasPreviousSweep = true
    }

    /**
     * The sim floor of this sweep's scan window: consecutive sweeps' windows tile the timeline with **no
     * gaps** — everything crossed since the previous sweep is always inside the current scan, however far
     * a single clock jump moved sim-now (one re-anchor frame can exceed any fixed cap). [capMillis] bounds
     * the window only while sweeps run steadily (its normal bookkeeping role).
     */
    fun scanFloorMillis(capMillis: Long): Long = minOf(previousSimNow, sweepSimNow - capMillis)

    /**
     * How many REAL milliseconds ago the clock crossed [boundarySimMillis] (≤ the current sweep's sim-now):
     * exact under a continuous clock, else the (tight) real gap since the previous sweep.
     */
    fun realLatenessMillis(boundarySimMillis: Long): Long =
        if (continuousSincePreviousSweep && sweepSpeed > 0.0) {
            ((sweepSimNow - boundarySimMillis).toDouble() / sweepSpeed).toLong()
        } else {
            realGapMillis
        }
}
