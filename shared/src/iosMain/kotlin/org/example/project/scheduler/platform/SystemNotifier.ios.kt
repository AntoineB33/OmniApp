package org.example.project.scheduler.platform

import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNNotificationSound
import platform.UserNotifications.UNUserNotificationCenter

/**
 * PRD §11 iOS notification. Delivered immediately (nil trigger). Requires the user to have granted
 * notification authorization (requested from the Swift AppDelegate — see docs/PAUSE_CUE_DELIVERY.md); when
 * not granted the system silently drops it, matching the best-effort contract.
 */
actual fun sendSystemNotification(title: String, message: String) {
    val content = UNMutableNotificationContent().apply {
        setTitle(title)
        setBody(message)
        setSound(UNNotificationSound.defaultSound)
    }
    // A unique-enough id per post so repeated notifications don't replace each other.
    val id = "omniapp-notif-" + NSDate().timeIntervalSince1970.toString()
    val request = UNNotificationRequest.requestWithIdentifier(id, content, null)
    UNUserNotificationCenter.currentNotificationCenter().addNotificationRequest(request, null)
}

/**
 * PRD §11 (iOS): drop what has been delivered to Notification Centre *and* anything still pending, so muting
 * clears the list rather than only stopping the next post. Not the pause-cue alarm, which is scheduled
 * through its own local-cue seam ([scheduleLocalPauseCuePlatform]) and is a spoken cue, not a notification.
 */
actual fun cancelSystemNotifications() {
    val center = UNUserNotificationCenter.currentNotificationCenter()
    center.removeAllDeliveredNotifications()
    center.removeAllPendingNotificationRequests()
}
