package org.example.project

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * Foreground service that keeps the scheduler [SchedulerHolder] ticking even when no Activity is alive —
 * the Android analog of the always-open desktop window. It owns no logic itself: it just keeps the process
 * running (foreground) with the singleton engine started, so PRD §11/§13/§15 reminders and voice cues fire
 * whether or not the UI is open. Started by [MainActivity] on launch and by [BootReceiver] after a reboot.
 */
class SchedulerService : Service() {

    override fun onCreate() {
        super.onCreate()
        SchedulerHolder.ensure(applicationContext)
        val notification = buildOngoingNotification()
        // API 29+ requires the foreground-service type at start time (must match the manifest declaration).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    // Restart if the system kills us for memory; re-ensure on every start command (cheap, idempotent).
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        SchedulerHolder.ensure(applicationContext)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildOngoingNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Scheduler running", NotificationManager.IMPORTANCE_LOW).apply {
                        description = "Keeps reminders running in the background"
                    },
                )
            }
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_my_calendar)
            .setContentTitle("OmniApp")
            .setContentText("Scheduler is running")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "omniapp_service"
        private const val NOTIFICATION_ID = 1001

        /** Start (or boot-start) the service in the foreground from anywhere. */
        fun start(context: Context) {
            val intent = Intent(context, SchedulerService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
