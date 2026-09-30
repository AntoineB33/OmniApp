package org.example.project

import io.ktor.client.HttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.example.project.quota.FakeSupabase
import org.example.project.scheduler.persistence.SyncMeta
import org.example.project.scheduler.persistence.SyncMetaStore
import org.example.project.scheduler.sync.RemoteSnapshotClient
import org.example.project.scheduler.sync.SchedulerSyncEngine
import org.example.project.scheduler.sync.SupabaseConfig

/**
 * The release app showed NO window on account 3 (2026-09-30): the engine's start-up catch-up asked the device id once
 * per stored active session, on the UI thread before the first frame, and every ask re-read the whole account row —
 * its `base_payload` snapshot included — out of SQLite. The id is allocated once and never rewritten while the process
 * runs, so it is read once.
 */
class DeviceIdReadOnceTest {
    private class CountingMetaStore : SyncMetaStore {
        var loads = 0
        private var meta: SyncMeta? = null

        override fun loadSyncMeta(): SyncMeta? {
            loads++
            return meta
        }

        override fun saveSyncMeta(meta: SyncMeta) {
            this.meta = meta
        }
    }

    @Test
    fun the_device_id_is_read_from_the_store_once_however_often_it_is_asked() = runTest {
        val store = CountingMetaStore()
        val client =
            RemoteSnapshotClient(
                SupabaseConfig("https://device-id.supabase.co", "anon"),
                HttpClient(FakeSupabase({ 0L }).engine(StandardTestDispatcher(testScheduler))),
            )
        val sync = SchedulerSyncEngine(client, store, startOffline = true)
        val loadsBefore = store.loads
        val first = sync.deviceId
        repeat(10_000) { assertEquals(first, sync.deviceId) }
        assertEquals(1, store.loads - loadsBefore)
    }
}
