package org.example.project.quota

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.ktor.client.HttpClient
import java.util.Properties
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.engine.SchedulerEngine
import org.example.project.scheduler.model.CellId
import org.example.project.scheduler.model.PanelPins
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.persistence.SqlDelightSchedulerStore
import org.example.project.scheduler.persistence.SyncMeta
import org.example.project.scheduler.persistence.db.SchedulerDatabase
import org.example.project.scheduler.platform.DeviceKind
import org.example.project.scheduler.state.HistoryWindow
import org.example.project.scheduler.state.EditExitNavigation
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState
import org.example.project.scheduler.sync.PeerMessage
import org.example.project.scheduler.sync.RealtimePhoenix
import org.example.project.scheduler.sync.RemoteSnapshotClient
import org.example.project.scheduler.sync.SchedulerPeerChannel
import org.example.project.scheduler.sync.SchedulerSyncEngine
import org.example.project.scheduler.sync.SnapshotChangeSubscription
import org.example.project.scheduler.sync.SupabaseConfig
import org.example.project.scheduler.ui.TaskSchedulerViewModel
import org.example.project.time.AppClock

/**
 * `docs/invariants/server-quota.md`: **one account used heavily on a desktop and a phone, against [FakeSupabase].**
 *
 * Each device is the real app minus its window: a [TaskSchedulerViewModel] on an in-memory SQLite store, the real
 * [SchedulerSyncEngine] over the real `RemoteSnapshotClient`, and a real [SchedulerEngine] (plans, presence beats,
 * break publishing, the scheduler peers' channel). Only the sockets are replaced, by [FakeRealtime].
 */
class QuotaScenario(private val test: TestScope, val server: FakeSupabase, val t0: Millis) {
    data class Millis(val value: Long)

    private val config = SupabaseConfig("https://quota.supabase.co", "anon")

    inner class Device(val id: String, val userId: String, initial: SchedulerState, kind: DeviceKind) {
        var active = true
        private val store =
            SqlDelightSchedulerStore(SchedulerDatabase(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), SchedulerDatabase.Schema)))
                .also {
                    val token = server.tokenFor(userId)
                    it.saveSyncMeta(SyncMeta(deviceId = id, accessToken = token, refreshToken = token, userId = userId, email = "$userId@quota"))
                }
        val realtime = FakeRealtime(id)
        val sync = SchedulerSyncEngine(RemoteSnapshotClient(config, HttpClient(server.engine(kotlinx.coroutines.test.StandardTestDispatcher(test.testScheduler)))), store, activeSessionStore = store)
        val vm =
            TaskSchedulerViewModel(
                initial = initial,
                store = store,
                saveDispatcher = kotlinx.coroutines.test.StandardTestDispatcher(test.testScheduler),
                syncEngine = sync,
                startupLogin = { null },
                snapshotSubscription = realtime,
            )
        val engine =
            SchedulerEngine(
                vm = vm,
                clock = object : AppClock {
                    override fun nowMillis(): Long = t0.value + test.testScheduler.currentTime
                },
                scope = test.backgroundScope,
                // `docs/scheduler_requirements.md` § *Progressive Calculation*: this simulated device is given NO
                // compute budget, so every fill stops after its first stage (one hour) instead of walking the
                // doubling stages out to the end of the week — which, over a simulated month of edits on a
                // 224-task account, is this scenario's whole cost and none of its subject. What the scheduler
                // returns is DERIVED state: it is stripped from the wire and never triggers a push, so it cannot
                // move a single byte of the quota this test measures. The device is otherwise the real app: it
                // still plans on every rule change, and a fill that DID reach the server would still be caught.
                calculationLimitMillis = 0,
                deviceKind = kind,
                screenActive = { active },
                speak = {},
                postNotification = { _, _ -> },
                clearNotifications = {},
                sleepGapQuery = { emptyList() },
                activeSessionStore = store,
                pauseCue = sync,
                schedulerPeers = realtime,
            )

        init {
            realtime.onRemoteChange = { vm.syncNow() }
            engine.start()
        }

        fun dispatch(intent: SchedulerIntent) = vm.dispatch(intent)
        val state: SchedulerState get() = vm.state.value
    }

    /** The two Realtime channels of one device, answered by [server] instead of a socket. */
    inner class FakeRealtime(private val deviceId: String) : SnapshotChangeSubscription, SchedulerPeerChannel {
        var onRemoteChange: () -> Unit = {}
        override val connected = MutableStateFlow(false)
        private val inbox = MutableSharedFlow<PeerMessage>(extraBufferCapacity = 256)
        override val messages: SharedFlow<PeerMessage> = inbox
        private var user: String? = null

        override fun start() = Unit

        override fun setAccount(userId: String?) {
            user = userId
            if (userId == null) {
                connected.value = false
                return
            }
            server.subscribePostgresChanges(deviceId, userId, "scheduler_head") { body ->
                // As RealtimeSnapshotSubscriber does: this device's own write is an echo, not a change to pull.
                if (RealtimePhoenix.changeWriterDeviceId(body) != deviceId) onRemoteChange()
            }
            server.joinBroadcast(deviceId, userId) { text -> PeerMessage.decode(text)?.let { inbox.tryEmit(it) } }
            connected.value = true
        }

        override fun send(message: PeerMessage) {
            val u = user ?: return
            server.broadcast(deviceId, u, PeerMessage.encode(message))
        }
    }

    /** Let [millis] of virtual time pass, running the server's cron as it goes. */
    fun pass(millis: Long) {
        var left = millis
        while (left > 0) {
            val step = minOf(left, 60_000L)
            test.advanceTimeBy(step)
            test.runCurrent()
            server.runCronUntil(t0.value + test.testScheduler.currentTime)
            left -= step
        }
    }

    companion object {
        /**
         * A realistic account built offline through the reducer: [topLevel] tasks, each with [children] sub-tasks —
         * the release account's shape (224 tasks, 434 cells, 219 lists, 2026-09-17).
         */
        fun account(topLevel: Int = 20, children: Int = 10): SchedulerState {
            var s = SchedulerState.empty()
            repeat(topLevel) { i ->
                val root = s.lists[s.rootListId]!!
                s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(root.cellIds[i], "Area $i"))
            }
            val parents = s.lists[s.rootListId]!!.cellIds.take(topLevel)
            for ((p, cell) in parents.withIndex()) {
                s = SchedulerReducer.reduce(s, SchedulerIntent.ToggleExpand(cell))
                val taskId = s.cells[cell]!!.taskId!!
                repeat(children) { c ->
                    val list = s.lists[s.tasks[taskId]!!.childListId!!]!!
                    s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(list.cellIds[c], "Area $p task $c"))
                }
            }
            return s.copy(screenBreaks = SchedulerDomain.DEFAULT_SCREEN_BREAKS)
        }
    }
}

/**
 * The heavy user: what one active minute on [device] does. Mixed in the proportions of the release account's own
 * history (renames and new tasks, calendar edits, expand/collapse, far more selection and window changes than any
 * of those), with every title TYPED character by character as the edit window receives it.
 */
class HeavyUser(private val device: QuotaScenario.Device, private val editsPerMinute: Int, private val seed: Int) {
    private var minute = 0

    private fun cellsWithTasks(): List<CellId> = device.state.cells.values.filter { it.taskId != null }.map { it.id }

    fun actOneMinute(nowMillis: Long) {
        val cells = cellsWithTasks()
        if (cells.isEmpty()) return
        repeat(editsPerMinute) { e ->
            val k = (minute * 31 + e * 17 + seed) and 0x7fffffff
            val cell = cells[k % cells.size]
            // Select it, the way a click does.
            device.dispatch(SchedulerIntent.ClickCell(cell, ctrl = false, shift = false, visibleOrder = cells))
            // Type a new title into it, one character at a time, and commit.
            val title = "Renamed ${k % 997} at $minute"
            device.dispatch(SchedulerIntent.BeginEdit(cell))
            for (i in 1..title.length) device.dispatch(SchedulerIntent.UpdateEditText(title.take(i)))
            device.dispatch(SchedulerIntent.ExitEdit(EditExitNavigation.Down))
            // Selection and window changes are several times more frequent than edits.
            repeat(4) { j -> device.dispatch(SchedulerIntent.ClickCell(cells[(k + j * 7) % cells.size], ctrl = false, shift = false, visibleOrder = cells)) }
            device.dispatch(SchedulerIntent.FocusWindow(if ((k and 1) == 0) HistoryWindow.Calendar else HistoryWindow.Tree))
        }
        val any = device.state.cells[cells[(minute * 13 + seed) % cells.size]]!!.taskId!!
        if (minute % 5 == 0) device.dispatch(SchedulerIntent.ToggleExpand(cells[(minute + seed) % cells.size]))
        if (minute % 20 == 0) {
            // A calendar block every 20 minutes: 30 a day, every day. (Every 3 minutes would be 73,000 a year.)
            device.dispatch(
                SchedulerIntent.AddTaskPanel(any, "block", nowMillis + (minute + 60) * 60_000L, nowMillis + (minute + 90) * 60_000L, PanelPins(existence = true)),
            )
        }
        if (minute % 10 == 0) device.dispatch(SchedulerIntent.SetTaskMinimumTime(any, 15 + minute % 30))
        minute++
    }

    @Suppress("unused")
    private fun taskOf(cell: CellId): TaskId? = device.state.cells[cell]?.taskId
}
