package org.example.project

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.example.project.scheduler.persistence.SyncMeta
import org.example.project.scheduler.persistence.SyncMetaStore
import org.example.project.scheduler.sync.NextBreakState
import org.example.project.scheduler.sync.PresenceState
import org.example.project.scheduler.sync.RemoteSnapshotClient
import org.example.project.scheduler.sync.SchedulerSyncEngine
import org.example.project.scheduler.sync.SupabaseConfig

/**
 * Exercises PRD §15 pause-end cue delivery through [SchedulerSyncEngine]'s
 * [PauseCueGateway][org.example.project.scheduler.sync.PauseCueGateway] impl against a Ktor [MockEngine].
 * Covers each call's method/path/body shape (last-phone / push-token carry the device id; the `t_a` presence
 * tick carries the break window + kind and reads `t_a` back; the screen-off short-circuit invokes the Edge
 * Function with this device's id), and that every call is a no-op while signed out (never reaches the server).
 * The cue's *timing* stays off the client — the server evaluates the presence rows this writes.
 */
class PauseCueGatewayTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val config = SupabaseConfig("https://test.supabase.co", "anon-key")

    private class FakeMetaStore(private var meta: SyncMeta? = null) : SyncMetaStore {
        override fun loadSyncMeta(): SyncMeta? = meta

        override fun saveSyncMeta(meta: SyncMeta) {
            this.meta = meta
        }
    }

    /** Captures the (single) data request the engine issued after auth. */
    private class Captured(var method: String? = null, var path: String? = null, var body: String? = null)

    private fun harness(cap: Captured, dataResponse: String = ""): RemoteSnapshotClient {
        val engine =
            MockEngine { request ->
                val jsonHeader = headersOf("Content-Type", "application/json")
                if (request.url.encodedPath.startsWith("/auth/v1")) {
                    respond(
                        """{"access_token":"at","refresh_token":"rt","user":{"id":"user-1"}}""",
                        HttpStatusCode.OK,
                        jsonHeader,
                    )
                } else {
                    cap.method = request.method.value
                    cap.path = request.url.encodedPath
                    cap.body = (request.body as? TextContent)?.text
                    if (dataResponse.isEmpty()) {
                        respond("", HttpStatusCode.NoContent)
                    } else {
                        respond(dataResponse, HttpStatusCode.OK, jsonHeader)
                    }
                }
            }
        return RemoteSnapshotClient(config, HttpClient(engine))
    }

    private suspend fun signedIn(cap: Captured, dataResponse: String = ""): SchedulerSyncEngine =
        SchedulerSyncEngine(harness(cap, dataResponse), FakeMetaStore(SyncMeta(deviceId = "self")), json)
            .also { it.signIn("a@b.c", "pw") }

    @Test
    fun claim_last_phone_writes_this_device() = runTest {
        val cap = Captured()
        signedIn(cap).claimLastPhone()

        assertEquals("POST", cap.method)
        assertTrue(cap.path!!.endsWith("/account_last_phone"))
        assertEquals("self", json.parseToJsonElement(cap.body!!).jsonObject["device_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun register_push_token_writes_platform_and_token() = runTest {
        val cap = Captured()
        signedIn(cap).registerPushToken("phone", "fcm", "tok-123")

        assertEquals("POST", cap.method)
        assertTrue(cap.path!!.endsWith("/device_push_token"))
        val body = json.parseToJsonElement(cap.body!!).jsonObject
        assertEquals("self", body["device_id"]!!.jsonPrimitive.content)
        assertEquals("fcm", body["platform"]!!.jsonPrimitive.content)
        assertEquals("tok-123", body["token"]!!.jsonPrimitive.content)
    }

    @Test
    fun publish_account_state_writes_sleeping_mode_and_wake() = runTest {
        val cap = Captured()
        signedIn(cap).publishAccountState(sleeping = true, wakeAtMillis = 1_800_000_000_000L)

        assertEquals("POST", cap.method)
        assertTrue(cap.path!!.endsWith("/account_state"))
        val body = json.parseToJsonElement(cap.body!!).jsonObject
        assertEquals("sleeping", body["mode"]!!.jsonPrimitive.content)
        assertEquals(
            kotlin.time.Instant.fromEpochMilliseconds(1_800_000_000_000L).toString(),
            body["wake_at"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun publish_account_state_working_has_null_wake() = runTest {
        val cap = Captured()
        signedIn(cap).publishAccountState(sleeping = false, wakeAtMillis = null)

        assertEquals("working", json.parseToJsonElement(cap.body!!).jsonObject["mode"]!!.jsonPrimitive.content)
        assertTrue(cap.body!!.contains("\"wake_at\":null"))
    }

    @Test
    fun the_presence_tick_body_is_the_device_id_and_nothing_else() = runTest {
        // Migration 20260726000000: the beat is identity + (server-stamped) time. Everything about the break
        // travels on its own event-driven row, so nothing that changes with the schedule rides the `t_a` tick.
        val cap = Captured()
        // The RPC returns the account's `t_a` in seconds — a bare scalar body.
        val tickSeconds = signedIn(cap, dataResponse = "30").publishPresence(PresenceState("ignored"))

        assertEquals("POST", cap.method)
        assertTrue(cap.path!!.endsWith("/rpc/publish_presence"))
        val body = json.parseToJsonElement(cap.body!!).jsonObject
        // The engine writes its own meta device id, not the one in the state object.
        assertEquals("self", body["p_device_id"]!!.jsonPrimitive.content)
        assertEquals(setOf("p_device_id"), body.keys, "the beat must carry nothing but the device id")
        // The server stamps the row's time and owns the account id — the client must send neither.
        assertTrue(!cap.body!!.contains("beat_at"))
        assertTrue(!cap.body!!.contains("user_id"))
        // `t_a` came back, so the publisher re-paces itself to 30 s.
        assertEquals(30, tickSeconds)
    }

    @Test
    fun the_next_break_write_carries_the_due_instant_kind_and_length() = runTest {
        val cap = Captured()
        signedIn(cap).publishNextBreak(
            NextBreakState(
                deviceId = "ignored", // the engine writes its own meta device id
                kind = "phone",
                breakKind = "15min_break",
                dueMillis = 1_800_000_000_000L,
                lengthMillis = 900_000L,
            ),
        )

        assertEquals("POST", cap.method)
        assertTrue(cap.path!!.endsWith("/rpc/publish_next_break"), "was ${cap.path}")
        val body = json.parseToJsonElement(cap.body!!).jsonObject
        assertEquals("self", body["p_device_id"]!!.jsonPrimitive.content)
        assertEquals("phone", body["p_kind"]!!.jsonPrimitive.content)
        assertEquals("15min_break", body["p_break_kind"]!!.jsonPrimitive.content)
        // The pose's DUE instant, not its drawn (now-line-riding) start — that is what makes this row writable
        // on change instead of on every beat.
        assertEquals(1_800_000_000_000L, body["p_break_due_ms"]!!.jsonPrimitive.content.toLong())
        assertEquals(900_000L, body["p_break_len_ms"]!!.jsonPrimitive.content.toLong())
        assertTrue(!cap.body!!.contains("user_id"))
    }

    @Test
    fun screen_off_invokes_the_edge_function_excluding_this_device() = runTest {
        val cap = Captured()
        signedIn(cap).notifyScreenOff()

        assertEquals("POST", cap.method)
        assertTrue(cap.path!!.endsWith("/functions/v1/pause-cue"), "was ${cap.path}")
        val body = json.parseToJsonElement(cap.body!!).jsonObject
        assertEquals("evaluate", body["action"]!!.jsonPrimitive.content)
        // e1 excludes the reporting device from the account-liveness check (its row is still fresh).
        assertEquals("self", body["device_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun signed_out_is_a_no_op() = runTest {
        val cap = Captured()
        val engine = SchedulerSyncEngine(harness(cap), FakeMetaStore(SyncMeta(deviceId = "self")), json)
        engine.claimLastPhone()
        engine.registerPushToken("phone", "fcm", "t")
        engine.publishAccountState(sleeping = true, wakeAtMillis = 1L)
        assertNull(engine.publishPresence(PresenceState("d")))
        engine.publishNextBreak(NextBreakState("d", "phone", "5min_break", 1L, 2L))
        engine.notifyScreenOff()
        // Signed out: nothing reached the server.
        assertNull(cap.method)
    }
}
