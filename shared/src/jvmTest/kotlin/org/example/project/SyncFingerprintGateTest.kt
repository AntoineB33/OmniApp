package org.example.project

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.example.project.scheduler.model.TaskPanel
import org.example.project.scheduler.model.TaskTimeRange
import org.example.project.scheduler.persistence.PersistedSnapshot
import org.example.project.scheduler.persistence.SchedulerStateCodec
import org.example.project.scheduler.persistence.SchedulerStore
import org.example.project.scheduler.persistence.SyncMeta
import org.example.project.scheduler.persistence.SyncMetaStore
import org.example.project.scheduler.state.AppWindow
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerState
import org.example.project.scheduler.sync.RemoteSnapshotClient
import org.example.project.scheduler.sync.SchedulerSyncEngine
import org.example.project.scheduler.sync.SupabaseConfig
import org.example.project.scheduler.ui.TaskSchedulerViewModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * CLAUDE.md reconstructibility rule / the "known deviation" fix: the ViewModel must sync the
 * `scheduler_snapshot` only on an **authoritative** change, never on an engine-tick reschedule that merely
 * re-derived the auto/side/sleep panels — otherwise an idle session chatters once per tick. The gate is
 * [SchedulerStateCodec.syncFingerprint] (the persisted snapshot minus the regenerated panels), consulted by
 * `TaskSchedulerViewModel.scheduleSave`/`flush`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncFingerprintGateTest {
    private fun fp(state: SchedulerState) = SchedulerStateCodec.syncFingerprint(state)

    // ---- The projection itself: derived panels are invisible, authoritative data is not ----

    @Test
    fun fingerprint_ignores_regenerated_auto_side_and_sleep_panels() {
        val base = SchedulerState.empty()
        val withDerived =
            base.copy(
                panels = listOf(
                    TaskPanel(id = "auto/0", taskId = null, title = "T", startEpochMillis = 0, endEpochMillis = 1_000, auto = true),
                    TaskPanel(id = "side/0", taskId = null, title = "Look away", startEpochMillis = 1_000, endEpochMillis = 2_000, auto = true, sideTask = true),
                    TaskPanel(id = "sleep/0", taskId = null, title = "Sleep", startEpochMillis = 2_000, endEpochMillis = 3_000, sleep = true),
                ),
            )
        // A tick that only (re)generated these panels is not a syncable change.
        assertEquals(fp(base), fp(withDerived), "regenerated panels must not move the sync fingerprint")
    }

    @Test
    fun fingerprint_changes_on_authoritative_panels_and_data() {
        val base = SchedulerState.empty()

        // A user-pinned panel is authoritative (survives a reschedule).
        val pinned = base.copy(
            panels = listOf(TaskPanel(id = "p", taskId = null, title = "pin", startEpochMillis = 0, endEpochMillis = 1_000, pinned = true)),
        )
        assertNotEquals(fp(base), fp(pinned), "a pinned panel is authoritative")

        // A reminder tag is kept across reschedules AND carries the authoritative `checked` state.
        val reminder = base.copy(
            panels = listOf(TaskPanel(id = "chore/x", taskId = null, title = "r", startEpochMillis = 5, endEpochMillis = 5, chore = true)),
        )
        val reminderChecked = base.copy(
            panels = listOf(TaskPanel(id = "chore/x", taskId = null, title = "r", startEpochMillis = 5, endEpochMillis = 5, chore = true, checked = true)),
        )
        assertNotEquals(fp(base), fp(reminder), "a reminder tag is not a derived panel")
        assertNotEquals(fp(reminder), fp(reminderChecked), "checking a reminder off is an authoritative change")

        // A completed-work record is authoritative (it is exactly what an elapsing auto panel banks on a tick).
        val (tid, task) = base.tasks.entries.first()
        val withRecord = base.copy(tasks = base.tasks + (tid to task.copy(record = listOf(TaskTimeRange(0, 1_000)))))
        assertNotEquals(fp(base), fp(withRecord), "a completed-work record must sync")
    }

    // ---- The ViewModel actually consults the gate ----

    private class NullStore : SchedulerStore {
        override fun load(): PersistedSnapshot? = null
        override fun save(snapshot: PersistedSnapshot) {}
    }

    private class FakeMetaStore(private var meta: SyncMeta? = null) : SyncMetaStore {
        override fun loadSyncMeta(): SyncMeta? = meta
        override fun saveSyncMeta(meta: SyncMeta) { this.meta = meta }
    }

    /** A signed-out engine: its MockEngine 404s everything, but nothing calls it (no session) — we only
     *  observe whether the ViewModel flagged the local state dirty. */
    private fun signedOutEngine(meta: FakeMetaStore): SchedulerSyncEngine {
        val client = RemoteSnapshotClient(
            SupabaseConfig("https://test.supabase.co", "anon-key"),
            HttpClient(MockEngine { respond("", HttpStatusCode.NotFound) }),
        )
        return SchedulerSyncEngine(client, meta, Json { ignoreUnknownKeys = true })
    }

    @Test
    fun engine_tick_reschedule_does_not_mark_dirty_but_an_authoritative_change_does() {
        val dispatcher = StandardTestDispatcher()
        runTest(dispatcher) {
            val meta = FakeMetaStore()
            val vm = TaskSchedulerViewModel(
                store = NullStore(),
                saveDispatcher = dispatcher,
                syncEngine = signedOutEngine(meta),
                startupLogin = { null },
            )
            runCurrent()

            // A §9 reschedule tick: with the seeded sleep window + side tasks but no leaf tasks, this only
            // (re)generates sleep/side panels — a pure re-derive. It must NOT mark the state dirty.
            vm.dispatch(SchedulerIntent.RefreshSchedule(nowMillis = 1_700_000_000_000L))
            advanceTimeBy(500)
            runCurrent()
            assertNotEquals(true, meta.loadSyncMeta()?.dirty, "a derived-only reschedule must not push")

            // An authoritative change (the focused window is persisted state) must mark dirty.
            vm.dispatch(SchedulerIntent.FocusWindow(AppWindow.Calendar))
            advanceTimeBy(500)
            runCurrent()
            assertEquals(true, meta.loadSyncMeta()?.dirty, "an authoritative change must push")
        }
    }
}
