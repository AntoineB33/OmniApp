package org.example.project

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import org.example.project.scheduler.engine.SchedulerEngine
import org.example.project.scheduler.persistence.ActiveSessionStore
import org.example.project.scheduler.persistence.DeclaredAwayStore
import org.example.project.scheduler.persistence.DeviceSleepGapStore
import org.example.project.scheduler.persistence.FrozenScreenBreakStore
import org.example.project.scheduler.persistence.NetworkModeStore
import org.example.project.scheduler.persistence.SleepScanCheckpointStore
import org.example.project.scheduler.persistence.SyncMetaStore
import org.example.project.scheduler.persistence.createDefaultSchedulerStore
import org.example.project.scheduler.sync.RemoteSnapshotClient
import org.example.project.scheduler.sync.SchedulerSyncEngine
import org.example.project.scheduler.sync.SnapshotChangeSubscription
import org.example.project.scheduler.sync.SupabaseConfig
import org.example.project.scheduler.ui.TaskSchedulerViewModel
import org.example.project.time.AppClock
import org.example.project.time.SystemAppClock

/**
 * **Does the app start on a real account?** — the desktop's start-up, minus its window, on a COPY of a real database.
 *
 * The release app drew no window for ~90 s on account 3 (2026-09-30) while every jvmTest was green: the engine's
 * start-up catch-up re-read a multi-MB row once per stored session, on the UI thread, before the first frame — a cost
 * only a large, realistic database shows. This builds what `App.kt` builds (the SQLite store as the desktop opens it,
 * the sync engine, the view-model, the engine with the same stores and dispatchers), runs everything `App.kt` runs on
 * the frame loop on one thread standing in for it, and fails if that thread is held longer than [UI_BUDGET_MILLIS] at
 * any one time — which is how long the first frame, or any frame, would wait.
 *
 * Opt-in: it does nothing unless `omniapp.startupCheckDir` names the directory holding the copy, which
 * `./gradlew :shared:startupCheck` sets after copying the release DB there (`-PstartupDb=` for another one). Offline
 * (`startOffline`), with a transport that refuses every request, and pointed at the copy by `omniapp.stateDir` — so
 * the release app's DB and log are never opened. Not covered: Compose's own composition and drawing of the calendar.
 */
class StartupOnRealDbTest {
    @Test
    fun the_app_starts_on_a_real_database_without_holding_the_ui_thread() {
        val dir = System.getProperty("omniapp.startupCheckDir")?.let(::File) ?: return
        val stateDir = System.getProperty("omniapp.stateDir")?.let(::File)
        assertEquals(dir.canonicalFile, stateDir?.canonicalFile, "the store must open the copy, never a live state dir")
        assertTrue(File(dir, "scheduler-state.db").isFile, "no database copy in $dir")

        val uiExecutor = Executors.newSingleThreadExecutor { Thread(it, "startup-check-ui") }
        val scope = CoroutineScope(SupervisorJob() + uiExecutor.asCoroutineDispatcher())
        val timings = linkedMapOf<String, Long>()

        fun <T> onUi(label: String, block: () -> T): T {
            val t0 = System.nanoTime()
            val result = uiExecutor.submit<T> { block() }.get(10, TimeUnit.MINUTES)
            timings[label] = (System.nanoTime() - t0) / 1_000_000
            return result
        }

        try {
            val store = onUi("open store (schema create/migrate)") { createDefaultSchedulerStore()!! }
            val sync =
                SchedulerSyncEngine(
                    RemoteSnapshotClient(
                        SupabaseConfig("https://startup-check.invalid", "anon"),
                        HttpClient(MockEngine { error("the start-up check sends nothing") }),
                    ),
                    store as SyncMetaStore,
                    activeSessionStore = store as? ActiveSessionStore,
                    networkModeStore = store as? NetworkModeStore,
                    startOffline = true,
                )
            val vm =
                onUi("load state (view-model)") {
                    TaskSchedulerViewModel(
                        store = store,
                        syncEngine = sync,
                        startupLogin = { null },
                        snapshotSubscription = object : SnapshotChangeSubscription {
                            override fun start() = Unit
                            override fun setAccount(userId: String?) = Unit
                        },
                    )
                }
            val engine =
                SchedulerEngine(
                    vm = vm,
                    // The morning start: the app closed overnight, so the catch-up walks the stretch nothing ran in —
                    // the path that held the UI thread for ~90 s. A copy of a running app's DB looks closed for
                    // seconds, which would skip it.
                    clock = object : AppClock {
                        override fun nowMillis(): Long = SystemAppClock.nowMillis() + CLOSED_FOR_MILLIS
                    },
                    scope = scope,
                    planDispatcher = Dispatchers.Default,
                    planSearch = true,
                    speak = {},
                    postNotification = { _, _ -> },
                    clearNotifications = {},
                    sleepGapStore = store as? DeviceSleepGapStore,
                    sleepScanCheckpoint = store as? SleepScanCheckpointStore,
                    declaredAwayStore = store as? DeclaredAwayStore,
                    frozenBreakStore = store as? FrozenScreenBreakStore,
                    activeSessionStore = store as? ActiveSessionStore,
                    pauseCue = vm.pauseCue,
                    schedulerPeers = vm.schedulerPeers,
                )
            onUi("engine.start()") { engine.start() }

            // What start() launched runs on the same thread afterwards: probe it the way a frame would find it.
            var longestWait = 0L
            val until = System.nanoTime() + SETTLE_MILLIS * 1_000_000
            while (System.nanoTime() < until) {
                val t0 = System.nanoTime()
                uiExecutor.submit {}.get(10, TimeUnit.MINUTES)
                longestWait = maxOf(longestWait, (System.nanoTime() - t0) / 1_000_000)
                Thread.sleep(PROBE_EVERY_MILLIS)
            }
            timings["longest UI-thread hold in the ${SETTLE_MILLIS / 1000} s after start"] = longestWait

            val report = timings.entries.joinToString("\n") { (k, v) -> "  %-55s %7d ms".format(k, v) }
            println("start-up on ${File(dir, "scheduler-state.db").length() / 1_000_000} MB database, closed for ${CLOSED_FOR_MILLIS / 3_600_000} h:\n$report")
            val over = timings.filterValues { it > UI_BUDGET_MILLIS }
            assertTrue(over.isEmpty(), "the UI thread was held longer than $UI_BUDGET_MILLIS ms:\n$report")
        } finally {
            scope.cancel()
            uiExecutor.shutdownNow()
        }
    }

    private companion object {
        /** A frame held this long is a window that looks dead; the release showed none for ~90 s. */
        const val UI_BUDGET_MILLIS = 5_000L
        const val SETTLE_MILLIS = 20_000L
        const val PROBE_EVERY_MILLIS = 50L
        const val CLOSED_FOR_MILLIS = 12 * 60 * 60_000L
    }
}
