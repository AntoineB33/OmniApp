package org.example.project.scheduler.platform

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.example.project.scheduler.persistence.AndroidSchedulerStoreHolder
import java.util.concurrent.atomic.AtomicInteger

/**
 * PRD §11 Notifications (Android): post a heads-up notification on the app's reminder channel. Best-effort
 * and wrapped in `runCatching` per the [sendSystemNotification] contract — it silently does nothing when the
 * app `Context` isn't ready yet or the user denied the POST_NOTIFICATIONS permission (API 33+). Each post
 * uses a fresh id so successive task-switch / side-task cues stack rather than replacing each other.
 */
private const val CHANNEL_ID = "omniapp_reminders"
private val nextNotificationId = AtomicInteger(1)

private fun ensureChannel(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
    if (manager.getNotificationChannel(CHANNEL_ID) != null) return
    manager.createNotificationChannel(
        NotificationChannel(CHANNEL_ID, "Reminders", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Task-to-do-now, side-task and wind-down reminders"
        },
    )
}

actual fun sendSystemNotification(title: String, message: String) {
    runCatching {
        val context = AndroidSchedulerStoreHolder.context ?: return
        ensureChannel(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        // notify() throws nothing if the permission is missing, but the post is dropped; that is fine.
        NotificationManagerCompat.from(context).notify(nextNotificationId.getAndIncrement(), notification)
    }
}
