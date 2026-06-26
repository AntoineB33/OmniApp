package org.example.project

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Starts [SchedulerService] after the device finishes booting, so the scheduler resumes firing reminders
 * without the user opening the app — the Android equivalent of the Windows Startup shortcut that
 * `scripts/release-deploy.bat` registers. Starting a foreground service from BOOT_COMPLETED is an allowed
 * exemption to the background-start restrictions.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            SchedulerService.start(context.applicationContext)
        }
    }
}
