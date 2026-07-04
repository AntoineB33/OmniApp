package org.example.project.scheduler.platform

import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNNotificationSound
import platform.UserNotifications.UNTimeIntervalNotificationTrigger
import platform.UserNotifications.UNUserNotificationCenter

// Stable id so a reschedule replaces (cancel-and-reschedule) the previously pending pause-end cue.
private const val PAUSE_CUE_ID = "omniapp-pause-cue"

/**
 * PRD §15 / ARCHITECTURE.md §8 iOS pause-end cue: schedule a local notification to fire at [dueAtMillis], or
 * cancel the pending one when null. Because iOS cannot run app code at a local-notification's fire time, the
 * cue is delivered by the system (banner + sound) rather than by re-running [SchedulerEngine.onPauseCueFire]'s
 * presence gate — a documented iOS limitation (the server-side origin check still suppresses redundant pushes).
 * Requires notification authorization (requested from the Swift AppDelegate — see docs/PAUSE_CUE_DELIVERY.md).
 */
actual fun scheduleLocalPauseCuePlatform(dueAtMillis: Long?) {
    val center = UNUserNotificationCenter.currentNotificationCenter()
    // Cancel any previously scheduled cue first (a change always supersedes the prior schedule).
    center.removePendingNotificationRequestsWithIdentifiers(listOf(PAUSE_CUE_ID))
    if (dueAtMillis == null) return
    val nowMillis = (NSDate().timeIntervalSince1970 * 1000.0).toLong()
    val delaySeconds = (dueAtMillis - nowMillis).toDouble() / 1000.0
    if (delaySeconds <= 0.0) return
    val content = UNMutableNotificationContent().apply {
        setTitle("Pause finished")
        setBody("Your pause is over. You can resume your work.")
        setSound(UNNotificationSound.defaultSound)
    }
    val trigger = UNTimeIntervalNotificationTrigger.triggerWithTimeInterval(delaySeconds, repeats = false)
    val request = UNNotificationRequest.requestWithIdentifier(PAUSE_CUE_ID, content, trigger)
    center.addNotificationRequest(request, null)
}

// iOS delivers the cue via this OS-scheduled notification, so the engine skips its in-app timer to avoid a
// double announcement (see SchedulerEngine.localPauseCueDelivery).
actual val localPauseCueDeliveryPlatform: Boolean = true

actual fun installPauseCuePushBridge(
    registerApnsToken: (token: String) -> Unit,
    onRemotePush: (action: String, dueAtIso: String?) -> Unit,
    onForegrounded: () -> Unit,
) {
    IosPushBridge.install(registerApnsToken, onRemotePush, onForegrounded)
}

