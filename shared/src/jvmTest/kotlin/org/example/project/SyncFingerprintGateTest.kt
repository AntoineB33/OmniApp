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
import org.example.project.scheduler.state.SchedulerReducer
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

        // A completed-work record is in the fingerprint so that a MANUAL record edit (RemoveRecordPeriod) is
        // detectable and syncs. (An AUTO-banked record still moves the fingerprint here, but the schedule-advance
        // intent that banks it is gated out of the push by TaskSchedulerViewModel.syncsToServer — see below.)
        val (tid, task) = base.tasks.entries.first()
        val withRecord = base.copy(tasks = base.tasks + (tid to task.copy(record = listOf(TaskTimeRange(0, 1_000)))))
        assertNotEquals(fp(base), fp(withRecord), "a record is in the fingerprint so a manual record edit syncs")
    }

    // ---- The account1-empty-and-open guarantee: an idle empty account never moves the fingerprint ----

    @Test
    fun empty_account_idle_now_advance_makes_zero_authoritative_changes() {
        // On a freshly-emptied account (no leaf tasks — only the seeded side tasks + default sleep) an idle
        // now-advance only re-derives the auto/side/sleep panels; it banks NO completed-work record, so the
        // sync fingerprint never moves and TaskSchedulerViewModel pushes no `scheduler_snapshot` revision.
        // Advancing across the 20-min look-away, the hourly 5-min pose, and the 2-hourly 15-min pose must all
        // stay silent. (A NON-empty account does push as elapsing auto WORK panels bank records — that is
        // authoritative by design, see fingerprint_changes_on_authoritative_panels_and_data.)
        var state = TaskSchedulerViewModel.prepareLoadedState(SchedulerState.empty())
        val start = 1_700_000_000_000L
        state = SchedulerReducer.reduce(state, SchedulerIntent.RefreshSchedule(start))
        val baseline = fp(state)
        var now = start
        val end = start + 3L * 60 * 60 * 1_000 // three hours of 30-s production ticks
        while (now <= end) {
            state = SchedulerReducer.reduce(state, SchedulerIntent.RefreshSchedule(now))
            assertEquals(baseline, fp(state), "an idle now-advance to $now must not move the sync fingerprint")
            now += 30_000L
        }
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

    @Test
    fun an_auto_banked_record_does_not_push_but_a_manual_record_edit_does() {
        val dispatcher = StandardTestDispatcher()
        runTest(dispatcher) {
            val meta = FakeMetaStore()
            val start = 1_700_000_000_000L
            val base = SchedulerState.empty()
            val (tid, task) = base.tasks.entries.first()
            // The synced baseline already holds one completed-work record; plus an elapsed auto panel for the
            // same task, sitting just before the now we will advance to.
            val seeded = TaskTimeRange(start - 10_000, start - 5_000)
            val initial =
                base.copy(
                    tasks = base.tasks + (tid to task.copy(record = listOf(seeded))),
                    panels = listOf(TaskPanel(id = "auto/0", taskId = tid, title = task.title, startEpochMillis = start, endEpochMillis = start + 1_000, auto = true)),
                )
            val vm = TaskSchedulerViewModel(
                initial = initial,
                store = NullStore(),
                saveDispatcher = dispatcher,
                syncEngine = signedOutEngine(meta),
                startupLogin = { null },
            )
            runCurrent()

            // (1) The now-line passes the auto panel → advanceSchedule banks [start, start+1000] as completed
            // work. That record is derived (another device re-banks it by advancing its own now-line over the
            // synced tree), so the schedule-advance tick must NOT push — even though task.record changed.
            vm.dispatch(SchedulerIntent.AdvanceSchedule(nowMillis = start + 2_000))
            advanceTimeBy(500)
            runCurrent()
            assertEquals(2, vm.state.value.tasks[tid]?.record?.size, "the tick banked the elapsed panel as a record")
            assertNotEquals(true, meta.loadSyncMeta()?.dirty, "an auto-banked completed-work record must not push")

            // (2) Manually removing a completed-work block is a user decision no other device can deduce → push.
            vm.dispatch(SchedulerIntent.RemoveRecordPeriod(tid, seeded.startEpochMillis, seeded.endEpochMillis))
            advanceTimeBy(500)
            runCurrent()
            assertEquals(true, meta.loadSyncMeta()?.dirty, "removing a completed-work block is authoritative and must push")
        }
    }
}
