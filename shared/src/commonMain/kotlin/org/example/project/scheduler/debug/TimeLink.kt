package org.example.project.scheduler.debug

import kotlinx.coroutines.flow.StateFlow
import org.example.project.time.SimAppClock

/**
 * Debug-only desktop→phone **time-sync link** (see docs/PAUSE_CUE_DELIVERY.md "Testing C"). The desktop's
 * accelerated [SimAppClock] is streamed to a plugged-in Android debug app (USB or emulator — both are `adb`
 * targets) so both devices share one accelerated `now`, making time-sensitive cross-device flows (the pause-end
 * voice cue) testable in seconds instead of hours. Transport is a loopback TCP socket bridged by
 * `adb reverse tcp:PORT tcp:PORT`; the phone re-anchors its own clock from each frame ([SimAppClock.adopt]).
 *
 * This is the DESKTOP (server) half. The phone (client) half lives in the Android app (`TimeLinkClient`), which
 * dials `127.0.0.1:`[TIME_LINK_PORT]. There is deliberately no server on non-desktop platforms.
 */
interface TimeLink {
    /** How many phone debug clients are currently connected (0 = link down; drives the panel's status). */
    val linkedCount: StateFlow<Int>

    /**
     * Debug "simulate pause + leap" scope: while `true`, every frame tells the connected phone(s) to treat
     * their screen as INACTIVE (`SchedulerEngine.setDebugForcedInactive`), so a leap whose scope includes the
     * phone makes it live the pause too. The flag rides the same frame as the speed, so the phone adopts the
     * acceleration and the inactivity atomically.
     */
    fun setPhoneForcedInactive(inactive: Boolean)

    /**
     * Debug "simulate pause + leap" ordering: suspends until every phone that lived the just-ended leap has
     * acked its leap-end session push (the phone's `TimeLinkClient` pushes its finalized sessions, then writes
     * one ack line back over the link), or [timeoutMillis] elapses. The desktop's own post-leap derive must
     * run AFTER those pushes: the server presumes a fresh OPEN session active through the now-line, so
     * deriving while the phone's stale open row is still on the server reads the whole leap window as
     * activity and hides the just-simulated pause (the "now-line drags the scheduled break" anomaly).
     * Returns immediately when no phone was in the leap's scope. Default no-op — only the desktop host
     * implements it.
     */
    suspend fun awaitPhoneLeapAcks(timeoutMillis: Long) {}

    /** Stop the server + adb reconcile (called when the composition holding it is torn down). */
    fun close()
}

/** Port the desktop server binds and the phone dials via `adb reverse tcp:PORT tcp:PORT`. */
const val TIME_LINK_PORT: Int = 47615

/**
 * Start the desktop time-broadcast server driven by [clock]. Returns a handle whose [TimeLink.linkedCount]
 * tracks connected phones, or `null` on platforms that do not host the server (Android/iOS/web) — the panel
 * then treats the link as unavailable. Only ever called on the desktop under `DebugFlags.TIME_SIMULATION`.
 */
expect fun startTimeLink(clock: SimAppClock): TimeLink?
