package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.example.project.scheduler.engine.PROGRESSIVE_FIRST_STAGE_MILLIS
import org.example.project.scheduler.engine.PROGRESSIVE_MIN_ADVANCE_MILLIS
import org.example.project.scheduler.engine.PROGRESSIVE_PACE_MILLIS
import org.example.project.scheduler.engine.progressiveStageCapMillis

/**
 * `docs/scheduler_requirements.md` § *Progressive Calculation*: *"if the definitive schedule is found for any
 * t < t1, then 10 seconds later the definitive schedule must be found for any t < t1 + 10 minutes."*
 *
 * The pace binds EVERY stage. Doubling the stages held it only on average: a stage costs in proportion to its
 * length, so the stage after a long one arrived too late on any device that was not very fast. What is pinned here
 * is that no stage reaches further past the definitive front than the device fills inside the pace.
 */
class ProgressivePaceTest {

    private val HOUR = 3_600_000L
    private val NOW = 1_700_000_000_000L

    /** The wall time this device takes to fill [spanMillis] at [hoursPerSecond]. */
    private fun costMillis(spanMillis: Long, hoursPerSecond: Double): Double =
        spanMillis / HOUR.toDouble() / hoursPerSecond * 1_000.0

    @Test
    fun no_stage_takes_longer_than_the_pace_on_a_slow_device() {
        // 0.2 s per hour of plan: the old doubling reached a 64-hour stage, 12.8 s of work after the one before.
        val rate = 5.0
        var stage = PROGRESSIVE_FIRST_STAGE_MILLIS
        var reached = NOW
        var first = true
        val goal = NOW + 168 * HOUR
        while (reached < goal) {
            val cap = minOf(progressiveStageCapMillis(NOW, reached, stage, first, rate), goal)
            val span = cap - if (first) NOW else reached
            if (!first) {
                assertTrue(
                    costMillis(span, rate) < PROGRESSIVE_PACE_MILLIS,
                    "a ${span / HOUR} h stage costs ${costMillis(span, rate)} ms, past the 10 s pace",
                )
                assertTrue(span >= minOf(PROGRESSIVE_MIN_ADVANCE_MILLIS, goal - reached), "every stage moves the front")
            }
            reached = cap
            stage = 2 * (cap - NOW)
            first = false
        }
    }

    @Test
    fun a_fast_device_keeps_doubling() {
        // A device that fills 100 h a second is nowhere near the pace, so nothing is taken from the doubling.
        assertEquals(NOW + 8 * HOUR, progressiveStageCapMillis(NOW, NOW + 4 * HOUR, 8 * HOUR, first = false, planHoursPerSecond = 100.0))
    }

    @Test
    fun the_first_stage_and_an_unmeasured_device_are_left_alone() {
        assertEquals(NOW + HOUR, progressiveStageCapMillis(NOW, NOW, HOUR, first = true, planHoursPerSecond = 0.01))
        assertEquals(NOW + 64 * HOUR, progressiveStageCapMillis(NOW, NOW + 32 * HOUR, 64 * HOUR, first = false, planHoursPerSecond = 0.0))
    }

    @Test
    fun a_device_too_slow_for_the_pace_still_moves_the_front_by_the_requirements_step() {
        // Ten minutes is the requirement's own step: less would be a stage that cannot satisfy it by construction.
        val cap = progressiveStageCapMillis(NOW, NOW + HOUR, 2 * HOUR, first = false, planHoursPerSecond = 0.001)
        assertEquals(NOW + HOUR + PROGRESSIVE_MIN_ADVANCE_MILLIS, cap)
    }
}
