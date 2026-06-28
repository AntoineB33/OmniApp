package org.example.project

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.example.project.scheduler.persistence.SyncMeta
import org.example.project.scheduler.persistence.SyncMetaStore
import org.example.project.scheduler.platform.DeviceKind
import org.example.project.scheduler.sync.RemoteSnapshotClient
import org.example.project.scheduler.sync.SchedulerSyncEngine
import org.example.project.scheduler.sync.SupabaseConfig

/**
 * Exercises PRD §15 cross-device presence through [SchedulerSyncEngine]'s [PresenceGateway][org.example.project.scheduler.sync.PresenceGateway]
 * impl against a stateful in-memory fake of Supabase (GoTrue auth + the `device_presence` rows) driven by
 * Ktor's [MockEngine]. Covers: the presence-beacon upsert body shape, and that the peer-active query counts
 * only a fresh peer with an active screen — never this device itself, a stale row, or a screen-off peer —
 * plus the null (unknown) vs false (definitive) distinction on a transport failure.
 */
class DevicePresenceTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val config = SupabaseConfig("https://test.supabase.co", "anon-key")
    private val stale = 90_000L

    private class FakeMetaStore(private var meta: SyncMeta? = null) : SyncMetaStore {
        override fun loadSyncMeta(): SyncMeta? = meta

        override fun saveSyncMeta(meta: SyncMeta) {
            this.meta = meta
        }
    }

    /** Server-side fake: the presence rows a GET returns, and the last upsert body a POST received. */
    private class PresenceServer(var rows: List<String> = emptyList(), var lastUpsert: String? = null)

    private fun row(deviceId: String, kind: String, screenActive: Boolean, updatedAt: String) =
        """{"device_id":"$deviceId","kind":"$kind","screen_active":$screenActive,"updated_at":"$updatedAt"}"""

    private fun harness(server: PresenceServer): RemoteSnapshotClient {
        val engine =
            MockEngine { request ->
                val path = request.url.encodedPath
                val jsonHeader = headersOf("Content-Type", "application/json")
                when {
                    path.startsWith("/auth/v1") ->
                        respond(
                            """{"access_token":"at","refresh_token":"rt","user":{"id":"user-1"}}""",
                            HttpStatusCode.OK,
                            jsonHeader,
                        )

                    path.endsWith("/device_presence") && request.method == HttpMethod.Get ->
                        respond("[${server.rows.joinToString(",")}]", HttpStatusCode.OK, jsonHeader)

                    path.endsWith("/device_presence") && request.method == HttpMethod.Post -> {
                        server.lastUpsert = (request.body as? TextContent)?.text ?: ""
                        respond("", HttpStatusCode.Created, jsonHeader)
                    }

                    else -> respond("", HttpStatusCode.NotFound)
                }
            }
        return RemoteSnapshotClient(config, HttpClient(engine))
    }

    private suspend fun signedInEngine(server: PresenceServer): SchedulerSyncEngine =
        SchedulerSyncEngine(harness(server), FakeMetaStore(SyncMeta(deviceId = "self")), json)
            .also { it.signIn("a@b.c", "pw") }

    @Test
    fun publish_presence_upserts_this_device() = runTest {
        val server = PresenceServer()
        signedInEngine(server).publishPresence(DeviceKind.Phone, screenActive = false)

        val body = json.parseToJsonElement(assertNotNull(server.lastUpsert)).jsonObject
        assertEquals("self", body["device_id"]!!.jsonPrimitive.content)
        assertEquals("phone", body["kind"]!!.jsonPrimitive.content)
        assertFalse(body["screen_active"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun fresh_peer_with_active_screen_counts() = runTest {
        val server = PresenceServer(listOf(row("desktop-1", "desktop", true, Clock.System.now().toString())))
        assertTrue(signedInEngine(server).activePeersExist(stale))
    }

    @Test
    fun own_active_row_does_not_count() = runTest {
        val server = PresenceServer(listOf(row("self", "phone", true, Clock.System.now().toString())))
        assertFalse(signedInEngine(server).activePeersExist(stale))
    }

    @Test
    fun stale_peer_does_not_count() = runTest {
        val tenMinAgo = (Clock.System.now() - 10.minutes).toString()
        val server = PresenceServer(listOf(row("desktop-1", "desktop", true, tenMinAgo)))
        assertFalse(signedInEngine(server).activePeersExist(stale))
    }

    @Test
    fun peer_with_screen_off_does_not_count() = runTest {
        val server = PresenceServer(listOf(row("desktop-1", "desktop", false, Clock.System.now().toString())))
        assertFalse(signedInEngine(server).activePeersExist(stale))
    }

    @Test
    fun signed_out_engine_reports_no_active_peers() = runTest {
        val server = PresenceServer(listOf(row("desktop-1", "desktop", true, Clock.System.now().toString())))
        val engine = SchedulerSyncEngine(harness(server), FakeMetaStore(SyncMeta(deviceId = "self")), json)
        assertFalse(engine.signedIn)
        // Signed out is a *definitive* "no peer" (false), not an unknown (null).
        assertFalse(engine.activePeersExistOrNull(stale) == null)
        assertFalse(engine.activePeersExist(stale))
    }

    @Test
    fun transport_failure_is_unknown_and_fails_open() = runTest {
        // A GET that errors out: the answer is unknown (null) so the cue caller can retry, while the fail-open
        // convenience still reports false (the phone then speaks the end cue rather than staying silent).
        val engine =
            MockEngine { request ->
                val jsonHeader = headersOf("Content-Type", "application/json")
                when {
                    request.url.encodedPath.startsWith("/auth/v1") ->
                        respond(
                            """{"access_token":"at","refresh_token":"rt","user":{"id":"user-1"}}""",
                            HttpStatusCode.OK,
                            jsonHeader,
                        )

                    request.url.encodedPath.endsWith("/device_presence") && request.method == HttpMethod.Get ->
                        respond("boom", HttpStatusCode.InternalServerError, jsonHeader)

                    else -> respond("", HttpStatusCode.NotFound)
                }
            }
        val sync =
            SchedulerSyncEngine(RemoteSnapshotClient(config, HttpClient(engine)), FakeMetaStore(SyncMeta(deviceId = "self")), json)
                .also { it.signIn("a@b.c", "pw") }

        assertNull(sync.activePeersExistOrNull(stale))
        assertFalse(sync.activePeersExist(stale))
    }
}
