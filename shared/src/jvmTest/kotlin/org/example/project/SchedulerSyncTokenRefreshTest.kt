package org.example.project

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.example.project.scheduler.persistence.PersistedSnapshot
import org.example.project.scheduler.persistence.SyncMeta
import org.example.project.scheduler.persistence.SyncMetaStore
import org.example.project.scheduler.sync.RemoteSnapshotClient
import org.example.project.scheduler.sync.SchedulerSyncEngine
import org.example.project.scheduler.sync.SupabaseConfig
import org.example.project.scheduler.sync.SyncState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Regression for the `400 refresh token not found` sync error (Android). Supabase rotates refresh tokens on
 * every use — the previous token is single-use and rejected afterwards. A dirty [SchedulerSyncEngine.reconcile]
 * issues *two* authenticated calls (`fetch` then `update`); when the access token has expired, both hit 401.
 * The old code refreshed each 401 with the same captured session, so the second refresh re-spent the token the
 * first had already rotated → 400. This asserts the reconcile now refreshes once and adopts the rotated token.
 */
class SchedulerSyncTokenRefreshTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val config = SupabaseConfig("https://test.supabase.co", "anon-key")

    private fun snap(tag: String) = PersistedSnapshot(statePayload = tag, history = emptyList(), pointers = emptyList())

    private class FakeMetaStore(private var meta: SyncMeta? = null) : SyncMetaStore {
        override fun loadSyncMeta(): SyncMeta? = meta
        override fun saveSyncMeta(meta: SyncMeta) { this.meta = meta }
    }

    /**
     * Stateful fake of GoTrue + the `scheduler_snapshot` row. [accessExpired] simulates the ~1h access-token
     * lifetime; refresh tokens are strictly single-use ([consumedRefresh]), and re-spending one yields the same
     * `400 refresh token not found` Supabase returns. [refreshNotFoundCount] records any such re-spend.
     */
    private class FakeBackend(var payload: String, var revision: Long) {
        var currentAccess = ""
        var currentRefresh = ""
        var accessExpired = false
        private val consumedRefresh = mutableSetOf<String>()
        private var seq = 0
        var refreshNotFoundCount = 0

        fun issue(): Pair<String, String> {
            seq++
            currentAccess = "at$seq"
            currentRefresh = "rt$seq"
            accessExpired = false
            return currentAccess to currentRefresh
        }
    }

    private fun harness(backend: FakeBackend): RemoteSnapshotClient {
        val jsonHeader = headersOf("Content-Type", "application/json")
        val engine =
            MockEngine { request ->
                val path = request.url.encodedPath
                val body = (request.body as? TextContent)?.text ?: ""
                val bearer = request.headers["Authorization"]?.removePrefix("Bearer ")

                when {
                    path.contains("/auth/v1") -> {
                        if (request.url.parameters["grant_type"] == "refresh_token") {
                            val token = json.parseToJsonElement(body).jsonObject["refresh_token"]!!.jsonPrimitive.content
                            if (token != backend.currentRefresh) {
                                backend.refreshNotFoundCount++
                                respond(
                                    """{"error":"invalid_grant","error_description":"refresh token not found"}""",
                                    HttpStatusCode.BadRequest,
                                    jsonHeader,
                                )
                            } else {
                                val (a, r) = backend.issue()
                                respond(
                                    """{"access_token":"$a","refresh_token":"$r","user":{"id":"user-1"}}""",
                                    HttpStatusCode.OK,
                                    jsonHeader,
                                )
                            }
                        } else {
                            // Password sign-in: seed the first session.
                            val (a, r) = backend.issue()
                            respond(
                                """{"access_token":"$a","refresh_token":"$r","user":{"id":"user-1"}}""",
                                HttpStatusCode.OK,
                                jsonHeader,
                            )
                        }
                    }

                    // Any data call with a stale/expired access token gets a 401 (drives the refresh path).
                    !path.contains("/auth/v1") && (backend.accessExpired || bearer != backend.currentAccess) ->
                        respond("""{"message":"JWT expired"}""", HttpStatusCode.Unauthorized, jsonHeader)

                    path.endsWith("/scheduler_snapshot") && request.method == HttpMethod.Get ->
                        respond(
                            """[{"payload":${json.encodeToString(backend.payload)},"revision":${backend.revision}}]""",
                            HttpStatusCode.OK,
                            jsonHeader,
                        )

                    path.endsWith("/scheduler_snapshot") && request.method == HttpMethod.Patch -> {
                        val expected = request.url.parameters["revision"]?.removePrefix("eq.")?.toLong()
                        if (backend.revision == expected) {
                            backend.payload = json.parseToJsonElement(body).jsonObject["payload"]!!.jsonPrimitive.content
                            backend.revision += 1
                            respond(
                                """[{"payload":${json.encodeToString(backend.payload)},"revision":${backend.revision}}]""",
                                HttpStatusCode.OK,
                                jsonHeader,
                            )
                        } else {
                            respond("[]", HttpStatusCode.OK, jsonHeader)
                        }
                    }

                    else -> respond("", HttpStatusCode.NotFound)
                }
            }
        return RemoteSnapshotClient(config, HttpClient(engine))
    }

    @Test
    fun dirty_reconcile_with_expired_access_token_refreshes_once_and_pushes() = runTest {
        val backend = FakeBackend(payload = json.encodeToString(snap("OLD")), revision = 2)
        val meta = FakeMetaStore(SyncMeta(deviceId = "d", lastKnownRevision = 2, dirty = true))
        val sync = SchedulerSyncEngine(harness(backend), meta, json).apply { bind({ snap("NEW") }, {}) }

        sync.signIn("a@b.c", "pw")
        backend.accessExpired = true // the ~1h access token lapsed before this reconcile
        sync.reconcile()

        // The reconcile succeeded (no "refresh token not found") and pushed the dirty local snapshot.
        assertIs<SyncState.Idle>(sync.state.value)
        assertEquals(0, backend.refreshNotFoundCount)
        assertEquals("NEW", json.decodeFromString<PersistedSnapshot>(backend.payload).statePayload)
        assertEquals(3, backend.revision)
        assertEquals(false, meta.loadSyncMeta()!!.dirty)
        // The rotated refresh token is persisted so the next run starts from a live token.
        assertEquals(backend.currentRefresh, meta.loadSyncMeta()!!.refreshToken)
    }
}
