package org.example.project

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import org.example.project.scheduler.persistence.SyncMeta
import org.example.project.scheduler.persistence.SyncMetaStore
import org.example.project.scheduler.sync.RemoteSnapshotClient
import org.example.project.scheduler.sync.SchedulerSyncEngine
import org.example.project.scheduler.sync.SupabaseConfig

/**
 * The unified sync moments ([SchedulerSyncEngine.syncMoments]): every [SchedulerSyncEngine.reconcile] —
 * startup, login completion, the manual sync button, the debounced-change flush all funnel through it —
 * must emit a moment for the engine's side channels (push own active sessions, pull derived pauses/gaps),
 * so a channel can never miss a trigger the snapshot ran on. Regression guard for the fresh-credential
 * launch anomaly: the engine's startup push raced the async auto-login and was silently dropped, and no
 * later moment retried it, so a peer derived an Inactivity band extending past this device's real activity
 * start. The moment must fire on every reconcile outcome (signed-out and transport-error included — the
 * side channels have their own fallbacks) and must REPLAY to a collector that subscribes after the
 * reconcile finished (the engine's collector starts on `engine.start()`, which races the ViewModel's
 * startup reconcile).
 */
class SyncMomentsTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val config = SupabaseConfig("https://test.supabase.co", "anon-key")

    private class FakeMetaStore(private var meta: SyncMeta? = null) : SyncMetaStore {
        override fun loadSyncMeta(): SyncMeta? = meta

        override fun saveSyncMeta(meta: SyncMeta) {
            this.meta = meta
        }
    }

    // Auth succeeds; every data endpoint 404s (reconcile surfaces a transport error, the worst-case path).
    private fun engine(): SchedulerSyncEngine {
        val mock =
            MockEngine { request ->
                if (request.url.encodedPath.startsWith("/auth/v1")) {
                    respond(
                        """{"access_token":"at","refresh_token":"rt","user":{"id":"user-1"}}""",
                        HttpStatusCode.OK,
                        headersOf("Content-Type", "application/json"),
                    )
                } else {
                    respond("", HttpStatusCode.NotFound)
                }
            }
        return SchedulerSyncEngine(
            RemoteSnapshotClient(config, HttpClient(mock)),
            FakeMetaStore(SyncMeta(deviceId = "self")),
            json,
        )
    }

    @Test
    fun no_sync_moment_before_the_first_reconcile() = runTest {
        val engine = engine()
        assertNull(withTimeoutOrNull(1_000) { engine.syncMoments.first() })
    }

    @Test
    fun reconcile_emits_a_sync_moment_even_when_signed_out() = runTest {
        // Signed out, reconcile returns early — but the moment still fires so the side channels run their
        // local fallbacks (e.g. the manual sync button re-derives the Inactivity bands from own sessions).
        val engine = engine()
        engine.reconcile()
        assertNotNull(withTimeoutOrNull(1_000) { engine.syncMoments.first() })
    }

    @Test
    fun a_late_collector_still_observes_a_signed_in_reconcile_moment() = runTest {
        // Sign in, reconcile (data endpoints 404 -> transport error), THEN subscribe: replay must hand the
        // late collector the moment. This is the startup shape — the engine's collector starts on
        // engine.start(), which can lose the race against the ViewModel's async login + reconcile.
        val engine = engine()
        engine.signIn("a@b.c", "pw")
        engine.reconcile()
        assertNotNull(withTimeoutOrNull(1_000) { engine.syncMoments.first() })
    }
}
