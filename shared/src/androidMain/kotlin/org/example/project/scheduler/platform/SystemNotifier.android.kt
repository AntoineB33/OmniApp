package org.example.project.scheduler.platform

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.example.project.scheduler.persistence.AndroidSchedulerStoreHolder
import java.util.concurrent.atomic.AtomicInteger

/**
 * PRD §11 Notifications (Android): post a heads-up notification on the app's reminder channel. Best-effort
 * and wrapped in `runCatching` per the [sendSystemNotification] contract — it silently does nothing when the
 * app `Context` isn't ready yet or the user denied the POST_NOTIFICATIONS permission (API 33+). Each post
 * uses a fresh id so successive task-switch / screen-break cues stack rather than replacing each other.
 */
private const val CHANNEL_ID = "omniapp_reminders"
private val nextNotificationId = AtomicInteger(1)

private fun ensureChannel(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
    if (manager.getNotificationChannel(CHANNEL_ID) != null) return
    manager.createNotificationChannel(
        NotificationChannel(CHANNEL_ID, "Reminders", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Task-to-do-now, screen-break and wind-down reminders"
        },
    )
}

/** The launch-Intent extra a notification's [NotificationTarget] is carried under — `MainActivity` reads it. */
const val NOTIFICATION_TARGET_EXTRA: String = "omniapp_notification_target"

actual fun sendSystemNotification(title: String, message: String, target: NotificationTarget) {
    runCatching {
        val context = AndroidSchedulerStoreHolder.context ?: return
        ensureChannel(context)
        val id = nextNotificationId.getAndIncrement()
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .apply { openAppIntent(context, id, target)?.let(::setContentIntent) }
            .build()
        // notify() throws nothing if the permission is missing, but the post is dropped; that is fine.
        NotificationManagerCompat.from(context).notify(id, notification)
    }
}

/**
 * PRD §11 (user rule 2026-10-03): a tap on the notification brings the app's activity up — the running one, or a fresh
 * one — carrying [target] ([NOTIFICATION_TARGET_EXTRA]) for it to hand to [NotificationClicks]. The package's own launch
 * Intent, since `shared` does not know the activity class; one request code per notification, so each keeps its extra.
 */
private fun openAppIntent(context: Context, id: Int, target: NotificationTarget): PendingIntent? {
    val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    launch.putExtra(NOTIFICATION_TARGET_EXTRA, target.name)
    return PendingIntent.getActivity(context, id, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
}

/** The target a launch [intent] carries from a notification tap, or null for any other launch. */
fun notificationTargetOf(intent: Intent?): NotificationTarget? =
    intent?.getStringExtra(NOTIFICATION_TARGET_EXTRA)?.let { name -> NotificationTarget.entries.firstOrNull { it.name == name } }

/**
 * PRD §11 (Android): withdraw every notification this app has posted — they persist in the shade until
 * dismissed, so muting has to clear them as well as stop posting new ones. `cancelAll` needs no permission
 * (unlike `notify`), and cancelling when nothing is showing is a no-op.
 */
actual fun cancelSystemNotifications() {
    runCatching {
        val context = AndroidSchedulerStoreHolder.context ?: return
        NotificationManagerCompat.from(context).cancelAll()
    }
}
