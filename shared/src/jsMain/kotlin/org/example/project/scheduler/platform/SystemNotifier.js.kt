package org.example.project.scheduler.platform

actual fun sendSystemNotification(title: String, message: String, target: NotificationTarget) = Unit

actual fun cancelSystemNotifications() = Unit
