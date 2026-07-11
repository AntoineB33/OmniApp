package org.example.project

import java.io.IOException
import java.net.Socket
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.example.project.scheduler.debug.TIME_LINK_PORT
import org.example.project.scheduler.platform.Diagnostics
import org.example.project.time.SimAppClock

// Diagnostics: a frame that re-anchors the driven clock by at least this much sim time is a discontinuity
// worth a timeline line — at 300× a 20-s break is only ~67 ms of real time, so one late-applied frame can
// leap the engine clean over it (the cue sweeps then swallow the break as stale). Steady-state frame jitter
// stays well below this; rate-limited so a busy main thread can't flood the log.
private const val JUMP_LOG_THRESHOLD_MILLIS = 10_000L
private const val JUMP_LOG_MIN_INTERVAL_MILLIS = 2_000L

/**
 * Phone half of the debug time-link (see `scheduler/debug/TimeLink` + docs/PAUSE_CUE_DELIVERY.md "Testing C").
 * Dials the desktop's server at `127.0.0.1:`[TIME_LINK_PORT] — reachable because the desktop keeps
 * `adb reverse tcp:PORT tcp:PORT` set up — and re-anchors the app's driven [SimAppClock] from each
 * `"<virtualNow> <speed> <inactive01>"` frame, so the phone runs on the desktop's accelerated clock. The
 * trailing token (absent on an older desktop ⇒ `false`) is the desktop's "simulate pause + leap" scope: while
 * `1`, this phone must treat its screen as inactive ([onForcedInactive] feeds
 * `SchedulerEngine.setDebugForcedInactive`), so a leap that includes the phone makes it live the pause too.
 * Reconnects on drop.
 *
 * The flag's `1→0` transition is the leap ending, and counts as an explicit sync moment on this phone too:
 * [onLeapEnd] runs (pushing the session the leap-start finalize wrote — otherwise this phone's stale OPEN
 * row on the server is presumed active through the now-line and hides the just-simulated pause from the
 * desktop's post-leap derive), then one `"pushed"` ack line goes back over the socket; the desktop's leap
 * job waits for the acks before deriving (`TimeLink.awaitPhoneLeapAcks`). Running it inline stalls frame
 * adoption for the push's duration, which is fine — the leap's acceleration is already over.
 *
 * Started only from a **debuggable** build ([SchedulerHolder]); a no-op transport when no desktop is attached
 * (the connect just keeps failing, the clock stays at real time). Reads on IO, applies on the Main thread
 * because [SimAppClock] is main-thread-only.
 */
object TimeLinkClient {
    fun start(
        scope: CoroutineScope,
        clock: SimAppClock,
        onForcedInactive: (Boolean) -> Unit = {},
        onLeapEnd: suspend () -> Unit = {},
    ) {
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                var wasConnected = false
                try {
                    Socket("127.0.0.1", TIME_LINK_PORT).use { socket ->
                        wasConnected = true
                        Diagnostics.log("time-link connected — desktop clock drives this device")
                        val reader = socket.getInputStream().bufferedReader()
                        val writer = socket.getOutputStream().bufferedWriter()
                        var lastInactive = false
                        var lastSpeed: Double? = null
                        var lastJumpLogRealMillis = 0L
                        while (isActive) {
                            val line = reader.readLine() ?: break
                            val parts = line.trim().split(" ")
                            val virtualNow = parts.getOrNull(0)?.toLongOrNull()
                            val speed = parts.getOrNull(1)?.toDoubleOrNull()
                            val forcedInactive = parts.getOrNull(2) == "1"
                            if (virtualNow != null && speed != null) {
                                val jumpMillis = withContext(Dispatchers.Main) {
                                    // Adopt the clock FIRST so the inactivity flip (which finalizes/reopens the
                                    // active session at `clock.nowMillis()`) reads the post-frame virtual time.
                                    val before = clock.nowMillis()
                                    clock.adopt(virtualNow, speed)
                                    onForcedInactive(forcedInactive)
                                    virtualNow - before
                                }
                                if (speed != lastSpeed) {
                                    Diagnostics.log(
                                        "time-link speed ${lastSpeed ?: "?"}x → ${speed}x " +
                                            "(frame re-anchored clock by $jumpMillis ms sim)",
                                    )
                                    lastSpeed = speed
                                } else if (abs(jumpMillis) >= JUMP_LOG_THRESHOLD_MILLIS &&
                                    System.currentTimeMillis() - lastJumpLogRealMillis >= JUMP_LOG_MIN_INTERVAL_MILLIS
                                ) {
                                    lastJumpLogRealMillis = System.currentTimeMillis()
                                    Diagnostics.log(
                                        "time-link frame re-anchored clock by $jumpMillis ms sim " +
                                            "(speed ${speed}x — a jump this size can leap the engine over a short break)",
                                    )
                                }
                                if (forcedInactive != lastInactive) {
                                    Diagnostics.log("time-link forced-inactive → $forcedInactive")
                                }
                                if (lastInactive && !forcedInactive) {
                                    // Leap end: push this phone's finalized sessions, then ack the desktop.
                                    runCatching { onLeapEnd() }
                                    Diagnostics.log("time-link leap end: finalized sessions pushed; acking desktop")
                                    runCatching {
                                        writer.write("pushed\n")
                                        writer.flush()
                                    }
                                }
                                lastInactive = forcedInactive
                            }
                        }
                    }
                } catch (_: IOException) {
                    // No desktop attached / link dropped — retry below.
                } finally {
                    if (wasConnected) {
                        Diagnostics.log("time-link disconnected — clock continues at last adopted speed")
                    }
                    // Link gone (drop/unplug): never leave the phone stuck "inactive" mid-leap. NonCancellable
                    // so the reset still runs if the finally is reached through the scope's own cancellation.
                    withContext(NonCancellable + Dispatchers.Main) { onForcedInactive(false) }
                }
                delay(1_000)
            }
        }
    }
}
