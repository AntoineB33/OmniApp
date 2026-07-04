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
import kotlinx.coroutines.flow.update
import org.example.project.time.SimAppClock

/**
 * Desktop half of the debug time-link (see [TimeLink]). Binds a loopback TCP server on [TIME_LINK_PORT],
 * streams `"<virtualNow> <speed>\n"` frames to each connected phone every [FRAME_INTERVAL_MILLIS], and keeps
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
        try {
            val out = socket.getOutputStream().bufferedWriter()
            while (scope.isActive && !socket.isClosed) {
                out.write("${clock.nowMillis()} ${clock.speed}\n")
                out.flush() // throws once the phone closes/unplugs → drops out of the loop
                delay(FRAME_INTERVAL_MILLIS)
            }
        } catch (_: IOException) {
            // Phone disconnected; fall through to cleanup.
        } finally {
            _linkedCount.update { (it - 1).coerceAtLeast(0) }
            runCatching { socket.close() }
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
