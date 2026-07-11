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
import org.example.project.scheduler.persistence.PersistedSnapshot
import org.example.project.scheduler.persistence.SchedulerStore
import org.example.project.scheduler.persistence.SyncMeta
import org.example.project.scheduler.persistence.SyncMetaStore
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.sync.RemoteSnapshotClient
import org.example.project.scheduler.sync.SchedulerSyncEngine
import org.example.project.scheduler.sync.SupabaseConfig
import org.example.project.scheduler.ui.TaskSchedulerViewModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ARCHITECTURE.md §8: logging out is part of the login/logout sync moment — a user-initiated sign-out must
 * run one **farewell reconcile** (push pending local changes / pull a newer remote) BEFORE dropping the
 * session, so an edit made right before signing out reaches the server instead of stranding locally until
 * some later login. Regression guard: [TaskSchedulerViewModel.signOut] used to drop the session immediately,
 * silently discarding the pending 10-s debounced push. The farewell is best-effort: an offline reconcile
 * must still sign the device out.
 *
 * Like [TaskSchedulerAutoLoginTest], sign-out is fire-and-forget (a coroutine off the save dispatcher), so
 * these run on a real dispatcher and await the observable result rather than virtual-time stepping a
 * launched-then-suspended HTTP call.
 */
class SignOutFarewellSyncTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val config = SupabaseConfig("https://test.supabase.co", "anon-key")

    private class FakeMetaStore(private var meta: SyncMeta? = null) : SyncMetaStore {
        @Synchronized override fun loadSyncMeta(): SyncMeta? = meta

        @Synchronized override fun saveSyncMeta(meta: SyncMeta) {
            this.meta = meta
        }
    }

    /** In-memory local store so the ViewModel's debounced-save/flush machinery actually runs. */
    private class MemoryStore : SchedulerStore {
        @Volatile private var snapshot: PersistedSnapshot? = null

        override fun load(): PersistedSnapshot? = snapshot

        override fun save(snapshot: PersistedSnapshot) {
            this.snapshot = snapshot
        }
    }

    /** Mutable server-side `scheduler_snapshot` row; [failData] makes every data endpoint 404 (offline-ish). */
    private class FakeServer(
        @Volatile var payload: String? = null,
        @Volatile var revision: Long = 0,
        @Volatile var failData: Boolean = false,
    )

    private fun client(server: FakeServer): RemoteSnapshotClient {
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

                    server.failData -> respond("", HttpStatusCode.NotFound)

                    // No account_logout marker: the farewell reconcile must not mistake sign-out for a
                    // remote force-logout.
                    path.endsWith("/account_logout") && request.method == HttpMethod.Get ->
                        respond("[]", HttpStatusCode.OK, jsonHeader)

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

    /** Polls [cond] up to [timeoutMillis], returning whether it became true. */
    private fun awaitUntil(timeoutMillis: Long = 5_000, cond: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return true
            Thread.sleep(10)
        }
        return cond()
    }

    /** Boots a signed-in ViewModel (auto-login) and waits for its startup reconcile to seed the server. */
    private fun signedInViewModel(server: FakeServer, meta: FakeMetaStore): TaskSchedulerViewModel {
        val vm =
            TaskSchedulerViewModel(
                store = MemoryStore(),
                saveDispatcher = Dispatchers.Default,
                syncEngine = SchedulerSyncEngine(client(server), meta, json),
                startupLogin = { org.example.project.scheduler.sync.StartupLogin("account1", "pw") },
            )
        assertTrue(awaitUntil { server.revision == 1L }, "startup login + seed reconcile did not complete")
        return vm
    }

    @Test
    fun sign_out_pushes_the_pending_change_before_dropping_the_session() {
        val server = FakeServer()
        val meta = FakeMetaStore()
        val vm = signedInViewModel(server, meta)

        // An authoritative change, then sign out while it still sits inside BOTH debounce windows (the 400 ms
        // local save and the 10 s server push). The farewell reconcile must carry it out — well before the
        // 10 s debounce could ever have fired.
        vm.dispatch(SchedulerIntent.SetAutomaticSchedule(enabled = false))
        vm.signOut()

        assertTrue(awaitUntil { server.revision == 2L }, "sign-out did not push the pending change")
        assertTrue(awaitUntil { meta.loadSyncMeta()?.userId == null }, "sign-out did not drop the session")
        assertEquals(false, meta.loadSyncMeta()!!.dirty, "the farewell push must clear the dirty flag")
    }

    @Test
    fun sign_out_still_signs_out_when_the_farewell_reconcile_fails() {
        val server = FakeServer()
        val meta = FakeMetaStore()
        val vm = signedInViewModel(server, meta)

        vm.dispatch(SchedulerIntent.SetAutomaticSchedule(enabled = false))
        server.failData = true // offline-ish: every data endpoint now 404s
        vm.signOut()

        assertTrue(awaitUntil { meta.loadSyncMeta()?.userId == null }, "an offline sign-out must still sign out")
        assertEquals(1, server.revision, "nothing could have been pushed")
        // The change stays flagged so the next login's reconcile sends it (best-effort farewell).
        assertEquals(true, meta.loadSyncMeta()!!.dirty)
    }

    @Test
    fun sign_out_with_nothing_pending_still_reconciles_then_signs_out() {
        val server = FakeServer()
        val meta = FakeMetaStore()
        val vm = signedInViewModel(server, meta)

        vm.signOut()

        assertTrue(awaitUntil { meta.loadSyncMeta()?.userId == null }, "sign-out did not drop the session")
        assertEquals(1, server.revision, "no authoritative change — the farewell must not bump the revision")
        assertNull(meta.loadSyncMeta()!!.accessToken)
    }
}
