package org.example.project

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import org.example.project.scheduler.platform.Diagnostics

/**
 * PRD §15 / ARCHITECTURE.md §8: the Android impl of the engine's local pause-cue seam
 * ([org.example.project.scheduler.engine.SchedulerEngine] `scheduleLocalPauseCue`). Schedules an **exact**
 * AlarmManager alarm that fires [PauseCueAlarmReceiver] at the pause-end instant so the cue speaks even if the
 * app was killed — or cancels the pending alarm. A single fixed-request-code [PendingIntent] means a
 * reschedule replaces the previous alarm and a cancel clears it (idempotent whether the local schedule or an
 * FCM push drives it).
 */
object PauseCueScheduler {
    private const val REQUEST_CODE = 4210

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, PauseCueAlarmReceiver::class.java)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags)
    }

    /** Schedule the cue at [dueAtMillis], or cancel the pending alarm when null. */
    fun apply(context: Context, dueAtMillis: Long?) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pendingIntent(context)
        if (dueAtMillis == null) {
            Diagnostics.log("pause-cue OS alarm cancelled")
            am.cancel(pi)
            return
        }
        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        Diagnostics.log(
            "pause-cue OS alarm scheduled for ${Diagnostics.formatInstant(dueAtMillis)} (exact=$canExact)",
        )
        if (canExact) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, dueAtMillis, pi)
        } else {
            // No exact-alarm permission granted: fall back to an inexact alarm (may fire a little late) rather
            // than crash. The user can grant "Alarms & reminders" to restore exactness.
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, dueAtMillis, pi)
        }
    }
}
