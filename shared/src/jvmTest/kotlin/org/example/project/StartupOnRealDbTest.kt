package org.example.project

import org.example.project.scheduler.platform.DeviceSleepGap
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
 * Then it EDITS, as a field of the app does ([editsDoNotHoldTheUiThread]) — the same thread, the same budget idea.
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
                    postNotification = { _, _, _ -> },
                    clearNotifications = {},
                    // The clock is faked twelve hours ahead, where the OS has recorded nothing: the night is the device
                    // asleep, as it is on a real morning start.
                    lockedIntervalsQuery = { since, until -> listOf(DeviceSleepGap(since, until)) },
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

            editsDoNotHoldTheUiThread(vm, uiExecutor)

            val report = timings.entries.joinToString("\n") { (k, v) -> "  %-55s %7d ms".format(k, v) }
            println("start-up on ${File(dir, "scheduler-state.db").length() / 1_000_000} MB database, closed for ${CLOSED_FOR_MILLIS / 3_600_000} h:\n$report")
            val over = timings.filterValues { it > UI_BUDGET_MILLIS }
            assertTrue(over.isEmpty(), "the UI thread was held longer than $UI_BUDGET_MILLIS ms:\n$report")
        } finally {
            scope.cancel()
            uiExecutor.shutdownNow()
        }
    }

    /**
     * Anomaly 2026-10-11: *"when I changed the starting time of the Sleep period, it froze a moment. Make sure the
     * modification of the calendar doesn't freeze the user when they modify fields in the app."* — **the edits a field
     * makes, on the real account**: each is dispatched on the thread standing in for the frame loop, as the field
     * does, and that thread is probed for [EDIT_SETTLE_MILLIS] afterwards (the save, the re-plan and what they launch
     * land on it too). The longest it is held, by the dispatch or after it, is what the user feels as a freeze.
     */
    private fun editsDoNotHoldTheUiThread(vm: TaskSchedulerViewModel, uiExecutor: java.util.concurrent.ExecutorService) {
        val tz = kotlinx.datetime.TimeZone.currentSystemDefault()
        val day = 86_400_000L
        val hour = 3_600_000L
        val rows = ArrayList<String>()
        var worst = 0L
        var worstAfter = 0L
        org.example.project.perf.Perf.enabled = true
        fun edit(label: String, intentOf: (org.example.project.scheduler.state.SchedulerState) -> org.example.project.scheduler.state.SchedulerIntent?) {
            org.example.project.perf.Perf.reset()
            val t0 = System.nanoTime()
            val done = uiExecutor.submit<Boolean> { intentOf(vm.state.value)?.let { vm.dispatch(it); true } ?: false }.get(10, TimeUnit.MINUTES)
            val dispatch = (System.nanoTime() - t0) / 1_000_000
            var after = 0L
            val until = System.nanoTime() + EDIT_SETTLE_MILLIS * 1_000_000
            while (System.nanoTime() < until) {
                val p0 = System.nanoTime()
                uiExecutor.submit {}.get(10, TimeUnit.MINUTES)
                after = maxOf(after, (System.nanoTime() - p0) / 1_000_000)
                Thread.sleep(PROBE_EVERY_MILLIS)
            }
            val top =
                org.example.project.perf.Perf.snapshot().sections.filter { it.maxNanos >= 20_000_000 }.sortedByDescending { it.maxNanos }.take(8)
                    .joinToString("; ") { it.name + " " + it.maxNanos / 1_000_000 + " ms x" + it.calls }
            rows += "  %-46s %s dispatch %5d ms, then held %5d ms   %s".format(label, if (done) "" else "(no such block) ", dispatch, after, top)
            if (done) { worst = maxOf(worst, dispatch); worstAfter = maxOf(worstAfter, after) }
        }
        fun nightOf(state: org.example.project.scheduler.state.SchedulerState, kind: String, nth: Int): org.example.project.scheduler.domain.SearchDomain.CalendarBlock? {
            val from = SystemAppClock.nowMillis()
            val owners = org.example.project.scheduler.domain.SearchDomain.resolve(state, listOf("RestrictivePeriod/$kind"))
            return org.example.project.scheduler.domain.SearchDomain.blocksOfAdded(state, owners, tz, from, from + 30 * day)
                .filter { it.derivedKind == kind }.getOrNull(nth)
        }
        fun moved(kind: String, nth: Int, by: Long) = { state: org.example.project.scheduler.state.SchedulerState ->
            nightOf(state, kind, nth)?.let {
                org.example.project.scheduler.domain.SearchDomain.blockEditIntent(state, it, it.startMillis + by, it.endMillis, SystemAppClock.nowMillis(), tz)
            }
        }
        val sleep = org.example.project.scheduler.domain.PeriodKinds.SLEEP
        // The anomaly's own gesture — the start field of tonight's night — then the same on later nights, the hour
        // before bed, and the period the first edit left (a block of the user's from there).
        edit("Sleep, tonight: start + 30 min", moved(sleep, 0, hour / 2))
        edit("Sleep, the night after: start + 30 min", moved(sleep, 1, hour / 2))
        edit("Sleep, in a week: start - 1 h", moved(sleep, 6, -hour))
        edit("Before bed, tomorrow: start - 15 min", moved(org.example.project.scheduler.domain.PeriodKinds.BEFORE_BED, 1, -hour / 4))
        edit("The placed Sleep period: start + 15 min") { state ->
            val from = SystemAppClock.nowMillis()
            val owners = org.example.project.scheduler.domain.SearchDomain.resolve(state, listOf("RestrictivePeriod/$sleep"))
            org.example.project.scheduler.domain.SearchDomain.blocksOfAdded(state, owners, tz, from, from + 30 * day)
                .firstOrNull { it.derivedKind == null && it.startMillis > from }
                ?.let { org.example.project.scheduler.domain.SearchDomain.blockEditIntent(state, it, it.startMillis + hour / 4, it.endMillis, from, tz) }
        }
        // The anomaly itself: the start of the night in progress (or the next one) TYPED — a time field writes every
        // text that reads as a time, so "05:1", "05:4", "05:45" are three writes with nothing between them, the later
        // ones landing while the engine is already running for the first. Each one stripped the work under the period
        // and planned for it on this thread: 0.4 s, then 2.7 s and 2.2 s beside the run.
        run {
            val now0 = SystemAppClock.nowMillis()
            fun current(state: org.example.project.scheduler.state.SchedulerState): org.example.project.scheduler.domain.SearchDomain.CalendarBlock? {
                val owners = org.example.project.scheduler.domain.SearchDomain.resolve(state, listOf("RestrictivePeriod/$sleep"))
                return org.example.project.scheduler.domain.SearchDomain.blocksOfAdded(state, owners, tz, now0 - day, now0 + 2 * day)
                    .filter { it.startMillis <= now0 + 12 * hour && it.endMillis > now0 }.minByOrNull { it.startMillis }
            }
            val first = current(vm.state.value)
            for ((i, shift) in listOf(-14L, -11L, 30L).withIndex()) {
                org.example.project.perf.Perf.reset()
                // Where this thread stands while the edit is reduced, read out only when it was slow: a budget that fails
                // should say on what.
                val ui = Thread.getAllStackTraces().keys.first { it.name.startsWith("startup-check-ui") }
                val hits = LinkedHashMap<String, Int>()
                var sampling = true
                val gc0 = java.lang.management.ManagementFactory.getGarbageCollectorMXBeans().sumOf { it.collectionTime }
                val sampler = Thread {
                    while (sampling) {
                        val st = ui.stackTrace
                        val own = st.filter { it.className.startsWith("org.example.project") && !it.className.contains("StartupOnRealDbTest") }
                        if (own.isNotEmpty()) {
                            own.map { it.className.substringAfterLast('.') + "." + it.methodName }.distinct().forEach { hits[it] = (hits[it] ?: 0) + 1 }
                            st.firstOrNull()?.let { val k = "TOP " + it.className.substringAfterLast('.') + "." + it.methodName + " [" + ui.state + "]"; hits[k] = (hits[k] ?: 0) + 1 }
                        }
                        Thread.sleep(2)
                    }
                }
                sampler.start()
                val t0 = System.nanoTime()
                uiExecutor.submit {
                    val st = vm.state.value
                    val b = current(st)
                    if (b != null && first != null) {
                        org.example.project.scheduler.domain.SearchDomain.blockEditIntent(st, b, first.startMillis + shift * 60_000L, b.endMillis, now0, tz)?.let { vm.dispatch(it) }
                    }
                }.get(10, TimeUnit.MINUTES)
                val dispatch = (System.nanoTime() - t0) / 1_000_000
                sampling = false
                sampler.join()
                val gc = java.lang.management.ManagementFactory.getGarbageCollectorMXBeans().sumOf { it.collectionTime } - gc0
                if (dispatch > 150) {
                    rows += "      SLOW dispatch $dispatch ms, GC $gc ms in it:"
                    hits.entries.sortedByDescending { it.value }.take(40).forEach { rows += "      %4d x2ms  %s".format(it.value, it.key) }
                }
                var after = 0L
                val until = System.nanoTime() + (if (i == 2) 15_000L else 150L) * 1_000_000
                while (System.nanoTime() < until) {
                    val p0 = System.nanoTime()
                    uiExecutor.submit {}.get(10, TimeUnit.MINUTES)
                    after = maxOf(after, (System.nanoTime() - p0) / 1_000_000)
                    Thread.sleep(10)
                }
                val top =
                    org.example.project.perf.Perf.snapshot().sections.filter { it.maxNanos >= 20_000_000 }.sortedByDescending { it.maxNanos }.take(10)
                        .joinToString("; ") { it.name + " " + it.maxNanos / 1_000_000 + " ms x" + it.calls }
                rows += "  %-46s  dispatch %5d ms, then held %5d ms   %s".format("Sleep now, typed: keystroke ${i + 1} (${shift} min)", dispatch, after, top)
                if (first != null) { worst = maxOf(worst, dispatch); worstAfter = maxOf(worstAfter, after) }
            }
        }
        org.example.project.perf.Perf.enabled = false
        println("edits on the real account (UI thread):\n" + rows.joinToString("\n"))
        assertTrue(worst <= EDIT_BUDGET_MILLIS, "an edit held the UI thread longer than $EDIT_BUDGET_MILLIS ms:\n" + rows.joinToString("\n"))
        assertTrue(worstAfter <= AFTER_EDIT_BUDGET_MILLIS, "the UI thread was held longer than $AFTER_EDIT_BUDGET_MILLIS ms after an edit:\n" + rows.joinToString("\n"))
    }

    private companion object {
        /**
         * An edit may not hold the frame loop this long. Measured: 10-90 ms alone, up to ~0.5 s while `checkChange`
         * builds the other targets beside it; the fill that used to run inside the edit was 0.4 s alone and 1.2-2.7 s
         * beside the engine's run. `CalendarEditNeverPlansInlineTest` is the exact gate; this one is the real account.
         */
        const val EDIT_BUDGET_MILLIS = 1_000L
        /**
         * …and what the edit sets off may not hold it this long afterwards. Looser: the engine still writes its own
         * small rows (the banked breaks, the open session) from this thread, and they wait for the snapshot save's
         * SQLite transaction — 0.1 s usually, 0.6 s seen on a loaded machine.
         */
        const val AFTER_EDIT_BUDGET_MILLIS = 1_500L
        const val EDIT_SETTLE_MILLIS = 6_000L
        /** A frame held this long is a window that looks dead; the release showed none for ~90 s. */
        const val UI_BUDGET_MILLIS = 5_000L
        const val SETTLE_MILLIS = 20_000L
        const val PROBE_EVERY_MILLIS = 50L
        const val CLOSED_FOR_MILLIS = 12 * 60 * 60_000L
    }
}
