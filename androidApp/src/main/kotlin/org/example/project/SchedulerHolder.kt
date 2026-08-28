package org.example.project

import android.content.Context
import android.content.pm.ApplicationInfo
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.example.project.scheduler.engine.AppSchedulerHost
import org.example.project.scheduler.engine.SchedulerEngine
import org.example.project.scheduler.persistence.AndroidSchedulerStoreHolder
import org.example.project.scheduler.platform.AndroidForegroundTracker
import org.example.project.scheduler.platform.AndroidUnlockTracker
import org.example.project.scheduler.platform.Diagnostics
import org.example.project.scheduler.persistence.ActiveSessionStore
import org.example.project.scheduler.persistence.DeviceSleepGapStore
import org.example.project.scheduler.persistence.SleepScanCheckpointStore
import org.example.project.scheduler.persistence.SyncMetaStore
import org.example.project.scheduler.persistence.createDefaultSchedulerStore
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.sync.RemoteSnapshotClient
import org.example.project.scheduler.sync.SchedulerSyncEngine
import org.example.project.scheduler.ui.TaskSchedulerViewModel
import org.example.project.time.AppClock
import org.example.project.time.SimAppClock
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
        // Debug fast-break knobs, re-applied before anything seeds the screen breaks below. MainActivity gets
        // them from the deploy script's launch extras, but a reboot comes back through BootReceiver ->
        // SchedulerService with no Activity at all, so this is the only place every entry point shares.
        AndroidDebugFlagStore.restore(context)
        // PRD §15: the phone's activity signal is "device unlocked" (see DeviceInfo.android.kt) — the device
        // writes its device_heartbeat while unlocked and closes it at lock. The Activity-lifecycle counter
        // stays installed for the resume-time poke (MainActivity.onResume → engine.onAppForegrounded),
        // registered before any Activity resumes (MainActivity.onCreate calls ensure() before setContent, so
        // the first onResume is always observed).
        AndroidForegroundTracker.install(context.applicationContext)
        AndroidUnlockTracker.install(context.applicationContext)
        // PRD §6: History Units are timestamped from this clock; set it before any reducer write in the service.
        SchedulerReducer.clock = SystemAppClock
        val store = createDefaultSchedulerStore()
        val syncEngine = (store as? SyncMetaStore)?.let {
            SchedulerSyncEngine(RemoteSnapshotClient(), it, activeSessionStore = store as? ActiveSessionStore)
        }
        val vm = TaskSchedulerViewModel(store = store, syncEngine = syncEngine)
        val appContext = context.applicationContext
        // Debug time-link (docs/PAUSE_CUE_DELIVERY.md "Testing C"): on a debuggable build, drive the engine's
        // `now` from a SimAppClock that TimeLinkClient re-anchors from a plugged-in desktop's accelerated clock.
        // Until a desktop is attached the SimAppClock runs at 1× ≡ real wall time, so behaviour is unchanged.
        // Release builds (and non-debuggable) keep SystemAppClock. History-unit timestamps stay on real time
        // (SchedulerReducer.clock above) — only the schedule/now that the pause-cue path depends on is driven.
        // The client starts AFTER the engine below, because each frame also carries the desktop's "simulate
        // pause + leap" forced-inactivity flag, which it feeds into the engine.
        val debuggable = (appContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        val clock: AppClock = if (debuggable) SimAppClock() else SystemAppClock
        val engine =
            SchedulerEngine(
                vm = vm,
                clock = clock,
                scope = scope,
                sleepGapStore = store as? DeviceSleepGapStore,
                sleepScanCheckpoint = store as? SleepScanCheckpointStore,
                activeSessionStore = store as? ActiveSessionStore,
                pauseCue = vm.pauseCue,
                // PRD §15: deliver the pause-end cue as an OS-scheduled alarm (fires even if the app was
                // killed). This replaces the in-app cue on Android, so pass localPauseCueDelivery = true to
                // avoid a double-speak.
                scheduleLocalPauseCue = { dueAtMillis -> PauseCueScheduler.apply(appContext, dueAtMillis) },
                localPauseCueDelivery = true,
                // PRD §18 Alarms: the phone arms its own exact OS alarm for the next ring in the synced alarm
                // list (so it rings offline, dozing, or with the app killed) and rings it in a foreground
                // service that plays the alarm ringtone for the configured length + vibrates.
                scheduleDeviceAlarm = { armed -> AlarmClockScheduler.apply(appContext, armed) },
                ringAlarm = { armed ->
                    AlarmRingService.ring(
                        appContext,
                        armed.label,
                        armed.soundSeconds,
                        armed.vibrate,
                        title = if (armed.timer) "Timer" else "Alarm",
                    )
                },
            )
        engine.start()
        // PRD §15: an unlock/lock flip re-samples the activity beat at once, so the presence socket
        // (re)connects the moment the phone is unlocked and drops at lock — not at the next minute beat.
        AndroidUnlockTracker.onChanged = { engine.onPlatformActivityChanged() }
        if (clock is SimAppClock) {
            TimeLinkClient.start(
                scope,
                clock,
                onForcedInactive = { inactive -> engine.setDebugForcedInactive(inactive) },
                // Leap end: re-derive this phone's own LOCAL Inactivity bands (activity is no longer synced).
                onLeapEnd = { engine.refreshDerivedPausesAndWait() },
            )
        }
        registerFcmToken(vm)
        return AppSchedulerHost(vm, engine).also { host = it }
    }

    /**
     * Registers this device's FCM token with the sync backend so the pause-cue Edge Function can reach it.
     * A no-op (swallowed) when sync is off or Firebase isn't configured (no google-services.json ⇒
     * [FirebaseMessaging] has no default app), so a build without a Firebase project still runs.
     */
    private fun registerFcmToken(vm: TaskSchedulerViewModel) {
        val pauseCue = vm.pauseCue ?: run {
            Diagnostics.log("FCM token registration skipped: pause-cue gateway unavailable (signed out / sync off)")
            return
        }
        runCatching {
            FirebaseMessaging.getInstance().token
                .addOnSuccessListener { token ->
                    Diagnostics.log("FCM token acquired (${token.take(12)}…); registering with backend")
                    scope.launch { runCatching { pauseCue.registerPushToken("phone", "fcm", token) } }
                }
                .addOnFailureListener { Diagnostics.log("FCM token fetch FAILED: ${it.message}") }
        }.onFailure { Diagnostics.log("FCM token registration skipped (Firebase unavailable): ${it.message}") }
    }
}
