package org.example.project.scheduler.debug

import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import org.example.project.scheduler.platform.Diagnostics
import org.example.project.time.SimAppClock

/**
 * Desktop half of the debug time-link (see [TimeLink]). Binds a loopback TCP server on [TIME_LINK_PORT],
 * streams `"<virtualNow> <speed> <inactive01>\n"` frames to each connected phone every [FRAME_INTERVAL_MILLIS]
 * (the trailing token is the "simulate pause + leap" forced-inactivity flag, `1`/`0`), and keeps
 * `adb reverse tcp:PORT tcp:PORT` set up so a plugged-in device's `localhost:PORT` reaches this server. Frames
 * are polled from [clock], so a `leap`/speed change is reflected within one interval and the phone re-anchors
 * on every frame. Failures are swallowed — it is a best-effort debug aid, never allowed to crash the app.
 */
actual fun startTimeLink(clock: SimAppClock): TimeLink? =
    runCatching { DesktopTimeLink(clock) }.getOrNull()

private const val FRAME_INTERVAL_MILLIS = 250L
private const val ADB_RECONCILE_INTERVAL_MILLIS = 3_000L

private class DesktopTimeLink(private val clock: SimAppClock) : TimeLink {
    private val _linkedCount = MutableStateFlow(0)
    override val linkedCount: StateFlow<Int> = _linkedCount.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val server = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress("127.0.0.1", TIME_LINK_PORT)) }

    // "simulate pause + leap" scope flag, polled by every per-client writer (set on the main thread, read on IO).
    @Volatile
    private var phoneForcedInactive = false

    // Leap-end acks: each phone writes one "pushed" line back over the link once its leap-end session push
    // completed (see TimeLinkClient); [awaitPhoneLeapAcks] waits for the count captured at the flag's
    // true→false flip, so the desktop's post-leap derive runs only after the phones' finalized sessions
    // reached the server. A monotonic counter (not a reset-per-leap latch) so an ack that lands between the
    // flip and the await is never lost.
    private val ackCount = MutableStateFlow(0)
    @Volatile
    private var ackTarget = 0

    override fun setPhoneForcedInactive(inactive: Boolean) {
        if (phoneForcedInactive != inactive) {
            Diagnostics.log("time-link phone forced-inactive → $inactive (${linkedCount.value} phone(s) linked)")
        }
        if (phoneForcedInactive && !inactive) {
            // Leap just ended: expect one ack per phone currently on the link (they all carried the flag).
            ackTarget = ackCount.value + linkedCount.value
        }
        phoneForcedInactive = inactive
    }

    override suspend fun awaitPhoneLeapAcks(timeoutMillis: Long) {
        val target = ackTarget
        val acked = withTimeoutOrNull(timeoutMillis) { ackCount.first { it >= target } } != null
        if (!acked) {
            Diagnostics.log(
                "time-link leap-end acks TIMED OUT after $timeoutMillis ms (got ${ackCount.value}, wanted $target) " +
                    "— post-leap derive may read a phone's stale open session",
            )
        }
        // A phone that never acked (dropped mid-push) must not make the NEXT leap's await inherit its ghost.
        ackTarget = ackCount.value
    }

    init {
        scope.launch { acceptLoop() }
        scope.launch { adbReverseReconcile() }
    }

    private suspend fun acceptLoop() {
        while (scope.isActive) {
            val socket = runCatching { server.accept() }.getOrNull() ?: break
            scope.launch { serveClient(socket) }
        }
    }

    // One writer per connected phone: stream the live virtual instant + speed until the socket drops.
    private suspend fun serveClient(socket: Socket) {
        _linkedCount.update { it + 1 }
        Diagnostics.log("time-link phone connected (${_linkedCount.value} linked)")
        // Read side of the otherwise one-way stream: the phone writes one "pushed" line after its leap-end
        // session push. Blocking readLine on the IO scope; the socket close in the finally unblocks it.
        val ackReader = scope.launch {
            runCatching {
                val reader = socket.getInputStream().bufferedReader()
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.trim() == "pushed") ackCount.update { it + 1 }
                }
            }
        }
        try {
            val out = socket.getOutputStream().bufferedWriter()
            while (scope.isActive && !socket.isClosed) {
                out.write("${clock.nowMillis()} ${clock.speed} ${if (phoneForcedInactive) 1 else 0}\n")
                out.flush() // throws once the phone closes/unplugs → drops out of the loop
                delay(FRAME_INTERVAL_MILLIS)
            }
        } catch (_: IOException) {
            // Phone disconnected; fall through to cleanup.
        } finally {
            _linkedCount.update { (it - 1).coerceAtLeast(0) }
            Diagnostics.log("time-link phone disconnected (${_linkedCount.value} linked)")
            runCatching { socket.close() }
            ackReader.cancel()
        }
    }

    // adb reverse is per-device and lost on reconnect, so re-apply it periodically (idempotent; a no-op error
    // when no device is attached). The phone app connecting is what actually flips linkedCount.
    private suspend fun adbReverseReconcile() {
        val adb = resolveAdb() ?: return
        while (scope.isActive) {
            runCatching {
                ProcessBuilder(adb, "reverse", "tcp:$TIME_LINK_PORT", "tcp:$TIME_LINK_PORT")
                    .redirectErrorStream(true)
                    .start()
                    .waitFor(2, TimeUnit.SECONDS)
            }
            delay(ADB_RECONCILE_INTERVAL_MILLIS)
        }
    }

    override fun close() {
        runCatching { ProcessBuilder(resolveAdb() ?: "adb", "reverse", "--remove", "tcp:$TIME_LINK_PORT").start() }
        runCatching { server.close() }
        scope.cancel()
    }
}

/** Locate the `adb` executable: PATH first, then the standard SDK platform-tools per OS, then ANDROID_HOME. */
private fun resolveAdb(): String? {
    val onPath = runCatching {
        ProcessBuilder("adb", "version").redirectErrorStream(true).start().waitFor(2, TimeUnit.SECONDS)
    }.isSuccess
    if (onPath) return "adb"

    val exe = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) "adb.exe" else "adb"
    val candidates = buildList {
        System.getenv("ANDROID_HOME")?.let { add(File(it, "platform-tools/$exe")) }
        System.getenv("ANDROID_SDK_ROOT")?.let { add(File(it, "platform-tools/$exe")) }
        System.getenv("LOCALAPPDATA")?.let { add(File(it, "Android/Sdk/platform-tools/$exe")) }
        val home = System.getProperty("user.home")
        add(File(home, "Library/Android/sdk/platform-tools/$exe")) // macOS
        add(File(home, "Android/Sdk/platform-tools/$exe"))         // Linux
    }
    return candidates.firstOrNull { it.canExecute() }?.absolutePath
}
