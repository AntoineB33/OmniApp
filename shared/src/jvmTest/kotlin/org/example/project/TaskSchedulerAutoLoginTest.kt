package org.example.project

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.example.project.scheduler.persistence.SyncMeta
import org.example.project.scheduler.persistence.SyncMetaStore
import org.example.project.scheduler.sync.RemoteSnapshotClient
import org.example.project.scheduler.sync.SchedulerSyncEngine
import org.example.project.scheduler.sync.StartupLogin
import org.example.project.scheduler.sync.SupabaseConfig
import org.example.project.scheduler.ui.TaskSchedulerViewModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The per-account `/scripts` launch the app with credentials so it opens already signed in. This verifies
 * [TaskSchedulerViewModel] consumes its injected startup credentials: when no session is cached it signs in
 * (synthesizing the email from the username) — and that with no credentials it never touches auth.
 *
 * Sync is BUTTON-ONLY: signing in does NOT reconcile, so nothing is pushed/pulled at launch (the remote is
 * seeded only when the user presses Sync). The assertion therefore observes the completed sign-in, not a seed.
 *
 * Auto-login is fire-and-forget (a coroutine off the save dispatcher), so these run it on a real dispatcher
 * and await the observable result rather than virtual-time stepping a launched-then-suspended HTTP call.
 */
class TaskSchedulerAutoLoginTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val config = SupabaseConfig("https://test.supabase.co", "anon-key")

    private class FakeMetaStore(private var meta: SyncMeta? = null) : SyncMetaStore {
        override fun loadSyncMeta(): SyncMeta? = meta

        @Synchronized override fun saveSyncMeta(meta: SyncMeta) {
            this.meta = meta
        }
    }

    /** Mutable server row plus a record of the email GoTrue last authenticated, for the synth-email assert. */
    private class FakeServer(@Volatile var revision: Long = 0, @Volatile var lastEmail: String? = null) {
        @Volatile var payload: String? = null
    }

    private fun client(server: FakeServer): RemoteSnapshotClient {
        val engine =
            MockEngine { request ->
                val path = request.url.encodedPath
                val body = (request.body as? TextContent)?.text ?: ""
                val jsonHeader = headersOf("Content-Type", "application/json")
                when {
                    path.startsWith("/auth/v1") -> {
                        server.lastEmail = json.parseToJsonElement(body).jsonObject["email"]?.jsonPrimitive?.content
                        respond(
                            """{"access_token":"at","refresh_token":"rt","user":{"id":"user-1"}}""",
                            HttpStatusCode.OK,
                            jsonHeader,
                        )
                    }

                    path.endsWith("/scheduler_snapshot") && request.method == HttpMethod.Get -> {
                        val arr =
                            server.payload?.let { """[{"payload":${json.encodeToString(it)},"revision":${server.revision}}]""" }
                                ?: "[]"
                        respond(arr, HttpStatusCode.OK, jsonHeader)
                    }

                    path.endsWith("/scheduler_snapshot") && request.method == HttpMethod.Post -> {
                        server.payload = json.parseToJsonElement(body).jsonObject["payload"]!!.jsonPrimitive.content
                        server.revision = 1
                        respond("", HttpStatusCode.Created, jsonHeader)
                    }

                    else -> respond("", HttpStatusCode.NotFound)
                }
            }
        return RemoteSnapshotClient(config, HttpClient(engine))
    }

    /** Polls [cond] up to [timeoutMillis], returning whether it became true. */
    private fun awaitUntil(timeoutMillis: Long = 5_000, cond: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return true
            Thread.sleep(10)
        }
        return cond()
    }

    @Test
    fun signs_in_with_synthesized_email_when_credentials_supplied() {
        val server = FakeServer()
        val meta = FakeMetaStore()
        val sync = SchedulerSyncEngine(client(server), meta, json)
        TaskSchedulerViewModel(
            store = null,
            saveDispatcher = Dispatchers.Default,
            syncEngine = sync,
            startupLogin = { StartupLogin("account1", "pw") },
        )

        // Sign-in completes (userId cached) but does NOT reconcile — the remote stays unseeded (button-only).
        assertEquals(true, awaitUntil { meta.loadSyncMeta()?.userId == "user-1" }, "auto-login did not complete")
        assertEquals("account1@omniapp.local", server.lastEmail)
        assertEquals(0L, server.revision, "sign-in must not push/seed the remote (sync is button-only)")
    }

    @Test
    fun stays_signed_out_when_no_credentials() {
        val server = FakeServer()
        val meta = FakeMetaStore()
        val sync = SchedulerSyncEngine(client(server), meta, json)
        TaskSchedulerViewModel(
            store = null,
            saveDispatcher = Dispatchers.Default,
            syncEngine = sync,
            startupLogin = { null },
        )

        // Give any stray async work a chance to run, then confirm auth was never hit.
        Thread.sleep(300)
        assertNull(server.lastEmail)
        assertNull(meta.loadSyncMeta()!!.userId)
    }
}
