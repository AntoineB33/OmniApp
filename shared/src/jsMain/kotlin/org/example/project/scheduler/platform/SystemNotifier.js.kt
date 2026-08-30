package org.example.project.scheduler.platform

actual fun sendSystemNotification(title: String, message: String) = Unit

actual fun cancelSystemNotifications() = Unit
