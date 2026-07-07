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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.example.project.scheduler.model.TaskTimeRange
import org.example.project.scheduler.persistence.ActiveSessionRecord
import org.example.project.scheduler.persistence.SyncMeta
import org.example.project.scheduler.persistence.SyncMetaStore
import org.example.project.scheduler.sync.RemoteSnapshotClient
import org.example.project.scheduler.sync.SchedulerSyncEngine
import org.example.project.scheduler.sync.SupabaseConfig

/**
 * Exercises PRD §15 device-active sessions through [SchedulerSyncEngine]'s
 * [ActiveSessionGateway][org.example.project.scheduler.sync.ActiveSessionGateway] impl against an in-memory
 * fake of Supabase (GoTrue auth + the `device_active_session` upsert + the `derive_pauses` RPC) driven by
 * Ktor's [MockEngine]. Covers the push body shape, the RPC arg shape + pause parsing, and the signed-out /
 * transport-failure distinction (empty list vs null).
 */
class ActiveSessionGatewayTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val config = SupabaseConfig("https://test.supabase.co", "anon-key")

    private class FakeMetaStore(private var meta: SyncMeta? = null) : SyncMetaStore {
        override fun loadSyncMeta(): SyncMeta? = meta

        override fun saveSyncMeta(meta: SyncMeta) {
            this.meta = meta
        }
    }

    private class SessionServer(
        var pauseRows: List<String> = emptyList(),
        var lastUpsert: String? = null,
        var lastRpcBody: String? = null,
    )

    private fun harness(server: SessionServer): RemoteSnapshotClient {
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

                    path.endsWith("/device_active_session") && request.method == HttpMethod.Post -> {
                        server.lastUpsert = (request.body as? TextContent)?.text ?: ""
                        respond("", HttpStatusCode.Created, jsonHeader)
                    }

                    path.endsWith("/rpc/derive_pauses") && request.method == HttpMethod.Post -> {
                        server.lastRpcBody = (request.body as? TextContent)?.text ?: ""
                        respond("[${server.pauseRows.joinToString(",")}]", HttpStatusCode.OK, jsonHeader)
                    }

                    else -> respond("", HttpStatusCode.NotFound)
                }
            }
        return RemoteSnapshotClient(config, HttpClient(engine))
    }

    private suspend fun signedInEngine(server: SessionServer): SchedulerSyncEngine =
        SchedulerSyncEngine(harness(server), FakeMetaStore(SyncMeta(deviceId = "self")), json)
            .also { it.signIn("a@b.c", "pw") }

    @Test
    fun push_active_sessions_sends_user_id_and_interval_columns() = runTest {
        val server = SessionServer()
        signedInEngine(server).pushActiveSessions(
            listOf(ActiveSessionRecord(deviceId = "self", startMillis = 1_000, endMillis = 2_000, updatedAtMillis = 9_000)),
        )

        val body = json.parseToJsonElement(assertNotNull(server.lastUpsert)).jsonArray
        val first = body.first().jsonObject
        assertEquals("user-1", first["user_id"]!!.jsonPrimitive.content)
        assertEquals("self", first["device_id"]!!.jsonPrimitive.content)
        assertEquals(1_000L, first["start_ms"]!!.jsonPrimitive.long)
        assertEquals(2_000L, first["end_ms"]!!.jsonPrimitive.long)
        // updated_at must go out as a bare epoch-millis number (the column is `bigint`, matching the local
        // SQLite INTEGER and device_sleep_gap.recorded_at). A quoted/ISO string here would make Postgres
        // reject the upsert with 22008 — the original 400-on-every-heartbeat bug. isString guards that.
        val updatedAt = first["updated_at"]!!.jsonPrimitive
        assertTrue(!updatedAt.isString, "updated_at must be a JSON number, not a string")
        assertEquals(9_000L, updatedAt.long)
    }

    @Test
    fun push_empty_list_makes_no_request() = runTest {
        val server = SessionServer()
        signedInEngine(server).pushActiveSessions(emptyList())
        assertNull(server.lastUpsert)
    }

    @Test
    fun fetch_derived_pauses_sends_window_args_and_parses_rows() = runTest {
        val server = SessionServer(pauseRows = listOf("""{"start_ms":50,"end_ms":70}""", """{"start_ms":100,"end_ms":140}"""))
        val pauses = assertNotNull(signedInEngine(server).fetchDerivedPauses(sinceMillis = 0, untilMillis = 200))

        val args = json.parseToJsonElement(assertNotNull(server.lastRpcBody)).jsonObject
        assertEquals(0L, args["p_since"]!!.jsonPrimitive.long)
        assertEquals(200L, args["p_until"]!!.jsonPrimitive.long)
        assertEquals(listOf(TaskTimeRange(50, 70), TaskTimeRange(100, 140)), pauses)
    }

    @Test
    fun signed_out_fetch_is_empty_not_null() = runTest {
        val engine = SchedulerSyncEngine(harness(SessionServer()), FakeMetaStore(SyncMeta(deviceId = "self")), json)
        assertEquals(emptyList(), engine.fetchDerivedPauses(0, 100))
    }

    @Test
    fun transport_failure_fetch_is_null() = runTest {
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

                    request.url.encodedPath.endsWith("/rpc/derive_pauses") ->
                        respond("boom", HttpStatusCode.InternalServerError, jsonHeader)

                    else -> respond("", HttpStatusCode.NotFound)
                }
            }
        val sync =
            SchedulerSyncEngine(RemoteSnapshotClient(config, HttpClient(engine)), FakeMetaStore(SyncMeta(deviceId = "self")), json)
                .also { it.signIn("a@b.c", "pw") }
        assertNull(sync.fetchDerivedPauses(0, 100))
    }
}
