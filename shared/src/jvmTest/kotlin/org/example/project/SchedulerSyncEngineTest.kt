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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Exercises [SchedulerSyncEngine]'s Phase 1 whole-document last-write-wins reconcile against a stateful
 * in-memory fake of Supabase (GoTrue auth + the single `scheduler_snapshot` row) driven through Ktor's
 * [MockEngine]. Covers: first-device seed (insert), pull-when-remote-newer, push-when-dirty, and the
 * conflict case where the remote advanced past what this device last saw.
 */
class SchedulerSyncEngineTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val config = SupabaseConfig("https://test.supabase.co", "anon-key")

    /** A snapshot tagged by its [statePayload] so tests can assert which one round-tripped. */
    private fun snap(tag: String) = PersistedSnapshot(statePayload = tag, history = emptyList(), pointers = emptyList())

    private class FakeMetaStore(private var meta: SyncMeta? = null) : SyncMetaStore {
        override fun loadSyncMeta(): SyncMeta? = meta

        override fun saveSyncMeta(meta: SyncMeta) {
            this.meta = meta
        }
    }

    /** Mutable server-side row: the stored payload (a serialized [PersistedSnapshot]) and its revision. */
    private class FakeServer(var payload: String? = null, var revision: Long = 0)

    private fun harness(server: FakeServer): RemoteSnapshotClient {
        val engine =
            MockEngine { request ->
                val path = request.url.encodedPath
                val body = (request.body as? TextContent)?.text ?: ""
                val jsonHeader = headersOf("Content-Type", "application/json")
                when {
                    path.startsWith("/auth/v1") ->
                        respond(
                            """{"access_token":"at","refresh_token":"rt","user":{"id":"user-1"}}""",
                            HttpStatusCode.OK,
                            jsonHeader,
                        )

                    path.endsWith("/scheduler_snapshot") && request.method == HttpMethod.Get -> {
                        val arr =
                            server.payload?.let { """[{"payload":${json.encodeToString(it)},"revision":${server.revision}}]""" }
                                ?: "[]"
                        respond(arr, HttpStatusCode.OK, jsonHeader)
                    }

                    path.endsWith("/scheduler_snapshot") && request.method == HttpMethod.Post -> {
                        if (server.payload != null) {
                            respond("", HttpStatusCode.Conflict, jsonHeader)
                        } else {
                            server.payload = json.parseToJsonElement(body).jsonObject["payload"]!!.jsonPrimitive.content
                            server.revision = 1
                            respond("", HttpStatusCode.Created, jsonHeader)
                        }
                    }

                    path.endsWith("/scheduler_snapshot") && request.method == HttpMethod.Patch -> {
                        val expected = request.url.parameters["revision"]?.removePrefix("eq.")?.toLong()
                        if (server.payload != null && server.revision == expected) {
                            server.payload = json.parseToJsonElement(body).jsonObject["payload"]!!.jsonPrimitive.content
                            server.revision += 1
                            respond(
                                """[{"payload":${json.encodeToString(server.payload!!)},"revision":${server.revision}}]""",
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

    private fun engine(
        client: RemoteSnapshotClient,
        meta: FakeMetaStore,
        local: () -> PersistedSnapshot,
        applied: (PersistedSnapshot) -> Unit,
    ): SchedulerSyncEngine =
        SchedulerSyncEngine(client, meta, json).apply { bind(local, applied) }

    @Test
    fun first_device_seeds_remote_via_insert() = runTest {
        val server = FakeServer()
        val meta = FakeMetaStore()
        var applied: PersistedSnapshot? = null
        val sync = engine(harness(server), meta, { snap("A") }, { applied = it })

        sync.signIn("a@b.c", "pw")
        sync.reconcile()

        assertEquals("A", json.decodeFromString<PersistedSnapshot>(server.payload!!).statePayload)
        assertEquals(1, server.revision)
        assertEquals(1, meta.loadSyncMeta()!!.lastKnownRevision)
        assertEquals(false, meta.loadSyncMeta()!!.dirty)
        assertNull(applied) // we pushed; nothing pulled
    }

    @Test
    fun pulls_when_remote_is_newer() = runTest {
        val server = FakeServer(payload = json.encodeToString(snap("REMOTE")), revision = 5)
        val meta = FakeMetaStore(SyncMeta(deviceId = "d", lastKnownRevision = 1))
        var applied: PersistedSnapshot? = null
        val sync = engine(harness(server), meta, { snap("LOCAL") }, { applied = it })

        sync.signIn("a@b.c", "pw")
        sync.reconcile()

        assertEquals("REMOTE", applied!!.statePayload)
        assertEquals(5, meta.loadSyncMeta()!!.lastKnownRevision)
        assertEquals(5, server.revision) // remote untouched
    }

    @Test
    fun pushes_local_changes_when_dirty() = runTest {
        val server = FakeServer(payload = json.encodeToString(snap("OLD")), revision = 2)
        val meta = FakeMetaStore(SyncMeta(deviceId = "d", lastKnownRevision = 2, dirty = true))
        var applied: PersistedSnapshot? = null
        val sync = engine(harness(server), meta, { snap("NEW") }, { applied = it })

        sync.signIn("a@b.c", "pw")
        sync.reconcile()

        assertEquals("NEW", json.decodeFromString<PersistedSnapshot>(server.payload!!).statePayload)
        assertEquals(3, server.revision)
        assertEquals(3, meta.loadSyncMeta()!!.lastKnownRevision)
        assertEquals(false, meta.loadSyncMeta()!!.dirty)
        assertNull(applied)
    }

    @Test
    fun conflict_remote_wins_and_local_change_is_dropped() = runTest {
        // We think we're at revision 2 with a dirty local edit, but the remote already moved to 4.
        val server = FakeServer(payload = json.encodeToString(snap("REMOTE_WINS")), revision = 4)
        val meta = FakeMetaStore(SyncMeta(deviceId = "d", lastKnownRevision = 2, dirty = true))
        var applied: PersistedSnapshot? = null
        val sync = engine(harness(server), meta, { snap("LOCAL_LOSES") }, { applied = it })

        sync.signIn("a@b.c", "pw")
        sync.reconcile()

        assertEquals("REMOTE_WINS", applied!!.statePayload)
        assertEquals(4, meta.loadSyncMeta()!!.lastKnownRevision)
        assertEquals(false, meta.loadSyncMeta()!!.dirty)
        assertEquals(4, server.revision) // local edit never pushed
    }
}
