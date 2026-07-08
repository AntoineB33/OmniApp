package org.example.project.time

import kotlin.math.abs
import kotlin.time.Clock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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

    private val _reconfigured = MutableStateFlow(0L)

    /**
     * Bumps whenever the clock's speed changes or its virtual instant jumps discontinuously — a manual
     * [leap]/[reset], or a time-link [adopt] frame that pushed a new speed or leaped time. Long-running poll
     * loops (the engine's advance tick) observe this to wake out of a coarse **production** sleep the instant
     * acceleration turns on: otherwise a phone driven up to 300× via the desktop time-link keeps sleeping a
     * full production tick (~30 s) before its now-line starts gliding (the reported "wait some time before
     * Android updates" anomaly). A steady same-speed [adopt] frame does NOT bump it, so an un-accelerated link
     * stays on the coarse cadence.
     */
    val reconfigured: StateFlow<Long> = _reconfigured.asStateFlow()

    override fun nowMillis(): Long =
        anchorVirtual + ((realNowMillis() - anchorReal).toDouble() * speed).toLong()

    /** Re-anchor at the current virtual instant, then continue at [newSpeed]× (0 pauses time). */
    fun setSpeed(newSpeed: Double) {
        anchorVirtual = nowMillis()
        anchorReal = realNowMillis()
        val changed = newSpeed != speed
        speed = newSpeed
        if (changed) _reconfigured.value += 1
    }

    /** Debug: jump virtual time forward by [deltaMillis] (a "time leap"), keeping the current speed. */
    fun leap(deltaMillis: Long) {
        anchorVirtual = nowMillis() + deltaMillis
        anchorReal = realNowMillis()
        _reconfigured.value += 1
    }

    /**
     * Debug time-link (desktop→phone): adopt a virtual instant + speed pushed from another device, re-anchored
     * to THIS device's real clock so it keeps advancing at [newSpeed] between frames and each frame cancels
     * drift. See `scheduler/debug/TimeLink` and docs/PAUSE_CUE_DELIVERY.md (Testing C). Cross-device wall-clock
     * skew is folded in on adoption (the phone deliberately takes the desktop's time as authoritative).
     */
    fun adopt(virtualNowMillis: Long, newSpeed: Double) {
        // Whether this frame is a genuine discontinuity (speed change or a leap) rather than the steady
        // per-frame tracking of an un-accelerated link. Compared against what THIS clock would have said
        // without the frame — under steady same-speed tracking the two agree within a frame's drift, so an
        // idle 1× link never bumps [reconfigured] (and never drags the poll loops off the coarse cadence).
        val discontinuity = abs(virtualNowMillis - nowMillis()) > ADOPT_DISCONTINUITY_MILLIS
        anchorVirtual = virtualNowMillis
        anchorReal = realNowMillis()
        val changed = newSpeed != speed
        speed = newSpeed
        if (changed || discontinuity) _reconfigured.value += 1
    }

    /** Snap back to real wall-clock time at 1× speed. */
    fun reset() {
        anchorVirtual = realNowMillis()
        anchorReal = anchorVirtual
        speed = 1.0
        _reconfigured.value += 1
    }

    private companion object {
        // A per-frame drift below this (a time-link frame arrives every ~250 ms; the phone extrapolates at
        // the same adopted speed between frames) is treated as steady tracking, not a leap. Comfortably above
        // frame jitter yet well below any meaningful "simulate pause" leap (20 s+).
        const val ADOPT_DISCONTINUITY_MILLIS = 3_000L
    }
}
