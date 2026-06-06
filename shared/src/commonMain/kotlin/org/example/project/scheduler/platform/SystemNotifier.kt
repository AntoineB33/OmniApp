package org.example.project.scheduler.platform

/**
 * PRD §11 Notifications: post a system notification with [title] and [message]. Best-effort — a
 * platform without notification support (or where the user denied it) silently does nothing.
 */
expect fun sendSystemNotification(title: String, message: String)
