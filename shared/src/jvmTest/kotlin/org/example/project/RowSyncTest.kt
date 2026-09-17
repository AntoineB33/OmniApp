package org.example.project

import io.ktor.client.HttpClient
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.example.project.quota.FakeSupabase
import org.example.project.scheduler.model.AlarmEntry
import org.example.project.scheduler.model.ChoreEntry
import org.example.project.scheduler.persistence.SchedulerStateCodec
import org.example.project.scheduler.persistence.SyncMeta
import org.example.project.scheduler.persistence.SyncMetaStore
import org.example.project.scheduler.state.HistoryCategory
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState
import org.example.project.scheduler.sync.RemoteSnapshotClient
import org.example.project.scheduler.sync.SchedulerSyncEngine
import org.example.project.scheduler.sync.SupabaseConfig
import org.example.project.time.AppClock

/**
 * `docs/invariants/sync-and-accounts.md` § *Sync by rows*: **the account is synced as one row per entity and one row
 * per History Unit, against the server's own triggers** ([FakeSupabase]: the revision sequence, the head row, the
 * history trim).
 */
class RowSyncTest {
    private val config = SupabaseConfig("https://rows.supabase.co", "anon")
    private val t0 = 1_788_343_200_000L
    private val day = 24L * 60 * 60 * 1_000

    @AfterTest
    fun reset() {
        SchedulerReducer.deviceId = { SchedulerReducer.LOCAL_DEVICE_ID }
        SchedulerReducer.clock = org.example.project.time.SystemAppClock
    }

    private class Meta(private var meta: SyncMeta) : SyncMetaStore {
        override fun loadSyncMeta(): SyncMeta = meta

        override fun saveSyncMeta(meta: SyncMeta) {
            this.meta = meta
        }
    }

    private inner class Device(val id: String, server: FakeSupabase, test: TestScope, var state: SchedulerState = SchedulerState.empty()) {
        var now = t0
        val meta = Meta(SyncMeta(deviceId = id))
        val sync =
            SchedulerSyncEngine(
                RemoteSnapshotClient(config, HttpClient(server.engine(StandardTestDispatcher(test.testScheduler)))),
                meta,
                clock = { now },
            )

        init {
            // As the ViewModel does: what is applied becomes what this device holds, its own history kept.
            sync.bind({ SchedulerStateCodec.encodeSnapshot(state) }, { state = SchedulerStateCodec.decodeSnapshot(it)!!.copy(histories = state.histories) })
            sync.bindHistory(
                own = { state.histories.all().associate { (category, history) -> category.name to history.units } },
                mergePeer = { units, dropped ->
                    SchedulerReducer.deviceId = { id }
                    state = SchedulerReducer.reduce(state, SchedulerIntent.MergePeerHistory(units, dropped))
                },
            )
        }

        fun edit(change: (SchedulerState) -> SchedulerState) {
            state = change(state)
            sync.markDirty()
        }

        fun act(intent: SchedulerIntent) {
            SchedulerReducer.deviceId = { id }
            SchedulerReducer.clock = object : AppClock { override fun nowMillis(): Long = now }
            now += 1_000
            edit { SchedulerReducer.reduce(it, intent) }
        }
    }

    private val alarm = AlarmEntry(id = "alarm-1", label = "Wake", timeOfDayMinutes = 450)
    private val chore = ChoreEntry(title = "Water plants", spanDays = 3.0, id = "reminder-1")

    private fun FakeSupabase.rowsOf(kind: String) = table("scheduler_entity").rows.values.filter { it["kind"].toString() == "\"$kind\"" }

    @Test
    fun the_first_device_writes_one_row_per_entity_and_a_second_device_pulls_them() = runTest {
        val server = FakeSupabase({ t0 })
        val desk = Device("desk", server, this, SchedulerState.empty().copy(alarms = listOf(alarm)))
        desk.sync.signIn("a@b.c", "pw")
        desk.sync.reconcile()

        assertEquals(1, server.rowsOf("alarms").size)
        assertEquals(1, server.table("scheduler_head").rows.size, "a push bumps the one published head row")

        val phone = Device("phone", server, this)
        phone.sync.signIn("a@b.c", "pw")
        val writes = server.table("scheduler_entity").writes
        phone.sync.reconcile()

        assertEquals(listOf("alarm-1"), phone.state.alarms.map { it.id })
        assertEquals(writes, server.table("scheduler_entity").writes, "a device that only pulled writes nothing back")
    }

    @Test
    fun an_edit_writes_only_the_rows_it_touched() = runTest {
        val server = FakeSupabase({ t0 })
        val desk = Device("desk", server, this, QuotaAccount.state)
        desk.sync.signIn("a@b.c", "pw")
        desk.sync.reconcile()
        val writes = server.table("scheduler_entity").writes
        assertTrue(writes > 100, "the account's rows were written: $writes")

        desk.edit { it.copy(chores = it.chores + chore) }
        desk.sync.reconcile()

        assertEquals(1L, server.table("scheduler_entity").writes - writes)
    }

    @Test
    fun a_deletion_is_a_tombstone_that_the_other_device_applies() = runTest {
        val server = FakeSupabase({ t0 })
        val desk = Device("desk", server, this, SchedulerState.empty().copy(alarms = listOf(alarm)))
        val phone = Device("phone", server, this)
        desk.sync.signIn("a@b.c", "pw")
        phone.sync.signIn("a@b.c", "pw")
        desk.sync.reconcile()
        phone.sync.reconcile()

        desk.edit { it.copy(alarms = emptyList()) }
        desk.sync.reconcile()
        assertEquals("true", server.rowsOf("alarms").single()["deleted"].toString())

        phone.sync.reconcile()
        assertEquals(emptyList(), phone.state.alarms)
    }

    @Test
    fun edits_made_on_two_devices_at_once_are_merged_and_both_devices_converge() = runTest {
        val server = FakeSupabase({ t0 })
        val desk = Device("desk", server, this)
        val phone = Device("phone", server, this)
        desk.sync.signIn("a@b.c", "pw")
        phone.sync.signIn("a@b.c", "pw")
        desk.sync.reconcile()
        phone.sync.reconcile()

        desk.edit { it.copy(alarms = listOf(alarm)) }
        phone.edit { it.copy(chores = listOf(chore)) }
        desk.sync.reconcile()
        phone.sync.reconcile()
        desk.sync.reconcile()

        for (device in listOf(desk, phone)) {
            assertEquals(listOf("alarm-1"), device.state.alarms.map { it.id }, device.id)
            assertEquals(listOf("reminder-1"), device.state.chores.map { it.id }, device.id)
        }
    }

    @Test
    fun a_push_whose_answer_was_lost_is_written_again_and_never_pulled_back_over_a_newer_edit() = runTest {
        // Regression (2026-07-27, whole-document sync): a push applied server-side whose answer never arrived was
        // later pulled back over the edits made since. A pull never returns this device's own rows, so nothing is
        // pulled back; but the device must still know what that push may have written, or deleting the entity next
        // would send no tombstone for a row the server does hold.
        val server = FakeSupabase({ t0 })
        val desk = Device("desk", server, this)
        desk.sync.signIn("a@b.c", "pw")
        desk.sync.reconcile()

        desk.edit { it.copy(alarms = listOf(alarm)) }
        server.dropAnswer = { it == "POST scheduler_entity" }
        desk.sync.reconcile()
        server.dropAnswer = { false }
        assertEquals(1, server.rowsOf("alarms").size, "the push landed")
        assertEquals(true, desk.meta.loadSyncMeta().dirty, "but the device never heard so")

        desk.edit { it.copy(alarms = emptyList(), chores = listOf(chore)) }
        desk.sync.reconcile()

        assertEquals(emptyList(), desk.state.alarms)
        assertEquals(listOf("reminder-1"), desk.state.chores.map { it.id })
        assertEquals("true", server.rowsOf("alarms").single()["deleted"].toString())
    }

    @Test
    fun a_device_that_has_not_pulled_for_a_week_reads_the_live_rows_in_full_and_misses_no_deletion() = runTest {
        val server = FakeSupabase({ t0 })
        val desk = Device("desk", server, this, SchedulerState.empty().copy(alarms = listOf(alarm)))
        val phone = Device("phone", server, this)
        desk.sync.signIn("a@b.c", "pw")
        phone.sync.signIn("a@b.c", "pw")
        desk.sync.reconcile()
        phone.sync.reconcile()
        assertEquals(1, phone.state.alarms.size)

        desk.edit { it.copy(alarms = emptyList()) }
        desk.sync.reconcile()
        // A week later `purge_scheduler_tombstones` has deleted the tombstone, and the phone was off all along.
        server.table("scheduler_entity").delete({ it["deleted"].toString() == "true" }, null)
        phone.now = t0 + 7 * day
        phone.sync.reconcile()

        assertEquals(emptyList(), phone.state.alarms)
    }

    @Test
    fun history_units_are_rows_every_device_holds_and_undo_stays_with_the_device_that_made_them() = runTest {
        val server = FakeSupabase({ t0 })
        val desk = Device("desk", server, this)
        val phone = Device("phone", server, this)
        desk.sync.signIn("a@b.c", "pw")
        phone.sync.signIn("a@b.c", "pw")
        desk.sync.reconcile()
        phone.sync.reconcile()

        desk.act(SchedulerIntent.SetCellTitle(desk.state.lists[desk.state.rootListId]!!.cellIds[0], "Read"))
        desk.sync.reconcile()
        assertTrue(server.table("history_unit").rows.isNotEmpty())
        assertTrue(server.table("history_unit").rows.values.all { it["device_id"].toString() == "\"desk\"" })

        phone.sync.reconcile()
        val units = phone.state.histories.forCategory(HistoryCategory.Main).units
        assertEquals(listOf("desk"), units.map { it.deviceId })

        // Ctrl+Z on the phone does not take back the desktop's edit.
        phone.act(SchedulerIntent.Undo)
        val title = phone.state.cells[phone.state.lists[phone.state.rootListId]!!.cellIds[0]]!!.taskId?.let { phone.state.tasks[it]?.title }
        assertEquals("Read", title)
    }

    /** A realistic account, built once. */
    private object QuotaAccount {
        val state: SchedulerState by lazy { org.example.project.quota.QuotaScenario.account() }
    }
}
