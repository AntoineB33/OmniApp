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
import org.example.project.scheduler.persistence.SleepGapRecord
import org.example.project.scheduler.persistence.SyncMeta
import org.example.project.scheduler.persistence.SyncMetaStore
import org.example.project.scheduler.sync.RemoteSnapshotClient
import org.example.project.scheduler.sync.SchedulerSyncEngine
import org.example.project.scheduler.sync.SupabaseConfig

/**
 * Exercises PRD §15 device-sleep gaps through [SchedulerSyncEngine]'s
 * [SleepGapGateway][org.example.project.scheduler.sync.SleepGapGateway] impl against an in-memory fake of
 * Supabase (GoTrue auth + the `device_sleep_gap` rows) driven by Ktor's [MockEngine]. Covers the push body
 * shape (the device's own user id + exact interval columns), fetch parsing, and the signed-out / transport
 * failure distinction (empty list vs null).
 */
class DeviceSleepGapTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val config = SupabaseConfig("https://test.supabase.co", "anon-key")

    private class FakeMetaStore(private var meta: SyncMeta? = null) : SyncMetaStore {
        override fun loadSyncMeta(): SyncMeta? = meta

        override fun saveSyncMeta(meta: SyncMeta) {
            this.meta = meta
        }
    }

    private class GapServer(var rows: List<String> = emptyList(), var lastUpsert: String? = null)

    private fun row(deviceId: String, start: Long, end: Long, recorded: Long) =
        """{"device_id":"$deviceId","sleep_start":$start,"sleep_end":$end,"recorded_at":$recorded}"""

    private fun harness(server: GapServer): RemoteSnapshotClient {
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

                    path.endsWith("/device_sleep_gap") && request.method == HttpMethod.Get ->
                        respond("[${server.rows.joinToString(",")}]", HttpStatusCode.OK, jsonHeader)

                    path.endsWith("/device_sleep_gap") && request.method == HttpMethod.Post -> {
                        server.lastUpsert = (request.body as? TextContent)?.text ?: ""
                        respond("", HttpStatusCode.Created, jsonHeader)
                    }

                    else -> respond("", HttpStatusCode.NotFound)
                }
            }
        return RemoteSnapshotClient(config, HttpClient(engine))
    }

    private suspend fun signedInEngine(server: GapServer): SchedulerSyncEngine =
        SchedulerSyncEngine(harness(server), FakeMetaStore(SyncMeta(deviceId = "self")), json)
            .also { it.signIn("a@b.c", "pw") }

    @Test
    fun push_sleep_gaps_sends_user_id_and_exact_columns() = runTest {
        val server = GapServer()
        signedInEngine(server).pushSleepGaps(
            listOf(SleepGapRecord(deviceId = "self", startMillis = 1_000, endMillis = 2_000, recordedAtMillis = 9_000)),
        )

        val body = json.parseToJsonElement(assertNotNull(server.lastUpsert)).jsonArray
        val first = body.first().jsonObject
        assertEquals("user-1", first["user_id"]!!.jsonPrimitive.content)
        assertEquals("self", first["device_id"]!!.jsonPrimitive.content)
        assertEquals(1_000L, first["sleep_start"]!!.jsonPrimitive.long)
        assertEquals(2_000L, first["sleep_end"]!!.jsonPrimitive.long)
    }

    @Test
    fun push_empty_list_makes_no_request() = runTest {
        val server = GapServer()
        signedInEngine(server).pushSleepGaps(emptyList())
        assertNull(server.lastUpsert)
    }

    @Test
    fun fetch_sleep_gaps_parses_all_devices() = runTest {
        val server = GapServer(listOf(row("desk", 1_000, 2_000, 9_000), row("phone", 5_000, 6_000, 9_500)))
        val gaps = assertNotNull(signedInEngine(server).fetchSleepGaps())
        assertEquals(2, gaps.size)
        assertTrue(gaps.contains(SleepGapRecord("desk", 1_000, 2_000, 9_000)))
        assertTrue(gaps.contains(SleepGapRecord("phone", 5_000, 6_000, 9_500)))
    }

    @Test
    fun signed_out_fetch_is_empty_not_null() = runTest {
        val engine = SchedulerSyncEngine(harness(GapServer()), FakeMetaStore(SyncMeta(deviceId = "self")), json)
        assertEquals(emptyList(), engine.fetchSleepGaps())
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

                    request.url.encodedPath.endsWith("/device_sleep_gap") && request.method == HttpMethod.Get ->
                        respond("boom", HttpStatusCode.InternalServerError, jsonHeader)

                    else -> respond("", HttpStatusCode.NotFound)
                }
            }
        val sync =
            SchedulerSyncEngine(RemoteSnapshotClient(config, HttpClient(engine)), FakeMetaStore(SyncMeta(deviceId = "self")), json)
                .also { it.signIn("a@b.c", "pw") }
        assertNull(sync.fetchSleepGaps())
    }
}
