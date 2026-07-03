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
