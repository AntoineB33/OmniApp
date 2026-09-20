package org.example.project

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.example.project.scheduler.engine.ArmedAlarm
import org.example.project.scheduler.engine.RingKind
import org.example.project.scheduler.model.AlertSettings
import org.example.project.scheduler.platform.AlertSound
import org.example.project.scheduler.platform.Diagnostics

/**
 * PRD §18 Alarms/Timers: fires at an armed ring's instant (see [AlarmClockScheduler]) and hands off to the
 * shared engine's `onAlarmFire`, which rings ([AlarmRingService]), posts the notification, disarms a one-off
 * alarm / resets a timer, and arms the next ring. The broadcast may wake the process from scratch, so it goes through
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
            // PRD §11: the row's four channels, as armed. A blank/unknown sound name is the sound channel
            // switched off, not a missing extra to guess a default for.
            alert = intent.getStringExtra(AlarmClockScheduler.EXTRA_SOUND)
                .let { name -> AlertSound.entries.firstOrNull { it.name == name } }
                .let { tone ->
                    AlertSettings(
                        sound = tone != null,
                        tone = tone ?: AlertSound.DEFAULT,
                        voice = intent.getBooleanExtra(AlarmClockScheduler.EXTRA_VOICE, false),
                        notification = intent.getBooleanExtra(AlarmClockScheduler.EXTRA_NOTIFICATION, false),
                        vibrate = intent.getBooleanExtra(AlarmClockScheduler.EXTRA_VIBRATE, false),
                    )
                },
            // PRD §18: which list it came from. Reminders never reach here — they are not OS-armed
            // (`alarms-and-timers.md`) — so the extra stays the two-valued flag it always was.
            kind = if (intent.getBooleanExtra(AlarmClockScheduler.EXTRA_TIMER, false)) RingKind.Timer
            else RingKind.Alarm,
        )
        Diagnostics.log("alarm receiver fired for id='${armed.alarmId}'")
        // Nothing to do for a ring with no id, no length, or every channel switched off — the last one is a
        // row the user silenced, which the engine no longer arms either ([AlarmEntry.schedulable]).
        if (armed.alarmId.isBlank() || armed.soundSeconds <= 0 || !armed.alert.announces) return
        // onAlarmFire is synchronous (it starts the ring service and dispatches), so no goAsync is needed.
        SchedulerHolder.ensure(context.applicationContext).engine.onAlarmFire(armed)
    }
}
