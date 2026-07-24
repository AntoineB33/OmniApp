package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import org.example.project.scheduler.engine.ArmedAlarm
import org.example.project.scheduler.engine.SchedulerEngine
import org.example.project.scheduler.model.AlarmEntry
import org.example.project.scheduler.platform.DeviceKind
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.ui.TaskSchedulerViewModel
import org.example.project.time.AppClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest

/**
 * PRD §18 Alarms — the engine half: what the phone hands the OS to arm, and what happens when that alarm
 * fires (it rings exactly what was armed, a one-off disarms itself, and the next ring is armed).
 *
 * The arming loop's flow plumbing is not exercised here; [SchedulerEngine.onAlarmFire] re-arms through the
 * same `armNextAlarm` the loop uses, so driving it covers the arming decision too.
 */
class AlarmEngineTest {
    private val tz = TimeZone.currentSystemDefault()
    private val day = 24 * 60 * 60 * 1000L

    private class FixedClock(var now: Long) : AppClock {
        override fun nowMillis(): Long = now
    }

    /** Local wall-clock `hh:mm` on 2026-07-24, in the engine's zone. */
    private fun at(hh: Int, mm: Int): Long =
        LocalDateTime(2026, 7, 24, hh, mm).toInstant(tz).toEpochMilliseconds()

    private class Harness(nowMillis: Long, deviceKind: DeviceKind = DeviceKind.Phone) {
        val armed = mutableListOf<ArmedAlarm?>()
        val rung = mutableListOf<ArmedAlarm>()
        val clock = FixedClock(nowMillis)
        val vm = TaskSchedulerViewModel(store = null, saveDispatcher = Dispatchers.Default)
        val engine = SchedulerEngine(
            vm = vm,
            clock = clock,
            scope = CoroutineScope(Dispatchers.Unconfined),
            deviceKind = deviceKind,
            screenActive = { true },
            scheduleDeviceAlarm = { armed.add(it) },
            ringAlarm = { rung.add(it) },
        )
    }

    private fun alarm(
        id: String,
        minutes: Int,
        label: String = "",
        soundSeconds: Int = 30,
        vibrate: Boolean = true,
        repeatDaily: Boolean = true,
    ) = AlarmEntry(
        id = id,
        label = label,
        timeOfDayMinutes = minutes,
        soundSeconds = soundSeconds,
        vibrate = vibrate,
        repeatDaily = repeatDaily,
    )

    @Test
    fun a_ringing_daily_alarm_rings_what_was_armed_and_arms_tomorrow() = runTest {
        val h = Harness(nowMillis = at(7, 0))
        h.vm.dispatch(
            SchedulerIntent.SetAlarms(
                listOf(alarm("alarm-0", 7 * 60, label = "Wake up", soundSeconds = 45, vibrate = true)),
            ),
        )
        val armedNow = ArmedAlarm("alarm-0", at(7, 0), "Wake up", 45, vibrate = true)

        h.engine.onAlarmFire(armedNow)

        // Rings exactly what was armed — length + vibration included.
        assertEquals(listOf(armedNow), h.rung)
        // A daily alarm stays armed and comes round tomorrow at the same wall-clock time.
        assertTrue(h.vm.state.value.alarms.single().enabled)
        val next = h.armed.single()
        assertNotNull(next)
        assertEquals("alarm-0", next.alarmId)
        assertEquals(at(7, 0) + day, next.atMillis)
        assertEquals(45, next.soundSeconds)
        assertTrue(next.vibrate)
    }

    @Test
    fun a_one_off_alarm_disarms_itself_and_leaves_nothing_armed() = runTest {
        val h = Harness(nowMillis = at(7, 0))
        h.vm.dispatch(SchedulerIntent.SetAlarms(listOf(alarm("alarm-0", 7 * 60, repeatDaily = false))))

        h.engine.onAlarmFire(ArmedAlarm("alarm-0", at(7, 0), "", 30, vibrate = true))

        assertEquals(1, h.rung.size, "the one-off still rings")
        assertFalse(h.vm.state.value.alarms.single().enabled, "a one-off disarms itself once it has rung")
        // Nothing is enabled any more, so no further ring is armed (the fired OS alarm consumed itself, so
        // there is nothing to cancel either).
        assertTrue(h.armed.none { it != null }, "no ring is armed once the only alarm has disarmed itself")
    }

    @Test
    fun a_one_off_already_disarmed_by_a_peer_still_rings_on_this_phone() = runTest {
        // PRD §18: the alarm rings on EVERY phone. If the peer that rang a moment earlier synced its "this
        // one-off has rung" disarm to us first, this phone must still ring — the arming decision was already
        // made, so the ring carries its own parameters and does not re-read the list.
        val h = Harness(nowMillis = at(7, 0))
        h.vm.dispatch(
            SchedulerIntent.SetAlarms(listOf(alarm("alarm-0", 7 * 60, repeatDaily = false))),
        )
        h.vm.dispatch(SchedulerIntent.SetAlarmEnabled("alarm-0", false))

        h.engine.onAlarmFire(ArmedAlarm("alarm-0", at(7, 0), "", 30, vibrate = true))

        assertEquals(1, h.rung.size)
    }

    @Test
    fun a_deleted_alarm_still_rings_but_arms_the_next_remaining_one() = runTest {
        val h = Harness(nowMillis = at(7, 0))
        h.vm.dispatch(SchedulerIntent.SetAlarms(listOf(alarm("alarm-1", 9 * 60))))

        h.engine.onAlarmFire(ArmedAlarm("alarm-0", at(7, 0), "Deleted", 30, vibrate = false))

        assertEquals(1, h.rung.size)
        val next = h.armed.single()
        assertNotNull(next)
        assertEquals("alarm-1", next.alarmId, "arming falls through to the alarm that is still there")
        assertEquals(at(9, 0), next.atMillis)
    }

    @Test
    fun the_soonest_of_several_alarms_is_the_one_armed() = runTest {
        val h = Harness(nowMillis = at(7, 0))
        h.vm.dispatch(
            SchedulerIntent.SetAlarms(
                listOf(alarm("alarm-0", 22 * 60), alarm("alarm-1", 8 * 60), alarm("alarm-2", 12 * 60)),
            ),
        )

        h.engine.onAlarmFire(ArmedAlarm("alarm-9", at(7, 0), "", 30, vibrate = false))

        val next = h.armed.single()
        assertNotNull(next)
        assertEquals("alarm-1", next.alarmId)
        assertEquals(at(8, 0), next.atMillis)
    }
}
