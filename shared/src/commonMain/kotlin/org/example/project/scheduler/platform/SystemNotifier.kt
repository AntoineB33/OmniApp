package org.example.project.scheduler.platform

/**
 * PRD §11 Notifications: post a system notification with [title] and [message]. Best-effort — a
 * platform without notification support (or where the user denied it) silently does nothing.
 */
expect fun sendSystemNotification(title: String, message: String)

/**
 * PRD §11 Notifications: **clear the notifications this app has already posted** and that the OS is still
 * showing.
 *
 * The companion of muting (`SchedulerState.notificationsEnabled`): switching notifications off has to answer
 * the pile already sitting in the shade as well as the ones still to come, or "notifications off" would leave
 * the interruption the user pressed the switch about still on screen. Nothing about the app's own record is
 * touched — the History window's Notifications column keeps every entry, muted or cleared.
 *
 * Best-effort and platform-shaped, exactly like [sendSystemNotification]: Android and iOS can withdraw a
 * delivered notification, a desktop tray balloon cannot be recalled once shown (it fades on its own), so
 * that actual is deliberately a no-op.
 */
expect fun cancelSystemNotifications()
