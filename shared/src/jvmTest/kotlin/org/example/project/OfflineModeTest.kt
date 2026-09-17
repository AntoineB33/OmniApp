package org.example.project

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.ktor.client.HttpClient
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.example.project.quota.FakeSupabase
import org.example.project.scheduler.model.AlarmEntry
import org.example.project.scheduler.persistence.SqlDelightSchedulerStore
import org.example.project.scheduler.persistence.SyncMeta
import org.example.project.scheduler.persistence.db.SchedulerDatabase
import org.example.project.scheduler.sync.NextBreakState
import org.example.project.scheduler.sync.RemoteSnapshotClient
import org.example.project.scheduler.sync.SchedulerSyncEngine
import org.example.project.scheduler.sync.SnapshotChangeSubscription
import org.example.project.scheduler.sync.StartupLogin
import org.example.project.scheduler.sync.SupabaseConfig
import org.example.project.scheduler.sync.SyncState
import org.example.project.scheduler.sync.WorkingOfflineException
import org.example.project.scheduler.state.SchedulerState
import org.example.project.scheduler.ui.TaskSchedulerViewModel

/**
 * `docs/invariants/sync-and-accounts.md` § *Working offline* (user request, 2026-09-17, while the Supabase project was
 * down): **a device switched offline sends nothing at all, keeps its edits, and pushes them when it goes online.**
 */
class OfflineModeTest {
    private val config = SupabaseConfig("https://offline.supabase.co", "anon")

    private fun store() = SqlDelightSchedulerStore(SchedulerDatabase(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), SchedulerDatabase.Schema)))

    private fun TestScope.client(server: FakeSupabase) =
        RemoteSnapshotClient(config, HttpClient(server.engine(StandardTestDispatcher(testScheduler))))

    private class RecordingRealtime : SnapshotChangeSubscription {
        val accounts = ArrayList<String?>()

        override fun start() = Unit

        override fun setAccount(userId: String?) {
            accounts += userId
        }
    }

    @Test
    fun an_offline_engine_sends_nothing_and_every_request_is_refused_before_it_leaves() = runTest {
        val server = FakeSupabase({ 0L })
        val store = store().also { it.saveSyncMeta(SyncMeta(deviceId = "desk", accessToken = server.tokenFor("u"), refreshToken = "r", userId = "u")) }
        val client = client(server)
        val sync = SchedulerSyncEngine(client, store, networkModeStore = store, startOffline = true)
        sync.bind({ org.example.project.scheduler.persistence.SchedulerStateCodec.encodeSnapshot(SchedulerState.empty()) }, {})
        sync.restoreSession()

        sync.markDirty()
        sync.reconcile()
        assertTrue(sync.ensureAccount(), "the restored account stays active locally")
        sync.publishPresence(org.example.project.scheduler.sync.PresenceState("desk"))
        sync.publishNextBreak(NextBreakState(null, null))
        sync.syncDeviceAway(true)
        assertNull(sync.realtimeAuth(), "no Realtime socket is opened")
        assertEquals(false, sync.signedIn)
        assertFailsWith<WorkingOfflineException> { sync.signIn("a@b.c", "pw") }
        // The hard gate: even a direct call is never sent.
        assertFailsWith<WorkingOfflineException> { client.fetchEntities(org.example.project.scheduler.sync.SupabaseSession("t", "r", "u"), null, 0) }

        assertEquals(0L, server.requests)
        assertEquals(SyncState.Offline, sync.state.value)
        assertEquals(true, store.loadSyncMeta()!!.dirty, "the edit stays marked for the next push")
    }

    @Test
    fun the_choice_is_remembered_and_a_launch_asked_to_start_offline_does_whatever_was_chosen() = runTest {
        val server = FakeSupabase({ 0L })
        val store = store()
        SchedulerSyncEngine(client(server), store, networkModeStore = store).setOffline(true)
        assertEquals(true, SchedulerSyncEngine(client(server), store, networkModeStore = store).offline.value)

        SchedulerSyncEngine(client(server), store, networkModeStore = store).setOffline(false)
        assertEquals(false, SchedulerSyncEngine(client(server), store, networkModeStore = store).offline.value)
        assertEquals(true, SchedulerSyncEngine(client(server), store, networkModeStore = store, startOffline = true).offline.value)
        assertEquals(false, store.loadOfflineChoice(), "starting offline does not overwrite the user's choice")
    }

    @Test
    fun an_offline_launch_neither_signs_in_nor_connects_and_going_online_runs_the_launch_sign_in() = runTest {
        val server = FakeSupabase({ 0L })
        val store = store()
        val realtime = RecordingRealtime()
        val sync = SchedulerSyncEngine(client(server), store, networkModeStore = store, startOffline = true)
        val vm =
            TaskSchedulerViewModel(
                initial = SchedulerState.empty(),
                store = store,
                saveDispatcher = StandardTestDispatcher(testScheduler),
                syncEngine = sync,
                startupLogin = { StartupLogin("account3", "pw") },
                snapshotSubscription = realtime,
            )
        advanceUntilIdle()
        assertEquals(0L, server.requests, "no startup sign-in, no guest account, no reconcile")
        assertTrue(realtime.accounts.all { it == null }, "no Realtime subscription")
        assertEquals(true, vm.offline!!.value)

        vm.setOffline(false)
        advanceUntilIdle()
        assertTrue(server.requests > 0)
        assertEquals("account3@omniapp.local", store.loadSyncMeta()!!.email, "going online runs the launch's sign-in")
        assertTrue(realtime.accounts.last() != null, "the Realtime subscription is back")
        vm.flush()
    }

    @Test
    fun a_signed_in_device_keeps_its_offline_edits_and_pushes_them_when_it_goes_online() = runTest {
        val server = FakeSupabase({ 0L })
        val store = store().also { it.saveSyncMeta(SyncMeta(deviceId = "desk", accessToken = server.tokenFor("u"), refreshToken = "r", userId = "u")) }
        val realtime = RecordingRealtime()
        val sync = SchedulerSyncEngine(client(server), store, networkModeStore = store, startOffline = true)
        val vm =
            TaskSchedulerViewModel(
                initial = SchedulerState.empty(),
                store = store,
                saveDispatcher = StandardTestDispatcher(testScheduler),
                syncEngine = sync,
                startupLogin = { null },
                snapshotSubscription = realtime,
            )
        advanceUntilIdle()

        vm.dispatch(org.example.project.scheduler.state.SchedulerIntent.SetAlarms(listOf(AlarmEntry(id = "alarm-1", label = "Wake", timeOfDayMinutes = 450))))
        advanceUntilIdle()
        assertEquals(0L, server.requests, "an edit offline sends nothing")
        assertEquals(true, store.loadSyncMeta()!!.dirty, "the edit is marked for the next push")

        vm.setOffline(false)
        advanceUntilIdle()
        assertTrue(
            server.table("scheduler_entity").rows.values.any { it["entity_id"].toString().contains("alarm-1") },
            "the edit made offline reached the server",
        )
        assertEquals(false, store.loadSyncMeta()!!.dirty)
        vm.flush()
    }
}
