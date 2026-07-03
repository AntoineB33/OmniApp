package org.example.project.scheduler.platform

// Android delivers the pause-end cue through SchedulerHolder's AlarmManager-backed seam (PauseCueScheduler),
// injected directly into the engine it builds — App()'s default branch is never taken on Android. So this
// commonMain seam is inert here (a no-op / false); it exists only to satisfy the expect for the android target.
actual fun scheduleLocalPauseCuePlatform(dueAtMillis: Long?) = Unit

actual val localPauseCueDeliveryPlatform: Boolean = false

// Android routes token registration / remote pushes through PauseCueMessagingService, not this bridge.
actual fun installPauseCuePushBridge(
    registerApnsToken: (token: String) -> Unit,
    onRemotePush: (action: String, dueAtIso: String?) -> Unit,
) = Unit
