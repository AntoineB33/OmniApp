package org.example.project.time

import kotlin.time.Clock

/**
 * Source of "now" (epoch millis) for the *running* app. The scheduler domain is already time-pure
 * (every function takes an explicit `nowMillis`); this only abstracts the handful of wall-clock
 * reads in the app shell so time can be accelerated for manual testing (see [SimAppClock]).
 */
interface AppClock {
    fun nowMillis(): Long
}

/** Real wall-clock — the production source of time. */
object SystemAppClock : AppClock {
    override fun nowMillis(): Long = Clock.System.now().toEpochMilliseconds()
}

/**
 * A virtual clock whose time runs at [speed]× real time, for the debug time-acceleration control.
 * Virtual-now is anchored to a real instant so that changing [speed] (or pausing with `0.0`) never
 * makes the clock jump: `virtualNow = anchorVirtual + (realNow − anchorReal)·speed`.
 *
 * Not thread-safe; it is mutated and read from the Compose main thread only.
 */
class SimAppClock(
    speed: Double = 1.0,
    private val realNowMillis: () -> Long = { SystemAppClock.nowMillis() },
) : AppClock {
    var speed: Double = speed
        private set

    private var anchorReal: Long = realNowMillis()
    private var anchorVirtual: Long = anchorReal

    override fun nowMillis(): Long =
        anchorVirtual + ((realNowMillis() - anchorReal).toDouble() * speed).toLong()

    /** Re-anchor at the current virtual instant, then continue at [newSpeed]× (0 pauses time). */
    fun setSpeed(newSpeed: Double) {
        anchorVirtual = nowMillis()
        anchorReal = realNowMillis()
        speed = newSpeed
    }

    /** Snap back to real wall-clock time at 1× speed. */
    fun reset() {
        anchorVirtual = realNowMillis()
        anchorReal = anchorVirtual
        speed = 1.0
    }
}
