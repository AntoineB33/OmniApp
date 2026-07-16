package org.example.project

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Instant
import org.example.project.scheduler.engine.SchedulerEngine
import org.example.project.scheduler.persistence.ActiveSessionRecord
import org.example.project.scheduler.persistence.ActiveSessionStore
import org.example.project.scheduler.persistence.SyncMeta
import org.example.project.scheduler.persistence.SyncMetaStore
import org.example.project.scheduler.platform.DeviceKind
import org.example.project.scheduler.sync.RemoteSnapshotClient
import org.example.project.scheduler.sync.SchedulerSyncEngine
import org.example.project.scheduler.sync.SupabaseConfig
import org.example.project.scheduler.ui.TaskSchedulerViewModel
import org.example.project.time.AppClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * PRD §15 active sessions under the button-only sync model: the Sync-button reconcile is the ONLY channel —
 * it pushes this device's own rows (never the signed-out `local` / legacy `remote-activity` rows) and pulls
 * every peer's into the local store, so the calendar can show which devices were open during past panels.
 * Also covers the phone's foreground activity model: each beat claims a one-minute lease (`[now, now+1min]`)
 * while the desktop claims only what its beat observed.
 */
class ActiveSessionSyncTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val config = SupabaseConfig("https://test.supabase.co", "anon-key")

    private class FakeMetaStore(private var meta: SyncMeta? = null) : SyncMetaStore {
        override fun loadSyncMeta(): SyncMeta? = meta

        override fun saveSyncMeta(meta: SyncMeta) {
            this.meta = meta
        }
    }

    private class FakeSessionStore : ActiveSessionStore {
        val rows = LinkedHashMap<Pair<String, Long>, ActiveSessionRecord>()

        override fun loadActiveSessions(): List<ActiveSessionRecord> = rows.values.sortedBy { it.startMillis }

        override fun saveActiveSessions(records: List<ActiveSessionRecord>) {
            records.forEach { rows[it.deviceId to it.startMillis] = it }
        }

        override fun deleteActiveSessionsForDevice(deviceId: String) {
            rows.keys.removeAll { it.first == deviceId }
        }
    }

    /** The remote table + a capture of what the client pushed there ([pushedDeviceIds]). */
    private class FakeServer(
        var sessionRowsJson: String = "[]",
        var logoutAtMillis: Long? = null,
    ) {
        val pushedDeviceIds = mutableListOf<String>()
        var sessionRequests = 0
    }

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

                    path.endsWith("/account_logout") ->
                        respond(
                            server.logoutAtMillis?.let { """[{"logout_at":"${Instant.fromEpochMilliseconds(it)}"}]""" }
                                ?: "[]",
                            HttpStatusCode.OK,
                            jsonHeader,
                        )

                    path.endsWith("/scheduler_snapshot") && request.method == HttpMethod.Get ->
                        respond("[]", HttpStatusCode.OK, jsonHeader)

                    path.endsWith("/scheduler_snapshot") && request.method == HttpMethod.Post ->
                        respond("", HttpStatusCode.Created, jsonHeader)

                    path.endsWith("/device_active_session") && request.method == HttpMethod.Post -> {
                        server.sessionRequests++
                        json.parseToJsonElement(body).jsonArray.forEach {
                            server.pushedDeviceIds.add(it.jsonObject["device_id"]!!.jsonPrimitive.content)
                        }
                        respond("", HttpStatusCode.Created, jsonHeader)
                    }

                    path.endsWith("/device_active_session") && request.method == HttpMethod.Get -> {
                        server.sessionRequests++
                        respond(server.sessionRowsJson, HttpStatusCode.OK, jsonHeader)
                    }

                    else -> respond("", HttpStatusCode.NotFound)
                }
            }
        return RemoteSnapshotClient(config, HttpClient(engine))
    }

    private fun snap() =
        org.example.project.scheduler.persistence.PersistedSnapshot("s", emptyList(), emptyList())

    @Test
    fun reconcile_pushes_only_own_rows_and_pulls_peers_into_the_store() = runTest {
        val server = FakeServer(
            sessionRowsJson =
                """[{"device_id":"peer","start_ms":100,"end_ms":200,"updated_at":250,"kind":"phone"},""" +
                    """{"device_id":"self","start_ms":10,"end_ms":20,"updated_at":25,"kind":"desktop"}]""",
        )
        val store = FakeSessionStore()
        store.saveActiveSessions(
            listOf(
                ActiveSessionRecord("self", 10, 30, 35, "desktop"),
                // Signed-out-era and legacy adoption rows belong to no peer and must never be pushed.
                ActiveSessionRecord("local", 1, 2, 3, ""),
                ActiveSessionRecord(SchedulerEngine.REMOTE_ACTIVITY_DEVICE_ID, 4, 5, 6, ""),
            ),
        )
        val meta = FakeMetaStore(SyncMeta(deviceId = "self"))
        val sync = SchedulerSyncEngine(harness(server), meta, json, activeSessionStore = store)
            .apply { bind({ snap() }, {}) }

        sync.signIn("a@b.c", "pw")
        sync.reconcile()

        assertEquals(listOf("self"), server.pushedDeviceIds, "only this install's own rows are pushed")
        val peer = store.rows["peer" to 100L]!!
        assertEquals(200L, peer.endMillis)
        assertEquals("phone", peer.kind, "the pulled peer row carries its device kind for the bubble label")
        // The server's echo of our own row must not clobber the fresher local one.
        assertEquals(30L, store.rows["self" to 10L]!!.endMillis)
    }

    @Test
    fun remote_force_logout_skips_the_session_channel_entirely() = runTest {
        val server = FakeServer()
        val store = FakeSessionStore()
        store.saveActiveSessions(listOf(ActiveSessionRecord("self", 10, 30, 35, "desktop")))
        val meta = FakeMetaStore(SyncMeta(deviceId = "self"))
        val sync = SchedulerSyncEngine(harness(server), meta, json, activeSessionStore = store)
            .apply { bind({ snap() }, {}) }

        sync.signIn("a@b.c", "pw")
        server.logoutAtMillis = 5_000L // account emptied after login
        sync.reconcile()

        assertEquals(0, server.sessionRequests, "a force-logged-out reconcile must push/pull nothing")
    }

    // ---- The phone's foreground one-minute lease vs. the desktop's observed-only beat ----

    private class FixedClock(var now: Long) : AppClock {
        override fun nowMillis(): Long = now
    }

    private fun engine(
        kind: DeviceKind,
        store: FakeSessionStore,
        clock: AppClock,
        scope: CoroutineScope,
    ): SchedulerEngine =
        SchedulerEngine(
            vm = TaskSchedulerViewModel(store = null, saveDispatcher = Dispatchers.Default),
            clock = clock,
            scope = scope,
            deviceKind = kind,
            screenActive = { true },
            activeSessionStore = store,
        )

    @Test
    fun phone_beat_claims_a_one_minute_foreground_lease() = runTest {
        val store = FakeSessionStore()
        val clock = FixedClock(1_000_000)
        val engine = engine(DeviceKind.Phone, store, clock, CoroutineScope(Dispatchers.Unconfined))

        engine.heartbeatSampleForTest(active = true, suspended = false)
        val opened = store.rows.values.single()
        assertEquals(1_000_000L, opened.startMillis)
        assertEquals(1_060_000L, opened.endMillis, "the phone claims [now, now + 1 min]")
        assertEquals("phone", opened.kind)

        // The next renewal (a minute later) extends the SAME row's lease — one continuous session.
        clock.now = 1_060_000
        engine.heartbeatSampleForTest(active = true, suspended = false)
        val extended = store.rows.values.single()
        assertEquals(1_000_000L, extended.startMillis)
        assertEquals(1_120_000L, extended.endMillis)

        // Backgrounding the app finalizes the session at its already-claimed lease end.
        clock.now = 1_090_000
        engine.heartbeatSampleForTest(active = false, suspended = false)
        assertEquals(1_120_000L, store.rows.values.single().endMillis)
        assertTrue(engine.activeSince.value == null, "no open session while backgrounded")
    }

    @Test
    fun desktop_beat_claims_only_the_observed_instant() = runTest {
        val store = FakeSessionStore()
        val clock = FixedClock(500_000)
        val engine = engine(DeviceKind.Desktop, store, clock, CoroutineScope(Dispatchers.Unconfined))

        engine.heartbeatSampleForTest(active = true, suspended = false)
        val row = store.rows.values.single()
        assertEquals(500_000L, row.startMillis)
        assertEquals(500_000L, row.endMillis, "no lease on the desktop — the beat records what it saw")
        assertEquals("desktop", row.kind)
    }
}
