package org.example.project.scheduler.platform

// Desktop keeps the in-app presence-gated cue (see SchedulerEngine.launchPoseFinishVoiceCue): no OS-scheduled
// local notification, so this seam is inert and delivery stays false.
actual fun scheduleLocalPauseCuePlatform(dueAtMillis: Long?) = Unit

actual val localPauseCueDeliveryPlatform: Boolean = false

actual fun installPauseCuePushBridge(
    registerApnsToken: (token: String) -> Unit,
    onRemotePush: (action: String, dueAtIso: String?) -> Unit,
) = Unit
