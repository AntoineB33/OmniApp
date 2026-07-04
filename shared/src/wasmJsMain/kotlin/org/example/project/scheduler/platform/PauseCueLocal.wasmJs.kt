package org.example.project.scheduler.platform

// Web has no OS-scheduled local notification path wired: inert, keeps the in-app cue path.
actual fun scheduleLocalPauseCuePlatform(dueAtMillis: Long?) = Unit

actual val localPauseCueDeliveryPlatform: Boolean = false

actual fun installPauseCuePushBridge(
    registerApnsToken: (token: String) -> Unit,
    onRemotePush: (action: String, dueAtIso: String?) -> Unit,
    onForegrounded: () -> Unit,
) = Unit
