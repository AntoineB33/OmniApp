package org.example.project

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * PRD §15 / ARCHITECTURE.md §8: fires at the scheduled pause-end instant (see [PauseCueScheduler]). Hands off
 * to the shared engine's [org.example.project.scheduler.engine.SchedulerEngine.onPauseCueFire], which runs the
 * presence/screen-off eligibility gate and, if it passes, speaks the "pause is over" cue. Uses [goAsync] so the
 * one-shot presence read can complete before the receiver is torn down.
 */
class PauseCueAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val host = SchedulerHolder.ensure(context.applicationContext)
        val pending = goAsync()
        CoroutineScope(Dispatchers.Main).launch {
            try {
                host.engine.onPauseCueFire()
            } finally {
                pending.finish()
            }
        }
    }
}
