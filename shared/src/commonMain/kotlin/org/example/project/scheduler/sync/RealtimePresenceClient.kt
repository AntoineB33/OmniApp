package org.example.project.scheduler.sync

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.coroutineContext
import kotlinx.serialization.json.JsonNull
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
    /**
     * PRD §15 (server-side break computation): the drawn START of the next ≥5-min rest pose. A start pinned
     * to the now-line means the break is already WAITING (overdue-unserved poses slide, `maxOf(due, now)`),
     * so the listener computes the true pause end as `max(disconnectStart, start) + len` from the instant
     * the LAST device actually left presence — not from this (up to one beat stale) client projection.
     */
    val nextBreakStartMillis: Long? = null,
    /** PRD §15: the length of that pose (`end − start`) — also the "waiting break" length for the cue. */
    val nextBreakLenMillis: Long? = null,
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
    /** Forces a session-token refresh (via the sync engine's serialized path) — called when the join is
     * rejected for an expired/invalid JWT, after which [auth] returns the fresh token on reconnect. */
    private val refreshAuth: suspend () -> Unit = {},
    private val httpClient: HttpClient = HttpClient { install(WebSockets) },
) : RealtimePresence {
    // The connection target: the ACCOUNT this device belongs to ([userId], sampled from [auth] at publish
    // time) plus the [payload] to track. Keying on the userId is what makes the association exclusive — see
    // [supervise]. Null when there is nothing to publish (inactive / signed out).
    private data class Target(val userId: String, val payload: PresenceState)

    private val desired = MutableStateFlow<Target?>(null)
    private var job: Job? = null

    override fun setPresence(state: PresenceState?) {
        // Bind the presence to the CURRENT account (auth() at this instant). A signed-out device (or one whose
        // active state was cleared) publishes nothing; a device that just switched accounts binds to the new
        // userId, which [supervise] reacts to by dropping the old account's socket.
        desired.value = state?.let { s -> auth()?.let { Target(it.first, s) } }
    }

    /** Starts the connection supervisor (idempotent). */
    fun start() {
        if (job != null) return
        job = scope.launch { supervise() }
    }

    // The connection is keyed on the ACCOUNT (userId): exactly one live socket at a time, always to the
    // currently-signed-in account. [collectLatest] cancels the previous account's connection the instant the
    // userId changes (account switch) or clears (sign-out / inactive) — cancelling [serve] closes its
    // WebSocket, so Phoenix removes this device from the OLD account's presence at once. That is what enforces
    // "a device is associated with, at most, one account at a time" (PRD §15): the previous key-only-on-active
    // supervisor left the old account's socket open across a switch, so the device appeared active on BOTH
    // accounts and the old one never saw the no-screen period. Payload-only changes within one account are sent
    // by [serve] collecting [desired] (no reconnect).
    private suspend fun supervise() {
        desired.map { it?.userId }.distinctUntilChanged().collectLatest { userId ->
            if (userId == null) return@collectLatest
            while (coroutineContext.isActive && desired.value?.userId == userId) {
                // Only connect with a token that still matches this account (the session may have changed
                // between the map above and here); a mismatch/absence just waits for the next [setPresence].
                val token = auth()?.takeIf { it.first == userId }?.second
                if (token != null) {
                    runCatching { serve(userId, token) }
                        .onFailure { Diagnostics.log("realtime presence connection ended: ${it.message}") }
                }
                if (coroutineContext.isActive && desired.value?.userId == userId) delay(RECONNECT_BACKOFF_MILLIS)
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
            val joinFrame = RealtimePhoenix.joinFrame(topic, accessToken, deviceId, joinRef)
            sendMutex.withLock { send(Frame.Text(joinFrame)) }
            // TEMP (presence bring-up): dump the exact wire bytes so the format is verifiable. Token elided.
            Diagnostics.log("realtime presence sent join: ${joinFrame.replace(accessToken, "<token>").take(400)}")

            val heartbeat = launch {
                while (isActive) {
                    delay(HEARTBEAT_MILLIS)
                    emit { RealtimePhoenix.heartbeatFrame(it) }
                }
            }
            val publisher = launch {
                // Re-track only the payload; an account change is handled by [supervise] restarting the whole
                // connection, so a payload here is always for [userId]. A null payload (went inactive) untracks.
                desired.map { it?.payload }.distinctUntilChanged().collect { state ->
                    if (state != null) emit { RealtimePhoenix.trackFrame(topic, joinRef, state, it) }
                    else emit { RealtimePhoenix.untrackFrame(topic, joinRef, it) }
                }
            }
            try {
                // Drain incoming (join/heartbeat replies, presence diffs) — we only publish, so ignore them.
                // The loop ends when the socket closes, returning from webSocket{} to trigger a reconnect.
                // TEMP (presence bring-up): log the first few server frames so a rejected phx_join is visible.
                var logged = 0
                for (frame in incoming) {
                    if (frame !is Frame.Text) continue
                    val text = frame.readText()
                    if (logged < 8) {
                        logged++
                        Diagnostics.log("realtime presence recv: ${text.take(600)}")
                    }
                    if (isAuthRejection(text)) {
                        Diagnostics.log("realtime presence join rejected for auth — refreshing token + reconnecting")
                        refreshAuth()
                        break // reconnect; auth() then returns the freshly-refreshed token
                    }
                }
                Diagnostics.log("realtime presence incoming stream closed")
            } finally {
                heartbeat.cancel()
                publisher.cancel()
            }
        }
    }

    // A `phx_reply` rejecting our join because the JWT is expired/invalid — the cue to refresh + reconnect.
    private fun isAuthRejection(text: String): Boolean =
        text.contains("phx_reply") && text.contains("\"status\":\"error\"") &&
            (text.contains("JWT", ignoreCase = true) || text.contains("token has expired", ignoreCase = true))

    companion object {
        // Phoenix drops a channel that misses heartbeats ~60 s apart; 25 s keeps a comfortable margin.
        private const val HEARTBEAT_MILLIS = 25_000L
        private const val RECONNECT_BACKOFF_MILLIS = 3_000L
    }
}

/**
 * Pure builders for the Phoenix (Supabase Realtime) message envelopes this client sends. The socket connects
 * with `vsn=1.0.0`, which selects Phoenix's **V1 object** serializer on the server — so messages are
 * `{"topic","event","payload","ref"[,"join_ref"]}` (matching `@supabase/realtime-js`, which `JSON.stringify`s
 * the same object shape; the V2 array form is only for `vsn=2.0.0`). Extracted from [RealtimePresenceClient] so
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
                // PRD §15: the pose window, so the listener can compute the pause end from the real
                // disconnect instant (`max(disconnectStart, start) + len`) instead of trusting the
                // client-projected end. Kept alongside `next_break_end_ms` for older listeners.
                if (state.nextBreakStartMillis != null) put("next_break_start_ms", state.nextBreakStartMillis) else put("next_break_start_ms", JsonNull)
                if (state.nextBreakLenMillis != null) put("next_break_len_ms", state.nextBreakLenMillis) else put("next_break_len_ms", JsonNull)
            }
        }

    /** Removes this device's presence entry. */
    fun untrackFrame(topic: String, joinRef: Long, ref: Long): String =
        envelope(topic, "presence", ref, joinRef) {
            put("type", "presence")
            put("event", "untrack")
        }

    // Supabase (vsn=1.0.0 / Phoenix V1) wire shape: {topic, event, payload, ref[, join_ref]}. refs are strings;
    // the heartbeat (a non-channel message) omits join_ref.
    private fun envelope(
        topic: String,
        event: String,
        ref: Long,
        joinRef: Long?,
        payload: JsonObjectBuilderScope,
    ): String =
        buildJsonObject {
            put("topic", topic)
            put("event", event)
            put("payload", buildJsonObject(payload))
            put("ref", ref.toString())
            if (joinRef != null) put("join_ref", joinRef.toString())
        }.toString()
}

// Alias for the trailing-lambda payload builder passed to [RealtimePhoenix.envelope].
private typealias JsonObjectBuilderScope = kotlinx.serialization.json.JsonObjectBuilder.() -> Unit
