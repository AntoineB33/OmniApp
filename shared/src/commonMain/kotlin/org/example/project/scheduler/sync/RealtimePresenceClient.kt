package org.example.project.scheduler.sync

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.example.project.scheduler.platform.Diagnostics

/**
 * PRD §15 cross-device presence, Realtime-Presence model: what a device publishes about itself while it is
 * **active** (app in the foreground, screen on, signed in). The external listener (see /listener) watches the
 * account's presence channel and, when the account goes fully inactive and is not sleeping, fires the phone's
 * pause-end cue at [nextBreakEndMillis] − 1 s. `null` [nextBreakEndMillis] means no upcoming ≥5-min break.
 */
data class PresenceState(
    val deviceId: String,
    val kind: String,
    val nextBreakEndMillis: Long?,
)

/**
 * The seam the [org.example.project.scheduler.engine.SchedulerEngine] uses to publish this device's presence.
 * `null` clears it (the device went inactive / signed out); a non-null state (re)tracks it. Idempotent —
 * setting the same value twice is a no-op. Implemented by [RealtimePresenceClient]; a no-op fake in tests.
 */
interface RealtimePresence {
    fun setPresence(state: PresenceState?)
}

/**
 * Hand-rolled Supabase **Realtime Presence** publisher over a Ktor WebSocket (the project deliberately avoids
 * the supabase-kt SDK — see [RemoteSnapshotClient]). It holds a Phoenix channel on the account's presence
 * topic **only while this device is active**, so the WebSocket connection itself is the liveness signal — there
 * is no heartbeat REST write. It only *publishes* (tracks/untracks) presence; reading peers moved to the
 * external listener, so incoming frames are drained and ignored.
 *
 * Lifecycle, driven by [setPresence]:
 *  - active (non-null) → open the WS, `phx_join` the topic, `track` the state, and keep a Phoenix heartbeat
 *    going; re-`track` whenever the state changes; reconnect (with a fixed backoff) if the socket drops.
 *  - inactive (null) → close the WS (Phoenix treats the leave as this device dropping out of presence).
 *
 * NOTE: the exact Phoenix wire format (message envelope, presence track payload) can only be validated against
 * a live Supabase Realtime endpoint; the pure frame builders in [RealtimePhoenix] are unit-tested, but the
 * end-to-end connection needs on-device verification (see docs/PAUSE_CUE_DELIVERY.md).
 */
class RealtimePresenceClient(
    private val scope: CoroutineScope,
    private val realtimeUrl: String,
    private val apiKey: String,
    private val deviceId: String,
    /** (userId, accessToken) for the Realtime join, or null while signed out. */
    private val auth: () -> Pair<String, String>?,
    private val httpClient: HttpClient = HttpClient { install(WebSockets) },
) : RealtimePresence {
    private val desired = MutableStateFlow<PresenceState?>(null)
    private var job: Job? = null

    override fun setPresence(state: PresenceState?) {
        desired.value = state
    }

    /** Starts the connection supervisor (idempotent). */
    fun start() {
        if (job != null) return
        job = scope.launch { supervise() }
    }

    // React only to active↔inactive transitions: while active, hold a connection (reconnecting on drop); while
    // inactive, hold none. Payload changes within an active session are sent by [serve] collecting [desired].
    private suspend fun supervise() {
        desired.map { it != null }.distinctUntilChanged().collect { active ->
            if (!active) return@collect
            // A fresh launch per active window so it is cancelled cleanly the moment we go inactive.
            scope.launch {
                while (isActive && desired.value != null) {
                    val credentials = auth()
                    if (credentials != null) {
                        runCatching { serve(credentials.first, credentials.second) }
                            .onFailure { Diagnostics.log("realtime presence connection ended: ${it.message}") }
                    }
                    if (isActive && desired.value != null) delay(RECONNECT_BACKOFF_MILLIS)
                }
            }
        }
    }

    private suspend fun serve(userId: String, accessToken: String) {
        val topic = RealtimePhoenix.topic(userId)
        httpClient.webSocket("$realtimeUrl?apikey=$apiKey&vsn=1.0.0") {
            val sendMutex = Mutex()
            var ref = 0L
            suspend fun emit(build: (ref: Long) -> String) = sendMutex.withLock { send(Frame.Text(build(++ref))) }

            val joinRef = sendMutex.withLock { ++ref }
            sendMutex.withLock { send(Frame.Text(RealtimePhoenix.joinFrame(topic, accessToken, deviceId, joinRef))) }
            Diagnostics.log("realtime presence joined $topic (device=$deviceId)")

            val heartbeat = launch {
                while (isActive) {
                    delay(HEARTBEAT_MILLIS)
                    emit { RealtimePhoenix.heartbeatFrame(it) }
                }
            }
            val publisher = launch {
                desired.collect { state ->
                    if (state != null) emit { RealtimePhoenix.trackFrame(topic, joinRef, state, it) }
                    else emit { RealtimePhoenix.untrackFrame(topic, joinRef, it) }
                }
            }
            try {
                // Drain incoming (join/heartbeat replies, presence diffs) — we only publish, so ignore them.
                // The loop ends when the socket closes, returning from webSocket{} to trigger a reconnect.
                for (frame in incoming) {
                    // no-op
                }
            } finally {
                heartbeat.cancel()
                publisher.cancel()
            }
        }
    }

    companion object {
        // Phoenix drops a channel that misses heartbeats ~60 s apart; 25 s keeps a comfortable margin.
        private const val HEARTBEAT_MILLIS = 25_000L
        private const val RECONNECT_BACKOFF_MILLIS = 5_000L
    }
}

/**
 * Pure builders for the Phoenix (Supabase Realtime, protocol vsn 1.0.0) message envelopes this client sends.
 * Object format: `{"topic","event","payload","ref"[,"join_ref"]}`. Extracted from [RealtimePresenceClient] so
 * the wire shape is unit-testable without a live socket.
 */
internal object RealtimePhoenix {
    fun topic(userId: String): String = "realtime:presence:$userId"

    /** `phx_join` with a presence key = this device id and the caller's access token. `ref == join_ref`. */
    fun joinFrame(topic: String, accessToken: String, presenceKey: String, ref: Long): String =
        envelope(topic, "phx_join", ref, ref) {
            putJsonObject("config") {
                putJsonObject("broadcast") { put("ack", false); put("self", false) }
                putJsonObject("presence") { put("key", presenceKey) }
                put("private", false)
            }
            put("access_token", accessToken)
        }

    /** Phoenix keep-alive on the reserved `phoenix` topic (no join_ref). */
    fun heartbeatFrame(ref: Long): String = envelope("phoenix", "heartbeat", ref, null) {}

    /** Tracks (adds/updates) this device's presence entry with its [state] payload. */
    fun trackFrame(topic: String, joinRef: Long, state: PresenceState, ref: Long): String =
        envelope(topic, "presence", ref, joinRef) {
            put("type", "presence")
            put("event", "track")
            putJsonObject("payload") {
                put("device_id", state.deviceId)
                put("kind", state.kind)
                if (state.nextBreakEndMillis != null) put("next_break_end_ms", state.nextBreakEndMillis) else put("next_break_end_ms", JsonNull)
            }
        }

    /** Removes this device's presence entry. */
    fun untrackFrame(topic: String, joinRef: Long, ref: Long): String =
        envelope(topic, "presence", ref, joinRef) {
            put("type", "presence")
            put("event", "untrack")
        }

    private fun envelope(
        topic: String,
        event: String,
        ref: Long,
        joinRef: Long?,
        payload: JsonObjectBuilderScope,
    ): String {
        val obj: JsonObject = buildJsonObject {
            put("topic", topic)
            put("event", event)
            put("payload", buildJsonObject(payload))
            put("ref", ref.toString())
            if (joinRef != null) put("join_ref", joinRef.toString())
        }
        return obj.toString()
    }
}

// Alias for the trailing-lambda payload builder passed to [RealtimePhoenix.envelope].
private typealias JsonObjectBuilderScope = kotlinx.serialization.json.JsonObjectBuilder.() -> Unit
