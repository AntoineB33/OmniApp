package org.example.project

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.TaskPanel
import org.example.project.scheduler.persistence.PersistedSnapshot
import org.example.project.scheduler.persistence.SchedulerStateCodec
import org.example.project.scheduler.persistence.SchedulerStore
import org.example.project.scheduler.persistence.SyncMeta
import org.example.project.scheduler.persistence.SyncMetaStore
import org.example.project.scheduler.state.AppWindow
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerSelection
import org.example.project.scheduler.state.SchedulerState
import org.example.project.scheduler.sync.RemoteSnapshotClient
import org.example.project.scheduler.sync.SchedulerSyncEngine
import org.example.project.scheduler.sync.SupabaseConfig
import org.example.project.scheduler.ui.TaskSchedulerViewModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Bandwidth rule (ARCHITECTURE.md §8 / CLAUDE.md reconstructibility): the snapshot that goes **over the
 * wire** is the authoritative projection — task tree, records, pinned/user panels, reminders, sleep,
 * settings, history — with the regenerated auto/side/sleep panels stripped and the per-device view state
 * neutralized. Every device re-derives/keeps those locally, so pushing them would only spend bandwidth on
 * data the puller discards. The flip side (the persisted-DB compatibility rule): a snapshot that *lacks*
 * the derived panels must still load and render — the next reschedule refills them.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncPayloadTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val config = SupabaseConfig("https://test.supabase.co", "anon-key")

    private class NullStore : SchedulerStore {
        override fun load(): PersistedSnapshot? = null
        override fun save(snapshot: PersistedSnapshot) {}
    }

    private class FakeMetaStore(private var meta: SyncMeta? = null) : SyncMetaStore {
        override fun loadSyncMeta(): SyncMeta? = meta
        override fun saveSyncMeta(meta: SyncMeta) { this.meta = meta }
    }

    /** Captures the payload of the first snapshot INSERT the client sends; everything else is minimal. */
    private class CapturingServer {
        var insertedPayload: String? = null
    }

    private fun client(server: CapturingServer): RemoteSnapshotClient {
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            val jsonHeader = headersOf("Content-Type", "application/json")
            when {
                path.startsWith("/auth/v1") ->
                    respond(
                        """{"access_token":"at","refresh_token":"rt","user":{"id":"user-1"}}""",
                        HttpStatusCode.OK,
                        jsonHeader,
                    )
                path.endsWith("/account_logout") -> respond("[]", HttpStatusCode.OK, jsonHeader)
                path.endsWith("/scheduler_snapshot") && request.method == HttpMethod.Get ->
                    respond("[]", HttpStatusCode.OK, jsonHeader)
                path.endsWith("/scheduler_snapshot") && request.method == HttpMethod.Post -> {
                    val body = (request.body as? TextContent)?.text ?: ""
                    server.insertedPayload =
                        json.parseToJsonElement(body).jsonObject["payload"]!!.jsonPrimitive.content
                    respond("", HttpStatusCode.Created, jsonHeader)
                }
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        return RemoteSnapshotClient(config, HttpClient(engine))
    }

    /** A state carrying every kind of non-authoritative baggage plus one authoritative pinned panel. */
    private fun stateWithBaggage(): SchedulerState {
        val base = SchedulerState.empty()
        return base.copy(
            panels = listOf(
                TaskPanel(id = "auto/0", taskId = base.tasks.keys.first(), title = "work", startEpochMillis = 0, endEpochMillis = 1_000, auto = true),
                TaskPanel(id = "side/0", taskId = null, title = "Look away", startEpochMillis = 1_000, endEpochMillis = 2_000, auto = true, screenBreak = true),
                TaskPanel(id = "sleep/0", taskId = null, title = "Sleep", startEpochMillis = 2_000, endEpochMillis = 3_000, sleep = true),
                TaskPanel(id = "pin/0", taskId = null, title = "user pin", startEpochMillis = 3_000, endEpochMillis = 4_000, pinned = true),
            ),
            focusedWindow = AppWindow.Calendar,
            selection = SchedulerSelection(main = base.cells.keys.first()),
            showScreenBreaks = true,
        )
    }

    @Test
    fun the_pushed_snapshot_carries_no_regenerated_panels_and_no_local_view_state() {
        runTest {
            val server = CapturingServer()
            // A restored session (the login sync moment): with an empty remote, the reconcile seeds it from
            // local — capturing exactly what goes over the wire. Constructing the ViewModel binds the wire
            // payload provider; the reconcile is then awaited directly so the capture can't race the VM's
            // own async startup reconcile.
            val meta = FakeMetaStore(
                SyncMeta(deviceId = "d", accessToken = "at", refreshToken = "rt", userId = "user-1", dirty = false),
            )
            val sync = SchedulerSyncEngine(client(server), meta, json)
            val vm = TaskSchedulerViewModel(
                initial = stateWithBaggage(),
                store = NullStore(),
                saveDispatcher = StandardTestDispatcher(testScheduler),
                syncEngine = sync,
                startupLogin = { null },
            )
            sync.reconcile()

            val payload = assertNotNull(server.insertedPayload, "the reconcile seeded the empty remote")
            val snapshot = json.decodeFromString<PersistedSnapshot>(payload)
            val pushed = assertNotNull(SchedulerStateCodec.decodeSnapshot(snapshot), "the wire payload decodes")

            // Derived panels are stripped; the authoritative pinned panel survives.
            assertEquals(
                listOf("pin/0"),
                pushed.panels.map { it.id },
                "only the authoritative (non-regenerated) panels go over the wire",
            )
            assertTrue(pushed.panels.none(SchedulerDomain::isRegeneratedPanel))

            // Per-device view state is neutralized, not shipped.
            assertEquals(AppWindow.Tree, pushed.focusedWindow, "the focused window is local-only")
            assertEquals(SchedulerSelection(), pushed.selection, "the tree selection is local-only")
            assertEquals(false, pushed.showScreenBreaks, "the calendar display switches are local-only")

            // The task tree itself (the authoritative data) is intact.
            assertEquals(vm.state.value.tasks.keys, pushed.tasks.keys)

            vm.flush()
        }
    }

    @Test
    fun a_pulled_snapshot_without_derived_panels_still_renders_after_the_next_reschedule() {
        // The persisted-DB compatibility rule applied to the wire shape: a remote snapshot written by this
        // build lacks the regenerated panels. Loading it and running the next §9 reschedule must rebuild
        // them (here: the seeded sleep window + screen breaks of a prepared state).
        val wire = SchedulerStateCodec.syncFingerprint(stateWithBaggage())
        val decoded = assertNotNull(SchedulerStateCodec.decodeSnapshot(wire))
        val prepared = TaskSchedulerViewModel.prepareLoadedState(decoded)

        assertTrue(prepared.panels.none(SchedulerDomain::isRegeneratedPanel), "sanity: nothing derived yet")

        val now = 1_700_000_000_000L
        val rescheduled = SchedulerReducer.reduce(prepared, SchedulerIntent.RefreshSchedule(now))
        assertTrue(
            rescheduled.panels.any { it.sleep },
            "the reschedule regenerates the sleep panels the wire payload stripped",
        )
        assertTrue(
            rescheduled.panels.any { it.screenBreak },
            "the reschedule regenerates the screen-break panels the wire payload stripped",
        )
        assertTrue(
            rescheduled.panels.any { it.id == "pin/0" },
            "the authoritative pinned panel survived the round-trip",
        )
    }
}
