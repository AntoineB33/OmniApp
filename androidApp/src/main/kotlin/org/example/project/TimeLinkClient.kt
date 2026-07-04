package org.example.project

import java.io.IOException
import java.net.Socket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.example.project.scheduler.debug.TIME_LINK_PORT
import org.example.project.time.SimAppClock

/**
 * Phone half of the debug time-link (see `scheduler/debug/TimeLink` + docs/PAUSE_CUE_DELIVERY.md "Testing C").
 * Dials the desktop's server at `127.0.0.1:`[TIME_LINK_PORT] — reachable because the desktop keeps
 * `adb reverse tcp:PORT tcp:PORT` set up — and re-anchors the app's driven [SimAppClock] from each
 * `"<virtualNow> <speed>"` frame, so the phone runs on the desktop's accelerated clock. Reconnects on drop.
 *
 * Started only from a **debuggable** build ([SchedulerHolder]); a no-op transport when no desktop is attached
 * (the connect just keeps failing, the clock stays at real time). Reads on IO, applies on the Main thread
 * because [SimAppClock] is main-thread-only.
 */
object TimeLinkClient {
    fun start(scope: CoroutineScope, clock: SimAppClock) {
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    Socket("127.0.0.1", TIME_LINK_PORT).use { socket ->
                        val reader = socket.getInputStream().bufferedReader()
                        while (isActive) {
                            val line = reader.readLine() ?: break
                            val parts = line.trim().split(" ")
                            val virtualNow = parts.getOrNull(0)?.toLongOrNull()
                            val speed = parts.getOrNull(1)?.toDoubleOrNull()
                            if (virtualNow != null && speed != null) {
                                withContext(Dispatchers.Main) { clock.adopt(virtualNow, speed) }
                            }
                        }
                    }
                } catch (_: IOException) {
                    // No desktop attached / link dropped — retry below.
                }
                delay(1_000)
            }
        }
    }
}
