package org.example.project

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import org.example.project.scheduler.engine.ArmedAlarm
import org.example.project.scheduler.platform.Diagnostics

/**
 * PRD §18 Alarms (Android): the phone's impl of the engine's `scheduleDeviceAlarm` seam. Arms an **exact**
 * AlarmManager alarm that fires [AlarmClockReceiver] at the alarm's instant so it rings even if the app was
 * killed or the phone is dozing, or cancels the armed one.
 *
 * Only ever ONE alarm is armed at a time — the soonest ring; the receiver arms the next one. A single fixed
 * request code means re-arming replaces the previous alarm and cancelling clears it, so the OS state can
 * never drift out of step with the engine's [ArmedAlarm]. The whole [ArmedAlarm] rides in the intent's extras
 * (so the ring is exactly what was armed, even in a process the broadcast had to start from scratch), with the
 * alarm id also in the intent *data* so [PendingIntent.FLAG_UPDATE_CURRENT] actually rewrites them.
 */
object AlarmClockScheduler {
    private const val REQUEST_CODE = 4211
    const val EXTRA_ALARM_ID = "org.example.project.ALARM_ID"
    const val EXTRA_LABEL = "org.example.project.ALARM_LABEL"
    const val EXTRA_SECONDS = "org.example.project.ALARM_SECONDS"
    const val EXTRA_VIBRATE = "org.example.project.ALARM_VIBRATE"
    // PRD §18 Timers: which list the armed ring came from. The receiver hands it straight back to
    // `onAlarmFire`, which needs it to reset the timer (rather than disarm a one-off alarm) and to title the
    // notification — so the routing survives a process the broadcast had to start from scratch.
    const val EXTRA_TIMER = "org.example.project.ALARM_IS_TIMER"

    private fun pendingIntent(context: Context, armed: ArmedAlarm?): PendingIntent {
        val intent = Intent(context, AlarmClockReceiver::class.java).apply {
            // Extras alone do not make two PendingIntents distinct; the data URI does, which also forces the
            // FLAG_UPDATE_CURRENT rewrite to take when only the extras changed.
            data = android.net.Uri.parse("omniapp://alarm/${armed?.alarmId.orEmpty()}")
            putExtra(EXTRA_ALARM_ID, armed?.alarmId.orEmpty())
            putExtra(EXTRA_LABEL, armed?.label.orEmpty())
            putExtra(EXTRA_SECONDS, armed?.soundSeconds ?: 0)
            putExtra(EXTRA_VIBRATE, armed?.vibrate ?: false)
            putExtra(EXTRA_TIMER, armed?.timer ?: false)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags)
    }

    /** Arm [armed] (at a real wall-clock instant), or cancel the armed alarm when null. */
    fun apply(context: Context, armed: ArmedAlarm?) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        if (armed == null) {
            am.cancel(pendingIntent(context, null))
            return
        }
        val pi = pendingIntent(context, armed)
        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        Diagnostics.log(
            "alarm ${armed.alarmId} OS-armed for ${Diagnostics.formatInstant(armed.atMillis)} (exact=$canExact)",
        )
        if (canExact) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, armed.atMillis, pi)
        } else {
            // No exact-alarm permission: an inexact alarm may ring a little late rather than not at all. The
            // user can grant "Alarms & reminders" to restore exactness.
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, armed.atMillis, pi)
        }
    }
}
