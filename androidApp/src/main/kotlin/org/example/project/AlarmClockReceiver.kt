package org.example.project

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.example.project.scheduler.engine.ArmedAlarm
import org.example.project.scheduler.platform.Diagnostics

/**
 * PRD §18 Alarms: fires at an alarm's instant (see [AlarmClockScheduler]) and hands off to the shared
 * engine's `onAlarmFire`, which rings ([AlarmRingService]), posts the notification, disarms a one-off alarm
 * and arms the next ring. The broadcast may wake the process from scratch, so it goes through
 * [SchedulerHolder.ensure] like every other Android entry point.
 */
class AlarmClockReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // The ring parameters travel in the intent (see AlarmClockScheduler), so the ring is exactly what was
        // armed — no state lookup, nothing a peer's just-synced edit can silence.
        val armed = ArmedAlarm(
            alarmId = intent.getStringExtra(AlarmClockScheduler.EXTRA_ALARM_ID).orEmpty(),
            atMillis = System.currentTimeMillis(),
            label = intent.getStringExtra(AlarmClockScheduler.EXTRA_LABEL).orEmpty(),
            soundSeconds = intent.getIntExtra(AlarmClockScheduler.EXTRA_SECONDS, 0),
            vibrate = intent.getBooleanExtra(AlarmClockScheduler.EXTRA_VIBRATE, false),
        )
        Diagnostics.log("alarm receiver fired for id='${armed.alarmId}'")
        if (armed.alarmId.isBlank() || armed.soundSeconds <= 0) return
        // onAlarmFire is synchronous (it starts the ring service and dispatches), so no goAsync is needed.
        SchedulerHolder.ensure(context.applicationContext).engine.onAlarmFire(armed)
    }
}
