package org.example.project.scheduler.sync

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.coroutineContext
import org.example.project.scheduler.platform.Diagnostics

/**
 * The seam the [org.example.project.scheduler.ui.TaskSchedulerViewModel] uses to hold the remote→local auto-pull
 * subscription. [setAccount] points it at the signed-in account (or null to disconnect). Implemented by
 * [RealtimeSnapshotSubscriber]; a no-op / recording fake in tests (so they never open a real WebSocket).
 */
interface SnapshotChangeSubscription {
    /** Starts the connection supervisor (idempotent). */
    fun start()

    /** Point the subscription at [userId] (the signed-in account), or null to disconnect (signed out). */
    fun setAccount(userId: String?)
}

/**
 * Bidirectional-sync pull half (PRD §5): a hand-rolled Supabase **Realtime `postgres_changes`** subscriber over
 * a Ktor WebSocket (the project deliberately avoids the supabase-kt SDK — see [RemoteSnapshotClient]). While the
 * device is signed in it holds a Phoenix channel subscribed to changes on the account's `scheduler_head`
 * row; whenever the row changes on the server (another device pushed) it invokes [onRemoteChange], which pokes
 * a normal [SchedulerSyncEngine.reconcile]. It never applies the event payload directly — reconcile pulls
 * through the tested LWW path and its `revision` guard silently drops this device's own echo (the row change
 * our own push just made is already at `lastKnownRevision`, so the reconcile is a no-op).
 *
 * This is the only live Realtime WebSocket the client holds (the pause-cue moved to the pg_cron +
 * `device_heartbeat` model). It uses the shared Phoenix frame builders ([RealtimePhoenix]) and is keyed on
 * being **signed in** (not "active"), because a device wants to receive peers' edits whenever it is logged in;
 * it only reads (no `track`/`untrack`).
 *
 * **Streaming is not the same as synchronized.** Realtime delivers a row change only to sockets that are
 * connected *at that instant*, and a `postgres_changes` subscription has no cursor to resume from — so anything
 * that lands while this device is disconnected (OS sleep, network blip, the JWT-expiry rejoin) is lost to it
 * permanently. Live-streaming alone therefore cannot keep devices converged; it must be paired with a reconcile
 * on every **(re)subscribe** (below) and one at **startup** (`TaskSchedulerViewModel`). Those two make the stale
 * window bounded by the reconnect rather than open-ended.
 *
 * Wire shape VERIFIED live 2026-07-28 against the real project: a row UPDATE reached a subscribed desktop and
 * poked the reconcile. The builders are unit-tested besides, and the poke is defensive (the event body is never
 * trusted or parsed).
 */
class RealtimeSnapshotSubscriber(
    private val scope: CoroutineScope,
    private val realtimeUrl: String,
    private val apiKey: String,
    /** (userId, accessToken) for the Realtime join, or null while signed out. */
    private val auth: () -> Pair<String, String>?,
    /** Forces a session-token refresh (via the sync engine's serialized path) when the join is rejected for an
     * expired/invalid JWT, after which [auth] returns the fresh token on reconnect. */
    private val refreshAuth: suspend () -> Unit = {},
    /** This device's sync id: a change this device wrote itself is its own echo, and pokes nothing. */
    private val ownDeviceId: () -> String? = { null },
    /** Invoked on every server-side change to the subscribed row — pokes a reconcile. Best-effort/idempotent. */
    private val onRemoteChange: suspend () -> Unit,
    private val httpClient: HttpClient = HttpClient { install(WebSockets) },
) : SnapshotChangeSubscription, SchedulerPeerChannel {
    // The account whose snapshot row we subscribe to (its userId), or null while signed out. [supervise] keys
    // the socket on this so an account switch drops the old subscription and a sign-out closes it entirely.
    private val account = MutableStateFlow<String?>(null)
    private var job: Job? = null

    // `docs/invariants/scheduler.md` § *One device plans*: the account's private scheduler broadcast channel rides
    // this same socket as a second Phoenix channel. [connected] is true only between its join being accepted and
    // the socket closing.
    private val _peersConnected = MutableStateFlow(false)
    override val connected: StateFlow<Boolean> = _peersConnected.asStateFlow()
    private val _peerMessages = MutableSharedFlow<PeerMessage>(extraBufferCapacity = 64)
    override val messages: SharedFlow<PeerMessage> = _peerMessages.asSharedFlow()
    private val peerOutbox = Channel<PeerMessage>(capacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    override fun send(message: PeerMessage) {
        if (_peersConnected.value) peerOutbox.trySend(message)
    }

    override fun setAccount(userId: String?) {
        account.value = userId
    }

    override fun start() {
        if (job != null) return
        job = scope.launch { supervise() }
    }

    // Exactly one live socket at a time, always to the currently-signed-in account; [collectLatest] cancels the
    // previous account's connection the instant the userId changes or clears.
    private suspend fun supervise() {
        // A StateFlow already conflates equal consecutive values, so no distinctUntilChanged is needed.
        account.collectLatest { userId ->
            if (userId == null) return@collectLatest
            while (coroutineContext.isActive && account.value == userId) {
                // Only connect with a token that still matches this account (the session may have changed).
                val token = auth()?.takeIf { it.first == userId }?.second
                if (token != null) {
                    runCatching { serve(userId, token) }
                        .onFailure { Diagnostics.log("realtime snapshot subscription ended: ${it.message}") }
                }
                if (coroutineContext.isActive && account.value == userId) delay(RECONNECT_BACKOFF_MILLIS)
            }
        }
    }

    private suspend fun serve(userId: String, accessToken: String) {
        val topic = RealtimePhoenix.postgresChangesTopic("db:scheduler_head:$userId")
        httpClient.webSocket("$realtimeUrl?apikey=$apiKey&vsn=1.0.0") {
            val sendMutex = Mutex()
            var ref = 0L
            suspend fun emit(build: (ref: Long) -> String) = sendMutex.withLock { send(Frame.Text(build(++ref))) }

            val joinRef = sendMutex.withLock { ++ref }
            val joinFrame = RealtimePhoenix.postgresChangesJoinFrame(
                topic = topic,
                accessToken = accessToken,
                schema = "public",
                // The one-row-per-account head the entity rows bump (migration 20260917000000): one message per push.
                table = "scheduler_head",
                filter = "user_id=eq.$userId",
                ref = joinRef,
            )
            sendMutex.withLock { send(Frame.Text(joinFrame)) }
            Diagnostics.log("realtime snapshot sent join: ${joinFrame.replace(accessToken, "<token>").take(400)}")

            // The scheduler peers' private broadcast channel, on the same socket.
            val peerTopic = RealtimePhoenix.schedulerBroadcastTopic(userId)
            val peerJoinRef = sendMutex.withLock { ++ref }
            sendMutex.withLock { send(Frame.Text(RealtimePhoenix.broadcastJoinFrame(peerTopic, accessToken, peerJoinRef))) }
            // Drop whatever was queued for a previous connection: a peer message is about the moment it was sent.
            while (peerOutbox.tryReceive().isSuccess) Unit
            val peerSender = launch {
                for (message in peerOutbox) {
                    val text = PeerMessage.encode(message)
                    emit { RealtimePhoenix.broadcastFrame(peerTopic, peerJoinRef, it, text) }
                }
            }

            val heartbeat = launch {
                while (isActive) {
                    delay(HEARTBEAT_MILLIS)
                    emit { RealtimePhoenix.heartbeatFrame(it) }
                }
            }
            try {
                var logged = 0
                for (frame in incoming) {
                    if (frame !is Frame.Text) continue
                    val text = frame.readText()
                    if (logged < 8) {
                        logged++
                        Diagnostics.log("realtime snapshot recv: ${text.take(600)}")
                    }
                    RealtimePhoenix.broadcastMessage(text, peerTopic)?.let { body ->
                        PeerMessage.decode(body)?.let { _peerMessages.tryEmit(it) }
                        continue
                    }
                    when (RealtimePhoenix.joinReplyStatus(text, peerTopic)) {
                        true -> {
                            if (!_peersConnected.value) Diagnostics.log("realtime scheduler peers: channel joined")
                            _peersConnected.value = true
                            continue
                        }
                        false -> {
                            // Most likely migration 20260916000000 (the channel's RLS) is not applied: the snapshot
                            // subscription goes on, and every device simply plans for itself.
                            Diagnostics.log("realtime scheduler peers: join REFUSED — planning stays local: ${text.take(300)}")
                            _peersConnected.value = false
                            continue
                        }
                        null -> Unit
                    }
                    if (isAuthRejection(text)) {
                        Diagnostics.log("realtime snapshot join rejected for auth — refreshing token + reconnecting")
                        refreshAuth()
                        break // reconnect; auth() then returns the freshly-refreshed token
                    }
                    if (RealtimePhoenix.isPostgresSubscriptionError(text)) {
                        // The Phoenix JOIN succeeded but the server refuses to STREAM changes — almost always the
                        // table isn't in the `supabase_realtime` publication (migration 20260722000000 not applied).
                        // No token refresh helps; without this branch the socket would sit connected-but-dead and
                        // silently deliver nothing (indistinguishable from "sync works"). Surface it loudly, then
                        // back off and reconnect so it self-heals once the publication is fixed — without hammering.
                        Diagnostics.log(
                            "realtime snapshot subscription REJECTED by server (postgres_changes not enabled — is " +
                                "migration 20260917000000_entity_and_history_rows applied / scheduler_head in " +
                                "the supabase_realtime publication?): ${text.take(300)}",
                        )
                        delay(SUBSCRIPTION_ERROR_RETRY_MILLIS)
                        break // reconnect (re-joins the channel); a redeploy of the migration then succeeds
                    }
                    if (RealtimePhoenix.isPostgresSubscriptionReady(text)) {
                        // CATCH-UP ON (RE)SUBSCRIBE. Realtime streams changes only while the socket is up and
                        // **never replays** what happened while it was down — a `postgres_changes` subscription has
                        // no cursor/resume point. So every disconnection (OS sleep, network blip, the JWT-expiry
                        // rejoin, a Phoenix drop) is a window in which a peer's push is missed FOREVER: the device
                        // then sits stale until an edit or a restart, and an edit is the dangerous one — its own
                        // auto-push fetch finds the remote ahead and LWW-pulls over it (see the startup reconcile in
                        // TaskSchedulerViewModel). Reconciling here turns every reconnect into a catch-up, so the
                        // stale window is bounded by the reconnect, not by the next user action. This fires on the
                        // FIRST subscribe of a connection too, which is harmless: reconcile is mutex-guarded and
                        // no-ops when the revision already matches.
                        Diagnostics.log("realtime snapshot: subscription live — reconciling to catch up")
                        runCatching { onRemoteChange() }
                            .onFailure { Diagnostics.log("realtime snapshot catch-up reconcile failed: ${it.message}") }
                    }
                    if (RealtimePhoenix.isPostgresChange(text)) {
                        val writer = RealtimePhoenix.changeWriterDeviceId(text)
                        if (writer != null && writer == ownDeviceId()) continue
                        Diagnostics.log("realtime snapshot: remote change — poking reconcile")
                        runCatching { onRemoteChange() }
                            .onFailure { Diagnostics.log("realtime snapshot reconcile poke failed: ${it.message}") }
                    }
                }
                Diagnostics.log("realtime snapshot incoming stream closed")
            } finally {
                _peersConnected.value = false
                peerSender.cancel()
                heartbeat.cancel()
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
        // A rejected postgres_changes subscription won't fix itself on a fast retry (it needs a server-side
        // migration), so wait this long before reconnecting — visible in diagnostics without hammering.
        private const val SUBSCRIPTION_ERROR_RETRY_MILLIS = 30_000L
    }
}
