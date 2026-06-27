package org.example.project

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.example.project.scheduler.engine.AppSchedulerHost
import org.example.project.scheduler.engine.SchedulerEngine
import org.example.project.scheduler.persistence.AndroidSchedulerStoreHolder
import org.example.project.scheduler.persistence.SyncMetaStore
import org.example.project.scheduler.persistence.createDefaultSchedulerStore
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.sync.RemoteSnapshotClient
import org.example.project.scheduler.sync.SchedulerSyncEngine
import org.example.project.scheduler.ui.TaskSchedulerViewModel
import org.example.project.time.SystemAppClock

/**
 * Process-wide singleton owning the one [TaskSchedulerViewModel] + [SchedulerEngine] the whole app shares.
 * Both the foreground [SchedulerService] (which keeps the engine ticking with no UI) and [MainActivity]
 * (which renders it) call [ensure], so there is exactly one scheduler state and one notification stream —
 * never a UI copy racing a service copy. The engine runs on a long-lived [Dispatchers.Main] scope (the same
 * thread the StateFlow/UI observe), independent of any Activity lifecycle, so it survives the UI closing.
 *
 * Android components (Activity/Service/Receiver) are invoked on the main thread, so [ensure] needs no extra
 * synchronization to stay single-init.
 */
object SchedulerHolder {
    private var host: AppSchedulerHost? = null

    // Outlives every Activity; the foreground service is what keeps the process (and thus this scope) alive.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    fun ensure(context: Context): AppSchedulerHost {
        host?.let { return it }
        AndroidSchedulerStoreHolder.context = context.applicationContext
        // PRD §6: History Units are timestamped from this clock; set it before any reducer write in the service.
        SchedulerReducer.clock = SystemAppClock
        val store = createDefaultSchedulerStore()
        val syncEngine = (store as? SyncMetaStore)?.let { SchedulerSyncEngine(RemoteSnapshotClient(), it) }
        val vm = TaskSchedulerViewModel(store = store, syncEngine = syncEngine)
        val engine = SchedulerEngine(vm = vm, clock = SystemAppClock, scope = scope, presence = vm.presence)
        engine.start()
        return AppSchedulerHost(vm, engine).also { host = it }
    }
}
