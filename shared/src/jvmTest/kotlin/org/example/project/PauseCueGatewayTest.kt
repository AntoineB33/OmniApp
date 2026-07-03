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
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.example.project.scheduler.persistence.SyncMeta
import org.example.project.scheduler.persistence.SyncMetaStore
import org.example.project.scheduler.sync.RemoteSnapshotClient
import org.example.project.scheduler.sync.SchedulerSyncEngine
import org.example.project.scheduler.sync.SupabaseConfig

/**
 * Exercises PRD §15 / ARCHITECTURE.md §8 pause-end cue delivery through [SchedulerSyncEngine]'s
 * [PauseCueGateway][org.example.project.scheduler.sync.PauseCueGateway] impl against a Ktor [MockEngine].
 * Covers each PostgREST write's method/path/body shape (schedule upsert carries the ISO `due_at` + this
 * device as `origin_device_id`; clear is a DELETE; last-phone/token carry the device id), and that every call
 * is a no-op while signed out (never reaches the server).
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

    private fun harness(cap: Captured): RemoteSnapshotClient {
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
                    respond("", HttpStatusCode.NoContent)
                }
            }
        return RemoteSnapshotClient(config, HttpClient(engine))
    }

    private suspend fun signedIn(cap: Captured): SchedulerSyncEngine =
        SchedulerSyncEngine(harness(cap), FakeMetaStore(SyncMeta(deviceId = "self")), json)
            .also { it.signIn("a@b.c", "pw") }

    @Test
    fun publish_schedule_writes_due_and_origin() = runTest {
        val cap = Captured()
        signedIn(cap).publishPauseCueSchedule(1_800_000_000_000L)

        assertEquals("POST", cap.method)
        assertTrue(cap.path!!.endsWith("/pause_cue_schedule"))
        val body = json.parseToJsonElement(cap.body!!).jsonObject
        assertEquals("self", body["origin_device_id"]!!.jsonPrimitive.content)
        assertEquals(
            Instant.fromEpochMilliseconds(1_800_000_000_000L).toString(),
            body["due_at"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun clear_schedule_deletes() = runTest {
        val cap = Captured()
        signedIn(cap).clearPauseCueSchedule()

        assertEquals("DELETE", cap.method)
        assertTrue(cap.path!!.endsWith("/pause_cue_schedule"))
    }

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
    fun signed_out_is_a_no_op() = runTest {
        val cap = Captured()
        val engine = SchedulerSyncEngine(harness(cap), FakeMetaStore(SyncMeta(deviceId = "self")), json)
        engine.publishPauseCueSchedule(1L)
        engine.clearPauseCueSchedule()
        engine.claimLastPhone()
        engine.registerPushToken("phone", "fcm", "t")
        // Signed out: nothing reached the server.
        assertNull(cap.method)
    }
}
